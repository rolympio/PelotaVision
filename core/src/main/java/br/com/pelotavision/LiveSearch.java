package br.com.pelotavision;

import org.opencv.core.Mat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * 0.16.0: busca rápida do ajuste com a cena parada — substitui a busca genética como padrão.
 *
 * Por que não o genético: o espaço é pequeno e estruturado (2–3 métodos × 4 perfis × 21 sensibilidades) e a
 * aptidão quase sempre é unimodal na sensibilidade. O genético gasta ~30–48 avaliações sorteando pontos que uma
 * busca ordenada não precisa visitar, e avalia uma por vez.
 *
 * Estratégia (determinística, mesma aptidão do {@link LiveTuner}):
 *  1. Triagem: cada combinação método × perfil na sensibilidade atual (8, ou 12 com a rede), em paralelo;
 *  2. Refino: nas {@link #KEEP} melhores combinações, busca de padrão na sensibilidade com passos 20 → 10 → 5
 *     (vizinhos ± passo avaliados em paralelo); move para o vizinho melhor, senão reduz o passo.
 *  3. Orçamento de tempo opcional: devolve o melhor até ali.
 *
 * As avaliações rodam em paralelo (núcleos − 1, até 4). A rede neural (um Net do OpenCV por modelo) é avaliada
 * em série. Com {@link #frames} os quadros são recortados UMA vez e o espaço de escala do LoG é reaproveitado
 * entre sensibilidades do mesmo perfil ({@link PelletDetector.Prepared}): a sensibilidade só muda o limiar.
 */
public final class LiveSearch {
    private LiveSearch() {}

    public static final int KEEP = 2;
    public static final String STRATEGY = "two_stage_parallel_v1";
    private static final Object NEURAL_LOCK = new Object();

    public static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    }

    public static LiveTuner.Run search(DetectionTuner.Option start, DetectionAssessment.Context ctx, LiveTuner.Evaluator ev,
                                       BooleanSupplier cancelled, LiveTuner.Progress progress, int threads, long budgetMs) {
        long t0 = System.nanoTime();
        int[] methods = LiveTuner.methods(start);
        Map<String, LiveTuner.Candidate> cache = Collections.synchronizedMap(new LinkedHashMap<>());
        int[] maxValid = {1};
        ExecutorService pool = threads > 1 ? Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(r, "pv-busca"); t.setDaemon(true); return t; }) : null;
        boolean[] stop = {false};
        int stage = 0;
        try {
            BooleanSupplier over = () -> cancelled.getAsBoolean() || (budgetMs > 0 && (System.nanoTime() - t0) / 1_000_000 >= budgetMs);
            // 1. Triagem.
            List<DetectionTuner.Option> screen = new ArrayList<>();
            screen.add(start);
            for (int m : methods) for (int p = 0; p < 4; p++) screen.add(new DetectionTuner.Option(p, start.sensitivity, m, start.belt));
            stage = 1;
            evaluate(screen, ev, ctx, cache, maxValid, pool, over, stop);
            report(progress, 1, cache, maxValid);
            // 2. Refino na sensibilidade das melhores combinações.
            List<LiveTuner.Candidate> ranked = ranked(cache, maxValid);
            List<String> combos = new ArrayList<>();
            List<int[]> current = new ArrayList<>();
            for (LiveTuner.Candidate c : ranked) {
                if (stop[0] || c.failed()) continue;
                String key = c.option.method + "_" + c.option.profile;
                if (combos.contains(key)) continue;
                combos.add(key);
                current.add(new int[]{c.option.method, c.option.profile, c.option.sensitivity});
                if (combos.size() >= KEEP) break;
            }
            for (int step = 20; step >= 5 && !stop[0]; ) {
                stage++;
                List<DetectionTuner.Option> next = new ArrayList<>();
                for (int[] c : current) for (int s : new int[]{c[2] - step, c[2] + step})
                    if (s >= 0 && s <= 100) next.add(new DetectionTuner.Option(c[1], s, c[0], start.belt));
                evaluate(next, ev, ctx, cache, maxValid, pool, over, stop);
                boolean moved = false;
                List<LiveTuner.Candidate> all = ranked(cache, maxValid);
                for (int[] c : current) {
                    LiveTuner.Candidate here = cache.get(new DetectionTuner.Option(c[1], c[2], c[0], start.belt).id), best = here;
                    for (int s : new int[]{c[2] - step, c[2] + step}) {
                        if (s < 0 || s > 100) continue;
                        LiveTuner.Candidate n = cache.get(new DetectionTuner.Option(c[1], s, c[0], start.belt).id);
                        if (n != null && (best == null || n.fitness > best.fitness + .25)) best = n;
                    }
                    if (best != null && best != here) { c[2] = best.option.sensitivity; moved = true; }
                }
                report(progress, stage, cache, maxValid);
                if (!moved) step /= 2;
                if (all.isEmpty()) break;
            }
        } finally {
            if (pool != null) pool.shutdownNow();
        }
        List<LiveTuner.Candidate> all = ranked(cache, maxValid);
        LiveTuner.Candidate best = all.isEmpty() ? null : all.get(0);
        List<LiveTuner.Candidate> evaluated;
        synchronized (cache) { evaluated = new ArrayList<>(cache.values()); }
        return new LiveTuner.Run(best, evaluated, stage, cancelled.getAsBoolean(), (System.nanoTime() - t0) / 1_000_000, STRATEGY);
    }

    private static void evaluate(List<DetectionTuner.Option> options, LiveTuner.Evaluator ev, DetectionAssessment.Context ctx, Map<String, LiveTuner.Candidate> cache,
                                 int[] maxValid, ExecutorService pool, BooleanSupplier over, boolean[] stop) {
        List<DetectionTuner.Option> todo = new ArrayList<>();
        for (DetectionTuner.Option o : options) if (!cache.containsKey(o.id) && !containsId(todo, o.id)) todo.add(o);
        if (pool == null) {
            for (DetectionTuner.Option o : todo) {
                if (over.getAsBoolean() && !cache.isEmpty()) { stop[0] = true; return; }
                put(cache, maxValid, one(o, ev, ctx));
            }
            return;
        }
        List<Future<LiveTuner.Candidate>> fs = new ArrayList<>();
        for (DetectionTuner.Option o : todo) fs.add(pool.submit(() -> over.getAsBoolean() ? null : one(o, ev, ctx)));
        for (Future<LiveTuner.Candidate> f : fs) {
            try {
                LiveTuner.Candidate c = f.get();
                if (c == null) stop[0] = true; else put(cache, maxValid, c);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); stop[0] = true; return;
            } catch (ExecutionException e) {
                stop[0] = true;
            }
        }
        if (over.getAsBoolean()) stop[0] = true;
    }

    private static boolean containsId(List<DetectionTuner.Option> l, String id) {
        for (DetectionTuner.Option o : l) if (o.id.equals(id)) return true;
        return false;
    }

    private static LiveTuner.Candidate one(DetectionTuner.Option o, LiveTuner.Evaluator ev, DetectionAssessment.Context ctx) {
        try {
            List<PelletDetector.Result> frames;
            if (o.neural()) { synchronized (NEURAL_LOCK) { frames = ev.run(o); } } else frames = ev.run(o);
            return new LiveTuner.Candidate(o, frames, ctx);
        } catch (RuntimeException e) {
            return new LiveTuner.Candidate(o, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void put(Map<String, LiveTuner.Candidate> cache, int[] maxValid, LiveTuner.Candidate c) {
        synchronized (cache) {
            cache.put(c.option.id, c);
            for (DetectionAssessment.Metrics m : c.metrics) maxValid[0] = Math.max(maxValid[0], m.valid);
        }
    }

    private static List<LiveTuner.Candidate> ranked(Map<String, LiveTuner.Candidate> cache, int[] maxValid) {
        List<LiveTuner.Candidate> all;
        synchronized (cache) {
            all = new ArrayList<>(cache.values());
            for (LiveTuner.Candidate c : all) c.score(maxValid[0]);
        }
        all.sort((a, b) -> Double.compare(b.fitness, a.fitness));
        return all;
    }

    private static void report(LiveTuner.Progress progress, int stage, Map<String, LiveTuner.Candidate> cache, int[] maxValid) {
        if (progress == null) return;
        List<LiveTuner.Candidate> r = ranked(cache, maxValid);
        if (!r.isEmpty()) progress.update(stage, 0, r.get(0), r.size());
    }

    /**
     * Avaliador padrão sobre quadros já em cinza: recorta a ROI uma vez por quadro e reaproveita o espaço de
     * escala do LoG entre sensibilidades. Mesmo pipeline de {@link LiveFrameAnalysis#detect} sem filtro de luz
     * (lado 640 px, 8 escalas, elipses nos pixels originais, coordenadas globais).
     */
    public static final class Frames implements AutoCloseable {
        private final List<Mat> crops = new ArrayList<>();
        private final List<AnalysisWindow> windows = new ArrayList<>();
        private final Map<String, PelletDetector.Prepared> prepared = new ConcurrentHashMap<>();
        private final List<Mat> owned = new ArrayList<>();

        public Frames(List<Mat> grays, InterestRegion roi) {
            for (Mat g : grays) {
                AnalysisWindow w = roi == null ? null : new AnalysisWindow(g.cols(), g.rows(), roi);
                if (w != null && (w.width < 32 || w.height < 32)) w = null;
                Mat c = w == null ? g : w.cropGray(g);
                if (w != null) owned.add(c);
                crops.add(c);
                windows.add(w);
            }
        }

        public List<PelletDetector.Result> run(DetectionTuner.Option o) {
            List<PelletDetector.Result> out = new ArrayList<>();
            for (int i = 0; i < crops.size(); i++) {
                Mat c = crops.get(i);
                AnalysisWindow w = windows.get(i);
                PelletDetector.Result seeds;
                if (o.method == DetectionTuner.LOG) {
                    int side = o.parameters.workSide(LiveFrameAnalysis.WORK_SIDE);
                    String key = i + "|" + o.parameters.minRadiusFraction + "|" + o.parameters.maxRadiusFraction + "|" + o.belt + "|" + side;
                    PelletDetector.Prepared p = prepared.computeIfAbsent(key, k -> PelletDetector.prepare(c, o.parameters, side, LiveFrameAnalysis.SCALES));
                    seeds = p.detect(o.parameters);
                } else {
                    seeds = DetectionTuner.seeds(c, o, LiveFrameAnalysis.WORK_SIDE, LiveFrameAnalysis.SCALES);
                }
                PelletDetector.Result r = new EllipseFitter().refine(c, seeds, o.belt);
                out.add(w == null ? r : w.toGlobal(w.filterLocal(r)));
            }
            return out;
        }

        public int preparedCount() { return prepared.size(); }

        @Override public void close() {
            for (Mat m : owned) m.release();
            owned.clear();
            prepared.clear();
        }
    }
}
