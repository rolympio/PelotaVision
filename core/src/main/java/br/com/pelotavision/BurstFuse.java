package br.com.pelotavision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.util.Arrays;
import java.util.List;

/**
 * Trilha 1 da rajada — Burst Clear (0.19.0): mediana robusta ponderada, sem mudar resolução.
 *
 * <p>Cada quadro é amostrado em {@code p + d} (ver {@link BurstAlign}) na grade de pixels da base, de
 * modo que a saída tem exatamente o tamanho, as coordenadas e a escala px/mm da foto base: nenhum
 * pixel é inventado e nenhuma coordenada muda. Por pixel, o consenso é a mediana do cinza entre os
 * quadros; cada quadro recebe peso {@code c_k · exp(-½ (r/(κσ))²)} pelo resíduo r contra esse consenso
 * (deghosting: o que se moveu num quadro perde peso em vez de borrar) e a saída é a média ponderada
 * das cores. Peso por cinza, aplicado igual aos três canais, evita franja colorida.</p>
 *
 * <p>Memória: processa em faixas de linhas e libera cada faixa; os N quadros de entrada continuam
 * sendo do chamador.</p>
 */
public final class BurstFuse {
    private BurstFuse() {}

    /** Largura da Gaussiana de robustez, em desvios de ruído. */
    public static final double KAPPA = 2.5;
    /** Piso do desvio usado nos pesos (níveis de cinza), para fotos quase sem ruído. */
    public static final double SIGMA_FLOOR = 1.5;

    /** Resultado da fusão. */
    public static final class Result {
        /** BGR (ou cinza) 8 bits, mesmo tamanho e tipo da base. Pertence ao chamador. */
        public final Mat image;
        /** Peso médio efetivo por quadro (ordem da lista recebida). */
        public final double[] meanWeights;
        public final int frames;

        Result(Mat image, double[] meanWeights, int frames) { this.image = image; this.meanWeights = meanWeights; this.frames = frames; }
    }

    /**
     * Funde {@code images} (8 bits, mesmo tamanho; {@code images[0]} é a base) com os deslocamentos
     * {@code dx/dy} e confianças {@code confidence} (0..1) por quadro. {@code sigma}: ruído estimado
     * da base em níveis de cinza.
     */
    public static Result fuse(List<Mat> images, double[] dx, double[] dy, double[] confidence, double sigma) {
        int n = images.size();
        if (n == 0) throw new IllegalArgumentException("Nada para fundir.");
        Mat base = images.get(0);
        int w = base.cols(), h = base.rows(), ch = base.channels();
        if (ch != 1 && ch != 3) throw new IllegalArgumentException("Use imagens BGR ou cinza de 8 bits.");
        int outType = ch == 3 ? CvType.CV_8UC3 : CvType.CV_8UC1;
        Mat out = new Mat(h, w, outType);
        double s = Math.max(SIGMA_FLOOR, sigma) * KAPPA;
        double inv2s2 = 1.0 / (2 * s * s);
        int strip = Math.max(8, Math.min(256, (int) (1_500_000L / Math.max(1L, (long) w * n * ch))));
        double[] weightSum = new double[n];
        long samples = 0;

        byte[][] rows = new byte[n][];
        float[] gray = new float[n], values = new float[n], sorted = new float[n], wk = new float[n];
        boolean[] valid = new boolean[n];
        Mat[] warped = new Mat[n];
        byte[] outRow = new byte[w * ch * strip];
        for (int y0 = 0; y0 < h; y0 += strip) {
            int rowsHere = Math.min(strip, h - y0);
            try {
                for (int k = 0; k < n; k++) {
                    Mat src = images.get(k);
                    if (k == 0 && dx[0] == 0 && dy[0] == 0) warped[k] = src.rowRange(y0, y0 + rowsHere).clone();
                    else warped[k] = BurstAlign.warpStrip(src, dx[k], dy[k], y0, rowsHere, Imgproc.INTER_CUBIC);
                    int len = w * ch * rowsHere;
                    if (rows[k] == null || rows[k].length < len) rows[k] = new byte[w * ch * strip];
                    warped[k].get(0, 0, rows[k]);
                }
            } finally {
                for (int k = 0; k < n; k++) if (warped[k] != null) { warped[k].release(); warped[k] = null; }
            }
            for (int yy = 0; yy < rowsHere; yy++) {
                int y = y0 + yy;
                for (int x = 0; x < w; x++) {
                    int o = (yy * w + x) * ch;
                    int m = 0;
                    for (int k = 0; k < n; k++) {
                        // Amostra fora da foto do quadro k (bordas replicadas) não conta.
                        double sx = x + dx[k], sy = y + dy[k];
                        valid[k] = k == 0 || (sx >= 0 && sy >= 0 && sx <= w - 1 && sy <= h - 1);
                        float g = ch == 3 ? (0.114f * (rows[k][o] & 255) + 0.587f * (rows[k][o + 1] & 255) + 0.299f * (rows[k][o + 2] & 255)) : (rows[k][o] & 255);
                        gray[k] = g;
                        if (valid[k]) sorted[m++] = g;
                    }
                    float med = median(sorted, m);
                    double total = 0;
                    for (int k = 0; k < n; k++) {
                        if (!valid[k]) { wk[k] = 0; continue; }
                        double r = gray[k] - med;
                        double c = confidence == null ? 1 : confidence[k];
                        wk[k] = (float) (c * Math.exp(-r * r * inv2s2));
                        total += wk[k];
                    }
                    if (total < 1e-6) { // ninguém concorda: fica a base
                        Arrays.fill(wk, 0f); wk[0] = 1f; total = 1;
                    }
                    for (int k = 0; k < n; k++) weightSum[k] += wk[k] / total;
                    samples++;
                    for (int c = 0; c < ch; c++) {
                        double acc = 0;
                        for (int k = 0; k < n; k++) if (wk[k] > 0) acc += wk[k] * (rows[k][o + c] & 255);
                        int v = (int) Math.round(acc / total);
                        outRow[o + c] = (byte) Math.max(0, Math.min(255, v));
                    }
                }
            }
            out.put(y0, 0, Arrays.copyOf(outRow, w * ch * rowsHere));
        }
        double[] mean = new double[n];
        for (int k = 0; k < n; k++) mean[k] = samples == 0 ? 0 : weightSum[k] / samples * n;
        return new Result(out, mean, n);
    }

    /** Mediana dos m primeiros valores; ordena {@code a} no lugar (rascunho do chamador). */
    static float median(float[] a, int m) {
        if (m <= 0) return 0;
        // Inserção: N ≤ 24.
        for (int i = 1; i < m; i++) { float t = a[i]; int j = i - 1; while (j >= 0 && a[j] > t) { a[j + 1] = a[j]; j--; } a[j + 1] = t; }
        return (m & 1) == 1 ? a[m / 2] : 0.5f * (a[m / 2 - 1] + a[m / 2]);
    }
}
