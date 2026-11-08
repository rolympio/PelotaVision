package br.com.pelotavision;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class ExperimentalMultitaskTest {
    @BeforeClass public static void loadNative() { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    @Test public void reflectAndTileMatchNumpyPadding() {
        int[] expected = {0, 1, 2, 1, 0, 1, 2, 1, 0};
        for (int i = -4; i <= 4; i++) assertEquals(expected[i + 4], ExperimentalMultitask.reflect(i, 3));
        byte[] gray = {0, 10, 20, 30, 40, 50};
        float[] tile = new float[128 * 128];
        ExperimentalMultitask.fillTile(gray, 3, 2, 0, 0, tile);
        assertEquals(0f, tile[32 * 128 + 32], 0f);
        assertEquals(10 / 255f, tile[32 * 128 + 31], 0f);
        assertEquals(30 / 255f, tile[31 * 128 + 32], 0f);
    }

    @Test public void syntheticLogitsProduceIndependentColorsAndUncalibratedEntropy() throws Exception {
        ExperimentalMultitask.Accumulator a = new ExperimentalMultitask.Accumulator(2, 2);
        float[] logits = new float[3 * 128 * 128];
        Arrays.fill(logits, 0, 128 * 128, 2);
        Arrays.fill(logits, 128 * 128, 2 * 128 * 128, -2);
        Arrays.fill(logits, 2 * 128 * 128, 3 * 128 * 128, 1);
        a.add(logits, 0, 0);
        assertArrayEquals(new long[]{4, 0, 4}, a.counts);
        assertEquals(0xa000ffff, a.overlay.getRGB(0, 0));
        assertTrue(a.entropySum > 0 && a.entropySum < 4);
        assertTrue((a.entropyOverlay.getRGB(0, 0) >>> 24) > 0);
        logits[32 * 128 + 32] = Float.NaN;
        assertThrows(IOException.class, () -> a.add(logits, 0, 0));
    }

    @Test public void resizeContractAndNearestMapping() {
        assertArrayEquals(new int[]{1024, 512}, ExperimentalMultitask.processingSize(2048, 1024));
        // 1025 * 1024/2048 = 512.5: Python round seleciona o par 512.
        assertArrayEquals(new int[]{1024, 512}, ExperimentalMultitask.processingSize(2048, 1025));
        assertArrayEquals(new int[]{500, 300}, ExperimentalMultitask.processingSize(500, 300));
        assertThrows(IllegalArgumentException.class, () -> ExperimentalMultitask.processingSize(4096, 2));
        assertThrows(IllegalArgumentException.class, () -> ExperimentalMultitask.processingSize(4097, 2));
        BufferedImage in = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        in.setRGB(0, 0, 0xff000001); in.setRGB(1, 0, 0xff000002);
        in.setRGB(0, 1, 0xff000003); in.setRGB(1, 1, 0xff000004);
        BufferedImage out = ExperimentalMultitask.nearest(in, 5, 3);
        assertEquals(0xff000001, out.getRGB(2, 1));
        assertEquals(0xff000002, out.getRGB(3, 1));
        assertEquals(0xff000004, out.getRGB(4, 2));
    }

    @Test public void rejectsWrongInputsAndModelHash() throws Exception {
        Mat bgr = new Mat(2, 2, CvType.CV_8UC3);
        Mat wrong = new Mat(2, 2, CvType.CV_8UC1);
        File invalid = Files.createTempFile("pv-invalid-model-", ".onnx").toFile();
        try {
            Files.write(invalid.toPath(), new byte[]{1, 2, 3});
            assertThrows(IllegalArgumentException.class, () -> ExperimentalMultitask.infer(invalid, wrong));
            assertThrows(IOException.class, () -> ExperimentalMultitask.infer(null, bgr));
            assertThrows(IOException.class, () -> ExperimentalMultitask.infer(invalid, bgr));
        } finally { bgr.release(); wrong.release(); Files.deleteIfExists(invalid.toPath()); }
    }

    @Test public void cancellationBeforeLoadingDoesNotConsumeModel() {
        Mat bgr = new Mat(2, 2, CvType.CV_8UC3);
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> ExperimentalMultitask.infer(null, bgr, () -> true));
        } finally { bgr.release(); }
    }

    /** Referência PyTorch/Python predict_tiled(core=64, context=32), imagem sintética 73x91. */
    @Test public void optionalRealModelMatchesPythonSyntheticReference() throws Exception {
        String path = System.getenv("PELOTAVISION_EXPERIMENTAL_MODEL");
        assumeTrue("Defina PELOTAVISION_EXPERIMENTAL_MODEL para testar o ONNX local", path != null && new File(path).isFile());
        int w = 91, h = 73;
        byte[] pixels = new byte[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            for (int c = 0; c < 3; c++) pixels[(y * w + x) * 3 + c] = (byte) ((x * 7 + y * 11) % 256);
        Mat bgr = new Mat(h, w, CvType.CV_8UC3);
        try {
            bgr.put(0, 0, pixels);
            ExperimentalMultitask.Result r = ExperimentalMultitask.infer(new File(path), bgr);
            assertEquals(6643, r.interiorPixels); assertEquals(22, r.boundaryPixels); assertEquals(0, r.separationPixels);
            assertEquals(0.8079909355351407, r.meanEntropy, 1e-5);
            assertEquals(0xa0ffff00, r.overlay.getRGB(0, 0));
            assertEquals(w, r.processingWidth); assertEquals(h, r.processingHeight);
            assertEquals(1, r.scaleX, 0); assertEquals(1, r.scaleY, 0);
            assertTrue(r.warning.contains("Experimental"));
        } finally { bgr.release(); }
    }

    /** Referência Python: INTER_AREA em BGR, cinza OpenCV, predict_tiled core=64. */
    @Test public void optionalModelMatchesPythonAfterDownscaling() throws Exception {
        String path = System.getenv("PELOTAVISION_EXPERIMENTAL_MODEL");
        assumeTrue("Defina PELOTAVISION_EXPERIMENTAL_MODEL para testar o ONNX local", path != null && new File(path).isFile());
        int w = 1028, h = 67;
        byte[] pixels = new byte[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 3;
            pixels[i] = (byte) ((x * 7 + y * 11) % 256);
            pixels[i + 1] = (byte) ((x * 3 + y * 17) % 256);
            pixels[i + 2] = (byte) ((x * 13 + y * 5) % 256);
        }
        Mat bgr = new Mat(h, w, CvType.CV_8UC3);
        try {
            bgr.put(0, 0, pixels);
            ExperimentalMultitask.Result r = ExperimentalMultitask.infer(new File(path), bgr);
            assertEquals(1024, r.processingWidth); assertEquals(67, r.processingHeight);
            assertEquals(1024.0 / 1028, r.scaleX, 0); assertEquals(1, r.scaleY, 0);
            assertEquals(w, r.overlay.getWidth()); assertEquals(h, r.overlay.getHeight());
            assertEquals(68608, r.interiorPixels); assertEquals(16, r.boundaryPixels); assertEquals(0, r.separationPixels);
            assertEquals(0.8256962705453966, r.meanEntropy, 1e-5);
        } finally { bgr.release(); }
    }
}
