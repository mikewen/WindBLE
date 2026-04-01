package com.windble.app.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.windble.app.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ScanActivity";

    public static final String EXTRA_SCAN_MODE = "scan_mode";
    public static final String EXTRA_ADDRESS   = "device_address";
    public static final String EXTRA_NAME      = "device_name";

    public static final int MODE_WIND = 0;
    public static final int MODE_GPS  = 1;

    private static final long SCAN_PERIOD_MS = 20_000;

    private BluetoothAdapter mAdapter;
    private BluetoothLeScanner mScanner;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mScanning = false;
    private int mScanMode = MODE_WIND;

    private DeviceAdapter mDeviceAdapter;
    private TextView mTvScanStatus;
    private View mProgressBar;

    private final List<ScannedDevice> mDevices = new ArrayList<>();
    private final Set<String> mSeen = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        mScanMode = getIntent().getIntExtra(EXTRA_SCAN_MODE, MODE_WIND);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(mScanMode == MODE_GPS
                    ? "Add BLE GPS Device" : "Scan for Wind Sensor");
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter = bm != null ? bm.getAdapter() : null;

        mTvScanStatus = findViewById(R.id.tvScanStatus);
        mProgressBar  = findViewById(R.id.scanProgress);

        RecyclerView rv = findViewById(R.id.rvDevices);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        mDeviceAdapter = new DeviceAdapter(mDevices);
        rv.setAdapter(mDeviceAdapter);

        mDeviceAdapter.setOnClickListener(device -> {
            stopScan();
            Intent result = new Intent();
            result.putExtra(EXTRA_ADDRESS, device.address);
            result.putExtra(EXTRA_NAME, device.name);
            result.putExtra(EXTRA_SCAN_MODE, mScanMode);
            setResult(RESULT_OK, result);
            finish();
        });

        startScan();
    }

    private void startScan() {
        if (mAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasPermission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission = ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            hasPermission = ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        if (!hasPermission) {
            Toast.makeText(this, "Bluetooth scan permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        mDevices.clear(); mSeen.clear();
        mDeviceAdapter.notifyDataSetChanged();
        mScanning = true;
        mProgressBar.setVisibility(View.VISIBLE);
        mTvScanStatus.setText(mScanMode == MODE_GPS
                ? "Scanning for BLE GPS devices\u2026"
                : getString(R.string.scanning));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mScanner = mAdapter.getBluetoothLeScanner();
            if (mScanner == null) {
                Toast.makeText(this, "BLE scanner not available", Toast.LENGTH_SHORT).show();
                mScanning = false;
                mProgressBar.setVisibility(View.GONE);
                return;
            }
            try {
                mScanner.startScan(mScanCallback);
            } catch (SecurityException e) {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            // Legacy API for Android 4.4.4
            try {
                //noinspection deprecation
                mAdapter.startLeScan(mLeScanCallback);
            } catch (SecurityException e) {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        mHandler.postDelayed(this::stopScan, SCAN_PERIOD_MS);
    }

    private void stopScan() {
        if (!mScanning) return;
        mScanning = false;
        mProgressBar.setVisibility(View.GONE);
        mTvScanStatus.setText(mDevices.isEmpty()
                ? getString(R.string.no_devices)
                : getString(R.string.tap_to_connect));
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { if (mScanner != null) mScanner.stopScan(mScanCallback); }
            catch (SecurityException ignored) {}
        } else {
            try {
                //noinspection deprecation
                mAdapter.stopLeScan(mLeScanCallback);
            } catch (SecurityException ignored) {}
        }
    }

    private final ScanCallback mScanCallback = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ? new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            addDevice(device, result.getRssi());
        }
        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                mTvScanStatus.setText("Scan failed (error " + errorCode + ")");
                mProgressBar.setVisibility(View.GONE);
            });
        }
    } : null;

    private final BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            addDevice(device, rssi);
        }
    };

    private void addDevice(BluetoothDevice device, int rssi) {
        String address = device.getAddress();
        if (mSeen.contains(address)) return;
        mSeen.add(address);
        String name;
        try { name = device.getName(); } catch (SecurityException e) { name = null; }
        if (name == null || name.isEmpty()) name = "Unknown (" + address + ")";
        
        String finalName = name;
        runOnUiThread(() -> {
            mDevices.add(new ScannedDevice(finalName, address, rssi));
            mDeviceAdapter.notifyItemInserted(mDevices.size() - 1);
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
    @Override
    protected void onDestroy() { stopScan(); super.onDestroy(); }

    // ---- Model ----
    static class ScannedDevice {
        String name, address; int rssi;
        ScannedDevice(String n, String a, int r) { name=n; address=a; rssi=r; }
    }

    // ---- Adapter ----
    static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {
        interface OnClick { void onClick(ScannedDevice d); }
        private final List<ScannedDevice> mList;
        private OnClick mListener;
        DeviceAdapter(List<ScannedDevice> list) { mList = list; }
        void setOnClickListener(OnClick l) { mListener = l; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ScannedDevice d = mList.get(pos);
            h.tvName.setText(d.name);
            h.tvAddress.setText(d.address);
            h.tvRssi.setText(d.rssi + " dBm");
            h.itemView.setOnClickListener(v -> { if (mListener != null) mListener.onClick(d); });
        }
        @Override public int getItemCount() { return mList.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvAddress, tvRssi;
            VH(View v) {
                super(v);
                tvName    = v.findViewById(R.id.tvDeviceName);
                tvAddress = v.findViewById(R.id.tvDeviceAddress);
                tvRssi    = v.findViewById(R.id.tvDeviceRssi);
            }
        }
    }
}
