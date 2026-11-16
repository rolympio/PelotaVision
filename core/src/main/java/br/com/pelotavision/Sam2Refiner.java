package br.com.pelotavision;

import org.json.JSONObject;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Refino das pelotas detectadas por máscaras SAM 2.1 Hiera Tiny (Meta, Apache-2.0), igual no Windows e no Android.
 *
 * <p>O detector escolhido (Bordas, LoG ou StarDist) continua localizando as pelotas. Cada detecção vira um
 * prompt (caixa + ponto central) para o SAM 2; a máscara devolvida substitui a elipse ajustada pela elipse de
 * mesmos momentos e mesma área da máscara. Assim o tamanho médio, D10/D50/D90, histogramas e Rosin–Rammler
 * passam a usar a área segmentada pelo SAM 2. Se a máscara falhar nos critérios de qualidade, a detecção
 * original é mantida e contada como "mantida".</p>
 *
 * <p>Paridade: o mesmo código, o mesmo ONNX e o mesmo motor (ONNX Runtime, CPU) rodam nas duas plataformas.
 * A GPU (CUDA no Windows) é opcional e só é usada com a aceleração por hardware ligada.</p>
 *
 * <p>Pipeline: a imagem é reescalada para que o raio mediano das detecções fique em {@link #TARGET_RADIUS} px,
 * dividida em blocos de 1024 px com sobreposição; cada bloco com detecções passa uma vez pelo encoder e as
 * detecções do bloco vão ao decoder em lotes de {@code batch}. A máscara (logits 256×256) é amostrada por
 * interpolação bilinear (mesma convenção do SAM 2) só na vizinhança de cada pelota.</p>
 */
public final class Sam2Refiner {
    public static final String ENCODER = "sam2_encoder.onnx", DECODER = "sam2_decoder.onnx", METADATA = "sam2.json";
    /** Raio mediano alvo das pelotas no quadro de 1024 px do SAM 2 (≈ 20 px de diâmetro na máscara de 256). */
    static final double TARGET_RADIUS = 40, MIN_SCALE = .2, MAX_SCALE = 2.5;
    static final long MAX_SCALED_PIXELS = 40_000_000L;
    /** Caixa do prompt e janela de análise da máscara, em raios da detecção. */
    static final double BOX = 1.15, WINDOW = 1.6;
    /** Critérios de aceitação da máscara. */
    static final double MIN_IOU = .5, MIN_AREA_RATIO = .35, MAX_AREA_RATIO = 2.8, REVIEW_IOU = .8;

    /** Motor de inferência com várias entradas e saídas (ONNX Runtime nas duas plataformas). */
    public interface Engine extends AutoCloseable {
        Map<String, float[]> run(Map<String, float[]> inputs, Map<String, long[]> shapes, String[] outputs) throws Exception;
        String description();
        @Override void close();
    }
    public interface EngineFactory { Engine open(File onnx) throws Exception; }

    /** Resumo da última execução (tela, relatório e JSON). */
    public static final class Report {
        public final int tiles, prompts, refined, kept, review; public final long encoderMs, decoderMs, totalMs; public final double scale;
        public final String engine;
        /** Pelotas cuja máscara foi recortada pela borda real (halo/sombra removidos). */
        public final int edgeSnapped;
        Report(int tiles, int prompts, int refined, int kept, int review, long enc, long dec, long total, double scale, String engine, int edgeSnapped) {
            this.edgeSnapped = edgeSnapped;
            this.tiles = tiles; this.prompts = prompts; this.refined = refined; this.kept = kept; this.review = review;
            encoderMs = enc; decoderMs = dec; totalMs = total; this.scale = scale; this.engine = engine;
        }
        public String summary() {
            return String.format(Locale.ROOT, "SAM 2: %d de %d pelotas com máscara (%d recortadas na borda real, %d mantidas do detector, %d para revisão) · %d blocos · escala %.2f · %.1f s · %s",
                    refined, prompts, edgeSnapped, kept, review, tiles, scale, totalMs / 1000.0, engine);
        }
        public JSONObject json() throws Exception {
            JSONObject j = new JSONObject();
            j.put("tiles", tiles); j.put("prompts", prompts); j.put("refined", refined); j.put("kept_from_detector", kept);
            j.put("review", review); j.put("edge_snapped", edgeSnapped); j.put("encoder_ms", encoderMs); j.put("decoder_ms", decoderMs); j.put("total_ms", totalMs);
            j.put("scale", scale); j.put("engine", engine);
            return j;
        }
    }

    private static Sam2Refiner installed;
    private static volatile boolean enabled;
    private static String status = "SAM 2 não instalado (sam2_encoder.onnx, sam2_decoder.onnx e sam2.json ausentes).";
    private static volatile Report last;

    public final String id; public final JSONObject metadata;
    final int size, batch, maskSize; final float[] mean, std;
    private final Engine encoder, decoder;

    Sam2Refiner(Engine encoder, Engine decoder, JSONObject metadata) throws Exception {
        this.encoder = encoder; this.decoder = decoder; this.metadata = metadata;
        id = metadata.getString("id");
        size = metadata.getInt("input_size"); batch = metadata.getInt("batch"); maskSize = metadata.getInt("mask_size");
        mean = floats(metadata.getJSONArray("mean")); std = floats(metadata.getJSONArray("std"));
        if (size != 1024 || maskSize != 256 || batch < 1 || mean.length != 3 || std.length != 3) throw new IllegalArgumentException("Metadado SAM 2 inválido.");
    }

    // ------------------------------------------------------------------ instalação e estado

    /** Instala o SAM 2 conferindo o SHA-256 dos dois ONNX. Falha não é fatal: o refino fica indisponível. */
    public static synchronized boolean install(File encoderOnnx, File decoderOnnx, String metadataJson, EngineFactory factory) {
        close();
        Engine e = null, d = null;
        try {
            if (!encoderOnnx.isFile() || !decoderOnnx.isFile()) throw new IllegalArgumentException("modelo não encontrado em " + encoderOnnx.getParent());
            JSONObject meta = new JSONObject(metadataJson);
            if (meta.optBoolean("random_weights", false)) throw new IllegalArgumentException("modelo com pesos aleatórios (só para teste) recusado");
            check(encoderOnnx, meta.getString("encoder_sha256"));
            check(decoderOnnx, meta.getString("decoder_sha256"));
            e = factory.open(encoderOnnx); d = factory.open(decoderOnnx);
            installed = new Sam2Refiner(e, d, meta);
            status = "SAM 2 " + installed.id + " · " + e.description();
            return true;
        } catch (Throwable t) {
            if (e != null) e.close();
            if (d != null) d.close();
            status = "SAM 2 indisponível: " + HardwareAcceleration.message(t);
            return false;
        }
    }
    /** Instância de teste com motores e metadado prontos (sem conferir SHA-256). */
    static Sam2Refiner forTest(Engine encoder, Engine decoder, JSONObject metadata) throws Exception { return new Sam2Refiner(encoder, decoder, metadata); }

    public static synchronized void close() {
        if (installed != null) { installed.encoder.close(); installed.decoder.close(); installed = null; }
    }
    public static synchronized boolean available() { return installed != null; }
    public static synchronized Sam2Refiner get() { return installed; }
    public static synchronized String status() { return status + (installed != null ? (enabled ? " · LIGADO" : " · desligado") : ""); }
    public static boolean enabled() { return enabled; }
    public static void setEnabled(boolean on) { enabled = on; }
    public static Report lastReport() { return last; }

    /** Refina com o SAM 2 se estiver instalado e ligado; senão devolve o resultado original. Erros não interrompem a análise. */
    public static PelletDetector.Result applyIfEnabled(Mat gray, PelletDetector.Result seeds, BooleanSupplier cancelled) {
        Sam2Refiner s;
        synchronized (Sam2Refiner.class) { s = enabled ? installed : null; }
        if (s == null || seeds == null || seeds.circles.isEmpty()) return seeds;
        try { return s.refine(gray, seeds, cancelled); }
        catch (RuntimeException | OutOfMemoryError e) {
            synchronized (Sam2Refiner.class) { status = "SAM 2 falhou nesta análise (resultado do detector mantido): " + HardwareAcceleration.message(e); }
            last = null;
            return seeds;
        }
    }

    // ------------------------------------------------------------------ refino

    /** Escala que leva o raio mediano ao alvo, limitada por memória. */
    static double chooseScale(double medianRadius, int w, int h) {
        double s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, TARGET_RADIUS / Math.max(1e-6, medianRadius)));
        double pixels = (double) w * h * s * s;
        if (pixels > MAX_SCALED_PIXELS) s = Math.sqrt(MAX_SCALED_PIXELS / ((double) w * h));
        return s;
    }

    /** Início dos blocos de lado {@code tile} cobrindo {@code length}, com passo {@code tile - overlap}. */
    static int[] starts(int length, int tile, int overlap) {
        if (length <= tile) return new int[]{0};
        List<Integer> s = new ArrayList<>();
        int stride = tile - overlap;
        for (int x = 0; x < length - tile; x += stride) s.add(x);
        if (s.get(s.size() - 1) != length - tile) s.add(length - tile);
        int[] out = new int[s.size()];
        for (int i = 0; i < out.length; i++) out[i] = s.get(i);
        return out;
    }
    /** Índice do bloco "dono" de uma coordenada (fronteira no meio da sobreposição). */
    static int owner(int[] starts, int tile, double v) {
        for (int i = 0; i < starts.length - 1; i++) if (v < (starts[i] + tile + starts[i + 1]) / 2.0) return i;
        return starts.length - 1;
    }

    public synchronized PelletDetector.Result refine(Mat gray, PelletDetector.Result seeds, BooleanSupplier cancelled) {
        long t0 = System.nanoTime();
        int w = gray.cols(), h = gray.rows();
        List<PelletDetector.Detection> in = seeds.circles;
        double[] radii = new double[in.size()];
        for (int i = 0; i < radii.length; i++) radii[i] = in.get(i).radius;
        double s = chooseScale(NeuralPelletDetector.median(radii), w, h);
        int sw = (int) Math.max(1, Math.round(w * s)), sh = (int) Math.max(1, Math.round(h * s));
        double sx = sw / (double) w, sy = sh / (double) h, sm = Math.sqrt(sx * sy);
        double[] sorted = radii.clone(); Arrays.sort(sorted);
        double r95 = sorted[(int) Math.min(sorted.length - 1, Math.floor(.95 * sorted.length))] * sm;
        int overlap = (int) Math.max(128, Math.min(512, Math.ceil(2 * WINDOW * r95 + 16)));
        Mat scaled = new Mat(), one = gray;
        byte[] pix = new byte[sw * sh];
        try {
            if (gray.channels() != 1) { one = new Mat(); Imgproc.cvtColor(gray, one, gray.channels() == 4 ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY); }
            if (one.depth() != org.opencv.core.CvType.CV_8U) throw new IllegalArgumentException("Imagem de 8 bits necessária para o SAM 2");
            Imgproc.resize(one, scaled, new Size(sw, sh), 0, 0, s < 1 ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
            scaled.get(0, 0, pix);
        } finally {
            scaled.release();
            if (one != gray) one.release();
        }
        return refinePixels(pix, sw, sh, sx, sy, s, overlap, seeds, cancelled, t0);
    }

    /** Parte sem OpenCV (testável em Java puro): blocos, encoder, decoder e medição das máscaras. */
    PelletDetector.Result refinePixels(byte[] pix, int sw, int sh, double sx, double sy, double s, int overlap,
                                       PelletDetector.Result seeds, BooleanSupplier cancelled, long t0) {
        List<PelletDetector.Detection> in = seeds.circles;
        PelletDetector.Detection[] out = in.toArray(new PelletDetector.Detection[0]);
        int refined = 0, kept = 0, review = 0, tiles = 0; long encNs = 0, decNs = 0;
        snapped = 0;
        double sm = Math.sqrt(sx * sy);
        {
            int pad = meanByte(pix);
            int[] xs = starts(sw, size, overlap), ys = starts(sh, size, overlap);
            List<List<Integer>> owned = new ArrayList<>();
            for (int i = 0; i < xs.length * ys.length; i++) owned.add(new ArrayList<>());
            for (int i = 0; i < out.length; i++) {
                double cx = (out[i].x + .5) * sx - .5, cy = (out[i].y + .5) * sy - .5;
                owned.get(owner(ys, size, cy) * xs.length + owner(xs, size, cx)).add(i);
            }
            float[] image = new float[3 * size * size];
            for (int ty = 0; ty < ys.length; ty++) for (int tx = 0; tx < xs.length; tx++) {
                List<Integer> ids = owned.get(ty * xs.length + tx);
                if (ids.isEmpty()) continue;
                if (cancelled != null && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Análise cancelada.");
                int x0 = xs[tx], y0 = ys[ty];
                fillInput(image, pix, sw, sh, x0, y0, pad);
                Map<String, float[]> ein = new HashMap<>(); Map<String, long[]> eshape = new HashMap<>();
                ein.put("image", image); eshape.put("image", new long[]{1, 3, size, size});
                long a = System.nanoTime();
                Map<String, float[]> emb = run(encoder, ein, eshape, new String[]{"image_embed", "high_res_feats_0", "high_res_feats_1"});
                encNs += System.nanoTime() - a; tiles++;
                for (int b0 = 0; b0 < ids.size(); b0 += batch) {
                    int n = Math.min(batch, ids.size() - b0);
                    float[] coords = new float[batch * 3 * 2], labels = new float[batch * 3];
                    for (int k = 0; k < batch; k++) {
                        PelletDetector.Detection d = out[ids.get(b0 + Math.min(k, n - 1))];
                        double cx = (d.x + .5) * sx - .5 - x0, cy = (d.y + .5) * sy - .5 - y0, r = Math.max(d.radius, d.semiMajor) * sm * BOX;
                        // Prompt em px do bloco 1024 (mesma convenção do SAM2ImagePredictor: coordenada do pixel, sem +0,5).
                        coords[k * 6] = (float) (cx - r); coords[k * 6 + 1] = (float) (cy - r);
                        coords[k * 6 + 2] = (float) (cx + r); coords[k * 6 + 3] = (float) (cy + r);
                        coords[k * 6 + 4] = (float) cx; coords[k * 6 + 5] = (float) cy;
                        labels[k * 3] = 2; labels[k * 3 + 1] = 3; labels[k * 3 + 2] = 1;
                    }
                    Map<String, float[]> din = new HashMap<>(); Map<String, long[]> dshape = new HashMap<>();
                    din.put("image_embed", emb.get("image_embed")); dshape.put("image_embed", new long[]{1, 256, 64, 64});
                    din.put("high_res_feats_0", emb.get("high_res_feats_0")); dshape.put("high_res_feats_0", new long[]{1, 32, 256, 256});
                    din.put("high_res_feats_1", emb.get("high_res_feats_1")); dshape.put("high_res_feats_1", new long[]{1, 64, 128, 128});
                    din.put("point_coords", coords); dshape.put("point_coords", new long[]{batch, 3, 2});
                    din.put("point_labels", labels); dshape.put("point_labels", new long[]{batch, 3});
                    a = System.nanoTime();
                    Map<String, float[]> dec = run(decoder, din, dshape, new String[]{"low_res_masks", "iou_predictions"});
                    decNs += System.nanoTime() - a;
                    float[] logits = dec.get("low_res_masks"), iou = dec.get("iou_predictions");
                    for (int k = 0; k < n; k++) {
                        int idx = ids.get(b0 + k);
                        PelletDetector.Detection r = measure(out[idx], logits, k * maskSize * maskSize, iou[k], x0, y0, sw, sh, sx, sy, pix);
                        if (r == null) { kept++; if (out[idx].needsReview) review++; continue; }
                        out[idx] = r; refined++; if (r.needsReview) review++;
                    }
                }
            }
        }
        long total = (System.nanoTime() - t0) / 1_000_000;
        last = new Report(tiles, in.size(), refined, kept, review, encNs / 1_000_000, decNs / 1_000_000, total, s, encoder.description(), snapped);
        return new PelletDetector.Result(Arrays.asList(out), seeds.width, seeds.height, seeds.candidates, seeds.durationMs + total,
                seeds.candidateLimit, seeds.method + "+sam2_" + id + (edgeSnap ? "+edge_v1" : ""));
    }

    private static Map<String, float[]> run(Engine e, Map<String, float[]> in, Map<String, long[]> shapes, String[] outs) {
        try { return e.run(in, shapes, outs); }
        catch (RuntimeException x) { throw x; }
        catch (Exception x) { throw new IllegalStateException("Falha no SAM 2: " + x.getMessage(), x); }
    }

    /** Bloco 1024×1024 em NCHW, RGB repetido do cinza, normalizado (ImageNet); fora da imagem: cinza médio. */
    void fillInput(float[] image, byte[] pix, int sw, int sh, int x0, int y0, int pad) {
        int plane = size * size;
        float[] lut = new float[256 * 3];
        for (int v = 0; v < 256; v++) for (int c = 0; c < 3; c++) lut[v * 3 + c] = (v / 255f - mean[c]) / std[c];
        for (int y = 0; y < size; y++) {
            int yy = y0 + y;
            for (int x = 0; x < size; x++) {
                int xx = x0 + x;
                int v = yy < sh && xx < sw ? pix[yy * sw + xx] & 255 : pad;
                int o = y * size + x;
                image[o] = lut[v * 3]; image[plane + o] = lut[v * 3 + 1]; image[2 * plane + o] = lut[v * 3 + 2];
            }
        }
    }

    static int meanByte(byte[] pix) { long s = 0; for (byte b : pix) s += b & 255; return pix.length == 0 ? 128 : (int) (s / pix.length); }

    /** Logit bilinear na posição (u, v) do quadro 1024 (interpolação align_corners=False do SAM 2, borda replicada). */
    static float logitAt(float[] m, int off, int ms, double u, double v, double ratio) {
        double gx = (u + .5) / ratio - .5, gy = (v + .5) / ratio - .5;
        gx = Math.max(0, Math.min(ms - 1, gx)); gy = Math.max(0, Math.min(ms - 1, gy));
        int ix = (int) Math.floor(gx), iy = (int) Math.floor(gy), jx = Math.min(ms - 1, ix + 1), jy = Math.min(ms - 1, iy + 1);
        double fx = gx - ix, fy = gy - iy;
        double a = m[off + iy * ms + ix], b = m[off + iy * ms + jx], c = m[off + jy * ms + ix], d = m[off + jy * ms + jx];
        return (float) ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy);
    }

    /**
     * Mede a máscara de uma pelota na janela em volta da detecção (px da imagem reescalada), mantém a componente
     * conexa que contém o centro (ou a mais próxima dele) e devolve a elipse de mesmos momentos e mesma área,
     * em px da imagem original. Null quando a máscara não passa nos critérios.
     */
    PelletDetector.Detection measure(PelletDetector.Detection seed, float[] logits, int off, float iou, int x0, int y0, int sw, int sh, double sx, double sy, byte[] pix) {
        if (!(iou >= MIN_IOU)) return null;
        double sm = Math.sqrt(sx * sy), ratio = size / (double) maskSize;
        double cx = (seed.x + .5) * sx - .5, cy = (seed.y + .5) * sy - .5, rw = Math.max(seed.radius, seed.semiMajor) * sm * WINDOW;
        int ax = (int) Math.floor(cx - rw), ay = (int) Math.floor(cy - rw), bx = (int) Math.ceil(cx + rw), by = (int) Math.ceil(cy + rw);
        int gw = bx - ax + 1, gh = by - ay + 1;
        if (gw < 3 || gh < 3 || (long) gw * gh > 4_000_000L) return null;
        boolean[] on = new boolean[gw * gh];
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            int px = ax + x, py = ay + y;
            if (px < 0 || py < 0 || px >= sw || py >= sh) continue;      // fora da foto
            int u = px - x0, v = py - y0;
            if (u < 0 || v < 0 || u >= size || v >= size) continue;      // fora do bloco
            on[y * gw + x] = logitAt(logits, off, maskSize, u, v, ratio) > 0;
        }
        // componente que contém o centro; senão a do pixel ativo mais próximo
        int seedIdx = -1; double best = Double.MAX_VALUE;
        int icx = (int) Math.round(cx) - ax, icy = (int) Math.round(cy) - ay;
        if (icx >= 0 && icy >= 0 && icx < gw && icy < gh && on[icy * gw + icx]) seedIdx = icy * gw + icx;
        else for (int i = 0; i < on.length; i++) if (on[i]) {
            double d = Math.hypot(i % gw - icx, i / gw - icy);
            if (d < best) { best = d; seedIdx = i; }
        }
        if (seedIdx < 0 || best > seed.radius * sm * .5 && best != Double.MAX_VALUE) return null;
        int[] queue = new int[on.length]; boolean[] seen = new boolean[on.length];
        int head = 0, tail = 0; queue[tail++] = seedIdx; seen[seedIdx] = true;
        long n = 0; double mx = 0, my = 0, mxx = 0, myy = 0, mxy = 0; boolean touchesWindow = false, touchesPhoto = false;
        while (head < tail) {
            int i = queue[head++]; int x = i % gw, y = i / gw;
            double px = ax + x, py = ay + y;
            n++; mx += px; my += py; mxx += px * px; myy += py * py; mxy += px * py;
            if (x == 0 || y == 0 || x == gw - 1 || y == gh - 1) touchesWindow = true;
            if (px <= 0 || py <= 0 || px >= sw - 1 || py >= sh - 1) touchesPhoto = true;
            int[] nb = {x > 0 ? i - 1 : -1, x < gw - 1 ? i + 1 : -1, y > 0 ? i - gw : -1, y < gh - 1 ? i + gw : -1};
            for (int j : nb) if (j >= 0 && on[j] && !seen[j]) { seen[j] = true; queue[tail++] = j; }
        }
        if (touchesWindow || n < 12) return null;  // vazou para fora da pelota ou ficou pequena demais
        double seedArea = Math.PI * seed.radius * seed.radius * sx * sy, ratioArea = n / seedArea;
        if (ratioArea < MIN_AREA_RATIO || ratioArea > MAX_AREA_RATIO) return null;
        mx /= n; my /= n;
        double cxx = mxx / n - mx * mx + 1 / 12.0, cyy = myy / n - my * my + 1 / 12.0, cxy = mxy / n - mx * my;
        double tr = cxx + cyy, det = cxx * cyy - cxy * cxy, disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
        double l1 = tr / 2 + disc, l2 = Math.max(1e-9, tr / 2 - disc);
        double a = 2 * Math.sqrt(l1), b = 2 * Math.sqrt(l2), k = Math.sqrt(n / (Math.PI * a * b));
        a *= k; b *= k;                                   // mesma área da máscara
        // 0.17.0: a máscara do SAM 2 tende a incluir o halo do processamento do celular e a sombra de contato
        // (+3,8% no diâmetro das esferas de 11 mm). Recorta pela borda real: maior degrau de brilho em cada raio.
        double[] edge = pix == null ? null : snapEdge(pix, sw, sh, mx, my, Math.sqrt(n / Math.PI));
        if (edge != null) {
            double f = Math.sqrt(edge[0] / n);
            a *= f; b *= f; mx = edge[1]; my = edge[2];
            snapped++;
        }
        double angle = Math.toDegrees(.5 * Math.atan2(2 * cxy, cxx - cyy));
        double ox = (mx + .5) / sx - .5, oy = (my + .5) / sy - .5;
        // anisotropia do reescalonamento (< 0,1%) tratada pela média geométrica
        boolean partial = seed.partial || touchesPhoto;
        boolean flag = seed.needsReview || iou < REVIEW_IOU;
        try {
            return new PelletDetector.Detection(seed, ox, oy, Math.max(a, b) / sm, Math.min(a, b) / sm, angle, true, iou, Double.NaN, partial, flag);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Recorte da máscara pela borda de maior gradiente (ligado por padrão). */
    static volatile boolean edgeSnap = true;
    static final int EDGE_RAYS = 72;
    static final double EDGE_IN = .80, EDGE_OUT = 1.15, EDGE_MIN_GRADIENT = 3, EDGE_MIN_VALID = .6, EDGE_MIN_AREA = .78, EDGE_MAX_AREA = 1.06;
    private int snapped;

    static double bilinear(byte[] pix, int w, int h, double x, double y) {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) return Double.NaN;
        int ix = (int) x, iy = (int) y, jx = Math.min(w - 1, ix + 1), jy = Math.min(h - 1, iy + 1);
        double fx = x - ix, fy = y - iy;
        double p00 = pix[iy * w + ix] & 255, p10 = pix[iy * w + jx] & 255, p01 = pix[jy * w + ix] & 255, p11 = pix[jy * w + jx] & 255;
        return (p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy;
    }

    /**
     * Borda da pelota por raios a partir do centróide da máscara: em cada um de {@link #EDGE_RAYS} raios, entre
     * 0,80 e 1,15 do raio equivalente da máscara, acha o maior degrau de brilho (com a polaridade dominante:
     * pelota clara em fundo escuro ou o contrário) com refinamento sub-pixel. Mediana móvel de 5 raios.
     * Devolve {área, cx, cy} do polígono ou null se o contraste for fraco, a borda incerta ou o recorte grande
     * demais (fica a máscara do SAM 2). Coordenadas da imagem reescalada.
     */
    static double[] snapEdge(byte[] pix, int w, int h, double cx, double cy, double req) {
        if (!edgeSnap || req < 4) return null;
        double step = .25;
        int ns = (int) Math.ceil((EDGE_OUT - EDGE_IN) * req / step) + 1;
        double[][] g = new double[EDGE_RAYS][ns];
        double polarity = 0;
        for (int t = 0; t < EDGE_RAYS; t++) {
            double th = 2 * Math.PI * t / EDGE_RAYS, c = Math.cos(th), sn = Math.sin(th);
            double[] prof = new double[ns + 2];
            for (int i = 0; i < ns + 2; i++) {
                double r = EDGE_IN * req + (i - 1) * step;
                prof[i] = bilinear(pix, w, h, cx + r * c, cy + r * sn);
            }
            for (int i = 0; i < ns; i++) {
                double d = (prof[i + 2] - prof[i]) / (2 * step);          // dI/dr (por px)
                g[t][i] = Double.isNaN(d) ? 0 : d;
                polarity += g[t][i];
            }
        }
        double sign = polarity < 0 ? -1 : 1;                             // pelota clara: brilho cai para fora (sign=-1, procura -dI/dr)
        double[] radii = new double[EDGE_RAYS];
        int valid = 0;
        for (int t = 0; t < EDGE_RAYS; t++) {
            int best = -1; double bv = 0;
            for (int i = 1; i < ns - 1; i++) {
                double v = sign * g[t][i];
                if (v > bv) { bv = v; best = i; }
            }
            if (best < 0 || bv < EDGE_MIN_GRADIENT) { radii[t] = Double.NaN; continue; }
            double ym = sign * g[t][best - 1], y0 = bv, yp = sign * g[t][best + 1];
            double den = ym - 2 * y0 + yp, d = den < 0 ? .5 * (ym - yp) / den : 0;
            radii[t] = EDGE_IN * req + (best + Math.max(-.5, Math.min(.5, d))) * step;
            valid++;
        }
        if (valid < EDGE_MIN_VALID * EDGE_RAYS) return null;
        double[] filled = new double[EDGE_RAYS];
        for (int t = 0; t < EDGE_RAYS; t++) {                            // mediana móvel de 5 (ignora raios sem borda)
            double[] win = new double[5]; int m = 0;
            for (int j = -2; j <= 2; j++) { double v = radii[(t + j + EDGE_RAYS) % EDGE_RAYS]; if (!Double.isNaN(v)) win[m++] = v; }
            if (m == 0) return null;
            Arrays.sort(win, 0, m);
            filled[t] = win[m / 2];
        }
        double area = 0, ax = 0, ay = 0;
        for (int t = 0; t < EDGE_RAYS; t++) {
            double t0 = 2 * Math.PI * t / EDGE_RAYS, t1 = 2 * Math.PI * (t + 1) / EDGE_RAYS;
            double x0 = filled[t] * Math.cos(t0), y0 = filled[t] * Math.sin(t0);
            double x1 = filled[(t + 1) % EDGE_RAYS] * Math.cos(t1), y1 = filled[(t + 1) % EDGE_RAYS] * Math.sin(t1);
            double cr = x0 * y1 - x1 * y0;
            area += cr; ax += (x0 + x1) * cr; ay += (y0 + y1) * cr;
        }
        area /= 2;
        if (!(area > 0)) return null;
        double ratio = area / (Math.PI * req * req);
        if (ratio < EDGE_MIN_AREA || ratio > EDGE_MAX_AREA) return null;
        return new double[]{area, cx + ax / (6 * area), cy + ay / (6 * area)};
    }

    // ------------------------------------------------------------------ utilidades

    static void check(File f, String expected) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] buf = new byte[1 << 16]; int r; while ((r = in.read(buf)) > 0) md.update(buf, 0, r); }
        StringBuilder s = new StringBuilder(); for (byte b : md.digest()) s.append(String.format("%02x", b & 255));
        if (!s.toString().equals(expected)) throw new IllegalArgumentException("SHA-256 de " + f.getName() + " não confere com sam2.json");
    }
    private static float[] floats(org.json.JSONArray v) throws Exception { float[] f = new float[v.length()]; for (int i = 0; i < f.length; i++) f[i] = (float) v.getDouble(i); return f; }
}
