package org.financial;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

public final class DynamicFieldSync {
    private static final IntegerProperty VERSION = new SimpleIntegerProperty(0);

    private DynamicFieldSync() {}

    public static IntegerProperty versionProperty() {
        return VERSION;
    }

    public static void bump() {
        VERSION.set(VERSION.get() + 1);
    }
}
