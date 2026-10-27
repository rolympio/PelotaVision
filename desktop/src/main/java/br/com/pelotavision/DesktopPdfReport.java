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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Relatório PDF do Windows (0.5.2), com o mesmo conteúdo e layout do PdfReport do Android 0.15.2:
 * os blocos de texto e as legendas vêm de ReportPdfContent (núcleo comum); aqui só se desenha, com PDFBox.
 * A4 retrato, fontes Helvetica padrão (sem fonte embutida), figuras em JPEG a ~200 dpi.
 */
final class DesktopPdfReport {
    private DesktopPdfReport() {}

    static final float W = PDRectangle.A4.getWidth(), H = PDRectangle.A4.getHeight(), M = 40;
    private static final Color INK = new Color(28, 38, 46), MUTED = new Color(96, 110, 120), ACCENT = new Color(0, 122, 135),
            RULE = new Color(205, 214, 220), BAND = new Color(238, 243, 245);

    static final class Figure {
        final BufferedImage image; final String title, caption;
        /** 0.5.3: ocupa até a página inteira (histograma + Rosin–Rammler); as demais usam meia página. */
        final boolean fullPage;
        Figure(BufferedImage image, ReportPdfContent.FigureText t) { this(image, t, false); }
        private Figure(BufferedImage image, ReportPdfContent.FigureText t, boolean fullPage) { this.image = image; title = t.title; caption = t.caption; this.fullPage = fullPage; }
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

    static byte[] render(JSONObject json, List<Figure> figures) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            Writer w = new Writer(doc, json.optString("pdf_file", ""));
            try {
                for (ReportPdfContent.Block b : ReportPdfContent.blocks(json)) w.block(b);
                if (figures != null && !figures.isEmpty()) {
                    w.ensure(H / 2f);
                    w.section("Imagens");
                    int n = 0;
                    for (Figure f : figures) if (f != null && f.image != null) w.figure(++n, f);
                }
            } finally {
                w.finish();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Número de páginas de um PDF gerado (testes). */
    static int pages(byte[] pdf) throws IOException {
        try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) { return d.getNumberOfPages(); }
    }

    /** Cursor com quebra automática de página; y cresce para baixo, como no Android. */
    private static final class Writer {
        final PDDocument doc; final String file;
        final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final Map<Character, Boolean> encodable = new HashMap<>();
        PDPageContentStream cs; float y; int pageNo;

        Writer(PDDocument doc, String file) throws IOException { this.doc = doc; this.file = file; newPage(); }

        void newPage() throws IOException {
            if (cs != null) { footer(); cs.close(); }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pageNo++;
            y = M;
        }
        void finish() throws IOException { if (cs != null) { footer(); cs.close(); cs = null; } }
        void ensure(float h) throws IOException { if (y + h > H - M - 20) newPage(); }

        // ---- primitivas (coordenadas com origem no topo) ----
        void text(String s, float x, float baseline, float size, boolean isBold, Color c) throws IOException {
            PDType1Font f = isBold ? bold : regular;
            cs.beginText(); cs.setFont(f, size); cs.setNonStrokingColor(c);
            cs.newLineAtOffset(x, H - baseline); cs.showText(safe(s, f)); cs.endText();
        }
        float width(String s, float size, boolean isBold) throws IOException {
            PDType1Font f = isBold ? bold : regular;
            return f.getStringWidth(safe(s, f)) / 1000f * size;
        }
        void fill(float x, float top, float w, float h, Color c) throws IOException {
            cs.setNonStrokingColor(c); cs.addRect(x, H - top - h, w, h); cs.fill();
        }
        void hline(float x1, float x2, float yy, float lw, Color c) throws IOException {
            cs.setStrokingColor(c); cs.setLineWidth(lw); cs.moveTo(x1, H - yy); cs.lineTo(x2, H - yy); cs.stroke();
        }

        /** Helvetica padrão usa WinAnsi: troca o que não existe nela (√, ≤, ≥, →…) por equivalentes legíveis. */
        String safe(String s, PDType1Font f) {
            if (s == null) return "";
            s = s.replace("√", "raiz").replace("≤", "<=").replace("≥", ">=").replace("→", "->").replace(" ", " ")
                    .replace("−", "-").replace("′", "'").replace("ⁿ", "^n").replace("∝", "~");
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                Boolean ok = encodable.get(ch);
                if (ok == null) {
                    try { f.encode(String.valueOf(ch)); ok = true; } catch (IllegalArgumentException | IOException e) { ok = false; }
                    encodable.put(ch, ok);
                }
                b.append(ok ? ch : '?');
            }
            return b.toString();
        }

        // ---- blocos comuns ----
        void block(ReportPdfContent.Block b) throws IOException {
            Color tone = b.tone == ReportPdfContent.Tone.MUTED ? MUTED : INK;
            switch (b.kind) {
                case TITLE: ensure(28); text(b.text, M, y + b.size, b.size, true, INK); y += 28; break;
                case LINE: ensure(b.size + 4); text(b.text, M, y + b.size, b.size, b.bold, tone); y += b.size + 4; break;
                case RULE: y += 6; hline(M, W - M, y, .8f, RULE); y += 8; break;
                case SECTION: section(b.text); break;
                case TABLE: table(b.rows); break;
                case GRID: grid(b.rows, b.fractions); break;
                case NOTE: wrap(b.text, b.size, tone); break;
            }
        }
        void section(String title) throws IOException {
            ensure(40); y += 6;
            text(title.toUpperCase(new Locale("pt", "BR")), M, y + 11, 11.5f, true, ACCENT);
            y += 17;
        }
        void wrap(String s, float size, Color c) throws IOException {
            for (String l : split(s, size, false, W - 2 * M)) { ensure(size + 3); text(l, M, y + size, size, false, c); y += size + 3; }
            y += 4;
        }
        void table(List<String[]> rows) throws IOException {
            float kw = (W - 2 * M) * .46f, vw = (W - 2 * M) - kw - 8;
            for (int i = 0; i < rows.size(); i++) {
                List<String> vl = split(rows.get(i)[1], 9.5f, true, vw), kl = split(rows.get(i)[0], 9, false, kw);
                int n = Math.max(vl.size(), kl.size());
                float h = n * 12 + 5;
                ensure(h);
                if (i % 2 == 0) fill(M - 4, y, W - 2 * M + 8, h, BAND);
                for (int r = 0; r < kl.size(); r++) text(kl.get(r), M, y + 12 + r * 12, 9, false, MUTED);
                for (int r = 0; r < vl.size(); r++) text(vl.get(r), M + kw + 8, y + 12 + r * 12, 9.5f, true, INK);
                y += h;
            }
            y += 4;
        }
        void grid(List<String[]> rows, float[] frac) throws IOException {
            for (int i = 0; i < rows.size(); i++) {
                if (y + 16 > H - M - 20) { newPage(); if (i > 0) row(rows.get(0), frac, true); }
                row(rows.get(i), frac, i == 0);
            }
            y += 4;
        }
        private void row(String[] r, float[] frac, boolean header) throws IOException {
            float total = W - 2 * M, h = 15;
            if (header) fill(M - 4, y, total + 8, h, BAND);
            float x = M;
            for (int c = 0; c < r.length; c++) {
                float cw = frac[c] * total;
                float tx = c == 0 ? x : x + cw - width(r[c], 9, header) - 6;
                text(r[c], tx, y + 11, 9, header, INK);
                x += cw;
            }
            hline(M - 4, W - M + 4, y + h, .5f, RULE);
            y += h;
        }
        List<String> chunks(String word, float size, boolean isBold, float maxWidth) throws IOException {
            List<String> out = new ArrayList<>();
            if (word.length() < 2 || width(word, size, isBold) <= maxWidth) { out.add(word); return out; }
            int start = 0;
            while (start < word.length()) {
                int end = start + 1;
                while (end < word.length() && width(word.substring(start, end + 1), size, isBold) <= maxWidth) end++;
                out.add(word.substring(start, end));
                start = end;
            }
            return out;
        }
        List<String> split(String s, float size, boolean isBold, float maxWidth) throws IOException {
            List<String> out = new ArrayList<>();
            if (s == null) s = "";
            for (String para : s.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String whole : para.split(" ")) {
                    for (String word : chunks(whole, size, isBold, maxWidth)) { // palavra maior que a linha é quebrada
                        String trial = line.length() == 0 ? word : line + " " + word;
                        if (width(trial, size, isBold) <= maxWidth || line.length() == 0) { line.setLength(0); line.append(trial); }
                        else { out.add(line.toString()); line.setLength(0); line.append(word); }
                    }
                }
                out.add(line.toString());
            }
            return out;
        }

        /** Figura numerada: título, legenda e imagem; até duas por página quando a imagem é paisagem. */
        void figure(int number, Figure f) throws IOException {
            List<String> lines = split(f.caption, 8.5f, false, W - 2 * M);
            float maxW = W - 2 * M, maxH = (H - 2 * M - 20) / (f.fullPage ? 1f : 2f) - 26 - lines.size() * 11.5f;
            float s = Math.min(maxW / f.image.getWidth(), maxH / f.image.getHeight());
            float dw = f.image.getWidth() * s, dh = f.image.getHeight() * s;
            ensure(16 + lines.size() * 11.5f + 4 + dh + 12);
            text("Figura " + number + " · " + f.title, M, y + 11, 11, true, INK); y += 16;
            for (String l : lines) { text(l, M, y + 9, 8.5f, false, MUTED); y += 11.5f; }
            y += 4;
            float left = M + (maxW - dw) / 2;
            // ~200 dpi no tamanho impresso: PDF leve sem perder a leitura das elipses.
            int tw = Math.max(1, Math.round(dw * 200f / 72f)), th = Math.max(1, Math.round(dh * 200f / 72f));
            BufferedImage img = tw < f.image.getWidth() ? scaled(f.image, tw, th) : rgb(f.image);
            PDImageXObject x = JPEGFactory.createFromImage(doc, img, 0.9f);
            cs.drawImage(x, left, H - y - dh, dw, dh);
            cs.setStrokingColor(RULE); cs.setLineWidth(.6f); cs.addRect(left, H - y - dh, dw, dh); cs.stroke();
            y += dh + 12;
        }
        private void footer() throws IOException {
            text("PelotaVision · " + file, M, H - 22, 7.5f, false, MUTED);
            String n = "Página " + pageNo;
            text(n, W - M - width(n, 7.5f, false), H - 22, 7.5f, false, MUTED);
        }
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
    private static BufferedImage rgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB && src.getRaster().getParent() == null) return src;
        return scaled(src, src.getWidth(), src.getHeight());
    }
}
