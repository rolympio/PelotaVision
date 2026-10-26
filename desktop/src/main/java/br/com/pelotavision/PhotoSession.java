package br.com.pelotavision;

import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Estado de uma foto aberta no Windows, com as mesmas regras do MainActivity Android
 * (applyRegion, activateRoi, refreshDistribution, commitScale, reviewCrackAt).
 * Sem interface grafica: testavel no CI.
 *
 * Coordenadas: tudo em pixels da imagem analisada inteira (orientada, reduzida por
 * "sample" quando o original passa de 3000 px). ROI e resultados ficam nessas coordenadas
 * mesmo quando a tela mostra so o recorte.
 */
final class PhotoSession {
    // Fonte
    final File file;                 // arquivo aberto (original byte a byte) ou null
    final BufferedImage frame;       // quadro da webcam (original em PNG) ou null
    final CameraSource.Info camera;
    final int originalWidth, originalHeight, sample, width, height;
    final Mat analyzed;              // BGR da imagem analisada (pertence a sessao)
    final BufferedImage analyzedImage;

    private InterestRegion region;
    private DesktopAnalysis.Output all;     // ultima analise (escopo = all.window)
    private CrackAnalysis allCracks;        // com revisoes manuais, mesma ordem de all.result
    private boolean needsAnalysis = true;
    private Granulometry.Settings scale;
    private double minimumExpectedMm = 5;

    // Derivados de refresh()
    private List<Integer> indices = Collections.emptyList();
    private PelletDetector.Result active;
    private CrackAnalysis activeCracks;
    private Granulometry.Distribution distribution;
    private ShapeStatistics shape;
    private String distributionError;

    PhotoSession(File file, BufferedImage frame, CameraSource.Info camera, Mat bgrOriginal) {
        this.file = file; this.frame = frame; this.camera = camera;
        originalWidth = bgrOriginal.cols(); originalHeight = bgrOriginal.rows();
        sample = Mats.sampleFor(originalWidth, originalHeight);
        analyzed = Mats.downsample(bgrOriginal, sample);
        width = analyzed.cols(); height = analyzed.rows();
        analyzedImage = Mats.toImage(analyzed);
    }

    void release() { analyzed.release(); }

    // ---------------- ROI ----------------

    InterestRegion region() { return region; }

    /** Igual a activateRoi + applyRegion: mudar de escopo exige nova analise. */
    void setRegion(InterestRegion r) {
        if (r != null) new AnalysisWindow(width, height, r); // valida limites
        if (!AnalysisWindow.sameRegion(region, r))
            needsAnalysis = all == null || !new AnalysisWindow(width, height, r).sameScope(all.window);
        region = r;
        refresh();
    }

    /** Janela de exibicao/relatorio (ROI ativa) ou null para a imagem inteira. */
    AnalysisWindow displayWindow() { return region == null ? null : new AnalysisWindow(width, height, region); }

    int regionExcluded() { return all == null || region == null ? 0 : all.result.circles.size() - region.indices(all.result).size(); }

    // ---------------- Analise ----------------

    boolean analyzed() { return all != null; }
    boolean needsAnalysis() { return needsAnalysis; }
    DesktopAnalysis.Output output() { return all; }

    /** 0.5.2: tratamento de iluminação da última análise (null antes de analisar). */
    LowLightInfo lowLight() { return all == null ? null : all.lowLight; }

    private DesktopAnalysis.Output filteredFor;
    private BufferedImage filteredImage;

    /**
     * 0.5.2: imagem filtrada (janela analisada com o realce aplicado; fora dela, a original) quando a última
     * análise usou realce e a área atual ainda é a analisada; senão null. Só para exibição e relatório.
     */
    BufferedImage filteredImage() {
        if (all == null || all.filteredGray == null || needsAnalysis) return null;
        if (filteredFor != all) {
            filteredFor = all;
            filteredImage = FilteredImage.compose(analyzedImage, all.filteredGray, all.window);
        }
        return filteredImage;
    }

    /** Imagem mostrada na tela e usada na segmentação/mapa do relatório: a filtrada, se houver; senão a original. */
    BufferedImage shownImage() {
        BufferedImage f = filteredImage();
        return f != null ? f : analyzedImage;
    }

    /** Quantas revisoes manuais de trinca a proxima reanalise descartaria. */
    int pendingReviews() { return allCracks == null ? 0 : allCracks.reviewedCount(); }

    /**
     * Recebe uma analise nova. Igual ao Android: se o contorno da referencia escolhida mudou,
     * a calibracao por objeto e removida (nunca se liga a outro candidato). Devolve aviso ou null.
     */
    String accept(DesktopAnalysis.Output out) {
        String warning = null;
        if (scale != null && scale.selectedObject != null && !out.window.keepsReference(scale.selectedObject, out.result, originalWidth, originalHeight)) {
            scale = null;
            warning = "A reanalise alterou o contorno da referencia escolhida. A calibracao por objeto foi removida; selecione a referencia novamente.";
        }
        if (scale != null && scale.beltReference != null && !scale.beltReference.matches(originalWidth, originalHeight, width, height)) {
            scale = null;
            warning = "A referencia da correia pertence a outra imagem. Recalibre.";
        }
        all = out;
        allCracks = out.cracks;
        needsAnalysis = !new AnalysisWindow(width, height, region).sameScope(out.window);
        refresh();
        return warning;
    }

    /** Mantem os contornos e so troca o relatorio de busca (busca sob demanda). */
    void setSuggestions(DetectionTuner.Report report) {
        if (all != null) all = all.withSuggestions(report);
    }

    /** Busca re-ranqueada com escala e minimo atuais, como rankedSuggestions() do Android. */
    DetectionTuner.Report rankedSuggestions() {
        if (all == null || all.suggestions == null || needsAnalysis) return null;
        return all.suggestions.rank(new DetectionAssessment.Context(scale, originalWidth, originalHeight, region, minimumExpectedMm, all.window));
    }

    double minimumExpectedMm() { return minimumExpectedMm; }
    void setMinimumExpectedMm(double v) {
        if (!Double.isFinite(v) || v < 0) throw new IllegalArgumentException("Minimo esperado deve ser zero ou positivo.");
        minimumExpectedMm = v;
    }

    // ---------------- Escala ----------------

    Granulometry.Settings scale() { return scale; }
    ObjectCalibration.Reference reference() { return scale == null ? null : scale.selectedObject; }

    /** Valida calculando a distribuicao antes de aplicar (commitScale do Android). */
    void setScale(Granulometry.Settings s) {
        if (s != null && all != null && !needsAnalysis) {
            List<Integer> ids = ObjectCalibration.sampleIndices(all.result, region, s.selectedObject);
            Granulometry.compute(items(InterestRegion.select(all.result, ids)), s, originalWidth, originalHeight, width, height);
        }
        scale = s;
        refresh();
    }

    /** Previa da distribuicao com uma escala candidata (dialogos de calibracao). */
    Granulometry.Distribution preview(Granulometry.Settings s) {
        if (all == null) throw new IllegalArgumentException("Analise a foto primeiro.");
        List<Integer> ids = ObjectCalibration.sampleIndices(all.result, region, s.selectedObject);
        return Granulometry.compute(items(InterestRegion.select(all.result, ids)), s, originalWidth, originalHeight, width, height);
    }

    // ---------------- Trincas ----------------

    /** Revisao manual de uma pelota ativa (status null = restaurar automatico). */
    void reviewCrack(int activeIndex, CrackAnalysis.Status status) {
        if (allCracks == null || needsAnalysis) return;
        allCracks = allCracks.review(indices.get(activeIndex), status);
        refresh();
    }

    /** Indice em all.result de uma pelota ativa. */
    int fullIndex(int activeIndex) { return indices.get(activeIndex); }

    // ---------------- Derivados ----------------

    PelletDetector.Result active() { return active; }
    CrackAnalysis activeCracks() { return activeCracks; }
    Granulometry.Distribution distribution() { return distribution; }
    /** Série de pelotas trincadas nas mesmas classes (indisponível sem classificação; nunca zero inventado). */
    CrackHistogram crackHistogram() { return distribution == null || active == null ? null : CrackHistogram.compute(distribution, items(active), activeCracks); }
    ShapeStatistics shape() { return shape; }
    String distributionError() { return distributionError; }
    List<Integer> indices() { return indices; }
    boolean canSave() { return all != null && !needsAnalysis && distribution != null; }

    /** Pelota ativa mais proxima de (x,y) dentro do contorno, ou -1 (reviewCrackAt). */
    int activeAt(double x, double y) {
        if (active == null) return -1;
        int chosen = -1;
        double closest = 1;
        for (int i = 0; i < active.circles.size(); i++) {
            double distance = active.circles.get(i).normalizedDistance(x, y);
            if (distance < closest) { closest = distance; chosen = i; }
        }
        return chosen;
    }

    private void refresh() {
        distribution = null; distributionError = null; shape = null; activeCracks = null;
        if (all == null) { active = null; indices = Collections.emptyList(); return; }
        indices = ObjectCalibration.sampleIndices(all.result, region, reference());
        if (needsAnalysis) {
            active = new PelletDetector.Result(Collections.emptyList(), all.result.width, all.result.height, 0, 0, false, "pending_roi_analysis");
            return;
        }
        active = InterestRegion.select(all.result, indices);
        activeCracks = allCracks == null ? null : allCracks.select(indices);
        shape = ShapeStatistics.of(active);
        try {
            if (scale == null) distribution = ReportTables.pixelHistogram(active);
            else {
                distribution = Granulometry.compute(items(active), scale, originalWidth, originalHeight, active.width, active.height);
                scale = distribution.settings; // media conhecida vira escala calculada, como no Android
            }
        } catch (IllegalArgumentException e) {
            distributionError = e.getMessage();
        }
    }

    static List<Granulometry.Item> items(PelletDetector.Result r) {
        List<Granulometry.Item> items = new ArrayList<>();
        for (PelletDetector.Detection d : r.circles) items.add(new Granulometry.Item(2 * d.radius, d.partial, d.needsReview));
        return items;
    }
}
