package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Rede StarDist no OpenCV nativo do Windows (4.12): paridade com a referência Python
 * (ONNX Runtime + common.py) e comparação dos três métodos contra os círculos
 * PROVISÓRIOS de ml_training/data. É medida de desenvolvimento, não metrologia.
 */
public class NeuralDetectorTest {
    private static File root, data, stardist;

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        root = new File(System.getProperty("pelotavision.validationDir")).getParentFile();
        data = new File(root, "ml_training/data");
        stardist = new File(root, "ml_training/stardist/output");
        assertTrue(NeuralPelletDetector.status(), NeuralPelletDetector.available());
    }

    static synchronized void record(String line) throws IOException {
        System.out.println(line);
        File out = new File("build/parity/neural.txt");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static Mat gray(File image) {
        Mat bgr = Imgcodecs.imread(image.getAbsolutePath(), Imgcodecs.IMREAD_COLOR), g = new Mat();
        assertTrue("imagem " + image, !bgr.empty());
        Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY);
        bgr.release();
        return g;
    }

    static String text(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    @Test public void geometryMatchesClosedForms() {
        float[] sx = new float[64], sy = new float[64];
        for (int i = 0; i < 64; i++) { double a = 2 * Math.PI * i / 64; sx[i] = (float) (10 + 5 * Math.cos(a)); sy[i] = (float) (20 + 5 * Math.sin(a)); }
        double[] p = NeuralPelletDetector.props(sx, sy);
        assertEquals(64 / 2. * 25 * Math.sin(2 * Math.PI / 64), p[0], 1e-3);
        assertEquals(10, p[1], 1e-4); assertEquals(20, p[2], 1e-4);
        float[] ax = {0, 2, 2, 0}, ay = {0, 0, 2, 2}, bx = {1, 3, 3, 1}, by = {1, 1, 3, 3};
        assertEquals(1, NeuralPelletDetector.intersectionArea(NeuralPelletDetector.hull(ax, ay), NeuralPelletDetector.hull(bx, by)), 1e-12);
        assertEquals(.375, NeuralPelletDetector.threshold(75), 1e-12);
    }

    /** Mesma imagem, mesma conversão de cinza: o Java deve reproduzir a referência Python. */
    @Test public void reproducesPythonReference() throws Exception {
        JSONObject ref = new JSONObject(text(new File(stardist, "reference_dense.json")));
        NeuralPelletDetector nn = NeuralPelletDetector.get();
        assertEquals("referência gerada com outro modelo", ref.getString("model_sha256"), nn.sha256);
        Mat g = gray(new File(root, ref.getString("image")));
        PelletDetector.Result r;
        try { r = nn.detect(g, ref.getInt("profile"), ref.getInt("sensitivity"), new DetectionTuner.Option(3, 75, DetectionTuner.NEURAL).parameters); }
        finally { g.release(); }
        NeuralPelletDetector.ScaleReport scales = nn.lastScales();
        assertTrue("escala final", scales.finalScale != null && Math.abs(scales.finalScale / ref.getDouble("final_scale") - 1) < 1e-6);
        JSONArray expected = ref.getJSONArray("detections");
        int matched = 0; double worstCenter = 0, worstArea = 0;
        for (int i = 0; i < expected.length(); i++) {
            JSONObject e = expected.getJSONObject(i);
            PelletDetector.Detection best = null; double bd = Double.MAX_VALUE;
            for (PelletDetector.Detection d : r.circles) { double dd = Math.hypot(d.x - e.getDouble("x"), d.y - e.getDouble("y")); if (dd < bd) { bd = dd; best = d; } }
            if (best != null && bd < 1) { matched++; worstCenter = Math.max(worstCenter, bd); worstArea = Math.max(worstArea, Math.abs(best.area / e.getDouble("area") - 1)); }
        }
        record(String.format(Locale.ROOT, "PARIDADE Java/OpenCV x Python/ORT dense: python=%d java=%d casadas=%d desvio_centro_max=%.4fpx desvio_area_max=%.4f%% candidatos py=%d java=%d escala_final py=%.5f java=%.5f %dms",
                expected.length(), r.circles.size(), matched, worstCenter, 100 * worstArea, ref.getInt("candidates"), r.candidates, ref.getDouble("final_scale"), scales.finalScale, r.durationMs));
        assertTrue("contagem", Math.abs(expected.length() - r.circles.size()) <= 1);
        assertTrue("casamento", matched >= expected.length() - 1);
        assertTrue("área", worstArea < .02);
    }

    static double[] score(PelletDetector.Result r, JSONArray gt) {
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < r.circles.size(); i++) for (int j = 0; j < gt.length(); j++) {
            JSONObject c = gt.getJSONObject(j); PelletDetector.Detection d = r.circles.get(i);
            double rr = c.getDouble("radius"), dd = Math.hypot(d.x - c.getDouble("x"), d.y - c.getDouble("y")), ratio = d.radius / rr;
            if (dd < .5 * rr && ratio >= .7 && ratio <= 1 / .7) pairs.add(new double[]{dd / rr, i, j});
        }
        pairs.sort((a, b) -> Double.compare(a[0], b[0]));
        boolean[] ud = new boolean[r.circles.size()], ug = new boolean[gt.length()]; int tp = 0; double err = 0; int ne = 0;
        for (double[] p : pairs) { int i = (int) p[1], j = (int) p[2]; if (ud[i] || ug[j]) continue; ud[i] = ug[j] = true; tp++;
            JSONObject c = gt.getJSONObject(j); if (!c.optBoolean("partial")) { err += Math.abs(r.circles.get(i).radius / c.getDouble("radius") - 1); ne++; } }
        double p = tp / (double) Math.max(1, r.circles.size()), rc = tp / (double) Math.max(1, gt.length());
        return new double[]{r.circles.size(), tp, p, rc, 2 * p * rc / Math.max(1e-9, p + rc), ne == 0 ? Double.NaN : 100 * err / ne};
    }

    /** Bordas, LoG e rede (mesma cadeia: localização + elipse nas bordas), perfil Auto, sensibilidade 75. */
    @Test public void comparesMethodsOnProvisionalCircles() throws Exception {
        JSONArray scenes = new JSONArray(text(new File(data, "annotations.json")));
        JSONObject meta = new JSONObject(text(DesktopApp.model(NeuralPelletDetector.METADATA)));
        JSONObject hold = new JSONObject(text(new File(stardist, "holdout_metrics.json")));
        JSONArray excluded = hold.getJSONArray("excluded_groups");
        meta.put("sha256", hold.getString("model_sha256"));
        NeuralPelletDetector holdout = NeuralPelletDetector.load(new File(stardist, "holdout.onnx"), meta.toString());
        record("COMPARACAO perfil Auto s75 contra circulos PROVISORIOS | colunas: n casadas precisao recall F1 erro_raio%");
        for (int s = 0; s < scenes.length(); s++) {
            JSONObject scene = scenes.getJSONObject(s); String group = scene.getString("group"); JSONArray gt = scene.getJSONArray("annotations");
            boolean out = false; for (int i = 0; i < excluded.length(); i++) out |= excluded.getString(i).equals(group);
            Mat g = gray(new File(data, group + ".png"));
            try {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-9s gt=%d", group, gt.length()));
                for (int method = 0; method < 3; method++) {
                    PelletDetector.Result r = DetectionTuner.detect(g, new DetectionTuner.Option(3, 75, method));
                    line.append(fmt(new String[]{"bordas", "LoG", "rede(final, TREINO)"}[method], score(r, gt)));
                }
                if (out) {
                    DetectionTuner.Option o = new DetectionTuner.Option(3, 75, DetectionTuner.NEURAL);
                    PelletDetector.Result seeds = holdout.detect(g, o.profile, o.sensitivity, o.parameters);
                    line.append(fmt("rede(cena FORA do treino)", score(new EllipseFitter().refine(g, seeds), gt)));
                }
                record(line.toString());
            } finally { g.release(); }
        }
    }

    static String fmt(String name, double[] m) {
        return String.format(Locale.ROOT, " | %s: %d %d %.2f %.2f %.2f %s", name, (int) m[0], (int) m[1], m[2], m[3], m[4], Double.isNaN(m[5]) ? "-" : String.format(Locale.ROOT, "%.1f", m[5]));
    }
}
