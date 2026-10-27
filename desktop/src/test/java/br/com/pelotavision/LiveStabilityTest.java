package br.com.pelotavision;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** 0.5.5: ao vivo com a cena parada — movimento, consenso temporal (fantasmas) e busca genética. Java puro. */
public class LiveStabilityTest {

    static PelletDetector.Detection pellet(double x, double y, double r) {
        PelletDetector.Detection seed = new PelletDetector.Detection(x, y, r, Math.PI * r * r, 1, false, false);
        return new PelletDetector.Detection(seed, x, y, r * 1.04, r / 1.04, 30, true, .9, .01, false, false);
    }

    /** 20 pelotas reais com tremor de subpixel + {@code ghosts} fantasmas novos por quadro em lugares aleatórios. */
    static PelletDetector.Result frame(Random rnd, int ghosts, double jitter) {
        List<PelletDetector.Detection> d = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double x = 60 + (i % 5) * 90, y = 60 + (i / 5) * 90;
            d.add(pellet(x + rnd.nextGaussian() * jitter, y + rnd.nextGaussian() * jitter, 30 + (i % 3) + rnd.nextGaussian() * jitter * .3));
        }
        for (int g = 0; g < ghosts; g++) d.add(pellet(20 + rnd.nextDouble() * 460, 20 + rnd.nextDouble() * 360, 8 + rnd.nextDouble() * 6));
        java.util.Collections.shuffle(d, rnd);
        return new PelletDetector.Result(d, 500, 400, d.size(), 1, false, "test");
    }

    @Test public void motionNeedsSeveralQuietTicksAndHasHysteresis() {
        LiveMotion m = new LiveMotion();
        byte[] a = new byte[96 * 54];
        Arrays.fill(a, (byte) 100);
        assertEquals(LiveMotion.State.MOVING, m.update(a, 0)); // sem quadro anterior
        for (int i = 1; i < LiveMotion.STILL_TICKS; i++) assertNotEquals(LiveMotion.State.STILL, m.update(a, i * 100));
        assertEquals(LiveMotion.State.STILL, m.update(a, 500));
        assertEquals(500, m.stillSince());
        // Ruído entre os limiares não tira do estado parado.
        byte[] noisy = a.clone();
        for (int i = 0; i < noisy.length; i += 2) noisy[i] = (byte) 102;
        double d = LiveMotion.meanAbsDiff(a, noisy);
        assertTrue(d > 0 && d < LiveMotion.MOVING);
        assertEquals(LiveMotion.State.STILL, m.update(noisy, 600));
        // Mão entrando no quadro: movimento.
        byte[] hand = a.clone();
        for (int i = 0; i < hand.length / 3; i++) hand[i] = (byte) 20;
        assertEquals(LiveMotion.State.MOVING, m.update(hand, 700));
        assertEquals(-1, m.stillSince());
    }

    @Test public void consensusKeepsRealPelletsAndDropsGhosts() {
        Random rnd = new Random(5);
        LiveConsensus c = new LiveConsensus();
        PelletDetector.Result last = null;
        List<Double> rawCounts = new ArrayList<>();
        for (int f = 0; f < 15; f++) {
            PelletDetector.Result raw = frame(rnd, 4, .6);
            rawCounts.add((double) raw.circles.size());
            last = c.update(raw);
        }
        assertEquals("pelotas confirmadas", 20, last.circles.size());
        for (PelletDetector.Detection d : last.circles) assertTrue("fantasma confirmado r=" + d.radius, d.radius > 25);
        assertTrue("fantasmas descartados " + c.ghostsDiscarded(), c.ghostsDiscarded() >= 20);
        double[] sd = c.stableCountStats();
        assertEquals(20, sd[0], 1e-9);
        assertEquals(0, sd[1], 1e-9);
        assertTrue("contagem crua oscila", LiveConsensus.meanSd(rawCounts)[0] > 23);
        assertTrue(last.method.endsWith("temporal_consensus_v1"));
        // Medoide: geometria de um quadro real, perto da posição verdadeira.
        for (PelletDetector.Detection d : last.circles) {
            double gx = Math.round((d.x - 60) / 90.0) * 90 + 60, gy = Math.round((d.y - 60) / 90.0) * 90 + 60;
            assertTrue(Math.hypot(d.x - gx, d.y - gy) < 2);
        }
        c.reset();
        assertEquals(0, c.update(frame(rnd, 0, .6)).circles.size()); // um quadro só não confirma
    }

    @Test public void agreementPenalizesFlickeringDetections() {
        Random rnd = new Random(9);
        List<PelletDetector.Result> clean = Arrays.asList(frame(rnd, 0, .5), frame(rnd, 0, .5), frame(rnd, 0, .5));
        List<PelletDetector.Result> ghosty = Arrays.asList(frame(rnd, 8, .5), frame(rnd, 8, .5), frame(rnd, 8, .5));
        double[] a = LiveConsensus.agreement(clean), b = LiveConsensus.agreement(ghosty);
        assertEquals(1, a[0], 1e-9);
        assertTrue("concordancia com fantasmas " + b[0], b[0] < .8);
        assertTrue("tremor do raio " + a[1], a[1] < .05);
    }

    /** Paisagem conhecida: LoG · perfil 1 · sens. 80 dá 20 pelotas firmes; longe disso, fantasmas e perdas. */
    @Test public void geneticSearchConvergesToStableSettingAndIsReproducible() {
        DetectionTuner.Option start = new DetectionTuner.Option(3, 40, DetectionTuner.GRADIENT, false);
        DetectionAssessment.Context ctx = new DetectionAssessment.Context(null, 500, 400, null, 0);
        LiveTuner.Evaluator ev = o -> {
            double dist = Math.abs(o.sensitivity - 80) / 20.0 + (o.method == DetectionTuner.LOG ? 0 : 1.2) + (o.profile == 1 ? 0 : .8);
            Random rnd = new Random(o.id.hashCode());
            List<PelletDetector.Result> out = new ArrayList<>();
            for (int f = 0; f < 3; f++) {
                PelletDetector.Result full = frame(rnd, (int) Math.round(3 * dist), .4 + .4 * dist);
                List<PelletDetector.Detection> keep = new ArrayList<>();
                for (PelletDetector.Detection d : full.circles) if (rnd.nextDouble() > Math.min(.5, .12 * dist)) keep.add(d);
                out.add(new PelletDetector.Result(keep, 500, 400, keep.size(), 1, false, "test"));
            }
            return out;
        };
        int[] calls = {0};
        LiveTuner.Run a = LiveTuner.evolve(start, ctx, o -> { calls[0]++; return ev.run(o); }, () -> false, null, 42);
        LiveTuner.Run b = LiveTuner.evolve(start, ctx, ev, () -> false, null, 42);
        assertEquals("reprodutivel", a.best.option.id, b.best.option.id);
        assertEquals(DetectionTuner.LOG, a.best.option.method);
        assertEquals(1, a.best.option.profile);
        assertTrue("sensibilidade " + a.best.option.sensitivity, Math.abs(a.best.option.sensitivity - 80) <= 10);
        assertTrue(a.best.agreement > .9);
        assertTrue("aptidao do melhor acima da inicial", a.best.fitness() > a.evaluated.get(0).fitness());
        assertTrue("avaliacoes " + calls[0], calls[0] <= LiveTuner.POPULATION * LiveTuner.GENERATIONS);
        assertEquals(calls[0], a.evaluated.size()); // cache: cada ajuste avaliado uma vez
    }

    @Test public void geneticSearchStopsWhenSceneMoves() {
        DetectionTuner.Option start = new DetectionTuner.Option(1, 75, DetectionTuner.LOG, false);
        DetectionAssessment.Context ctx = new DetectionAssessment.Context(null, 500, 400, null, 0);
        int[] calls = {0};
        LiveTuner.Run r = LiveTuner.evolve(start, ctx, o -> { calls[0]++; return Arrays.asList(frame(new Random(1), 0, .3), frame(new Random(2), 0, .3)); },
                () -> calls[0] >= 3, null, 1);
        assertTrue(r.cancelled);
        assertTrue(calls[0] <= 3);
    }
}
