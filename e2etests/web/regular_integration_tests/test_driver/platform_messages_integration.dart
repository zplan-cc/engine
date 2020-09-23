// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:html' as html;
// ignore: undefined_shown_name
import 'dart:ui' as ui show platformViewRegistry;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:regular_integration_tests/platform_messages_main.dart' as app;

import 'package:integration_test/integration_test.dart';

void main() async {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('platform message for Clipboard.setData reply with future',
      (WidgetTester tester) async {
    app.main();
    await tester.pumpAndSettle();

    // TODO(nurhan): https://github.com/flutter/flutter/issues/51885
    SystemChannels.textInput.setMockMethodCallHandler(null);
    // Focus on a TextFormField.
    final Finder finder = find.byKey(const Key('input'));
    expect(finder, findsOneWidget);
    await tester.tap(find.byKey(const Key('input')));
    // Focus in input, otherwise clipboard will fail with
    // 'document is not focused' platform exception.
    html.document.querySelector('input').focus();
    await Clipboard.setData(const ClipboardData(text: 'sample text'));
  }, skip: true); // https://github.com/flutter/flutter/issues/54296

  testWidgets('Should create and dispose view embedder',
      (WidgetTester tester) async {
    int viewInstanceCount = 0;

    final int currentViewId = platformViewsRegistry.getNextPlatformViewId();
    // ignore: undefined_prefixed_name
    ui.platformViewRegistry.registerViewFactory('MyView', (int viewId) {
      ++viewInstanceCount;
      return html.DivElement();
    });

    app.main();
    await tester.pumpAndSettle();
    final Map<String, dynamic> createArgs = <String, dynamic>{
      'id': '567',
      'viewType': 'MyView',
    };
    await SystemChannels.platform_views.invokeMethod<void>('create', createArgs);
    final Map<String, dynamic> disposeArgs = <String, dynamic>{
      'id': '567',
    };
    await SystemChannels.platform_views.invokeMethod<void>('dispose', disposeArgs);
    expect(viewInstanceCount, 1);
  });
}
