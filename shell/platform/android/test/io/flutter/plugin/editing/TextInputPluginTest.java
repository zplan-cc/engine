package io.flutter.plugin.editing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.AdditionalMatchers.geq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.notNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStructure;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.platform.PlatformViewsController;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowInputMethodManager;

@Config(manifest = Config.NONE, shadows = TextInputPluginTest.TestImm.class)
@RunWith(RobolectricTestRunner.class)
public class TextInputPluginTest {
  @Mock FlutterJNI mockFlutterJni;
  @Mock FlutterLoader mockFlutterLoader;

  // Verifies the method and arguments for a captured method call.
  private void verifyMethodCall(ByteBuffer buffer, String methodName, String[] expectedArgs)
      throws JSONException {
    buffer.rewind();
    MethodCall methodCall = JSONMethodCodec.INSTANCE.decodeMethodCall(buffer);
    assertEquals(methodName, methodCall.method);
    if (expectedArgs != null) {
      JSONArray args = methodCall.arguments();
      assertEquals(expectedArgs.length, args.length());
      for (int i = 0; i < args.length(); i++) {
        assertEquals(expectedArgs[i], args.get(i).toString());
      }
    }
  }

  private static void sendToBinaryMessageHandler(
      BinaryMessenger.BinaryMessageHandler binaryMessageHandler, String method, Object args) {
    MethodCall methodCall = new MethodCall(method, args);
    ByteBuffer encodedMethodCall = JSONMethodCodec.INSTANCE.encodeMethodCall(methodCall);
    binaryMessageHandler.onMessage(
        (ByteBuffer) encodedMethodCall.flip(), mock(BinaryMessenger.BinaryReply.class));
  }

  @Test
  public void textInputPlugin_RequestsReattachOnCreation() throws JSONException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(RuntimeEnvironment.application);

    FlutterJNI mockFlutterJni = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJni, mock(AssetManager.class)));
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));

    ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);

    verify(dartExecutor, times(1))
        .send(
            channelCaptor.capture(),
            bufferCaptor.capture(),
            any(BinaryMessenger.BinaryReply.class));
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(bufferCaptor.getValue(), "TextInputClient.requestExistingInputState", null);
  }

  @Test
  public void setTextInputEditingState_doesNotRestartWhenTextIsIdentical() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));

    // Move the cursor.
    assertEquals(1, testImm.getRestartCount(testView));
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));

    // Verify that we haven't restarted the input.
    assertEquals(1, testImm.getRestartCount(testView));
  }

  @Test
  public void setTextInputEditingState_alwaysSetEditableWhenDifferent() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now. With
    // changed text, we should
    // always set the Editable contents.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("hello", 0, 0));
    assertEquals(1, testImm.getRestartCount(testView));
    assertTrue(textInputPlugin.getEditable().toString().equals("hello"));

    // No pending restart, set Editable contents anyways.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("Shibuyawoo", 0, 0));
    assertEquals(1, testImm.getRestartCount(testView));
    assertTrue(textInputPlugin.getEditable().toString().equals("Shibuyawoo"));
  }

  // See https://github.com/flutter/flutter/issues/29341 and
  // https://github.com/flutter/flutter/issues/31512
  // All modern Samsung keybords are affected including non-korean languages and thus
  // need the restart.
  @Test
  public void setTextInputEditingState_alwaysRestartsOnAffectedDevices2() {
    // Initialize a TextInputPlugin that needs to be always restarted.
    ShadowBuild.setManufacturer("samsung");
    InputMethodSubtype inputMethodSubtype =
        new InputMethodSubtype(0, 0, /*locale=*/ "en", "", "", false, false);
    Settings.Secure.putString(
        RuntimeEnvironment.application.getContentResolver(),
        Settings.Secure.DEFAULT_INPUT_METHOD,
        "com.sec.android.inputmethod/.SamsungKeypad");
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));

    // Move the cursor.
    assertEquals(1, testImm.getRestartCount(testView));
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));

    // Verify that we've restarted the input.
    assertEquals(2, testImm.getRestartCount(testView));
  }

  @Test
  public void setTextInputEditingState_doesNotRestartOnUnaffectedDevices() {
    // Initialize a TextInputPlugin that needs to be always restarted.
    ShadowBuild.setManufacturer("samsung");
    InputMethodSubtype inputMethodSubtype =
        new InputMethodSubtype(0, 0, /*locale=*/ "en", "", "", false, false);
    Settings.Secure.putString(
        RuntimeEnvironment.application.getContentResolver(),
        Settings.Secure.DEFAULT_INPUT_METHOD,
        "com.fake.test.blah/.NotTheRightKeyboard");
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));

    // Move the cursor.
    assertEquals(1, testImm.getRestartCount(testView));
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));

    // Verify that we've restarted the input.
    assertEquals(1, testImm.getRestartCount(testView));
  }

  @Test
  public void setTextInputEditingState_nullInputMethodSubtype() {
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(null);

    View testView = new View(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));
    assertEquals(1, testImm.getRestartCount(testView));
  }

  @Test
  public void destroy_clearTextInputMethodHandler() {
    View testView = new View(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    verify(textInputChannel, times(1))
        .setTextInputMethodHandler(notNull(TextInputChannel.TextInputMethodHandler.class));
    textInputPlugin.destroy();
    verify(textInputChannel, times(1))
        .setTextInputMethodHandler(isNull(TextInputChannel.TextInputMethodHandler.class));
  }

  @Test
  public void inputConnection_createsActionFromEnter() throws JSONException {
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    FlutterJNI mockFlutterJni = mock(FlutterJNI.class);
    View testView = new View(RuntimeEnvironment.application);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJni, mock(AssetManager.class)));
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(TextInputChannel.TextInputType.TEXT, false, false),
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));

    ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(dartExecutor, times(1))
        .send(
            channelCaptor.capture(),
            bufferCaptor.capture(),
            any(BinaryMessenger.BinaryReply.class));
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(bufferCaptor.getValue(), "TextInputClient.requestExistingInputState", null);
    InputConnection connection = textInputPlugin.createInputConnection(testView, new EditorInfo());

    connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    verify(dartExecutor, times(2))
        .send(
            channelCaptor.capture(),
            bufferCaptor.capture(),
            any(BinaryMessenger.BinaryReply.class));
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(
        bufferCaptor.getValue(),
        "TextInputClient.performAction",
        new String[] {"0", "TextInputAction.done"});
    connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));

    connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER));
    verify(dartExecutor, times(3))
        .send(
            channelCaptor.capture(),
            bufferCaptor.capture(),
            any(BinaryMessenger.BinaryReply.class));
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(
        bufferCaptor.getValue(),
        "TextInputClient.performAction",
        new String[] {"0", "TextInputAction.done"});
  }

  @Test
  public void inputConnection_finishComposingTextUpdatesIMM() throws JSONException {
    ShadowBuild.setManufacturer("samsung");
    InputMethodSubtype inputMethodSubtype =
        new InputMethodSubtype(0, 0, /*locale=*/ "en", "", "", false, false);
    Settings.Secure.putString(
        RuntimeEnvironment.application.getContentResolver(),
        Settings.Secure.DEFAULT_INPUT_METHOD,
        "com.sec.android.inputmethod/.SamsungKeypad");
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    FlutterJNI mockFlutterJni = mock(FlutterJNI.class);
    View testView = new View(RuntimeEnvironment.application);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJni, mock(AssetManager.class)));
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(TextInputChannel.TextInputType.TEXT, false, false),
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0));
    InputConnection connection = textInputPlugin.createInputConnection(testView, new EditorInfo());

    connection.finishComposingText();

    if (Build.VERSION.SDK_INT >= 21) {
      CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
      builder.setComposingText(-1, "");
      CursorAnchorInfo anchorInfo = builder.build();
      assertEquals(testImm.getLastCursorAnchorInfo(), anchorInfo);
    }
  }

  @Test
  public void autofill_onProvideVirtualViewStructure() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

    FlutterView testView = new FlutterView(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    final TextInputChannel.Configuration.Autofill autofill1 =
        new TextInputChannel.Configuration.Autofill(
            "1", new String[] {"HINT1"}, new TextInputChannel.TextEditState("", 0, 0));
    final TextInputChannel.Configuration.Autofill autofill2 =
        new TextInputChannel.Configuration.Autofill(
            "2", new String[] {"HINT2", "EXTRA"}, new TextInputChannel.TextEditState("", 0, 0));

    final TextInputChannel.Configuration config1 =
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofill1,
            null);
    final TextInputChannel.Configuration config2 =
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofill2,
            null);

    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofill1,
            new TextInputChannel.Configuration[] {config1, config2}));

    final ViewStructure viewStructure = mock(ViewStructure.class);
    final ViewStructure[] children = {mock(ViewStructure.class), mock(ViewStructure.class)};

    when(viewStructure.newChild(anyInt()))
        .thenAnswer(invocation -> children[invocation.getArgumentAt(0, int.class)]);

    textInputPlugin.onProvideAutofillVirtualStructure(viewStructure, 0);

    verify(viewStructure).newChild(0);
    verify(viewStructure).newChild(1);

    verify(children[0]).setAutofillId(any(), eq("1".hashCode()));
    verify(children[0]).setAutofillHints(aryEq(new String[] {"HINT1"}));
    verify(children[0]).setDimens(anyInt(), anyInt(), anyInt(), anyInt(), geq(0), geq(0));

    verify(children[1]).setAutofillId(any(), eq("2".hashCode()));
    verify(children[1]).setAutofillHints(aryEq(new String[] {"HINT2", "EXTRA"}));
    verify(children[1]).setDimens(anyInt(), anyInt(), anyInt(), anyInt(), geq(0), geq(0));
  }

  @Test
  public void autofill_onProvideVirtualViewStructure_single() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }

    FlutterView testView = new FlutterView(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    final TextInputChannel.Configuration.Autofill autofill =
        new TextInputChannel.Configuration.Autofill(
            "1", new String[] {"HINT1"}, new TextInputChannel.TextEditState("", 0, 0));

    // Autofill should still work without AutofillGroup.
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofill,
            null));

    final ViewStructure viewStructure = mock(ViewStructure.class);
    final ViewStructure[] children = {mock(ViewStructure.class)};

    when(viewStructure.newChild(anyInt()))
        .thenAnswer(invocation -> children[invocation.getArgumentAt(0, int.class)]);

    textInputPlugin.onProvideAutofillVirtualStructure(viewStructure, 0);

    verify(viewStructure).newChild(0);

    verify(children[0]).setAutofillId(any(), eq("1".hashCode()));
    verify(children[0]).setAutofillHints(aryEq(new String[] {"HINT1"}));
    // Verifies that the child has a non-zero size.
    verify(children[0]).setDimens(anyInt(), anyInt(), anyInt(), anyInt(), geq(0), geq(0));
  }

  @Test
  public void respondsToInputChannelMessages() {
    ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> binaryMessageHandlerCaptor =
        ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
    DartExecutor mockBinaryMessenger = mock(DartExecutor.class);
    TextInputChannel.TextInputMethodHandler mockHandler =
        mock(TextInputChannel.TextInputMethodHandler.class);
    TextInputChannel textInputChannel = new TextInputChannel(mockBinaryMessenger);

    textInputChannel.setTextInputMethodHandler(mockHandler);

    verify(mockBinaryMessenger, times(1))
        .setMessageHandler(any(String.class), binaryMessageHandlerCaptor.capture());

    BinaryMessenger.BinaryMessageHandler binaryMessageHandler =
        binaryMessageHandlerCaptor.getValue();

    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.requestAutofill", null);
    verify(mockHandler, times(1)).requestAutofill();

    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.finishAutofillContext", true);
    verify(mockHandler, times(1)).finishAutofillContext(true);

    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.finishAutofillContext", false);
    verify(mockHandler, times(1)).finishAutofillContext(false);
  }

  @Test
  public void sendAppPrivateCommand_dataIsEmpty() throws JSONException {
    ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> binaryMessageHandlerCaptor =
        ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
    DartExecutor mockBinaryMessenger = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(mockBinaryMessenger);

    EventHandler mockEventHandler = mock(EventHandler.class);
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setEventHandler(mockEventHandler);

    View testView = new View(RuntimeEnvironment.application);
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));

    verify(mockBinaryMessenger, times(1))
        .setMessageHandler(any(String.class), binaryMessageHandlerCaptor.capture());

    JSONObject arguments = new JSONObject();
    arguments.put("action", "actionCommand");
    arguments.put("data", "");

    BinaryMessenger.BinaryMessageHandler binaryMessageHandler =
        binaryMessageHandlerCaptor.getValue();
    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.sendAppPrivateCommand", arguments);
    verify(mockEventHandler, times(1))
        .sendAppPrivateCommand(any(View.class), eq("actionCommand"), eq(null));
  }

  @Test
  public void sendAppPrivateCommand_hasData() throws JSONException {
    ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> binaryMessageHandlerCaptor =
        ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
    DartExecutor mockBinaryMessenger = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(mockBinaryMessenger);

    EventHandler mockEventHandler = mock(EventHandler.class);
    TestImm testImm =
        Shadow.extract(
            RuntimeEnvironment.application.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setEventHandler(mockEventHandler);

    View testView = new View(RuntimeEnvironment.application);
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));

    verify(mockBinaryMessenger, times(1))
        .setMessageHandler(any(String.class), binaryMessageHandlerCaptor.capture());

    JSONObject arguments = new JSONObject();
    arguments.put("action", "actionCommand");
    arguments.put("data", "actionData");

    ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
    BinaryMessenger.BinaryMessageHandler binaryMessageHandler =
        binaryMessageHandlerCaptor.getValue();
    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.sendAppPrivateCommand", arguments);
    verify(mockEventHandler, times(1))
        .sendAppPrivateCommand(any(View.class), eq("actionCommand"), bundleCaptor.capture());
    assertEquals("actionData", bundleCaptor.getValue().getCharSequence("data"));
  }

  @Test
  @TargetApi(30)
  @Config(sdk = 30)
  public void ime_windowInsetsSync() {
    FlutterView testView = new FlutterView(RuntimeEnvironment.application);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(testView, textInputChannel, mock(PlatformViewsController.class));
    TextInputPlugin.ImeSyncDeferringInsetsCallback imeSyncCallback =
        textInputPlugin.getImeSyncCallback();
    FlutterEngine flutterEngine =
        spy(new FlutterEngine(RuntimeEnvironment.application, mockFlutterLoader, mockFlutterJni));
    FlutterRenderer flutterRenderer = spy(new FlutterRenderer(mockFlutterJni));
    when(flutterEngine.getRenderer()).thenReturn(flutterRenderer);
    testView.attachToFlutterEngine(flutterEngine);

    WindowInsetsAnimation animation = mock(WindowInsetsAnimation.class);
    when(animation.getTypeMask()).thenReturn(WindowInsets.Type.ime());

    List<WindowInsetsAnimation> animationList = new ArrayList();
    animationList.add(animation);

    WindowInsets.Builder builder = new WindowInsets.Builder();
    WindowInsets noneInsets = builder.build();

    // imeInsets0, 1, and 2 contain unique IME bottom insets, and are used
    // to distinguish which insets were sent at each stage.
    builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 100));
    builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(10, 10, 10, 40));
    WindowInsets imeInsets0 = builder.build();

    builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 30));
    builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(10, 10, 10, 40));
    WindowInsets imeInsets1 = builder.build();

    builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 50));
    builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(10, 10, 10, 40));
    WindowInsets imeInsets2 = builder.build();

    builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 200));
    builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(10, 10, 10, 0));
    WindowInsets deferredInsets = builder.build();

    ArgumentCaptor<FlutterRenderer.ViewportMetrics> viewportMetricsCaptor =
        ArgumentCaptor.forClass(FlutterRenderer.ViewportMetrics.class);

    imeSyncCallback.onApplyWindowInsets(testView, deferredInsets);
    imeSyncCallback.onApplyWindowInsets(testView, noneInsets);

    verify(flutterRenderer).setViewportMetrics(viewportMetricsCaptor.capture());
    assertEquals(0, viewportMetricsCaptor.getValue().paddingBottom);
    assertEquals(0, viewportMetricsCaptor.getValue().paddingTop);
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetTop);

    imeSyncCallback.onPrepare(animation);
    imeSyncCallback.onApplyWindowInsets(testView, deferredInsets);
    imeSyncCallback.onStart(animation, null);
    // Only the final state call is saved, extra calls are passed on.
    imeSyncCallback.onApplyWindowInsets(testView, imeInsets2);

    verify(flutterRenderer).setViewportMetrics(viewportMetricsCaptor.capture());
    // No change, as deferredInset is stored to be passed in onEnd()
    assertEquals(0, viewportMetricsCaptor.getValue().paddingBottom);
    assertEquals(0, viewportMetricsCaptor.getValue().paddingTop);
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetTop);

    imeSyncCallback.onProgress(imeInsets0, animationList);

    verify(flutterRenderer).setViewportMetrics(viewportMetricsCaptor.capture());
    assertEquals(40, viewportMetricsCaptor.getValue().paddingBottom);
    assertEquals(10, viewportMetricsCaptor.getValue().paddingTop);
    assertEquals(60, viewportMetricsCaptor.getValue().viewInsetBottom);
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetTop);

    imeSyncCallback.onProgress(imeInsets1, animationList);

    verify(flutterRenderer).setViewportMetrics(viewportMetricsCaptor.capture());
    assertEquals(40, viewportMetricsCaptor.getValue().paddingBottom);
    assertEquals(10, viewportMetricsCaptor.getValue().paddingTop);
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom); // Cannot be negative
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetTop);

    imeSyncCallback.onEnd(animation);

    verify(flutterRenderer).setViewportMetrics(viewportMetricsCaptor.capture());
    // Values should be of deferredInsets, not imeInsets2
    assertEquals(0, viewportMetricsCaptor.getValue().paddingBottom);
    assertEquals(10, viewportMetricsCaptor.getValue().paddingTop);
    assertEquals(200, viewportMetricsCaptor.getValue().viewInsetBottom);
    assertEquals(0, viewportMetricsCaptor.getValue().viewInsetTop);
  }

  interface EventHandler {
    void sendAppPrivateCommand(View view, String action, Bundle data);
  }

  @Implements(InputMethodManager.class)
  public static class TestImm extends ShadowInputMethodManager {
    private InputMethodSubtype currentInputMethodSubtype;
    private SparseIntArray restartCounter = new SparseIntArray();
    private CursorAnchorInfo cursorAnchorInfo;
    private ArrayList<Integer> selectionUpdateValues;
    private boolean trackSelection = false;
    private EventHandler handler;

    public TestImm() {
      selectionUpdateValues = new ArrayList<Integer>();
    }

    @Implementation
    public InputMethodSubtype getCurrentInputMethodSubtype() {
      return currentInputMethodSubtype;
    }

    @Implementation
    public void restartInput(View view) {
      int count = restartCounter.get(view.hashCode(), /*defaultValue=*/ 0) + 1;
      restartCounter.put(view.hashCode(), count);
    }

    public void setCurrentInputMethodSubtype(InputMethodSubtype inputMethodSubtype) {
      this.currentInputMethodSubtype = inputMethodSubtype;
    }

    public int getRestartCount(View view) {
      return restartCounter.get(view.hashCode(), /*defaultValue=*/ 0);
    }

    public void setEventHandler(EventHandler eventHandler) {
      handler = eventHandler;
    }

    @Implementation
    public void sendAppPrivateCommand(View view, String action, Bundle data) {
      handler.sendAppPrivateCommand(view, action, data);
    }

    @Implementation
    public void updateCursorAnchorInfo(View view, CursorAnchorInfo cursorAnchorInfo) {
      this.cursorAnchorInfo = cursorAnchorInfo;
    }

    // We simply store the values to verify later.
    @Implementation
    public void updateSelection(
        View view, int selStart, int selEnd, int candidatesStart, int candidatesEnd) {
      if (trackSelection) {
        this.selectionUpdateValues.add(selStart);
        this.selectionUpdateValues.add(selEnd);
        this.selectionUpdateValues.add(candidatesStart);
        this.selectionUpdateValues.add(candidatesEnd);
      }
    }

    // only track values when enabled via this.
    public void setTrackSelection(boolean val) {
      trackSelection = val;
    }

    // Returns true if the last updateSelection call passed the following values.
    public ArrayList<Integer> getSelectionUpdateValues() {
      return selectionUpdateValues;
    }

    public CursorAnchorInfo getLastCursorAnchorInfo() {
      return cursorAnchorInfo;
    }
  }
}
