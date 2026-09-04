package com.hatkid.mkxpz;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup.LayoutParams;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.RelativeLayout;
import org.libsdl.app.SDLControllerManager;
import java.util.HashMap;
import java.util.Map;
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
     * The person's controller map, from Enginehost's controller settings
     * (resolved per engine, sent as CONTROLLER_BINDINGS). Pad key code ->
     * action id, and stick axis -> action id. Nothing here decides which
     * button does what; that is configured in Enginehost.
     */
    private final Map<Integer, String> mPadKeyActions = new HashMap<>();
    private final Map<Integer, String> mPadAxisActions = new HashMap<>();
    /** Axis actions currently held as a direction key, so a centred stick releases it. */
    private final Map<String, Integer> mAxisHeldKeys = new HashMap<>();

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

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
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

        // Setup in-screen gamepad
        mGamepadInvisible = (isAndroidTV() || isChromebook());
        GamepadConfig gpadConfig = new GamepadConfig();
        mGamepad.init(gpadConfig, mGamepadInvisible);
        mGamepad.setOnKeyDownListener(SDLActivity::onNativeKeyDown);
        mGamepad.setOnKeyUpListener(SDLActivity::onNativeKeyUp);

        if (mLayout != null) {
            mGamepad.attachTo(this, mLayout);
            attachControlsToggle();
        }

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
        super.onDestroy();

        // HACK: Exiting the JVM (process) since Ruby does not likes when we
        // trying to re-initialize Ruby VM in mkxp-z (JNI native library)
        // that leads to segmentation fault, even we have cleanup the Ruby VM.
        System.exit(0);
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
        mPadKeyActions.clear();
        mPadAxisActions.clear();
        if (json == null) return;
        try {
            JSONObject map = new JSONObject(json);
            java.util.Iterator<String> actions = map.keys();
            while (actions.hasNext()) {
                String action = actions.next();
                JSONObject binding = map.getJSONObject(action);
                if ("key".equals(binding.getString("type"))) {
                    mPadKeyActions.put(binding.getInt("code"), action);
                } else if ("axis".equals(binding.getString("type"))) {
                    mPadAxisActions.put(binding.getInt("axis"), action);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Ignoring an unreadable controller map: " + error);
        }
    }

    /**
     * What each Enginehost action means to an RGSS game, expressed as the
     * keyboard key RPG Maker itself reads for it (the same keys the touch
     * overlay sends). This table is the plugin's; which pad button triggers
     * an action is the person's, in Enginehost's controller settings.
     */
    private static int keyForAction(String action)
    {
        switch (action) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "confirm": return KeyEvent.KEYCODE_ENTER;      // RGSS C
            case "cancel": return KeyEvent.KEYCODE_ESCAPE;      // RGSS B
            case "menu": return KeyEvent.KEYCODE_ESCAPE;        // RGSS B opens the menu
            case "auto": return KeyEvent.KEYCODE_SHIFT_LEFT;    // RGSS A: dash / auto-run
            case "skip": return KeyEvent.KEYCODE_CTRL_LEFT;     // message skip in scripts that read Ctrl
            case "page_previous": return KeyEvent.KEYCODE_Q;    // RGSS L
            case "page_next": return KeyEvent.KEYCODE_W;        // RGSS R
            case "history": return KeyEvent.KEYCODE_A;          // RGSS X
            case "quick_save": return KeyEvent.KEYCODE_S;       // RGSS Y
            case "quick_load": return KeyEvent.KEYCODE_D;       // RGSS Z
            default: return KeyEvent.KEYCODE_UNKNOWN;
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
            String action = mPadKeyActions.get(evt.getKeyCode());
            int key = action == null ? KeyEvent.KEYCODE_UNKNOWN : keyForAction(action);
            if (key == KeyEvent.KEYCODE_UNKNOWN)
                return true; // a pad button the person has not bound does nothing
            if (evt.getAction() == KeyEvent.ACTION_DOWN && evt.getRepeatCount() == 0)
                SDLActivity.onNativeKeyDown(key);
            else if (evt.getAction() == KeyEvent.ACTION_UP)
                SDLActivity.onNativeKeyUp(key);
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

    @Override
    public boolean onGenericMotionEvent(MotionEvent evt)
    {
        // Sticks go through the same map: an axis bound to a direction action
        // presses that direction's key past the dead zone and releases it at
        // centre. Hats arrive as D-pad key events and take the key path.
        if (evt.getDevice() != null && SDLControllerManager.isDeviceSDLJoystick(evt.getDeviceId())) {
            for (Map.Entry<Integer, String> bound : mPadAxisActions.entrySet()) {
                float value = evt.getAxisValue(bound.getKey());
                String action = bound.getValue();
                int negativeKey, positiveKey;
                if (action.equals("left_x") || action.equals("right_x")) {
                    negativeKey = KeyEvent.KEYCODE_DPAD_LEFT; positiveKey = KeyEvent.KEYCODE_DPAD_RIGHT;
                } else if (action.equals("left_y") || action.equals("right_y")) {
                    negativeKey = KeyEvent.KEYCODE_DPAD_UP; positiveKey = KeyEvent.KEYCODE_DPAD_DOWN;
                } else {
                    continue;
                }
                int wanted = value < -0.5f ? negativeKey : value > 0.5f ? positiveKey : KeyEvent.KEYCODE_UNKNOWN;
                Integer held = mAxisHeldKeys.get(action);
                if (held != null && held != wanted) {
                    SDLActivity.onNativeKeyUp(held);
                    mAxisHeldKeys.remove(action);
                }
                if (wanted != KeyEvent.KEYCODE_UNKNOWN && (held == null || held != wanted)) {
                    SDLActivity.onNativeKeyDown(wanted);
                    mAxisHeldKeys.put(action, wanted);
                }
            }
            return true;
        }
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
