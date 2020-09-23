package io.flutter.embedding.engine.systemchannels;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.Log;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@link TextInputChannel} is a platform channel between Android and Flutter that is used to
 * communicate information about the user's text input.
 *
 * <p>When the user presses an action button like "done" or "next", that action is sent from Android
 * to Flutter through this {@link TextInputChannel}.
 *
 * <p>When an input system in the Flutter app wants to show the keyboard, or hide it, or configure
 * editing state, etc. a message is sent from Flutter to Android through this {@link
 * TextInputChannel}.
 *
 * <p>{@link TextInputChannel} comes with a default {@link
 * io.flutter.plugin.common.MethodChannel.MethodCallHandler} that parses incoming messages from
 * Flutter. Register a {@link TextInputMethodHandler} to respond to standard Flutter text input
 * messages.
 */
public class TextInputChannel {
  private static final String TAG = "TextInputChannel";

  @NonNull public final MethodChannel channel;
  @Nullable private TextInputMethodHandler textInputMethodHandler;

  private final MethodChannel.MethodCallHandler parsingMethodHandler =
      new MethodChannel.MethodCallHandler() {
        @Override
        public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
          if (textInputMethodHandler == null) {
            // If no explicit TextInputMethodHandler has been registered then we don't
            // need to forward this call to an API. Return.
            return;
          }

          String method = call.method;
          Object args = call.arguments;
          Log.v(TAG, "Received '" + method + "' message.");
          switch (method) {
            case "TextInput.show":
              textInputMethodHandler.show();
              result.success(null);
              break;
            case "TextInput.hide":
              textInputMethodHandler.hide();
              result.success(null);
              break;
            case "TextInput.setClient":
              try {
                final JSONArray argumentList = (JSONArray) args;
                final int textInputClientId = argumentList.getInt(0);
                final JSONObject jsonConfiguration = argumentList.getJSONObject(1);
                textInputMethodHandler.setClient(
                    textInputClientId, Configuration.fromJson(jsonConfiguration));
                result.success(null);
              } catch (JSONException | NoSuchFieldException exception) {
                // JSONException: missing keys or bad value types.
                // NoSuchFieldException: one or more values were invalid.
                result.error("error", exception.getMessage(), null);
              }
              break;
            case "TextInput.requestAutofill":
              textInputMethodHandler.requestAutofill();
              result.success(null);
              break;
            case "TextInput.setPlatformViewClient":
              final int id = (int) args;
              textInputMethodHandler.setPlatformViewClient(id);
              break;
            case "TextInput.setEditingState":
              try {
                final JSONObject editingState = (JSONObject) args;
                textInputMethodHandler.setEditingState(TextEditState.fromJson(editingState));
                result.success(null);
              } catch (JSONException exception) {
                result.error("error", exception.getMessage(), null);
              }
              break;
            case "TextInput.setEditableSizeAndTransform":
              try {
                final JSONObject arguments = (JSONObject) args;
                final double width = arguments.getDouble("width");
                final double height = arguments.getDouble("height");
                final JSONArray jsonMatrix = arguments.getJSONArray("transform");
                final double[] matrix = new double[16];
                for (int i = 0; i < 16; i++) {
                  matrix[i] = jsonMatrix.getDouble(i);
                }

                textInputMethodHandler.setEditableSizeAndTransform(width, height, matrix);
              } catch (JSONException exception) {
                result.error("error", exception.getMessage(), null);
              }
              break;
            case "TextInput.clearClient":
              textInputMethodHandler.clearClient();
              result.success(null);
              break;
            case "TextInput.sendAppPrivateCommand":
              try {
                final JSONObject arguments = (JSONObject) args;
                final String action = arguments.getString("action");
                final String data = arguments.getString("data");
                Bundle bundle = null;
                if (data != null && !data.isEmpty()) {
                  bundle = new Bundle();
                  bundle.putString("data", data);
                }
                textInputMethodHandler.sendAppPrivateCommand(action, bundle);
                result.success(null);
              } catch (JSONException exception) {
                result.error("error", exception.getMessage(), null);
              }
              break;
            case "TextInput.finishAutofillContext":
              textInputMethodHandler.finishAutofillContext((boolean) args);
              result.success(null);
              break;
            default:
              result.notImplemented();
              break;
          }
        }
      };

  /**
   * Constructs a {@code TextInputChannel} that connects Android to the Dart code running in {@code
   * dartExecutor}.
   *
   * <p>The given {@code dartExecutor} is permitted to be idle or executing code.
   *
   * <p>See {@link DartExecutor}.
   */
  public TextInputChannel(@NonNull DartExecutor dartExecutor) {
    this.channel = new MethodChannel(dartExecutor, "flutter/textinput", JSONMethodCodec.INSTANCE);
    channel.setMethodCallHandler(parsingMethodHandler);
  }

  /**
   * Instructs Flutter to reattach the last active text input client, if any.
   *
   * <p>This is necessary when the view heirarchy has been detached and reattached to a {@link
   * FlutterEngine}, as the engine may have kept alive a text editing client on the Dart side.
   */
  public void requestExistingInputState() {
    channel.invokeMethod("TextInputClient.requestExistingInputState", null);
  }

  private static HashMap<Object, Object> createEditingStateJSON(
      String text, int selectionStart, int selectionEnd, int composingStart, int composingEnd) {
    HashMap<Object, Object> state = new HashMap<>();
    state.put("text", text);
    state.put("selectionBase", selectionStart);
    state.put("selectionExtent", selectionEnd);
    state.put("composingBase", composingStart);
    state.put("composingExtent", composingEnd);
    return state;
  }
  /**
   * Instructs Flutter to update its text input editing state to reflect the given configuration.
   */
  public void updateEditingState(
      int inputClientId,
      String text,
      int selectionStart,
      int selectionEnd,
      int composingStart,
      int composingEnd) {
    Log.v(
        TAG,
        "Sending message to update editing state: \n"
            + "Text: "
            + text
            + "\n"
            + "Selection start: "
            + selectionStart
            + "\n"
            + "Selection end: "
            + selectionEnd
            + "\n"
            + "Composing start: "
            + composingStart
            + "\n"
            + "Composing end: "
            + composingEnd);

    final HashMap<Object, Object> state =
        createEditingStateJSON(text, selectionStart, selectionEnd, composingStart, composingEnd);

    channel.invokeMethod("TextInputClient.updateEditingState", Arrays.asList(inputClientId, state));
  }

  public void updateEditingStateWithTag(
      int inputClientId, HashMap<String, TextEditState> editStates) {
    Log.v(
        TAG,
        "Sending message to update editing state for "
            + String.valueOf(editStates.size())
            + " field(s).");

    final HashMap<String, HashMap<Object, Object>> json = new HashMap<>();
    for (Map.Entry<String, TextEditState> element : editStates.entrySet()) {
      final TextEditState state = element.getValue();
      json.put(
          element.getKey(),
          createEditingStateJSON(state.text, state.selectionStart, state.selectionEnd, -1, -1));
    }
    channel.invokeMethod(
        "TextInputClient.updateEditingStateWithTag", Arrays.asList(inputClientId, json));
  }

  /** Instructs Flutter to execute a "newline" action. */
  public void newline(int inputClientId) {
    Log.v(TAG, "Sending 'newline' message.");
    channel.invokeMethod(
        "TextInputClient.performAction", Arrays.asList(inputClientId, "TextInputAction.newline"));
  }

  /** Instructs Flutter to execute a "go" action. */
  public void go(int inputClientId) {
    Log.v(TAG, "Sending 'go' message.");
    channel.invokeMethod(
        "TextInputClient.performAction", Arrays.asList(inputClientId, "TextInputAction.go"));
  }

  /** Instructs Flutter to execute a "search" action. */
  public void search(int inputClientId) {
    Log.v(TAG, "Sending 'search' message.");
    channel.invokeMethod(
        "TextInputClient.performAction", Arrays.asList(inputClientId, "TextInputAction.search"));
  }

  /** Instructs Flutter to execute a "send" action. */
  public void send(int inputClientId) {
    Log.v(TAG, "Sending 'send' message.");
    channel.invokeMethod(
        "TextInputClient.performAction", Arrays.asList(inputClientId, "TextInputAction.send"));
  }

  /** Instructs Flutter to execute a "done" action. */
  public void done(int inputClientId) {
    Log.v(TAG, "Sending 'done' message.");
    channel.invokeMethod(
        "TextInputClient.performAction", Arrays.asList(inputClientId, "TextInputAction.done"));
  }

  /** Instructs Flutter to execute a "next" action. */
  public void next(int inputClientId) {
    Log.v(TAG, "Sending 'next' message.");
    channel.invokeMethod(
        "TextInputClient.performAction", Arrays.asList(inputClientId, "TextInputAction.next"));
  }

  /** Instructs Flutter to execute a "previous" action. */
  public void previous(int inputClientId) {
    Log.v(TAG, "Sending 'previous' message.");
    channel.invokeMethod(
        "TextInputClient.performAction", Arrays.asList(inputClientId, "TextInputAction.previous"));
  }

  /** Instructs Flutter to execute an "unspecified" action. */
  public void unspecifiedAction(int inputClientId) {
    Log.v(TAG, "Sending 'unspecified' message.");
    channel.invokeMethod(
        "TextInputClient.performAction",
        Arrays.asList(inputClientId, "TextInputAction.unspecified"));
  }

  public void performPrivateCommand(int inputClientId, String action, Bundle data) {
    HashMap<Object, Object> json = new HashMap<>();
    json.put("action", action);
    if (data != null) {
      HashMap<String, Object> dataMap = new HashMap<>();
      Set<String> keySet = data.keySet();
      for (String key : keySet) {
        Object value = data.get(key);
        if (value instanceof byte[]) {
          dataMap.put(key, data.getByteArray(key));
        } else if (value instanceof Byte) {
          dataMap.put(key, data.getByte(key));
        } else if (value instanceof char[]) {
          dataMap.put(key, data.getCharArray(key));
        } else if (value instanceof Character) {
          dataMap.put(key, data.getChar(key));
        } else if (value instanceof CharSequence[]) {
          dataMap.put(key, data.getCharSequenceArray(key));
        } else if (value instanceof CharSequence) {
          dataMap.put(key, data.getCharSequence(key));
        } else if (value instanceof float[]) {
          dataMap.put(key, data.getFloatArray(key));
        } else if (value instanceof Float) {
          dataMap.put(key, data.getFloat(key));
        }
      }
      json.put("data", dataMap);
    }
    channel.invokeMethod(
        "TextInputClient.performPrivateCommand", Arrays.asList(inputClientId, json));
  }

  /**
   * Sets the {@link TextInputMethodHandler} which receives all events and requests that are parsed
   * from the underlying platform channel.
   */
  public void setTextInputMethodHandler(@Nullable TextInputMethodHandler textInputMethodHandler) {
    this.textInputMethodHandler = textInputMethodHandler;
  }

  public interface TextInputMethodHandler {
    // TODO(mattcarroll): javadoc
    void show();

    // TODO(mattcarroll): javadoc
    void hide();

    /**
     * Requests that the autofill dropdown menu appear for the current client.
     *
     * <p>Has no effect if the current client does not support autofill.
     */
    void requestAutofill();

    /**
     * Requests that the {@link AutofillManager} cancel or commit the current autofill context.
     *
     * <p>The method calls {@link android.view.autofill.AutofillManager#commit()} when {@code
     * shouldSave} is true, and calls {@link android.view.autofill.AutofillManager#cancel()}
     * otherwise.
     *
     * @param shouldSave whether the active autofill service should save the current user input for
     *     future use.
     */
    void finishAutofillContext(boolean shouldSave);

    // TODO(mattcarroll): javadoc
    void setClient(int textInputClientId, @NonNull Configuration configuration);

    /**
     * Sets a platform view as the text input client.
     *
     * <p>Subsequent calls to createInputConnection will be delegated to the platform view until a
     * different client is set.
     *
     * @param id the ID of the platform view to be set as a text input client.
     */
    void setPlatformViewClient(int id);

    /**
     * Sets the size and the transform matrix of the current text input client.
     *
     * @param width the width of text input client. Must be finite.
     * @param height the height of text input client. Must be finite.
     * @param transform a 4x4 matrix that maps the local paint coordinate system to coordinate
     *     system of the FlutterView that owns the current client.
     */
    void setEditableSizeAndTransform(double width, double height, double[] transform);

    // TODO(mattcarroll): javadoc
    void setEditingState(@NonNull TextEditState editingState);

    // TODO(mattcarroll): javadoc
    void clearClient();

    /**
     * Sends client app private command to the current text input client(input method). The app
     * private command result will be informed through {@code performPrivateCommand}.
     *
     * @param action Name of the command to be performed. This must be a scoped name. i.e. prefixed
     *     with a package name you own, so that different developers will not create conflicting
     *     commands.
     * @param data Any data to include with the command.
     */
    void sendAppPrivateCommand(String action, Bundle data);
  }

  /** A text editing configuration. */
  public static class Configuration {
    public static Configuration fromJson(@NonNull JSONObject json)
        throws JSONException, NoSuchFieldException {
      final String inputActionName = json.getString("inputAction");
      if (inputActionName == null) {
        throw new JSONException("Configuration JSON missing 'inputAction' property.");
      }
      Configuration[] fields = null;
      if (!json.isNull("fields")) {
        final JSONArray jsonFields = json.getJSONArray("fields");
        fields = new Configuration[jsonFields.length()];
        for (int i = 0; i < fields.length; i++) {
          fields[i] = Configuration.fromJson(jsonFields.getJSONObject(i));
        }
      }
      final Integer inputAction = inputActionFromTextInputAction(inputActionName);
      return new Configuration(
          json.optBoolean("obscureText"),
          json.optBoolean("autocorrect", true),
          json.optBoolean("enableSuggestions"),
          TextCapitalization.fromValue(json.getString("textCapitalization")),
          InputType.fromJson(json.getJSONObject("inputType")),
          inputAction,
          json.isNull("actionLabel") ? null : json.getString("actionLabel"),
          json.isNull("autofill") ? null : Autofill.fromJson(json.getJSONObject("autofill")),
          fields);
    }

    @NonNull
    private static Integer inputActionFromTextInputAction(@NonNull String inputAction) {
      switch (inputAction) {
        case "TextInputAction.newline":
          return EditorInfo.IME_ACTION_NONE;
        case "TextInputAction.none":
          return EditorInfo.IME_ACTION_NONE;
        case "TextInputAction.unspecified":
          return EditorInfo.IME_ACTION_UNSPECIFIED;
        case "TextInputAction.done":
          return EditorInfo.IME_ACTION_DONE;
        case "TextInputAction.go":
          return EditorInfo.IME_ACTION_GO;
        case "TextInputAction.search":
          return EditorInfo.IME_ACTION_SEARCH;
        case "TextInputAction.send":
          return EditorInfo.IME_ACTION_SEND;
        case "TextInputAction.next":
          return EditorInfo.IME_ACTION_NEXT;
        case "TextInputAction.previous":
          return EditorInfo.IME_ACTION_PREVIOUS;
        default:
          // Present default key if bad input type is given.
          return EditorInfo.IME_ACTION_UNSPECIFIED;
      }
    }

    public static class Autofill {
      public static Autofill fromJson(@NonNull JSONObject json)
          throws JSONException, NoSuchFieldException {
        final String uniqueIdentifier = json.getString("uniqueIdentifier");
        final JSONArray hints = json.getJSONArray("hints");
        final JSONObject editingState = json.getJSONObject("editingValue");
        final String[] hintList = new String[hints.length()];

        for (int i = 0; i < hintList.length; i++) {
          hintList[i] = translateAutofillHint(hints.getString(i));
        }
        return new Autofill(uniqueIdentifier, hintList, TextEditState.fromJson(editingState));
      }

      public final String uniqueIdentifier;
      public final String[] hints;
      public final TextEditState editState;

      @NonNull
      private static String translateAutofillHint(@NonNull String hint) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
          return hint;
        }
        switch (hint) {
          case "addressCity":
            return "addressLocality";
          case "addressState":
            return "addressRegion";
          case "birthday":
            return "birthDateFull";
          case "birthdayDay":
            return "birthDateDay";
          case "birthdayMonth":
            return "birthDateMonth";
          case "birthdayYear":
            return "birthDateYear";
          case "countryName":
            return "addressCountry";
          case "creditCardExpirationDate":
            return View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE;
          case "creditCardExpirationDay":
            return View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DAY;
          case "creditCardExpirationMonth":
            return View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_MONTH;
          case "creditCardExpirationYear":
            return View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR;
          case "creditCardNumber":
            return View.AUTOFILL_HINT_CREDIT_CARD_NUMBER;
          case "creditCardSecurityCode":
            return View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE;
          case "email":
            return View.AUTOFILL_HINT_EMAIL_ADDRESS;
          case "familyName":
            return "personFamilyName";
          case "fullStreetAddress":
            return "streetAddress";
          case "gender":
            return "gender";
          case "givenName":
            return "personGivenName";
          case "middleInitial":
            return "personMiddleInitial";
          case "middleName":
            return "personMiddleName";
          case "name":
            return "personName";
          case "namePrefix":
            return "personNamePrefix";
          case "nameSuffix":
            return "personNameSuffix";
          case "newPassword":
            return "newPassword";
          case "newUsername":
            return "newUsername";
          case "oneTimeCode":
            return "smsOTPCode";
          case "password":
            return View.AUTOFILL_HINT_PASSWORD;
          case "postalAddress":
            return View.AUTOFILL_HINT_POSTAL_ADDRESS;
          case "postalAddressExtended":
            return "extendedAddress";
          case "postalAddressExtendedPostalCode":
            return "extendedPostalCode";
          case "postalCode":
            return View.AUTOFILL_HINT_POSTAL_CODE;
          case "telephoneNumber":
            return "phoneNumber";
          case "telephoneNumberCountryCode":
            return "phoneCountryCode";
          case "telephoneNumberDevice":
            return "phoneNumberDevice";
          case "telephoneNumberNational":
            return "phoneNational";
          case "username":
            return View.AUTOFILL_HINT_USERNAME;
          default:
            return hint;
        }
      }

      public Autofill(
          @NonNull String uniqueIdentifier,
          @NonNull String[] hints,
          @NonNull TextEditState editingState) {
        this.uniqueIdentifier = uniqueIdentifier;
        this.hints = hints;
        this.editState = editingState;
      }
    }

    public final boolean obscureText;
    public final boolean autocorrect;
    public final boolean enableSuggestions;
    @NonNull public final TextCapitalization textCapitalization;
    @NonNull public final InputType inputType;
    @Nullable public final Integer inputAction;
    @Nullable public final String actionLabel;
    @Nullable public final Autofill autofill;
    @Nullable public final Configuration[] fields;

    public Configuration(
        boolean obscureText,
        boolean autocorrect,
        boolean enableSuggestions,
        @NonNull TextCapitalization textCapitalization,
        @NonNull InputType inputType,
        @Nullable Integer inputAction,
        @Nullable String actionLabel,
        @Nullable Autofill autofill,
        @Nullable Configuration[] fields) {
      this.obscureText = obscureText;
      this.autocorrect = autocorrect;
      this.enableSuggestions = enableSuggestions;
      this.textCapitalization = textCapitalization;
      this.inputType = inputType;
      this.inputAction = inputAction;
      this.actionLabel = actionLabel;
      this.autofill = autofill;
      this.fields = fields;
    }
  }

  /**
   * A text input type.
   *
   * <p>If the {@link #type} is {@link TextInputType#NUMBER}, this {@code InputType} also reports
   * whether that number {@link #isSigned} and {@link #isDecimal}.
   */
  public static class InputType {
    @NonNull
    public static InputType fromJson(@NonNull JSONObject json)
        throws JSONException, NoSuchFieldException {
      return new InputType(
          TextInputType.fromValue(json.getString("name")),
          json.optBoolean("signed", false),
          json.optBoolean("decimal", false));
    }

    @NonNull public final TextInputType type;
    public final boolean isSigned;
    public final boolean isDecimal;

    public InputType(@NonNull TextInputType type, boolean isSigned, boolean isDecimal) {
      this.type = type;
      this.isSigned = isSigned;
      this.isDecimal = isDecimal;
    }
  }

  /** Types of text input. */
  public enum TextInputType {
    TEXT("TextInputType.text"),
    DATETIME("TextInputType.datetime"),
    NAME("TextInputType.name"),
    POSTAL_ADDRESS("TextInputType.address"),
    NUMBER("TextInputType.number"),
    PHONE("TextInputType.phone"),
    MULTILINE("TextInputType.multiline"),
    EMAIL_ADDRESS("TextInputType.emailAddress"),
    URL("TextInputType.url"),
    VISIBLE_PASSWORD("TextInputType.visiblePassword");

    static TextInputType fromValue(@NonNull String encodedName) throws NoSuchFieldException {
      for (TextInputType textInputType : TextInputType.values()) {
        if (textInputType.encodedName.equals(encodedName)) {
          return textInputType;
        }
      }
      throw new NoSuchFieldException("No such TextInputType: " + encodedName);
    }

    @NonNull private final String encodedName;

    TextInputType(@NonNull String encodedName) {
      this.encodedName = encodedName;
    }
  }

  /** Text capitalization schemes. */
  public enum TextCapitalization {
    CHARACTERS("TextCapitalization.characters"),
    WORDS("TextCapitalization.words"),
    SENTENCES("TextCapitalization.sentences"),
    NONE("TextCapitalization.none");

    static TextCapitalization fromValue(@NonNull String encodedName) throws NoSuchFieldException {
      for (TextCapitalization textCapitalization : TextCapitalization.values()) {
        if (textCapitalization.encodedName.equals(encodedName)) {
          return textCapitalization;
        }
      }
      throw new NoSuchFieldException("No such TextCapitalization: " + encodedName);
    }

    @NonNull private final String encodedName;

    TextCapitalization(@NonNull String encodedName) {
      this.encodedName = encodedName;
    }
  }

  /** State of an on-going text editing session. */
  public static class TextEditState {
    public static TextEditState fromJson(@NonNull JSONObject textEditState) throws JSONException {
      return new TextEditState(
          textEditState.getString("text"),
          textEditState.getInt("selectionBase"),
          textEditState.getInt("selectionExtent"));
    }

    @NonNull public final String text;
    public final int selectionStart;
    public final int selectionEnd;

    public TextEditState(@NonNull String text, int selectionStart, int selectionEnd) {
      this.text = text;
      this.selectionStart = selectionStart;
      this.selectionEnd = selectionEnd;
    }
  }
}
