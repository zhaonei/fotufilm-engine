package com.fotufilm.xraw;

import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 富士 RAW 转换语义层。完整时序（libfuji fuji_process_raf / rawji fuji_usb.py 交叉验证）：
 *
 *   OpenSession → GetDeviceInfo → 校验 0xD16E==6 → 清空遗留对象
 *   → SendObjectInfo(0x900C, objFormat=0xF802, "FUP_FILE.dat") → SendObject2(0x900D, RAF 流)
 *   → GetDevicePropValue(0xD185) → SetDevicePropValue(0xD185) → SetDevicePropValue(0xD183, 1|0)
 *   → 轮询 GetObjectHandles → GetThumb/GetObject → DeleteObject
 */
public final class Fuji {
    private static final String TAG = "Fuji";
    private static final int POLL_MS = 50;
    private static final int OBJ_FORMAT_RAF = 0xF802;
    private static final String UPLOAD_NAME = "FUP_FILE.dat";

    public static final class Session {
        public final String manufacturer, model, version, serial;
        public final int usbMode, freeSdram, battery;
        Session(String a, String b, String c, String d, int m, int f, int bat) {
            manufacturer = a; model = b; version = c; serial = d;
            usbMode = m; freeSdram = f; battery = bat;
        }
        public String title() {
            return (model == null || model.isEmpty() ? "FUJIFILM" : model) + " · USB RAW CONV.";
        }
    }

    public static final class DeviceInfo {
        public String manufacturer = "", model = "", deviceVersion = "", serial = "";
        public int vendorExtensionId, functionalMode;
        public int[] operations = new int[0], properties = new int[0];

        public boolean supportsProperty(int dpc) {
            for (int p : properties) if (p == dpc) return true;
            return false;
        }

        public boolean supportsOperation(int op) {
            for (int o : operations) if (o == op) return true;
            return false;
        }

        public static DeviceInfo parse(byte[] d) {
            DeviceInfo di = new DeviceInfo();
            int[] p = {0};
            di.functionalMode = 0;
            try {
                u16(d, p);                        // standardVersion
                di.vendorExtensionId = (int) u32(d, p);
                u16(d, p);                        // vendorExtensionVersion
                str(d, p);                        // vendorExtensionDesc
                di.functionalMode = u16(d, p);
                di.operations = arr16(d, p);
                arr16(d, p);                      // eventsSupported
                di.properties = arr16(d, p);
                arr16(d, p);                      // captureFormats
                arr16(d, p);                      // imageFormats
                di.manufacturer = str(d, p);
                di.model = str(d, p);
                di.deviceVersion = str(d, p);
                di.serial = str(d, p);
            } catch (Exception e) {
                Log.w(TAG, "DeviceInfo 解析不完整: " + e);
            }
            return di;
        }

        private static int u8(byte[] d, int[] p) { return d[p[0]++] & 0xFF; }
        private static int u16(byte[] d, int[] p) { int v = UsbPtp.le16(d, p[0]); p[0] += 2; return v; }
        private static long u32(byte[] d, int[] p) { long v = UsbPtp.le32(d, p[0]) & 0xFFFFFFFFL; p[0] += 4; return v; }
        private static String str(byte[] d, int[] p) {
            int n = u8(d, p);
            if (n == 0) return "";
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n - 1; i++) sb.append((char) u16(d, p));
            u16(d, p);
            return sb.toString();
        }
        private static int[] arr16(byte[] d, int[] p) {
            long n = u32(d, p);
            if (n < 0 || n > 4096) { p[0] = d.length; return new int[0]; }
            int[] out = new int[(int) n];
            for (int i = 0; i < n; i++) out[i] = u16(d, p);
            return out;
        }
    }

    private final UsbPtp t;
    private Session session;
    private DeviceInfo deviceInfo;

    public Fuji(UsbPtp transport) { this.t = transport; }

    public Session session() { return session; }

    public Session handshake() throws IOException {
        t.openSession(1);

        UsbPtp.DataIn di = t.commandDataIn(Ptp.OC_GET_DEVICE_INFO);
        di.resp.requireOk();
        DeviceInfo info = DeviceInfo.parse(di.data);
        deviceInfo = info;

        int usbMode = readU16Or(Ptp.DPC_USB_MODE, -1);
        if (usbMode != Ptp.USB_MODE_RAW_CONV) {
            throw new IOException("相机不在「USB RAW CONV.」模式（USBMode=" + usbMode + "）。\n"
                    + "请在相机上：MENU → 网络/USB 设置 → CONNECTION MODE → "
                    + "USB RAW CONV./BACKUP RESTORE，然后重新插线。");
        }

        // 握手只做三件事：OpenSession / GetDeviceInfo / 校验 USBMode。
        // 任何"可选的属性读取"都不放在这里——已实测：多余事务会让相机进入不响应状态。
        int free = -1, bat = -1;
        session = new Session(info.manufacturer, info.model, info.deviceVersion, info.serial,
                usbMode, free, bat);
        return session;
    }

    // ------------------------------------------------------------ 上传

    public void uploadRaf(File raf, UsbPtp.Progress progress) throws IOException {
        if (raf.length() <= 0) throw new IOException("RAF 文件为空：" + raf.getName());
        try {
            uploadOnce(raf, progress);
        } catch (IOException first) {
            // 富士在 STALL / 事件堆积后会让整条会话作废：重建会话后再试一次
            Log.w(TAG, "上传失败，重建 PTP 会话后重试一次: " + first.getMessage());
            try {
                recover();
                uploadOnce(raf, progress);
            } catch (IOException second) {
                throw new IOException(second.getMessage()
                        + "\n（首次失败：" + first.getMessage() + "）");
            }
        }
    }

    /** 关会话 → 重开会话 → 重新取 DeviceInfo，把相机拉回可通信状态 */
    public void recover() throws IOException {
        try { t.command(Ptp.OC_CLOSE_SESSION); } catch (Exception ignored) { }
        t.openSession(1);
        t.commandDataIn(Ptp.OC_GET_DEVICE_INFO);
    }

    private void uploadOnce(File raf, UsbPtp.Progress progress) throws IOException {
        byte[] oi = buildObjectInfo((int) raf.length(), UPLOAD_NAME);
        // ---- 主通道：富士专用 SendObjectInfo/SendObject2 ----
        String firstErr = null;
        try {
            UsbPtp.Resp r1 = t.commandDataOut(Ptp.OC_FUJI_SEND_OBJECT_INFO, new int[]{0, 0, 0}, oi);
            if (!r1.ok()) {
                throw new IOException("0x900C 被拒绝(" + r1 + ")");
            }
            UsbPtp.Resp r2 = t.commandDataOutStream(Ptp.OC_FUJI_SEND_OBJECT2, new int[0], raf, progress);
            r2.requireOk();
            Log.i(TAG, "上传完成(0x900C/0x900D) " + raf.getName() + " " + raf.length() / 1048576 + "MB");
            return;
        } catch (IOException e) {
            firstErr = e.getMessage();
            Log.w(TAG, "富士专用上传通道失败，回退标准通道: " + firstErr);
        }

        // ---- 回退通道：标准 SendObjectInfo/SendObject（libfuji 在 backup restore 路径用过）----
        // 不在这里 clearHalts：CLEAR_FEATURE 打在未 halt 的端点上会复位 data toggle，
        // 使端点与主机失步，之后该端点再也发不出去（真机日志已证实）。
        UsbPtp.Resp a = t.commandDataOut(Ptp.OC_SEND_OBJECT_INFO, new int[]{0, 0}, oi);
        if (!a.ok()) {
            throw new IOException("两种上传通道都被相机拒绝：标准通道 " + a + "；富士通道 " + firstErr);
        }
        UsbPtp.Resp b = t.commandDataOutStream(Ptp.OC_SEND_OBJECT, new int[0], raf, progress);
        b.requireOk();
        Log.i(TAG, "上传完成(标准 0x100C/0x100D) " + raf.getName());
    }

    private static byte[] buildObjectInfo(int size, String filename) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(80);
        write32(o, 0);                 // StorageID
        write16(o, OBJ_FORMAT_RAF);    // ObjectFormat
        write16(o, 0);                 // ProtectionStatus
        write32(o, size);              // CompressedSize
        write16(o, 0);                 // ThumbFormat
        write32(o, 0);                 // ThumbCompressedSize
        write32(o, 0); write32(o, 0);  // ThumbW/H
        write32(o, 0); write32(o, 0);  // ImageW/H
        write32(o, 0);                 // BitDepth
        write32(o, 0);                 // ParentObject
        write16(o, 0);                 // AssociationType
        write32(o, 0);                 // AssociationDesc
        write32(o, 0);                 // SequenceNumber
        writePtpString(o, filename);
        writePtpString(o, "");
        writePtpString(o, "");
        writePtpString(o, "");
        return o.toByteArray();
    }

    private static void writePtpString(ByteArrayOutputStream o, String s) {
        if (s.isEmpty()) { o.write(0); return; }
        o.write(s.length() + 1);
        for (int i = 0; i < s.length(); i++) write16(o, s.charAt(i));
        write16(o, 0);
    }

    private static void write16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); }
    private static void write32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); o.write((v >>> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }

    // ------------------------------------------------------------ 配置档

    public D185 readProfile() throws IOException {
        UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_DEVICE_PROP_VALUE, Ptp.DPC_RAW_CONV_PROFILE);
        r.resp.requireOk();
        if (r.data.length < D185.MIN_SIZE)
            throw new IOException("d185 长度异常：" + r.data.length + "（期望 ≥ " + D185.MIN_SIZE + "）");
        return D185.parse(r.data);
    }

    public void writeProfile(D185 p) throws IOException {
        UsbPtp.Resp r = t.commandDataOut(Ptp.OC_SET_DEVICE_PROP_VALUE,
                new int[]{Ptp.DPC_RAW_CONV_PROFILE}, p.raw());
        r.requireOk();
    }

    // ------------------------------------------------------------ 渲染

    /** @param full true=全分辨率(D183=1) ; false=预览(D183=0 + GetThumb) */
    public byte[] render(boolean full, long timeoutMs) throws IOException {
        drainObjects();
        setU16(Ptp.DPC_START_RAW_CONV, full ? 1 : 0);

        int handle = waitForObject(timeoutMs);
        try {
            return full ? getObject(handle) : getThumbOrObject(handle);
        } finally {
            try { deleteObject(handle); } catch (Exception e) { Log.w(TAG, "DeleteObject 失败: " + e); }
        }
    }

    public void renderTo(boolean full, OutputStream sink, long timeoutMs) throws IOException {
        drainObjects();
        setU16(Ptp.DPC_START_RAW_CONV, full ? 1 : 0);
        int handle = waitForObject(timeoutMs);
        try {
            UsbPtp.Resp r = t.commandDataInToStream(Ptp.OC_GET_OBJECT, new int[]{handle}, sink);
            r.requireOk();
        } finally {
            try { deleteObject(handle); } catch (Exception ignored) { }
        }
    }

    private int waitForObject(long timeoutMs) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            int h = firstHandle();
            if (h != 0) return h;
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { throw new IOException("已取消"); }
        }
        boolean alive = heartbeat();
        throw new IOException(alive
                ? "渲染超时（相机仍在响应：可能是机身 SDRAM 不足或这张 RAF 过大）"
                : "相机无响应，连接可能已断开");
    }

    // ------------------------------------------------------------ 对象

    public int[] getObjectHandles() throws IOException {
        UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_OBJECT_HANDLES, 0xFFFFFFFF, 0, 0);
        r.resp.requireOk();
        if (r.data.length < 4) return new int[0];
        int n = UsbPtp.le32(r.data, 0);
        if (n <= 0 || r.data.length < 4 + n * 4) return new int[0];
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = UsbPtp.le32(r.data, 4 + i * 4);
        return out;
    }

    private int firstHandle() throws IOException {
        int[] h = getObjectHandles();
        return h.length > 0 ? h[0] : 0;
    }

    public void deleteObject(int handle) throws IOException {
        t.command(Ptp.OC_DELETE_OBJECT, handle).requireOk();
    }

    /** 清空相机端残留对象；不做会导致「第二张必失败」与「取到上一张的图」 */
    public void drainObjects() {
        for (int round = 0; round < 16; round++) {
            int[] hs;
            try { hs = getObjectHandles(); } catch (IOException e) { return; }
            if (hs.length == 0) return;
            for (int h : hs) {
                try { deleteObject(h); } catch (IOException ignored) { }
            }
        }
    }

    private byte[] getObject(int handle) throws IOException {
        UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_OBJECT, handle);
        r.resp.requireOk();
        if (r.data.length == 0) throw new IOException("GetObject 返回空数据");
        return r.data;
    }

    private byte[] getThumbOrObject(int handle) throws IOException {
        try {
            UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_THUMB, handle);
            if (r.resp.ok() && r.data.length > 512) return r.data;
        } catch (IOException e) {
            Log.w(TAG, "GetThumb 失败，回退 GetObject: " + e);
        }
        return getObject(handle);
    }

    // ------------------------------------------------------------ 属性

    public int getU16(int code) throws IOException {
        UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_DEVICE_PROP_VALUE, code);
        r.resp.requireOk();
        if (r.data.length < 2) throw new IOException(String.format("属性 0x%04X 无数据", code));
        return UsbPtp.le16(r.data, 0);
    }

    public String getString(int code) throws IOException {
        UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_DEVICE_PROP_VALUE, code);
        r.resp.requireOk();
        return ptpString(r.data);
    }

    private static String ptpString(byte[] d) {
        if (d.length < 1) return "";
        int n = d[0] & 0xFF;
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n - 1 && 1 + i * 2 + 1 < d.length; i++)
            sb.append((char) UsbPtp.le16(d, 1 + i * 2));
        return sb.toString();
    }

    public int readU16Or(int code, int def) {
        try { return getU16(code); } catch (IOException e) { return def; }
    }

    public void setU16(int code, int value) throws IOException {
        byte[] d = {(byte) value, (byte) (value >>> 8)};
        t.commandDataOut(Ptp.OC_SET_DEVICE_PROP_VALUE, new int[]{code}, d).requireOk();
    }

    /**
     * 探测这台相机对关键属性的真实约束（min/max/step 或枚举）。
     * 这是替代硬编码猜测的唯一权威来源：高光/阴影到底 1 还是 0.5 步进、白平衡有哪些档、
     * 胶片模拟枚举到几，全部以相机回报为准。
     */
    public java.util.Map<Integer, PropDesc> probeDescriptors() {
        int[] codes = {
                // 当前拍摄态（C0）
                0xD001, 0xD007, 0xD02E, 0xD023, 0xD029, 0xD030, 0xD189,
                0x5005, 0xD00B, 0xD00C, 0xD017, 0xD320, 0xD321, 0xD104, 0xD031,
                0xD018, 0xD017,
                // 配方槽域（部分机型只暴露这套）
                0xD192, 0xD190, 0xD191, 0xD195, 0xD196, 0xD197, 0xD198,
                0xD199, 0xD19A, 0xD19B, 0xD19C, 0xD19D, 0xD19E,
                0xD18E, 0xD18F, 0xD18C,
        };
        java.util.Map<Integer, PropDesc> out = new java.util.LinkedHashMap<>();
        for (int c : codes) {
            if (out.containsKey(c)) continue;
            // 只查相机在 DeviceInfo 里声明支持的属性：
            // 对不存在的属性发 0x1014 会得到 0x200A，实测这会让随后的 bulk OUT 失效。
            if (deviceInfo != null && deviceInfo.properties.length > 0
                    && !deviceInfo.supportsProperty(c)) {
                continue;
            }
            try {
                UsbPtp.DataIn r = t.getPropDesc(c);
                if (r.resp.ok() && r.data.length >= 5) {
                    out.put(c, PropDesc.parse(c, r.data));
                }
            } catch (Exception ignored) {
                // 单条失败不影响其它属性
            }
        }
        Log.i(TAG, "能力探测完成：" + out.size() + " 条属性描述");
        return out;
    }

    /** 可选状态读取：失败不影响主流程 */
    public void refreshStatus() {
        if (session == null) return;
        int free = readU16Or(Ptp.DPC_FREE_SDRAM, -1);
        int bat = readU16Or(Ptp.DPC_BATTERY, -1);
        session = new Session(session.manufacturer, session.model, session.version, session.serial,
                session.usbMode, free, bat);
    }

    public boolean heartbeat() {
        try { getU16(Ptp.DPC_BATTERY); return true; } catch (IOException e) { return false; }
    }

    /** 停机序列：先标记状态，再 CloseSession（吞异常），再释放接口 */
    public void shutdown() { t.close(); }
}
