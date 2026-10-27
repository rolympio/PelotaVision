package br.com.pelotavision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Busca genética do ajuste de detecção com a cena parada (0.15.5).
 *
 * Genes: método (bordas, LoG; rede neural só se o usuário a escolheu e o modelo existe), perfil de
 * tamanho (0..3) e sensibilidade (0..100, passo 5). População {@link #POPULATION}, até
 * {@link #GENERATIONS} gerações, elitismo {@link #ELITE}, torneio de 3, cruzamento uniforme e mutação
 * (sensibilidade ±N(0,12); perfil sorteado com p=0,25; método trocado com p=0,2). Para quando o
 * melhor não muda por {@link #PATIENCE} gerações.
 *
 * Aptidão (0..100, heurística fixada a priori, NÃO é acurácia): a mesma pontuação da busca na foto
 * (DetectionAssessment.score, com a concordância entre quadros como "estabilidade"), média em
 * {@code frames} quadros parados, menos penalidades de instabilidade: 25×(1−concordância),
 * 30×CV da contagem e 100×tremor mediano do raio (limitado a 0,2). Fantasmas aparecem num quadro e
 * somem no outro, então baixam a concordância.
 */
public final class LiveTuner {
    private LiveTuner() {}

    public static final int POPULATION = 8, GENERATIONS = 6, ELITE = 2, PATIENCE = 2;

    /** Detecta nos quadros parados com um ajuste (um resultado por quadro). */
    public interface Evaluator { List<PelletDetector.Result> run(DetectionTuner.Option option); }

    public interface Progress { void update(int generation, int generations, Candidate best, int evaluated); }

    public static final class Candidate {
        public final DetectionTuner.Option option;
        public final List<DetectionAssessment.Metrics> metrics;
        public final double agreement, countCv, jitter, meanCount;
        public final boolean limit;
        double fitness;
        final String error;

        Candidate(DetectionTuner.Option option, List<PelletDetector.Result> frames, DetectionAssessment.Context ctx) {
            this.option = option;
            metrics = new ArrayList<>();
            boolean lim = false;
            List<Double> counts = new ArrayList<>();
            for (PelletDetector.Result r : frames) {
                metrics.add(DetectionAssessment.measure(r, ctx));
                lim |= r.candidateLimit;
                counts.add((double) r.circles.size());
            }
            limit = lim;
            double[] ag = LiveConsensus.agreement(frames);
            agreement = ag[0];
            jitter = ag[1];
            double m = 0; for (double c : counts) m += c / counts.size();
            double s = 0; for (double c : counts) s += (c - m) * (c - m);
            meanCount = m;
            countCv = m <= 0 ? 1 : Math.sqrt(s / Math.max(1, counts.size() - 1)) / m;
            error = null;
        }

        Candidate(DetectionTuner.Option option, String error) {
            this.option = option; this.error = error; metrics = Collections.emptyList();
            agreement = 0; countCv = 1; jitter = 1; meanCount = 0; limit = false; fitness = 0;
        }

        public double fitness() { return fitness; }
        public boolean failed() { return error != null; }

        void score(int maxValid) {
            if (error != null || metrics.isEmpty()) { fitness = 0; return; }
            double base = 0;
            for (DetectionAssessment.Metrics m : metrics) base += DetectionAssessment.score(m, maxValid, agreement, limit) / metrics.size();
            fitness = Math.max(0, Math.min(100, base - 25 * (1 - agreement) - 30 * countCv - 100 * Math.min(.2, jitter)));
        }

        public String summary() {
            return String.format(java.util.Locale.ROOT, "%s · aptidao %.1f · concordancia %.0f%% · n %.0f", option.label(), fitness, 100 * agreement, meanCount);
        }
    }

    public static final class Run {
        public final Candidate best;
        public final List<Candidate> evaluated;
        public final int generations;
        public final boolean cancelled;
        public final long durationMs;
        Run(Candidate best, List<Candidate> evaluated, int generations, boolean cancelled, long ms) {
            this.best = best; this.evaluated = Collections.unmodifiableList(evaluated); this.generations = generations; this.cancelled = cancelled; durationMs = ms;
        }
    }

    private static int[] methods(DetectionTuner.Option start) {
        boolean neural = start.neural() && NeuralPelletDetector.available(start.belt);
        return neural ? new int[]{DetectionTuner.GRADIENT, DetectionTuner.LOG, DetectionTuner.NEURAL} : new int[]{DetectionTuner.GRADIENT, DetectionTuner.LOG};
    }

    static DetectionTuner.Option random(Random rng, int[] methods, boolean belt) {
        return new DetectionTuner.Option(rng.nextInt(4), 5 * rng.nextInt(21), methods[rng.nextInt(methods.length)], belt);
    }

    static DetectionTuner.Option mutate(DetectionTuner.Option o, Random rng, int[] methods) {
        int sens = (int) Math.max(0, Math.min(100, Math.round((o.sensitivity + rng.nextGaussian() * 12) / 5.0) * 5));
        int profile = rng.nextDouble() < .25 ? rng.nextInt(4) : o.profile;
        int method = rng.nextDouble() < .2 ? methods[rng.nextInt(methods.length)] : o.method;
        return new DetectionTuner.Option(profile, sens, method, o.belt);
    }

    static DetectionTuner.Option cross(DetectionTuner.Option a, DetectionTuner.Option b, Random rng) {
        return new DetectionTuner.Option(rng.nextBoolean() ? a.profile : b.profile, rng.nextBoolean() ? a.sensitivity : b.sensitivity,
                rng.nextBoolean() ? a.method : b.method, a.belt);
    }

    /**
     * @param start ajuste atual (entra na população inicial e serve de referência)
     * @param seed  semente fixa: a mesma cena dá a mesma busca (reprodutível)
     */
    public static Run evolve(DetectionTuner.Option start, DetectionAssessment.Context ctx, Evaluator ev, BooleanSupplier cancelled,
                             Progress progress, long seed) {
        long t0 = System.nanoTime();
        Random rng = new Random(seed);
        int[] methods = methods(start);
        Map<String, Candidate> cache = new LinkedHashMap<>();
        int[] maxValid = {1};
        java.util.function.Function<DetectionTuner.Option, Candidate> eval = o -> cache.computeIfAbsent(o.id, k -> {
            Candidate c;
            try {
                c = new Candidate(o, ev.run(o), ctx);
                for (DetectionAssessment.Metrics m : c.metrics) maxValid[0] = Math.max(maxValid[0], m.valid);
            } catch (RuntimeException e) {
                c = new Candidate(o, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return c;
        });
        List<DetectionTuner.Option> pop = new ArrayList<>();
        pop.add(start);
        // Ponto de partida conhecido da busca na foto (bordas e LoG, perfil médio e auto), depois aleatórios.
        for (int[] s : new int[][]{{1, 75, DetectionTuner.LOG}, {3, 75, DetectionTuner.GRADIENT}, {1, 75, DetectionTuner.GRADIENT}})
            pop.add(new DetectionTuner.Option(s[0], s[1], s[2], start.belt));
        while (pop.size() < POPULATION) pop.add(random(rng, methods, start.belt));
        Candidate best = null;
        int still = 0, gen = 0;
        boolean stop = false;
        for (gen = 1; gen <= GENERATIONS && !stop; gen++) {
            List<Candidate> scored = new ArrayList<>();
            for (DetectionTuner.Option o : pop) {
                if (cancelled.getAsBoolean()) { stop = true; break; }
                scored.add(eval.apply(o));
            }
            for (Candidate c : cache.values()) c.score(maxValid[0]);
            scored.sort((a, b) -> Double.compare(b.fitness, a.fitness));
            if (scored.isEmpty()) break;
            Candidate genBest = Collections.max(cache.values(), (a, b) -> Double.compare(a.fitness, b.fitness));
            still = best != null && best.option.id.equals(genBest.option.id) ? still + 1 : 0;
            best = genBest;
            if (progress != null) progress.update(gen, GENERATIONS, best, cache.size());
            if (stop || still >= PATIENCE) break;
            List<DetectionTuner.Option> next = new ArrayList<>();
            for (int i = 0; i < Math.min(ELITE, scored.size()); i++) next.add(scored.get(i).option);
            int guard = 0;
            while (next.size() < POPULATION && guard++ < 200) {
                DetectionTuner.Option a = tournament(scored, rng), b = tournament(scored, rng);
                DetectionTuner.Option child = mutate(cross(a, b, rng), rng, methods);
                boolean dup = false;
                for (DetectionTuner.Option o : next) if (o.id.equals(child.id)) { dup = true; break; }
                if (!dup) next.add(child);
            }
            pop = next;
        }
        for (Candidate c : cache.values()) c.score(maxValid[0]);
        if (!cache.isEmpty()) best = Collections.max(cache.values(), (a, b) -> Double.compare(a.fitness, b.fitness));
        return new Run(best, new ArrayList<>(cache.values()), Math.min(gen, GENERATIONS), cancelled.getAsBoolean(), (System.nanoTime() - t0) / 1_000_000);
    }

    private static DetectionTuner.Option tournament(List<Candidate> scored, Random rng) {
        Candidate best = null;
        for (int i = 0; i < 3; i++) {
            Candidate c = scored.get(rng.nextInt(scored.size()));
            if (best == null || c.fitness > best.fitness) best = c;
        }
        return best.option;
    }
}
