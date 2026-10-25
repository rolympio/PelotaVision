package br.com.pelotavision;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Mapa granulométrico e histograma de trincadas: cálculos e renderização em casos sintéticos
 * controlados (não precisam de OpenCV nativo). Numeração = itens do prompt (seção 9).
 */
public class GranulometryVisualsTest {
    private static final double PX_PER_MM = 10; // 10 px = 1 mm

    private static Granulometry.Distribution dist(List<Granulometry.Item> items, double bin, boolean partial, boolean review) {
        return Granulometry.compute(items, new Granulometry.Settings(PX_PER_MM, bin, partial, review), 1000, 1000, 1000, 1000);
    }

    private static List<Granulometry.Item> items(double... mm) {
        List<Granulometry.Item> l = new ArrayList<>();
        for (double v : mm) l.add(new Granulometry.Item(v * PX_PER_MM, false, false));
        return l;
    }

    private static CrackAnalysis cracks(CrackAnalysis.Status... s) {
        List<CrackAnalysis.Entry> e = new ArrayList<>();
        for (int i = 0; i < s.length; i++) e.add(new CrackAnalysis.Entry(i + 1, s[i], new float[0], 0.5, CrackModel.ID));
        return new CrackAnalysis(1000, 1000, 50, 1, e);
    }

    // 1. Conversão de diâmetro para cor
    @Test public void diameterToColorFollowsBlueToRed() {
        Granulometry.Distribution d = dist(items(8, 10, 12, 14, 16), 1, true, false);
        GranulometryColorScale s = GranulometryColorScale.relative(d);
        assertEquals(8, s.min, 1e-12);
        assertEquals(16, s.max, 1e-12);
        int lo = s.rgb(8), hi = s.rgb(16), mid = s.rgb(12);
        assertTrue("menor = azul", (lo & 255) > ((lo >> 16) & 255) && (lo & 255) > ((lo >> 8) & 255));
        assertTrue("maior = vermelho", ((hi >> 16) & 255) > (hi & 255) && ((hi >> 16) & 255) > ((hi >> 8) & 255));
        assertTrue("meio = verde", ((mid >> 8) & 255) > ((mid >> 16) & 255) && ((mid >> 8) & 255) > (mid & 255));
        assertEquals(GranulometryColorScale.colorAt(0.25), s.rgb(10));
        assertEquals("ciano a 1/4", 0, ((GranulometryColorScale.colorAt(0.25) >> 16) & 255) / 64);
        GranulometryColorScale fixed = GranulometryColorScale.fixed(5, 25, "mm");
        assertEquals(0.5, fixed.fraction(15), 1e-12);
        assertEquals("fora da faixa fixa satura", s.rgb(16), fixed.rgb(40));
        assertEquals(GranulometryColorScale.colorAt(0), fixed.rgb(1));
        assertArrayEquals(new double[]{8, 10, 12, 14, 16}, s.ticks(5), 1e-12);
    }

    // 2. Transparência das máscaras (25% cor / 75% foto; limite 70%)
    @Test public void maskTransparencyIsSourceOver() {
        int photo = 0x204060, color = 0xFF0000;
        int b = GranulometryColorScale.blend(photo, color, 0.25);
        assertEquals(Math.round(0x20 * 0.75 + 255 * 0.25), (b >> 16) & 255);
        assertEquals(Math.round(0x40 * 0.75), (b >> 8) & 255);
        assertEquals(Math.round(0x60 * 0.75), b & 255);
        assertEquals("opacidade limitada a 70%", GranulometryColorScale.blend(photo, color, 0.70), GranulometryColorScale.blend(photo, color, 0.95));
        assertEquals(0.25, ReportVisuals.defaults().mapOpacity, 0);
        assertEquals(0.70, GranulometryColorScale.clampOpacity(2), 0);
        assertEquals(0, GranulometryColorScale.clampOpacity(-1), 0);
    }

    // 3. Preservação da fotografia original fora das pelotas e com opacidade 0
    @Test public void photoOutsidePelletsIsUntouched() {
        int w = 60, h = 40;
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | ((i * 7919) & 0xFFFFFF);
        int[] orig = px.clone();
        PelletDetector.Detection p = new PelletDetector.Detection(20, 20, 8, Math.PI * 64, 1, false, false);
        PelletDetector.Result r = new PelletDetector.Result(Arrays.asList(p), w, h, 1, 0, false, "test");
        Granulometry.Distribution d = Granulometry.compute(Arrays.asList(new Granulometry.Item(16, false, false)),
                new Granulometry.Settings(1, 1, true, false), w, h, w, h);
        int colored = GranulometryColorScale.paintMap(px, w, h, r, d, GranulometryColorScale.relative(d), 0.25);
        assertEquals(1, colored);
        int changed = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double dx = x + .5 - 20, dy = y + .5 - 20;
                if (dx * dx + dy * dy > 64) assertEquals("fora da pelota: intacto", orig[i], px[i]);
                else { changed++; assertEquals("alfa preservado", orig[i] >>> 24, px[i] >>> 24); }
            }
        assertTrue(changed > 150);
        int[] again = orig.clone();
        GranulometryColorScale.paintMap(again, w, h, r, d, GranulometryColorScale.relative(d), 0);
        assertArrayEquals("opacidade 0 = foto original", orig, again);
    }

    // 4. Todos os diâmetros iguais: cor intermediária, sem divisão por zero
    @Test public void equalDiametersUseMiddleColor() {
        Granulometry.Distribution d = dist(items(12, 12, 12), 1, true, false);
        GranulometryColorScale s = GranulometryColorScale.relative(d);
        assertTrue(s.equalRange());
        assertEquals(0.5, s.fraction(12), 0);
        assertEquals(GranulometryColorScale.colorAt(0.5), s.rgb(12));
        for (double t : s.ticks(5)) assertTrue(Double.isFinite(t));
    }

    // 5. Pelotas sem dimensão válida (NaN, zero, parcial excluída) não entram nem recebem cor
    @Test public void invalidPelletsAreExcludedAndNotColored() {
        List<Granulometry.Item> l = items(9, 11);
        l.add(new Granulometry.Item(Double.NaN, false, false));
        l.add(new Granulometry.Item(0, false, false));
        l.add(new Granulometry.Item(100, true, false)); // parcial na borda
        Granulometry.Distribution d = dist(l, 1, true, false);
        assertEquals(2, d.included);
        assertEquals(3, d.excluded);
        for (int i = 2; i < l.size(); i++) assertFalse(GranulometryColorScale.colored(d, l.get(i)));
        assertTrue(GranulometryColorScale.colored(d, l.get(0)));
        assertEquals(11, GranulometryColorScale.relative(d).max, 1e-12);
        assertNull(GranulometryColorScale.relative(dist(new ArrayList<>(), 1, true, false)));
    }

    // 6. Contagem correta por intervalo (mesma regra do histograma original)
    @Test public void binIndexMatchesHistogramCounts() {
        double[] mm = {8.0, 8.4, 9.0, 9.99, 10.0, 12.5, 13.0};
        Granulometry.Distribution d = dist(items(mm), 1, true, false);
        int[] c = new int[d.binCount()];
        for (double v : mm) c[d.binIndex(v)]++;
        for (int i = 0; i < d.binCount(); i++) assertEquals(d.count(i), c[i]);
        assertEquals(-1, d.binIndex(Double.NaN));
        assertEquals("última borda superior inclusiva", d.binCount() - 1, d.binIndex(13.0));
    }

    // 7. Histograma de trincadas é subconjunto do total, nas mesmas classes
    @Test public void crackedSeriesIsSubsetInSameClasses() {
        List<Granulometry.Item> l = items(8.2, 8.7, 9.1, 9.5, 10.2, 10.9, 11.4);
        CrackAnalysis c = cracks(CrackAnalysis.Status.POSSIBLE, CrackAnalysis.Status.NO_INDICATION, CrackAnalysis.Status.POSSIBLE,
                CrackAnalysis.Status.POSSIBLE, CrackAnalysis.Status.INCONCLUSIVE, CrackAnalysis.Status.NO_INDICATION, CrackAnalysis.Status.POSSIBLE);
        Granulometry.Distribution d = dist(l, 1, true, false);
        CrackHistogram h = CrackHistogram.compute(d, l, c);
        assertTrue(h.available);
        assertEquals(d.binCount(), h.binCount());
        int sum = 0;
        for (int i = 0; i < h.binCount(); i++) {
            assertTrue(h.cracked(i) <= h.all(i));
            assertTrue(h.crackedValue(i, CrackHistogram.Normalization.PERCENT_OF_ALL) <= h.allValue(i, CrackHistogram.Normalization.PERCENT_OF_ALL) + 1e-12);
            sum += h.cracked(i);
        }
        assertEquals(4, sum);
        assertEquals(4, h.crackedTotal);
        assertArrayEquals(new int[]{1, 2, 0, 1}, new int[]{h.cracked(0), h.cracked(1), h.cracked(2), h.cracked(3)});
        double total = 0, conditional = 0;
        for (int i = 0; i < h.binCount(); i++) { total += h.allValue(i, CrackHistogram.Normalization.PERCENT_OF_ALL); conditional += h.crackedValue(i, CrackHistogram.Normalization.CONDITIONAL); }
        assertEquals(100, total, 1e-9);
        assertEquals("condicional soma 100% na própria série", 100, conditional, 1e-9);
        assertEquals(100.0 * 1 / 7, h.crackedValue(0, CrackHistogram.Normalization.PERCENT_OF_ALL), 1e-9);
        assertEquals("absoluto", 2, h.crackedValue(1, CrackHistogram.Normalization.ABSOLUTE), 0);
        // série total preservada quando a segunda série estiver desabilitada (mesmos valores do gráfico original)
        for (int i = 0; i < d.binCount(); i++) assertEquals(d.percent(i), h.allValue(i, CrackHistogram.Normalization.PERCENT_OF_ALL), 1e-12);
    }

    // 7b. Pelotas excluídas da distribuição (parciais) não entram na série de trincadas
    @Test public void excludedPelletsDoNotEnterCrackedSeries() {
        List<Granulometry.Item> l = items(9, 10);
        l.add(new Granulometry.Item(95, true, false));
        CrackAnalysis c = cracks(CrackAnalysis.Status.NO_INDICATION, CrackAnalysis.Status.POSSIBLE, CrackAnalysis.Status.POSSIBLE);
        CrackHistogram h = CrackHistogram.compute(dist(l, 1, true, false), l, c);
        assertEquals(1, h.crackedTotal);
        assertEquals(2, h.total);
    }

    // 8. Classificação ausente: "indisponível", nunca zero medido
    @Test public void missingClassificationIsUnavailableNotZero() {
        List<Granulometry.Item> l = items(9, 10, 11);
        Granulometry.Distribution d = dist(l, 1, true, false);
        CrackHistogram none = CrackHistogram.compute(d, l, null);
        assertFalse(none.available);
        assertEquals("classificação indisponível", none.unavailableReason);
        assertTrue(Double.isNaN(none.crackedValue(0, CrackHistogram.Normalization.ABSOLUTE)));
        assertTrue(Double.isNaN(none.crackedPercentOfEvaluable()));
        assertFalse("tamanhos diferentes", CrackHistogram.compute(d, l, cracks(CrackAnalysis.Status.POSSIBLE)).available);
        CrackHistogram allUncertain = CrackHistogram.compute(d, l, cracks(CrackAnalysis.Status.INCONCLUSIVE, CrackAnalysis.Status.INCONCLUSIVE, CrackAnalysis.Status.INCONCLUSIVE));
        assertFalse("nenhuma avaliável", allUncertain.available);
        assertEquals(3, allUncertain.uncertainTotal);
        CrackHistogram zero = CrackHistogram.compute(d, l, cracks(CrackAnalysis.Status.NO_INDICATION, CrackAnalysis.Status.NO_INDICATION, CrackAnalysis.Status.INCONCLUSIVE));
        assertTrue("sem trincas mas avaliável: série zero real", zero.available);
        assertEquals(0, zero.crackedTotal);
        assertEquals(0, zero.crackedValue(0, CrackHistogram.Normalization.ABSOLUTE), 0);
    }

    // 9. Incertas: fora do denominador do percentual de trincadas
    @Test public void uncertainPelletsLeaveTheDenominator() {
        List<Granulometry.Item> l = items(9, 10, 11, 12);
        CrackHistogram h = CrackHistogram.compute(dist(l, 1, true, false), l, cracks(CrackAnalysis.Status.POSSIBLE,
                CrackAnalysis.Status.INCONCLUSIVE, CrackAnalysis.Status.NO_INDICATION, CrackAnalysis.Status.INCONCLUSIVE));
        assertEquals(2, h.uncertainTotal);
        assertEquals(2, h.evaluable());
        assertEquals(50, h.crackedPercentOfEvaluable(), 1e-12);
        assertEquals("percentual da população total é outra grandeza", 25, h.crackedValue(0, CrackHistogram.Normalization.PERCENT_OF_ALL), 1e-12);
    }

    // 10. Exportação: JSON versionado, histograma comparativo, mapa e CSV com colunas acrescentadas no fim
    @Test public void exportBlocksAreComplete() throws Exception {
        List<Granulometry.Item> l = items(8.5, 9.5, 10.5);
        Granulometry.Distribution d = dist(l, 1, true, false);
        CrackHistogram h = CrackHistogram.compute(d, l, cracks(CrackAnalysis.Status.POSSIBLE, CrackAnalysis.Status.NO_INDICATION, CrackAnalysis.Status.INCONCLUSIVE));
        ReportVisuals v = ReportVisuals.defaults();
        JSONObject j = ReportJson.crackHistogramJson(h, v);
        assertEquals(3, j.getInt("total_pellets"));
        assertEquals(1, j.getInt("cracked_pellets"));
        assertEquals(1, j.getInt("uncertain_pellets"));
        assertEquals(50, j.getDouble("cracked_percent_of_evaluable"), 1e-12);
        assertEquals(3, j.getJSONArray("bins").length());
        assertEquals(1, j.getJSONArray("bins").getJSONObject(0).getInt("count_cracked"));
        JSONObject unavailable = ReportJson.crackHistogramJson(CrackHistogram.compute(d, l, null), v);
        assertTrue(unavailable.isNull("cracked_pellets"));
        assertFalse(unavailable.getBoolean("available"));
        JSONObject map = (JSONObject) ReportJson.mapJson(d, v);
        assertEquals("relativa", map.getString("scale_mode"));
        assertEquals(0.25, map.getDouble("opacity"), 0);
        assertEquals(8.5, map.getDouble("min"), 1e-12);
        JSONObject fixed = (JSONObject) ReportJson.mapJson(d, v.withMap(0.4, true, true, 5, 20));
        assertEquals("fixa", fixed.getString("scale_mode"));
        assertEquals(20, fixed.getDouble("max"), 0);
        String csv = ReportTables.histogramCsv(h);
        String[] lines = csv.split("\n");
        assertTrue(lines[0].endsWith(";count;percent_by_number;count_cracked;percent_cracked_of_all"));
        assertTrue("colunas antigas preservadas no início", lines[1].startsWith(ReportTables.histogramCsv(d).split("\n")[1]));
        assertEquals(2, ReportJson.SCHEMA_VERSION);
    }

    // 12. Fallback quando o modelo neural não estiver disponível
    @Test public void networkChoiceFallsBackToForest() {
        CrackClassifierChoice.Resolution r = CrackClassifierChoice.resolve(CrackClassifierChoice.MOBILENET, true, false, "arquivo ausente");
        assertTrue(r.fellBack());
        assertEquals(CrackClassifierChoice.RANDOM_FOREST, r.effective);
        assertTrue(r.message.contains("arquivo ausente"));
        assertEquals(CrackClassifierChoice.RANDOM_FOREST, CrackClassifierChoice.resolve(CrackClassifierChoice.COMPARISON, true, false, null).effective);
        assertFalse(CrackClassifierChoice.resolve(CrackClassifierChoice.RANDOM_FOREST, true, false, null).fellBack());
        assertEquals(CrackClassifierChoice.MOBILENET, CrackClassifierChoice.resolve(CrackClassifierChoice.COMPARISON, false, true, null).effective);
        assertEquals(CrackClassifierChoice.RANDOM_FOREST, CrackClassifierChoice.parse("lixo"));
        try { new CrackDetector(null, null, CrackClassifierChoice.MOBILENET); fail("rede ausente deve ser recusada"); }
        catch (IllegalArgumentException expected) { /* fallback é obrigatório */ }
        assertNull(MobileNetCrackModel.load(new java.io.File("nao_existe.onnx"), "{}"));
        assertNotNull(MobileNetCrackModel.lastError);
    }
}
