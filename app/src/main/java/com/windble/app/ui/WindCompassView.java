package com.windble.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom View that renders a nautical-style wind compass.
 */
public class WindCompassView extends View {

    public static final int MODE_COMPASS = 0;
    public static final int MODE_BOAT = 1;

    private int mMode = MODE_COMPASS;

    // Wind data
    private float mAwa = 0f;
    private float mAws = 0f;
    private float mTwa = 0f;
    private float mTws = 0f;
    private float mTwd = 0f;
    private float mHeading = 0f;
    private boolean mShowTrueWind = true;
    private boolean mShowApparentWind = true;

    private SpeedFormatter mSpeedFormatter;

    public interface SpeedFormatter {
        String format(float ms);
    }

    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mApparentArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrueArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBoatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int COLOR_BG         = Color.parseColor("#0A0E1A");
    private static final int COLOR_RING        = Color.parseColor("#1A2540");
    private static final int COLOR_RING_BORDER = Color.parseColor("#2A3A60");
    private static final int COLOR_TICK        = Color.parseColor("#3A5080");
    private static final int COLOR_CARDINAL    = Color.parseColor("#7AB4FF");
    private static final int COLOR_BOAT        = Color.parseColor("#A0C0FF");
    private static final int COLOR_APPARENT    = Color.parseColor("#00E5FF");
    private static final int COLOR_TRUE        = Color.parseColor("#FF6B35");
    private static final int COLOR_NORTH       = Color.parseColor("#FF4444");

    private float mCx, mCy, mRadius;
    private final RectF mRingRect = new RectF();

    public WindCompassView(Context context) { super(context); init(); }
    public WindCompassView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public WindCompassView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        mBgPaint.setColor(COLOR_BG);
        mBgPaint.setStyle(Paint.Style.FILL);
        mRingPaint.setColor(COLOR_RING);
        mRingPaint.setStyle(Paint.Style.FILL);
        mGlowPaint.setColor(COLOR_RING_BORDER);
        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setStrokeWidth(2f);
        mRingTextPaint.setTextSize(28f);
        mRingTextPaint.setTextAlign(Paint.Align.CENTER);
        mRingTextPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        mTickPaint.setColor(COLOR_TICK);
        mTickPaint.setStyle(Paint.Style.STROKE);
        mTickPaint.setStrokeWidth(2f);
        mApparentArrowPaint.setColor(COLOR_APPARENT);
        mApparentArrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mApparentArrowPaint.setStrokeWidth(3f);
        mApparentArrowPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrueArrowPaint.setColor(COLOR_TRUE);
        mTrueArrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mTrueArrowPaint.setStrokeWidth(3f);
        mTrueArrowPaint.setStrokeCap(Paint.Cap.ROUND);
        mBoatPaint.setColor(COLOR_BOAT);
        mBoatPaint.setStyle(Paint.Style.STROKE);
        mBoatPaint.setStrokeWidth(3f);
        mBoatPaint.setStrokeCap(Paint.Cap.ROUND);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(32f);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mCx = w / 2f;
        mCy = h / 2f;
        // Increase padding significantly to prevent any clipping.
        // 0.20f means the radius will be 30% of the view size, leaving 20% margin on each side.
        float padding = Math.min(w, h) * 0.12f;
        mRadius = Math.min(mCx, mCy) - padding;
        mRingRect.set(mCx - mRadius, mCy - mRadius, mCx + mRadius, mCy + mRadius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mBgPaint);

        // Center the compass vertically to avoid top/bottom clipping issues.
        // Moving it up slightly (0.05) to leave room for labels at the bottom.
        float visualCenterY = mCy - mRadius * 0.15f;

        canvas.save();
        canvas.translate(mCx, visualCenterY);
        float compassRotation = (mMode == MODE_COMPASS) ? -mHeading : 0f;
        canvas.rotate(compassRotation);
        drawCompassRing(canvas);
        canvas.restore();

        canvas.save();
        canvas.translate(mCx, visualCenterY);
        drawWindArrows(canvas);
        canvas.restore();

        canvas.save();
        canvas.translate(mCx, visualCenterY);
        drawBoat(canvas);
        canvas.restore();

        drawSpeedLabels(canvas, visualCenterY);
    }

    private void drawCompassRing(Canvas canvas) {
        mRingPaint.setColor(COLOR_RING);
        canvas.drawCircle(0, 0, mRadius, mRingPaint);
        mGlowPaint.setColor(COLOR_RING_BORDER);
        mGlowPaint.setStrokeWidth(3f);
        canvas.drawCircle(0, 0, mRadius, mGlowPaint);
        float innerRadius = mRadius * 0.75f;
        mGlowPaint.setColor(Color.parseColor("#1A2540"));
        mGlowPaint.setStrokeWidth(2f);
        canvas.drawCircle(0, 0, innerRadius, mGlowPaint);

        for (int deg = 0; deg < 360; deg += 5) {
            float rad = (float) Math.toRadians(deg);
            float sinA = (float) Math.sin(rad), cosA = (float) Math.cos(rad);
            float outer = mRadius - 4f, inner;
            if (deg % 90 == 0) {
                inner = mRadius * 0.78f; mTickPaint.setStrokeWidth(4f); mTickPaint.setColor(COLOR_CARDINAL);
            } else if (deg % 45 == 0) {
                inner = mRadius * 0.82f; mTickPaint.setStrokeWidth(3f); mTickPaint.setColor(COLOR_RING_BORDER);
            } else if (deg % 10 == 0) {
                inner = mRadius * 0.86f; mTickPaint.setStrokeWidth(2f); mTickPaint.setColor(COLOR_TICK);
            } else {
                inner = mRadius * 0.90f; mTickPaint.setStrokeWidth(1f); mTickPaint.setColor(Color.parseColor("#253050"));
            }
            canvas.drawLine(inner * sinA, -inner * cosA, outer * sinA, -outer * cosA, mTickPaint);
        }

        String[] cardinals = {"N", "E", "S", "W"};
        int[] cardDeg = {0, 90, 180, 270};
        float labelR = mRadius * 0.66f;
        for (int i = 0; i < cardinals.length; i++) {
            float rad = (float) Math.toRadians(cardDeg[i]);
            float x = labelR * (float) Math.sin(rad), y = -labelR * (float) Math.cos(rad);
            mRingTextPaint.setColor(i == 0 ? COLOR_NORTH : COLOR_CARDINAL);
            mRingTextPaint.setTextSize(mRadius * 0.13f);
            canvas.drawText(cardinals[i], x, y + mRingTextPaint.getTextSize() / 3, mRingTextPaint);
        }
    }

    private void drawWindArrows(Canvas canvas) {
        float arrowLen = mRadius * 0.55f, headLen = mRadius * 0.12f;
        float apparentAngle = (mMode == MODE_BOAT) ? mAwa : mHeading + mAwa;
        float trueAngle = (mMode == MODE_BOAT) ? mTwa : mTwd;
        if (mShowTrueWind) drawArrow(canvas, trueAngle, arrowLen, headLen, mTrueArrowPaint, true);
        if (mShowApparentWind) drawArrow(canvas, apparentAngle, arrowLen * 0.9f, headLen, mApparentArrowPaint, false);
    }

    private void drawArrow(Canvas canvas, float angleDeg, float len, float headLen, Paint paint, boolean filled) {
        float rad = (float) Math.toRadians(angleDeg);
        float sinA = (float) Math.sin(rad), cosA = (float) Math.cos(rad);
        float tipX = sinA * len, tipY = -cosA * len;
        canvas.drawLine(0, 0, tipX, tipY, paint);
        float headAngle = (float) Math.toRadians(30);
        float hx1 = tipX - headLen * (float) (Math.sin(rad - headAngle)), hy1 = tipY + headLen * (float) (Math.cos(rad - headAngle));
        float hx2 = tipX - headLen * (float) (Math.sin(rad + headAngle)), hy2 = tipY + headLen * (float) (Math.cos(rad + headAngle));
        Path path = new Path();
        path.moveTo(tipX, tipY); path.lineTo(hx1, hy1); path.lineTo(hx2, hy2); path.close();
        paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
    }

    private void drawBoat(Canvas canvas) {
        float size = mRadius * 0.18f;
        Path hull = new Path();
        hull.moveTo(0, -size * 1.4f); hull.lineTo(size * 0.55f, size * 0.3f); hull.lineTo(size * 0.3f, size * 0.7f);
        hull.lineTo(-size * 0.3f, size * 0.7f); hull.lineTo(-size * 0.55f, size * 0.3f); hull.close();
        mBoatPaint.setColor(COLOR_BOAT); canvas.drawPath(hull, mBoatPaint);
        canvas.drawLine(0, -size * 0.6f, 0, size * 0.3f, mBoatPaint);
    }

    private void drawSpeedLabels(Canvas canvas, float visualCenterY) {
        // Position labels relative to the compass center, but near the bottom of the view.
        float cx = mCx;
        float boxY = visualCenterY + mRadius * 1.15f; 
        float spacing = mRadius * 1.5f; // Increased spacing as requested.

        String awsStr = mSpeedFormatter != null ? mSpeedFormatter.format(mAws) : String.format("%.1f", mAws);
        String twsStr = mSpeedFormatter != null ? mSpeedFormatter.format(mTws) : String.format("%.1f", mTws);
        if (mShowApparentWind) drawDataBox(canvas, cx - spacing * 0.5f, boxY, COLOR_APPARENT, "AWS", awsStr, String.format("%.0f°", mAwa));
        if (mShowTrueWind) drawDataBox(canvas, cx + spacing * 0.5f, boxY, COLOR_TRUE, "TWS", twsStr, String.format("%.0f°", mMode == MODE_COMPASS ? mTwd : mTwa));
    }

    private void drawDataBox(Canvas canvas, float cx, float cy, int color, String label, String speed, String angle) {
        float w = mRadius * 0.65f, h = mRadius * 0.55f;
        RectF box = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(160, 10, 18, 40));
        canvas.drawRoundRect(box, 12, 12, bgPaint);
        bgPaint.setColor(color); bgPaint.setStyle(Paint.Style.STROKE); bgPaint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(box, 12, 12, bgPaint);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextSize(mRadius * 0.09f);
        canvas.drawText(label, cx, cy - h * 0.32f, p);

        String[] parts = speed.split(" ");
        String val = parts[0], unit = parts.length > 1 ? parts[1] : "";
        p.setColor(Color.WHITE); p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        p.setTextSize(mRadius * 0.22f);
        if (unit.isEmpty()) {
            canvas.drawText(val, cx, cy + h * 0.05f, p);
        } else {
            float vw = p.measureText(val);
            Paint up = new Paint(p); up.setTextSize(mRadius * 0.08f); up.setColor(Color.parseColor("#A0C0E0"));
            float totalW = vw + up.measureText(unit) + 8f;
            canvas.drawText(val, cx - totalW/2 + vw/2, cy + h * 0.05f, p);
            canvas.drawText(unit, cx + totalW/2 - up.measureText(unit)/2, cy + h * 0.05f, up);
        }

        p.setTextSize(mRadius * 0.15f); p.setColor(Color.parseColor("#A0C0E0"));
        canvas.drawText(angle, cx, cy + h * 0.38f, p);
    }

    public void setMode(int mode) { mMode = mode; invalidate(); }
    public void setWindData(float aws, float awa, float tws, float twa, float twd, float heading) {
        mAws = aws; mAwa = awa; mTws = tws; mTwa = twa; mTwd = twd; mHeading = heading; invalidate();
    }
    public void setHeading(float heading) { mHeading = heading; invalidate(); }
    public void setShowApparentWind(boolean show) { mShowApparentWind = show; invalidate(); }
    public void setShowTrueWind(boolean show) { mShowTrueWind = show; invalidate(); }
    public void setSpeedFormatter(SpeedFormatter fmt) { mSpeedFormatter = fmt; invalidate(); }
    public int getMode() { return mMode; }
}
