// What the engine develops, and when. One request is in flight at a time (the engine thread runs
// them in order), and only the newest edit waiting is developed next. A newer edit cancels a
// full-size develop in progress, but not a slider's draft: where a draft takes longer than the
// hand takes to move again (a phone's GPU), cancelling each would leave the picture still until
// the slider was let go.
//
//   1. preview  the whole photograph at the size it is shown (smaller while a slider moves)
//   2. detail   when magnified and settled, only the visible region at full resolution
//   3. background, while nothing else waits: the histogram, the film rows and the format tiles
//
// With a native presenter the develops go straight to the screen as frames; otherwise they come
// back as PNGs.
import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart' show TargetPlatform, defaultTargetPlatform;

import '../engine/engine.dart';
import '../engine/presenter.dart';
import '../debug/log_tape.dart';
import 'catalogue.dart';
import 'crop_geometry.dart';
import 'document.dart';
import 'edit.dart';
import 'histogram.dart';
import 'view.dart';

/// What the renderer reads from the editor and tells it.
abstract interface class RenderHost {
  PhotoDocument? get active;
  ViewState get view;
  Json? stockById(String? id);
  List<Json> get stocks;

  /// Whether the developed picture's tones are drawn: by the Histogram panel, or behind the
  /// tone curve.
  bool get histogramShown;
  bool get formatDetail;

  /// The film rows on screen ('' for Normal): only their pictures develop.
  Set<String> get shownFilmRows;

  /// Whether the format picker's tiles are on screen. Each is a develop of its own and they only
  /// show in the Film panel, so nothing else should pay for them -- six of them at a quarter of a
  /// second each is the queue a slider's step used to wait behind.
  bool get formatTilesShown;

  /// Options the shown picture's develops carry beyond the edit (cropMode, stage, difference,
  /// showMask, printFrame); small pictures never carry them.
  Json renderOptions(PhotoDocument doc);

  /// The preset rows' edits, by preset id, while the Presets tab shows them.
  Map<String, Json> presetEdits(PhotoDocument doc);
  void rendered();
  void renderFailed(String message);
  void measured(HistogramData? histogram);
}

class Renderer {
  Renderer({required this.engine, required this.catalogue, required this.host, this.presenter});

  final EngineConnection engine;
  final Catalogue catalogue;
  final RenderHost host;
  final NativePresenter? presenter;

  /// The edge a slider's develops are held to while it moves, as a first guess: what the next
  /// step may afford is measured from the one before (`_draftEdgeFor`).
  static const interactiveEdge = 1200;

  /// A phone's: a draft starts here, then the feedback below decides.
  static const phoneInteractiveEdge = 600;

  /// What a step of a drag may cost: most of a frame at 120 Hz, so a draft lands before the next
  /// pointer sample and the picture keeps up with the hand. The edge follows the pixels -- a
  /// develop costs about the pixels it develops -- so the edge that fits the budget is
  /// `edge * sqrt(budget / measured)`, damped here because the fixed part of a develop (the
  /// kernel's prepare, the frame's transfer) does not shrink with the edge.
  static const draftBudgetMilliseconds = 11.0;
  static const smallestDraftEdge = 240;

  /// How long the hand has to be still before the background pictures (the film rows, the format
  /// tiles) may take the engine: a develop there costs a quarter of a second and cannot be
  /// interrupted, so starting one 250 ms after a slider step is how a drag loses its next frame.
  static const backgroundQuiet = 900;

  /// How far one step may take the edge, either way, so a noisy measurement rocks the resolution
  /// by a few percent rather than by half.
  static const draftGrowth = 1.35, draftShrink = 0.8;

  static int get _draftEdge => switch (defaultTargetPlatform) {
        TargetPlatform.android || TargetPlatform.iOS => phoneInteractiveEdge,
        _ => interactiveEdge,
      };

  /// The draft edge the next step develops at, and the photograph it was measured on: a new
  /// photograph starts from the platform's own guess again.
  int _draftEdgeNow = 0;
  int? _draftEdgePhoto;

  /// The edge a step develops at: never past what the settled picture would use (a draft larger
  /// than the picture gains nothing), never under `smallestDraftEdge`, and measured rather than
  /// assumed -- a phone whose GPU finishes a step in a few milliseconds develops a sharper draft
  /// more often than the constant above would ever ask for.
  int _draftEdgeFor(PhotoDocument doc, int settled) {
    if (_draftEdgePhoto != doc.handle) {
      _draftEdgePhoto = doc.handle;
      _draftEdgeNow = math.min(_draftEdge, settled);
    }
    final lowest = math.min(smallestDraftEdge, settled);
    return math.min(settled, _draftEdgeNow.clamp(lowest, settled).toInt());
  }

  /// When the hand last moved: a step is timed from here to the picture being on screen, which
  /// is the only latency anyone feels.
  int _stepBegan = 0;

  /// The drag being timed: its group, its steps, and what they cost.
  String? _dragGroup;
  int _dragSteps = 0;
  double _dragTotal = 0, _dragWorst = 0;

  /// Times one step of a drag from the finger to the screen, records it when it was slow, and
  /// keeps the running tally.
  void _measuredStep(String? group, int edge) {
    final began = _stepBegan;
    _stepBegan = 0;
    if (began == 0) return;
    final ms = (DateTime.now().microsecondsSinceEpoch - began) / 1000;
    LogTape.timing('滑杆/交互 ${group ?? '-'} -> 屏幕 (${edge}px)', ms);
    if (group != _dragGroup) {
      _dragGroup = group;
      _dragSteps = 0;
      _dragTotal = 0;
      _dragWorst = 0;
    }
    _dragSteps++;
    _dragTotal += ms;
    if (ms > _dragWorst) _dragWorst = ms;
  }

  /// The drag is over: one line with what the whole of it cost, so a long session's tape holds a
  /// summary per drag rather than a line per step.
  void finishDrag(String? group) {
    if (_dragSteps == 0 || (group != null && group != _dragGroup)) return;
    LogTape.note('[拖动] ${_dragGroup ?? '-'}: ${_dragSteps} 步, '
        '平均 ${(_dragTotal / _dragSteps).toStringAsFixed(1)} ms, '
        '最差 ${_dragWorst.toStringAsFixed(1)} ms');
    _dragGroup = null;
    _dragSteps = 0;
    _dragTotal = 0;
    _dragWorst = 0;
  }

  /// Folds one step's measured develop time into the next step's edge: the pixels a step could
  /// afford, approached from wherever the last one was. It never grows while a step ran long and
  /// it drops fast when one ran very long -- a stutter is worse than a softer draft.
  void _measuredDraft(Json? answer, int edge) {
    final ms = (answer?['renderMilliseconds'] as num?)?.toDouble();
    if (ms == null || !(ms > 0)) return;
    final wanted = edge * math.sqrt(draftBudgetMilliseconds / ms);
    final lowest = edge * (ms > draftBudgetMilliseconds * 2 ? 0.5 : draftShrink);
    final highest = edge * (ms > draftBudgetMilliseconds ? 1.0 : draftGrowth);
    _draftEdgeNow = wanted.clamp(lowest, highest).round();
  }

  /// The longest preview develop; past it the detail layer carries the resolution.
  static const maxPreviewEdge = 4096;

  /// A phone preview stops at 2048: its screen is around 1200 px across, so a develop past that
  /// has nothing left to show while costing the square of the pixels -- the stall that switching
  /// films shows. The desktop keeps 4096.
  static const phonePreviewEdge = 2048;

  static int get _previewCap => switch (defaultTargetPlatform) {
        TargetPlatform.android || TargetPlatform.iOS => phonePreviewEdge,
        _ => maxPreviewEdge,
      };

  bool _running = false;
  bool _foreground = false;
  bool _draft = false;
  bool _dirty = false;
  bool _interactive = false;
  Timer? _background;

  /// Set while a long call (an export) has the engine: a cancel would stop it, not a develop.
  bool holdCancel = false;

  /// Set while a movie plays or is scrubbed (video.dart): its frame in flight is never cancelled,
  /// so the newest time waits behind it, and the background pictures wait.
  bool playing = false;

  /// Which photograph's frame each native layer holds now.
  final Map<String, int> _layerOwner = {};

  bool get busy => _running || _dirty;

  /// Rows came into view while the background was past the film rows.
  bool _moreShown = false;

  /// Film rows came into view: their pictures develop once nothing else waits.
  void developShown() {
    if (_dirty) return;
    if (_running) {
      _moreShown = true;
      return;
    }
    _background?.cancel();
    _background = Timer(const Duration(milliseconds: 100), _runBackground);
  }

  /// Foreground develops placed on screen so far, for timing checks.
  int developed = 0;

  /// The shown preview's develop answer (its size, time and backend), for the viewer's status.
  Json? lastPreview;

  /// Whether `layer` holds a frame of `doc`, so the viewer may draw it.
  bool shows(String layer, PhotoDocument doc) => _layerOwner[layer] == doc.handle;

  /// Asks for the picture to follow the edit or the view. `interactive` while a control moves.
  void schedule({bool interactive = false}) {
    _interactive = interactive;
    // The wait a person feels is from the first move the picture has not answered yet, not from
    // the last one: a step measured from the newest sample reads 5 ms while the picture is half a
    // second behind.
    if (interactive && _stepBegan == 0) _stepBegan = DateTime.now().microsecondsSinceEpoch;
    _dirty = true;
    _background?.cancel();
    if (_running) {
      // A draft in flight is left alone -- cancelling every one leaves the picture still until
      // the hand stops. Anything else gives way, background thumbnails included: one of their
      // develops is a quarter of a second the next step of a drag would spend queued behind it.
      if (!(_foreground && _draft) && !holdCancel && !playing) engine.cancel();
      return;
    }
    _run();
  }

  Future<void> _run() async {
    _running = true;
    try {
      while (_dirty) {
        _dirty = false;
        final doc = host.active;
        if (doc == null) break;
        final interactive = _interactive;
        _foreground = true;
        try {
          _draft = interactive;
          await _preview(doc, interactive);
          _draft = false;
          if (!_dirty && !interactive) await _detail(doc);
        } on FotufilmCancelled {
          // A newer request is waiting.
        } catch (e) {
          host.renderFailed('$e');
        } finally {
          _foreground = _draft = false;
        }
      }
    } finally {
      _running = false;
      _lastForegroundAt = DateTime.now().millisecondsSinceEpoch;
    }
    _background = Timer(const Duration(milliseconds: 250), _runBackground);
  }

  // ── Foreground ────────────────────────────────────────────────────────────────────────────

  int _previewEdge(PhotoDocument doc, bool interactive, {bool whole = false}) {
    // The picture delivered: cropped, or the crop tool's whole frame.
    final photo = developedSize(doc, host.renderOptions(doc));
    final long = math.max(photo.width, photo.height).round();
    final view = host.view;
    // The preview is the fitted picture; magnified, the detail layer carries the pixels. A
    // `whole` picture (a print frame's) has no detail layer, so its preview carries them.
    final scale = view.magnified(photo) && !whole ? view.fitScale(photo) : view.scale(photo);
    final shown = (long * scale * view.devicePixelRatio).round();
    final settled = math.min(long, math.min(_previewCap, math.max(256, shown)));
    return interactive ? _draftEdgeFor(doc, settled) : settled;
  }

  Json _request(PhotoDocument doc, Json edit, {int? maxEdge, bool draft = false}) =>
      renderRequest(
        catalogue: catalogue,
        handle: doc.handle,
        edit: edit,
        stock: host.stockById(edit['stock'] as String?),
        maxEdge: maxEdge,
        draft: draft,
      );

  /// What a shown picture is developed from: the edit and the view's options, or the edit the
  /// options carry instead (the crop tool's, without the crop).
  String _signature(PhotoDocument doc, Json options) => options.isEmpty
      ? jsonEncode(doc.edit)
      : options.containsKey('edit')
          ? jsonEncode(options)
          : '${jsonEncode(doc.edit)}${jsonEncode(options)}';

  /// The presenter's scope: frames for the crop tool or a stage never stand in for the photograph.
  static String _scope(PhotoDocument doc, Json options) =>
      'photo:${doc.handle}|${options['cropMode'] == true ? 'crop' : 'photo'}|${options['stage'] ?? ''}';

  Future<void> _preview(PhotoDocument doc, bool interactive) async {
    final options = host.renderOptions(doc);
    final signature = _signature(doc, options);
    final edge = _previewEdge(doc, interactive, whole: options.containsKey('printFrame'));
    final shown = _Shown(developedSize(doc, options), options['cropMode'] == true);
    final presenter = this.presenter;
    if (presenter != null) {
      final current = doc.preview;
      // What is on screen is already what this request would develop: a step that only moved a
      // crop's rectangle, or a grouped change let go at the size it was already shown, costs
      // nothing.
      if (current != null && current.signature == signature &&
          math.max(current.width, current.height) == edge && shows('preview', doc)) {
        return;
      }
      final request = {..._request(doc, doc.edit, maxEdge: edge, draft: interactive), ...options};
      final answer = await engine.call('render', {
        ...request,
        // A playing movie may leave its original out (video.dart), and a draft never carries
        // one: it is not on screen while a hand moves, and it doubled a geometry step's cost.
        'original': !interactive && (options['original'] ?? true),
        'present': {'slot': 'preview', 'scope': _scope(doc, options)},
      });
      doc..shownRequest = request..shownAnswer = answer.json;
      if (interactive) {
        _measuredStep(doc.history.group, edge);
        _measuredDraft(answer.json, edge);
      }
      _askAgainIfGrainIsComing(answer.json);
      await _place(doc, answer, 'preview', signature, shown: shown);
    } else {
      if (doc.previewSignature == signature && doc.previewEdge == edge &&
          doc.previewImage != null) {
        return;
      }
      final request = {..._request(doc, doc.edit, maxEdge: edge, draft: interactive), ...options};
      final answer = await engine.call('render', {
        ...request,
        'original': !interactive && (options['original'] ?? true),
        if (doc.originalKey != null) 'haveOriginal': doc.originalKey,
      });
      doc..shownRequest = request..shownAnswer = answer.json;
      final preview = answer.bytes('preview');
      if (preview != null) doc.previewImage = await decodePng(preview);
      final original = answer.bytes('original');
      if (original != null) doc.originalImage = await decodePng(original);
      doc.originalKey = answer.json['originalKey'] as String? ?? doc.originalKey;
      shown.apply(doc);
      if (interactive) {
        _measuredStep(doc.history.group, edge);
        _measuredDraft(answer.json, edge);
      }
      _askAgainIfGrainIsComing(answer.json);
      doc.previewSignature = signature;
      doc.previewEdge = edge;
      lastPreview = answer.json;
      developed++;
      host.rendered();
    }
  }

  /// A frame that came back without film grain because the roll's crystals were still being laid
  /// down: ask for the picture again in a moment. The engine skips the grain rather than spending a
  /// minute inside a develop -- which is what a freeze after a new expired or pushed setting was --
  /// so this is how the grain arrives.
  Timer? _grainRetry;
  int _grainRetries = 0;

  void _askAgainIfGrainIsComing(Json answer) {
    if (answer['grainPending'] != true) {
      _grainRetries = 0;
      return;
    }
    if (_grainRetry?.isActive ?? false) return;
    // A minute and a half of asking: a preparation that never finishes is one that failed, and the
    // engine will not offer it again (FilmGrainWarmth remembers the ones that failed).
    if (_grainRetries >= 45) return;
    _grainRetries++;
    _grainRetry = Timer(const Duration(milliseconds: 2000), () {
      _grainRetry = null;
      schedule();
    });
  }

  /// The visible region of a magnified photograph at the screen's own resolution.
  Future<void> _detail(PhotoDocument doc) async {
    final presenter = this.presenter;
    final view = host.view;
    final photo = developedSize(doc, host.renderOptions(doc));
    // The engine frames only a whole picture, never a region of it.
    if (presenter == null || !view.magnified(photo) ||
        host.renderOptions(doc).containsKey('printFrame')) {
      doc.detail = null;
      return;
    }
    final scale = view.scale(photo);
    final region = view.visibleRegion(photo);
    if (region.isEmpty) return;
    final dpr = view.devicePixelRatio;
    final options = host.renderOptions(doc);
    final signature = _signature(doc, options);
    final current = doc.detail;
    if (current != null && current.signature == signature && current.scale == scale &&
        current.region == region && shows('detail', doc)) {
      return;
    }
    final px = _PixelRect(region.left * dpr, region.top * dpr, region.width * dpr, region.height * dpr);
    final answer = await engine.call('render', {
      ..._request(doc, doc.edit),
      ...options,
      'original': true,
      'viewport': {
        'width': (photo.width * scale * dpr).round(),
        'height': (photo.height * scale * dpr).round(),
        'region': {'x': px.x, 'y': px.y, 'width': px.width, 'height': px.height},
      },
      'present': {'slot': 'detail', 'scope': '${_scope(doc, options)}|$region@$scale'},
    });
    await _place(doc, answer, 'detail', signature, region: region, scale: scale);
  }

  /// Shows a presented frame and its original, then lays the frame out.
  Future<void> _place(PhotoDocument doc, EngineReply answer, String layer, String signature,
      {ui.Rect? region, double? scale, _Shown? shown}) async {
    final presented = answer.json['presented'] as Map?;
    if (presented == null) return;
    final frame = (presented['frame'] as num).toInt();
    final original = (presented['original'] as num?)?.toInt() ?? 0;
    await presenter!.show(frame);
    if (original != 0) await presenter!.show(original);
    _layerOwner[layer] = doc.handle;
    if (original != 0) _layerOwner['$layer.original'] = doc.handle;
    final placed = PresentedFrame(
      id: frame,
      signature: signature,
      width: (answer.json['width'] as num).toInt(),
      height: (answer.json['height'] as num).toInt(),
      region: region,
      scale: scale,
    );
    if (layer == 'preview') {
      lastPreview = answer.json;
      doc.preview = placed;
      shown?.apply(doc);
    } else {
      doc.detail = placed;
    }
    developed++;
    host.rendered();
  }

  // ── Background ────────────────────────────────────────────────────────────────────────────

  /// When the engine last finished the foreground's work.
  int _lastForegroundAt = 0;

  Future<void> _runBackground() async {
    final doc = host.active;
    if (doc == null || _running || playing) return;
    // The hand comes first for the small pictures -- a quarter of a second each, and they cannot
    // be interrupted -- but the tones drawn behind the tone curve are one small picture and are
    // wanted while a slider is held still.
    final quiet = DateTime.now().millisecondsSinceEpoch - _lastForegroundAt >= backgroundQuiet;
    if (!quiet && !host.histogramShown) {
      _background?.cancel();
      _background = Timer(const Duration(milliseconds: backgroundQuiet), _runBackground);
      return;
    }
    _running = true;
    _moreShown = false;
    try {
      if (host.histogramShown) {
        await _histogram(doc);
      } else {
        // Nothing draws it: what shows it next waits for the tones of the picture then shown.
        host.measured(null);
      }
      // The small pictures wait for the hand to stop.
      if (quiet && doc.history.group == null) {
        await _presetThumbnails(doc);
        await _filmThumbnails(doc);
        await _formatThumbnails(doc);
      }
    } catch (_) {
      // Small pictures are a convenience; the next settle tries again.
    } finally {
      _running = false;
    }
    if (_dirty) {
      _run();
    } else if (_moreShown) {
      developShown();
    }
  }

  bool _stale(PhotoDocument doc) => _dirty || host.active != doc;

  Future<void> _histogram(PhotoDocument doc) async {
    ui.Image? image;
    if (presenter != null) {
      final answer = await engine.call('presentedImage');
      final bytes = answer.bytes('preview');
      if (bytes != null) image = await decodePng(bytes);
    } else {
      image = doc.previewImage;
    }
    if (image != null) {
      host.measured(
          await HistogramData.measure(image, displayP3: engine.picturesAreDisplayP3));
    }
  }

  /// Each film row on screen: the photograph on that film, without grain or halation. Rows out
  /// of view wait until they scroll in; the set is read again after each picture, so rows that
  /// came into view meanwhile are not missed.
  Future<void> _filmThumbnails(PhotoDocument doc) async {
    final set = doc.filmThumbnails;
    final base = Map<String, dynamic>.of(doc.edit)..remove('stock');
    set.renew(jsonEncode(base));
    // '' is Normal, the row without a film. A negative is read only as a film that has a
    // negative.
    final negative = doc.edit['negative'] != null;
    final keys = [
      '',
      for (final s in host.stocks)
        if (!negative || s['readsNegative'] == true) s['id'] as String,
    ];
    while (!_stale(doc)) {
      final shown = host.shownFilmRows;
      final key = keys.where((k) => shown.contains(k) && !set.fresh.contains(k)).firstOrNull;
      if (key == null) return;
      await _thumbnail(doc, set, key, thumbnailEdit(doc.edit, key.isEmpty ? null : key), 160);
    }
  }

  /// Each preset row: the photograph with the preset pasted on, without grain or halation.
  Future<void> _presetThumbnails(PhotoDocument doc) async {
    final edits = host.presetEdits(doc);
    if (edits.isEmpty) return;
    final set = doc.presetThumbnails;
    set.renew(jsonEncode(doc.edit));
    for (final MapEntry(:key, :value) in edits.entries) {
      if (_stale(doc)) return;
      if (set.fresh.contains(key)) continue;
      await _thumbnail(doc, set, key, thumbnailEdit(value, value['stock'] as String?), 160);
    }
  }

  // A quarter of the frame each way, from the middle.
  static const _detailCrop = [
    [0.375, 0.375],
    [0.625, 0.375],
    [0.625, 0.625],
    [0.375, 0.625],
  ];

  /// Each format tile: the photograph on that gauge, whole or as a magnified detail.
  Future<void> _formatThumbnails(PhotoDocument doc) async {
    // Only while the picker shows them: they are not visible anywhere else, and the engine's
    // queue is what a drag's next step waits in.
    if (!host.formatTilesShown) return;
    if (doc.edit['stock'] == null) return;
    // A scanned negative's Film panel has no format to choose.
    if (doc.edit['negative'] != null) return;
    final set = doc.formatThumbnails;
    final detail = host.formatDetail;
    final base = Map<String, dynamic>.of(doc.edit)..remove('format');
    set.renew('${jsonEncode(base)}$detail');
    for (final format in catalogue.filmFormats) {
      if (_stale(doc)) return;
      final id = format['id'] as String;
      if (set.fresh.contains(id)) continue;
      final edit = copyEdit(doc.edit)..['format'] = id;
      if (detail) edit.addAll({'crop': _detailCrop, 'cropShape': 'rectangle'});
      // The edge caps the whole frame, and the detail is a quarter of it.
      await _thumbnail(doc, set, id, edit, detail ? 168 * 4 : 168);
    }
  }

  Future<void> _thumbnail(
      PhotoDocument doc, ThumbnailSet set, String key, Json edit, int maxEdge) async {
    try {
      final answer = await engine.call('render', {
        ..._request(doc, edit, maxEdge: maxEdge, draft: true),
        'original': false,
      });
      final bytes = answer.bytes('preview');
      if (bytes != null) set.images[key] = await decodePng(bytes);
      host.rendered();
    } catch (_) {
      // The row keeps what it showed.
    }
    set.fresh.add(key);
  }
}

/// What a preview is of, noted when it is asked for and put on the document once it shows.
class _Shown {
  const _Shown(this.size, this.uncropped);
  final ui.Size size;
  final bool uncropped;

  void apply(PhotoDocument doc) {
    doc.shownSize = size;
    doc.shownUncropped = uncropped;
  }
}

class _PixelRect {
  _PixelRect(double x, double y, double width, double height)
      : x = x.floor(),
        y = y.floor(),
        width = width.ceil(),
        height = height.ceil();
  final int x, y, width, height;
}

Future<ui.Image> decodePng(Uint8List bytes) async {
  final codec = await ui.instantiateImageCodec(bytes);
  final frame = await codec.getNextFrame();
  codec.dispose();
  return frame.image;
}
