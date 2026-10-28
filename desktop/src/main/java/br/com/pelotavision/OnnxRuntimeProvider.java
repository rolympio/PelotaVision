package br.com.pelotavision;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * Motor do Windows para a aceleração por hardware: ONNX Runtime com CUDA (GPU NVIDIA) e, se a CUDA não
 * carregar, ONNX Runtime na CPU. Os mesmos arquivos .onnx do OpenCV DNN, sem conversão.
 *
 * A CUDA exige placa NVIDIA, driver recente e as bibliotecas CUDA/cuDNN instaladas no Windows (não vão no
 * pacote, por tamanho e licença). Faltando qualquer uma, o status mostra o motivo e a CPU assume.
 */
final class OnnxRuntimeProvider implements HardwareAcceleration.Provider {
    static final OnnxRuntimeProvider INSTANCE = new OnnxRuntimeProvider();
    private volatile String cudaError;

    private OnnxRuntimeProvider() {}

    @Override public String name() {
        String v = OrtEnvironment.class.getPackage() == null ? null : OrtEnvironment.class.getPackage().getImplementationVersion();
        return "ONNX Runtime" + (v == null ? "" : " " + v);
    }

    /** Último motivo de a CUDA não ter sido usada (null se usada ou não tentada). */
    String cudaError() { return cudaError; }

    @Override public InferenceBackend open(File onnx, HardwareAcceleration.Model model, boolean precise) throws OrtException {
        if (precise) return null; // CUDA e CPU já calculam em fp32: não há segunda tentativa diferente
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        String why;
        if (OrtEnvironment.getAvailableProviders().contains(OrtProvider.CUDA)) {
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            try {
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                o.addCUDA(0);
                OrtSession s = env.createSession(onnx.getAbsolutePath(), o);
                cudaError = null;
                return new Backend(env, s, o, "ONNX Runtime · CUDA (GPU 0)", true);
            } catch (Throwable e) {
                o.close();
                why = HardwareAcceleration.message(e);
            }
        } else why = "pacote sem o provedor CUDA";
        cudaError = why;
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        try {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtSession s = env.createSession(onnx.getAbsolutePath(), o);
            return new Backend(env, s, o, "ONNX Runtime · CPU (CUDA indisponível: " + why + ")", false);
        } catch (OrtException | RuntimeException e) {
            o.close();
            throw e;
        }
    }

    static final class Backend implements InferenceBackend {
        private final OrtEnvironment env;
        private OrtSession session;
        private final OrtSession.SessionOptions options;
        private final String input, description;
        private final boolean gpu;

        Backend(OrtEnvironment env, OrtSession session, OrtSession.SessionOptions options, String description, boolean gpu) {
            this.env = env; this.session = session; this.options = options; this.description = description; this.gpu = gpu;
            this.input = session.getInputNames().iterator().next();
        }

        @Override public synchronized float[] run(float[] x, int[] shape) throws OrtException {
            if (session == null) throw new IllegalStateException("Sessão ONNX Runtime liberada.");
            long[] s = new long[shape.length];
            for (int i = 0; i < s.length; i++) s[i] = shape[i];
            try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), s);
                 OrtSession.Result r = session.run(Collections.singletonMap(input, t))) {
                OnnxValue v = r.get(0);
                if (!(v instanceof OnnxTensor)) throw new IllegalStateException("Saída ONNX inesperada.");
                FloatBuffer b = ((OnnxTensor) v).getFloatBuffer();
                float[] y = new float[b.remaining()];
                b.get(y);
                return y;
            }
        }

        @Override public String description() { return description; }
        @Override public boolean accelerated() { return gpu; }

        @Override public synchronized void close() {
            if (session == null) return;
            try { session.close(); } catch (OrtException ignored) {}
            options.close();
            session = null;
        }
    }
}
