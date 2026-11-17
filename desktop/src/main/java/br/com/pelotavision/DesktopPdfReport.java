package br.com.pelotavision;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.json.JSONObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório PDF do Windows. 0.10.0 (núcleo 0.18.0): layout de laudo da Planta Piloto decidido no núcleo
 * ({@link ReportLayout}): aqui só se executam as operações de desenho com PDFBox, as mesmas que o Android
 * executa com PdfDocument. Helvetica padrão (sem fonte embutida), imagens em JPEG a ~200 dpi.
 */
final class DesktopPdfReport {
    private DesktopPdfReport() {}

    static final float W = ReportLayout.W, H = ReportLayout.H;

    static final class Figure {
        final BufferedImage image; final String title, caption;
        /** Papel no layout novo (null: figura sem lugar no laudo, ex.: composto ou painel de resumo). */
        final ReportLayout.Role role;
        /** Mantido por compatibilidade (0.5.3): figura de página inteira; o layout novo desenha os gráficos em vetor. */
        final boolean fullPage;
        Figure(BufferedImage image, ReportPdfContent.FigureText t) { this(image, t, false); }
        private Figure(BufferedImage image, ReportPdfContent.FigureText t, boolean fullPage) { this.image = image; title = t.title; caption = t.caption; role = t.role; this.fullPage = fullPage; }
        static Figure fullPage(BufferedImage image, ReportPdfContent.FigureText t) { return new Figure(image, t, true); }
    }

    /** Figuras na ordem do relatório, recortando o composto _resultado.png pelo mesmo layout do Android. */
    static List<Figure> figures(JSONObject json, BufferedImage composite, int panelHeight, BufferedImage filteredPanel, BufferedImage map) {
        List<Figure> out = new ArrayList<>();
        boolean filtered = filteredPanel != null;
        BufferedImage original = null, segmented = null, summary = null;
        if (composite != null && panelHeight > 0) {
            DesktopExport.Layout l = new DesktopExport.Layout(composite.getWidth(), panelHeight);
            if (l.height == composite.getHeight()) {
                original = composite.getSubimage(0, l.originalTop, composite.getWidth(), panelHeight);
                segmented = composite.getSubimage(0, l.annotatedTop, composite.getWidth(), panelHeight);
                summary = composite.getSubimage(0, l.summaryTop, composite.getWidth(), composite.getHeight() - l.summaryTop);
            }
        }
        if (original != null) {
            out.add(new Figure(original, ReportPdfContent.original(json)));
            if (filtered) out.add(new Figure(filteredPanel, ReportPdfContent.filtered(json)));
            out.add(new Figure(segmented, ReportPdfContent.segmented(filtered)));
        } else if (composite != null) {
            if (filtered) out.add(new Figure(filteredPanel, ReportPdfContent.filtered(json)));
            out.add(new Figure(composite, ReportPdfContent.composite(filtered)));
        }
        if (map != null) out.add(new Figure(map, ReportPdfContent.map(filtered)));
        if (summary != null) out.add(new Figure(summary, ReportPdfContent.summaryPanel()));
        return out;
    }

    /** Imagens por papel, a partir das figuras (a primeira de cada papel vale). */
    static Map<ReportLayout.Role, BufferedImage> byRole(List<Figure> figures) {
        Map<ReportLayout.Role, BufferedImage> m = new EnumMap<>(ReportLayout.Role.class);
        if (figures != null) for (Figure f : figures) if (f != null && f.image != null && f.role != null && !m.containsKey(f.role)) m.put(f.role, f.image);
        return m;
    }

    static byte[] render(JSONObject json, List<Figure> figures) throws IOException {
        Map<ReportLayout.Role, BufferedImage> images = byRole(figures);
        Map<ReportLayout.Role, int[]> sizes = new EnumMap<>(ReportLayout.Role.class);
        for (Map.Entry<ReportLayout.Role, BufferedImage> e : images.entrySet()) sizes.put(e.getKey(), new int[]{e.getValue().getWidth(), e.getValue().getHeight()});
        List<ReportLayout.Page> pages = ReportLayout.build(json, sizes);
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            Map<String, PDImageXObject> cache = new HashMap<>();
            for (ReportLayout.Page p : pages) {
                PDPage page = new PDPage(new PDRectangle(W, H));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    for (ReportLayout.Op o : p.ops) draw(doc, cs, o, images, cache, regular, bold);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static Color rgb(int c) { return new Color((c >> 16) & 255, (c >> 8) & 255, c & 255); }

    private static void draw(PDDocument doc, PDPageContentStream cs, ReportLayout.Op o, Map<ReportLayout.Role, BufferedImage> images,
                             Map<String, PDImageXObject> cache, PDType1Font regular, PDType1Font bold) throws IOException {
        switch (o.kind) {
            case TEXT: {
                if (o.text == null || o.text.isEmpty()) return;
                cs.beginText(); cs.setFont(o.bold ? bold : regular, o.size); cs.setNonStrokingColor(rgb(o.color));
                cs.newLineAtOffset(o.x, H - o.y); cs.showText(encodable(o.text, o.bold ? bold : regular)); cs.endText();
                return;
            }
            case RECT: {
                cs.setLineDashPattern(new float[0], 0);
                if (o.fill >= 0) { cs.setNonStrokingColor(rgb(o.fill)); cs.addRect(o.x, H - o.y - o.h, o.w, o.h); cs.fill(); }
                if (o.stroke >= 0 && o.lineWidth > 0) { cs.setStrokingColor(rgb(o.stroke)); cs.setLineWidth(o.lineWidth); cs.addRect(o.x, H - o.y - o.h, o.w, o.h); cs.stroke(); }
                return;
            }
            case LINE: {
                cs.setStrokingColor(rgb(o.stroke)); cs.setLineWidth(o.lineWidth);
                cs.setLineDashPattern(o.dash == null ? new float[0] : o.dash, 0);
                cs.moveTo(o.x, H - o.y); cs.lineTo(o.x2, H - o.y2); cs.stroke();
                cs.setLineDashPattern(new float[0], 0);
                return;
            }
            case POLY: {
                cs.setStrokingColor(rgb(o.stroke)); cs.setLineWidth(o.lineWidth);
                cs.setLineDashPattern(o.dash == null ? new float[0] : o.dash, 0);
                cs.moveTo(o.points[0], H - o.points[1]);
                for (int i = 2; i + 1 < o.points.length; i += 2) cs.lineTo(o.points[i], H - o.points[i + 1]);
                cs.stroke();
                cs.setLineDashPattern(new float[0], 0);
                return;
            }
            case CIRCLE: {
                float k = .5523f * o.w, cx = o.x, cy = H - o.y, r = o.w;
                cs.setLineDashPattern(new float[0], 0);
                cs.moveTo(cx + r, cy);
                cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r); cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
                cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r); cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
                cs.closePath();
                if (o.fill >= 0 && o.stroke >= 0) { cs.setNonStrokingColor(rgb(o.fill)); cs.setStrokingColor(rgb(o.stroke)); cs.setLineWidth(o.lineWidth); cs.fillAndStroke(); }
                else if (o.fill >= 0) { cs.setNonStrokingColor(rgb(o.fill)); cs.fill(); }
                else { cs.setStrokingColor(rgb(o.stroke)); cs.setLineWidth(o.lineWidth); cs.stroke(); }
                return;
            }
            case IMAGE: {
                BufferedImage src = images.get(o.role);
                if (src == null) return;
                String key = o.role + (o.source == null ? "" : ":" + Math.round(o.source[0]) + "," + Math.round(o.source[1]) + "," + Math.round(o.source[2])) + "@" + Math.round(o.w);
                PDImageXObject x = cache.get(key);
                if (x == null) {
                    BufferedImage part = src;
                    if (o.source != null) {
                        int sx = Math.max(0, Math.round(o.source[0])), sy = Math.max(0, Math.round(o.source[1]));
                        int sw = Math.min(src.getWidth() - sx, Math.max(1, Math.round(o.source[2]))), sh = Math.min(src.getHeight() - sy, Math.max(1, Math.round(o.source[3])));
                        part = src.getSubimage(sx, sy, sw, sh);
                    }
                    // ~200 dpi no tamanho impresso: PDF leve sem perder a leitura das elipses.
                    int tw = Math.max(1, Math.round(o.w * 200f / 72f)), th = Math.max(1, Math.round(o.h * 200f / 72f));
                    BufferedImage img = tw < part.getWidth() ? scaled(part, tw, th) : rgbCopy(part);
                    x = JPEGFactory.createFromImage(doc, img, 0.9f);
                    cache.put(key, x);
                }
                cs.drawImage(x, o.x, H - o.y - o.h, o.w, o.h);
                return;
            }
            default:
        }
    }

    /** O núcleo já limpou o texto (ReportFont.safe); aqui só garante que nada fora do WinAnsi chegue ao PDFBox. */
    private static String encodable(String s, PDType1Font f) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            try { f.encode(String.valueOf(ch)); b.append(ch); } catch (IllegalArgumentException | IOException e) { b.append('?'); }
        }
        return b.toString();
    }

    /** Número de páginas de um PDF gerado (testes). */
    static int pages(byte[] pdf) throws IOException {
        try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) { return d.getNumberOfPages(); }
    }

    private static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
    private static BufferedImage rgbCopy(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB && src.getRaster().getParent() == null) return src;
        return scaled(src, src.getWidth(), src.getHeight());
    }
}
