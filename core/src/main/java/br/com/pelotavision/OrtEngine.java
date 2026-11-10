package br.com.pelotavision;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Motor ONNX Runtime com várias entradas e saídas, compartilhado por Windows (onnxruntime_gpu) e Android
 * (onnxruntime-android): mesma API Java, mesmo grafo e, na CPU, os mesmos kernels. É o motor do SAM 2.
 * Com {@code gpu} tenta CUDA (só existe no Windows); se não houver, fica na CPU.
 */
public final class OrtEngine implements Sam2Refiner.Engine {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String description;

    public OrtEngine(File onnx, boolean gpu) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
        String device = "CPU";
        if (gpu) {
            try { options.addCUDA(0); device = "CUDA"; } catch (Throwable e) { device = "CPU (CUDA indisponível)"; }
        }
        OrtSession s;
        try { s = env.createSession(onnx.getAbsolutePath(), options); }
        catch (Exception e) {
            if (!device.equals("CUDA")) throw e;
            OrtSession.SessionOptions cpu = new OrtSession.SessionOptions();
            cpu.setIntraOpNumThreads(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
            s = env.createSession(onnx.getAbsolutePath(), cpu);
            device = "CPU (CUDA falhou)";
        }
        session = s;
        description = "ONNX Runtime · " + device;
    }

    /** Fábrica para {@link Sam2Refiner#install}. {@code tryCuda}: Windows com a aceleração ligada; Android sempre CPU. */
    public static Sam2Refiner.EngineFactory factory(boolean tryCuda) {
        return onnx -> new OrtEngine(onnx, tryCuda);
    }

    @Override
    public Map<String, float[]> run(Map<String, float[]> inputs, Map<String, long[]> shapes, String[] outputs) throws Exception {
        Map<String, OnnxTensor> feed = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, float[]> e : inputs.entrySet())
                feed.put(e.getKey(), OnnxTensor.createTensor(env, FloatBuffer.wrap(e.getValue()), shapes.get(e.getKey())));
            Map<String, float[]> out = new HashMap<>();
            try (OrtSession.Result r = session.run(feed)) {
                for (String name : outputs) {
                    Optional<OnnxValue> v = r.get(name);
                    if (!v.isPresent()) throw new IllegalStateException("Saída ausente: " + name);
                    FloatBuffer fb = ((OnnxTensor) v.get()).getFloatBuffer();
                    float[] a = new float[fb.remaining()];
                    fb.get(a);
                    out.put(name, a);
                }
            }
            return out;
        } finally {
            for (OnnxTensor t : feed.values()) t.close();
        }
    }

    @Override public String description() { return description; }

    @Override public void close() {
        try { session.close(); } catch (Exception ignored) {}
    }
}
