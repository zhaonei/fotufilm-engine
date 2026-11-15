package com.fotufilm.xraw;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一个相机槽位（C1–C4）读回来的 23 项原始值与语义解码 */
public final class SlotData {
    public final int slot;
    public String name = "";
    public final Map<Integer, Integer> raw = new LinkedHashMap<>();
    /** 相机回读时缺失的属性（0x200A / 0x201C 等），用于 UI 标注"该机型不支持" */
    public final Map<Integer, String> missing = new LinkedHashMap<>();

    public SlotData(int slot) { this.slot = slot; }

    public int get(int code, int def) {
        Integer v = raw.get(code);
        return v == null ? def : v;
    }

    public boolean has(int code) { return raw.containsKey(code); }

    public String label() { return "C" + slot + (name.isEmpty() ? "" : " " + name); }

    /** 语义摘要，用于列表展示 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(D185.simName(get(SlotProps.FILM_SIM, 0)));
        int dr = get(SlotProps.DR_PERCENT, 0);
        if (dr == 0xFFFF) sb.append(" · DR Auto");
        else if (dr > 0) sb.append(" · DR").append(dr);
        int prio = get(SlotProps.DR_PRIORITY, 0);
        if (prio != 0) sb.append(" · 优先").append(prio == 0x8000 ? "Auto" : String.valueOf(prio));
        sb.append(" · 高光").append(tone(get(SlotProps.HIGHLIGHT, 0)));
        sb.append(" 阴影").append(tone(get(SlotProps.SHADOW, 0)));
        return sb.toString();
    }

    static String tone(int enc) {
        if ((enc & 0xFFFF) == 0x8000) return "—";
        int v = (short) (enc & 0xFFFF);
        return v % 10 == 0 ? (v > 0 ? "+" + v / 10 : String.valueOf(v / 10))
                : String.format(java.util.Locale.US, "%s%.1f", v > 0 ? "+" : "", v / 10.0);
    }
}
