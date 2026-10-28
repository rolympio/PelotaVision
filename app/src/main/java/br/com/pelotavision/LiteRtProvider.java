package br.com.pelotavision;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Motor do Android para a aceleração por hardware: LiteRT (antigo TensorFlow Lite) com o GpuDelegate e,
 * se a GPU não servir, LiteRT na CPU (XNNPACK). Usa o .tflite convertido do MESMO ONNX no CI
 * (tools/litert/convert_tflite.py); o .tflite.json ao lado guarda o SHA-256 do ONNX de origem e o
 * layout da saída. Conversão de outro ONNX, ou ausente, é recusada (o modelo fica no OpenCV DNN).
 *
 * Cada interpretador vive numa thread própria: o GpuDelegate (OpenGL) exige que a rede rode na thread
 * em que foi criado.
 */
final class LiteRtProvider implements HardwareAcceleration.Provider {
    static final String VERSION = "1.4.2";
    private final Context context;

    LiteRtProvider(Context context) { this.context = context.getApplicationContext(); }

    @Override public String name() { return "LiteRT " + VERSION; }

    @Override public InferenceBackend open(File onnx, HardwareAcceleration.Model model, boolean precise) throws Exception {
        String stem = onnx.getName().replaceFirst("\\.onnx$", "");
        JSONObject side;
        try { side = new JSONObject(readAsset(stem + ".tflite.json")); }
        catch (java.io.IOException e) { throw new IllegalStateException("sem " + stem + ".tflite no aplicativo"); }
        if (!model.onnxSha256.equals(side.optString("onnx_sha256")))
            throw new IllegalStateException(stem + ".tflite foi convertido de outro ONNX (conversão desatualizada)");
        if (!side.optBoolean("accepted", false)) throw new IllegalStateException(stem + ".tflite reprovado na paridade da conversão");
        File tflite = copyAsset(side.getString("tflite"));
        if (!side.getString("tflite_sha256").equals(sha256(tflite))) throw new IllegalStateException(stem + ".tflite corrompido (SHA-256)");
        int[] in = shape(side.getJSONArray("input_shape")), out = shape(side.getJSONArray("output_shape"));
        int n = 1;
        for (int d : in) n *= d;
        int m = 1;
        for (int d : model.inputShape) m *= d;
        if (n != m) throw new IllegalStateException("entrada do .tflite " + java.util.Arrays.toString(in) + " difere do ONNX");
        boolean nhwc = "NHWC".equals(side.optString("output_layout"));
        return Backend.create(tflite, in, out, nhwc, precise);
    }

    static final class Backend implements InferenceBackend {
        private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PelotaVision-LiteRT"); t.setDaemon(true); return t; });
        private Interpreter interpreter;
        private GpuDelegate delegate;
        private ByteBuffer input, output;
        private final int[] outShape;
        private final boolean nhwc;
        private String description;
        private boolean gpu;

        private Backend(int[] outShape, boolean nhwc) { this.outShape = outShape; this.nhwc = nhwc; }

        static Backend create(File tflite, int[] in, int[] out, boolean nhwc, boolean precise) throws Exception {
            Backend b = new Backend(out, nhwc);
            try {
                b.call(() -> { b.build(tflite, in, out, precise); return null; });
            } catch (Exception e) {
                b.close();
                throw e;
            }
            if (b.interpreter == null) { b.close(); return null; }
            return b;
        }

        /** GPU (fp16, ou fp32 se {@code precise}); sem GPU compatível, CPU XNNPACK (só na primeira tentativa). */
        private void build(File tflite, int[] in, int[] out, boolean precise) {
            String why;
            try (CompatibilityList compat = new CompatibilityList()) {
                if (compat.isDelegateSupportedOnThisDevice()) {
                    GpuDelegate.Options o = compat.getBestOptionsForThisDevice();
                    o.setPrecisionLossAllowed(!precise);
                    o.setInferencePreference(org.tensorflow.lite.gpu.GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
                    try {
                        delegate = new GpuDelegate(o);
                        Interpreter.Options io = new Interpreter.Options();
                        io.addDelegate(delegate);
                        interpreter = new Interpreter(tflite, io);
                        gpu = true;
                        description = "LiteRT · GPU (" + (precise ? "fp32" : "fp16") + ")";
                        buffers(in, out);
                        return;
                    } catch (Throwable e) {
                        if (interpreter != null) interpreter.close();
                        interpreter = null;
                        if (delegate != null) delegate.close();
                        delegate = null;
                        why = HardwareAcceleration.message(e);
                    }
                } else why = "GPU fora da lista de compatibilidade do LiteRT";
            }
            if (precise) return; // a CPU já calcula em fp32: nada novo a tentar
            Interpreter.Options io = new Interpreter.Options();
            io.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            io.setUseXNNPACK(true);
            interpreter = new Interpreter(tflite, io);
            description = "LiteRT · CPU (GPU indisponível: " + why + ")";
            buffers(in, out);
        }

        private void buffers(int[] in, int[] out) {
            int n = 1, m = 1;
            for (int d : in) n *= d;
            for (int d : out) m *= d;
            input = ByteBuffer.allocateDirect(4 * n).order(ByteOrder.nativeOrder());
            output = ByteBuffer.allocateDirect(4 * m).order(ByteOrder.nativeOrder());
        }

        @Override public float[] run(float[] x, int[] shape) throws Exception {
            return call(() -> {
                if (interpreter == null) throw new IllegalStateException("LiteRT liberado.");
                if (x.length * 4 != input.capacity()) throw new IllegalArgumentException("Entrada com tamanho inesperado.");
                input.rewind();
                input.asFloatBuffer().put(x);
                output.rewind();
                interpreter.run(input, output);
                output.rewind();
                FloatBuffer fb = output.asFloatBuffer();
                float[] y = new float[fb.remaining()];
                fb.get(y);
                return nhwc && outShape.length == 4 ? toNchw(y, outShape) : y;
            });
        }

        /** [1,H,W,C] → [1,C,H,W]. */
        static float[] toNchw(float[] y, int[] s) {
            int h = s[1], w = s[2], c = s[3];
            float[] z = new float[y.length];
            for (int i = 0; i < h * w; i++) for (int k = 0; k < c; k++) z[k * h * w + i] = y[i * c + k];
            return z;
        }

        private <T> T call(Callable<T> task) throws Exception {
            try { return thread.submit(task).get(); }
            catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception) throw (Exception) c;
                throw new IllegalStateException(c);
            }
        }

        @Override public String description() { return description; }
        @Override public boolean accelerated() { return gpu; }

        @Override public void close() {
            if (thread.isShutdown()) return;
            try {
                thread.submit(() -> {
                    if (interpreter != null) interpreter.close();
                    if (delegate != null) delegate.close();
                    interpreter = null; delegate = null;
                }).get();
            } catch (Exception ignored) {
            } finally { thread.shutdown(); }
        }
    }

    // ------------------------------------------------------------------ assets

    private File copyAsset(String name) throws java.io.IOException {
        File target = new File(context.getCacheDir(), name);
        try (InputStream in = context.getAssets().open(name); java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] b = new byte[16384]; int k;
            while ((k = in.read(b)) != -1) out.write(b, 0, k);
        }
        return target;
    }

    private String readAsset(String name) throws java.io.IOException {
        try (InputStream in = context.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int k;
            while ((k = in.read(b)) != -1) bytes.write(b, 0, k);
            return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static int[] shape(JSONArray a) throws org.json.JSONException {
        int[] s = new int[a.length()];
        for (int i = 0; i < s.length; i++) s[i] = a.getInt(i);
        return s;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[16384]; int k;
            while ((k = in.read(b)) != -1) md.update(b, 0, k);
        }
        StringBuilder s = new StringBuilder();
        for (byte v : md.digest()) s.append(String.format("%02x", v & 255));
        return s.toString();
    }
}
