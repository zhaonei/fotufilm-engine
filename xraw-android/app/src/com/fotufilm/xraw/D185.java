package com.fotufilm.xraw;

import java.io.IOException;

/**
 * d185（0xD185 RawConvProfile）——相机的"一整套转换参数"。
 *
 * 底层由 C 实现（{@link FpNative}，jni/fp_d185.c，布局对齐 petabyt/libfuji fp/src/d185.c）：
 *   0x000 u16 nProps=29 / 0x002 u8 strlen / 0x003 UTF-16LE IOPCode / 0x201 起 29×u32 / 共 629 字节
 *
 * 所有参数**一次性打包**成这个结构体写回相机，不再逐个属性硬拼。
 * 字段索引采用 libfuji `D185Props` 顺序（已用真机 629 字节包核对：
 *   [0]=ShootingCond=2, [1]=FileType=7(JPG), [2]=ImageSize=7(L3:2), [3]=ImageQuality=2(FINE),
 *   [5]=DR=100, [7]=FilmSim, [8]=Grain(1=Off), [9]=Chrome(1=Off), [10]=WBShootCond=1(AsShot),
 *   [19]=NR=0x8000 哨兵，与语义完全吻合）。
 */
public final class D185 {
    public static final int PROPS = FpNative.PROPS;

    public static final class Idx {
        public static final int SHOOTING_COND = 0;
        public static final int FILE_TYPE = 1;
        public static final int IMAGE_SIZE = 2;
        public static final int IMAGE_QUALITY = 3;
        public static final int EXPOSURE_BIAS = 4;
        public static final int DYNAMIC_RANGE = 5;
        public static final int WIDE_DRANGE = 6;
        public static final int FILM_SIM = 7;
        public static final int GRAIN = 8;
        public static final int CHROME = 9;
        public static final int WB_SHOOT_COND = 10;
        public static final int WB_MODE = 11;
        public static final int WB_SHIFT_R = 12;
        public static final int WB_SHIFT_B = 13;
        public static final int WB_COLOR_TEMP = 14;
        public static final int HIGHLIGHT = 15;
        public static final int SHADOW = 16;
        public static final int COLOR = 17;
        public static final int SHARPNESS = 18;
        public static final int NOISE_RED = 19;
        public static final int LMO = 20;
        public static final int COLOR_SPACE = 21;
        public static final int BLACK_TONE = 22;      // 单色 WC
        public static final int UNKNOWN_23 = 23;
        public static final int FX_BLUE = 24;
        public static final int MONO_RG = 25;         // 单色 MG
        public static final int CLARITY = 26;
        public static final int UNKNOWN_27 = 27;
        public static final int UNKNOWN_28 = 28;
    }

    // ---- 取值 ----
    public static final int DR_100 = 100, DR_200 = 200, DR_400 = 400;
    public static final int DRP_OFF = 0, DRP_WEAK = 1, DRP_STRONG = 2;

    /** 颗粒：libfuji 复合字段（强度 + 大小） */
    public static final int GRAIN_OFF = 1, GRAIN_WEAK = 2, GRAIN_STRONG = 3, GRAIN_SIZE_LARGE = 2;

    /** 效果类：1-indexed（1=Off 2=Weak 3=Strong） */
    public static final int EFF_OFF = 1, EFF_WEAK = 2, EFF_STRONG = 3;

    /** 降噪专有码表（-4..+4） */
    public static final int[] NR_ENCODE = {0x8000, 0x7000, 0x4000, 0x3000, 0x2000, 0x1000, 0x0000, 0x6000, 0x5000};

    public static final int WB_SHOOT_ON = 1, WB_SHOOT_OFF = 2;
    public static final int WB_AS_SHOT = 0, WB_AUTO = 0x2, WB_AUTO_WHITE = 0x8020,
            WB_AUTO_AMBIENCE = 0x8021, WB_DAYLIGHT = 0x4, WB_INCANDESCENT = 0x6,
            WB_UNDERWATER = 0x8, WB_FL1 = 0x8001, WB_FL2 = 0x8002, WB_FL3 = 0x8003,
            WB_SHADE = 0x8006, WB_TEMPERATURE = 0x8007,
            WB_CUSTOM1 = 0x8008, WB_CUSTOM2 = 0x8009, WB_CUSTOM3 = 0x800a;

    public static final int[] SIM_VALUES = {1, 2, 3, 11, 20, 4, 5, 17, 19, 16, 18, 12, 13, 14, 15, 6, 7, 8, 9, 10};
    public static final String[] SIM_NAMES = {
            "PROVIA/标准", "Velvia/鲜艳", "ASTIA/柔和", "CLASSIC CHROME", "REALA ACE",
            "PRO Neg. Hi", "PRO Neg. Std", "CLASSIC Neg.", "NOSTALGIC Neg.",
            "ETERNA/电影", "ETERNA BLEACH BYPASS (BB)",
            "ACROS", "ACROS+Ye", "ACROS+R", "ACROS+G",
            "黑白", "黑白+Ye", "黑白+R", "黑白+G", "棕褐色"
    };
    private static final boolean[] SIM_MONO = {
            false, false, false, false, false, false, false, false, false, false, false,
            true, true, true, true, true, true, true, true, true
    };

    public static final String[] WB_NAMES = {
            "自动", "白色优先", "环境优先", "自定义 1", "自定义 2", "自定义 3",
            "色温 (K)", "日光", "阴天", "荧光灯 1", "荧光灯 2", "荧光灯 3",
            "白炽灯", "水下", "按拍摄时 (As Shot)"
    };
    public static final int[] WB_VALUES = {
            WB_AUTO, WB_AUTO_WHITE, WB_AUTO_AMBIENCE, WB_CUSTOM1, WB_CUSTOM2, WB_CUSTOM3,
            WB_TEMPERATURE, WB_DAYLIGHT, WB_SHADE, WB_FL1, WB_FL2, WB_FL3,
            WB_INCANDESCENT, WB_UNDERWATER, WB_AS_SHOT
    };

    private final int[] v = new int[PROPS];
    /** 相机给的原始 629 字节包：所有修改都在它上面**原地**改写，绝不重建 */
    private final byte[] pack;
    private final int iopCode;
    private final int rawPropCount;
    private final int rawLength;

    private D185(int[] values, byte[] pack, int iop, int propCount, int length) {
        System.arraycopy(values, 0, v, 0, PROPS);
        this.pack = pack;
        this.iopCode = iop;
        this.rawPropCount = propCount;
        this.rawLength = length;
    }

    public static D185 parse(byte[] raw) throws IOException {
        if (raw == null || raw.length < 64) throw new IOException("d185 太短：" + (raw == null ? 0 : raw.length));
        int[] r = FpNative.parse(raw);
        int[] vals = new int[PROPS];
        System.arraycopy(r, 0, vals, 0, PROPS);
        return new D185(vals, raw.clone(), r[PROPS], r[PROPS + 1], raw.length);
    }

    /**
     * 要写回相机的包：**保留相机原始字节**，只把 29 个字段按当前值覆盖。
     * IOPCode 区、未知字段、尾部数据全部原样保留——这正是 filmkit 的 patchProfile 做法。
     * （早前用 C 层从 29 个字段重建整包，会把未知区清零，导致相机整体偏色。）
     */
    public byte[] create() {
        byte[] out = pack.clone();
        int base = FpNative.PROPS_OFFSET;
        if (base + PROPS * 4 > out.length) return FpNative.create(v, iopCode);   // 兜底
        for (int i = 0; i < PROPS; i++) UsbPtp.put32(out, base + i * 4, v[i]);
        return out;
    }

    public D185 copy() {
        D185 d = new D185(v, pack.clone(), iopCode, rawPropCount, rawLength);
        return d;
    }

    public int get(int idx) { return (idx >= 0 && idx < PROPS) ? v[idx] : 0; }
    public D185 set(int idx, int value) { if (idx >= 0 && idx < PROPS) v[idx] = value; return this; }

    /** 相机原始包（只读副本） */
    public byte[] rawPack() { return pack.clone(); }
    public int[] values() { return v; }
    public int iopCode() { return iopCode; }
    public int rawPropCount() { return rawPropCount; }
    public int rawLength() { return rawLength; }

    // ---------------- 语义 ----------------
    public int filmSim() { return get(Idx.FILM_SIM); }
    public int dr() { return get(Idx.DYNAMIC_RANGE); }
    public int drPriority() { return get(Idx.WIDE_DRANGE); }
    public int wbMode() { return get(Idx.WB_MODE); }
    public int wbShootCond() { return get(Idx.WB_SHOOT_COND); }
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

    /** 色调/色彩/锐度/清晰度线上是 ×10（有符号 16 位） */
    public static int decodeTenths(int raw) {
        if ((raw & 0xFFFF) == 0x8000) return 0;     // 哨兵：沿用 EXIF
        return ((short) (raw & 0xFFFF)) / 10;
    }

    public static int encodeTenths(int dial) { return dial * 10; }

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

    private static String signed(int x) { return x > 0 ? "+" + x : String.valueOf(x); }

    public String hexDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("len=").append(rawLength)
                .append(" nProps=").append(rawPropCount)
                .append(" iop=0x").append(Integer.toHexString(iopCode))
                .append(" native=").append(FpNative.available()).append('\n');
        for (int i = 0; i < PROPS; i++) {
            sb.append(String.format(java.util.Locale.US, "  [%02d] %11d (0x%08X)%n", i, get(i), get(i)));
        }
        return sb.toString();
    }
}
