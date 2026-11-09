package com.fotufilm.xraw;

import java.io.IOException;

/**
 * PTP GetDevicePropDesc (0x1014) 的解析结果。
 *
 * 布局（ISO 15740 / ILFforever 文档）：
 *   u16 propertyCode | u16 dataType | u8 getSet(1=可写)
 *   value factoryDefault | value current
 *   u8 form (0=none, 1=range, 2=enumeration)
 *   form 1: value min, value max, value step
 *   form 2: u16 count, then count 个 value
 *
 * 这是**唯一权威的"这台相机允许什么值、步进多少"**来源，用来替代硬编码猜测
 * （比如高光/阴影到底是 1 还是 0.5 步进、白平衡有哪些档位、胶片模拟枚举到几）。
 */
public final class PropDesc {

    public static final int FORM_NONE = 0, FORM_RANGE = 1, FORM_ENUM = 2;

    public final int code;
    public final int dataType;
    public final int getSet;
    public final int defaultValue;
    public final int currentValue;
    public final int form;
    public final int min, max, step;
    public final int[] values;

    private PropDesc(int code, int dataType, int getSet, int def, int cur,
                     int form, int min, int max, int step, int[] values) {
        this.code = code; this.dataType = dataType; this.getSet = getSet;
        this.defaultValue = def; this.currentValue = cur;
        this.form = form; this.min = min; this.max = max; this.step = step;
        this.values = values == null ? new int[0] : values;
    }

    public boolean isRange() { return form == FORM_RANGE; }
    public boolean isEnum() { return form == FORM_ENUM; }
    public boolean writable() { return (getSet & 0x01) != 0; }

    public boolean hasEnumValue(int v) {
        for (int x : values) if (x == v) return true;
        return false;
    }

    public static PropDesc parse(int code, byte[] d) throws IOException {
        if (d == null || d.length < 5) throw new IOException("PropDesc 太短");
        int[] p = {0};
        int c = u16(d, p);
        int type = u16(d, p);
        int getSet = d[p[0]++] & 0xFF;
        int size = valueSize(type);
        int def = (int) readValue(d, p, size, type);
        int cur = (int) readValue(d, p, size, type);
        int form = d[p[0]++] & 0xFF;
        int mn = 0, mx = 0, st = 0;
        int[] vals = new int[0];
        if (form == FORM_RANGE) {
            mn = (int) readValue(d, p, size, type);
            mx = (int) readValue(d, p, size, type);
            st = (int) readValue(d, p, size, type);
        } else if (form == FORM_ENUM) {
            int n = u16(d, p);
            if (n < 0 || n > 4096) throw new IOException("枚举数量异常 " + n);
            vals = new int[n];
            for (int i = 0; i < n; i++) vals[i] = (int) readValue(d, p, size, type);
        }
        return new PropDesc(c == 0 ? code : c, type, getSet, def, cur, form, mn, mx, st, vals);
    }

    /** PTP 数据类型 → 字节数 */
    private static int valueSize(int type) {
        switch (type) {
            case 0x0001: return 1;   // INT8
            case 0x0002: return 1;   // UINT8
            case 0x0003: return 2;   // INT16
            case 0x0004: return 2;   // UINT16
            case 0x0005: return 4;   // INT32
            case 0x0006: return 4;   // UINT32
            case 0x0007: return 8;   // INT64
            case 0x0008: return 8;   // UINT64
            default:     return 2;   // 0xFFFF = string 等，按 2 处理（只用于跳过）
        }
    }

    private static long readValue(byte[] d, int[] p, int size, int type) throws IOException {
        if (p[0] + size > d.length) throw new IOException("PropDesc 值越界");
        long v;
        switch (size) {
            case 1: v = type == 0x0001 ? d[p[0]] : (d[p[0]] & 0xFFL); break;
            case 2: {
                int u = UsbPtp.le16(d, p[0]);
                v = type == 0x0003 ? (short) u : u;
                break;
            }
            case 4: {
                int u = UsbPtp.le32(d, p[0]);
                v = type == 0x0005 ? u : (u & 0xFFFFFFFFL);
                break;
            }
            default: {
                long lo = UsbPtp.le32(d, p[0]) & 0xFFFFFFFFL;
                long hi = UsbPtp.le32(d, p[0] + 4) & 0xFFFFFFFFL;
                v = lo | (hi << 32);
                break;
            }
        }
        p[0] += size;
        return v;
    }

    private static int u16(byte[] d, int[] p) throws IOException {
        if (p[0] + 2 > d.length) throw new IOException("PropDesc 越界");
        int v = UsbPtp.le16(d, p[0]);
        p[0] += 2;
        return v;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("0x%04X type=0x%04X %s cur=%d ", code, dataType, writable() ? "rw" : "ro", currentValue));
        if (isRange()) sb.append(String.format("range[%d..%d step %d]", min, max, step));
        else if (isEnum()) {
            sb.append("enum{");
            for (int i = 0; i < Math.min(values.length, 40); i++) {
                if (i > 0) sb.append(',');
                sb.append(values[i]);
            }
            sb.append('}');
        } else sb.append("form=none");
        return sb.toString();
    }
}
