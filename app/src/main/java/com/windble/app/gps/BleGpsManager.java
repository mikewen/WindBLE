package com.windble.app.gps;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Connects to a BLE GPS device and subscribes to NMEA notifications.
 * Includes stability fixes for Motorola/Samsung devices.
 */
public class BleGpsManager {

    private static final String TAG = "BleGpsManager";

    public static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_TX_CHAR = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 15000;

    public interface BleGpsListener {
        void onGpsData(NmeaParser.RmcData data);
        void onConnectionStateChange(int state);
        void onNmeaRaw(String sentence);
    }

    private final Context mContext;
    private BleGpsListener mListener;
    private BluetoothGatt mGatt;
    private String mDeviceAddress;
    private volatile int mState = BluetoothProfile.STATE_DISCONNECTED;
    private boolean mReconnect = true;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mTimeoutRunnable;

    private final StringBuilder mNmeaBuffer = new StringBuilder();

    public BleGpsManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public void setListener(BleGpsListener listener) {
        mListener = listener;
    }

    public void connect(String address) {
        if (address == null || address.isEmpty()) return;
        mDeviceAddress = address;
        mReconnect = true;
        
        mHandler.removeCallbacksAndMessages(null);
        if (mGatt != null) {
            mGatt.close();
            mGatt = null;
        }

        mState = BluetoothProfile.STATE_CONNECTING;
        notifyState();

        // Motorola fix: tiny delay before connecting
        mHandler.postDelayed(this::doConnect, 200);
    }

    private void doConnect() {
        try {
            BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
            if (adapter == null) return;
            
            BluetoothDevice device = adapter.getRemoteDevice(mDeviceAddress);
            Log.i(TAG, "Connecting to BLE GPS: " + mDeviceAddress);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mGatt = device.connectGatt(mContext, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                mGatt = device.connectGatt(mContext, false, mGattCallback);
            }
            
            if (mGatt == null) {
                handleDisconnect();
            } else {
                startTimeout();
            }
        } catch (Exception e) {
            Log.e(TAG, "Connect failed", e);
            handleDisconnect();
        }
    }

    private void startTimeout() {
        stopTimeout();
        mTimeoutRunnable = () -> {
            if (mState == BluetoothProfile.STATE_CONNECTING) {
                Log.w(TAG, "GPS Connection timeout");
                if (mGatt != null) mGatt.close();
                mGatt = null;
                handleDisconnect();
            }
        };
        mHandler.postDelayed(mTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    private void stopTimeout() {
        if (mTimeoutRunnable != null) {
            mHandler.removeCallbacks(mTimeoutRunnable);
            mTimeoutRunnable = null;
        }
    }

    public void disconnect() {
        mReconnect = false;
        stopTimeout();
        mHandler.removeCallbacksAndMessages(null);
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        mState = BluetoothProfile.STATE_DISCONNECTED;
        notifyState();
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT error status: " + status);
                gatt.close();
                if (gatt == mGatt) mGatt = null;
                handleDisconnect();
                return;
            }

            mState = newState;
            notifyState();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                stopTimeout();
                Log.i(TAG, "BLE GPS connected, discovering services");
                mHandler.post(gatt::discoverServices);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close();
                if (gatt == mGatt) mGatt = null;
                handleDisconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            BluetoothGattCharacteristic chr = findNmeaCharacteristic(gatt);
            if (chr == null) {
                Log.w(TAG, "No NMEA characteristic found");
                return;
            }
            enableNotify(gatt, chr);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic chr) {
            byte[] value = chr.getValue();
            if (value != null) processNmeaChunk(new String(value, StandardCharsets.UTF_8));
        }
    };

    private void handleDisconnect() {
        mState = BluetoothProfile.STATE_DISCONNECTED;
        notifyState();
        if (mReconnect && mDeviceAddress != null) {
            mHandler.postDelayed(this::doConnect, RECONNECT_DELAY_MS);
        }
    }

    private BluetoothGattCharacteristic findNmeaCharacteristic(BluetoothGatt gatt) {
        BluetoothGattService nus = gatt.getService(NUS_SERVICE);
        if (nus != null) {
            BluetoothGattCharacteristic c = nus.getCharacteristic(NUS_TX_CHAR);
            if (c != null && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) return c;
        }
        for (BluetoothGattService svc : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) return c;
            }
        }
        return null;
    }

    private void enableNotify(BluetoothGatt gatt, BluetoothGattCharacteristic chr) {
        gatt.setCharacteristicNotification(chr, true);
        BluetoothGattDescriptor desc = chr.getDescriptor(CCCD);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
        }
    }

    private void processNmeaChunk(String chunk) {
        mNmeaBuffer.append(chunk);
        int idx;
        while ((idx = indexOfLineEnd(mNmeaBuffer)) >= 0) {
            String line = mNmeaBuffer.substring(0, idx).trim();
            int next = idx + 1;
            if (next < mNmeaBuffer.length() && mNmeaBuffer.charAt(next) == '\n') next++;
            mNmeaBuffer.delete(0, next);
            if (!line.isEmpty()) {
                if (mListener != null) mListener.onNmeaRaw(line);
                NmeaParser.RmcData rmc = NmeaParser.tryParseRmc(line);
                if (rmc != null && mListener != null) mListener.onGpsData(rmc);
            }
        }
        if (mNmeaBuffer.length() > 512) mNmeaBuffer.delete(0, mNmeaBuffer.length() - 256);
    }

    private int indexOfLineEnd(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\r' || c == '\n') return i;
        }
        return -1;
    }

    private void notifyState() {
        if (mListener != null) mHandler.post(() -> mListener.onConnectionStateChange(mState));
    }
}
