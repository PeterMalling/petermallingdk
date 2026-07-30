package dk.petermalling.journalpdf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_PDF = 42;
    private static final long TARGET_BYTES = 880L * 1024L;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        Uri incoming = extractPdfUri(getIntent());
        if (incoming != null) {
            compressAsync(incoming);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Journal-PDF Kompressor");
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView intro = new TextView(this);
        intro.setText("Flader PDF-sider, bevarer farver og forsøger at holde filen under 880 KB. Alt foregår lokalt på tabletten.");
        intro.setTextSize(18f);
        intro.setPadding(0, 32, 0, 32);
        root.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        Button choose = new Button(this);
        choose.setText("Vælg PDF");
        choose.setOnClickListener(v -> choosePdf());
        root.addView(choose, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.topMargin = 32;
        root.addView(progress, pp);

        status = new TextView(this);
        status.setText("Du kan også vælge appen fra BOOX' Del-menu.");
        status.setTextSize(17f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 24, 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void choosePdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, PICK_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            compressAsync(data.getData());
        }
    }

    private Uri extractPdfUri(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
        return null;
    }

    private void compressAsync(Uri input) {
        progress.setVisibility(View.VISIBLE);
        status.setText("Komprimerer PDF …");
        executor.execute(() -> {
            try {
                Result result = compressToTarget(input);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Færdig: " + formatBytes(result.file.length()) + "\nÅbner delingsmenuen …");
                    sharePdf(result.file);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Kunne ikke komprimere PDF'en: " + e.getMessage());
                    Toast.makeText(this, "Komprimeringen mislykkedes", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Result compressToTarget(Uri input) throws IOException {
        String baseName = safeBaseName(displayName(input));
        File dir = new File(getCacheDir(), "journalpdf");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Kunne ikke oprette arbejdsmappe");

        int[] longEdges = {1500, 1300, 1100, 950, 820, 700};
        File latest = null;
        for (int edge : longEdges) {
            File candidate = new File(dir, baseName + "_komprimeret_" + edge + ".pdf");
            renderPdf(input, candidate, edge);
            if (latest != null && !latest.equals(candidate)) latest.delete();
            latest = candidate;
            if (candidate.length() <= TARGET_BYTES) break;
        }
        if (latest == null) throw new IOException("Der blev ikke lavet en fil");
        File finalFile = new File(dir, baseName + "_komprimeret.pdf");
        if (finalFile.exists()) finalFile.delete();
        if (!latest.renameTo(finalFile)) {
            copyFile(latest, finalFile);
            latest.delete();
        }
        return new Result(finalFile);
    }

    private void renderPdf(Uri input, File output, int maxLongEdge) throws IOException {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(input, "r");
             PdfRenderer renderer = new PdfRenderer(pfd);
             PdfDocument out = new PdfDocument()) {

            for (int i = 0; i < renderer.getPageCount(); i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    int sourceW = Math.max(1, page.getWidth());
                    int sourceH = Math.max(1, page.getHeight());
                    float scale = Math.min(1f, (float) maxLongEdge / Math.max(sourceW, sourceH));
                    int bitmapW = Math.max(1, Math.round(sourceW * scale));
                    int bitmapH = Math.max(1, Math.round(sourceH * scale));

                    Bitmap bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.RGB_565);
                    bitmap.eraseColor(Color.WHITE);
                    Matrix matrix = new Matrix();
                    matrix.setScale((float) bitmapW / sourceW, (float) bitmapH / sourceH);
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(sourceW, sourceH, i + 1).create();
                    PdfDocument.Page newPage = out.startPage(info);
                    Canvas canvas = newPage.getCanvas();
                    canvas.drawColor(Color.WHITE);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, sourceW, sourceH), paint);
                    out.finishPage(newPage);
                    bitmap.recycle();
                }
            }
            try (FileOutputStream stream = new FileOutputStream(output)) {
                out.writeTo(stream);
            }
        }
    }

    private void sharePdf(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/pdf");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Del komprimeret PDF"));
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return "journal_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date()) + ".pdf";
    }

    private String safeBaseName(String name) {
        String base = name == null ? "journal" : name.replaceFirst("(?i)\\.pdf$", "");
        base = base.replaceAll("[^a-zA-Z0-9æøåÆØÅ _.-]", "_").trim();
        return base.isEmpty() ? "journal" : base;
    }

    private static void copyFile(File source, File target) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
             java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.forLanguageTag("da-DK"), "%.0f KB", bytes / 1024.0);
        return String.format(Locale.forLanguageTag("da-DK"), "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class Result {
        final File file;
        Result(File file) { this.file = file; }
    }
}
