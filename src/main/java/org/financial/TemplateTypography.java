package org.financial;

/**
 * Single source of truth for template text size: stored {@link TemplateFieldStyle#fontSize()} scales
 * every tier linearly from a named default at {@link #REFERENCE_MIN_SIDE}.
 * <p>
 * At {@code fontSize == 12} and a card whose shorter edge equals {@link #REFERENCE_MIN_SIDE}, nominal sizes match:
 * <ul>
 *   <li>{@link Tier#NAME} — ~18–20 px (display name)</li>
 *   <li>{@link Tier#TITLE} — ~14–16 px (ID number)</li>
 *   <li>{@link Tier#BODY} — ~12–14 px (position, department, back body)</li>
 *   <li>{@link Tier#META} — ~10–12 px (date of birth value)</li>
 *   <li>{@link Tier#CAPTION} — ~10–12 px (uppercase field labels)</li>
 * </ul>
 * Other card sizes scale proportionally to {@code minSide / REFERENCE_MIN_SIDE}. No separate transform is applied to glyphs.
 */
public final class TemplateTypography {

    /** Shorter card edge (px) at which {@link Tier} defaults match the bands above. */
    public static final double REFERENCE_MIN_SIDE = 520.0;

    /** Stored panel value: neutral scale (1.0× tier default at reference). */
    public static final double STYLE_BASELINE = 12.0;

    /** Line height multiplier for wrapped lines (spacing = font × (this − 1)), target band 1.2–1.4. */
    public static final double LINE_HEIGHT_RATIO = 1.30;

    /** Space below caption before value, as multiple of caption font size. */
    public static final double CAPTION_TO_VALUE_LEADING = 1.32;

    public enum Tier {
        /** Cardholder name */
        NAME(19.0, 0.068),
        /** ID number */
        TITLE(15.0, 0.056),
        /** Position, department, address lines, etc. */
        BODY(13.0, 0.052),
        /** Date of birth value and similar secondary text */
        META(11.0, 0.044),
        /** Uppercase labels above values */
        CAPTION(11.0, 0.044);

        private final double defaultPxAtReferenceMinSide;
        private final double maxTotalFracOfMinSide;

        Tier(double defaultPxAtReferenceMinSide, double maxTotalFracOfMinSide) {
            this.defaultPxAtReferenceMinSide = defaultPxAtReferenceMinSide;
            this.maxTotalFracOfMinSide = maxTotalFracOfMinSide;
        }

        public double defaultPxAtReferenceMinSide() {
            return defaultPxAtReferenceMinSide;
        }

        public double maxPxAfterScale(double minSide) {
            return minSide * maxTotalFracOfMinSide;
        }
    }

    private TemplateTypography() {}

    /**
     * Extra vertical gap between baselines beyond the font em-box (for {@code fillText} stacking).
     */
    public static double lineSpacingPx(double fontPx) {
        return fontPx * (LINE_HEIGHT_RATIO - 1.0);
    }

    /**
     * Vertical offset from caption line top to value line top.
     */
    public static double captionToValueOffsetPx(double captionFontPx) {
        return captionFontPx * CAPTION_TO_VALUE_LEADING;
    }

    /**
     * Effective pixel size from stored {@code fontSize} only — box resize does not change this.
     * Preview, export, and editor use this same path via {@link IdImageGenerator}.
     */
    public static double effectiveFontPx(TemplateFieldStyle style, Tier tier, double minSide) {
        double scale = minSide / REFERENCE_MIN_SIDE;
        double basePx = tier.defaultPxAtReferenceMinSide * scale;
        double fs = STYLE_BASELINE;
        if (style != null) {
            int s = style.fontSize();
            if (s < 8) s = 8;
            if (s > 144) s = 144;
            fs = s;
        }
        double px = basePx * (fs / STYLE_BASELINE);
        return clamp(px, 5, Math.min(tier.maxPxAfterScale(minSide), minSide * 0.11));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
