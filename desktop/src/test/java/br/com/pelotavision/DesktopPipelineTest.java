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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Executa o nucleo comum com o OpenCV nativo do Windows (4.12) nas imagens de
 * verificacao do repositorio. Os testes Robolectric do Android nao executam o OpenCV
 * nativo; este e o ensaio de execucao real do nucleo nesta versao.
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

    private static DesktopAnalysis.Output run(File image, DesktopAnalysis.Config cfg) throws IOException {
        Mat bgr = Mats.readUpright(image);
        int ow = bgr.cols(), oh = bgr.rows(), sample = Mats.sampleFor(ow, oh);
        Mat analyzed = Mats.downsample(bgr, sample);
        bgr.release();
        try {
            return DesktopAnalysis.analyze(analyzed, ow, oh, sample, cfg, () -> false, (d, t, l) -> {});
        } finally {
            analyzed.release();
        }
    }

    private static void record(String line) throws IOException {
        summary.add(line);
        System.out.println(line);
        File out = new File("build/parity/summary.txt");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), (String.join("\n", summary) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Foto real da 0.8.1 (1536x865): o registro historico com OpenCV 4.9 desktop foi 125 propostas no automatico e 122 no LoG medio/74%. */
    @Test public void referencePhotoMatchesHistoricalOrder() throws IOException {
        File img = new File(verification, "coverage-0.8.1/reference_input.webp");
        DesktopAnalysis.Config manual = new DesktopAnalysis.Config();
        manual.autoSuggest = false; manual.profile = 1; manual.sensitivity = 74; manual.legacy = true;
        DesktopAnalysis.Output m = run(img, manual);
        DesktopAnalysis.Output a = run(img, new DesktopAnalysis.Config());
        record(String.format(Locale.ROOT, "reference_input.webp %dx%d | manual LoG medio/74%%: %d (historico 4.9: 122) | automatico: %d, ajuste %s (historico 4.9: 125, log_p1_s90) | busca %d/%d em %.1fs | OpenCV %s",
                a.originalWidth, a.originalHeight, m.result.circles.size(), a.result.circles.size(), a.applied.id,
                a.suggestions.trials.size(), a.suggestions.planned, a.suggestions.durationMs / 1000.0, Core.VERSION));
        // Faixa larga: detecta quebra grosseira do nucleo/OpenCV, nao mede acuracia.
        assertTrue("manual fora da ordem historica: " + m.result.circles.size(), m.result.circles.size() >= 61 && m.result.circles.size() <= 183);
        assertTrue("automatico fora da ordem historica: " + a.result.circles.size(), a.result.circles.size() >= 62 && a.result.circles.size() <= 188);
    }

    @Test public void fixturesRunEndToEndAndExport() throws Exception {
        File[] images = {new File(verification, "fixtures/1000411645.jpg"), new File(verification, "fixtures/1000411646.jpg"),
                new File(verification, "fixtures/1000411647.jpg"), new File(verification, "fixtures/crack_example.png")};
        File outDir = new File("build/parity/export");
        for (File img : images) {
            assertTrue(img.toString(), img.isFile());
            DesktopAnalysis.Output out = run(img, new DesktopAnalysis.Config());
            assertTrue("sem propostas em " + img.getName(), out.result.circles.size() > 0);
            assertNotNull(out.cracks);
            assertEquals(out.result.circles.size(), out.cracks.entries.size());
            record(String.format(Locale.ROOT, "%s %dx%d (1/%d) | propostas %d | elipses p/ forma %d | b/a %.3f | trincas possivel %d inconclusivo %d | ajuste %s | %.1fs",
                    img.getName(), out.originalWidth, out.originalHeight, out.sample, out.result.circles.size(), out.shape.included, out.shape.mean,
                    out.cracks.count(CrackAnalysis.Status.POSSIBLE), out.cracks.count(CrackAnalysis.Status.INCONCLUSIVE), out.applied.id, out.durationMs / 1000.0));
            // Exportacao completa, com escala digitada, como no uso real.
            DesktopAnalysis.Output scaled = out.withScale(new Granulometry.Settings(10, 1, true, false));
            Mat bgr = Mats.readUpright(img);
            Mat analyzed = Mats.downsample(bgr, out.sample);
            List<File> files = DesktopExport.save(outDir, new DesktopExport.Source(img, null, null), Mats.toImage(analyzed), scaled, new DesktopAnalysis.Config());
            bgr.release(); analyzed.release();
            assertEquals(5, files.size());
            assertTrue("original deve ser copia byte a byte", java.util.Arrays.equals(Files.readAllBytes(img.toPath()), Files.readAllBytes(files.get(0).toPath())));
            List<String> lines = Files.readAllLines(files.get(2).toPath(), StandardCharsets.UTF_8);
            assertEquals(out.result.circles.size() + 1, lines.size());
            for (String line : lines) assertEquals("CSV deve ter 32 colunas: " + line, 32, line.split(";", -1).length);
            String json = new String(Files.readAllBytes(files.get(4).toPath()), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"physical_scale\"") && json.contains("\"crack_screening\"") && json.contains("\"opencv_version\""));
            Thread.sleep(5); // nomes com milissegundos
        }
    }

    @Test public void reportTablesMatchAndroidHeader() {
        assertEquals(32, ReportTables.PELLET_HEADER.split(";").length);
    }
}
