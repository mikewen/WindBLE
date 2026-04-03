package com.windble.app.ui;

import android.app.Application;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import com.windble.app.ble.BleConstants;
import com.windble.app.ble.BleService;
import com.windble.app.gps.BleGpsManager;
import com.windble.app.gps.GpsManager;
import com.windble.app.gps.NmeaParser;
import com.windble.app.model.WindData;
import com.windble.app.server.WindHttpServer;
import com.windble.app.logger.TripLogger;
import com.windble.app.alert.WindShiftAlert;

import java.util.ArrayDeque;
import java.util.Deque;

public class WindViewModel extends AndroidViewModel {
    private static final String TAG = "WindViewModel";

    public static final int UNIT_KNOTS = 0;
    public static final int UNIT_MS    = 1;
    public static final int UNIT_KMH   = 2;

    public static final int GPS_SOURCE_PHONE = 0;
    public static final int GPS_SOURCE_BLE   = 1;

    private final MutableLiveData<WindData> mWindData = new MutableLiveData<>();
    private final MutableLiveData<Integer> mConnectionState = new MutableLiveData<>(BleConstants.STATE_DISCONNECTED);
    private final MutableLiveData<String> mConnectedDevice = new MutableLiveData<>("");
    private final MutableLiveData<Float> mCompassHeading = new MutableLiveData<>(0f);
    private final MutableLiveData<Boolean> mGpsAvailable = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> mBleGpsState = new MutableLiveData<>(BluetoothProfile.STATE_DISCONNECTED);
    private final MutableLiveData<String> mBleGpsDevice = new MutableLiveData<>("");
    private final MutableLiveData<String> mLastNmea = new MutableLiveData<>("");
    private final MutableLiveData<Integer> mGpsSource = new MutableLiveData<>(GPS_SOURCE_PHONE);
    private final MutableLiveData<Boolean> mLogging = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> mLogRowCount = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> mNightMode = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> mShiftAlertOn = new MutableLiveData<>(false);
    private final MutableLiveData<ShiftEvent> mShiftEvent = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mKeepScreenOn = new MutableLiveData<>(true);

    private BleService mBleService;
    private boolean mBound = false;

    private final GpsManager mGpsManager;
    private final BleGpsManager mBleGpsManager;
    private final WindHttpServer mHttpServer;
    private final TripLogger mTripLogger;
    private final WindShiftAlert mShiftAlert;
    private final SensorManager mSensorManager;
    private final Sensor mAccelerometer, mMagnetometer;
    private float[] mGravity, mGeomagnetic;
    private float mHeading = 0;
    private float mSog = 0, mCog = 0;

    private final SharedPreferences mPrefs;
    private int mSpeedUnit = UNIT_KNOTS;
    private float mAwaOffset = 0, mAwsMultiplier = 1.0f;

    // Rolling averages
    private final Deque<AwsSample> mHistory1m = new ArrayDeque<>();
    private final Deque<AwsSample> mHistory1h = new ArrayDeque<>();
    private double mSum1m = 0, mSum1h = 0;

    public static class AwsSample { long ts; float val; AwsSample(long t, float v){ts=t; val=v;} }
    public static class ShiftEvent {
        public float shiftDeg, newTwd; public WindShiftAlert.ShiftType type;
        ShiftEvent(float d, WindShiftAlert.ShiftType t, float n) { shiftDeg=d; type=t; newTwd=n; }
    }

    public WindViewModel(@NonNull Application app) {
        super(app);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(app);

        // Trip logger setup
        mTripLogger = new TripLogger(app);
        mTripLogger.setListener(new TripLogger.LoggerListener() {
            @Override public void onLoggingStarted(String filename) {
                mLogging.postValue(true);
                mLogRowCount.postValue(0);
            }
            @Override public void onLoggingStopped(String filename, int rowCount) {
                mLogging.postValue(false);
                mLogRowCount.postValue(rowCount);
            }
            @Override public void onError(String message) {
                Log.e(TAG, "Logger error: " + message);
            }
        });

        // HTTP server for remote viewing (starts only when enabled by user)
        mHttpServer = new WindHttpServer(app);

        // Wind shift detection logic
        mShiftAlert = new WindShiftAlert(app);
        mShiftAlert.setListener((shiftDeg, type, newTwd) -> {
            mShiftEvent.postValue(new ShiftEvent(shiftDeg, type, newTwd));
            if (mHttpServer.isRunning()) mHttpServer.pushShiftEvent(shiftDeg, type.name(), newTwd);
        });

        // Phone GPS manager
        mGpsManager = new GpsManager(app);
        mGpsManager.setListener(new GpsManager.GpsListener() {
            @Override
            public void onLocationUpdate(float sog, float cog, double lat, double lon, float acc) {
                if (mGpsSource.getValue() == null || mGpsSource.getValue() == GPS_SOURCE_PHONE) {
                    mSog = sog; mCog = cog;
                    mGpsAvailable.postValue(true);
                }
            }
            @Override public void onGpsStatusChange(boolean available) {
                if (mGpsSource.getValue() == null || mGpsSource.getValue() == GPS_SOURCE_PHONE)
                    mGpsAvailable.postValue(available);
            }
        });
        mGpsManager.start();

        // BLE GPS manager (external NMEA sources)
        mBleGpsManager = new BleGpsManager(app);
        mBleGpsManager.setListener(new BleGpsManager.BleGpsListener() {
            @Override public void onGpsData(NmeaParser.RmcData data) {
                if (data.valid) {
                    mSog = data.sogMs; mCog = data.cogDeg;
                    mGpsAvailable.postValue(true);
                    mGpsSource.postValue(GPS_SOURCE_BLE);
                }
            }
            @Override public void onConnectionStateChange(int state) {
                mBleGpsState.postValue(state);
                if (state == BluetoothProfile.STATE_DISCONNECTED)
                    mGpsSource.postValue(GPS_SOURCE_PHONE);
            }
            @Override public void onNmeaRaw(String sentence) {
                mLastNmea.postValue(sentence);
            }
        });

        // Compass (magnetometer + accelerometer)
        mSensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mMagnetometer  = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mAccelerometer != null)
                mSensorManager.registerListener(mSensorListener, mAccelerometer, SensorManager.SENSOR_DELAY_UI);
            if (mMagnetometer != null)
                mSensorManager.registerListener(mSensorListener, mMagnetometer, SensorManager.SENSOR_DELAY_UI);
        } else {
            mAccelerometer = null;
            mMagnetometer  = null;
        }

        // Load persisted preferences
        loadPreferences();
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    /**
     * Reads calibration and unit preferences from SharedPreferences.
     */
    private void loadPreferences() {
        String unit = mPrefs.getString("speed_unit", "knots");
        if ("ms".equals(unit))       mSpeedUnit = UNIT_MS;
        else if ("kmh".equals(unit)) mSpeedUnit = UNIT_KMH;
        else                         mSpeedUnit = UNIT_KNOTS;

        float threshold = mPrefs.getFloat("shift_threshold", 10f);
        mShiftAlert.setThreshold(threshold);

        mKeepScreenOn.setValue(mPrefs.getBoolean("screen_on", true));

        try {
            mAwaOffset = Float.parseFloat(mPrefs.getString("awa_offset", "0.0"));
            mAwsMultiplier = Float.parseFloat(mPrefs.getString("aws_multiplier", "1.0"));
        } catch (NumberFormatException e) {
            mAwaOffset = 0f;
            mAwsMultiplier = 1.0f;
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = (prefs, key) -> {
        if ("speed_unit".equals(key) || "awa_offset".equals(key) || "aws_multiplier".equals(key) || "shift_threshold".equals(key) || "screen_on".equals(key)) {
            loadPreferences();
        }
    };

    // ---- Wind BLE service ----

    public void bindBleService() {
        Intent intent = new Intent(getApplication(), BleService.class);
        getApplication().bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    public void unbindBleService() {
        if (mBound) {
            try { getApplication().unregisterReceiver(mGattReceiver); } catch (Exception ignored) {}
            getApplication().unbindService(mServiceConnection);
            mBound = false;
        }
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBleService = ((BleService.LocalBinder) service).getService();
            mBound = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(BleConstants.ACTION_GATT_CONNECTED);
            filter.addAction(BleConstants.ACTION_GATT_DISCONNECTED);
            filter.addAction(BleConstants.ACTION_DATA_AVAILABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getApplication().registerReceiver(mGattReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getApplication().registerReceiver(mGattReceiver, filter);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { mBound = false; }
    };

    private final BroadcastReceiver mGattReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BleConstants.ACTION_GATT_CONNECTED.equals(action)) {
                mConnectionState.postValue(BleConstants.STATE_CONNECTED);
                String addr = intent.getStringExtra(BleConstants.EXTRA_DEVICE_ADDRESS);
                if (addr != null) mConnectedDevice.postValue(addr);
            } else if (BleConstants.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnectionState.postValue(BleConstants.STATE_DISCONNECTED);
            } else if (BleConstants.ACTION_DATA_AVAILABLE.equals(action)) {
                processPacket(intent.getByteArrayExtra(BleConstants.EXTRA_DATA));
            }
        }
    };

    /**
     * Entry point for raw BLE packets. Parses, calibrates, and calculates true wind.
     */
    private void processPacket(byte[] raw) {
        WindData wd = WindData.fromBytes(raw, mAwaOffset, mAwsMultiplier);
        if (wd == null) return;
        wd.sog = mSog; wd.cog = mCog; wd.heading = mHeading;
        wd.hasGps = Boolean.TRUE.equals(mGpsAvailable.getValue());
        
        // Update rolling averages
        updateAverages(wd);

        // Main calculation: combine apparent wind with boat speed to get true wind
        wd.calculateTrueWind(wd.awa, wd.aws, mSog, mHeading);
        wd.valid = true;

        // Feed data to secondary systems
        mShiftAlert.onTwd(wd.twd);
        mTripLogger.log(wd);
        if (mTripLogger.isLogging()) mLogRowCount.postValue(mTripLogger.getRowCount());

        // Update HTTP observers and UI
        if (mHttpServer.isRunning()) mHttpServer.pushWindData(wd, formatSpeedUnit());
        mWindData.postValue(wd);
    }

    private synchronized void updateAverages(WindData wd) {
        long now = System.currentTimeMillis();
        
        // 1-minute rolling average and max
        mHistory1m.addLast(new AwsSample(now, wd.aws));
        mSum1m += wd.aws;
        while (!mHistory1m.isEmpty() && now - mHistory1m.peekFirst().ts > 60_000) {
            mSum1m -= mHistory1m.removeFirst().val;
        }
        wd.awsAvg1m = (float) (mSum1m / mHistory1m.size());
        
        float max1m = 0;
        for (AwsSample s : mHistory1m) if (s.val > max1m) max1m = s.val;
        wd.awsMax1m = max1m;

        // 1-hour rolling average and max
        mHistory1h.addLast(new AwsSample(now, wd.aws));
        mSum1h += wd.aws;
        while (!mHistory1h.isEmpty() && now - mHistory1h.peekFirst().ts > 3600_000) {
            mSum1h -= mHistory1h.removeFirst().val;
        }
        wd.awsAvg1h = (float) (mSum1h / mHistory1h.size());
        
        float max1h = 0;
        for (AwsSample s : mHistory1h) if (s.val > max1h) max1h = s.val;
        wd.awsMax1h = max1h;
    }

    public void connectDevice(String address) {
        if (mBleService != null) {
            if (mBleService.connect(address)) {
                mConnectionState.postValue(BleConstants.STATE_CONNECTING);
            } else {
                mConnectionState.postValue(BleConstants.STATE_DISCONNECTED);
            }
        }
    }
    public void disconnectDevice() { if (mBleService != null) mBleService.disconnect(); }

    // ---- BLE GPS ----

    public void connectBleGps(String address, String name) {
        mBleGpsManager.connect(address);
        mBleGpsDevice.postValue(name != null ? name : address);
    }
    public void disconnectBleGps() { mBleGpsManager.disconnect(); }

    // ---- Getters ----

    public LiveData<WindData> getWindData() { return mWindData; }
    public LiveData<Integer> getConnectionState() { return mConnectionState; }
    public LiveData<Float> getCompassHeading() { return mCompassHeading; }
    public LiveData<Boolean> getGpsAvailable() { return mGpsAvailable; }
    public LiveData<Integer> getBleGpsState() { return mBleGpsState; }
    public LiveData<String> getLastNmea() { return mLastNmea; }
    public LiveData<Boolean> getLogging() { return mLogging; }
    public LiveData<Integer> getLogRowCount() { return mLogRowCount; }
    public LiveData<Boolean> getNightMode() { return mNightMode; }
    public LiveData<Boolean> getShiftAlertOn() { return mShiftAlertOn; }
    public LiveData<ShiftEvent> getShiftEvent() { return mShiftEvent; }
    public LiveData<Boolean> getKeepScreenOn() { return mKeepScreenOn; }

    public void setNightMode(boolean night) { mNightMode.setValue(night); }
    public void setShiftAlertEnabled(boolean on) { mShiftAlertOn.setValue(on); }
    public void setShiftThreshold(float deg) {
        mShiftAlert.setThreshold(deg);
        mPrefs.edit().putFloat("shift_threshold", deg).apply();
    }
    public void resetShiftBaseline() { mShiftAlert.resetBaseline(); }

    public void setKeepScreenOn(boolean on) {
        mKeepScreenOn.setValue(on);
        mPrefs.edit().putBoolean("screen_on", on).apply();
    }

    public void startLogging() { mTripLogger.start(); }
    public void stopLogging() { mTripLogger.stop(); }
    public boolean isLogging() { return Boolean.TRUE.equals(mLogging.getValue()); }

    public void startWebServer() { mHttpServer.start(); }
    public void stopWebServer() { mHttpServer.stop(); }
    public boolean isWebServerRunning() { return mHttpServer.isRunning(); }
    public WindHttpServer getHttpServer() { return mHttpServer; }

    public WindShiftAlert getShiftAlert() { return mShiftAlert; }

    public void setSpeedUnit(int unit) { mSpeedUnit = unit; }
    public String formatSpeed(float ms) {
        if (mSpeedUnit == UNIT_MS) return String.format("%.1f m/s", ms);
        if (mSpeedUnit == UNIT_KMH) return String.format("%.1f km/h", ms * 3.6f);
        return String.format("%.1f kt", ms * 1.94384f);
    }
    private String formatSpeedUnit() {
        if (mSpeedUnit == UNIT_MS) return "m/s";
        if (mSpeedUnit == UNIT_KMH) return "km/h";
        return "kt";
    }

    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) mGravity = event.values;
            if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) mGeomagnetic = event.values;
            if (mGravity != null && mGeomagnetic != null) {
                float[] R = new float[9], I = new float[9];
                if (SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic)) {
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    mHeading = (float) Math.toDegrees(orientation[0]);
                    if (mHeading < 0) mHeading += 360;
                    mCompassHeading.postValue(mHeading);
                }
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
}
