package br.com.pelotavision;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

/** Ensaio isolado: não é detector, segmentação de referência nem medida científica. */
public final class ExperimentalMultitask {
    public static final String MODEL_SHA256 = "eaf79162c6693b3e502613254c47a0ea016faae8212604a36625faf0f9488c31";
    public static final String WARNING = "Experimental, sem validação independente; limiar 0,5 heurístico. "
            + "Não é referência, diagnóstico de trinca/integridade ou medida; não usar em análises e exportações.";
    public static final double THRESHOLD = 0.5;
    public static final int MAX_PIXELS = 4_194_304;
    public static final int MAX_SIDE = 4096;
    private static final int MAX_MODEL_BYTES = 10 * 1024 * 1024;
    private static final int CORE = 64, HALO = 32, TILE = 128, PLANE = TILE * TILE;
    private static Net cachedNet;

    private ExperimentalMultitask() {}

    /** Visualizações na grade analisada; contagens e entropia na grade da rede, não em pelotas. */
    public static final class Result {
        /** Verde interior, vermelho borda, azul separação; combinações somam cores. */
        public final BufferedImage overlay;
        /** Magenta com opacidade proporcional à entropia Bernoulli média, não calibrada. */
        public final BufferedImage entropyOverlay;
        public final long interiorPixels, boundaryPixels, separationPixels;
        public final double meanEntropy;
        public final int processingWidth, processingHeight, analyzedWidth, analyzedHeight;
        public final double scaleX, scaleY;
        public final String warning = WARNING;

        private Result(Accumulator a, int analyzedWidth, int analyzedHeight) {
            this.analyzedWidth = analyzedWidth; this.analyzedHeight = analyzedHeight;
            processingWidth = a.overlay.getWidth(); processingHeight = a.overlay.getHeight();
            scaleX = processingWidth / (double) analyzedWidth; scaleY = processingHeight / (double) analyzedHeight;
            overlay = nearest(a.overlay, analyzedWidth, analyzedHeight);
            entropyOverlay = nearest(a.entropyOverlay, analyzedWidth, analyzedHeight);
            interiorPixels = a.counts[0]; boundaryPixels = a.counts[1]; separationPixels = a.counts[2];
            meanEntropy = a.entropySum / ((long) processingWidth * processingHeight);
        }
    }

    /**
     * CPU, arquivo escolhido pelo usuário, BGR CV_8UC3, sem alterar a imagem. Reduz primeiro
     * a grade BGR para lado máximo 1024, com INTER_AREA e arredondamento Python (empates pares).
     * Retorna as visualizações na grade analisada, por vizinho mais próximo. Carregue o OpenCV
     * antes da chamada e execute fora da EDT. O chamador conserva a posse do Mat. Interrupção
     * é verificada entre blocos. Erros são propagados; nunca retorna imagem parcial como sucesso.
     */
    public static synchronized Result infer(File onnx, Mat bgr) throws IOException {
        return infer(onnx, bgr, () -> false);
    }

    public static synchronized Result infer(File onnx, Mat bgr, BooleanSupplier cancelled) throws IOException {
        if (cancelled == null) throw new IllegalArgumentException("Cancelamento deve ser fornecido");
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Inferência cancelada");
        if (bgr == null || bgr.empty() || bgr.type() != CvType.CV_8UC3)
            throw new IllegalArgumentException("Imagem deve ser BGR CV_8UC3 não vazia");
        int sourceH = bgr.rows(), sourceW = bgr.cols();
        validateSize(sourceW, sourceH);
        int[] size = processingSize(sourceW, sourceH);
        int w = size[0], h = size[1];
        byte[] model = verifiedModel(onnx);
        if (cachedNet == null) {
            MatOfByte bytes = new MatOfByte(model);
            Net loaded;
            try { loaded = Dnn.readNetFromONNX(bytes); }
            finally { bytes.release(); }
            if (loaded.empty()) throw new IOException("Rede ONNX vazia");
            loaded.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            loaded.setPreferableTarget(Dnn.DNN_TARGET_CPU);
            cachedNet = loaded;
        }
        Net net = cachedNet;
        // A API Java OpenCV 4.12 não expõe close/release em Net: o objeto nativo é
        // liberado pelo finalizador do wrapper. A única rede fixada pelo SHA é reutilizada;
        // isso evita acumular um Net nativo a cada análise. Mats são liberados explicitamente.
        Mat gray = new Mat(), resized = new Mat();
        try {
            Mat source = bgr;
            if (w != sourceW || h != sourceH) {
                Imgproc.resize(bgr, resized, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
                source = resized;
            }
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
            byte[] pixels = new byte[w * h];
            if (gray.get(0, 0, pixels) != pixels.length) throw new IOException("Falha ao ler imagem cinza");
            Accumulator a = new Accumulator(w, h);
            float[] input = new float[PLANE];
            float[] logits = new float[3 * PLANE];
            Mat blob = new Mat(new int[]{1, 1, TILE, TILE}, CvType.CV_32F);
            try {
                for (int y = 0; y < h; y += CORE) for (int x = 0; x < w; x += CORE) {
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("Inferência cancelada");
                    fillTile(pixels, w, h, x, y, input);
                    blob.put(new int[]{0, 0, 0, 0}, input);
                    net.setInput(blob);
                    Mat out = net.forward();
                    try {
                        if (out.dims() != 4 || out.size(0) != 1 || out.size(1) != 3
                                || out.size(2) != TILE || out.size(3) != TILE || out.type() != CvType.CV_32F)
                            throw new IOException("Saída ONNX incompatível: esperada 1x3x128x128 float32");
                        if (out.get(new int[]{0, 0, 0, 0}, logits) != logits.length * Float.BYTES)
                            throw new IOException("Falha ao ler logits");
                        a.add(logits, x, y);
                    } finally { out.release(); }
                }
            } finally { blob.release(); }
            return new Result(a, sourceW, sourceH);
        } finally { gray.release(); resized.release(); }
    }

    private static void validateSize(int w, int h) {
        if (w < 2 || h < 2 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS)
            throw new IllegalArgumentException("Imagem deve ter lados entre 2 e 4096 e até 4194304 pixels");
    }

    static int[] processingSize(int w, int h) {
        validateSize(w, h);
        double scale = Math.min(1.0, 1024.0 / Math.max(w, h));
        int pw = (int) Math.rint(w * scale), ph = (int) Math.rint(h * scale);
        if (pw < 2 || ph < 2) throw new IllegalArgumentException("Grade reduzida precisa ter ambos os lados >= 2");
        return new int[]{pw, ph};
    }

    // INTER_NEAREST OpenCV: origem em zero e índice floor(dest * processamento / analisado).
    static BufferedImage nearest(BufferedImage source, int w, int h) {
        if (w == source.getWidth() && h == source.getHeight()) return source;
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] src = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
        int[] dst = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        int sw = source.getWidth(), sh = source.getHeight();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            dst[y * w + x] = src[(int) ((long) y * sh / h) * sw + (int) ((long) x * sw / w)];
        return result;
    }

    private static byte[] verifiedModel(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Selecione um arquivo ONNX local existente");
        byte[] data;
        try (InputStream in = Files.newInputStream(file.toPath())) { data = in.readNBytes(MAX_MODEL_BYTES + 1); }
        if (data.length > MAX_MODEL_BYTES) throw new IOException("Arquivo ONNX excede o limite de 10 MiB");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            if (!MessageDigest.isEqual(digest, HexFormat.of().parseHex(MODEL_SHA256)))
                throw new IOException("SHA-256 incompatível: modelo experimental não suportado ou alterado");
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 indisponível", e); }
        return data; // Os mesmos bytes verificados são usados pelo importador, sem reabrir o arquivo.
    }

    /** Equivalente a numpy.pad(mode='reflect'), mesmo se o halo exceder o tamanho do lado. */
    static int reflect(int p, int size) {
        int period = 2 * (size - 1);
        int v = Math.floorMod(p, period);
        return v < size ? v : period - v;
    }

    static void fillTile(byte[] gray, int w, int h, int x, int y, float[] tile) {
        for (int j = 0; j < TILE; j++) {
            int row = reflect(y + j - HALO, h) * w;
            for (int i = 0; i < TILE; i++)
                tile[j * TILE + i] = (gray[row + reflect(x + i - HALO, w)] & 255) / 255.0f;
        }
    }

    static final class Accumulator {
        final BufferedImage overlay, entropyOverlay;
        final int[] colors, entropyColors;
        final long[] counts = new long[3];
        double entropySum;
        Accumulator(int w, int h) {
            validateSize(w, h);
            overlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            entropyOverlay = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            colors = ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData();
            entropyColors = ((DataBufferInt) entropyOverlay.getRaster().getDataBuffer()).getData();
        }
        void add(float[] logits, int x, int y) throws IOException {
            if (logits.length != 3 * PLANE) throw new IOException("Número inválido de logits");
            int w = overlay.getWidth(), h = overlay.getHeight();
            for (int j = 0; j < Math.min(CORE, h - y); j++) for (int i = 0; i < Math.min(CORE, w - x); i++) {
                int k = (j + HALO) * TILE + i + HALO;
                int color = 0;
                double entropy = 0;
                for (int c = 0; c < 3; c++) {
                    double z = logits[c * PLANE + k];
                    if (!Double.isFinite(z)) throw new IOException("Logit não finito");
                    double p = 1.0 / (1.0 + Math.exp(-z));
                    if (p > THRESHOLD) { counts[c]++; color |= c == 0 ? 0x00ff00 : c == 1 ? 0xff0000 : 0x0000ff; }
                    if (p > 0 && p < 1) entropy -= (p * Math.log(p) + (1 - p) * Math.log1p(-p)) / (3 * Math.log(2));
                }
                int dest = (y + j) * w + x + i;
                colors[dest] = color == 0 ? 0 : 0xa0000000 | color;
                entropySum += entropy;
                int alpha = (int) Math.round(160 * Math.max(0, Math.min(1, entropy)));
                entropyColors[dest] = alpha == 0 ? 0 : (alpha << 24) | 0xff00ff;
            }
        }
    }
}
