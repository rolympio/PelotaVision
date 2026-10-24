package br.com.pelotavision;

import org.json.JSONObject;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Localização por rede neural StarDist (polígonos estrelados), via OpenCV DNN (ONNX).
 * Espelho exato de ml_training/stardist/common.py: escala, normalização por percentis,
 * blocos fixos, máximos locais, NMS de polígonos convexos e descrição dos objetos.
 * A rede só PROPÕE objetos; a elipse final continua sendo medida nas bordas observadas
 * (EllipseFitter), como nos outros dois métodos. O modelo distribuído é de demonstração.
 */
public final class NeuralPelletDetector {
    public static final String MODEL = "pellet_stardist.onnx", METADATA = "pellet_stardist.json";
    /** Raios esperados (fração da largura) por perfil: pequenas, médias, grandes, auto (3 escalas). */
    static final double[][] PROFILE_RADII = {{.022}, {.035}, {.065}, {.02, .042, .09}};
    /** Correia: raios típicos medidos nas fotos reais de correia (0,4%–1,6% da largura). */
    static final double[][] BELT_RADII = {{.006}, {.009}, {.013}, {.005, .008, .013}};
    static final int MAX_CANDIDATES = 20000, MAX_OBJECTS = 600;

    private static NeuralPelletDetector installed;
    private static String status = "Modelo neural não instalado.";

    public final String id, sha256;
    public final boolean demonstration;
    public final JSONObject metadata;
    final int rays, tile, overlap;
    final double nominalRadius, normLow, normHigh, nmsOverlap, minRadiusNet;
    private final double[] cos, sin;
    private final Net net;

    private NeuralPelletDetector(File onnx, String metadataJson) throws Exception {
        metadata = new JSONObject(metadataJson);
        byte[] bytes = Files.readAllBytes(onnx.toPath());
        sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!sha256.equals(metadata.getString("sha256")))
            throw new IllegalArgumentException("SHA-256 do modelo não confere com o metadado.");
        id = metadata.getString("id");
        demonstration = metadata.optBoolean("demonstration", true);
        rays = metadata.getInt("rays"); tile = metadata.getInt("tile"); overlap = metadata.getInt("overlap");
        nominalRadius = metadata.getDouble("nominal_radius_px");
        normLow = metadata.getDouble("norm_low"); normHigh = metadata.getDouble("norm_high");
        nmsOverlap = metadata.getDouble("nms_overlap"); minRadiusNet = metadata.getDouble("min_radius_net_px");
        if (rays < 8 || tile < 64 || overlap < 0 || overlap >= tile) throw new IllegalArgumentException("Metadado inválido.");
        cos = new double[rays]; sin = new double[rays];
        for (int k = 0; k < rays; k++) { double a = 2 * Math.PI * k / rays; cos[k] = Math.cos(a); sin[k] = Math.sin(a); }
        net = Dnn.readNetFromONNX(onnx.getAbsolutePath());
        if (net.empty()) throw new IllegalArgumentException("ONNX não carregado.");
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
    }

    /** Instala o modelo para toda a aplicação. Falha não é fatal: o método neural fica indisponível. */
    public static synchronized boolean install(File onnx, String metadataJson) {
        try {
            installed = new NeuralPelletDetector(onnx, metadataJson);
            status = "Rede neural " + installed.id + (installed.demonstration ? " (DEMONSTRAÇÃO, treino em 7 cenas provisórias)" : "");
            return true;
        } catch (Throwable e) {
            installed = null;
            status = "Rede neural indisponível: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }
    /** Instância avulsa (ex.: modelo de validação), sem instalar na aplicação. */
    static NeuralPelletDetector load(File onnx, String metadataJson) throws Exception { return new NeuralPelletDetector(onnx, metadataJson); }
    public static synchronized NeuralPelletDetector get() { return installed; }
    public static synchronized boolean available() { return installed != null; }
    public static synchronized String status() { return status; }

    public static double threshold(int sensitivity) { return Math.max(.2, Math.min(.8, .75 - .005 * sensitivity)); }

    static final class Candidate {
        final float score; final float[] px, py; final double scale;
        Candidate(float score, float[] px, float[] py, double scale) { this.score = score; this.px = px; this.py = py; this.scale = scale; }
    }
    static final class Kept {
        final Candidate c; final double[][] hull; final double area, cx, cy, perimeter, minX, minY, maxX, maxY;
        Kept(Candidate c, double[][] hull, double[] props, double[] box) {
            this.c = c; this.hull = hull; area = props[0]; cx = props[1]; cy = props[2]; perimeter = props[3];
            minX = box[0]; minY = box[1]; maxX = box[2]; maxY = box[3];
        }
    }

    /** Diagnóstico da última detecção: escalas testadas e escala final (null se nada foi encontrado). */
    public static final class ScaleReport {
        public final double[] scales, medianNetRadius; public final int[] kept; public final Double finalScale;
        ScaleReport(double[] s, double[] r, int[] k, Double f) { scales = s; medianNetRadius = r; kept = k; finalScale = f; }
    }
    private ScaleReport lastScales;
    public synchronized ScaleReport lastScales() { return lastScales; }

    static final double REFINE_MIN = .5, REFINE_MAX = 2;

    /**
     * Escala em duas etapas (igual a common.detect): uma passada por raio esperado do perfil; escolhe a
     * escala cujo raio mediano na entrada da rede fica mais perto do nominal (em log); passada final na
     * escala que leva esse raio ao nominal (fator 0,5–2). Escalas não são misturadas, porque em escala
     * grande demais a rede fragmenta pelotas.
     */
    public synchronized PelletDetector.Result detect(Mat gray, int profile, int sensitivity, PelletDetector.Parameters cfg) {
        if (gray.empty() || gray.type() != CvType.CV_8UC1) throw new IllegalArgumentException("Imagem cinza de 8 bits necessária");
        if (profile < 0 || profile > 3) throw new IllegalArgumentException("Perfil inválido");
        long start = System.nanoTime();
        int w = gray.cols(), h = gray.rows();
        double thr = threshold(sensitivity);
        double[] fr = (cfg.belt ? BELT_RADII : PROFILE_RADII)[profile], scales = new double[fr.length], medians = new double[fr.length];
        int[] counts = new int[fr.length];
        int total = 0, chosen = -1;
        boolean[] limit = {false};
        for (int i = 0; i < fr.length; i++) {
            scales[i] = nominalRadius / (fr[i] * w);
            List<Candidate> c = new ArrayList<>();
            candidatesForScale(gray, scales[i], thr, c);
            total += c.size();
            boolean[] ignored = {false};
            List<Kept> kept = nms(c, ignored, cfg.maxObjects, cfg.belt ? 4 * MAX_CANDIDATES : MAX_CANDIDATES);
            counts[i] = kept.size();
            double[] r = new double[kept.size()];
            for (int k = 0; k < r.length; k++) r[k] = Math.sqrt(kept.get(k).area / Math.PI) * scales[i];
            medians[i] = median(r);
            if (counts[i] > 0 && (chosen < 0 || Math.abs(Math.log(medians[i] / nominalRadius)) < Math.abs(Math.log(medians[chosen] / nominalRadius)))) chosen = i;
        }
        List<PelletDetector.Detection> out = new ArrayList<>();
        Double finalScale = null;
        if (chosen >= 0) {
            double factor = Math.min(REFINE_MAX, Math.max(REFINE_MIN, nominalRadius / medians[chosen]));
            finalScale = scales[chosen] * factor;
            List<Candidate> c = new ArrayList<>();
            candidatesForScale(gray, finalScale, thr, c);
            total += c.size();
            for (Kept k : nms(c, limit, cfg.maxObjects, cfg.belt ? 4 * MAX_CANDIDATES : MAX_CANDIDATES)) {
                double radius = Math.sqrt(k.area / Math.PI);
                double roundness = Math.min(1., 4 * Math.PI * k.area / Math.max(1e-9, k.perimeter * k.perimeter));
                boolean partial = k.minX < 0 || k.minY < 0 || k.maxX > w - 1 || k.maxY > h - 1;
                boolean review = radius < w * cfg.minRadiusFraction || radius > w * cfg.maxRadiusFraction || roundness < .62 || k.c.score < thr + .1;
                out.add(new PelletDetector.Detection(k.cx, k.cy, radius, k.area, roundness, partial, review));
            }
        }
        lastScales = new ScaleReport(scales, medians, counts, finalScale);
        out.sort(Comparator.comparingDouble((PelletDetector.Detection d) -> d.y).thenComparingDouble(d -> d.x));
        return new PelletDetector.Result(out, w, h, total, (System.nanoTime() - start) / 1_000_000, limit[0], "stardist_" + id);
    }

    /** Mediana explícita (média dos dois centrais em n par), igual ao Python; NaN se vazio. */
    static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] s = v.clone(); java.util.Arrays.sort(s); int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    // ------------------------------------------------------------ escala, blocos e candidatos

    static int[] normStats(byte[] pixels, double low, double high) {
        long[] cdf = new long[256];
        for (byte b : pixels) cdf[b & 255]++;
        for (int i = 1; i < 256; i++) cdf[i] += cdf[i - 1];
        long n = cdf[255];
        int lo = first(cdf, n * low), hi = first(cdf, n * high), med = first(cdf, n * .5);
        if (hi - lo < 8) hi = lo + 8;
        return new int[]{lo, hi, med};
    }
    private static int first(long[] cdf, double value) { for (int i = 0; i < 256; i++) if (cdf[i] >= value) return i; return 256; }

    List<Integer> starts(int length) {
        List<Integer> s = new ArrayList<>();
        if (length <= tile) { s.add(0); return s; }
        int stride = tile - overlap;
        for (int x = 0; x < length - tile; x += stride) s.add(x);
        if (s.get(s.size() - 1) != length - tile) s.add(length - tile);
        return s;
    }
    double[][] ownBounds(List<Integer> starts, int length) {
        double[][] b = new double[starts.size()][2];
        for (int i = 0; i < starts.size(); i++) {
            b[i][0] = i == 0 ? 0 : (starts.get(i - 1) + tile + starts.get(i)) / 2.;
            b[i][1] = i == starts.size() - 1 ? length : (starts.get(i) + tile + starts.get(i + 1)) / 2.;
        }
        return b;
    }

    void candidatesForScale(Mat gray, double scale, double thr, List<Candidate> out) {
        int w2 = (int) Math.max(1, Math.rint(gray.cols() * scale)), h2 = (int) Math.max(1, Math.rint(gray.rows() * scale));
        Mat small = new Mat(), blob = null, pred = null;
        try {
            Imgproc.resize(gray, small, new Size(w2, h2), 0, 0, scale < 1 ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
            byte[] pixels = new byte[w2 * h2];
            small.get(0, 0, pixels);
            int[] st = normStats(pixels, normLow, normHigh);
            float span = (float) (st[1] - st[0]);
            float fill = (float) ((st[2] - st[0]) / (double) (st[1] - st[0]));
            List<Integer> xs = starts(w2), ys = starts(h2);
            double[][] bx = ownBounds(xs, w2), by = ownBounds(ys, h2);
            int area = tile * tile;
            float[] input = new float[area], output = new float[(1 + rays) * area];
            blob = new Mat(new int[]{1, 1, tile, tile}, CvType.CV_32F);
            for (int ty = 0; ty < ys.size(); ty++) for (int tx = 0; tx < xs.size(); tx++) {
                int x0 = xs.get(tx), y0 = ys.get(ty), th = Math.min(tile, h2 - y0), tw = Math.min(tile, w2 - x0);
                java.util.Arrays.fill(input, fill);
                for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++)
                    input[y * tile + x] = (float) ((pixels[(y0 + y) * w2 + x0 + x] & 255) - st[0]) / span;
                blob.put(new int[]{0, 0, 0, 0}, input);
                net.setInput(blob);
                if (pred != null) pred.release();
                pred = net.forward();
                if (pred.dims() != 4 || pred.size(1) != 1 + rays || pred.size(2) != tile || pred.size(3) != tile)
                    throw new IllegalStateException("Saída da rede inesperada.");
                pred.get(new int[]{0, 0, 0, 0}, output);
                for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) {
                    float p = output[y * tile + x];
                    if (!(p > thr)) continue;
                    boolean peak = true;
                    for (int dy = -1; dy <= 1 && peak; dy++) for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int yy = y + dy, xx = x + dx;
                        if (yy < 0 || xx < 0 || yy >= tile || xx >= tile) continue;
                        if (output[yy * tile + xx] > p) { peak = false; break; }
                    }
                    if (!peak) continue;
                    int gx = x + x0, gy = y + y0;
                    if (gx < bx[tx][0] || gx >= bx[tx][1] || gy < by[ty][0] || gy >= by[ty][1]) continue;
                    float[] px = new float[rays], py = new float[rays];
                    for (int k = 0; k < rays; k++) {
                        double d = Math.max(0f, output[(1 + k) * area + y * tile + x]);
                        px[k] = (float) (((gx + d * cos[k]) + .5) / scale - .5);
                        py[k] = (float) (((gy + d * sin[k]) + .5) / scale - .5);
                    }
                    out.add(new Candidate(p, px, py, scale));
                }
            }
        } finally {
            small.release();
            if (blob != null) blob.release();
            if (pred != null) pred.release();
        }
    }

    // ------------------------------------------------------------ geometria (idêntica ao Python)

    List<Kept> nms(List<Candidate> candidates, boolean[] limit) { return nms(candidates, limit, MAX_OBJECTS, MAX_CANDIDATES); }
    List<Kept> nms(List<Candidate> candidates, boolean[] limit, int maxObjects, int maxCandidates) {
        List<Candidate> order = new ArrayList<>(candidates);
        order.sort((a, b) -> Float.compare(b.score, a.score)); // estável, como sorted() do Python
        if (order.size() > maxCandidates) { limit[0] = true; order = order.subList(0, maxCandidates); }
        List<Kept> kept = new ArrayList<>();
        for (Candidate c : order) {
            double[] props = props(c.px, c.py);
            if (props[0] < Math.PI * Math.pow(minRadiusNet / c.scale, 2)) continue;
            double[][] hull = hull(c.px, c.py);
            double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < c.px.length; i++) {
                box[0] = Math.min(box[0], c.px[i]); box[1] = Math.min(box[1], c.py[i]);
                box[2] = Math.max(box[2], c.px[i]); box[3] = Math.max(box[3], c.py[i]);
            }
            boolean duplicate = false;
            for (Kept k : kept) {
                if (box[0] > k.maxX || k.minX > box[2] || box[1] > k.maxY || k.minY > box[3]) continue;
                if (intersectionArea(hull, k.hull) > nmsOverlap * Math.min(props[0], k.area)) { duplicate = true; break; }
            }
            if (duplicate) continue;
            if (kept.size() >= maxObjects) { limit[0] = true; break; }
            kept.add(new Kept(c, hull, props, box));
        }
        return kept;
    }

    /** {área, cx, cy, perímetro} pelo teorema de Green. */
    static double[] props(float[] px, float[] py) {
        double a = 0, cx = 0, cy = 0, per = 0;
        int n = px.length;
        for (int i = 0; i < n; i++) {
            double x0 = px[i], y0 = py[i], x1 = px[(i + 1) % n], y1 = py[(i + 1) % n], c = x0 * y1 - x1 * y0;
            a += c; cx += (x0 + x1) * c; cy += (y0 + y1) * c; per += Math.hypot(x1 - x0, y1 - y0);
        }
        a /= 2;
        if (Math.abs(a) < 1e-12) {
            double mx = 0, my = 0; for (int i = 0; i < n; i++) { mx += px[i]; my += py[i]; }
            return new double[]{0, mx / n, my / n, per};
        }
        return new double[]{Math.abs(a), cx / (6 * a), cy / (6 * a), per};
    }

    private static double cross(double[] o, double[] a, double[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }

    /** Cadeia monótona de Andrew, anti-horária, sem colineares. */
    static double[][] hull(float[] px, float[] py) {
        int n = px.length;
        double[][] p = new double[n][];
        for (int i = 0; i < n; i++) p[i] = new double[]{px[i], py[i]};
        java.util.Arrays.sort(p, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        if (n < 3) return p;
        List<double[]> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (double[] q : p) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), q) <= 0) lower.remove(lower.size() - 1);
            lower.add(q);
        }
        for (int i = n - 1; i >= 0; i--) {
            double[] q = p[i];
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), q) <= 0) upper.remove(upper.size() - 1);
            upper.add(q);
        }
        List<double[]> out = new ArrayList<>(lower.subList(0, lower.size() - 1));
        out.addAll(upper.subList(0, upper.size() - 1));
        return out.toArray(new double[0][]);
    }

    /** Sutherland–Hodgman entre polígonos convexos anti-horários. */
    static double intersectionArea(double[][] a, double[][] b) {
        List<double[]> out = new ArrayList<>(java.util.Arrays.asList(a));
        int m = b.length;
        for (int i = 0; i < m; i++) {
            if (out.isEmpty()) break;
            double[] c0 = b[i], c1 = b[(i + 1) % m];
            List<double[]> in = out; out = new ArrayList<>();
            int n = in.size();
            for (int j = 0; j < n; j++) {
                double[] p = in.get(j), q = in.get((j + 1) % n);
                boolean pin = cross(c0, c1, p) >= 0, qin = cross(c0, c1, q) >= 0;
                if (pin) out.add(p);
                if (pin != qin) {
                    double dx = q[0] - p[0], dy = q[1] - p[1], ex = c1[0] - c0[0], ey = c1[1] - c0[1], den = dx * ey - dy * ex;
                    if (den != 0) {
                        double t = ((c0[0] - p[0]) * ey - (c0[1] - p[1]) * ex) / den;
                        out.add(new double[]{p[0] + t * dx, p[1] + t * dy});
                    }
                }
            }
        }
        if (out.size() < 3) return 0;
        double s = 0;
        for (int i = 0; i < out.size(); i++) {
            double[] p = out.get(i), q = out.get((i + 1) % out.size());
            s += p[0] * q[1] - q[0] * p[1];
        }
        return Math.abs(s) / 2;
    }

    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format("%02x", b & 255)); return s.toString(); }
}
