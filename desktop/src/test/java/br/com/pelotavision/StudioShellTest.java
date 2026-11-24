package br.com.pelotavision;

import org.junit.Test;
import javax.swing.*;
import static org.junit.Assert.*;

public class StudioShellTest {
    @Test public void allSixStagesKeepControlsAndRejectInvalidStage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel[] panels = new JPanel[6];
            JButton[] actions = new JButton[6];
            for (int i = 0; i < 6; i++) { actions[i] = new JButton("acao " + i); panels[i] = StudioShell.stack(actions[i]); }
            StudioShell shell = new StudioShell(new JPanel(), new JPanel(), panels);
            for (int i = 0; i < 6; i++) { shell.select(i); assertEquals(i, shell.stage()); assertSame(panels[i], actions[i].getParent()); }
            try { shell.select(6); fail("etapa invalida aceita"); } catch (IllegalArgumentException expected) { }
            assertEquals(5, shell.stage());
        });
    }
    @Test public void reportStageShowsPdfAndCollapsesInspector() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel[] panels = new JPanel[6];
            for (int i = 0; i < 6; i++) panels[i] = StudioShell.stack(new JLabel("etapa " + i));
            JPanel viewer = new JPanel(), inspector = new JPanel(), report = new JPanel();
            StudioShell shell = new StudioShell(viewer, inspector, panels, report);
            shell.select(4);
            assertTrue(inspector.isVisible()); assertTrue(viewer.isVisible()); assertFalse(report.isVisible());
            shell.select(5);
            assertFalse(inspector.isVisible()); assertTrue(report.isVisible()); assertFalse(viewer.isVisible());
            shell.select(0);
            assertTrue(inspector.isVisible()); assertTrue(viewer.isVisible());
        });
    }
    @Test public void chartPaletteKeepsDataColorsAndDarkensBackground() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(3, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xffffff); img.setRGB(1, 0, 0x404040); img.setRGB(2, 0, HistogramView.ALL.getRGB() & 0xffffff);
        StudioChartPalette.apply(img);
        assertEquals(StudioTheme.SURFACE.getRGB() & 0xffffff, img.getRGB(0, 0) & 0xffffff);
        assertTrue(((img.getRGB(1, 0) >> 16) & 255) > 150);
        assertEquals(HistogramView.ALL.getRGB() & 0xffffff, img.getRGB(2, 0) & 0xffffff);
    }
    @Test public void requiresExactlySixContextPanels() {
        try { new StudioShell(new JPanel(), new JPanel(), new JPanel[0]); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void developedIconIsPackagedAtAllRequiredSizes() {
        java.util.List<java.awt.Image> icons = MainFrame.appIcons();
        assertEquals(6, icons.size());
        assertEquals(16, icons.get(0).getWidth(null));
        assertEquals(256, icons.get(icons.size() - 1).getWidth(null));
    }
}
