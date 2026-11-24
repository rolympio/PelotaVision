package br.com.pelotavision;

import org.junit.Assume;
import org.junit.Test;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * SAM 2 real (ONNX Runtime, CPU) na foto de referência. Só roda quando o modelo exportado está na pasta de
 * modelos (app/src/main/assets, fora do Git); sem ele o teste é ignorado. Confere instalação com SHA-256,
 * refino da maioria das pelotas, tamanhos coerentes com o detector e determinismo (duas execuções iguais).
 */
public class Sam2ModelTest {
    @org.junit.BeforeClass public static void loadNative() { System.loadLibrary(org.opencv.core.Core.NATIVE_LIBRARY_NAME); }

    @Test public void refinesReferencePhotoDeterministically() throws Exception {
        File dir = new File(System.getProperty("pelotavision.modelDir", "../app/src/main/assets"));
        File enc = new File(dir, Sam2Refiner.ENCODER), dec = new File(dir, Sam2Refiner.DECODER), meta = new File(dir, Sam2Refiner.METADATA);
        Assume.assumeTrue("SAM 2 não exportado (rode COMPILAR_PELOTAVISION_COMPLETO.bat)", enc.isFile() && dec.isFile() && meta.isFile());
        assertTrue(Sam2Refiner.status(), Sam2Refiner.install(enc, dec, new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8), OrtEngine.factory(false)));
        File photo = new File(System.getProperty("pelotavision.validationDir", "../verification"), "fixtures/1000411645.jpg");
        Mat bgr = Imgcodecs.imread(photo.getAbsolutePath());
        Mat gray = Mats.gray(bgr);
        try {
            PelletDetector.Result seeds = DetectionTuner.detect(gray, new DetectionTuner.Option(3, 75, DetectionTuner.GRADIENT));
            assertTrue("detector sem pelotas", seeds.circles.size() >= 5);
            PelletDetector.Result a = Sam2Refiner.get().refine(gray, seeds, () -> false);
            Sam2Refiner.Report ra = Sam2Refiner.lastReport();
            PelletDetector.Result b = Sam2Refiner.get().refine(gray, seeds, () -> false);
            System.out.println(ra.summary());
            assertTrue(ra.summary(), ra.refined >= .5 * seeds.circles.size());
            double[] ratio = new double[a.circles.size()];
            for (int i = 0; i < ratio.length; i++) {
                ratio[i] = a.circles.get(i).radius / seeds.circles.get(i).radius;
                assertEquals("determinismo", a.circles.get(i).radius, b.circles.get(i).radius, 1e-9);
                assertEquals("determinismo", a.circles.get(i).x, b.circles.get(i).x, 1e-9);
            }
            double median = NeuralPelletDetector.median(ratio);
            assertTrue("raio SAM 2 / detector = " + median, median > .7 && median < 1.3);
            assertTrue(a.method.contains("+sam2_"));
        } finally {
            gray.release(); bgr.release();
            Sam2Refiner.close();
        }
    }
}
