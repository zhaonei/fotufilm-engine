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

/**
 * 标准 PTP (ISO 15740) over USB bulk，只依赖 Android framework 的 UsbManager。
 *
 * 针对真机实测问题（上传时 `bulk OUT 失败 0/24 ret=-1`）的加固：
 *  1. **事件通道必须取**：富士会往 interrupt IN 灌 PTP 事件，主机不读会把队列塞满，
 *     之后相机就停止接受命令（表现就是 bulk OUT 一直 timeout）。
 *  2. **端点 halt 自愈**：写失败时用 controlTransfer(CLEAR_FEATURE, ENDPOINT_HALT) 清 halt，
 *     退避重试并逐级缩小 chunk。
 *  3. **发命令前清空 IN 管道**：扔掉上一次残留的 DATA/RESPONSE，避免协议错位。
 *  4. 事务日志留在内存里（`dumpLog()`），方便用户回传定位。
 */
public final class UsbPtp implements Closeable {
    private static final String TAG = "UsbPtp";

    /**
     * 真机日志显示 512KB 单次 URB 在这台手机上会返回 -1（需要降级后才能传），
     * 因此默认直接用实测可用的 128KB，避免白白消耗两次超时。
     */
    public static final int CHUNK_DEFAULT = 128 * 1024;
    public static final int CHUNK_MAX = 512 * 1024;
    public static final int CHUNK_MIN = 4 * 1024;
    private static final int CMD_TIMEOUT = 5000;
    private static final int DATA_TIMEOUT = 30000;
    /**
     * 数据 IN 的首包超时：GetObject 返回的是 2~3MB 的 JPEG，
     * 相机准备/开始推送可能远超过命令超时。给不足就会读超时 → 中止传输 →
     * 端点被 STALL → 整场会话失效（"换几个胶片模拟就没反应"就是这么来的）。
     */
    private static final int IN_FIRST_TIMEOUT = 25000;
    private static final int LARGE_TIMEOUT = 180000;
    private static final int USB_BULK_MAX = 512;
    private static final int UPLOAD_CHUNK = 512 * 1024;
    private static final int OUT_RETRIES = 3;

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
    private UsbEndpoint epEvt;


    private int txId = 0;
    private int chunk = CHUNK_DEFAULT;
    private final byte[] rxBuf = new byte[64 * 1024];

    public volatile boolean sessionOpen = false;

    private final StringBuilder txLog = new StringBuilder();
    private static final int TXLOG_MAX = 24000;

    public UsbPtp(Context ctx, UsbDevice dev) {
        this.usbManager = (UsbManager) ctx.getApplicationContext().getSystemService(Context.USB_SERVICE);
        this.device = dev;
    }

    public boolean hasPermission() { return usbManager.hasPermission(device); }

    public int chunkSize() { return chunk; }

    private void log(String s) {
        synchronized (txLog) {
            if (txLog.length() > TXLOG_MAX) txLog.delete(0, txLog.length() - TXLOG_MAX / 2);
            txLog.append(s).append('\n');
        }
    }

    /** 供上层往事务日志里写备注（会出现在导出的 probe_dump 里） */
    public void note(String msg) { log("[note] " + msg); }

    public String dumpLog() {
        synchronized (txLog) { return txLog.toString(); }
    }

    // ------------------------------------------------------------ 生命周期

    public synchronized void open() throws IOException {
        if (conn != null) return;
        if (!hasPermission()) throw new IOException("没有 USB 权限");
        if (device.getVendorId() != Ptp.VID_FUJIFILM)
            throw new IOException(String.format("不是富士相机（VID=0x%04X）", device.getVendorId()));

        // 端点必须在「将要 claim 的那个接口」里找。
        // v1.2.1 曾把搜索范围放宽到所有接口，结果拿到未 claim 接口上的端点，
        // 所有传输都返回 -1（真机表现为 IN 读超时）——这是硬性约束。
        UsbInterface claimIface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface it = device.getInterface(i);
            if (it.getInterfaceClass() == Ptp.USB_CLASS_STILL_IMAGE) { claimIface = it; break; }
        }
        if (claimIface == null) {
            throw new IOException("未找到 PTP(Still Image, class 6) 接口。请把相机 "
                    + "MENU → 网络/USB 设置 → CONNECTION MODE 设为「USB RAW CONV./BACKUP RESTORE」");
        }

        UsbEndpoint in = null, out = null, evt = null;
        for (int e = 0; e < claimIface.getEndpointCount(); e++) {
            UsbEndpoint ep = claimIface.getEndpoint(e);
            boolean inDir = ep.getDirection() == UsbConstants.USB_DIR_IN;
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (inDir) { if (in == null) in = ep; }
                else { if (out == null) out = ep; }
            } else if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT && inDir && evt == null) {
                evt = ep;
            }
        }
        // 极少数机型把 bulk 端点放在独立的 alternate 接口里：只有 class-6 接口没有 bulk 时才回退
        if (in == null || out == null) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface it = device.getInterface(i);
                if (it == claimIface) continue;
                UsbEndpoint bi = null, bo = null;
                for (int e = 0; e < it.getEndpointCount(); e++) {
                    UsbEndpoint ep = it.getEndpoint(e);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) bi = ep; else bo = ep;
                }
                if (bi != null && bo != null) {
                    claimIface = it; in = bi; out = bo; evt = null;
                    break;
                }
            }
        }
        if (in == null || out == null) {
            throw new IOException(String.format(
                    "PTP 接口(if=%d)上找不到 bulk 端点：in=%s out=%s。"
                            + "这台手机的 USB 栈可能未正确枚举相机，请拔插线或换线重试。",
                    claimIface.getId(), in, out));
        }
        final UsbInterface ifaceToClaim = claimIface;
        final UsbEndpoint epInF = in, epOutF = out, epEvtF = evt;

        UsbDeviceConnection c = usbManager.openDevice(device);
        if (c == null) throw new IOException("UsbManager.openDevice 返回 null");
        if (!c.claimInterface(ifaceToClaim, true)) {
            c.close();
            throw new IOException("claimInterface 失败：接口可能被其它 App 占用");
        }
        conn = c; intf = ifaceToClaim; epIn = epInF; epOut = epOutF; epEvt = epEvtF;
        log(String.format("open iface=%d epIn=0x%02X epOut=0x%02X evt=%s chunk=%d",
                ifaceToClaim.getId(), epInF.getAddress(), epOutF.getAddress(),
                epEvtF == null ? "无" : String.format("0x%02X", epEvtF.getAddress()), chunk));

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
        conn = null; intf = null; epIn = null; epOut = null; epEvt = null;
    }

    // ------------------------------------------------------------ 事务

    public synchronized Resp command(int opcode, int... params) throws IOException {
        drainEvents();
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        Resp r = waitResponse(tid, opcode);
        log(String.format("cmd 0x%04X %s -> %s", opcode, ints(params), r));
        return r;
    }

    public synchronized DataIn commandDataIn(int opcode, int... params) throws IOException {
        drainEvents();
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        byte[] data = new byte[0];
        while (true) {
            Container c = readContainer(null, IN_FIRST_TIMEOUT);
            if (c.type == Ptp.CT_DATA) data = c.payload;
            else if (c.type == Ptp.CT_RESPONSE) {
                Resp r = new Resp(c.code, opcode, c.params);
                log(String.format("cmd 0x%04X %s -> %s dataLen=%d", opcode, ints(params), r, data.length));
                return new DataIn(r, data);
            }
        }
    }

    public synchronized Resp commandDataOut(int opcode, int[] params, byte[] data) throws IOException {
        drainEvents();
        int tid = ++txId;
        sendCommandAndData(opcode, tid, params, data);
        Resp r = waitResponse(tid, opcode);
        log(String.format("cmd(out) 0x%04X %s outLen=%d -> %s", opcode, ints(params), data.length, r));
        return r;
    }

    public synchronized Resp commandDataOutStream(int opcode, int[] params, File file, Progress p)
            throws IOException {
        long total = file.length();
        if (total <= 0 || total > Integer.MAX_VALUE) throw new IOException("文件大小异常: " + total);
        int tid = ++txId;
        sendCommand(opcode, tid, params);

        // DATA 容器的 header 必须和第一批负载在同一个 USB 传输里（filmkit 的做法）
        int dataLen = (int) (12 + total);
        int firstPayload = (int) Math.min(chunk - 12L, total);
        if (firstPayload < 0) firstPayload = 0;
        byte[] first = new byte[12 + firstPayload];
        UsbPtp.put32(first, 0, dataLen);
        UsbPtp.put16(first, 4, Ptp.CT_DATA);
        UsbPtp.put16(first, 6, opcode);
        UsbPtp.put32(first, 8, tid);

        long sent = 0;
        InputStream in = new FileInputStream(file);
        try {
            if (firstPayload > 0) {
                int got = readFully(in, first, 12, firstPayload);
                if (got != firstPayload) throw new IOException("读取文件失败");
            }
            writeAll(first, 0, first.length, LARGE_TIMEOUT);
            sent = firstPayload;
            if (p != null) p.onProgress(sent, total);

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
        // 大文件发完后相机要处理/校验，filmkit 这里给 60s
        Resp r = waitResponse(tid, opcode, LARGE_TIMEOUT);
        log(String.format("upload 0x%04X %d bytes -> %s", opcode, total, r));
        return r;
    }

    public synchronized Resp commandDataInToStream(int opcode, int[] params, OutputStream sink)
            throws IOException {
        int tid = ++txId;
        sendCommand(opcode, tid, params);
        while (true) {
            Container c = readContainer(sink, LARGE_TIMEOUT);
            if (c.type == Ptp.CT_RESPONSE) {
                Resp r = new Resp(c.code, opcode, c.params);
                log(String.format("download 0x%04X -> %s", opcode, r));
                return r;
            }
        }
    }

    // ------------------------------------------------------------ 底层


    /** CLEAR_FEATURE(ENDPOINT_HALT)：端点被相机 STALL 之后必须清，否则后续所有写都返回 -1 */
    private void clearHalt(int epAddress) {
        if (conn == null) return;
        try {
            conn.controlTransfer(0x02, 0x01, 0x0000, epAddress, new byte[0], 0, 1000);
            log(String.format("clearHalt 0x%02X", epAddress));
        } catch (Exception e) {
            log("clearHalt 失败: " + e);
        }
    }

    /**
     * 排空 PTP 事件（interrupt IN）。
     *
     * 这是"用久了突然全卡死"的根因：富士会把 ObjectAdded/PreviewAvailable 等事件
     * 塞进 interrupt IN 端点，主机不取的话队列填满后相机就**停止响应任何命令**
     * （表现：连 12 字节命令容器都写不出去）。
     *
     * 必须**在锁内同步做**：早期用 UsbRequest + 独立线程会和同步 bulkTransfer
     * 抢同一个 UsbDeviceConnection，抛 IllegalArgumentException（已复现过）。
     */
    private void drainEvents() {
        if (conn == null || epEvt == null) return;
        byte[] tmp = new byte[64];
        for (int i = 0; i < 8; i++) {
            int n;
            try {
                n = conn.bulkTransfer(epEvt, tmp, 0, tmp.length, 10);
            } catch (Exception e) {
                return;
            }
            if (n <= 0) return;
            if (n >= 6) log(String.format("event 0x%04X len=%d", le16(tmp, 4), n));
        }
    }

    /**
     * 管道重新对齐：清 halt + 把 IN 端点上残留的垃圾读干净。
     * 相机拒绝某个参数包时会 STALL 端点，若不清理，后续读到的就是残包
     * （典型症状：把 JPEG 里的 0x20202020 当成容器长度）。
     */
    public synchronized void resync() {
        clearHalts();
        byte[] tmp = new byte[USB_BULK_MAX];
        for (int i = 0; i < 16; i++) {
            int n;
            try {
                n = conn == null || epIn == null ? -1 : conn.bulkTransfer(epIn, tmp, 0, tmp.length, 20);
            } catch (Exception e) {
                return;
            }
            if (n <= 0) return;
            log("resync 丢弃 " + n + " 字节");
        }
    }

    /** 上传前一次性清两个端点的 halt：相机 STALL 之后不清 halt，后续所有写都会 -1 */
    public synchronized void clearHalts() {
        if (epOut != null) clearHalt(epOut.getAddress());
        if (epIn != null) clearHalt(epIn.getAddress());
        if (epEvt != null) clearHalt(epEvt.getAddress());
    }

    /** GetDevicePropDesc(0x1014)：拿到这台相机对某属性允许的 min/max/step 或枚举 */
    public synchronized DataIn getPropDesc(int code) throws IOException {
        return commandDataIn(Ptp.OC_GET_DEVICE_PROP_DESC, code);
    }

    /** 构造一个完整的 PTP 容器字节流（header + params/data 必须连续） */
    private static byte[] buildContainer(int type, int opcode, int tid, int[] params, byte[] data, int dataOff, int dataLen) {
        int paramBytes = params == null ? 0 : params.length * 4;
        int payload = data == null ? 0 : dataLen;
        byte[] b = new byte[12 + paramBytes + payload];
        put32(b, 0, b.length);
        put16(b, 4, type);
        put16(b, 6, opcode);
        put32(b, 8, tid);
        int o = 12;
        if (params != null) for (int v : params) { put32(b, o, v); o += 4; }
        if (data != null && payload > 0) System.arraycopy(data, dataOff, b, o, payload);
        return b;
    }

    /**
     * 发出 COMMAND 容器（无 DATA 阶段）。
     * 容器的 header 与参数必须在同一次 USB 传输里——USB bulk 以短包表示传输结束，
     * 把 12 字节头单独发一个 URB，相机就会认为数据阶段被截断并 STALL 端点。
     */
    private void sendCommand(int opcode, int tid, int[] params) throws IOException {
        if (params.length > 5) throw new IOException("PTP 命令最多 5 个参数");
        byte[] b = buildContainer(Ptp.CT_COMMAND, opcode, tid, params, null, 0, 0);
        writeAll(b, 0, b.length, CMD_TIMEOUT);
    }

    /** COMMAND + DATA（小数据）：两个容器各自一次性发完，header 与 payload 不分离 */
    private void sendCommandAndData(int opcode, int tid, int[] params, byte[] data) throws IOException {
        if (params.length > 5) throw new IOException("PTP 命令最多 5 个参数");
        byte[] cmd = buildContainer(Ptp.CT_COMMAND, opcode, tid, params, null, 0, 0);
        writeAll(cmd, 0, cmd.length, CMD_TIMEOUT);
        byte[] dat = buildContainer(Ptp.CT_DATA, opcode, tid, null, data, 0, data.length);
        writeAll(dat, 0, dat.length, DATA_TIMEOUT);
    }

    private Resp waitResponse(int tid, int opcode) throws IOException {
        return waitResponse(tid, opcode, CMD_TIMEOUT);
    }

    private Resp waitResponse(int tid, int opcode, int headerTimeout) throws IOException {
        while (true) {
            Container c = readContainer(null, headerTimeout);
            if (c.type == Ptp.CT_RESPONSE) {
                if (c.tid != tid) log("事务号不匹配 期望 " + tid + " 收到 " + c.tid);
                return new Resp(c.code, opcode, c.params);
            }
            if (c.type == Ptp.CT_DATA) log("丢弃非预期 DATA " + c.payload.length + "B");
        }
    }

    private static final class Container {
        int type, code, tid;
        int[] params = new int[0];
        byte[] payload = new byte[0];
    }

    private Container readContainer(OutputStream sink) throws IOException {
        return readContainer(sink, CMD_TIMEOUT);
    }

    private Container readContainer(OutputStream sink, int headerTimeout) throws IOException {
        byte[] first = new byte[USB_BULK_MAX];
        int have = bulkIn(first, 0, USB_BULK_MAX, headerTimeout);
        if (have < 0) {
            // 关键：-1 是超时/错误，绝不能拿它当偏移继续读（曾导致 off=-1 len=513 的内部越界）
            throw new IOException("相机没有回应（bulk IN 超时 " + (CMD_TIMEOUT / 1000) + "s）。\n"
                    + "如果刚才有过一次上传失败，相机固件很可能已卡死——这是富士的已知行为：\n"
                    + "**请拔掉数据线、等 3 秒再插上（或关机重开），然后重新「连接相机」**。\n"
                    + "若刚启动就出现此提示，请确认相机 CONNECTION MODE = USB RAW CONV./BACKUP RESTORE。");
        }
        while (have < 12) {
            int n = bulkIn(first, have, USB_BULK_MAX - have, headerTimeout);
            if (n < 0) throw new IOException("读取容器头超时（已收到 " + have + " 字节）");
            if (n == 0) throw new IOException("读取容器头得到 0 字节（已收到 " + have + "）");
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

    /** 统一的 bulk 读：显式校验 offset/length，避免 Android checkBounds 抛裸 IAE */
    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(buf, off + done, len - done);
            if (n <= 0) break;
            done += n;
        }
        return done;
    }

    private int bulkIn(byte[] buf, int off, int len, int timeout) throws IOException {
        if (conn == null || epIn == null) throw new IOException("USB 未打开");
        checkBounds("IN", buf, off, len);
        try {
            return conn.bulkTransfer(epIn, buf, off, len, timeout);
        } catch (IllegalArgumentException e) {
            throw new IOException("bulkTransfer(IN) 参数异常 off=" + off + " len=" + len
                    + " buf=" + buf.length + " : " + e.getMessage(), e);
        }
    }

    private static void checkBounds(String tag, byte[] buf, int off, int len) throws IOException {
        if (buf == null) throw new IOException(tag + " 缓冲区为 null");
        if (off < 0 || len < 0 || off + len > buf.length) {
            throw new IOException(String.format("%s 越界：off=%d len=%d buf=%d（内部 bug，请回传此信息）",
                    tag, off, len, buf.length));
        }
    }

    /**
     * 写入：失败时清 halt + 退避重试 + 逐级缩小 chunk。
     * 富士在收到它暂时处理不了的命令时会 STALL OUT 端点，之后所有 bulkTransfer 都返回 -1，
     * 只有 CLEAR_FEATURE 才能恢复——这就是「0/24 ret=-1」的处置方式。
     */
    private void writeAll(byte[] buf, int off, int len, int timeout) throws IOException {
        if (conn == null || epOut == null) throw new IOException("USB 未打开");
        int written = 0;
        int attempt = 0;
        while (written < len) {
            int want = Math.min(chunk, len - written);
            checkBounds("OUT", buf, off + written, want);
            int n;
            try {
                n = conn.bulkTransfer(epOut, buf, off + written, want, timeout);
            } catch (IllegalArgumentException e) {
                throw new IOException("bulkTransfer(OUT) 参数异常 off=" + (off + written)
                        + " len=" + want + " buf=" + buf.length + " : " + e.getMessage(), e);
            }
            if (n <= 0) {
                attempt++;
                log(String.format("bulk OUT %d/%d ret=%d attempt=%d chunk=%d",
                        written, len, n, attempt, chunk));
                if (attempt <= OUT_RETRIES) {
                    // 先只做退避 + 缩小 chunk；重试 3 次仍失败时，才作为最后手段清一次 halt
                    // （相机确实 STALL 时只有 CLEAR_FEATURE 能救；但不要在正常情况下乱清）
                    try { Thread.sleep(150L * attempt); } catch (InterruptedException ignored) { }
                    if (attempt >= 2) chunk = Math.max(chunk / 4, CHUNK_MIN);
                    if (attempt == 3) clearHalt(epOut.getAddress());
                    continue;
                }
                throw new IOException(String.format(
                        "bulk OUT 失败 %d/%d ret=%d（已重试 %d 次；请拔插数据线重连）\n"
                                + "请依次确认：① 相机 USB POWER SUPPLY SETTING = OFF "
                                + "② 换一根「数据线」（非充电线）③ 相机 AUTO POWER OFF = OFF "
                                + "④ 直接插手机、不要经 HUB，然后重新连接相机。",
                        written, len, n, OUT_RETRIES));
            }
            written += n;
            attempt = 0;
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

    private static String ints(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.append(']').toString();
    }

    public String describe() {
        return String.format("VID=0x%04X PID=0x%04X ifaces=%d claimed=%s epIn=%s epOut=%s evt=%s chunk=%d",
                device.getVendorId(), device.getProductId(), device.getInterfaceCount(),
                intf == null ? "-" : String.valueOf(intf.getId()),
                epIn == null ? "-" : String.format("0x%02X", epIn.getAddress()),
                epOut == null ? "-" : String.format("0x%02X", epOut.getAddress()),
                epEvt == null ? "-" : String.format("0x%02X", epEvt.getAddress()),
                chunk);
    }
}
