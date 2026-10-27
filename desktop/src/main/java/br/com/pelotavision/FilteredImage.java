package br.com.pelotavision;

import java.awt.image.BufferedImage;

/**
 * 0.5.2 (paridade com FilteredPhoto do Android 0.15.2): cópia da imagem analisada com a janela da análise
 * substituída pelos tons de cinza filtrados. Só exibição e relatório; as medidas usam os pixels originais.
 */
final class FilteredImage {
    private FilteredImage() {}

    static BufferedImage compose(BufferedImage image, byte[] gray, AnalysisWindow window) {
        if (image.getWidth() != window.fullWidth || image.getHeight() != window.fullHeight)
            throw new IllegalArgumentException("Imagem e janela incompatíveis.");
        if (gray == null || gray.length != window.width * window.height)
            throw new IllegalArgumentException("Imagem filtrada incompatível com a janela.");
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        int[] row = new int[window.width];
        for (int y = 0; y < window.height; y++) {
            int base = y * window.width;
            for (int x = 0; x < window.width; x++) {
                int v = gray[base + x] & 255;
                row[x] = (v << 16) | (v << 8) | v;
            }
            out.setRGB(window.left, window.top + y, window.width, 1, row, 0, window.width);
        }
        return out;
    }
}
