package com.scanwhirl.app.pdf.engine;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;

import com.scanwhirl.app.util.PdfEncryptor;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * Runs one engine workflow against the R8-minified release APK. No AndroidX test
 * runner and no Kotlin runtime in the test process: this class is plain Java and
 * the build config is set by the CI init script.
 */
public class MinifiedEngineInstrumentation extends Instrumentation {

    private static File makePdf(File file, String marker) throws Exception {
        PDDocument doc = new PDDocument();
        try {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            try {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f);
                cs.newLineAtOffset(48f, 720f);
                cs.showText(marker);
                cs.endText();
            } finally {
                cs.close();
            }
            doc.save(file);
        } finally {
            doc.close();
        }
        return file;
    }

    private static byte[] signaturePng() {
        Bitmap bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawRect(0f, 0f, 80f, 40f, paint);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        PdfBoxEngine engine = null;
        try {
            File dir = new File(getTargetContext().getCacheDir(),
                "w7a-minified-" + System.nanoTime());
            if (!dir.mkdirs()) throw new IllegalStateException("no temp dir");
            engine = new PdfBoxEngine(getTargetContext(), new File(dir, "work"),
                new PdfEngineBudgets(), false);

            File a = makePdf(new File(dir, "a.pdf"), "MINIFIED_MARKER_A");
            File b = makePdf(new File(dir, "b.pdf"), "MINIFIED_MARKER_B");
            PdfHandle merged = engine.merge(java.util.Arrays.asList(a, b),
                new File(dir, "merged.pdf").getPath());
            if (merged.getPageCount() != 2) throw new AssertionError("merge page count");
            if (!engine.verifyText(merged.getFile()).contains("MINIFIED_MARKER_A")) {
                throw new AssertionError("A text missing");
            }
            if (!engine.verifyText(merged.getFile()).contains("MINIFIED_MARKER_B")) {
                throw new AssertionError("B text missing");
            }

            engine.overlay(merged, 0, signaturePng(), new PdfRect(300f, 60f, 120f, 60f),
                "MINIFIED_SIGNATURE", "2026-11-28");
            if (!engine.verifyText(merged.getFile()).contains("MINIFIED_SIGNATURE")) {
                throw new AssertionError("overlay text missing");
            }

            File protectedFile = new File(dir, "protected.pdf");
            engine.saveEncrypted(merged, protectedFile, "user-fictional");
            if (!engine.verifyText(protectedFile, "user-fictional").contains("MINIFIED_MARKER_A")) {
                throw new AssertionError("encrypted text missing");
            }
            PdfHandle reopened = engine.open(protectedFile, "user-fictional");
            if (reopened.getPageCount() != 2) throw new AssertionError("reopen page count");

            PdfHandle redacted = engine.redact(reopened, 0,
                java.util.Collections.singletonList(new PdfRect(0f, 620f, 400f, 160f)),
                new File(dir, "redacted.pdf").getPath());
            if (redacted.getPageCount() != 2) throw new AssertionError("redact page count");
            if (engine.verifyText(redacted.getFile()).contains("MINIFIED_MARKER_A")) {
                throw new AssertionError("redacted text survived");
            }
            result.putString("detail", "minified engine workflow OK");
            finish(0, result);
        } catch (Throwable t) {
            result.putString("detail", String.valueOf(t));
            finish(-1, result);
        } finally {
            if (engine != null) engine.close();
        }
    }
}
