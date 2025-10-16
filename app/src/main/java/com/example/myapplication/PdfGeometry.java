package com.example.myapplication;

import com.github.barteksc.pdfviewer.PDFView;

/** Computes page sizes/positions in viewer-content space (zoom=1). */
public final class PdfGeometry {
    public final int pageCount;
    public final float spacingPx;
    public final float[] pageLeft, pageTop, pageWidth, pageHeight;
    public final float contentWidth, contentHeight;

    public PdfGeometry(PDFView pdfView, float spacingPx) {
        this.pageCount = pdfView.getPageCount();
        this.spacingPx = spacingPx;

        pageLeft  = new float[pageCount];
        pageTop   = new float[pageCount];
        pageWidth = new float[pageCount];
        pageHeight= new float[pageCount];

        float maxW = 0f;
        for (int i = 0; i < pageCount; i++) {
            float w = pdfView.getPageSize(i).getWidth();
            float h = pdfView.getPageSize(i).getHeight();
            pageWidth[i]  = w;
            pageHeight[i] = h;
            if (w > maxW) maxW = w;
        }

        float y = 0f;
        for (int i = 0; i < pageCount; i++) {
            pageLeft[i] = (maxW - pageWidth[i]) / 2f; // horizontally centered
            pageTop[i]  = y;
            y += pageHeight[i] + spacingPx;
        }

        contentWidth  = maxW;
        contentHeight = y - spacingPx;
    }
}
