package br.com.pelotavision;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Mat;

/** 0.5.4: contagem ao vivo restrita à ROI e gráficos alimentados por ela (OpenCV nativo, como no CI do Windows). */
public class LiveRoiTest {
    static File verification;

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        verification = new File(System.getProperty("pelotavision.validationDir"));
    }

    @Test public void liveCountStaysInsideRoiAndFeedsCharts() throws Exception {
        // Foto real da webcam (1920x1080) com a panela; ROI circular dentro da borda, como o usuário faria.
        Mat bgr = Mats.readUpright(new File(verification, "container/panela_webcam_1920x1080.png"));
        Mat gray = Mats.gray(bgr);
        try {
            DesktopAnalysis.Config cfg = new DesktopAnalysis.Config();
            InterestRegion roi = new InterestRegion(InterestRegion.Shape.CIRCLE, 879 - 480, 547 - 480, 879 + 480, 547 + 480);
            PelletDetector.Result full = DesktopAnalysis.live(gray, cfg);
            PelletDetector.Result inRoi = DesktopAnalysis.live(gray, cfg, roi);
            assertEquals(1920, inRoi.width);
            assertEquals(1080, inRoi.height);
            assertTrue("pelotas na ROI: " + inRoi.circles.size(), inRoi.circles.size() >= 30);
            for (PelletDetector.Detection d : inRoi.circles) assertTrue("fora da ROI: " + d.x + "," + d.y, roi.includes(d));
            assertTrue(inRoi.method.contains("active_roi_crop_v1"));
            // Gráficos ao vivo: px sem escala; com px/mm digitado, em mm.
            Granulometry.Distribution px = MainFrame.liveDistribution(inRoi, null);
            assertEquals("px", px.settings.unit());
            Granulometry.Distribution mm = MainFrame.liveDistribution(inRoi, new Granulometry.Settings(4.78, 1, true, false));
            assertEquals("mm", mm.settings.unit());
            assertTrue("D50 " + mm.d50, mm.d50 > 6 && mm.d50 < 25);
            RosinRammler.Fit fit = RosinRammler.fit(mm, RosinRammler.Basis.NUMBER);
            assertTrue(fit.reason, fit.available);
            System.out.printf("ao vivo: quadro %d propostas, ROI %d; D50 %.2f mm; %s%n", full.circles.size(), inRoi.circles.size(), mm.d50, fit.formula());
        } finally {
            gray.release();
            bgr.release();
        }
    }

    @Test public void referenceFromOtherResolutionFallsBackToPixels() {
        ContainerCalibration.Reference ref = ContainerCalibration.select(new ContainerCalibration.Circle(320, 240, 200, false),
                ContainerCalibration.Plane.RIM, 300, null, 640, 480, 640, 480);
        Granulometry.Settings scale = Granulometry.Settings.container(ref, 1, true, false);
        java.util.List<PelletDetector.Detection> one = java.util.Arrays.asList(new PelletDetector.Detection(500, 400, 20, 1256, 1, false, false));
        PelletDetector.Result r = new PelletDetector.Result(one, 1920, 1080, 1, 1, false, "test");
        assertEquals("px", MainFrame.liveDistribution(r, scale).settings.unit());
    }
}
