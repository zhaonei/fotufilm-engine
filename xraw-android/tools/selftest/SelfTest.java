import com.fotufilm.xraw.D185;
import com.fotufilm.xraw.Recipe;
import com.fotufilm.xraw.FpNative;

/**
 * JVM 自测：d185 动态偏移解析 / 原生字段索引 / 原生编码 / 配方往返。
 * 结论来自 filmkit（WebUSB 版，同机型实测）。
 */
public final class SelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] a) throws Exception {
        byte[] raw = fakeD185();
        D185 p = D185.parse(raw);

        // 1) 动态偏移：propsOffset = 总长 - propCount*4（不是硬编码 0x201）
        check("propCount=29", p.rawPropCount() == 29);
        check("布局固定 0x201/29", D185.PROPS == 29 && FpNative.PROPS_OFFSET == 0x201);
        check("629 包解析出的字段数=29", p.values().length == 29);
        check("629 字节总长", FpNative.SIZE == 629);

        // 2) 往返不漂移
        Recipe r = Recipe.fromD185(p);
        D185 out = D185.parse(raw);
        r.applyTo(out);
        int mismatch = 0;
        for (int idx : new int[]{D185.Idx.FILM_SIM, D185.Idx.DYNAMIC_RANGE, D185.Idx.WIDE_DRANGE,
                D185.Idx.GRAIN, D185.Idx.CHROME, D185.Idx.FX_BLUE, D185.Idx.WB_MODE,
                D185.Idx.WB_COLOR_TEMP, D185.Idx.WB_SHIFT_R, D185.Idx.WB_SHIFT_B,
                D185.Idx.HIGHLIGHT, D185.Idx.SHADOW, D185.Idx.COLOR, D185.Idx.SHARPNESS,
                D185.Idx.NOISE_RED, D185.Idx.CLARITY}) {
            if (p.get(idx) != out.get(idx)) {
                mismatch++;
                System.out.printf("  MISMATCH idx=%d base=%d out=%d%n", idx, p.get(idx), out.get(idx));
            }
        }
        check("往返（16 个受控字段）无漂移", mismatch == 0);

        // 3) 编码：色调/色彩/锐度/清晰度 ×10
        r.highlight = 20; r.shadow = -20; r.color = -3; r.sharpness = 4; r.clarity = 5;
        D185 e = D185.parse(raw);
        r.applyTo(e);
        check("高光 +2.0 → 20（编码值直写）", e.get(D185.Idx.HIGHLIGHT) == 20);
        check("阴影 -2 → -20", e.get(D185.Idx.SHADOW) == -20);
        check("色彩 -3 → -30", e.get(D185.Idx.COLOR) == -30);
        check("锐度 +4 → 40", e.get(D185.Idx.SHARPNESS) == 40);
        check("清晰度 +5 → 50", e.get(D185.Idx.CLARITY) == 50);

        // 4) 效果 1-indexed
        r.chrome = 0; r.fxBlue = 2;
        D185 f = D185.parse(raw);
        r.applyTo(f);
        check("彩色铬 关 → 1", f.get(D185.Idx.CHROME) == 1);
        check("FX 蓝 强 → 3", f.get(D185.Idx.FX_BLUE) == 3);

        // 5) 颗粒扁平枚举
        r.grainStrength = 2; r.grainLarge = true;
        D185 g = D185.parse(raw);
        r.applyTo(g);
        check("颗粒 强+大 → 5", g.get(D185.Idx.GRAIN) == D185.GRAIN_STRONG + D185.GRAIN_SIZE_LARGE);
        r.grainStrength = 0;
        D185 g0 = D185.parse(raw);
        r.applyTo(g0);
        check("颗粒 关 → 1", g0.get(D185.Idx.GRAIN) == D185.GRAIN_OFF);

        // 6) 降噪专有码表
        check("NR -4 → 0x8000", D185.nrEncode(-4) == 0x8000);
        check("NR  0 → 0x2000", D185.nrEncode(0) == 0x2000);
        check("NR +4 → 0x5000", D185.nrEncode(4) == 0x5000);
        check("NR 反查 0x6000 → +3", D185.nrDecode(0x6000) == 3);

        // 7) 动态范围原值 + 优先抑制
        r.dr = 400; r.drPriority = 0;
        D185 h = D185.parse(raw); r.applyTo(h);
        check("DR → 400（百分比原值）", h.get(D185.Idx.DYNAMIC_RANGE) == 400);
        r.drPriority = 2;
        D185 h2 = D185.parse(raw); int before = h2.dr(); r.applyTo(h2);
        check("DR 优先开启时不写 DR", h2.dr() == before);

        // 8) 白平衡：白色优先 / 环境优先
        boolean wp = false, ap = false;
        for (int v : D185.WB_VALUES) { if (v == 0x8020) wp = true; if (v == 0x8021) ap = true; }
        check("白平衡含白色优先 0x8020", wp);
        check("白平衡含环境优先 0x8021", ap);

        // 9) 胶片模拟：含 BB(18)，菜单首位 PROVIA
        boolean bb = false;
        for (int v : D185.SIM_VALUES) if (v == 18) bb = true;
        check("含 BB(ETERNA BLEACH BYPASS=18)", bb);
        check("首位 PROVIA(1)", D185.SIM_VALUES[0] == 1);
        check("BB 名称含 BB", D185.simName(18).contains("BB"));

        // 10) ×10 解码（含 0x8000 哨兵）
        check("decodeTenths 20 → +2", D185.decodeTenths(20) == 2);
        check("decodeTenths 0x8000 → 0（哨兵）", D185.decodeTenths(0x8000) == 0);

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : ("FAILURES: " + failed + " / " + (passed + failed)));
        if (failed != 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    /** 造一个 625 字节的 d185（与相机实际返回长度一致，用于验证动态偏移） */
    static byte[] fakeD185() {
        byte[] b = new byte[625];
        b[0] = 29; b[1] = 0;
        String iop = "00179500";
        b[2] = (byte) (iop.length() + 1);
        int p = 3;
        for (int i = 0; i < iop.length(); i++) { b[p++] = (byte) iop.charAt(i); b[p++] = 0; }
        b[p++] = 0; b[p++] = 0;
        int off = b.length - 29 * 4;
        int[] def = new int[29];
        // libfuji 布局（真机 629 字节包实测一致）
        def[D185.Idx.SHOOTING_COND] = 2;
        def[D185.Idx.FILE_TYPE] = 7;          // JPG
        def[D185.Idx.IMAGE_SIZE] = 7;         // L3:2
        def[D185.Idx.IMAGE_QUALITY] = 2;      // FINE
        def[D185.Idx.EXPOSURE_BIAS] = 0;
        def[D185.Idx.DYNAMIC_RANGE] = 100;
        def[D185.Idx.WIDE_DRANGE] = 0;
        def[D185.Idx.FILM_SIM] = 17;          // CLASSIC Neg.
        def[D185.Idx.GRAIN] = 1;              // Off
        def[D185.Idx.CHROME] = 1;             // Off
        def[D185.Idx.WB_SHOOT_COND] = 1;      // As Shot
        def[D185.Idx.WB_MODE] = 2;            // Auto
        def[D185.Idx.WB_COLOR_TEMP] = 5500;
        def[D185.Idx.NOISE_RED] = 0x2000;     // 0
        def[D185.Idx.FX_BLUE] = 1;            // Off
        def[D185.Idx.CLARITY] = 0;
        for (int i = 0; i < 29; i++) {
            int o = off + i * 4;
            b[o] = (byte) def[i]; b[o + 1] = (byte) (def[i] >>> 8);
            b[o + 2] = (byte) (def[i] >>> 16); b[o + 3] = (byte) (def[i] >>> 24);
        }
        return b;
    }
}
