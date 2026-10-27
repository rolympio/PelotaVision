package br.com.pelotavision;

import static org.junit.Assert.*;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Random;

/** Calibracao pelo diametro interno do recipiente: geometria, deteccao automatica e escala. Java puro (sem OpenCV). */
public class ContainerCalibrationTest {

    /** Mesa clara, aro claro (R..R+7), parede escura (L..R) e pelotas cinza encostadas na parede em L. */
    static byte[] synthetic(int w, int h, double cx, double cy, double R, double L, double sx, double sy, long seed) {
        byte[] a = new byte[w * h];
        Random rnd = new Random(seed);
        double pr = 13;
        java.util.List<double[]> pellets = new java.util.ArrayList<>();
        for (int k = 0; k < 40; k++) { double t = 2 * Math.PI * k / 40; pellets.add(new double[]{cx + (L - pr) * Math.cos(t) * sx, cy + (L - pr) * Math.sin(t) * sy}); }
        for (double y = cy - L; y <= cy + L; y += 2 * pr * .87) for (double x = cx - L; x <= cx + L; x += 2 * pr)
            if (Math.hypot((x - cx) / sx, (y - cy) / sy) < L - 2.6 * pr) pellets.add(new double[]{x + rnd.nextGaussian() * 2, y + rnd.nextGaussian() * 2});
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double d = Math.hypot((x - cx) / sx, (y - cy) / sy);
            double v = d > R + 7 ? 228 : d > R ? 200 : d > L ? 45 : 70;
            if (d <= L) for (double[] p : pellets) if (Math.hypot(x - p[0], y - p[1]) < pr) { v = 135; break; }
            a[y * w + x] = (byte) Math.max(0, Math.min(255, (int) Math.round(v + rnd.nextGaussian() * 6)));
        }
        return a;
    }

    @Test public void syntheticContainerFindsRimAndPelletLevel() {
        int w = 480, h = 400;
        ContainerCalibration.Detection d = ContainerRimDetector.detect(synthetic(w, h, 250, 205, 170, 145, 1, 1, 1), w, h);
        assertNotNull(d);
        assertTrue(d.rim.automatic);
        assertEquals(250, d.rim.cx, 1.5);
        assertEquals(205, d.rim.cy, 1.5);
        assertEquals(170, d.rim.r, 1.7);                 // borda interna do aro, nao a externa (R+7)
        assertTrue(d.rimOvality < .02);
        assertNotNull(d.pelletLevel);
        assertEquals(145, d.pelletLevel.r, 3);
        assertEquals(170 / 145. - 1, d.rimOverPelletScale(), .03);
        assertEquals(ContainerCalibration.Plane.PELLET_LEVEL, ContainerCalibration.suggestedPlane(d));
    }

    @Test public void obliquePhotoIsMeasuredAsOvalAndBlocked() {
        int w = 480, h = 400;
        ContainerCalibration.Detection d = ContainerRimDetector.detect(synthetic(w, h, 240, 200, 160, 135, 1, .90, 2), w, h);
        assertNotNull(d);
        assertEquals("ovalizacao (160-144)/152", .105, d.rimOvality, .015);
        assertTrue(d.rimOvality > ContainerCalibration.MAX_OVALITY);
        try { ContainerCalibration.select(d.rim, ContainerCalibration.Plane.RIM, 200, d, 960, 800, w, h); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("ovalizada")); }
    }

    @Test public void noContainerOrRimOutsidePhotoGivesNoProposal() {
        byte[] flat = new byte[480 * 400];
        Arrays.fill(flat, (byte) 110);
        assertNull(ContainerRimDetector.detect(flat, 480, 400));
        // Recipiente grande demais: a borda sai da foto pela esquerda e pela direita.
        assertNull(ContainerRimDetector.detect(synthetic(300, 400, 150, 200, 190, 160, 1, 1, 3), 300, 400));
    }

    @Test public void scaleUsesOriginalPixelsAndPlane() {
        ContainerCalibration.Circle c = new ContainerCalibration.Circle(190, 150, 140, false);
        ContainerCalibration.Reference r = ContainerCalibration.select(c, ContainerCalibration.Plane.RIM, 280, null, 760, 600, 380, 300);
        assertEquals(560, r.originalDiameterPixels, 1e-9);
        assertEquals(2, r.originalPixelsPerMm, 1e-9);
        assertTrue(Double.isNaN(r.rimOvality));
        assertTrue(r.matches(760, 600, 380, 300));
        assertFalse(r.matches(760, 600, 400, 300));
        // Redimensionamento anisotropico: media geometrica, como Granulometry.resizeRatio.
        ContainerCalibration.Reference a = ContainerCalibration.select(c, ContainerCalibration.Plane.RIM, 100, null, 760, 300, 380, 300);
        assertEquals(280 * Math.sqrt(2), a.originalDiameterPixels, 1e-9);
        Granulometry.Settings cfg = Granulometry.Settings.container(r, 1, true, false);
        assertSame(r, cfg.withHistogram(2, false, true).containerReference);
        assertNull(cfg.beltReference);
        assertEquals("container_inner_diameter_user_confirmed", cfg.calibrationSource);
        assertEquals(1, cfg.analyzedPixelsPerMm(760, 600, 380, 300), 1e-9);
        Granulometry.Distribution d = Granulometry.compute(Arrays.asList(new Granulometry.Item(12, false, false)), cfg, 760, 600, 380, 300);
        assertEquals(12, d.meanMm, 1e-9);
        try { cfg.analyzedPixelsPerMm(760, 600, 400, 300); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void invalidInputsCannotCommit() {
        ContainerCalibration.Circle c = new ContainerCalibration.Circle(190, 150, 140, false);
        for (double mm : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            try { ContainerCalibration.select(c, ContainerCalibration.Plane.RIM, mm, null, 760, 600, 380, 300); fail(); } catch (IllegalArgumentException expected) {}
        try { ContainerCalibration.select(new ContainerCalibration.Circle(40, 150, 140, false), ContainerCalibration.Plane.RIM, 200, null, 760, 600, 380, 300); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("sai da foto")); }
        try { ContainerCalibration.select(new ContainerCalibration.Circle(190, 150, 5, false), ContainerCalibration.Plane.RIM, 200, null, 760, 600, 380, 300); fail(); }
        catch (IllegalArgumentException expected) {}
        try { new ContainerCalibration.Circle(1, 1, 0, false); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void editingKeepsCircleInsidePhotoAndMarksManual() {
        ContainerCalibration.Circle c = new ContainerCalibration.Circle(190, 150, 100, true);
        ContainerCalibration.Circle m = c.moved(500, -500, 380, 300);
        assertEquals(379, m.cx, 0); assertEquals(0, m.cy, 0); assertFalse(m.automatic);
        ContainerCalibration.Circle r = c.resizedTo(190 + 60, 150 + 80, 8, 380, 300);
        assertEquals(100, r.r, 1e-9); assertEquals(190, r.cx, 0);
        assertEquals(8, c.resizedTo(190, 150, 8, 380, 300).r, 0);
        InterestRegion roi = ContainerCalibration.interiorRegion(new ContainerCalibration.Circle(190, 150, 140, true), 380, 300);
        assertEquals(InterestRegion.Shape.CIRCLE, roi.shape);
        assertEquals(190, roi.cx(), 1e-9); assertEquals(2 * 137.2, roi.width(), 1e-9);
        assertTrue(roi.top >= 0 && roi.bottom <= 300);
    }

    /** Foto real de bancada (26/10/2026): peneira com pelotas; a borda fica bem acima da camada. */
    @Test public void realBenchPhotoRegression() throws Exception {
        String dir = System.getProperty("pelotavision.validationDir", "../verification");
        BufferedImage img = javax.imageio.ImageIO.read(new File(dir, "container/recipiente_pelotas_640.jpg"));
        assertNotNull(img);
        ContainerCalibration.Detection d = ContainerFeature.suggest(img);
        assertNotNull(d);
        // 640 px de altura: Ø da borda ~ 449 px (1554 px na foto de 2212 px).
        assertEquals(449.5, 2 * d.rim.r, 4.5);
        assertTrue(d.rimOvality < ContainerCalibration.MAX_OVALITY);
        assertNotNull(d.pelletLevel);
        double ratio = d.pelletLevel.r / d.rim.r;
        assertTrue("razao nivel/borda " + ratio, ratio > .80 && ratio < .88);
    }
}
