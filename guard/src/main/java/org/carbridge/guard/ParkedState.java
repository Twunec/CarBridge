package org.carbridge.guard;

/**
 * Decides from location fixes whether the car is parked. Pure Java so it can be unit tested on
 * the JVM. It fails closed: without a recent fix it never reports {@link Status#PARKED}.
 */
public final class ParkedState {

    public enum Status {
        /** No recent location fix yet, so parking can't be confirmed. */
        CHECKING,
        /** Moving, or not yet still for long enough. */
        MOVING,
        /** Still for at least {@link #PARKED_HOLD_MS}. */
        PARKED,
    }

    /** Above this speed (about 10 km/h) the car counts as moving at once. */
    public static final float MOVING_MPS = 2.8f;
    /** Below this speed (about 4 km/h; GPS drifts a little when still) the car may be parked. */
    public static final float STILL_MPS = 1.1f;
    /** How long the car must stay below {@link #STILL_MPS} before video unlocks. */
    public static final long PARKED_HOLD_MS = 5_000;
    /** Fixes older than this no longer count, so a lost signal locks video again. */
    public static final long FIX_MAX_AGE_MS = 10_000;

    private long lastFixAt = -1;
    private long stillSince = -1;
    private boolean parked = false;

    /** Records a fix at [nowMs] (elapsed realtime) with the measured or derived speed. */
    public void onFix(long nowMs, float speedMps) {
        lastFixAt = nowMs;
        if (Float.isNaN(speedMps) || speedMps >= MOVING_MPS) {
            stillSince = -1;
            parked = false;
        } else if (speedMps < STILL_MPS) {
            if (stillSince < 0) stillSince = nowMs;
            if (nowMs - stillSince >= PARKED_HOLD_MS) parked = true;
        } else {
            // Between the thresholds: keep the current state, but a stop has to start over
            if (!parked) stillSince = -1;
        }
    }

    public Status status(long nowMs) {
        if (lastFixAt < 0 || nowMs - lastFixAt > FIX_MAX_AGE_MS) return Status.CHECKING;
        if (parked) return Status.PARKED;
        // A pending stop can complete without a new fix only once the hold has passed
        if (stillSince >= 0 && nowMs - stillSince >= PARKED_HOLD_MS) return Status.PARKED;
        return Status.MOVING;
    }

    /** Clears everything, e.g. when monitoring stops; the next start must confirm again. */
    public void reset() {
        lastFixAt = -1;
        stillSince = -1;
        parked = false;
    }

    /** Speed between two fixes when a fix doesn't carry one; NaN when it can't be told. */
    public static float derivedSpeed(double metres, long millis) {
        if (millis <= 0) return Float.NaN;
        return (float) (metres / (millis / 1000.0));
    }
}
