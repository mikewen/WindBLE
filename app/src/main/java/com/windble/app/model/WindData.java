package com.windble.app.model;

/**
 * Holds apparent and true wind data parsed from BLE packets.
 */
public class WindData {

    // Apparent Wind Speed (m/s)
    public float aws;
    // Apparent Wind Angle (degrees, 0-360, 0=ahead)
    public float awa;

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
     * Parse a 7-byte BLE notification packet.
     * Byte 0: 'W' (0x57)
     * Byte 1: flags
     * Byte 2-3: AWS uint16, m/s * 100
     * Byte 4-5: AWA uint16, deg * 100
     * Byte 6: XOR checksum (bytes 0-5)
     */
    public static WindData fromBytes(byte[] data) {
        if (data == null || data.length < 7) return null;

        // Validate magic byte
        if ((data[0] & 0xFF) != 0x57) return null;

        // Validate checksum
        byte xor = 0;
        for (int i = 0; i < 6; i++) xor ^= data[i];
        if (xor != data[6]) return null;

        WindData wd = new WindData();
        int awsRaw = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int awaRaw = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);

        wd.aws = awsRaw / 100.0f;
        wd.awa = awaRaw / 100.0f;
        wd.valid = true;
        return wd;
    }

    /**
     * Calculate True Wind from Apparent Wind and Boat Speed/COG.
     * Uses vector decomposition.
     *
     * @param awa  apparent wind angle, degrees (0=ahead, port negative or 0-360)
     * @param aws  apparent wind speed, m/s
     * @param bs   boat speed, m/s
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
    public static float msToKnots(float ms) {
        return ms * 1.94384f;
    }

    /** Convert m/s to km/h */
    public static float msToKmh(float ms) {
        return ms * 3.6f;
    }

    /** Normalize angle to 0-360 */
    public static float normalize360(float deg) {
        deg = deg % 360;
        if (deg < 0) deg += 360;
        return deg;
    }
}
