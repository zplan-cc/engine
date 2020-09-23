// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/external_view_embedder/external_view_embedder.h"

#include "flutter/fml/trace_event.h"

namespace flutter {

AndroidExternalViewEmbedder::AndroidExternalViewEmbedder(
    std::shared_ptr<AndroidContext> android_context,
    std::shared_ptr<PlatformViewAndroidJNI> jni_facade,
    const AndroidSurface::Factory& surface_factory)
    : ExternalViewEmbedder(),
      android_context_(android_context),
      jni_facade_(jni_facade),
      surface_factory_(surface_factory),
      surface_pool_(std::make_unique<SurfacePool>()) {}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder::PrerollCompositeEmbeddedView(
    int view_id,
    std::unique_ptr<EmbeddedViewParams> params) {
  TRACE_EVENT0("flutter",
               "AndroidExternalViewEmbedder::PrerollCompositeEmbeddedView");

  auto rtree_factory = RTreeFactory();
  view_rtrees_.insert_or_assign(view_id, rtree_factory.getInstance());

  auto picture_recorder = std::make_unique<SkPictureRecorder>();
  picture_recorder->beginRecording(SkRect::Make(frame_size_), &rtree_factory);

  picture_recorders_.insert_or_assign(view_id, std::move(picture_recorder));
  composition_order_.push_back(view_id);
  // Update params only if they changed.
  if (view_params_.count(view_id) == 1 &&
      view_params_.at(view_id) == *params.get()) {
    return;
  }
  view_params_.insert_or_assign(view_id, EmbeddedViewParams(*params.get()));
}

// |ExternalViewEmbedder|
SkCanvas* AndroidExternalViewEmbedder::CompositeEmbeddedView(int view_id) {
  if (picture_recorders_.count(view_id) == 1) {
    return picture_recorders_.at(view_id)->getRecordingCanvas();
  }
  return nullptr;
}

// |ExternalViewEmbedder|
std::vector<SkCanvas*> AndroidExternalViewEmbedder::GetCurrentCanvases() {
  std::vector<SkCanvas*> canvases;
  for (size_t i = 0; i < composition_order_.size(); i++) {
    int64_t view_id = composition_order_[i];
    canvases.push_back(picture_recorders_.at(view_id)->getRecordingCanvas());
  }
  return canvases;
}

SkRect AndroidExternalViewEmbedder::GetViewRect(int view_id) const {
  const EmbeddedViewParams& params = view_params_.at(view_id);
  // TODO(egarciad): The rect should be computed from the mutator stack.
  // (Clipping is missing)
  // https://github.com/flutter/flutter/issues/59821
  return SkRect::MakeXYWH(params.finalBoundingRect().x(),      //
                          params.finalBoundingRect().y(),      //
                          params.finalBoundingRect().width(),  //
                          params.finalBoundingRect().height()  //
  );
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder::SubmitFrame(
    GrDirectContext* context,
    std::unique_ptr<SurfaceFrame> frame) {
  TRACE_EVENT0("flutter", "AndroidExternalViewEmbedder::SubmitFrame");

  if (!FrameHasPlatformLayers()) {
    frame->Submit();
    return;
  }

  std::unordered_map<int64_t, std::list<SkRect>> overlay_layers;
  std::unordered_map<int64_t, sk_sp<SkPicture>> pictures;
  SkCanvas* background_canvas = frame->SkiaCanvas();
  auto current_frame_view_count = composition_order_.size();

  // Restore the clip context after exiting this method since it's changed
  // below.
  SkAutoCanvasRestore save(background_canvas, /*doSave=*/true);

  for (size_t i = 0; i < current_frame_view_count; i++) {
    int64_t view_id = composition_order_[i];

    sk_sp<SkPicture> picture =
        picture_recorders_.at(view_id)->finishRecordingAsPicture();
    FML_CHECK(picture);
    pictures.insert({view_id, picture});

    overlay_layers.insert({view_id, {}});

    sk_sp<RTree> rtree = view_rtrees_.at(view_id);
    // Determinate if Flutter UI intersects with any of the previous
    // platform views stacked by z position.
    //
    // This is done by querying the r-tree that holds the records for the
    // picture recorder corresponding to the flow layers added after a platform
    // view layer.
    for (ssize_t j = i; j >= 0; j--) {
      int64_t current_view_id = composition_order_[j];
      SkRect current_view_rect = GetViewRect(current_view_id);
      // Each rect corresponds to a native view that renders Flutter UI.
      std::list<SkRect> intersection_rects =
          rtree->searchNonOverlappingDrawnRects(current_view_rect);
      auto allocation_size = intersection_rects.size();

      // Limit the number of native views, so it doesn't grow forever.
      //
      // In this case, the rects are merged into a single one that is the union
      // of all the rects.
      if (allocation_size > kMaxLayerAllocations) {
        SkRect joined_rect;
        for (const SkRect& rect : intersection_rects) {
          joined_rect.join(rect);
        }
        intersection_rects.clear();
        intersection_rects.push_back(joined_rect);
      }
      for (SkRect& intersection_rect : intersection_rects) {
        // Subpixels in the platform may not align with the canvas subpixels.
        //
        // To workaround it, round the floating point bounds and make the rect
        // slighly larger. For example, {0.3, 0.5, 3.1, 4.7} becomes {0, 0, 4,
        // 5}.
        intersection_rect.set(intersection_rect.roundOut());
        overlay_layers.at(view_id).push_back(intersection_rect);
        // Clip the background canvas, so it doesn't contain any of the pixels
        // drawn on the overlay layer.
        background_canvas->clipRect(intersection_rect, SkClipOp::kDifference);
      }
    }
    background_canvas->drawPicture(pictures.at(view_id));
  }
  // Submit the background canvas frame before switching the GL context to
  // the overlay surfaces.
  //
  // Skip a frame if the embedding is switching surfaces, and indicate in
  // `PostPrerollAction` that this frame must be resubmitted.
  auto should_submit_current_frame = previous_frame_view_count_ > 0;
  if (should_submit_current_frame) {
    frame->Submit();
  }

  for (int64_t view_id : composition_order_) {
    SkRect view_rect = GetViewRect(view_id);
    const EmbeddedViewParams& params = view_params_.at(view_id);
    // Display the platform view. If it's already displayed, then it's
    // just positioned and sized.
    jni_facade_->FlutterViewOnDisplayPlatformView(
        view_id,             //
        view_rect.x(),       //
        view_rect.y(),       //
        view_rect.width(),   //
        view_rect.height(),  //
        params.sizePoints().width() * device_pixel_ratio_,
        params.sizePoints().height() * device_pixel_ratio_,
        params.mutatorsStack()  //
    );
    for (const SkRect& overlay_rect : overlay_layers.at(view_id)) {
      std::unique_ptr<SurfaceFrame> frame =
          CreateSurfaceIfNeeded(context,               //
                                view_id,               //
                                pictures.at(view_id),  //
                                overlay_rect           //
          );
      if (should_submit_current_frame) {
        frame->Submit();
      }
    }
  }
}

// |ExternalViewEmbedder|
std::unique_ptr<SurfaceFrame>
AndroidExternalViewEmbedder::CreateSurfaceIfNeeded(GrDirectContext* context,
                                                   int64_t view_id,
                                                   sk_sp<SkPicture> picture,
                                                   const SkRect& rect) {
  std::shared_ptr<OverlayLayer> layer = surface_pool_->GetLayer(
      context, android_context_, jni_facade_, surface_factory_);

  std::unique_ptr<SurfaceFrame> frame =
      layer->surface->AcquireFrame(frame_size_);
  // Display the overlay surface. If it's already displayed, then it's
  // just positioned and sized.
  jni_facade_->FlutterViewDisplayOverlaySurface(layer->id,     //
                                                rect.x(),      //
                                                rect.y(),      //
                                                rect.width(),  //
                                                rect.height()  //
  );
  SkCanvas* overlay_canvas = frame->SkiaCanvas();
  overlay_canvas->clear(SK_ColorTRANSPARENT);
  // Offset the picture since its absolute position on the scene is determined
  // by the position of the overlay view.
  overlay_canvas->translate(-rect.x(), -rect.y());
  overlay_canvas->drawPicture(picture);
  return frame;
}

// |ExternalViewEmbedder|
PostPrerollResult AndroidExternalViewEmbedder::PostPrerollAction(
    fml::RefPtr<fml::RasterThreadMerger> raster_thread_merger) {
  if (!FrameHasPlatformLayers()) {
    return PostPrerollResult::kSuccess;
  }
  if (!raster_thread_merger->IsMerged()) {
    // The raster thread merger may be disabled if the rasterizer is being
    // created or teared down.
    //
    // In such cases, the current frame is dropped, and a new frame is attempted
    // with the same layer tree.
    //
    // Eventually, the frame is submitted once this method returns `kSuccess`.
    // At that point, the raster tasks are handled on the platform thread.
    raster_thread_merger->MergeWithLease(kDefaultMergedLeaseDuration);
    CancelFrame();
    return PostPrerollResult::kSkipAndRetryFrame;
  }
  raster_thread_merger->ExtendLeaseTo(kDefaultMergedLeaseDuration);
  // Surface switch requires to resubmit the frame.
  // TODO(egarciad): https://github.com/flutter/flutter/issues/65652
  if (previous_frame_view_count_ == 0) {
    return PostPrerollResult::kResubmitFrame;
  }
  return PostPrerollResult::kSuccess;
}

bool AndroidExternalViewEmbedder::FrameHasPlatformLayers() {
  return composition_order_.size() > 0;
}

// |ExternalViewEmbedder|
SkCanvas* AndroidExternalViewEmbedder::GetRootCanvas() {
  // On Android, the root surface is created from the on-screen render target.
  return nullptr;
}

void AndroidExternalViewEmbedder::Reset() {
  previous_frame_view_count_ = composition_order_.size();

  composition_order_.clear();
  picture_recorders_.clear();
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder::BeginFrame(
    SkISize frame_size,
    GrDirectContext* context,
    double device_pixel_ratio,
    fml::RefPtr<fml::RasterThreadMerger> raster_thread_merger) {
  Reset();

  // The surface size changed. Therefore, destroy existing surfaces as
  // the existing surfaces in the pool can't be recycled.
  if (frame_size_ != frame_size) {
    surface_pool_->DestroyLayers(jni_facade_);
  }
  frame_size_ = frame_size;
  device_pixel_ratio_ = device_pixel_ratio;
  // JNI method must be called on the platform thread.
  if (raster_thread_merger->IsOnPlatformThread()) {
    jni_facade_->FlutterViewBeginFrame();
  }
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder::CancelFrame() {
  Reset();
}

// |ExternalViewEmbedder|
void AndroidExternalViewEmbedder::EndFrame(
    bool should_resubmit_frame,
    fml::RefPtr<fml::RasterThreadMerger> raster_thread_merger) {
  surface_pool_->RecycleLayers();
  // JNI method must be called on the platform thread.
  if (raster_thread_merger->IsOnPlatformThread()) {
    jni_facade_->FlutterViewEndFrame();
  }
}

// |ExternalViewEmbedder|
bool AndroidExternalViewEmbedder::SupportsDynamicThreadMerging() {
  return true;
}

}  // namespace flutter
