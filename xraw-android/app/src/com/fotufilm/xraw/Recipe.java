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
    public int expBiasThirds = 0;

    // 输出相关（相机原生 d185 中未确认，暂不下发）
    public int imageSizeIdx = 0, qualityIdx = 0, fileTypeIdx = 0, colorSpaceIdx = 0;

    // ---------------------------------------------------------------- 读回

    public static Recipe fromD185(D185 p) {
        Recipe r = new Recipe();
        r.base = p.values().clone();

        r.filmSim = p.filmSim();
        r.expBiasThirds = Math.round(p.exposureBias() * 3f / 1000f);
        r.drPriority = p.drPriority();
        int dr = p.dr();
        r.dr = (dr == D185.DR_200 || dr == D185.DR_400) ? dr : D185.DR_100;

        int g = p.get(D185.Idx.GRAIN);
        r.grainLarge = g > D185.GRAIN_STRONG;
        r.grainStrength = Math.max(0, (r.grainLarge ? g - D185.GRAIN_SIZE_LARGE : g) - 1);

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
        r.wbTemp = p.get(D185.Idx.WB_COLOR_TEMP);        // 保留原值（0 = 未使用），不强行夹到 2500
        r.wbR = D185.clamp((short) (p.get(D185.Idx.WB_SHIFT_R) & 0xFFFF), -9, 9);
        r.wbB = D185.clamp((short) (p.get(D185.Idx.WB_SHIFT_B) & 0xFFFF), -9, 9);

        r.highlight = D185.clamp((short) (p.get(D185.Idx.HIGHLIGHT) & 0xFFFF), -20, 40);
        r.shadow = D185.clamp((short) (p.get(D185.Idx.SHADOW) & 0xFFFF), -20, 40);
        r.color = D185.clamp(D185.decodeTenths(p.get(D185.Idx.COLOR)), -4, 4);
        r.sharpness = D185.clamp(D185.decodeTenths(p.get(D185.Idx.SHARPNESS)), -4, 4);
        r.nr = D185.clamp(D185.nrDecode(p.get(D185.Idx.NOISE_RED)), -4, 4);
        r.clarity = D185.clamp(D185.decodeTenths(p.get(D185.Idx.CLARITY)), -5, 5);
        return r;
    }

    // ---------------------------------------------------------------- 差量打包

    /** 把 UI 值套到 base 的副本上，**只把与 base 不同的字段**写进 d185 */
    public void applyTo(D185 p) {
        int[] want = base.clone();

        want[D185.Idx.FILM_SIM] = filmSim;
        want[D185.Idx.EXPOSURE_BIAS] = Math.round(expBiasThirds * 1000f / 3f);
        want[D185.Idx.WIDE_DRANGE] = drPriority;
        if (drPriority == D185.DRP_OFF) want[D185.Idx.DYNAMIC_RANGE] = dr;

        int grain;
        if (grainStrength <= 0) grain = D185.GRAIN_OFF;
        else if (grainStrength == 1) grain = D185.GRAIN_WEAK + (grainLarge ? D185.GRAIN_SIZE_LARGE : 0);
        else grain = D185.GRAIN_STRONG + (grainLarge ? D185.GRAIN_SIZE_LARGE : 0);
        want[D185.Idx.GRAIN] = grain;

        // 效果类：若基线是 0（哨兵/未设置）且用户没改，保持 0，避免把"未设置"变成"关闭"
        want[D185.Idx.CHROME] = (chrome == 0 && base[D185.Idx.CHROME] == 0) ? 0 : chrome + 1;
        want[D185.Idx.FX_BLUE] = (fxBlue == 0 && base[D185.Idx.FX_BLUE] == 0) ? 0 : fxBlue + 1;

        // 白平衡：As Shot 时只置 WBShootCond，绝不覆盖 WB 模式/色温
        if (wbIndex == D185.WB_VALUES.length - 1) {
            want[D185.Idx.WB_SHOOT_COND] = D185.WB_SHOOT_ON;
        } else {
            int mode = D185.WB_VALUES[Math.max(0, Math.min(D185.WB_VALUES.length - 1, wbIndex))];
            want[D185.Idx.WB_SHOOT_COND] = D185.WB_SHOOT_OFF;
            want[D185.Idx.WB_MODE] = mode;
            want[D185.Idx.WB_SHIFT_R] = wbR;
            want[D185.Idx.WB_SHIFT_B] = wbB;
            if (mode == D185.WB_TEMPERATURE) {
                want[D185.Idx.WB_COLOR_TEMP] = D185.clamp(wbTemp, 2500, 10000);
            }
        }

        want[D185.Idx.HIGHLIGHT] = D185.clamp(highlight, -20, 40);
        want[D185.Idx.SHADOW] = D185.clamp(shadow, -20, 40);
        want[D185.Idx.COLOR] = D185.encodeTenths(color);
        want[D185.Idx.SHARPNESS] = D185.encodeTenths(sharpness);
        want[D185.Idx.NOISE_RED] = D185.nrEncode(nr);
        want[D185.Idx.CLARITY] = D185.encodeTenths(clarity);

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
        sb.append(" · 改动 ").append(changedCount()).append(" 项");
        return sb.toString();
    }

    private static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    /** 编码值 → 显示（0.5 档带一位小数） */
    private static String tone(int enc) {
        if (enc % 10 == 0) return signed(enc / 10);
        return String.format(java.util.Locale.US, "%s%.1f", enc > 0 ? "+" : "", enc / 10.0);
    }

    public String exportExtension() { return ".jpg"; }

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
