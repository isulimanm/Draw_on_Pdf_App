package com.example.myapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.PDFView;

public class InkOverlayView extends View {

    private PDFView pdfView;
    private PdfGeometry geom;

    private final Path currentPath = new Path();
    private Paint currentPaint = defaultPaint();
    private int currentPage = 0;

    private final InkManager inkManager = new InkManager();

    public InkOverlayView(Context context) { super(context); }
    public InkOverlayView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public InkOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setPdfView(PDFView v) { this.pdfView = v; }
    public void setGeometry(PdfGeometry g) { this.geom = g; }
    public InkManager getInkManager() { return inkManager; }

    public void setStrokeColor(int color) { currentPaint.setColor(color); }
    public void setStrokeWidth(float w) { currentPaint.setStrokeWidth(w); }

    private Paint defaultPaint() {
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(8f);
        p.setColor(0xFFFF0000);
        return p;
    }

    private float contentX(float x) { return (x - pdfView.getCurrentXOffset()) / pdfView.getZoom(); }
    private float contentY(float y) { return (y - pdfView.getCurrentYOffset()) / pdfView.getZoom(); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled() || pdfView == null || geom == null) return false;

        // Eat touches so the PDFView doesn't pan/zoom
        getParent().requestDisallowInterceptTouchEvent(true);

        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            currentPage = pdfView.getCurrentPage();
        }

        float cx = contentX(e.getX());
        float cy = contentY(e.getY());
        // Convert content → page-local coordinates
        float px = cx - geom.pageLeft[currentPage];
        float py = cy - geom.pageTop[currentPage];

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                currentPath.reset();
                currentPath.moveTo(px, py);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                currentPath.lineTo(px, py);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                Path done = new Path(currentPath);
                Paint p = new Paint(currentPaint);
                inkManager.add(new InkStroke(done, p, currentPage));
                currentPath.reset();
                invalidate();
                return true;
        }
        return false;
    }

    /** Finish in-progress stroke when toggling pen off. */
    public void finishStroke() {
        if (!currentPath.isEmpty()) {
            Path done = new Path(currentPath);
            Paint p = new Paint(currentPaint);
            inkManager.add(new InkStroke(done, p, currentPage));
            currentPath.reset();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (pdfView == null || geom == null) return;

        c.save();
        // mirror the PDFView transform
        c.translate(pdfView.getCurrentXOffset(), pdfView.getCurrentYOffset());
        c.scale(pdfView.getZoom(), pdfView.getZoom());
        // translate to current page origin
        c.translate(geom.pageLeft[currentPage], geom.pageTop[currentPage]);

        // draw saved strokes for the current page
        for (InkStroke s : inkManager.getStrokesForPage(currentPage)) {
            c.drawPath(s.path, s.paint);
        }
        // draw the in-progress stroke
        c.drawPath(currentPath, currentPaint);

        c.restore();
    }
}
