// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_FLOW_LAYERS_LAYER_TREE_H_
#define FLUTTER_FLOW_LAYERS_LAYER_TREE_H_

#include <cstdint>
#include <memory>

#include "flutter/flow/compositor_context.h"
#include "flutter/flow/layers/layer.h"
#include "flutter/fml/macros.h"
#include "flutter/fml/time/time_delta.h"
#include "third_party/skia/include/core/SkPicture.h"
#include "third_party/skia/include/core/SkSize.h"

namespace flutter {

class LayerTree {
 public:
  LayerTree(const SkISize& frame_size, float device_pixel_ratio);

  // Perform a preroll pass on the tree and return information about
  // the tree that affects rendering this frame.
  //
  // Returns:
  // - a boolean indicating whether or not the top level of the
  //   layer tree performs any operations that require readback
  //   from the root surface.
  bool Preroll(CompositorContext::ScopedFrame& frame,
               bool ignore_raster_cache = false);

#if defined(LEGACY_FUCHSIA_EMBEDDER)
  void UpdateScene(SceneUpdateContext& context);
#endif

  void Paint(CompositorContext::ScopedFrame& frame,
             bool ignore_raster_cache = false) const;

  sk_sp<SkPicture> Flatten(const SkRect& bounds);

  Layer* root_layer() const { return root_layer_.get(); }

  void set_root_layer(std::shared_ptr<Layer> root_layer) {
    root_layer_ = std::move(root_layer);
  }

  const SkISize& frame_size() const { return frame_size_; }
  float device_pixel_ratio() const { return device_pixel_ratio_; }

  void RecordBuildTime(fml::TimePoint vsync_start,
                       fml::TimePoint build_start,
                       fml::TimePoint target_time);
  fml::TimePoint vsync_start() const { return vsync_start_; }
  fml::TimeDelta vsync_overhead() const { return build_start_ - vsync_start_; }
  fml::TimePoint build_start() const { return build_start_; }
  fml::TimePoint build_finish() const { return build_finish_; }
  fml::TimeDelta build_time() const { return build_finish_ - build_start_; }
  fml::TimePoint target_time() const { return target_time_; }

  // The number of frame intervals missed after which the compositor must
  // trace the rasterized picture to a trace file. Specify 0 to disable all
  // tracing
  void set_rasterizer_tracing_threshold(uint32_t interval) {
    rasterizer_tracing_threshold_ = interval;
  }

  uint32_t rasterizer_tracing_threshold() const {
    return rasterizer_tracing_threshold_;
  }

  void set_checkerboard_raster_cache_images(bool checkerboard) {
    checkerboard_raster_cache_images_ = checkerboard;
  }

  void set_checkerboard_offscreen_layers(bool checkerboard) {
    checkerboard_offscreen_layers_ = checkerboard;
  }

 private:
  std::shared_ptr<Layer> root_layer_;
  fml::TimePoint vsync_start_;
  fml::TimePoint build_start_;
  fml::TimePoint build_finish_;
  fml::TimePoint target_time_;
  SkISize frame_size_ = SkISize::MakeEmpty();  // Physical pixels.
  const float device_pixel_ratio_;  // Logical / Physical pixels ratio.
  uint32_t rasterizer_tracing_threshold_;
  bool checkerboard_raster_cache_images_;
  bool checkerboard_offscreen_layers_;

  FML_DISALLOW_COPY_AND_ASSIGN(LayerTree);
};

}  // namespace flutter

#endif  // FLUTTER_FLOW_LAYERS_LAYER_TREE_H_
