// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_H_

#include <unordered_map>

#include "flutter/flow/embedded_views.h"
#include "flutter/flow/rtree.h"
#include "flutter/shell/platform/android/context/android_context.h"
#include "flutter/shell/platform/android/external_view_embedder/surface_pool.h"
#include "flutter/shell/platform/android/jni/platform_view_android_jni.h"
#include "third_party/skia/include/core/SkPictureRecorder.h"

namespace flutter {

//------------------------------------------------------------------------------
/// Allows to embed Android views into a Flutter application.
///
/// This class calls Java methods via |PlatformViewAndroidJNI| to manage the
/// lifecycle of the Android view corresponding to |flutter::PlatformViewLayer|.
///
/// It also orchestrates overlay surfaces. These are Android views
/// that render above (by Z order) the Android view corresponding to
/// |flutter::PlatformViewLayer|.
///
class AndroidExternalViewEmbedder final : public ExternalViewEmbedder {
 public:
  AndroidExternalViewEmbedder(
      std::shared_ptr<AndroidContext> android_context,
      std::shared_ptr<PlatformViewAndroidJNI> jni_facade,
      const AndroidSurface::Factory& surface_factory);

  // |ExternalViewEmbedder|
  void PrerollCompositeEmbeddedView(
      int view_id,
      std::unique_ptr<flutter::EmbeddedViewParams> params) override;

  // |ExternalViewEmbedder|
  SkCanvas* CompositeEmbeddedView(int view_id) override;

  // |ExternalViewEmbedder|
  std::vector<SkCanvas*> GetCurrentCanvases() override;

  // |ExternalViewEmbedder|
  void SubmitFrame(GrDirectContext* context,
                   std::unique_ptr<SurfaceFrame> frame) override;

  // |ExternalViewEmbedder|
  PostPrerollResult PostPrerollAction(
      fml::RefPtr<fml::RasterThreadMerger> raster_thread_merger) override;

  // |ExternalViewEmbedder|
  SkCanvas* GetRootCanvas() override;

  // |ExternalViewEmbedder|
  void BeginFrame(
      SkISize frame_size,
      GrDirectContext* context,
      double device_pixel_ratio,
      fml::RefPtr<fml::RasterThreadMerger> raster_thread_merger) override;

  // |ExternalViewEmbedder|
  void CancelFrame() override;

  // |ExternalViewEmbedder|
  void EndFrame(
      bool should_resubmit_frame,
      fml::RefPtr<fml::RasterThreadMerger> raster_thread_merger) override;

  bool SupportsDynamicThreadMerging() override;

  // Gets the rect based on the device pixel ratio of a platform view displayed
  // on the screen.
  SkRect GetViewRect(int view_id) const;

 private:
  static const int kMaxLayerAllocations = 2;

  // The number of frames the rasterizer task runner will continue
  // to run on the platform thread after no platform view is rendered.
  //
  // Note: this is an arbitrary number that attempts to account for cases
  // where the platform view might be momentarily off the screen.
  static const int kDefaultMergedLeaseDuration = 10;

  // Provides metadata to the Android surfaces.
  const std::shared_ptr<AndroidContext> android_context_;

  // Allows to call methods in Java.
  const std::shared_ptr<PlatformViewAndroidJNI> jni_facade_;

  // Allows to create surfaces.
  const AndroidSurface::Factory surface_factory_;

  // Holds surfaces. Allows to recycle surfaces or allocate new ones.
  const std::unique_ptr<SurfacePool> surface_pool_;

  // The size of the root canvas.
  SkISize frame_size_;

  // The pixel ratio used to determinate the size of a platform view layer
  // relative to the device layout system.
  double device_pixel_ratio_;

  // The order of composition. Each entry contains a unique id for the platform
  // view.
  std::vector<int64_t> composition_order_;

  // The platform view's picture recorder keyed off the platform view id, which
  // contains any subsequent operation until the next platform view or the end
  // of the last leaf node in the layer tree.
  std::unordered_map<int64_t, std::unique_ptr<SkPictureRecorder>>
      picture_recorders_;

  // The params for a platform view, which contains the size, position and
  // mutation stack.
  std::unordered_map<int64_t, EmbeddedViewParams> view_params_;

  // The r-tree that captures the operations for the picture recorders.
  std::unordered_map<int64_t, sk_sp<RTree>> view_rtrees_;

  // The number of platform views in the previous frame.
  int64_t previous_frame_view_count_;

  // Resets the state.
  void Reset();

  // Whether the layer tree in the current frame has platform layers.
  bool FrameHasPlatformLayers();

  // Creates a Surface when needed or recycles an existing one.
  // Finally, draws the picture on the frame's canvas.
  std::unique_ptr<SurfaceFrame> CreateSurfaceIfNeeded(GrDirectContext* context,
                                                      int64_t view_id,
                                                      sk_sp<SkPicture> picture,
                                                      const SkRect& rect);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_H_
