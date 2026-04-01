package com.windble.app.gps;

/**
 * Parses NMEA $GNRMC / $GPRMC sentences.
 *
 * Sentence format:
 * $GNRMC,HHMMSS.ss,A,LLLL.LL,a,YYYYY.YY,a,x.x,x.x,DDMMYY,x.x,a*hh
 *   [0]  sentence ID
 *   [1]  UTC time
 *   [2]  Status  A=active V=void
 *   [3]  Latitude
 *   [4]  N/S
 *   [5]  Longitude
 *   [6]  E/W
 *   [7]  Speed over ground, knots
 *   [8]  Course over ground, degrees True
 *   [9]  Date DDMMYY
 *  [10]  Magnetic variation
 *  [11]  E/W
 *  [12]  checksum (*HH)
 */
public class NmeaParser {

    public static class RmcData {
        public boolean valid;
        public double latitude;
        public double longitude;
        public float sogKnots;   // speed over ground, knots
        public float sogMs;      // speed over ground, m/s
        public float cogDeg;     // course over ground, degrees True
        public String utcTime;
        public String date;
    }

    /**
     * Try to parse a complete NMEA line. Returns non-null RmcData if it is
     * a valid, active $GNRMC or $GPRMC sentence with a correct checksum.
     */
    public static RmcData tryParseRmc(String line) {
        if (line == null) return null;
        line = line.trim();
        if (!line.startsWith("$GNRMC") && !line.startsWith("$GPRMC")) return null;
        if (!verifyChecksum(line)) return null;

        // Strip checksum suffix
        int starIdx = line.lastIndexOf('*');
        String body = (starIdx > 0) ? line.substring(1, starIdx) : line.substring(1);
        String[] fields = body.split(",", -1);

        if (fields.length < 9) return null;

        RmcData d = new RmcData();
        d.utcTime = fields[1];

        // Status must be A (active) for a valid fix
        if (!"A".equals(fields[2])) {
            d.valid = false;
            return d;
        }

        try {
            d.latitude  = parseLatLon(fields[3], fields[4]);
            d.longitude = parseLatLon(fields[5], fields[6]);
            d.sogKnots  = fields[7].isEmpty() ? 0f : Float.parseFloat(fields[7]);
            d.sogMs     = d.sogKnots * 0.514444f;
            d.cogDeg    = fields[8].isEmpty() ? 0f : Float.parseFloat(fields[8]);
            if (fields.length > 9) d.date = fields[9];
            d.valid = true;
        } catch (NumberFormatException e) {
            d.valid = false;
        }
        return d;
    }

    /** Parse NMEA lat/lon: DDDMM.MMMM + hemisphere */
    private static double parseLatLon(String value, String hemisphere) {
        if (value == null || value.isEmpty()) return 0.0;
        // Format: DDDMM.MMMMM  — first 2 (lat) or 3 (lon) digits are degrees
        double raw = Double.parseDouble(value);
        int degrees = (int) (raw / 100);
        double minutes = raw - (degrees * 100);
        double decimal = degrees + minutes / 60.0;
        if ("S".equals(hemisphere) || "W".equals(hemisphere)) decimal = -decimal;
        return decimal;
    }

    /** Verify XOR checksum: all bytes between $ and * */
    public static boolean verifyChecksum(String sentence) {
        int starIdx = sentence.lastIndexOf('*');
        if (starIdx < 0 || starIdx + 3 > sentence.length()) return false;
        String checksumHex = sentence.substring(starIdx + 1, starIdx + 3);
        int expected;
        try {
            expected = Integer.parseInt(checksumHex, 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int calc = 0;
        int start = sentence.startsWith("$") ? 1 : 0;
        for (int i = start; i < starIdx; i++) {
            calc ^= sentence.charAt(i);
        }
        return calc == expected;
    }
}
