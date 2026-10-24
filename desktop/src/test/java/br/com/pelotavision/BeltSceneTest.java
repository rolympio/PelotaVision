package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Cena de correia nas fotos reais de ml_training/data_belt (SEM rótulos): compara
 * bancada x correia em cada método. Sem verdade-terreno, mede só contagem, limite,
 * tempo e coerência do raio com o espaçamento estimado por autocorrelação.
 */
public class BeltSceneTest {
    private static File dir;
    private static JSONArray manifest;
    // Um quadro por disco/câmera, evitando quadros consecutivos.
    private static final String[] FRAMES = {"img109.jpg", "img014.jpg", "img378.jpg", "img509.jpg", "img751.jpg", "img988.jpg", "20171227_11-41-37.jpg", "img562.jpg"};

    @BeforeClass public static void load() throws IOException {
        DesktopApp.loadOpenCv();
        dir = new File(new File(System.getProperty("pelotavision.validationDir")).getParentFile(), "ml_training/data_belt");
        manifest = new JSONArray(new String(Files.readAllBytes(new File(dir, "manifest.json").toPath()), StandardCharsets.UTF_8));
    }

    static void record(String line) throws IOException {
        System.out.println(line);
        File out = new File("build/parity/belt.txt");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static JSONObject entry(String file) {
        for (int i = 0; i < manifest.length(); i++) if (manifest.getJSONObject(i).getString("file").equals(file)) return manifest.getJSONObject(i);
        throw new AssertionError(file);
    }

    static double medianRadius(PelletDetector.Result r) {
        double[] v = r.circles.stream().filter(d -> !d.partial).mapToDouble(d -> d.radius).toArray();
        if (v.length == 0) return Double.NaN;
        Arrays.sort(v);
        return v[v.length / 2];
    }

    @Test public void beltSceneFindsSmallPelletsWithoutTheBenchLimit() throws Exception {
        record("CORREIA (fotos reais sem rotulo) | por metodo: bancada n/limite -> correia n/limite, raio mediano px, ms | espacamento estimado/2 px");
        int better = 0, comparable = 0;
        for (String f : FRAMES) {
            Mat bgr = Mats.readUpright(new File(dir, "raw/" + f)), g = Mats.gray(bgr);
            bgr.release();
            try {
                JSONObject e = entry(f);
                double expected = e.getInt("spacing_px") / 2.;
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-22s %dx%d r_esp=%.1f", f, g.cols(), g.rows(), expected));
                for (int method = 0; method < 3; method++) {
                    if (method == DetectionTuner.NEURAL && !NeuralPelletDetector.available()) continue;
                    PelletDetector.Result bench = DetectionTuner.detect(g, new DetectionTuner.Option(3, 75, method, false));
                    long t0 = System.currentTimeMillis();
                    PelletDetector.Result belt = DetectionTuner.detect(g, new DetectionTuner.Option(3, 75, method, true));
                    long ms = System.currentTimeMillis() - t0;
                    int fitted = 0; for (PelletDetector.Detection d : belt.circles) if (ShapeStatistics.includes(d)) fitted++;
                    double rm = medianRadius(belt);
                    line.append(String.format(Locale.ROOT, " | %s: %d%s -> %d%s (elipses %d) r=%.1f %dms", new String[]{"bordas", "LoG", "rede"}[method],
                            bench.circles.size(), bench.candidateLimit ? "*" : "", belt.circles.size(), belt.candidateLimit ? "*" : "", fitted, rm, ms));
                    assertTrue("limite da correia", belt.circles.size() <= 5000);
                    if (e.getDouble("periodicity") >= .05 && method == DetectionTuner.GRADIENT) {
                        comparable++;
                        if (belt.circles.size() > bench.circles.size() && rm > .5 * expected && rm < 1.6 * expected) better++;
                    }
                }
                record(line.toString());
            } finally { g.release(); }
        }
        record(String.format(Locale.ROOT, "CORREIA resumo: bordas na correia achou mais objetos que na bancada, com raio coerente, em %d de %d quadros com pelotas", better, comparable));
        assertTrue("correia deveria superar a bancada na maioria dos quadros de correia", better * 2 > comparable);
    }

    @Test public void benchDefaultsUnchanged() {
        DetectionTuner.Option o = new DetectionTuner.Option(3, 75, DetectionTuner.GRADIENT);
        assertEquals(600, o.parameters.maxObjects);
        assertEquals(1024, o.parameters.workSide(1024));
    }
}
