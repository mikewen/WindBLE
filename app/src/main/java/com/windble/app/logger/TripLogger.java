package com.windble.app.logger;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.windble.app.model.WindData;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logs wind + GPS data to a CSV file in the app's external files directory.
 * Files are stored in: <ExternalStorage>/Android/data/com.windble.app/files/trips/
 * Each recording session creates a new timestamped file.
 */
public class TripLogger {

    private static final String TAG = "TripLogger";
    private static final String DIR_NAME = "trips";
    private static final String CSV_HEADER =
            "timestamp_ms,utc,aws_ms,awa_deg,tws_ms,twa_deg,twd_deg,sog_ms,cog_deg,heading_deg";

    public interface LoggerListener {
        void onLoggingStarted(String filename);
        void onLoggingStopped(String filename, int rowCount);
        void onError(String message);
    }

    private final Context mContext;
    private LoggerListener mListener;
    private BufferedWriter mWriter;
    private String mCurrentFile;
    private boolean mLogging = false;
    private int mRowCount = 0;

    private final SimpleDateFormat mFileDateFmt =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private final SimpleDateFormat mRowDateFmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public TripLogger(Context context) {
        mContext = context;
    }

    public void setListener(LoggerListener listener) {
        mListener = listener;
    }

    public boolean isLogging() { return mLogging; }
    public String getCurrentFile() { return mCurrentFile; }
    public int getRowCount() { return mRowCount; }

    /** Start a new recording session. Creates a new CSV file. */
    public void start() {
        if (mLogging) stop();

        File dir = getLogDir();
        if (dir == null) {
            if (mListener != null) mListener.onError("Cannot access storage");
            return;
        }

        String filename = "trip_" + mFileDateFmt.format(new Date()) + ".csv";
        File file = new File(dir, filename);
        mCurrentFile = file.getAbsolutePath();
        mRowCount = 0;

        try {
            mWriter = new BufferedWriter(new FileWriter(file, false));
            mWriter.write(CSV_HEADER);
            mWriter.newLine();
            mWriter.flush();
            mLogging = true;
            Log.i(TAG, "Trip logging started: " + mCurrentFile);
            if (mListener != null) mListener.onLoggingStarted(filename);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open log file", e);
            if (mListener != null) mListener.onError("Cannot create file: " + e.getMessage());
        }
    }

    /** Stop the current recording session and flush the file. */
    public void stop() {
        if (!mLogging) return;
        mLogging = false;
        try {
            if (mWriter != null) {
                mWriter.flush();
                mWriter.close();
                mWriter = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing log file", e);
        }
        Log.i(TAG, "Trip logging stopped. Rows: " + mRowCount);
        String name = mCurrentFile != null
                ? new File(mCurrentFile).getName() : "unknown";
        if (mListener != null) mListener.onLoggingStopped(name, mRowCount);
    }

    /** Log one WindData sample. Call this every time new wind data arrives. */
    public void log(WindData wd) {
        if (!mLogging || mWriter == null || wd == null) return;
        try {
            long now = System.currentTimeMillis();
            String row = String.format(Locale.US,
                    "%d,%s,%.3f,%.1f,%.3f,%.1f,%.1f,%.3f,%.1f,%.1f",
                    now,
                    mRowDateFmt.format(new Date(now)),
                    wd.aws,
                    wd.awa,
                    wd.tws,
                    wd.twa,
                    wd.twd,
                    wd.sog,
                    wd.cog,
                    wd.heading);
            mWriter.write(row);
            mWriter.newLine();
            mRowCount++;
            // Flush every 10 rows to avoid data loss without hammering I/O
            if (mRowCount % 10 == 0) mWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Write error", e);
        }
    }

    /** Returns all saved trip files, newest first. */
    public File[] listTrips() {
        File dir = getLogDir();
        if (dir == null) return new File[0];
        File[] files = dir.listFiles(f -> f.getName().endsWith(".csv"));
        if (files == null) return new File[0];
        // Sort newest first
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    /** Delete a specific trip file. */
    public boolean deleteTrip(File file) {
        return file != null && file.delete();
    }

    private File getLogDir() {
        File extDir = mContext.getExternalFilesDir(null);
        if (extDir == null) return null;
        File dir = new File(extDir, DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return dir;
    }
}
