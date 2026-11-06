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
import 'package:flutter/material.dart' show SelectableText;
import 'package:flutter/services.dart' show Clipboard, ClipboardData;
import 'package:flutter/widgets.dart';

/// The lines, newest last, capped so a long session cannot grow without bound.
class LogTape {
  static const int capacity = 400;

  static final List<String> _lines = [];
  static final ValueNotifier<int> _revision = ValueNotifier<int>(0);
  static final ValueNotifier<String?> _flash = ValueNotifier<String?>(null);

  static List<String> get lines => List<String>.unmodifiable(_lines);

  /// Every line, oldest first, as one block: what the copy button puts on the clipboard.
  static String get text => _lines.join('\n');
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
  bool _copied = false;
  Timer? _dismiss;
  Timer? _copiedFor;
  final ScrollController _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    LogTape.flash.addListener(_onFlash);
  }

  @override
  void dispose() {
    LogTape.flash.removeListener(_onFlash);
    _dismiss?.cancel();
    _copiedFor?.cancel();
    _scroll.dispose();
    super.dispose();
  }

  /// Puts the whole tape on the clipboard, to paste into a message.
  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: LogTape.text));
    _copiedFor?.cancel();
    setState(() => _copied = true);
    _copiedFor = Timer(const Duration(seconds: 2), () {
      if (mounted) setState(() => _copied = false);
    });
  }

  void _toggleTape() {
    setState(() => _open = !_open);
    if (!_open) return;
    // The last line is usually the reason the tape was opened.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
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
          bottom: 36,
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
                    onTap: _copy,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      child: Text(_copied ? '已复制' : '复制',
                          style: TextStyle(
                              color: _copied ? const Color(0xFF9AD08A) : _ink, fontSize: 12)),
                    ),
                  ),
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
                    if (LogTape.lines.isEmpty) {
                      return const Padding(
                        padding: EdgeInsets.all(10),
                        child: Text('暂无日志', style: TextStyle(color: _muted, fontSize: 12)),
                      );
                    }
                    // One selectable block instead of a row per line: this is text meant to be
                    // handed to someone else, so it has to be selectable and copyable whole.
                    // Oldest first, and opening the tape scrolls to the newest line.
                    return SingleChildScrollView(
                      controller: _scroll,
                      padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
                      child: SelectableText(
                        LogTape.text,
                        style: const TextStyle(color: _ink, fontSize: 10.5, height: 1.35),
                      ),
                    );
                  },
                ),
              ),
            ]),
          ),
        ),
      // A small gear in the corner rather than a labelled button: the tape is for the times
      // something is wrong, not something to look at while editing. Tap to open, tap to close.
      Positioned(
        right: 8,
        bottom: 8,
        child: GestureDetector(
          onTap: _toggleTape,
          child: DecoratedBox(
            decoration: BoxDecoration(
                color: _open ? const Color(0x33FFFFFF) : const Color(0x22000000)),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
              child: ValueListenableBuilder<int>(
                valueListenable: LogTape.revision,
                builder: (context, _, __) => Text('⚙',
                    style: TextStyle(
                        color: _open ? _ink : const Color(0x66E8E2D8), fontSize: 15)),
              ),
            ),
          ),
        ),
      ),
    ]);
  }
}
