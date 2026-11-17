package br.com.pelotavision;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

/**
 * 0.10.0: gera o relatório novo a partir de uma análise real gravada (JSON + _resultado/_filtrada/_mapa),
 * quando a pasta é indicada em -Dpelotavision.previewDir. Sem a pasta, o teste é ignorado.
 */
public class ReportLayoutPreviewTest {
    @Test public void realAnalysis() throws Exception {
        String dir = System.getProperty("pelotavision.previewDir", System.getenv("PV_PREVIEW_DIR"));
        Assume.assumeTrue(dir != null && new File(dir, "a.json").exists());
        JSONObject json = new JSONObject(new String(Files.readAllBytes(new File(dir, "a.json").toPath()), StandardCharsets.UTF_8));
        BufferedImage composite = ImageIO.read(new File(dir, "resultado.png"));
        BufferedImage filtered = ImageIO.read(new File(dir, "filtrada.png"));
        BufferedImage map = ImageIO.read(new File(dir, "mapa.png"));
        List<DesktopPdfReport.Figure> figs = DesktopPdfReport.figures(json, composite, json.optInt("photo_panel_height"), filtered, map);
        byte[] pdf = DesktopPdfReport.render(json, figs);
        Files.write(new File(dir, "relatorio_novo.pdf").toPath(), pdf);
        assertTrue(DesktopPdfReport.pages(pdf) >= 3);
    }
}
