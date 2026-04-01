package com.windble.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.windble.app.R;
import com.windble.app.alert.WindShiftAlert;

/**
 * Full-screen large-number display for reading from the helm at distance.
 * Shows one configurable value at a time; tap to cycle through values.
 */
public class LargeNumberActivity extends AppCompatActivity {

    public static final String EXTRA_FIELD = "field";
    public static final int FIELD_TWA  = 0;
    public static final int FIELD_TWS  = 1;
    public static final int FIELD_TWD  = 2;
    public static final int FIELD_AWA  = 3;
    public static final int FIELD_AWS  = 4;
    public static final int FIELD_SOG  = 5;

    private static final String[] FIELD_LABELS = {"TWA", "TWS", "TWD", "AWA", "AWS", "SOG"};
    private static final int[] FIELD_COLORS = {
            0xFFFF6B35,  // TWA  orange
            0xFFFF6B35,  // TWS  orange
            0xFFFF6B35,  // TWD  orange
            0xFF00E5FF,  // AWA  cyan
            0xFF00E5FF,  // AWS  cyan
            0xFF4CAF50,  // SOG  green
    };

    private WindViewModel mViewModel;
    private TextView mTvLabel;
    private TextView mTvValue;
    private TextView mTvUnit;
    private TextView mTvShiftBadge;

    private int mCurrentField = FIELD_TWA;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen, keep on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_large_number);

        mCurrentField = getIntent().getIntExtra(EXTRA_FIELD, FIELD_TWA);

        mTvLabel      = findViewById(R.id.tvLargeLabel);
        mTvValue      = findViewById(R.id.tvLargeValue);
        mTvUnit       = findViewById(R.id.tvLargeUnit);
        mTvShiftBadge = findViewById(R.id.tvShiftBadge);

        mViewModel = new ViewModelProvider(this).get(WindViewModel.class);

        // Tap anywhere to cycle to next field
        findViewById(R.id.largeNumberRoot).setOnClickListener(v -> {
            mCurrentField = (mCurrentField + 1) % FIELD_LABELS.length;
            updateFieldStyle();
        });

        // Long-press returns to main
        findViewById(R.id.largeNumberRoot).setOnLongClickListener(v -> {
            finish();
            return true;
        });

        updateFieldStyle();
        observeData();
    }

    private void updateFieldStyle() {
        mTvLabel.setText(FIELD_LABELS[mCurrentField]);
        mTvValue.setTextColor(FIELD_COLORS[mCurrentField]);
        mTvLabel.setTextColor(FIELD_COLORS[mCurrentField] & 0x99FFFFFF | 0x99000000);
    }

    private void observeData() {
        mViewModel.getWindData().observe(this, wind -> {
            if (wind == null) return;
            String value, unit;
            switch (mCurrentField) {
                case FIELD_TWA:
                    value = String.format("%.0f", wind.twa);
                    unit  = "°";
                    break;
                case FIELD_TWS:
                    String tws = mViewModel.formatSpeed(wind.tws);
                    splitValueUnit(tws);
                    return;
                case FIELD_TWD:
                    value = String.format("%.0f", wind.twd);
                    unit  = "°T";
                    break;
                case FIELD_AWA:
                    value = String.format("%.0f", wind.awa);
                    unit  = "°";
                    break;
                case FIELD_AWS:
                    String aws = mViewModel.formatSpeed(wind.aws);
                    splitValueUnit(aws);
                    return;
                case FIELD_SOG:
                    String sog = mViewModel.formatSpeed(wind.sog);
                    splitValueUnit(sog);
                    return;
                default:
                    return;
            }
            mTvValue.setText(value);
            mTvUnit.setText(unit);
        });

        // Show shift badge when a shift fires
        mViewModel.getShiftEvent().observe(this, shift -> {
            if (shift == null) return;
            String badge = String.format("%s %+.0f°",
                    shift.type == WindShiftAlert.ShiftType.LIFT ? "▲ LIFT" : "▼ HDR",
                    shift.shiftDeg);
            mTvShiftBadge.setText(badge);
            mTvShiftBadge.setTextColor(
                    shift.type == WindShiftAlert.ShiftType.LIFT ? 0xFF4CAF50 : 0xFFFF5252);
            mTvShiftBadge.setVisibility(View.VISIBLE);
            mTvShiftBadge.postDelayed(() -> mTvShiftBadge.setVisibility(View.INVISIBLE), 5000);
        });
    }

    /** Split a formatted speed string like "12.3 kt" into value + unit TextViews */
    private void splitValueUnit(String formatted) {
        int space = formatted.lastIndexOf(' ');
        if (space > 0) {
            mTvValue.setText(formatted.substring(0, space));
            mTvUnit.setText(formatted.substring(space + 1));
        } else {
            mTvValue.setText(formatted);
            mTvUnit.setText("");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}