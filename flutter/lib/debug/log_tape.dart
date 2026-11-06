// A log you can read on the phone.
//
// The editor's diagnostics go to logcat, which needs a cable. This keeps the same lines in memory
// and draws them on the screen: a "日志" button in the corner opens them, and anything urgent --
// a call that took over a second, an export that failed, an exception out of a framework callback
// -- also arrives as a banner that goes away by itself.
//
// Everything here is plain widgets: the tape must work when the editor's own kit or theme is what
// failed.
import 'dart:async';
import 'dart:developer' as developer;

// ValueListenable lives in foundation; widgets.dart does not re-export it.
import 'package:flutter/foundation.dart' show ValueListenable, ValueNotifier;
import 'package:flutter/widgets.dart';

/// The lines, newest last, capped so a long session cannot grow without bound.
class LogTape {
  static const int capacity = 400;

  static final List<String> _lines = [];
  static final ValueNotifier<int> _revision = ValueNotifier<int>(0);
  static final ValueNotifier<String?> _flash = ValueNotifier<String?>(null);

  static List<String> get lines => List<String>.unmodifiable(_lines);
  static ValueListenable<int> get revision => _revision;
  static ValueListenable<String?> get flash => _flash;

  /// Records a line: on the tape, in logcat, and -- for [urgent] -- on the screen.
  ///
  /// [flashText] is what the banner shows when the line itself is too long for a banner, which a
  /// message carrying a stack trace always is.
  static void record(String message, {bool urgent = false, String? flashText}) {
    final now = DateTime.now();
    final stamp = '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
    _lines.add('$stamp  $message');
    if (_lines.length > capacity) _lines.removeRange(0, _lines.length - capacity);
    _revision.value++;
    developer.log(message, name: 'fotufilm');
    if (urgent) _flash.value = flashText ?? message;
  }

  static void clear() {
    _lines.clear();
    _revision.value++;
  }

  static void dismissFlash() => _flash.value = null;
}

/// The button, the panel it opens, and the banner that shows up on its own.
class LogTapeLayer extends StatefulWidget {
  const LogTapeLayer({super.key});

  @override
  State<LogTapeLayer> createState() => _LogTapeLayerState();
}

class _LogTapeLayerState extends State<LogTapeLayer> {
  bool _open = false;
  Timer? _dismiss;

  @override
  void initState() {
    super.initState();
    LogTape.flash.addListener(_onFlash);
  }

  @override
  void dispose() {
    LogTape.flash.removeListener(_onFlash);
    _dismiss?.cancel();
    super.dispose();
  }

  void _onFlash() {
    _dismiss?.cancel();
    if (LogTape.flash.value == null) return;
    // Long enough to read a sentence, short enough not to sit in the way.
    _dismiss = Timer(const Duration(seconds: 8), () {
      if (mounted) LogTape.dismissFlash();
    });
    if (mounted) setState(() {});
  }

  static const _ink = Color(0xFFE8E2D8);
  static const _muted = Color(0xFF8A857C);
  static const _panel = Color(0xE6101010);

  @override
  Widget build(BuildContext context) {
    return Stack(children: [
      if (LogTape.flash.value != null)
        Positioned(
          left: 12,
          right: 12,
          bottom: 56,
          child: GestureDetector(
            onTap: LogTape.dismissFlash,
            child: DecoratedBox(
              decoration: const BoxDecoration(color: Color(0xF02A1414)),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                child: Text(LogTape.flash.value!,
                    style: const TextStyle(color: Color(0xFFE8B4A6), fontSize: 12, height: 1.35)),
              ),
            ),
          ),
        ),
      if (_open)
        Positioned(
          right: 12,
          bottom: 44,
          width: 330,
          height: 300,
          child: DecoratedBox(
            decoration: const BoxDecoration(color: _panel),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(10, 8, 6, 6),
                child: Row(children: [
                  const Text('日志', style: TextStyle(color: _ink, fontSize: 13)),
                  const Spacer(),
                  GestureDetector(
                    onTap: LogTape.clear,
                    child: const Padding(
                      padding: EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      child: Text('清空', style: TextStyle(color: _muted, fontSize: 12)),
                    ),
                  ),
                  GestureDetector(
                    onTap: () => setState(() => _open = false),
                    child: const Padding(
                      padding: EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      child: Text('关闭', style: TextStyle(color: _muted, fontSize: 12)),
                    ),
                  ),
                ]),
              ),
              Expanded(
                child: ValueListenableBuilder<int>(
                  valueListenable: LogTape.revision,
                  builder: (context, _, __) {
                    final lines = LogTape.lines;
                    if (lines.isEmpty) {
                      return const Padding(
                        padding: EdgeInsets.all(10),
                        child: Text('暂无日志', style: TextStyle(color: _muted, fontSize: 12)),
                      );
                    }
                    // Newest first: the reason for opening this is almost always the last line.
                    return ListView.builder(
                      padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
                      itemCount: lines.length,
                      itemBuilder: (context, i) => Padding(
                        padding: const EdgeInsets.only(bottom: 3),
                        child: Text(lines[lines.length - 1 - i],
                            style: const TextStyle(color: _ink, fontSize: 10.5, height: 1.3)),
                      ),
                    );
                  },
                ),
              ),
            ]),
          ),
        ),
      Positioned(
        right: 12,
        bottom: 12,
        child: GestureDetector(
          onTap: () => setState(() => _open = !_open),
          child: DecoratedBox(
            decoration: const BoxDecoration(color: _panel),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              child: ValueListenableBuilder<int>(
                valueListenable: LogTape.revision,
                builder: (context, _, __) => Text('日志 ${LogTape.lines.length}',
                    style: const TextStyle(color: _muted, fontSize: 11)),
              ),
            ),
          ),
        ),
      ),
    ]);
  }
}
