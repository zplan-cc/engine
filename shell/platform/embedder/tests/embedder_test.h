// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_EMBEDDER_TESTS_EMBEDDER_TEST_H_
#define FLUTTER_SHELL_PLATFORM_EMBEDDER_TESTS_EMBEDDER_TEST_H_

#include <map>
#include <memory>

#include "flutter/fml/macros.h"
#include "flutter/shell/platform/embedder/tests/embedder_test_context_gl.h"
#include "flutter/shell/platform/embedder/tests/embedder_test_context_software.h"
#include "flutter/testing/testing.h"
#include "flutter/testing/thread_test.h"

namespace flutter {
namespace testing {

class EmbedderTest : public ThreadTest {
 public:
  enum class ContextType {
    kSoftwareContext,
    kOpenGLContext,
  };

  EmbedderTest();

  std::string GetFixturesDirectory() const;

  EmbedderTestContext& GetEmbedderContext(ContextType type);

 private:
  std::map<ContextType, std::unique_ptr<EmbedderTestContext>>
      embedder_contexts_;

  FML_DISALLOW_COPY_AND_ASSIGN(EmbedderTest);
};

}  // namespace testing
}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_EMBEDDER_TESTS_EMBEDDER_TEST_H_
