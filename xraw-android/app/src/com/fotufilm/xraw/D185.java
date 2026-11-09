package com.fotufilm.xraw;

import java.io.IOException;

/**
 * d185 = DevicePropValue 0xD185 (RawConvProfile) 的二进制结构体，即「一套配方」在 USB 线上的真实形态。
 *
 * 布局（libfuji fp/src/d185.c 实测）：
 *   0x000 u16 propCount(0x1D)  0x002 u8 strLen  0x003 UTF-16LE IOPCode  0x201.. 29 × u32(LE)
 *   总长 0x275(629)。相机回读时常为 601 字节，因此只用「读回→patch→回写」，绝不从零构造。
 *
 * 全部取值表来自 libfuji fp/src/fp.h + data.c（X-M5 = X-Processor 5，高光/阴影用 0.5 步进 ×10）。
 */
public final class D185 {
    public static final int PROPS_OFFSET = 0x201;
    public static final int PROP_COUNT = 0x1D;
    public static final int MIN_SIZE = 0x274;

    // 字段索引（libfuji enum D185Props）
    public static final int IDX_SHOOTING_COND = 0;
    public static final int IDX_FILE_TYPE = 1;
    public static final int IDX_IMAGE_SIZE = 2;
    public static final int IDX_IMAGE_QUALITY = 3;
    public static final int IDX_EXPOSURE_BIAS = 4;
    public static final int IDX_DYNAMIC_RANGE = 5;
    public static final int IDX_WIDE_DRANGE = 6;
    public static final int IDX_FILM_SIM = 7;
    public static final int IDX_GRAIN = 8;
    public static final int IDX_CHROME = 9;
    public static final int IDX_WB_SHOOT_COND = 10;
    public static final int IDX_WB_MODE = 11;
    public static final int IDX_WB_SHIFT_R = 12;
    public static final int IDX_WB_SHIFT_B = 13;
    public static final int IDX_WB_COLOR_TEMP = 14;
    public static final int IDX_HIGHLIGHT = 15;
    public static final int IDX_SHADOW = 16;
    public static final int IDX_COLOR = 17;
    public static final int IDX_SHARPNESS = 18;
    public static final int IDX_NOISE_RED = 19;
    public static final int IDX_LMO = 20;
    public static final int IDX_COLOR_SPACE = 21;
    public static final int IDX_BLACK_IMAGE_TONE = 22;
    public static final int IDX_UNKNOWN_23 = 23;
    public static final int IDX_FX_BLUE = 24;
    public static final int IDX_MONO_RG = 25;
    public static final int IDX_CLARITY = 26;
    public static final int IDX_UNKNOWN_28 = 27;
    public static final int IDX_UNKNOWN_29 = 28;

    // ---- 取值表 ----
    public static final int FILE_JPG = 7, FILE_TIF_8 = 9, FILE_TIF_16 = 11;
    public static final int SIZE_L3x2 = 0x7, SIZE_L16x9 = 0x8, SIZE_L1x1 = 0x9;
    public static final int SIZE_M3x2 = 0x4, SIZE_M16x9 = 0x5, SIZE_M1x1 = 0x6;
    public static final int SIZE_S3x2 = 0x1, SIZE_S16x9 = 0x2, SIZE_S1x1 = 0x3;
    public static final int QUALITY_FINE = 0x2, QUALITY_NORMAL = 0x3;
    public static final int DR_100 = 100, DR_200 = 200, DR_400 = 400, DR_HDR_800 = 800;
    public static final int DRP_OFF = 0, DRP_WEAK = 1, DRP_STRONG = 2;
    public static final int GRAIN_OFF = 1, GRAIN_WEAK = 2, GRAIN_STRONG = 3, GRAIN_SIZE_LARGE = 2;
    public static final int CHROME_OFF = 1, CHROME_WEAK = 2, CHROME_STRONG = 3;
    public static final int FXB_OFF = 0, FXB_WEAK = 1, FXB_STRONG = 2;
    public static final int LMO_ON = 1, LMO_OFF = 2;
    public static final int CS_SRGB = 1, CS_ADOBE = 3;
    public static final int WB_AS_SHOT = 0, WB_AUTO = 0x2, WB_DAYLIGHT = 0x4, WB_INCANDESCENT = 0x6,
            WB_UNDERWATER = 0x8, WB_FL1 = 0x8001, WB_FL2 = 0x8002, WB_FL3 = 0x8003,
            WB_SHADE = 0x8006, WB_TEMPERATURE = 0x8007,
            WB_CUSTOM1 = 0x8008, WB_CUSTOM2 = 0x8009, WB_CUSTOM3 = 0x800a;
    public static final int WB_AUTO_WHITE_PRIORITY = 0x8020, WB_AUTO_AMBIENCE_PRIORITY = 0x8021;
    public static final int WB_SHOOT_COND_ON = 1, WB_SHOOT_COND_OFF = 2;

    /** 机内为整数刻度的参数（X-M5：高光/阴影 -2..+4、色彩/锐度 -4..+4、清晰度 -5..+5），线上统一 ×10 */
    public static final int TONE_MIN = -2, TONE_MAX = 4;
    public static final int COLOR_MIN = -4, COLOR_MAX = 4;
    public static final int CLARITY_MIN = -5, CLARITY_MAX = 5;

    /** 降噪：非线性编码（0x0/0x1000.../0x8000），顺序为 +4..-4 */
    public static final int[] NR_VALUES = {0x5000, 0x6000, 0x0, 0x1000, 0x2000, 0x3000, 0x4000, 0x7000, 0x8000};

    /**
     * 胶片模拟（idx7）。数值 1..20 来自社区交叉验证（Filmcase / ILFforever），
     * 18 = ETERNA BLEACH BYPASS（即影友说的 BB 滤镜）、19 = 怀旧负片、20 = REALA ACE。
     * 数组顺序 = X-M5 机内菜单顺序（不是枚举顺序），UI 直接按此顺序展示。
     */
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

    /** 白平衡：数值来源 ILFforever 在 X-H2 上逐值写读验证（含 0x8020/0x8021 两档自动） */
    public static final String[] WB_NAMES = {
            "自动", "白色优先", "环境优先",
            "自定义 1", "自定义 2", "自定义 3",
            "色温 (K)", "日光", "阴天",
            "荧光灯 1", "荧光灯 2", "荧光灯 3",
            "白炽灯", "水下", "按拍摄时 (As Shot)"
    };
    public static final int[] WB_VALUES = {
            WB_AUTO, WB_AUTO_WHITE_PRIORITY, WB_AUTO_AMBIENCE_PRIORITY,
            WB_CUSTOM1, WB_CUSTOM2, WB_CUSTOM3,
            WB_TEMPERATURE, WB_DAYLIGHT, WB_SHADE,
            WB_FL1, WB_FL2, WB_FL3,
            WB_INCANDESCENT, WB_UNDERWATER, WB_AS_SHOT
    };
    public static final String[] IMAGE_SIZE_NAMES = {
            "L 3:2", "L 16:9", "L 1:1", "M 3:2", "M 16:9", "M 1:1", "S 3:2", "S 16:9", "S 1:1"
    };
    public static final int[] IMAGE_SIZE_VALUES = {
            SIZE_L3x2, SIZE_L16x9, SIZE_L1x1, SIZE_M3x2, SIZE_M16x9, SIZE_M1x1,
            SIZE_S3x2, SIZE_S16x9, SIZE_S1x1
    };
    public static final String[] QUALITY_NAMES = {"FINE（精细）", "NORMAL（标准）"};
    public static final int[] QUALITY_VALUES = {QUALITY_FINE, QUALITY_NORMAL};
    public static final String[] FILE_TYPE_NAMES = {"JPEG", "TIFF 8bit", "TIFF 16bit"};
    public static final int[] FILE_TYPE_VALUES = {FILE_JPG, FILE_TIF_8, FILE_TIF_16};
    public static final String[] COLOR_SPACE_NAMES = {"sRGB", "AdobeRGB"};
    public static final int[] COLOR_SPACE_VALUES = {CS_SRGB, CS_ADOBE};

    private final byte[] b;
    private final int iopCode;

    private D185(byte[] raw) { this.b = raw; this.iopCode = parseIopCode(raw); }

    public static D185 parse(byte[] raw) throws IOException {
        if (raw.length < MIN_SIZE) throw new IOException("d185 太短：" + raw.length);
        int count = UsbPtp.le16(raw, 0);
        if (count <= 0 || count > 0x40) throw new IOException("d185 属性数量异常：" + count);
        return new D185(raw.clone());
    }

    private static int parseIopCode(byte[] raw) {
        int n = raw[2] & 0xFF;
        int p = 3;
        int code = 0;
        for (int i = 0; i < n && p + 1 < raw.length; i++) {
            int wc = UsbPtp.le16(raw, p);
            p += 2;
            if (wc == 0) break;
            int digit;
            if (wc >= '0' && wc <= '9') digit = wc - '0';
            else if (wc >= 'A' && wc <= 'F') digit = wc - 'A' + 10;
            else if (wc >= 'a' && wc <= 'f') digit = wc - 'a' + 10;
            else break;
            if (i > 8) break;
            code = (code << 4) + digit;
        }
        return code;
    }

    public int iopCode() { return iopCode; }

    /** X-Processor 5（X-M5 属于此类）：高光/阴影支持 -2.0..+4.0 的 0.5 步进 */
    public boolean isXProcessor5() { return (iopCode & 0x00ffff00) == 0x00179500; }

    public byte[] raw() { return b.clone(); }
    public int size() { return b.length; }

    public int get(int idx) {
        int off = PROPS_OFFSET + idx * 4;
        return UsbPtp.le32(b, off);
    }

    public D185 set(int idx, int value) {
        int off = PROPS_OFFSET + idx * 4;
        if (off + 4 <= b.length) UsbPtp.put32(b, off, value);
        return this;
    }

    // ---------------- 语义读写 ----------------
    public int filmSim() { return get(IDX_FILM_SIM); }
    public int dr() { return get(IDX_DYNAMIC_RANGE); }
    public int drPriority() { return get(IDX_WIDE_DRANGE); }
    public int wbMode() { return get(IDX_WB_MODE); }
    public int fileType() { return get(IDX_FILE_TYPE); }
    public int imageSize() { return get(IDX_IMAGE_SIZE); }

    public boolean isMono() {
        int s = filmSim();
        for (int i = 0; i < SIM_VALUES.length; i++) if (SIM_VALUES[i] == s) return SIM_MONO[i];
        return false;
    }

    public static String simName(int value) {
        for (int i = 0; i < SIM_VALUES.length; i++) if (SIM_VALUES[i] == value) return SIM_NAMES[i];
        return "Sim#" + value;
    }

    public static int nrFromStep(int step) { // step -4..+4
        int i = 4 - step;                      // +4 → 0, 0 → 4, -4 → 8
        i = Math.max(0, Math.min(NR_VALUES.length - 1, i));
        return NR_VALUES[i];
    }

    public static int nrToStep(int value) {
        for (int i = 0; i < NR_VALUES.length; i++) if (NR_VALUES[i] == value) return 4 - i;
        return 0;
    }

    /**
     * 抑制/夹取规则（来自 rawji 的实测注释）：
     *  1) DR 优先非 OFF：相机拒绝写 DR(5)，并清空高光/阴影 → 我们不动这两项。
     *  2) 单色模拟：不写彩色相关字段。
     *  3) 色温仅在 WB=0x8007 有意义。
     *  4) DR 不得高于原 RAF 拍摄时的 DR。
     */
    public D185 sanitize(int originalRafDr) {
        if (originalRafDr > 0 && drPriority() == DRP_OFF) {
            int dr = dr();
            if (dr != DR_100 && dr != DR_200 && dr != DR_400) set(IDX_DYNAMIC_RANGE, originalRafDr);
            else if (dr > originalRafDr) set(IDX_DYNAMIC_RANGE, originalRafDr);
        }
        set(IDX_HIGHLIGHT, clamp(get(IDX_HIGHLIGHT), -20, 40));
        set(IDX_SHADOW, clamp(get(IDX_SHADOW), -20, 40));
        set(IDX_CLARITY, clamp(get(IDX_CLARITY), -50, 50));
        if (!isMono()) {
            set(IDX_COLOR, clamp(get(IDX_COLOR), -40, 40));
            set(IDX_SHARPNESS, clamp(get(IDX_SHARPNESS), -40, 40));
            set(IDX_WB_SHIFT_R, clamp(get(IDX_WB_SHIFT_R), -90, 90));
            set(IDX_WB_SHIFT_B, clamp(get(IDX_WB_SHIFT_B), -90, 90));
        }
        if (wbMode() == WB_TEMPERATURE) set(IDX_WB_COLOR_TEMP, clamp(get(IDX_WB_COLOR_TEMP), 2500, 10000));
        return this;
    }

    public static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(simName(filmSim()));
        if (drPriority() != DRP_OFF) sb.append(" · DR优先");
        else sb.append(" · DR").append(dr());
        sb.append(" · 高光").append(oneDp(get(IDX_HIGHLIGHT)));
        sb.append(" 阴影").append(oneDp(get(IDX_SHADOW)));
        if (!isMono()) {
            sb.append(" · 色彩").append(get(IDX_COLOR) / 10f);
            sb.append(" 锐度").append(get(IDX_SHARPNESS) / 10f);
        }
        if (get(IDX_CLARITY) != 0) sb.append(" 清晰度").append(get(IDX_CLARITY) / 10f);
        sb.append(" · ").append(fileTypeName(fileType()));
        return sb.toString();
    }

    public static String fileTypeName(int v) {
        for (int i = 0; i < FILE_TYPE_VALUES.length; i++) if (FILE_TYPE_VALUES[i] == v) return FILE_TYPE_NAMES[i];
        return "FMT#" + v;
    }

    private static String oneDp(int tenths) { return String.format(java.util.Locale.US, "%.1f", tenths / 10.0); }

    /** 用于预览缓存 key */
    public String paramHash() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROP_COUNT; i++) sb.append(Integer.toHexString(get(i))).append(',');
        return sb.toString();
    }

    public String hexDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("len=").append(b.length)
                .append(" iop=0x").append(Integer.toHexString(iopCode))
                .append(" xp5=").append(isXProcessor5())
                .append('\n');
        for (int i = 0; i < PROP_COUNT; i++) {
            sb.append(String.format(java.util.Locale.US, "  [%02d] %10d (0x%08X)%n", i, get(i), get(i)));
        }
        return sb.toString();
    }
}
