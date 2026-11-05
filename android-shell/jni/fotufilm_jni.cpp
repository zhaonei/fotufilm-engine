/*
 * The Android shell's JNI bridge: com.fotufilm.shell.Shell calls these.
 *
 * It only speaks the public C API (fotufilm.h), so it stays valid when the placeholder engine
 * is replaced by the real libfotufilm.so: same symbols, same semantics.
 */
#include <jni.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "fotufilm.h"

namespace {

jstring to_jstring(JNIEnv *env, const std::string &text) {
    return env->NewStringUTF(text.c_str());
}

std::string quote(const char *value) { return value == nullptr ? std::string("") : value; }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_fotufilm_shell_Shell_nativeApiVersion(JNIEnv *, jclass) {
    return fotufilm_api_version();
}

JNIEXPORT jstring JNICALL
Java_com_fotufilm_shell_Shell_nativeCapabilities(JNIEnv *env, jclass) {
    char *caps = fotufilm_capabilities();
    std::string text = quote(caps);
    fotufilm_free(caps);
    return to_jstring(env, text);
}

/* Creates an engine, and says in one line how that went. */
JNIEXPORT jstring JNICALL
Java_com_fotufilm_shell_Shell_nativeCreateEngine(JNIEnv *env, jclass) {
    char *error = nullptr;
    fotufilm_engine *engine = fotufilm_engine_create(&error);
    std::string report;
    if (engine != nullptr) {
        char *description = fotufilm_engine_describe(engine);
        report = "engine created: " + quote(description);
        fotufilm_free(description);
        fotufilm_engine_destroy(engine);
    } else {
        report = "engine_create failed: " + quote(error);
    }
    fotufilm_free(error);
    return to_jstring(env, report);
}

/*
 * Drives the render entry point through the ABI structs with a 64x64 RGBA8 target and returns
 * what the engine answered -- which, with the placeholder linked in, is the explaining error.
 */
JNIEXPORT jstring JNICALL
Java_com_fotufilm_shell_Shell_nativeRenderProbe(JNIEnv *env, jclass) {
    char *error = nullptr;
    fotufilm_engine *engine = fotufilm_engine_create(&error);
    fotufilm_free(error);
    error = nullptr;

    constexpr uint32_t kEdge = 64;
    std::string pixels(kEdge * kEdge * 4, '\0');
    fotufilm_render_target target{};
    target.max_edge = kEdge;
    target.format = FOTUFILM_PIXELS_RGBA8_DISPLAY_P3;
    target.pixels = &pixels[0];
    target.row_bytes = kEdge * 4;
    target.capacity = pixels.size();
    fotufilm_render_info info{};

    const int32_t status = fotufilm_render(engine, nullptr, "{\"version\":1}", &target, &info,
                                           &error);
    char buffer[512];
    std::snprintf(buffer, sizeof(buffer),
                  "render status=%d (%s), target=%ux%u %zu bytes, message=%s", status,
                  status == FOTUFILM_OK ? "OK" : "ERROR", kEdge, kEdge, pixels.size(),
                  quote(error).c_str());
    fotufilm_free(error);
    fotufilm_engine_destroy(engine);
    return to_jstring(env, buffer);
}

}  // extern "C"
