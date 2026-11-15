package com.fotufilm.xraw;

/**
 * 相机自定义配方槽位（C1–C4，X-M5 共 4 个）的属性码与写入顺序。
 *
 * 事实来源（三方一致）：
 *  - X-M5 官方手册：EDIT/SAVE CUSTOM SETTING 为 CUSTOM 1–4
 *  - zgrimz fujifilm-ptp-reference（覆盖 X-M5 fw1.30）：4 banks
 *  - FujiStyle 实测表：X-M5 | C1–C4
 *
 * 协议：0xD18C 选槽 → 0xD18D 名称（PTP 字符串）→ 0xD18E…0xD1A4 共 23 项 u16。
 *
 * **写入顺序不是随便排的**（zgrimz docs/04-custom-slots.md，X RAW Studio 抓包）：
 *  - 大体升序
 *  - Kelvin(0xD19C) 紧跟 WB 模式(0xD199)；WB 偏移(0xD19A/0xD19B) 在 Kelvin 之后
 *    （X-S10 上顺序错会把偏移清零）
 *  - 关闭「动态范围优先」时：先写 0xD191 再写 0xD190
 *  - 胶片模拟(0xD192) 必须在色调/色彩之前（GFX100RF 会重置它们）
 *  - 写完必须逐项回读（0x2001 不代表存进去了；颗粒"关"会被规范化成 6/7）
 */
public final class SlotProps {
    private SlotProps() { }

    public static final int SLOT_SELECT = 0xD18C;
    public static final int SLOT_NAME = 0xD18D;

    public static final int IMAGE_SIZE = 0xD18E;
    public static final int IMAGE_QUALITY = 0xD18F;
    public static final int DR_PERCENT = 0xD190;     // 100/200/400，0xFFFF=Auto
    public static final int DR_PRIORITY = 0xD191;    // 0 Off / 1 Weak / 2 Strong / 0x8000 Auto
    public static final int FILM_SIM = 0xD192;       // 1..20（19 怀旧负片 / 20 REALA ACE）
    public static final int MONO_WC = 0xD193;        // ×10，仅单色
    public static final int MONO_MG = 0xD194;        // ×10，仅单色
    public static final int GRAIN = 0xD195;          // 写 1..5；回读 Off 变 6/7
    public static final int CHROME = 0xD196;         // 1 Off / 2 Weak / 3 Strong
    public static final int FX_BLUE = 0xD197;        // 1/2/3
    public static final int SMOOTH_SKIN = 0xD198;    // 1/2/3（X-M5 不支持，返回 0x200A）
    public static final int WB = 0xD199;             // 2 Auto / 0x8020 白优先 / 0x8021 环境 / 0x8007 色温 …
    public static final int WB_SHIFT_R = 0xD19A;     // −9..+9（int16）
    public static final int WB_SHIFT_B = 0xD19B;     // −9..+9（int16）
    public static final int COLOR_TEMP = 0xD19C;     // Kelvin 原值
    public static final int HIGHLIGHT = 0xD19D;      // ×10
    public static final int SHADOW = 0xD19E;         // ×10
    public static final int COLOR = 0xD19F;          // ×10
    public static final int SHARPNESS = 0xD1A0;      // ×10
    public static final int HIGH_ISO_NR = 0xD1A1;    // 专有码表
    public static final int CLARITY = 0xD1A2;        // ×10
    public static final int LONG_EXP_NR = 0xD1A3;
    public static final int COLOR_SPACE = 0xD1A4;

    /** 槽位域名（按读取顺序） */
    public static final int[] ALL = {
            IMAGE_SIZE, IMAGE_QUALITY, DR_PERCENT, DR_PRIORITY, FILM_SIM,
            MONO_WC, MONO_MG, GRAIN, CHROME, FX_BLUE, SMOOTH_SKIN,
            WB, WB_SHIFT_R, WB_SHIFT_B, COLOR_TEMP,
            HIGHLIGHT, SHADOW, COLOR, SHARPNESS, HIGH_ISO_NR, CLARITY,
            LONG_EXP_NR, COLOR_SPACE,
    };

    public static String name(int code) {
        switch (code) {
            case SLOT_SELECT: return "槽位选择";
            case SLOT_NAME: return "名称";
            case IMAGE_SIZE: return "图像尺寸";
            case IMAGE_QUALITY: return "图像质量";
            case DR_PERCENT: return "动态范围";
            case DR_PRIORITY: return "动态范围优先";
            case FILM_SIM: return "胶片模拟";
            case MONO_WC: return "单色 WC";
            case MONO_MG: return "单色 MG";
            case GRAIN: return "颗粒";
            case CHROME: return "彩色铬";
            case FX_BLUE: return "FX 蓝";
            case SMOOTH_SKIN: return "平滑皮肤";
            case WB: return "白平衡";
            case WB_SHIFT_R: return "WB 偏移 R";
            case WB_SHIFT_B: return "WB 偏移 B";
            case COLOR_TEMP: return "色温";
            case HIGHLIGHT: return "高光色调";
            case SHADOW: return "阴影色调";
            case COLOR: return "色彩";
            case SHARPNESS: return "锐度";
            case HIGH_ISO_NR: return "降噪";
            case CLARITY: return "清晰度";
            case LONG_EXP_NR: return "长曝光降噪";
            case COLOR_SPACE: return "色彩空间";
            default: return String.format("0x%04X", code);
        }
    }
}
