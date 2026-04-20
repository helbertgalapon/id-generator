package org.financial;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * High-resolution PNG export and optional combined PDF from rendered ID cards.
 * Separates export from UI controllers.
 */
public final class IdExportService {

    /** Scale factor applied to template pixel dimensions for print-ready output. */
    public static final double PRINT_SCALE = 2.0;

    private IdExportService() {}

    public static void exportPngPair(
        IdRecord record,
        String referenceFrontPath,
        String referenceBackPath,
        String localBaseUrl,
        String cloudUrl,
        Path outDir,
        String baseFileName,
        double scale
    ) throws IOException {
        Files.createDirectories(outDir);
        int[] fd = IdImageGenerator.getTemplateDimensions(referenceFrontPath);
        int[] bd = IdImageGenerator.getTemplateDimensions(referenceBackPath);
        int fw = scaleDim(fd != null ? fd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH, scale);
        int fh = scaleDim(fd != null ? fd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT, scale);
        int bw = scaleDim(bd != null ? bd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH, scale);
        int bh = scaleDim(bd != null ? bd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT, scale);

        Canvas front = new Canvas(fw, fh);
        Canvas back = new Canvas(bw, bh);
        IdImageGenerator.drawFront(front.getGraphicsContext2D(), record, referenceFrontPath, localBaseUrl, cloudUrl, fw, fh);
        IdImageGenerator.drawBack(back.getGraphicsContext2D(), record, referenceBackPath, localBaseUrl, cloudUrl, bw, bh);

        writePng(front, outDir.resolve(baseFileName + "_front.png"));
        writePng(back, outDir.resolve(baseFileName + "_back.png"));
    }

    public static BufferedImage renderFrontBuffered(
        IdRecord record,
        String referenceFrontPath,
        String localBaseUrl,
        String cloudUrl,
        int w,
        int h
    ) {
        Canvas c = new Canvas(w, h);
        IdImageGenerator.drawFront(c.getGraphicsContext2D(), record, referenceFrontPath, localBaseUrl, cloudUrl, w, h);
        return canvasToBuffered(c);
    }

    public static BufferedImage renderBackBuffered(
        IdRecord record,
        String referenceBackPath,
        String localBaseUrl,
        String cloudUrl,
        int w,
        int h
    ) {
        Canvas c = new Canvas(w, h);
        IdImageGenerator.drawBack(c.getGraphicsContext2D(), record, referenceBackPath, localBaseUrl, cloudUrl, w, h);
        return canvasToBuffered(c);
    }

    private static BufferedImage canvasToBuffered(Canvas canvas) {
        WritableImage snap = new WritableImage((int) canvas.getWidth(), (int) canvas.getHeight());
        canvas.snapshot(null, snap);
        return SwingFXUtils.fromFXImage(snap, null);
    }

    private static void writePng(Canvas canvas, Path file) throws IOException {
        WritableImage wi = new WritableImage((int) canvas.getWidth(), (int) canvas.getHeight());
        canvas.snapshot(null, wi);
        BufferedImage bi = SwingFXUtils.fromFXImage(wi, null);
        ImageIO.write(bi, "png", file.toFile());
    }

    private static int scaleDim(int base, double scale) {
        return Math.max(1, (int) Math.round(base * scale));
    }

    public static void exportCombinedPdf(
        List<IdRecord> records,
        String referenceFrontPath,
        String referenceBackPath,
        String localBaseUrl,
        Path pdfFile,
        double scale
    ) throws IOException {
        int[] fd = IdImageGenerator.getTemplateDimensions(referenceFrontPath);
        int[] bd = IdImageGenerator.getTemplateDimensions(referenceBackPath);
        int fw = scaleDim(fd != null ? fd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH, scale);
        int fh = scaleDim(fd != null ? fd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT, scale);
        int bw = scaleDim(bd != null ? bd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH, scale);
        int bh = scaleDim(bd != null ? bd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT, scale);

        List<BufferedImage> pages = new java.util.ArrayList<>();
        for (IdRecord r : records) {
            String cloud = SupabaseStorageService.getPublicPdfUrlForRecord(r).orElse("");
            pages.add(renderFrontBuffered(r, referenceFrontPath, localBaseUrl, cloud, fw, fh));
            pages.add(renderBackBuffered(r, referenceBackPath, localBaseUrl, cloud, bw, bh));
        }
        byte[] bytes = IdPdfGenerator.imagesToPdf(pages);
        Files.write(pdfFile, bytes);
    }
}
