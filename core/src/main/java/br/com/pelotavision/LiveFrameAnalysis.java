package br.com.pelotavision;

import org.opencv.core.Mat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 0.16.0: pipeline único do ao vivo, comum a Android e Windows.
 *
 * O que a foto já fazia e passa a valer em cada quadro ao vivo:
 *  - ROI (guardada em FRAÇÕES do quadro, para sobreviver à troca de resolução entre ao vivo e foto);
 *  - filtro de luz (o mesmo {@link LowLightPreprocessor} da foto): o filtro só PROPÕE as pelotas;
 *    as elipses são ajustadas nos pixels ORIGINAIS do quadro, como na foto;
 *  - escala física: a calibração da última foto (ou feita sobre o próprio quadro) é transferida para a
 *    resolução do quadro por {@link ScaleTransfer}; histograma, D10/D50/D90 e Rosin–Rammler em mm;
 *  - mapa granulométrico (mesma distribuição).
 *
 * Trincas continuam só na foto: no quadro de 640 px a pelota tem poucos pixels e o classificador
 * declara a triagem inconclusiva por construção.
 */
public final class LiveFrameAnalysis {
    private LiveFrameAnalysis() {}

    /** Lado de trabalho e escalas do ao vivo (iguais aos da 0.15.x: Android e Windows). */
    public static final int WORK_SIDE = 640, SCALES = 8;

    /** Filtro de luz no ao vivo. AUTO aplica GAMMA_CLAHE só quando a avaliação do quadro pede correção. */
    public enum Light { OFF, AUTO, GAMMA_CLAHE, DENOISE_CLAHE, RETINEX;
        public LowLightPreprocessor.Mode fixedMode() {
            switch (this) {
                case GAMMA_CLAHE: return LowLightPreprocessor.Mode.GAMMA_CLAHE;
                case DENOISE_CLAHE: return LowLightPreprocessor.Mode.DENOISE_CLAHE;
                case RETINEX: return LowLightPreprocessor.Mode.RETINEX;
                default: return null;
            }
        }
        public String label() {
            switch (this) {
                case OFF: return "Desligado";
                case AUTO: return "Automático (só se escuro)";
                case GAMMA_CLAHE: return "Gama + CLAHE";
                case DENOISE_CLAHE: return "Suavizar + CLAHE";
                default: return "Retinex";
            }
        }
        public static Light fromName(String s) { try { return s == null ? OFF : valueOf(s); } catch (IllegalArgumentException e) { return OFF; } }
    }

    /**
     * Escala da foto (ou de um quadro) levada para outra resolução do MESMO enquadramento.
     * Recusa (devolve null) quando a proporção do quadro difere da origem em mais de 2%: aí o campo de visão
     * provavelmente mudou e a escala não vale.
     */
    public static final class ScaleTransfer {
        public final Granulometry.Settings settings;
        public final int sourceWidth, sourceHeight;
        public final String origin;
        /** Borda usada quando a escala veio do recipiente no próprio quadro (para o usuário conferir); senão null. */
        public final ContainerCalibration.Circle circle;

        public ScaleTransfer(Granulometry.Settings settings, int sourceWidth, int sourceHeight, String origin) {
            this(settings, sourceWidth, sourceHeight, origin, null);
        }

        public ScaleTransfer(Granulometry.Settings settings, int sourceWidth, int sourceHeight, String origin, ContainerCalibration.Circle circle) {
            if (settings == null || sourceWidth <= 0 || sourceHeight <= 0) throw new IllegalArgumentException("Escala inválida.");
            this.settings = settings; this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight; this.origin = origin; this.circle = circle;
        }

        public boolean compatible(int w, int h) {
            if (w <= 0 || h <= 0) return false;
            double a = sourceWidth / (double) sourceHeight, b = w / (double) h;
            return Math.abs(a / b - 1) <= .02;
        }

        /** px/mm no quadro (w × h), ou NaN se incompatível ou sem escala física. */
        public double pixelsPerMm(int w, int h) {
            if (settings.pixelsOnly() || settings.estimated() || !compatible(w, h)) return Double.NaN;
            return settings.originalPixelsPerMm * Math.sqrt((double) w / sourceWidth * ((double) h / sourceHeight));
        }

        /** Configuração do histograma para o quadro; null = usar pixels. Média assumida é refeita em cada quadro. */
        public Granulometry.Settings forFrame(int w, int h) {
            if (settings.pixelsOnly()) return null;
            if (settings.estimated() && Double.isFinite(settings.knownMeanMm))
                return Granulometry.Settings.estimatedMean(settings.knownMeanMm, settings.binWidthMm, settings.excludePartial, settings.excludeReview);
            double ppm = pixelsPerMm(w, h);
            if (!Double.isFinite(ppm) || ppm <= 0) return null;
            return new Granulometry.Settings(ppm, settings.binWidthMm, settings.excludePartial, settings.excludeReview);
        }

        public String describe(int w, int h) {
            double ppm = pixelsPerMm(w, h);
            if (settings.pixelsOnly()) return "sem escala física (px)";
            if (settings.estimated()) return String.format(Locale.ROOT, "média assumida %.1f mm (estimada)", settings.knownMeanMm);
            if (!Double.isFinite(ppm)) return "escala da foto não vale para este enquadramento (" + w + "×" + h + "): recalibre";
            return String.format(Locale.ROOT, "%.3f px/mm no quadro %d×%d (%s)", ppm, w, h, origin == null ? "calibração" : origin);
        }
    }

    public static final class Request {
        public final DetectionTuner.Option option;
        /** ROI em frações do quadro; null = quadro inteiro. */
        public final RoiFraction roiFraction;
        public final Light light;
        public final ScaleTransfer scale;
        public final RosinRammler.Basis basis;
        public final boolean keepFiltered;

        public Request(DetectionTuner.Option option, RoiFraction roiFraction, Light light, ScaleTransfer scale,
                       RosinRammler.Basis basis, boolean keepFiltered) {
            if (option == null) throw new IllegalArgumentException("Ajuste ausente.");
            this.option = option; this.roiFraction = roiFraction; this.light = light == null ? Light.OFF : light;
            this.scale = scale; this.basis = basis == null ? RosinRammler.Basis.NUMBER : basis; this.keepFiltered = keepFiltered;
        }
    }

    public static final class Output implements AutoCloseable {
        public final PelletDetector.Result result;
        /** Quadro filtrado (cinza, mesmo tamanho) quando pedido e aplicado; pertence ao chamador via {@link #close()}. */
        public Mat filtered;
        public final LowLightPreprocessor.Mode appliedLight;
        public final LowLightPreprocessor.Quality quality;
        public final InterestRegion roi;
        public final Granulometry.Distribution distribution;
        public final RosinRammler.Fit fit;
        public final String scaleNote;

        Output(PelletDetector.Result result, Mat filtered, LowLightPreprocessor.Mode applied, LowLightPreprocessor.Quality quality,
               InterestRegion roi, Granulometry.Distribution distribution, RosinRammler.Fit fit, String scaleNote) {
            this.result = result; this.filtered = filtered; appliedLight = applied; this.quality = quality; this.roi = roi;
            this.distribution = distribution; this.fit = fit; this.scaleNote = scaleNote;
        }

        public boolean physical() { return distribution != null && !distribution.settings.pixelsOnly(); }

        @Override public void close() { if (filtered != null) { filtered.release(); filtered = null; } }
    }

    /**
     * ROI guardada em proporção do quadro, para valer em qualquer resolução do mesmo enquadramento.
     * Retângulo: bordas em fração da largura/altura. Círculo: centro em fração e diâmetro em fração do MENOR lado
     * (assim continua círculo em qualquer proporção).
     */
    public static final class RoiFraction {
        public final InterestRegion.Shape shape;
        public final double left, top, right, bottom, cx, cy, diameter;
        private RoiFraction(InterestRegion.Shape shape, double l, double t, double r, double b, double cx, double cy, double d) {
            this.shape = shape; left = l; top = t; right = r; bottom = b; this.cx = cx; this.cy = cy; diameter = d;
        }
    }

    /** ROI em pixels do quadro a partir das frações; null se ausente, fora do quadro ou pequena demais (< 32 px). */
    public static InterestRegion roiInFrame(RoiFraction f, int w, int h) {
        if (f == null) return null;
        double l, t, r, b;
        if (f.shape == InterestRegion.Shape.CIRCLE) {
            double d = f.diameter * Math.min(w, h), cx = f.cx * w, cy = f.cy * h;
            l = cx - d / 2; r = cx + d / 2; t = cy - d / 2; b = cy + d / 2;
            if (l < -1e-6 || t < -1e-6 || r > w + 1e-6 || b > h + 1e-6) return null;
            l = Math.max(0, l); t = Math.max(0, t);
            double side = Math.min(Math.min(w, r) - l, Math.min(h, b) - t);
            r = l + side; b = t + side;
        } else {
            l = clamp(f.left) * w; t = clamp(f.top) * h; r = clamp(f.right) * w; b = clamp(f.bottom) * h;
        }
        if (r - l < 32 || b - t < 32) return null;
        return new InterestRegion(f.shape, l, t, r, b);
    }

    /** Frações de uma ROI em pixels de uma imagem w × h (a foto, por exemplo). */
    public static RoiFraction toFraction(InterestRegion roi, int w, int h) {
        if (roi == null || w <= 0 || h <= 0) return null;
        return new RoiFraction(roi.shape, roi.left / w, roi.top / h, roi.right / w, roi.bottom / h,
                roi.cx() / w, roi.cy() / h, roi.width() / Math.min(w, h));
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    /**
     * Analisa um quadro cinza (8 bits). O quadro não é modificado. Coordenadas do resultado: quadro inteiro.
     */
    public static Output run(Mat gray, Request req) {
        int w = gray.cols(), h = gray.rows();
        InterestRegion roi = roiInFrame(req.roiFraction, w, h);
        LowLightPreprocessor.Quality quality = null;
        LowLightPreprocessor.Mode mode = req.light.fixedMode();
        if (req.light == Light.AUTO) {
            quality = LowLightPreprocessor.assess(gray);
            mode = quality.needsCorrection() ? LowLightPreprocessor.Mode.GAMMA_CLAHE : null;
        }
        Mat enhanced = null;
        PelletDetector.Result result;
        try {
            if (mode != null) enhanced = LowLightPreprocessor.apply(gray, mode);
            result = detect(gray, enhanced, req.option, roi);
        } catch (RuntimeException e) {
            if (enhanced != null) enhanced.release();
            throw e;
        }
        Mat keep = null;
        if (enhanced != null) {
            if (req.keepFiltered) keep = enhanced; else enhanced.release();
        }
        Granulometry.Distribution dist = distribution(result, req.scale);
        RosinRammler.Fit fit = dist == null ? null : RosinRammler.fit(dist, req.basis);
        String note = req.scale == null ? "sem escala física (px)" : req.scale.describe(w, h);
        return new Output(result, keep, mode == null ? LowLightPreprocessor.Mode.ORIGINAL : mode, quality, roi, dist, fit, note);
    }

    /** Propostas no quadro filtrado (se houver), elipses nos pixels originais; só a ROI, coordenadas globais. */
    static PelletDetector.Result detect(Mat original, Mat enhanced, DetectionTuner.Option option, InterestRegion roi) {
        AnalysisWindow win = roi == null ? null : new AnalysisWindow(original.cols(), original.rows(), roi);
        if (win != null && (win.width < 32 || win.height < 32)) win = null;
        Mat o = win == null ? original : win.cropGray(original);
        Mat e = enhanced == null ? null : win == null ? enhanced : win.cropGray(enhanced);
        try {
            PelletDetector.Result seeds = DetectionTuner.seeds(e == null ? o : e, option, WORK_SIDE, SCALES);
            PelletDetector.Result refined = new EllipseFitter().refine(o, seeds, option.belt);
            return win == null ? refined : win.toGlobal(win.filterLocal(refined));
        } finally {
            if (win != null) { o.release(); if (e != null) e.release(); }
        }
    }

    /** Distribuição na escala transferida para o quadro; sem escala válida, em pixels (classe de 10 px). */
    public static Granulometry.Distribution distribution(PelletDetector.Result r, ScaleTransfer scale) {
        List<Granulometry.Item> items = new ArrayList<>();
        for (PelletDetector.Detection d : r.circles) items.add(new Granulometry.Item(2 * d.radius, d.partial, d.needsReview));
        try {
            Granulometry.Settings s = scale == null ? null : scale.forFrame(r.width, r.height);
            if (s != null) return Granulometry.compute(items, s, r.width, r.height, r.width, r.height);
        } catch (RuntimeException ignored) {
            // Escala recusada (classe pequena demais, média assumida sem pelotas): cai para pixels, sem erro no ao vivo.
        }
        try { return ReportTables.pixelHistogram(r); } catch (RuntimeException e) { return null; }
    }

    /**
     * Calibração direto no quadro ao vivo pela borda interna do recipiente (detecção automática da 0.15.3).
     * Devolve a escala no quadro, ou null se a borda não foi achada com segurança.
     */
    public static ScaleTransfer containerOnFrame(Mat gray, double innerDiameterMm, ContainerCalibration.Plane plane,
                                                 double binWidthMm, boolean excludePartial, boolean excludeReview) {
        if (!(innerDiameterMm > 0)) throw new IllegalArgumentException("Informe o diâmetro interno em mm.");
        int w = gray.cols(), h = gray.rows();
        byte[] px = new byte[w * h];
        gray.get(0, 0, px);
        ContainerCalibration.Detection d = ContainerRimDetector.detect(px, w, h);
        if (d == null) return null;
        ContainerCalibration.Circle c = d.circle(plane == null ? ContainerCalibration.Plane.RIM : plane);
        if (c == null || c.framingProblem(w, h) != null) return null;
        double ppm = 2 * c.r / innerDiameterMm;
        Granulometry.Settings s = new Granulometry.Settings(ppm, binWidthMm, excludePartial, excludeReview);
        return new ScaleTransfer(s, w, h, String.format(Locale.ROOT, "recipiente no ao vivo, Ø %.1f mm = %.1f px", innerDiameterMm, 2 * c.r), c);
    }
}
