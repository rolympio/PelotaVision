package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.18.0: relatório PDF no padrão dos laudos da Planta Piloto (modelo: relatório da MicroscopiaAI).
 *
 * O núcleo decide TUDO o que vai em cada página, em coordenadas absolutas (pontos, A4, origem no topo):
 * textos já quebrados, tabelas com moldura, gráficos vetoriais (histograma e curva passante com
 * Rosin–Rammler) e onde entra cada imagem (ou recorte de imagem). Windows (PDFBox) e Android
 * (PdfDocument) só executam a lista de operações, então os dois PDFs têm o mesmo layout.
 *
 * Páginas: 1 laudo (identificação, indicadores, imagens, classes e histograma); 2 distribuição
 * acumulada, mapa e estatísticas; 3 trincas com recortes (só quando há o que mostrar); última:
 * rastreabilidade completa (sempre). Tudo sai do MESMO JSON gravado ao lado do PDF.
 */
public final class ReportLayout {
    private ReportLayout() {}

    public static final float W = 595.28f, H = 841.89f, M = 34f;
    public static final String HEADER = "Diretoria de Pelotização - Gerência Enga. Processo e Sustentabilidade - Planta Piloto da Pelotização";
    public static final String TITLE = "Relatório de Análise Granulométrica de Pelotas por Imagem";

    // Paleta (RGB).
    public static final int INK = 0x1C262E, MUTED = 0x5F6E78, ACCENT = 0x007A87, RULE = 0x9AA7B0, LIGHT = 0xDCE3E7,
            BAND = 0xEEF3F5, HEAD = 0xD9E6EA, CELL = 0xE6F4EA, ALL = 0x268CAB, CRACK = 0xF28C1A, FIT = 0x1F5FA8,
            OK = 0x2E9E4F, REVIEW = 0xE0A800, BAD = 0xD43A2F, WHITE = 0xFFFFFF;

    /** Imagens que a plataforma fornece (painéis do composto _resultado.png e o mapa). */
    public enum Role { ORIGINAL, FILTERED, SEGMENTED, MAP }

    public enum Kind { TEXT, RECT, LINE, POLY, IMAGE, CIRCLE }

    /** Uma operação de desenho. Coordenadas em pontos, origem no canto superior esquerdo. */
    public static final class Op {
        public final Kind kind;
        public float x, y, w, h, x2, y2, size, lineWidth;
        public float[] points, dash, source;
        public int fill = -1, stroke = -1, color = INK;
        public boolean bold;
        public String text;
        public Role role;
        Op(Kind k) { kind = k; }
    }

    public static final class Page {
        public final List<Op> ops = new ArrayList<>();
        public final int number;
        Page(int n) { number = n; }
    }

    /**
     * Monta as páginas. sizes: largura/altura (px) de cada imagem disponível; papéis ausentes são omitidos
     * sem quebrar o layout (ex.: sem imagem filtrada quando não houve realce).
     */
    public static List<Page> build(JSONObject j, Map<Role, int[]> sizes) {
        Builder b = new Builder(j, sizes == null ? new EnumMap<Role, int[]>(Role.class) : sizes);
        b.pageResumo();
        b.pageDistribuicao();
        b.pageTrincas();
        b.pageRastreabilidade();
        b.footers();
        return b.pages;
    }

    // =====================================================================================================

    private static final class Builder {
        final JSONObject j;
        final Map<Role, int[]> sizes;
        final List<Page> pages = new ArrayList<>();
        Page page;
        final String unit;
        /** Onde terminou o conteúdo da página corrente (para encaixar a seção seguinte na mesma página). */
        float cursor;

        Builder(JSONObject j, Map<Role, int[]> sizes) {
            this.j = j; this.sizes = sizes;
            JSONObject h = j.optJSONObject("histogram");
            unit = h == null ? "px" : h.optString("unit", "px");
        }

        // ------------------------------------------------------------------ primitivas

        Page newPage() {
            page = new Page(pages.size() + 1);
            pages.add(page);
            return page;
        }
        Op add(Op o) { page.ops.add(o); return o; }

        float text(float x, float baseline, String s, float size, boolean bold, int color) {
            Op o = new Op(Kind.TEXT);
            o.x = x; o.y = baseline; o.text = ReportFont.safe(s); o.size = size; o.bold = bold; o.color = color;
            o.w = ReportFont.width(o.text, size, bold);
            add(o);
            return o.w;
        }
        void textRight(float right, float baseline, String s, float size, boolean bold, int color) {
            String t = ReportFont.safe(s);
            text(right - ReportFont.width(t, size, bold), baseline, t, size, bold, color);
        }
        void textCenter(float cx, float baseline, String s, float size, boolean bold, int color) {
            String t = ReportFont.safe(s);
            text(cx - ReportFont.width(t, size, bold) / 2, baseline, t, size, bold, color);
        }
        /** Texto que não pode passar de maxW: reduz a fonte até caber (mín. 70%), depois corta com "…". */
        void textFit(float x, float baseline, String s, float size, boolean bold, int color, float maxW) {
            String t = ReportFont.safe(s);
            float sz = size;
            while (sz > size * .7f && ReportFont.width(t, sz, bold) > maxW) sz -= .25f;
            while (t.length() > 1 && ReportFont.width(t, sz, bold) > maxW) t = t.substring(0, t.length() - 2) + "…";
            text(x, baseline, t, sz, bold, color);
        }
        void rect(float x, float y, float w, float h, int fill, int stroke, float lw) {
            Op o = new Op(Kind.RECT); o.x = x; o.y = y; o.w = w; o.h = h; o.fill = fill; o.stroke = stroke; o.lineWidth = lw; add(o);
        }
        void line(float x1, float y1, float x2, float y2, int color, float lw, float[] dash) {
            Op o = new Op(Kind.LINE); o.x = x1; o.y = y1; o.x2 = x2; o.y2 = y2; o.stroke = color; o.lineWidth = lw; o.dash = dash; add(o);
        }
        void poly(float[] pts, int color, float lw, float[] dash) {
            if (pts.length < 4) return;
            Op o = new Op(Kind.POLY); o.points = pts; o.stroke = color; o.lineWidth = lw; o.dash = dash; add(o);
        }
        void circle(float cx, float cy, float r, int fill, int stroke, float lw) {
            Op o = new Op(Kind.CIRCLE); o.x = cx; o.y = cy; o.w = r; o.fill = fill; o.stroke = stroke; o.lineWidth = lw; add(o);
        }
        /** Imagem inteira (ou recorte src = {x,y,w,h} em px) ajustada dentro da caixa, centralizada, com moldura. */
        float[] image(Role role, float x, float y, float w, float h, float[] src) {
            int[] s = sizes.get(role);
            if (s == null || s[0] <= 0 || s[1] <= 0) return null;
            float sw = src == null ? s[0] : src[2], sh = src == null ? s[1] : src[3];
            float k = Math.min(w / sw, h / sh), dw = sw * k, dh = sh * k;
            Op o = new Op(Kind.IMAGE);
            o.role = role; o.x = x + (w - dw) / 2; o.y = y + (h - dh) / 2; o.w = dw; o.h = dh; o.source = src;
            add(o);
            rect(o.x, o.y, dw, dh, -1, RULE, .5f);
            return new float[]{o.x, o.y, dw, dh};
        }
        List<String> wrap(String s, float size, boolean bold, float maxW) {
            List<String> out = new ArrayList<>();
            String t = ReportFont.safe(s);
            for (String para : t.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String whole : para.split(" ")) for (String word : chunks(whole, size, bold, maxW)) {
                    if (word.isEmpty()) continue;
                    String trial = line.length() == 0 ? word : line + " " + word;
                    if (ReportFont.width(trial, size, bold) <= maxW || line.length() == 0) { line.setLength(0); line.append(trial); }
                    else { out.add(line.toString()); line.setLength(0); line.append(word); }
                }
                out.add(line.toString());
            }
            return out;
        }
        List<String> chunks(String word, float size, boolean bold, float maxW) {
            List<String> out = new ArrayList<>();
            if (word.length() < 2 || ReportFont.width(word, size, bold) <= maxW) { out.add(word); return out; }
            int start = 0;
            while (start < word.length()) {
                int end = start + 1;
                while (end < word.length() && ReportFont.width(word.substring(start, end + 1), size, bold) <= maxW) end++;
                out.add(word.substring(start, end));
                start = end;
            }
            return out;
        }
        float paragraph(float x, float y, float w, String s, float size, int color) {
            for (String l : wrap(s, size, false, w)) { y += size * 1.25f; text(x, y, l, size, false, color); }
            return y + size * .5f;
        }

        // ------------------------------------------------------------------ cabeçalho e rodapé

        float header(String subtitle) {
            float y = M;
            textCenter(W / 2, y + 8, HEADER, 8.2f, false, INK);
            y += 21;
            textCenter(W / 2, y + 2, subtitle == null ? TITLE : subtitle, 12.5f, true, INK);
            y += 9;
            line(M, y, W - M, y, INK, .9f, null);
            return y + 8;
        }
        float sectionTitle(float x, float y, float w, String t) {
            text(x, y + 10, t, 10.5f, true, ACCENT);
            line(x, y + 14, x + w, y + 14, LIGHT, .6f, null);
            return y + 20;
        }
        void footers() {
            String file = j.optString("pdf_file", "");
            String app = j.optString("app", "PelotaVision");
            int n = pages.size();
            for (Page p : pages) {
                page = p;
                float y = H - 20;
                line(M, y - 9, W - M, y - 9, LIGHT, .5f, null);
                textFit(M, y, app + " · " + file, 6.5f, false, MUTED, W * .52f);
                textCenter(W * .70f, y, "EXPERIMENTAL · não substitui peneiramento", 6.5f, false, MUTED);
                textRight(W - M, y, "página " + p.number + "/" + n, 6.5f, false, MUTED);
            }
        }

        // ------------------------------------------------------------------ página 1: laudo

        void pageResumo() {
            newPage();
            float y = header(null);
            // Identificação (duas colunas de campos)
            String[][] campos = {
                    {"Amostra:", sample()}, {"Data da análise:", when()},
                    {"Cena:", scene()}, {"Captura:", capture()},
                    {"Escala:", scaleShort()}, {"Região analisada:", roiShort()},
            };
            float colW = (W - 2 * M) / 2;
            for (int i = 0; i < campos.length; i++) {
                float x = M + (i % 2) * colW, by = y + 10 + (i / 2) * 13;
                float lw = text(x, by, campos[i][0], 8.5f, true, INK);
                textFit(x + Math.max(lw + 5, 80), by, campos[i][1], 8.5f, false, INK, colW - Math.max(lw + 5, 80) - 6);
            }
            y += 10 + 13 * 3 + 2;
            line(M, y, W - M, y, INK, .9f, null);
            y += 9;
            y = kpis(y);
            y += 8;
            // Imagens lado a lado
            float gap = 12, iw = (W - 2 * M - gap) / 2;
            float tableH = 14 + classRows() * 13.5f, ih = Math.max(190, Math.min(iw * 1.02f, H - M - 52 - tableH - 34 - y - 14));
            textCenter(M + iw / 2, y + 9, "Aspecto geral da amostra", 9.5f, true, INK);
            textCenter(M + iw + gap + iw / 2, y + 9, "Segmentação e triagem de trincas", 9.5f, true, INK);
            y += 14;
            if (image(Role.ORIGINAL, M, y, iw, ih, null) == null) placeholder(M, y, iw, ih, "imagem original indisponível");
            Role seg = sizes.containsKey(Role.SEGMENTED) ? Role.SEGMENTED : Role.MAP;
            if (image(seg, M + iw + gap, y, iw, ih, null) == null) placeholder(M + iw + gap, y, iw, ih, "imagem segmentada indisponível");
            y += ih + 4;
            legendaSegmentacao(M + iw + gap, y + 7);
            textFit(M, y + 7, imageNote(), 6.8f, false, MUTED, iw);
            y += 16;
            // Classes (tabela) + histograma (gráfico)
            float tw = (W - 2 * M) * .44f, cx = M + tw + 14, cw = W - M - cx;
            textCenter(M + tw / 2, y + 9, "Distribuição granulométrica por número", 9.5f, true, INK);
            textCenter(cx + cw / 2, y + 9, "Histograma (todas × trincadas)", 9.5f, true, INK);
            y += 14;
            float bottom = H - M - 44;
            float yt = classTable(M, y, tw, bottom);
            float hh = Math.max(150, yt - y);
            histogram(cx, y, cw, hh);
            y = Math.max(yt, y + hh) + 6;
            paragraph(M, y, W - 2 * M, "Diâmetro equivalente: diâmetro do círculo de mesma área da elipse ajustada a cada pelota, medido nos pixels da foto original e convertido pela escala informada. "
                    + "Distribuição por número de objetos visíveis em imagem 2D; não substitui peneiramento nem ensaio validado. "
                    + "Triagem de trincas pela superfície visível: \"sem indício\" não prova ausência de trinca.", 6.8f, MUTED);
        }

        /** Linhas da tabela de classes (cabeçalho + classes do 1º ao último não vazio + total). */
        int classRows() {
            JSONObject h = j.optJSONObject("histogram");
            JSONArray bins = h == null ? null : h.optJSONArray("bins");
            if (bins == null || bins.length() == 0) return 2;
            int first = 0, last = bins.length() - 1;
            while (first < last && bins.optJSONObject(first).optInt("count") == 0) first++;
            while (last > first && bins.optJSONObject(last).optInt("count") == 0) last--;
            return last - first + 3;
        }

        float kpis(float y) {
            JSONObject h = j.optJSONObject("histogram");
            JSONObject c = j.optJSONObject("crack_histogram");
            JSONObject sh = j.optJSONObject("shape_analysis");
            String u = " " + unit;
            String[][] k = {
                    {"Pelotas medidas", h == null ? String.valueOf(j.optInt("count")) : String.valueOf(h.optInt("included")), h == null ? "" : "excluídas: " + h.optInt("excluded")},
                    {"Diâmetro médio", h == null ? "—" : n2(h.opt("mean_" + unit)) + u, "por número"},
                    {"D50", h == null ? "—" : n2(h.opt("d50_" + unit)) + u, "mediana"},
                    {"D10 – D90", h == null ? "—" : n2(h.opt("d10_" + unit)) + " – " + n2(h.opt("d90_" + unit)) + u, span(h)},
                    {"Possíveis trincas", c == null || !c.optBoolean("available", true) ? "—" : c.optInt("cracked_pellets") + " (" + pct1(c.optDouble("cracked_percent_of_evaluable")) + ")", c == null ? "" : "de " + c.optInt("evaluable_pellets") + " avaliáveis"},
                    {"Esfericidade 2D", sh == null ? "—" : n3(sh.opt("mean")), sh == null ? "" : "b/a · n=" + sh.optInt("included")},
            };
            float gap = 6, bw = (W - 2 * M - gap * (k.length - 1)) / k.length, bh = 44;
            for (int i = 0; i < k.length; i++) {
                float x = M + i * (bw + gap);
                rect(x, y, bw, bh, BAND, LIGHT, .6f);
                rect(x, y, 3, bh, ACCENT, -1, 0);
                textCenter(x + bw / 2 + 1.5f, y + 12, k[i][0], 7.2f, false, MUTED);
                String v = ReportFont.safe(k[i][1]);
                float sz = 12.5f;
                while (sz > 8 && ReportFont.width(v, sz, true) > bw - 10) sz -= .5f;
                textCenter(x + bw / 2 + 1.5f, y + 28, v, sz, true, INK);
                textCenter(x + bw / 2 + 1.5f, y + 39, k[i][2], 6.5f, false, MUTED);
            }
            return y + bh;
        }

        void legendaSegmentacao(float x, float y) {
            int[] cores = {OK, REVIEW, BAD};
            String[] t = {"medida", "revisar", "possível trinca"};
            float xx = x;
            for (int i = 0; i < 3; i++) {
                circle(xx + 3, y - 2.5f, 3, -1, cores[i], 1.4f);
                xx += 9 + text(xx + 9, y, t[i], 6.8f, false, MUTED) + 10;
            }
        }

        void placeholder(float x, float y, float w, float h, String s) {
            rect(x, y, w, h, BAND, LIGHT, .5f);
            textCenter(x + w / 2, y + h / 2, s, 8, false, MUTED);
        }

        // ------------------------------------------------------------------ tabela de classes

        float classTable(float x, float y, float w, float bottom) {
            JSONObject h = j.optJSONObject("histogram");
            JSONArray bins = h == null ? null : h.optJSONArray("bins");
            if (bins == null || bins.length() == 0) { text(x, y + 10, "Sem distribuição (sem pelotas incluídas).", 8, false, MUTED); return y + 16; }
            JSONArray cb = null;
            JSONObject ch = j.optJSONObject("crack_histogram");
            if (ch != null && ch.optBoolean("available", false)) cb = ch.optJSONArray("bins");
            int first = 0, last = bins.length() - 1;
            while (first < last && bins.optJSONObject(first).optInt("count") == 0) first++;
            while (last > first && bins.optJSONObject(last).optInt("count") == 0) last--;
            int total = 0;
            for (int i = 0; i < bins.length(); i++) total += bins.optJSONObject(i).optInt("count");
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Classe (" + unit + ")", "Pelotas", "% núm.", "% pass.", "Trinc."});
            int cum = 0;
            for (int i = 0; i < first; i++) cum += bins.optJSONObject(i).optInt("count");
            for (int i = first; i <= last; i++) {
                JSONObject o = bins.optJSONObject(i);
                cum += o.optInt("count");
                String cracked = "—";
                if (cb != null && i < cb.length() && cb.optJSONObject(i) != null) cracked = String.valueOf(cb.optJSONObject(i).optInt("count_cracked"));
                rows.add(new String[]{num(o.opt("lower_" + unit)) + " – " + num(o.opt("upper_" + unit)), String.valueOf(o.optInt("count")),
                        n1(o.opt("percent_by_number")), total > 0 ? n1((Object) (100.0 * cum / total)) : "—", cracked});
            }
            rows.add(new String[]{"Total", String.valueOf(total), "100,0", "", cb == null ? "—" : String.valueOf(ch.optInt("cracked_pellets"))});
            float rh = Math.min(13.5f, Math.max(9.5f, (bottom - y) / rows.size()));
            if (rows.size() * rh > bottom - y) rh = (bottom - y) / rows.size();
            return grid(x, y, w, new float[]{.34f, .17f, .17f, .17f, .15f}, rows, rh, 7.6f, true);
        }

        /** Tabela com moldura (estilo laudo): cabeçalho cinza, células claras, total em negrito. */
        float grid(float x, float y, float w, float[] frac, List<String[]> rows, float rh, float fs, boolean lastBold) {
            for (int r = 0; r < rows.size(); r++) {
                boolean head = r == 0, tot = lastBold && r == rows.size() - 1;
                float cx = x;
                for (int c = 0; c < frac.length; c++) {
                    float cw = frac[c] * w;
                    rect(cx, y, cw, rh, head ? HEAD : (tot ? BAND : (c == 0 ? WHITE : CELL)), INK, .4f);
                    String s = c < rows.get(r).length ? rows.get(r)[c] : "";
                    if (c == 0) textFit(cx + 3, y + rh / 2 + fs * .36f, s, fs, head || tot, INK, cw - 5);
                    else { String t = ReportFont.safe(s); textCenter(cx + cw / 2, y + rh / 2 + fs * .36f, t, fs, head || tot, INK); }
                    cx += cw;
                }
                y += rh;
            }
            return y;
        }

        // ------------------------------------------------------------------ gráficos vetoriais

        void histogram(float x, float y, float w, float h) {
            JSONObject hj = j.optJSONObject("histogram");
            JSONArray bins = hj == null ? null : hj.optJSONArray("bins");
            if (bins == null || bins.length() == 0) return;
            JSONObject ch = j.optJSONObject("crack_histogram");
            JSONArray cb = ch != null && ch.optBoolean("available", false) ? ch.optJSONArray("bins") : null;
            float l = x + 26, r = x + w - 4, t = y + 14, b = y + h - 22;
            int max = 1;
            for (int i = 0; i < bins.length(); i++) max = Math.max(max, bins.optJSONObject(i).optInt("count"));
            int step = niceStep(max / 4.0);
            int top = ((max + step - 1) / step) * step;
            rect(l, t, r - l, b - t, -1, LIGHT, .5f);
            for (int v = 0; v <= top; v += step) {
                float yy = b - (b - t) * v / top;
                if (v > 0) line(l, yy, r, yy, BAND, .5f, null);
                textRight(l - 3, yy + 2.5f, String.valueOf(v), 6.5f, false, MUTED);
            }
            double lo = bins.optJSONObject(0).optDouble("lower_" + unit), hi = bins.optJSONObject(bins.length() - 1).optDouble("upper_" + unit);
            float bw = (r - l) / bins.length();
            for (int i = 0; i < bins.length(); i++) {
                JSONObject o = bins.optJSONObject(i);
                float bx = l + i * bw;
                int n = o.optInt("count");
                float bh = (b - t) * n / top;
                if (n > 0) rect(bx + .8f, b - bh, bw - 1.6f, bh, 0x8CC3D6, ALL, .5f);
                if (cb != null && i < cb.length() && cb.optJSONObject(i) != null) {
                    int k = cb.optJSONObject(i).optInt("count_cracked");
                    float kh = (b - t) * k / top;
                    if (k > 0) rect(bx + bw * .25f, b - kh, bw * .5f, kh, CRACK, -1, 0);
                }
                if (bins.length() <= 16 || i % 2 == 0) textCenter(bx, b + 9, num(o.opt("lower_" + unit)), 6.5f, false, MUTED);
            }
            textCenter(r, b + 9, num(hi), 6.5f, false, MUTED);
            textCenter((l + r) / 2, b + 19, "diâmetro equivalente (" + unit + ")", 7, false, MUTED);
            // D10 / D50 / D90
            String[] tags = {"D10", "D50", "D90"};
            for (int i = 0; i < 3; i++) {
                double d = hj.optDouble(tags[i].toLowerCase(Locale.ROOT) + "_" + unit);
                if (!Double.isFinite(d) || d < lo || d > hi) continue;
                float xx = (float) (l + (r - l) * (d - lo) / (hi - lo));
                line(xx, t, xx, b, i == 1 ? INK : MUTED, i == 1 ? .9f : .6f, new float[]{3, 2});
                textCenter(xx, t + 8 + i * 0, tags[i], 6.5f, true, i == 1 ? INK : MUTED);
            }
            // legenda
            float lw2 = cb == null ? 0 : 11 + ReportFont.width("possíveis trincas", 6.8f, false) + 10;
            float lx = r - (11 + ReportFont.width("todas", 6.8f, false)) - lw2, ly = t - 3;
            rect(lx, ly - 6, 8, 6, 0x8CC3D6, ALL, .5f); float tw1 = text(lx + 11, ly, "todas", 6.8f, false, INK);
            if (cb != null) { rect(lx + 21 + tw1, ly - 6, 8, 6, CRACK, -1, 0); text(lx + 32 + tw1, ly, "possíveis trincas", 6.8f, false, INK); }
            text(x, t - 3, "pelotas", 6.5f, false, MUTED);
        }

        /** Curva passante acumulada (eixo x log) com pontos medidos e a curva Rosin–Rammler da base escolhida. */
        void cumulative(float x, float y, float w, float h) {
            JSONObject rr = j.optJSONObject("rosin_rammler");
            JSONObject hj = j.optJSONObject("histogram");
            JSONArray bins = hj == null ? null : hj.optJSONArray("bins");
            if (bins == null || bins.length() == 0) return;
            RosinRammler.Basis basis = rr == null ? RosinRammler.Basis.NUMBER : RosinRammler.Basis.fromId(rr.optString("selected_basis", "number"));
            JSONObject f = rr == null ? null : rr.optJSONObject(basis.id);
            double lo = Math.max(1e-3, bins.optJSONObject(0).optDouble("lower_" + unit));
            double hi = bins.optJSONObject(bins.length() - 1).optDouble("upper_" + unit);
            if (!(hi > lo)) return;
            float l = x + 30, r = x + w - 6, t = y + 8, b = y + h - 26;
            rect(l, t, r - l, b - t, -1, LIGHT, .5f);
            double llo = Math.log(lo), lhi = Math.log(hi);
            for (int v = 0; v <= 100; v += 20) {
                float yy = b - (b - t) * v / 100f;
                if (v > 0 && v < 100) line(l, yy, r, yy, BAND, .5f, null);
                textRight(l - 3, yy + 2.5f, String.valueOf(v), 6.5f, false, MUTED);
            }
            for (int i = 0; i <= bins.length(); i++) {
                double d = i < bins.length() ? bins.optJSONObject(i).optDouble("lower_" + unit) : hi;
                if (!(d > 0)) continue;
                float xx = (float) (l + (r - l) * (Math.log(d) - llo) / (lhi - llo));
                line(xx, t, xx, b, BAND, .5f, null);
                if (bins.length() <= 14 || i % 2 == 0) textCenter(xx, b + 9, num(d), 6.5f, false, MUTED);
            }
            textCenter((l + r) / 2, b + 20, "diâmetro (" + unit + ") · escala logarítmica", 7, false, MUTED);
            text(x, t - 2, "% passante", 6.8f, false, MUTED);
            // pontos medidos
            JSONArray pts = f == null ? null : f.optJSONArray("points");
            List<float[]> p = new ArrayList<>();
            if (pts != null) for (int i = 0; i < pts.length(); i++) {
                JSONObject o = pts.optJSONObject(i);
                double d = o.optDouble("size"), v = o.optDouble("percent_finer");
                if (d > 0 && Double.isFinite(v)) p.add(new float[]{(float) (l + (r - l) * (Math.log(d) - llo) / (lhi - llo)), (float) (b - (b - t) * v / 100)});
            }
            if (p.isEmpty()) {   // sem RR: acumulado por número a partir das classes
                int tot = 0, cum = 0;
                for (int i = 0; i < bins.length(); i++) tot += bins.optJSONObject(i).optInt("count");
                for (int i = 0; i < bins.length() && tot > 0; i++) {
                    cum += bins.optJSONObject(i).optInt("count");
                    double d = bins.optJSONObject(i).optDouble("upper_" + unit);
                    p.add(new float[]{(float) (l + (r - l) * (Math.log(d) - llo) / (lhi - llo)), (float) (b - (b - t) * cum / (double) tot)});
                }
            }
            float[] line = new float[p.size() * 2];
            for (int i = 0; i < p.size(); i++) { line[2 * i] = p.get(i)[0]; line[2 * i + 1] = p.get(i)[1]; }
            poly(line, INK, .8f, null);
            for (float[] q : p) circle(q[0], q[1], 2.2f, WHITE, INK, .8f);
            // curva ajustada
            if (f != null && f.optBoolean("available", false)) {
                double n = f.optDouble("n"), dp = f.optDouble("d_prime");
                if (n > 0 && dp > 0) {
                    float[] c = new float[2 * 80];
                    for (int i = 0; i < 80; i++) {
                        double d = Math.exp(llo + (lhi - llo) * i / 79.0), v = 100 * (1 - Math.exp(-Math.pow(d / dp, n)));
                        c[2 * i] = (float) (l + (r - l) * i / 79.0); c[2 * i + 1] = (float) (b - (b - t) * v / 100);
                    }
                    poly(c, FIT, 1.3f, new float[]{4, 2.5f});
                }
            }
            float lx = l + 6, ly = t + 10;
            line(lx, ly - 2.5f, lx + 12, ly - 2.5f, INK, .8f, null); circle(lx + 6, ly - 2.5f, 2, WHITE, INK, .8f);
            float a = text(lx + 15, ly, "medido (" + basis.label.toLowerCase(new Locale("pt", "BR")) + ")", 6.8f, false, INK);
            if (f != null && f.optBoolean("available", false)) {
                line(lx + 25 + a, ly - 2.5f, lx + 37 + a, ly - 2.5f, FIT, 1.3f, new float[]{4, 2.5f});
                textFit(lx + 40 + a, ly, f.optString("formula", "Rosin–Rammler"), 6.8f, false, INK, r - lx - 46 - a);
            }
        }

        // ------------------------------------------------------------------ página 2

        void pageDistribuicao() {
            newPage();
            float y = header("Distribuição acumulada, mapa granulométrico e estatísticas");
            float gap = 12, iw = (W - 2 * M - gap) / 2, ih = 222;
            boolean f = sizes.containsKey(Role.FILTERED);
            textCenter(M + iw / 2, y + 9, "Mapa granulométrico", 9.5f, true, INK);
            textCenter(M + iw + gap + iw / 2, y + 9, f ? "Imagem tratada · " + ReportPdfContent.treatmentLabel(j) : "Imagem original", 9.5f, true, INK);
            y += 14;
            if (image(Role.MAP, M, y, iw, ih, null) == null) placeholder(M, y, iw, ih, "mapa indisponível");
            if (image(f ? Role.FILTERED : Role.ORIGINAL, M + iw + gap, y, iw, ih, null) == null) placeholder(M + iw + gap, y, iw, ih, "imagem indisponível");
            y += ih + 4;
            textFit(M, y + 7, mapNote(), 6.8f, false, MUTED, iw);
            textFit(M + iw + gap, y + 7, f ? "Realce só para propor objetos e visualizar; medidas nos pixels originais." : "", 6.8f, false, MUTED, iw);
            y += 18;
            float cw = (W - 2 * M) * .56f, tx = M + cw + 14, tw = W - M - tx;
            y = sectionTitle(M, y, W - 2 * M, "Curva granulométrica acumulada e ajuste Rosin–Rammler");
            float ch = 222;
            cumulative(M, y, cw, ch);
            float yy = stats(tx, y, tw);
            yy = rrTable(tx, yy + 8, tw);
            y = Math.max(y + ch, yy) + 6;
            y = paragraph(M, y, W - 2 * M, "F(d) = 100·[1 − exp(−(d/d′)ⁿ)], F = % passante acumulado; ajuste por máxima verossimilhança nas pelotas individuais. "
                    + "R² da curva acumulada é sempre alto e não prova bom ajuste: RMSE (pontos percentuais) e KS são mais informativos. "
                    + "A base \"massa estimada\" supõe esferas de mesma densidade (∝ d³) a partir de diâmetros 2D e não é comparável a parâmetros RR de peneiramento.", 6.8f, MUTED);
            cursor = y;
        }

        float stats(float x, float y, float w) {
            JSONObject h = j.optJSONObject("histogram");
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Estatística", "Valor"});
            String u = " " + unit;
            if (h != null) {
                rows.add(new String[]{"Pelotas no histograma", h.optInt("included") + " (excl. " + h.optInt("excluded") + ")"});
                rows.add(new String[]{"Média ± desvio-padrão", n2(h.opt("mean_" + unit)) + " ± " + n2((Object) stdDiameter()) + u});
                rows.add(new String[]{"Mínimo / máximo", n2(h.opt("min_" + unit)) + " / " + n2(h.opt("max_" + unit)) + u});
                rows.add(new String[]{"D10 / D50 / D90", n2(h.opt("d10_" + unit)) + " / " + n2(h.opt("d50_" + unit)) + " / " + n2(h.opt("d90_" + unit)) + u});
                rows.add(new String[]{"Span (D90 − D10)/D50", span(h).replace("span ", "")});
                rows.add(new String[]{"Largura de classe", num(h.opt("bin_width_" + unit)) + u});
            }
            JSONObject sh = j.optJSONObject("shape_analysis");
            if (sh != null) rows.add(new String[]{"Esfericidade aparente 2D (b/a)", n3(sh.opt("mean")) + " · n=" + sh.optInt("included")});
            return grid(x, y, w, new float[]{.55f, .45f}, rows, 12.5f, 7.4f, false);
        }

        float rrTable(float x, float y, float w) {
            JSONObject rr = j.optJSONObject("rosin_rammler");
            if (rr == null) return y;
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Rosin–Rammler", "Número", "Massa est."});
            JSONObject a = rr.optJSONObject("number"), b = rr.optJSONObject("estimated_mass_d3");
            rows.add(new String[]{"n (uniformidade)", rrv(a, "n", false), rrv(b, "n", false)});
            rows.add(new String[]{"d′ (" + unit + ")", rrv(a, "d_prime", false), rrv(b, "d_prime", false)});
            rows.add(new String[]{"d50 ajustado (" + unit + ")", rrv(a, "d50_fit", false), rrv(b, "d50_fit", false)});
            rows.add(new String[]{"R² · RMSE (p.p.)", rrv(a, "r2_at_class_limits", true) + " · " + rrv(a, "rmse_percent_points", false), rrv(b, "r2_at_class_limits", true) + " · " + rrv(b, "rmse_percent_points", false)});
            rows.add(new String[]{"KS", rrv(a, "ks_individual", true), rrv(b, "ks_individual", true)});
            return grid(x, y, w, new float[]{.42f, .29f, .29f}, rows, 12.5f, 7.4f, false);
        }
        String rrv(JSONObject f, String k, boolean four) {
            if (f == null || !f.optBoolean("available", false)) return "—";
            double v = f.optDouble(k);
            return four ? RosinRammler.fmt4(v) : RosinRammler.fmt(v);
        }

        // ------------------------------------------------------------------ página 3: trincas (quando houver)

        void pageTrincas() {
            JSONObject c = j.optJSONObject("cracks");
            JSONArray dets = j.optJSONArray("detections");
            if (c == null || dets == null || "not_analyzed".equals(c.optString("status"))) return;
            List<JSONObject> shown = new ArrayList<>();
            for (int i = 0; i < dets.length(); i++) {
                JSONObject d = dets.optJSONObject(i);
                if (d != null && "POSSIBLE".equals(d.optString("crack_status"))) shown.add(d);
            }
            for (int i = 0; i < dets.length(); i++) {
                JSONObject d = dets.optJSONObject(i);
                if (d != null && d.optBoolean("crack_manually_reviewed") && !"POSSIBLE".equals(d.optString("crack_status"))) shown.add(d);
            }
            if (shown.isEmpty() || !sizes.containsKey(Role.ORIGINAL)) return;
            float gap = 7, limit = H - M - 40;
            // Encaixa na página corrente: escolhe de 6 a 9 colunas para caberem todos os recortes; se nem com
            // recortes pequenos couber pelo menos uma linha, a seção abre página própria.
            int cols = 6;
            float cw = 0, chh = 0, avail = limit - (cursor + 24 + 32);
            for (int c2 = 6; c2 <= 9; c2++) {
                cols = c2; cw = (W - 2 * M - gap * (cols - 1)) / cols; chh = cw + 21;
                int rowsFit = (int) Math.floor((avail + gap) / (chh + gap));
                if (rowsFit * cols >= shown.size()) break;
            }
            float y;
            if (avail >= chh) y = sectionTitle(M, cursor + 6, W - 2 * M, "Triagem de trincas");
            else { newPage(); y = header("Triagem de trincas"); cols = 6; cw = (W - 2 * M - gap * 5) / 6; chh = cw + 21; }
            String resumo = "Possíveis trincas: " + c.optInt("possible_crack_pellets") + " · sem indício: " + c.optInt("no_indication_pellets")
                    + " · inconclusivas (fora da contagem): " + c.optInt("inconclusive_pellets") + " · revisadas pelo operador: " + c.optInt("manually_reviewed_pellets")
                    + ". Classificador " + classifierPt(c) + "; escore = saída da rede sem calibração. Recortes nos pixels originais; vermelho: possível trinca; contorno azul: decisão revisada.";
            y = paragraph(M, y - 4, W - 2 * M, resumo, 7.2f, INK) + 2;
            int k = 0;
            for (JSONObject d : shown) {
                float cy = y + (k / cols) * (chh + gap);
                if (cy + chh > limit) { newPage(); y = header("Triagem de trincas (continuação)"); k = 0; cy = y; }
                crackCell(d, M + (k % cols) * (cw + gap), cy, cw);
                k++;
            }
            cursor = y + ((k + cols - 1) / cols) * (chh + gap);
        }

        void crackCell(JSONObject d, float x, float y, float w) {
            float[] src = cropFor(d);
            boolean possible = "POSSIBLE".equals(d.optString("crack_status"));
            boolean reviewed = d.optBoolean("crack_manually_reviewed");
            if (src == null || image(Role.ORIGINAL, x, y, w, w, src) == null) placeholder(x, y, w, w, "sem recorte");
            rect(x, y, w, w, -1, possible ? BAD : MUTED, possible ? 1.4f : .8f);
            if (reviewed) rect(x + 2, y + 2, w - 4, w - 4, -1, FIT, .9f);
            rect(x, y, 22, 11, possible ? BAD : MUTED, -1, 0);
            textCenter(x + 11, y + 8, "#" + d.optInt("id"), 6.8f, true, WHITE);
            textFit(x, y + w + 9, statusLabel(d.optString("crack_status")) + (reviewed ? " · revisada" : ""), 6.8f, true, possible ? BAD : INK, w);
            textFit(x, y + w + 18, "escore " + n2(d.opt("crack_score")) + " · Ø " + n2(d.opt("equivalent_diameter_" + unit)) + " " + unit, 6.5f, false, MUTED, w);
        }

        /** Recorte (px do painel ORIGINAL) em torno da pelota: 1,35× o raio, quadrado. */
        float[] cropFor(JSONObject d) {
            int[] s = sizes.get(Role.ORIGINAL);
            if (s == null) return null;
            double x = d.optDouble("x_px"), y = d.optDouble("y_px");
            double r = d.optDouble("crack_crop_radius_px", d.optDouble("radius_px"));
            if (!Double.isFinite(x) || !Double.isFinite(y) || !(r > 0)) return null;
            JSONObject v = j.optJSONObject("report_viewport");
            double k, ox = 0, oy = 0;
            if (v != null && v.optDouble("render_scale") > 0) { k = v.optDouble("render_scale"); ox = v.optDouble("crop_left", 0); oy = v.optDouble("crop_top", 0); }
            else { int aw = j.optInt("analysis_photo_width", s[0]); k = s[0] / (double) Math.max(1, aw); }
            double half = 1.35 * r * k, cx = (x - ox) * k, cy = (y - oy) * k;
            double x0 = Math.max(0, cx - half), y0 = Math.max(0, cy - half), x1 = Math.min(s[0], cx + half), y1 = Math.min(s[1], cy + half);
            if (x1 - x0 < 4 || y1 - y0 < 4) return null;
            return new float[]{(float) x0, (float) y0, (float) (x1 - x0), (float) (y1 - y0)};
        }

        // ------------------------------------------------------------------ última página: rastreabilidade

        void pageRastreabilidade() {
            newPage();
            float top = header("Rastreabilidade");
            List<Object[]> secs = new ArrayList<>();
            secs.add(new Object[]{"Documento", documento()});
            secs.add(new Object[]{"Imagem e captura", imagem()});
            secs.add(new Object[]{"Tratamento de iluminação", iluminacao()});
            secs.add(new Object[]{"Calibração de escala", calibracao()});
            secs.add(new Object[]{"Região de interesse", roi()});
            secs.add(new Object[]{"Localização e segmentação", segmentacao()});
            secs.add(new Object[]{"Refinamento SAM 2", sam2()});
            secs.add(new Object[]{"Triagem de trincas", trincas()});
            secs.add(new Object[]{"Distribuição e forma", distribuicao()});
            // Duas colunas; transborda para nova página quando preciso.
            float gap = 16, cw = (W - 2 * M - gap) / 2, bottom = H - M - 30;
            float[] col = {top, top};
            int c = 0;
            for (Object[] s : secs) {
                @SuppressWarnings("unchecked") List<String[]> rows = (List<String[]>) s[1];
                if (rows.isEmpty()) continue;
                float need = 20 + rowsHeight(rows, cw);
                if (col[c] + need > bottom) { c = 1 - c; if (col[c] + need > bottom) { newPage(); top = header("Rastreabilidade (continuação)"); col[0] = col[1] = top; c = 0; } }
                float x = M + c * (cw + gap);
                col[c] = sectionTitle(x, col[c], cw, (String) s[0]);
                col[c] = kv(x, col[c], cw, rows) + 6;
                if (col[1 - c] < col[c]) c = 1 - c;
            }
            float y = Math.max(col[0], col[1]) + 4;
            String lim = "Limitações: medida 2D projetada (não é esfericidade 3D nem peneiramento). Pelotas encostadas e de baixo contraste podem fundir ou encolher. "
                    + "Modelos marcados como experimentais não foram validados com amostras de produção. O JSON e o CSV de mesmo nome contêm todos os valores por pelota.";
            if (y + 40 > bottom) { newPage(); y = header("Rastreabilidade (continuação)"); }
            paragraph(M, y, W - 2 * M, lim, 7, MUTED);
        }

        float rowsHeight(List<String[]> rows, float w) {
            float h = 0;
            for (String[] r : rows) h += Math.max(1, wrap(r[1], 7.2f, false, w * .62f - 4).size()) * 9.2f + 2.2f;
            return h;
        }
        float kv(float x, float y, float w, List<String[]> rows) {
            float kw = w * .38f;
            for (int i = 0; i < rows.size(); i++) {
                List<String> vl = wrap(rows.get(i)[1], 7.2f, false, w - kw - 4);
                float h = Math.max(1, vl.size()) * 9.2f + 2.2f;
                if (i % 2 == 0) rect(x, y, w, h, BAND, -1, 0);
                textFit(x + 2, y + 8, rows.get(i)[0], 7.2f, true, INK, kw - 4);
                for (int k = 0; k < vl.size(); k++) text(x + kw, y + 8 + k * 9.2f, vl.get(k), 7.2f, false, INK);
                y += h;
            }
            return y;
        }

        List<String[]> documento() {
            List<String[]> r = new ArrayList<>();
            kvAdd(r, "Relatório", j.optString("pdf_file", null));
            kvAdd(r, "Dados (JSON/CSV)", j.optString("pdf_file", "").replace("_relatorio.pdf", "") + ".json / .csv");
            kvAdd(r, "Gerado em", when());
            kvAdd(r, "Programa", j.optString("app", null));
            kvAdd(r, "Esquema do JSON", j.has("report_schema_version") ? String.valueOf(j.optInt("report_schema_version")) : null);
            kvAdd(r, "Pasta", j.optString("output_folder", null));
            kvAdd(r, "Amostra / teste", sample());
            kvAdd(r, "Revisado pelo operador", j.optBoolean("reviewed") ? "sim" : "não");
            kvAdd(r, "Tempo de análise", j.has("analysis_ms") ? j.optLong("analysis_ms") + " ms" : null);
            return r;
        }
        List<String[]> imagem() {
            List<String[]> r = new ArrayList<>();
            JSONObject s = j.optJSONObject("source");
            kvAdd(r, "Arquivo original", j.optString("original_file", null));
            if (s != null) {
                kvAdd(r, "Origem", s.optString("type", "—") + " · " + s.optString("platform", ""));
                kvAdd(r, "Resolução", s.optInt("original_width") + " × " + s.optInt("original_height") + " px (analisada " + s.optInt("analyzed_width") + " × " + s.optInt("analyzed_height") + ")");
                JSONObject cam = s.optJSONObject("camera");
                if (cam != null) {
                    kvAdd(r, "Câmera", "índice " + cam.optInt("camera_index") + " · " + cam.optString("backend", "") + " · " + cam.optString("fourcc", "") + " · " + cam.optInt("width") + "×" + cam.optInt("height"));
                    kvAdd(r, "Exposição / foco", "exposição travada: " + yn(cam.optBoolean("auto_exposure_off_accepted_by_driver")) + " · foco travado: " + yn(cam.optBoolean("autofocus_off_accepted_by_driver")));
                }
                kvAdd(r, "OpenCV", s.optString("opencv_version", null));
            }
            JSONObject p = j.optJSONObject("processing_scope");
            if (p != null) kvAdd(r, "Recorte analisado", p.optInt("input_width") + " × " + p.optInt("input_height") + " px a partir de (" + p.optInt("local_to_full_x") + ", " + p.optInt("local_to_full_y") + "); pixels fora da ROI analisados: " + yn(p.optBoolean("outside_roi_original_pixels_analyzed")));
            return r;
        }
        List<String[]> iluminacao() {
            List<String[]> r = new ArrayList<>();
            JSONObject l = j.optJSONObject("low_light");
            if (l == null) { kvAdd(r, "Tratamento", "não registrado"); return r; }
            kvAdd(r, "Tratamento", ReportPdfContent.treatmentLabel(j) + " · " + LowLightInfo.selectionLabel(l.optString("selection", "not_applied")));
            JSONObject q = l.optJSONObject("illumination");
            if (q != null) {
                kvAdd(r, "Cinza médio · desvio", num(q.opt("mean_gray")) + " · " + num(q.opt("std_gray")));
                kvAdd(r, "Saturados preto/branco", ReportPdfContent.pct(q.optDouble("clipped_black_fraction")) + " / " + ReportPdfContent.pct(q.optDouble("clipped_white_fraction")));
            }
            kvAdd(r, "Uso", "realce só propõe objetos; elipses e recortes de trinca nos pixels originais");
            return r;
        }
        List<String[]> calibracao() {
            List<String[]> r = new ArrayList<>();
            JSONObject s = j.optJSONObject("physical_scale");
            if (s == null) { kvAdd(r, "Escala", "sem calibração física: valores em pixels"); return r; }
            kvAdd(r, "Escala no centro", num(s.opt("original_pixels_per_mm")) + " px/mm" + (s.optBoolean("estimated") ? " (estimada)" : ""));
            kvAdd(r, "Fonte", sourceLabel(s.optString("source", "—")));
            JSONObject o = s.optJSONObject("selected_object");
            if (o != null) {
                kvAdd(r, "Referência", measureLabel(o.optString("measure", "—")) + " · " + num(o.opt("real_dimension_mm")) + " mm = " + num(o.opt("original_reference_length_px")) + " px");
                JSONObject pc = o.optJSONObject("perspective_correction");
                if (pc != null) kvAdd(r, "Correção de perspectiva", "k = " + num(pc.opt("k")) + " (" + pc.optString("source", "—") + ") · " + pc.optInt("spheres") + " esfera(s) · escala local = centro × (1 + k·ρ²)");
                kvAdd(r, "Referência nas estatísticas", o.optBoolean("exclude_from_statistics") ? "excluída" : "incluída");
            }
            JSONObject box = s.optJSONObject("container_reference");
            if (box != null) kvAdd(r, "Recipiente", "Ø interno " + num(box.opt("real_inner_diameter_mm")) + " mm · " + box.optString("measurement_plane", "—"));
            JSONObject belt = s.optJSONObject("belt_reference");
            if (belt != null) kvAdd(r, "Correia", "largura de referência " + num(belt.opt("real_width_mm")) + " mm");
            return r;
        }
        List<String[]> roi() {
            List<String[]> r = new ArrayList<>();
            JSONObject o = j.optJSONObject("region_of_interest");
            if (o == null) { kvAdd(r, "Região", "imagem inteira"); return r; }
            kvAdd(r, "Forma", shapeLabel(o.optString("shape")) + (o.has("automatic") ? (o.optBoolean("automatic") ? " · automática" : " · manual") : ""));
            if (o.has("detected_as")) kvAdd(r, o.optBoolean("automatic") ? "Detectada como" : "Proposta automática",
                    o.optString("detected_label", o.optString("detected_as")) + (o.has("auto_confidence") ? String.format(Locale.ROOT, " · confiança %.0f%%", 100 * o.optDouble("auto_confidence")) : "")
                            + (o.has("auto_reason") ? " (" + o.optString("auto_reason") + ")" : ""));
            kvAdd(r, "Limites (px)", "x " + num(o.opt("left_px")) + "–" + num(o.opt("right_px")) + " · y " + num(o.opt("top_px")) + "–" + num(o.opt("bottom_px")));
            kvAdd(r, "Objetos fora/cruzando", String.valueOf(o.optInt("excluded_outside_or_crossing")));
            kvAdd(r, "Regra", "só entram pelotas com a elipse inteira dentro da região");
            return r;
        }
        List<String[]> segmentacao() {
            List<String[]> r = new ArrayList<>();
            JSONObject sc = j.optJSONObject("scene");
            if (sc != null) kvAdd(r, "Cena", scene() + " · lado de trabalho " + sc.optInt("work_side_px") + " px");
            JSONObject am = j.optJSONObject("analysis_models");
            JSONObject seg = am == null ? null : am.optJSONObject("segmentation");
            if (seg != null) kvAdd(r, "Localização", seg.optString("selected", "—"));
            JSONObject nn = j.optJSONObject("neural_model");
            if (nn != null) {
                kvAdd(r, "Rede", nn.optString("model_id", "—") + (nn.optBoolean("demonstration_model") ? " (demonstração)" : ""));
                kvAdd(r, "SHA-256 da rede", shortSha(nn.optString("model_sha256", null)));
                kvAdd(r, "Rótulos de treino", nn.optString("label_origin", "—") + " · validada em fotos da usina: " + yn(nn.optBoolean("validated_on_plant_photos")));
            }
            kvAdd(r, "Método registrado", j.optString("method", null));
            return r;
        }
        List<String[]> sam2() {
            List<String[]> r = new ArrayList<>();
            JSONObject s = j.optJSONObject("sam2_refinement");
            if (s == null) return r;
            kvAdd(r, "Modelo", s.optString("model_id", "—") + " · ajuste fino em pelotas: " + yn(s.optBoolean("fine_tuned_on_pellets")));
            kvAdd(r, "SHA-256 checkpoint", shortSha(s.optString("checkpoint_sha256", null)));
            kvAdd(r, "SHA-256 encoder/decoder", shortSha(s.optString("encoder_sha256", null)) + " / " + shortSha(s.optString("decoder_sha256", null)));
            JSONObject lr = s.optJSONObject("last_run");
            if (lr != null) {
                kvAdd(r, "Pelotas refinadas", lr.optInt("refined") + " de " + lr.optInt("prompts") + " · mantidas do detector " + lr.optInt("kept_from_detector") + " · para revisão " + lr.optInt("review"));
                kvAdd(r, "Recorte pela borda", lr.optInt("edge_snapped") + " pelotas");
                kvAdd(r, "Motor e tempo", lr.optString("engine", "—") + " · " + lr.optLong("total_ms") + " ms (" + lr.optInt("tiles") + " blocos)");
            }
            kvAdd(r, "Licença", s.optString("license", null));
            return r;
        }
        List<String[]> trincas() {
            List<String[]> r = new ArrayList<>();
            JSONObject c = j.optJSONObject("cracks");
            if (c == null) { kvAdd(r, "Triagem", "não analisada"); return r; }
            kvAdd(r, "Classificador", classifierPt(c));
            kvAdd(r, "Modelo", c.optString("method", null));
            kvAdd(r, "Sensibilidade", c.has("sensitivity") ? String.valueOf(c.optInt("sensitivity")) : null);
            kvAdd(r, "Escore", "saída da rede sem calibração (não é probabilidade)");
            kvAdd(r, "Recortes", "tons de cinza originais da foto (nunca a imagem super-resolvida)");
            kvAdd(r, "Leitura", "triagem pela superfície visível; \"sem indício\" não prova ausência de trinca");
            return r;
        }
        List<String[]> distribuicao() {
            List<String[]> r = new ArrayList<>();
            JSONObject h = j.optJSONObject("histogram");
            if (h != null) {
                kvAdd(r, "Base", "número de pelotas · percentis por interpolação linear");
                kvAdd(r, "Classes", "limite inferior incluído e superior excluído (última classe fechada); largura " + num(h.opt("bin_width_" + unit)) + " " + unit);
                kvAdd(r, "Exclusões", "parciais na borda: " + yn(h.optBoolean("exclude_partial_edge")) + " · em revisão: " + yn(h.optBoolean("exclude_needs_review")));
            }
            JSONObject rr = j.optJSONObject("rosin_rammler");
            if (rr != null) kvAdd(r, "Rosin–Rammler (gráfico)", RosinRammler.Basis.fromId(rr.optString("selected_basis", "number")).label);
            JSONObject sh = j.optJSONObject("shape_analysis");
            if (sh != null) kvAdd(r, "Forma", "esfericidade aparente 2D (b/a da elipse) · exclui parciais, em revisão, fora da região e a referência de escala; não é esfericidade 3D");
            return r;
        }

        // ------------------------------------------------------------------ textos curtos

        String classifierPt(JSONObject c) {
            String s = c.optString("classifier", c.optString("method", "—"));
            return s.replace("(experimental, not validated)", "(experimental, não validado)").replace("experimental, not validated", "experimental, não validado");
        }
        String sample() { String t = j.isNull("test_name") ? "" : j.optString("test_name", "").trim(); return t.isEmpty() ? "—" : t; }
        String when() {
            long ms = j.optLong("created_at_epoch_ms", 0);
            return ms <= 0 ? "—" : new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", new Locale("pt", "BR")).format(new Date(ms));
        }
        String scene() {
            JSONObject s = j.optJSONObject("scene");
            String m = s == null ? "" : s.optString("mode", "");
            return "belt".equals(m) ? "Correia transportadora" : "bench".equals(m) ? "Bancada / recipiente" : (m.isEmpty() ? "—" : m);
        }
        String capture() {
            JSONObject s = j.optJSONObject("source");
            if (s == null) return "—";
            String type = s.optString("type", "foto");
            return ("webcam".equals(type) ? "Webcam" : "camera".equals(type) ? "Câmera" : type) + " " + s.optInt("original_width") + "×" + s.optInt("original_height");
        }
        String scaleShort() {
            JSONObject s = j.optJSONObject("physical_scale");
            if (s == null) return "sem calibração (px)";
            String src = sourceLabel(s.optString("source", ""));
            JSONObject o = s.optJSONObject("selected_object");
            if (o != null && "STANDARD_SPHERE".equals(o.optString("measure"))) src = "esfera padrão Ø " + num(o.opt("real_dimension_mm")) + " mm";
            JSONObject pc = o == null ? null : o.optJSONObject("perspective_correction");
            return num(s.opt("original_pixels_per_mm")) + " px/mm · " + src + (pc != null && pc.optDouble("k") != 0 ? " · k=" + num(pc.opt("k")) : "");
        }
        String roiShort() {
            JSONObject o = j.optJSONObject("region_of_interest");
            if (o == null) return "imagem inteira";
            return shapeLabel(o.optString("shape")) + (o.optBoolean("automatic") ? " automática" : "") + " · " + o.optInt("excluded_outside_or_crossing") + " fora/na borda";
        }
        String imageNote() { return j.optJSONObject("region_of_interest") != null ? "Região de interesse ampliada; pixels originais, sem marcações." : "Pixels originais, sem marcações."; }
        String mapNote() {
            JSONObject m = j.optJSONObject("granulometric_map");
            return "Cor pela classe de diâmetro de cada pelota incluída" + (m == null ? "." : "; escala " + m.optString("scale_mode", "") + " " + n2(m.opt("min")) + "–" + n2(m.opt("max")) + " " + unit + ".");
        }
        String span(JSONObject h) {
            if (h == null) return "";
            double d10 = h.optDouble("d10_" + unit), d50 = h.optDouble("d50_" + unit), d90 = h.optDouble("d90_" + unit);
            return d50 > 0 ? "span " + n2((Object) ((d90 - d10) / d50)) : "";
        }
        double stdDiameter() {
            JSONArray d = j.optJSONArray("detections");
            if (d == null) return Double.NaN;
            double s = 0, s2 = 0; int n = 0;
            for (int i = 0; i < d.length(); i++) {
                JSONObject o = d.optJSONObject(i);
                if (o == null || !o.optBoolean("included_in_histogram", true)) continue;
                double v = o.optDouble("equivalent_diameter_" + unit);
                if (!Double.isFinite(v)) continue;
                s += v; s2 += v * v; n++;
            }
            return n > 1 ? Math.sqrt(Math.max(0, (s2 - s * s / n) / (n - 1))) : Double.NaN;
        }
    }

    // ------------------------------------------------------------------ formatação

    static void kvAdd(List<String[]> r, String k, String v) { if (v != null && !v.isEmpty() && !"null".equals(v)) r.add(new String[]{k, v}); }
    static String yn(boolean b) { return b ? "sim" : "não"; }
    static String shortSha(String s) { return s == null || s.length() < 16 ? (s == null ? "—" : s) : s.substring(0, 16) + "…"; }
    static String statusLabel(String s) {
        switch (s) { case "POSSIBLE": return "Possível trinca"; case "NO_INDICATION": return "Sem indício"; case "INCONCLUSIVE": return "Inconclusiva"; default: return s; }
    }
    static String measureLabel(String s) {
        switch (s) { case "STANDARD_SPHERE": return "esfera padrão"; case "DIAMETER": return "diâmetro"; case "LENGTH": return "comprimento"; default: return s.replace('_', ' ').toLowerCase(Locale.ROOT); }
    }
    static String shapeLabel(String s) { return "CIRCLE".equals(s) ? "Circular" : "RECTANGLE".equals(s) ? "Retangular" : s; }
    static String sourceLabel(String s) {
        switch (s) {
            case "user_selected_object": return "objeto de referência";
            case "container": case "container_inner_diameter": return "diâmetro interno do recipiente";
            case "belt": case "belt_width": return "largura da correia";
            case "assumed_mean": return "média assumida";
            default: return s.isEmpty() ? "—" : s.replace('_', ' ');
        }
    }
    static String num(Object v) { return ReportPdfContent.num(v); }
    static String n1(Object v) { return fmt(v, "%.1f"); }
    static String n2(Object v) { return fmt(v, "%.2f"); }
    static String n3(Object v) { return fmt(v, "%.3f"); }
    static String pct1(double v) { return Double.isFinite(v) ? String.format(new Locale("pt", "BR"), "%.1f%%", v) : "—"; }
    private static String fmt(Object v, String f) {
        if (!(v instanceof Number)) return "—";
        double d = ((Number) v).doubleValue();
        return Double.isFinite(d) ? String.format(new Locale("pt", "BR"), f, d) : "—";
    }
    static int niceStep(double raw) {
        if (raw <= 1) return 1;
        double p = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double m : new double[]{1, 2, 5, 10}) if (m * p >= raw) return (int) Math.max(1, Math.round(m * p));
        return (int) Math.round(10 * p);
    }

    /** Papéis presentes, na ordem de leitura (para testes e diagnóstico). */
    public static List<Role> roles(Map<Role, int[]> sizes) { List<Role> r = new ArrayList<>(sizes.keySet()); Collections.sort(r); return r; }
}
