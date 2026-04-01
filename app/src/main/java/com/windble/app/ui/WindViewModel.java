package com.windble.app.ui;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.windble.app.ble.BleConstants;
import com.windble.app.ble.BleService;
import com.windble.app.gps.GpsManager;
import com.windble.app.model.WindData;

public class WindViewModel extends AndroidViewModel {
    private static final String TAG = "WindViewModel";

    // Units
    public static final int UNIT_KNOTS = 0;
    public static final int UNIT_MS = 1;
    public static final int UNIT_KMH = 2;

    private final MutableLiveData<WindData> mWindData = new MutableLiveData<>();
    private final MutableLiveData<Integer> mConnectionState = new MutableLiveData<>(BleConstants.STATE_DISCONNECTED);
    private final MutableLiveData<String> mConnectedDevice = new MutableLiveData<>("");
    private final MutableLiveData<Float> mCompassHeading = new MutableLiveData<>(0f);
    private final MutableLiveData<Boolean> mGpsAvailable = new MutableLiveData<>(false);

    private int mSpeedUnit = UNIT_KNOTS;

    private BleService mBleService;
    private boolean mBound = false;

    private GpsManager mGpsManager;
    private float mSog = 0f;
    private float mCog = 0f;

    // Compass
    private SensorManager mSensorManager;
    private Sensor mAccelerometer, mMagnetometer;
    private float[] mGravity, mGeomagnetic;
    private float mHeading = 0f;

    public WindViewModel(@NonNull Application application) {
        super(application);

        mGpsManager = new GpsManager(application);
        mGpsManager.setListener(new GpsManager.GpsListener() {
            @Override
            public void onLocationUpdate(float sog, float cog, double lat, double lon, float accuracy) {
                mSog = sog;
                mCog = cog;
                mGpsAvailable.postValue(true);
            }
            @Override
            public void onGpsStatusChange(boolean available) {
                mGpsAvailable.postValue(available);
            }
        });
        mGpsManager.start();

        mSensorManager = (SensorManager) application.getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mAccelerometer != null) mSensorManager.registerListener(mSensorListener, mAccelerometer, SensorManager.SENSOR_DELAY_UI);
            if (mMagnetometer != null) mSensorManager.registerListener(mSensorListener, mMagnetometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    // ---- BLE Service binding ----

    public void bindBleService() {
        Intent intent = new Intent(getApplication(), BleService.class);
        getApplication().bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    public void unbindBleService() {
        if (mBound) {
            getApplication().unregisterReceiver(mGattReceiver);
            getApplication().unbindService(mServiceConnection);
            mBound = false;
        }
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BleService.LocalBinder binder = (BleService.LocalBinder) service;
            mBleService = binder.getService();
            mBound = true;

            IntentFilter filter = new IntentFilter();
            filter.addAction(BleConstants.ACTION_GATT_CONNECTED);
            filter.addAction(BleConstants.ACTION_GATT_DISCONNECTED);
            filter.addAction(BleConstants.ACTION_DATA_AVAILABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getApplication().registerReceiver(mGattReceiver, filter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                getApplication().registerReceiver(mGattReceiver, filter);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
        }
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
                byte[] raw = intent.getByteArrayExtra(BleConstants.EXTRA_DATA);
                processPacket(raw);
            }
        }
    };

    private void processPacket(byte[] raw) {
        WindData wd = WindData.fromBytes(raw);
        if (wd == null) return;

        // Normalize AWA: device likely sends 0-36000 (0-360 degrees)
        // Some devices use 0-180 port/starboard, handle both
        float awa = wd.awa;
        if (awa > 360) awa = awa % 360;

        wd.sog = mSog;
        wd.cog = mCog;
        wd.heading = mHeading;
        wd.hasGps = Boolean.TRUE.equals(mGpsAvailable.getValue());

        // Calculate true wind using boat speed and heading
        wd.calculateTrueWind(awa, wd.aws, mSog, mHeading);
        wd.valid = true;

        mWindData.postValue(wd);
    }

    public void connectDevice(String address) {
        if (mBleService != null) {
            mBleService.connect(address);
            mConnectionState.postValue(BleConstants.STATE_CONNECTING);
        }
    }

    public void disconnectDevice() {
        if (mBleService != null) {
            mBleService.disconnect();
        }
    }

    // ---- Compass ----

    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                mGravity = event.values.clone();
            } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                mGeomagnetic = event.values.clone();
            }
            if (mGravity != null && mGeomagnetic != null) {
                float[] R = new float[9];
                float[] I = new float[9];
                if (SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic)) {
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    float azimuth = (float) Math.toDegrees(orientation[0]);
                    azimuth = (azimuth + 360) % 360;
                    mHeading = azimuth;
                    mCompassHeading.postValue(azimuth);
                }
            }
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    // ---- Getters ----

    public LiveData<WindData> getWindData() { return mWindData; }
    public LiveData<Integer> getConnectionState() { return mConnectionState; }
    public LiveData<String> getConnectedDevice() { return mConnectedDevice; }
    public LiveData<Float> getCompassHeading() { return mCompassHeading; }
    public LiveData<Boolean> getGpsAvailable() { return mGpsAvailable; }

    public int getSpeedUnit() { return mSpeedUnit; }
    public void setSpeedUnit(int unit) { mSpeedUnit = unit; }

    public String formatSpeed(float ms) {
        switch (mSpeedUnit) {
            case UNIT_MS:  return String.format("%.1f m/s", ms);
            case UNIT_KMH: return String.format("%.1f km/h", WindData.msToKmh(ms));
            default:       return String.format("%.1f kt", WindData.msToKnots(ms));
        }
    }

    @Override
    protected void onCleared() {
        unbindBleService();
        mGpsManager.stop();
        if (mSensorManager != null) mSensorManager.unregisterListener(mSensorListener);
        super.onCleared();
    }
}
