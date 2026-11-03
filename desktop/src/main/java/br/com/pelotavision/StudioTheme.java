package br.com.pelotavision;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;

/** Tema local sem bibliotecas novas; organizacao inspirada no MicroscopiaAI Studio. */
final class StudioTheme {
    static final Color BACKGROUND = new Color(11, 18, 32);
    static final Color SURFACE = new Color(18, 31, 54);
    static final Color TEXT = new Color(230, 237, 247);
    static final Color MUTED = new Color(142, 163, 196);
    static final Color ACCENT = new Color(47, 128, 255);

    private StudioTheme() {}

    static void install() {
        try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); }
        catch (Exception ignored) { }
        UIManager.put("control", new ColorUIResource(SURFACE));
        UIManager.put("info", new ColorUIResource(SURFACE));
        UIManager.put("nimbusBase", new ColorUIResource(28, 48, 80));
        UIManager.put("nimbusBlueGrey", new ColorUIResource(34, 54, 90));
        UIManager.put("nimbusLightBackground", new ColorUIResource(BACKGROUND));
        UIManager.put("text", new ColorUIResource(TEXT));
        UIManager.put("nimbusSelectionBackground", new ColorUIResource(ACCENT));
        UIManager.put("nimbusSelectedText", new ColorUIResource(Color.WHITE));
        UIManager.put("defaultFont", new FontUIResource("Segoe UI", Font.PLAIN, 13));
        UIManager.put("TitledBorder.titleColor", new ColorUIResource(MUTED));
        UIManager.put("ToolTip.background", new ColorUIResource(SURFACE));
        UIManager.put("ToolTip.foreground", new ColorUIResource(TEXT));
    }
}
