package br.com.pelotavision;

import org.junit.Test;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.Assert.*;

public class ChartTextTest {
    @Test public void wrapsLongLabelsWithoutLosingInformation() {
        Graphics2D g = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            String input = "Pelotas: 157 · trincadas: 14 (20.6% das 68 avaliaveis) · incertas: 89";
            List<String> lines = ChartText.wrap(input, g.getFontMetrics(), 150);
            assertTrue(lines.size() > 1);
            assertEquals(input, String.join(" ", lines));
            for (String line : lines) assertTrue(g.getFontMetrics().stringWidth(line) <= 150);
        } finally { g.dispose(); }
    }
    @Test public void scrollPanelTracksViewportWidth() {
        StudioScrollablePanel panel = new StudioScrollablePanel();
        assertTrue(panel.getScrollableTracksViewportWidth());
        assertFalse(panel.getScrollableTracksViewportHeight());
    }
    @Test public void handlesLongUnbrokenWordWithoutTruncation() {
        Graphics2D g = new BufferedImage(200, 80, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            String text = "inconclusivos-supercompridos";
            List<String> lines = ChartText.wrap(text, g.getFontMetrics(), 30);
            assertTrue(lines.size() > 1);
            assertEquals(text, String.join("", lines));
        } finally { g.dispose(); }
    }
}
