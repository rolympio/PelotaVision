package br.com.pelotavision;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Trilha 0 (0.19.0): laudo da rajada em cenas sintéticas com deslocamentos conhecidos. */
public class BurstDiagnosticsTest {
    @BeforeClass public static void loadNative() { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    static final double[][] CENTERS = {{70, 80}, {160, 70}, {250, 95}, {90, 190}, {190, 200}, {280, 230}, {130, 280}, {240, 300}};
    static final BurstSynth.Scene SCENE = BurstSynth.discs(CENTERS, 18);

    static List<Mat> burst(double[][] offsets, double sigma, int size) {
        List<Mat> out = new ArrayList<>();
        for (int i = 0; i < offsets.length; i++) out.add(BurstSynth.frame(SCENE, size, size, offsets[i][0], offsets[i][1], sigma, 0, 1000 + i));
        return out;
    }

    static void release(List<Mat> l) { for (Mat m : l) m.release(); }

    @Test public void recoversSubpixelOffsetsWithProjectSignConvention() {
        Random r = new Random(1);
        double[][] off = new double[6][2];
        for (int i = 1; i < off.length; i++) { off[i][0] = -1.5 + 3 * r.nextDouble(); off[i][1] = -1.5 + 3 * r.nextDouble(); }
        List<Mat> frames = burst(off, 2, 352);
        try {
            BurstDiagnostics.Report rep = BurstDiagnostics.analyze(frames, null, null);
            double[] base = off[rep.baseFrame];
            for (BurstDiagnostics.FrameStat f : rep.frames) {
                // d estimado relativo à base: quadro_k(p + d) = base(p) ⇒ d = off_k − off_base.
                assertEquals("dx quadro " + f.index, off[f.index][0] - base[0], f.offset.dx, 0.08);
                assertEquals("dy quadro " + f.index, off[f.index][1] - base[1], f.offset.dy, 0.08);
            }
            assertTrue("erro interno " + rep.registrationRmse, rep.registrationRmse < 0.1);
            assertTrue(rep.rejected.isEmpty());
        } finally { release(frames); }
    }

    @Test public void tripodWithoutJitterGivesMedianOnly() {
        double[][] off = new double[10][2];
        List<Mat> frames = burst(off, 6, 320);
        try {
            BurstDiagnostics.Report rep = BurstDiagnostics.analyze(frames, null, Boolean.TRUE);
            assertEquals(BurstDiagnostics.Verdict.SOMENTE_MEDIANA, rep.verdict);
            assertTrue(rep.describe(), rep.describe().contains("tremor"));
            assertTrue(rep.gridCoverage <= 0.25);
            assertEquals(10, rep.usedCount());
        } finally { release(frames); }
    }

    @Test public void singlePhotoHasNothingToDo() {
        List<Mat> frames = burst(new double[1][2], 4, 256);
        try {
            BurstDiagnostics.Report rep = BurstDiagnostics.analyze(frames, null, null);
            assertEquals(BurstDiagnostics.Verdict.NADA_A_FAZER, rep.verdict);
            assertFalse(rep.srAllowed());
        } finally { release(frames); }
    }

    @Test public void brightnessDriftFrameIsRejected() {
        double[][] off = {{0, 0}, {0.3, -0.2}, {-0.4, 0.5}, {0.6, 0.1}};
        List<Mat> frames = new ArrayList<>();
        for (int i = 0; i < off.length; i++) frames.add(BurstSynth.frame(SCENE, 320, 320, off[i][0], off[i][1], 3, i == 2 ? 20 : 0, 50 + i));
        try {
            BurstDiagnostics.Report rep = BurstDiagnostics.analyze(frames, null, null);
            assertEquals(1, rep.rejected.size());
            assertEquals(2, rep.rejected.get(0).index);
            assertEquals("deriva_brilho", rep.rejected.get(0).reason);
            assertEquals(3, rep.usedCount());
        } finally { release(frames); }
    }

    @Test public void controlledHalfPixelShiftsMakeSrPlausible() {
        // "Pixel shift feito em casa": fases 0 / ½ em x e y, 3 repetições, tremor residual pequeno.
        Random r = new Random(9);
        double[][] off = new double[12][2];
        for (int i = 0; i < off.length; i++) {
            off[i][0] = 0.5 * (i % 2) + (i == 0 ? 0 : 0.05 * r.nextGaussian());
            off[i][1] = 0.5 * ((i / 2) % 2) + (i == 0 ? 0 : 0.05 * r.nextGaussian());
        }
        List<Mat> frames = burst(off, 2, 352);
        try {
            BurstDiagnostics.Report rep = BurstDiagnostics.analyze(frames, null, Boolean.TRUE);
            assertEquals(rep.describe(), BurstDiagnostics.Verdict.SR_2X_PLAUSIVEL, rep.verdict);
            assertTrue(rep.gridCoverage >= 0.75);
            assertTrue(rep.toJson().has("phase_histogram_4x4"));
        } catch (Exception e) { throw new AssertionError(e); }
        finally { release(frames); }
    }

    @Test public void noisyHandheldBurstStaysWithMedian() {
        Random r = new Random(3);
        double[][] off = new double[12][2];
        for (int i = 1; i < off.length; i++) { off[i][0] = 0.9 * r.nextGaussian(); off[i][1] = 0.9 * r.nextGaussian(); }
        List<Mat> frames = burst(off, 25, 320);
        try {
            BurstDiagnostics.Report rep = BurstDiagnostics.analyze(frames, null, null);
            assertEquals(rep.describe(), BurstDiagnostics.Verdict.SOMENTE_MEDIANA, rep.verdict);
            assertTrue(rep.describe(), rep.describe().contains("ruído"));
        } finally { release(frames); }
    }
}
