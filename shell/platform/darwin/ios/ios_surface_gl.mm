// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#import "flutter/shell/platform/darwin/ios/ios_surface_gl.h"

#include "flutter/fml/trace_event.h"
#include "flutter/shell/gpu/gpu_surface_gl.h"
#import "flutter/shell/platform/darwin/ios/ios_context_gl.h"

namespace flutter {

static IOSContextGL* CastToGLContext(const std::shared_ptr<IOSContext>& context) {
  return reinterpret_cast<IOSContextGL*>(context.get());
}

IOSSurfaceGL::IOSSurfaceGL(fml::scoped_nsobject<CAEAGLLayer> layer,
                           std::shared_ptr<IOSContext> context,
                           FlutterPlatformViewsController* platform_views_controller)
    : IOSSurface(context, platform_views_controller) {
  render_target_ = CastToGLContext(context)->CreateRenderTarget(std::move(layer));
}

IOSSurfaceGL::~IOSSurfaceGL() = default;

// |IOSSurface|
bool IOSSurfaceGL::IsValid() const {
  return render_target_->IsValid();
}

// |IOSSurface|
void IOSSurfaceGL::UpdateStorageSizeIfNecessary() {
  if (IsValid()) {
    render_target_->UpdateStorageSizeIfNecessary();
  }
}

// |IOSSurface|
std::unique_ptr<Surface> IOSSurfaceGL::CreateGPUSurface(GrDirectContext* gr_context) {
  if (gr_context) {
    return std::make_unique<GPUSurfaceGL>(sk_ref_sp(gr_context), this, true);
  }
  return std::make_unique<GPUSurfaceGL>(this, true);
}

// |GPUSurfaceGLDelegate|
intptr_t IOSSurfaceGL::GLContextFBO(GLFrameInfo frame_info) const {
  return IsValid() ? render_target_->GetFramebuffer() : GL_NONE;
}

// |GPUSurfaceGLDelegate|
bool IOSSurfaceGL::SurfaceSupportsReadback() const {
  // The onscreen surface wraps a GL renderbuffer, which is extremely slow to read on iOS.
  // Certain filter effects, in particular BackdropFilter, require making a copy of
  // the current destination. For performance, the iOS surface will specify that it
  // does not support readback so that the engine compositor can implement a workaround
  // such as rendering the scene to an offscreen surface or Skia saveLayer.
  return false;
}

// |GPUSurfaceGLDelegate|
std::unique_ptr<GLContextResult> IOSSurfaceGL::GLContextMakeCurrent() {
  if (!IsValid()) {
    return std::make_unique<GLContextDefaultResult>(false);
  }
  bool update_if_necessary = render_target_->UpdateStorageSizeIfNecessary();
  if (!update_if_necessary) {
    return std::make_unique<GLContextDefaultResult>(false);
  }
  return GetContext()->MakeCurrent();
}

// |GPUSurfaceGLDelegate|
bool IOSSurfaceGL::GLContextClearCurrent() {
  // |GLContextMakeCurrent| should handle the scope of the gl context.
  return true;
}

// |GPUSurfaceGLDelegate|
bool IOSSurfaceGL::GLContextPresent(uint32_t fbo_id) {
  TRACE_EVENT0("flutter", "IOSSurfaceGL::GLContextPresent");
  return IsValid() && render_target_->PresentRenderBuffer();
}

// |GPUSurfaceGLDelegate|
ExternalViewEmbedder* IOSSurfaceGL::GetExternalViewEmbedder() {
  return this;
}

}  // namespace flutter
