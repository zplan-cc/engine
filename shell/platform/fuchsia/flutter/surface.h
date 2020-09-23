// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#pragma once

#include "flutter/flow/surface.h"
#include "flutter/fml/macros.h"
#include "flutter/fml/memory/weak_ptr.h"

namespace flutter_runner {

// The interface between the Flutter rasterizer and the underlying platform. May
// be constructed on any thread but will be used by the engine only on the
// raster thread.
class Surface final : public flutter::Surface {
 public:
  Surface(std::string debug_label,
          flutter::ExternalViewEmbedder* view_embedder,
          GrDirectContext* gr_context);

  ~Surface() override;

 private:
  const bool valid_ = CanConnectToDisplay();
  const std::string debug_label_;
  flutter::ExternalViewEmbedder* view_embedder_;
  GrDirectContext* gr_context_;

  // |flutter::Surface|
  bool IsValid() override;

  // |flutter::Surface|
  std::unique_ptr<flutter::SurfaceFrame> AcquireFrame(
      const SkISize& size) override;

  // |flutter::Surface|
  GrDirectContext* GetContext() override;

  // |flutter::Surface|
  SkMatrix GetRootTransformation() const override;

  // |flutter::Surface|
  flutter::ExternalViewEmbedder* GetExternalViewEmbedder() override;

  static bool CanConnectToDisplay();

  FML_DISALLOW_COPY_AND_ASSIGN(Surface);
};

}  // namespace flutter_runner
