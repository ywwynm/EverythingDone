package com.ywwynm.everythingdone.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by ywwynm on 2015/9/23.
 * Utils for Bitmap
 */
public class BitmapUtil {

    public static final String TAG = "BitmapUtil";

    private BitmapUtil() {}

    private static int calculateInSampleSize(int oWidth, int oHeight, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (oHeight > reqWidth || oWidth > reqHeight) {
            final int halfHeight = oHeight / 2;
            final int halfWidth = oWidth / 2;
            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static Bitmap createLayeredBitmap(Drawable d, int color) {
        return createLayeredBitmap(d,
                com.ywwynm.everythingdone.model.ThingBackground.pure(color));
    }

    /**
     * Phase 8: gradient-aware {@link #createLayeredBitmap(Drawable, int)}. PURE
     * stays a flat {@link ColorDrawable} layer; GRADIENT uses a
     * {@link android.graphics.drawable.GradientDrawable} with the bg's stops
     * and orientation so the rendered bitmap carries the gradient. Foreground
     * drawable {@code d} layers on top unchanged.
     *
     * <p>Used by {@code SystemNotificationUtil.extendWearable} (and any other
     * caller that needs to bake an accent into a fixed-size bitmap — notably
     * RemoteViews paths that can't accept a Shader).
     */
    public static Bitmap createLayeredBitmap(
            Drawable d, com.ywwynm.everythingdone.model.ThingBackground bg) {
        Drawable background;
        if (bg == null || bg.mode == com.ywwynm.everythingdone.model.ThingBackground.Mode.PURE) {
            background = new ColorDrawable(bg == null ? 0 : bg.color);
        } else {
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setColors(new int[] { bg.color, bg.endColor });
            gd.setOrientation(toAndroidOrientation(bg.orientation));
            background = gd;
        }

        LayerDrawable lb = new LayerDrawable(new Drawable[] { background, d });

        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        lb.setBounds(0, 0, w, h);
        lb.draw(new Canvas(bm));

        return bm;
    }

    private static android.graphics.drawable.GradientDrawable.Orientation
            toAndroidOrientation(com.ywwynm.everythingdone.model.ThingBackground.Orientation o) {
        switch (o) {
            case L_R:   return android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT;
            case T_B:   return android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM;
            case LT_RB: return android.graphics.drawable.GradientDrawable.Orientation.TL_BR;
            case RT_LB: return android.graphics.drawable.GradientDrawable.Orientation.TR_BL;
            case LB_RT: return android.graphics.drawable.GradientDrawable.Orientation.BL_TR;
            case RB_LT: return android.graphics.drawable.GradientDrawable.Orientation.BR_TL;
            case R_L:   return android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT;
            case B_T:   return android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP;
        }
        return android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT;
    }

    public static Bitmap createScaledBitmap(Bitmap src, int reqWidth, int reqHeight, boolean inside) {
        if (src == null) {
            return null;
        }

        int oWidth  = src.getWidth();
        int oHeight = src.getHeight();

        Bitmap dst;
        if (inside && oWidth <= reqWidth && oHeight <= reqHeight) {
            dst = src;
        } else {
            float fW = (float) oWidth  / reqWidth;
            float fH = (float) oHeight / reqHeight;
            int maintainedSide;
            if (inside) {
                maintainedSide = fW >= fH ? oWidth : oHeight;
            } else {
                maintainedSide = fW <= fH ? oWidth : oHeight;
            }
            if (maintainedSide == oWidth) {
                int height = oHeight * reqWidth / oWidth;
                dst = Bitmap.createScaledBitmap(src, reqWidth, height, !inside);
            } else {
                int width = oWidth * reqHeight / oHeight;
                dst = Bitmap.createScaledBitmap(src, width, reqHeight, !inside);
            }
        }
        if (src != dst) {
            src.recycle();
        }
        return dst;
    }

    public static Bitmap createCroppedBitmap(Bitmap src, int reqWidth, int reqHeight) {
        Bitmap scaledBm = createScaledBitmap(src, reqWidth, reqHeight, false);

        int x = 0, y = 0;
        int oWidth  = scaledBm.getWidth();
        int oHeight = scaledBm.getHeight();

        if (reqWidth < oWidth) {
            x = (oWidth - reqWidth) / 2;
        }
        if (reqHeight < oHeight) {
            y = (oHeight - reqHeight) / 2;
        }

        Bitmap croppedBm = Bitmap.createBitmap(scaledBm, x, y, reqWidth, reqHeight);
        if (scaledBm != croppedBm) {
            scaledBm.recycle();
        }
        return croppedBm;
    }

    public static Bitmap decodeFileWithRequiredSize(String pathName, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(pathName, options);

        int oWidth  = options.outWidth;
        int oHeight = options.outHeight;

        if (oWidth == 0 || oHeight == 0) {
            return null;
        }

        options.inSampleSize = calculateInSampleSize(oWidth, oHeight, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        Bitmap src = BitmapFactory.decodeFile(pathName, options);
        src = tryToGetRotatedBitmap(src, pathName);

        return createCroppedBitmap(src, reqWidth, reqHeight);
    }

    public static Bitmap decodeFileFitsSize(String pathName, int fWidth, int fHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(pathName, options);

        Bitmap src;
        int oWidth  = options.outWidth;
        int oHeight = options.outHeight;

        if (oWidth >= fWidth && oHeight >= fHeight) {
            options.inSampleSize = calculateInSampleSize(oWidth, oHeight, fWidth, fHeight);
            options.inJustDecodeBounds = false;
            src = BitmapFactory.decodeFile(pathName, options);
        } else {
            src = BitmapFactory.decodeFile(pathName);
        }
        src = tryToGetRotatedBitmap(src, pathName);

        return createScaledBitmap(src, fWidth, fHeight, true);
    }

    public static Bitmap tryToGetRotatedBitmap(Bitmap src, String pathName) {
        try {
            ExifInterface exif = new ExifInterface(pathName);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                matrix.postRotate(90);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                matrix.postRotate(180);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                matrix.postRotate(270);
            } else {
                return src;
            }
            Bitmap ret = Bitmap.createBitmap(src, 0, 0, src.getWidth(),
                    src.getHeight(), matrix, true);
            if (ret != src) {
                src.recycle();
            }
            return ret;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return src;
    }

    public static File saveBitmapToStorage(String parentPath, String name, Bitmap bitmap) {
        File file = FileUtil.createFile(parentPath, name);
        if (file == null) {
            return null;
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            FileUtil.closeStream(fos);
        }
        return file;
    }
}
