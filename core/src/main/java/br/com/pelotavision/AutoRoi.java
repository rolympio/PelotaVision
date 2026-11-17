package br.com.pelotavision;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/**
 * 0.18.0: região de interesse automática. Propõe onde estão as pelotas sem o operador desenhar nada:
 * recipiente redondo, recipiente quadrado/retangular (bandeja), trecho carregado de correia transportadora
 * ou campo cheio. Só propõe geometria; o operador continua podendo ajustar, e o JSON registra que a região
 * foi automática e com que tipo e confiança.
 *
 * Sinais (Java puro, igual no Android e no Windows; imagem cinza de ~480–800 px no lado maior):
 * 1. Aro de recipiente redondo ({@link ContainerRimDetector}) — o mais preciso quando existe.
 * 2. Máscara de material por textura: módulo do gradiente normalizado pelo brilho local, média em janela
 *    do tamanho de uma pelota, limiar de Otsu, maior componente conexo, fechamento morfológico. Pelotas
 *    formam um campo de bordas densas; correia vazia, fundo de bandeja e parede são lisos.
 * 3. Bordas longas da correia ({@link BeltEdgeDetector}) para limitar a faixa da correia.
 * A forma da máscara decide o tipo: preenchimento do círculo mínimo × do retângulo; contato com dois lados
 * opostos do quadro e alongamento indicam correia. A região final fica para dentro da borda do material
 * (margem de meia janela) para não pegar pelotas cortadas pela parede ou pela borda da carga.
 */
public final class AutoRoi {
    private AutoRoi() {}

    public enum Kind {
        ROUND_CONTAINER("recipiente redondo", "round_container"),
        RECT_CONTAINER("recipiente retangular", "rect_container"),
        BELT_LOAD("correia: trecho carregado", "belt_load"),
        FULL_FIELD("campo cheio de pelotas", "full_field"),
        NONE("não identificado", "none");
        public final String label, code;
        Kind(String label, String code) { this.label = label; this.code = code; }
    }

    /** Proposta de região. {@code region} está nas coordenadas da imagem recebida. */
    public static final class Result {
        public final Kind kind;
        public final InterestRegion region;
        /** 0..1: quanto a forma encontrada se parece com o tipo e quanto o material se separa do fundo. */
        public final double confidence;
        /** Fração do quadro coberta pela máscara de material e contraste de textura material/fundo. */
        public final double materialFraction, textureContrast;
        public final String reason;
        Result(Kind kind, InterestRegion region, double confidence, double materialFraction, double textureContrast, String reason) {
            this.kind = kind; this.region = region; this.confidence = Math.max(0, Math.min(1, confidence));
            this.materialFraction = materialFraction; this.textureContrast = textureContrast; this.reason = reason;
        }
        public boolean usable() { return region != null && kind != Kind.NONE && confidence >= MIN_CONFIDENCE; }
        /** Leva a proposta feita numa imagem reduzida para as coordenadas de outra imagem (fatores sx, sy). */
        public Result scaled(double sx, double sy, int w, int h) {
            if (region == null) return this;
            InterestRegion r;
            if (region.shape == InterestRegion.Shape.CIRCLE) {
                double s = Math.sqrt(sx * sy);
                r = circle(region.cx() * sx, region.cy() * sy, region.width() / 2 * s, w, h);
            } else r = rect(region.left * sx, region.top * sy, region.right * sx, region.bottom * sy, w, h);
            return new Result(kind, r, confidence, materialFraction, textureContrast, reason);
        }
        public String describe() {
            return String.format(Locale.ROOT, "%s · confiança %.0f%%", kind.label, 100 * confidence);
        }
    }

    /** Abaixo disto a proposta é mostrada mas não aplicada sozinha. */
    public static final double MIN_CONFIDENCE = .55;

    /** Lado maior em que a detecção roda; fotos maiores são reduzidas por média de área. */
    public static final int WORK_SIDE = 640;

    /** Como {@link #detect}, para qualquer resolução: reduz a ~{@link #WORK_SIDE} px e devolve nas coordenadas originais. */
    public static Result detectAnySize(byte[] gray, int w, int h) {
        int f = Math.max(1, (int) Math.round(Math.max(w, h) / (double) WORK_SIDE));
        if (f == 1 || gray == null || gray.length != w * h) return detect(gray, w, h);
        int sw = w / f, sh = h / f; byte[] small = new byte[sw * sh];
        for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++) {
            int acc = 0;
            for (int dy = 0; dy < f; dy++) { int row = (y * f + dy) * w + x * f; for (int dx = 0; dx < f; dx++) acc += gray[row + dx] & 255; }
            small[y * sw + x] = (byte) (acc / (f * f));
        }
        return detect(small, sw, sh).scaled(f, f, w, h);
    }

    /** Detecta a região numa imagem cinza (1 byte por pixel). Nunca devolve null. */
    public static Result detect(byte[] gray, int w, int h) {
        if (gray == null || gray.length != w * h || Math.min(w, h) < 64)
            return new Result(Kind.NONE, null, 0, 0, 0, "imagem pequena demais");
        Texture t = texture(gray, w, h);
        boolean[] mask = t.mask;
        double frac = t.fraction;

        // 1. Recipiente redondo pelo aro: mais preciso que a máscara quando o aro aparece.
        ContainerCalibration.Detection rim = null;
        try { rim = ContainerRimDetector.detect(gray, w, h); } catch (RuntimeException ignored) { }
        if (rim != null && rim.rim != null) {
            ContainerCalibration.Circle c = rim.pelletLevel != null && rim.pelletLevel.r < rim.rim.r ? rim.pelletLevel : rim.rim;
            double inside = coverage(mask, w, h, c.cx, c.cy, c.r * .9);
            double outside = ringCoverage(mask, w, h, c.cx, c.cy, rim.rim.r * 1.06, rim.rim.r * 1.25);
            if (inside > .6) {
                // Parede projeta sombra e esconde meia pelota: recua ~3% do raio (ou meia janela).
                double r = c.r - Math.max(c.r * .03, t.window * .5);
                double conf = .45 + .3 * Math.min(1, rim.rimCoverage) + .25 * Math.max(0, inside - Math.max(0, outside)) - (Double.isFinite(rim.rimOvality) ? Math.min(.2, rim.rimOvality) : 0);
                return new Result(Kind.ROUND_CONTAINER, circle(c.cx, c.cy, r, w, h), conf, frac, t.contrast,
                        rim.pelletLevel != null ? "aro + nível das pelotas" : "aro do recipiente");
            }
        }

        if (t.contrast < 1.35 || frac > .9) {
            // Textura uniforme: ou o quadro todo é material (campo cheio), ou não há pelotas.
            if (t.meanTexture >= t.fieldTexture)
                return new Result(Kind.FULL_FIELD, rect(w * .02, h * .02, w * .98, h * .98, w, h), .6 + .2 * Math.min(1, frac), frac, t.contrast, "textura de pelotas no quadro inteiro");
            return new Result(Kind.NONE, null, 0, frac, t.contrast, "sem contraste de textura entre material e fundo");
        }
        if (frac < .04) return new Result(Kind.NONE, null, 0, frac, t.contrast, "material ocupa menos de 4% do quadro");

        Shape s = shape(mask, w, h);
        double margin = t.window * .5 + 2;

        // 2. Correia: material encosta em dois lados opostos do quadro e é alongado.
        boolean spansX = s.touchLeft && s.touchRight, spansY = s.touchTop && s.touchBottom;
        if ((spansX || spansY) && !(spansX && spansY)) {
            double l = s.minX + margin, r = s.maxX - margin, tp = s.minY + margin, b = s.maxY - margin;
            if (spansX) { l = 0; r = w - 1; } else { tp = 0; b = h - 1; }
            // A faixa da correia, quando achada, limita o lado transversal.
            String why = "faixa de material atravessando o quadro";
            try {
                BeltCalibration.Edges e = BeltEdgeDetector.suggest(gray, w, h);
                if (e != null) {
                    double ax = Math.abs(e.x(1) - e.x(0)), ay = Math.abs(e.y(1) - e.y(0));
                    boolean horizontal = ax > ay;
                    if (horizontal == spansX) {
                        if (horizontal) { double y0 = Math.min(mid(e.y(0), e.y(1)), mid(e.y(2), e.y(3))), y1 = Math.max(mid(e.y(0), e.y(1)), mid(e.y(2), e.y(3))); tp = Math.max(tp, y0 + margin); b = Math.min(b, y1 - margin); }
                        else { double x0 = Math.min(mid(e.x(0), e.x(1)), mid(e.x(2), e.x(3))), x1 = Math.max(mid(e.x(0), e.x(1)), mid(e.x(2), e.x(3))); l = Math.max(l, x0 + margin); r = Math.min(r, x1 - margin); }
                        why = "faixa carregada entre as bordas da correia";
                    }
                }
            } catch (RuntimeException ignored) { }
            InterestRegion region = rect(l, tp, r, b, w, h);
            double fill = region == null ? 0 : fillRect(mask, w, h, region);
            return new Result(Kind.BELT_LOAD, region, .35 + .45 * fill + .2 * Math.min(1, (t.contrast - 1) / 1.5), frac, t.contrast, why);
        }

        // 3. Recipiente: círculo × retângulo pelo preenchimento da máscara.
        double circleFill = s.area / (Math.PI * s.rMin * s.rMin);
        double rectFill = s.area / Math.max(1, (s.maxX - s.minX + 1) * (s.maxY - s.minY + 1));
        if (circleFill >= .82 && circleFill >= rectFill * 1.05) {
            double r = s.rMin - margin;
            return new Result(Kind.ROUND_CONTAINER, circle(s.cx, s.cy, r, w, h), .3 + .5 * Math.min(1, circleFill) + .2 * Math.min(1, (t.contrast - 1) / 1.5), frac, t.contrast, "mancha de material circular (sem aro nítido)");
        }
        // Retângulo: maior retângulo alinhado que fica quase todo dentro da máscara (bandeja pode estar girada).
        InterestRegion inner = innerRect(mask, w, h, s, margin);
        if (inner != null) {
            double fill = fillRect(mask, w, h, inner);
            double areaShare = inner.width() * inner.height() / Math.max(1, s.area);
            Kind k = rectFill >= .7 ? Kind.RECT_CONTAINER : Kind.NONE;
            double conf = .25 + .35 * Math.min(1, rectFill) + .25 * fill + .15 * Math.min(1, areaShare);
            if (k == Kind.NONE) return new Result(Kind.NONE, inner, Math.min(conf, MIN_CONFIDENCE - .05), frac, t.contrast, "forma irregular: proposta só como sugestão");
            return new Result(k, inner, conf, frac, t.contrast, "mancha de material retangular");
        }
        return new Result(Kind.NONE, null, 0, frac, t.contrast, "forma do material não reconhecida");
    }

    private static double mid(double a, double b) { return (a + b) / 2; }

    // ---------- textura ----------
    /** Gradiente relativo (% do brilho local por pixel) considerado borda: rampa entre LO e HI. */
    static final float EDGE_LO = 4f, EDGE_HI = 10f;
    static final double FIELD_DENSITY = 12;
    static final class Texture { boolean[] mask; double fraction, contrast, meanTexture, fieldTexture; int window; float[] map; }

    static Texture texture(byte[] gray, int w, int h) {
        int n = w * h;
        float[] g = new float[n];
        for (int i = 0; i < n; i++) g[i] = gray[i] & 255;
        float[] s = boxBlur(g, w, h, 1);
        int win = Math.max(7, Math.min(w, h) / 22) | 1;
        float[] mean = boxBlur(s, w, h, win / 2);
        float[] mag = new float[n], gxs = new float[n], gys = new float[n];
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x;
            float gx = s[i + 1] - s[i - 1], gy = s[i + w] - s[i - w];
            // Normaliza pelo brilho local: pelotas escuras numa foto escura têm a mesma textura relativa.
            mag[i] = (float) (Math.sqrt(gx * gx + gy * gy) / (mean[i] + 12) * 100);
            gxs[i] = gx; gys[i] = gy;
        }
        // Densidade de bordas (magnitude saturada) vezes isotropia: pelotas têm bordas em todas as direções;
        // parede de bandeja, borda de correia e aro são linhas de direção única (coerência ~1) e somem.
        float[] edge = new float[n], jxx = new float[n], jyy = new float[n], jxy = new float[n];
        for (int i = 0; i < n; i++) {
            float e = Math.min(1f, Math.max(0f, (mag[i] - EDGE_LO) / (EDGE_HI - EDGE_LO)));
            edge[i] = e;
            if (e > 0) { double gm = Math.hypot(gxs[i], gys[i]); float ux = (float) (gxs[i] / gm), uy = (float) (gys[i] / gm); jxx[i] = e * ux * ux; jyy[i] = e * uy * uy; jxy[i] = e * ux * uy; }
        }
        float[] dens = boxBlur(edge, w, h, win / 2), axx = boxBlur(jxx, w, h, win / 2), ayy = boxBlur(jyy, w, h, win / 2), axy = boxBlur(jxy, w, h, win / 2);
        float[] tex = new float[n];
        for (int i = 0; i < n; i++) {
            double tr = axx[i] + ayy[i], coh = tr <= 1e-6 ? 1 : Math.sqrt((axx[i] - ayy[i]) * (axx[i] - ayy[i]) + 4 * axy[i] * axy[i]) / tr;
            tex[i] = (float) (100 * dens[i] * Math.max(0, 1 - coh));
        }
        Texture t = new Texture();
        t.window = win; t.map = tex;
        // Otsu em 128 níveis sobre a faixa útil (percentis 1–99).
        float[] sorted = tex.clone(); Arrays.sort(sorted);
        float lo = sorted[(int) (n * .01)], hi = sorted[(int) (n * .99)];
        double sum = 0; for (float v : tex) sum += v; t.meanTexture = sum / n;
        // Campo de pelotas: pelo menos ~12% de pixels de borda em média (discos encostados dão 20–40%).
        t.fieldTexture = FIELD_DENSITY;
        if (hi - lo < 1e-3) { t.mask = new boolean[n]; t.fraction = 0; t.contrast = 1; return t; }
        int B = 128; double[] hist = new double[B];
        for (float v : tex) { int b = (int) ((Math.min(hi, Math.max(lo, v)) - lo) / (hi - lo) * (B - 1)); hist[b]++; }
        double total = n, sumAll = 0; for (int i = 0; i < B; i++) sumAll += i * hist[i];
        double wB = 0, sumB = 0, best = -1; int thr = B / 2;
        for (int i = 0; i < B; i++) {
            wB += hist[i]; if (wB == 0) continue; double wF = total - wB; if (wF == 0) break;
            sumB += i * hist[i]; double mB = sumB / wB, mF = (sumAll - sumB) / wF, between = wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; thr = i; }
        }
        float threshold = lo + (thr + .5f) / (B - 1) * (hi - lo);
        boolean[] m = new boolean[n];
        double inSum = 0, outSum = 0; int inN = 0, outN = 0;
        for (int i = 0; i < n; i++) { if (tex[i] > threshold) { m[i] = true; inSum += tex[i]; inN++; } else { outSum += tex[i]; outN++; } }
        t.contrast = inN == 0 || outN == 0 ? 1 : (inSum / inN + 1) / (outSum / outN + 1);
        m = close(m, w, h, Math.max(2, win / 3));
        m = largestComponent(m, w, h);
        m = fillHoles(m, w, h);
        int c = 0; for (boolean b : m) if (b) c++;
        t.mask = m; t.fraction = c / (double) n;
        return t;
    }

    static float[] boxBlur(float[] a, int w, int h, int r) {
        if (r < 1) return a.clone();
        float[] tmp = new float[a.length], out = new float[a.length];
        for (int y = 0; y < h; y++) {
            double acc = 0; int row = y * w;
            for (int x = -r; x <= r; x++) acc += a[row + clamp(x, w)];
            for (int x = 0; x < w; x++) { tmp[row + x] = (float) (acc / (2 * r + 1)); acc += a[row + clamp(x + r + 1, w)] - a[row + clamp(x - r, w)]; }
        }
        for (int x = 0; x < w; x++) {
            double acc = 0;
            for (int y = -r; y <= r; y++) acc += tmp[clamp(y, h) * w + x];
            for (int y = 0; y < h; y++) { out[y * w + x] = (float) (acc / (2 * r + 1)); acc += tmp[clamp(y + r + 1, h) * w + x] - tmp[clamp(y - r, h) * w + x]; }
        }
        return out;
    }
    private static int clamp(int v, int n) { return v < 0 ? 0 : v >= n ? n - 1 : v; }

    static boolean[] close(boolean[] m, int w, int h, int r) { return erode(dilate(m, w, h, r), w, h, r); }
    static boolean[] dilate(boolean[] m, int w, int h, int r) { return threshold(boxBlur(toFloat(m), w, h, r), 1e-4f); }
    static boolean[] erode(boolean[] m, int w, int h, int r) { return threshold(boxBlur(toFloat(m), w, h, r), 1 - 1e-4f); }
    private static float[] toFloat(boolean[] m) { float[] f = new float[m.length]; for (int i = 0; i < m.length; i++) f[i] = m[i] ? 1 : 0; return f; }
    private static boolean[] threshold(float[] f, float t) { boolean[] m = new boolean[f.length]; for (int i = 0; i < f.length; i++) m[i] = f[i] >= t; return m; }

    static boolean[] largestComponent(boolean[] m, int w, int h) {
        int[] label = new int[m.length]; int best = 0, bestSize = 0, next = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < m.length; s++) {
            if (!m[s] || label[s] != 0) continue;
            int id = ++next, size = 0; label[s] = id; q.add(s);
            while (!q.isEmpty()) {
                int i = q.poll(); size++; int x = i % w, y = i / w;
                if (x > 0 && m[i - 1] && label[i - 1] == 0) { label[i - 1] = id; q.add(i - 1); }
                if (x < w - 1 && m[i + 1] && label[i + 1] == 0) { label[i + 1] = id; q.add(i + 1); }
                if (y > 0 && m[i - w] && label[i - w] == 0) { label[i - w] = id; q.add(i - w); }
                if (y < h - 1 && m[i + w] && label[i + w] == 0) { label[i + w] = id; q.add(i + w); }
            }
            if (size > bestSize) { bestSize = size; best = id; }
        }
        boolean[] out = new boolean[m.length];
        for (int i = 0; i < m.length; i++) out[i] = best != 0 && label[i] == best;
        return out;
    }

    /** Preenche buracos que não tocam a borda do quadro (pelota lisa grande, reflexo). */
    static boolean[] fillHoles(boolean[] m, int w, int h) {
        boolean[] outside = new boolean[m.length]; ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < w; x++) { seed(m, outside, q, x); seed(m, outside, q, (h - 1) * w + x); }
        for (int y = 0; y < h; y++) { seed(m, outside, q, y * w); seed(m, outside, q, y * w + w - 1); }
        while (!q.isEmpty()) {
            int i = q.poll(), x = i % w, y = i / w;
            if (x > 0) seed(m, outside, q, i - 1);
            if (x < w - 1) seed(m, outside, q, i + 1);
            if (y > 0) seed(m, outside, q, i - w);
            if (y < h - 1) seed(m, outside, q, i + w);
        }
        boolean[] out = new boolean[m.length];
        for (int i = 0; i < m.length; i++) out[i] = m[i] || !outside[i];
        return out;
    }
    private static void seed(boolean[] m, boolean[] seen, ArrayDeque<Integer> q, int i) { if (!m[i] && !seen[i]) { seen[i] = true; q.add(i); } }

    // ---------- forma ----------
    static final class Shape { double area, cx, cy, rMin; int minX, minY, maxX, maxY; boolean touchLeft, touchRight, touchTop, touchBottom; }

    static Shape shape(boolean[] m, int w, int h) {
        Shape s = new Shape(); s.minX = w; s.minY = h; s.maxX = -1; s.maxY = -1;
        double sx = 0, sy = 0; int n = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (m[y * w + x]) {
            n++; sx += x; sy += y;
            if (x < s.minX) s.minX = x; if (x > s.maxX) s.maxX = x; if (y < s.minY) s.minY = y; if (y > s.maxY) s.maxY = y;
        }
        s.area = n; s.cx = n == 0 ? w / 2.0 : sx / n; s.cy = n == 0 ? h / 2.0 : sy / n;
        int edge = 2;
        s.touchLeft = s.minX <= edge; s.touchRight = s.maxX >= w - 1 - edge; s.touchTop = s.minY <= edge; s.touchBottom = s.maxY >= h - 1 - edge;
        // Raio do círculo que contém a mancha (aproximado pelo 99º percentil da distância ao centróide).
        double[] d = new double[Math.max(1, n)]; int k = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (m[y * w + x]) d[k++] = Math.hypot(x - s.cx, y - s.cy);
        Arrays.sort(d, 0, k);
        s.rMin = k == 0 ? 0 : d[Math.min(k - 1, (int) (k * .995))];
        return s;
    }

    /** Encolhe o retângulo envolvente até ≥97% dele ser material, pelo lado mais vazio a cada passo. */
    static InterestRegion innerRect(boolean[] m, int w, int h, Shape s, double margin) {
        int l = s.minX, r = s.maxX, t = s.minY, b = s.maxY;
        int[] integral = integral(m, w, h);
        for (int iter = 0; iter < 4 * (w + h); iter++) {
            if (r - l < 16 || b - t < 16) return null;
            double fill = sum(integral, w, l, t, r, b) / (double) ((r - l + 1) * (b - t + 1));
            if (fill >= .97) break;
            double fl = sum(integral, w, l, t, l, b) / (double) (b - t + 1), fr = sum(integral, w, r, t, r, b) / (double) (b - t + 1);
            double ft = sum(integral, w, l, t, r, t) / (double) (r - l + 1), fb = sum(integral, w, l, b, r, b) / (double) (r - l + 1);
            double min = Math.min(Math.min(fl, fr), Math.min(ft, fb));
            if (min == fl) l++; else if (min == fr) r--; else if (min == ft) t++; else b--;
        }
        return rect(l + margin, t + margin, r - margin, b - margin, w, h);
    }
    private static int[] integral(boolean[] m, int w, int h) {
        int[] I = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) { int row = 0; for (int x = 0; x < w; x++) { row += m[y * w + x] ? 1 : 0; I[(y + 1) * (w + 1) + x + 1] = I[y * (w + 1) + x + 1] + row; } }
        return I;
    }
    private static int sum(int[] I, int w, int l, int t, int r, int b) {
        int W = w + 1; return I[(b + 1) * W + r + 1] - I[t * W + r + 1] - I[(b + 1) * W + l] + I[t * W + l];
    }

    static double fillRect(boolean[] m, int w, int h, InterestRegion r) {
        int l = (int) Math.max(0, Math.ceil(r.left)), t = (int) Math.max(0, Math.ceil(r.top)), rr = (int) Math.min(w - 1, Math.floor(r.right)), b = (int) Math.min(h - 1, Math.floor(r.bottom));
        if (rr <= l || b <= t) return 0;
        int c = 0; for (int y = t; y <= b; y++) for (int x = l; x <= rr; x++) if (m[y * w + x]) c++;
        return c / (double) ((rr - l + 1) * (b - t + 1));
    }
    static double coverage(boolean[] m, int w, int h, double cx, double cy, double r) { return ringCoverage(m, w, h, cx, cy, 0, r); }
    static double ringCoverage(boolean[] m, int w, int h, double cx, double cy, double r0, double r1) {
        int c = 0, n = 0;
        int x0 = (int) Math.max(0, cx - r1), x1 = (int) Math.min(w - 1, cx + r1), y0 = (int) Math.max(0, cy - r1), y1 = (int) Math.min(h - 1, cy + r1);
        for (int y = y0; y <= y1; y += 2) for (int x = x0; x <= x1; x += 2) {
            double d = Math.hypot(x - cx, y - cy); if (d < r0 || d > r1) continue; n++; if (m[y * w + x]) c++;
        }
        return n == 0 ? -1 : c / (double) n;
    }

    // ---------- regiões válidas ----------
    static InterestRegion circle(double cx, double cy, double r, int w, int h) {
        r = Math.min(r, Math.min(Math.min(cx, cy), Math.min(w - 1 - cx, h - 1 - cy)));
        if (!(r >= 8)) return null;
        return new InterestRegion(InterestRegion.Shape.CIRCLE, cx - r, cy - r, cx + r, cy + r);
    }
    static InterestRegion rect(double l, double t, double r, double b, int w, int h) {
        l = Math.max(0, l); t = Math.max(0, t); r = Math.min(w - 1, r); b = Math.min(h - 1, b);
        if (r - l < 16 || b - t < 16) return null;
        return new InterestRegion(InterestRegion.Shape.RECTANGLE, l, t, r, b);
    }

    // ---------- visualização ao vivo ----------
    /**
     * Estabiliza a proposta entre quadros da câmera: média exponencial da geometria quando o tipo se mantém,
     * troca de tipo só depois de {@code switchFrames} quadros seguidos concordando. Evita a ROI "pulando".
     */
    public static final class Stabilizer {
        private final double alpha; private final int switchFrames;
        private Result current; private Kind pending; private int pendingCount;
        public Stabilizer() { this(.35, 3); }
        public Stabilizer(double alpha, int switchFrames) { this.alpha = alpha; this.switchFrames = switchFrames; }
        public synchronized Result current() { return current; }
        public synchronized void reset() { current = null; pending = null; pendingCount = 0; }
        public synchronized Result update(Result r, int w, int h) {
            if (r == null) return current;
            if (current == null || current.region == null) { if (r.region != null) current = r; return current; }
            if (r.kind == current.kind && r.region != null && r.region.shape == current.region.shape) {
                pending = null; pendingCount = 0;
                InterestRegion a = current.region, b = r.region; double k = alpha;
                InterestRegion mixed;
                if (a.shape == InterestRegion.Shape.CIRCLE) mixed = circle(a.cx() + k * (b.cx() - a.cx()), a.cy() + k * (b.cy() - a.cy()), (a.width() + k * (b.width() - a.width())) / 2, w, h);
                else mixed = rect(a.left + k * (b.left - a.left), a.top + k * (b.top - a.top), a.right + k * (b.right - a.right), a.bottom + k * (b.bottom - a.bottom), w, h);
                current = new Result(r.kind, mixed == null ? b : mixed, current.confidence + k * (r.confidence - current.confidence), r.materialFraction, r.textureContrast, r.reason);
                return current;
            }
            if (r.kind == pending) pendingCount++; else { pending = r.kind; pendingCount = 1; }
            if (pendingCount >= switchFrames && r.region != null) { current = r; pending = null; pendingCount = 0; }
            return current;
        }
    }
}
