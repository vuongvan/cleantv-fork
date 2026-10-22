package dev.ntsv.cleantv;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Turns a picture the owner picked (any image the file picker can open) into the launcher's
 * wallpaper.jpg: decoded at reduced size, rotated by its EXIF tag, longest side at most 1920 px.
 * A TV has little memory, and the home screen reads this file at every start.
 */
final class WallpaperImporter {
    private static final int MAX_SIDE = 1920;
    private static final int MIN_SIDE = 1080;

    private WallpaperImporter() {
    }

    /** Blocking: call from a background thread. Returns false (and leaves any old wallpaper) on failure. */
    static boolean importImage(Context ctx, Uri source, File dest) {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try {
            ContentResolver cr = ctx.getContentResolver();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(source)) {
                if (in == null) return false;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            int shortSide = Math.min(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longSide / (sample * 2) >= MAX_SIDE && shortSide / (sample * 2) >= MIN_SIDE) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(source)) {
                if (in == null) return false;
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return false;

            int rotation = exifRotation(cr, source);
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                bmp = rotated;
            }
            int big = Math.max(bmp.getWidth(), bmp.getHeight());
            if (big > MAX_SIDE) {
                float k = MAX_SIDE / (float) big;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * k),
                        Math.round(bmp.getHeight() * k), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) return false;
            } finally {
                bmp.recycle();
            }
            return tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest));
        } catch (Exception | OutOfMemoryError e) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static int exifRotation(ContentResolver cr, Uri source) {
        try (InputStream in = cr.openInputStream(source)) {
            if (in == null) return 0;
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
        }
        return 0;
    }
}
