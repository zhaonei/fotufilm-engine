package com.muastudio.fotufilm.flutter

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/** The editor (lib/) in Flutter's activity, with the engine's films unpacked before Dart starts. */
class MainActivity : FlutterActivity() {
    private var openedFiles: OpenedFiles? = null
    private var fujiUsb: FujiUsb? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        EngineResources.prepare(this)
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // One handler for "fotufilm/files": it opens files, and it is also where an export
        // lands (tempDir, publish, publishDir) because Android has no save dialog for
        // file_selector. Registering a second object on the same channel would replace this one.
        openedFiles = OpenedFiles(this, flutterEngine.dartExecutor.binaryMessenger)
        // The camera-as-processor road: the Fujifilm RAW conversion protocol over USB.
        fujiUsb = FujiUsb(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    @Deprecated("FlutterActivity is an Activity, not a ComponentActivity: no result launchers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (openedFiles?.onActivityResult(requestCode, resultCode, data) == true) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }
}
