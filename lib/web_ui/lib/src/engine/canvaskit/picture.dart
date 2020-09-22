// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// @dart = 2.10
part of engine;

class CkPicture implements ui.Picture {
  final SkiaObject<SkPicture> skiaObject;
  final ui.Rect? cullRect;

  CkPicture(SkPicture picture, this.cullRect)
      : skiaObject = SkPictureSkiaObject(picture);

  @override
  int get approximateBytesUsed => 0;

  @override
  void dispose() {
    skiaObject.delete();
  }

  @override
  Future<ui.Image> toImage(int width, int height) async {
    final SkSurface skSurface = canvasKit.MakeSurface(width, height);
    final SkCanvas skCanvas = skSurface.getCanvas();
    skCanvas.drawPicture(skiaObject.skiaObject);
    final SkImage skImage = skSurface.makeImageSnapshot();
    skSurface.dispose();
    return CkImage(skImage);
  }
}

class SkPictureSkiaObject extends OneShotSkiaObject<SkPicture> {
  SkPictureSkiaObject(SkPicture picture) : super(picture);

  @override
  void delete() {
    rawSkiaObject.delete();
  }
}
