// Fotufilm's editor drawn by Flutter, from one UI on the desktop and in a browser. The desktop
// calls libfotufilm through dart:ffi on its own isolate and presents its pictures straight into
// platform textures; a browser calls the WASM and WebGPU engine through web/src/flutter-bridge.js.
//
//   app/      the window: workspace layout, commands and menus, the host window
//   model/    the editor's state, what the engine develops, and the control catalogue
//   engine/   the engine connection (native and browser) and the native presenter
//   l10n/     the interface in Chinese
//   ui/       theme (colours, sizes, type), kit (controls), workspace and inspector
import 'dart:async';
import 'dart:developer' as developer;
import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/foundation.dart' show FlutterError;
import 'package:flutter/widgets.dart';

import 'app/app.dart';
import 'app/host_window.dart';
import 'debug/debug.dart';
import 'l10n/zh.dart';
import 'model/catalogue.dart';
import 'model/cef_profile.dart';
import 'model/editor.dart';
import 'ui/kit/icons.dart';
import 'ui/workspace/picture.dart';

void main(List<String> arguments) {
  WidgetsFlutterBinding.ensureInitialized();
  _reportErrors();
  // runApp first, always. Everything the editor needs is loaded inside the app, so a missing asset
  // or a failed engine start shows up as a readable screen instead of a black one. It used to be
  // the other way round -- four loads awaited before runApp -- and one absent asset
  // (assets/generated/controls.json, which pubspec had stopped bundling) meant no widget tree at
  // all: the app started to a permanent black screen with nothing but a bare exception in logcat.
  runApp(Boot(arguments: arguments));
}

/// Anything that escapes a framework callback reaches logcat, where `adb logcat` can see it.
void _reportErrors() {
  FlutterError.onError = (details) {
    developer.log('framework: ${details.exceptionAsString()}',
        name: 'fotufilm', error: details.exception, stackTrace: details.stack);
    FlutterError.presentError(details);
  };
  PlatformDispatcher.instance.onError = (error, stack) {
    developer.log('uncaught: $error', name: 'fotufilm', error: error, stackTrace: stack);
    return true;
  };
}

/// Loads what the editor needs while drawing something, then hands over to FotufilmApp.
class Boot extends StatefulWidget {
  const Boot({super.key, required this.arguments});
  final List<String> arguments;

  @override
  State<Boot> createState() => _BootState();
}

class _BootState extends State<Boot> {
  final GlobalKey _boundary = GlobalKey();
  EditorModel? _editor;
  Object? _failure;
  StackTrace? _stack;
  String _stage = 'Starting…';

  @override
  void initState() {
    super.initState();
    unawaited(_boot());
  }

  void _showStage(String stage) {
    if (mounted) setState(() => _stage = stage);
  }

  Future<void> _boot() async {
    try {
      await Future<void>.delayed(Duration.zero);
      _showStage('Reading the control catalogue…');
      final (catalogue, _, _, settings) =
          await (Catalogue.load(), IconLibrary.load(), ColourConversion.load(), AppSettings.load())
              .wait;
      importCefProfile(settings);
      final editor = EditorModel(catalogue, settings: settings);

      _showStage('Starting the engine…');
      if (!mounted) return;
      setState(() {
        _editor = editor;
      });
      // The picture boundary is the debug driver's way in; it has to be in the tree first.
      await WidgetsBinding.instance.endOfFrame;

      await editor.start();
      unawaited(resolveCefEdits(editor));
      // A browser engine answers in sRGB; the desktop engine's Display P3 is converted for drawing.
      ColourConversion.enabled = editor.engine?.picturesAreDisplayP3 ?? true;
      Driver.start(editor, _boundary);

      _showStage('Opening photographs…');
      await editor.openPaths(await HostWindow.takeOpenedFiles(editor.openPaths,
          arguments: widget.arguments));
      _showStage('');
    } catch (error, stack) {
      developer.log('boot failed: $error', name: 'fotufilm', error: error, stackTrace: stack);
      if (!mounted) return;
      setState(() {
        _failure = error;
        _stack = stack;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final failure = _failure;
    if (failure != null) return _BootFailure(failure: failure, stack: _stack);
    final editor = _editor;
    if (editor == null) return _BootSplash(stage: tr(_stage));
    return RepaintBoundary(key: _boundary, child: FotufilmApp(editor: editor));
  }
}

/// What the screen shows while the catalogue, the icons and the engine are coming up.
class _BootSplash extends StatelessWidget {
  const _BootSplash({required this.stage});
  final String stage;

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.ltr,
      child: ColoredBox(
        color: const Color(0xFF101010),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Fotufilm',
                  style: TextStyle(color: Color(0xFFE8E2D8), fontSize: 26, letterSpacing: 2)),
              const SizedBox(height: 14),
              Text(stage, style: const TextStyle(color: Color(0xFF8A857C), fontSize: 13)),
            ],
          ),
        ),
      ),
    );
  }
}

/// What the screen shows when the editor could not start: the failure itself, on the phone, so a
/// report can be made from the device without a cable.
class _BootFailure extends StatelessWidget {
  const _BootFailure({required this.failure, required this.stack});
  final Object failure;
  final StackTrace? stack;

  @override
  Widget build(BuildContext context) {
    final trace = (stack?.toString() ?? '').split('\n').take(12).join('\n');
    return Directionality(
      textDirection: TextDirection.ltr,
      child: ColoredBox(
        color: const Color(0xFF101010),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('编辑器启动失败',
                    style: TextStyle(color: Color(0xFFE8E2D8), fontSize: 20)),
                const SizedBox(height: 10),
                const Text('请把以下内容与 adb logcat -s fotufilm 的输出一起反馈。',
                    style: TextStyle(color: Color(0xFF8A857C), fontSize: 13)),
                const SizedBox(height: 16),
                Expanded(
                  child: SingleChildScrollView(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('$failure',
                            style: const TextStyle(color: Color(0xFFD98A7A), fontSize: 13)),
                        const SizedBox(height: 12),
                        Text(trace,
                            style: const TextStyle(
                                color: Color(0xFF7F7A72), fontSize: 11, height: 1.4)),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
