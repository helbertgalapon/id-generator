package org.financial;

public record CustomFieldDef(
    String fieldKey,
    String displayName,
    boolean showFront,
    boolean showBack,
    boolean defaultField
) {
    @Override
    public String toString() {
        String side = showFront && showBack ? "Front, Back" : (showFront ? "Front" : (showBack ? "Back" : "None"));
        String suffix = defaultField ? " (default)" : "";
        return displayName + " [" + side + "]" + suffix;
    }
}
