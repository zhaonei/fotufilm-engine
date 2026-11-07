// The editor's state and actions: the engine and its films, the open photographs and their edit
// histories, and the workspace (panels, zoom, histogram). What the engine develops and when is the
// Renderer's business (renderer.dart).
import 'dart:async';

import 'package:flutter/services.dart' show MethodChannel;

import '../debug/log_tape.dart';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';

import '../engine/engine.dart';
import '../engine/presenter.dart';
import 'app_dialogs.dart';
import 'auto_adjust.dart';
import 'band_sets.dart';
import 'catalogue.dart';
import 'crop.dart';
import 'crop_geometry.dart';
import 'document.dart';
import 'edit.dart';
import 'edit_history.dart';
import 'export.dart';
import 'film_packs.dart';
import 'film_suggestion.dart';
import 'film_types.dart';
import 'histogram.dart';
import 'kept_edits.dart';
import 'lens.dart';
import 'negatives.dart';
import 'panel_layout.dart';
import 'photo_library.dart';
import 'photo_strip.dart';
import 'pipeline.dart';
import 'plugins.dart';
import 'presets.dart';
import 'print_frame.dart';
import 'tone_curve.dart';
import 'renderer.dart';
import 'selective.dart';
import 'settings.dart';
import 'startup.dart';
import 'strip_selection.dart';
import 'updates.dart';
import 'video.dart';
import 'view.dart';


export 'catalogue.dart' show Json;
export 'document.dart';
export 'film_types.dart';
export 'photo_library.dart';
export 'photo_strip.dart';
export 'settings.dart';
export 'view.dart';
export 'video.dart';

/// The host's file channel: used only to drop an imported copy once it has been exported.
const _forgetFile = MethodChannel('fotufilm/files');

/// The inspector's panels: the four tabs, the roll's tab for scanned negatives, and the canvas
/// tools the toolbar opens (editor-catalogue.js `inspectorPanels`, negative-document.js).
enum Panel {
  film('film', 'Film', 'film'),
  light('light', 'Expose', 'expose'),
  develop('develop', 'Develop', 'develop'),
  print('print', 'Print', 'print'),
  roll('roll', 'Roll', 'negative'),
  crop('crop', 'Crop', 'crop'),
  selective('selective', 'Selective', 'selective'),
  pipeline('pipeline', 'Pipeline', 'pipeline');

  const Panel(this.id, this.title, this.icon);
  final String id, title, icon;

  /// The inspector's tabs for every photograph.
  static const tabs = [film, light, develop, print];

  /// The canvas tools: they take the inspector over and change what the viewer shows.
  bool get isTool => this == crop || this == selective || this == pipeline;
}

class EditorModel extends ChangeNotifier implements RenderHost {
  EditorModel(this.catalogue, {AppSettings? settings})
      : settings = settings ?? AppSettings.memory() {
    // The features attach now: they add to the render options before anything develops, and
    // listen to edits before the first one.
    [crop, selective, pipeline, negatives, printFrame, keptEdits, auto, library, video];
  }

  /// The crop tool (crop.dart).
  late final crop = CropTool(this);

  /// Movies: the playhead and the transport (video.dart).
  late final video = VideoPlayer(this);

  final Catalogue catalogue;

  /// Export, Export All, Export Original and Copy Photo (export.dart).
  late final exporter = ExportTool(this);

  /// Preferences and remembered things, kept on this device.
  final AppSettings settings;

  // ── Features ──────────────────────────────────────────────────────────────────────────────
  late final history = EditHistory(this);
  late final selection = StripSelection(this);
  late final presets = Presets(this);
  late final keptEdits = KeptEdits(this);
  late final auto = AutoAdjustment(this);
  late final filmSuggestion = FilmSuggestion(this);
  EngineConnection? engine;
  NativePresenter? presenter;
  Renderer? renderer;
  String? startupError;

  bool get ready => renderer != null && stocks.isNotEmpty;

  /// The photo library, and the strip of open and waiting photos.
  late final library = PhotoLibrary(this);
  late final strip = PhotoStripModel(this);

  // ── App services (Settings, film packs, plug-ins, updates, the panels' layout) ────────────
  late final dialogs = AppDialogs();
  late final filmPacks = FilmPacks(this);
  late final plugins = Plugins(this);
  late final updates = Updates(this);
  late final layout = PanelLayout(this);
  final startup = ValueNotifier(StartupState.initial);

  /// Whether the engine has work in hand or waiting.
  bool get busy => (renderer?.busy ?? false) || status != null || _viewSettle != null;

  // ── Films ─────────────────────────────────────────────────────────────────────────────────

  /// The engine's films (`prepare`'s catalogue), in the order the library lists them.
  @override
  List<Json> stocks = [];
  final Map<String, Json> _stockById = {};

  @override
  Json? stockById(String? id) => id == null ? null : _stockById[id];

  /// The film rows on screen (FilmLibrary), the only ones whose pictures develop: a row develops
  /// once it scrolls into view.
  /// Under the photo library none are on screen.
  @override
  Set<String> get shownFilmRows => library.open ? const {} : _shownFilmRows.keys.toSet();
  final Map<String, int> _shownFilmRows = {};

  /// Counted, since a row rebuilt for another film may let go of its old one after a new row
  /// took it.
  void showFilmRow(String id, bool shown) {
    final count = (_shownFilmRows[id] ?? 0) + (shown ? 1 : -1);
    if (count > 0) {
      _shownFilmRows[id] = count;
    } else {
      _shownFilmRows.remove(id);
    }
    if (shown) renderer?.developShown();
  }

  /// The film types the library offers, and the one it shows.
  List<FilmType> filmTypes = const [FilmType.all];
  FilmType filmType = FilmType.all;

  // ── Photographs ───────────────────────────────────────────────────────────────────────────

  final List<PhotoDocument> documents = [];
  int activeIndex = -1;

  @override
  PhotoDocument? get active =>
      activeIndex >= 0 && activeIndex < documents.length ? documents[activeIndex] : null;
  Json get edit => active?.edit ?? _emptyEdit;
  late final Json _emptyEdit = defaultEdit(catalogue);
  Json? get selectedStock => stockById(edit['stock'] as String?);

  /// The default edit on film `id`, printing on the film's own medium.
  Json blankEdit(String? id) =>
      defaultEdit(catalogue, id, stockById(id)?['filmMedium'] as String?);

  /// The picture on show at full size: the cropped photograph, or the crop tool's whole frame.
  /// Until one is on show, the one asked for.
  ui.Size get photoSize => active == null
      ? ui.Size.zero
      : printFrame.framed(active!.shownSize ?? developedSize(active!, renderOptions(active!)));

  // ── Workspace ─────────────────────────────────────────────────────────────────────────────

  Panel panel = Panel.film;

  /// The inspector's tabs for the shown photograph (negative-document.js `documentPanels`).
  List<Panel> get inspectorTabs => negatives.tabs(Panel.tabs);
  bool filmOpen = true;
  bool inspectorOpen = true;
  @override
  bool histogramOpen = false;

  /// Whether the format tiles show a quarter of the frame, where grain and halation tell.
  @override
  bool formatDetail = true;
  bool presetsTab = false;
  bool get autoAdjust => auto.active;
  bool comparing = false;
  bool exporting = false;
  String search = '';
  String? status;
  HistogramData? histogram;

  /// The banner over the photograph. A new message drops the Retry of the one before.
  String? get error => _error;
  set error(String? message) {
    _error = message;
    _retry = null;
    // The picture stays clear: what went wrong is a line in the tape, not a banner over it.
    if (message != null) LogTape.record('error: $message');
  }
  String? _error;

  /// What the banner's Retry does again: opening the files that failed, or developing the
  /// photograph again. Null offers no Retry.
  Future<void> Function()? _retry;
  bool get canRetry => _retry != null;

  void retry() {
    final again = _retry;
    _set(() => error = null);
    again?.call();
  }

  /// Opening runs a file at a time; Cancel stops it after the one being read, which is let go.
  int _imports = 0, _cancelledImports = 0;

  /// The status is an opening's, which Cancel stops.
  bool get importing => status != null && _importStatus == status;
  String? _importStatus;

  void cancelImport() {
    _cancelledImports = _imports;
    _set(() => status = null);
  }

  @override
  final ViewState view = ViewState();

  /// The Expose panel's lens correction, and the Print panel's frame and receiver band sets.
  late final lens = LensCorrection(this);
  late final printFrame = PrintFrame(this);
  late final toneCurve = ToneCurve(this);
  late final bandSets = BandSets(catalogue, settings);

  /// What the foreground develops ask the engine for beyond the edit: the crop tool's uncropped
  /// frame, a pipeline stage, the selection's mask. Features add to it through
  /// [renderOptionSources].
  @override
  Json renderOptions(PhotoDocument doc) => {
        for (final source in renderOptionSources) ...source(doc),
      };
  final List<Json Function(PhotoDocument doc)> renderOptionSources = [];

  // ── Canvas tools ──────────────────────────────────────────────────────────────────────────

  late final selective = SelectiveTool(this)..addListener(notifyListeners);
  late final pipeline = PipelineTool(this)..addListener(notifyListeners);


  // ── Features ──────────────────────────────────────────────────────────────────────────────

  /// Scanned negatives: importing them, their reading, their roll and Show Negative.
  late final negatives = NegativeScans(this);

  // ── RenderHost ────────────────────────────────────────────────────────────────────────────

  @override
  void rendered() => notifyListeners();

  @override
  void renderFailed(String message) {
    // Without a film there may be nothing to develop; that is not worth a banner.
    final doc = active;
    if (doc == null || doc.edit['stock'] != null || doc.preview == null) {
      error = message;
      _retry = () async => renderer?.schedule();
    }
    notifyListeners();
  }

  /// The Presets tab's rows, while it is shown: each preset pasted onto the photograph.
  @override
  Map<String, Json> presetEdits(PhotoDocument doc) => {
        if (filmOpen && presetsTab)
          for (final preset in presets.visible)
            preset.id: ?presets.shownWith(preset, doc.edit),
      };

  @override
  void measured(HistogramData? data) {
    histogram = data;
    notifyListeners();
  }

  // ── Start-up ──────────────────────────────────────────────────────────────────────────────

  Future<void> start() async {
    try {
      startup.value = StartupState.connecting;
      final engine = await connectEngine();
      startup.value = StartupState.loadingFilms;
      await loadStocks(engine);
      final address = engine.address;
      if (address != null) presenter = await NativePresenter.attach(address);
      this.engine = engine;
      renderer = Renderer(
          engine: engine,
          catalogue: catalogue,
          host: this,
          presenter: presenter,
          fineZoomPreview: settings.get<bool>('fineZoomPreview') ?? true,
        );
      // A screen with more or less light above white takes the photograph developed for it.
      presenter?.onHeadroomChange = () => renderer?.schedule();
      startup.value = StartupState.ready;
    } catch (e) {
      startupError = '$e';
      startup.value = StartupState.failed;
    }
    notifyListeners();
    if (engine != null) {
      filmPacks.launch();
      plugins.launch();
      updates.launch();
      filmSuggestion.refresh();
    }
  }

  /// The engine's films (`prepare`'s catalogue), again after a film pack changes them.
  Future<void> loadStocks([EngineConnection? via]) async {
    final prepared = await (via ?? engine!).call('prepare');
    // Which backend the kernels run on: 'vulkan' means the AOT Vulkan kernels and the phone's GPU,
    // anything else means the CPU path. This is the line that answers "is it really using Vulkan".
    LogTape.record(
        'engine backend=${prepared.json['backend'] ?? '?'} '
        'films=${prepared.json['stocks']?.length ?? 0}');
    final ids = ((prepared.json['stocks'] as List?) ?? const []).cast<String>().toSet();
    stocks = [
      for (final entry in ((prepared.json['catalogue'] as List?) ?? const []).cast<Json>())
        if (ids.contains(entry['id'])) entry,
    ]..sort((a, b) => _sortName(a).compareTo(_sortName(b)));
    _stockById
      ..clear()
      ..addEntries(stocks.map((s) => MapEntry(s['id'] as String, s)));
    filmTypes = FilmType.offered(stocks);
    notifyListeners();
    renderer?.schedule();
  }

  /// Films by name, ignoring a maker's prefix so Aerochrome sits with the A's.
  static String _sortName(Json stock) => (stock['name'] as String)
      .replaceFirst(RegExp(r'^(Kodak|Fujifilm|Fuji|Ilford) '), '')
      .toLowerCase();

  // ── Opening and closing ───────────────────────────────────────────────────────────────────

  /// Opens photographs the system named by path (the Finder, the command line, the driver); one
  /// brings its folder into the strip. Film packs among them are installed instead.
  /// Forgets every open photograph except [keep]: called when a new one replaces them.
  /// Walks the whole control catalogue and asks the engine, for each control, whether moving it
  /// to an extreme changes a single pixel -- and whether the interface would even send it.
  ///
  /// Nothing here is hand-picked: the list comes from `catalogue.controls` and `catalogue.sliders`,
  /// so a control added upstream is covered the next time this runs. Each line reads
  ///
  ///   自检 <control>：✓ 变化 N 字节 / UI 路径会发送     the whole path works
  ///   自检 <control>：✗ 引擎无变化 / UI 路径会发送     the engine ignores it
  ///   自检 <control>：✗ … / UI 路径丢弃了 [field]      the request builder drops it
  Future<void> runLeverCheck() async {
    final doc = active, engine = this.engine;
    if (doc == null || engine == null) {
      LogTape.record('自检：请先打开一张照片');
      return;
    }
    // Film settings act on a film. On Normal every one of them is inert by design, so a stock is
    // installed for the check without touching the photograph's own edit.
    final baseEdit = <String, dynamic>{...doc.edit};
    var film = baseEdit['stock'] as String?;
    if (film == null) {
      if (stocks.isEmpty) {
        LogTape.record('自检：没有任何胶片可测');
        return;
      }
      film = stocks.first['id'] as String?;
      baseEdit['stock'] = film;
      baseEdit['medium'] = mediumOnFilm(baseEdit, stockById(film));
      LogTape.record('自检：这张照片还没装胶片，临时用 ${stocks.first['name']}');
    }
    final stockRow = stockById(film);
    final filmMedium = stockRow?['filmMedium'] as String?;
    final profileNow = (baseEdit['profile'] as Map?)?.cast<String, dynamic>() ?? const {};
    final current = <String, dynamic>{
      ...profileNow,
      'grainModel': profileNow['grainModel'] ?? stockRow?['grainModel'] ?? 'clump',
    };

    Future<List<int>?> shot(Map<String, Object?> profile, Map<String, Object?> params,
        {String? medium}) async {
      final edit = <String, dynamic>{...baseEdit};
      if (params.isNotEmpty) {
        edit['params'] = <String, dynamic>{
          ...?(baseEdit['params'] as Map?)?.cast<String, dynamic>(),
          ...params,
        };
      }
      final request = renderRequest(
        catalogue: catalogue,
        handle: doc.handle,
        edit: edit,
        stock: stockById(edit['stock'] as String?),
        maxEdge: 192,
      );
      final controls = <String, dynamic>{
        ...(request['profileRequest'] as Json)['controls'] as Json,
        ...profile,
        'digitalReference': edit['digitalReference'] ?? 'auto-levels',
        'halationModel': edit['halationModel'] ?? 'legacy',
      };
      try {
        final answer = await engine.call('render', {
          ...request,
          'profileRequest': {
            ...request['profileRequest'] as Json,
            'controls': controls,
            if (medium != null) 'medium': medium,
          },
          'original': false,
        });
        return answer.bytes('preview');
      } catch (e) {
        return null;
      }
    }

    int compare(List<int> a, List<int> b) {
      var differing = 0;
      final n = a.length < b.length ? a.length : b.length;
      for (var i = 0; i < n; i++) {
        if (a[i] != b[i]) differing++;
      }
      return differing;
    }

    /// What the interface itself would send for this patch, so a lever the request builder drops
    /// can be told apart from one the engine ignores.
    Set<String> uiSends(Map<String, Object?> profile, Map<String, Object?> params) {
      final edit = <String, dynamic>{...baseEdit};
      // Without this the helper measured the photograph's unchanged profile and reported every
      // film control as dropped.
      edit['profile'] = <String, dynamic>{
        ...?(baseEdit['profile'] as Map?)?.cast<String, dynamic>(),
        ...profile,
      };
      if (params.isNotEmpty) {
        edit['params'] = <String, dynamic>{
          ...?(baseEdit['params'] as Map?)?.cast<String, dynamic>(),
          ...params,
        };
      }
      final request = renderRequest(
        catalogue: catalogue,
        handle: doc.handle,
        edit: edit,
        stock: stockById(edit['stock'] as String?),
        maxEdge: 192,
      );
      return ((request['profileRequest'] as Json)['controls'] as Json).keys.toSet();
    }

    double extreme(num min, num max, num neutral) =>
        (neutral - min).abs() > (max - neutral).abs() ? min.toDouble() : max.toDouble();

    final baselines = <String, List<int>?>{};
    Future<List<int>?> baseline(Map<String, Object?> profile, String? medium) async {
      final key = '$profile|$medium';
      if (!baselines.containsKey(key)) {
        baselines[key] = await shot(profile, const {}, medium: medium);
      }
      return baselines[key];
    }

    Future<void> check(String name, Map<String, Object?> context, Map<String, Object?> lever,
        Map<String, Object?> params, String? medium) async {
      final base0 = await baseline(context, medium);
      if (base0 == null) {
        LogTape.record('自检 $name：基准渲染失败');
        return;
      }
      final variant = await shot({...context, ...lever}, params, medium: medium);
      final dropped = <String>[];
      if (lever.keys.isNotEmpty) {
        final sent = uiSends({...context, ...lever}, params);
        dropped.addAll(lever.keys.where((key) => !sent.contains(key)));
      }
      final uiNote = dropped.isEmpty ? 'UI 路径会发送' : 'UI 路径丢弃了 $dropped';
      if (variant == null) {
        LogTape.record('自检 $name：引擎拒收 / $uiNote');
        return;
      }
      final differing = compare(base0, variant);
      LogTape.record(differing == 0
          ? '自检 $name：✗ 引擎无变化 / $uiNote'
          : '自检 $name：✓ 变化 $differing 字节 / $uiNote');
    }

    LogTape.record('自检开始（${doc.name}，192px；胶片=$film）');
    final control0 = await shot(const {}, const {'ev': 1.0});
    final base0 = await baseline(const {}, null);
    LogTape.record(control0 == null || base0 == null || compare(base0, control0) == 0
        ? '自检[工具] 曝光 +1EV：✗ 连曝光都没变化 → 下面的结果不可信'
        : '自检[工具] 曝光 +1EV：✓ 变化 ${compare(base0, control0)} 字节 → 这台工具能测出差异');

    // The editor's own sliders (params), straight from the catalogue.
    for (final spec in catalogue.sliders) {
      await check('参数 ${spec.label}',
          const {}, <String, Object?>{}, {spec.key: extreme(spec.min, spec.max, spec.def)}, null);
    }

    // Every catalogue control, with the settings that give it something to act on.
    for (final c in catalogue.controls) {
      final field = c['field'] as String;
      final kind = c['kind'] as String?;
      if (kind == 'takeover') continue;
      final scale = c['scale'] as Map?;
      final choices = (c['choices'] as List?)?.cast<Map>() ?? const [];
      final chips = (c['chips'] as List?)?.cast<Map>() ?? const [];
      Object? value;
      if (scale != null) {
        value = extreme(
          (scale['min'] as num?) ?? 0, (scale['max'] as num?) ?? 1, (scale['neutral'] as num?) ?? 0);
      } else if (chips.isNotEmpty) {
        value = chips.last['value'];
      } else if (choices.isNotEmpty) {
        final ids = [for (final ch in choices) ch['id']];
        value = ids.firstWhere((id) => id != current[field], orElse: () => ids.last);
      } else if (kind == 'toggle') {
        value = current[field] != true;
      } else {
        continue;
      }
      final section = '${c['section'] ?? ''}';
      final context = <String, Object?>{
        if (field.startsWith('film') && field != 'film') 'grainModel': 'film',
        if (field == 'antiHalation' || field == 'baseThickness' || field == 'halationHaze')
          ...{'halation': 4.0, 'halationReturn': 0.5, 'estimatedHalation': true},
        // The illuminant sliders only exist once the source is Custom.
        if (field == 'sceneLightKelvin') 'sceneLight': 'custom',
        // A printer or a screen shows its controls only on the road that has one.
        if (section.contains('print') || section.contains('screen') || section.contains('labScan'))
          ...{'enlarger': 'on', 'printerEnabled': true},
      };
      // Print and screen controls need a medium that has a lamp or a screen, not a digital reference.
      final needsPrintRoad = section.contains('print') ||
          section.contains('screen') ||
          section.contains('labScan') ||
          field.startsWith('printer');
      final medium = needsPrintRoad ? filmMedium : null;
      await check('控件 ${c['title'] ?? field}', context, {field: value}, const {}, medium);
    }
    LogTape.record('自检结束');
  }

  Future<void> forgetOthers({Set<PhotoDocument> keep = const {}}) async {
    for (final doc in documents.where((d) => !keep.contains(d)).toList()) {
      keptEdits.forget(doc);
      library.forgetPath(doc.path);
      try {
        await _forgetFile.invokeMethod<bool>('deleteImport', {'path': doc.path});
      } catch (_) {}
      final index = documents.indexOf(doc);
      if (index >= 0) await closeDocument(index);
    }
  }

  Future<void> openPaths(List<String> paths) async {
    final packs = paths.where(isFilmPack).toList();
    if (packs.isNotEmpty) await filmPacks.importPaths(packs);
    final photos = paths.where((path) => !isFilmPack(path)).toList();
    if (photos.isEmpty) return;
    final before = {...documents};
    await strip.open([for (final path in photos) PhotoFile(path.split('/').last, path: path)]);
    if (keepOnlyCurrentPhoto) {
      // What was open before this call is replaced, not accumulated.
      await forgetOthers(keep: {...documents}.difference(before));
    }
  }

  /// Opens photographs: by path where the engine reads files itself, otherwise by their bytes.
  /// `negative` opens them as scanned negatives, read as a film (negatives.dart).
  Future<void> openFiles(List<PhotoFile> files, {bool negative = false, String? scanLight}) async {
    final engine = this.engine;
    if (engine == null || files.isEmpty) return;
    // A new photograph starts on the film the shown one is on, as the web editor's do.
    final stock = edit['stock'] as String?;
    final run = ++_imports;
    bool cancelled() => run <= _cancelledImports;
    final failed = <PhotoFile>[];
    final failures = <String>[];
    for (final file in files) {
      if (cancelled()) break;
      final key = file.path ?? file.name;
      // A negatives folder's photos are negatives however they are opened.
      final asNegative = negative || file.negative;
      final existing = documents.indexWhere((d) => d.path == key);
      if (existing >= 0) {
        _activate(existing);
        continue;
      }
      status = _importStatus = 'Opening ${file.name}…';
      notifyListeners();
      try {
        final answer = engine.opensPaths && file.path != null
            ? await engine.call('importPath', {'path': file.path, 'negative': asNegative})
            // A library photo in a browser has a path and no bytes: the bridge reads it.
            : await engine.call('importFile', {'name': file.name, 'path': file.path, 'negative': asNegative},
                file.read == null ? null : await file.read!());
        final json = answer.json;
        if (cancelled()) {
          await engine.call('release', {'handle': (json['handle'] as num).toInt()});
          break;
        }
        // A photograph opened before starts from the edit it was left with.
        final start = keptEdits.startingEdit(json['identity'] as String?, stock);
        final doc = PhotoDocument(
          path: key,
          handle: (json['handle'] as num).toInt(),
          width: (json['naturalWidth'] as num).toInt(),
          height: (json['naturalHeight'] as num).toInt(),
          identity: json['identity'] as String?,
          raw: json['original'] != null,
          headroom: ((json['hdr'] as Map?)?['headroom'] as num?)?.toDouble() ?? 1,
          // A new scan starts as a negative; one opened before keeps the edit it was left with.
          history: History(asNegative && !start.saved
              ? negatives.startingEdit(start.edit, json['negative'] as Json?, scanLight)
              : start.edit),
        )
          ..negativeScan = json['negative'] as Json?
          ..descriptor = json;
        keptEdits.opened(doc, saved: start.saved);
        filmSuggestion.opened(doc, saved: start.saved);
        final preview = answer.bytes('preview');
        if (preview != null) doc.importPicture = await decodePng(preview);
        documents.add(doc);
        _activate(documents.length - 1);
      } catch (e) {
        failed.add(file);
        failures.add('${file.name} could not be opened. $e');
      }
    }
    if (failed.isNotEmpty) {
      error = failures.join(' ');
      _retry = () => openFiles(failed, negative: negative, scanLight: scanLight);
    }
    // A later opening's status stays.
    if (run == _imports) status = null;
    notifyListeners();
  }

  void _activate(int index) {
    activeIndex = index;
    view
      ..zoom = null
      ..pan = ui.Offset.zero;
    comparing = false;
    histogram = null;
    notifyListeners();
    renderer?.schedule();
  }

  void selectDocument(int index) {
    if (index == activeIndex) return;
    endEdit();
    _activate(index);
  }

  /// Exports make a picture and nothing else: when this is on, the photograph the export came
  /// from is forgotten afterwards -- its edit, its library entry, the copy taken in from the
  /// picker -- so the app holds no trace of what it developed. Off, it behaves like a library.
  bool get forgetAfterExport => settings.get<bool>('forgetAfterExport') ?? false;

  /// Whether opening photographs replaces the ones already open. The app is a tool for the picture
  /// in front of you: the previous one's copy, edit and history go when the next arrives.
  bool get keepOnlyCurrentPhoto => settings.get<bool>('keepOnlyCurrentPhoto') ?? true;

  Future<void> forgetExported(PhotoDocument doc) async {
    if (!forgetAfterExport) return;
    final path = doc.path;
    keptEdits.forget(doc);
    library.forgetPath(path);
    var deleted = false;
    try {
      deleted = await _forgetFile.invokeMethod<bool>('deleteImport', {'path': path}) ?? false;
    } catch (e) {
      LogTape.record('forget: could not delete $path: $e');
    }
    final index = documents.indexOf(doc);
    if (index >= 0) await closeDocument(index);
    LogTape.record('forgot ${doc.name}: edit cleared, source ${deleted ? 'deleted' : 'kept'}');
  }

  Future<void> closeDocument(int index) async {
    final doc = documents.removeAt(index);
    if (index < activeIndex || activeIndex >= documents.length) activeIndex--;
    if (activeIndex < 0 && documents.isNotEmpty) activeIndex = 0;
    notifyListeners();
    // A photograph an export still develops stays loaded until it is done.
    if (exporter.needs(doc.handle)) {
      exporter.releaseAfter(doc.handle);
    } else {
      await engine?.call('release', {'handle': doc.handle});
    }
    renderer?.schedule();
  }

  // ── Editing (web/src/editor-state.js historyReducer) ─────────────────────────────────────

  bool get canUndo => active?.history.past.isNotEmpty ?? false;
  bool get canRedo => active?.history.future.isNotEmpty ?? false;
  bool get edited =>
      active != null && !sameEdit(edit, blankEdit(edit['stock'] as String?));

  /// Told of each change to a photograph's history, so features follow edits (kept edits, Auto
  /// Adjust).
  final List<void Function(EditChange change)> editListeners = [];

  /// A photograph's history changed (`kind`: edit, undo, redo, goTo or replace) from `before`:
  /// the picture follows and the features are told.
  void historyChanged(PhotoDocument doc, String kind, Json before, {bool restoring = false}) {
    notifyListeners();
    renderer?.schedule(interactive: doc.history.group != null);
    final change = EditChange(doc, kind, before, restoring: restoring);
    for (final listener in [...editListeners]) {
      listener(change);
    }
  }

  void showError(String message) => _set(() => error = message);

  /// Replaces the edit with a whole one (a paste, a preset, Load Edits), as one step.
  void restore(Json edit) {
    endEdit();
    error = null;
    patch(edit, restoring: true);
  }

  /// Changes the edit. Changes under one `group` (a slider being dragged) make one step.
  void patch(Json changes, {String? group, bool restoring = false}) {
    final doc = active;
    if (doc == null) return;
    final h = doc.history;
    final next = copyEdit(h.present)..addAll(changes);
    if (sameEdit(next, h.present)) return;
    if (group == null || h.group != group) {
      h.past.add(h.present);
      h.future.clear();
    }
    final before = h.present;
    h.present = next;
    h.group = group;
    historyChanged(doc, 'edit', before, restoring: restoring);
  }

  /// A change made in one step: a picker, a switch or a button.
  void change(Json changes) {
    endEdit();
    patch(changes);
  }

  /// Ends a grouped change (a slider let go), so the picture develops at full size.
  void endEdit() {
    final doc = active;
    if (doc == null || doc.history.group == null) return;
    doc.history.group = null;
    renderer?.schedule();
  }

  void setParam(String key, double value) =>
      patch({'params': {...(edit['params'] as Map), key: value}}, group: 'param-$key');

  /// Several adjustments moved together (the grade pad's two axes), as one step.
  void setParams(Map<String, double> values, String group) =>
      patch({'params': {...(edit['params'] as Map), ...values}}, group: group);

  void setProfile(String field, Object? value, {bool grouped = true}) {
    final profile = Map<String, dynamic>.from(edit['profile'] as Map);
    if (value == null) {
      profile.remove(field);
    } else {
      profile[field] = value;
    }
    // The grain sub-options only act when the grain model is the film one; this line says what
    // the engine is actually being told, which a slider that moves and changes nothing asks for.
    if (field.startsWith('film') || field == 'grainModel' || field == 'grainMottle') {
      LogTape.record('profile $field=$value (grainModel=${profile['grainModel']})');
    }
    // Reach sets the pair couplings, so explicit ones give way (profile-settings.js).
    if (field == 'couplerReach') {
      profile.remove('couplerRedGreen');
      profile.remove('couplerGreenBlue');
    }
    if (grouped) {
      patch({'profile': profile}, group: 'profile-$field');
    } else {
      change({'profile': profile});
    }
  }

  void chooseStock(String? id) {
    if (active == null) return;
    change({'stock': id, 'medium': mediumOnFilm(edit, stockById(id))});
  }

  void undo() => _step(back: true);
  void redo() => _step(back: false);

  void _step({required bool back}) {
    final h = active?.history;
    if (h == null || (back ? h.past : h.future).isEmpty) return;
    final before = h.present;
    if (back) {
      h.future.insert(0, h.present);
      h.present = h.past.removeLast();
    } else {
      h.past.add(h.present);
      h.present = h.future.removeAt(0);
    }
    h.group = null;
    historyChanged(active!, back ? 'undo' : 'redo', before);
  }

  void resetEdit() {
    if (active != null) change(blankEdit(edit['stock'] as String?));
  }

  void newGrainPattern() => change({'seed': math.Random().nextInt(0x7fffffff) + 1});

  /// Auto Adjust's mode on or off (auto_adjust.dart).
  void toggleAutoAdjust() => auto.toggle();

  // ── Workspace actions ─────────────────────────────────────────────────────────────────────

  void _set(VoidCallback update) {
    update();
    notifyListeners();
  }

  void setPanel(Panel next) {
    if (next == panel && inspectorOpen) return;
    final leaving = panel;
    _set(() {
      if (!panel.isTool) _lastTab = panel;
      panel = next;
      inspectorOpen = true;
    });
    // A tool changes what the shown picture is developed with.
    if (leaving.isTool || next.isTool) renderer?.schedule();
  }

  Panel _lastTab = Panel.film;

  /// Opens a canvas tool, or closes it back to the tab it was opened from.
  void toggleTool(Panel tool) => setPanel(panel == tool ? _lastTab : tool);
  void toggleFilm() => _set(() => filmOpen = !filmOpen);
  void toggleInspector() {
    _set(() => inspectorOpen = !inspectorOpen);
    // A tool shows its picture only while the inspector does.
    if (panel.isTool) renderer?.schedule();
  }

  void setPresetsTab(bool value) {
    _set(() => presetsTab = value);
    // The preset rows' pictures develop in the background.
    renderer?.schedule();
  }
  void setSearch(String value) => _set(() => search = value);
  void setFilmType(FilmType value) => _set(() => filmType = value);
  void dismissError() => _set(() => error = null);

  /// A feature's model (lib/model/<area>.dart) changed what the workspace shows.
  void notify() => notifyListeners();

  void setComparing(bool value) {
    if (comparing == value || active == null) return;
    _set(() => comparing = value);
  }

  void toggleHistogram() {
    _set(() => histogramOpen = !histogramOpen);
    if (histogramOpen) renderer?.schedule();
  }

  void setFormatDetail(bool value) {
    _set(() => formatDetail = value);
    renderer?.schedule();
  }

  // ── View ──────────────────────────────────────────────────────────────────────────────────

  double get scale => view.scale(photoSize);
  double get fitScale => view.fitScale(photoSize);
  bool get zoomed => view.zoom != null;

  /// The view moved: the picture follows at once, and develops for it once it stops moving,
  /// so a pinch or a window resize does not start a develop for every frame.
  Timer? _viewSettle;
  void _viewChanged() {
    notifyListeners();
    _viewSettle?.cancel();
    _viewSettle = Timer(const Duration(milliseconds: 120), () {
      _viewSettle = null;
      renderer?.schedule();
    });
  }

  void updateViewer(ui.Size size, double dpr) {
    if (size == view.viewer && dpr == view.devicePixelRatio) return;
    view
      ..viewer = size
      ..devicePixelRatio = dpr
      ..clampPan(photoSize);
    _viewChanged();
  }

  /// Magnifies to `value` around the viewer's centre, or fits the photograph when null.
  void setZoom(double? value) {
    // The crop tool shows the whole frame, fitted.
    if (value != null && crop.showing) return;
    if (value == null) {
      view
        ..zoom = null
        ..pan = ui.Offset.zero;
    } else {
      view.zoomAround(photoSize, value, view.viewer.center(ui.Offset.zero));
    }
    _viewChanged();
  }

  /// Magnifies to `value`, keeping the point under `focal` (viewer coordinates) still.
  void zoomAround(double value, ui.Offset focal) {
    if (crop.showing) return;
    view.zoomAround(photoSize, value, focal);
    _viewChanged();
  }

  void panBy(ui.Offset delta) {
    if (view.zoom == null) return;
    view
      ..pan += delta
      ..clampPan(photoSize);
    _viewChanged();
  }

  void zoomIn() => setZoom(
      ViewState.steps.firstWhere((s) => s > scale * 1.001, orElse: () => ViewState.maxZoom));

  void zoomOut() {
    final next = ViewState.steps.lastWhere((s) => s < scale * 0.999, orElse: () => fitScale);
    setZoom(next <= fitScale ? null : next);
  }
}

/// A change to a photograph's history, as features are told of it.
class EditChange {
  const EditChange(this.doc, this.kind, this.before, {this.restoring = false});
  final PhotoDocument doc;

  /// edit, undo, redo, goTo or replace (a film the app chose, not a step to undo).
  final String kind;
  final Json before;

  /// A whole edit put in place: a paste, a preset, Load Edits.
  final bool restoring;
}

/// A photograph to open: its name, and its path where there is a file system, or its bytes.
class PhotoFile {
  PhotoFile(this.name, {this.path, this.read, this.negative = false});
  final String name;
  final String? path;
  final Future<Uint8List> Function()? read;

  /// A scanned negative, imported as one (a library negatives folder).
  final bool negative;
}
