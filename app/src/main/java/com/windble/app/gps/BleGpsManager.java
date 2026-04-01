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
 *
 * Common BLE GPS services that stream NMEA text:
 *   - Nordic UART Service (NUS):
 *       Service  6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *       TX char  6E400003-B5A3-F393-E0A9-E50E24DCCA9E  (NOTIFY, device→phone)
 *   - u-blox / generic NMEA-over-BLE (same UUIDs as NUS in practice)
 *
 * If the device uses a proprietary service we perform a full service scan
 * and attach to the first NOTIFY characteristic we find, then treat its
 * value as raw NMEA bytes.
 */
public class BleGpsManager {

    private static final String TAG = "BleGpsManager";

    // Nordic UART Service (most BLE GPS modules)
    public static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_TX_CHAR = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    // Standard Client Characteristic Config Descriptor
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int RECONNECT_DELAY_MS = 5000;

    public interface BleGpsListener {
        void onGpsData(NmeaParser.RmcData data);
        void onConnectionStateChange(int state); // BluetoothProfile.STATE_*
        void onNmeaRaw(String sentence); // for debug / display
    }

    private final Context mContext;
    private BleGpsListener mListener;
    private BluetoothGatt mGatt;
    private String mDeviceAddress;
    private int mState = BluetoothProfile.STATE_DISCONNECTED;
    private boolean mReconnect = true;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Accumulate partial NMEA lines across packets
    private final StringBuilder mNmeaBuffer = new StringBuilder();

    public BleGpsManager(Context context) {
        mContext = context;
    }

    public void setListener(BleGpsListener listener) {
        mListener = listener;
    }

    public void connect(String address) {
        mDeviceAddress = address;
        mReconnect = true;
        doConnect();
    }

    private void doConnect() {
        if (mGatt != null) {
            mGatt.close();
            mGatt = null;
        }
        try {
            BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
            if (adapter == null) return;
            BluetoothDevice device = adapter.getRemoteDevice(mDeviceAddress);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mGatt = device.connectGatt(mContext, false, mGattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } else {
                mGatt = device.connectGatt(mContext, false, mGattCallback);
            }
            mState = BluetoothProfile.STATE_CONNECTING;
            notifyState();
        } catch (Exception e) {
            Log.e(TAG, "connect failed", e);
        }
    }

    public void disconnect() {
        mReconnect = false;
        mHandler.removeCallbacksAndMessages(null);
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        mState = BluetoothProfile.STATE_DISCONNECTED;
        notifyState();
    }

    public int getState() { return mState; }
    public String getDeviceAddress() { return mDeviceAddress; }

    // ---- GATT callback ----

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            mState = newState;
            notifyState();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE GPS connected, discovering services");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE GPS disconnected");
                if (mReconnect) {
                    mHandler.postDelayed(BleGpsManager.this::doConnect, RECONNECT_DELAY_MS);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            BluetoothGattCharacteristic chr = findNmeaCharacteristic(gatt);
            if (chr == null) {
                Log.w(TAG, "No NMEA notify characteristic found");
                return;
            }
            enableNotify(gatt, chr);
            Log.i(TAG, "BLE GPS: subscribing to " + chr.getUuid());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic chr) {
            byte[] value = chr.getValue();
            if (value == null || value.length == 0) return;
            String chunk = new String(value, StandardCharsets.UTF_8);
            processNmeaChunk(chunk);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "BLE GPS descriptor write: " + status);
        }
    };

    // ---- Service / characteristic discovery ----

    /**
     * Prefer NUS TX; fall back to any service that has a NOTIFY characteristic
     * whose value looks like it could carry ASCII text (NMEA).
     */
    private BluetoothGattCharacteristic findNmeaCharacteristic(BluetoothGatt gatt) {
        // 1. Try Nordic UART TX
        BluetoothGattService nus = gatt.getService(NUS_SERVICE);
        if (nus != null) {
            BluetoothGattCharacteristic c = nus.getCharacteristic(NUS_TX_CHAR);
            if (c != null && hasNotify(c)) return c;
        }

        // 2. Scan all services for any NOTIFY characteristic
        for (BluetoothGattService svc : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                if (hasNotify(c)) return c;
            }
        }
        return null;
    }

    private boolean hasNotify(BluetoothGattCharacteristic c) {
        return (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
    }

    private void enableNotify(BluetoothGatt gatt, BluetoothGattCharacteristic chr) {
        gatt.setCharacteristicNotification(chr, true);
        BluetoothGattDescriptor desc = chr.getDescriptor(CCCD);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
        }
    }

    // ---- NMEA parsing ----

    /**
     * BLE packets may be fragmented — accumulate until we see a full line
     * ending with \r\n or \n, then parse each complete sentence.
     */
    private void processNmeaChunk(String chunk) {
        mNmeaBuffer.append(chunk);
        int idx;
        while ((idx = indexOfLineEnd(mNmeaBuffer)) >= 0) {
            String line = mNmeaBuffer.substring(0, idx).trim();
            // Consume up to and including the line terminator
            int next = idx + 1;
            if (next < mNmeaBuffer.length() && mNmeaBuffer.charAt(next) == '\n') next++;
            mNmeaBuffer.delete(0, next);

            if (line.isEmpty()) continue;

            if (mListener != null) mListener.onNmeaRaw(line);

            NmeaParser.RmcData rmc = NmeaParser.tryParseRmc(line);
            if (rmc != null && mListener != null) {
                mListener.onGpsData(rmc);
            }
        }

        // Guard against runaway buffer (e.g. device sending binary garbage)
        if (mNmeaBuffer.length() > 512) {
            mNmeaBuffer.delete(0, mNmeaBuffer.length() - 256);
        }
    }

    private int indexOfLineEnd(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\r' || c == '\n') return i;
        }
        return -1;
    }

    private void notifyState() {
        if (mListener != null) {
            mHandler.post(() -> mListener.onConnectionStateChange(mState));
        }
    }
}
