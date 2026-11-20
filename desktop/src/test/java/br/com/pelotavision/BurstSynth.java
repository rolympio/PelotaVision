package br.com.pelotavision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.util.Random;

/**
 * Cenas sintéticas para os testes da rajada (0.19.0): função contínua da cena integrada sobre a área de
 * cada pixel (supersampling S×S), com deslocamento sub-pixel exato e ruído gaussiano. Convenção do
 * pacote Burst: o quadro com deslocamento d mostra em q o que a base mostra em q − d.
 */
final class BurstSynth {
    private BurstSynth() {}

    interface Scene { double at(double x, double y); }

    /** Discos claros de diâmetro conhecido sobre fundo texturizado (escala: 1 unidade = 1 pixel da base). */
    static Scene discs(double[][] centers, double radius) {
        return (x, y) -> {
            double v = 80 + 12 * Math.sin(2 * Math.PI * x / 37.0) * Math.sin(2 * Math.PI * y / 23.0)
                    + 8 * Math.sin(2 * Math.PI * (x + 2 * y) / 9.7) + 6 * Math.cos(2 * Math.PI * (3 * x - y) / 13.3);
            for (double[] c : centers) {
                double dx = x - c[0], dy = y - c[1];
                if (dx * dx + dy * dy <= radius * radius) return 200 + 10 * Math.sin(2 * Math.PI * (x - y) / 7.1);
            }
            return v;
        };
    }

    static Scene sine(double cyclesPerPixel, double amplitude) {
        return (x, y) -> 128 + amplitude * Math.sin(2 * Math.PI * cyclesPerPixel * x);
    }

    static Scene flat(double level) { return (x, y) -> level; }

    /** Imagem float sem ruído (double[h*w]) do quadro com deslocamento (dx, dy). */
    static double[] renderClean(Scene s, int w, int h, double dx, double dy, int ss) {
        double[] out = new double[w * h];
        double inv = 1.0 / (ss * ss);
        for (int q = 0; q < h; q++) for (int p = 0; p < w; p++) {
            double acc = 0;
            for (int a = 0; a < ss; a++) for (int b = 0; b < ss; b++) {
                double x = p - dx - 0.5 + (b + 0.5) / ss, y = q - dy - 0.5 + (a + 0.5) / ss;
                acc += s.at(x, y);
            }
            out[q * w + p] = acc * inv;
        }
        return out;
    }

    /** Quadro BGR 8 bits (cinza replicado) com ruído gaussiano σ e brilho somado. */
    static Mat frame(Scene s, int w, int h, double dx, double dy, double sigma, double brightness, long seed) {
        double[] clean = renderClean(s, w, h, dx, dy, 6);
        Random rnd = new Random(seed);
        byte[] px = new byte[w * h * 3];
        for (int i = 0; i < clean.length; i++) {
            int v = (int) Math.round(clean[i] + brightness + sigma * rnd.nextGaussian());
            byte b = (byte) Math.max(0, Math.min(255, v));
            px[i * 3] = b; px[i * 3 + 1] = b; px[i * 3 + 2] = b;
        }
        Mat m = new Mat(h, w, CvType.CV_8UC3);
        m.put(0, 0, px);
        return m;
    }

    /** Canal 0 de uma Mat 8 bits como double[]. */
    static double[] channel0(Mat m) {
        int w = m.cols(), h = m.rows(), ch = m.channels();
        byte[] px = new byte[w * h * ch];
        Mat c = m.isContinuous() ? m : m.clone();
        c.get(0, 0, px);
        if (c != m) c.release();
        double[] out = new double[w * h];
        for (int i = 0; i < out.length; i++) out[i] = px[i * ch] & 255;
        return out;
    }

    static double rmse(double[] a, double[] b, int w, int h, int margin) {
        double s = 0; int n = 0;
        for (int y = margin; y < h - margin; y++) for (int x = margin; x < w - margin; x++) { double d = a[y * w + x] - b[y * w + x]; s += d * d; n++; }
        return Math.sqrt(s / n);
    }

    /** Diâmetro equivalente (px) do disco cujo centro é (cx, cy), por fração de área acima do limiar. */
    static double equivalentDiameter(double[] img, int w, int h, double cx, double cy, double halfBox, double low, double high) {
        double area = 0;
        int x0 = (int) Math.floor(cx - halfBox), x1 = (int) Math.ceil(cx + halfBox), y0 = (int) Math.floor(cy - halfBox), y1 = (int) Math.ceil(cy + halfBox);
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
            double f = (img[y * w + x] - low) / (high - low);
            area += Math.max(0, Math.min(1, f));
        }
        return 2 * Math.sqrt(area / Math.PI);
    }
}
