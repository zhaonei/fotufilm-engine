package com.fotufilm.shell;

/** Thin Java side of the bridge: the library does the talking, this only declares the calls. */
final class Shell {
    static {
        System.loadLibrary("fotufilm");
    }

    private Shell() {}

    static native int nativeApiVersion();

    static native String nativeCapabilities();

    static native String nativeCreateEngine();

    static native String nativeRenderProbe();
}
