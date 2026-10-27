package br.com.pelotavision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Consenso temporal do ao vivo (0.15.5): acompanha cada pelota entre quadros e só a mostra como estável
 * quando aparece na maioria dos quadros recentes. Detecções que surgem e somem ("fantasmas") são contadas,
 * mas não entram no resultado estável. A geometria exibida é a do quadro mais típico da pelota (medoide):
 * é uma detecção real, não uma elipse inventada pela média.
 *
 * Associação entre quadros: centros a menos de {@link #MATCH_CENTER}×raio e razão de raios em
 * [1/{@link #MATCH_RADIUS}, {@link #MATCH_RADIUS}]; pares mais próximos primeiro (guloso).
 */
public final class LiveConsensus {
    public static final double MATCH_CENTER = .40, MATCH_RADIUS = 1.30;
    /** Janela (quadros) e fração mínima de presença para estável. */
    public static final int WINDOW = 10, MIN_HITS = 3;
    public static final double MIN_PRESENCE = .6;

    private static final class Track {
        final boolean[] seen = new boolean[WINDOW];
        final List<PelletDetector.Detection> samples = new ArrayList<>();
        int hits, born;
        boolean everStable;
        PelletDetector.Detection last;

        int recentHits() { int n = 0; for (boolean b : seen) if (b) n++; return n; }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int frame, ghostsDiscarded;
    private int width, height;
    private String method = "live";
    private final double[] stableCounts = new double[WINDOW];

    public synchronized void reset() { tracks.clear(); frame = 0; ghostsDiscarded = 0; Arrays.fill(stableCounts, Double.NaN); }

    public LiveConsensus() { reset(); }

    /** Pares (i em a, j em b) da mesma pelota; guloso pela distância normalizada. */
    public static int[][] match(List<PelletDetector.Detection> a, List<PelletDetector.Detection> b) {
        List<double[]> cand = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            PelletDetector.Detection p = a.get(i);
            for (int j = 0; j < b.size(); j++) {
                PelletDetector.Detection q = b.get(j);
                double r = Math.max(p.radius, q.radius), ratio = Math.max(p.radius, q.radius) / Math.max(1e-9, Math.min(p.radius, q.radius));
                double d = Math.hypot(p.x - q.x, p.y - q.y) / Math.max(1e-9, r);
                if (d < MATCH_CENTER && ratio <= MATCH_RADIUS) cand.add(new double[]{d, i, j});
            }
        }
        cand.sort((u, v) -> Double.compare(u[0], v[0]));
        boolean[] ua = new boolean[a.size()], ub = new boolean[b.size()];
        List<int[]> out = new ArrayList<>();
        for (double[] c : cand) {
            int i = (int) c[1], j = (int) c[2];
            if (ua[i] || ub[j]) continue;
            ua[i] = ub[j] = true;
            out.add(new int[]{i, j});
        }
        return out.toArray(new int[0][]);
    }

    /** Novo quadro; devolve o resultado estável (pode ser vazio nos primeiros quadros). */
    public synchronized PelletDetector.Result update(PelletDetector.Result r) {
        width = r.width; height = r.height; method = r.method;
        int slot = frame % WINDOW;
        List<PelletDetector.Detection> last = new ArrayList<>();
        for (Track t : tracks) last.add(t.last);
        int[][] pairs = match(last, r.circles);
        boolean[] used = new boolean[r.circles.size()];
        for (Track t : tracks) t.seen[slot] = false;
        for (int[] p : pairs) {
            Track t = tracks.get(p[0]);
            PelletDetector.Detection d = r.circles.get(p[1]);
            t.seen[slot] = true; t.hits++; t.last = d;
            t.samples.add(d);
            if (t.samples.size() > WINDOW) t.samples.remove(0);
            used[p[1]] = true;
        }
        for (int j = 0; j < used.length; j++) {
            if (used[j]) continue;
            Track t = new Track();
            t.born = frame; t.hits = 1; t.last = r.circles.get(j); t.samples.add(t.last); t.seen[slot] = true;
            tracks.add(t);
        }
        frame++;
        // Expira quem não apareceu na janela inteira; o que nunca ficou estável conta como fantasma.
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (t.recentHits() == 0) { if (!t.everStable) ghostsDiscarded++; tracks.remove(i); }
        }
        PelletDetector.Result stable = stable();
        stableCounts[(frame - 1) % WINDOW] = stable.circles.size();
        return stable;
    }

    private boolean isStable(Track t) {
        int window = Math.min(WINDOW, frame - t.born);
        return t.hits >= MIN_HITS && t.recentHits() >= Math.ceil(MIN_PRESENCE * Math.min(WINDOW, Math.max(MIN_HITS, window)));
    }

    /** Pelotas estáveis agora (medoide de cada trilha). */
    public synchronized PelletDetector.Result stable() {
        List<PelletDetector.Detection> out = new ArrayList<>();
        for (Track t : tracks) {
            if (!isStable(t)) continue;
            t.everStable = true;
            out.add(medoid(t.samples));
        }
        return new PelletDetector.Result(out, width, height, tracks.size(), 0, false, method + "+temporal_consensus_v1");
    }

    static PelletDetector.Detection medoid(List<PelletDetector.Detection> s) {
        if (s.size() <= 2) return s.get(s.size() - 1);
        int best = 0; double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < s.size(); i++) {
            double c = 0;
            for (PelletDetector.Detection q : s) {
                PelletDetector.Detection p = s.get(i);
                c += Math.hypot(p.x - q.x, p.y - q.y) + Math.abs(p.radius - q.radius) * 2;
            }
            if (c < bestCost) { bestCost = c; best = i; }
        }
        return s.get(best);
    }

    /** Quantos quadros já foram consolidados desde o último reinício. */
    public synchronized int frames() { return frame; }
    /** Trilhas que apareceram e sumiram sem ficar estáveis desde o reinício. */
    public synchronized int ghostsDiscarded() { return ghostsDiscarded; }
    /** Trilhas ainda instáveis agora (candidatas a fantasma). */
    public synchronized int unstableNow() { int n = 0; for (Track t : tracks) if (!isStable(t)) n++; return n; }

    /** Média e desvio-padrão da contagem estável na janela (NaN se < 3 quadros). */
    public synchronized double[] stableCountStats() {
        List<Double> v = new ArrayList<>();
        for (double c : stableCounts) if (Double.isFinite(c)) v.add(c);
        return meanSd(v);
    }

    /** Média e desvio-padrão amostral; NaN com menos de 3 valores. */
    public static double[] meanSd(List<Double> v) {
        if (v.size() < 3) return new double[]{Double.NaN, Double.NaN};
        double m = 0; for (double x : v) m += x / v.size();
        double s = 0; for (double x : v) s += (x - m) * (x - m);
        return new double[]{m, Math.sqrt(s / (v.size() - 1))};
    }

    /** Fração das detecções de cada quadro reencontradas nos outros (média dos pares) e tremor do raio. */
    public static double[] agreement(List<PelletDetector.Result> frames) {
        double agree = 0, pairs = 0;
        List<Double> jitter = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++)
            for (int j = 0; j < frames.size(); j++) {
                if (i == j) continue;
                List<PelletDetector.Detection> a = frames.get(i).circles, b = frames.get(j).circles;
                int[][] m = match(a, b);
                agree += a.isEmpty() ? (b.isEmpty() ? 1 : 0) : m.length / (double) a.size();
                pairs++;
                if (i < j) for (int[] p : m) {
                    double ra = a.get(p[0]).radius, rb = b.get(p[1]).radius;
                    jitter.add(Math.abs(ra - rb) / ((ra + rb) / 2));
                }
            }
        Collections.sort(jitter);
        double med = jitter.isEmpty() ? 0 : jitter.get(jitter.size() / 2);
        return new double[]{pairs == 0 ? 1 : agree / pairs, med};
    }
}
