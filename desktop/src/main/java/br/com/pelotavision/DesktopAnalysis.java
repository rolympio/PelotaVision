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
        CrackClassifierChoice crackChoice = CrackClassifierChoice.DEFAULT; // Modelos de analise (0.5.5: MobileNetV3)
        ReportVisuals visuals = HistogramView.DEFAULTS; // mapa e grafico (gravados no relatorio)
        /** 0.5.3: base da curva Rosin–Rammler exibida e destacada no relatório (as duas vão para o JSON). */
        RosinRammler.Basis rrBasis = RosinRammler.Basis.NUMBER;
        /** 0.5.2: realce de imagem escura escolhido em "Luz / imagem escura…"; null = automático (como no Android). */
        LowLightPreprocessor.Mode lowLightMode;

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
        /** 0.5.2: tratamento de baixa iluminação desta análise (bloco low_light do JSON/PDF), ou null. */
        final LowLightInfo lowLight;
        /** 0.5.2: janela analisada já filtrada (window.width × window.height, 8 bits), só com realce; senão null. */
        final byte[] filteredGray;
        /** 0.5.2: relatório da segmentação detalhada (blocos nos pixels originais), ou null na análise rápida. */
        final DetailedAnalysis.Report detailed;

        Output(PelletDetector.Result result, CrackAnalysis cracks, DetectionTuner.Option applied, DetectionTuner.Report suggestions,
               AnalysisWindow window, int ow, int oh, int sample, long ms, String note) {
            this(result, cracks, applied, suggestions, window, ow, oh, sample, ms, note, null, null);
        }
        Output(PelletDetector.Result result, CrackAnalysis cracks, DetectionTuner.Option applied, DetectionTuner.Report suggestions,
               AnalysisWindow window, int ow, int oh, int sample, long ms, String note, LowLightInfo lowLight, byte[] filteredGray) {
            this(result, cracks, applied, suggestions, window, ow, oh, sample, ms, note, lowLight, filteredGray, null);
        }
        Output(PelletDetector.Result result, CrackAnalysis cracks, DetectionTuner.Option applied, DetectionTuner.Report suggestions,
               AnalysisWindow window, int ow, int oh, int sample, long ms, String note, LowLightInfo lowLight, byte[] filteredGray, DetailedAnalysis.Report detailed) {
            this.detailed = detailed;
            this.result = result; this.cracks = cracks; this.applied = applied; this.suggestions = suggestions; this.window = window;
            originalWidth = ow; originalHeight = oh; this.sample = sample; durationMs = ms; crackModelNote = note;
            this.lowLight = lowLight; this.filteredGray = lowLight != null && lowLight.enhanced() ? filteredGray : null;
        }

        Output withSuggestions(DetectionTuner.Report report) {
            return new Output(result, cracks, applied, report, window, originalWidth, originalHeight, sample, durationMs, crackModelNote, lowLight, filteredGray, detailed);
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

    /** Preferência (Preferences do MainFrame) e propriedade de sistema que ligam a aceleração por hardware. */
    static final String ACCEL_PREF = "hwAccel", ACCEL_PROPERTY = "pelotavision.accel";

    /** Registra o ONNX Runtime como motor da plataforma e aplica a escolha salva (padrão: desligada). */
    static void configureAcceleration() {
        try {
            HardwareAcceleration.setProvider(OnnxRuntimeProvider.INSTANCE);
        } catch (Throwable e) { // jar do ONNX Runtime ausente: só o OpenCV DNN
            HardwareAcceleration.setProvider(null);
        }
        boolean on = Boolean.getBoolean(ACCEL_PROPERTY);
        if (!on) {
            try { on = java.util.prefs.Preferences.userNodeForPackage(MainFrame.class).getBoolean(ACCEL_PREF, false); }
            catch (Throwable ignored) {}
        }
        HardwareAcceleration.setEnabled(on);
    }

    /** Liga ou desliga a aceleração e recarrega as redes no motor escolhido. Devolve o estado por modelo. */
    static synchronized String setAcceleration(boolean on) {
        HardwareAcceleration.setEnabled(on);
        File onnx = DesktopApp.model(NeuralPelletDetector.MODEL), meta = DesktopApp.model(NeuralPelletDetector.METADATA);
        NeuralPelletDetector.install(onnx, readOrEmpty(meta));
        File beltOnnx = DesktopApp.model(NeuralPelletDetector.BELT_MODEL), beltMeta = DesktopApp.model(NeuralPelletDetector.BELT_METADATA);
        if (beltOnnx.isFile()) NeuralPelletDetector.installBelt(beltOnnx, readOrEmpty(beltMeta));
        if (network != null) {
            network.close();
            network = null;
            File n = DesktopApp.model(MobileNetCrackModel.MODEL), m = DesktopApp.model(MobileNetCrackModel.METADATA);
            network = MobileNetCrackModel.load(n, m.isFile() ? readOrEmpty(m) : "");
        }
        return HardwareAcceleration.status();
    }

    /** Instala as redes StarDist (bancada e, em slot separado, correia) da pasta modelos (pacote) ou pelotavision.modelDir (testes). */
    static synchronized void installNeuralModel() {
        if (HardwareAcceleration.provider() == null) configureAcceleration();
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
        String base = crackModel() == null ? crackNote : "Random Forest (reserva, crack_forest.pvrf).";
        if (r == null || r.effective == CrackClassifierChoice.RANDOM_FOREST) return r != null && r.message != null ? r.message : base;
        return r.effective == CrackClassifierChoice.MOBILENET ? "MobileNetV3 (padrao); melhor que o RF em dois testes independentes, ainda sem validacao em producao." : base + " Comparacao com MobileNetV3 registrada (status do RF).";
    }

    private static MobileNetCrackModel network;
    static volatile CrackClassifierChoice.Resolution lastResolution;

    /** Rede carregada so quando escolhida; liberada quando o RF volta a ser usado. */
    static synchronized CrackDetector crackDetector(CrackClassifierChoice requested) {
        if (requested == null) requested = CrackClassifierChoice.DEFAULT;
        if (requested.needsNetwork()) {
            if (network == null) {
                File onnx = DesktopApp.model(MobileNetCrackModel.MODEL), meta = DesktopApp.model(MobileNetCrackModel.METADATA);
                network = MobileNetCrackModel.load(onnx, meta.isFile() ? readOrEmpty(meta) : "");
            }
        } else if (network != null) { network.close(); network = null; }
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
            LowLightPreprocessor.Quality quality = LowLightPreprocessor.assess(gray);
            LowLightInfo light = LowLightInfo.notApplied(quality, region == null ? "low-light recovery runs only on a selected ROI" : "illumination adequate; original kept");
            // Igual ao Android: só numa ROI; automático quando a imagem precisa, ou a escolha manual do usuário.
            if (region != null && (cfg.lowLightMode != null || quality.needsCorrection())) {
                if (cfg.lowLightMode == null) {
                    DetectionAssessment.Context lowContext = new DetectionAssessment.Context(cfg.scale, ow, oh, null, cfg.minimumExpectedMm, window);
                    LowLightRecovery.Result recovered = LowLightRecovery.analyze(gray, selected, window, lowContext);
                    local = recovered.pellets;
                    light = LowLightInfo.automatic(recovered);
                } else if (cfg.lowLightMode == LowLightPreprocessor.Mode.ORIGINAL) {
                    light = LowLightInfo.manual(LowLightPreprocessor.Mode.ORIGINAL, quality);
                } else {
                    light = LowLightInfo.manual(cfg.lowLightMode, quality);
                    Mat enhanced = LowLightPreprocessor.apply(gray, cfg.lowLightMode);
                    try {
                        PelletDetector.Result seeds = DetectionTuner.seeds(enhanced, selected, selected.parameters.workSide(1024), 12);
                        local = window.filterLocal(new EllipseFitter().refine(gray, seeds, selected.belt));
                    } finally {
                        enhanced.release();
                    }
                }
            }
            CrackAnalysis cracks = window.toGlobal(crackDetector(cfg.crackChoice).detect(gray, local, cfg.crackSensitivity));
            PelletDetector.Result result = window.toGlobal(local);
            return new Output(result, cracks, selected, suggestions, window, ow, oh, sample, System.currentTimeMillis() - start, crackModelNote(),
                    light, filteredGray(gray, light));
        } finally {
            if (gray != null && gray != full) gray.release();
            full.release();
        }
    }

    /** Janela cinza com o realce escolhido, para exibição e relatório; null sem realce ou se falhar. Nunca é medida. */
    static byte[] filteredGray(Mat windowGray, LowLightInfo light) {
        if (light == null || !light.enhanced()) return null;
        Mat f = null;
        try {
            f = LowLightPreprocessor.apply(windowGray, light.mode);
            if (f.cols() != windowGray.cols() || f.rows() != windowGray.rows() || f.channels() != 1) return null;
            byte[] out = new byte[f.cols() * f.rows()];
            f.get(0, 0, out);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (f != null) f.release();
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
            LowLightInfo light = LowLightInfo.notApplied(LowLightPreprocessor.assess(gray), "result reused from suggestion search");
            return new Output(window.toGlobal(trial.result), cracks, trial.option, report, window, ow, oh, sample, System.currentTimeMillis() - start, crackModelNote(), light, null);
        } finally {
            if (gray != null && gray != full) gray.release();
            full.release();
        }
    }

    /** Analise ao vivo, igual ao Android: lado maior 640 px, 8 escalas, sem busca e sem trincas. */
    static PelletDetector.Result live(Mat gray, Config cfg) { return liveOption(gray, cfg.option()); }

    static PelletDetector.Result liveOption(Mat gray, DetectionTuner.Option option) {
        PelletDetector.Result seeds = DetectionTuner.seeds(gray, option, 640, 8);
        return new EllipseFitter().refine(gray, seeds, option.belt);
    }

    /**
     * 0.5.4: contagem ao vivo só na ROI. O recorte é analisado no mesmo lado de trabalho (640 px), então a ROI
     * ganha resolução; pelotas que cruzam o limite ficam fora (como na foto). Coordenadas no quadro inteiro.
     */
    static PelletDetector.Result live(Mat gray, Config cfg, InterestRegion roi) { return live(gray, cfg.option(), roi); }

    /** 0.5.5: mesma contagem rápida com um ajuste explícito (busca genética com a cena parada). */
    static PelletDetector.Result live(Mat gray, DetectionTuner.Option option, InterestRegion roi) {
        if (roi == null) return liveOption(gray, option);
        AnalysisWindow w = new AnalysisWindow(gray.cols(), gray.rows(), roi);
        if (w.width < 32 || w.height < 32) return liveOption(gray, option);
        Mat crop = w.cropGray(gray);
        try {
            return w.toGlobal(w.filterLocal(liveOption(crop, option)));
        } finally {
            crop.release();
        }
    }
}
