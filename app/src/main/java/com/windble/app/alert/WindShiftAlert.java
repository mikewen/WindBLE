package com.windble.app.alert;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;

import com.windble.app.R;

/**
 * Detects True Wind Direction (TWD) shifts beyond a configurable threshold
 * and alerts the user via vibration + optional notification.
 *
 * A "lift" is when TWD shifts to give you a better angle to windward.
 * A "header" is the opposite. Both are reported as shifts with sign.
 */
public class WindShiftAlert {

    public enum ShiftType { LIFT, HEADER }

    public interface ShiftListener {
        /**
         * @param shiftDeg  signed shift in degrees (positive = clockwise = lift on starboard)
         * @param type      LIFT or HEADER
         * @param newTwd    current TWD after shift
         */
        void onShift(float shiftDeg, ShiftType type, float newTwd);
    }

    private static final String CHANNEL_ID = "wind_shift";
    private static final int    NOTIF_ID   = 42;
    private static final long   MIN_INTERVAL_MS = 10_000; // suppress repeats within 10 s

    private final Context mContext;
    private ShiftListener mListener;

    // Settings
    private float mThresholdDeg = 10f;   // degrees before alerting
    private boolean mVibrateEnabled = true;
    private boolean mNotifyEnabled  = false;

    // State
    private float   mBaseTwd       = Float.NaN; // reference TWD when baseline was set
    private float   mSmoothedTwd   = Float.NaN; // exponential moving average
    private long    mLastAlertMs   = 0;
    private boolean mEnabled       = false;

    private static final float ALPHA = 0.15f; // EMA smoothing (lower = smoother)

    public WindShiftAlert(Context context) {
        mContext = context;
        createNotificationChannel();
    }

    public void setListener(ShiftListener listener) { mListener = listener; }
    public void setThreshold(float degrees) { mThresholdDeg = degrees; }
    public void setVibrateEnabled(boolean v) { mVibrateEnabled = v; }
    public void setNotifyEnabled(boolean n) { mNotifyEnabled = n; }
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (enabled) resetBaseline();
    }
    public boolean isEnabled() { return mEnabled; }
    public float getThreshold() { return mThresholdDeg; }

    /** Feed each new TWD reading. Call from WindViewModel on every packet. */
    public void onTwd(float twd) {
        if (!mEnabled) return;

        // Initialise smoothed value on first sample
        if (Float.isNaN(mSmoothedTwd)) {
            mSmoothedTwd = twd;
            mBaseTwd = twd;
            return;
        }

        // EMA on circular angle — unwrap difference to avoid 359°→1° glitch
        float diff = angleDiff(twd, mSmoothedTwd);
        mSmoothedTwd = mSmoothedTwd + ALPHA * diff;
        mSmoothedTwd = (mSmoothedTwd + 360) % 360;

        // Check shift from baseline
        if (Float.isNaN(mBaseTwd)) { mBaseTwd = mSmoothedTwd; return; }

        float shift = angleDiff(mSmoothedTwd, mBaseTwd);

        if (Math.abs(shift) >= mThresholdDeg) {
            long now = System.currentTimeMillis();
            if (now - mLastAlertMs > MIN_INTERVAL_MS) {
                mLastAlertMs = now;
                // Positive shift = wind moved clockwise = lift on starboard tack
                ShiftType type = (shift > 0) ? ShiftType.LIFT : ShiftType.HEADER;
                fireAlert(shift, type, mSmoothedTwd);
                // Advance baseline so we detect the next shift from here
                mBaseTwd = mSmoothedTwd;
            }
        }
    }

    /** Reset the baseline TWD reference to the current smoothed value. */
    public void resetBaseline() {
        mBaseTwd = mSmoothedTwd;
        mLastAlertMs = 0;
    }

    /** Returns most recent smoothed TWD, or NaN if no data yet. */
    public float getSmoothedTwd() { return mSmoothedTwd; }

    private void fireAlert(float shiftDeg, ShiftType type, float newTwd) {
        if (mListener != null) mListener.onShift(shiftDeg, type, newTwd);
        if (mVibrateEnabled) vibrate(type);
        if (mNotifyEnabled)  notify(shiftDeg, type, newTwd);
    }

    private void vibrate(ShiftType type) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager)
                        mContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    android.os.Vibrator v = vm.getDefaultVibrator();
                    // Lift: two short pulses. Header: one long pulse.
                    long[] pattern = (type == ShiftType.LIFT)
                            ? new long[]{0, 80, 80, 80}
                            : new long[]{0, 300};
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                }
            } else {
                Vibrator v = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    long[] pattern = (type == ShiftType.LIFT)
                            ? new long[]{0, 80, 80, 80}
                            : new long[]{0, 300};
                    v.vibrate(pattern, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void notify(float shiftDeg, ShiftType type, float newTwd) {
        String title = String.format("Wind %s  %+.0f°",
                type == ShiftType.LIFT ? "Lift ↑" : "Header ↓", shiftDeg);
        String text = String.format("TWD now %.0f°", newTwd);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_gps_on)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Wind Shift Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alerts when true wind direction shifts");
            NotificationManager nm = (NotificationManager)
                    mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /**
     * Signed angular difference b - a, normalised to [-180, +180].
     * Positive = b is clockwise from a.
     */
    private static float angleDiff(float b, float a) {
        float d = ((b - a) % 360 + 360) % 360;
        if (d > 180) d -= 360;
        return d;
    }
}
