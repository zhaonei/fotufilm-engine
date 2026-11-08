// Fotufilm's editor drawn by Flutter, from one UI on the desktop and in a browser. The desktop
// calls libfotufilm through dart:ffi on its own isolate and presents its pictures straight into
// platform textures; a browser calls the WASM and WebGPU engine through web/src/flutter-bridge.js.
//
//   app/      the window: workspace layout, commands and menus, the host window
//   model/    the editor's state, what the engine develops, and the control catalogue
//   engine/   the engine connection (native and browser) and the native presenter
//   ui/       theme (colours, sizes, type), kit (controls), workspace and inspector
import 'dart:async';
import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/foundation.dart' show defaultTargetPlatform, kIsWeb, TargetPlatform;
import 'package:flutter/services.dart' show MethodChannel;
import 'package:flutter/widgets.dart';

import 'app/app.dart';
import 'app/host_window.dart';
import 'debug/debug.dart';
import 'debug/log_tape.dart';
import 'model/catalogue.dart';
import 'model/cef_profile.dart';
import 'model/editor.dart';
import 'ui/kit/icons.dart';
import 'ui/workspace/picture.dart';

/// Held for the life of the process: the listener unregisters when it is collected.
late AppLifecycleListener _lifecycle;

/// Clears the scratch space -- published exports and the engine's temporary decodes -- so nothing
/// of a finished photograph is left behind. Settings and presets are untouched.
Future<void> _tidyScratch() async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
  try {
    await const MethodChannel('fotufilm/files').invokeMethod<bool>('clearExports');
  } catch (_) {}
}

Future<void> main(List<String> arguments) async {
  WidgetsFlutterBinding.ensureInitialized();
  // The tape is silent at launch: the interface is clean unless someone opens 日志. Everything is
  // recorded either way, including what fails while the editor is being built.
  LogTape.showBanners = false;
  FlutterError.onError = (details) {
    LogTape.record('framework: ${details.exceptionAsString()}\n${details.stack ?? ''}');
    FlutterError.presentError(details);
  };
  PlatformDispatcher.instance.onError = (error, stack) {
    LogTape.record('uncaught: $error\n$stack', urgent: true);
    return true;
  };
  final (catalogue, _, _, settings) =
      await (Catalogue.load(), IconLibrary.load(), ColourConversion.load(), AppSettings.load())
          .wait;
  // What the previous desktop app kept comes over before the features read their settings.
  importCefProfile(settings);
  final editor = EditorModel(catalogue, settings: settings);
  final boundary = GlobalKey();
  runApp(RepaintBoundary(key: boundary, child: FotufilmApp(editor: editor)));
  await editor.start();
  await _tidyScratch();
  _lifecycle = AppLifecycleListener(
    onDetach: () {
      LogTape.clear();
      unawaited(_tidyScratch());
    },
    onPause: () => unawaited(_tidyScratch()),
  );
  unawaited(resolveCefEdits(editor));
  // A browser engine answers in sRGB; the desktop engine's Display P3 is converted for drawing.
  ColourConversion.enabled = editor.engine?.picturesAreDisplayP3 ?? true;
  Driver.start(editor, boundary);
  // What arrived: the first question a slow develop asks is which backend and films are in use.
  LogTape.record('catalogue ${catalogue.controls.length} controls; ${editor.stocks.length} stocks; '
      'engine capabilities ${editor.engine?.capabilities.keys.join(",") ?? "-"}');
  // Photographs from the Finder or the command line, then as they arrive.
  await editor.openPaths(
      await HostWindow.takeOpenedFiles(editor.openPaths, arguments: arguments));
}
