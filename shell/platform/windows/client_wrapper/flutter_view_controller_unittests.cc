// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <memory>
#include <string>

#include "flutter/shell/platform/windows/client_wrapper/include/flutter/flutter_view_controller.h"
#include "flutter/shell/platform/windows/client_wrapper/testing/stub_flutter_windows_api.h"
#include "gtest/gtest.h"

namespace flutter {

namespace {

// Stub implementation to validate calls to the API.
class TestWindowsApi : public testing::StubFlutterWindowsApi {
 public:
  // |flutter::testing::StubFlutterWindowsApi|
  FlutterDesktopViewControllerRef ViewControllerCreate(
      int width,
      int height,
      FlutterDesktopEngineRef engine) override {
    return reinterpret_cast<FlutterDesktopViewControllerRef>(2);
  }

  // |flutter::testing::StubFlutterWindowsApi|
  void ViewControllerDestroy() override { view_controller_destroyed_ = true; }

  // |flutter::testing::StubFlutterWindowsApi|
  FlutterDesktopEngineRef EngineCreate(
      const FlutterDesktopEngineProperties& engine_properties) override {
    return reinterpret_cast<FlutterDesktopEngineRef>(1);
  }

  // |flutter::testing::StubFlutterWindowsApi|
  bool EngineDestroy() override {
    engine_destroyed_ = true;
    return true;
  }

  bool engine_destroyed() { return engine_destroyed_; }
  bool view_controller_destroyed() { return view_controller_destroyed_; }

 private:
  bool engine_destroyed_ = false;
  bool view_controller_destroyed_ = false;
};

}  // namespace

TEST(FlutterViewControllerTest, CreateDestroy) {
  DartProject project(L"data");
  testing::ScopedStubFlutterWindowsApi scoped_api_stub(
      std::make_unique<TestWindowsApi>());
  auto test_api = static_cast<TestWindowsApi*>(scoped_api_stub.stub());
  { FlutterViewController controller(100, 100, project); }
  EXPECT_TRUE(test_api->view_controller_destroyed());
  // Per the C API, once a view controller has taken ownership of an engine
  // the engine destruction method should not be called.
  EXPECT_FALSE(test_api->engine_destroyed());
}

TEST(FlutterViewControllerTest, GetEngine) {
  DartProject project(L"data");
  testing::ScopedStubFlutterWindowsApi scoped_api_stub(
      std::make_unique<TestWindowsApi>());
  auto test_api = static_cast<TestWindowsApi*>(scoped_api_stub.stub());
  FlutterViewController controller(100, 100, project);
  EXPECT_NE(controller.engine(), nullptr);
}

TEST(FlutterViewControllerTest, GetView) {
  DartProject project(L"data");
  testing::ScopedStubFlutterWindowsApi scoped_api_stub(
      std::make_unique<TestWindowsApi>());
  auto test_api = static_cast<TestWindowsApi*>(scoped_api_stub.stub());
  FlutterViewController controller(100, 100, project);
  EXPECT_NE(controller.view(), nullptr);
}

}  // namespace flutter
