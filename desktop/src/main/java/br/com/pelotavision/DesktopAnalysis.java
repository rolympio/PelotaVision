package br.com.pelotavision;

import org.opencv.core.Mat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Fluxo de analise de foto do Windows. Reproduz o caminho de MainActivity.processPhoto
 * do Android 0.11.0 (sem ROI e sem analise detalhada): busca de ajuste opcional,
 * deteccao + elipses, triagem de trincas e granulometria por numero.
 */
final class DesktopAnalysis {

    static final class Config {
        int profile = 3;            // 0 pequenas, 1 medias, 2 grandes, 3 auto (padrao Android)
        int sensitivity = 75;       // padrao Android
        boolean legacy = false;     // false = bordas/gradientes; true = LoG/segmentacao
        boolean autoSuggest = true; // padrao Android
        int crackSensitivity = 50;  // padrao Android
        double minimumExpectedMm = 5;
        Granulometry.Settings scale; // null = sem escala fisica (px)

        Config copy() {
            Config c = new Config();
            c.profile = profile; c.sensitivity = sensitivity; c.legacy = legacy; c.autoSuggest = autoSuggest;
            c.crackSensitivity = crackSensitivity; c.minimumExpectedMm = minimumExpectedMm; c.scale = scale;
            return c;
        }
    }

    static final class Output {
        final PelletDetector.Result result;
        final CrackAnalysis cracks;
        final Granulometry.Distribution distribution;
        final ShapeStatistics shape;
        final DetectionTuner.Option applied;
        final DetectionTuner.Report suggestions;
        final int originalWidth, originalHeight, sample;
        final long durationMs;
        final String crackModelNote;

        Output(PelletDetector.Result result, CrackAnalysis cracks, Granulometry.Distribution distribution, DetectionTuner.Option applied,
               DetectionTuner.Report suggestions, int ow, int oh, int sample, long ms, String note) {
            this.result = result; this.cracks = cracks; this.distribution = distribution; this.shape = ShapeStatistics.of(result);
            this.applied = applied; this.suggestions = suggestions; originalWidth = ow; originalHeight = oh; this.sample = sample;
            durationMs = ms; crackModelNote = note;
        }

        /** Recalcula so a granulometria (troca de escala/classe), sem refazer contornos. */
        Output withScale(Granulometry.Settings scale) {
            return new Output(result, cracks, distribution(result, scale, originalWidth, originalHeight), applied, suggestions,
                    originalWidth, originalHeight, sample, durationMs, crackModelNote);
        }
    }

    private static CrackModel crackModel;
    private static String crackNote;

    static synchronized CrackModel crackModel() {
        if (crackModel != null || crackNote != null) return crackModel;
        File file = DesktopApp.model("crack_forest.pvrf");
        try (InputStream in = new FileInputStream(file)) {
            crackModel = new CrackModel(in);
        } catch (IOException | RuntimeException e) {
            crackNote = "Modelo de trincas indisponivel (" + file + "): triagem heuristica.";
        }
        return crackModel;
    }

    /**
     * @param analyzed imagem BGR ja reduzida para analise (lado maior <= 3000 px)
     * @param ow,oh   dimensoes da imagem original (antes da reducao)
     */
    static Output analyze(Mat analyzed, int ow, int oh, int sample, Config cfg, BooleanSupplier cancelled, DetectionTuner.Progress progress) {
        long start = System.currentTimeMillis();
        Mat gray = Mats.gray(analyzed);
        try {
            AnalysisWindow window = new AnalysisWindow(gray.cols(), gray.rows(), null);
            window.requireDetectable();
            DetectionTuner.Option current = new DetectionTuner.Option(cfg.profile, cfg.sensitivity, cfg.legacy);
            DetectionTuner.Option selected = current;
            DetectionTuner.Report suggestions = null;
            PelletDetector.Result local;
            if (cfg.autoSuggest) {
                DetectionAssessment.Context context = new DetectionAssessment.Context(cfg.scale, ow, oh, null, cfg.minimumExpectedMm, window);
                suggestions = DetectionTuner.search(gray, current, context, cancelled, progress);
                DetectionTuner.Trial chosen = suggestions.cancelled ? suggestions.find(current.id) : suggestions.best();
                if (chosen != null && chosen.result != null) { selected = chosen.option; local = chosen.result; }
                else local = DetectionTuner.detect(gray, current, window);
            } else {
                local = DetectionTuner.detect(gray, current, window);
            }
            CrackModel model = crackModel();
            CrackAnalysis cracks = window.toGlobal(new CrackDetector(model).detect(gray, local, cfg.crackSensitivity));
            PelletDetector.Result result = window.toGlobal(local);
            Granulometry.Distribution distribution = distribution(result, cfg.scale, ow, oh);
            return new Output(result, cracks, distribution, selected, suggestions, ow, oh, sample, System.currentTimeMillis() - start,
                    model == null ? crackNote : "Random Forest experimental (crack_forest.pvrf), sem novo treinamento.");
        } finally {
            gray.release();
        }
    }

    static Granulometry.Distribution distribution(PelletDetector.Result result, Granulometry.Settings scale, int ow, int oh) {
        if (scale == null) return ReportTables.pixelHistogram(result);
        List<Granulometry.Item> items = new ArrayList<>();
        for (PelletDetector.Detection d : result.circles) items.add(new Granulometry.Item(2 * d.radius, d.partial, d.needsReview));
        return Granulometry.compute(items, scale, ow, oh, result.width, result.height);
    }

    /** Analise ao vivo, igual ao Android: lado maior 640 px, 8 escalas, sem busca e sem trincas. */
    static PelletDetector.Result live(Mat gray, Config cfg) {
        DetectionTuner.Option option = new DetectionTuner.Option(cfg.profile, cfg.sensitivity, cfg.legacy);
        PelletDetector.Result seeds = option.legacy ? new PelletDetector().detect(gray, option.parameters, 640, 8)
                : new AdaptivePelletDetector().detect(gray, option.parameters, 640, 8);
        return new EllipseFitter().refine(gray, seeds);
    }
}
