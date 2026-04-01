package com.windble.app.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import com.windble.app.R;
import com.windble.app.ble.BleConstants;
import com.windble.app.model.WindData;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WindViewModel mViewModel;
    private WindCompassView mCompassView;
    private TextView mTvAws, mTvAwa, mTvTws, mTwa, mTvTwd, mTvSog, mTvCog, mTvHeading;
    private TextView mTvStatus;
    private ImageView mIvGpsStatus, mIvBleStatus;
    private View mBtnToggleView, mBtnConnect;

    private static final int REQUEST_PERMISSIONS = 100;

    private final ActivityResultLauncher<Intent> mScanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String address = result.getData().getStringExtra(ScanActivity.EXTRA_ADDRESS);
                    if (address != null) {
                        mViewModel.connectDevice(address);
                        saveLastDevice(address);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mViewModel = new ViewModelProvider(this).get(WindViewModel.class);
        mViewModel.bindBleService();

        // Apply preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("screen_on", true)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        String unitPref = prefs.getString("speed_unit", "knots");
        if ("ms".equals(unitPref))       mViewModel.setSpeedUnit(WindViewModel.UNIT_MS);
        else if ("kmh".equals(unitPref)) mViewModel.setSpeedUnit(WindViewModel.UNIT_KMH);
        else                             mViewModel.setSpeedUnit(WindViewModel.UNIT_KNOTS);

        bindViews();
        setupListeners();
        observeViewModel();
        checkAndRequestPermissions();

        // Restore last connected device
        String lastDevice = getLastDevice();
        if (lastDevice != null && !lastDevice.isEmpty()) {
            mViewModel.connectDevice(lastDevice);
        }
    }

    private void bindViews() {
        mCompassView = findViewById(R.id.windCompassView);
        mTvAws = findViewById(R.id.tvAws);
        mTvAwa = findViewById(R.id.tvAwa);
        mTvTws = findViewById(R.id.tvTws);
        mTwa = findViewById(R.id.tvTwa);
        mTvTwd = findViewById(R.id.tvTwd);
        mTvSog = findViewById(R.id.tvSog);
        mTvCog = findViewById(R.id.tvCog);
        mTvHeading = findViewById(R.id.tvHeading);
        mTvStatus = findViewById(R.id.tvStatus);
        mIvGpsStatus = findViewById(R.id.ivGpsStatus);
        mIvBleStatus = findViewById(R.id.ivBleStatus);
        mBtnToggleView = findViewById(R.id.btnToggleView);
        mBtnConnect = findViewById(R.id.btnConnect);

        // Set speed formatter
        mCompassView.setSpeedFormatter(ms -> mViewModel.formatSpeed(ms));
    }

    private void setupListeners() {
        mBtnToggleView.setOnClickListener(v -> {
            int newMode = (mCompassView.getMode() == WindCompassView.MODE_COMPASS)
                    ? WindCompassView.MODE_BOAT : WindCompassView.MODE_COMPASS;
            mCompassView.setMode(newMode);
            updateToggleLabel(newMode);
        });

        mBtnConnect.setOnClickListener(v -> {
            Integer state = mViewModel.getConnectionState().getValue();
            if (state != null && state == BleConstants.STATE_CONNECTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Disconnect")
                        .setMessage("Disconnect from current device?")
                        .setPositiveButton("Disconnect", (d, w) -> mViewModel.disconnectDevice())
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                openScanActivity();
            }
        });
    }

    private void observeViewModel() {
        mViewModel.getWindData().observe(this, wind -> {
            if (wind == null) return;
            String aws = mViewModel.formatSpeed(wind.aws);
            String tws = mViewModel.formatSpeed(wind.tws);

            mTvAws.setText(aws);
            mTvAwa.setText(String.format("%.1f°", wind.awa));
            mTvTws.setText(tws);
            mTwa.setText(String.format("%.1f°", wind.twa));
            mTvTwd.setText(String.format("%.0f°T", wind.twd));
            mTvSog.setText(mViewModel.formatSpeed(wind.sog));
            mTvCog.setText(String.format("%.0f°T", wind.cog));

            mCompassView.setWindData(wind.aws, wind.awa, wind.tws, wind.twa, wind.twd, wind.heading);
        });

        mViewModel.getCompassHeading().observe(this, heading -> {
            mTvHeading.setText(String.format("%.0f°T", heading));
            mCompassView.setHeading(heading);
        });

        mViewModel.getConnectionState().observe(this, state -> {
            switch (state) {
                case BleConstants.STATE_CONNECTED:
                    mTvStatus.setText(R.string.status_connected);
                    mTvStatus.setTextColor(getResources().getColor(R.color.color_connected));
                    mIvBleStatus.setImageResource(R.drawable.ic_ble_connected);
                    ((TextView) mBtnConnect).setText(R.string.disconnect);
                    break;
                case BleConstants.STATE_CONNECTING:
                    mTvStatus.setText(R.string.status_connecting);
                    mTvStatus.setTextColor(getResources().getColor(R.color.color_connecting));
                    mIvBleStatus.setImageResource(R.drawable.ic_ble_connecting);
                    ((TextView) mBtnConnect).setText(R.string.connecting);
                    break;
                default:
                    mTvStatus.setText(R.string.status_disconnected);
                    mTvStatus.setTextColor(getResources().getColor(R.color.color_disconnected));
                    mIvBleStatus.setImageResource(R.drawable.ic_ble_disconnected);
                    ((TextView) mBtnConnect).setText(R.string.connect);
                    break;
            }
        });

        mViewModel.getGpsAvailable().observe(this, available -> {
            mIvGpsStatus.setImageResource(available
                    ? R.drawable.ic_gps_on : R.drawable.ic_gps_off);
        });
    }

    private void updateToggleLabel(int mode) {
        TextView tv = (TextView) mBtnToggleView;
        tv.setText(mode == WindCompassView.MODE_COMPASS ? "BOAT VIEW" : "COMPASS VIEW");
    }

    private void openScanActivity() {
        if (!checkBluetoothEnabled()) return;
        Intent intent = new Intent(this, ScanActivity.class);
        mScanLauncher.launch(intent);
    }

    private boolean checkBluetoothEnabled() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_show_apparent) {
            item.setChecked(!item.isChecked());
            mCompassView.setShowApparentWind(item.isChecked());
            return true;
        } else if (id == R.id.action_show_true) {
            item.setChecked(!item.isChecked());
            mCompassView.setShowTrueWind(item.isChecked());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem apparent = menu.findItem(R.id.action_show_apparent);
        MenuItem trueWind = menu.findItem(R.id.action_show_true);
        if (apparent != null) apparent.setChecked(true);
        if (trueWind != null) trueWind.setChecked(true);
        return super.onPrepareOptionsMenu(menu);
    }

    // ---- Permissions ----

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Permissions handled; user can connect manually
    }

    // ---- Preferences ----

    private void saveLastDevice(String address) {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString("last_device", address).apply();
    }

    private String getLastDevice() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getString("last_device", null);
    }

    @Override
    protected void onDestroy() {
        mViewModel.unbindBleService();
        super.onDestroy();
    }
}
