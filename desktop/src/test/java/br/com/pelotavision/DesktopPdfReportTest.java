package br.com.pelotavision;

import static org.junit.Assert.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 0.5.2: relatório PDF do Windows, sem OpenCV nativo. Mesmo conteúdo do Android (ReportPdfContent),
 * figuras separadas (original, filtrada, segmentada, mapa, resumo) e texto com acentos e símbolos.
 * Grava o exemplo em verification/pdf-windows-0.5.2/.
 */
public class DesktopPdfReportTest {
    @Test public void pdfHasSharedSectionsAndSeparateFiguresIncludingFiltered() throws Exception {
        int w = 380, ph = 300;
        DesktopExport.Layout layout = new DesktopExport.Layout(w, ph);
        BufferedImage composite = new BufferedImage(w, layout.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(new Color(16, 26, 35)); g.fillRect(0, 0, w, layout.height);
        g.setColor(new Color(40, 45, 50)); g.fillRect(0, layout.originalTop, w, ph);       // original escura
        g.setColor(new Color(200, 200, 200)); g.fillRect(0, layout.annotatedTop, w, ph);  // segmentada sobre a filtrada
        g.dispose();
        BufferedImage filtered = new BufferedImage(w, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D f = filtered.createGraphics(); f.setColor(new Color(200, 200, 200)); f.fillRect(0, 0, w, ph); f.dispose();
        BufferedImage map = new BufferedImage(w, ph + 70, BufferedImage.TYPE_INT_RGB);

        JSONObject json = new JSONObject();
        json.put("app", "PelotaVision Windows 0.5.2 (nucleo 0.15.2)");
        json.put("annotated_file", "PelotaVision_teste_resultado.png");
        json.put("files_location", "mesma pasta deste PDF");
        json.put("test_name", "Disco 3 · umidade 9,2%");
        json.put("rosin_rammler", RosinRammler.reportJson(RosinRammlerTest.sample(400, 4.5, 12.5, 1, 7), RosinRammler.Basis.MASS));
        json.put("pdf_file", "PelotaVision_teste_relatorio.pdf");
        json.put("count", 12);
        json.put("method", "gradient+ellipse_boundary_v1");
        JSONObject roi = new JSONObject(); roi.put("shape", "RECTANGLE"); roi.put("excluded_outside_or_crossing", 2);
        json.put("region_of_interest", roi);
        json.put("low_light", LowLightInfo.manual(LowLightPreprocessor.Mode.DENOISE_CLAHE, new LowLightPreprocessor.Quality(40, 15, .12, 0)).toJson());
        JSONObject h = new JSONObject(); h.put("unit", "px"); h.put("included", 11); h.put("excluded", 1);
        h.put("mean_px", 40.3); h.put("d10_px", 22.9); h.put("d50_px", 40.96); h.put("d90_px", 56.48); h.put("bin_width_px", 10);
        JSONArray bins = new JSONArray();
        for (int i = 0; i < 40; i++) { JSONObject b = new JSONObject(); b.put("lower_px", 10 * i); b.put("upper_px", 10 * i + 10); b.put("count", i % 5); b.put("percent_by_number", 2.5); bins.put(b); }
        h.put("bins", bins);
        json.put("histogram", h);

        List<DesktopPdfReport.Figure> figures = DesktopPdfReport.figures(json, composite, ph, filtered, map);
        assertEquals(5, figures.size());
        assertEquals("Imagem original", figures.get(0).title);
        assertTrue(figures.get(1).title.startsWith("Imagem filtrada · Redução de ruído"));
        assertEquals("Imagem segmentada", figures.get(2).title);
        assertEquals("Mapa granulométrico", figures.get(3).title);
        assertEquals("Resumo da imagem e histograma", figures.get(4).title);
        assertEquals(new Color(40, 45, 50).getRGB(), figures.get(0).image.getRGB(10, 10));
        assertEquals(new Color(200, 200, 200).getRGB(), figures.get(2).image.getRGB(10, 10));

        // 0.5.3: histograma + Rosin–Rammler numa figura de página inteira, com a fórmula na legenda.
        figures.add(DesktopPdfReport.Figure.fullPage(new BufferedImage(1120, 1568, BufferedImage.TYPE_INT_RGB), ReportPdfContent.distributionFigure(json)));
        assertTrue(figures.get(5).fullPage);
        assertTrue(figures.get(5).caption.contains("massa estimada"));
        byte[] pdf = DesktopPdfReport.render(json, figures);
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(DesktopPdfReport.pages(pdf) >= 3);
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            for (String s : new String[]{"Relatório de análise de pelotas", "ILUMINAÇÃO E TRATAMENTO DE IMAGEM ESCURA", "Redução de ruído + CLAHE",
                    "DISTRIBUIÇÃO GRANULOMÉTRICA", "Figura 1 · Imagem original", "Figura 2 · Imagem filtrada", "Figura 3 · Imagem segmentada", "Figura 4 · Mapa granulométrico", "Figura 5 · Resumo", "Disco 3 · umidade 9,2%",
                    "AJUSTE ROSIN", "Massa estimada (", "F(d) = 100", "Figura 6 · Distribuição granulométrica · histograma e Rosin"})
                assertTrue("PDF sem \"" + s + "\"", text.contains(s));
        }
        File dir = new File(System.getProperty("pelotavision.validationDir", "../verification"), "pdf-windows-0.5.2");
        dir.mkdirs();
        Files.write(new File(dir, "exemplo_relatorio.pdf").toPath(), pdf);
    }

    @Test public void withoutFilterThereIsNoFilteredFigure() throws Exception {
        int w = 380, ph = 200;
        DesktopExport.Layout layout = new DesktopExport.Layout(w, ph);
        BufferedImage composite = new BufferedImage(w, layout.height, BufferedImage.TYPE_INT_RGB);
        JSONObject json = new JSONObject();
        List<DesktopPdfReport.Figure> figures = DesktopPdfReport.figures(json, composite, ph, null, null);
        assertEquals(3, figures.size());
        assertEquals("Imagem segmentada", figures.get(1).title);
        assertFalse(figures.get(1).caption.contains("filtrada"));
        assertTrue(DesktopPdfReport.pages(DesktopPdfReport.render(json, figures)) >= 2);
    }
}
