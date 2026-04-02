package com.windble.app.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
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
import com.windble.app.alert.WindShiftAlert;
import com.windble.app.ble.BleConstants;
import com.windble.app.model.WindData;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WindViewModel mViewModel;
    private WindCompassView mCompassView;
    private TextView mTvAws, mTvAwa, mTvTws, mTvTwa, mTvTwd, mTvSog, mTvCog, mTvHeading;
    private TextView mTvStatus, mBtnConnect, mBtnToggleView;
    private TextView mTvNmea, mTvShiftBanner, mTvLogStatus;
    private ImageView mIvGpsStatus, mIvBleStatus, mIvBleGpsStatus;
    private View mRootView;

    private static final int REQUEST_PERMISSIONS = 100;

    // Toggle state for menu checkboxes
    private boolean mShowApparentWind = true;
    private boolean mShowTrueWind     = true;
    private boolean mNightMode        = false;

    // Wind sensor scan
    private final ActivityResultLauncher<Intent> mWindScanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String address = result.getData().getStringExtra(ScanActivity.EXTRA_ADDRESS);
                    if (address != null) { mViewModel.connectDevice(address); saveLastDevice(address); }
                }
            });

    // BLE GPS scan
    private final ActivityResultLauncher<Intent> mGpsScanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String address = result.getData().getStringExtra(ScanActivity.EXTRA_ADDRESS);
                    String name    = result.getData().getStringExtra(ScanActivity.EXTRA_NAME);
                    if (address != null) { mViewModel.connectBleGps(address, name); saveLastBleGps(address, name); }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mViewModel = new ViewModelProvider(this).get(WindViewModel.class);
        mViewModel.bindBleService();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("screen_on", true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String unitPref = prefs.getString("speed_unit", "knots");
        if ("ms".equals(unitPref))       mViewModel.setSpeedUnit(WindViewModel.UNIT_MS);
        else if ("kmh".equals(unitPref)) mViewModel.setSpeedUnit(WindViewModel.UNIT_KMH);
        else                             mViewModel.setSpeedUnit(WindViewModel.UNIT_KNOTS);

        bindViews();
        setupListeners();
        observeViewModel();
        checkAndRequestPermissions();

        // Auto-reconnect
        String lastWind = getLastDevice();
        if (lastWind != null && !lastWind.isEmpty()) mViewModel.connectDevice(lastWind);
        String lastGpsAddr = prefs.getString("last_ble_gps_addr", null);
        String lastGpsName = prefs.getString("last_ble_gps_name", null);
        if (lastGpsAddr != null && !lastGpsAddr.isEmpty()) mViewModel.connectBleGps(lastGpsAddr, lastGpsName);
    }

    private void bindViews() {
        mRootView         = findViewById(R.id.rootLayout);
        mCompassView      = findViewById(R.id.windCompassView);
        mTvAws            = findViewById(R.id.tvAws);
        mTvAwa            = findViewById(R.id.tvAwa);
        mTvTws            = findViewById(R.id.tvTws);
        mTvTwa            = findViewById(R.id.tvTwa);
        mTvTwd            = findViewById(R.id.tvTwd);
        mTvSog            = findViewById(R.id.tvSog);
        mTvCog            = findViewById(R.id.tvCog);
        mTvHeading        = findViewById(R.id.tvHeading);
        mTvStatus         = findViewById(R.id.tvStatus);
        mIvGpsStatus      = findViewById(R.id.ivGpsStatus);
        mIvBleStatus      = findViewById(R.id.ivBleStatus);
        mIvBleGpsStatus   = findViewById(R.id.ivBleGpsStatus);
        mBtnToggleView    = findViewById(R.id.btnToggleView);
        mBtnConnect       = findViewById(R.id.btnConnect);
        mTvNmea           = findViewById(R.id.tvNmea);
        mTvShiftBanner    = findViewById(R.id.tvShiftBanner);
        mTvLogStatus      = findViewById(R.id.tvLogStatus);

        mCompassView.setSpeedFormatter(ms -> mViewModel.formatSpeed(ms));
    }

    private void setupListeners() {
        mBtnToggleView.setOnClickListener(v -> {
            int newMode = (mCompassView.getMode() == WindCompassView.MODE_COMPASS)
                    ? WindCompassView.MODE_BOAT : WindCompassView.MODE_COMPASS;
            mCompassView.setMode(newMode);
            mBtnToggleView.setText(newMode == WindCompassView.MODE_COMPASS ? "BOAT VIEW" : "COMPASS VIEW");
        });

        mBtnConnect.setOnClickListener(v -> {
            Integer state = mViewModel.getConnectionState().getValue();
            if (state != null && state == BleConstants.STATE_CONNECTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Disconnect Wind Sensor")
                        .setMessage("Disconnect from current wind sensor?")
                        .setPositiveButton("Disconnect", (d, w) -> mViewModel.disconnectDevice())
                        .setNegativeButton("Cancel", null).show();
            } else { openWindScan(); }
        });

        mIvBleGpsStatus.setOnClickListener(v -> {
            Integer gpsState = mViewModel.getBleGpsState().getValue();
            if (gpsState != null && gpsState == BluetoothProfile.STATE_CONNECTED) {
                new AlertDialog.Builder(this)
                        .setTitle("BLE GPS")
                        .setMessage("Disconnect BLE GPS and use phone GPS?")
                        .setPositiveButton("Disconnect", (d, w) -> { mViewModel.disconnectBleGps(); saveLastBleGps("", ""); })
                        .setNegativeButton("Cancel", null).show();
            } else { openGpsScan(); }
        });

        // Tap shift banner to reset baseline
        mTvShiftBanner.setOnClickListener(v -> {
            mViewModel.resetShiftBaseline();
            mTvShiftBanner.setVisibility(View.GONE);
        });
    }

    private void observeViewModel() {
        mViewModel.getWindData().observe(this, wind -> {
            if (wind == null) return;
            mTvAws.setText(mViewModel.formatSpeed(wind.aws));
            mTvAwa.setText(String.format("%.1f°", wind.awa));
            mTvTws.setText(mViewModel.formatSpeed(wind.tws));
            mTvTwa.setText(String.format("%.1f°", wind.twa));
            mTvTwd.setText(String.format("%.0f°T", wind.twd));
            mTvSog.setText(mViewModel.formatSpeed(wind.sog));
            mTvCog.setText(String.format("%.0f°T", wind.cog));
            mCompassView.setWindData(wind.aws, wind.awa, wind.tws, wind.twa, wind.twd, wind.heading);
        });

        mViewModel.getCompassHeading().observe(this, h -> {
            mTvHeading.setText(String.format("%.0f°T", h));
            mCompassView.setHeading(h);
        });

        mViewModel.getConnectionState().observe(this, state -> {
            switch (state) {
                case BleConstants.STATE_CONNECTED:
                    mTvStatus.setText(R.string.status_connected);
                    mTvStatus.setTextColor(getResources().getColor(R.color.color_connected));
                    mIvBleStatus.setImageResource(R.drawable.ic_ble_connected);
                    mBtnConnect.setText(R.string.disconnect);
                    break;
                case BleConstants.STATE_CONNECTING:
                    mTvStatus.setText(R.string.status_connecting);
                    mTvStatus.setTextColor(getResources().getColor(R.color.color_connecting));
                    mIvBleStatus.setImageResource(R.drawable.ic_ble_connecting);
                    mBtnConnect.setText(R.string.connecting);
                    break;
                default:
                    mTvStatus.setText(R.string.status_disconnected);
                    mTvStatus.setTextColor(getResources().getColor(R.color.color_disconnected));
                    mIvBleStatus.setImageResource(R.drawable.ic_ble_disconnected);
                    mBtnConnect.setText(R.string.connect);
                    break;
            }
        });

        mViewModel.getGpsAvailable().observe(this, available ->
                mIvGpsStatus.setImageResource(available ? R.drawable.ic_gps_on : R.drawable.ic_gps_off));

        mViewModel.getBleGpsState().observe(this, state -> {
            switch (state) {
                case BluetoothProfile.STATE_CONNECTED:
                    mIvBleGpsStatus.setImageResource(R.drawable.ic_ble_gps_on);
                    mIvBleGpsStatus.setColorFilter(getResources().getColor(R.color.color_connected));
                    mTvNmea.setVisibility(View.VISIBLE);
                    break;
                case BluetoothProfile.STATE_CONNECTING:
                    mIvBleGpsStatus.setImageResource(R.drawable.ic_ble_gps_on);
                    mIvBleGpsStatus.setColorFilter(getResources().getColor(R.color.color_connecting));
                    mTvNmea.setText("Connecting to BLE GPS\u2026");
                    mTvNmea.setVisibility(View.VISIBLE);
                    break;
                default:
                    mIvBleGpsStatus.setImageResource(R.drawable.ic_ble_gps_off);
                    mIvBleGpsStatus.clearColorFilter();
                    mTvNmea.setVisibility(View.GONE);
                    break;
            }
        });

        mViewModel.getLastNmea().observe(this, nmea -> {
            if (nmea != null && !nmea.isEmpty() && mTvNmea.getVisibility() == View.VISIBLE)
                mTvNmea.setText(nmea);
        });

        // Trip logging row count
        mViewModel.getLogRowCount().observe(this, rows -> {
            if (mViewModel.isLogging())
                mTvLogStatus.setText(String.format("● REC  %d pts", rows));
        });
        mViewModel.getLogging().observe(this, logging -> {
            mTvLogStatus.setVisibility(logging ? View.VISIBLE : View.GONE);
            if (!logging) mTvLogStatus.setText("");
            invalidateOptionsMenu(); // refresh menu item label
        });

        // Wind shift events
        mViewModel.getShiftEvent().observe(this, event -> {
            if (event == null) return;
            boolean isLift = event.type == WindShiftAlert.ShiftType.LIFT;
            String msg = String.format("%s  %+.0f°  TWD %.0f°",
                    isLift ? "▲ LIFT" : "▼ HEADER", event.shiftDeg, event.newTwd);
            mTvShiftBanner.setText(msg);
            mTvShiftBanner.setTextColor(getResources().getColor(
                    isLift ? R.color.color_connected : R.color.color_disconnected));
            mTvShiftBanner.setVisibility(View.VISIBLE);
            // Auto-hide after 8 s
            mTvShiftBanner.removeCallbacks(mHideShiftBanner);
            mTvShiftBanner.postDelayed(mHideShiftBanner, 8000);
        });

        // Night mode
        mViewModel.getNightMode().observe(this, this::applyNightMode);
    }

    private final Runnable mHideShiftBanner = () -> mTvShiftBanner.setVisibility(View.GONE);

    // ---- Night mode ----

    private void applyNightMode(boolean night) {
        mNightMode = night;
        if (night) {
            // Red-tint everything using a color matrix
            ColorMatrix cm = new ColorMatrix(new float[]{
                    0.299f, 0.587f, 0.114f, 0, 0,   // R = luminance
                    0,      0,      0,      0, 0,   // G = 0
                    0,      0,      0,      0, 0,   // B = 0
                    0,      0,      0,      1, 0    // A = unchanged
            });
            // Boost red channel
            ColorMatrix red = new ColorMatrix(new float[]{
                    1.2f, 0, 0, 0, 20,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 1, 0
            });
            red.preConcat(cm);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(red);
            mRootView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColorFilter(filter);
            mRootView.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            mRootView.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        invalidateOptionsMenu();
    }

    // ---- Menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem apparent   = menu.findItem(R.id.action_show_apparent);
        MenuItem trueWind   = menu.findItem(R.id.action_show_true);
        MenuItem nightMode  = menu.findItem(R.id.action_night_mode);
        MenuItem shiftAlert = menu.findItem(R.id.action_shift_alert);
        MenuItem logging    = menu.findItem(R.id.action_log_trip);
        MenuItem webServer  = menu.findItem(R.id.action_web_server_toggle);

        if (apparent   != null) apparent.setChecked(mShowApparentWind);
        if (trueWind   != null) trueWind.setChecked(mShowTrueWind);
        if (nightMode  != null) nightMode.setChecked(mNightMode);
        if (shiftAlert != null) shiftAlert.setChecked(
                Boolean.TRUE.equals(mViewModel.getShiftAlertOn().getValue()));
        if (logging    != null) logging.setTitle(
                mViewModel.isLogging() ? "Stop Recording" : "Start Recording");
        if (webServer  != null) webServer.setChecked(mViewModel.isWebServerRunning());

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_show_apparent) {
            mShowApparentWind = !mShowApparentWind;
            item.setChecked(mShowApparentWind);
            mCompassView.setShowApparentWind(mShowApparentWind);
            return true;
        } else if (id == R.id.action_show_true) {
            mShowTrueWind = !mShowTrueWind;
            item.setChecked(mShowTrueWind);
            mCompassView.setShowTrueWind(mShowTrueWind);
            return true;
        } else if (id == R.id.action_night_mode) {
            boolean newVal = !mNightMode;
            mViewModel.setNightMode(newVal);
            return true;
        } else if (id == R.id.action_shift_alert) {
            boolean current = Boolean.TRUE.equals(mViewModel.getShiftAlertOn().getValue());
            if (!current) showShiftThresholdDialog();
            else          mViewModel.setShiftAlertEnabled(false);
            return true;
        } else if (id == R.id.action_log_trip) {
            if (mViewModel.isLogging()) {
                mViewModel.stopLogging();
                Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show();
            } else {
                mViewModel.startLogging();
                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_large_number) {
            startActivity(new Intent(this, LargeNumberActivity.class));
            return true;
        } else if (id == R.id.action_add_ble_gps) {
            openGpsScan();
            return true;
        } else if (id == R.id.action_web_server_toggle) {
            toggleWebServer();
            return true;
        } else if (id == R.id.action_server_url) {
            showServerUrlDialog();
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showShiftThresholdDialog() {
        float current = mViewModel.getShiftAlert().getThreshold();
        String[] options = {"5°", "10°", "15°", "20°"};
        float[] values   = {5f,   10f,   15f,   20f};
        int selected = 1; // default 10°
        for (int i = 0; i < values.length; i++) if (values[i] == current) { selected = i; break; }

        final int[] choice = {selected};
        new AlertDialog.Builder(this)
                .setTitle("Wind Shift Threshold")
                .setSingleChoiceItems(options, selected, (d, w) -> choice[0] = w)
                .setPositiveButton("Enable", (d, w) -> {
                    mViewModel.setShiftThreshold(values[choice[0]]);
                    mViewModel.setShiftAlertEnabled(true);
                    Toast.makeText(this,
                            "Shift alert on: ≥" + options[choice[0]], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Navigation ----

    private void openWindScan() {
        if (!checkBluetoothEnabled()) return;
        Intent i = new Intent(this, ScanActivity.class);
        i.putExtra(ScanActivity.EXTRA_SCAN_MODE, ScanActivity.MODE_WIND);
        mWindScanLauncher.launch(i);
    }

    private void openGpsScan() {
        if (!checkBluetoothEnabled()) return;
        Intent i = new Intent(this, ScanActivity.class);
        i.putExtra(ScanActivity.EXTRA_SCAN_MODE, ScanActivity.MODE_GPS);
        mGpsScanLauncher.launch(i);
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

    // ---- Permissions ----

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ---- Prefs ----

    private void saveLastDevice(String address) {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString("last_device", address).apply();
    }
    private String getLastDevice() {
        return PreferenceManager.getDefaultSharedPreferences(this).getString("last_device", null);
    }
    private void saveLastBleGps(String address, String name) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("last_ble_gps_addr", address)
                .putString("last_ble_gps_name", name).apply();
    }

    private void toggleWebServer() {
        if (mViewModel.isWebServerRunning()) {
            mViewModel.stopWebServer();
            Toast.makeText(this, "WiFi server stopped", Toast.LENGTH_SHORT).show();
        } else {
            mViewModel.startWebServer();
            // Small delay so the server thread has time to bind the socket
            // before we read the IP address for the dialog
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            showServerUrlDialog();
                            invalidateOptionsMenu(); // refresh checkbox after server starts
                        }
                    }, 300);
        }
        invalidateOptionsMenu();
    }

    private void showServerUrlDialog() {
        String ip  = mViewModel.getHttpServer().getWifiIpAddress();
        String url = "http://" + ip + ":" + com.windble.app.server.WindHttpServer.PORT + "/";
        new AlertDialog.Builder(this)
                .setTitle("WiFi Wind Display")
                .setMessage("Open on any device on the same WiFi:\n\n" + url
                        + "\n\nAdd ?mode=ink for e-ink / Kindle.")
                .setPositiveButton("Copy URL", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                        Toast.makeText(this, "Copied: " + url, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        mViewModel.unbindBleService();
        super.onDestroy();
    }
}