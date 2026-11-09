package com.fotufilm.xraw;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * 标准 PTP (ISO 15740) over USB bulk，只依赖 Android framework 的 UsbManager。
 *
 * 关键设计（与设计文档 §2.2/§5.4 一致）：
 *  - 单实例 + 串行：相机是有状态单会话设备，本类所有公开方法都加锁。
 *  - 收容器先读 512B（header 与 payload 可能同包到达），再按 length 补齐。
 *  - 上行大文件（30~60MB RAF）：先发 12B DATA 头，再流式写文件，内存恒定 ~512KB。
 *  - chunk 自适应：失败即 512K→64K→16K→4K 降级并记忆。
 *  - 断线不可恢复：任何 IOException 都应让上层重建会话。
 */
public final class UsbPtp implements Closeable {
    private static final String TAG = "UsbPtp";

    public static final int CHUNK_DEFAULT = 64 * 1024;
    public static final int CHUNK_MAX = 512 * 1024;
    public static final int CHUNK_MIN = 4 * 1024;
    private static final int CMD_TIMEOUT = 5000;
    private static final int DATA_TIMEOUT = 30000;
    private static final int LARGE_TIMEOUT = 180000;
    private static final int USB_BULK_MAX = 512;
    private static final int UPLOAD_CHUNK = 512 * 1024;

    public interface Progress {
        void onProgress(long sent, long total);
    }

    public static final class Resp {
        public final int code;
        public final int opcode;
        public final int[] params;
        Resp(int code, int opcode, int[] params) { this.code = code; this.opcode = opcode; this.params = params; }
        public boolean ok() { return code == Ptp.RC_OK; }
        public void requireOk() throws IOException {
            if (!ok()) throw new IOException(Ptp.rcText(code) + String.format(" [op=0x%04X]", opcode));
        }
        @Override public String toString() { return String.format("0x%04X", code); }
    }

    public static final class DataIn {
        public final Resp resp;
        public final byte[] data;
        DataIn(Resp r, byte[] d) { this.resp = r; this.data = d; }
    }

    private final UsbManager usbManager;
    public final UsbDevice device;

    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;

    private int txId = 0;
    private int chunk = CHUNK_DEFAULT;
    private final byte[] rxBuf = new byte[64 * 1024];

    public volatile boolean sessionOpen = false;

    public UsbPtp(Context ctx, UsbDevice dev) {
        this.usbManager = (UsbManager) ctx.getApplicationContext().getSystemService(Context.USB_SERVICE);
        this.device = dev;
    }

    public boolean hasPermission() { return usbManager.hasPermission(device); }

    public int chunkSize() { return chunk; }

    public synchronized void open() throws IOException {
        if (conn != null) return;
        if (!hasPermission()) throw new IOException("没有 USB 权限");
        if (device.getVendorId() != Ptp.VID_FUJIFILM)
            throw new IOException(String.format("不是富士相机（VID=0x%04X）", device.getVendorId()));

        UsbInterface found = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            if (it.getInterfaceClass() == Ptp.USB_CLASS_STILL_IMAGE) { found = it; break; }
        }
        if (found == null) {
            throw new IOException("未找到 PTP(Still Image, class 6) 接口。请把相机 "
                    + "MENU → 网络/USB 设置 → CONNECTION MODE 设为「USB RAW CONV./BACKUP RESTORE」");
        }

        UsbEndpoint in = null, out = null;
        for (int i = 0; i < found.getEndpointCount(); i++) {
            UsbEndpoint ep = found.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
        }
        if (in == null || out == null) throw new IOException("PTP 接口缺少 bulk 端点");

        UsbDeviceConnection c = usbManager.openDevice(device);
        if (c == null) throw new IOException("UsbManager.openDevice 返回 null");
        if (!c.claimInterface(found, true)) {
            c.close();
            throw new IOException("claimInterface 失败：接口可能被其它 App 占用");
        }
        conn = c; intf = found; epIn = in; epOut = out;
        Log.i(TAG, String.format("opened in=0x%02X out=0x%02X chunk=%d",
                in.getAddress(), out.getAddress(), chunk));
    }

    public synchronized void openSession(int sessionId) throws IOException {
        txId = 0;
        Resp r = command(Ptp.OC_OPEN_SESSION, sessionId);
        if (r.code != Ptp.RC_OK && r.code != Ptp.RC_SESSION_ALREADY_OPEN)
            throw new IOException("OpenSession 被拒绝：" + Ptp.rcText(r.code));
        sessionOpen = true;
    }

    @Override public synchronized void close() {
        boolean wasOpen = sessionOpen;
        sessionOpen = false;             // 先清状态，再发 CloseSession（拔线时它必然失败）
        if (wasOpen) {
            try { command(Ptp.OC_CLOSE_SESSION); } catch (Exception ignored) { }
        }
        try { if (intf != null && conn != null) conn.releaseInterface(intf); } catch (Exception ignored) { }
        try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        conn = null; intf = null; epIn = null; epOut = null;
    }

    // ------------------------------------------------------------ 事务

    public synchronized Resp command(int opcode, int... params) throws IOException {
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        return waitResponse(tid, opcode);
    }

    public synchronized DataIn commandDataIn(int opcode, int... params) throws IOException {
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        byte[] data = new byte[0];
        while (true) {
            Container c = readContainer(null);
            if (c.type == Ptp.CT_DATA) data = c.payload;
            else if (c.type == Ptp.CT_RESPONSE) return new DataIn(new Resp(c.code, opcode, c.params), data);
        }
    }

    public synchronized Resp commandDataOut(int opcode, int[] params, byte[] data) throws IOException {
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        sendDataHeader(opcode, tid, 12 + data.length);
        writeAll(data, 0, data.length, DATA_TIMEOUT);
        return waitResponse(tid, opcode);
    }

    /** 流式 DATA-OUT：把整个文件推给相机（0x900D），内存占用恒定 */
    public synchronized Resp commandDataOutStream(int opcode, int[] params, File file, Progress p)
            throws IOException {
        long total = file.length();
        if (total <= 0 || total > Integer.MAX_VALUE) throw new IOException("文件大小异常: " + total);
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        sendDataHeader(opcode, tid, (int) (12 + total));
        long sent = 0;
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[UPLOAD_CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                writeAll(buf, 0, n, LARGE_TIMEOUT);
                sent += n;
                if (p != null) p.onProgress(sent, total);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        if (sent != total) throw new IOException("上传字节数不符 " + sent + "/" + total);
        return waitResponse(tid, opcode);
    }

    /** 流式 DATA-IN：把相机回传的 JPEG/TIFF 直接写进文件，不进堆 */
    public synchronized Resp commandDataInToStream(int opcode, int[] params, OutputStream sink)
            throws IOException {
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        while (true) {
            Container c = readContainer(sink);
            if (c.type == Ptp.CT_RESPONSE) return new Resp(c.code, opcode, c.params);
            // CT_DATA 的 payload 已写入 sink
        }
    }

    // ------------------------------------------------------------ 底层

    private void sendCommand(int opcode, int tid, int[] params) throws IOException {
        if (params.length > 5) throw new IOException("PTP 命令最多 5 个参数");
        byte[] b = new byte[12 + params.length * 4];
        put32(b, 0, b.length);
        put16(b, 4, Ptp.CT_COMMAND);
        put16(b, 6, opcode);
        put32(b, 8, tid);
        for (int i = 0; i < params.length; i++) put32(b, 12 + i * 4, params[i]);
        writeAll(b, 0, b.length, CMD_TIMEOUT);
    }

    private void sendDataHeader(int opcode, int tid, int totalLen) throws IOException {
        byte[] b = new byte[12];
        put32(b, 0, totalLen);
        put16(b, 4, Ptp.CT_DATA);
        put16(b, 6, opcode);
        put32(b, 8, tid);
        writeAll(b, 0, 12, CMD_TIMEOUT);
    }

    private Resp waitResponse(int tid, int opcode) throws IOException {
        while (true) {
            Container c = readContainer(null);
            if (c.type == Ptp.CT_RESPONSE) {
                if (c.tid != tid) Log.w(TAG, "事务号不匹配 期望 " + tid + " 收到 " + c.tid);
                return new Resp(c.code, opcode, c.params);
            }
            if (c.type == Ptp.CT_DATA) Log.w(TAG, "丢弃非预期 DATA 阶段 " + c.payload.length + "B");
        }
    }

    private static final class Container {
        int type, code, tid;
        int[] params = new int[0];
        byte[] payload = new byte[0];
    }

    private Container readContainer(OutputStream sink) throws IOException {
        byte[] first = new byte[USB_BULK_MAX];
        int have = bulkIn(first, 0, USB_BULK_MAX, CMD_TIMEOUT);
        while (have < 12) {
            int n = bulkIn(first, have, USB_BULK_MAX - have, CMD_TIMEOUT);
            if (n <= 0) throw new IOException("读取容器头失败 have=" + have);
            have += n;
        }
        int total = le32(first, 0);
        Container c = new Container();
        c.type = le16(first, 4);
        c.code = le16(first, 6);
        c.tid = le32(first, 8);
        if (total < 12 || total > 512 * 1024 * 1024)
            throw new IOException("非法容器长度 " + total + "，会话状态错乱需重连");

        if (c.type == Ptp.CT_RESPONSE) {
            int counted = Math.min((have - 12) / 4, 5);
            c.params = new int[counted];
            for (int i = 0; i < counted; i++) c.params[i] = le32(first, 12 + i * 4);
            return c;
        }

        int remain = total - 12;
        ByteArrayOutputStream bos = sink == null ? new ByteArrayOutputStream(Math.min(remain, 1 << 20)) : null;
        int done = Math.min(Math.max(have - 12, 0), remain);
        if (done > 0) {
            if (sink != null) sink.write(first, 12, done); else bos.write(first, 12, done);
        }
        while (done < remain) {
            int want = Math.min(rxBuf.length, remain - done);
            int n = bulkIn(rxBuf, 0, want, LARGE_TIMEOUT);
            if (n <= 0) throw new IOException("数据阶段中断 " + done + "/" + remain);
            if (sink != null) sink.write(rxBuf, 0, n); else bos.write(rxBuf, 0, n);
            done += n;
        }
        if (bos != null) c.payload = bos.toByteArray();
        return c;
    }

    private int bulkIn(byte[] buf, int off, int len, int timeout) throws IOException {
        if (conn == null || epIn == null) throw new IOException("USB 未打开");
        return conn.bulkTransfer(epIn, buf, off, len, timeout);
    }

    private void writeAll(byte[] buf, int off, int len, int timeout) throws IOException {
        if (conn == null || epOut == null) throw new IOException("USB 未打开");
        int written = 0;
        while (written < len) {
            int want = Math.min(chunk, len - written);
            int n = conn.bulkTransfer(epOut, buf, off + written, want, timeout);
            if (n <= 0) {
                if (chunk > CHUNK_MIN) {
                    chunk = Math.max(chunk / 4, CHUNK_MIN);
                    Log.w(TAG, "bulk OUT 失败(" + n + ")，chunk 降级为 " + chunk);
                    continue;
                }
                throw new IOException("bulk OUT 失败 " + written + "/" + len + " ret=" + n);
            }
            written += n;
        }
    }

    static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
    static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); }
    static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }

    public String describe() {
        return String.format("VID=0x%04X PID=0x%04X chunk=%d", device.getVendorId(), device.getProductId(), chunk);
    }

    static String hex(byte[] d, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(d.length, max); i++) sb.append(String.format("%02X ", d[i]));
        return sb.toString();
    }
}
