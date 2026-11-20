package br.com.pelotavision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rajada (0.19.0): orquestra diagnóstico → Burst Clear → (opcional) Burst SR 2×, igual no Windows e
 * no Android, e produz o bloco {@code "burst"} do JSON.
 *
 * <p>Política de dano contido (a mesma do FSRCNN de hoje): a imagem empilhada fica na grade e na
 * escala da foto base e pode ser analisada; a reconstrução 2× é arquivo derivado, nunca entra na
 * medição ({@code used_for_measurement = false}) e só é tentada quando o laudo diz
 * {@code SR_2X_PLAUSIVEL} e a checagem de consistência passa. Os quadros originais são do chamador e
 * não são alterados.</p>
 */
public final class BurstStack {
    private BurstStack() {}

    public static final int DEFAULT_FRAMES = 8, MAX_FRAMES = 24;
    /** Lado máximo (pixels da foto) do recorte reconstruído em 2×: custo em CPU, ver docs/rajada-burst.md. */
    public static final int SR_MAX_SIDE = 512;

    public static final class Options {
        /** Região para registrar (e centro do recorte SR). null = centro da foto. */
        public Rect roi;
        /** Estado do LiveMotion se a rajada veio do ao vivo; null se desconhecido. */
        public Boolean liveStill;
        public boolean fuse = true;
        /** Pedir SR 2× (só roda se o veredito permitir, a menos de {@link #forceSrForTesting}). */
        public boolean sr;
        /** Somente ensaios: roda a SR mesmo com veredito contrário (o JSON registra). */
        public boolean forceSrForTesting;
        public int srIterations = BurstSr.DEFAULT_ITERATIONS;
        public int srMaxSide = SR_MAX_SIDE;
    }

    /** Detalhes da tentativa de SR. */
    public static final class SrInfo {
        public final Rect rect;
        public final boolean accepted;
        public final String reason;
        public final int iterations, frames;
        public final double chi2, adjointError, step, lambdaMax, regWeight;
        public final boolean forced;
        SrInfo(Rect rect, boolean accepted, String reason, int iterations, int frames, double chi2, double adjointError, double step,
               double lambdaMax, double regWeight, boolean forced) {
            this.rect = rect; this.accepted = accepted; this.reason = reason; this.iterations = iterations; this.frames = frames;
            this.chi2 = chi2; this.adjointError = adjointError; this.step = step; this.lambdaMax = lambdaMax; this.regWeight = regWeight; this.forced = forced;
        }
    }

    public static final class Result {
        public final BurstDiagnostics.Report diagnostics;
        /** Empilhada na grade da base (null se NADA_A_FAZER ou fusão não pedida). */
        public final Mat fused;
        public final double[] frameWeights;
        public final double noiseBase, noiseFused;
        /** Reconstrução 2× do recorte {@code sr.rect} (null se não tentada ou rejeitada). */
        public final Mat sr;
        public final SrInfo srInfo;
        public final long millis;
        /** Fixado na criação: continua válido depois de {@link #release()} (o JSON é montado na exportação). */
        private final String mode;

        Result(BurstDiagnostics.Report diagnostics, Mat fused, double[] frameWeights, double noiseBase, double noiseFused, Mat sr, SrInfo srInfo, long millis) {
            this.diagnostics = diagnostics; this.fused = fused; this.frameWeights = frameWeights; this.noiseBase = noiseBase;
            this.noiseFused = noiseFused; this.sr = sr; this.srInfo = srInfo; this.millis = millis;
            if (fused == null) mode = diagnostics.verdict == BurstDiagnostics.Verdict.NADA_A_FAZER ? "rejected" : "diagnostics_only";
            else mode = sr != null ? "median_sr2x" : "median";
        }

        public double snrGainDbMeasured() {
            return noiseFused > 0 && noiseBase > 0 ? 20 * Math.log10(noiseBase / noiseFused) : Double.NaN;
        }

        public String mode() { return mode; }

        public void release() { if (fused != null) fused.release(); if (sr != null) sr.release(); }

        /** Texto para a tela: laudo + o que foi produzido. */
        public String describe() {
            StringBuilder b = new StringBuilder(diagnostics.describe());
            if (!"rejected".equals(mode) && !"diagnostics_only".equals(mode)) {
                double m = snrGainDbMeasured();
                b.append(String.format(BurstDiagnostics.BR, "%nEmpilhada (mediana robusta): ruído %.1f → %.1f níveis%s · mesma grade e escala da foto base.",
                        noiseBase, noiseFused, Double.isFinite(m) ? String.format(BurstDiagnostics.BR, " (%.1f dB medidos)", m) : ""));
            }
            if (srInfo != null) {
                b.append(String.format(BurstDiagnostics.BR, "%nSR 2× %s: %s (χ² %.2f, %d iterações)%s.",
                        srInfo.accepted ? "aceita" : "rejeitada", srInfo.reason, srInfo.chi2, srInfo.iterations, srInfo.forced ? " · FORÇADA para ensaio" : ""));
                if (srInfo.accepted) b.append(" Arquivo derivado, só para visualização: medidas continuam nos pixels da foto.");
            }
            b.append(String.format(BurstDiagnostics.BR, "%nTempo: %.1f s.", millis / 1000.0));
            return b.toString();
        }

        /**
         * Bloco {@code "burst"} do JSON. {@code framePaths}: nomes dos quadros salvos (ordem de captura);
         * {@code fusedPath}/{@code srPath}: nomes dos derivados (ou null); {@code usedForMeasurement}: se a
         * empilhada foi a imagem analisada.
         */
        public JSONObject toJson(List<String> framePaths, String fusedPath, String srPath, boolean usedForMeasurement) throws Exception {
            JSONObject j = diagnostics.toJson();
            j.put("enabled", true);
            j.put("mode", mode());
            j.put("snr_gain_db_measured", Double.isFinite(snrGainDbMeasured()) ? BurstDiagnostics.round(snrGainDbMeasured(), 2) : JSONObject.NULL);
            j.put("noise_base", BurstDiagnostics.round(noiseBase, 3));
            j.put("noise_fused", !Double.isFinite(noiseFused) ? JSONObject.NULL : BurstDiagnostics.round(noiseFused, 3));
            if (frameWeights != null) {
                JSONArray w = new JSONArray();
                for (double v : frameWeights) w.put(BurstDiagnostics.round(v, 4));
                j.put("fusion_mean_weight_per_used_frame", w);
            }
            JSONArray fp = new JSONArray();
            if (framePaths != null) for (String p : framePaths) fp.put(p);
            j.put("frame_files", fp);
            JSONObject out = new JSONObject();
            out.put("path", fusedPath == null ? JSONObject.NULL : fusedPath);
            out.put("grid", "original");
            out.put("scale_factor_vs_base_photo", 1);
            out.put("method", "weighted_robust_median_v1 (consensus = per-pixel gray median; weight = confidence * exp(-0.5 (r / (2.5 sigma))^2))");
            j.put("output", out);
            if (srInfo != null) {
                JSONObject ibp = new JSONObject();
                ibp.put("iterations", srInfo.iterations); ibp.put("relaxation", BurstSr.RELAXATION);
                ibp.put("step", BurstDiagnostics.round(srInfo.step, 6)); ibp.put("lambda_max", BurstDiagnostics.round(srInfo.lambdaMax, 6));
                ibp.put("smoothness_weight", BurstDiagnostics.round(srInfo.regWeight, 4));
                ibp.put("adjoint_error", srInfo.adjointError);
                ibp.put("chi2", Double.isFinite(srInfo.chi2) ? BurstDiagnostics.round(srInfo.chi2, 4) : JSONObject.NULL);
                ibp.put("chi2_max", BurstSr.CHI2_MAX);
                ibp.put("accepted", srInfo.accepted); ibp.put("reason", srInfo.reason); ibp.put("frames", srInfo.frames);
                ibp.put("forced_for_testing", srInfo.forced);
                j.put("ibp", ibp);
                JSONObject so = new JSONObject();
                so.put("path", srPath == null ? JSONObject.NULL : srPath);
                so.put("grid", "2x");
                so.put("scale_factor_vs_base_photo", BurstSr.SCALE);
                so.put("scale_rule", "px/mm in this file = 2 x px/mm of the base photo; crop origin in base photo pixels below");
                JSONObject rr = new JSONObject();
                rr.put("x", srInfo.rect.x); rr.put("y", srInfo.rect.y); rr.put("width", srInfo.rect.width); rr.put("height", srInfo.rect.height);
                so.put("crop_in_base_photo", rr);
                so.put("used_for_measurement", false);
                j.put("sr_output", so);
            } else j.put("ibp", JSONObject.NULL);
            j.put("measurement_source", usedForMeasurement ? "burst_median_on_base_grid" : "original_pixels");
            j.put("used_for_measurement", usedForMeasurement);
            j.put("note", "Burst median keeps the base photo grid and scale. SR 2x output is display/proposal only; ellipses and cracks are never measured on reconstructed pixels.");
            j.put("elapsed_ms", millis);
            return j;
        }
    }

    public static Result run(List<Mat> images, Options o) {
        long t0 = System.currentTimeMillis();
        if (images.size() > MAX_FRAMES) throw new IllegalArgumentException("Rajada limitada a " + MAX_FRAMES + " fotos.");
        BurstDiagnostics.Report rep = BurstDiagnostics.analyze(images, o.roi, o.liveStill);
        Mat fused = null; double[] weights = null; double nb = rep.noiseSigma, nf = Double.NaN;
        Mat sr = null; SrInfo info = null;
        int[] order = BurstDiagnostics.usedOrder(rep);
        if (rep.verdict != BurstDiagnostics.Verdict.NADA_A_FAZER && o.fuse) {
            List<Mat> list = new ArrayList<>(order.length);
            double[] dx = new double[order.length], dy = new double[order.length], conf = new double[order.length];
            for (int i = 0; i < order.length; i++) {
                BurstDiagnostics.FrameStat f = rep.frame(order[i]);
                list.add(images.get(order[i]));
                dx[i] = f.offset.dx; dy[i] = f.offset.dy; conf[i] = confidence(f, i == 0);
            }
            BurstFuse.Result fr = BurstFuse.fuse(list, dx, dy, conf, rep.noiseSigma);
            fused = fr.image; weights = fr.meanWeights;
            Mat g = BurstAlign.grayFloat(fused);
            try { nf = BurstDiagnostics.noiseSigma(g.submat(rep.registrationRect)); } finally { g.release(); }

            if (o.sr && (rep.srAllowed() || o.forceSrForTesting)) {
                Object[] got = superResolve(images, rep, order, conf, o, fused);
                sr = (Mat) got[0]; info = (SrInfo) got[1];
            }
        }
        return new Result(rep, fused, weights, nb, nf, sr, info, System.currentTimeMillis() - t0);
    }

    static double confidence(BurstDiagnostics.FrameStat f, boolean base) {
        if (base) return 1;
        if (Double.isFinite(f.offset.ecc)) return Math.max(0.2, Math.min(1, f.offset.ecc));
        return Math.max(0.2, Math.min(0.6, f.offset.response * 4));
    }

    /**
     * Recorte SR centrado na região de registro, recuado do maior deslocamento usado para que todos os
     * quadros deslocados caibam na foto (sem isso os quadros com fase diferente seriam descartados).
     */
    static Rect srRect(BurstDiagnostics.Report rep, int width, int height, int maxSide) {
        double maxShift = 0;
        for (BurstDiagnostics.FrameStat f : rep.frames) if (f.used) maxShift = Math.max(maxShift, Math.max(Math.abs(f.offset.dx), Math.abs(f.offset.dy)));
        int margin = (int) Math.ceil(maxShift) + 1;
        Rect r = rep.registrationRect;
        int x0 = Math.max(r.x, margin), y0 = Math.max(r.y, margin);
        int x1 = Math.min(r.x + r.width, width - margin), y1 = Math.min(r.y + r.height, height - margin);
        int aw = Math.max(1, x1 - x0), ah = Math.max(1, y1 - y0);
        int w = Math.min(aw, maxSide), h = Math.min(ah, maxSide);
        return new Rect(x0 + (aw - w) / 2, y0 + (ah - h) / 2, w, h);
    }

    private static Object[] superResolve(List<Mat> images, BurstDiagnostics.Report rep, int[] order, double[] conf, Options o, Mat fused) {
        Mat first = images.get(order[0]);
        int width = first.cols(), height = first.rows(), ch = first.channels();
        Rect R = srRect(rep, width, height, Math.max(32, o.srMaxSide));
        List<Integer> keep = new ArrayList<>();
        List<double[]> fxy = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            BurstDiagnostics.FrameStat f = rep.frame(order[i]);
            int ix = (int) Math.round(f.offset.dx), iy = (int) Math.round(f.offset.dy);
            if (R.x + ix < 0 || R.y + iy < 0 || R.x + ix + R.width > width || R.y + iy + R.height > height) continue;
            keep.add(i);
            fxy.add(new double[]{f.offset.dx - ix, f.offset.dy - iy, ix, iy});
        }
        int n = keep.size();
        double[] fx = new double[n], fy = new double[n], c = new double[n];
        for (int k = 0; k < n; k++) { fx[k] = fxy.get(k)[0]; fy[k] = fxy.get(k)[1]; c[k] = conf[keep.get(k)]; }
        if (n < 2) return new Object[]{null, new SrInfo(R, false, "menos de 2 quadros cobrem o recorte", 0, n, Double.NaN, Double.NaN, 0, 0, 0, o.forceSrForTesting && !rep.srAllowed())};
        BurstSr.Plan plan = new BurstSr.Plan(R.height, R.width, fx, fy);
        int channels = ch == 1 ? 1 : 3;
        double[][][] data = new double[channels + 1][n][];
        for (int k = 0; k < n; k++) {
            Mat src = images.get(order[keep.get(k)]);
            int ix = (int) fxy.get(k)[2], iy = (int) fxy.get(k)[3];
            Mat crop = src.submat(new Rect(R.x + ix, R.y + iy, R.width, R.height)).clone();
            byte[] px = new byte[R.width * R.height * ch];
            crop.get(0, 0, px); crop.release();
            int len = R.width * R.height;
            double[] gray = new double[len];
            for (int cc = 0; cc < channels; cc++) data[cc][k] = new double[len];
            for (int i = 0; i < len; i++) {
                if (ch == 1) { gray[i] = px[i] & 255; data[0][k][i] = gray[i]; continue; }
                double b = px[i * ch] & 255, g = px[i * ch + 1] & 255, r = px[i * ch + 2] & 255;
                data[0][k][i] = b; data[1][k][i] = g; data[2][k][i] = r;
                gray[i] = 0.114 * b + 0.587 * g + 0.299 * r;
            }
            data[channels][k] = gray;
        }
        boolean forced = o.forceSrForTesting && !rep.srAllowed();
        // Ponto de partida: a empilhada (mesma grade da base) do recorte, ampliada 2× por bicúbica.
        double[][] init = new double[channels + 1][];
        {
            Mat crop = fused.submat(R), up = new Mat();
            Imgproc.resize(crop, up, new Size(plan.W, plan.H), 0, 0, Imgproc.INTER_CUBIC);
            byte[] px = new byte[plan.W * plan.H * ch];
            Mat cont = up.isContinuous() ? up : up.clone();
            cont.get(0, 0, px);
            if (cont != up) cont.release();
            up.release();
            int len = plan.W * plan.H;
            for (int cc = 0; cc <= channels; cc++) init[cc] = new double[len];
            for (int i = 0; i < len; i++) {
                if (ch == 1) { init[0][i] = px[i] & 255; init[channels][i] = init[0][i]; continue; }
                double b = px[i * ch] & 255, gg = px[i * ch + 1] & 255, r = px[i * ch + 2] & 255;
                init[0][i] = b; init[1][i] = gg; init[2][i] = r; init[channels][i] = 0.114 * b + 0.587 * gg + 0.299 * r;
            }
        }
        BurstSr.Result g = BurstSr.reconstruct(plan, data[channels], c, rep.noiseSigma, o.srIterations, null, init[channels]);
        SrInfo info = new SrInfo(R, g.accepted, g.reason, g.iterations, n, g.chi2, g.adjointError, g.step, g.lambdaMax, g.regWeight, forced);
        if (!g.accepted) return new Object[]{null, info};
        Mat out = new Mat(plan.H, plan.W, ch == 1 ? CvType.CV_8UC1 : CvType.CV_8UC3);
        byte[] buf = new byte[plan.H * plan.W * (ch == 1 ? 1 : 3)];
        if (ch == 1) {
            for (int i = 0; i < g.hr.length; i++) buf[i] = clip(g.hr[i]);
        } else {
            for (int cc = 0; cc < 3; cc++) {
                BurstSr.Result rc = BurstSr.reconstruct(plan, data[cc], c, rep.noiseSigma, o.srIterations, g.mask, init[cc]);
                for (int i = 0; i < rc.hr.length; i++) buf[i * 3 + cc] = clip(rc.hr[i]);
            }
        }
        out.put(0, 0, buf);
        return new Object[]{out, info};
    }

    static byte clip(double v) { return (byte) Math.max(0, Math.min(255, (int) Math.round(v))); }

    /** Nome curto do modo para a interface. */
    public static String label(BurstDiagnostics.Verdict v) {
        switch (v) {
            case SR_2X_PLAUSIVEL: return "SR 2× plausível";
            case SOMENTE_MEDIANA: return "somente mediana";
            default: return "nada a fazer";
        }
    }

    static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }
}
