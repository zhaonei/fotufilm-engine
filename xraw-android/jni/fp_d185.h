/* d185 (RawConvProfile) 编解码 —— 布局严格对齐 petabyt/libfuji fp/src/d185.c
 *
 *   0x000  u16 n_props = 0x1D (29)
 *   0x002  u8  strlen（含结尾 0 的字符数）
 *   0x003  UTF-16LE IOPCode 字符串（如 "00179500" 或 "FF159510"）
 *   ...    补 0 到 0x201
 *   0x201  29 × u32 LE
 *   总长   0x201 + 29*4 = 629
 *
 * SPDX-License-Identifier: MIT（布局与语义取自 libfuji，作者 Daniel Cook）
 */
#ifndef FP_D185_H
#define FP_D185_H

#include <stdint.h>

#define FP_D185_PROPS        29
#define FP_D185_PROPS_OFFSET 0x201
#define FP_D185_SIZE         (FP_D185_PROPS_OFFSET + FP_D185_PROPS * 4)   /* 629 */

/* 29 个字段（顺序即 libfuji D185Props） */
typedef struct {
    uint32_t v[FP_D185_PROPS];
    uint32_t iop_code;      /* 解析出的 IOPCode（十六进制数值） */
    int      prop_count;    /* 从包里读到的 n_props */
} fp_d185;

/* 解析；返回 0 成功。传入长度需 >= 64。 */
int  fp_d185_parse(const uint8_t *buf, int len, fp_d185 *out);

/* 序列化为 629 字节标准包；返回写入长度，<0 失败 */
int  fp_d185_create(const fp_d185 *in, uint8_t *out, int out_len);

#endif
