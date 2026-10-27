package br.com.pelotavision;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 0.5.2: recursos do Android levados ao Windows — segmentação detalhada nos pixels originais,
 * escolha manual do realce de imagem escura (com imagem filtrada no relatório) e conversões de imagem.
 * Executa o núcleo com o OpenCV nativo; não é validação de acurácia.
 */
public class DesktopParityTest {
    private static File verification;

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        verification = new File(System.getProperty("pelotavision.validationDir"));
        assertTrue(verification.isDirectory());
    }

    @Test public void originalRoiGeometryMapsOriginalPixelsToAnalyzedImage() {
        Mat original = new Mat(3000, 4000, CvType.CV_8UC3, new Scalar(90, 90, 90));
        try (DesktopDetail.Input in = DesktopDetail.decode(original, 2000, 1500, new InterestRegion(InterestRegion.Shape.RECTANGLE, 100, 100, 600, 500))) {
            assertEquals(1000, in.gray.cols());
            assertEquals(800, in.gray.rows());
            assertEquals(1, in.geometry.sample);
            assertEquals(.5, in.geometry.sx, 1e-9);
            assertEquals(100, in.geometry.tx, 1e-9);
            assertTrue(in.geometry.inside(10, 10));
        } finally {
            original.release();
        }
        // Acima de 8 megapixels a leitura é reduzida em potências de dois, com escala coerente.
        Mat big = new Mat(4000, 4000, CvType.CV_8UC3, new Scalar(90, 90, 90));
        try (DesktopDetail.Input in = DesktopDetail.decode(big, 2000, 2000, null)) {
            assertEquals(2, in.geometry.sample);
            assertEquals(2000, in.gray.cols());
            assertEquals(1, in.geometry.sx, 1e-9);
        } finally {
            big.release();
        }
    }

    @Test public void webcamFrameRoundTripsThroughMat() {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        img.setRGB(5, 7, 0x123456);
        Mat m = Mats.fromImage(img);
        try {
            assertEquals(CvType.CV_8UC3, m.type());
            assertEquals(0x123456, Mats.toImage(m).getRGB(5, 7) & 0xFFFFFF);
        } finally {
            m.release();
        }
    }

    @Test public void detailedSegmentationRunsOnOriginalRoiAndIsExported() throws Exception {
        PhotoSession s = DesktopPipelineTest.open(new File(verification, "fixtures/crack_example.png"));
        try {
            DesktopAnalysis.Config cfg = new DesktopAnalysis.Config();
            cfg.autoSuggest = false;
            s.setRegion(new InterestRegion(InterestRegion.Shape.RECTANGLE, s.width * .25, s.height * .25, s.width * .75, s.height * .75));
            DesktopDetail.Options opt = new DesktopDetail.Options();
            opt.compare = true; opt.search = false; opt.superResolution = false;
            DesktopAnalysis.Output out = DesktopDetail.run(s, cfg, null, opt, () -> false, (d, t, l) -> {});
            assertNotNull(out.detailed);
            assertTrue(out.result.method.contains("detail_original_tiles_v1"));
            assertEquals(s.width, out.result.width);
            assertNull(out.lowLight);
            s.accept(out);
            assertFalse(s.needsAnalysis());
            assertFalse("detalhada sem propostas", s.active().circles.isEmpty());
            assertTrue(DesktopDetail.summary(out.detailed).contains("blocos"));
            File dir = new File("build/parity/detalhada");
            List<File> files = DesktopExport.save(dir, s, cfg);
            JSONObject json = new JSONObject(new String(Files.readAllBytes(files.get(4).toPath()), StandardCharsets.UTF_8));
            assertFalse(json.isNull("detailed_analysis"));
            assertTrue(json.getJSONObject("processing_scope").has("input_width"));
            assertTrue(files.get(files.size() - 1).getName().endsWith("_relatorio.pdf"));
            // Na correia o modo detalhado é recusado, como no Android.
            cfg.belt = true;
            try { DesktopDetail.run(s, cfg, null, opt, () -> false, (d, t, l) -> {}); fail(); } catch (IllegalArgumentException expected) {}
        } finally {
            s.release();
        }
    }

    @Test public void manualLowLightChoiceIsRecordedAndShownFiltered() throws Exception {
        PhotoSession s = DesktopPipelineTest.open(new File(verification, "fixtures/crack_example.png"));
        try {
            DesktopAnalysis.Config cfg = new DesktopAnalysis.Config();
            cfg.autoSuggest = false;
            // Sem ROI, a escolha manual não é aplicada (igual ao Android).
            cfg.lowLightMode = LowLightPreprocessor.Mode.DENOISE_CLAHE;
            DesktopAnalysis.Output full = DesktopPipelineTest.run(s, cfg, null);
            assertEquals("not_applied", full.lowLight.selection);
            assertNull(s.filteredImage());

            s.setRegion(new InterestRegion(InterestRegion.Shape.RECTANGLE, s.width * .2, s.height * .2, s.width * .8, s.height * .8));
            DesktopAnalysis.Output out = DesktopPipelineTest.run(s, cfg, full.applied);
            assertEquals("manual", out.lowLight.selection);
            assertEquals(LowLightPreprocessor.Mode.DENOISE_CLAHE, out.lowLight.mode);
            assertNotNull(out.filteredGray);
            BufferedImage shown = s.shownImage();
            assertNotSame(s.analyzedImage, shown);
            // Fora da janela analisada a imagem mostrada é a original.
            assertEquals(s.analyzedImage.getRGB(2, 2), shown.getRGB(2, 2));

            s.setScale(new Granulometry.Settings(10, 1, true, false));
            List<File> files = DesktopExport.save(new File("build/parity/luz"), s, cfg);
            JSONObject json = new JSONObject(new String(Files.readAllBytes(files.get(4).toPath()), StandardCharsets.UTF_8));
            assertEquals("manual", json.getJSONObject("low_light").getString("selection"));
            assertEquals("filtered", json.getJSONObject("report_images").getString("segmented_base"));
            boolean filtered = false;
            for (File f : files) filtered |= f.getName().endsWith("_filtrada.png");
            assertTrue(filtered);
            assertTrue(files.get(files.size() - 1).getName().endsWith("_relatorio.pdf"));

            // Original escolhido manualmente: registrado, sem imagem filtrada.
            cfg.lowLightMode = LowLightPreprocessor.Mode.ORIGINAL;
            DesktopAnalysis.Output original = DesktopPipelineTest.run(s, cfg, out.applied);
            assertEquals("manual", original.lowLight.selection);
            assertNull(s.filteredImage());
        } finally {
            s.release();
        }
    }
}
