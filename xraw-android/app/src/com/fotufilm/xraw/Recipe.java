package com.fotufilm.xraw;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一套「配方」的 UI 侧模型（单位与 X RAW Studio / 机内菜单一致），负责：
 *  - applyTo(D185)：把 UI 值写进 d185 的 29 个字段
 *  - fromD185(D185)：从相机回读的 d185 还原成 UI 值（这是「读取原 RAF 机内配方」的实现）
 *  - JSON 存取：/data/.../files/recipes/*.json
 */
public final class Recipe {
    public String name = "未命名配方";

    public int filmSim = D185.SIM_VALUES[10];           // CLASSIC CHROME
    public int dr = D185.DR_100;
    public int drPriority = D185.DRP_OFF;
    public int grainEffect = D185.GRAIN_OFF;
    public boolean grainLarge = false;
    public int chrome = D185.CHROME_OFF;
    public int fxBlue = D185.FXB_OFF;
    public int wbIndex = 0;                             // index of D185.WB_VALUES
    public int wbTemp = 5500;
    public int wbR = 0, wbB = 0;
    public int highlight = 0;                           // ×10 tenths, -20..40
    public int shadow = 0;                              // ×10
    public int color = 0;                               // -4..4（线上 ×10）
    public int sharpness = 0;                            // -4..4（线上 ×10）
    public int nr = 0;                                   // -4..4（线上非线性）
    public int clarity = 0;                              // -5..5（线上 ×10）
    public int lmo = D185.LMO_ON;
    public int imageSizeIdx = 0;                         // L 3:2
    public int qualityIdx = 0;                           // FINE
    public int fileTypeIdx = 0;                          // JPEG
    public int colorSpaceIdx = 0;                        // sRGB
    public int expBiasThirds = 0;                        // -9..9 (1/3 EV)
    public int monoWC = 0, monoMG = 0;                   // -18..18（线上 ×10）

    // ---------------------------------------------------------------- d185 桥接

    public void applyTo(D185 p) {
        p.set(D185.IDX_FILM_SIM, filmSim);
        p.set(D185.IDX_WIDE_DRANGE, drPriority);
        if (drPriority == D185.DRP_OFF) p.set(D185.IDX_DYNAMIC_RANGE, dr);
        p.set(D185.IDX_GRAIN, grainEffect + (grainLarge ? D185.GRAIN_SIZE_LARGE : 0));
        p.set(D185.IDX_CHROME, chrome);
        p.set(D185.IDX_FX_BLUE, fxBlue);
        // 「按拍摄时 (As Shot)」= 只置 WBShootCond=ON，不写 WB 模式，让相机沿用原 RAF 的白平衡
        if (wbIndex == D185.WB_VALUES.length - 1) {
            p.set(D185.IDX_WB_SHOOT_COND, D185.WB_SHOOT_COND_ON);
        } else {
            p.set(D185.IDX_WB_SHOOT_COND, D185.WB_SHOOT_COND_OFF);
            p.set(D185.IDX_WB_MODE, D185.WB_VALUES[Math.max(0, Math.min(D185.WB_VALUES.length - 1, wbIndex))]);
        }
        p.set(D185.IDX_WB_COLOR_TEMP, wbTemp);
        p.set(D185.IDX_WB_SHIFT_R, wbR * 10);
        p.set(D185.IDX_WB_SHIFT_B, wbB * 10);
        p.set(D185.IDX_HIGHLIGHT, highlight);
        p.set(D185.IDX_SHADOW, shadow);
        if (p.isMono()) {
            p.set(D185.IDX_BLACK_IMAGE_TONE, monoWC * 10);
            p.set(D185.IDX_MONO_RG, monoMG * 10);
        } else {
            p.set(D185.IDX_COLOR, color * 10);
            p.set(D185.IDX_SHARPNESS, sharpness * 10);
        }
        p.set(D185.IDX_NOISE_RED, D185.nrFromStep(nr));
        p.set(D185.IDX_CLARITY, clarity * 10);
        p.set(D185.IDX_LMO, lmo);
        p.set(D185.IDX_IMAGE_SIZE, D185.IMAGE_SIZE_VALUES[clampIdx(imageSizeIdx, D185.IMAGE_SIZE_VALUES.length)]);
        p.set(D185.IDX_IMAGE_QUALITY, D185.QUALITY_VALUES[clampIdx(qualityIdx, D185.QUALITY_VALUES.length)]);
        p.set(D185.IDX_FILE_TYPE, D185.FILE_TYPE_VALUES[clampIdx(fileTypeIdx, D185.FILE_TYPE_VALUES.length)]);
        p.set(D185.IDX_COLOR_SPACE, D185.COLOR_SPACE_VALUES[clampIdx(colorSpaceIdx, D185.COLOR_SPACE_VALUES.length)]);
        p.set(D185.IDX_EXPOSURE_BIAS, Math.round(expBiasThirds * 1000f / 3f));
    }

    private static int clampIdx(int i, int n) { return Math.max(0, Math.min(n - 1, i)); }

    public static Recipe fromD185(D185 p) {
        Recipe r = new Recipe();
        r.filmSim = p.filmSim();
        r.drPriority = p.drPriority();
        int dr = p.get(D185.IDX_DYNAMIC_RANGE);
        r.dr = (dr == D185.DR_200 || dr == D185.DR_400) ? dr : D185.DR_100;
        int g = p.get(D185.IDX_GRAIN);
        r.grainLarge = g > D185.GRAIN_STRONG;
        r.grainEffect = r.grainLarge ? g - D185.GRAIN_SIZE_LARGE : g;
        if (r.grainEffect < D185.GRAIN_OFF || r.grainEffect > D185.GRAIN_STRONG) r.grainEffect = D185.GRAIN_OFF;
        r.chrome = clampRange(p.get(D185.IDX_CHROME), D185.CHROME_OFF, D185.CHROME_STRONG);
        r.fxBlue = clampRange(p.get(D185.IDX_FX_BLUE), D185.FXB_OFF, D185.FXB_STRONG);
        boolean asShot = p.get(D185.IDX_WB_SHOOT_COND) == D185.WB_SHOOT_COND_ON;
        int wb = p.wbMode();
        r.wbIndex = D185.WB_VALUES.length - 1;      // 默认视为「按拍摄时」
        if (!asShot) {
            for (int i = 0; i < D185.WB_VALUES.length; i++) {
                if (D185.WB_VALUES[i] == wb) { r.wbIndex = i; break; }
            }
        }
        r.wbTemp = D185.clamp(p.get(D185.IDX_WB_COLOR_TEMP), 2500, 10000);
        r.wbR = p.get(D185.IDX_WB_SHIFT_R) / 10;
        r.wbB = p.get(D185.IDX_WB_SHIFT_B) / 10;
        r.highlight = p.get(D185.IDX_HIGHLIGHT);
        r.shadow = p.get(D185.IDX_SHADOW);
        r.color = p.get(D185.IDX_COLOR) / 10;
        r.sharpness = p.get(D185.IDX_SHARPNESS) / 10;
        r.nr = D185.nrToStep(p.get(D185.IDX_NOISE_RED));
        r.clarity = p.get(D185.IDX_CLARITY) / 10;
        r.lmo = p.get(D185.IDX_LMO) == D185.LMO_OFF ? D185.LMO_OFF : D185.LMO_ON;
        r.imageSizeIdx = indexOf(D185.IMAGE_SIZE_VALUES, p.get(D185.IDX_IMAGE_SIZE));
        r.qualityIdx = indexOf(D185.QUALITY_VALUES, p.get(D185.IDX_IMAGE_QUALITY));
        r.fileTypeIdx = indexOf(D185.FILE_TYPE_VALUES, p.get(D185.IDX_FILE_TYPE));
        r.colorSpaceIdx = indexOf(D185.COLOR_SPACE_VALUES, p.get(D185.IDX_COLOR_SPACE));
        r.expBiasThirds = Math.round(p.get(D185.IDX_EXPOSURE_BIAS) * 3f / 1000f);
        r.monoWC = p.get(D185.IDX_BLACK_IMAGE_TONE) / 10;
        r.monoMG = p.get(D185.IDX_MONO_RG) / 10;
        return r;
    }

    private static int clampRange(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    public String summaryText() {
        StringBuilder sb = new StringBuilder(D185.simName(filmSim));
        if (drPriority != D185.DRP_OFF) sb.append(" · DR优先");
        else sb.append(" · DR").append(dr);
        sb.append(" · 高光").append(String.format(java.util.Locale.US, "%.1f", highlight / 10.0));
        sb.append(" 阴影").append(String.format(java.util.Locale.US, "%.1f", shadow / 10.0));
        sb.append(" · ").append(D185.FILE_TYPE_NAMES[clampIdx(fileTypeIdx, 3)]);
        sb.append(' ').append(D185.IMAGE_SIZE_NAMES[clampIdx(imageSizeIdx, 9)]);
        return sb.toString();
    }

    public String exportExtension() {
        int ft = D185.FILE_TYPE_VALUES[clampIdx(fileTypeIdx, 3)];
        return ft == D185.FILE_JPG ? ".jpg" : ".tif";
    }

    // ---------------------------------------------------------------- JSON

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("filmSim", filmSim);
            o.put("dr", dr);
            o.put("drPriority", drPriority);
            o.put("grainEffect", grainEffect);
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
            o.put("monoWC", monoWC);
            o.put("monoMG", monoMG);
        } catch (Exception ignored) { }
        return o;
    }

    public static Recipe fromJson(JSONObject o) {
        Recipe r = new Recipe();
        r.name = o.optString("name", r.name);
        r.filmSim = o.optInt("filmSim", r.filmSim);
        r.dr = o.optInt("dr", r.dr);
        r.drPriority = o.optInt("drPriority", r.drPriority);
        r.grainEffect = o.optInt("grainEffect", r.grainEffect);
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
        r.monoWC = o.optInt("monoWC", r.monoWC);
        r.monoMG = o.optInt("monoMG", r.monoMG);
        return r;
    }

    // ---------------------------------------------------------------- 本地配方库

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
                String s = readFile(f);
                out.add(fromJson(new JSONObject(s)));
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

    public static String toJsonArray(List<Recipe> rs) {
        JSONArray arr = new JSONArray();
        for (Recipe r : rs) arr.put(r.toJson());
        return arr.toString();
    }
}
