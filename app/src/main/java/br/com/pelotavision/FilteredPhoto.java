package br.com.pelotavision;

import android.graphics.Bitmap;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * 0.15.2: foto com o filtro de iluminação aplicado, para EXIBIÇÃO (tela, imagem segmentada, mapa e PDF).
 * O filtro é aplicado exatamente sobre a janela analisada (a mesma ROI cinza usada para propor objetos);
 * fora dela permanecem os pixels originais, que de todo modo ficam fora do recorte do relatório.
 * Elipses, diâmetros e recortes de trinca continuam medidos nos pixels ORIGINAIS: esta imagem nunca é medida.
 */
final class FilteredPhoto {
    private FilteredPhoto() {}

    /** Devolve null quando não há realce (modo original) — quem chama usa então a foto original. */
    static Bitmap render(Bitmap photo, Mat windowGray, AnalysisWindow window, LowLightPreprocessor.Mode mode) {
        if (mode == null || mode == LowLightPreprocessor.Mode.ORIGINAL) return null;
        Mat filtered = LowLightPreprocessor.apply(windowGray, mode);
        try {
            if (filtered.type() != CvType.CV_8UC1 || filtered.cols() != window.width || filtered.rows() != window.height)
                throw new IllegalArgumentException("Filtro com tamanho inesperado.");
            byte[] gray = new byte[window.width * window.height];
            filtered.get(0, 0, gray);
            return compose(photo, gray, window);
        } finally {
            filtered.release();
        }
    }

    /** Cópia da foto com a janela substituída pelos tons de cinza filtrados (width × height, 8 bits). */
    static Bitmap compose(Bitmap photo, byte[] gray, AnalysisWindow window) {
        if (photo.getWidth() != window.fullWidth || photo.getHeight() != window.fullHeight)
            throw new IllegalArgumentException("Foto e janela incompatíveis.");
        if (gray == null || gray.length != window.width * window.height)
            throw new IllegalArgumentException("Imagem filtrada incompatível com a janela.");
        Bitmap out = photo.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) throw new OutOfMemoryError("Sem memória para a imagem filtrada.");
        int[] row = new int[window.width];
        for (int y = 0; y < window.height; y++) {
            int base = y * window.width;
            for (int x = 0; x < window.width; x++) {
                int v = gray[base + x] & 255;
                row[x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            out.setPixels(row, 0, window.width, window.left, window.top + y, window.width, 1);
        }
        return out;
    }
}
