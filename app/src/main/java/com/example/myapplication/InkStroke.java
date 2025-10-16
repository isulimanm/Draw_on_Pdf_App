package com.example.myapplication;

import android.graphics.Paint;
import android.graphics.Path;

public class InkStroke {
    public final Path path;     // stored in page-local coordinates
    public final Paint paint;   // stroke attributes at commit time
    public final int pageIndex;

    public InkStroke(Path path, Paint paint, int pageIndex) {
        this.path = path;
        this.paint = paint;
        this.pageIndex = pageIndex;
    }
}
