package com.windble.app.ble;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.UUID;

public class BleService extends Service {
    private static final String TAG = "BleService";

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private volatile int mConnectionState = BleConstants.STATE_DISCONNECTED;
    private String mDeviceAddress;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Reconnect and Timeouts
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private boolean mReconnectEnabled = true;
    private Runnable mReconnectRunnable;
    private Runnable mConnectTimeoutRunnable;

    public class LocalBinder extends Binder {
        public BleService getService() { return BleService.this; }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager mgr = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mgr != null) mBluetoothAdapter = mgr.getAdapter();
    }

    public boolean connect(String address) {
        if (mBluetoothAdapter == null || address == null) return false;
        
        // If already connected to this address, just broadcast success
        if (mConnectionState == BleConstants.STATE_CONNECTED && address.equals(mDeviceAddress)) {
            broadcastUpdate(BleConstants.ACTION_GATT_CONNECTED);
            return true;
        }

        mDeviceAddress = address;
        mReconnectEnabled = true;

        stopConnectTimeout();
        
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Closing existing GATT connection before new attempt");
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }

        mConnectionState = BleConstants.STATE_CONNECTING;
        
        // Motorola fix: add a small delay and use application context
        mHandler.postDelayed(() -> {
            try {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                Log.i(TAG, "Initiating GATT connection to " + address);
                
                // Moto G / Legacy fix: Use autoConnect=false and potentially different transport
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    //noinspection deprecation
                    mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
                }
                
                if (mBluetoothGatt == null) {
                    Log.e(TAG, "connectGatt returned null");
                    handleDisconnect();
                } else {
                    startConnectTimeout();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during connectGatt", e);
                handleDisconnect();
            }
        }, 500); // Increased delay for Moto G stability
        
        return true;
    }

    private void startConnectTimeout() {
        stopConnectTimeout();
        mConnectTimeoutRunnable = () -> {
            if (mConnectionState == BleConstants.STATE_CONNECTING) {
                Log.w(TAG, "Connection timeout reached for " + mDeviceAddress);
                close();
                handleDisconnect();
            }
        };
        mHandler.postDelayed(mConnectTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    private void stopConnectTimeout() {
        if (mConnectTimeoutRunnable != null) {
            mHandler.removeCallbacks(mConnectTimeoutRunnable);
            mConnectTimeoutRunnable = null;
        }
    }

    public void disconnect() {
        mReconnectEnabled = false;
        stopConnectTimeout();
        if (mReconnectRunnable != null) {
            mHandler.removeCallbacks(mReconnectRunnable);
        }
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
        }
    }

    public void close() {
        disconnect();
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        mConnectionState = BleConstants.STATE_DISCONNECTED;
    }

    public int getConnectionState() { return mConnectionState; }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT error status: " + status + ". Closing GATT.");
                gatt.close();
                if (gatt == mBluetoothGatt) mBluetoothGatt = null;
                handleDisconnect();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                stopConnectTimeout();
                mConnectionState = BleConstants.STATE_CONNECTED;
                Log.i(TAG, "Connected to GATT server, discovering services...");
                // Motorola/Samsung fix: discoverServices works better on UI thread with a delay
                mHandler.postDelayed(gatt::discoverServices, 600);
                broadcastUpdate(BleConstants.ACTION_GATT_CONNECTED);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close();
                if (gatt == mBluetoothGatt) mBluetoothGatt = null;
                handleDisconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered failed: " + status);
                return;
            }
            enableNotifications(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (BleConstants.CHAR_AE02.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                broadcastData(data);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "Descriptor write status: " + status);
        }
    };

    private void handleDisconnect() {
        mConnectionState = BleConstants.STATE_DISCONNECTED;
        Log.i(TAG, "Disconnected from GATT server");
        broadcastUpdate(BleConstants.ACTION_GATT_DISCONNECTED);
        if (mReconnectEnabled) {
            scheduleReconnect();
        }
    }

    private void enableNotifications(BluetoothGatt gatt) {
        BluetoothGattCharacteristic characteristic = findCharacteristic(gatt,
                BleConstants.SERVICE_AE00, BleConstants.CHAR_AE02);
        if (characteristic == null) {
            characteristic = findCharacteristic(gatt,
                    BleConstants.SERVICE_AE30, BleConstants.CHAR_AE02);
        }
        if (characteristic == null) {
            for (BluetoothGattService svc : gatt.getServices()) {
                BluetoothGattCharacteristic c = svc.getCharacteristic(BleConstants.CHAR_AE02);
                if (c != null) { characteristic = c; break; }
            }
        }

        if (characteristic == null) {
            Log.e(TAG, "ae02 characteristic not found");
            return;
        }

        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(BleConstants.CCCD);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }
        Log.i(TAG, "Notifications enabled for ae02");
    }

    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt gatt, UUID serviceUuid, UUID charUuid) {
        BluetoothGattService service = gatt.getService(serviceUuid);
        if (service == null) return null;
        return service.getCharacteristic(charUuid);
    }

    private void scheduleReconnect() {
        if (!mReconnectEnabled || mDeviceAddress == null) return;
        if (mReconnectRunnable != null) mHandler.removeCallbacks(mReconnectRunnable);
        mReconnectRunnable = () -> {
            if (mReconnectEnabled && mConnectionState == BleConstants.STATE_DISCONNECTED) {
                Log.i(TAG, "Attempting reconnect to " + mDeviceAddress);
                connect(mDeviceAddress);
            }
        };
        mHandler.postDelayed(mReconnectRunnable, RECONNECT_DELAY_MS);
    }

    private void broadcastUpdate(String action) {
        Intent intent = new Intent(action);
        if (mDeviceAddress != null) {
            intent.putExtra(BleConstants.EXTRA_DEVICE_ADDRESS, mDeviceAddress);
        }
        sendBroadcast(intent);
    }

    private void broadcastData(byte[] data) {
        Intent intent = new Intent(BleConstants.ACTION_DATA_AVAILABLE);
        intent.putExtra(BleConstants.EXTRA_DATA, data);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
    }
}
