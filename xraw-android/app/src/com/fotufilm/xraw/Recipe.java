package com.fotufilm.xraw;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一套「配方」的 UI 模型 + **差量打包**逻辑。
 *
 * 设计要点（这是修掉"白平衡严重偏移/参数互斥错乱"的关键）：
 *   1. 从相机读回的原始档保存为 {@link #base}（29 字段基线）。
 *   2. 用户改哪个参数就只改对应字段；打包时拿 base 副本套用 UI 值，
 *      **只把与 base 不同的字段写进 d185**，其余原样保留。
 *   3. 于是「按拍摄时(As Shot)」这种哨兵状态永远不会被无意覆盖
 *      （以前把色温 5500 一起写回去，导致 WB 大幅偏移）。
 */
public final class Recipe {
    public String name = "未命名配方";

    private int[] base = new int[D185.PROPS];
    /** 基线白平衡：切到"色温"时用它兜底，绝不凭空猜一个 K 值（那会造成整幅偏色） */
    private int baseWbMode = 0;
    private int baseWbTemp = 0;
    /** 用户是否真的动过色温滑块（没动过就不写 K） */
    public transient boolean wbTempTouched = false;
    /** 本次打包发生的"降级/受限"说明，UI 会显示出来 */
    public transient String note = "";
    /** DR 已通过相机属性下发时置 true：profile 里就不再动 DR/优先两个字段 */
    public transient boolean skipDrInProfile = false;

    public int filmSim = 17;
    public int dr = D185.DR_100;
    public int drPriority = D185.DRP_OFF;
    public int grainStrength = 0;
    public boolean grainLarge = false;
    public int chrome = 0;
    public int fxBlue = 0;
    public int wbIndex = D185.WB_VALUES.length - 1;   // 默认「按拍摄时」
    public int wbTemp = 5500;
    public int wbR = 0, wbB = 0;
    public int highlight = 0, shadow = 0;        // 编码值：-20..+40（即 -2.0..+4.0，0.5 步进）
    public int color = 0, sharpness = 0;
    public int nr = 0;
    public int clarity = 0;
    /** 镜头调制优化器：D185.LMO_ON(1) / LMO_OFF(2)；实测 X-M5 基线为 2=关 */
    public int lmo = D185.LMO_OFF;
    public int expBiasThirds = 0;

    // 输出相关（字段表已锁定：idx1 FileType / idx2 ImageSize / idx3 ImageQuality / idx21 ColorSpace）
    public int imageSizeIdx = 0;     // 0..8 → L3:2 … S1:1
    public int qualityIdx = 0;       // 0=FINE 1=NORMAL
    public int fileTypeIdx = 0;      // 0=JPEG 1=TIFF8 2=TIFF16
    public int colorSpaceIdx = 0;    // 0=sRGB 1=AdobeRGB

    public static final String[] SIZE_NAMES = {"L 3:2","L 16:9","L 1:1","M 3:2","M 16:9","M 1:1","S 3:2","S 16:9","S 1:1"};
    public static final int[] SIZE_VALUES = {7,8,9,4,5,6,1,2,3};
    public static final String[] QUALITY_NAMES = {"FINE","NORMAL"};
    public static final int[] QUALITY_VALUES = {2,3};
    public static final String[] FILETYPE_NAMES = {"JPEG","TIFF 8bit","TIFF 16bit"};
    public static final int[] FILETYPE_VALUES = {7,9,11};
    public static final String[] CSPACE_NAMES = {"sRGB","AdobeRGB"};
    public static final int[] CSPACE_VALUES = {2,1};

    // ---------------------------------------------------------------- 读回

    /** 属性下发后，相机可能已自行更新 profile：同步基线，使差量仍然准确 */
    public void setBaseline(int[] values) {
        if (values == null || values.length < D185.PROPS) return;
        System.arraycopy(values, 0, base, 0, D185.PROPS);
    }

    public static Recipe fromD185(D185 p) {
        Recipe r = new Recipe();
        r.base = p.values().clone();

        r.filmSim = p.filmSim();
        r.expBiasThirds = Math.round(p.exposureBias() * 3f / 1000f);
        r.drPriority = p.drPriority();
        int dr = p.dr();
        r.dr = (dr == D185.DR_200 || dr == D185.DR_400) ? dr : D185.DR_100;

        // 文档(zgrimz/X-M5 实测)：Grain 写入用 1..5；Off 会回读成 6(小) 或 7(大)
        int g = p.get(D185.Idx.GRAIN);
        if (g >= 6) {                       // 6 = Off(小) / 7 = Off(大)
            r.grainStrength = 0;
            r.grainLarge = (g == 7);
        } else {
            r.grainLarge = g > D185.GRAIN_STRONG;
            r.grainStrength = Math.max(0, Math.min(2,
                    (r.grainLarge ? g - D185.GRAIN_SIZE_LARGE : g) - 1));
        }

        r.chrome = Math.max(0, Math.min(2, p.get(D185.Idx.CHROME) - 1));
        r.fxBlue = Math.max(0, Math.min(2, p.get(D185.Idx.FX_BLUE) - 1));

        // WB：WBShootCond=ON 表示沿用 RAW 的「按拍摄时」
        boolean asShot = p.wbShootCond() == D185.WB_SHOOT_ON;
        r.wbIndex = D185.WB_VALUES.length - 1;
        if (!asShot) {
            int wb = p.wbMode();
            for (int i = 0; i < D185.WB_VALUES.length; i++) {
                if (D185.WB_VALUES[i] == wb) { r.wbIndex = i; break; }
            }
        }
        r.baseWbMode = p.wbMode();
        r.baseWbTemp = p.get(D185.Idx.WB_COLOR_TEMP);
        r.wbTemp = r.baseWbTemp;                          // 保留原值（0 = 未使用）
        r.wbR = D185.clamp((short) (p.get(D185.Idx.WB_SHIFT_R) & 0xFFFF), -9, 9);
        r.wbB = D185.clamp((short) (p.get(D185.Idx.WB_SHIFT_B) & 0xFFFF), -9, 9);

        r.highlight = D185.clamp((short) (p.get(D185.Idx.HIGHLIGHT) & 0xFFFF), -20, 40);
        r.shadow = D185.clamp((short) (p.get(D185.Idx.SHADOW) & 0xFFFF), -20, 40);
        r.color = D185.clamp(D185.decodeTenths(p.get(D185.Idx.COLOR)), -4, 4);
        r.sharpness = D185.clamp(D185.decodeTenths(p.get(D185.Idx.SHARPNESS)), -4, 4);
        r.nr = D185.clamp(D185.nrDecode(p.get(D185.Idx.NOISE_RED)), -4, 4);
        r.clarity = D185.clamp(D185.decodeTenths(p.get(D185.Idx.CLARITY)), -5, 5);
        r.fileTypeIdx = indexOf(FILETYPE_VALUES, p.get(D185.Idx.FILE_TYPE));
        r.imageSizeIdx = indexOf(SIZE_VALUES, p.get(D185.Idx.IMAGE_SIZE));
        r.qualityIdx = indexOf(QUALITY_VALUES, p.get(D185.Idx.IMAGE_QUALITY));
        r.colorSpaceIdx = indexOf(CSPACE_VALUES, p.get(D185.Idx.COLOR_SPACE));
        return r;
    }

    // ---------------------------------------------------------------- 差量打包

    /** 把 UI 值套到 base 的副本上，**只把与 base 不同的字段**写进 d185 */
    public void applyTo(D185 p) {
        int[] want = base.clone();

        want[D185.Idx.FILM_SIM] = filmSim;
        want[D185.Idx.EXPOSURE_BIAS] = Math.round(expBiasThirds * 1000f / 3f);
        note = "";
        if (skipDrInProfile) {
            want[D185.Idx.WIDE_DRANGE] = base[D185.Idx.WIDE_DRANGE];
            want[D185.Idx.DYNAMIC_RANGE] = base[D185.Idx.DYNAMIC_RANGE];
            drPriority = base[D185.Idx.WIDE_DRANGE];
            dr = base[D185.Idx.DYNAMIC_RANGE];
            note = "DR 已由相机属性下发（profile 不重复写）";
        }
        want[D185.Idx.WIDE_DRANGE] = drPriority;
        if (drPriority == D185.DRP_OFF) {
            // 相机硬约束：DR 不得高于原 RAF 拍摄时的 DR
            int baseDr = base[D185.Idx.DYNAMIC_RANGE];
            if (baseDr == D185.DR_100 || baseDr == D185.DR_200 || baseDr == D185.DR_400) {
                if (dr > baseDr) {
                    note = "DR" + dr + " 高于原片 DR" + baseDr + "，已按原片收窄";
                    dr = baseDr;
                }
            }
            want[D185.Idx.DYNAMIC_RANGE] = dr;
        }

        int grain;
        if (grainStrength <= 0) grain = D185.GRAIN_OFF;
        else if (grainStrength == 1) grain = D185.GRAIN_WEAK + (grainLarge ? D185.GRAIN_SIZE_LARGE : 0);
        else grain = D185.GRAIN_STRONG + (grainLarge ? D185.GRAIN_SIZE_LARGE : 0);
        want[D185.Idx.GRAIN] = grain;

        // 效果类：若基线是 0（哨兵/未设置）且用户没改，保持 0，避免把"未设置"变成"关闭"
        want[D185.Idx.CHROME] = (chrome == 0 && base[D185.Idx.CHROME] == 0) ? 0 : chrome + 1;
        want[D185.Idx.FX_BLUE] = (fxBlue == 0 && base[D185.Idx.FX_BLUE] == 0) ? 0 : fxBlue + 1;

        // 白平衡：As Shot 时**完全不碰** WB 相关字段（保持相机原样）；
        // 这是"整体变绿"的根因：以前 AsShot 也会被写成"色温+乱值K"
        if (wbIndex == D185.WB_VALUES.length - 1) {
            want[D185.Idx.WB_SHOOT_COND] = base[D185.Idx.WB_SHOOT_COND];
            want[D185.Idx.WB_MODE] = base[D185.Idx.WB_MODE];
            want[D185.Idx.WB_COLOR_TEMP] = base[D185.Idx.WB_COLOR_TEMP];
            want[D185.Idx.WB_SHIFT_R] = base[D185.Idx.WB_SHIFT_R];
            want[D185.Idx.WB_SHIFT_B] = base[D185.Idx.WB_SHIFT_B];
        } else {
            int mode = D185.WB_VALUES[Math.max(0, Math.min(D185.WB_VALUES.length - 1, wbIndex))];
            want[D185.Idx.WB_SHOOT_COND] = D185.WB_SHOOT_OFF;
            want[D185.Idx.WB_MODE] = mode;
            want[D185.Idx.WB_SHIFT_R] = wbR;
            want[D185.Idx.WB_SHIFT_B] = wbB;
            if (mode == D185.WB_TEMPERATURE) {
                // K 值优先级：用户拖过的值 → 基线 K（若有效）→ 5500
                int k = wbTempTouched && wbTemp >= 2500 ? wbTemp
                        : (baseWbTemp >= 2500 ? baseWbTemp : 5500);
                want[D185.Idx.WB_COLOR_TEMP] = D185.clamp(k, 2500, 10000);
            } else {
                want[D185.Idx.WB_COLOR_TEMP] = base[D185.Idx.WB_COLOR_TEMP];
            }
        }

        if (drPriority == D185.DRP_OFF) {
            want[D185.Idx.HIGHLIGHT] = D185.clamp(highlight, -20, 40);
            want[D185.Idx.SHADOW] = D185.clamp(shadow, -20, 40);
        }
        want[D185.Idx.COLOR] = D185.encodeTenths(color);
        want[D185.Idx.SHARPNESS] = D185.encodeTenths(sharpness);
        want[D185.Idx.NOISE_RED] = D185.nrEncode(nr);
        want[D185.Idx.CLARITY] = D185.encodeTenths(clarity);
        want[D185.Idx.LMO] = lmo;
        want[D185.Idx.FILE_TYPE] = FILETYPE_VALUES[clampIdx(fileTypeIdx, FILETYPE_VALUES.length)];
        want[D185.Idx.IMAGE_SIZE] = SIZE_VALUES[clampIdx(imageSizeIdx, SIZE_VALUES.length)];
        want[D185.Idx.IMAGE_QUALITY] = QUALITY_VALUES[clampIdx(qualityIdx, QUALITY_VALUES.length)];
        want[D185.Idx.COLOR_SPACE] = CSPACE_VALUES[clampIdx(colorSpaceIdx, CSPACE_VALUES.length)];

        for (int i = 0; i < D185.PROPS; i++) {
            if (want[i] != base[i]) p.set(i, want[i]);
        }
    }

    /** 供 UI 展示「有多少个字段与原始档不同」 */
    public int changedCount() {
        int[] want = base.clone();
        D185 tmp = D185Holder.of(want);
        applyTo(tmp);
        int n = 0;
        int[] after = tmp.values();
        for (int i = 0; i < D185.PROPS; i++) if (after[i] != base[i]) n++;
        return n;
    }

    public String summaryText() {
        StringBuilder sb = new StringBuilder(D185.simName(filmSim));
        if (drPriority != D185.DRP_OFF) sb.append(" · DR优先").append(drPriority);
        else sb.append(" · DR").append(dr);
        sb.append(" · 高光").append(tone(highlight)).append(" 阴影").append(tone(shadow));
        sb.append(" · 色彩").append(signed(color)).append(" 锐度").append(signed(sharpness));
        sb.append(" 清晰度").append(signed(clarity));
        sb.append(" · ").append(FILETYPE_NAMES[clampIdx(fileTypeIdx, 3)])
                .append(' ').append(SIZE_NAMES[clampIdx(imageSizeIdx, 9)])
                .append(" · 改动 ").append(changedCount()).append(" 项");
        return sb.toString();
    }

    private static int clampIdx(int i, int n) { return Math.max(0, Math.min(n - 1, i)); }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    private static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    /** 编码值 → 显示（0.5 档带一位小数） */
    private static String tone(int enc) {
        if (enc % 10 == 0) return signed(enc / 10);
        return String.format(java.util.Locale.US, "%s%.1f", enc > 0 ? "+" : "", enc / 10.0);
    }

    /** 按文件类型给出扩展名：JPEG(.jpg) / TIFF(.tif) */
    public String exportExtension() {
        int ft = FILETYPE_VALUES[clampIdx(fileTypeIdx, FILETYPE_VALUES.length)];
        return ft == D185.FILE_JPG ? ".jpg" : ".tif";
    }

    public String exportMime() {
        int ft = FILETYPE_VALUES[clampIdx(fileTypeIdx, FILETYPE_VALUES.length)];
        return ft == D185.FILE_JPG ? "image/jpeg" : "image/tiff";
    }

    // ---------------------------------------------------------------- JSON / 配方库

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("filmSim", filmSim);
            o.put("dr", dr);
            o.put("drPriority", drPriority);
            o.put("grainStrength", grainStrength);
            o.put("grainLarge", grainLarge);
            o.put("chrome", chrome);
            o.put("fxBlue", fxBlue);
            o.put("wbIndex", wbIndex);
            o.put("wbTemp", wbTemp);
            o.put("wbR", wbR);
            o.put("wbB", wbB);
            o.put("highlight", highlight);
            o.put("shadow", shadow);
            o.put("color", color);
            o.put("sharpness", sharpness);
            o.put("nr", nr);
            o.put("clarity", clarity);
            o.put("lmo", lmo);
            o.put("imageSizeIdx", imageSizeIdx);
            o.put("qualityIdx", qualityIdx);
            o.put("fileTypeIdx", fileTypeIdx);
            o.put("colorSpaceIdx", colorSpaceIdx);
            o.put("expBiasThirds", expBiasThirds);
        } catch (Exception ignored) { }
        return o;
    }

    public static Recipe fromJson(JSONObject o) {
        Recipe r = new Recipe();
        r.name = o.optString("name", r.name);
        r.filmSim = o.optInt("filmSim", r.filmSim);
        r.dr = o.optInt("dr", r.dr);
        r.drPriority = o.optInt("drPriority", r.drPriority);
        r.grainStrength = o.optInt("grainStrength", r.grainStrength);
        r.grainLarge = o.optBoolean("grainLarge", r.grainLarge);
        r.chrome = o.optInt("chrome", r.chrome);
        r.fxBlue = o.optInt("fxBlue", r.fxBlue);
        r.wbIndex = o.optInt("wbIndex", r.wbIndex);
        r.wbTemp = o.optInt("wbTemp", r.wbTemp);
        r.wbR = o.optInt("wbR", r.wbR);
        r.wbB = o.optInt("wbB", r.wbB);
        r.highlight = o.optInt("highlight", r.highlight);
        r.shadow = o.optInt("shadow", r.shadow);
        r.color = o.optInt("color", r.color);
        r.sharpness = o.optInt("sharpness", r.sharpness);
        r.nr = o.optInt("nr", r.nr);
        r.clarity = o.optInt("clarity", r.clarity);
        r.lmo = o.optInt("lmo", r.lmo);
        r.imageSizeIdx = o.optInt("imageSizeIdx", r.imageSizeIdx);
        r.qualityIdx = o.optInt("qualityIdx", r.qualityIdx);
        r.fileTypeIdx = o.optInt("fileTypeIdx", r.fileTypeIdx);
        r.colorSpaceIdx = o.optInt("colorSpaceIdx", r.colorSpaceIdx);
        r.expBiasThirds = o.optInt("expBiasThirds", r.expBiasThirds);
        return r;
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "recipes");
        if (!d.exists() && !d.mkdirs()) return c.getFilesDir();
        return d;
    }

    public static void save(Context c, Recipe r) throws IOException {
        String safe = r.name.replaceAll("[^\\p{L}\\p{N}_\\- ]", "_");
        File f = new File(dir(c), safe + ".json");
        String text;
        try {
            text = r.toJson().toString(2);
        } catch (org.json.JSONException e) {
            throw new IOException("配方序列化失败: " + e.getMessage());
        }
        FileOutputStream fos = new FileOutputStream(f);
        try { fos.write(text.getBytes(StandardCharsets.UTF_8)); } finally { fos.close(); }
    }

    public static List<Recipe> list(Context c) {
        List<Recipe> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                out.add(fromJson(new JSONObject(readFile(f))));
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static String readFile(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /** 内部小工具：用给定字段数组造一个 D185（用于统计改动数） */
    private static final class D185Holder {
        static D185 of(int[] values) {
            try {
                // 用一个全零包解析后覆盖，避免暴露 D185 的私有构造
                byte[] zero = FpNative.create(new int[D185.PROPS], 0);
                D185 d = D185.parse(zero);
                for (int i = 0; i < D185.PROPS; i++) d.set(i, values[i]);
                return d;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
