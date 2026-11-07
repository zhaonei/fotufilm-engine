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
    ?filmFormatSection(c),
    ?filmConditionSection(c),
    halationSection(c),
  ];
}

/// The format chosen by eye; a fixed profile has none.
Section? filmFormatSection(InspectorControls c) {
  if (c.fixedSettings) return null;
  final editor = c.editor;
  return Section(title: tr('Film Format'), children: [
    FormatPicker(editor: editor),
    SwitchRow(
      label: tr('Magnified Detail'),
      value: editor.formatDetail,
      onChanged: editor.setFormatDetail,
    ),
    ..._following(editor),
  ]);
}

Section? filmConditionSection(InspectorControls c) => c.fixedSettings
    ? null
    : Section(title: tr('Film Condition'), children: c.profileControls(const ['expired']));

Section halationSection(InspectorControls c) => Section(title: tr('Halation'), children: [
      PickerField<String>(
        label: tr('Halation Model'),
        detail: c.catalogue.detail('halationModel'),
        value: c.edit['halationModel'] as String? ?? 'legacy',
        options: const [
          PickerOption('legacy', 'Legacy'),
          PickerOption('layered', 'Layered Transport'),
        ],
        onChanged: (v) => c.editor.change({'halationModel': v}),
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
      const Note('0 档 = 原样；点“增强光晕”可直接看到效果。'),
      QuietButton(
        label: tr('Boost Halation'),
        onPressed: () => editor.change({
          'profile': {
            ...?c.edit['profile'] as Map?,
            'halation': 4.0,
            'halationReturn': 0.25,
          }
        }),
      ),
    ]);
