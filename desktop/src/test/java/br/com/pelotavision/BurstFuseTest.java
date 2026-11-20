package br.com.pelotavision;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Trilha 1 (0.19.0): Burst Clear reduz ruído ≈ √N sem mudar geometria nem escala. */
public class BurstFuseTest {
    @BeforeClass public static void loadNative() { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    static final int SIZE = 320;
    static final double[][] CENTERS = BurstDiagnosticsTest.CENTERS;
    static final BurstSynth.Scene SCENE = BurstSynth.discs(CENTERS, 18);

    static double[][] jitter(int n, double s, long seed) {
        Random r = new Random(seed);
        double[][] off = new double[n][2];
        for (int i = 1; i < n; i++) { off[i][0] = s * r.nextGaussian(); off[i][1] = s * r.nextGaussian(); }
        return off;
    }

    @Test public void medianReducesNoiseAndKeepsGridAndDiameters() throws Exception {
        double[][] off = jitter(10, 0.8, 4);
        List<Mat> frames = new ArrayList<>();
        for (int i = 0; i < off.length; i++) frames.add(BurstSynth.frame(SCENE, SIZE, SIZE, off[i][0], off[i][1], 12, 0, 300 + i));
        BurstStack.Result res = BurstStack.run(frames, new BurstStack.Options());
        try {
            int b = res.diagnostics.baseFrame;
            assertNotNull(res.fused);
            assertEquals(SIZE, res.fused.cols()); assertEquals(SIZE, res.fused.rows()); assertEquals(frames.get(0).type(), res.fused.type());
            double[] clean = BurstSynth.renderClean(SCENE, SIZE, SIZE, off[b][0], off[b][1], 6);
            double single = BurstSynth.rmse(BurstSynth.channel0(frames.get(b)), clean, SIZE, SIZE, 8);
            double fused = BurstSynth.rmse(BurstSynth.channel0(res.fused), clean, SIZE, SIZE, 8);
            assertTrue(String.format("RMSE 1 quadro %.2f, empilhada %.2f", single, fused), fused * 2.5 < single);
            // Geometria: diâmetro equivalente de cada disco igual ao da cena limpa (base) em ±0,15 px.
            double[] f = BurstSynth.channel0(res.fused);
            for (double[] c : CENTERS) {
                double cx = c[0] + off[b][0], cy = c[1] + off[b][1];
                double ref = BurstSynth.equivalentDiameter(clean, SIZE, SIZE, cx, cy, 26, 100, 190);
                double got = BurstSynth.equivalentDiameter(f, SIZE, SIZE, cx, cy, 26, 100, 190);
                assertEquals("disco em " + cx + "," + cy, ref, got, 0.15);
            }
            assertTrue(res.snrGainDbMeasured() > 6);
            JSONObject j = res.toJson(Collections.emptyList(), "x_burst.png", null, true);
            assertEquals("median", j.getString("mode"));
            assertEquals("original", j.getJSONObject("output").getString("grid"));
            assertTrue(j.getBoolean("used_for_measurement"));
            assertTrue(j.isNull("ibp"));
        } finally { res.release(); for (Mat m : frames) m.release(); }
    }

    @Test public void movingObjectInOneFrameIsRejectedPerPixelNotBlended() {
        double[][] off = jitter(8, 0.6, 8);
        List<Mat> frames = new ArrayList<>();
        BurstSynth.Scene intruder = (x, y) -> (Math.hypot(x - 160, y - 140) < 12) ? 30 : SCENE.at(x, y);
        for (int i = 0; i < off.length; i++) frames.add(BurstSynth.frame(i == 5 ? intruder : SCENE, SIZE, SIZE, off[i][0], off[i][1], 4, 0, 700 + i));
        BurstStack.Result res = BurstStack.run(frames, new BurstStack.Options());
        try {
            int b = res.diagnostics.baseFrame;
            assertTrue("o quadro com intruso não pode ser a base", b != 5);
            double[] clean = BurstSynth.renderClean(SCENE, SIZE, SIZE, off[b][0], off[b][1], 6);
            double[] f = BurstSynth.channel0(res.fused);
            double worst = 0;
            for (int y = 132; y < 148; y++) for (int x = 152; x < 168; x++) worst = Math.max(worst, Math.abs(f[y * SIZE + x] - clean[y * SIZE + x]));
            assertTrue("fantasma de " + worst + " níveis", worst < 12);
        } finally { res.release(); for (Mat m : frames) m.release(); }
    }

    @Test public void singlePhotoIsNotFused() {
        List<Mat> frames = new ArrayList<>();
        frames.add(BurstSynth.frame(SCENE, 200, 200, 0, 0, 4, 0, 1));
        BurstStack.Result res = BurstStack.run(frames, new BurstStack.Options());
        try {
            assertNull(res.fused);
            assertEquals("rejected", res.mode());
            assertFalse(res.diagnostics.srAllowed());
        } finally { res.release(); frames.get(0).release(); }
    }
}
