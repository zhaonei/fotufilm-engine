/* SPDX-License-Identifier: MIT —— d185 编解码，布局与语义参考 petabyt/libfuji (fp/src/d185.c) */
#include <string.h>
#include "fp_d185.h"

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void wr16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

int fp_d185_parse(const uint8_t *buf, int len, fp_d185 *out) {
    if (!buf || !out || len < 64) return -1;
    memset(out, 0, sizeof(*out));

    out->prop_count = rd16(buf);
    int n = out->prop_count;
    if (n <= 0 || n > 64) return -2;

    /* IOPCode：u8 字符数 + UTF-16LE，逐字符当十六进制数字拼起来 */
    int slen = buf[2];
    int p = 3;
    uint32_t code = 0;
    for (int i = 0; i < slen && p + 1 < len; i++) {
        uint16_t wc = rd16(buf + p);
        p += 2;
        if (wc == 0) break;
        int d;
        if (wc >= '0' && wc <= '9') d = wc - '0';
        else if (wc >= 'A' && wc <= 'F') d = wc - 'A' + 10;
        else if (wc >= 'a' && wc <= 'f') d = wc - 'a' + 10;
        else break;
        if (i > 8) break;
        code = (code << 4) | (uint32_t)d;
    }
    out->iop_code = code;

    /* 属性区固定 0x201（libfuji 布局）。
     * 注意：相机返回包里 u16@0 实测是 30（不是属性个数），
     * 若按 len - n*4 计算会得到 509，导致整条数组错位一格 →
     * 差量比较把几乎所有字段都当成"已修改"，最终把画面写成一片绿。
     * 只有包短到放不下 29 个 u32 时才回退。 */
    int base = FP_D185_PROPS_OFFSET;
    if (base + FP_D185_PROPS * 4 > len) {
        base = len - n * 4;
        if (base < 3) return -3;
    }

    for (int i = 0; i < FP_D185_PROPS; i++) {
        int off = base + i * 4;
        out->v[i] = (off + 4 <= len) ? rd32(buf + off) : 0;
    }
    return 0;
}

int fp_d185_create(const fp_d185 *in, uint8_t *out, int out_len) {
    if (!in || !out || out_len < FP_D185_SIZE) return -1;
    memset(out, 0, FP_D185_SIZE);

    wr16(out, (uint16_t)FP_D185_PROPS);

    /* IOPCode 以 8 位十六进制大写字符串写入 */
    char iop[16];
    uint32_t code = in->iop_code;
    for (int i = 7; i >= 0; i--) { iop[i] = "0123456789ABCDEF"[code & 0xF]; code >>= 4; }
    iop[8] = 0;
    out[2] = 9;                      /* 8 字符 + 结尾 */
    int p = 3;
    for (int i = 0; i < 8; i++) { wr16(out + p, (uint16_t)iop[i]); p += 2; }
    wr16(out + p, 0);

    for (int i = 0; i < FP_D185_PROPS; i++) {
        wr32(out + FP_D185_PROPS_OFFSET + i * 4, in->v[i]);
    }
    return FP_D185_SIZE;
}
