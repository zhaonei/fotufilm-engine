package com.fotufilm.xraw;

import java.io.IOException;

/**
 * d185（DevicePropValue 0xD185 RawConvProfile）—— 相机原生"配方结构体"。
 *
 * 关键点全部来自 filmkit（WebUSB 版，在同一类 X-Processor 5 机身上实测通过）：
 *
 *  1) **属性区偏移是动态的**：numParams = u16@0，propsOffset = 总长度 - numParams*4。
 *     相机返回 601/625/629 字节不等，硬编码 0x201 会整体写偏 4 字节——这是之前
 *     "参数写了但不起作用/步进不对"的根本原因。
 *  2) **字段索引与 libfuji/rawji 的写入格式不同**（整体错位约 1 格），见 NativeIdx。
 *  3) 编码：效果类 1-indexed（1=Off,2=Weak,3=Strong）；颗粒是扁平枚举 1..5；
 *     动态范围是百分比原值；色调/色彩/锐度/清晰度是 ×10；降噪是专有码表；
 *     WB 偏移是直接刻度值。
 *
 * 铁律不变：**读回相机给的档 → 只 patch 已知字段 → 原样回写**。
 */
public final class D185 {

    /** 原生字段索引（filmkit NativeIdx，X100VI/X-Processor 5 实测） */
    public static final class Idx {
        public static final int EXPOSURE_BIAS = 4;
        public static final int DYNAMIC_RANGE = 6;
        public static final int WIDE_DRANGE = 7;
        public static final int FILM_SIM = 8;
        public static final int GRAIN = 9;
        public static final int CHROME = 10;
        public static final int SMOOTH_SKIN = 11;
        public static final int WB_MODE = 12;
        public static final int WB_SHIFT_R = 13;
        public static final int WB_SHIFT_B = 14;
        public static final int WB_COLOR_TEMP = 15;
        public static final int HIGHLIGHT = 16;
        public static final int SHADOW = 17;
        public static final int COLOR = 18;
        public static final int SHARPNESS = 19;
        public static final int NOISE_RED = 20;
        public static final int FX_BLUE = 25;
        public static final int CLARITY = 27;
    }

    // ---- 取值 ----
    public static final int DR_100 = 100, DR_200 = 200, DR_400 = 400;
    public static final int DRP_OFF = 0, DRP_WEAK = 1, DRP_STRONG = 2;

    /** 颗粒：原生扁平枚举 */
    public static final int GRAIN_OFF = 1, GRAIN_WEAK_SMALL = 2, GRAIN_STRONG_SMALL = 3,
            GRAIN_WEAK_LARGE = 4, GRAIN_STRONG_LARGE = 5;

    /** 效果类：原生 1-indexed */
    public static final int EFF_OFF = 1, EFF_WEAK = 2, EFF_STRONG = 3;

    /** 降噪：专有码表（-4..+4），与 filmkit NR_ENCODE / libfuji 一致 */
    public static final int[] NR_ENCODE = {
            0x8000, // -4
            0x7000, // -3
            0x4000, // -2
            0x3000, // -1
            0x2000, //  0
            0x1000, // +1
            0x0000, // +2
            0x6000, // +3
            0x5000, // +4
    };

    public static final int WB_AS_SHOT = 0, WB_AUTO = 0x2, WB_AUTO_WHITE = 0x8020,
            WB_AUTO_AMBIENCE = 0x8021, WB_DAYLIGHT = 0x4, WB_INCANDESCENT = 0x6,
            WB_UNDERWATER = 0x8, WB_FL1 = 0x8001, WB_FL2 = 0x8002, WB_FL3 = 0x8003,
            WB_SHADE = 0x8006, WB_TEMPERATURE = 0x8007,
            WB_CUSTOM1 = 0x8008, WB_CUSTOM2 = 0x8009, WB_CUSTOM3 = 0x800a;

    /** 胶片模拟（数值 1..20；顺序 = X-M5 机内菜单顺序） */
    public static final int[] SIM_VALUES = {1, 2, 3, 11, 20, 4, 5, 17, 19, 16, 18, 12, 13, 14, 15, 6, 7, 8, 9, 10};
    public static final String[] SIM_NAMES = {
            "PROVIA/标准", "Velvia/鲜艳", "ASTIA/柔和", "CLASSIC CHROME", "REALA ACE",
            "PRO Neg. Hi", "PRO Neg. Std", "CLASSIC Neg.", "NOSTALGIC Neg.",
            "ETERNA/电影", "ETERNA BLEACH BYPASS (BB)",
            "ACROS", "ACROS+Ye", "ACROS+R", "ACROS+G",
            "黑白", "黑白+Ye", "黑白+R", "黑白+G", "棕褐色"
    };
    private static final boolean[] SIM_MONO = {
            false, false, false, false, false,
            false, false, false, false,
            false, false,
            true, true, true, true,
            true, true, true, true, true
    };

    /** 白平衡（含 白色优先 / 环境优先，新增"按拍摄时"=0 表示沿用 EXIF） */
    public static final String[] WB_NAMES = {
            "自动", "白色优先", "环境优先",
            "自定义 1", "自定义 2", "自定义 3",
            "色温 (K)", "日光", "阴天",
            "荧光灯 1", "荧光灯 2", "荧光灯 3",
            "白炽灯", "水下", "按拍摄时 (As Shot)"
    };
    public static final int[] WB_VALUES = {
            WB_AUTO, WB_AUTO_WHITE, WB_AUTO_AMBIENCE,
            WB_CUSTOM1, WB_CUSTOM2, WB_CUSTOM3,
            WB_TEMPERATURE, WB_DAYLIGHT, WB_SHADE,
            WB_FL1, WB_FL2, WB_FL3,
            WB_INCANDESCENT, WB_UNDERWATER, WB_AS_SHOT
    };

    private final byte[] b;
    private final int propCount;
    private final int propsOffset;
    private final int iopCode;

    private D185(byte[] raw) throws IOException {
        this.b = raw;
        this.propCount = UsbPtp.le16(raw, 0);
        if (propCount <= 0 || propCount > 0x40) throw new IOException("d185 属性数量异常：" + propCount);
        this.propsOffset = raw.length - propCount * 4;
        if (propsOffset < 3) throw new IOException("d185 长度与属性数量不匹配：" + raw.length + "/" + propCount);
        this.iopCode = parseIopCode(raw);
    }

    public static D185 parse(byte[] raw) throws IOException {
        if (raw.length < 64) throw new IOException("d185 太短：" + raw.length);
        return new D185(raw.clone());
    }

    private static int parseIopCode(byte[] raw) {
        int n = raw[2] & 0xFF;
        int p = 3, code = 0;
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
            code = (code << 4) + d;
        }
        return code;
    }

    public int iopCode() { return iopCode; }
    public int propCount() { return propCount; }
    public int propsOffset() { return propsOffset; }
    public int size() { return b.length; }
    public byte[] raw() { return b.clone(); }

    public int get(int idx) {
        int off = propsOffset + idx * 4;
        return (off + 4 <= b.length) ? UsbPtp.le32(b, off) : 0;
    }

    public D185 set(int idx, int value) {
        int off = propsOffset + idx * 4;
        if (off + 4 <= b.length) UsbPtp.put32(b, off, value);
        return this;
    }

    // ---------------- 语义访问 ----------------
    public int filmSim() { return get(Idx.FILM_SIM); }
    public int dr() { return get(Idx.DYNAMIC_RANGE); }
    public int drPriority() { return get(Idx.WIDE_DRANGE); }
    public int wbMode() { return get(Idx.WB_MODE); }
    public int exposureBias() { return get(Idx.EXPOSURE_BIAS); }

    public boolean isMono() {
        int s = filmSim();
        for (int i = 0; i < SIM_VALUES.length; i++) if (SIM_VALUES[i] == s) return SIM_MONO[i];
        return false;
    }

    public static String simName(int value) {
        for (int i = 0; i < SIM_VALUES.length; i++) if (SIM_VALUES[i] == value) return SIM_NAMES[i];
        return "Sim#" + value;
    }

    public static int nrEncode(int step) {
        int i = step + 4;
        return (i >= 0 && i < NR_ENCODE.length) ? NR_ENCODE[i] : 0x2000;
    }

    public static int nrDecode(int raw) {
        int u = raw & 0xFFFF;
        for (int i = 0; i < NR_ENCODE.length; i++) if ((NR_ENCODE[i] & 0xFFFF) == u) return i - 4;
        return 0;
    }

    /** tone/color/sharpness/clarity 都是 ×10，回读时用这个还原有符号值 */
    public static int decodeTenths(int raw) {
        if (raw == 0x8000 || raw == -32768) return 0;   // 哨兵：沿用 EXIF
        short s = (short) (raw & 0xFFFF);
        return s / 10;
    }

    /** 量程保护：只夹取我们确实要写的字段 */
    public D185 sanitize() {
        int hs = (short) (get(Idx.HIGHLIGHT) & 0xFFFF);
        int ss = (short) (get(Idx.SHADOW) & 0xFFFF);
        set(Idx.HIGHLIGHT, clamp(hs, -40, 40));
        set(Idx.SHADOW, clamp(ss, -40, 40));
        return this;
    }

    public static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(simName(filmSim()));
        if (drPriority() != DRP_OFF) sb.append(" · DR优先").append(drPriority());
        else sb.append(" · DR").append(dr());
        sb.append(" · 高光").append(signed(decodeTenths(get(Idx.HIGHLIGHT))));
        sb.append(" 阴影").append(signed(decodeTenths(get(Idx.SHADOW))));
        if (!isMono()) {
            sb.append(" · 色彩").append(signed(decodeTenths(get(Idx.COLOR))));
            sb.append(" 锐度").append(signed(decodeTenths(get(Idx.SHARPNESS))));
        }
        sb.append(" 清晰度").append(signed(decodeTenths(get(Idx.CLARITY))));
        return sb.toString();
    }

    private static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    public String hexDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("len=").append(b.length)
                .append(" propCount=").append(propCount)
                .append(" propsOffset=").append(propsOffset)
                .append(" (0x").append(Integer.toHexString(propsOffset)).append(")")
                .append(" iop=0x").append(Integer.toHexString(iopCode)).append('\n');
        for (int i = 0; i < propCount; i++) {
            sb.append(String.format(java.util.Locale.US, "  [%02d] %11d (0x%08X)%n",
                    i, get(i), get(i)));
        }
        return sb.toString();
    }
}
