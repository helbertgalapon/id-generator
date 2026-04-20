package org.financial;

/**
 * Persistent style settings for a template field.
 */
public record TemplateFieldStyle(
    String side,
    String fieldName,
    int fontSize,
    boolean bold,
    String align,
    boolean locked,
    boolean visible,
    /** When true and {@code fieldName} is a photo region, clip the portrait to an ellipse inside the bounds. */
    boolean photoCircular
) {}
