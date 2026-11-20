package br.com.pelotavision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Trilha 0 da rajada (0.19.0): laudo de N fotos da mesma cena, sem produzir imagem final.
 *
 * <p>Mede deslocamentos sub-pixel ({@link BurstAlign}), campo de fases e cobertura da grade 2×,
 * amplitude do tremor, deriva de brilho, nitidez, ruído e o erro interno de registro (duas metades), e
 * decide entre {@link Verdict#NADA_A_FAZER}, {@link Verdict#SOMENTE_MEDIANA} e
 * {@link Verdict#SR_2X_PLAUSIVEL}. Regra de ouro: primeiro medir, depois prometer. A SR 2× nunca é
 * habilitada por padrão; o veredito só libera a opção.</p>
 *
 * <p>Os limiares são constantes nomeadas, calibradas pelo ensaio de 2026-11-20 (fotos de correia do
 * repositório, N = 24, sensor simulado 2× pior): a SR 2× só ganhou da média com ruído ≲ 10/255 e
 * erro de offset ≲ 0,15 px. Por isso o veredito SR raramente dispara com vibração natural, e isso é
 * a resposta certa, não um defeito.</p>
 */
public final class BurstDiagnostics {
    private BurstDiagnostics() {}

    static final Locale BR = Locale.forLanguageTag("pt-BR");

    public enum Verdict { NADA_A_FAZER, SOMENTE_MEDIANA, SR_2X_PLAUSIVEL }

    // ---- Critério SR_2X_PLAUSIVEL (prompt master §2, Trilha 0) ----
    public static final int SR_MIN_FRAMES = 8;
    public static final double SR_MIN_P90_JITTER_PX = 0.15, SR_MAX_P90_JITTER_PX = 2.0;
    public static final double SR_MIN_GRID_COVERAGE = 0.60;
    public static final double SR_MAX_REGISTRATION_RMSE_PX = 0.15;
    public static final double SR_MAX_BRIGHTNESS_DRIFT = 1.5;
    /** Do ensaio: com ruído 35/255 a SR perde da média até com alinhamento perfeito; ganha com ~10/255. */
    public static final double SR_MAX_NOISE_SIGMA = 10.0;

    // ---- Rejeição de quadro ----
    public static final double REJECT_BRIGHTNESS_DRIFT = 6.0;
    public static final double REJECT_MIN_PHASE_RESPONSE = 0.02;
    /** Deslocamento máximo aceito como fração do lado menor do recorte de registro. */
    public static final double REJECT_MAX_SHIFT_FRACTION = 0.10;
    /** Resíduo após alinhar acima de k × o esperado só por ruído indica cena em movimento. */
    public static final double REJECT_MOTION_FACTOR = 3.0, REJECT_MOTION_FLOOR = 2.0;
    /** Metades discordando mais que isto: movimento não-translacional (rotação, objeto andando). */
    public static final double REJECT_HALF_DISAGREEMENT_PX = 1.5;

    /** Um quadro rejeitado e o motivo (chaves estáveis para o JSON). */
    public static final class Rejection {
        public final int index; public final String reason;
        Rejection(int index, String reason) { this.index = index; this.reason = reason; }
    }

    /** Medidas por quadro. */
    public static final class FrameStat {
        public final int index;
        public final BurstAlign.Offset offset;
        public final double sharpness, noiseSigma, brightnessDrift, residual;
        public boolean used = true;
        FrameStat(int index, BurstAlign.Offset offset, double sharpness, double noiseSigma, double brightnessDrift, double residual) {
            this.index = index; this.offset = offset; this.sharpness = sharpness; this.noiseSigma = noiseSigma;
            this.brightnessDrift = brightnessDrift; this.residual = residual;
        }
    }

    /** Laudo completo. Índices referem-se à lista de entrada. */
    public static final class Report {
        public final int requested;
        public final int baseFrame;
        public final List<FrameStat> frames;
        public final List<Rejection> rejected;
        public final double jitterMedian, jitterP90;
        public final double gridCoverage;
        /** Erro interno de registro (px, RMS) estimado pela discordância das metades: rms(|dA-dB|)/2. */
        public final double registrationRmse;
        public final double brightnessDriftP90;
        public final double noiseSigma;
        public final double snrGainDbExpected;
        /** null = desconhecido (rajada de arquivos); true/false vindo do LiveMotion do ao vivo. */
        public final Boolean liveStill;
        public final boolean sceneStill;
        public final Verdict verdict;
        public final List<String> reasons;
        /** Histograma 4×4 das frações (dx mod 1, dy mod 1) dos quadros usados. */
        public final int[][] phaseHistogram;
        public final Rect registrationRect;

        Report(int requested, int baseFrame, List<FrameStat> frames, List<Rejection> rejected, double jitterMedian, double jitterP90,
               double gridCoverage, double registrationRmse, double brightnessDriftP90, double noiseSigma, double snrGainDbExpected,
               Boolean liveStill, boolean sceneStill, Verdict verdict, List<String> reasons, int[][] phaseHistogram, Rect registrationRect) {
            this.requested = requested; this.baseFrame = baseFrame; this.frames = frames; this.rejected = rejected;
            this.jitterMedian = jitterMedian; this.jitterP90 = jitterP90; this.gridCoverage = gridCoverage;
            this.registrationRmse = registrationRmse; this.brightnessDriftP90 = brightnessDriftP90; this.noiseSigma = noiseSigma;
            this.snrGainDbExpected = snrGainDbExpected; this.liveStill = liveStill; this.sceneStill = sceneStill; this.verdict = verdict;
            this.reasons = reasons; this.phaseHistogram = phaseHistogram; this.registrationRect = registrationRect;
        }

        public int usedCount() { int n = 0; for (FrameStat f : frames) if (f.used) n++; return n; }

        public boolean srAllowed() { return verdict == Verdict.SR_2X_PLAUSIVEL; }

        public FrameStat frame(int index) { for (FrameStat f : frames) if (f.index == index) return f; return null; }

        /** Texto do laudo para a tela (português, números com vírgula). */
        public String describe() {
            StringBuilder b = new StringBuilder();
            b.append("Veredito: ").append(verdict).append('\n');
            b.append(String.format(BR, "Quadros: %d pedidos, %d usados, %d rejeitados · base = quadro %d%n",
                    requested, usedCount(), rejected.size(), baseFrame + 1));
            b.append(String.format(BR, "Tremor: mediana %.2f px · p90 %.2f px · cobertura da grade 2× %.0f%%%n",
                    jitterMedian, jitterP90, 100 * gridCoverage));
            b.append(String.format(BR, "Erro interno de registro: %s px · deriva de brilho p90 %.2f níveis%n",
                    Double.isFinite(registrationRmse) ? String.format(BR, "%.3f", registrationRmse) : "n/d", brightnessDriftP90));
            b.append(String.format(BR, "Ruído estimado %.1f níveis · ganho teórico de SNR %.1f dB · cena %s%n",
                    noiseSigma, snrGainDbExpected, sceneStill ? "parada" : "com movimento"));
            for (Rejection r : rejected) b.append("· quadro ").append(r.index + 1).append(" rejeitado: ").append(r.reason).append('\n');
            for (String r : reasons) b.append("· ").append(r).append('\n');
            return b.toString().trim();
        }

        /** org.json do Android declara JSONException checada; por isso {@code throws Exception}, como ReportJson. */
        public JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("verdict", verdict.name());
            j.put("frames_requested", requested);
            j.put("frames_used", usedCount());
            JSONArray rej = new JSONArray();
            for (Rejection r : rejected) { JSONObject o = new JSONObject(); o.put("index", r.index); o.put("reason", r.reason); rej.put(o); }
            j.put("frames_rejected", rej);
            j.put("base_frame", baseFrame);
            JSONObject jit = new JSONObject(); jit.put("median", round(jitterMedian, 4)); jit.put("p90", round(jitterP90, 4));
            j.put("jitter_px", jit);
            j.put("grid2x_coverage", round(gridCoverage, 4));
            j.put("registration_rmse_px", Double.isFinite(registrationRmse) ? round(registrationRmse, 4) : JSONObject.NULL);
            j.put("brightness_drift_p90", round(brightnessDriftP90, 3));
            j.put("noise_estimate", round(noiseSigma, 3));
            j.put("snr_gain_db_expected", round(snrGainDbExpected, 2));
            j.put("live_still", liveStill == null ? JSONObject.NULL : liveStill);
            j.put("scene_still", sceneStill);
            j.put("reasons", new JSONArray(reasons));
            JSONArray ph = new JSONArray();
            for (int[] row : phaseHistogram) ph.put(new JSONArray(row));
            j.put("phase_histogram_4x4", ph);
            JSONObject rr = new JSONObject();
            rr.put("x", registrationRect.x); rr.put("y", registrationRect.y); rr.put("width", registrationRect.width); rr.put("height", registrationRect.height);
            j.put("registration_rect", rr);
            JSONArray fr = new JSONArray();
            for (FrameStat f : frames) {
                JSONObject o = new JSONObject();
                o.put("index", f.index); o.put("used", f.used);
                o.put("dx", round(f.offset.dx, 4)); o.put("dy", round(f.offset.dy, 4));
                o.put("phase_response", round(f.offset.response, 4));
                o.put("ecc", Double.isFinite(f.offset.ecc) ? round(f.offset.ecc, 5) : JSONObject.NULL);
                double hd = f.offset.halfDisagreement();
                o.put("half_disagreement_px", Double.isFinite(hd) ? round(hd, 4) : JSONObject.NULL);
                o.put("sharpness_laplacian_var", round(f.sharpness, 2));
                o.put("noise_sigma", round(f.noiseSigma, 3));
                o.put("brightness_drift", round(f.brightnessDrift, 3));
                o.put("residual_sigma", round(f.residual, 3));
                fr.put(o);
            }
            j.put("frames", fr);
            JSONObject crit = new JSONObject();
            crit.put("min_frames", SR_MIN_FRAMES); crit.put("p90_jitter_px", new JSONArray(new double[]{SR_MIN_P90_JITTER_PX, SR_MAX_P90_JITTER_PX}));
            crit.put("min_grid2x_coverage", SR_MIN_GRID_COVERAGE); crit.put("max_registration_rmse_px", SR_MAX_REGISTRATION_RMSE_PX);
            crit.put("max_brightness_drift", SR_MAX_BRIGHTNESS_DRIFT); crit.put("max_noise_sigma", SR_MAX_NOISE_SIGMA);
            j.put("sr2x_criteria", crit);
            return j;
        }
    }

    /** Arredonda; não-finito vira 0 (org.json do Android recusa NaN). Use {@code JSONObject.NULL} antes quando NaN tiver significado. */
    static double round(double v, int places) {
        if (!Double.isFinite(v)) return 0;
        double k = Math.pow(10, places);
        return Math.round(v * k) / k;
    }

    /**
     * Gera o laudo. {@code images}: N fotos BGR (ou cinza) 8 bits do mesmo tamanho, na ordem de captura.
     * {@code roi}: região usada para registrar (null = centro da imagem). {@code liveStill}: estado do
     * {@link LiveMotion} quando a rajada vem do ao vivo (null se desconhecido).
     */
    public static Report analyze(List<Mat> images, Rect roi, Boolean liveStill) {
        int n = images.size();
        if (n == 0) throw new IllegalArgumentException("Rajada vazia.");
        int w = images.get(0).cols(), h = images.get(0).rows();
        for (Mat m : images) if (m.cols() != w || m.rows() != h) throw new IllegalArgumentException("Todas as fotos da rajada precisam ter o mesmo tamanho.");
        Rect rect = BurstAlign.registrationRect(w, h, roi);

        // Nitidez e ruído por quadro; base = mais nítido (padrão do projeto: variância do Laplaciano).
        List<Mat> grays = new ArrayList<>(n);
        double[] sharp = new double[n], noise = new double[n], level = new double[n];
        try {
            for (int i = 0; i < n; i++) {
                Mat g = BurstAlign.grayFloat(images.get(i));
                grays.add(g);
                Mat crop = g.submat(rect);
                sharp[i] = laplacianVariance(crop);
                noise[i] = noiseSigma(crop);
                level[i] = Core.mean(crop).val[0];
            }
            // Base = mais nítido entre os quadros de brilho típico (um quadro com exposição fora não vira referência).
            List<Double> levels = new ArrayList<>();
            for (double v : level) levels.add(v);
            double typical = percentile(levels, 0.5);
            int base = -1;
            for (int i = 0; i < n; i++) {
                if (Math.abs(level[i] - typical) > REJECT_BRIGHTNESS_DRIFT / 2) continue;
                if (base < 0 || sharp[i] > sharp[base] * 1.0001) base = i;
            }
            if (base < 0) base = 0;
            Mat baseGray = grays.get(base);

            List<FrameStat> stats = new ArrayList<>(n);
            List<Rejection> rejected = new ArrayList<>();
            double maxShift = REJECT_MAX_SHIFT_FRACTION * Math.min(rect.width, rect.height);
            for (int i = 0; i < n; i++) {
                BurstAlign.Offset off = i == base ? BurstAlign.Offset.identity() : BurstAlign.register(baseGray, grays.get(i), rect);
                double[] dr = i == base ? new double[]{0, 0} : driftAndResidual(baseGray, grays.get(i), rect, off);
                FrameStat st = new FrameStat(i, off, sharp[i], noise[i], dr[0], dr[1]);
                String reason = null;
                double expected = Math.sqrt(noise[base] * noise[base] + noise[i] * noise[i]);
                if (i != base) {
                    if (!Double.isFinite(off.dx) || !Double.isFinite(off.dy)) reason = "registro_falhou";
                    else if (off.magnitude() > maxShift) reason = "deslocamento_excessivo";
                    else if (off.response < REJECT_MIN_PHASE_RESPONSE && !Double.isFinite(off.ecc)) reason = "registro_sem_pico";
                    else if (Math.abs(dr[0]) > REJECT_BRIGHTNESS_DRIFT) reason = "deriva_brilho";
                    else if (Double.isFinite(off.halfDisagreement()) && off.halfDisagreement() > REJECT_HALF_DISAGREEMENT_PX) reason = "movimento_nao_translacional";
                    else if (dr[1] > REJECT_MOTION_FACTOR * expected + REJECT_MOTION_FLOOR) reason = "cena_em_movimento";
                }
                if (reason != null) { st.used = false; rejected.add(new Rejection(i, reason)); }
                stats.add(st);
            }
            return conclude(n, base, stats, rejected, noise[base], liveStill, rect);
        } finally {
            for (Mat g : grays) g.release();
        }
    }

    /** Separado para testes: decide o veredito a partir das medidas por quadro. */
    static Report conclude(int requested, int base, List<FrameStat> stats, List<Rejection> rejected, double baseNoise, Boolean liveStill, Rect rect) {
        List<Double> mags = new ArrayList<>(), drifts = new ArrayList<>(), halves = new ArrayList<>();
        int[][] hist = new int[4][4];
        int[] cells = new int[4];
        int used = 0;
        boolean anyMoving = false;
        for (Rejection r : rejected) if ("cena_em_movimento".equals(r.reason) || "movimento_nao_translacional".equals(r.reason)) anyMoving = true;
        for (FrameStat f : stats) {
            if (!f.used) continue;
            used++;
            mags.add(f.offset.magnitude());
            if (f.index != base) drifts.add(Math.abs(f.brightnessDrift));
            double hd = f.offset.halfDisagreement();
            if (f.index != base && Double.isFinite(hd)) halves.add(hd);
            // Posição da amostra do quadro na grade da base: p = q - d. Fase = (-d) mod 1.
            double fx = frac(-f.offset.dx), fy = frac(-f.offset.dy);
            hist[Math.min(3, (int) (fy * 4))][Math.min(3, (int) (fx * 4))]++;
            // Células da grade 2× centradas nas posições HR (fase 0 e ½), para que tremor de ±0,05 px
            // em torno de 0 não caia em duas células diferentes.
            double cx = frac(-f.offset.dx + 0.25), cy = frac(-f.offset.dy + 0.25);
            cells[Math.min(1, (int) (cy * 2)) * 2 + Math.min(1, (int) (cx * 2))]++;
        }
        int covered = 0;
        for (int c : cells) if (c >= 2) covered++;
        double coverage = covered / 4.0;
        double jitterMedian = percentile(mags, 0.5), jitterP90 = percentile(mags, 0.9);
        double driftP90 = drifts.isEmpty() ? 0 : percentile(drifts, 0.9);
        double rmse = Double.NaN;
        if (!halves.isEmpty()) { double s = 0; for (double v : halves) s += v * v; rmse = Math.sqrt(s / halves.size()) / 2; }
        boolean sceneStill = liveStill != null ? liveStill && !anyMoving : !anyMoving;
        double snrGain = used > 0 ? 10 * Math.log10(used) : 0;

        List<String> reasons = new ArrayList<>();
        Verdict verdict;
        if (used < 2) {
            verdict = Verdict.NADA_A_FAZER;
            reasons.add(requested < 2 ? "Uma foto só: não há o que empilhar." : "Menos de 2 quadros utilizáveis após a rejeição.");
        } else {
            List<String> fails = new ArrayList<>();
            if (used < SR_MIN_FRAMES) fails.add(String.format(Locale.ROOT, "SR 2× exige N ≥ %d quadros usados (há %d)", SR_MIN_FRAMES, used));
            if (jitterP90 < SR_MIN_P90_JITTER_PX) fails.add(String.format(BR, "tremor p90 %.2f px < %.2f px: câmera parada não gera fases novas, empilhar só reduz ruído", jitterP90, SR_MIN_P90_JITTER_PX));
            if (jitterP90 > SR_MAX_P90_JITTER_PX) fails.add(String.format(BR, "tremor p90 %.2f px > %.1f px: movimento grande demais para SR confiável", jitterP90, SR_MAX_P90_JITTER_PX));
            if (coverage < SR_MIN_GRID_COVERAGE) fails.add(String.format(BR, "cobertura da grade 2× %.0f%% < %.0f%%", 100 * coverage, 100 * SR_MIN_GRID_COVERAGE));
            if (!Double.isFinite(rmse)) fails.add("erro de registro não mensurável (metades falharam)");
            else if (rmse > SR_MAX_REGISTRATION_RMSE_PX) fails.add(String.format(BR, "erro de registro %.3f px > %.2f px: SR criaria imagem fantasma", rmse, SR_MAX_REGISTRATION_RMSE_PX));
            if (driftP90 > SR_MAX_BRIGHTNESS_DRIFT) fails.add(String.format(BR, "deriva de brilho %.2f > %.1f níveis: trave exposição e balanço de branco", driftP90, SR_MAX_BRIGHTNESS_DRIFT));
            if (baseNoise > SR_MAX_NOISE_SIGMA) fails.add(String.format(BR, "ruído %.1f > %.0f níveis: com esse ruído a SR perde da mediana (ensaio 2026-11-20)", baseNoise, SR_MAX_NOISE_SIGMA));
            if (!sceneStill) fails.add("cena não está parada (LiveMotion ou resíduo após alinhamento)");
            if (fails.isEmpty()) {
                verdict = Verdict.SR_2X_PLAUSIVEL;
                reasons.add("Todas as condições medidas permitem tentar SR 2×; a reconstrução ainda passa pela checagem de consistência.");
            } else {
                verdict = Verdict.SOMENTE_MEDIANA;
                reasons.add(String.format(BR, "Empilhar (mediana robusta) é o resultado útil: ganho teórico de SNR ≈ %.1f dB, sem mudar a geometria.", snrGain));
                reasons.addAll(fails);
            }
        }
        return new Report(requested, base, stats, rejected, jitterMedian, jitterP90, coverage, rmse, driftP90, baseNoise, snrGain,
                liveStill, sceneStill, verdict, reasons, hist, rect);
    }

    static double frac(double v) { double f = v - Math.floor(v); return f >= 1 ? 0 : f; }

    static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        double pos = q * (s.size() - 1);
        int i = (int) Math.floor(pos);
        int j = Math.min(s.size() - 1, i + 1);
        return s.get(i) + (s.get(j) - s.get(i)) * (pos - i);
    }

    /** Variância do Laplaciano (nitidez). */
    static double laplacianVariance(Mat gray) {
        Mat lap = new Mat();
        MatOfDouble mean = new MatOfDouble(), std = new MatOfDouble();
        try {
            Imgproc.Laplacian(gray, lap, CvType.CV_32F);
            Core.meanStdDev(lap, mean, std);
            double s = std.toArray()[0];
            return s * s;
        } finally { lap.release(); mean.release(); std.release(); }
    }

    /**
     * Desvio do ruído (níveis de cinza) pelo método de Immerkær (1996): resposta absoluta média a uma
     * máscara que anula estruturas lineares. Rápido e robusto; superestima em textura fina.
     */
    public static double noiseSigma(Mat grayFloat) {
        Mat k = new Mat(3, 3, CvType.CV_32F), r = new Mat(), a = new Mat();
        try {
            k.put(0, 0, 1, -2, 1, -2, 4, -2, 1, -2, 1);
            Imgproc.filter2D(grayFloat, r, CvType.CV_32F, k);
            int w = r.cols(), h = r.rows();
            if (w < 3 || h < 3) return 0;
            Core.absdiff(r, new org.opencv.core.Scalar(0), a);
            double sum = Core.sumElems(a.submat(1, h - 1, 1, w - 1)).val[0];
            return Math.sqrt(Math.PI / 2) * sum / (6.0 * (w - 2) * (h - 2));
        } finally { k.release(); r.release(); a.release(); }
    }

    /**
     * Deriva de brilho (mediana das diferenças de média em blocos 16×16, quadro alinhado − base) e
     * resíduo robusto após alinhamento (1,4826 × mediana |diferença − deriva|).
     */
    static double[] driftAndResidual(Mat baseGray, Mat frameGray, Rect rect, BurstAlign.Offset off) {
        Mat aligned = BurstAlign.warp(frameGray, off.dx, off.dy);
        try {
            Mat a = aligned.submat(rect), b = baseGray.submat(rect);
            int bw = Math.max(1, rect.width / 16), bh = Math.max(1, rect.height / 16);
            List<Double> diffs = new ArrayList<>();
            int margin = (int) Math.ceil(Math.max(Math.abs(off.dx), Math.abs(off.dy))) + 2;
            for (int by = 0; by < 16; by++) for (int bx = 0; bx < 16; bx++) {
                int x0 = bx * bw, y0 = by * bh;
                if (x0 < margin || y0 < margin || x0 + bw > rect.width - margin || y0 + bh > rect.height - margin) continue;
                double ma = Core.mean(a.submat(y0, y0 + bh, x0, x0 + bw)).val[0];
                double mb = Core.mean(b.submat(y0, y0 + bh, x0, x0 + bw)).val[0];
                diffs.add(ma - mb);
            }
            double drift = diffs.isEmpty() ? 0 : percentile(diffs, 0.5);
            // Resíduo: amostra de pixels internos (passo para custo).
            int step = Math.max(1, (int) Math.sqrt(rect.width * (double) rect.height / 40000.0));
            float[] ra = new float[rect.width], rb = new float[rect.width];
            List<Double> res = new ArrayList<>();
            Mat ac = a.clone(), bc = b.clone();
            try {
                for (int y = margin; y < rect.height - margin; y += step) {
                    ac.get(y, 0, ra); bc.get(y, 0, rb);
                    for (int x = margin; x < rect.width - margin; x += step) res.add((double) Math.abs(ra[x] - rb[x] - drift));
                }
            } finally { ac.release(); bc.release(); }
            double residual = res.isEmpty() ? 0 : 1.4826 * percentile(res, 0.5);
            return new double[]{drift, residual};
        } finally { aligned.release(); }
    }

    /** Índices dos quadros usados, base primeiro. */
    public static int[] usedOrder(Report r) {
        List<Integer> idx = new ArrayList<>();
        idx.add(r.baseFrame);
        for (FrameStat f : r.frames) if (f.used && f.index != r.baseFrame) idx.add(f.index);
        int[] out = new int[idx.size()];
        for (int i = 0; i < out.length; i++) out[i] = idx.get(i);
        return out;
    }

    static String fmt(double[] v) { return Arrays.toString(v); }
}
