package com.holtherndon.bazelviz.ui.theme;

import com.formdev.flatlaf.FlatLightLaf;

/** Central look-and-feel setup so no other class touches FlatLaf directly. */
public final class Themes {

    private Themes() {}

    /** Installs the default theme. Must run on the EDT before any component is created. */
    public static void installDefault() {
        FlatLightLaf.setup();
    }
}
