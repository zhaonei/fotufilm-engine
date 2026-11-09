import com.fotufilm.xraw.D185;
import com.fotufilm.xraw.Recipe;

/**
 * JVM 自测：验证 d185 编解码 / 配方往返 / 抑制规则。
 * 只用到 android.jar 里的空壳类（不触发任何真机 API）。
 *
 *   javac -cp android.jar -d out/test tools/selftest/*.java + 需要的 app 源码
 *   java  -cp android.jar:out/test:out/classes SelfTest
 */
public final class SelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] a) throws Exception {
        byte[] raw = fakeD185("00179500");
        D185 p = D185.parse(raw);
        check("size>=628", p.size() >= D185.MIN_SIZE);
        check("iop=0x179500", p.iopCode() == 0x00179500);
        check("isXProcessor5", p.isXProcessor5());

        // 1) 往返：d185 → Recipe → d185，除 unknown 外应逐字段相等
        D185 base = D185.parse(raw);
        Recipe r = Recipe.fromD185(base);
        D185 out = D185.parse(base.raw());
        r.applyTo(out);
        int mismatch = 0;
        for (int i = 0; i < D185.PROP_COUNT; i++) {
            if (i == D185.IDX_UNKNOWN_23 || i == D185.IDX_UNKNOWN_28 || i == D185.IDX_UNKNOWN_29) continue;
            if (i == D185.IDX_EXPOSURE_BIAS) continue;      // 1/3 档取整，允许误差
            if (base.get(i) != out.get(i)) {
                mismatch++;
                System.out.printf("  MISMATCH idx=%d base=%d out=%d%n", i, base.get(i), out.get(i));
            }
        }
        check("往返无字段漂移", mismatch == 0);

        // 2) 高光/阴影：机内整数刻度 → 线上 ×10
        r.highlight = 2;    // +2
        r.shadow = -2;      // -2
        D185 p2 = D185.parse(base.raw());
        r.applyTo(p2);
        check("高光 +2 → 20", p2.get(D185.IDX_HIGHLIGHT) == 20);
        check("阴影 -2 → -20", p2.get(D185.IDX_SHADOW) == -20);

        // 2b) 整数刻度上限保护
        r.highlight = 99;
        D185 p2b = D185.parse(base.raw());
        r.applyTo(p2b);
        check("高光越界被 clamp 到 +4", p2b.get(D185.IDX_HIGHLIGHT) == 40);
        r.highlight = 0;

        // 2c) 色彩/清晰度同为整数刻度 ×10
        r.color = -3; r.clarity = 4;
        D185 p2c = D185.parse(base.raw());
        r.applyTo(p2c);
        check("色彩 -3 → -30", p2c.get(D185.IDX_COLOR) == -30);
        check("清晰度 +4 → 40", p2c.get(D185.IDX_CLARITY) == 40);

        // 3) DR 不得高于原 RAF 的 DR
        r.dr = D185.DR_400;
        r.drPriority = D185.DRP_OFF;
        D185 p3 = D185.parse(base.raw());
        r.applyTo(p3);
        p3.sanitize(D185.DR_100);
        check("DR clamp 到 100", p3.dr() == D185.DR_100);

        // 4) DR 优先开启时不写 DR
        r.drPriority = D185.DRP_STRONG;
        D185 p4 = D185.parse(base.raw());
        int before = p4.dr();
        r.applyTo(p4);
        p4.sanitize(before);
        check("DR 优先时不动 DR", p4.dr() == before);

        // 5) 颗粒复合编码：STRONG+LARGE = 3+2 = 5
        r.grainEffect = D185.GRAIN_STRONG;
        r.grainLarge = true;
        D185 p5 = D185.parse(base.raw());
        r.applyTo(p5);
        check("颗粒 STRONG+LARGE=5", p5.get(D185.IDX_GRAIN) == 5);

        // 6) 降噪非线性映射
        check("NR +4 → 0x5000", D185.nrFromStep(4) == 0x5000);
        check("NR 0  → 0x2000", D185.nrFromStep(0) == 0x2000);
        check("NR -4 → 0x8000", D185.nrFromStep(-4) == 0x8000);
        check("NR 反查 0x3000 → -1", D185.nrToStep(0x3000) == -1);

        // 7) 单色模拟识别
        check("ACROS 属单色", isMono(12));
        check("CLASSIC Neg 非单色", !isMono(17));

        // 8) 文件名/扩展名
        r.fileTypeIdx = 2;
        check("TIFF16 扩展名 .tif", ".tif".equals(r.exportExtension()));
        r.fileTypeIdx = 0;
        check("JPEG 扩展名 .jpg", ".jpg".equals(r.exportExtension()));

        // 8b) 胶片模拟：BB = ETERNA BLEACH BYPASS 必须在列表里，且菜单顺序首位是 PROVIA
        boolean bb = false;
        for (int v : D185.SIM_VALUES) if (v == 18) bb = true;
        check("包含 BB（ETERNA BLEACH BYPASS = 18）", bb);
        check("菜单首位为 PROVIA(1)", D185.SIM_VALUES[0] == 1);
        check("BB 显示名含 BB", D185.simName(18).contains("BB"));

        // 8c) 白平衡：白色优先 / 环境优先
        boolean wp = false, ap = false;
        for (int v : D185.WB_VALUES) { if (v == 0x8020) wp = true; if (v == 0x8021) ap = true; }
        check("白平衡含白色优先 0x8020", wp);
        check("白平衡含环境优先 0x8021", ap);

        // 9) 曝光补偿 1/3 档编码
        r.expBiasThirds = 1;
        D185 p6 = D185.parse(base.raw());
        r.applyTo(p6);
        check("+1/3EV → 333", p6.get(D185.IDX_EXPOSURE_BIAS) == 333);

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : ("FAILURES: " + failed + " / " + (passed + failed)));
        if (failed != 0) System.exit(1);
    }

    private static boolean isMono(int sim) {
        D185 p = fakeParse();
        p.set(D185.IDX_FILM_SIM, sim);
        return p.isMono();
    }

    private static D185 fakeParse() {
        try { return D185.parse(fakeD185("00179500")); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    /** 造一个 629 字节的 d185：0x201 前放 IOPCode，之后 29 个 u32 填一组合理默认值 */
    static byte[] fakeD185(String iop) {
        byte[] b = new byte[0x275];
        b[0] = (byte) 0x1D; b[1] = 0;
        b[2] = (byte) (iop.length() + 1);
        int p = 3;
        for (int i = 0; i < iop.length(); i++) {
            b[p++] = (byte) iop.charAt(i);
            b[p++] = 0;
        }
        b[p++] = 0; b[p++] = 0;
        int[] defaults = {
                1, 7, 7, 2, 0, 100, 0, 17, 1, 1, 1, 2, 0, 0, 5500, 0, 0, 0, 0, 0x2000,
                1, 1, 0, 0, 0, 0, 0, 0, 0
        };
        for (int i = 0; i < 29; i++) {
            int v = defaults[i];
            int o = 0x201 + i * 4;
            b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8);
            b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
        }
        return b;
    }
}
