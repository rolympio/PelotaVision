package br.com.pelotavision;

import org.opencv.core.Mat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * Fluxo de analise de foto do Windows. Reproduz MainActivity.processPhoto do Android
 * 0.11.0: ROI como entrada efetiva (AnalysisWindow), busca de ajuste opcional,
 * deteccao + elipses, triagem de trincas. A granulometria e calculada por PhotoSession.
 */
final class DesktopAnalysis {

    static final class Config {
        int profile = 3;            // 0 pequenas, 1 medias, 2 grandes, 3 auto (padrao Android)
        int sensitivity = 75;       // padrao Android
        int method = DetectionTuner.GRADIENT; // GRADIENT bordas, LOG segmentacao, NEURAL StarDist
        boolean belt = false;       // cena de correia (pelotas pequenas e numerosas)
        boolean autoSuggest = true; // padrao Android
        int crackSensitivity = 50;  // padrao Android
        double minimumExpectedMm = 5;
        Granulometry.Settings scale; // escala vigente (entra no contexto da busca, como no Android)
        CrackClassifierChoice crackChoice = CrackClassifierChoice.RANDOM_FOREST; // Modelos de analise
        ReportVisuals visuals = HistogramView.DEFAULTS; // mapa e grafico (gravados no relatorio)

        /** O metodo neural so vale com o modelo instalado; sem ele, volta para bordas. */
        int effectiveMethod() { return method == DetectionTuner.NEURAL && !NeuralPelletDetector.available(belt) ? DetectionTuner.GRADIENT : method; }
        DetectionTuner.Option option() { return new DetectionTuner.Option(profile, sensitivity, effectiveMethod(), belt); }
    }

    static final class Output {
        final PelletDetector.Result result;   // coordenadas da imagem analisada inteira
        final CrackAnalysis cracks;           // mesma ordem de result.circles
        final DetectionTuner.Option applied;
        final DetectionTuner.Report suggestions; // null quando nao houve busca
        final AnalysisWindow window;          // escopo (ROI) desta analise
        final int originalWidth, originalHeight, sample;
        final long durationMs;
        final String crackModelNote;

        Output(PelletDetector.Result result, CrackAnalysis cracks, DetectionTuner.Option applied, DetectionTuner.Report suggestions,
               AnalysisWindow window, int ow, int oh, int sample, long ms, String note) {
            this.result = result; this.cracks = cracks; this.applied = applied; this.suggestions = suggestions; this.window = window;
            originalWidth = ow; originalHeight = oh; this.sample = sample; durationMs = ms; crackModelNote = note;
        }

        Output withSuggestions(DetectionTuner.Report report) {
            return new Output(result, cracks, applied, report, window, originalWidth, originalHeight, sample, durationMs, crackModelNote);
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

    /** Instala as redes StarDist (bancada e, em slot separado, correia) da pasta modelos (pacote) ou pelotavision.modelDir (testes). */
    static synchronized void installNeuralModel() {
        if (!NeuralPelletDetector.available()) {
            File onnx = DesktopApp.model(NeuralPelletDetector.MODEL), meta = DesktopApp.model(NeuralPelletDetector.METADATA);
            NeuralPelletDetector.install(onnx, readOrEmpty(meta));
        }
        if (NeuralPelletDetector.getBelt() == null) {
            File onnx = DesktopApp.model(NeuralPelletDetector.BELT_MODEL), meta = DesktopApp.model(NeuralPelletDetector.BELT_METADATA);
            NeuralPelletDetector.installBelt(onnx, readOrEmpty(meta)); // ausente: correia + rede usa o modelo de bancada (com aviso)
        }
    }

    private static String readOrEmpty(File f) {
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException e) { return "{}"; }
    }

    static String crackModelNote() {
        CrackClassifierChoice.Resolution r = lastResolution;
        String base = crackModel() == null ? crackNote : "Random Forest experimental (crack_forest.pvrf), sem novo treinamento.";
        if (r == null || r.effective == CrackClassifierChoice.RANDOM_FOREST) return r != null && r.message != null ? r.message : base;
        return r.effective == CrackClassifierChoice.MOBILENET ? "MobileNetV3 experimental (nao validada); o RF continua o padrao." : base + " Comparacao com MobileNetV3 registrada (status do RF).";
    }

    private static MobileNetCrackModel network;
    static volatile CrackClassifierChoice.Resolution lastResolution;

    /** Rede carregada so quando escolhida; liberada quando o RF volta a ser usado. */
    static synchronized CrackDetector crackDetector(CrackClassifierChoice requested) {
        if (requested == null) requested = CrackClassifierChoice.RANDOM_FOREST;
        if (requested.needsNetwork()) {
            if (network == null) {
                File onnx = DesktopApp.model(MobileNetCrackModel.MODEL), meta = DesktopApp.model(MobileNetCrackModel.METADATA);
                network = MobileNetCrackModel.load(onnx, meta.isFile() ? readOrEmpty(meta) : "");
            }
        } else network = null;
        CrackClassifierChoice.Resolution r = CrackClassifierChoice.resolve(requested, crackModel() != null, network != null, MobileNetCrackModel.lastError);
        lastResolution = r;
        return new CrackDetector(crackModel(), r.effective.needsNetwork() ? network : null, r.effective);
    }
    static synchronized String networkId() { return network == null ? null : network.id; }

    /**
     * @param analyzed imagem BGR ja reduzida para analise (lado maior <= 3000 px)
     * @param region   ROI em coordenadas da imagem analisada, ou null
     * @param fixed    ajuste a aplicar sem busca (reanalise), ou null para seguir cfg
     */
    static Output analyze(Mat analyzed, int ow, int oh, int sample, InterestRegion region, Config cfg, DetectionTuner.Option fixed,
                          BooleanSupplier cancelled, DetectionTuner.Progress progress) {
        long start = System.currentTimeMillis();
        Mat full = Mats.gray(analyzed);
        Mat gray = null;
        try {
            AnalysisWindow window = new AnalysisWindow(full.cols(), full.rows(), region);
            window.requireDetectable();
            gray = window.region == null ? full : window.cropGray(full);
            DetectionTuner.Option current = fixed != null ? fixed : cfg.option();
            DetectionTuner.Option selected = current;
            DetectionTuner.Report suggestions = null;
            PelletDetector.Result local;
            if (fixed == null && cfg.autoSuggest) {
                DetectionAssessment.Context context = new DetectionAssessment.Context(cfg.scale, ow, oh, null, cfg.minimumExpectedMm, window);
                suggestions = DetectionTuner.search(gray, current, context, cancelled, progress);
                DetectionTuner.Trial chosen = suggestions.cancelled ? suggestions.find(current.id) : suggestions.best();
                if (chosen != null && chosen.result != null) { selected = chosen.option; local = chosen.result; }
                else local = DetectionTuner.detect(gray, current, window);
            } else {
                local = DetectionTuner.detect(gray, current, window);
            }
            if (region != null && LowLightPreprocessor.assess(gray).needsCorrection()) {
                DetectionAssessment.Context lowContext = new DetectionAssessment.Context(cfg.scale, ow, oh, null, cfg.minimumExpectedMm, window);
                local = LowLightRecovery.analyze(gray, selected, window, lowContext).pellets;
            }
            CrackAnalysis cracks = window.toGlobal(crackDetector(cfg.crackChoice).detect(gray, local, cfg.crackSensitivity));
            PelletDetector.Result result = window.toGlobal(local);
            return new Output(result, cracks, selected, suggestions, window, ow, oh, sample, System.currentTimeMillis() - start, crackModelNote());
        } finally {
            if (gray != null && gray != full) gray.release();
            full.release();
        }
    }

    /** Busca sob demanda ("Buscar melhor ajuste"): nao aplica nada, so devolve o relatorio. */
    static DetectionTuner.Report search(Mat analyzed, int ow, int oh, InterestRegion region, Config cfg, DetectionTuner.Option current,
                                        BooleanSupplier cancelled, DetectionTuner.Progress progress) {
        Mat full = Mats.gray(analyzed);
        Mat gray = null;
        try {
            AnalysisWindow window = new AnalysisWindow(full.cols(), full.rows(), region);
            window.requireDetectable();
            gray = window.region == null ? full : window.cropGray(full);
            DetectionAssessment.Context context = new DetectionAssessment.Context(cfg.scale, ow, oh, null, cfg.minimumExpectedMm, window);
            return DetectionTuner.search(gray, current, context, cancelled, progress);
        } finally {
            if (gray != null && gray != full) gray.release();
            full.release();
        }
    }

    /** Aplica uma opcao ja avaliada na busca (mesmos contornos), refazendo so a triagem de trincas. */
    static Output applyTrial(Mat analyzed, int ow, int oh, int sample, AnalysisWindow window, DetectionTuner.Report report,
                             DetectionTuner.Trial trial, int crackSensitivity) {
        long start = System.currentTimeMillis();
        Mat full = Mats.gray(analyzed);
        Mat gray = null;
        try {
            gray = window.region == null ? full : window.cropGray(full);
            CrackAnalysis cracks = window.toGlobal(crackDetector(lastResolution == null ? CrackClassifierChoice.RANDOM_FOREST : lastResolution.requested).detect(gray, trial.result, crackSensitivity));
            return new Output(window.toGlobal(trial.result), cracks, trial.option, report, window, ow, oh, sample, System.currentTimeMillis() - start, crackModelNote());
        } finally {
            if (gray != null && gray != full) gray.release();
            full.release();
        }
    }

    /** Analise ao vivo, igual ao Android: lado maior 640 px, 8 escalas, sem busca e sem trincas. */
    static PelletDetector.Result live(Mat gray, Config cfg) {
        DetectionTuner.Option option = cfg.option();
        PelletDetector.Result seeds = DetectionTuner.seeds(gray, option, 640, 8);
        return new EllipseFitter().refine(gray, seeds, option.belt);
    }
}
