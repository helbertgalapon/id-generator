package org.financial;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders ID card front/back images using the template and saved layout.
 * Typography is scaled to card size for a consistent, professional look.
 */
public final class IdImageGenerator {

    public static final int DEFAULT_CARD_WIDTH = 340;
    public static final int DEFAULT_CARD_HEIGHT = 220;

    private static final String FONT_FAMILY = "Segoe UI";
    /** Subtle shift for center-aligned glyphs (JavaFX canvas; font-dependent). */
    private static final double CENTER_OPTICAL_NUDGE_PX = 0.5;
    private static final Color COLOR_PRIMARY = Color.rgb(26, 26, 26);
    private static final Color COLOR_LABEL = Color.rgb(75, 85, 99);

    // Default layout when no template layout is saved (fraction of card size)
    private static final double DEF_PHOTO_X = 0.08, DEF_PHOTO_Y = 0.26, DEF_PHOTO_W = 0.24, DEF_PHOTO_H = 0.52;
    private static final double DEF_NAME_X = 0.36, DEF_NAME_Y = 0.28;
    private static final double DEF_ID_X = 0.36, DEF_ID_Y = 0.40;
    private static final double DEF_POS_X = 0.36, DEF_POS_Y = 0.52;
    private static final double DEF_DEPT_X = 0.36, DEF_DEPT_Y = 0.635;
    private static final double DEF_DOB_X = 0.36, DEF_DOB_Y = 0.755;
    private static final double DEF_QR_X = 0.64, DEF_QR_Y = 0.06, DEF_QR_SIZE = 0.30;
    private static final double DEF_BACK_X = 0.08;
    private static final double DEF_BACK_DOB_Y = 0.12;
    private static final double DEF_BACK_ADDR_Y = 0.28;
    private static final double DEF_BACK_CONTACT_Y = 0.44;
    private static final double DEF_BACK_EM_Y = 0.60;

    /**
     * Renders the front side of an ID card onto the given GraphicsContext.
     */
    public static void drawFront(GraphicsContext gc, IdRecord r, String referencePath,
                                String localBaseUrl, String cloudUrl, int cardWidth, int cardHeight) {
        drawFront(gc, r, referencePath, localBaseUrl, cloudUrl, cardWidth, cardHeight, DbHelper.getTemplateLayout(), DbHelper.getTemplateStyles());
    }

    /**
     * Blueprint (layout editor) rendering:
     * - Never draw fallback fields that are not in the template layout.
     * - Never pull saved per-record custom values from DB (preview-only).
     */
    public static void drawFrontBlueprint(GraphicsContext gc, IdRecord r, String referencePath,
                                          int cardWidth, int cardHeight,
                                          List<TemplateRegion> templateLayout,
                                          List<TemplateFieldStyle> templateStyles) {
        renderFront(gc, r, referencePath, "", "", cardWidth, cardHeight, templateLayout, templateStyles, false, true);
    }

    /**
     * Rendering entry point when the caller already has the template layout/style lists in memory.
     * This is required for pixel-perfect editor/preview/export parity.
     */
    public static void drawFront(GraphicsContext gc, IdRecord r, String referencePath,
                                   String localBaseUrl, String cloudUrl, int cardWidth, int cardHeight,
                                   List<TemplateRegion> templateLayout,
                                   List<TemplateFieldStyle> templateStyles) {
        renderFront(gc, r, referencePath, localBaseUrl, cloudUrl, cardWidth, cardHeight, templateLayout, templateStyles, true, false);
    }

    /**
     * Renders the back side of an ID card onto the given GraphicsContext.
     */
    public static void drawBack(GraphicsContext gc, IdRecord r, String referencePath,
                               String localBaseUrl, String cloudUrl, int cardWidth, int cardHeight) {
        drawBack(gc, r, referencePath, localBaseUrl, cloudUrl, cardWidth, cardHeight, DbHelper.getTemplateLayout(), DbHelper.getTemplateStyles());
    }

    public static void drawBackBlueprint(GraphicsContext gc, IdRecord r, String referencePath,
                                         int cardWidth, int cardHeight,
                                         List<TemplateRegion> templateLayout,
                                         List<TemplateFieldStyle> templateStyles) {
        renderBack(gc, r, referencePath, "", "", cardWidth, cardHeight, templateLayout, templateStyles, false, true);
    }

    public static void drawBack(GraphicsContext gc, IdRecord r, String referencePath,
                                  String localBaseUrl, String cloudUrl, int cardWidth, int cardHeight,
                                  List<TemplateRegion> templateLayout,
                                  List<TemplateFieldStyle> templateStyles) {
        renderBack(gc, r, referencePath, localBaseUrl, cloudUrl, cardWidth, cardHeight, templateLayout, templateStyles, true, false);
    }

    private static void renderFront(GraphicsContext gc, IdRecord r, String referencePath,
                                     String localBaseUrl, String cloudUrl,
                                     int cardWidth, int cardHeight,
                                     List<TemplateRegion> templateLayout,
                                     List<TemplateFieldStyle> templateStyles,
                                     boolean allowFallbacks,
                                     boolean blueprintPreview) {
        if (templateLayout == null) templateLayout = List.of();
        if (templateStyles == null) templateStyles = List.of();

        double w = cardWidth;
        double h = cardHeight;

        Image bg = loadImage(referencePath);
        if (bg != null) {
            gc.drawImage(bg, 0, 0, w, h);
        } else {
            drawFormalCardBackground(gc, w, h);
        }

        gc.setTextBaseline(VPos.TOP);

        Map<String, TemplateFieldStyle> styleMap = templateStyles.stream()
            .collect(Collectors.toMap(s -> styleKey(s.side(), s.fieldName()), s -> s, (a, b) -> b));
        Map<String, String> customLabels = DbHelper.getCustomFieldLabelsByKey();
        Map<String, String> customValues = blueprintPreview ? Map.of() : DbHelper.getCustomValuesForRecord(r.id());

        double minSide = Math.min(w, h);

        boolean drewPhoto = false;
        boolean drewName = false;
        boolean drewId = false;
        boolean drewPos = false;
        boolean drewDept = false;
        boolean drewDob = false;

        for (TemplateRegion tr : templateLayout) {
            if (tr == null) continue;
            if (!TemplateRegion.SIDE_FRONT.equals(tr.side())) continue;
            String field = tr.fieldName();
            if (field == null) continue;

            // Shapes first/last follows layout order (z-index parity).
            if (field.startsWith(TemplateRegion.FIELD_RECTANGLE) || field.startsWith(TemplateRegion.FIELD_CIRCLE)) {
                drawDesignShapeForRegion(gc, TemplateRegion.SIDE_FRONT, field, tr, styleMap, w, h);
                continue;
            }

            if (field.startsWith(TemplateRegion.FIELD_PHOTO)) {
                drewPhoto = true;
                double px = tr.x() * w;
                double py = tr.y() * h;
                double pw = tr.width() * w;
                double ph = tr.height() * h;
                int cacheEdge = (int) Math.ceil(Math.max(pw, ph) * 2);
                Image photo = PhotoImageCache.getImageForPath(r.photoPath(), Math.max(256, cacheEdge));
                TemplateFieldStyle pst = styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, field));
                boolean circular = pst != null && pst.photoCircular();
                drawCoverPhoto(gc, photo, px, py, pw, ph, w, circular);
                continue;
            }

            if (field.startsWith(TemplateRegion.FIELD_NAME)) {
                drewName = true;
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_NAME, "Full Name", r) : nvl(r.name());
                drawFieldBlock(gc, v, tr, w, h, DEF_NAME_X, DEF_NAME_Y,
                    TemplateTypography.Tier.NAME, FontWeight.BOLD, COLOR_PRIMARY, 0, 0.06,
                    styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, field)));
                continue;
            }

            if (field.startsWith(TemplateRegion.FIELD_ID_NUMBER)) {
                drewId = true;
                double rhPx = tr != null ? tr.height() * h : Math.max(1, h * 0.10);
                double minPx = 22.0;
                double padYEm = rhPx > 0 ? Math.max(0.54, minPx / rhPx) : 0.54;
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_ID_NUMBER, "ID-XXXXXXX", r) : nvl(r.idNumber());
                drawFieldBlock(gc, v, tr, w, h, DEF_ID_X, DEF_ID_Y,
                    TemplateTypography.Tier.TITLE, FontWeight.SEMI_BOLD, COLOR_PRIMARY, 0, padYEm,
                    styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, field)));
                continue;
            }

            if (field.startsWith(TemplateRegion.FIELD_POSITION)) {
                drewPos = true;
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_POSITION, "Position", r) : nvl(r.position());
                drawLabeledField(gc, "Position", v, tr, w, h, DEF_POS_X, DEF_POS_Y,
                    FontWeight.NORMAL, TemplateTypography.Tier.BODY, styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, field)));
                continue;
            }

            if (field.startsWith(TemplateRegion.FIELD_DEPARTMENT)) {
                drewDept = true;
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_DEPARTMENT, "Department", r) : nvl(r.department());
                drawLabeledField(gc, "Department", v, tr, w, h, DEF_DEPT_X, DEF_DEPT_Y,
                    FontWeight.NORMAL, TemplateTypography.Tier.BODY, styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, field)));
                continue;
            }

            if (field.startsWith(TemplateRegion.FIELD_DATE_OF_BIRTH)) {
                drewDob = true;
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_DATE_OF_BIRTH, "Date of Birth", r) : nvl(r.dateOfBirth());
                drawLabeledField(gc, "Date of birth", v, tr, w, h, DEF_DOB_X, DEF_DOB_Y,
                    FontWeight.NORMAL, TemplateTypography.Tier.META, styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, field)));
                continue;
            }
            if (DbHelper.isCustomFieldMarker(field)) {
                String fieldKey = DbHelper.fieldKeyFromMarker(field);
                String label = customLabels.getOrDefault(fieldKey, "Custom");
                String value = blueprintPreview
                    ? blueprintValue(field, label, r)
                    : customValues.getOrDefault(fieldKey, "");
                drawLabeledField(gc, label, value, tr, w, h, 0.36, 0.74,
                    FontWeight.NORMAL, TemplateTypography.Tier.BODY, styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, field)));
            }
        }

        // Fallbacks if template is incomplete.
        if (!allowFallbacks) return;
        if (!drewPhoto) {
            double px = DEF_PHOTO_X * w;
            double py = DEF_PHOTO_Y * h;
            double pw = DEF_PHOTO_W * w;
            double ph = DEF_PHOTO_H * h;
            int cacheEdge = (int) Math.ceil(Math.max(pw, ph) * 2);
            Image photo = PhotoImageCache.getImageForPath(r.photoPath(), Math.max(256, cacheEdge));
            drawCoverPhoto(gc, photo, px, py, pw, ph, w, false);
        }
        if (!drewName) {
            String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_NAME, "Full Name", r) : nvl(r.name());
            drawFieldBlock(gc, v, null, w, h, DEF_NAME_X, DEF_NAME_Y,
                TemplateTypography.Tier.NAME, FontWeight.BOLD, COLOR_PRIMARY, 0, 0.06,
                styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, TemplateRegion.FIELD_NAME)));
        }
        if (!drewId) {
            double fallbackRhPx = Math.max(1, DEF_ID_Y * h * 0.10);
            double minPx = 22.0;
            double padYEm = fallbackRhPx > 0 ? Math.max(0.54, minPx / fallbackRhPx) : 0.54;
            String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_ID_NUMBER, "ID-XXXXXXX", r) : nvl(r.idNumber());
            drawFieldBlock(gc, v, null, w, h,
                DEF_ID_X, DEF_ID_Y, TemplateTypography.Tier.TITLE, FontWeight.SEMI_BOLD,
                COLOR_PRIMARY, 0, padYEm,
                styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, TemplateRegion.FIELD_ID_NUMBER)));
        }
        if (!drewPos) {
            String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_POSITION, "Position", r) : nvl(r.position());
            drawLabeledField(gc, "Position", v, null, w, h, DEF_POS_X, DEF_POS_Y,
                FontWeight.NORMAL, TemplateTypography.Tier.BODY,
                styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, TemplateRegion.FIELD_POSITION)));
        }
        if (!drewDept) {
            String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_DEPARTMENT, "Department", r) : nvl(r.department());
            drawLabeledField(gc, "Department", v, null, w, h, DEF_DEPT_X, DEF_DEPT_Y,
                FontWeight.NORMAL, TemplateTypography.Tier.BODY,
                styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, TemplateRegion.FIELD_DEPARTMENT)));
        }
        if (!drewDob) {
            String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_DATE_OF_BIRTH, "Date of Birth", r) : nvl(r.dateOfBirth());
            drawLabeledField(gc, "Date of birth", v, null, w, h, DEF_DOB_X, DEF_DOB_Y,
                FontWeight.NORMAL, TemplateTypography.Tier.META,
                styleMap.get(styleKey(TemplateRegion.SIDE_FRONT, TemplateRegion.FIELD_DATE_OF_BIRTH)));
        }
    }

    private static void renderBack(GraphicsContext gc, IdRecord r, String referencePath,
                                    String localBaseUrl, String cloudUrl,
                                    int cardWidth, int cardHeight,
                                    List<TemplateRegion> templateLayout,
                                    List<TemplateFieldStyle> templateStyles,
                                    boolean allowFallbacks,
                                    boolean blueprintPreview) {
        if (templateLayout == null) templateLayout = List.of();
        if (templateStyles == null) templateStyles = List.of();

        double w = cardWidth;
        double h = cardHeight;

        Image bg = loadImage(referencePath);
        if (bg != null) {
            gc.drawImage(bg, 0, 0, w, h);
        } else {
            gc.setFill(Color.LIGHTGRAY);
            gc.fillRect(0, 0, w, h);
            gc.setStroke(Color.DARKGRAY);
            gc.strokeRect(0, 0, w, h);
        }

        gc.setTextBaseline(VPos.TOP);

        Map<String, TemplateFieldStyle> styleMap = templateStyles.stream()
            .collect(Collectors.toMap(s -> styleKey(s.side(), s.fieldName()), s -> s, (a, b) -> b));
        Map<String, String> customLabels = DbHelper.getCustomFieldLabelsByKey();
        Map<String, String> customValues = blueprintPreview ? Map.of() : DbHelper.getCustomValuesForRecord(r.id());

        // Choose x anchor using template order (parity with editor).
        TemplateRegion anchor = null;
        for (TemplateRegion tr : templateLayout) {
            if (tr == null) continue;
            if (!TemplateRegion.SIDE_BACK.equals(tr.side())) continue;
            String field = tr.fieldName();
            if (field == null) continue;
            if (field.startsWith(TemplateRegion.FIELD_ADDRESS)) {
                anchor = tr;
                break;
            }
            if (anchor == null && field.startsWith(TemplateRegion.FIELD_CONTACT)) anchor = tr;
            if (anchor == null && field.startsWith(TemplateRegion.FIELD_EMERGENCY)) anchor = tr;
        }

        double defX = DEF_BACK_X * w;

        boolean drewAddr = false;
        boolean drewContact = false;
        boolean drewEmerg = false;
        boolean drewQr = false;

        for (TemplateRegion tr : templateLayout) {
            if (tr == null) continue;
            if (!TemplateRegion.SIDE_BACK.equals(tr.side())) continue;
            String field = tr.fieldName();
            if (field == null) continue;

            if (field.startsWith(TemplateRegion.FIELD_RECTANGLE) || field.startsWith(TemplateRegion.FIELD_CIRCLE)) {
                drawDesignShapeForRegion(gc, TemplateRegion.SIDE_BACK, field, tr, styleMap, w, h);
                continue;
            }

            if (field.startsWith(TemplateRegion.FIELD_DATE_OF_BIRTH)) {
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_DATE_OF_BIRTH, "Date of Birth", r) : nvl(r.dateOfBirth());
                drawBackBlock(gc, "Date of birth", v, tr, anchor, w, h, DEF_BACK_DOB_Y, defX,
                    styleMap.get(styleKey(TemplateRegion.SIDE_BACK, field)));
                continue;
            }
            if (field.startsWith(TemplateRegion.FIELD_ADDRESS)) {
                drewAddr = true;
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_ADDRESS, "Address", r) : nvl(r.address());
                drawBackBlock(gc, "Address", v, tr, anchor, w, h, DEF_BACK_ADDR_Y, defX,
                    styleMap.get(styleKey(TemplateRegion.SIDE_BACK, field)));
                continue;
            }
            if (field.startsWith(TemplateRegion.FIELD_CONTACT)) {
                drewContact = true;
                String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_CONTACT, "Contact Number", r) : nvl(r.contactNumber());
                drawBackBlock(gc, "Contact number", v, tr, anchor, w, h, DEF_BACK_CONTACT_Y, defX,
                    styleMap.get(styleKey(TemplateRegion.SIDE_BACK, field)));
                continue;
            }
            if (field.startsWith(TemplateRegion.FIELD_EMERGENCY)) {
                drewEmerg = true;
                String v = blueprintPreview
                    ? blueprintValue(TemplateRegion.FIELD_EMERGENCY, "Emergency Contact", r)
                    : (nvl(r.emergencyName()) + (r.emergencyContact().isBlank() ? "" : " · " + nvl(r.emergencyContact())));
                drawBackBlock(gc, "Emergency contact", v, tr, anchor, w, h, DEF_BACK_EM_Y, defX,
                    styleMap.get(styleKey(TemplateRegion.SIDE_BACK, field)));
                continue;
            }
            if (field.startsWith(TemplateRegion.FIELD_QR)) {
                drewQr = true;
                drawQr(gc, w, h, localBaseUrl, cloudUrl, r, tr, styleMap.get(styleKey(TemplateRegion.SIDE_BACK, field)), blueprintPreview);
                continue;
            }
            if (DbHelper.isCustomFieldMarker(field)) {
                String fieldKey = DbHelper.fieldKeyFromMarker(field);
                String label = customLabels.getOrDefault(fieldKey, "Custom");
                String value = blueprintPreview
                    ? blueprintValue(field, label, r)
                    : customValues.getOrDefault(fieldKey, "");
                drawBackBlock(gc, label, value, tr, anchor, w, h, 0.80, defX,
                    styleMap.get(styleKey(TemplateRegion.SIDE_BACK, field)));
            }
        }

        if (!allowFallbacks) return;
        if (!drewAddr) {
            String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_ADDRESS, "Address", r) : nvl(r.address());
            drawBackBlock(gc, "Address", v, null, anchor, w, h, DEF_BACK_ADDR_Y, defX,
                styleMap.get(styleKey(TemplateRegion.SIDE_BACK, TemplateRegion.FIELD_ADDRESS)));
        }
        if (!drewContact) {
            String v = blueprintPreview ? blueprintValue(TemplateRegion.FIELD_CONTACT, "Contact Number", r) : nvl(r.contactNumber());
            drawBackBlock(gc, "Contact number", v, null, anchor, w, h, DEF_BACK_CONTACT_Y, defX,
                styleMap.get(styleKey(TemplateRegion.SIDE_BACK, TemplateRegion.FIELD_CONTACT)));
        }
        if (!drewEmerg) {
            String v = blueprintPreview
                ? blueprintValue(TemplateRegion.FIELD_EMERGENCY, "Emergency Contact", r)
                : (nvl(r.emergencyName()) + (r.emergencyContact().isBlank() ? "" : " · " + nvl(r.emergencyContact())));
            drawBackBlock(gc, "Emergency contact", v, null, anchor, w, h, DEF_BACK_EM_Y, defX,
                styleMap.get(styleKey(TemplateRegion.SIDE_BACK, TemplateRegion.FIELD_EMERGENCY)));
        }
        if (!drewQr) {
            drawQr(gc, w, h, localBaseUrl, cloudUrl, r, null,
                styleMap.get(styleKey(TemplateRegion.SIDE_BACK, TemplateRegion.FIELD_QR)), blueprintPreview);
        }
    }

    private static void drawDesignShapeForRegion(GraphicsContext gc,
                                                   String side,
                                                   String field,
                                                   TemplateRegion tr,
                                                   Map<String, TemplateFieldStyle> styleMap,
                                                   double w, double h) {
        boolean isRect = field.startsWith(TemplateRegion.FIELD_RECTANGLE);
        boolean isCircle = field.startsWith(TemplateRegion.FIELD_CIRCLE);
        if (!isRect && !isCircle) return;

        TemplateFieldStyle st = styleMap.get(styleKey(side, field));
        if (st != null && !st.visible()) return;

        double x = tr.x() * w;
        double y = tr.y() * h;
        double rw = tr.width() * w;
        double rh = tr.height() * h;

        gc.setFill(Color.rgb(47, 111, 239, 0.10));
        gc.setStroke(Color.rgb(47, 111, 239, 0.75));
        gc.setLineWidth(Math.max(1, Math.min(w, h) * 0.003));

        if (isRect) {
            double arc = Math.max(2, Math.min(rw, rh) * 0.12);
            gc.fillRoundRect(x, y, rw, rh, arc, arc);
            gc.strokeRoundRect(x, y, rw, rh, arc, arc);
        } else {
            gc.fillOval(x, y, rw, rh);
            gc.strokeOval(x, y, rw, rh);
        }
    }

    /**
     * Same horizontal inset as text rendering — for editor overlays / tools.
     */
    public static double horizontalMarginPx(double cardWidthPx) {
        return Math.max(4, cardWidthPx * 0.01);
    }

    /**
     * X position for {@link GraphicsContext#fillText} with {@link javafx.scene.text.TextAlignment}:
     * left/right inset symmetrically; center uses the region's geometric center (JavaFX uses x as center when
     * alignment is CENTER) plus a tiny optical nudge.
     */
    private static double anchorXForRegion(double regionLeft, double regionW, double cardW, TextAlignment align, double minSide) {
        double m = horizontalMarginPx(cardW);
        double x = switch (align) {
            case LEFT -> regionLeft + m;
            case RIGHT -> regionLeft + regionW - m;
            default -> regionLeft + regionW / 2.0;
        };
        if (align == TextAlignment.CENTER) {
            x += CENTER_OPTICAL_NUDGE_PX * clamp(minSide / 520.0, 0.9, 1.1);
        }
        return x;
    }

    private static void drawBackBlock(GraphicsContext gc, String label, String value, TemplateRegion valueReg,
                                      TemplateRegion xRefReg, double w, double h, double defYFrac,
                                      double defX, TemplateFieldStyle style) {
        if (style != null && !style.visible()) return;
        if (value.isBlank()) return;
        double minSide = Math.min(w, h);
        double refLeft = xRefReg != null ? xRefReg.x() * w : defX;
        double refW = xRefReg != null ? xRefReg.width() * w : Math.max(w * 0.2, w - refLeft - w * 0.06);
        double yTop = valueReg != null ? valueReg.y() * h : defYFrac * h;
        double margin = horizontalMarginPx(w);
        final double maxW = Math.max(w * 0.2, refW - 2 * margin);
        TextAlignment textAlignment = resolveAlign(style);
        double anchorX = anchorXForRegion(refLeft, refW, w, textAlignment, minSide);
        double effBodySize = TemplateTypography.effectiveFontPx(style, TemplateTypography.Tier.BODY, minSide);
        double effCaption = Math.max(
            TemplateTypography.effectiveFontPx(style, TemplateTypography.Tier.CAPTION, minSide),
            clamp(effBodySize * 0.50, minSide * 0.017, minSide * 0.052)
        );
        final double effBodyFinal = effBodySize;
        final double effCaptionFinal = effCaption;
        final double effLineGapFinal = TemplateTypography.lineSpacingPx(effBodyFinal);

        Runnable paint = () -> {
            gc.setTextAlign(textAlignment);
            gc.setFont(font(effCaptionFinal, FontWeight.SEMI_BOLD));
            gc.setFill(COLOR_LABEL);
            gc.fillText(label.toUpperCase(), anchorX, yTop);

            double valueY = yTop + TemplateTypography.captionToValueOffsetPx(effCaptionFinal);
            gc.setFont(font(effBodyFinal, (style != null && style.bold()) ? FontWeight.BOLD : FontWeight.NORMAL));
            gc.setFill(COLOR_PRIMARY);
            drawWrappedLines(gc, value, anchorX, valueY, maxW, effBodyFinal, effLineGapFinal, textAlignment);
            gc.setTextAlign(TextAlignment.LEFT);
        };

        if (valueReg != null) {
            withRegionClip(gc, valueReg, w, h, paint);
        } else {
            paint.run();
        }
    }

    private static void drawLabeledField(GraphicsContext gc, String label, String value, TemplateRegion reg,
                                        double w, double h, double defX, double defY,
                                        FontWeight valueWeight, TemplateTypography.Tier valueTier, TemplateFieldStyle style) {
        if (style != null && !style.visible()) return;
        if (value.isBlank()) return;
        double minSide = Math.min(w, h);
        double regionLeft = reg != null ? reg.x() * w : defX * w;
        double regionW = reg != null ? reg.width() * w : Math.max(w * 0.15, w - regionLeft - w * 0.08);
        double yTop = reg != null ? reg.y() * h : defY * h;
        double margin = horizontalMarginPx(w);
        final double maxW = Math.max(w * 0.12, regionW - 2 * margin);
        TextAlignment textAlignment = resolveAlign(style);
        double anchorX = anchorXForRegion(regionLeft, regionW, w, textAlignment, minSide);
        double effValueSize = TemplateTypography.effectiveFontPx(style, valueTier, minSide);
        double effLabelSize = Math.max(
            TemplateTypography.effectiveFontPx(style, TemplateTypography.Tier.CAPTION, minSide),
            clamp(effValueSize * 0.52, minSide * 0.016, minSide * 0.045)
        );
        final double effValueFinal = effValueSize;
        final double effLabelFinal = effLabelSize;

        Runnable paint = () -> {
            gc.setTextAlign(textAlignment);
            gc.setFont(font(effLabelFinal, FontWeight.NORMAL));
            gc.setFill(COLOR_LABEL);
            gc.fillText(label.toUpperCase(), anchorX, yTop);

            double valueY = yTop + TemplateTypography.captionToValueOffsetPx(effLabelFinal);
            FontWeight effWeight = style != null && style.bold() ? FontWeight.BOLD : valueWeight;
            gc.setFont(font(effValueFinal, effWeight));
            gc.setFill(COLOR_PRIMARY);
            double lineGap = TemplateTypography.lineSpacingPx(effValueFinal);
            drawWrappedLines(gc, value, anchorX, valueY, maxW, effValueFinal, lineGap, textAlignment);
            gc.setTextAlign(TextAlignment.LEFT);
        };

        if (reg != null) {
            withRegionClip(gc, reg, w, h, paint);
        } else {
            paint.run();
        }
    }

    private static void drawFieldBlock(GraphicsContext gc, String text, TemplateRegion reg, double w, double h,
                                      double defX, double defY, TemplateTypography.Tier tier, FontWeight weight, Color color,
                                      double padXEm, double padYEm, TemplateFieldStyle style) {
        if (style != null && !style.visible()) return;
        if (text.isEmpty()) return;
        double minSide = Math.min(w, h);
        double regionLeft;
        double regionW;
        if (reg != null) {
            regionLeft = reg.x() * w + reg.width() * w * padXEm;
            regionW = reg.width() * w * (1 - 2 * padXEm);
        } else {
            regionLeft = defX * w;
            regionW = w - regionLeft - w * 0.06;
        }
        double yTop = reg != null ? reg.y() * h + reg.height() * h * padYEm : defY * h;
        double margin = horizontalMarginPx(w);
        final double maxW = Math.max(w * 0.12, regionW - 2 * margin);
        TextAlignment textAlignment = resolveAlign(style);
        double anchorX = anchorXForRegion(regionLeft, regionW, w, textAlignment, minSide);
        double effSize = TemplateTypography.effectiveFontPx(style, tier, minSide);
        FontWeight effWeight = style != null && style.bold() ? FontWeight.BOLD : weight;
        final double effSizeFinal = effSize;
        final double lineGap = TemplateTypography.lineSpacingPx(effSizeFinal);

        Runnable paint = () -> {
            gc.setTextAlign(textAlignment);
            gc.setFont(font(effSizeFinal, effWeight));
            gc.setFill(color);
            drawWrappedLines(gc, text, anchorX, yTop, maxW, effSizeFinal, lineGap, textAlignment);
            gc.setTextAlign(TextAlignment.LEFT);
        };

        if (reg != null) {
            withRegionClip(gc, reg, w, h, paint);
        } else {
            paint.run();
        }
    }

    private static void drawWrappedLines(GraphicsContext gc, String text, double x, double y, double maxWidth,
                                        double fontSize, double lineSpacing, TextAlignment align) {
        if (text.isEmpty()) return;
        List<String> lines = wrapText(text, maxWidth, gc.getFont());
        gc.setTextAlign(align);
        double yy = y;
        for (String line : lines) {
            gc.fillText(line, x, yy);
            yy += fontSize + lineSpacing;
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private static void drawFormalCardBackground(GraphicsContext gc, double w, double h) {
        gc.setFill(Color.rgb(248, 250, 252));
        gc.fillRect(0, 0, w, h);
        gc.setStroke(Color.rgb(203, 213, 225));
        gc.setLineWidth(Math.max(1, Math.min(w, h) * 0.006));
        gc.strokeRect(1, 1, w - 2, h - 2);
    }

    /**
     * Cover-style crop inside a rectangle or ellipse (portrait).
     */
    private static void drawCoverPhoto(GraphicsContext gc, Image photo, double px, double py, double pw, double ph, double cardW, boolean circular) {
        double iw = photo.getWidth();
        double ih = photo.getHeight();
        if (iw <= 0 || ih <= 0 || pw <= 0 || ph <= 0) {
            return;
        }
        gc.save();
        gc.beginPath();
        if (circular) {
            double cx = px + pw / 2;
            double cy = py + ph / 2;
            double rx = pw / 2;
            double ry = ph / 2;
            if (rx > 0 && ry > 0) {
                gc.moveTo(cx + rx, cy);
                gc.arc(cx, cy, rx, ry, 0, 360);
                gc.closePath();
            } else {
                gc.rect(px, py, pw, ph);
            }
        } else {
            gc.rect(px, py, pw, ph);
        }
        gc.clip();
        double scale = Math.max(pw / iw, ph / ih);
        double dw = iw * scale;
        double dh = ih * scale;
        double dx = px + (pw - dw) / 2;
        double dy = py + (ph - dh) / 2;
        gc.drawImage(photo, dx, dy, dw, dh);
        gc.restore();

        gc.setStroke(Color.rgb(30, 41, 59));
        gc.setLineWidth(Math.max(1, cardW * 0.004));
        if (circular) {
            gc.strokeOval(px, py, pw, ph);
        } else {
            gc.strokeRect(px, py, pw, ph);
        }
    }

    private static List<String> wrapText(String text, double maxWidth, Font font) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        String normalized = text.replace('\r', ' ').replace('\n', ' ');
        String[] words = normalized.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            String trial = line.isEmpty() ? word : line + " " + word;
            if (measureWidth(trial, font) <= maxWidth) {
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (measureWidth(word, font) <= maxWidth) {
                    line.append(word);
                } else {
                    // Long token: hard-break
                    for (String part : breakLongWord(word, maxWidth, font)) {
                        lines.add(part);
                    }
                }
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.isEmpty() ? List.of(normalized) : lines;
    }

    private static List<String> breakLongWord(String word, double maxWidth, Font font) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            cur.append(word.charAt(i));
            if (measureWidth(cur.toString(), font) > maxWidth && cur.length() > 1) {
                cur.deleteCharAt(cur.length() - 1);
                parts.add(cur.toString());
                cur.setLength(0);
                cur.append(word.charAt(i));
            }
        }
        if (!cur.isEmpty()) parts.add(cur.toString());
        return parts;
    }

    private static double measureWidth(String s, Font font) {
        Text t = new Text(s);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    private static Font font(double size, FontWeight weight) {
        return Font.font(FONT_FAMILY, weight, size);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void withRegionClip(GraphicsContext gc, TemplateRegion reg, double w, double h, Runnable draw) {
        if (reg == null) {
            draw.run();
            return;
        }
        gc.save();
        double rx = reg.x() * w;
        double ry = reg.y() * h;
        double rw = reg.width() * w;
        double rh = reg.height() * h;
        gc.beginPath();
        gc.rect(rx, ry, rw, rh);
        gc.clip();
        draw.run();
        gc.restore();
    }

    private static void drawQr(GraphicsContext gc, double w, double h, String localBaseUrl, String cloudUrl, IdRecord r,
                               TemplateRegion qrReg, TemplateFieldStyle style, boolean allowPlaceholder) {
        if (style != null && !style.visible()) return;
        String url = null;
        if (cloudUrl != null && !cloudUrl.isBlank()) {
            url = cloudUrl.trim();
        } else if (localBaseUrl != null && !localBaseUrl.isBlank()) {
            String uid = (r.qrUid() != null && !r.qrUid().isBlank()) ? r.qrUid() : String.valueOf(r.id());
            url = localBaseUrl.trim() + "/qr/" + uid;
        }
        double qrSize;
        double qx, qy;
        if (qrReg != null) {
            double rx = qrReg.x() * w;
            double ry = qrReg.y() * h;
            double rw = qrReg.width() * w;
            double rh = qrReg.height() * h;
            qrSize = Math.min(rw, rh);
            qx = rx + (rw - qrSize) / 2.0;
            qy = ry + (rh - qrSize) / 2.0;
        } else {
            qx = DEF_QR_X * w;
            qy = DEF_QR_Y * h;
            qrSize = Math.min(w, h) * DEF_QR_SIZE;
        }
        int size = (int) Math.round(qrSize);
        if (url == null || url.isBlank()) {
            if (allowPlaceholder) {
                drawQrPlaceholder(gc, qx, qy, size);
            }
            return;
        }
        Image qr = createQrImage(url, size);
        if (qr != null) {
            gc.drawImage(qr, qx, qy, size, size);
        }
    }

    private static void drawQrPlaceholder(GraphicsContext gc, double x, double y, double size) {
        double s = Math.max(8, size);
        gc.save();
        gc.setFill(Color.WHITE);
        gc.fillRect(x, y, s, s);
        gc.setStroke(Color.rgb(148, 163, 184));
        gc.setLineWidth(Math.max(1, s * 0.02));
        gc.strokeRect(x, y, s, s);
        gc.setStroke(Color.rgb(148, 163, 184, 0.7));
        gc.setLineDashes(6, 4);
        gc.strokeLine(x, y, x + s, y + s);
        gc.strokeLine(x + s, y, x, y + s);
        gc.setLineDashes(null);
        gc.setFill(Color.rgb(71, 85, 105));
        gc.setFont(font(Math.max(10, s * 0.16), FontWeight.SEMI_BOLD));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("QR", x + s / 2.0, y + s / 2.0 - (s * 0.08));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.restore();
    }

    private static Image createQrImage(String text, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size);
            WritableImage image = new WritableImage(size, size);
            PixelWriter pw = image.getPixelWriter();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pw.setColor(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return image;
        } catch (WriterException e) {
            return null;
        }
    }

    private static Image loadImage(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            if (Files.exists(Path.of(path))) {
                Image img = new Image("file:" + path);
                return img.isError() ? null : img;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Returns the native dimensions of the template image [width, height], or null if the image cannot be loaded.
     * Use this so generated IDs are output at the same size as the reference (no scaling).
     */
    public static int[] getTemplateDimensions(String path) {
        Image img = loadImage(path);
        if (img == null) return null;
        int iw = (int) Math.round(img.getWidth());
        int ih = (int) Math.round(img.getHeight());
        return iw > 0 && ih > 0 ? new int[]{ iw, ih } : null;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String blueprintValue(String fieldName, String label, IdRecord r) {
        if (fieldName == null) return "Value";
        return switch (fieldName) {
            case TemplateRegion.FIELD_NAME -> "Full Name";
            case TemplateRegion.FIELD_ID_NUMBER -> "ID-XXXXXXX";
            case TemplateRegion.FIELD_POSITION -> "Position";
            case TemplateRegion.FIELD_DEPARTMENT -> "Department";
            case TemplateRegion.FIELD_DATE_OF_BIRTH -> "Date of Birth";
            case TemplateRegion.FIELD_ADDRESS -> "Address";
            case TemplateRegion.FIELD_CONTACT -> "Contact Number";
            case TemplateRegion.FIELD_EMERGENCY -> "Emergency Contact";
            default -> {
                // Custom fields (or any other dynamic text field): show a stable placeholder.
                if (DbHelper.isCustomFieldMarker(fieldName)) {
                    yield (label == null || label.isBlank()) ? "Custom Field" : label;
                }
                yield (label == null || label.isBlank()) ? "Value" : label;
            }
        };
    }

    private static String styleKey(String side, String field) {
        return side + ":" + field;
    }

    private static TextAlignment resolveAlign(TemplateFieldStyle style) {
        if (style == null || style.align() == null) return TextAlignment.CENTER;
        return switch (style.align()) {
            case "Left" -> TextAlignment.LEFT;
            case "Right" -> TextAlignment.RIGHT;
            default -> TextAlignment.CENTER;
        };
    }

}
