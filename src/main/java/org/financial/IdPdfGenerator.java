package org.financial;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generates a PDF for an ID record so it can be served via QR and viewed on mobile.
 */
public final class IdPdfGenerator {

    private static final float MARGIN = 50;
    private static final float TITLE_FONT_SIZE = 18;
    private static final float BODY_FONT_SIZE = 12;
    private static final float LINE_HEIGHT = 18;

    /**
     * Returns PDF bytes for the given ID record, or null on error.
     * Uses the same template layout and reference images as preview/export when those are configured;
     * otherwise falls back to a simple text layout.
     */
    public static byte[] toPdf(IdRecord r) {
        if (r == null) return null;
        try {
            String[] ref = DbHelper.getReferenceDesign();
            String frontPath = ref[0] != null ? ref[0].trim() : "";
            String backPath = ref[1] != null ? ref[1].trim() : "";
            Path fp = frontPath.isEmpty() ? null : Path.of(frontPath);
            Path bp = backPath.isEmpty() ? null : Path.of(backPath);
            if (fp != null && Files.isRegularFile(fp) && bp != null && Files.isRegularFile(bp)) {
                String baseUrl = App.getServerBaseUrl();
                String cloudUrl = SupabaseStorageService.getPublicPdfUrlForRecord(r).orElse("");
                int[] fd = IdImageGenerator.getTemplateDimensions(frontPath);
                int[] bd = IdImageGenerator.getTemplateDimensions(backPath);
                int fw = fd != null ? fd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH;
                int fh = fd != null ? fd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT;
                int bw = bd != null ? bd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH;
                int bh = bd != null ? bd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT;
                BufferedImage imgF = IdExportService.renderFrontBuffered(r, frontPath, baseUrl, cloudUrl, fw, fh);
                BufferedImage imgB = IdExportService.renderBackBuffered(r, backPath, baseUrl, cloudUrl, bw, bh);
                return imagesToPdf(List.of(imgF, imgB));
            }
        } catch (IOException e) {
            SyncLogger.log("IdPdfGenerator.toPdf: template-based PDF generation failed", e);
            return null;
        } catch (Exception e) {
            SyncLogger.log("IdPdfGenerator.toPdf: unexpected template rendering error", e);
            return null;
        }
        SyncLogger.log("IdPdfGenerator.toPdf: template images not found; falling back to legacy text PDF");
        return legacyPlainTwoPagePdf(r);
    }

    /**
     * Generates PDF off the JavaFX thread by running {@link #toPdf} on the FX application thread.
     * Required for Supabase flush and {@link IdServer} HTTP handlers, which run on worker threads.
     */
    public static byte[] toPdfFromBackground(IdRecord r) {
        if (r == null) return null;
        if (Platform.isFxApplicationThread()) {
            return toPdf(r);
        }
        CompletableFuture<byte[]> fut = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                fut.complete(toPdf(r));
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        try {
            return fut.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            SyncLogger.log("IdPdfGenerator.toPdfFromBackground failed", e);
            return null;
        }
    }

    private static byte[] legacyPlainTwoPagePdf(IdRecord r) {
        try (PDDocument doc = new PDDocument()) {
            // Page 1 - Front (photo, name, id number, dob)
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            float pageWidth = page1.getMediaBox().getWidth();
            float pageHeight = page1.getMediaBox().getHeight();

            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                float y = pageHeight - MARGIN;

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), TITLE_FONT_SIZE);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText("ID — Front");
                cs.endText();
                y -= LINE_HEIGHT * 1.5f;

                // Photo if present
                if (r.photoPath() != null && !r.photoPath().isEmpty()) {
                    Path photoPath = Path.of(r.photoPath());
                    if (Files.exists(photoPath)) {
                        try {
                            BufferedImage img = ImageIO.read(photoPath.toFile());
                            if (img != null) {
                                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
                                float imgW = 120;
                                float imgH = 120 * pdImage.getHeight() / pdImage.getWidth();
                                if (imgH > 140) imgH = 140;
                                cs.drawImage(pdImage, MARGIN, y - imgH, imgW, imgH);
                                y -= imgH + LINE_HEIGHT;
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }

                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), BODY_FONT_SIZE);
                drawLine(cs, MARGIN, y, "Name: " + nvl(r.name()));
                y -= LINE_HEIGHT;
                drawLine(cs, MARGIN, y, "ID Number: " + nvl(r.idNumber()));
                y -= LINE_HEIGHT;
                drawLine(cs, MARGIN, y, "Position: " + nvl(r.position()));
                y -= LINE_HEIGHT;
                drawLine(cs, MARGIN, y, "Department: " + nvl(r.department()));
                y -= LINE_HEIGHT;
                drawLine(cs, MARGIN, y, "Date of Birth: " + nvl(r.dateOfBirth()));
            }

            // Page 2 - Back (contact, address, emergency)
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);
            float page2Height = page2.getMediaBox().getHeight();

            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                float y = page2Height - MARGIN;

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), TITLE_FONT_SIZE);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText("ID — Back");
                cs.endText();
                y -= LINE_HEIGHT * 1.5f;

                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), BODY_FONT_SIZE);
                drawLine(cs, MARGIN, y, "Contact Number: " + nvl(r.contactNumber()));
                y -= LINE_HEIGHT;
                drawLine(cs, MARGIN, y, "Address: " + nvl(r.address()));
                y -= LINE_HEIGHT * 2;
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), BODY_FONT_SIZE);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText("Person to notify (Emergency)");
                cs.endText();
                y -= LINE_HEIGHT;
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), BODY_FONT_SIZE);
                drawLine(cs, MARGIN, y, "Name: " + nvl(r.emergencyName()));
                y -= LINE_HEIGHT;
                drawLine(cs, MARGIN, y, "Contact: " + nvl(r.emergencyContact()));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            SyncLogger.log("IdPdfGenerator.legacyPlainTwoPagePdf failed", e);
            return null;
        }
    }

    private static void drawLine(PDPageContentStream cs, float x, float y, String text) throws IOException {
        if (text == null) text = "";
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(text));
        cs.endText();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", " ");
    }

    /**
     * One PDF page per image, each image scaled to fit the page while preserving aspect ratio.
     */
    public static byte[] imagesToPdf(List<BufferedImage> pages) throws IOException {
        if (pages == null || pages.isEmpty()) {
            return new byte[0];
        }
        try (PDDocument doc = new PDDocument()) {
            for (BufferedImage img : pages) {
                if (img == null) continue;
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float pw = page.getMediaBox().getWidth();
                    float ph = page.getMediaBox().getHeight();
                    float margin = 36;
                    float maxW = pw - 2 * margin;
                    float maxH = ph - 2 * margin;
                    float iw = pdImage.getWidth();
                    float ih = pdImage.getHeight();
                    float scale = Math.min(maxW / iw, maxH / ih);
                    float dw = iw * scale;
                    float dh = ih * scale;
                    float x = (pw - dw) / 2;
                    float y = (ph - dh) / 2;
                    cs.drawImage(pdImage, x, y, dw, dh);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
