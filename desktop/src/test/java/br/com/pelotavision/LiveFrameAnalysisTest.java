package br.com.pelotavision;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/** 0.16.0: ao vivo com ROI, filtro de luz, escala transferida, calibração no quadro e cache do LoG. OpenCV nativo. */
public class LiveFrameAnalysisTest {
    static File verification;

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        verification = new File(System.getProperty("pelotavision.validationDir"));
    }

    private static Mat webcam() {
        Mat bgr;
        try { bgr = Mats.readUpright(new File(verification, "container/panela_webcam_1920x1080.png")); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        try { return Mats.gray(bgr); } finally { bgr.release(); }
    }

    @Test public void roiFractionRoundTripsAcrossResolutions() {
        InterestRegion circle = new InterestRegion(InterestRegion.Shape.CIRCLE, 399, 67, 1359, 1027);
        LiveFrameAnalysis.RoiFraction f = LiveFrameAnalysis.toFraction(circle, 1920, 1080);
        InterestRegion back = LiveFrameAnalysis.roiInFrame(f, 1920, 1080);
        assertEquals(circle.cx(), back.cx(), 1e-6);
        assertEquals(circle.width(), back.width(), 1e-6);
        InterestRegion small = LiveFrameAnalysis.roiInFrame(f, 640, 360);
        assertEquals("continua círculo", small.width(), small.height(), 1e-6);
        assertEquals(circle.width() / 3, small.width(), 1e-6);
        assertNull("ROI menor que 32 px é ignorada", LiveFrameAnalysis.roiInFrame(f, 40, 22));
        InterestRegion rect = new InterestRegion(InterestRegion.Shape.RECTANGLE, 100, 50, 900, 650);
        InterestRegion r2 = LiveFrameAnalysis.roiInFrame(LiveFrameAnalysis.toFraction(rect, 1000, 800), 500, 400);
        assertEquals(50, r2.left, 1e-9); assertEquals(325, r2.bottom, 1e-9);
    }

    @Test public void scaleTransfersByResolutionAndRefusesOtherFraming() {
        Granulometry.Settings photo = new Granulometry.Settings(20, 1, true, false); // 20 px/mm numa foto 4000x3000
        LiveFrameAnalysis.ScaleTransfer t = new LiveFrameAnalysis.ScaleTransfer(photo, 4000, 3000, "teste");
        assertEquals(3.2, t.pixelsPerMm(640, 480), 1e-9);
        assertTrue(Double.isNaN(t.pixelsPerMm(640, 360)));
        assertNull(t.forFrame(640, 360));
        List<PelletDetector.Detection> ds = new ArrayList<>();
        for (int i = 0; i < 12; i++) ds.add(new PelletDetector.Detection(50 + 40 * i, 200, 16 + (i % 3), Math.PI * 256, .95, false, false));
        PelletDetector.Result r = new PelletDetector.Result(ds, 640, 480, ds.size(), 1, false, "teste");
        Granulometry.Distribution d = LiveFrameAnalysis.distribution(r, t);
        assertEquals("mm", d.settings.unit());
        assertEquals(2 * 17 / 3.2, d.d50, 1e-6);
        PelletDetector.Result wide = new PelletDetector.Result(ds, 640, 360, ds.size(), 1, false, "teste");
        assertEquals("outra proporção: px", "px", LiveFrameAnalysis.distribution(wide, t).settings.unit());
    }

    @Test public void lightFilterProposesButMeasuresOriginalPixels() {
        Mat gray = webcam();
        try {
            InterestRegion roi = new InterestRegion(InterestRegion.Shape.CIRCLE, 879 - 480, 547 - 480, 879 + 480, 547 + 480);
            DetectionTuner.Option o = new DetectionTuner.Option(1, 75, DetectionTuner.LOG, false);
            LiveFrameAnalysis.Request plain = new LiveFrameAnalysis.Request(o, LiveFrameAnalysis.toFraction(roi, gray.cols(), gray.rows()),
                    LiveFrameAnalysis.Light.OFF, null, null, false);
            LiveFrameAnalysis.Request lit = new LiveFrameAnalysis.Request(o, plain.roiFraction, LiveFrameAnalysis.Light.GAMMA_CLAHE, null, null, true);
            try (LiveFrameAnalysis.Output a = LiveFrameAnalysis.run(gray, plain); LiveFrameAnalysis.Output b = LiveFrameAnalysis.run(gray, lit)) {
                assertTrue("contagem " + a.result.circles.size(), a.result.circles.size() > 30);
                assertTrue(b.result.circles.size() > 30);
                assertNull(a.filtered);
                assertNotNull(b.filtered);
                assertEquals(gray.cols(), b.filtered.cols());
                assertEquals(LowLightPreprocessor.Mode.GAMMA_CLAHE, b.appliedLight);
                for (PelletDetector.Detection d : b.result.circles) assertTrue("dentro da ROI", a.roi.contains(d.x, d.y));
                // Igual ao caminho anterior do Windows sem filtro.
                assertEquals(DesktopAnalysis.live(gray, o, a.roi).circles.size(), a.result.circles.size());
                assertEquals("px", a.distribution.settings.unit());
                assertNotNull(a.fit);
            }
        } finally { gray.release(); }
    }

    @Test public void preparedLogGivesIdenticalResultForEverySensitivity() {
        Mat gray = webcam();
        try {
            for (int profile = 0; profile < 4; profile++) {
                PelletDetector.Parameters base = new DetectionTuner.Option(profile, 0, DetectionTuner.LOG, false).parameters;
                PelletDetector.Prepared p = PelletDetector.prepare(gray, base, 640, 8);
                for (int s = 0; s <= 100; s += 25) {
                    PelletDetector.Parameters cfg = new DetectionTuner.Option(profile, s, DetectionTuner.LOG, false).parameters;
                    PelletDetector.Result a = new PelletDetector().detect(gray, cfg, 640, 8), b = p.detect(cfg);
                    assertEquals(a.circles.size(), b.circles.size());
                    for (int i = 0; i < a.circles.size(); i++) {
                        assertEquals(a.circles.get(i).x, b.circles.get(i).x, 0);
                        assertEquals(a.circles.get(i).radius, b.circles.get(i).radius, 0);
                    }
                }
            }
        } finally { gray.release(); }
    }

    @Test public void containerOnSyntheticFrameGivesExpectedScale() {
        Mat frame = new Mat(720, 1280, CvType.CV_8UC1, new Scalar(200));
        try {
            Imgproc.circle(frame, new Point(640, 360), 330, new Scalar(40), -1);  // fundo escuro da panela, Ø 660 px
            Imgproc.circle(frame, new Point(640, 360), 330, new Scalar(90), 6);
            Imgproc.GaussianBlur(frame, frame, new org.opencv.core.Size(5, 5), 1.2);
            LiveFrameAnalysis.ScaleTransfer t = LiveFrameAnalysis.containerOnFrame(frame, 330, ContainerCalibration.Plane.RIM, 1, true, false);
            assertNotNull("borda detectada", t);
            assertEquals("~2 px/mm", 2.0, t.pixelsPerMm(1280, 720), .06);
            assertNotNull(t.circle);
        } finally { frame.release(); }
    }

    @Test public void fastSearchOnRealFramesUsesTheLogCache() {
        Mat gray = webcam();
        List<Mat> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(gray);
        try (LiveSearch.Frames f = new LiveSearch.Frames(frames, null)) {
            DetectionTuner.Option start = new DetectionTuner.Option(1, 75, DetectionTuner.LOG, false);
            DetectionAssessment.Context ctx = new DetectionAssessment.Context(null, gray.cols(), gray.rows(), null, 0);
            LiveTuner.Run run = LiveSearch.search(start, ctx, f::run, () -> false, null, 2, 0);
            assertNotNull(run.best);
            assertTrue("cache por quadro e perfil, não por sensibilidade: " + f.preparedCount(), f.preparedCount() <= 3 * 4);
            // mesmo resultado que o caminho antigo para o ajuste vencedor
            if (run.best.option.method != DetectionTuner.NEURAL)
                assertEquals(DesktopAnalysis.live(gray, run.best.option, null).circles.size(), f.run(run.best.option).get(0).circles.size());
        } finally { gray.release(); }
    }
}
