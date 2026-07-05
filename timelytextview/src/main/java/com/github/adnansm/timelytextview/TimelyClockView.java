package com.github.adnansm.timelytextview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * A grouped timely clock readout. It draws all visible digits, colons and the
 * infinity symbol as one visual object so Thing gradients and alpha can span the
 * whole readout continuously.
 */
public class TimelyClockView extends View {

    public static final int MODE_AUTO_HIDE_HOUR = 0;
    public static final int MODE_FULL = 1;

    public static final int ORIENTATION_L_R = 0;
    public static final int ORIENTATION_R_L = 1;
    public static final int ORIENTATION_T_B = 2;
    public static final int ORIENTATION_B_T = 3;
    public static final int ORIENTATION_LT_RB = 4;
    public static final int ORIENTATION_RB_LT = 5;
    public static final int ORIENTATION_RT_LB = 6;
    public static final int ORIENTATION_LB_RT = 7;

    private static final String DEFAULT_STYLE = "poppins";
    private static final String[] LEVELS = {"h", "m", "s"};
    private static final int DIGIT_COUNT = 6;

    private static final float HOUR_WEIGHT_STROKE = 0.080f;
    private static final float MINUTE_WEIGHT_STROKE = 0.035f;
    private static final float SECOND_WEIGHT_STROKE = 0.000f;
    private static final float STENCIL_SECOND_TARGET_GAP = 0.32f;
    private static final float STENCIL_SECOND_MAX_TIGHTEN = 0.30f;

    private static final float ALPHA_START = 0.90f;
    private static final float ALPHA_END = 1.00f;
    private static final float GLOW_STROKE_FRACTION = 0.018f;
    private static final float GLOW_ALPHA = 0.32f;
    private static final float GLOW_BASE_MIX = 0.64f;
    private static final float GLOW_THING_MIX = 0.36f;

    private static final long ANIM_DURATION_MS = 300L;

    private static final Map<String, StyleData> CACHE = new HashMap<String, StyleData>();

    static final class Shape {
        float[][] outer;
        float[][][] holes;
    }

    static final class RawGlyph {
        float[][] outer;
        float[][][] holes;
    }

    static final class StyleData {
        int n, m;
        float advance;
        final RawGlyph[][] glyphs = new RawGlyph[3][10];
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF layerBounds = new RectF();
    private final DigitSlot[] slots = new DigitSlot[DIGIT_COUNT];

    private String styleName = DEFAULT_STYLE;
    private StyleData styleData;
    private boolean fillMode = true;
    private int mode = MODE_AUTO_HIDE_HOUR;
    private boolean infinite = false;
    private boolean showHour = true;
    private int[] lastDigits;

    private boolean inkGradient = false;
    private int inkStartColor = Color.WHITE;
    private int inkEndColor = Color.WHITE;
    private int inkOrientation = ORIENTATION_L_R;
    private int glowBaseColor = Color.WHITE;
    private float colonWidthFactor = 0.5f;

    public TimelyClockView(Context context) {
        super(context);
        init();
    }

    public TimelyClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimelyClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        for (int i = 0; i < DIGIT_COUNT; i++) {
            slots[i] = new DigitSlot();
        }
    }

    public void setStyleName(String style) {
        if (style == null || style.length() == 0) style = DEFAULT_STYLE;
        if (style.equals(styleName) && styleData != null) return;
        styleName = style;
        styleData = load(getContext(), styleName);
        rebuildCurrentShapes();
        requestLayout();
        invalidate();
    }

    public void setRenderMode(boolean fill) {
        fillMode = fill;
        invalidate();
    }

    public void setClockMode(int clockMode) {
        mode = clockMode;
        updateHourVisibility(lastVisibleMillis());
    }

    public void setColonWidthFactor(float factor) {
        float next = Math.max(0.36f, Math.min(0.5f, factor));
        if (Math.abs(colonWidthFactor - next) < 0.001f) return;
        colonWidthFactor = next;
        requestLayout();
        invalidate();
    }

    public void setHostDark(boolean hostDark) {
        glowBaseColor = hostDark ? Color.WHITE : Color.BLACK;
        invalidate();
    }

    public void setInkColor(int color) {
        inkGradient = false;
        inkStartColor = color;
        inkEndColor = color;
        invalidate();
    }

    public void setInkGradient(int startColor, int endColor, int orientation) {
        inkGradient = true;
        inkStartColor = startColor;
        inkEndColor = endColor;
        inkOrientation = orientation;
        invalidate();
    }

    public void setInfinite(boolean value) {
        if (infinite == value) return;
        infinite = value;
        requestLayout();
        invalidate();
    }

    public void setTimeMillis(long millis, boolean animate) {
        if (millis < 0) {
            setInfinite(true);
            return;
        }
        setInfinite(false);
        updateHourVisibility(millis);
        int[] digits = digitsForMillis(millis);
        if (animate && lastDigits != null) {
            animateDigits(lastDigits, digits, millis);
        } else {
            showDigits(digits, millis);
        }
    }

    public void animateDigits(int[] from, int[] to, long visibleMillis) {
        if (from == null || to == null || from.length < DIGIT_COUNT || to.length < DIGIT_COUNT) {
            return;
        }
        setInfinite(false);
        updateHourVisibility(visibleMillis);
        ensureLoaded();
        for (int i = 0; i < DIGIT_COUNT; i++) {
            animateSlot(i, from[i], to[i]);
        }
        lastDigits = copyDigits(to);
    }

    public void animateIn(long visibleMillis) {
        setInfinite(false);
        updateHourVisibility(visibleMillis);
        ensureLoaded();
        int[] digits = digitsForMillis(visibleMillis);
        for (int i = 0; i < DIGIT_COUNT; i++) {
            animateSlot(i, -1, digits[i]);
        }
        lastDigits = copyDigits(digits);
    }

    public void showDigits(int[] digits, long visibleMillis) {
        if (digits == null || digits.length < DIGIT_COUNT) return;
        setInfinite(false);
        updateHourVisibility(visibleMillis);
        ensureLoaded();
        for (int i = 0; i < DIGIT_COUNT; i++) {
            cancelSlot(i);
            slots[i].shape = buildPair(i, digits[i], digits[i])[1];
        }
        lastDigits = copyDigits(digits);
        invalidate();
    }

    private long lastVisibleMillis() {
        if (lastDigits == null) return 0L;
        long hours = lastDigits[0] * 10L + lastDigits[1];
        long minutes = lastDigits[2] * 10L + lastDigits[3];
        long seconds = lastDigits[4] * 10L + lastDigits[5];
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L;
    }

    private void updateHourVisibility(long millis) {
        boolean next = mode == MODE_FULL || millis >= 60L * 60L * 1000L;
        if (showHour != next) {
            showHour = next;
            requestLayout();
            invalidate();
        }
    }

    private void animateSlot(final int slotIndex, int from, int to) {
        cancelSlot(slotIndex);
        final Shape[] pair = buildPair(slotIndex, from, to);
        slots[slotIndex].shape = pair[0];
        if (from == to) {
            slots[slotIndex].shape = pair[1];
            invalidate();
            return;
        }
        final DigitSlot slot = slots[slotIndex];
        final ShapeEvaluator evaluator = new ShapeEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_DURATION_MS);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                slot.shape = evaluator.evaluate(t, pair[0], pair[1]);
                invalidate();
            }
        });
        slot.animator = animator;
        animator.start();
    }

    private void cancelSlot(int slotIndex) {
        ValueAnimator animator = slots[slotIndex].animator;
        if (animator != null) {
            animator.cancel();
            slots[slotIndex].animator = null;
        }
    }

    private void rebuildCurrentShapes() {
        if (lastDigits == null) return;
        ensureLoaded();
        for (int i = 0; i < DIGIT_COUNT; i++) {
            cancelSlot(i);
            slots[i].shape = buildPair(i, lastDigits[i], lastDigits[i])[1];
        }
    }

    private int[] digitsForMillis(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = Math.min(99L, totalSeconds / 3600L);
        long minutes = (totalSeconds / 60L) % 60L;
        long seconds = totalSeconds % 60L;
        return new int[] {
                (int) (hours / 10L), (int) (hours % 10L),
                (int) (minutes / 10L), (int) (minutes % 10L),
                (int) (seconds / 10L), (int) (seconds % 10L)
        };
    }

    private int[] copyDigits(int[] digits) {
        int[] copy = new int[DIGIT_COUNT];
        System.arraycopy(digits, 0, copy, 0, DIGIT_COUNT);
        return copy;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        ensureLoaded();
        int desiredHeight = defaultHeightPx();
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int height;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(desiredHeight, heightSize);
        } else {
            height = desiredHeight;
        }
        int desiredWidth = Math.round(widthUnits() * height) + getPaddingLeft() + getPaddingRight();
        int width = resolveSize(desiredWidth, widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    private int defaultHeightPx() {
        return Math.round(getResources().getDisplayMetrics().density * 72f);
    }

    private float widthUnits() {
        float advance = styleData != null ? styleData.advance : 0.72f;
        if (infinite) return 1.45f;
        float colon = advance * colonWidthFactor;
        return showHour
                ? advance * 6f + colon * 2f
                : advance * 4f + colon;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensureLoaded();
        if (styleData == null) return;

        int contentW = getWidth() - getPaddingLeft() - getPaddingRight();
        int contentH = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentW <= 0 || contentH <= 0) return;

        float units = widthUnits();
        float drawH = Math.min(contentH, contentW / units);
        float totalW = units * drawH;
        float left = getPaddingLeft() + (contentW - totalW) / 2f;
        float top = getPaddingTop() + (contentH - drawH) / 2f;
        float layerPad = drawH * 0.12f;
        layerBounds.set(left - layerPad, top, left + totalW + layerPad, top + drawH + layerPad);

        if (infinite) {
            drawInfinityLayer(canvas, left, top, totalW, drawH, true);
            drawInfinityLayer(canvas, left, top, totalW, drawH, false);
        } else {
            drawClockLayer(canvas, left, top, totalW, drawH, true);
            drawClockLayer(canvas, left, top, totalW, drawH, false);
        }
    }

    private void drawClockLayer(Canvas canvas, float left, float top, float totalW,
                                float contentH, boolean glow) {
        int save = canvas.saveLayer(layerBounds, null);
        configureInkPaint(glow, left, top, totalW, contentH);
        float cursor = 0f;
        int startSlot = showHour ? 0 : 2;
        float advance = styleData.advance * contentH;
        float colonW = advance * colonWidthFactor;
        float secondPairTighten = secondPairTightenUnits() * contentH;
        for (int i = startSlot; i < DIGIT_COUNT; i++) {
            float cx = left + cursor + advance / 2f;
            if (i == 5) cx -= secondPairTighten;
            drawDigit(i, canvas, cx, top, contentH, glow);
            cursor += advance;
            if ((i == 1 && showHour) || i == 3) {
                drawColon(canvas, left + cursor + colonW / 2f, top, contentH, glow);
                cursor += colonW;
            }
        }
        applyAlphaMask(canvas, left, top, totalW, contentH);
        canvas.restoreToCount(save);
    }

    private float secondPairTightenUnits() {
        if (!isStencilStyle() || styleData == null || lastDigits == null
                || lastDigits.length < DIGIT_COUNT) {
            return 0f;
        }
        int tens = lastDigits[4];
        int ones = lastDigits[5];
        if (tens < 0 || tens > 9 || ones < 0 || ones > 9) return 0f;
        RawGlyph left = styleData.glyphs[2][tens];
        RawGlyph right = styleData.glyphs[2][ones];
        if (left == null || right == null) return 0f;
        float currentGap = styleData.advance + glyphMinX(right) - glyphMaxX(left);
        float targetGap = styleData.advance * STENCIL_SECOND_TARGET_GAP;
        if (currentGap <= targetGap) return 0f;
        return Math.min(currentGap - targetGap, styleData.advance * STENCIL_SECOND_MAX_TIGHTEN);
    }

    private boolean isStencilStyle() {
        return "bigshouldersstencil".equals(styleName)
                || "sirinstencil".equals(styleName)
                || "allertastencil".equals(styleName)
                || "sairastencil".equals(styleName)
                || "stardosstencil".equals(styleName);
    }

    private static float glyphMinX(RawGlyph glyph) {
        return glyphBoundX(glyph, true);
    }

    private static float glyphMaxX(RawGlyph glyph) {
        return glyphBoundX(glyph, false);
    }

    private static float glyphBoundX(RawGlyph glyph, boolean min) {
        if (glyph == null) return 0f;
        float bound = min ? Float.MAX_VALUE : -Float.MAX_VALUE;
        bound = ringBoundX(glyph.outer, min, bound);
        if (glyph.holes != null) {
            for (float[][] hole : glyph.holes) bound = ringBoundX(hole, min, bound);
        }
        if (bound == Float.MAX_VALUE || bound == -Float.MAX_VALUE) return 0f;
        return bound;
    }

    private static float ringBoundX(float[][] ring, boolean min, float bound) {
        if (ring == null) return bound;
        for (float[] point : ring) {
            if (point == null || point.length == 0) continue;
            bound = min ? Math.min(bound, point[0]) : Math.max(bound, point[0]);
        }
        return bound;
    }

    private void drawInfinityLayer(Canvas canvas, float left, float top, float totalW,
                                   float contentH, boolean glow) {
        int save = canvas.saveLayer(layerBounds, null);
        configureInkPaint(glow, left, top, totalW, contentH);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(contentH * 1.05f);
        if (glow) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(contentH * GLOW_STROKE_FRACTION);
        } else if (fillMode) {
            paint.setStyle(Paint.Style.FILL);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, contentH * 0.035f));
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float x = left + totalW / 2f;
        float y = top + contentH / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("\u221e", x, y, paint);
        canvas.restoreToCount(save);
    }

    private void drawDigit(int slotIndex, Canvas canvas, float cx, float top,
                           float contentH, boolean glow) {
        Shape shape = slots[slotIndex].shape;
        if (shape == null || shape.outer == null) return;
        float glyphH = contentH * 0.80f;
        float oy = top + contentH * 0.86f - glyphH;
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        addRing(path, shape.outer, cx, oy, glyphH);
        if (shape.holes != null) {
            for (float[][] hole : shape.holes) addRing(path, hole, cx, oy, glyphH);
        }
        if (glow) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, glyphH * GLOW_STROKE_FRACTION));
        } else if (fillMode) {
            float weight = weightStroke(slotIndex);
            if (weight > 0f) {
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(glyphH * weight);
            } else {
                paint.setStyle(Paint.Style.FILL);
            }
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, glyphH * (0.03f + weightStroke(slotIndex))));
        }
        canvas.drawPath(path, paint);
    }

    private void drawColon(Canvas canvas, float cx, float top, float contentH, boolean glow) {
        float glyphH = contentH * 0.80f;
        float baseY = top + contentH * 0.86f;
        float radius = glyphH * 0.045f;
        if (glow) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, glyphH * GLOW_STROKE_FRACTION));
        } else {
            paint.setStyle(Paint.Style.FILL);
        }
        canvas.drawCircle(cx, baseY - glyphH * 0.46f, radius, paint);
        canvas.drawCircle(cx, baseY - glyphH * 0.20f, radius, paint);
    }

    private float weightStroke(int slotIndex) {
        if (slotIndex < 2) return HOUR_WEIGHT_STROKE;
        if (slotIndex < 4) return MINUTE_WEIGHT_STROKE;
        return SECOND_WEIGHT_STROKE;
    }

    private void configureInkPaint(boolean glow, float left, float top, float width, float height) {
        paint.setAntiAlias(true);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        int start = glow ? glowColor(inkStartColor) : stripAlpha(inkStartColor);
        int end = glow ? glowColor(inkEndColor) : stripAlpha(inkEndColor);
        int alpha = glow ? Math.round(GLOW_ALPHA * 255f) : 255;
        start = withAlpha(start, alpha);
        end = withAlpha(end, alpha);
        if (inkGradient || (stripAlpha(inkStartColor) != stripAlpha(inkEndColor))) {
            paint.setShader(gradient(start, end, left, top, width, height));
        } else {
            paint.setShader(null);
            paint.setColor(start);
        }
    }

    private Shader gradient(int start, int end, float left, float top, float width, float height) {
        float x0 = left, y0 = top, x1 = left + width, y1 = top;
        switch (inkOrientation) {
            case ORIENTATION_R_L:
                x0 = left + width; y0 = top; x1 = left; y1 = top; break;
            case ORIENTATION_T_B:
                x0 = left; y0 = top; x1 = left; y1 = top + height; break;
            case ORIENTATION_B_T:
                x0 = left; y0 = top + height; x1 = left; y1 = top; break;
            case ORIENTATION_LT_RB:
                x0 = left; y0 = top; x1 = left + width; y1 = top + height; break;
            case ORIENTATION_RB_LT:
                x0 = left + width; y0 = top + height; x1 = left; y1 = top; break;
            case ORIENTATION_RT_LB:
                x0 = left + width; y0 = top; x1 = left; y1 = top + height; break;
            case ORIENTATION_LB_RT:
                x0 = left; y0 = top + height; x1 = left + width; y1 = top; break;
            case ORIENTATION_L_R:
            default:
                break;
        }
        return new LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP);
    }

    private void applyAlphaMask(Canvas canvas, float left, float top, float width, float height) {
        int a0 = Math.round(ALPHA_START * 255f);
        int a1 = Math.round(ALPHA_END * 255f);
        LinearGradient mask = new LinearGradient(
                left, top, left + width, top,
                Color.argb(a0, 0, 0, 0),
                Color.argb(a1, 0, 0, 0),
                Shader.TileMode.CLAMP
        );
        maskPaint.setShader(mask);
        canvas.drawRect(layerBounds, maskPaint);
        maskPaint.setShader(null);
    }

    private int glowColor(int thingColor) {
        int base = stripAlpha(glowBaseColor);
        int thing = stripAlpha(thingColor);
        int r = Math.round(Color.red(base) * GLOW_BASE_MIX + Color.red(thing) * GLOW_THING_MIX);
        int g = Math.round(Color.green(base) * GLOW_BASE_MIX + Color.green(thing) * GLOW_THING_MIX);
        int b = Math.round(Color.blue(base) * GLOW_BASE_MIX + Color.blue(thing) * GLOW_THING_MIX);
        return Color.rgb(r, g, b);
    }

    private int stripAlpha(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void addRing(Path out, float[][] ring, float ox, float oy, float scale) {
        if (ring == null || ring.length == 0) return;
        out.moveTo(ox + ring[0][0] * scale, oy + ring[0][1] * scale);
        for (int i = 1; i < ring.length; i++) {
            out.lineTo(ox + ring[i][0] * scale, oy + ring[i][1] * scale);
        }
        out.close();
    }

    private Shape[] buildPair(int slotIndex, int from, int to) {
        RawGlyph a = getRaw(slotIndex, from);
        RawGlyph b = getRaw(slotIndex, to);
        Shape sa = new Shape();
        Shape sb = new Shape();
        sa.outer = a.outer;
        sb.outer = bestOffset(a.outer, b.outer);
        int ka = a.holes.length, kb = b.holes.length, k = Math.max(ka, kb);
        sa.holes = new float[k][][];
        sb.holes = new float[k][][];
        for (int i = 0; i < k; i++) {
            float[][] ha = i < ka ? a.holes[i] : null;
            float[][] hb = i < kb ? b.holes[i] : null;
            if (ha != null && hb != null) {
                sa.holes[i] = ha;
                sb.holes[i] = bestOffset(ha, hb);
            } else if (hb == null) {
                sa.holes[i] = ha;
                sb.holes[i] = seed(centroid(ha), ha.length);
            } else {
                sa.holes[i] = seed(centroid(hb), hb.length);
                sb.holes[i] = hb;
            }
        }
        return new Shape[] {sa, sb};
    }

    private RawGlyph getRaw(int slotIndex, int digit) {
        ensureLoaded();
        if (styleData == null || digit < 0 || digit > 9) {
            RawGlyph rg = new RawGlyph();
            int n = styleData != null ? styleData.n : 128;
            rg.outer = new float[n][2];
            for (int i = 0; i < n; i++) {
                rg.outer[i][0] = 0f;
                rg.outer[i][1] = 0.5f;
            }
            rg.holes = new float[0][][];
            return rg;
        }
        return styleData.glyphs[levelIndexForSlot(slotIndex)][digit];
    }

    private int levelIndexForSlot(int slotIndex) {
        if (slotIndex < 2) return 0;
        if (slotIndex < 4) return 1;
        return 2;
    }

    private static float[][] bestOffset(float[][] base, float[][] ring) {
        int n = ring.length;
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int s = 0; s < n; s++) {
            double distance = 0;
            for (int i = 0; i < n; i++) {
                float[] p = base[i];
                float[] q = ring[(i + s) % n];
                double dx = p[0] - q[0];
                double dy = p[1] - q[1];
                distance += dx * dx + dy * dy;
                if (distance >= bestDistance) break;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = s;
            }
        }
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = ring[(i + best) % n][0];
            out[i][1] = ring[(i + best) % n][1];
        }
        return out;
    }

    private static float[] centroid(float[][] points) {
        float sx = 0f, sy = 0f;
        for (float[] point : points) {
            sx += point[0];
            sy += point[1];
        }
        return new float[] {sx / points.length, sy / points.length};
    }

    private static float[][] seed(float[] center, int count) {
        float[][] out = new float[count][2];
        for (int i = 0; i < count; i++) {
            out[i][0] = center[0];
            out[i][1] = center[1];
        }
        return out;
    }

    static final class ShapeEvaluator {
        private Shape cache;

        Shape evaluate(float t, Shape a, Shape b) {
            if (cache == null || cache.outer == null || cache.outer.length != a.outer.length
                    || cache.holes == null || cache.holes.length != a.holes.length) {
                cache = new Shape();
                cache.outer = new float[a.outer.length][2];
                cache.holes = new float[a.holes.length][][];
                for (int k = 0; k < a.holes.length; k++) {
                    cache.holes[k] = new float[a.holes[k].length][2];
                }
            }
            for (int i = 0; i < a.outer.length; i++) {
                cache.outer[i][0] = a.outer[i][0] + t * (b.outer[i][0] - a.outer[i][0]);
                cache.outer[i][1] = a.outer[i][1] + t * (b.outer[i][1] - a.outer[i][1]);
            }
            for (int k = 0; k < a.holes.length; k++) {
                float[][] ha = a.holes[k], hb = b.holes[k], hc = cache.holes[k];
                for (int i = 0; i < ha.length; i++) {
                    hc[i][0] = ha[i][0] + t * (hb[i][0] - ha[i][0]);
                    hc[i][1] = ha[i][1] + t * (hb[i][1] - ha[i][1]);
                }
            }
            return cache;
        }
    }

    private void ensureLoaded() {
        if (styleData == null) styleData = load(getContext(), styleName);
    }

    private static StyleData load(Context ctx, String style) {
        StyleData data = CACHE.get(style);
        if (data != null) return data;
        try {
            InputStream is = ctx.getAssets().open("timely/" + style + ".json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                builder.append(buffer, 0, count);
            }
            reader.close();
            JSONObject root = new JSONObject(builder.toString());
            data = new StyleData();
            data.n = root.getInt("N");
            data.m = root.getInt("M");
            data.advance = (float) root.getDouble("advance");
            for (int level = 0; level < 3; level++) {
                JSONObject levelObj = root.getJSONObject(LEVELS[level]);
                for (int digit = 0; digit < 10; digit++) {
                    JSONObject glyphObj = levelObj.getJSONObject(String.valueOf(digit));
                    RawGlyph glyph = new RawGlyph();
                    glyph.outer = parseFlat(glyphObj.getJSONArray("outer"));
                    JSONArray holes = glyphObj.getJSONArray("holes");
                    glyph.holes = new float[holes.length()][][];
                    for (int h = 0; h < holes.length(); h++) {
                        glyph.holes[h] = parseFlat(holes.getJSONArray(h));
                    }
                    data.glyphs[level][digit] = glyph;
                }
            }
            CACHE.put(style, data);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return data;
    }

    private static float[][] parseFlat(JSONArray array) throws Exception {
        int n = array.length() / 2;
        float[][] points = new float[n][2];
        for (int i = 0; i < n; i++) {
            points[i][0] = (float) array.getDouble(i * 2);
            points[i][1] = (float) array.getDouble(i * 2 + 1);
        }
        return points;
    }

    private static final class DigitSlot {
        Shape shape;
        ValueAnimator animator;
    }
}
