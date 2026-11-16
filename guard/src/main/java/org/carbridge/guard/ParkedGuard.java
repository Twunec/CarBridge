package org.carbridge.guard;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs inside a CarBridge car copy. While an activity of the copy is shown on a car display, it
 * covers the activity and pauses playback unless GPS shows the car is parked. Activities on the
 * phone's own screen are left alone. Uses only framework APIs so it can't clash with the app's
 * own libraries.
 */
final class ParkedGuard implements Application.ActivityLifecycleCallbacks, LocationListener {
    private static final String TAG = "CarBridgeGuard";
    private static final String OVERLAY_TAG = "org.carbridge.guard.overlay";
    private static final long TICK_MS = 1_000;

    private static ParkedGuard instance;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ParkedState state = new ParkedState();
    private final List<Activity> onCar = new ArrayList<>();
    private Location previous;
    private boolean listening;
    private ParkedState.Status shown;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            if (!onCar.isEmpty()) main.postDelayed(this, TICK_MS);
        }
    };

    private ParkedGuard(Application app) {
        this.app = app;
    }

    static synchronized void install(Application app) {
        if (instance != null) return;
        instance = new ParkedGuard(app);
        app.registerActivityLifecycleCallbacks(instance);
        Log.i(TAG, "Parked-only lock active");
    }

    // ---- Activity tracking ----

    @Override
    public void onActivityResumed(Activity activity) {
        if (!isOnCarDisplay(activity)) return;
        if (!onCar.contains(activity)) onCar.add(activity);
        startListening();
        shown = null; // Force the overlay onto the newly resumed activity
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // Paused activities on the car can still be visible (e.g. behind a dialog): keep them covered
    }

    @Override
    public void onActivityStopped(Activity activity) {
        forget(activity);
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        forget(activity);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    private void forget(Activity activity) {
        if (!onCar.remove(activity)) return;
        removeOverlay(activity);
        if (onCar.isEmpty()) {
            main.removeCallbacks(tick);
            stopListening();
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isOnCarDisplay(Activity activity) {
        Display display;
        if (Build.VERSION.SDK_INT >= 30) {
            display = activity.getDisplay();
        } else {
            display = activity.getWindowManager().getDefaultDisplay();
        }
        // Android Auto runs car apps on their own virtual display; the phone screen is display 0
        return display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY;
    }

    // ---- Location ----

    private boolean hasLocationPermission() {
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void startListening() {
        if (listening || !hasLocationPermission()) return;
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, TICK_MS, 0f, this, Looper.getMainLooper());
            listening = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "GPS unavailable", e);
        }
    }

    private void stopListening() {
        if (!listening) return;
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) lm.removeUpdates(this);
        listening = false;
        previous = null;
        state.reset();
    }

    @Override
    public void onLocationChanged(Location location) {
        long now = SystemClock.elapsedRealtime();
        float speed;
        if (location.hasSpeed()) {
            speed = location.getSpeed();
        } else if (previous != null) {
            long ms = (location.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos()) / 1_000_000;
            speed = ParkedState.derivedSpeed(location.distanceTo(previous), ms);
        } else {
            speed = Float.NaN;
        }
        previous = location;
        state.onFix(now, speed);
        update();
    }

    @Override
    public void onProviderDisabled(String provider) {
        state.reset();
        update();
    }

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    @SuppressWarnings("deprecation") // Still abstract before Android 11
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    // ---- Overlay ----

    private enum Lock { NONE, NO_PERMISSION, LOCATION_OFF, CHECKING, MOVING }

    private Lock currentLock() {
        if (!hasLocationPermission()) return Lock.NO_PERMISSION;
        if (!listening) startListening();
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return Lock.LOCATION_OFF;
        switch (state.status(SystemClock.elapsedRealtime())) {
            case PARKED: return Lock.NONE;
            case MOVING: return Lock.MOVING;
            default: return Lock.CHECKING;
        }
    }

    private Lock lastLock;

    private void update() {
        Lock lock = currentLock();
        boolean wasLocked = lastLock != null && lastLock != Lock.NONE;
        if (lock != Lock.NONE && !wasLocked) pausePlayback();
        if (lock != lastLock) Log.i(TAG, "Car screen lock: " + lock);
        lastLock = lock;
        for (Activity activity : new ArrayList<>(onCar)) {
            if (lock == Lock.NONE) removeOverlay(activity);
            else showOverlay(activity, lock);
        }
    }

    private void pausePlayback() {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || !audio.isMusicActive()) return;
        // Goes to the active media session, which is this app's player while it's on the car screen
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    private void showOverlay(Activity activity, Lock lock) {
        Window window = activity.getWindow();
        if (window == null) return;
        ViewGroup decor = (ViewGroup) window.getDecorView();
        View existing = decor.findViewWithTag(OVERLAY_TAG);
        TextView body;
        if (existing == null) {
            existing = buildOverlay(activity);
            decor.addView(existing, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (decor.indexOfChild(existing) != decor.getChildCount() - 1) {
            existing.bringToFront();
        }
        body = existing.findViewWithTag(OVERLAY_TAG + ".body");
        if (body != null) body.setText(message(lock));
    }

    private static void removeOverlay(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        ViewGroup decor = (ViewGroup) window.getDecorView();
        View existing = decor.findViewWithTag(OVERLAY_TAG);
        if (existing != null) decor.removeView(existing);
    }

    private View buildOverlay(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        root.setTag(OVERLAY_TAG);
        root.setBackgroundColor(Color.rgb(15, 21, 18));
        // Swallows every touch so nothing behind it can be used
        root.setClickable(true);
        root.setFocusable(true);
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                return true;
            }
        });

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int pad = dp(activity, 32);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setText("Available when parked");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        TextView body = new TextView(activity);
        body.setTag(OVERLAY_TAG + ".body");
        body.setTextColor(Color.rgb(191, 201, 194));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(activity, 12), 0, 0);

        column.addView(title);
        column.addView(body);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return root;
    }

    private static String message(Lock lock) {
        switch (lock) {
            case NO_PERMISSION:
                return "Allow location for this car copy on your phone (CarBridge → Checks) so it can tell when you're parked.";
            case LOCATION_OFF:
                return "Turn on location on your phone so this app can tell when you're parked.";
            case CHECKING:
                return "Checking that you're parked… This needs a GPS signal.";
            default:
                return "Video is paused while the car is moving.";
        }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
