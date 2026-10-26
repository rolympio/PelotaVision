package br.com.pelotavision;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Conteúdo do relatório PDF (0.15.2), comum a Android e Windows. Monta, a partir do MESMO JSON gravado
 * ao lado, a sequência de blocos de texto (título, seções, tabelas, notas) e os títulos/legendas das
 * figuras. Cada plataforma só desenha: Android com PdfDocument, Windows com PDFBox. Assim os dois
 * relatórios não divergem.
 */
public final class ReportPdfContent {
    private ReportPdfContent() {}

    public enum Kind { TITLE, LINE, RULE, SECTION, TABLE, GRID, NOTE }
    /** Tom do texto: tinta principal ou cinza secundário. */
    public enum Tone { INK, MUTED }

    public static final class Block {
        public final Kind kind;
        public final String text;
        public final float size;
        public final boolean bold;
        public final Tone tone;
        /** TABLE: pares rótulo/valor. GRID: primeira linha é o cabeçalho. */
        public final List<String[]> rows;
        public final float[] fractions;

        private Block(Kind kind, String text, float size, boolean bold, Tone tone, List<String[]> rows, float[] fractions) {
            this.kind = kind; this.text = text; this.size = size; this.bold = bold; this.tone = tone;
            this.rows = rows == null ? Collections.<String[]>emptyList() : rows; this.fractions = fractions;
        }
        static Block title(String t) { return new Block(Kind.TITLE, t, 20, true, Tone.INK, null, null); }
        static Block line(String t, float size) { return new Block(Kind.LINE, t, size, false, Tone.MUTED, null, null); }
        static Block rule() { return new Block(Kind.RULE, "", 0, false, Tone.INK, null, null); }
        static Block section(String t) { return new Block(Kind.SECTION, t, 11.5f, true, Tone.INK, null, null); }
        static Block table(List<String[]> rows) { return new Block(Kind.TABLE, "", 9.5f, false, Tone.INK, rows, null); }
        static Block grid(List<String[]> rows, float[] fractions) { return new Block(Kind.GRID, "", 9, false, Tone.INK, rows, fractions); }
        static Block note(String t, float size) { return new Block(Kind.NOTE, t, size, false, Tone.MUTED, null, null); }
    }

    /** Título e legenda de uma figura; a imagem fica com a plataforma. */
    public static final class FigureText {
        public final String title, caption;
        FigureText(String title, String caption) { this.title = title; this.caption = caption; }
    }

    // ---------- blocos de texto ----------

    public static List<Block> blocks(JSONObject j) {
        List<Block> out = new ArrayList<>();
        header(out, j);
        summary(out, j);
        lowLight(out, j);
        models(out, j);
        bins(out, j);
        notes(out);
        return out;
    }

    private static void header(List<Block> out, JSONObject j) {
        out.add(Block.title("Relatório de análise de pelotas"));
        long ms = j.optLong("created_at_epoch_ms", System.currentTimeMillis());
        String when = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", new Locale("pt", "BR")).format(new Date(ms));
        out.add(Block.line(j.optString("app", "PelotaVision") + "  ·  " + when, 10));
        out.add(Block.line("Arquivos: " + j.optString("annotated_file", "—").replace("_resultado.png", "") + "_* (" + j.optString("files_location", "Imagens e Downloads / PelotaVision") + ")", 9));
        out.add(Block.rule());
    }

    private static void summary(List<Block> out, JSONObject j) {
        out.add(Block.section("Resumo"));
        JSONObject h = j.optJSONObject("histogram");
        String unit = h == null ? "px" : h.optString("unit", "px");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Pelotas detectadas", String.valueOf(j.optInt("count", 0))});
        if (h != null) {
            rows.add(new String[]{"No histograma (incluídas / excluídas)", h.optInt("included") + " / " + h.optInt("excluded")});
            rows.add(new String[]{"Diâmetro médio", num(h.opt("mean_" + unit)) + " " + unit});
            rows.add(new String[]{"Mínimo / máximo", num(h.opt("min_" + unit)) + " / " + num(h.opt("max_" + unit)) + " " + unit});
            rows.add(new String[]{"D10 / D50 / D90", num(h.opt("d10_" + unit)) + " / " + num(h.opt("d50_" + unit)) + " / " + num(h.opt("d90_" + unit)) + " " + unit});
            rows.add(new String[]{"Largura de classe", num(h.opt("bin_width_" + unit)) + " " + unit + (h.optBoolean("bin_width_auto_adjusted") ? " (ajustada automaticamente)" : "")});
        }
        JSONObject s = j.optJSONObject("physical_scale");
        if (s != null) rows.add(new String[]{"Escala", num(s.opt("original_pixels_per_mm")) + " px/mm · " + s.optString("source", "—") + (s.optBoolean("estimated") ? " (estimada)" : "")});
        else rows.add(new String[]{"Escala", "sem calibração física — valores em pixels"});
        JSONObject c = j.optJSONObject("cracks");
        if (c == null || "not_analyzed".equals(c.optString("status"))) rows.add(new String[]{"Trincas", "não analisadas"});
        else {
            rows.add(new String[]{"Trincas: possíveis / sem indício / inconclusivas", c.optInt("possible_crack_pellets") + " / " + c.optInt("no_indication_pellets") + " / " + c.optInt("inconclusive_pellets")});
            rows.add(new String[]{"Trincas revisadas manualmente", String.valueOf(c.optInt("manually_reviewed_pellets"))});
        }
        JSONObject roi = j.optJSONObject("region_of_interest");
        rows.add(new String[]{"Região de interesse", roi == null ? "imagem inteira" : roi.optString("shape", "ROI") + " · " + roi.optInt("excluded_outside_or_crossing") + " objeto(s) fora/cruzando a borda"});
        rows.add(new String[]{"Tempo de análise", j.optLong("analysis_ms") + " ms"});
        out.add(Block.table(rows));
    }

    private static void lowLight(List<Block> out, JSONObject j) {
        out.add(Block.section("Iluminação e tratamento de imagem escura"));
        JSONObject l = j.optJSONObject("low_light");
        if (l == null) { out.add(Block.note("Não registrado nesta análise (modo detalhado ou versão anterior à 0.15.0).", 9)); return; }
        List<String[]> rows = new ArrayList<>();
        JSONObject q = l.optJSONObject("illumination");
        if (q != null) {
            rows.add(new String[]{"Nível médio de cinza (0–255)", num(q.opt("mean_gray"))});
            rows.add(new String[]{"Desvio-padrão (contraste)", num(q.opt("std_gray"))});
            rows.add(new String[]{"Pixels saturados em preto / branco", pct(q.optDouble("clipped_black_fraction")) + " / " + pct(q.optDouble("clipped_white_fraction"))});
            rows.add(new String[]{"Imagem escura ou de baixo contraste?", q.optBoolean("needs_correction") ? "sim" : "não"});
        }
        rows.add(new String[]{"Seleção do tratamento", LowLightInfo.selectionLabel(l.optString("selection", "not_applied"))});
        rows.add(new String[]{"Tratamento aplicado", LowLightInfo.label(mode(l))});
        if (l.opt("original_score") instanceof Number) rows.add(new String[]{"Escore heurístico: original → escolhido", num(l.opt("original_score")) + " → " + num(l.opt("chosen_score"))});
        out.add(Block.table(rows));
        out.add(Block.note("O realce serve só para propor objetos. Elipses, diâmetros e recortes de trinca são medidos nos pixels ORIGINAIS. Os escores são heurísticos de cobertura, não acurácia nem confiança.", 8));
    }

    private static void models(List<Block> out, JSONObject j) {
        out.add(Block.section("Método e modelos"));
        List<String[]> rows = new ArrayList<>();
        JSONObject sc = j.optJSONObject("scene");
        if (sc != null) rows.add(new String[]{"Cena", sc.optString("mode", "—")});
        JSONObject am = j.optJSONObject("analysis_models");
        if (am != null) {
            JSONObject seg = am.optJSONObject("segmentation");
            if (seg != null) rows.add(new String[]{"Localização / segmentação", seg.optString("selected", "—")});
            JSONObject cls = am.optJSONObject("crack_classifier");
            if (cls != null) rows.add(new String[]{"Classificador de trincas", cls.optString("effective", "—") + (cls.optString("requested").equals(cls.optString("effective")) ? "" : " (solicitado: " + cls.optString("requested") + ")")});
        }
        JSONObject nn = j.optJSONObject("neural_model");
        if (nn != null) rows.add(new String[]{"Rede neural", nn.optString("model_id", "—") + (nn.optBoolean("demonstration_model") ? " (demonstração)" : "")});
        rows.add(new String[]{"Método registrado", j.optString("method", "—")});
        out.add(Block.table(rows));
    }

    private static void bins(List<Block> out, JSONObject j) {
        JSONObject h = j.optJSONObject("histogram");
        if (h == null) return;
        JSONArray b = h.optJSONArray("bins");
        if (b == null || b.length() == 0) return;
        String u = h.optString("unit", "px");
        out.add(Block.section("Distribuição granulométrica (por número)"));
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Classe (" + u + ")", "Pelotas", "% em número"});
        for (int i = 0; i < b.length(); i++) {
            JSONObject o = b.optJSONObject(i);
            if (o == null) continue;
            rows.add(new String[]{num(o.opt("lower_" + u)) + " – " + num(o.opt("upper_" + u)), String.valueOf(o.optInt("count")), num(o.opt("percent_by_number"))});
        }
        out.add(Block.grid(rows, new float[]{.5f, .25f, .25f}));
    }

    private static void notes(List<Block> out) {
        out.add(Block.section("Limitações"));
        out.add(Block.note("Distribuição por número de objetos visíveis em imagem 2D; não substitui peneiramento nem ensaio validado. Diâmetro equivalente da elipse = 2·√(a·b). Triagem de trincas pela superfície visível: \"sem indício\" não prova ausência de trinca. Modelos experimentais não foram validados com amostras reais de produção. O JSON e o CSV de mesmo nome contêm todos os valores por pelota.", 8));
    }

    // ---------- figuras ----------

    private static String scope(JSONObject json) { return json.optJSONObject("region_of_interest") != null ? "ROI ampliada. " : ""; }

    public static FigureText original(JSONObject json) {
        return new FigureText("Imagem original", scope(json) + "Pixels originais da foto, sem filtro e sem marcações. Elipses, diâmetros e trincas são medidos nestes pixels.");
    }
    public static FigureText filtered(JSONObject json) {
        return new FigureText("Imagem filtrada · " + treatmentLabel(json), scope(json) + "Tratamento de iluminação usado para propor os objetos. Serve à visualização e à segmentação; não é usado nas medidas.");
    }
    public static FigureText segmented(boolean filtered) {
        return new FigureText("Imagem segmentada", (filtered ? "Sobre a imagem filtrada. " : "") + "Elipses ajustadas e possíveis trincas. Vermelho: possível trinca · amarelo: revisão · tracejado: forma sem ajuste.");
    }
    public static FigureText composite(boolean filtered) {
        return new FigureText("Resultado da análise", "Original sem marcações, " + (filtered ? "segmentação sobre a imagem filtrada" : "elipses ajustadas") + ", possíveis trincas e histograma (todas × trincadas).");
    }
    public static FigureText map(boolean filtered) {
        return new FigureText("Mapa granulométrico", (filtered ? "Sobre a imagem filtrada. " : "") + "Cor pela classe de diâmetro equivalente de cada pelota incluída; legenda na própria imagem.");
    }
    public static FigureText summaryPanel() {
        return new FigureText("Resumo da imagem e histograma", "Contagem, triagem de trincas, forma aparente 2D e distribuição por número (todas × trincadas).");
    }

    public static String treatmentLabel(JSONObject json) {
        JSONObject l = json.optJSONObject("low_light");
        return LowLightInfo.label(l == null ? LowLightPreprocessor.Mode.ORIGINAL : mode(l));
    }
    private static LowLightPreprocessor.Mode mode(JSONObject l) {
        try { return LowLightPreprocessor.Mode.valueOf(l.optString("mode", "ORIGINAL")); }
        catch (IllegalArgumentException e) { return LowLightPreprocessor.Mode.ORIGINAL; }
    }

    // ---------- números ----------

    public static String num(Object v) {
        if (!(v instanceof Number)) return "—";
        double d = ((Number) v).doubleValue();
        if (!Double.isFinite(d)) return "—";
        if (Math.abs(d - Math.rint(d)) < 1e-9 && Math.abs(d) < 1e9) return String.format(new Locale("pt", "BR"), "%,d", (long) Math.rint(d));
        return String.format(new Locale("pt", "BR"), Math.abs(d) >= 100 ? "%,.1f" : "%,.2f", d);
    }
    public static String pct(double f) { return Double.isFinite(f) ? String.format(new Locale("pt", "BR"), "%.1f%%", 100 * f) : "—"; }
}
