package com.fotufilm.xraw;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 相机自定义配方槽位（C1–C4）的读写。
 *
 * 与主流程（d185 / RAF 转换）**完全独立**：只用 0xD18C + 0xD18E…0xD1A4 这些普通设备属性，
 * 不涉及 RAW 上传，所以可以在"工坊"里单独用，不影响拍摄/调参。
 *
 * 关键实现纪律（全部来自 zgrimz docs/04-custom-slots.md 的 X RAW Studio 抓包）：
 *  1. 每次操作前都要写 0xD18C 选槽（它只是"编辑指针"，会跨连接保留）
 *  2. 只写"与现值不同"的项
 *  3. 写入顺序有硬规则，见 {@link #writeOrder}
 *  4. 写完逐项回读；0x2001 不代表真的存进去了
 */
public final class FujiSlots {

    public static final int MAX_SLOTS = 4;          // X-M5 = C1..C4

    /** 单个属性的写入结果 */
    public static final class ItemResult {
        public final int code;
        public final int want;
        public final Integer got;      // null = 回读失败/不支持
        public final String note;
        ItemResult(int code, int want, Integer got, String note) {
            this.code = code; this.want = want; this.got = got; this.note = note;
        }
        public boolean okByMeaning() { return note == null || note.isEmpty(); }
        public String describe() {
            String n = SlotProps.name(code);
            if (got == null) return String.format("%s 写%d → 回读失败(%s)", n, want, note);
            if (okByMeaning()) return String.format("%s 写%d → 回读%d ✓", n, want, got);
            return String.format("%s 写%d → 回读%d（%s）", n, want, got, note);
        }
    }

    /** 一次写槽的完整结果 */
    public static final class WriteResult {
        public final int slot;
        public final List<ItemResult> items = new ArrayList<>();
        public String nameNote = "";
        public boolean allOk = true;
        public WriteResult(int slot) { this.slot = slot; }
        public int written() { return items.size(); }
        public int failed() {
            int n = 0;
            for (ItemResult it : items) if (!it.okByMeaning()) n++;
            return n;
        }
        public String summarize() {
            return "C" + slot + " 写入 " + written() + " 项，异常 " + failed() + " 项"
                    + (nameNote.isEmpty() ? "" : "，名称：" + nameNote);
        }
    }

    private final Fuji fuji;
    private final UsbPtp t;

    public FujiSlots(Fuji fuji, UsbPtp transport) {
        this.fuji = fuji;
        this.t = transport;
    }

    // ---------------------------------------------------------------- 读

    /** 选槽（0xD18C）。每次读写前都必须调用。 */
    public void selectSlot(int slot) throws IOException {
        putU16(SlotProps.SLOT_SELECT, slot);
        sleep(80);      // 文档建议切换后给一点稳定时间（X-H2 上 50ms 足够）
    }

    /** 读一个槽位的全部 23 项 */
    public SlotData readSlot(int slot) throws IOException {
        selectSlot(slot);
        SlotData d = new SlotData(slot);
        try {
            d.name = readName();
        } catch (IOException e) {
            d.missing.put(SlotProps.SLOT_NAME, e.getMessage());
        }
        for (int code : SlotProps.ALL) {
            try {
                d.raw.put(code, getU16(code));
            } catch (IOException e) {
                d.missing.put(code, e.getMessage());
            }
        }
        return d;
    }

    public String readName() throws IOException {
        UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_DEVICE_PROP_VALUE, SlotProps.SLOT_NAME);
        r.resp.requireOk();
        return ptpString(r.data);
    }

    // ---------------------------------------------------------------- 写

    /**
     * 把一条配方写进槽位：只写与现值不同的项，按文档规定的顺序，写完逐项回读。
     *
     * @param name 可为 null（不写名称；X-M5 可能不支持写入名称，失败不阻塞）
     */
    public WriteResult writeSlot(int slot, Recipe r, String name) throws IOException {
        selectSlot(slot);

        // 1) 先读现值，形成差量（也顺便拿到"当前优先级"以决定 0xD190/0xD191 的顺序）
        int curPrio = safeGet(SlotProps.DR_PRIORITY, 0);
        Map<Integer, Integer> want = buildWanted(r, curPrio);

        WriteResult res = new WriteResult(slot);

        // 2) 名称（可选，失败只记录）
        if (name != null && !name.trim().isEmpty()) {
            try {
                t.commandDataOut(Ptp.OC_SET_DEVICE_PROP_VALUE,
                        new int[]{SlotProps.SLOT_NAME}, packPtpString(name.trim()));
                res.nameNote = "已写入";
            } catch (IOException e) {
                res.nameNote = "该机型可能不支持写名称（" + e.getMessage() + "）";
            }
        }

        // 3) 按顺序写"变化项"
        for (int code : writeOrder(want, curPrio)) {
            Integer v = want.get(code);
            if (v == null) continue;
            int cur = safeGet(code, Integer.MIN_VALUE);
            if (cur == v) continue;                       // 未变化，不写
            try {
                putU16(code, v);
                Integer got = safeGetOrNull(code);
                res.items.add(new ItemResult(code, v, got, normalizeNote(code, v, got)));
                if (res.items.size() > 0) sleep(2);       // 文档：0ms 也可，保守给 2ms
            } catch (IOException e) {
                ItemResult bad = new ItemResult(code, v, null, e.getMessage());
                res.items.add(bad);
                res.allOk = false;
            }
        }
        return res;
    }

    /**
     * 写入顺序（**这是最容易踩坑的地方**）：
     *   D18E, D18F → [关优先: D191→D190 | 否则 D190→D191] → D192(胶片模拟)
     *   → D193/D194(单色) → D195…D198(效果) → D199(WB) → D19C(Kelvin) → D19A/D19B(偏移)
     *   → D19D…D1A2(色调/色彩/锐度/NR/清晰度) → D1A3/D1A4
     */
    private List<Integer> writeOrder(Map<Integer, Integer> want, int curPrio) {
        List<Integer> order = new ArrayList<>();
        order.add(SlotProps.IMAGE_SIZE);
        order.add(SlotProps.IMAGE_QUALITY);
        boolean turningOff = want.containsKey(SlotProps.DR_PRIORITY)
                && want.get(SlotProps.DR_PRIORITY) == 0 && curPrio != 0;
        if (turningOff) {
            order.add(SlotProps.DR_PRIORITY);
            order.add(SlotProps.DR_PERCENT);
        } else {
            order.add(SlotProps.DR_PERCENT);
            order.add(SlotProps.DR_PRIORITY);
        }
        order.add(SlotProps.FILM_SIM);
        order.add(SlotProps.MONO_WC);
        order.add(SlotProps.MONO_MG);
        order.add(SlotProps.GRAIN);
        order.add(SlotProps.CHROME);
        order.add(SlotProps.FX_BLUE);
        order.add(SlotProps.SMOOTH_SKIN);
        order.add(SlotProps.WB);
        order.add(SlotProps.COLOR_TEMP);      // Kelvin 紧跟 WB 模式
        order.add(SlotProps.WB_SHIFT_R);      // 偏移在 Kelvin 之后
        order.add(SlotProps.WB_SHIFT_B);
        order.add(SlotProps.HIGHLIGHT);
        order.add(SlotProps.SHADOW);
        order.add(SlotProps.COLOR);
        order.add(SlotProps.SHARPNESS);
        order.add(SlotProps.HIGH_ISO_NR);
        order.add(SlotProps.CLARITY);
        order.add(SlotProps.LONG_EXP_NR);
        order.add(SlotProps.COLOR_SPACE);
        return order;
    }

    /** UI 配方 → 槽位属性值（只放"需要下发"的项） */
    private Map<Integer, Integer> buildWanted(Recipe r, int curPrio) {
        Map<Integer, Integer> w = new LinkedHashMap<>();

        w.put(SlotProps.FILM_SIM, r.filmSim);

        // DR：优先≠Off 时相机接管 DR，不能写 D190
        if (r.drPriority != 0) {
            w.put(SlotProps.DR_PRIORITY, r.drPriority);
        } else {
            w.put(SlotProps.DR_PRIORITY, 0);
            w.put(SlotProps.DR_PERCENT, r.dr);
        }

        int grain;
        if (r.grainStrength <= 0) grain = 1;
        else if (r.grainStrength == 1) grain = r.grainLarge ? 4 : 2;
        else grain = r.grainLarge ? 5 : 3;
        w.put(SlotProps.GRAIN, grain);
        w.put(SlotProps.CHROME, r.chrome + 1);
        w.put(SlotProps.FX_BLUE, r.fxBlue + 1);

        int wbMode = D185.WB_VALUES[Math.max(0, Math.min(D185.WB_VALUES.length - 1, r.wbIndex))];
        if (wbMode != D185.WB_AS_SHOT) {
            w.put(SlotProps.WB, wbMode);
            if (wbMode == D185.WB_TEMPERATURE) {
                w.put(SlotProps.COLOR_TEMP, D185.clamp(r.wbTemp, 2500, 10000));
            }
            w.put(SlotProps.WB_SHIFT_R, D185.clamp(r.wbR, -9, 9));
            w.put(SlotProps.WB_SHIFT_B, D185.clamp(r.wbB, -9, 9));
        }

        w.put(SlotProps.HIGHLIGHT, D185.clamp(r.highlight, -20, 40));
        w.put(SlotProps.SHADOW, D185.clamp(r.shadow, -20, 40));
        w.put(SlotProps.COLOR, r.color * 10);
        w.put(SlotProps.SHARPNESS, r.sharpness * 10);
        w.put(SlotProps.HIGH_ISO_NR, D185.nrEncode(r.nr));
        w.put(SlotProps.CLARITY, r.clarity * 10);
        return w;
    }

    /** 文档里点名的"合法规范化"：不算错误 */
    private String normalizeNote(int code, int want, Integer got) {
        if (got == null) return "回读失败";
        if (got == want) return "";
        if (code == SlotProps.SLOT_NAME) return "名称被忽略";
        if (code == SlotProps.GRAIN) {
            // 写 1(Off) 回读成 6 或 7 是正常（保留上次大小）
            if (want == 1 && (got == 6 || got == 7)) return "";
        }
        if (code == SlotProps.HIGHLIGHT || code == SlotProps.SHADOW) {
            // 降级/夹取在 ±40 内视为正常
            if (Math.abs(got) <= 40) return "被规范化";
        }
        return "值未被接受";
    }

    // ---------------------------------------------------------------- 底层

    private void putU16(int code, int value) throws IOException {
        byte[] d = {(byte) value, (byte) (value >>> 8)};
        t.commandDataOut(Ptp.OC_SET_DEVICE_PROP_VALUE, new int[]{code}, d).requireOk();
    }

    private int getU16(int code) throws IOException {
        UsbPtp.DataIn r = t.commandDataIn(Ptp.OC_GET_DEVICE_PROP_VALUE, code);
        r.resp.requireOk();
        if (r.data.length < 2) throw new IOException("空数据");
        return UsbPtp.le16(r.data, 0);
    }

    private int safeGet(int code, int def) {
        try { return getU16(code); } catch (IOException e) { return def; }
    }

    private Integer safeGetOrNull(int code) {
        try { return getU16(code); } catch (IOException e) { return null; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** PTP 字符串：u8 字符数(含结尾) + UTF-16LE + 0x0000 */
    static byte[] packPtpString(String s) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        if (s.isEmpty()) { o.write(0); return o.toByteArray(); }
        o.write(s.length() + 1);
        for (int i = 0; i < s.length(); i++) {
            o.write(s.charAt(i) & 0xFF);
            o.write((s.charAt(i) >>> 8) & 0xFF);
        }
        o.write(0); o.write(0);
        return o.toByteArray();
    }

    static String ptpString(byte[] d) {
        if (d.length < 1) return "";
        int n = d[0] & 0xFF;
        StringBuilder sb = new StringBuilder(Math.max(0, n - 1));
        for (int i = 0; i < n - 1 && 1 + i * 2 + 1 < d.length; i++) {
            sb.append((char) UsbPtp.le16(d, 1 + i * 2));
        }
        return sb.toString().trim();
    }
}
