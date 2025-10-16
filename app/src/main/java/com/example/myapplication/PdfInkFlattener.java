package com.example.myapplication;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.github.barteksc.pdfviewer.PDFView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reusable background flattener: merges InkManager content into a NEW PDF file. */
public final class PdfInkFlattener {

    public interface Callback {
        void onSuccess(File outFile);
        void onError(Throwable t);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void flattenAsync(
            Context ctx,
            Uri inputUri,
            PDFView pdfView,
            InkManager ink,
            float spacingPx,
            float scale,              // e.g., 1.5f (quality vs. speed/memory)
            Callback cb) {

        executor.execute(() -> {
            File out = new File(ctx.getCacheDir(), "merged-" + System.currentTimeMillis() + ".pdf");
            try {
                commitInkByRasterFlatten(ctx, inputUri, pdfView, ink, spacingPx, scale, out);
                main.post(() -> cb.onSuccess(out));
            } catch (Throwable t) {
                main.post(() -> cb.onError(t));
            }
        });
    }

    private static void commitInkByRasterFlatten(
            Context ctx,
            Uri inputUri,
            PDFView pdfView,
            InkManager ink,
            float spacingPx,
            float scale,
            File outFile) throws IOException {

        // Scale guard
        final float SCALE = Math.max(1.0f, Math.min(2.0f, scale));

        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(inputUri, "r");
             PdfRenderer renderer = new PdfRenderer(pfd);
             ) {
            PdfDocument out = new PdfDocument();
            PdfGeometry geom = new PdfGeometry(pdfView, spacingPx);
            int pageCount = renderer.getPageCount();

            for (int i = 0; i < pageCount; i++) {
                PdfRenderer.Page src = renderer.openPage(i);

                float baseW = geom.pageWidth[i];
                float baseH = geom.pageHeight[i];

                int bmpW = Math.max(1, Math.round(baseW * SCALE));
                int bmpH = Math.max(1, Math.round(baseH * SCALE));

                // ARGB_8888 gives best line quality; if emulator OOMs, drop to RGB_565
                android.graphics.Bitmap pageBmp = android.graphics.Bitmap
                        .createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888);

                // Render original page
                src.render(pageBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                src.close();

                // Draw ink (page-local -> bitmap)
                android.graphics.Canvas bc = new android.graphics.Canvas(pageBmp);
                float sx = bmpW / baseW;
                float sy = bmpH / baseH;
                Matrix m = new Matrix();
                m.setScale(sx, sy);

                for (InkStroke s : ink.getStrokesForPage(i)) {
                    Path p = new Path();
                    s.path.transform(m, p);
                    Paint paint = new Paint(s.paint);
                    paint.setAntiAlias(true);
                    paint.setStrokeWidth(s.paint.getStrokeWidth() * sx);
                    bc.drawPath(p, paint);
                }

                // Write to new PDF page
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                        Math.max(1, Math.round(baseW)),
                        Math.max(1, Math.round(baseH)),
                        i + 1).create();

                PdfDocument.Page page = out.startPage(info);
                android.graphics.Canvas c = page.getCanvas();
                Rect srcRect = new Rect(0, 0, bmpW, bmpH);
                RectF dstRect = new RectF(0, 0, info.getPageWidth(), info.getPageHeight());
                c.drawBitmap(pageBmp, srcRect, dstRect, null);
                out.finishPage(page);

                pageBmp.recycle();
            }

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                out.writeTo(fos);
            }
        }
    }
}
