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

import com.windble.app.alert.WindShiftAlert;
import com.windble.app.ble.BleConstants;
import com.windble.app.ble.BleService;
import com.windble.app.gps.BleGpsManager;
import com.windble.app.gps.GpsManager;
import com.windble.app.gps.NmeaParser;
import com.windble.app.logger.TripLogger;
import com.windble.app.model.WindData;

public class WindViewModel extends AndroidViewModel {
    private static final String TAG = "WindViewModel";

    public static final int UNIT_KNOTS = 0;
    public static final int UNIT_MS    = 1;
    public static final int UNIT_KMH   = 2;

    public static final int GPS_SOURCE_PHONE = 0;
    public static final int GPS_SOURCE_BLE   = 1;

    /** Carries a single wind-shift event to observers (one-shot via SingleLiveEvent). */
    public static class ShiftEvent {
        public final float shiftDeg;
        public final WindShiftAlert.ShiftType type;
        public final float newTwd;
        public ShiftEvent(float s, WindShiftAlert.ShiftType t, float twd) {
            shiftDeg = s; type = t; newTwd = twd;
        }
    }

    // --- LiveData ---
    private final MutableLiveData<WindData>    mWindData        = new MutableLiveData<>();
    private final MutableLiveData<Integer>     mConnectionState = new MutableLiveData<>(BleConstants.STATE_DISCONNECTED);
    private final MutableLiveData<String>      mConnectedDevice = new MutableLiveData<>("");
    private final MutableLiveData<Float>       mCompassHeading  = new MutableLiveData<>(0f);
    private final MutableLiveData<Boolean>     mGpsAvailable    = new MutableLiveData<>(false);
    private final MutableLiveData<Integer>     mBleGpsState     = new MutableLiveData<>(BluetoothProfile.STATE_DISCONNECTED);
    private final MutableLiveData<String>      mBleGpsDevice    = new MutableLiveData<>("");
    private final MutableLiveData<String>      mLastNmea        = new MutableLiveData<>("");
    private final MutableLiveData<Integer>     mGpsSource       = new MutableLiveData<>(GPS_SOURCE_PHONE);
    private final MutableLiveData<Boolean>     mLogging         = new MutableLiveData<>(false);
    private final MutableLiveData<Integer>     mLogRowCount     = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean>     mShiftAlertOn    = new MutableLiveData<>(false);
    private final MutableLiveData<ShiftEvent>  mShiftEvent      = new MutableLiveData<>();
    private final MutableLiveData<Boolean>     mNightMode       = new MutableLiveData<>(false);

    private int mSpeedUnit = UNIT_KNOTS;

    // --- Wind sensor BLE ---
    private BleService mBleService;
    private boolean mBound = false;

    // --- GPS ---
    private GpsManager    mGpsManager;
    private BleGpsManager mBleGpsManager;
    private float mSog = 0f;
    private float mCog = 0f;

    // --- Compass ---
    private SensorManager mSensorManager;
    private Sensor mAccelerometer, mMagnetometer;
    private float[] mGravity, mGeomagnetic;
    private float mHeading = 0f;

    // --- Trip logger ---
    private final TripLogger mTripLogger;

    // --- Wind shift alert ---
    private final WindShiftAlert mShiftAlert;

    public WindViewModel(@NonNull Application app) {
        super(app);

        // Trip logger
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

        // Wind shift alert
        mShiftAlert = new WindShiftAlert(app);
        mShiftAlert.setListener((shiftDeg, type, newTwd) -> {
            mShiftEvent.postValue(new ShiftEvent(shiftDeg, type, newTwd));
        });

        // Phone GPS
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

        // BLE GPS
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

        // Compass
        mSensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mMagnetometer  = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mAccelerometer != null)
                mSensorManager.registerListener(mSensorListener, mAccelerometer, SensorManager.SENSOR_DELAY_UI);
            if (mMagnetometer != null)
                mSensorManager.registerListener(mSensorListener, mMagnetometer, SensorManager.SENSOR_DELAY_UI);
        }

        // Restore prefs
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String unit = prefs.getString("speed_unit", "knots");
        if ("ms".equals(unit))       mSpeedUnit = UNIT_MS;
        else if ("kmh".equals(unit)) mSpeedUnit = UNIT_KMH;
        else                         mSpeedUnit = UNIT_KNOTS;

        float threshold = prefs.getFloat("shift_threshold", 10f);
        mShiftAlert.setThreshold(threshold);
    }

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

    private void processPacket(byte[] raw) {
        WindData wd = WindData.fromBytes(raw);
        if (wd == null) return;
        float awa = wd.awa % 360;
        wd.sog = mSog; wd.cog = mCog; wd.heading = mHeading;
        wd.hasGps = Boolean.TRUE.equals(mGpsAvailable.getValue());
        wd.calculateTrueWind(awa, wd.aws, mSog, mHeading);
        wd.valid = true;

        // Feed subsystems
        mShiftAlert.onTwd(wd.twd);
        mTripLogger.log(wd);
        if (mTripLogger.isLogging()) mLogRowCount.postValue(mTripLogger.getRowCount());

        mWindData.postValue(wd);
    }

    public void connectDevice(String address) {
        if (mBleService != null) {
            mBleService.connect(address);
            mConnectionState.postValue(BleConstants.STATE_CONNECTING);
        }
    }
    public void disconnectDevice() { if (mBleService != null) mBleService.disconnect(); }

    // ---- BLE GPS ----

    public void connectBleGps(String address, String name) {
        mBleGpsManager.connect(address);
        mBleGpsDevice.postValue(name != null ? name : address);
        mGpsSource.postValue(GPS_SOURCE_BLE);
    }
    public void disconnectBleGps() {
        mBleGpsManager.disconnect();
        mBleGpsDevice.postValue("");
        mGpsSource.postValue(GPS_SOURCE_PHONE);
    }
    public boolean isBleGpsConnected() {
        return mBleGpsState.getValue() != null
                && mBleGpsState.getValue() == BluetoothProfile.STATE_CONNECTED;
    }

    // ---- Trip logging ----

    public void startLogging()  { mTripLogger.start(); }
    public void stopLogging()   { mTripLogger.stop(); }
    public boolean isLogging()  { return mTripLogger.isLogging(); }
    public TripLogger getTripLogger() { return mTripLogger; }

    // ---- Wind shift alert ----

    public void setShiftAlertEnabled(boolean on) {
        mShiftAlert.setEnabled(on);
        mShiftAlertOn.postValue(on);
    }
    public void setShiftThreshold(float deg) {
        mShiftAlert.setThreshold(deg);
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit().putFloat("shift_threshold", deg).apply();
    }
    public void resetShiftBaseline() { mShiftAlert.resetBaseline(); }
    public WindShiftAlert getShiftAlert() { return mShiftAlert; }

    // ---- Night mode ----

    public void setNightMode(boolean on) { mNightMode.postValue(on); }

    // ---- Compass ----

    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
                mGravity = event.values.clone();
            else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
                mGeomagnetic = event.values.clone();
            if (mGravity != null && mGeomagnetic != null) {
                float[] R = new float[9], I = new float[9];
                if (SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic)) {
                    float[] o = new float[3];
                    SensorManager.getOrientation(R, o);
                    mHeading = ((float) Math.toDegrees(o[0]) + 360) % 360;
                    mCompassHeading.postValue(mHeading);
                }
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    // ---- Unit formatting ----

    public int  getSpeedUnit() { return mSpeedUnit; }
    public void setSpeedUnit(int unit) { mSpeedUnit = unit; }

    public String formatSpeed(float ms) {
        switch (mSpeedUnit) {
            case UNIT_MS:  return String.format("%.1f m/s", ms);
            case UNIT_KMH: return String.format("%.1f km/h", WindData.msToKmh(ms));
            default:       return String.format("%.1f kt", WindData.msToKnots(ms));
        }
    }

    // ---- LiveData getters ----

    public LiveData<WindData>   getWindData()        { return mWindData; }
    public LiveData<Integer>    getConnectionState() { return mConnectionState; }
    public LiveData<String>     getConnectedDevice() { return mConnectedDevice; }
    public LiveData<Float>      getCompassHeading()  { return mCompassHeading; }
    public LiveData<Boolean>    getGpsAvailable()    { return mGpsAvailable; }
    public LiveData<Integer>    getBleGpsState()     { return mBleGpsState; }
    public LiveData<String>     getBleGpsDevice()    { return mBleGpsDevice; }
    public LiveData<String>     getLastNmea()        { return mLastNmea; }
    public LiveData<Integer>    getGpsSource()       { return mGpsSource; }
    public LiveData<Boolean>    getLogging()         { return mLogging; }
    public LiveData<Integer>    getLogRowCount()     { return mLogRowCount; }
    public LiveData<Boolean>    getShiftAlertOn()    { return mShiftAlertOn; }
    public LiveData<ShiftEvent> getShiftEvent()      { return mShiftEvent; }
    public LiveData<Boolean>    getNightMode()       { return mNightMode; }

    @Override
    protected void onCleared() {
        unbindBleService();
        mGpsManager.stop();
        mBleGpsManager.disconnect();
        mTripLogger.stop();
        if (mSensorManager != null) mSensorManager.unregisterListener(mSensorListener);
        super.onCleared();
    }
}
