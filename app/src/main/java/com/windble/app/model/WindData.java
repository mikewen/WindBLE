package com.windble.app.model;

import android.util.Log;

/**
 * Holds apparent and true wind data parsed from BLE packets.
 */
public class WindData {
    private static final String TAG = "WindData";

    // Apparent Wind Speed (m/s)
    public float aws;
    // Apparent Wind Angle (degrees, 0-360, 0=ahead)
    public float awa;

    // Rolling averages for AWS (m/s) - populated by ViewModel
    public float awsAvg1m;
    public float awsAvg1h;
    
    // Rolling maximums for AWS (m/s)
    public float awsMax1m;
    public float awsMax1h;

    // Raw ADC values for debugging firmware bugs
    public int adcDir;
    public int adcSpeed;

    // True Wind Speed (m/s)  — calculated
    public float tws;
    // True Wind Angle (degrees, 0-360, 0=ahead of boat)
    public float twa;
    // True Wind Direction (degrees True, relative to North)
    public float twd;

    // Boat speed over ground (m/s) from GPS
    public float sog;
    // Boat course over ground (degrees True) from GPS
    public float cog;
    // Boat heading (degrees True) — may equal COG when no separate heading sensor
    public float heading;

    public boolean hasGps;
    public boolean valid;

    public WindData() {}

    /**
     * Parse a BLE notification packet.
     *
     * @param data        11-byte packet
     * @param awaOffset   Calibration offset for AWA (degrees)
     * @param awsMultiplier Calibration multiplier for AWS (default 1.0)
     * @return WindData object or null if invalid
     */
    public static WindData fromBytes(byte[] data, float awaOffset, float awsMultiplier) {
        if (data == null || data.length < 11) return null;

        // Validate frame ID: 'W' (0x57)
        if ((data[0] & 0xFF) != 0x57) return null;

        // XOR checksum.
        byte xor = 0;
        for (int i = 0; i < 10; i++) xor ^= data[i];
        
        if (xor != data[10]) {
            Log.e(TAG, String.format("Checksum mismatch! Expected %02X but got %02X", 
                    data[10] & 0xFF, xor & 0xFF));
            return null;
        }

        int flags = data[1] & 0xFF;
        if ((flags & 0x01) == 0) return null;

        int awsRaw = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int awaRaw = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);

        WindData wd = new WindData();
        wd.aws = (awsRaw / 100.0f) * awsMultiplier;

        float awa = (awaRaw / 100.0f) + awaOffset;
        awa = awa % 360.0f;
        if (awa < 0) awa += 360.0f;
        wd.awa = awa;

        wd.adcDir = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        wd.adcSpeed = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
        
        wd.valid = true;
        return wd;
    }
    
    public static WindData fromBytes(byte[] data) {
        return fromBytes(data, 0, 1.0f);
    }

    /**
     * Calculate True Wind from Apparent Wind and Boat Speed/COG.
     * Uses vector decomposition.
     */
    public void calculateTrueWind(float awa, float aws, float bs, float heading) {
        // Fix for 180-degree flip at zero/near-zero speed:
        // If boat speed is negligible, True Wind is the same as Apparent Wind.
        if (bs < 0.15f) { // ~0.3 knots threshold
            this.tws = aws;
            this.twa = awa;
            this.twd = normalize360(heading + twa);
            return;
        }

        // Convert AWA to radians (0 = bow, clockwise positive)
        double awaRad = Math.toRadians(awa);

        // Apparent wind components in boat frame (X=forward, Y=starboard)
        double awX = aws * Math.cos(awaRad);
        double awY = aws * Math.sin(awaRad);

        // Boat velocity in boat frame: moving forward
        double bsX = bs;
        double bsY = 0.0;

        // True wind (from) = Apparent wind (from) - Boat velocity (to)
        double twX = awX - bsX;
        double twY = awY - bsY;

        tws = (float) Math.sqrt(twX * twX + twY * twY);
        twa = (float) Math.toDegrees(Math.atan2(twY, twX));
        if (twa < 0) twa += 360;

        twd = normalize360(heading + twa);
    }

    public static float msToKnots(float ms) { return ms * 1.94384f; }
    public static float msToKmh(float ms) { return ms * 3.6f; }
    public static float normalize360(float deg) {
        deg = deg % 360;
        if (deg < 0) deg += 360;
        return deg;
    }
}
