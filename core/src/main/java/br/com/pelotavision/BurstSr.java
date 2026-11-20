package br.com.pelotavision;

import java.util.Arrays;
import java.util.Random;

/**
 * Trilha 2 da rajada — Burst SR 2× (0.19.0), condicionada ao veredito {@link BurstDiagnostics.Verdict#SR_2X_PLAUSIVEL}.
 *
 * <p>Reconstrução explícita e auditável do modelo direto {@code y_k = D B S_k(x) + n_k}: translação
 * sub-pixel {@code S_k}, integração do pixel {@code B} (caixa de lado 2 na grade HR) e decimação
 * {@code D}. Sem rede neural e sem "aumentar e afiar".</p>
 * <ol>
 *   <li><b>Drizzle / convolução normalizada:</b> {@code x0 = Σ Aᵀ(c_k y_k) / Σ Aᵀ(c_k 1)}, pesos de área.</li>
 *   <li><b>Back-projection iterativa (IBP):</b> descida em {@code ½ Σ ||M_k (A_k x − y_k)||² + μ R(x)},
 *   passo {@code 1,2 / λ_max} com λ_max por iteração de potência, 6 a 12 iterações (nunca 60: o ensaio
 *   mostrou que amplifica ruído). Máscara de robustez M rejeita amostras inconsistentes (deghosting) e
 *   R suaviza só as áreas planas (peso {@code exp(-(|∇x0|/τ)²)}), preservando a aresta.</li>
 * </ol>
 *
 * <p>Garantias verificadas em teste e em toda execução: adjunta exata ({@code <Ax,r> = <x,Aᵀr>} até
 * 1e-9 relativo) e consistência com os dados (χ² dos resíduos contra o ruído); se o χ² passar de
 * {@link #CHI2_MAX} (registro errado, cena mexeu, modelo não descreve a câmera), a SR é rejeitada e o
 * chamador fica com a mediana. Coordenadas: amostra (i, j) do quadro k está na posição
 * {@code (j − fx_k, i − fy_k)} da grade LR do recorte base.</p>
 */
public final class BurstSr {
    private BurstSr() {}

    public static final int SCALE = 2;
    public static final int MIN_ITERATIONS = 6, MAX_ITERATIONS = 12, DEFAULT_ITERATIONS = 10;
    public static final double RELAXATION = 1.2;
    public static final double ROBUST_KAPPA = 3.0;
    /**
     * Peso da suavidade (× N/4, a escala de λ de Σ AᵀA). A caixa de 2 px HR tem resposta zero no
     * Nyquist da grade HR: sem regularização o ruído ali não aparece nos dados e o IBP o amplifica.
     * 0,5 foi o melhor valor na varredura de 2026-11-20 (0,05 → 34,2 dB; 0,3–1 → 38,8 dB; 3 → 37,8 dB).
     */
    public static final double REG_WEIGHT = 0.5;
    public static final double CHI2_MAX = 2.5;
    public static final double ADJOINT_TOLERANCE = 1e-9;

    /** Geometria do operador A para N quadros h×w e grade HR (2h)×(2w). */
    public static final class Plan {
        public final int n, h, w, H, W;
        final int[] ox, oy;          // índice HR inicial (relativo a 2j / 2i)
        final double[][] wx, wy;     // 4 pesos por eixo (somam 1)

        public Plan(int h, int w, double[] fx, double[] fy) {
            if (fx.length != fy.length || fx.length == 0) throw new IllegalArgumentException("Offsets inválidos.");
            this.n = fx.length; this.h = h; this.w = w; this.H = SCALE * h; this.W = SCALE * w;
            ox = new int[n]; oy = new int[n]; wx = new double[n][]; wy = new double[n][];
            for (int k = 0; k < n; k++) {
                double[] a = footprint(fx[k]); ox[k] = (int) a[0]; wx[k] = Arrays.copyOfRange(a, 1, 5);
                double[] b = footprint(fy[k]); oy[k] = (int) b[0]; wy[k] = Arrays.copyOfRange(b, 1, 5);
            }
        }

        /**
         * Amostra LR em u = j − f cobre, na grade HR (pixel m ocupa [m−½, m+½]), o intervalo
         * [2u − ½, 2u + 3/2]. Devolve {o, w0..w3}: pixels HR 2j+o .. 2j+o+3 com pesos de área normalizados.
         */
        static double[] footprint(double f) {
            double s = -2 * f - 0.5;          // início do intervalo para j = 0
            int o = (int) Math.floor(s + 0.5);
            double[] out = new double[5];
            out[0] = o;
            for (int t = 0; t < 4; t++) {
                int m = o + t;
                double ov = Math.min(s + 2, m + 0.5) - Math.max(s, m - 0.5);
                out[1 + t] = Math.max(0, ov) / 2;
            }
            return out;
        }

        /** Borda replicada: a pegada que sai da grade HR lê (e, na adjunta, espalha em) o pixel da borda. */
        static int clampIndex(int i, int n) { return i < 0 ? 0 : i >= n ? n - 1 : i; }

        /** y = A_k x. */
        public void forward(int k, double[] x, double[] y) {
            double[] ax = wx[k], ay = wy[k];
            int bx = ox[k], by = oy[k];
            for (int i = 0; i < h; i++) {
                int m0 = 2 * i + by;
                for (int j = 0; j < w; j++) {
                    int n0 = 2 * j + bx;
                    double acc = 0;
                    for (int a = 0; a < 4; a++) {
                        double wa = ay[a];
                        if (wa == 0) continue;
                        int row = clampIndex(m0 + a, H) * W;
                        for (int b = 0; b < 4; b++) {
                            double wb = ax[b];
                            if (wb == 0) continue;
                            acc += wa * wb * x[row + clampIndex(n0 + b, W)];
                        }
                    }
                    y[i * w + j] = acc;
                }
            }
        }

        /** x += A_kᵀ r (scatter com os mesmos pesos). */
        public void adjointAdd(int k, double[] r, double[] x) {
            double[] ax = wx[k], ay = wy[k];
            int bx = ox[k], by = oy[k];
            for (int i = 0; i < h; i++) {
                int m0 = 2 * i + by;
                for (int j = 0; j < w; j++) {
                    double v = r[i * w + j];
                    if (v == 0) continue;
                    int n0 = 2 * j + bx;
                    for (int a = 0; a < 4; a++) {
                        double wa = ay[a];
                        if (wa == 0) continue;
                        int row = clampIndex(m0 + a, H) * W;
                        for (int b = 0; b < 4; b++) {
                            double wb = ax[b];
                            if (wb == 0) continue;
                            x[row + clampIndex(n0 + b, W)] += wa * wb * v;
                        }
                    }
                }
            }
        }

        /** Erro relativo |<Ax,r> − <x,Aᵀr>| / max(|<Ax,r>|, ε), com vetores aleatórios reprodutíveis. */
        public double adjointError(long seed) {
            Random rnd = new Random(seed);
            double[] x = new double[H * W];
            for (int i = 0; i < x.length; i++) x[i] = rnd.nextGaussian();
            double lhs = 0, rhs = 0;
            double[] ax = new double[h * w], r = new double[h * w], atr = new double[H * W];
            for (int k = 0; k < n; k++) {
                for (int i = 0; i < r.length; i++) r[i] = rnd.nextGaussian();
                forward(k, x, ax);
                for (int i = 0; i < r.length; i++) lhs += ax[i] * r[i];
                adjointAdd(k, r, atr);
            }
            for (int i = 0; i < x.length; i++) rhs += x[i] * atr[i];
            return Math.abs(lhs - rhs) / Math.max(1e-300, Math.abs(lhs));
        }
    }

    /** Saída de um canal. */
    public static final class Result {
        public final double[] hr;
        public final int H, W, iterations;
        public final double chi2, adjointError, lambdaMax, step, regWeight;
        public final boolean accepted;
        public final String reason;
        /** Máscara de robustez por quadro (para reaproveitar nos outros canais). */
        public final double[][] mask;

        Result(double[] hr, int H, int W, int iterations, double chi2, double adjointError, double lambdaMax, double step,
               double regWeight, boolean accepted, String reason, double[][] mask) {
            this.hr = hr; this.H = H; this.W = W; this.iterations = iterations; this.chi2 = chi2; this.adjointError = adjointError;
            this.lambdaMax = lambdaMax; this.step = step; this.regWeight = regWeight; this.accepted = accepted; this.reason = reason; this.mask = mask;
        }
    }

    /** Drizzle puro (convolução normalizada); buracos preenchidos por difusão dos vizinhos. */
    public static double[] drizzle(Plan p, double[][] y, double[] confidence) {
        double[] num = new double[p.H * p.W], den = new double[p.H * p.W];
        double[] ones = new double[p.h * p.w], tmp = new double[p.h * p.w];
        for (int k = 0; k < p.n; k++) {
            double c = confidence == null ? 1 : confidence[k];
            Arrays.fill(ones, c);
            for (int i = 0; i < tmp.length; i++) tmp[i] = c * y[k][i];
            p.adjointAdd(k, tmp, num);
            p.adjointAdd(k, ones, den);
        }
        double maxDen = 0;
        for (double d : den) maxDen = Math.max(maxDen, d);
        double eps = 1e-3 * maxDen;
        double[] x = new double[num.length];
        boolean[] hole = new boolean[num.length];
        int holes = 0;
        for (int i = 0; i < x.length; i++) {
            if (den[i] > eps) x[i] = num[i] / den[i]; else { hole[i] = true; holes++; }
        }
        for (int pass = 0; holes > 0 && pass < 64; pass++) {
            int filled = 0;
            for (int m = 0; m < p.H; m++) for (int c = 0; c < p.W; c++) {
                int i = m * p.W + c;
                if (!hole[i]) continue;
                double s = 0; int cnt = 0;
                if (m > 0 && !hole[i - p.W]) { s += x[i - p.W]; cnt++; }
                if (m < p.H - 1 && !hole[i + p.W]) { s += x[i + p.W]; cnt++; }
                if (c > 0 && !hole[i - 1]) { s += x[i - 1]; cnt++; }
                if (c < p.W - 1 && !hole[i + 1]) { s += x[i + 1]; cnt++; }
                if (cnt > 0) { x[i] = s / cnt; hole[i] = false; filled++; }
            }
            holes -= filled;
            if (filled == 0) break;
        }
        return x;
    }

    /**
     * Reconstrói um canal. {@code y[k]}: amostras h×w do quadro k (k = 0 é a base). {@code sigma}: ruído
     * em níveis de cinza. {@code fixedMask}: máscara de outro canal (null = calcula). Nunca lança por
     * inconsistência: devolve {@code accepted = false} com o motivo.
     */
    public static Result reconstruct(Plan p, double[][] y, double[] confidence, double sigma, int iterations, double[][] fixedMask) {
        return reconstruct(p, y, confidence, sigma, iterations, fixedMask, null);
    }

    /**
     * Como acima, partindo de {@code init} (HR, H×W) quando dado — no app, a empilhada da Trilha 1
     * ampliada por bicúbica, como no IBP clássico de Irani–Peleg. Sem {@code init}, parte do drizzle.
     * Partir da empilhada evita que as poucas iterações permitidas fiquem gastas desfazendo o borrão
     * extra do drizzle (pegada de 2 px HR somada à do pixel).
     */
    public static Result reconstruct(Plan p, double[][] y, double[] confidence, double sigma, int iterations, double[][] fixedMask, double[] init) {
        int it = Math.max(MIN_ITERATIONS, Math.min(MAX_ITERATIONS, iterations));
        double adj = p.adjointError(20261120L);
        double[] x = init != null && init.length == p.H * p.W ? init.clone() : drizzle(p, y, confidence);
        double s = Math.max(1.0, sigma);
        int lr = p.h * p.w;
        double[] ax = new double[lr], r = new double[lr];

        // Peso de suavidade (fase 2): só nas áreas planas da estimativa da fase 1, que já contém a alta
        // frequência legítima dos dados; o ponto de partida (drizzle ou empilhada ampliada) é borrado demais.
        double tau = 4 * s + 1;
        double[] smooth = new double[x.length];
        double mu = REG_WEIGHT * p.n / 4.0;
        Arrays.fill(smooth, 1);

        // Fase 1 (metade das iterações): só a confiança por quadro. O drizzle é borrado demais para julgar
        // amostras de alta frequência legítimas; a máscara de robustez sai do resíduo contra esta estimativa.
        double[][] mask = fixedMask;
        boolean computeMask = mask == null;
        if (computeMask) {
            mask = new double[p.n][lr];
            for (int k = 0; k < p.n; k++) Arrays.fill(mask[k], confidence == null ? 1 : confidence[k]);
        }
        double lambda = powerIteration(p, mask, 15);
        int first = it / 2;
        double muNow = 0; // fase 1 sem suavidade
        double step = RELAXATION / lambda;
        double[] grad = new double[x.length];
        for (int t = 0; t < it; t++) {
            if (t == first) {
                // Fase 2: máscara de robustez (deghosting), peso de suavidade e novo passo.
                if (computeMask) {
                    double inv = 1.0 / (2 * ROBUST_KAPPA * ROBUST_KAPPA * s * s);
                    for (int k = 0; k < p.n; k++) {
                        double c = confidence == null ? 1 : confidence[k];
                        p.forward(k, x, ax);
                        for (int i = 0; i < lr; i++) { double d = y[k][i] - ax[i]; mask[k][i] = c * Math.exp(-d * d * inv); }
                    }
                    lambda = powerIteration(p, mask, 15);
                }
                for (int m = 0; m < p.H; m++) for (int c = 0; c < p.W; c++) {
                    double gx = x[m * p.W + Math.min(p.W - 1, c + 1)] - x[m * p.W + Math.max(0, c - 1)];
                    double gy = x[Math.min(p.H - 1, m + 1) * p.W + c] - x[Math.max(0, m - 1) * p.W + c];
                    double g = 0.5 * Math.hypot(gx, gy) / tau;
                    smooth[m * p.W + c] = Math.exp(-g * g);
                }
                muNow = mu;
                step = RELAXATION / (lambda + 8 * mu);
            }
            Arrays.fill(grad, 0);
            for (int k = 0; k < p.n; k++) {
                p.forward(k, x, ax);
                double[] mk = mask[k];
                for (int i = 0; i < lr; i++) r[i] = mk[i] * (ax[i] - y[k][i]);
                p.adjointAdd(k, r, grad);
            }
            for (int m = 0; m < p.H; m++) for (int c = 0; c < p.W; c++) {
                int i = m * p.W + c;
                double lap = x[m * p.W + Math.min(p.W - 1, c + 1)] + x[m * p.W + Math.max(0, c - 1)]
                        + x[Math.min(p.H - 1, m + 1) * p.W + c] + x[Math.max(0, m - 1) * p.W + c] - 4 * x[i];
                grad[i] -= muNow * smooth[i] * lap;
            }
            for (int i = 0; i < x.length; i++) x[i] -= step * grad[i];
        }

        // Consistência com os dados: χ² sem máscara (registro errado aparece aqui).
        double sse = 0; long cnt = 0;
        for (int k = 0; k < p.n; k++) {
            p.forward(k, x, ax);
            for (int i = 0; i < lr; i++) { double d = y[k][i] - ax[i]; sse += d * d; cnt++; }
        }
        double chi2 = cnt == 0 ? Double.NaN : sse / cnt / (s * s);
        boolean ok = true; String reason = "consistente";
        if (!(adj <= ADJOINT_TOLERANCE)) { ok = false; reason = String.format(java.util.Locale.ROOT, "adjunta inconsistente (%.2e)", adj); }
        else if (!(chi2 <= CHI2_MAX)) { ok = false; reason = String.format(java.util.Locale.ROOT, "dados inconsistentes com a reconstrução: chi2 = %.2f > %.1f (registro, movimento ou modelo de câmera)", chi2, CHI2_MAX); }
        return new Result(x, p.H, p.W, it, chi2, adj, lambda, step, mu, ok, reason, mask);
    }

    static double powerIteration(Plan p, double[][] mask, int iterations) {
        Random rnd = new Random(7);
        double[] v = new double[p.H * p.W];
        for (int i = 0; i < v.length; i++) v[i] = rnd.nextDouble();
        double[] av = new double[p.h * p.w], out = new double[v.length];
        double lambda = 0;
        for (int t = 0; t < iterations; t++) {
            double norm = 0;
            for (double d : v) norm += d * d;
            norm = Math.sqrt(norm);
            if (norm == 0) return 1;
            for (int i = 0; i < v.length; i++) v[i] /= norm;
            Arrays.fill(out, 0);
            for (int k = 0; k < p.n; k++) {
                p.forward(k, v, av);
                for (int i = 0; i < av.length; i++) av[i] *= mask[k][i];
                p.adjointAdd(k, av, out);
            }
            double dot = 0;
            for (int i = 0; i < v.length; i++) dot += v[i] * out[i];
            lambda = dot;
            double[] swap = v; v = out; out = swap;
        }
        return Math.max(lambda, 1e-9);
    }
}
