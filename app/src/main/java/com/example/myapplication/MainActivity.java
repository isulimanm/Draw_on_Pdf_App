package com.example.myapplication;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.ToggleButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private PDFView pdfView;
    private InkOverlayView overlay;

    private Button btnOpen, btnUndo, btnRedo, btnRed, btnBlue, btnSaveAs;
    private ToggleButton tbPen;
    private SeekBar seekSize;
    private LinearProgressIndicator progress;

    private static final int SPACING_DP = 10;
    private float spacingPx;

    @Nullable
    private Uri currentPdfUri = null;
    @Nullable
    private File lastCommittedCacheFile = null;

    private final PdfInkFlattener flattener = new PdfInkFlattener();

    private final ActivityResultLauncher<String[]> openDocLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
                        try {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {
                        }
                        currentPdfUri = uri;
                        loadPdf(uri, 0);
                    });

    private final ActivityResultLauncher<String> createDocLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"),
                    outUri -> {
                        if (outUri == null) return;
                        if (lastCommittedCacheFile == null || !lastCommittedCacheFile.exists()) {
                            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            copyFileToUri(lastCommittedCacheFile, outUri);
                            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        spacingPx = dpToPx(SPACING_DP);
        setContentView(R.layout.activity_main);

        pdfView = findViewById(R.id.pdfView);
        overlay = findViewById(R.id.inkOverlay);
        progress = findViewById(R.id.mergeProgress);

        btnOpen = findViewById(R.id.btnOpen);
        btnUndo = findViewById(R.id.btnUndo);
        btnRedo = findViewById(R.id.btnRedo);
        btnRed = findViewById(R.id.btnRed);
        btnBlue = findViewById(R.id.btnBlue);
        btnSaveAs = findViewById(R.id.btnSaveAs);
        tbPen = findViewById(R.id.tbPen);
        seekSize = findViewById(R.id.seekSize);

        overlay.setEnabled(false);

        btnOpen.setOnClickListener(v -> openDocLauncher.launch(new String[]{"application/pdf"}));
        btnRed.setOnClickListener(v -> overlay.setStrokeColor(0xFFFF0000));
        btnBlue.setOnClickListener(v -> overlay.setStrokeColor(0xFF1976D2));

        seekSize.setMax(60);
        seekSize.setProgress(8);
        seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                overlay.setStrokeWidth(Math.max(1, value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        btnUndo.setOnClickListener(v -> {
            if (!overlay.getInkManager().canUndo()) return;
            overlay.getInkManager().undo();
            pdfView.invalidate();
            overlay.invalidate();
            updateUndoRedoEnabled();
        });

        btnRedo.setOnClickListener(v -> {
            if (!overlay.getInkManager().canRedo()) return;
            overlay.getInkManager().redo();
            pdfView.invalidate();
            overlay.invalidate();
            updateUndoRedoEnabled();
        });

        btnSaveAs.setOnClickListener(v ->
                createDocLauncher.launch("merged-" + System.currentTimeMillis() + ".pdf"));

        tbPen.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                overlay.finishStroke();
                overlay.setEnabled(true);
                updateUndoRedoEnabled();
            } else {
                overlay.finishStroke();
                overlay.setEnabled(false);
                updateUndoRedoEnabled();

                if (currentPdfUri == null) {
                    Toast.makeText(this, "Open a PDF first", Toast.LENGTH_SHORT).show();
                    tbPen.setChecked(false);
                    return;
                }
                int restorePage = pdfView.getCurrentPage();

                // show progress & disable controls
                setUiEnabled(false);

                // Flatten in background (reusable helper)
                flattener.flattenAsync(
                        this,
                        currentPdfUri,
                        pdfView,
                        overlay.getInkManager(),
                        spacingPx,
                        1.5f, // scale: 1.25~2.0; lower is faster/less RAM
                        new PdfInkFlattener.Callback() {
                            @Override
                            public void onSuccess(File outFile) {
                                overlay.getInkManager().clear();
                                overlay.invalidate();

                                lastCommittedCacheFile = outFile;
                                Uri newUri = FileProvider.getUriForFile(
                                        MainActivity.this, getPackageName() + ".fileprovider", outFile);
                                currentPdfUri = newUri;

                                loadPdf(currentPdfUri, restorePage);
                                setUiEnabled(true);
                            }

                            @Override
                            public void onError(Throwable t) {
                                Toast.makeText(MainActivity.this, "Commit failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                                tbPen.setChecked(true); // keep drawing, no loss
                                setUiEnabled(true);
                            }
                        });
            }
        });

        updateUndoRedoEnabled();
    }

    private void setUiEnabled(boolean enabled) {
        btnOpen.setEnabled(enabled);
        btnUndo.setEnabled(enabled && overlay.getInkManager().canUndo());
        btnRedo.setEnabled(enabled && overlay.getInkManager().canRedo());
        btnRed.setEnabled(enabled);
        btnBlue.setEnabled(enabled);
        seekSize.setEnabled(enabled);
        btnSaveAs.setEnabled(enabled && lastCommittedCacheFile != null);
        tbPen.setEnabled(true); // allow cancel while working
        progress.setVisibility(enabled ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void loadPdf(Uri uri, int restorePage) {
        pdfView.recycle();
        pdfView.fromUri(uri)
                .pageFitPolicy(FitPolicy.WIDTH)
                .spacing(SPACING_DP)
                .enableAnnotationRendering(true)
                .defaultPage(Math.max(restorePage, 0))
                .onLoad(new OnLoadCompleteListener() {
                    @Override
                    public void loadComplete(int nbPages) {
                        overlay.setPdfView(pdfView);
                        overlay.setGeometry(new PdfGeometry(pdfView, spacingPx));
                        pdfView.jumpTo(Math.min(restorePage, nbPages - 1), false);
                    }
                })
                .onDrawAll((canvas, pageW, pageH, pageIndex) -> {
                    for (InkStroke s : overlay.getInkManager().getStrokesForPage(pageIndex)) {
                        canvas.drawPath(s.path, s.paint);
                    }
                })
                .load();
    }

    private void copyFileToUri(File src, Uri dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             OutputStream os = getContentResolver().openOutputStream(dest, "w")) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        }
    }

    private float dpToPx(int dp) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dp * dm.density;
    }

    private void updateUndoRedoEnabled() {
        boolean penOn = tbPen != null && tbPen.isChecked();
        btnUndo.setEnabled(penOn && overlay.getInkManager().canUndo());
        btnRedo.setEnabled(penOn && overlay.getInkManager().canRedo());
        btnSaveAs.setEnabled(lastCommittedCacheFile != null);
    }
}
