package com.muastudio.fotufilm.flutter

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * The Fujifilm RAW conversion road: the camera does the work, this talks to it.
 *
 * X RAW STUDIO is not a RAW converter -- the camera's own processor develops the file, and the
 * desktop app only carries the request there and the JPEG back. The same is true here, so nothing
 * in this class decodes a RAF file.
 *
 * The protocol is PTP (ISO 15740) over USB bulk transfers, plus Fujifilm's vendor operations. The
 * flow and the numbers below follow the open work that reverse-engineered it -- eggricesoy/filmkit
 * (MIT, verified on an X100VI) and petabyt/libfuji (MIT, documents the vendor opcodes)
 * -- and PhotoSync-style Android USB host handling:
 *
 *   1. OpenSession                                    0x1002
 *   2. SendObjectInfo 0x900C + SendObject2 0x900D     upload the RAF
 *   3. GetDevicePropValue 0x1015, property 0xD185     read the camera's 625-byte profile
 *   4. SetDevicePropValue 0x1016, property 0xD185     write it back patched
 *   5. SetDevicePropValue 0x1016, property 0xD183 = 0 trigger the conversion
 *   6. GetObjectHandles 0x1007                        poll until the result exists
 *   7. GetObject 0x1009                               download the JPEG
 *   8. DeleteObject 0x100B                            drop the temporary object
 *   9. CloseSession                                   0x1003
 *
 * WHAT IS NOT KNOWN YET, and must not be guessed: the field layout of the 625-byte profile was
 * confirmed on an X100VI. An X-M5 may differ. Nothing here assumes a field index -- the profile is
 * read from the camera and only the byte offsets the Dart side names are overwritten, so a wrong
 * offset damages nothing but that one byte the caller asked for.
 */
class FujiUsb(private val context: Context, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "fujifilm/usb")
    private val worker = Executors.newSingleThreadExecutor()
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var transaction = 0u

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "status" -> result.success(status())
                "convert" -> {
                    val raf = call.argument<String>("raf")
                    val out = call.argument<String>("out")
                    val patches = call.argument<Map<String, Int>>("patches") ?: emptyMap()
                    if (raf == null || out == null) {
                        result.error("bad-arguments", "convert needs a RAF path and an output path", null)
                    } else {
                        worker.execute {
                            val answer = runCatching { convert(File(raf), File(out), patches) }
                            val reply = answer.fold(
                                onSuccess = { "ok $it" },
                                onFailure = { "failed: ${it.message ?: it.toString()}" })
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { result.success(reply) }
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    /** USB ids, whether a body is attached, and whether we may open it. */
    private fun status(): Map<String, Any?> {
        val device = manager.deviceList.values.firstOrNull { isFuji(it) }
        return mapOf(
            "attached" to (device != null),
            "vendorId" to device?.vendorId,
            "productId" to device?.productId,
            "model" to device?.productName,
            "permitted" to (device != null && manager.hasPermission(device)),
            "open" to (connection != null),
        )
    }

    private fun isFuji(device: UsbDevice) = device.vendorId == FUJI_VENDOR_ID

    /**
     * The whole conversion, in the order the camera expects. Throws with the step that failed:
     * without a device to test against, the step name is the only useful diagnostic.
     */
    private fun convert(raf: File, out: File, patches: Map<String, Int>): String {
        connect()
        try {
            openSession()
            uploadObject(raf)
            val profile = getDeviceProp(PROP_PROFILE) ?: throw IllegalStateException("no profile from 0xD185")
            if (profile.size != PROFILE_BYTES) {
                // Not fatal: an X-M5 may answer with a different length, and the caller is told so.
                return "profile is ${profile.size} bytes, not $PROFILE_BYTES"
            }
            for ((index, value) in patches) {
                val i = index.toIntOrNull() ?: throw IllegalArgumentException("bad profile index $index")
                if (!patchByte(profile, i, value)) throw IllegalArgumentException("index $i outside the profile")
            }
            setDeviceProp(PROP_PROFILE, profile)
            setDeviceProp(PROP_TRIGGER, u32Data(0))
            val jpeg = awaitResult() ?: throw IllegalStateException("the camera produced no object")
            out.parentFile?.mkdirs()
            out.writeBytes(jpeg)
            deleteObject(resultHandle)
            return "${jpeg.size} bytes"
        } finally {
            runCatching { closeSession() }
            runCatching { disconnect() }
        }
    }

    // ── PTP transport ─────────────────────────────────────────────────────────────────────────

    private fun connect() {
        if (connection != null) return
        val device = manager.deviceList.values.firstOrNull { isFuji(it) }
            ?: throw IllegalStateException("no Fujifilm camera on USB")
        if (!manager.hasPermission(device)) {
            manager.requestPermission(device, PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE))
            throw IllegalStateException("permission requested; call again once it is granted")
        }
        val iface: UsbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
            ?: device.getInterface(0)
        val conn = manager.openDevice(device) ?: throw IllegalStateException("cannot open the camera")
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw IllegalStateException("cannot claim the PTP interface")
        }
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            // Bulk only: the RAF goes up and the JPEG comes down as bulk transfers.
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep else endpointOut = ep
        }
        if (endpointIn == null || endpointOut == null) {
            conn.close()
            throw IllegalStateException("the camera exposes no PTP bulk endpoints")
        }
        connection = conn
        transaction = 0u
    }

    private fun disconnect() {
        connection?.close()
        connection = null
        endpointIn = null
        endpointOut = null
    }

    private fun nextTransaction(): UInt = ++transaction

    private fun send(bytes: ByteArray) {
        val out = endpointOut ?: throw IllegalStateException("no bulk out endpoint")
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + BULK_CHUNK, bytes.size))
            val wrote = connection?.bulkTransfer(out, chunk, chunk.size, TIMEOUT) ?: -1
            if (wrote <= 0) throw IllegalStateException("bulk write failed at $offset")
            offset += wrote
        }
    }

    private fun receive(): ByteArray {
        val input = endpointIn ?: throw IllegalStateException("no bulk in endpoint")
        val buffer = ByteArray(BULK_CHUNK)
        val read = connection?.bulkTransfer(input, buffer, buffer.size, TIMEOUT) ?: -1
        if (read <= 0) throw IllegalStateException("bulk read returned $read")
        return buffer.copyOfRange(0, read)
    }

    private fun container(type: Int, code: Int, transactionId: UInt, params: List<UInt>,
                          data: ByteArray? = null): ByteArray {
        val header = 12 + params.size * 4
        val length = header + (data?.size ?: 0)
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length).putShort(type.toShort()).putShort(code.toShort()).putInt(transactionId.toInt())
        params.forEach { buffer.putInt(it.toInt()) }
        if (data != null) buffer.put(data)
        return buffer.array()
    }

    /** Sends a command container and returns the data phase (if any) and the response code. */
    private fun transact(code: Int, params: List<UInt> = emptyList(), data: ByteArray? = null): Pair<ByteArray?, Int> {
        val id = nextTransaction()
        send(container(TYPE_COMMAND, code, id, params, data))
        var stageData: ByteArray? = null
        while (true) {
            val bytes = receive()
            if (bytes.size < 12) continue
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val length = buffer.int
            val type = buffer.short.toInt()
            val opcode = buffer.short.toInt()
            when (type) {
                TYPE_DATA -> stageData = if (length <= bytes.size) bytes.copyOfRange(12, length)
                else bytes.copyOfRange(12, bytes.size)
                TYPE_RESPONSE -> return stageData to opcode
            }
        }
    }

    // ── The operations ────────────────────────────────────────────────────────────────────────

    private fun openSession() {
        val (_, response) = transact(OP_OPEN_SESSION, listOf(u32(1)))
        if (response != 0x2001) throw IllegalStateException("OpenSession answered 0x${response.toString(16)}")
    }

    private fun closeSession() {
        transact(OP_CLOSE_SESSION)
    }

    /**
     * Uploads the RAF with Fujifilm's own object operations: SendObjectInfo creates the entry,
     * SendObject2 streams the bytes. The stock PTP SendObject cannot write to the camera's card.
     */
    private fun uploadObject(file: File) {
        val name = file.name
        val info = objectInfo(file.length(), name)
        val (_, response) = transact(OP_FUJI_SEND_OBJECT_INFO, listOf(u32(0), u32(0)), info)
        if (response != 0x2001) throw IllegalStateException("SendObjectInfo answered 0x${response.toString(16)}")
        val (_, sent) = transact(OP_FUJI_SEND_OBJECT2, listOf(u32(0)), file.readBytes())
        if (sent != 0x2001) throw IllegalStateException("SendObject2 answered 0x${sent.toString(16)}")
    }

    /** A minimal PTP ObjectInfo string: fixed fields zero, filename as a PTP string, padded. */
    private fun objectInfo(size: Long, name: String): ByteArray {
        val encoded = name.toByteArray(Charsets.UTF_16LE)
        val buffer = ByteBuffer.allocate(53 + 1 + encoded.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0)                       // StorageID
        buffer.putShort(0x3000)                // ObjectFormat: undefined
        buffer.putShort(0)                     // ProtectionStatus
        buffer.putInt(size.toInt())            // ObjectCompressedSize
        buffer.putShort(0)                     // ThumbFormat
        buffer.putInt(0)                       // ThumbCompressedSize
        buffer.putInt(0)                       // ThumbPixWidth
        buffer.putInt(0)                       // ThumbPixHeight
        buffer.putInt(0)                       // ImagePixWidth
        buffer.putInt(0)                       // ImagePixHeight
        buffer.putInt(0)                       // ImageBitDepth
        buffer.putInt(0)                       // ParentObject
        buffer.putShort(0)                     // AssociationType
        buffer.putInt(0)                       // AssociationDesc
        buffer.putInt(0)                       // SequenceNumber
        buffer.put((encoded.size + 1).toByte()) // PTP string: characters plus terminator
        buffer.put(encoded)
        buffer.put(0)
        val used = buffer.position()
        val out = ByteArray((used + 3) / 4 * 4) // ObjectInfo is padded to a word
        System.arraycopy(buffer.array(), 0, out, 0, used)
        return out
    }

    private fun getDeviceProp(property: Int): ByteArray? {
        val (data, response) = transact(OP_GET_DEVICE_PROP, listOf(u32(property)))
        return if (response == 0x2001) data else null
    }

    private fun setDeviceProp(property: Int, value: ByteArray) {
        val (_, response) = transact(OP_SET_DEVICE_PROP, listOf(u32(property)), value)
        if (response != 0x2001) throw IllegalStateException("SetDevicePropValue(0x${property.toString(16)}) answered 0x${response.toString(16)}")
    }

    /** Polls the object handles until one appears that was not there before the trigger. */
    private fun awaitResult(): ByteArray? {
        repeat(RESULT_POLLS) {
            val (data, response) = transact(OP_GET_OBJECT_HANDLES, listOf(0xFFFFFFFFu))
            if (response == 0x2001 && data != null && data.size >= 4) {
                val count = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
                if (count > 0) {
                    val handle = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
                    resultHandle = handle
                    val (jpeg, got) = transact(OP_GET_OBJECT, listOf(handle.toUInt()))
                    if (got == 0x2001) return jpeg
                }
            }
            Thread.sleep(RESULT_POLL_MILLIS.toLong())
        }
        return null
    }

    private var resultHandle: Int = 0

    private fun deleteObject(handle: Int) {
        if (handle != 0) transact(OP_DELETE_OBJECT, listOf(handle.toUInt()))
    }

    /** Overwrites one byte, refusing an index outside the profile rather than growing it. */
    private fun patchByte(profile: ByteArray, index: Int, value: Int): Boolean {
        if (index < 0 || index >= profile.size) return false
        profile[index] = value.toByte()
        return true
    }

    private companion object {
        const val FUJI_VENDOR_ID = 0x04CB        // Fujifilm
        const val ACTION_USB_PERMISSION = "com.muastudio.fotufilm.flutter.USB_PERMISSION"

        const val TYPE_COMMAND = 1
        const val TYPE_DATA = 2
        const val TYPE_RESPONSE = 3

        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT = 0x1009
        const val OP_DELETE_OBJECT = 0x100B
        const val OP_OPEN_SESSION = 0x1002
        const val OP_CLOSE_SESSION = 0x1003
        const val OP_GET_DEVICE_PROP = 0x1015
        const val OP_SET_DEVICE_PROP = 0x1016
        const val OP_FUJI_SEND_OBJECT_INFO = 0x900C   // create the entry on the card
        const val OP_FUJI_SEND_OBJECT2 = 0x900D       // stream the bytes

        const val PROP_PROFILE = 0xD185   // the camera's 625-byte conversion profile
        const val PROP_TRIGGER = 0xD183   // write 0 to ask for the conversion
        const val PROFILE_BYTES = 625

        const val BULK_CHUNK = 1 shl 20   // 1 MiB
        const val TIMEOUT = 5000
        const val RESULT_POLLS = 60
        const val RESULT_POLL_MILLIS = 500
    }
}

/** Little-endian u32 in a UInt, for PTP parameters. */
private fun u32(value: Int): UInt = value.toUInt()

/** The same value as the four little-endian bytes a PTP data phase carries (a DevicePropValue). */
private fun u32Data(value: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

/** Reads a PTP string at [offset]: one length byte, then UTF-16LE. */
internal fun ptpString(bytes: ByteArray, offset: Int): String {
    if (offset >= bytes.size) return ""
    val length = bytes[offset].toInt() and 0xFF
    val end = minOf(offset + 1 + length * 2, bytes.size)
    return String(bytes, offset + 1, end - (offset + 1), Charsets.UTF_16LE)
}
