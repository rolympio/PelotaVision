package br.com.pelotavision;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Segmentação detalhada no Windows (0.5.2), paridade com MainActivity.processDetailedPhoto do Android 0.11.0+:
 * lê a ROI nos pixels ORIGINAIS (antes da redução para análise), analisa em blocos sobrepostos com o mesmo
 * DetailedAnalysis do núcleo, opcionalmente compara contraste local / ruído leve e testa FSRCNN 2×.
 * Contornos e trincas usam os pixels originais; o resultado volta às coordenadas da imagem analisada.
 */
final class DesktopDetail {
    private DesktopDetail() {}

    /** Mesmo teto do Android (OriginalRoi.MAX_PIXELS): acima disso a leitura é reduzida em potências de dois. */
    static final long MAX_PIXELS = 8_000_000;

    static final class Options {
        boolean compare = true;   // contraste local e redução leve de ruído
        boolean search = true;    // pré-ajuste num bloco representativo
        boolean superResolution;  // FSRCNN 2× (experimental)
    }

    /** ROI lida nos pixels originais, em cinza, com o mapeamento para a imagem analisada. Deve ser liberada. */
    static final class Input implements AutoCloseable {
        final Mat gray; final DetailGeometry geometry;
        Input(Mat gray, DetailGeometry geometry) { this.gray = gray; this.geometry = geometry; }
        @Override public void close() { gray.release(); }
    }

    /** Lê a imagem original inteira (arquivo relido; webcam: o quadro capturado). BGR, deve ser liberada. */
    static Mat original(PhotoSession s) throws IOException {
        if (s.file != null) return Mats.readUpright(s.file);
        if (s.frame != null) return Mats.fromImage(s.frame);
        return s.analyzed.clone();
    }

    /**
     * Recorte da ROI (ou imagem inteira) em pixels originais. sx/sy/tx/ty levam pixels do recorte a pixels da
     * imagem analisada (mesma fórmula do OriginalRoi do Android).
     */
    static Input decode(Mat originalBgr, int analyzedWidth, int analyzedHeight, InterestRegion roi) {
        int ow = originalBgr.cols(), oh = originalBgr.rows();
        double fx = ow / (double) analyzedWidth, fy = oh / (double) analyzedHeight;
        int left = roi == null ? 0 : Math.max(0, (int) Math.floor(roi.left * fx + 1e-4));
        int top = roi == null ? 0 : Math.max(0, (int) Math.floor(roi.top * fy + 1e-4));
        int right = roi == null ? ow : Math.min(ow, (int) Math.ceil(roi.right * fx - 1e-4));
        int bottom = roi == null ? oh : Math.min(oh, (int) Math.ceil(roi.bottom * fy - 1e-4));
        if (right <= left || bottom <= top) throw new IllegalArgumentException("Área original vazia.");
        int cw = right - left, ch = bottom - top, sample = 1;
        while ((long) cw * ch / sample / sample > MAX_PIXELS) sample *= 2;
        Mat sub = originalBgr.submat(new Rect(left, top, cw, ch)), gray = null, reduced = null;
        try {
            gray = Mats.gray(sub);
            if (sample > 1) {
                reduced = new Mat();
                Imgproc.resize(gray, reduced, new Size(Math.max(1, cw / sample), Math.max(1, ch / sample)), 0, 0, Imgproc.INTER_AREA);
                gray.release();
                gray = reduced;
                reduced = null;
            }
            int w = gray.cols(), h = gray.rows();
            DetailGeometry map = new DetailGeometry(w, h, analyzedWidth, analyzedHeight,
                    cw / (double) w / fx, ch / (double) h / fy, left / fx, top / fy, sample, roi);
            map.mask(gray);
            Input in = new Input(gray, map);
            gray = null;
            return in;
        } finally {
            sub.release();
            if (gray != null) gray.release();
            if (reduced != null) reduced.release();
        }
    }

    /** Executa a análise detalhada. Lança CancellationException ao cancelar (resultado anterior preservado). */
    static DesktopAnalysis.Output run(PhotoSession s, DesktopAnalysis.Config cfg, DetectionTuner.Option supplied, Options opt,
                                      BooleanSupplier cancel, DetectionTuner.Progress progress) throws IOException {
        long start = System.currentTimeMillis();
        InterestRegion region = s.region();
        AnalysisWindow scope = new AnalysisWindow(s.width, s.height, region);
        scope.requireDetectable();
        DetectionTuner.Option selected = supplied != null ? supplied : cfg.option();
        if (selected.belt) throw new IllegalArgumentException("Na correia use a análise rápida: ela já trabalha na resolução nativa (até 1600 px) e o modo detalhado mede os perfis pelo bloco, não pela foto.");
        DetectionTuner.Report search = null;
        Mat original = original(s);
        try (Input input = decode(original, s.width, s.height, region)) {
            original.release();
            original = null;
            if (opt.search) {
                DetailGeometry map = input.geometry;
                List<TilePlan.Tile> tiles = TilePlan.create(map.width, map.height, opt.superResolution);
                TilePlan.Tile representative = tiles.get(0);
                double distance = Double.POSITIVE_INFINITY;
                for (TilePlan.Tile t : tiles) {
                    double d = Math.hypot(t.left + t.width / 2. - map.width / 2., t.top + t.height / 2. - map.height / 2.);
                    if (d < distance) { distance = d; representative = t; }
                }
                Mat view = input.gray.submat(representative.top, representative.top + representative.height, representative.left, representative.left + representative.width);
                Mat small = view.clone();
                view.release();
                try {
                    search = DetectionTuner.search(small, selected, DetectionAssessment.Context.tile(cfg.scale, s.originalWidth, s.originalHeight, region, cfg.minimumExpectedMm, map, representative),
                            cancel, (done, total, label) -> progress.update(done, total, "Pré-ajuste no bloco original · " + label));
                } finally {
                    small.release();
                }
                if (cancel.getAsBoolean()) throw new java.util.concurrent.CancellationException("Análise cancelada; resultado anterior preservado.");
                if (search.best() != null) selected = search.best().option;
            }
            FsrcnnUpscaler sr = opt.superResolution ? new FsrcnnUpscaler(DesktopApp.model(FsrcnnUpscaler.MODEL).getAbsolutePath()) : null;
            DetailedAnalysis.Output out = DetailedAnalysis.run(input.gray, input.geometry, selected, opt.compare, sr, cfg.scale, s.originalWidth, s.originalHeight,
                    cfg.minimumExpectedMm, DesktopAnalysis.crackDetector(cfg.crackChoice), cfg.crackSensitivity, cancel, progress);
            DetailedAnalysis.Report report = out.report.withSearch(search);
            return new DesktopAnalysis.Output(out.result, out.cracks, selected, null, scope, s.originalWidth, s.originalHeight, s.sample,
                    System.currentTimeMillis() - start, DesktopAnalysis.crackModelNote(), null, null, report);
        } finally {
            if (original != null) original.release();
        }
    }

    /** Texto do resumo "Segmentação detalhada" (mesmo conteúdo do Android). */
    static String summary(DetailedAnalysis.Report r) {
        return r.summary() + "\nAjuste: " + r.optionId + "\nSem tratamento: " + r.baselineCount + " marcações; combinação escolhida: " + r.selectedCount + ".\n"
                + "Blocos escolhidos: original " + r.treatments[0] + ", contraste " + r.treatments[1] + ", ruído leve " + r.treatments[2] + ", SR " + r.treatments[3] + ".\n\n"
                + "Comparação heurística, sem contagem verdadeira de referência. Os contornos e as trincas usam a foto original; SR não é uma medição independente.";
    }
}
