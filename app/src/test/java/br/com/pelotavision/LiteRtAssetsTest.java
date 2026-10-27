package br.com.pelotavision;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.*;

/**
 * 0.16.0: os .tflite embarcados correspondem aos ONNX embarcados (o app recusa conversão desatualizada) e a
 * transposição NHWC → NCHW da saída do LiteRT está correta. A GPU em si só roda no aparelho.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LiteRtAssetsTest {
    private static final File ASSETS = new File("src/main/assets");

    private static String sha256(File f) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()))) s.append(String.format("%02x", b & 255));
        return s.toString();
    }

    @Test public void tfliteSidecarsMatchEmbeddedOnnx() throws Exception {
        int found = 0;
        for (String stem : new String[]{"pellet_stardist", "pellet_stardist_belt", "crack_mobilenetv3"}) {
            File side = new File(ASSETS, stem + ".tflite.json");
            if (!side.isFile()) continue; // sem .tflite o Android usa o OpenCV DNN (status explica)
            found++;
            JSONObject j = new JSONObject(new String(Files.readAllBytes(side.toPath()), StandardCharsets.UTF_8));
            assertEquals(stem + ": ONNX de origem", sha256(new File(ASSETS, stem + ".onnx")), j.getString("onnx_sha256"));
            assertEquals(stem + ": .tflite", sha256(new File(ASSETS, j.getString("tflite"))), j.getString("tflite_sha256"));
            assertTrue(stem + ": paridade aceita na conversão", j.getBoolean("accepted"));
        }
        System.out.println("tflite embarcados: " + found);
    }

    @Test public void nhwcToNchw() {
        // [1,H=2,W=3,C=2]: valor = 100*c + 10*h + w
        int h = 2, w = 3, c = 2;
        float[] nhwc = new float[h * w * c];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int k = 0; k < c; k++) nhwc[(y * w + x) * c + k] = 100 * k + 10 * y + x;
        float[] nchw = LiteRtProvider.Backend.toNchw(nhwc, new int[]{1, h, w, c});
        for (int k = 0; k < c; k++) for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            assertEquals(100 * k + 10 * y + x, nchw[(k * h + y) * w + x], 0f);
    }
}
