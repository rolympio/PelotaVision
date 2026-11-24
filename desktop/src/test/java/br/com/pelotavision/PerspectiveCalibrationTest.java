package br.com.pelotavision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 0.17.0: várias esferas padrão e correção de perspectiva (1 + k·ρ²), iguais no Windows e no Android. */
public class PerspectiveCalibrationTest {
    static final int W = 3000, H = 2000;

    /** Esfera/pelota de diâmetro real mm vista com escala central ppm0 e perspectiva k. */
    static PelletDetector.Detection seen(double x, double y, double mm, double ppm0, double k) {
        double r = mm / 2 * ppm0 * (1 + k * ObjectCalibration.rho2(x, y, W, H));
        PelletDetector.Detection seed = new PelletDetector.Detection(x, y, r, Math.PI * r * r, .95, false, false);
        return new PelletDetector.Detection(seed, x, y, r, r, 0, true, 1, .5, false, false);
    }

    @Test public void singleReferenceWithoutCorrectionIsUnchanged() {
        PelletDetector.Detection s = seen(2600, 1700, 11, 12, .08);
        ObjectCalibration.Reference old = ObjectCalibration.select(s, 1, 1, ObjectCalibration.Measure.STANDARD_SPHERE, 11, true, W, H, W, H);
        assertEquals(old.originalReferencePixels / 11, old.originalPixelsPerMm, 1e-9);
        assertEquals(0, old.perspectiveK, 0);
        assertEquals("off", old.perspectiveSource);
        assertEquals(11, old.equivalentMm(), 1e-9);
    }

    @Test public void singleOffCenterReferenceWithKnownKGivesCenterScale() {
        PelletDetector.Detection s = seen(2700, 1800, 11, 12, .08);
        ObjectCalibration.Reference r = ObjectCalibration.select(s, 1, 1, ObjectCalibration.Measure.STANDARD_SPHERE, 11, true, W, H, W, H, null, .08);
        assertEquals(12, r.originalPixelsPerMm, 1e-9);
        assertEquals(11, r.equivalentMm(), 1e-9);
        assertEquals("user_k", r.perspectiveSource);
    }

    @Test public void threeSpheresFitKAndScale() {
        List<PelletDetector.Detection> extras = Arrays.asList(seen(200, 150, 11, 12, .08), seen(2800, 1850, 11, 12, .08));
        ObjectCalibration.Reference r = ObjectCalibration.select(seen(1500, 1000, 11, 12, .08), 1, 1, ObjectCalibration.Measure.STANDARD_SPHERE, 11, true, W, H, W, H, extras, 0);
        assertEquals(.08, r.perspectiveK, 1e-6);
        assertEquals(12, r.originalPixelsPerMm, 1e-6);
        assertTrue(r.perspectiveSource.startsWith("fitted_3"));
        assertEquals(3, r.spheres().size());
        assertTrue(r.maxSphereDeviationPct() < 1e-6);
        for (PelletDetector.Detection e : extras) assertTrue(r.covers(e));
    }

    @Test public void twoSpheresCloseTogetherAverageWithUserK() {
        List<PelletDetector.Detection> extras = Arrays.asList(seen(1560, 1030, 11, 12, .08));
        ObjectCalibration.Reference r = ObjectCalibration.select(seen(1500, 1000, 11, 12, .08), 1, 1, ObjectCalibration.Measure.STANDARD_SPHERE, 11, true, W, H, W, H, extras, .08);
        assertEquals(.08, r.perspectiveK, 0);
        assertEquals(12, r.originalPixelsPerMm, 1e-6);
        assertEquals("mean_2_spheres_user_k", r.perspectiveSource);
    }

    @Test public void granulometryUsesLocalScale() {
        List<PelletDetector.Detection> extras = Arrays.asList(seen(200, 150, 11, 12, .08), seen(2800, 1850, 11, 12, .08), seen(250, 1800, 11, 12, .08));
        ObjectCalibration.Reference r = ObjectCalibration.select(seen(1500, 1000, 11, 12, .08), 1, 1, ObjectCalibration.Measure.STANDARD_SPHERE, 11, true, W, H, W, H, extras, 0);
        Granulometry.Settings cfg = Granulometry.Settings.selectedObject(r, .5, false, false);
        List<Granulometry.Item> items = new ArrayList<>();
        double[] truth = {9, 10.5, 12, 14, 16};
        int i = 0;
        for (double x = 100; x < W; x += 700) for (double y = 100; y < H; y += 450) items.add(Granulometry.Item.of(seen(x, y, truth[i++ % truth.length], 12, .08)));
        Granulometry.Distribution d = Granulometry.compute(items, cfg, W, H, W, H);
        double[] sizes = d.sizes();
        double[] expect = new double[items.size()];
        for (int j = 0; j < expect.length; j++) expect[j] = truth[j % truth.length];
        Arrays.sort(expect);
        for (int j = 0; j < sizes.length; j++) assertEquals(expect[j], sizes[j], 1e-6);
        for (Granulometry.Item it : items) assertTrue(Double.isFinite(d.diameterMm(it)));
        // sem correção, as pelotas do canto sairiam maiores
        Granulometry.Distribution off = Granulometry.compute(items, Granulometry.Settings.selectedObject(
                ObjectCalibration.select(seen(1500, 1000, 11, 12, .08), 1, 1, ObjectCalibration.Measure.STANDARD_SPHERE, 11, true, W, H, W, H), .5, false, false), W, H, W, H);
        assertTrue(off.meanMm > d.meanMm * 1.02);
    }

    @Test public void kOutsideLimitsIsRejected() {
        try {
            ObjectCalibration.select(seen(1500, 1000, 11, 12, 0), 1, 1, ObjectCalibration.Measure.STANDARD_SPHERE, 11, true, W, H, W, H, null, .5);
            fail();
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void extraSpheresOnlyForStandardSphere() {
        try {
            ObjectCalibration.select(seen(1500, 1000, 11, 12, 0), 1, 1, ObjectCalibration.Measure.MAJOR_AXIS, 11, false, W, H, W, H, Arrays.asList(seen(100, 100, 11, 12, 0)), 0);
            fail();
        } catch (IllegalArgumentException expected) { }
    }
}
