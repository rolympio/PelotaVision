package br.com.pelotavision;

import java.awt.image.BufferedImage;

/** Adaptação apenas para a tela; as figuras exportadas mantêm a paleta original. */
final class StudioChartPalette {
    private StudioChartPalette() {}
    static void apply(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 5) {
                int level = (r + g + b) / 3;
                double t = level / 255.0;
                int nr = (int) Math.round(230 * (1 - t) + 18 * t);
                int ng = (int) Math.round(237 * (1 - t) + 31 * t);
                int nb = (int) Math.round(247 * (1 - t) + 54 * t);
                image.setRGB(x, y, 0xff000000 | nr << 16 | ng << 8 | nb);
            }
        }
    }
}
