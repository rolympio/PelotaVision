package br.com.pelotavision;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** Ajuste Rosin–Rammler do núcleo (sem OpenCV): recupera parâmetros conhecidos e gera a geometria do gráfico. */
public class RosinRammlerTest {
    /** Amostra Weibull(k, lambda) em mm, convertida em px a 10 px/mm, imagem 1000x1000 sem redimensionamento. */
    static Granulometry.Distribution sample(int n, double k, double lambda, double binMm, long seed) {
        Random r = new Random(seed);
        List<Granulometry.Item> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double u = r.nextDouble();
            double mm = lambda * Math.pow(-Math.log(1 - u), 1 / k);
            items.add(new Granulometry.Item(mm * 10, false, false));
        }
        return Granulometry.compute(items, new Granulometry.Settings(10, binMm, true, false), 1000, 1000, 1000, 1000);
    }

    @Test public void numberBasisRecoversKnownParameters() {
        Granulometry.Distribution d = sample(3000, 4.5, 12.5, 1, 7);
        RosinRammler.Fit f = RosinRammler.fit(d, RosinRammler.Basis.NUMBER);
        assertTrue(f.reason, f.available);
        assertEquals(4.5, f.n, 0.2);
        assertEquals(12.5, f.characteristic, 0.15);
        assertEquals(f.characteristic * Math.pow(Math.log(2), 1 / f.n), f.d50, 1e-9);
        assertEquals(63.212, f.finer(f.characteristic), 0.01);
        assertEquals(f.characteristic, f.sizeAt(100 * (1 - Math.exp(-1))), 1e-9);
        assertTrue(f.r2 > 0.99);
        assertTrue(f.ks < 0.03);
        assertEquals("mm", f.unit);
        assertTrue(f.formula(), f.formula().startsWith("F(d) = 100·[1 − exp(−(d / 12,"));
        // Pontos: acumulado crescente terminando em 100% no último limite de classe.
        double last = -1;
        for (RosinRammler.Point p : f.points) { assertTrue(p.percentFiner >= last); last = p.percentFiner; }
        assertEquals(100, last, 1e-9);
        assertEquals(d.binCount(), f.points.size());
    }

    @Test public void fitDoesNotDependOnClassWidthButPointsDo() {
        RosinRammler.Fit a = RosinRammler.fit(sample(800, 3, 10, 0.5, 3), RosinRammler.Basis.NUMBER);
        RosinRammler.Fit b = RosinRammler.fit(sample(800, 3, 10, 2, 3), RosinRammler.Basis.NUMBER);
        assertEquals(a.n, b.n, 1e-9);
        assertEquals(a.characteristic, b.characteristic, 1e-9);
        assertNotEquals(a.points.size(), b.points.size());
    }

    @Test public void massBasisShiftsCurveToCoarserSizes() {
        Granulometry.Distribution d = sample(2000, 3, 12, 1, 11);
        RosinRammler.Fit num = RosinRammler.fit(d, RosinRammler.Basis.NUMBER), mass = RosinRammler.fit(d, RosinRammler.Basis.MASS);
        assertTrue(mass.available);
        assertTrue(mass.characteristic > num.characteristic);
        assertTrue(mass.d50 > num.d50);
        // Em massa, cada ponto fica abaixo (ou igual) do mesmo ponto em número.
        for (int i = 0; i < num.points.size(); i++) assertTrue(mass.points.get(i).percentFiner <= num.points.get(i).percentFiner + 1e-9);
    }

    @Test public void tooFewOrConstantPelletsAreUnavailable() {
        RosinRammler.Fit f = RosinRammler.fit(sample(8, 4, 12, 1, 1), RosinRammler.Basis.NUMBER);
        assertFalse(f.available);
        assertTrue(f.formula().contains("menos de 12"));
        List<Granulometry.Item> same = new ArrayList<>();
        for (int i = 0; i < 20; i++) same.add(new Granulometry.Item(120, false, false));
        Granulometry.Distribution d = Granulometry.compute(same, new Granulometry.Settings(10, 1, true, false), 1000, 1000, 1000, 1000);
        assertFalse(RosinRammler.fit(d, RosinRammler.Basis.NUMBER).available);
        assertFalse(RosinRammler.fit(null, RosinRammler.Basis.MASS).available);
    }

    @Test public void chartUsesLogAxisWithRoundTicks() {
        RosinRammler.Fit f = RosinRammler.fit(sample(600, 4.5, 12.5, 1, 5), RosinRammler.Basis.NUMBER);
        RosinRammler.Chart c = new RosinRammler.Chart(f, 50, 20, 450, 320);
        assertTrue(c.xMin < f.points.get(0).size && c.xMax > f.points.get(f.points.size() - 1).size);
        assertTrue(c.xTicks.length >= 2 && c.xTicks.length <= 9);
        for (double t : c.xTicks) assertTrue(t >= c.xMin && t <= c.xMax);
        assertEquals(320, c.y(0), 1e-4);
        assertEquals(20, c.y(100), 1e-4);
        assertEquals(50, c.x(c.xMin), 1e-3);
        float[] curve = c.curve(50);
        assertEquals(100, curve.length);
        for (int i = 3; i < curve.length; i += 2) assertTrue(curve[i] <= curve[i - 2] + 1e-3); // crescente em %, y desce
        assertArrayEquals(new double[]{1, 2, 5, 10}, RosinRammler.Chart.ticks(0.8, 12), 1e-12);
        assertArrayEquals(new double[]{7, 8, 9, 10, 20}, RosinRammler.Chart.ticks(6.5, 22), 1e-12);
    }

    /** A figura do relatório mantém a 2ª série do histograma (pelotas trincadas, em quantidade) sobre a das todas. */
    @Test public void reportFigureKeepsCrackedSeriesOverlaidOnHistogram() throws Exception {
        Random r = new Random(21);
        List<Granulometry.Item> items = new ArrayList<>();
        List<CrackAnalysis.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            double mm = 12.5 * Math.pow(-Math.log(1 - r.nextDouble()), 1 / 4.5);
            items.add(new Granulometry.Item(mm * 10, false, false));
            CrackAnalysis.Status st = mm > 13 && r.nextDouble() < .45 ? CrackAnalysis.Status.POSSIBLE : r.nextDouble() < .1 ? CrackAnalysis.Status.INCONCLUSIVE : CrackAnalysis.Status.NO_INDICATION;
            entries.add(new CrackAnalysis.Entry(i + 1, st, new float[0], .5, CrackModel.ID));
        }
        Granulometry.Distribution d = Granulometry.compute(items, new Granulometry.Settings(10, 1, true, false), 1000, 1000, 1000, 1000);
        CrackHistogram ch = CrackHistogram.compute(d, items, new CrackAnalysis(1000, 1000, 50, 1, entries));
        assertTrue(ch.available);
        assertTrue(ch.crackedTotal > 10);
        java.awt.image.BufferedImage img = DesktopExport.distributionFigure(d, ch, HistogramView.DEFAULTS, RosinRammler.Basis.NUMBER);
        assertTrue("padrão do Windows: série de trincadas ligada e em quantidade", HistogramView.DEFAULTS.showCracked && HistogramView.DEFAULTS.normalization == CrackHistogram.Normalization.ABSOLUTE);
        // Laranja (trincadas, 65%) sobre branco/azul: só existe se a 2ª série foi desenhada no histograma (metade de cima).
        int orange = 0;
        for (int y = 0; y < 600; y += 2) for (int x = 0; x < img.getWidth(); x += 2) {
            int c = img.getRGB(x, y), red = (c >> 16) & 255, green = (c >> 8) & 255, blue = c & 255;
            if (red > 200 && green > 110 && green < 190 && blue < 120) orange++;
        }
        assertTrue("série de trincadas ausente na figura (" + orange + " px)", orange > 200);
        java.io.File out = TestPaths.artifacts("rosin-rammler-windows");
        out.mkdirs();
        javax.imageio.ImageIO.write(img, "png", new java.io.File(out, "figura_relatorio_com_trincadas.png"));
    }
}
