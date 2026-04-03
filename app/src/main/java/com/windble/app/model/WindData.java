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
        // We now expect exactly 11 bytes for the debug firmware.
        // Standard packet was 7 bytes; this debug version adds 4 bytes of raw ADC data.
        if (data == null || data.length < 11) return null;

        // Validate frame ID: 'W' (0x57)
        if ((data[0] & 0xFF) != 0x57) return null;

        // XOR checksum. The checksum is stored in the last byte (index 10).
        byte xor = 0;
        for (int i = 0; i < 10; i++) xor ^= data[i];
        
        if (xor != data[10]) {
            Log.e(TAG, String.format("Checksum mismatch! Expected %02X but got %02X", 
                    data[10] & 0xFF, xor & 0xFF));
            return null;
        }

        // Check valid flag (bit 0 of flags byte)
        // Byte 1: flags — bit 0 = valid fix/sensor data
        int flags = data[1] & 0xFF;
        if ((flags & 0x01) == 0) return null;   // sensor reports no valid data

        /**
         * Decode Apparent Wind data (uint16 big-endian).
         * Byte 2-3: AWS uint16 big-endian, m/s * 100
         * Byte 4-5: AWA uint16 big-endian, deg * 100
         */
        int awsRaw = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int awaRaw = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);

        WindData wd = new WindData();
        
        // Apply AWS calibration (raw value / 100 to get m/s, then multiplier)
        wd.aws = (awsRaw / 100.0f) * awsMultiplier;

        // Apply AWA calibration (raw value / 100 to get degrees, then offset)
        // Normalise to [0, 360) range.
        float awa = (awaRaw / 100.0f) + awaOffset;
        awa = awa % 360.0f;
        if (awa < 0) awa += 360.0f;
        wd.awa = awa;

        /**
         * Parse extra ADC data (11-byte packet debug).
         * Byte 6-7: adc_dir uint16 big-endian
         * Byte 8-9: adc_speed uint16 big-endian
         */
        wd.adcDir = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        wd.adcSpeed = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
        
        // Log both calibrated and raw data for debugging firmware/calibration issues
        Log.d(TAG, String.format("Parsed (11-byte): AWA=%.2f (Raw=%.2f, Off=%.1f), AWS=%.2f (Raw=%.2f, Mult=%.2f), ADC_DIR=%d, ADC_SPEED=%d",
                wd.awa, awaRaw/100.0f, awaOffset, wd.aws, awsRaw/100.0f, awsMultiplier, wd.adcDir, wd.adcSpeed));

        wd.valid = true;
        return wd;
    }
    
    /**
     * Legacy/Simpler interface for parsing without explicit calibration values.
     */
    public static WindData fromBytes(byte[] data) {
        return fromBytes(data, 0, 1.0f);
    }

    /**
     * Calculate True Wind from Apparent Wind and Boat Speed/COG.
     * Uses vector decomposition.
     *
     * @param awa     apparent wind angle, degrees (0=ahead)
     * @param aws     apparent wind speed, m/s
     * @param bs      boat speed over ground, m/s
     * @param heading boat heading, degrees True (North=0)
     */
    public void calculateTrueWind(float awa, float aws, float bs, float heading) {
        // Convert AWA to radians (0 = bow, clockwise positive)
        double awaRad = Math.toRadians(awa);

        // Apparent wind components in boat frame (X=forward, Y=starboard)
        double awX = aws * Math.cos(awaRad);
        double awY = aws * Math.sin(awaRad);

        // Boat velocity in boat frame: moving forward
        double bsX = bs;
        double bsY = 0.0;

        // True wind = Apparent wind - Boat velocity (vector subtraction)
        double twX = awX - bsX;
        double twY = awY - bsY;

        tws = (float) Math.sqrt(twX * twX + twY * twY);
        twa = (float) Math.toDegrees(Math.atan2(twY, twX));
        if (twa < 0) twa += 360;

        // True Wind Direction (compass bearing from which wind comes)
        twd = (heading + twa + 180) % 360;
    }

    /** Convert m/s to knots */
    public static float msToKnots(float ms) { return ms * 1.94384f; }
    /** Convert m/s to km/h */
    public static float msToKmh(float ms) { return ms * 3.6f; }
    /** Normalize angle to 0-360 */
    public static float normalize360(float deg) {
        deg = deg % 360;
        if (deg < 0) deg += 360;
        return deg;
    }
}
