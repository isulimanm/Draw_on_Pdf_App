package com.example.myapplication;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.graphics.path.PathIterator;
import androidx.graphics.path.PathSegment;

import com.github.barteksc.pdfviewer.PDFView;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reusable background flattener (VECTOR): append strokes to PDF content. */
public final class PdfInkFlattener {

    public interface Callback {
        void onSuccess(File outFile);
        void onError(Throwable t);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Vector flatten. Text stays selectable in the output PDF.
     * NOTE: We snapshot geometry + strokes on the caller (UI) thread.
     */
    public void flattenAsync(
            Context ctx,
            Uri inputUri,
            PDFView pdfView,
            InkManager ink,
            float spacingPx,
            float unusedScale,   // kept for API compatibility; ignored for vector
            Callback cb) {

        // Build geometry and snapshot strokes before jumping to background
        final PdfGeometry geom = new PdfGeometry(pdfView, spacingPx);
        final List<InkStroke> snapshot = new ArrayList<>(ink.getAll());

        executor.execute(() -> {
            File out = new File(ctx.getCacheDir(), "merged-" + System.currentTimeMillis() + ".pdf");
            try {
                commitInkByVectorAppend(ctx, inputUri, out, geom, snapshot);
                main.post(() -> cb.onSuccess(out));
            } catch (Throwable t) {
                main.post(() -> cb.onError(t));
            }
        });
    }

    /** Writes each stroke as vector path operators; keeps original text layer intact. */
    private static void commitInkByVectorAppend(
            Context ctx, Uri inputUri, File outFile,
            PdfGeometry geom, List<InkStroke> strokes) throws IOException {

        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument doc = PDDocument.load(is)) {

            final int pageCount = doc.getNumberOfPages();

            // Group strokes by page
            @SuppressWarnings("unchecked")
            List<InkStroke>[] byPage = new List[pageCount];
            for (int i = 0; i < pageCount; i++) byPage[i] = new ArrayList<>();
            for (InkStroke s : strokes) {
                if (s.pageIndex >= 0 && s.pageIndex < pageCount) byPage[s.pageIndex].add(s);
            }

            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getCropBox(); // PDF points
                float pdfW = box.getWidth();
                float pdfH = box.getHeight();

                // Map page-local coords (top-left origin) → PDF user space (bottom-left origin)
                float baseW = geom.pageWidth[i];
                float baseH = geom.pageHeight[i];
                final float sx = pdfW / baseW;
                final float sy = pdfH / baseH;

                try (PDPageContentStream cs =
                             new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {

                    cs.setLineCapStyle(1);   // round
                    cs.setLineJoinStyle(1);  // round

                    for (InkStroke s : byPage[i]) {
                        int color = s.paint.getColor();
                        cs.setStrokingColor(Color.red(color), Color.green(color), Color.blue(color));
                        cs.setLineWidth(s.paint.getStrokeWidth() * sx);

                        // Transform Android Path to PDF coordinates: scale, flip Y, translate up
                        android.graphics.Matrix m = new android.graphics.Matrix();
                        m.postScale(sx, -sy);
                        m.postTranslate(0, pdfH);

                        android.graphics.Path pdfPath = new android.graphics.Path();
                        s.path.transform(m, pdfPath);

                        // Iterate segments; approximate conics as quadratics for portability
                        PathIterator it = new PathIterator(
                                pdfPath,
                                PathIterator.ConicEvaluation.AsQuadratics,
                                0.25f /* tolerance */
                        );

                        float[] c = new float[8];
                        float lastX = 0f, lastY = 0f;
                        boolean haveLast = false;

                        while (it.hasNext()) {
                            PathSegment.Type t = it.next(c);
                            switch (t) {
                                case Move:
                                    cs.moveTo(c[0], c[1]);
                                    lastX = c[0]; lastY = c[1]; haveLast = true;
                                    break;
                                case Line:
                                    cs.lineTo(c[0], c[1]);
                                    lastX = c[0]; lastY = c[1];
                                    break;
                                case Quadratic:
                                    // Convert quadratic -> cubic
                                    if (!haveLast) { cs.moveTo(c[2], c[3]); lastX = c[2]; lastY = c[3]; break; }
                                    float c1x = lastX + (2f/3f) * (c[0] - lastX);
                                    float c1y = lastY + (2f/3f) * (c[1] - lastY);
                                    float c2x = c[2] + (2f/3f) * (c[0] - c[2]);
                                    float c2y = c[3] + (2f/3f) * (c[1] - c[3]);
                                    cs.curveTo(c1x, c1y, c2x, c2y, c[2], c[3]);
                                    lastX = c[2]; lastY = c[3];
                                    break;
                                case Cubic:
                                    cs.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                                    lastX = c[4]; lastY = c[5];
                                    break;
                                case Close:
                                    cs.closePath();
                                    break;
                            }
                        }
                        cs.stroke();
                    }
                }
            }
            doc.save(outFile);
        }
    }
}
