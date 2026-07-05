/*
Copyright 2014 Adnan A M.
Rewritten 2026 for filled-outline glyph morphing (see
docs/features/timely-digit-typography/). Each digit is loaded from a generated
JSON asset as an outer contour plus up to two hole contours; a digit change
morphs by linearly interpolating matched points, with best-rotation-offset
correspondence and zero-area hole seeding so counters open/close in place.

Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.github.adnansm.timelytextview;

import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Property;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class TimelyView extends View {
    private static final String DEFAULT_STYLE = "poppins";
    private static final String[] LEVELS = {"h", "m", "s"};

    // ---- animated value: an outer ring + K hole rings (matched structure) ----
    static final class Shape {
        float[][] outer;      // [N][2]
        float[][][] holes;    // [K][M][2]
    }

    static final class RawGlyph {
        float[][] outer;
        float[][][] holes;    // K = 0..2
    }

    static final class StyleData {
        int n, m;
        float advance;
        final RawGlyph[][] glyphs = new RawGlyph[3][10]; // [levelIdx][digit]
    }

    private static final Map<String, StyleData> CACHE = new HashMap<>();

    private static final Property<TimelyView, Shape> SHAPE_PROPERTY =
            new Property<TimelyView, Shape>(Shape.class, "shape") {
                @Override public Shape get(TimelyView v) { return v.shape; }
                @Override public void set(TimelyView v, Shape s) { v.setShape(s); }
            };

    private Paint mPaint;
    private Path mPath;
    private Shape shape;
    private int textColor;
    private float lineWidth;
    private boolean fill = true;
    private float scale = 1f;
    private float weightStroke = 0f;   // synthesized extra boldness (fraction of glyph height)
    private String styleName = DEFAULT_STYLE;
    private int levelIdx = 1;            // default minute/Regular
    private StyleData styleData;

    public TimelyView(Context context) {
        super(context);
        init();
    }

    public TimelyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.TimelyView);
        textColor = ta.getColor(R.styleable.TimelyView_textColor, Color.BLACK);
        lineWidth = ta.getFloat(R.styleable.TimelyView_lineWidth, 10f);
        ta.recycle();
        init();
    }

    public TimelyView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setColor(textColor);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPath = new Path();
    }

    /** Choose the digit style (asset name) and weight level ("h"/"m"/"s"). */
    public void setStyle(String style, String level) {
        this.styleName = style;
        this.levelIdx = levelIndex(level);
        this.styleData = load(getContext(), style);
        invalidate();
    }

    /** Set only the weight level for the current style. */
    public void setWeightLevel(String level) {
        this.levelIdx = levelIndex(level);
        invalidate();
    }

    public Shape getShape() { return shape; }

    public void setShape(Shape s) {
        this.shape = s;
        invalidate();
    }

    public ObjectAnimator animate(int start, int end) {
        ensureLoaded();
        Shape[] pair = buildPair(start, end);
        return ObjectAnimator.ofObject(this, SHAPE_PROPERTY, new ShapeEvaluator(), pair[0], pair[1]);
    }

    public ObjectAnimator animate(int end) {
        return animate(-1, end);
    }

    /** Statically show a single digit (no animation) — used by previews. */
    public void showDigit(int d) {
        ensureLoaded();
        Shape[] pair = buildPair(d, d);
        setShape(pair[0]);
    }

    /** Fill (solid) vs outline (stroke the contours). */
    public void setRenderMode(boolean fillMode) {
        this.fill = fillMode;
        invalidate();
    }

    /** Per-unit size factor — kept at 1.0 (h/m/s stay the same size). */
    public void setScale(float s) {
        this.scale = s;
        invalidate();
    }

    public float getScale() {
        return scale;
    }

    /**
     * Synthesized extra boldness for this unit, as a fraction of glyph height:
     * hour thickest, second thinnest. This is the primary h/m/s cue and works for
     * every font (including single-weight ones), at identical digit size.
     */
    public void setWeightStroke(float w) {
        this.weightStroke = w;
        invalidate();
    }

    /**
     * Render a whole "01:29:36" readout for one style/render-mode into a bitmap,
     * with the hour/minute/second weight + opacity ladder. Used by the chooser.
     */
    public static Bitmap renderClock(Context ctx, String style, boolean fillMode,
                                     int color, int wPx, int hPx) {
        return renderClock(ctx, style, fillMode, color, color, wPx, hPx);
    }

    /**
     * Render a preview readout with one continuous left-to-right colour gradient,
     * then apply the shared 90% -> 100% positional alpha mask used by TimelyClockView.
     */
    public static Bitmap renderClock(Context ctx, String style, boolean fillMode,
                                     int startColor, int endColor, int wPx, int hPx) {
        StyleData sd = load(ctx, style);
        Bitmap bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888);
        if (sd == null) return bmp;
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);

        int[] digits = {0, 1, 2, 9, 3, 6};
        int[] levels = {0, 0, 1, 1, 2, 2};        // h,h,m,m,s,s
        float[] sizeF = {1f, 1f, 1f, 1f, 1f, 1f};  // same size; hierarchy from stroke weight
        float[] wstroke = {0.08f, 0.08f, 0.035f, 0.035f, 0f, 0f};
        boolean[] colonAfter = {false, true, false, true, false, false};
        float colonW = sd.advance * 0.5f;

        float total = 0f;
        for (int i = 0; i < 6; i++) {
            total += sd.advance * sizeF[i];
            if (colonAfter[i]) total += colonW;
        }
        float s = Math.min(wPx * 0.94f / total, hPx * 0.60f);
        float baseY = hPx * 0.80f;
        float startX = (wPx - total * s) / 2f;
        float endX = startX + total * s;

        Shader colorShader = new LinearGradient(
                startX, 0f, endX, 0f,
                Color.rgb(Color.red(startColor), Color.green(startColor), Color.blue(startColor)),
                Color.rgb(Color.red(endColor), Color.green(endColor), Color.blue(endColor)),
                Shader.TileMode.CLAMP);
        Shader alphaShader = new LinearGradient(
                startX, 0f, endX, 0f,
                Color.argb(Math.round(Color.alpha(startColor) * 0.90f), 0, 0, 0),
                Color.argb(Math.round(Color.alpha(endColor) * 1.00f), 0, 0, 0),
                Shader.TileMode.CLAMP);

        Path path = new Path();
        float cursor = 0f;
        int layer = canvas.saveLayer(0f, 0f, wPx, hPx, null);
        p.setShader(colorShader);
        p.setAlpha(255);
        for (int i = 0; i < 6; i++) {
            RawGlyph g = sd.glyphs[levels[i]][digits[i]];
            float cellW = sd.advance * sizeF[i];
            float gS = s * sizeF[i];
            float ox = startX + (cursor + cellW / 2f) * s;
            float oyTop = baseY - gS;
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            addRingTo(path, g.outer, ox, oyTop, gS);
            for (float[][] hole : g.holes) addRingTo(path, hole, ox, oyTop, gS);
            if (fillMode) {
                if (wstroke[i] > 0f) {
                    p.setStyle(Paint.Style.FILL_AND_STROKE);
                    p.setStrokeWidth(gS * wstroke[i]);
                } else {
                    p.setStyle(Paint.Style.FILL);
                }
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, gS * (0.03f + wstroke[i])));
            }
            canvas.drawPath(path, p);
            cursor += cellW;
            if (colonAfter[i]) {
                float cx = startX + (cursor + colonW / 2f) * s;
                p.setStyle(Paint.Style.FILL);
                float r = s * 0.045f;
                canvas.drawCircle(cx, baseY - s * 0.46f, r, p);
                canvas.drawCircle(cx, baseY - s * 0.20f, r, p);
                cursor += colonW;
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(alphaShader);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawRect(0f, 0f, wPx, hPx, p);
        p.setXfermode(null);
        p.setShader(null);
        canvas.restoreToCount(layer);
        return bmp;
    }

    private static void addRingTo(Path path, float[][] r, float ox, float oy, float s) {
        if (r == null || r.length == 0) return;
        path.moveTo(ox + r[0][0] * s, oy + r[0][1] * s);
        for (int i = 1; i < r.length; i++) path.lineTo(ox + r[i][0] * s, oy + r[i][1] * s);
        path.close();
    }

    // -------- morph construction --------

    private Shape[] buildPair(int from, int to) {
        RawGlyph a = getRaw(from);
        RawGlyph b = getRaw(to);
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
            } else if (hb == null) {           // b lacks this counter -> shrink to a point
                sa.holes[i] = ha;
                sb.holes[i] = seed(centroid(ha), ha.length);
            } else {                           // a lacks this counter -> grow from a point
                sa.holes[i] = seed(centroid(hb), hb.length);
                sb.holes[i] = hb;
            }
        }
        return new Shape[]{sa, sb};
    }

    private RawGlyph getRaw(int digit) {
        if (digit < 0 || styleData == null) {
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
        return styleData.glyphs[levelIdx][digit];
    }

    private static float[][] bestOffset(float[][] base, float[][] ring) {
        int n = ring.length;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int s = 0; s < n; s++) {
            double d = 0;
            for (int i = 0; i < n; i++) {
                float[] p = base[i];
                float[] q = ring[(i + s) % n];
                double dx = p[0] - q[0], dy = p[1] - q[1];
                d += dx * dx + dy * dy;
                if (d >= bd) break;
            }
            if (d < bd) { bd = d; best = s; }
        }
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = ring[(i + best) % n][0];
            out[i][1] = ring[(i + best) % n][1];
        }
        return out;
    }

    private static float[] centroid(float[][] p) {
        float sx = 0, sy = 0;
        for (float[] q : p) { sx += q[0]; sy += q[1]; }
        return new float[]{sx / p.length, sy / p.length};
    }

    private static float[][] seed(float[] c, int m) {
        float[][] o = new float[m][2];
        for (int i = 0; i < m; i++) { o[i][0] = c[0]; o[i][1] = c[1]; }
        return o;
    }

    static final class ShapeEvaluator implements TypeEvaluator<Shape> {
        private Shape cache;

        @Override public Shape evaluate(float t, Shape a, Shape b) {
            if (cache == null || cache.outer == null || cache.outer.length != a.outer.length
                    || cache.holes == null || cache.holes.length != a.holes.length) {
                cache = new Shape();
                cache.outer = new float[a.outer.length][2];
                cache.holes = new float[a.holes.length][][];
                for (int k = 0; k < a.holes.length; k++) cache.holes[k] = new float[a.holes[k].length][2];
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

    // -------- rendering --------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (shape == null || shape.outer == null) return;
        int w = getWidth(), h = getHeight();
        float glyphH = h * 0.80f * scale;
        float ox = w / 2f;
        float oy = h * 0.86f - glyphH;   // baseline anchored so h/m/s share a baseline
        mPath.reset();
        mPath.setFillType(Path.FillType.EVEN_ODD);
        addRing(shape.outer, ox, oy, glyphH);
        if (shape.holes != null) {
            for (float[][] hole : shape.holes) addRing(hole, ox, oy, glyphH);
        }
        if (fill) {
            if (weightStroke > 0f) {
                mPaint.setStyle(Paint.Style.FILL_AND_STROKE);
                mPaint.setStrokeWidth(glyphH * weightStroke);
            } else {
                mPaint.setStyle(Paint.Style.FILL);
            }
        } else {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(2f, glyphH * (0.03f + weightStroke)));
        }
        canvas.drawPath(mPath, mPaint);
    }

    private void addRing(float[][] r, float ox, float oy, float s) {
        if (r == null || r.length == 0) return;
        mPath.moveTo(ox + r[0][0] * s, oy + r[0][1] * s);
        for (int i = 1; i < r.length; i++) mPath.lineTo(ox + r[i][0] * s, oy + r[i][1] * s);
        mPath.close();
    }

    /** Tabular advance (relative to the digit box height) for the current style. */
    public float getAdvance() {
        ensureLoaded();
        return styleData != null ? styleData.advance : 0.72f;
    }

    // -------- asset loading --------

    private void ensureLoaded() {
        if (styleData == null) styleData = load(getContext(), styleName);
    }

    private static int levelIndex(String lvl) {
        if ("h".equals(lvl)) return 0;
        if ("s".equals(lvl)) return 2;
        return 1;
    }

    private static StyleData load(Context ctx, String style) {
        StyleData sd = CACHE.get(style);
        if (sd != null) return sd;
        try {
            InputStream is = ctx.getAssets().open("timely/" + style + ".json");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int k;
            while ((k = r.read(buf)) > 0) sb.append(buf, 0, k);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            sd = new StyleData();
            sd.n = o.getInt("N");
            sd.m = o.getInt("M");
            sd.advance = (float) o.getDouble("advance");
            for (int li = 0; li < 3; li++) {
                JSONObject lo = o.getJSONObject(LEVELS[li]);
                for (int d = 0; d < 10; d++) {
                    JSONObject g = lo.getJSONObject(String.valueOf(d));
                    RawGlyph rg = new RawGlyph();
                    rg.outer = parseFlat(g.getJSONArray("outer"));
                    JSONArray hs = g.getJSONArray("holes");
                    rg.holes = new float[hs.length()][][];
                    for (int h = 0; h < hs.length(); h++) rg.holes[h] = parseFlat(hs.getJSONArray(h));
                    sd.glyphs[li][d] = rg;
                }
            }
            CACHE.put(style, sd);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sd;
    }

    private static float[][] parseFlat(JSONArray a) throws Exception {
        int n = a.length() / 2;
        float[][] p = new float[n][2];
        for (int i = 0; i < n; i++) {
            p[i][0] = (float) a.getDouble(2 * i);
            p[i][1] = (float) a.getDouble(2 * i + 1);
        }
        return p;
    }
}
