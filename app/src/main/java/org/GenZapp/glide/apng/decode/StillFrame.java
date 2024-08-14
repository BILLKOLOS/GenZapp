/*
 * Copyright 2019 Zhou Pengfei
 * SPDX-License-Identifier: Apache-2.0
 */

package org.GenZapp.glide.apng.decode;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import org.GenZapp.glide.apng.io.APNGReader;
import org.GenZapp.glide.apng.io.APNGWriter;
import org.GenZapp.glide.common.decode.Frame;

import java.io.IOException;

/**
 * @Description: APNG4Android
 * @Author: pengfei.zhou
 * @CreateDate: 2019-05-13
 */
public class StillFrame extends Frame<APNGReader, APNGWriter> {

    public StillFrame(APNGReader reader) {
        super(reader);
    }

    @Override
    public Bitmap draw(Canvas canvas, Paint paint, int sampleSize, Bitmap reusedBitmap, APNGWriter writer) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        options.inMutable = true;
        options.inBitmap = reusedBitmap;
        Bitmap bitmap = null;
        try {
            reader.reset();
            bitmap = BitmapFactory.decodeStream(reader.toInputStream(), null, options);
            assert bitmap != null;
            paint.setXfermode(null);
            canvas.drawBitmap(bitmap, 0, 0, paint);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }
}
