package br.com.pelotavision;

import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ao vivo do Windows (0.5.5): detecta quando a cena para, roda a busca genética do ajuste (LiveTuner) em
 * quadros parados e, enquanto nada se mexer, mostra só as pelotas confirmadas pelo consenso temporal
 * (LiveConsensus), com indicadores de estabilidade. Qualquer movimento cancela a busca e reinicia o consenso.
 */
final class LiveStabilizer {
    /** Quadros parados usados na aptidão e intervalo entre eles. */
    static final int TUNE_FRAMES = 3;
    static final long TUNE_FRAME_GAP_MS = 180;

    private final LiveMotion motion = new LiveMotion();
    private final LiveConsensus consensus = new LiveConsensus();
    private final List<Double> d50 = new ArrayList<>();
    private volatile AtomicBoolean cancel;
    private volatile boolean tuning, tunedThisStill;
    private volatile LiveTuner.Run lastRun;
    private volatile String progress;
    /** "Otimizar quando parado": desligado, o ao vivo mostra o quadro cru (comportamento da 0.5.4). */
    volatile boolean enabled = true;
    /** 0.16.0: busca genética da 0.5.5 (true) ou busca rápida em duas etapas, paralela (false, padrão). */
    volatile boolean genetic = false;

    /** Miniatura 96 px de largura, desfocada, em tons de cinza (entrada do detector de movimento). */
    static byte[] thumbnail(Mat bgr) {
        Mat g = Mats.gray(bgr), s = new Mat();
        try {
            int w = 96, h = Math.max(8, (int) Math.round(96.0 * g.rows() / Math.max(1, g.cols())));
            Imgproc.resize(g, s, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(s, s, new Size(3, 3), 0);
            byte[] b = new byte[w * h];
            s.get(0, 0, b);
            return b;
        } finally {
            g.release();
            s.release();
        }
    }

    /** Atualiza o estado de movimento; ao voltar a mexer, cancela a busca e zera o consenso. */
    synchronized LiveMotion.State onThumb(byte[] thumb, long now) {
        LiveMotion.State before = motion.state(), after = motion.update(thumb, now);
        if (after == LiveMotion.State.MOVING && before != LiveMotion.State.MOVING) restart();
        return after;
    }

    synchronized void restart() {
        AtomicBoolean c = cancel;
        if (c != null) c.set(true);
        consensus.reset();
        d50.clear();
        tunedThisStill = false;
    }

    void resetAll() { motion.reset(); restart(); lastRun = null; progress = null; }

    boolean still() { return motion.state() == LiveMotion.State.STILL; }
    private boolean stabilizing() { return enabled && still(); }
    LiveMotion.State state() { return motion.state(); }
    boolean tuning() { return tuning; }
    boolean shouldTune(boolean enabled) { return enabled && still() && !tuning && !tunedThisStill; }
    LiveTuner.Run lastRun() { return lastRun; }
    String progress() { return progress; }

    /**
     * Busca genética nos quadros parados (já em cinza). Devolve null se cancelada por movimento.
     * Os quadros são liberados aqui.
     */
    LiveTuner.Run tune(List<Mat> grays, DetectionTuner.Option start, InterestRegion roi, DetectionAssessment.Context ctx, Runnable onProgress) {
        AtomicBoolean c = new AtomicBoolean(false);
        cancel = c;
        tuning = true;
        progress = "Cena parada: iniciando a busca do melhor ajuste…";
        try {
            LiveTuner.Run run;
            if (genetic) {
                run = LiveTuner.evolve(start, ctx, option -> {
                    List<PelletDetector.Result> out = new ArrayList<>();
                    for (Mat g : grays) out.add(DesktopAnalysis.live(g, option, roi));
                    return out;
                }, c::get, (gen, total, best, evaluated) -> {
                    progress = String.format(Locale.ROOT, "OTIMIZANDO (algoritmo genetico) geracao %d/%d · %d ajustes avaliados · melhor: %s", gen, total, evaluated, best.summary());
                    if (onProgress != null) onProgress.run();
                }, 20261027L + grays.get(0).cols());
            } else {
                // 0.16.0: triagem método × perfil e refino da sensibilidade, em paralelo; LoG reaproveita o espaço de escala.
                try (LiveSearch.Frames frames = new LiveSearch.Frames(grays, roi)) {
                    run = LiveSearch.search(start, ctx, frames::run, c::get, (stage, total, best, evaluated) -> {
                        progress = String.format(Locale.ROOT, "OTIMIZANDO (busca rapida) etapa %d · %d ajustes avaliados · melhor: %s", stage, evaluated, best.summary());
                        if (onProgress != null) onProgress.run();
                    }, LiveSearch.defaultThreads(), 0);
                }
            }
            if (c.get() || run.best == null) return null;
            synchronized (this) {
                lastRun = run;
                tunedThisStill = true;
                consensus.reset();
                d50.clear();
            }
            return run;
        } finally {
            tuning = false;
            for (Mat g : grays) g.release();
        }
    }

    /** Parado: consenso temporal; em movimento: o próprio quadro. */
    synchronized PelletDetector.Result consolidate(PelletDetector.Result raw) {
        if (!stabilizing()) return raw;
        return consensus.update(raw);
    }

    /** Registra o D50 do resultado estável exibido (para o ± na faixa). */
    synchronized void recordD50(double value) {
        if (!stabilizing() || !Double.isFinite(value)) return;
        d50.add(value);
        if (d50.size() > LiveConsensus.WINDOW) d50.remove(0);
    }

    /** Texto da faixa no estado atual. */
    synchronized String banner(PelletDetector.Result raw, PelletDetector.Result shown, Granulometry.Distribution dist, boolean inRoi) {
        String where = inRoi ? " · ROI" : "";
        if (tuning && progress != null) return progress;
        if (!stabilizing()) {
            String s = !enabled ? "quadro a quadro" : motion.state() == LiveMotion.State.SETTLING ? "aguardando parar" : "em movimento";
            return "AO VIVO" + where + " · " + s + ": " + raw.circles.size() + " propostas"
                    + (dist == null || dist.included == 0 ? "" : " · D50 " + HistogramView.fmt(dist.d50) + " " + dist.settings.unit())
                    + " (contagem rapida; trincas na foto)";
        }
        double[] n = consensus.stableCountStats(), d = LiveConsensus.meanSd(d50);
        long secs = motion.stillSince() < 0 ? 0 : (System.currentTimeMillis() - motion.stillSince()) / 1000;
        StringBuilder b = new StringBuilder("ESTAVEL").append(where).append(" · parado ha ").append(secs).append(" s · ")
                .append(shown.circles.size()).append(" pelotas confirmadas");
        if (Double.isFinite(n[1])) b.append(String.format(Locale.ROOT, " (±%.1f)", n[1]));
        if (dist != null && dist.included > 0) {
            b.append(" · D50 ").append(HistogramView.fmt(dist.d50)).append(' ').append(dist.settings.unit());
            if (Double.isFinite(d[1])) b.append(String.format(Locale.ROOT, " ±%.2f", d[1]));
        }
        b.append(" · fantasmas descartados ").append(consensus.ghostsDiscarded()).append(" · instaveis agora ").append(consensus.unstableNow());
        LiveTuner.Run r = lastRun;
        if (r != null && tunedThisStill) b.append(LiveSearch.STRATEGY.equals(r.strategy) ? " · ajuste (busca rapida): " : " · ajuste GA: ").append(r.best.option.label());
        else if (shown.circles.isEmpty()) b.append(" · confirmando (3 quadros)…");
        return b.toString();
    }
}
