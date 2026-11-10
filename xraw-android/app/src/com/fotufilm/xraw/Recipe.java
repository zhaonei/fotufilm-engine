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
 * 一套「配方」的 UI 模型。单位与机内菜单一致（整数刻度），
 * 与 d185 的编码转换全部在 {@link #applyTo(D185)} 里完成：
 *   色调/色彩/锐度/清晰度 ×10；效果类 +1（1-indexed）；颗粒→扁平枚举；降噪用专有码表。
 */
public final class Recipe {
    public String name = "未命名配方";

    public int filmSim = 17;                 // CLASSIC Neg.
    public int dr = D185.DR_100;
    public int drPriority = D185.DRP_OFF;
    public int grainStrength = 0;            // 0=关 1=弱 2=强
    public boolean grainLarge = false;
    public int chrome = 0;                   // 0=关 1=弱 2=强
    public int fxBlue = 0;
    public int smoothSkin = 0;
    public int wbIndex = 0;                  // WB_VALUES 下标
    public int wbTemp = 5500;
    public int wbR = 0, wbB = 0;
    public int highlight = 0;                // -4..+4（整数）
    public int shadow = 0;
    public int color = 0;                    // -4..+4
    public int sharpness = 0;                // -4..+4
    public int nr = 0;                       // -4..+4
    public int clarity = 0;                  // -5..+5
    public int expBiasThirds = 0;            // -9..+9（1/3 EV）

    // 以下四项在相机原生 d185 里的字段未确认，暂不写入（UI 会标注）
    public int imageSizeIdx = 0, qualityIdx = 0, fileTypeIdx = 0, colorSpaceIdx = 0;

    // ---------------------------------------------------------------- 应用

    public void applyTo(D185 p) {
        p.set(D185.Idx.FILM_SIM, filmSim);
        p.set(D185.Idx.EXPOSURE_BIAS, Math.round(expBiasThirds * 1000f / 3f));

        p.set(D185.Idx.WIDE_DRANGE, drPriority);
        if (drPriority == D185.DRP_OFF) p.set(D185.Idx.DYNAMIC_RANGE, dr);

        // 颗粒：强度+大小 → 扁平枚举
        int grain;
        if (grainStrength <= 0) grain = D185.GRAIN_OFF;
        else if (grainStrength == 1) grain = grainLarge ? D185.GRAIN_WEAK_LARGE : D185.GRAIN_WEAK_SMALL;
        else grain = grainLarge ? D185.GRAIN_STRONG_LARGE : D185.GRAIN_STRONG_SMALL;
        p.set(D185.Idx.GRAIN, grain);

        p.set(D185.Idx.CHROME, chrome + 1);
        p.set(D185.Idx.FX_BLUE, fxBlue + 1);
        p.set(D185.Idx.SMOOTH_SKIN, smoothSkin + 1);

        p.set(D185.Idx.WB_MODE, D185.WB_VALUES[clampIdx(wbIndex, D185.WB_VALUES.length)]);
        p.set(D185.Idx.WB_COLOR_TEMP, wbTemp);
        p.set(D185.Idx.WB_SHIFT_R, wbR);
        p.set(D185.Idx.WB_SHIFT_B, wbB);

        p.set(D185.Idx.HIGHLIGHT, highlight * 10);
        p.set(D185.Idx.SHADOW, shadow * 10);
        p.set(D185.Idx.COLOR, color * 10);
        p.set(D185.Idx.SHARPNESS, sharpness * 10);
        p.set(D185.Idx.NOISE_RED, D185.nrEncode(nr));
        p.set(D185.Idx.CLARITY, clarity * 10);
    }

    private static int clampIdx(int i, int n) { return Math.max(0, Math.min(n - 1, i)); }

    public static Recipe fromD185(D185 p) {
        Recipe r = new Recipe();
        r.filmSim = p.filmSim();
        r.expBiasThirds = Math.round(p.exposureBias() * 3f / 1000f);
        r.drPriority = p.drPriority();
        int dr = p.dr();
        r.dr = (dr == D185.DR_200 || dr == D185.DR_400) ? dr : D185.DR_100;

        int g = p.get(D185.Idx.GRAIN);
        if (g <= D185.GRAIN_OFF) { r.grainStrength = 0; r.grainLarge = false; }
        else if (g == D185.GRAIN_WEAK_SMALL) { r.grainStrength = 1; r.grainLarge = false; }
        else if (g == D185.GRAIN_STRONG_SMALL) { r.grainStrength = 2; r.grainLarge = false; }
        else if (g == D185.GRAIN_WEAK_LARGE) { r.grainStrength = 1; r.grainLarge = true; }
        else { r.grainStrength = 2; r.grainLarge = true; }

        r.chrome = clamp(p.get(D185.Idx.CHROME) - 1, 0, 2);
        r.fxBlue = clamp(p.get(D185.Idx.FX_BLUE) - 1, 0, 2);
        r.smoothSkin = clamp(p.get(D185.Idx.SMOOTH_SKIN) - 1, 0, 2);

        int wb = p.wbMode();
        r.wbIndex = D185.WB_VALUES.length - 1;      // 默认按拍摄时
        for (int i = 0; i < D185.WB_VALUES.length; i++) if (D185.WB_VALUES[i] == wb) { r.wbIndex = i; break; }
        r.wbTemp = clamp(p.get(D185.Idx.WB_COLOR_TEMP), 2500, 10000);
        r.wbR = clamp(p.get(D185.Idx.WB_SHIFT_R), -9, 9);
        r.wbB = clamp(p.get(D185.Idx.WB_SHIFT_B), -9, 9);

        r.highlight = clamp(D185.decodeTenths(p.get(D185.Idx.HIGHLIGHT)), -4, 4);
        r.shadow = clamp(D185.decodeTenths(p.get(D185.Idx.SHADOW)), -4, 4);
        r.color = clamp(D185.decodeTenths(p.get(D185.Idx.COLOR)), -4, 4);
        r.sharpness = clamp(D185.decodeTenths(p.get(D185.Idx.SHARPNESS)), -4, 4);
        r.nr = clamp(D185.nrDecode(p.get(D185.Idx.NOISE_RED)), -4, 4);
        r.clarity = clamp(D185.decodeTenths(p.get(D185.Idx.CLARITY)), -5, 5);
        return r;
    }

    public static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }

    public String summaryText() {
        StringBuilder sb = new StringBuilder(D185.simName(filmSim));
        if (drPriority != D185.DRP_OFF) sb.append(" · DR优先").append(drPriority);
        else sb.append(" · DR").append(dr);
        sb.append(" · 高光").append(signed(highlight)).append(" 阴影").append(signed(shadow));
        sb.append(" · 色彩").append(signed(color)).append(" 锐度").append(signed(sharpness));
        sb.append(" 清晰度").append(signed(clarity));
        return sb.toString();
    }

    /** 输出扩展名：相机原生 d185 未确认文件类型字段，暂按 JPEG */
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
            o.put("smoothSkin", smoothSkin);
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
        r.smoothSkin = o.optInt("smoothSkin", r.smoothSkin);
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
}
