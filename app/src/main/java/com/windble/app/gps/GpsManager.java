package com.windble.app.gps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class GpsManager {
    private static final String TAG = "GpsManager";
    private static final long MIN_TIME_MS = 1000;
    private static final float MIN_DIST_M = 0.5f;

    public interface GpsListener {
        void onLocationUpdate(float sog, float cog, double lat, double lon, float accuracy);
        void onGpsStatusChange(boolean available);
    }

    private final Context mContext;
    private LocationManager mLocationManager;
    private GpsListener mListener;
    private boolean mEnabled = false;
    private float mLastSog = 0f;
    private float mLastCog = 0f;
    private double mLastLat = 0;
    private double mLastLon = 0;

    // Manual heading override (from compass or BLE GPS)
    private float mManualHeading = Float.NaN;
    private boolean mUseManualHeading = false;

    public GpsManager(Context context) {
        mContext = context;
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(GpsListener listener) {
        mListener = listener;
    }

    public void start() {
        if (mEnabled) return;
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No location permission");
            return;
        }
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS, MIN_DIST_M, mLocationListener);
            // Fallback to network
            mLocationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS * 2, MIN_DIST_M, mLocationListener);
            mEnabled = true;
            Log.i(TAG, "GPS started");
        } catch (Exception e) {
            Log.e(TAG, "GPS start failed", e);
        }
    }

    public void stop() {
        if (!mEnabled) return;
        mLocationManager.removeUpdates(mLocationListener);
        mEnabled = false;
        Log.i(TAG, "GPS stopped");
    }

    public float getSog() { return mLastSog; }
    public float getCog() { return mLastCog; }
    public double getLat() { return mLastLat; }
    public double getLon() { return mLastLon; }

    /** Returns effective heading: manual override if set, else COG */
    public float getHeading() {
        if (mUseManualHeading && !Float.isNaN(mManualHeading)) return mManualHeading;
        return mLastCog;
    }

    public void setManualHeading(float heading) {
        mManualHeading = heading;
        mUseManualHeading = true;
    }

    public void clearManualHeading() {
        mUseManualHeading = false;
        mManualHeading = Float.NaN;
    }

    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;
            mLastLat = location.getLatitude();
            mLastLon = location.getLongitude();

            if (location.hasSpeed()) {
                mLastSog = location.getSpeed(); // m/s
            }
            if (location.hasBearing()) {
                mLastCog = location.getBearing(); // degrees True
            }

            float accuracy = location.hasAccuracy() ? location.getAccuracy() : -1f;

            if (mListener != null) {
                mListener.onLocationUpdate(mLastSog, mLastCog, mLastLat, mLastLon, accuracy);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {
            if (mListener != null) mListener.onGpsStatusChange(true);
        }

        @Override
        public void onProviderDisabled(String provider) {
            if (mListener != null) mListener.onGpsStatusChange(false);
        }
    };
}
