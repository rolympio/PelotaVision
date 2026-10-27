package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Aceleração por hardware (0.16.0 / Windows 0.5.6): ONNX Runtime contra o OpenCV DNN de referência.
 * O runner do CI não tem GPU NVIDIA: aqui o ONNX Runtime roda na CPU, o que testa a troca de motor, a
 * paridade, o retorno ao OpenCV e os tempos; a CUDA só é exercitada numa máquina com GPU.
 * Instâncias avulsas (load), para não mexer nos modelos instalados que os outros testes usam.
 */
public class HardwareAccelerationTest {
    private static File root, assets;

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        root = new File(System.getProperty("pelotavision.validationDir")).getParentFile();
        assets = new File(System.getProperty("pelotavision.modelDir"));
    }

    @After public void restore() {
        HardwareAcceleration.setEnabled(false);
        HardwareAcceleration.setProvider(OnnxRuntimeProvider.INSTANCE);
    }

    static synchronized void record(String line) throws IOException {
        System.out.println(line);
        File out = new File("build/parity/accel.txt");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String text(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    private static NeuralPelletDetector stardist(boolean accelerated) throws Exception {
        HardwareAcceleration.setEnabled(accelerated);
        try { return NeuralPelletDetector.load(new File(assets, NeuralPelletDetector.MODEL), text(new File(assets, NeuralPelletDetector.METADATA))); }
        finally { HardwareAcceleration.setEnabled(false); }
    }

    @Test public void defaultIsOffAndUsesOpenCv() throws Exception {
        NeuralPelletDetector d = stardist(false);
        try {
            assertEquals("OpenCV DNN · CPU", d.backend().description());
            assertTrue(HardwareAcceleration.status(), HardwareAcceleration.status().contains("desligada"));
        } finally { d.close(); }
    }

    /** Mesmas pelotas com o ONNX Runtime e com o OpenCV DNN, nas fotos de verificação; tempo por foto. */
    @Test public void onnxRuntimeGivesSameStarDistDetections() throws Exception {
        NeuralPelletDetector ref = stardist(false), ort = stardist(true);
        try {
            String desc = ort.backend().description();
            assertTrue("motor ONNX Runtime aprovado na sonda: " + HardwareAcceleration.status(), desc.startsWith("ONNX Runtime"));
            record("StarDist motor acelerado: " + HardwareAcceleration.status("StarDist " + ort.id));
            DetectionTuner.Option o = new DetectionTuner.Option(3, 75, DetectionTuner.NEURAL);
            for (String name : new String[]{"1000411645.jpg", "1000411646.jpg", "1000411647.jpg"}) {
                Mat g = Imgcodecs.imread(new File(root, "verification/fixtures/" + name).getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
                assertFalse(g.empty());
                ref.detect(g, o.profile, o.sensitivity, o.parameters); // aquecimento dos dois motores
                ort.detect(g, o.profile, o.sensitivity, o.parameters);
                long t0 = System.nanoTime();
                PelletDetector.Result a = ref.detect(g, o.profile, o.sensitivity, o.parameters);
                long t1 = System.nanoTime();
                PelletDetector.Result b = ort.detect(g, o.profile, o.sensitivity, o.parameters);
                long t2 = System.nanoTime();
                g.release();
                assertEquals(name + ": mesmo número de pelotas", a.circles.size(), b.circles.size());
                double worst = 0;
                for (int i = 0; i < a.circles.size(); i++) {
                    PelletDetector.Detection p = a.circles.get(i), q = b.circles.get(i);
                    worst = Math.max(worst, Math.max(Math.hypot(p.x - q.x, p.y - q.y), Math.abs(p.radius - q.radius)) / p.radius);
                }
                assertTrue(name + ": centro/raio relativos " + worst, worst < 1e-3);
                record(String.format(Locale.ROOT, "StarDist %s: %d pelotas nos dois motores, maior diferenca relativa %.2e | OpenCV DNN %.0f ms, %s %.0f ms (%.2fx)",
                        name, a.circles.size(), worst, (t1 - t0) / 1e6, desc.startsWith("ONNX Runtime · CUDA") ? "ORT CUDA" : "ORT CPU", (t2 - t1) / 1e6, (t1 - t0) / (double) Math.max(1, t2 - t1)));
            }
        } finally { ref.close(); ort.close(); }
    }

    /** Escores de trinca da referência Python reproduzidos pelo ONNX Runtime. */
    @Test public void onnxRuntimeReproducesCrackScores() throws Exception {
        File onnx = new File(assets, MobileNetCrackModel.MODEL), meta = new File(assets, MobileNetCrackModel.METADATA);
        String json = text(meta);
        HardwareAcceleration.setEnabled(true);
        MobileNetCrackModel m = MobileNetCrackModel.load(onnx, json);
        HardwareAcceleration.setEnabled(false);
        assertNotNull(MobileNetCrackModel.lastError, m);
        try {
            assertTrue(m.backend().description(), m.backend().description().startsWith("ONNX Runtime"));
            JSONArray ref = new JSONObject(json).getJSONArray("parity_reference");
            Mat gray = Imgcodecs.imread(new File(root, "verification/fixtures/crack_example.png").getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
            double worst = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < ref.length(); i++) {
                JSONObject r = ref.getJSONObject(i);
                worst = Math.max(worst, Math.abs(m.score(gray, r.getDouble("x"), r.getDouble("y"), r.getDouble("r")) - r.getDouble("p_cracked")));
            }
            double ms = (System.nanoTime() - t0) / 1e6 / ref.length();
            gray.release();
            assertTrue("escore ORT x Python " + worst, worst < 1e-3);
            record(String.format(Locale.ROOT, "MobileNetV3 %s: %d recortes, maior diferenca de escore %.2e, %.1f ms/recorte",
                    m.backend().description(), ref.length(), worst, ms));
        } finally { m.close(); }
    }

    /** Motor que devolve saída errada: recusado na sonda, a rede fica no OpenCV. */
    @Test public void wrongOutputIsRejectedByParityProbe() throws Exception {
        HardwareAcceleration.setProvider(new FakeProvider(null, Integer.MAX_VALUE, true));
        NeuralPelletDetector d = stardist(true);
        try {
            assertEquals("OpenCV DNN · CPU", d.backend().description());
            String st = HardwareAcceleration.status("StarDist " + d.id);
            assertTrue(st, st.contains("recusada") && st.contains("sem paridade"));
        } finally { d.close(); }
    }

    /** Motor que não abre: OpenCV, com o motivo no status. */
    @Test public void providerFailureFallsBackToOpenCv() throws Exception {
        HardwareAcceleration.setProvider(new FakeProvider(new IllegalStateException("sem GPU nesta máquina"), 0, false));
        NeuralPelletDetector d = stardist(true);
        try {
            assertEquals("OpenCV DNN · CPU", d.backend().description());
            assertTrue(HardwareAcceleration.status("StarDist " + d.id).contains("sem GPU nesta máquina"));
        } finally { d.close(); }
    }

    /** Motor que passa na sonda e falha depois, durante a análise: a análise termina no OpenCV com o mesmo resultado. */
    @Test public void runtimeFailureFallsBackMidAnalysis() throws Exception {
        NeuralPelletDetector ref = stardist(false);
        HardwareAcceleration.setProvider(new FakeProvider(null, 4, false)); // 2 chamadas da sonda + 2 da análise
        NeuralPelletDetector d = stardist(true);
        try {
            assertTrue(d.backend().description(), d.backend().description().startsWith("Fake"));
            Mat g = Imgcodecs.imread(new File(root, "verification/fixtures/1000411645.jpg").getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
            DetectionTuner.Option o = new DetectionTuner.Option(3, 75, DetectionTuner.NEURAL);
            List<PelletDetector.Detection> a = ref.detect(g, o.profile, o.sensitivity, o.parameters).circles;
            List<PelletDetector.Detection> b = d.detect(g, o.profile, o.sensitivity, o.parameters).circles;
            g.release();
            assertEquals("OpenCV DNN · CPU", d.backend().description());
            assertTrue(HardwareAcceleration.status("StarDist " + d.id).contains("falhou durante a análise"));
            assertEquals(a.size(), b.size());
        } finally { ref.close(); d.close(); }
    }

    /** Motor de teste: delega ao OpenCV; pode falhar ao abrir, falhar após N execuções ou zerar a saída. */
    static final class FakeProvider implements HardwareAcceleration.Provider {
        final RuntimeException openError; final int failAfter; final boolean zero;
        FakeProvider(RuntimeException openError, int failAfter, boolean zero) { this.openError = openError; this.failAfter = failAfter; this.zero = zero; }
        @Override public String name() { return "Fake"; }
        @Override public InferenceBackend open(File onnx, HardwareAcceleration.Model model, boolean precise) {
            if (openError != null) throw openError;
            OpenCvBackend inner = new OpenCvBackend(onnx);
            AtomicInteger calls = new AtomicInteger();
            return new InferenceBackend() {
                @Override public float[] run(float[] input, int[] shape) {
                    if (calls.incrementAndGet() > failAfter) throw new IllegalStateException("GPU perdida");
                    float[] y = inner.run(input, shape);
                    if (zero) java.util.Arrays.fill(y, 0f);
                    return y;
                }
                @Override public String description() { return "Fake · GPU"; }
                @Override public boolean accelerated() { return true; }
                @Override public void close() { inner.close(); }
            };
        }
    }
}
