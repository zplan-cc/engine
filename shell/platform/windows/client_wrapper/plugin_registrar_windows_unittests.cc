// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <memory>
#include <string>

#include "flutter/shell/platform/windows/client_wrapper/include/flutter/plugin_registrar_windows.h"
#include "flutter/shell/platform/windows/client_wrapper/testing/stub_flutter_windows_api.h"
#include "gtest/gtest.h"

namespace flutter {

namespace {

// Stub implementation to validate calls to the API.
class TestWindowsApi : public testing::StubFlutterWindowsApi {
 public:
  void PluginRegistrarRegisterTopLevelWindowProcDelegate(
      FlutterDesktopWindowProcCallback delegate,
      void* user_data) override {
    ++registered_delegate_count_;
    last_registered_delegate_ = delegate;
    last_registered_user_data_ = user_data;
  }

  void PluginRegistrarUnregisterTopLevelWindowProcDelegate(
      FlutterDesktopWindowProcCallback delegate) override {
    --registered_delegate_count_;
  }

  int registered_delegate_count() { return registered_delegate_count_; }

  FlutterDesktopWindowProcCallback last_registered_delegate() {
    return last_registered_delegate_;
  }

  void* last_registered_user_data() { return last_registered_user_data_; }

 private:
  int registered_delegate_count_ = 0;
  FlutterDesktopWindowProcCallback last_registered_delegate_ = nullptr;
  void* last_registered_user_data_ = nullptr;
};

}  // namespace

TEST(PluginRegistrarWindowsTest, GetView) {
  testing::ScopedStubFlutterWindowsApi scoped_api_stub(
      std::make_unique<TestWindowsApi>());
  auto test_api = static_cast<TestWindowsApi*>(scoped_api_stub.stub());
  PluginRegistrarWindows registrar(
      reinterpret_cast<FlutterDesktopPluginRegistrarRef>(1));
  EXPECT_NE(registrar.GetView(), nullptr);
}

TEST(PluginRegistrarWindowsTest, RegisterUnregister) {
  testing::ScopedStubFlutterWindowsApi scoped_api_stub(
      std::make_unique<TestWindowsApi>());
  auto test_api = static_cast<TestWindowsApi*>(scoped_api_stub.stub());
  PluginRegistrarWindows registrar(
      reinterpret_cast<FlutterDesktopPluginRegistrarRef>(1));

  WindowProcDelegate delegate = [](HWND hwnd, UINT message, WPARAM wparam,
                                   LPARAM lparam) {
    return std::optional<LRESULT>();
  };
  int id_a = registrar.RegisterTopLevelWindowProcDelegate(delegate);
  EXPECT_EQ(test_api->registered_delegate_count(), 1);
  int id_b = registrar.RegisterTopLevelWindowProcDelegate(delegate);
  // All the C++-level delegates are driven by a since C callback, so the
  // registration count should stay the same.
  EXPECT_EQ(test_api->registered_delegate_count(), 1);

  // Unregistering one of the two delegates shouldn't cause the underlying C
  // callback to be unregistered.
  registrar.UnregisterTopLevelWindowProcDelegate(id_a);
  EXPECT_EQ(test_api->registered_delegate_count(), 1);
  // Unregistering both should unregister it.
  registrar.UnregisterTopLevelWindowProcDelegate(id_b);
  EXPECT_EQ(test_api->registered_delegate_count(), 0);

  EXPECT_NE(id_a, id_b);
}

TEST(PluginRegistrarWindowsTest, CallsRegisteredDelegates) {
  testing::ScopedStubFlutterWindowsApi scoped_api_stub(
      std::make_unique<TestWindowsApi>());
  auto test_api = static_cast<TestWindowsApi*>(scoped_api_stub.stub());
  PluginRegistrarWindows registrar(
      reinterpret_cast<FlutterDesktopPluginRegistrarRef>(1));

  HWND dummy_hwnd;
  bool called_a = false;
  WindowProcDelegate delegate_a = [&called_a, &dummy_hwnd](
                                      HWND hwnd, UINT message, WPARAM wparam,
                                      LPARAM lparam) {
    called_a = true;
    EXPECT_EQ(hwnd, dummy_hwnd);
    EXPECT_EQ(message, 2);
    EXPECT_EQ(wparam, 3);
    EXPECT_EQ(lparam, 4);
    return std::optional<LRESULT>();
  };
  bool called_b = false;
  WindowProcDelegate delegate_b = [&called_b](HWND hwnd, UINT message,
                                              WPARAM wparam, LPARAM lparam) {
    called_b = true;
    return std::optional<LRESULT>();
  };
  int id_a = registrar.RegisterTopLevelWindowProcDelegate(delegate_a);
  int id_b = registrar.RegisterTopLevelWindowProcDelegate(delegate_b);

  LRESULT result = 0;
  bool handled = test_api->last_registered_delegate()(
      dummy_hwnd, 2, 3, 4, test_api->last_registered_user_data(), &result);
  EXPECT_TRUE(called_a);
  EXPECT_TRUE(called_b);
  EXPECT_FALSE(handled);
}

TEST(PluginRegistrarWindowsTest, StopsOnceHandled) {
  testing::ScopedStubFlutterWindowsApi scoped_api_stub(
      std::make_unique<TestWindowsApi>());
  auto test_api = static_cast<TestWindowsApi*>(scoped_api_stub.stub());
  PluginRegistrarWindows registrar(
      reinterpret_cast<FlutterDesktopPluginRegistrarRef>(1));

  bool called_a = false;
  WindowProcDelegate delegate_a = [&called_a](HWND hwnd, UINT message,
                                              WPARAM wparam, LPARAM lparam) {
    called_a = true;
    return std::optional<LRESULT>(7);
  };
  bool called_b = false;
  WindowProcDelegate delegate_b = [&called_b](HWND hwnd, UINT message,
                                              WPARAM wparam, LPARAM lparam) {
    called_b = true;
    return std::optional<LRESULT>(7);
  };
  int id_a = registrar.RegisterTopLevelWindowProcDelegate(delegate_a);
  int id_b = registrar.RegisterTopLevelWindowProcDelegate(delegate_b);

  HWND dummy_hwnd;
  LRESULT result = 0;
  bool handled = test_api->last_registered_delegate()(
      dummy_hwnd, 2, 3, 4, test_api->last_registered_user_data(), &result);
  // Only one of the delegates should have been called, since each claims to
  // have fully handled the message.
  EXPECT_TRUE(called_a || called_b);
  EXPECT_NE(called_a, called_b);
  // The return value should propagate through.
  EXPECT_TRUE(handled);
  EXPECT_EQ(result, 7);
}

}  // namespace flutter
