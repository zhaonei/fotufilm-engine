/* JNI 桥：com.fotufilm.xraw.FpNative */
#include <jni.h>
#include <stdlib.h>
#include "fp_d185.h"

JNIEXPORT jintArray JNICALL
Java_com_fotufilm_xraw_FpNative_parseD185(JNIEnv *env, jclass cls, jbyteArray in) {
    (void)cls;
    if (!in) return NULL;
    jsize len = (*env)->GetArrayLength(env, in);
    if (len < 64) return NULL;
    jbyte *buf = (*env)->GetByteArrayElements(env, in, NULL);
    fp_d185 fp;
    int rc = fp_d185_parse((const uint8_t *)buf, len, &fp);
    (*env)->ReleaseByteArrayElements(env, in, buf, JNI_ABORT);
    if (rc != 0) return NULL;

    /* 0..28 = 29 个字段；第 29 项放 IOPCode；第 30 项放 prop_count */
    jint out[32];
    for (int i = 0; i < FP_D185_PROPS; i++) out[i] = (jint)fp.v[i];
    out[FP_D185_PROPS] = (jint)fp.iop_code;
    out[FP_D185_PROPS + 1] = (jint)fp.prop_count;
    jintArray res = (*env)->NewIntArray(env, 32);
    if (res) (*env)->SetIntArrayRegion(env, res, 0, 32, out);
    return res;
}

JNIEXPORT jbyteArray JNICALL
Java_com_fotufilm_xraw_FpNative_createD185(JNIEnv *env, jclass cls, jintArray props, jint iopCode) {
    (void)cls;
    if (!props || (*env)->GetArrayLength(env, props) < FP_D185_PROPS) return NULL;
    fp_d185 fp;
    fp_d185_parse((const uint8_t *)"", 0, &fp);   /* 清零 */
    jint vals[FP_D185_PROPS];
    (*env)->GetIntArrayRegion(env, props, 0, FP_D185_PROPS, vals);
    for (int i = 0; i < FP_D185_PROPS; i++) fp.v[i] = (uint32_t)vals[i];
    fp.iop_code = (uint32_t)iopCode;
    fp.prop_count = FP_D185_PROPS;

    uint8_t out[FP_D185_SIZE];
    int n = fp_d185_create(&fp, out, sizeof(out));
    if (n <= 0) return NULL;
    jbyteArray res = (*env)->NewByteArray(env, n);
    if (res) (*env)->SetByteArrayRegion(env, res, 0, n, (const jbyte *)out);
    return res;
}
