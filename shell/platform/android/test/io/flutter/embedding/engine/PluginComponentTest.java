// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package test.io.flutter.embedding.engine;

import static junit.framework.TestCase.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class PluginComponentTest {
  @Before
  public void setUp() {
    FlutterInjector.reset();
  }

  @Test
  public void pluginsCanAccessFlutterAssetPaths() {
    // Setup test.
    FlutterInjector.setInstance(new FlutterInjector.Builder().setShouldLoadNative(false).build());
    FlutterJNI flutterJNI = mock(FlutterJNI.class);
    when(flutterJNI.isAttached()).thenReturn(true);

    FlutterLoader flutterLoader = new FlutterLoader();

    // Execute behavior under test.
    FlutterEngine flutterEngine =
        new FlutterEngine(RuntimeEnvironment.application, flutterLoader, flutterJNI);

    // As soon as our plugin is registered it will look up asset paths and store them
    // for our verification.
    PluginThatAccessesAssets plugin = new PluginThatAccessesAssets();
    flutterEngine.getPlugins().add(plugin);

    // Verify results.
    assertEquals("flutter_assets/fake_asset.jpg", plugin.getAssetPathBasedOnName());
    assertEquals(
        "flutter_assets/packages/fakepackage/fake_asset.jpg",
        plugin.getAssetPathBasedOnNameAndPackage());
    assertEquals("flutter_assets/some/path/fake_asset.jpg", plugin.getAssetPathBasedOnSubpath());
    assertEquals(
        "flutter_assets/packages/fakepackage/some/path/fake_asset.jpg",
        plugin.getAssetPathBasedOnSubpathAndPackage());
    FlutterInjector.reset();
  }

  private static class PluginThatAccessesAssets implements FlutterPlugin {
    private String assetPathBasedOnName;
    private String assetPathBasedOnNameAndPackage;
    private String assetPathBasedOnSubpath;
    private String assetPathBasedOnSubpathAndPackage;

    public String getAssetPathBasedOnName() {
      return assetPathBasedOnName;
    }

    public String getAssetPathBasedOnNameAndPackage() {
      return assetPathBasedOnNameAndPackage;
    }

    public String getAssetPathBasedOnSubpath() {
      return assetPathBasedOnSubpath;
    }

    public String getAssetPathBasedOnSubpathAndPackage() {
      return assetPathBasedOnSubpathAndPackage;
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
      assetPathBasedOnName = binding.getFlutterAssets().getAssetFilePathByName("fake_asset.jpg");

      assetPathBasedOnNameAndPackage =
          binding.getFlutterAssets().getAssetFilePathByName("fake_asset.jpg", "fakepackage");

      assetPathBasedOnSubpath =
          binding.getFlutterAssets().getAssetFilePathByName("some/path/fake_asset.jpg");

      assetPathBasedOnSubpathAndPackage =
          binding
              .getFlutterAssets()
              .getAssetFilePathByName("some/path/fake_asset.jpg", "fakepackage");
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {}
  }
}
