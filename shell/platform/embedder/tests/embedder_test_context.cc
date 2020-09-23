// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/embedder/tests/embedder_test_context.h"

#include "flutter/fml/make_copyable.h"
#include "flutter/fml/paths.h"
#include "flutter/runtime/dart_vm.h"
#include "flutter/shell/platform/embedder/tests/embedder_assertions.h"
#include "flutter/testing/testing.h"
#include "third_party/dart/runtime/bin/elf_loader.h"
#include "third_party/skia/include/core/SkSurface.h"

namespace flutter {
namespace testing {

EmbedderTestContext::EmbedderTestContext(std::string assets_path)
    : assets_path_(std::move(assets_path)),
      aot_symbols_(LoadELFSymbolFromFixturesIfNeccessary()),
      native_resolver_(std::make_shared<TestDartNativeResolver>()) {
  SetupAOTMappingsIfNecessary();
  SetupAOTDataIfNecessary();
  isolate_create_callbacks_.push_back(
      [weak_resolver =
           std::weak_ptr<TestDartNativeResolver>{native_resolver_}]() {
        if (auto resolver = weak_resolver.lock()) {
          resolver->SetNativeResolverForIsolate();
        }
      });
}

EmbedderTestContext::~EmbedderTestContext() {
  SetGLGetFBOCallback(nullptr);
}

void EmbedderTestContext::SetupAOTMappingsIfNecessary() {
  if (!DartVM::IsRunningPrecompiledCode()) {
    return;
  }
  vm_snapshot_data_ =
      std::make_unique<fml::NonOwnedMapping>(aot_symbols_.vm_snapshot_data, 0u);
  vm_snapshot_instructions_ = std::make_unique<fml::NonOwnedMapping>(
      aot_symbols_.vm_snapshot_instrs, 0u);
  isolate_snapshot_data_ =
      std::make_unique<fml::NonOwnedMapping>(aot_symbols_.vm_isolate_data, 0u);
  isolate_snapshot_instructions_ = std::make_unique<fml::NonOwnedMapping>(
      aot_symbols_.vm_isolate_instrs, 0u);
}

void EmbedderTestContext::SetupAOTDataIfNecessary() {
  if (!DartVM::IsRunningPrecompiledCode()) {
    return;
  }
  FlutterEngineAOTDataSource data_in = {};
  FlutterEngineAOTData data_out = nullptr;

  const auto elf_path =
      fml::paths::JoinPaths({GetFixturesPath(), kAOTAppELFFileName});

  data_in.type = kFlutterEngineAOTDataSourceTypeElfPath;
  data_in.elf_path = elf_path.c_str();

  ASSERT_EQ(FlutterEngineCreateAOTData(&data_in, &data_out), kSuccess);

  aot_data_.reset(data_out);
}

const std::string& EmbedderTestContext::GetAssetsPath() const {
  return assets_path_;
}

const fml::Mapping* EmbedderTestContext::GetVMSnapshotData() const {
  return vm_snapshot_data_.get();
}

const fml::Mapping* EmbedderTestContext::GetVMSnapshotInstructions() const {
  return vm_snapshot_instructions_.get();
}

const fml::Mapping* EmbedderTestContext::GetIsolateSnapshotData() const {
  return isolate_snapshot_data_.get();
}

const fml::Mapping* EmbedderTestContext::GetIsolateSnapshotInstructions()
    const {
  return isolate_snapshot_instructions_.get();
}

FlutterEngineAOTData EmbedderTestContext::GetAOTData() const {
  return aot_data_.get();
}

void EmbedderTestContext::SetRootSurfaceTransformation(SkMatrix matrix) {
  root_surface_transformation_ = matrix;
}

void EmbedderTestContext::AddIsolateCreateCallback(fml::closure closure) {
  if (closure) {
    isolate_create_callbacks_.push_back(closure);
  }
}

VoidCallback EmbedderTestContext::GetIsolateCreateCallbackHook() {
  return [](void* user_data) {
    reinterpret_cast<EmbedderTestContext*>(user_data)
        ->FireIsolateCreateCallbacks();
  };
}

void EmbedderTestContext::FireIsolateCreateCallbacks() {
  for (auto closure : isolate_create_callbacks_) {
    closure();
  }
}

void EmbedderTestContext::AddNativeCallback(const char* name,
                                            Dart_NativeFunction function) {
  native_resolver_->AddNativeCallback({name}, function);
}

void EmbedderTestContext::SetSemanticsNodeCallback(
    const SemanticsNodeCallback& update_semantics_node_callback) {
  update_semantics_node_callback_ = update_semantics_node_callback;
}

void EmbedderTestContext::SetSemanticsCustomActionCallback(
    const SemanticsActionCallback& update_semantics_custom_action_callback) {
  update_semantics_custom_action_callback_ =
      update_semantics_custom_action_callback;
}

void EmbedderTestContext::SetPlatformMessageCallback(
    const std::function<void(const FlutterPlatformMessage*)>& callback) {
  platform_message_callback_ = callback;
}

void EmbedderTestContext::PlatformMessageCallback(
    const FlutterPlatformMessage* message) {
  if (platform_message_callback_) {
    platform_message_callback_(message);
  }
}

FlutterUpdateSemanticsNodeCallback
EmbedderTestContext::GetUpdateSemanticsNodeCallbackHook() {
  return [](const FlutterSemanticsNode* semantics_node, void* user_data) {
    auto context = reinterpret_cast<EmbedderTestContext*>(user_data);
    if (auto callback = context->update_semantics_node_callback_) {
      callback(semantics_node);
    }
  };
}

FlutterUpdateSemanticsCustomActionCallback
EmbedderTestContext::GetUpdateSemanticsCustomActionCallbackHook() {
  return [](const FlutterSemanticsCustomAction* action, void* user_data) {
    auto context = reinterpret_cast<EmbedderTestContext*>(user_data);
    if (auto callback = context->update_semantics_custom_action_callback_) {
      callback(action);
    }
  };
}

FlutterComputePlatformResolvedLocaleCallback
EmbedderTestContext::GetComputePlatformResolvedLocaleCallbackHook() {
  return [](const FlutterLocale** supported_locales,
            size_t length) -> const FlutterLocale* {
    return supported_locales[0];
  };
}

void EmbedderTestContext::SetupOpenGLSurface(SkISize surface_size) {
  FML_CHECK(!gl_surface_);
  gl_surface_ = std::make_unique<TestGLSurface>(surface_size);
}

bool EmbedderTestContext::GLMakeCurrent() {
  FML_CHECK(gl_surface_) << "GL surface must be initialized.";
  return gl_surface_->MakeCurrent();
}

bool EmbedderTestContext::GLClearCurrent() {
  FML_CHECK(gl_surface_) << "GL surface must be initialized.";
  return gl_surface_->ClearCurrent();
}

bool EmbedderTestContext::GLPresent(uint32_t fbo_id) {
  FML_CHECK(gl_surface_) << "GL surface must be initialized.";
  gl_surface_present_count_++;

  GLPresentCallback callback;
  {
    std::scoped_lock lock(gl_callback_mutex_);
    callback = gl_present_callback_;
  }

  if (callback) {
    callback(fbo_id);
  }

  FireRootSurfacePresentCallbackIfPresent(
      [&]() { return gl_surface_->GetRasterSurfaceSnapshot(); });

  if (!gl_surface_->Present()) {
    return false;
  }

  return true;
}

void EmbedderTestContext::SetGLGetFBOCallback(GLGetFBOCallback callback) {
  std::scoped_lock lock(gl_callback_mutex_);
  gl_get_fbo_callback_ = callback;
}

void EmbedderTestContext::SetGLPresentCallback(GLPresentCallback callback) {
  std::scoped_lock lock(gl_callback_mutex_);
  gl_present_callback_ = callback;
}

uint32_t EmbedderTestContext::GLGetFramebuffer(FlutterFrameInfo frame_info) {
  FML_CHECK(gl_surface_) << "GL surface must be initialized.";

  GLGetFBOCallback callback;
  {
    std::scoped_lock lock(gl_callback_mutex_);
    callback = gl_get_fbo_callback_;
  }

  if (callback) {
    callback(frame_info);
  }

  const auto size = frame_info.size;
  return gl_surface_->GetFramebuffer(size.width, size.height);
}

bool EmbedderTestContext::GLMakeResourceCurrent() {
  FML_CHECK(gl_surface_) << "GL surface must be initialized.";
  return gl_surface_->MakeResourceCurrent();
}

void* EmbedderTestContext::GLGetProcAddress(const char* name) {
  FML_CHECK(gl_surface_) << "GL surface must be initialized.";
  return gl_surface_->GetProcAddress(name);
}

FlutterTransformation EmbedderTestContext::GetRootSurfaceTransformation() {
  return FlutterTransformationMake(root_surface_transformation_);
}

void EmbedderTestContext::SetupCompositor() {
  FML_CHECK(!compositor_) << "Already ssetup a compositor in this context.";
  FML_CHECK(gl_surface_)
      << "Setup the GL surface before setting up a compositor.";
  compositor_ = std::make_unique<EmbedderTestCompositor>(
      gl_surface_->GetSurfaceSize(), gl_surface_->GetGrContext());
}

EmbedderTestCompositor& EmbedderTestContext::GetCompositor() {
  FML_CHECK(compositor_)
      << "Accessed the compositor on a context where one was not setup. Use "
         "the config builder to setup a context with a custom compositor.";
  return *compositor_;
}

void EmbedderTestContext::SetNextSceneCallback(
    const NextSceneCallback& next_scene_callback) {
  if (compositor_) {
    compositor_->SetNextSceneCallback(next_scene_callback);
    return;
  }
  next_scene_callback_ = next_scene_callback;
}

std::future<sk_sp<SkImage>> EmbedderTestContext::GetNextSceneImage() {
  std::promise<sk_sp<SkImage>> promise;
  auto future = promise.get_future();
  SetNextSceneCallback(
      fml::MakeCopyable([promise = std::move(promise)](auto image) mutable {
        promise.set_value(image);
      }));
  return future;
}

bool EmbedderTestContext::SofwarePresent(sk_sp<SkImage> image) {
  software_surface_present_count_++;

  FireRootSurfacePresentCallbackIfPresent([image] { return image; });

  return true;
}

size_t EmbedderTestContext::GetGLSurfacePresentCount() const {
  return gl_surface_present_count_;
}

size_t EmbedderTestContext::GetSoftwareSurfacePresentCount() const {
  return software_surface_present_count_;
}

/// @note Procedure doesn't copy all closures.
void EmbedderTestContext::FireRootSurfacePresentCallbackIfPresent(
    const std::function<sk_sp<SkImage>(void)>& image_callback) {
  if (!next_scene_callback_) {
    return;
  }
  auto callback = next_scene_callback_;
  next_scene_callback_ = nullptr;
  callback(image_callback());
}

uint32_t EmbedderTestContext::GetWindowFBOId() const {
  FML_CHECK(gl_surface_);
  return gl_surface_->GetWindowFBOId();
}

}  // namespace testing
}  // namespace flutter
