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

  /// Whether a line that wants attention also shows as a banner over the picture. The tape keeps
  /// recording either way; this only decides whether it interrupts. The switch is in the tape's
  /// own header, and main.dart ties it to a setting so it survives a restart.
  static bool showBanners = true;
  static void Function(bool on)? persistBanners;

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
  static bool _recording = false;
  static String? _lastFlash;
  static DateTime? _lastFlashAt;

  static void record(String message, {bool urgent = false, String? flashText}) {
    // An exception thrown while recording -- inside the tape's own widgets, say -- must not come
    // back through FlutterError into record() again. That loop is what streamed identical banners.
    if (_recording) return;
    _recording = true;
    try {
      final now = DateTime.now();
      final stamp = '${now.hour.toString().padLeft(2, '0')}:'
          '${now.minute.toString().padLeft(2, '0')}:'
          '${now.second.toString().padLeft(2, '0')}';
      _lines.add('$stamp  $message');
      if (_lines.length > capacity) _lines.removeRange(0, _lines.length - capacity);
      _revision.value++;
      developer.log(message, name: 'fotufilm');

      // Silent unless asked: `showBanners` is off at launch and only the gear turns it on.
      if (!urgent || !showBanners) return;
      final text = flashText ?? message;
      final nowMillis = DateTime.now();
      if (text == _lastFlash &&
          _lastFlashAt != null &&
          nowMillis.difference(_lastFlashAt!) < const Duration(seconds: 3)) {
        return;                       // the same line repeating is a loop, not news
      }
      _lastFlash = text;
      _lastFlashAt = nowMillis;
      _flash.value = text;
    } finally {
      _recording = false;
    }
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

  /// Turns the banner over the picture on or off, and remembers the choice.
  void _toggleBanners() {
    setState(() => LogTape.showBanners = !LogTape.showBanners);
    LogTape.persistBanners?.call(LogTape.showBanners);
    if (!LogTape.showBanners) LogTape.dismissFlash();
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
    // Rebuild first: dismissing sets flash to null and used to return before this line, so the
    // banner stayed on screen with its timer already cancelled -- a close button that did nothing.
    if (mounted) setState(() {});
    if (LogTape.flash.value == null) return;
    // Long enough to read a sentence, short enough not to sit in the way.
    _dismiss = Timer(const Duration(seconds: 5), () {
      if (mounted) LogTape.dismissFlash();
    });
    if (mounted) setState(() {});
  }

  static const _ink = Color(0xFFE8E2D8);
  static const _muted = Color(0xFF8A857C);
  static const _panel = Color(0xE6101010);

  @override
  Widget build(BuildContext context) {
    // Read once. Reading the notifier twice and using `!` on the second read is how a banner
    // could throw inside build: the error handler then recorded again, rebuilt, and threw again --
    // an unbounded stream of identical red bars.
    final flash = LogTape.flash.value;
    return Stack(children: [
      if (flash != null)
        Positioned(
          left: 12,
          right: 12,
          bottom: 56,
          child: DecoratedBox(
            decoration: const BoxDecoration(color: Color(0xF02A1414)),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                  child: Text(flash,
                      style: const TextStyle(
                          color: Color(0xFFE8B4A6), fontSize: 12, height: 1.35)),
                ),
                // Out of the way the moment it has been read: a banner over the picture is in the
                // way of editing it, so it goes on one tap and on its own after a few seconds.
                GestureDetector(
                  onTap: LogTape.dismissFlash,
                  behavior: HitTestBehavior.opaque,
                  child: const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 10, vertical: 2),
                    child: Text('✕', style: TextStyle(color: _muted, fontSize: 14)),
                  ),
                ),
              ]),
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
                    onTap: _toggleBanners,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      child: Text(LogTape.showBanners ? '提示 开' : '提示 关',
                          style: TextStyle(
                              color: LogTape.showBanners ? _muted : const Color(0xFF9AD08A),
                              fontSize: 12)),
                    ),
                  ),
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
