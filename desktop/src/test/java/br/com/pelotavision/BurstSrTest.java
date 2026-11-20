package br.com.pelotavision;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Trilha 2 (0.19.0): as quatro garantias numéricas do prompt master — adjunta exata, MTF acima da
 * média alinhada na frequência de Nyquist da grade LR, campo plano sem viés e rejeição de offsets errados.
 */
public class BurstSrTest {
    @BeforeClass public static void loadNative() { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    /** Fases 0/½ em x e y (pixel shift controlado) repetidas, com tremor residual pequeno. */
    static double[][] lattice(int n, double residual, long seed) {
        Random r = new Random(seed);
        double[][] off = new double[n][2];
        for (int i = 0; i < n; i++) {
            off[i][0] = 0.5 * (i % 2) + (i == 0 ? 0 : residual * r.nextGaussian());
            off[i][1] = 0.5 * ((i / 2) % 2) + (i == 0 ? 0 : residual * r.nextGaussian());
        }
        return off;
    }

    static double[][] lrData(BurstSynth.Scene s, double[][] off, int w, int h, double sigma, long seed) {
        Random r = new Random(seed);
        double[][] y = new double[off.length][];
        for (int k = 0; k < off.length; k++) {
            y[k] = BurstSynth.renderClean(s, w, h, off[k][0], off[k][1], 8);
            for (int i = 0; i < y[k].length; i++) y[k][i] = Math.round(y[k][i] + sigma * r.nextGaussian());
        }
        return y;
    }

    /** O quadro com deslocamento d tem a amostra (i, j) na posição (j − d, i − d) da base: fx = dx. */
    static BurstSr.Plan plan(double[][] off, int w, int h) {
        double[] fx = new double[off.length], fy = new double[off.length];
        for (int k = 0; k < off.length; k++) { fx[k] = off[k][0]; fy[k] = off[k][1]; }
        return new BurstSr.Plan(h, w, fx, fy);
    }

    @Test public void adjointIsExact() {
        Random r = new Random(5);
        double[] fx = new double[9], fy = new double[9];
        for (int k = 0; k < 9; k++) { fx[k] = 2 * r.nextDouble() - 1; fy[k] = 2 * r.nextDouble() - 1; }
        BurstSr.Plan p = new BurstSr.Plan(37, 41, fx, fy);
        double err = p.adjointError(11);
        assertTrue("erro da adjunta " + err, err < 1e-9);
        // Pegada normalizada: A·1 = 1 no interior.
        double[] ones = new double[p.H * p.W]; java.util.Arrays.fill(ones, 1);
        double[] y = new double[p.h * p.w];
        p.forward(3, ones, y);
        assertEquals(1.0, y[20 * p.w + 20], 1e-12);
    }

    @Test public void flatFieldHasNoBiasNorArtifacts() {
        double[][] off = lattice(12, 0.05, 2);
        int w = 64, h = 64;
        double[][] y = lrData(BurstSynth.flat(120), off, w, h, 2, 3);
        BurstSr.Result res = BurstSr.reconstruct(plan(off, w, h), y, null, 2, 10, null);
        assertTrue(res.reason, res.accepted);
        double sum = 0, sq = 0; int n = 0;
        for (int m = 8; m < res.H - 8; m++) for (int c = 8; c < res.W - 8; c++) { double v = res.hr[m * res.W + c]; sum += v; sq += v * v; n++; }
        double mean = sum / n, std = Math.sqrt(sq / n - mean * mean);
        assertEquals("viés", 120, mean, 0.5);
        assertTrue("desvio no campo plano " + std, std < 2.0);
    }

    @Test public void srRaisesMtfAboveAlignedMeanAtLrNyquist() {
        double f = 0.5; // ciclos por pixel LR = Nyquist da grade LR = ½ Nyquist da grade HR
        BurstSynth.Scene s = BurstSynth.sine(f, 60);
        double[][] off = lattice(12, 0.03, 6);
        int w = 96, h = 32;
        double[][] y = lrData(s, off, w, h, 2, 7);
        BurstSr.Result sr = BurstSr.reconstruct(plan(off, w, h), y, null, 2, 10, null);
        assertTrue(sr.reason, sr.accepted);
        double mSr = modulationHr(sr.hr, sr.W, sr.H, f);

        // Referência: média alinhada (BurstFuse com os mesmos offsets) ampliada 2× por bicúbica.
        List<Mat> frames = new ArrayList<>();
        double[] dx = new double[off.length], dy = new double[off.length];
        for (int k = 0; k < off.length; k++) {
            Mat m = new Mat(h, w, CvType.CV_8UC1);
            byte[] px = new byte[w * h];
            for (int i = 0; i < px.length; i++) px[i] = (byte) Math.max(0, Math.min(255, (int) y[k][i]));
            m.put(0, 0, px); frames.add(m); dx[k] = off[k][0]; dy[k] = off[k][1];
        }
        BurstFuse.Result fused = BurstFuse.fuse(frames, dx, dy, null, 2);
        Mat up = new Mat();
        Imgproc.resize(fused.image, up, new Size(2 * w, 2 * h), 0, 0, Imgproc.INTER_CUBIC);
        double mMean = modulationHr(BurstSynth.channel0(up), 2 * w, 2 * h, f);
        up.release(); fused.image.release(); for (Mat m : frames) m.release();
        assertTrue(String.format("modulação SR %.2f × média alinhada %.2f", mSr, mMean), mSr > mMean + 5);
    }

    @Test public void wrongOffsetsAreRejected() {
        double[][] off = lattice(12, 0.03, 12);
        int w = 120, h = 120;
        double[][] y = lrData(BurstSynth.discs(new double[][]{{30, 30}, {85, 40}, {50, 90}}, 16), off, w, h, 2, 13);
        BurstSr.Result good = BurstSr.reconstruct(plan(off, w, h), y, null, 2, 10, null);
        assertTrue("offsets corretos: " + good.reason, good.accepted);
        Random r = new Random(14);
        double[][] wrong = new double[off.length][2];
        for (int k = 0; k < off.length; k++) {
            double a = 2 * Math.PI * r.nextDouble();
            wrong[k][0] = off[k][0] + (k == 0 ? 0 : 0.3 * Math.cos(a));
            wrong[k][1] = off[k][1] + (k == 0 ? 0 : 0.3 * Math.sin(a));
        }
        BurstSr.Result bad = BurstSr.reconstruct(plan(wrong, w, h), y, null, 2, 10, null);
        assertFalse(String.format("offsets com erro de 0,3 px aceitos (χ² %.2f)", bad.chi2), bad.accepted);
        assertTrue(bad.chi2 > good.chi2 * 2);
    }

    @Test public void stackRunsSrOnlyWhenVerdictAllowsAndRecordsScale() throws Exception {
        double[][] off = lattice(12, 0.04, 21);
        BurstSynth.Scene s = BurstSynth.discs(BurstDiagnosticsTest.CENTERS, 18);
        List<Mat> frames = new ArrayList<>();
        for (int i = 0; i < off.length; i++) frames.add(BurstSynth.frame(s, 352, 352, off[i][0], off[i][1], 2, 0, 900 + i));
        BurstStack.Options o = new BurstStack.Options();
        o.sr = true; o.liveStill = Boolean.TRUE; o.srMaxSide = 160;
        BurstStack.Result res = BurstStack.run(frames, o);
        try {
            assertEquals(res.diagnostics.describe(), BurstDiagnostics.Verdict.SR_2X_PLAUSIVEL, res.diagnostics.verdict);
            assertNotNull(res.srInfo);
            assertTrue(res.describe(), res.srInfo.accepted);
            assertNotNull(res.sr);
            assertEquals(320, res.sr.cols());
            JSONObject j = res.toJson(Collections.emptyList(), "b.png", "b_sr2x.png", true);
            assertEquals("median_sr2x", j.getString("mode"));
            assertEquals(2, j.getJSONObject("sr_output").getInt("scale_factor_vs_base_photo"));
            assertFalse(j.getJSONObject("sr_output").getBoolean("used_for_measurement"));
            assertTrue(j.getJSONObject("ibp").getDouble("adjoint_error") < 1e-9);
        } finally { res.release(); for (Mat m : frames) m.release(); }

        // Mesma rajada sem tremor: SR pedida, mas o veredito não libera.
        List<Mat> still = new ArrayList<>();
        for (int i = 0; i < 10; i++) still.add(BurstSynth.frame(s, 352, 352, 0, 0, 2, 0, 950 + i));
        BurstStack.Result r2 = BurstStack.run(still, o);
        try {
            assertEquals(BurstDiagnostics.Verdict.SOMENTE_MEDIANA, r2.diagnostics.verdict);
            assertTrue(r2.sr == null && r2.srInfo == null);
            assertEquals("median", r2.mode());
        } finally { r2.release(); for (Mat m : still) m.release(); }
    }

    /** Amplitude da senoide de f ciclos/pixel LR numa imagem HR (centro do pixel HR m em l = (m + ½)/2 − ½). */
    static double modulationHr(double[] img, int W, int H, double f) {
        double re = 0, im = 0, mean = 0; int n = 0;
        for (int m = 6; m < H - 6; m++) for (int c = 12; c < W - 12; c++) { mean += img[m * W + c]; n++; }
        mean /= n;
        for (int m = 6; m < H - 6; m++) for (int c = 12; c < W - 12; c++) {
            double l = (c + 0.5) / 2 - 0.5;
            double v = img[m * W + c] - mean;
            re += v * Math.cos(2 * Math.PI * f * l); im += v * Math.sin(2 * Math.PI * f * l);
        }
        return 2 * Math.hypot(re, im) / n;
    }
}
