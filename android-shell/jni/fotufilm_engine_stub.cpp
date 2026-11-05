/*
 * Placeholder engine behind the real Fotufilm C API (Sources/CFotufilmHost/include/fotufilm.h).
 *
 * The shipping engine is implemented in Swift on top of Halide AOT kernels and the portable
 * codecs; none of that can be produced on this host (see BUILD-REPORT.md). What is real here:
 * the ABI -- every symbol below has the signature fotufilm.h declares -- so the JNI shell, the
 * APK and the loader path are exercised end to end, and dropping in the genuine libfotufilm.so
 * later is a file replacement, not a rewrite.
 *
 * Everything that would have to compute returns FOTUFILM_ERROR with an explaining string.
 */
#include "fotufilm.h"

#include <cstdlib>
#include <cstring>
#include <string>

namespace {

int32_t fail(char **error, const char *message) {
    if (error != nullptr) {
        const size_t length = std::strlen(message);
        char *copy = static_cast<char *>(std::malloc(length + 1));
        if (copy != nullptr) {
            std::memcpy(copy, message, length + 1);
            *error = copy;
        }
    }
    return FOTUFILM_ERROR;
}

constexpr char kNoEngine[] =
    "this libfotufilm.so carries the JNI shell only: the Swift/Halide engine was not built on "
    "this host (Android NDK has no aarch64 Linux compiler and the Swift/Halide chain is absent)";

}  // namespace

/* The opaque handle fotufilm.h forward-declares; this build never hands one out. */
struct fotufilm_engine {
    int placeholder = 1;
};

extern "C" {

int32_t fotufilm_api_version(void) { return FOTUFILM_API_VERSION; }

void fotufilm_free(char *string) { std::free(string); }

char *fotufilm_capabilities(void) {
    static const char kJSON[] =
        "{\"importPath\":false,\"subjectSelection\":false,\"copyImage\":false,"
        "\"printFrames\":false,\"imageExportTypes\":[],\"hdrExport\":false,"
        "\"engine\":false,\"shell\":\"jni\",\"apiVersion\":1}";
    char *copy = static_cast<char *>(std::malloc(sizeof(kJSON)));
    if (copy == nullptr) return nullptr;
    std::memcpy(copy, kJSON, sizeof(kJSON));
    return copy;
}

fotufilm_engine *fotufilm_engine_create(char **error) {
    fail(error, kNoEngine);
    return nullptr;
}

void fotufilm_engine_destroy(fotufilm_engine *engine) { delete engine; }

char *fotufilm_engine_describe(fotufilm_engine *) { return nullptr; }

void fotufilm_engine_cancel(fotufilm_engine *) {}

void fotufilm_engine_set_presenter(fotufilm_engine *, const fotufilm_presenter *) {}

fotufilm_image *fotufilm_image_open(fotufilm_engine *, const char *, char **error) {
    fail(error, kNoEngine);
    return nullptr;
}

void fotufilm_image_size(const fotufilm_image *, uint32_t *width, uint32_t *height) {
    if (width != nullptr) *width = 0;
    if (height != nullptr) *height = 0;
}

void fotufilm_image_release(fotufilm_image *) {}

void fotufilm_render_size(const fotufilm_image *, uint32_t, uint32_t *width, uint32_t *height) {
    if (width != nullptr) *width = 0;
    if (height != nullptr) *height = 0;
}

int32_t fotufilm_render(fotufilm_engine *, fotufilm_image *, const char *,
                        const fotufilm_render_target *target, fotufilm_render_info *info,
                        char **error) {
    if (info != nullptr) {
        info->width = 0;
        info->height = 0;
        info->milliseconds = 0;
    }
    if (target == nullptr || target->pixels == nullptr || target->capacity == 0) {
        return fail(error, "render target has no pixels");
    }
    return fail(error, kNoEngine);
}

int32_t fotufilm_host_call(fotufilm_engine *, const char *, const char *, const void *, size_t,
                           fotufilm_answer *answer, char **error) {
    if (answer != nullptr) {
        answer->json = nullptr;
        answer->payload = nullptr;
        answer->payload_length = 0;
    }
    return fail(error, kNoEngine);
}

void fotufilm_answer_free(fotufilm_answer *answer) {
    if (answer == nullptr) return;
    std::free(answer->json);
    std::free(answer->payload);
    answer->json = nullptr;
    answer->payload = nullptr;
    answer->payload_length = 0;
}

int32_t fotufilm_host_call_progress(fotufilm_engine *engine, const char *method,
                                    const char *params_json, const void *payload,
                                    size_t payload_length, fotufilm_progress_callback,
                                    void *, fotufilm_answer *answer, char **error) {
    return fotufilm_host_call(engine, method, params_json, payload, payload_length, answer, error);
}

}  // extern "C"
