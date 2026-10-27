package br.com.pelotavision;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aceleração por hardware das redes neurais (StarDist e MobileNetV3), DESLIGADA por padrão.
 *
 * Ligada, cada plataforma oferece o seu motor ({@link Provider}): ONNX Runtime no Windows (CUDA, depois CPU)
 * e LiteRT no Android (GPU, depois CPU). O motor só é aceito depois de uma SONDA DE PARIDADE: a mesma
 * entrada roda nele e no OpenCV DNN (a referência) e as saídas precisam coincidir dentro da tolerância
 * do modelo. Sem paridade, sem motor da plataforma ou com erro (ao abrir ou depois, numa execução), o
 * modelo volta sozinho para o OpenCV DNN. O pré e o pós-processamento não mudam: só o motor da rede.
 *
 * A super-resolução FSRCNN e todo o processamento clássico continuam na CPU.
 */
public final class HardwareAcceleration {
    private HardwareAcceleration() {}

    /** Motor de uma plataforma. {@code precise}: pede cálculo em fp32 (segunda tentativa após falha de paridade em fp16). */
    public interface Provider {
        InferenceBackend open(File onnx, Model model, boolean precise) throws Exception;
        /** Ex.: "ONNX Runtime 1.31.1", "LiteRT 1.4.2". */
        String name();
    }

    /** Descrição de um modelo para a sonda de paridade. */
    public static final class Model {
        public final String key, onnxSha256;
        public final int[] inputShape;
        final float[] probe;
        /** Tolerância absoluta do primeiro canal (ou de toda a saída, se {@code channelSize} = 0) e dos demais. */
        final double tolFirst, tolRest;
        final int channelSize;

        public Model(String key, String onnxSha256, int[] inputShape, float[] probe, double tolFirst, double tolRest, int channelSize) {
            this.key = key; this.onnxSha256 = onnxSha256; this.inputShape = inputShape.clone(); this.probe = probe;
            this.tolFirst = tolFirst; this.tolRest = tolRest; this.channelSize = channelSize;
        }

        /** Maior erro relativo à tolerância (≤ 1 aceita). Formas diferentes: infinito. */
        double parityError(float[] reference, float[] candidate) {
            if (reference.length != candidate.length) return Double.POSITIVE_INFINITY;
            double worst = 0;
            for (int i = 0; i < reference.length; i++) {
                double tol = channelSize <= 0 || i < channelSize ? tolFirst : tolRest;
                double e = Math.abs(reference[i] - candidate[i]);
                if (!(e <= Double.MAX_VALUE)) return Double.POSITIVE_INFINITY; // NaN/Inf
                worst = Math.max(worst, e / tol);
            }
            return worst;
        }
    }

    private static volatile boolean enabled;
    private static volatile Provider provider;
    private static final Map<String, String> status = new LinkedHashMap<>();

    public static boolean enabled() { return enabled; }
    /** Só muda a escolha; quem carrega os modelos precisa recarregá-los (cada plataforma faz isso ao trocar a opção). */
    public static void setEnabled(boolean on) { enabled = on; }
    public static void setProvider(Provider p) { provider = p; }
    public static Provider provider() { return provider; }

    /**
     * Motor para o modelo: OpenCV DNN quando a aceleração está desligada; senão o da plataforma, aprovado na sonda,
     * envolto num {@link Guarded} que volta ao OpenCV se uma execução falhar.
     */
    public static InferenceBackend open(File onnx, Model model) {
        OpenCvBackend reference = new OpenCvBackend(onnx);
        Provider p = provider;
        if (!enabled) { record(model.key, reference.description()); return reference; }
        if (p == null) { record(model.key, reference.description() + " · aceleração indisponível nesta plataforma"); return reference; }
        String why = null;
        try {
            long t0 = System.nanoTime();
            float[] ref = reference.run(model.probe, model.inputShape);
            ref = reference.run(model.probe, model.inputShape);
            double refMs = (System.nanoTime() - t0) / 2e6;
            for (boolean precise : new boolean[]{false, true}) {
                InferenceBackend candidate = null;
                try {
                    candidate = p.open(onnx, model, precise);
                    if (candidate == null) { if (why == null) why = "motor não disponível"; continue; }
                    candidate.run(model.probe, model.inputShape); // aquecimento (compila kernels da GPU)
                    long t1 = System.nanoTime();
                    float[] got = candidate.run(model.probe, model.inputShape);
                    double ms = (System.nanoTime() - t1) / 1e6;
                    double err = model.parityError(ref, got);
                    if (err <= 1) {
                        String d = String.format(java.util.Locale.ROOT, "%s · paridade %.2f da tolerância · sonda %.0f ms (OpenCV %.0f ms)",
                                candidate.description(), err, ms, refMs);
                        record(model.key, d);
                        return new Guarded(candidate, reference, model.key);
                    }
                    why = String.format(java.util.Locale.ROOT, "%s sem paridade (%.1f× a tolerância)", candidate.description(), err);
                    candidate.close();
                } catch (Throwable e) {
                    if (candidate != null) candidate.close();
                    why = (precise ? "fp32: " : "") + message(e);
                }
            }
        } catch (Throwable e) {
            why = message(e);
        }
        record(model.key, reference.description() + " · aceleração recusada: " + why);
        return reference;
    }

    /** Estado por modelo (para a tela de opções e o autoteste). */
    public static synchronized String status() {
        StringBuilder s = new StringBuilder("Aceleração por hardware: ").append(enabled ? "LIGADA" : "desligada");
        Provider p = provider;
        if (p != null) s.append(" · motor da plataforma: ").append(p.name());
        for (Map.Entry<String, String> e : status.entrySet()) s.append('\n').append(e.getKey()).append(": ").append(e.getValue());
        return s.toString();
    }
    public static synchronized String status(String key) { return status.get(key); }
    static synchronized void record(String key, String text) { status.put(key, text); }

    static String message(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ');
        return m.length() > 240 ? m.substring(0, 240) + "…" : m;
    }

    /** Motor acelerado com retorno ao OpenCV DNN na primeira falha de execução. */
    static final class Guarded implements InferenceBackend {
        private InferenceBackend active;
        private final OpenCvBackend fallback;
        private final String key;
        private long runs, nanos;

        Guarded(InferenceBackend accelerated, OpenCvBackend fallback, String key) { active = accelerated; this.fallback = fallback; this.key = key; }

        @Override public synchronized float[] run(float[] input, int[] shape) throws Exception {
            if (active != fallback) {
                try {
                    long t = System.nanoTime();
                    float[] y = active.run(input, shape);
                    nanos += System.nanoTime() - t; runs++;
                    return y;
                } catch (Throwable e) {
                    String d = active.description();
                    active.close();
                    active = fallback;
                    record(key, fallback.description() + " · " + d + " falhou durante a análise e foi desligado: " + message(e));
                }
            }
            return fallback.run(input, shape);
        }
        @Override public synchronized String description() { return active.description(); }
        @Override public synchronized boolean accelerated() { return active.accelerated(); }
        /** Tempo médio por execução no motor acelerado (ms), ou NaN. */
        synchronized double meanMs() { return runs == 0 ? Double.NaN : nanos / 1e6 / runs; }
        @Override public synchronized void close() { if (active != fallback) active.close(); fallback.close(); }
    }

    /** Tempo médio por execução no motor acelerado, se houver. */
    public static double meanMs(InferenceBackend b) { return b instanceof Guarded ? ((Guarded) b).meanMs() : Double.NaN; }
}
