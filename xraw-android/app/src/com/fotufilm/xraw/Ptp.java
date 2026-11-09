package com.fotufilm.xraw;

/** PTP / 富士私有常量表（出处：libfuji lib/fujiptp.h、libgphoto2 ptp.h、ILFforever 文档） */
public interface Ptp {
    int VID_FUJIFILM = 0x04CB;
    int USB_CLASS_STILL_IMAGE = 6;

    // 容器
    int CT_COMMAND = 0x0001;
    int CT_DATA = 0x0002;
    int CT_RESPONSE = 0x0003;
    int CT_EVENT = 0x0004;

    // 标准 opcode
    int OC_GET_DEVICE_INFO = 0x1001;
    int OC_OPEN_SESSION = 0x1002;
    int OC_CLOSE_SESSION = 0x1003;
    int OC_GET_STORAGE_IDS = 0x1004;
    int OC_GET_STORAGE_INFO = 0x1005;
    int OC_GET_NUM_OBJECTS = 0x1006;
    int OC_GET_OBJECT_HANDLES = 0x1007;
    int OC_GET_OBJECT_INFO = 0x1008;
    int OC_GET_OBJECT = 0x1009;
    int OC_GET_THUMB = 0x100A;
    int OC_DELETE_OBJECT = 0x100B;
    int OC_GET_DEVICE_PROP_DESC = 0x1014;
    int OC_GET_DEVICE_PROP_VALUE = 0x1015;
    int OC_SET_DEVICE_PROP_VALUE = 0x1016;

    // 富士私有 opcode
    int OC_FUJI_SEND_OBJECT_INFO = 0x900C;
    int OC_FUJI_SEND_OBJECT2 = 0x900D;
    int OC_FUJI_SEND_OBJECT = 0x901D;
    int OC_FUJI_GET_DEVICE_INFO = 0x902B;

    // 响应码
    int RC_OK = 0x2001;
    int RC_GENERAL_ERROR = 0x2002;
    int RC_SESSION_NOT_OPEN = 0x2003;
    int RC_OPERATION_NOT_SUPPORTED = 0x2005;
    int RC_INVALID_TRANSACTION_ID = 0x2009;
    int RC_DEVICE_PROP_NOT_SUPPORTED = 0x2019;
    int RC_INVALID_DEVICE_PROP_VALUE = 0x201C;
    int RC_SESSION_ALREADY_OPEN = 0x201E;

    // DevicePropValue
    int DPC_FILM_SIMULATION = 0xD001;
    int DPC_USB_MODE = 0xD16E;
    int DPC_START_RAW_CONV = 0xD183;
    int DPC_IOP_CODE = 0xD184;
    int DPC_RAW_CONV_PROFILE = 0xD185;
    int DPC_TETHER_RAW_COND = 0xD186;
    int DPC_TETHER_RAW_COMPAT = 0xD187;
    int DPC_FREE_SDRAM = 0xD20E;
    int DPC_BATTERY = 0xD242;
    int DPC_RECIPE_SLOT = 0xD18C;
    int DPC_RECIPE_NAME = 0xD18D;

    int USB_MODE_RAW_CONV = 6;

    static String rcText(int rc) {
        switch (rc) {
            case RC_OK: return "成功";
            case RC_GENERAL_ERROR: return "相机拒绝（0x2002）：最常见原因——这张 RAF 不是本机（或同型号）拍摄";
            case RC_SESSION_NOT_OPEN: return "会话已失效（0x2003），请重新连接";
            case RC_OPERATION_NOT_SUPPORTED: return "该机型不支持此操作（0x2005）";
            case RC_DEVICE_PROP_NOT_SUPPORTED: return "该机型不支持此参数（0x2019）";
            case RC_INVALID_DEVICE_PROP_VALUE: return "参数值被拒绝（0x201C）：常见于「动态范围优先」开启时写 DR";
            case RC_SESSION_ALREADY_OPEN: return "会话已打开（0x201E）";
            default: return String.format("PTP 错误 0x%04X", rc);
        }
    }
}
