package br.com.pelotavision;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * Item 11 (consistência entre plataformas): o MobileNetCrackModel.java (OpenCV DNN 4.12, o mesmo motor
 * do Android) reproduz a entrada e o escore de tools/cracks/train_mobilenet.py na foto real
 * crack_example.png. A referência foi gravada pelo treino no CI (crack_mobilenetv3.json).
 * Também verifica o fallback para o RF e o modo de comparação no CrackDetector.
 */
public class MobileNetParityTest {
    private static File root, assets;

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        root = new File(System.getProperty("pelotavision.validationDir")).getParentFile();
        assets = new File(System.getProperty("pelotavision.modelDir"));
    }

    @Test public void javaInputAndScoreMatchPythonReference() throws Exception {
        File onnx = new File(assets, MobileNetCrackModel.MODEL), meta = new File(assets, MobileNetCrackModel.METADATA);
        assertTrue("modelo experimental embarcado", onnx.isFile() && meta.isFile());
        String json = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
        MobileNetCrackModel model = MobileNetCrackModel.load(onnx, json);
        assertNotNull(MobileNetCrackModel.lastError, model);
        assertFalse("não validado: continua experimental", model.validated());
        JSONArray ref = new JSONObject(json).getJSONArray("parity_reference");
        assertTrue(ref.length() >= 8);
        Mat gray = Imgcodecs.imread(new File(root, "verification/fixtures/crack_example.png").getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
        assertFalse(gray.empty());
        double worstScore = 0, worstSum = 0;
        StringBuilder log = new StringBuilder("id;python_p;java_p;python_input_sum;java_input_sum\n");
        for (int i = 0; i < ref.length(); i++) {
            JSONObject r = ref.getJSONObject(i);
            Mat x = model.input(gray, r.getDouble("x"), r.getDouble("y"), r.getDouble("r"));
            double sum = Core.sumElems(x).val[0];
            x.release();
            double p = model.score(gray, r.getDouble("x"), r.getDouble("y"), r.getDouble("r"));
            worstScore = Math.max(worstScore, Math.abs(p - r.getDouble("p_cracked")));
            worstSum = Math.max(worstSum, Math.abs(sum - r.getDouble("input_sum")) / Math.max(1, r.getDouble("input_sum")));
            log.append(r.getString("id")).append(';').append(r.getDouble("p_cracked")).append(';').append(p).append(';')
               .append(r.getDouble("input_sum")).append(';').append(sum).append('\n');
        }
        File out = new File(root, "verification/mobilenet-cracks-20261025/paridade_java_windows.csv");
        Files.write(out.toPath(), (log + "max_abs_diff_score=" + worstScore + "\nmax_rel_diff_input_sum=" + worstSum + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue("entrada idêntica (soma relativa) " + worstSum, worstSum < 1e-6);
        assertTrue("escore Java x Python " + worstScore, worstScore < 1e-3);
    }

    @Test public void comparisonModeKeepsForestStatusAndRecordsNetworkScore() throws Exception {
        File onnx = new File(assets, MobileNetCrackModel.MODEL);
        String json = new String(Files.readAllBytes(new File(assets, MobileNetCrackModel.METADATA).toPath()), StandardCharsets.UTF_8);
        MobileNetCrackModel net = MobileNetCrackModel.load(onnx, json);
        CrackModel rf;
        try (java.io.InputStream in = new java.io.FileInputStream(new File(assets, "crack_forest.pvrf"))) { rf = new CrackModel(in); }
        Mat gray = Imgcodecs.imread(new File(root, "verification/fixtures/crack_example.png").getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
        JSONObject fx = new JSONObject(new String(Files.readAllBytes(new File(root, "verification/fixtures/crack_example_analysis.json").toPath()), StandardCharsets.UTF_8));
        List<PelletDetector.Detection> ds = new ArrayList<>();
        JSONArray cs = fx.getJSONArray("circles");
        for (int i = 0; i < 20; i++) { JSONObject c = cs.getJSONObject(i); ds.add(new PelletDetector.Detection(c.getDouble("x"), c.getDouble("y"), c.getDouble("radius"), c.getDouble("area"), 1, c.getBoolean("partial"), false)); }
        PelletDetector.Result result = new PelletDetector.Result(ds, gray.cols(), gray.rows(), ds.size(), 0, false, "fixture");
        CrackAnalysis forest = new CrackDetector(rf).detect(gray, result, 50);
        CrackAnalysis compared = new CrackDetector(rf, net, CrackClassifierChoice.COMPARISON).detect(gray, result, 50);
        CrackAnalysis network = new CrackDetector(rf, net, CrackClassifierChoice.MOBILENET).detect(gray, result, 50);
        for (int i = 0; i < ds.size(); i++) {
            assertEquals("comparação não altera o status do RF", forest.entries.get(i).status, compared.entries.get(i).status);
            assertEquals(forest.entries.get(i).modelScore, compared.entries.get(i).modelScore, 0);
            assertEquals(network.entries.get(i).modelScore, compared.entries.get(i).comparisonScore, 1e-9);
            assertEquals(net.id, network.entries.get(i).method);
        }
        CrackClassifierChoice.Resolution missing = CrackClassifierChoice.resolve(CrackClassifierChoice.MOBILENET, true,
                MobileNetCrackModel.load(new File(assets, "nao_existe.onnx"), json) != null, MobileNetCrackModel.lastError);
        assertEquals(CrackClassifierChoice.RANDOM_FOREST, missing.effective);
        CrackAnalysis fallback = new CrackDetector(rf, null, missing.effective).detect(gray, result, 50);
        assertEquals(CrackModel.ID, fallback.entries.get(0).method);
    }
}
