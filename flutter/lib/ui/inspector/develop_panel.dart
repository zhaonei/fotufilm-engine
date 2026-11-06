// The Develop panel: push and bleach, grain, and the film's colour separation.
import 'package:flutter/widgets.dart';

import '../kit/kit.dart';
import 'controls.dart';
import '../../l10n/zh.dart';

List<Widget> developPanel(InspectorControls c) {
  if (!c.hasFilm) {
    return [
      Section(title: tr('Normal'), children: [
        Note('Choose a film from the library to use development and grain.'),
      ]),
    ];
  }
  const separation = [
    'couplers',
    'couplerReach',
    'couplerSelf',
    'chromaticFringeAmount',
    'chromaticFringeRadius',
  ];
  return [
    Section(title: tr('Development'), children: c.profileControls(const ['push', 'bleach'])),
    Section(title: tr('Grain'), children: [
      ...c.sliders('Character'),
      ...c.profileControls(const [
        'grainMottle',
        'grainModel',
        'filmGrainSize',
        'filmColourGrain',
        'filmRedLayer',
        'filmGreenLayer',
        'filmBlueLayer',
        'filmScanSoftness',
      ]),
      // The film grain model averages the light through each pixel's patch of emulsion, so at a
      // 2048 preview the grain averages away with the resolution. Saying so beats a slider that
      // looks broken.
      if ((c.edit['profile'] as Map?)?['grainModel'] == 'film')
        Note('胶片颗粒按每个像素覆盖的胶片面积取平均：预览缩放会把它平均掉，请导出后或按 100% 查看。'),
      PillButton(tr('New Grain Pattern'), expand: true, onPressed: c.editor.newGrainPattern),
    ]),
    if (c.available.any(separation.take(4).contains))
      Section(title: tr('Colour Separation'), children: [
        ...c.profileControls(separation),
        // These act on adjacency between the emulsion's layers: a few percent of colour, most
        // visible where colours meet, so a fitted preview shows them weakly by nature.
        const Note('分色/成色剂影响的是乳剂层之间的串色与边缘，幅度本身很小：把滑块拉到头更容易看出，或导出后对比。'),
      ]),
  ];
}
