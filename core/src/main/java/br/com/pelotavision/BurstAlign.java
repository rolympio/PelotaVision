package br.com.pelotavision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

/**
 * Registro de rajada (0.19.0): translação global sub-pixel de cada quadro contra a base.
 *
 * <p>Convenção única em todo o pacote Burst: o deslocamento {@code d = (dx, dy)} do quadro k satisfaz
 * {@code quadro_k(p + d) ≈ base(p)}. Ou seja, o conteúdo que está em {@code p} na base aparece em
 * {@code p + d} no quadro k; alinhar é amostrar o quadro em {@code p + d}
 * ({@code warpAffine} com {@code WARP_INVERSE_MAP} e {@code M = [1 0 dx; 0 1 dy]}).</p>
 *
 * <p>Estimativa em duas etapas, como pede o prompt: correlação de fase com janela de Hanning em
 * metade da resolução (parte inteira e ~0,1 px), seguida de ECC por translação na resolução cheia
 * (refino sub-pixel). Uma terceira e quarta estimativas independentes, em duas metades do recorte,
 * medem o erro interno de registro: se as metades discordam, o registro não é confiável para SR.</p>
 */
public final class BurstAlign {
    private BurstAlign() {}

    /** Lado máximo do recorte central usado para registrar (custo, não exatidão). */
    public static final int MAX_REGISTRATION_SIDE = 1024;

    /** Resultado do registro de um quadro. */
    public static final class Offset {
        public final double dx, dy;
        /** Resposta da correlação de fase (0..1, maior = pico mais nítido). */
        public final double response;
        /** Coeficiente final do ECC (0..1) ou NaN se o ECC não convergiu (fica a fase). */
        public final double ecc;
        /** Estimativas independentes nas duas metades do recorte (NaN se indisponíveis). */
        public final double halfAdx, halfAdy, halfBdx, halfBdy;

        public Offset(double dx, double dy, double response, double ecc, double halfAdx, double halfAdy, double halfBdx, double halfBdy) {
            this.dx = dx; this.dy = dy; this.response = response; this.ecc = ecc;
            this.halfAdx = halfAdx; this.halfAdy = halfAdy; this.halfBdx = halfBdx; this.halfBdy = halfBdy;
        }

        public static Offset identity() { return new Offset(0, 0, 1, 1, 0, 0, 0, 0); }

        public double magnitude() { return Math.hypot(dx, dy); }

        /** Discordância entre as metades (px); NaN se alguma metade falhou. */
        public double halfDisagreement() {
            if (!Double.isFinite(halfAdx) || !Double.isFinite(halfBdx)) return Double.NaN;
            return Math.hypot(halfAdx - halfBdx, halfAdy - halfBdy);
        }
    }

    /** Recorte central (ou a ROI dada) limitado a {@link #MAX_REGISTRATION_SIDE}. */
    public static Rect registrationRect(int width, int height, Rect roi) {
        Rect r = roi == null ? new Rect(0, 0, width, height) : clip(roi, width, height);
        int w = Math.min(r.width, MAX_REGISTRATION_SIDE), h = Math.min(r.height, MAX_REGISTRATION_SIDE);
        return new Rect(r.x + (r.width - w) / 2, r.y + (r.height - h) / 2, w, h);
    }

    static Rect clip(Rect r, int width, int height) {
        int x0 = Math.max(0, Math.min(width - 1, r.x)), y0 = Math.max(0, Math.min(height - 1, r.y));
        int x1 = Math.max(x0 + 1, Math.min(width, r.x + r.width)), y1 = Math.max(y0 + 1, Math.min(height, r.y + r.height));
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    /** Cinza float32 (0..255) de uma imagem BGR/BGRA/cinza 8 bits. O chamador libera. */
    public static Mat grayFloat(Mat image) {
        Mat g = new Mat();
        if (image.channels() == 3) Imgproc.cvtColor(image, g, Imgproc.COLOR_BGR2GRAY);
        else if (image.channels() == 4) Imgproc.cvtColor(image, g, Imgproc.COLOR_BGRA2GRAY);
        else image.copyTo(g);
        Mat f = new Mat();
        g.convertTo(f, CvType.CV_32F);
        g.release();
        return f;
    }

    /**
     * Registra {@code frame} contra {@code base} (ambos cinza float32, mesmo tamanho) dentro de {@code rect}.
     * Nunca lança: em falha total devolve deslocamento da fase (ou zero) com {@code ecc = NaN}.
     */
    public static Offset register(Mat baseGray, Mat frameGray, Rect rect) {
        Mat b = baseGray.submat(rect), f = frameGray.submat(rect);
        double[] coarse = phase(b, f, 2);
        double dx = coarse[0], dy = coarse[1], response = coarse[2];
        double[] fine = ecc(b, f, dx, dy);
        double eccValue = Double.NaN;
        if (fine != null) { dx = fine[0]; dy = fine[1]; eccValue = fine[2]; }
        // Metades independentes (lado maior dividido ao meio), partindo da estimativa global.
        double ax = Double.NaN, ay = Double.NaN, bx = Double.NaN, by = Double.NaN;
        boolean wide = rect.width >= rect.height;
        Rect ra = wide ? new Rect(0, 0, rect.width / 2, rect.height) : new Rect(0, 0, rect.width, rect.height / 2);
        Rect rb = wide ? new Rect(rect.width / 2, 0, rect.width - rect.width / 2, rect.height) : new Rect(0, rect.height / 2, rect.width, rect.height - rect.height / 2);
        if (Math.min(ra.width, ra.height) >= 48) {
            double[] ha = ecc(b.submat(ra), f.submat(ra), dx, dy);
            double[] hb = ecc(b.submat(rb), f.submat(rb), dx, dy);
            if (ha != null && hb != null) { ax = ha[0]; ay = ha[1]; bx = hb[0]; by = hb[1]; }
        }
        return new Offset(dx, dy, response, eccValue, ax, ay, bx, by);
    }

    /**
     * Correlação de fase em resolução reduzida por {@code factor}; devolve {dx, dy, resposta} já na
     * escala cheia e na convenção da classe.
     */
    static double[] phase(Mat base, Mat frame, int factor) {
        Mat a = new Mat(), c = new Mat(), win = new Mat();
        try {
            if (factor > 1 && Math.min(base.cols(), base.rows()) / factor >= 64) {
                Imgproc.resize(base, a, new Size(base.cols() / factor, base.rows() / factor), 0, 0, Imgproc.INTER_AREA);
                Imgproc.resize(frame, c, new Size(frame.cols() / factor, frame.rows() / factor), 0, 0, Imgproc.INTER_AREA);
            } else { base.copyTo(a); frame.copyTo(c); factor = 1; }
            Imgproc.createHanningWindow(win, a.size(), CvType.CV_32F);
            double[] response = new double[1];
            Point shift = Imgproc.phaseCorrelate(a, c, win, response);
            // phaseCorrelate devolve o deslocamento de c em relação a a: c(p) = a(p - s) ⇒ c(p + s) = a(p) ⇒ d = s.
            return new double[]{shift.x * factor, shift.y * factor, response[0]};
        } finally { a.release(); c.release(); win.release(); }
    }

    /** ECC por translação a partir de (dx, dy); null se não convergir. {dx, dy, rho}. */
    static double[] ecc(Mat base, Mat frame, double dx, double dy) {
        Mat warp = Mat.eye(2, 3, CvType.CV_32F);
        // findTransformECC exige matrizes contínuas: recortes (submat) são copiados.
        Mat b = base.isContinuous() ? base : base.clone(), f = frame.isContinuous() ? frame : frame.clone();
        try {
            warp.put(0, 2, dx); warp.put(1, 2, dy);
            double rho = Video.findTransformECC(b, f, warp, Video.MOTION_TRANSLATION,
                    new TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 60, 1e-5), new Mat(), 3);
            float[] t = new float[1]; // warp é CV_32F: get(double[]) lançaria
            warp.get(0, 2, t); double ex = t[0];
            warp.get(1, 2, t); double ey = t[0];
            if (!Double.isFinite(ex) || !Double.isFinite(ey) || Math.hypot(ex - dx, ey - dy) > 3) return null;
            return new double[]{ex, ey, rho};
        } catch (RuntimeException e) {
            return null; // CvException: não convergiu
        } finally {
            warp.release();
            if (b != base) b.release();
            if (f != frame) f.release();
        }
    }

    /**
     * Amostra {@code src} em {@code p + d} para as linhas {@code [y0, y0 + rows)} da grade da base
     * (interpolação cúbica, borda replicada). Opera só na faixa necessária da fonte.
     */
    public static Mat warpStrip(Mat src, double dx, double dy, int y0, int rows, int interpolation) {
        int h = src.rows();
        int top = Math.max(0, (int) Math.floor(y0 + dy) - 4);
        int bottom = Math.min(h, (int) Math.ceil(y0 + rows + dy) + 4);
        Mat out = new Mat();
        if (bottom <= top) { // faixa toda fora: replica a borda mais próxima
            top = dy < 0 ? 0 : Math.max(0, h - 1); bottom = top + 1;
        }
        Mat part = src.rowRange(top, bottom);
        Mat m = new Mat(2, 3, CvType.CV_64F);
        m.put(0, 0, 1, 0, dx, 0, 1, dy + y0 - top);
        Imgproc.warpAffine(part, out, m, new Size(src.cols(), rows), interpolation | Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE, new Scalar(0));
        m.release();
        return out;
    }

    /** Alinha a imagem inteira (conveniência para imagens pequenas e testes). */
    public static Mat warp(Mat src, double dx, double dy) {
        return warpStrip(src, dx, dy, 0, src.rows(), Imgproc.INTER_CUBIC);
    }
}
