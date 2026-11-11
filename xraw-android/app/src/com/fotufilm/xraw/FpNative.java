package com.fotufilm.xraw;

/**
 * d185 结构体的 **C 层**编解码（jni/fp_d185.c + fp_jni.c，布局对齐 petabyt/libfuji fp/src/d185.c）。
 *
 * 布局：0x000 u16 nProps=29 / 0x002 u8 strlen / 0x003 UTF-16LE IOPCode /
 *       0x201 起 29 × u32 / 总长 629。
 *
 * 全部参数一次性打包成 629 字节再写 0xD185，不再逐个属性硬拼。
 */
public final class FpNative {
    private static Boolean available;

    public static final int PROPS_OFFSET = 0x201;
    public static final int PROPS = 29;
    public static final int SIZE = PROPS_OFFSET + PROPS * 4;   // 629

    private FpNative() { }

    public static synchronized boolean available() {
        if (available == null) {
            try {
                System.loadLibrary("fpx");
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /** 返回 32 个 int：0..28 为字段值，[29]=IOPCode，[30]=包内 nProps */
    private static native int[] parseD185(byte[] d185);

    /** 以 29 字段 + IOPCode 生成 629 字节标准包 */
    private static native byte[] createD185(int[] props, int iopCode);

    // ---------------------------------------------------------------- Java 兜底（native 不可用时）

    static int[] parseFallback(byte[] raw) {
        int n = UsbPtp.le16(raw, 0);
        int base = PROPS_OFFSET;
        if (base + n * 4 > raw.length) base = raw.length - n * 4;
        int[] out = new int[32];
        for (int i = 0; i < PROPS; i++) {
            int off = base + i * 4;
            out[i] = (off + 4 <= raw.length) ? UsbPtp.le32(raw, off) : 0;
        }
        out[PROPS] = parseIop(raw);
        out[PROPS + 1] = n;
        return out;
    }

    static byte[] createFallback(int[] props, int iopCode) {
        byte[] b = new byte[SIZE];
        UsbPtp.put16(b, 0, PROPS);
        char[] hex = String.format("%08X", iopCode).toCharArray();
        b[2] = 9;
        int p = 3;
        for (char c : hex) { UsbPtp.put16(b, p, c); p += 2; }
        UsbPtp.put16(b, p, 0);
        for (int i = 0; i < PROPS; i++) UsbPtp.put32(b, PROPS_OFFSET + i * 4, props[i]);
        return b;
    }

    private static int parseIop(byte[] raw) {
        int n = raw[2] & 0xFF, p = 3, code = 0;
        for (int i = 0; i < n && p + 1 < raw.length; i++) {
            int wc = UsbPtp.le16(raw, p);
            p += 2;
            if (wc == 0) break;
            int d;
            if (wc >= '0' && wc <= '9') d = wc - '0';
            else if (wc >= 'A' && wc <= 'F') d = wc - 'A' + 10;
            else if (wc >= 'a' && wc <= 'f') d = wc - 'a' + 10;
            else break;
            if (i > 8) break;
            code = (code << 4) | d;
        }
        return code;
    }

    // ---------------------------------------------------------------- 公开入口

    public static int[] parse(byte[] d185) {
        if (available()) {
            try {
                int[] r = parseD185(d185);
                if (r != null && r.length >= 32) return r;
            } catch (Throwable ignored) { }
        }
        return parseFallback(d185);
    }

    public static byte[] create(int[] props, int iopCode) {
        if (available()) {
            try {
                byte[] r = createD185(props, iopCode);
                if (r != null && r.length == SIZE) return r;
            } catch (Throwable ignored) { }
        }
        return createFallback(props, iopCode);
    }
}
