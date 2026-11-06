// The Film panel: the loaded film, its format chosen by eye, its condition and its halation. A film
// with a fixed profile has none of its settings.
import 'package:flutter/widgets.dart';

import '../../model/editor.dart';
import '../kit/kit.dart';
import 'controls.dart';
import 'format_picker.dart';
import '../../l10n/zh.dart';

/// A chosen format goes back to the camera's or the film's; otherwise which one is followed.
List<Widget> _following(EditorModel editor) {
  final camera = editor.active?.sensorGauge != null;
  if (editor.edit['format'] != null) {
    return [
      Align(
        alignment: Alignment.centerLeft,
        child: PillButton(camera ? 'Match the Camera' : 'Match the Film',
            onPressed: () => editor.change({'format': null})),
      ),
    ];
  }
  if (!camera) return const [Note('Following the film.')];
  final followed = followedFormat(editor);
  final name = editor.catalogue.filmFormats
      .where((f) => f['id'] == followed)
      .map((f) => f['name'] as String)
      .firstOrNull;
  return [Note('Following the camera: ${name ?? followed}.')];
}

List<Widget> filmPanel(InspectorControls c) {
  final editor = c.editor;
  if (!c.hasFilm) {
    return [
      Section(title: tr('Normal'), children: [InfoRow('Stock', 'None')]),
    ];
  }
  final fixed = c.fixedSettings;
  return [
    Section(title: tr('Loaded Film'), children: [
      if (fixed) const Note('Fixed profile: film settings are off.'),
      InfoRow('Stock', c.stock?['name'] as String? ?? ''),
    ]),
    if (!fixed) Section(title: tr('Film Format'), children: [
      FormatPicker(editor: editor),
      SwitchRow(
        label: tr('Magnified Detail'),
        value: editor.formatDetail,
        onChanged: editor.setFormatDetail,
      ),
      ..._following(editor),
    ]),
    if (!fixed) Section(title: tr('Film Condition'), children: c.profileControls(const ['expired'])),
    Section(title: tr('Halation'), children: [
      PickerField<String>(
        label: tr('Halation Model'),
        detail: c.catalogue.detail('halationModel'),
        value: c.edit['halationModel'] as String? ?? 'legacy',
        options: const [
          PickerOption('legacy', 'Legacy'),
          PickerOption('layered', 'Layered Transport'),
        ],
        onChanged: (v) => editor.change({'halationModel': v}),
      ),
      ...c.profileControls(const [
        'halation',
        'halationReturn',
        'halationColour',
        'halationSpectrum',
        'halationHaze',
        'antiHalation',
        'baseThickness',
        'pressurePlate',
        'estimatedHalation',
      ]),
      // Halation is a multiplier in stops (0 leaves the film exactly as it is), and how bright the
      // halo is also depends on the film's own return, which most stocks state at well under one
      // percent. Both have to move before anything shows, which looks like a dead slider.
      if ((c.edit['profile'] as Map?)?['halation'] == null ||
          ((c.edit['profile'] as Map?)!['halation'] as num? ?? 0) == 0)
        const Note('光晕强度 = Halation Return × 档位倍数 × 胶卷系数：本胶卷返回率只有 0.8%，所以单拉档位几乎看不见；请用下方“增强光晕”或同时抬高 Halation Return。'),
      QuietButton(
        label: tr('Boost Halation'),
        onPressed: () => editor.change({
          'profile': {
            ...?c.edit['profile'] as Map?,
            // The engine multiplies three numbers: the film's return ratio, exp2(stops) and the
            // stock's look scale. Stock returns sit at patent-floor densities -- 0.8% here -- so
            // 0.008 x 1 x 1 is invisible by construction. +4 stops and a 25% return is 0.25 x 16
            // = 4: unmistakable, and one Undo away from where it was.
            'halation': 4.0,
            'halationReturn': 0.25,
          }
        }),
      ),
    ]),
  ];
}
