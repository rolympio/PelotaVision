package br.com.pelotavision;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Executa o nucleo comum com o OpenCV nativo do Windows (4.12) nas imagens de
 * verificacao e as regras da PhotoSession (equivalentes ao MainActivity Android).
 * Nao e validacao de acuracia: as imagens nao tem verdade-terreno homologada.
 */
public class DesktopPipelineTest {
    private static File verification;
    private static final List<String> summary = new ArrayList<>();

    @BeforeClass public static void load() {
        DesktopApp.loadOpenCv();
        verification = new File(System.getProperty("pelotavision.validationDir"));
        assertTrue("verification ausente: " + verification, verification.isDirectory());
        assertNotNull("modelo de trincas", DesktopAnalysis.crackModel());
    }

    static PhotoSession open(File image) throws IOException {
        Mat bgr = Mats.readUpright(image);
        try { return new PhotoSession(image, null, null, bgr); } finally { bgr.release(); }
    }

    static DesktopAnalysis.Output run(PhotoSession s, DesktopAnalysis.Config cfg, DetectionTuner.Option fixed) {
        cfg.scale = s.scale();
        DesktopAnalysis.Output out = DesktopAnalysis.analyze(s.analyzed, s.originalWidth, s.originalHeight, s.sample, s.region(), cfg, fixed, () -> false, (d, t, l) -> {});
        s.accept(out);
        return out;
    }

    static DesktopAnalysis.Config manual(DetectionTuner.Option o) {
        DesktopAnalysis.Config c = new DesktopAnalysis.Config();
        c.autoSuggest = false; c.profile = o.profile; c.sensitivity = o.sensitivity; c.method = o.method;
        return c;
    }

    private static synchronized void record(String line) throws IOException {
        summary.add(line);
        System.out.println(line);
        File out = new File("build/parity/summary.txt");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), (String.join("\n", summary) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A transformação anisotrópica deve atualizar a circularidade geométrica. */
    @Test public void affineRecomputesEllipseRoundness() {
        PelletDetector.Detection seed = new PelletDetector.Detection(50, 50, 10, Math.PI * 100, 1, false, false);
        PelletDetector.Detection circle = new PelletDetector.Detection(seed, 50, 50, 10, 10, 0, true, 1, 0, false, false);
        PelletDetector.Detection stretched = circle.affine(2, 1, 0, 0);
        assertEquals(20, stretched.semiMajor, 1e-8);
        assertEquals(10, stretched.semiMinor, 1e-8);
        assertTrue("circularidade deve diminuir após alongamento", stretched.roundness < circle.roundness);
        assertEquals(2 * circle.area, stretched.area, 1e-8);
        assertEquals(circle.roundness, circle.affine(2, 2, 0, 0).roundness, 1e-8);
    }

    /** Foto real da 0.8.1 (1536x865): historico OpenCV 4.9 desktop = 125 no automatico (log_p1_s90) e 122 no LoG medio/74%. */
    @Test public void referencePhotoMatchesHistoricalOrder() throws IOException {
        File img = new File(verification, "coverage-0.8.1/reference_input.webp");
        PhotoSession m = open(img);
        DesktopAnalysis.Output mo = run(m, manual(new DetectionTuner.Option(1, 74, true)), null);
        PhotoSession a = open(img);
        DesktopAnalysis.Output ao = run(a, new DesktopAnalysis.Config(), null);
        record(String.format(Locale.ROOT, "reference_input.webp %dx%d | manual LoG medio/74%%: %d (historico 4.9: 122) | automatico: %d, ajuste %s (historico 4.9: 125, log_p1_s90) | busca %d/%d em %.1fs | OpenCV %s",
                a.originalWidth, a.originalHeight, mo.result.circles.size(), ao.result.circles.size(), ao.applied.id,
                ao.suggestions.trials.size(), ao.suggestions.planned, ao.suggestions.durationMs / 1000.0, Core.VERSION));
        assertTrue(mo.result.circles.size() >= 61 && mo.result.circles.size() <= 183);
        assertTrue(ao.result.circles.size() >= 62 && ao.result.circles.size() <= 188);
        m.release(); a.release();
    }

    /** Item 1: ROI exige nova analise, bloqueia salvar, filtra pelo limite e reaplicar o mesmo escopo preserva. */
    @Test public void roiRulesMatchAndroid() throws Exception {
        PhotoSession s = open(new File(verification, "fixtures/1000411645.jpg"));
        DesktopAnalysis.Output full = run(s, manual(new DetectionTuner.Option(1, 90, true)), null);
        int fullCount = s.active().circles.size();
        assertTrue(s.canSave());
        InterestRegion circle = InterestRegion.initial(InterestRegion.Shape.CIRCLE, s.width, s.height);
        s.setRegion(circle);
        assertTrue("mudar a ROI exige nova analise", s.needsAnalysis());
        assertFalse("salvar bloqueado ate reanalisar", s.canSave());
        assertEquals(0, s.active().circles.size());
        s.setRegion(null);
        assertFalse("voltar ao escopo analisado preserva o resultado", s.needsAnalysis());
        assertEquals(fullCount, s.active().circles.size());
        s.setRegion(circle);
        run(s, manual(full.applied), full.applied);
        assertFalse(s.needsAnalysis());
        assertTrue(s.output().result.method.contains("active_roi_crop_v1"));
        for (PelletDetector.Detection d : s.active().circles) assertTrue("elipse cruzando o limite deve ficar fora", circle.includes(d));
        assertTrue(s.active().circles.size() < fullCount);
        File dir = new File("build/parity/roi");
        List<File> files = DesktopExport.save(dir, s, new DesktopAnalysis.Config());
        String json = new String(Files.readAllBytes(files.get(4).toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"CIRCLE\"") && json.contains("\"roi_input\": true") && json.contains("\"circular_mask\": true"));
        record(String.format(Locale.ROOT, "ROI circular 80%%: %d de %d propostas da foto inteira (reanalise no recorte; limite exclui elipses cruzando)", s.active().circles.size(), fullCount));
        s.release();
    }

    /** Item 2: calibracao por objeto, exclusao da esfera e invalidacao se o contorno mudar. */
    @Test public void objectCalibrationRules() throws Exception {
        PhotoSession s = open(new File(verification, "fixtures/1000411645.jpg"));
        DesktopAnalysis.Output out = run(s, manual(new DetectionTuner.Option(1, 90, true)), null);
        int index = -1;
        for (int i = 0; i < s.active().circles.size(); i++) {
            PelletDetector.Detection d = s.active().circles.get(i);
            if (d.ellipseFitted && !d.partial && !d.needsReview) { index = i; break; }
        }
        assertTrue("precisa de uma elipse elegivel", index >= 0);
        PelletDetector.Detection d = s.active().circles.get(index);
        int before = s.active().circles.size();
        ObjectCalibration.Reference ref = ObjectCalibration.select(d, s.fullIndex(index) + 1, index + 1, ObjectCalibration.Measure.STANDARD_SPHERE, 12.0, true,
                s.originalWidth, s.originalHeight, out.result.width, out.result.height);
        s.setScale(Granulometry.Settings.selectedObject(ref, 1, true, false));
        assertEquals("esfera excluida das estatisticas", before - 1, s.active().circles.size());
        assertEquals(ref.originalReferencePixels / 12.0, s.scale().originalPixelsPerMm, 1e-9);
        assertNull("reanalise deterministica mantem a referencia", s.accept(DesktopAnalysis.analyze(s.analyzed, s.originalWidth, s.originalHeight, s.sample, null, manual(out.applied), out.applied, () -> false, (a, b, c) -> {})));
        assertNotNull(s.reference());
        String warning = s.accept(DesktopAnalysis.analyze(s.analyzed, s.originalWidth, s.originalHeight, s.sample, null, manual(new DetectionTuner.Option(3, 55, false)), new DetectionTuner.Option(3, 55, false), () -> false, (a, b, c) -> {}));
        if (s.reference() == null) assertNotNull("remocao da referencia deve avisar", warning);
        try {
            PelletDetector.Detection bad = null;
            for (PelletDetector.Detection x : s.output().result.circles) if (x.partial || x.needsReview || !x.ellipseFitted) { bad = x; break; }
            if (bad != null) { ObjectCalibration.eligible(bad); throw new AssertionError("contorno parcial/alerta nao pode calibrar"); }
        } catch (IllegalArgumentException expected) { /* regra do Android */ }
        record(String.format(Locale.ROOT, "Calibracao por objeto: esfera 12 mm -> %.4f px/mm; esfera fora da amostra (%d -> %d); outro ajuste %s a referencia",
                ref.originalPixelsPerMm, before, before - 1, s.reference() == null ? "removeu (com aviso)" : "manteve"));
        s.release();
    }

    /** Item 3: sugestao de bordas igual ao registro Android 0.9.0 (x~211 e x~1098 px) e bloqueio > 8%. */
    @Test public void beltSuggestionAndRules() throws Exception {
        PhotoSession s = open(new File(verification, "belt-0.9.0/input_correia.jpg"));
        BeltCalibration.Edges e = Features.suggestEdges(s);
        assertNotNull("sugestao de bordas", e);
        double a = (e.x(0) + e.x(1)) / 2, b = (e.x(2) + e.x(3)) / 2, left = Math.min(a, b), right = Math.max(a, b);
        record(String.format(Locale.ROOT, "Correia %dx%d: bordas sugeridas x=%.0f e x=%.0f px (registro Android 0.9.0: ~211 e ~1098)", s.width, s.height, left, right));
        assertEquals(211, left, 25);
        assertEquals(1098, right, 25);
        BeltCalibration.Reference ref = BeltCalibration.select(e, 1000, s.originalWidth, s.originalHeight, s.width, s.height);
        assertTrue(ref.widthVariation <= .08);
        double[] skew = {200, 100, 200, 1400, 1000, 100, 1150, 1400};
        try { BeltCalibration.select(new BeltCalibration.Edges(skew, false), 1000, s.originalWidth, s.originalHeight, s.width, s.height); throw new AssertionError("variacao > 8% deve bloquear"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("8%")); }
        s.release();
    }

    /** Item 4: revisao manual por pelota, restaurar e contagem de revisadas. */
    @Test public void crackReview() throws Exception {
        PhotoSession s = open(new File(verification, "fixtures/crack_example.png"));
        run(s, manual(new DetectionTuner.Option(1, 90, true)), null);
        CrackAnalysis.Entry e0 = s.activeCracks().entries.get(0);
        CrackAnalysis.Status other = e0.status == CrackAnalysis.Status.POSSIBLE ? CrackAnalysis.Status.NO_INDICATION : CrackAnalysis.Status.POSSIBLE;
        s.reviewCrack(0, other);
        assertEquals(other, s.activeCracks().entries.get(0).status);
        assertTrue(s.activeCracks().entries.get(0).manuallyReviewed);
        assertEquals(1, s.pendingReviews());
        s.reviewCrack(0, null);
        assertEquals(e0.automatic, s.activeCracks().entries.get(0).status);
        assertEquals(0, s.pendingReviews());
        s.release();
    }

    /** Item 5: busca, re-ranqueamento e aplicacao de outra opcao com os mesmos contornos. */
    @Test public void suggestionComparisonAndApply() throws Exception {
        PhotoSession s = open(new File(verification, "fixtures/1000411646.jpg"));
        DesktopAnalysis.Output out = run(s, new DesktopAnalysis.Config(), null);
        DetectionTuner.Report ranked = s.rankedSuggestions();
        assertNotNull(ranked);
        DetectionTuner.Trial other = null;
        for (DetectionTuner.Trial t : ranked.ordered()) if (t.result != null && !t.option.id.equals(out.applied.id)) { other = t; break; }
        assertNotNull(other);
        DesktopAnalysis.Output applied = DesktopAnalysis.applyTrial(s.analyzed, s.originalWidth, s.originalHeight, s.sample, out.window, out.suggestions, other, 50);
        s.accept(applied);
        assertEquals(other.option.id, s.output().applied.id);
        assertEquals(other.result.circles.size(), s.active().circles.size());
        assertNotNull("aplicar outra opcao preserva a busca", s.rankedSuggestions());
        record(String.format(Locale.ROOT, "Comparacao: recomendado %s (%d) -> aplicado %s (%d) com os mesmos contornos da busca",
                out.applied.id, out.result.circles.size(), other.option.id, s.active().circles.size()));
        s.release();
    }

    /** Item 6: escala por media conhecida fica marcada como estimativa. */
    @Test public void estimatedMeanIsFlagged() throws Exception {
        PhotoSession s = open(new File(verification, "fixtures/1000411647.jpg"));
        run(s, manual(new DetectionTuner.Option(0, 55, true)), null);
        s.setScale(Granulometry.Settings.estimatedMean(12, 1, true, false));
        assertTrue(s.scale().estimated());
        assertEquals(12, s.distribution().meanMm, 1e-6);
        List<File> files = DesktopExport.save(new File("build/parity/mean"), s, new DesktopAnalysis.Config());
        String json = new String(Files.readAllBytes(files.get(4).toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"estimated\": true") && json.contains("\"assumed_mean_mm\": 12"));
        s.release();
    }

    /** Item 7 + fluxo completo nas imagens de verificacao. */
    @Test public void fixturesRunEndToEndAndExport() throws Exception {
        File[] images = {new File(verification, "fixtures/1000411645.jpg"), new File(verification, "fixtures/1000411646.jpg"),
                new File(verification, "fixtures/1000411647.jpg"), new File(verification, "fixtures/crack_example.png")};
        File outDir = new File("build/parity/export");
        for (File img : images) {
            PhotoSession s = open(img);
            DesktopAnalysis.Output out = run(s, new DesktopAnalysis.Config(), null);
            assertTrue("sem propostas em " + img.getName(), s.active().circles.size() > 0);
            assertEquals(s.active().circles.size(), s.activeCracks().entries.size());
            record(String.format(Locale.ROOT, "%s %dx%d (1/%d) | propostas %d | elipses p/ forma %d | b/a %.3f | trincas possivel %d inconclusivo %d | ajuste %s | %.1fs",
                    img.getName(), s.originalWidth, s.originalHeight, s.sample, s.active().circles.size(), s.shape().included, s.shape().mean,
                    s.activeCracks().count(CrackAnalysis.Status.POSSIBLE), s.activeCracks().count(CrackAnalysis.Status.INCONCLUSIVE), out.applied.id, out.durationMs / 1000.0));
            s.setScale(new Granulometry.Settings(10, 1, true, false));
            List<File> files = DesktopExport.save(outDir, s, new DesktopAnalysis.Config());
            // 0.5.0: original, resultado, csv, granulometria, json (mesmos indices) + _mapa.png no fim
            assertEquals(6, files.size());
            assertTrue(files.get(5).getName().endsWith("_mapa.png"));
            assertTrue("original deve ser copia byte a byte", java.util.Arrays.equals(Files.readAllBytes(img.toPath()), Files.readAllBytes(files.get(0).toPath())));
            List<String> lines = Files.readAllLines(files.get(2).toPath(), StandardCharsets.UTF_8);
            assertEquals(s.active().circles.size() + 1, lines.size());
            for (String line : lines) assertEquals("CSV deve ter 32 colunas: " + line, 32, line.split(";", -1).length);
            String json = new String(Files.readAllBytes(files.get(4).toPath()), StandardCharsets.UTF_8);
            for (String key : new String[]{"region_of_interest", "detection_suggestion", "processing_scope", "report_viewport", "shape_analysis", "cracks", "physical_scale", "histogram", "detections", "source",
                    "report_schema_version", "analysis_models", "crack_histogram", "granulometric_map", "visualization"})
                assertTrue("JSON sem chave Android " + key, json.contains("\"" + key + "\""));
            java.awt.image.BufferedImage png = javax.imageio.ImageIO.read(files.get(1));
            DesktopExport.Layout layout = new DesktopExport.Layout(s.width, s.height);
            assertEquals(layout.height, png.getHeight());
            Thread.sleep(5);
            s.release();
        }
    }

    @Test public void reportTablesMatchAndroidHeader() {
        assertEquals(32, ReportTables.PELLET_HEADER.split(";").length);
    }
}
