package com.fotufilm.xraw;

import java.io.File;

/**
 * d185 参数快照：把相机返回的 629 字节配置包**逐字段展开**成人类可读的表。
 *
 * 用途：调试。你现在可以在 App 里「自检 → ⑧」一键导出，文件落在
 *   下载/FotofilmXRAW/d185_snapshot.txt
 * 内容包含：会话信息、字段表（索引 / 原始值 / 十六进制 / 解码含义）、以及疑似异常标记。
 */
public final class D185Dump {

    private D185Dump() { }

    /** 29 个字段的名称（libfuji D185Props 顺序，已用真机 629 字节包核对） */
    private static final String[] NAMES = {
            "ShootingCondition 拍摄条件",
            "FileType 文件类型",
            "ImageSize 图像尺寸",
            "ImageQuality 图像质量",
            "ExposureBias 曝光补偿",
            "DynamicRange 动态范围",
            "WideDRange 动态范围优先",
            "FilmSimulation 胶片模拟",
            "GrainEffect 颗粒",
            "ColorChrome 彩色铬",
            "WBShootCond 白平衡拍摄条件",
            "WhiteBalance 白平衡模式",
            "WBShiftR 白平衡偏移R",
            "WBShiftB 白平衡偏移B",
            "WBColorTemp 色温",
            "HighlightTone 高光色调",
            "ShadowTone 阴影色调",
            "Color 色彩",
            "Sharpness 锐度",
            "NoiseReduction 降噪",
            "LensModulationOpt 镜头调制优化器",
            "ColorSpace 色彩空间",
            "BlackImageTone 单色WC/校准",
            "unknown_23 未识别",
            "ColorChromeFXBlue 彩色FX蓝",
            "MonoColorRG 单色MG",
            "Clarity 清晰度",
            "unknown_27 未识别",
            "unknown_28 未识别",
    };

    public static String report(D185 p, Fuji.Session s, File raf) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== d185 参数快照 =====\n");
        sb.append("生成时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date())).append('\n');
        if (s != null) {
            sb.append("相机: ").append(s.model).append("  固件: ").append(s.version)
                    .append("  序列号: ").append(s.serial).append('\n')
                    .append("USB 模式(0xD16E): ").append(s.usbMode).append(" (6 = RAW CONV.)\n");
        }
        if (raf != null) {
            sb.append("RAF: ").append(raf.getName())
                    .append("  ").append(raf.length() / 1024 / 1024).append(" MB\n");
        }
        sb.append("包长: ").append(p.rawLength())
                .append("  包内 nProps(u16@0): ").append(p.rawPropCount())
                .append("  IOPCode: 0x").append(Integer.toHexString(p.iopCode()))
                .append("  解析布局: 0x201/29")
                .append("  native解码: ").append(FpNative.available() ? "C层" : "Java兜底")
                .append("\n\n");

        sb.append("idx | 字段                              | 原始值      | 十六进制   | 解码含义\n");
        sb.append("----+-----------------------------------+-------------+------------+------------------------------\n");
        int[] v = p.values();
        for (int i = 0; i < D185.PROPS; i++) {
            sb.append(String.format(java.util.Locale.US, "%3d | %-33s | %11d | 0x%08X | %s%n",
                    i, NAMES[i], v[i], v[i], decode(i, v[i])));
        }

        sb.append("\n===== 关键结论 =====\n");
        sb.append("胶片模拟 = ").append(p.filmSim()).append("  →  ").append(D185.simName(p.filmSim()));
        if (p.filmSim() < 1 || p.filmSim() > 20) sb.append("   ⚠ 超出 1..20，索引可能错位");
        sb.append('\n');
        sb.append("动态范围 = ").append(p.dr());
        if (p.dr() != 100 && p.dr() != 200 && p.dr() != 400) sb.append("   ⚠ 应为 100/200/400 或 0xFFFF(Auto)");
        sb.append('\n');
        sb.append("动态范围优先 = ").append(p.drPriority()).append('\n');
        sb.append("白平衡: 模式=0x").append(Integer.toHexString(p.wbMode()))
                .append(", AsShot=").append(p.wbShootCond() == D185.WB_SHOOT_ON ? "是" : "否")
                .append(", R=").append((short) (p.get(D185.Idx.WB_SHIFT_R) & 0xFFFF))
                .append(", B=").append((short) (p.get(D185.Idx.WB_SHIFT_B) & 0xFFFF))
                .append(", K=").append(p.get(D185.Idx.WB_COLOR_TEMP)).append('\n');
        sb.append("色调: 高光=").append(D185.decodeTenths(p.get(D185.Idx.HIGHLIGHT)))
                .append(", 阴影=").append(D185.decodeTenths(p.get(D185.Idx.SHADOW)))
                .append(", 色彩=").append(D185.decodeTenths(p.get(D185.Idx.COLOR)))
                .append(", 锐度=").append(D185.decodeTenths(p.get(D185.Idx.SHARPNESS)))
                .append(", 清晰度=").append(D185.decodeTenths(p.get(D185.Idx.CLARITY))).append('\n');
        sb.append("降噪: 原始=0x").append(Integer.toHexString(p.get(D185.Idx.NOISE_RED)))
                .append(" → 档位=").append(D185.nrDecode(p.get(D185.Idx.NOISE_RED))).append('\n');
        sb.append("颗粒: ").append(grainText(p.get(D185.Idx.GRAIN))).append('\n');
        sb.append("\n【怎么用这份快照】\n");
        sb.append("1) 这就是这台 X-M5 对这张 RAF 给出的**原始参数包**，我按它对齐字段。\n");
        sb.append("2) 若你换一张**不同设置**拍的 RAF（例如只用 ACROS、高光+2、WB 日光），\n");
        sb.append("   再导一份快照，两份 diff 就能确定每个字段的语义——不需要抓包。\n");
        sb.append("3) 请把 d185_snapshot.txt 发我即可（文本很小）。\n");
        return sb.toString();
    }

    private static String decode(int idx, int v) {
        switch (idx) {
            case D185.Idx.FILE_TYPE:
                return v == 7 ? "JPG" : v == 9 ? "TIFF 8bit" : v == 11 ? "TIFF 16bit" : "?";
            case D185.Idx.IMAGE_SIZE:
                return v == 7 ? "L 3:2" : v == 8 ? "L 16:9" : v == 9 ? "L 1:1"
                        : v == 4 ? "M 3:2" : v == 5 ? "M 16:9" : v == 6 ? "M 1:1"
                        : v == 1 ? "S 3:2" : v == 2 ? "S 16:9" : v == 3 ? "S 1:1" : "?";
            case D185.Idx.IMAGE_QUALITY:
                return v == 2 ? "FINE" : v == 3 ? "NORMAL" : "?";
            case D185.Idx.EXPOSURE_BIAS:
                return String.format(java.util.Locale.US, "%.2f EV", v / 1000.0);
            case D185.Idx.DYNAMIC_RANGE:
                return v == 0xFFFF ? "Auto" : "DR" + v;
            case D185.Idx.WIDE_DRANGE:
                return v == 0 ? "关闭" : v == 1 ? "弱" : v == 2 ? "强" : v == 0x8000 ? "Auto" : "?";
            case D185.Idx.FILM_SIM:
                return D185.simName(v);
            case D185.Idx.GRAIN:
                return grainText(v);
            case D185.Idx.CHROME:
            case D185.Idx.FX_BLUE:
                return v == 1 ? "关闭" : v == 2 ? "弱" : v == 3 ? "强" : "?";
            case D185.Idx.WB_SHOOT_COND:
                return v == 1 ? "按拍摄时(AsShot)" : v == 2 ? "使用指定白平衡" : "?";
            case D185.Idx.WB_MODE:
                return wbText(v);
            case D185.Idx.WB_SHIFT_R:
            case D185.Idx.WB_SHIFT_B:
                return String.valueOf((short) (v & 0xFFFF));
            case D185.Idx.WB_COLOR_TEMP:
                return v == 0 ? "未使用" : v + " K";
            case D185.Idx.HIGHLIGHT:
            case D185.Idx.SHADOW:
            case D185.Idx.COLOR:
            case D185.Idx.SHARPNESS:
            case D185.Idx.CLARITY:
                return (v & 0xFFFF) == 0x8000 ? "哨兵(沿用EXIF)" : signedTenths(v);
            case D185.Idx.NOISE_RED:
                return "档位 " + D185.nrDecode(v);
            case D185.Idx.LMO:
                return v == 1 ? "开" : v == 2 ? "关" : "?";
            case D185.Idx.COLOR_SPACE:
                return v == 2 ? "sRGB" : v == 1 ? "AdobeRGB(待验证)" : "?";
            default:
                return "";
        }
    }

    private static String grainText(int v) {
        switch (v) {
            case 1: case 6: return "关闭(小)";
            case 7: return "关闭(大)";
            case 2: return "弱/小";
            case 3: return "强/小";
            case 4: return "弱/大";
            case 5: return "强/大";
            default: return "?";
        }
    }

    private static String wbText(int v) {
        for (int i = 0; i < D185.WB_VALUES.length; i++) {
            if ((D185.WB_VALUES[i] & 0xFFFF) == (v & 0xFFFF)) return D185.WB_NAMES[i];
        }
        return "0x" + Integer.toHexString(v);
    }

    private static String signedTenths(int v) {
        int s = (short) (v & 0xFFFF);
        if (s % 10 == 0) return (s > 0 ? "+" : "") + (s / 10);
        return String.format(java.util.Locale.US, "%s%.1f", s > 0 ? "+" : "", s / 10.0);
    }
}
