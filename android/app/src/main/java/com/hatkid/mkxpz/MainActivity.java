package com.hatkid.mkxpz;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup.LayoutParams;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.RelativeLayout;
import org.libsdl.app.SDLControllerManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.storage.StorageManager;
import android.os.storage.OnObbStateChangeListener;
import android.util.Log;
import android.util.DisplayMetrics;
import android.system.ErrnoException;
import android.system.Os;
import java.util.Locale;
import java.io.File;

import org.json.JSONArray;
import org.json.JSONObject;

import org.libsdl.app.SDLActivity;
import com.hatkid.mkxpz.gamepad.Gamepad;
import com.hatkid.mkxpz.gamepad.GamepadConfig;

public class MainActivity extends SDLActivity
{
    // This activity inherits from SDLActivity activity.
    // Put your Java-side stuff here.

    private static final String TAG = "mkxp-z[Activity]";
    private static final String GAME_PATH_DEFAULT = Environment.getExternalStorageDirectory() + "/mkxp-z";
    private static String GAME_PATH = GAME_PATH_DEFAULT;
    private static String OBB_MAIN_FILENAME;
    private static boolean DEBUG = false;

    protected boolean mStarted = false;

    protected static Handler mMainHandler;
    protected static StorageManager mStorageManager;
    protected static Vibrator mVibrator;

    protected static TextView tvFps;

    /** Selects one of the two native mkxp/Ruby pairs bundled in this APK. */
    @Override
    protected String[] getLibraries()
    {
        String capability = getIntent().getStringExtra("dev.enginehost.runtime.CAPABILITY_ID");
        boolean ruby19 = capability != null && capability.endsWith("-ruby19");
        return new String[] {
            "SDL2",
            "SDL2_image",
            "SDL2_ttf",
            "SDL2_sound",
            "openal",
            ruby19 ? "ruby19" : "ruby31",
            ruby19 ? "mkxp-z-ruby19" : "mkxp-z-ruby31"
        };
    }

    // In-screen gamepad
    private final Gamepad mGamepad = new Gamepad();
    private boolean mGamepadInvisible = false;
    // The person hid the touch controls themselves; a stray touch must not bring them back.
    private boolean mGamepadUserHidden = false;
    private Button mToggleControls;

    /**
     * One row of the person's controller map, from Enginehost's controller
     * settings (resolved per engine, sent as CONTROLLER_BINDINGS): an RGSS
     * action and the pad control it is bound to, a key or one half or the
     * whole of an axis. An action bound to nothing has no row. Nothing here
     * decides which control does what; that is configured in Enginehost.
     */
    private static final class PadBinding
    {
        final String action;
        final boolean key;
        final int code;      // key code, or axis
        final int direction; // for an axis: -1 or 1 for one half, 0 for all of it

        PadBinding(String action, boolean key, int code, int direction)
        {
            this.action = action;
            this.key = key;
            this.code = code;
            this.direction = direction;
        }
    }

    private final List<PadBinding> mPadBindings = new ArrayList<>();
    /** The RGSS key each action is holding down now, if any. */
    private final Map<String, Integer> mActionKeys = new HashMap<>();
    /** The actions holding each RGSS key down: the key is up again when the last lets go. */
    private final Map<Integer, Set<String>> mKeyHolders = new HashMap<>();
    /** Where the pad's hat rests now, so only a change becomes a d-pad press or release. */
    private int mHatX, mHatY;

    private void runSDLThread()
    {
        if (!mStarted) {
            Log.i(TAG, "Game path: " + GAME_PATH);
        }

        mStarted = true;

        // Run (resume) native SDL thread
        if (mHasMultiWindow) {
            resumeNativeThread();
        }
    }

    OnObbStateChangeListener obbListener = new OnObbStateChangeListener()
    {
        @Override
        public void onObbStateChange(String path, int state)
        {
            super.onObbStateChange(path, state);

            Log.v(TAG, "OBB state of " + path + " changed to " + state);

            switch (state)
            {
                case OnObbStateChangeListener.MOUNTED:
                    String obbPath = mStorageManager.getMountedObbPath(path);
                    Log.v(TAG, "OBB " + path + " is mounted to " + obbPath);
                    GAME_PATH = obbPath;
                    break;

                case OnObbStateChangeListener.UNMOUNTED:
                    Log.v(TAG, "OBB " + path + " is unmounted");
                    GAME_PATH = GAME_PATH_DEFAULT;
                    break;

                default:
                    Log.e(TAG, "Failed to mount OBB " + path + ": Got state " + state);
                    break;
            }

            runSDLThread();
        }
    };


    /**
     * Enginehost spells a handful of options the same way for every engine so
     * that a user learns them once. mkxp-z reads its own historic names, so
     * translate the shared spellings here rather than teaching the config
     * editor an engine-specific vocabulary. A value the game already gives
     * under mkxp-z's own name wins, and anything unrecognised is passed
     * through untouched so a user can still set any mkxp.json key by hand.
     */
    /**
     * The preload shims mkxp-z ships, on by default. RPG Maker games written
     * for Windows call Win32API for fullscreen toggles, key state and window
     * placement; on Android there is no user32 to dlopen, so without
     * win32_wrap.rb the first such script kills the game with
     * "library user32 not found" (MGQ Paradox's Fullscreen++ did exactly that).
     * A game's own enginehost.json may name its own preloadScript list and
     * then wins outright.
     */
    private static String withDefaultPreloads(String optionsJson, String bundleRoot)
    {
        if (bundleRoot == null) return optionsJson;
        try {
            JSONObject options = new JSONObject(optionsJson);
            if (!options.has("preloadScript")) {
                JSONArray preloads = new JSONArray();
                for (String script : new String[] {"ruby_classic_wrap.rb", "mkxp_wrap.rb", "win32_wrap.rb", "enginehost_win32_extras.rb"}) {
                    preloads.put(new File(bundleRoot, "scripts/" + script).getAbsolutePath());
                }
                options.put("preloadScript", preloads);
            }
            return options.toString();
        } catch (Exception error) {
            Log.w(TAG, "Could not add default preload scripts: " + error);
            return optionsJson;
        }
    }

    private static String withSharedOptionNames(String optionsJson)
    {
        try {
            JSONObject options = new JSONObject(optionsJson);
            if (options.has("fpsLimit") && !options.has("fixedFramerate")) {
                options.put("fixedFramerate", options.get("fpsLimit"));
            }
            options.remove("fpsLimit");
            return options.toString();
        } catch (Exception error) {
            // Not an object we can rewrite; mkxp-z reports its own parse failure.
            Log.w(TAG, "Could not normalise enginehost options: " + error);
            return optionsJson;
        }
    }

    /**
     * Makes this APK's resource table reachable from the activity's own
     * Resources, where the gamepad layout, its drawables and sdp dimens are
     * looked up.
     *
     * Enginehost attaches the bundle's resources from its component factory,
     * to the application's Resources. Android has already built this
     * activity's base Resources by then (performLaunchActivity creates the
     * context before it asks the factory for the activity), so the loader
     * never reaches it and inflating R.layout.gamepad_layout threw
     * Resources.NotFoundException and took the game down. On API 30+ the
     * application's loaders are copied across; earlier, the resource APKs
     * the host names in the intent are added to our AssetManager directly.
     * Standalone, the table is our own and this finds it at once.
     */
    private void attachBundleResources()
    {
        if (hasBundleResources()) return;
        Resources own = getResources();
        if (Build.VERSION.SDK_INT >= 30) {
            List<ResourcesLoader> loaders = getApplicationContext().getResources().getLoaders();
            if (!loaders.isEmpty()) own.addLoaders(loaders.toArray(new ResourcesLoader[0]));
        } else {
            ArrayList<String> apks = getIntent().getStringArrayListExtra("dev.enginehost.runtime.RESOURCE_APKS");
            if (apks != null) {
                for (String apk : apks) {
                    try {
                        AssetManager assets = own.getAssets();
                        assets.getClass().getMethod("addAssetPath", String.class).invoke(assets, apk);
                    } catch (Exception error) {
                        Log.w(TAG, "Could not add resource APK " + apk + ": " + error);
                    }
                }
                own.updateConfiguration(own.getConfiguration(), own.getDisplayMetrics());
            }
        }
        if (hasBundleResources()) {
            Log.i(TAG, "Attached the bundle's resources to the activity");
        } else {
            Log.e(TAG, "The bundle's resources are not reachable; the touch controls are left out");
        }
    }

    private boolean hasBundleResources()
    {
        try {
            getResources().getResourceTypeName(R.layout.gamepad_layout);
            return true;
        } catch (Resources.NotFoundException missing) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        attachBundleResources();
        String capability = getIntent().getStringExtra("dev.enginehost.runtime.CAPABILITY_ID");
        boolean useRuby19 = capability != null && capability.endsWith("-ruby19");
        String bundleRoot = getIntent().getStringExtra("dev.enginehost.runtime.BUNDLE_ROOT");
        if (bundleRoot != null) {
            String rubyRuntime = useRuby19 ? "ruby19" : "ruby31";
            String abi = Build.SUPPORTED_ABIS.length == 0 ? "" : Build.SUPPORTED_ABIS[0];
            String common = new File(bundleRoot, "runtime/" + rubyRuntime + "/common").getAbsolutePath();
            String nativeRuntime = new File(bundleRoot, "runtime/" + rubyRuntime + "/" + abi).getAbsolutePath();
            try {
                Os.setenv("RUBYLIB", common + File.pathSeparator + nativeRuntime, true);
                Os.setenv("ENGINEHOST_BUNDLE_ROOT", new File(bundleRoot).getAbsolutePath(), true);
            } catch (ErrnoException error) {
                throw new IllegalStateException("Unable to configure bundled Ruby runtime", error);
            }
        }
        // Standalone, the manifest asks for landscape. Under enginehost the
        // component is the host's proxy with no orientation of its own, so a
        // phone held any way up got a portrait window and RPG Maker's 640x480
        // frame sat unscaled in one corner. Every RGSS game is a landscape
        // game; say so before the window exists.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        String engineHostPath = getIntent().getStringExtra("dev.enginehost.runtime.PATH");
        if (engineHostPath != null && new File(engineHostPath).isDirectory()) {
            GAME_PATH = new File(engineHostPath).getAbsolutePath();
            // The native side never sees GAME_PATH: main() resolves the game
            // root from getenv("SRCDIR") and otherwise falls back to
            // SDL_GetBasePath(), which is NULL on Android -- and
            // std::string(nullptr) took the whole process down in
            // getDefaultGameRoot(). Hand it the directory the way it
            // already knows how to read.
            try {
                Os.setenv("SRCDIR", GAME_PATH, true);
            } catch (ErrnoException error) {
                throw new IllegalStateException("Unable to pass the game directory", error);
            }
        }
        loadControllerBindings(getIntent().getStringExtra("dev.enginehost.runtime.CONTROLLER_BINDINGS"));
        String engineHostOptions = getIntent().getStringExtra("dev.enginehost.runtime.OPTIONS");
        String mergedOptions = withDefaultPreloads(
            engineHostOptions == null ? "{}" : withSharedOptionNames(engineHostOptions), bundleRoot);
        try {
            Os.setenv("ENGINEHOST_OPTIONS", mergedOptions, true);
        } catch (ErrnoException error) {
            throw new IllegalStateException("Unable to pass enginehost options", error);
        }
        super.onCreate(savedInstanceState);

        mMainHandler = new Handler(getMainLooper());

        mStorageManager = (StorageManager) getSystemService(STORAGE_SERVICE);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Get main OBB filepath
        final String obbPrefix = "main"; // "main", "patch"
        final int obbVersion = 1;
        OBB_MAIN_FILENAME = getObbDir() + "/" + obbPrefix + "." + obbVersion + "." + getPackageName() + ".obb";

        // Get Debug flag
        try {
            ActivityInfo actInfo = getPackageManager().getActivityInfo(this.getComponentName(), PackageManager.GET_META_DATA);
            // metaData is null when this activity is not the one declared in the
            // manifest: under enginehost the component is its BundledActivityProxy,
            // which carries no <meta-data> of ours. Absent means "not debug".
            DEBUG = actInfo.metaData != null && actInfo.metaData.getBoolean("mkxp_debug");
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to set debug flag: " + e);
            e.printStackTrace();
        }

        // Setup in-screen gamepad. A pad already connected when the game
        // starts (the common case on a console like the Retroid, where the
        // built-in pad is present from boot) hides the overlay from the
        // first frame instead of waiting for a button press.
        mGamepadInvisible = (isAndroidTV() || isChromebook() || hasConnectedGamepad());
        GamepadConfig gpadConfig = new GamepadConfig();
        mGamepad.init(gpadConfig, mGamepadInvisible);
        mGamepad.setOnKeyDownListener(SDLActivity::onNativeKeyDown);
        mGamepad.setOnKeyUpListener(SDLActivity::onNativeKeyUp);

        if (mLayout != null && hasBundleResources()) {
            mGamepad.attachTo(this, mLayout);
            attachControlsToggle();
        }
        registerGamepadHotplugListener();

        // Setup FPS textview
        tvFps = new TextView(this);
        tvFps.setTextSize((8 * ((float) getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT)));
        tvFps.setTextColor(Color.argb(96, 255, 255, 255));
        tvFps.setVisibility(View.GONE);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.setMargins(16, 16, 0, 0);
        tvFps.setLayoutParams(params);

        mLayout.addView(tvFps);
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        if (!mStarted) {
            // Check for main OBB file
            if (new File(OBB_MAIN_FILENAME).exists()) {
                Log.v(TAG, "Main OBB file found, starting with main OBB mount");

                // Try to mount main OBB file
                mStorageManager.mountObb(OBB_MAIN_FILENAME, null, obbListener);
            } else {
                Log.v(TAG, "Main OBB file not found, starting without main OBB mount");

                // Run from default game directory
                runSDLThread();
            }
        } else {
            // onStart: Resume SDL thread
            runSDLThread();
        }
    }

    @Override
    protected void onDestroy()
    {
        unregisterGamepadHotplugListener();
        super.onDestroy();

        // HACK: Exiting the JVM (process) since Ruby does not likes when we
        // trying to re-initialize Ruby VM in mkxp-z (JNI native library)
        // that leads to segmentation fault, even we have cleanup the Ruby VM.
        System.exit(0);
    }

    /** True if any currently connected input device looks like a gamepad. */
    private static boolean isGamepadDevice(InputDevice device)
    {
        if (device == null || device.isVirtual()) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static boolean hasConnectedGamepad()
    {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (isGamepadDevice(device)) {
                Log.i(TAG, "Gamepad present: " + device.getName() + " (id " + id + ")");
                found = true;
            }
        }
        if (!found) Log.i(TAG, "No gamepad connected");
        return found;
    }

    private InputManager.InputDeviceListener mGamepadHotplugListener;

    /**
     * The touch overlay's own listener (dispatchKeyEvent/onGenericMotionEvent)
     * only reacts once a pad is actually pressed. A pad attached before the
     * game starts, or plugged in while the person never touches it, left the
     * overlay drawn over a console with a real pad already in hand. Watch
     * device add/remove directly so presence alone is enough.
     */
    private void registerGamepadHotplugListener()
    {
        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) return;
        mGamepadHotplugListener = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId)
            {
                if (isGamepadDevice(InputDevice.getDevice(deviceId)) && !mGamepadInvisible) {
                    mGamepad.hideView();
                    mGamepadInvisible = true;
                    updateControlsToggleLabel();
                }
            }

            @Override
            public void onInputDeviceRemoved(int deviceId)
            {
                // Bring the overlay back once no gamepad remains, unless the
                // person hid it themselves; the device that just vanished is
                // already gone from getDeviceIds() by this callback.
                if (!mGamepadUserHidden && mGamepadInvisible && !hasConnectedGamepad()
                        && !isAndroidTV() && !isChromebook()) {
                    mGamepad.showView();
                    mGamepadInvisible = false;
                    updateControlsToggleLabel();
                }
            }

            @Override
            public void onInputDeviceChanged(int deviceId) { }
        };
        inputManager.registerInputDeviceListener(mGamepadHotplugListener, mMainHandler);
    }

    private void unregisterGamepadHotplugListener()
    {
        if (mGamepadHotplugListener == null) return;
        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(mGamepadHotplugListener);
        mGamepadHotplugListener = null;
    }

    /**
     * A small always-available switch for the touch controls. They also hide
     * by themselves when a hardware pad or keyboard is used and come back on
     * a touch, but a person playing with a pad on a touch screen needs a way
     * to keep them gone; that is what "user hidden" protects.
     */
    private void attachControlsToggle()
    {
        mToggleControls = new Button(this);
        mToggleControls.setAllCaps(false);
        mToggleControls.setTextSize(11);
        mToggleControls.setAlpha(0.55f);
        mToggleControls.setPadding(24, 4, 24, 4);
        mToggleControls.setOnClickListener(v -> {
            if (mGamepadInvisible) {
                mGamepadUserHidden = false;
                mGamepad.showView();
                mGamepadInvisible = false;
            } else {
                mGamepadUserHidden = true;
                mGamepad.hideView();
                mGamepadInvisible = true;
            }
            updateControlsToggleLabel();
        });
        updateControlsToggleLabel();
        if (mLayout instanceof RelativeLayout) {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            params.addRule(RelativeLayout.CENTER_HORIZONTAL);
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            mLayout.addView(mToggleControls, params);
        } else {
            mLayout.addView(mToggleControls, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
    }

    private void loadControllerBindings(String json)
    {
        mPadBindings.clear();
        if (json == null) return;
        try {
            JSONObject map = new JSONObject(json);
            java.util.Iterator<String> actions = map.keys();
            while (actions.hasNext()) {
                String action = actions.next();
                JSONObject binding = map.getJSONObject(action);
                String type = binding.getString("type");
                if ("key".equals(type)) {
                    mPadBindings.add(new PadBinding(action, true, binding.getInt("code"), 0));
                } else if ("axis".equals(type)) {
                    mPadBindings.add(new PadBinding(action, false, binding.getInt("axis"),
                        binding.optInt("direction", 0)));
                }
                // "none": the person unbound it, so nothing on the pad reaches it.
            }
        } catch (Exception error) {
            Log.w(TAG, "Ignoring an unreadable controller map: " + error);
        }
        int keys = 0;
        for (PadBinding binding : mPadBindings) if (binding.key) keys++;
        Log.i(TAG, "Controller map: " + keys + " buttons, " + (mPadBindings.size() - keys) + " axes");
    }

    /**
     * What each of Enginehost's RGSS actions is, as the keyboard key mkxp-z
     * reads for that RGSS input. The action ids are RGSS's own `Input`
     * symbols (src/input/input.h), as Enginehost's controller settings name
     * them; the keys are mkxp-z's own keyboard defaults for those symbols:
     * defaultKbBindings in src/input/keybindings.cpp for the rebindable
     * inputs, and staticKbBindings in src/input/input.cpp for Shift, Ctrl,
     * Alt and F5 to F9, which RGSS reads off fixed keys. These keys are the
     * ones common to RGSS1, 2 and 3, so the table holds for XP, VX and VX Ace.
     */
    private static int keyForAction(String action)
    {
        switch (action) {
            case "rgss_up": return KeyEvent.KEYCODE_DPAD_UP;
            case "rgss_down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "rgss_left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "rgss_right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "rgss_c": return KeyEvent.KEYCODE_ENTER;         // Return -> C
            case "rgss_b": return KeyEvent.KEYCODE_ESCAPE;        // Escape -> B
            case "rgss_b_second": return KeyEvent.KEYCODE_NUMPAD_0; // KP 0 -> B, RGSS's second B key
            case "rgss_a": return KeyEvent.KEYCODE_SHIFT_LEFT;    // LShift -> A
            case "rgss_x": return KeyEvent.KEYCODE_A;             // A -> X
            case "rgss_y": return KeyEvent.KEYCODE_S;             // S -> Y
            case "rgss_z": return KeyEvent.KEYCODE_D;             // D -> Z
            case "rgss_l": return KeyEvent.KEYCODE_Q;             // Q -> L
            case "rgss_r": return KeyEvent.KEYCODE_W;             // W -> R
            // RShift reaches Shift alone; LShift is A as well, as on a keyboard.
            case "rgss_shift": return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case "rgss_ctrl": return KeyEvent.KEYCODE_CTRL_LEFT;
            case "rgss_alt": return KeyEvent.KEYCODE_ALT_LEFT;
            case "rgss_f5": return KeyEvent.KEYCODE_F5;
            case "rgss_f6": return KeyEvent.KEYCODE_F6;
            case "rgss_f7": return KeyEvent.KEYCODE_F7;
            case "rgss_f8": return KeyEvent.KEYCODE_F8;
            case "rgss_f9": return KeyEvent.KEYCODE_F9;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    /**
     * A stick action's two directions, negative half first; null for an
     * action that is not a stick. RGSS has no analogue input: a stick is
     * its four directions, as mkxp-z's own pad table makes the left stick
     * past half travel (addAxisBinding in src/input/keybindings.cpp).
     */
    private static int[] directionsForStick(String action)
    {
        switch (action) {
            case "left_x": case "right_x":
                return new int[] { KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT };
            case "left_y": case "right_y":
                return new int[] { KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN };
            default:
                return null;
        }
    }

    /**
     * An action's value now, as Enginehost itself measures it: 1 or 0 for a
     * key, the signed axis for a whole axis, and 0 to 1 for one half.
     */
    private static float bindingValue(PadBinding binding, float raw)
    {
        if (binding.direction < 0) return Math.max(-raw, 0f);
        if (binding.direction > 0) return Math.max(raw, 0f);
        return raw;
    }

    /** Holds down the RGSS key [action] means at [value], releasing what it held before. */
    private void applyAction(String action, float value)
    {
        int wanted;
        int[] directions = directionsForStick(action);
        if (directions != null) {
            wanted = value < -0.5f ? directions[0] : value > 0.5f ? directions[1] : KeyEvent.KEYCODE_UNKNOWN;
        } else {
            wanted = value > 0.5f ? keyForAction(action) : KeyEvent.KEYCODE_UNKNOWN;
        }
        Integer held = mActionKeys.get(action);
        if (held != null && held == wanted) return;
        if (held != null) {
            mActionKeys.remove(action);
            Set<String> holders = mKeyHolders.get(held);
            if (holders != null && holders.remove(action) && holders.isEmpty())
                SDLActivity.onNativeKeyUp(held);
        }
        if (wanted == KeyEvent.KEYCODE_UNKNOWN) return;
        mActionKeys.put(action, wanted);
        Set<String> holders = mKeyHolders.get(wanted);
        if (holders == null) mKeyHolders.put(wanted, holders = new HashSet<>());
        if (holders.isEmpty()) SDLActivity.onNativeKeyDown(wanted);
        holders.add(action);
    }

    /** A pad button, down or up, through every action bound to it. */
    private void applyPadKey(int keyCode, boolean down)
    {
        for (PadBinding binding : mPadBindings)
            if (binding.key && binding.code == keyCode) applyAction(binding.action, down ? 1f : 0f);
    }

    /**
     * The hat moved. Android turns a hat nobody consumed into d-pad key
     * events; this activity consumes the pad's motion so that SDL's own
     * joystick path does not drive RGSS behind the map's back, so it does
     * the same turning itself. A pad that also sends d-pad keys for its hat
     * presses the same actions twice over, and a held key is not pressed again.
     */
    private void applyHat(MotionEvent evt)
    {
        int x = Math.round(evt.getAxisValue(MotionEvent.AXIS_HAT_X));
        int y = Math.round(evt.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (x != mHatX) {
            if (mHatX != 0) applyPadKey(mHatX < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, false);
            if (x != 0) applyPadKey(x < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT, true);
            mHatX = x;
        }
        if (y != mHatY) {
            if (mHatY != 0) applyPadKey(mHatY < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, false);
            if (y != 0) applyPadKey(y < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, true);
            mHatY = y;
        }
    }

    private void updateControlsToggleLabel()
    {
        if (mToggleControls != null)
            mToggleControls.setText(mGamepadInvisible ? "Show controls" : "Hide controls");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent evt)
    {
        // A hardware pad is translated through the person's Enginehost
        // controller map: pad button -> action -> the keyboard key RGSS reads
        // for that action. Feeding raw pad codes to SDL as keyboard keys made
        // the D-pad work by accident (those codes double as arrows) and left
        // A/B/X/Y meaning nothing.
        if (evt.getDevice() != null && SDLControllerManager.isDeviceSDLJoystick(evt.getDeviceId())) {
            if (!mGamepadInvisible) {
                mGamepad.hideView();
                mGamepadInvisible = true;
                updateControlsToggleLabel();
            }
            if (evt.getAction() == KeyEvent.ACTION_DOWN && evt.getRepeatCount() == 0) {
                // The one line that says a real pad reached the engine: which
                // button arrived, what Enginehost calls it, and the RGSS key it
                // became. Without it a dead pad and an unbound pad look alike.
                StringBuilder became = new StringBuilder();
                for (PadBinding binding : mPadBindings) {
                    if (!binding.key || binding.code != evt.getKeyCode()) continue;
                    int[] directions = directionsForStick(binding.action);
                    int key = directions != null ? directions[1] : keyForAction(binding.action);
                    became.append(became.length() == 0 ? "" : ", ").append(binding.action)
                        .append(" -> RGSS key ").append(KeyEvent.keyCodeToString(key));
                }
                Log.i(TAG, "Pad key " + KeyEvent.keyCodeToString(evt.getKeyCode()) + " -> action "
                    + (became.length() == 0 ? "(unbound)" : became.toString()));
            }
            // A pad button the person has not bound does nothing.
            if (evt.getAction() == KeyEvent.ACTION_DOWN && evt.getRepeatCount() == 0)
                applyPadKey(evt.getKeyCode(), true);
            else if (evt.getAction() == KeyEvent.ACTION_UP)
                applyPadKey(evt.getKeyCode(), false);
            return true;
        }
        if (
            evt.getKeyCode() != KeyEvent.KEYCODE_BACK &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN &&
            evt.getKeyCode() != KeyEvent.KEYCODE_VOLUME_MUTE && 
            evt.getKeyCode() != KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            // Hide gamepad view on key events when visible
            if (!mGamepadInvisible) {
                mGamepad.hideView();
                mGamepadInvisible = true;
                updateControlsToggleLabel();
            }
        }

        if (mGamepad.processGamepadEvent(evt))
            return true;

        return super.dispatchKeyEvent(evt);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent evt)
    {
        // Show gamepad view on touch when hidden, unless the person hid it.
        if (mGamepadInvisible && !mGamepadUserHidden) {
            mGamepad.showView();
            mGamepadInvisible = false;
            updateControlsToggleLabel();
        }

        return super.dispatchTouchEvent(evt);
    }

    /**
     * The pad's sticks, triggers and hat, through the same map as its
     * buttons. This has to come before the views: SDL's surface takes every
     * joystick motion for its own game-controller path, and mkxp-z's pad
     * table would then move RGSS with the left stick and the hat whatever
     * the person had bound, unbound or remapped.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent evt)
    {
        if (evt.getDevice() != null && SDLControllerManager.isDeviceSDLJoystick(evt.getDeviceId())
                && (evt.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            for (PadBinding binding : mPadBindings) {
                if (binding.key) continue;
                float raw = evt.getAxisValue(binding.code);
                applyAction(binding.action, bindingValue(binding, raw));
            }
            applyHat(evt);
            return true;
        }
        return super.dispatchGenericMotionEvent(evt);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent evt)
    {
        if (mGamepad.processDPadEvent(evt))
            return true;

        return super.onGenericMotionEvent(evt);
    }

    /**
     * This method is for arguments for launching native mkxp-z.
     * 
     * @return arguments for the mkxp-z
     */
    @Override
    protected String[] getArguments()
    {
        String[] args;

        if (DEBUG) {
            // Arguments in Debug mode
            args = new String[] { "debug" };
        } else {
            // Arguments in normal mode
            args = new String[] {};
        }

        return args;
    }

    /**
     * This static method is used in native mkxp-z. (see eventthread.cpp)
     * This method updates text with given FPS count to FPS TextView in Activity.
     */
    @SuppressLint("SetTextI18n")
    @SuppressWarnings("unused")
    private static void updateFPSText(int num)
    {
        mMainHandler.post(() -> tvFps.setText(num + " FPS"));
    }

    /**
     * This static method is used in native mkxp-z. (see eventthread.cpp)
     * This method sets the visibility of FPS TextView in Activity.
     */
    @SuppressWarnings("unused")
    private static void setFPSVisibility(boolean visible)
    {
        mMainHandler.post(() -> {
            if (visible)
                tvFps.setVisibility(View.VISIBLE);
            else
                tvFps.setVisibility(View.INVISIBLE);
        });
    }

    /**
     * This static method is used in native mkxp-z. (see systemImpl.cpp)
     * This method returns a string of current device locale tag. (e.g. "en_US")
     * 
     * @return string of locale tag
     */
    @SuppressWarnings("unused")
    private static String getSystemLanguage()
    {
        return Locale.getDefault().toString();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating that the device has a vibrator or not.
     * 
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean hasVibrator()
    {
        return mVibrator.hasVibrator();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method makes device vibrating with given milliseconds duration.
     * 
     * @param duration milliseconds duration of vibration
     */
    @SuppressWarnings("unused")
    private static void vibrate(int duration)
    {
        if (duration >= 10000) {
            duration = 10000;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mVibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.EFFECT_HEAVY_CLICK));
        } else {
            mVibrator.vibrate(duration);
        }
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method turns off the current device vibration.
     */
    @SuppressWarnings("unused")
    private static void vibrateStop()
    {
        mVibrator.cancel();
    }

    /**
     * This static method is used in native mkxp-z. (see android-binding.cpp)
     * This method returns a boolean indicating the app is in multi window mode or not.
     * (Multi-window mode supports from Android 7.0 Nougat (API 24) and higher.)
     * 
     * @param activity current MainActivity instance
     * @return boolean
     */
    @SuppressWarnings("unused")
    private static boolean inMultiWindow(Activity activity)
    {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode();
    }
}
