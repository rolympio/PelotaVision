package br.com.pelotavision;

import static org.junit.Assert.*;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** 0.18.0: ROI automática — tipo e geometria em cenas sintéticas de recipiente redondo, bandeja, correia, campo cheio e vazio. */
public class AutoRoiTest {
    static final int W = 640, H = 480;

    interface Inside { boolean at(double x, double y); }


    /** Pelotas sombreadas sem sobreposição, centros dentro de {@code inside}. */
    static void pellets(Graphics2D g, Inside inside, double rMin, double rMax, int n, long seed) {
        Random rnd = new Random(seed);
        List<double[]> placed = new ArrayList<>();
        for (int k = 0; k < n * 20 && placed.size() < n; k++) {
            double x = rnd.nextDouble() * W, y = rnd.nextDouble() * H, r = rMin + rnd.nextDouble() * (rMax - rMin);
            if (!inside.at(x, y)) continue;
            boolean free = true;
            for (double[] p : placed) if (Math.hypot(x - p[0], y - p[1]) < r + p[2] - 1) { free = false; break; }
            if (!free) continue;
            placed.add(new double[]{x, y, r});
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (double[] p : placed) {
            int v = 110 + rnd.nextInt(60);
            g.setColor(new Color(v, v, v)); g.fill(new Ellipse2D.Double(p[0] - p[2], p[1] - p[2], 2 * p[2], 2 * p[2]));
            int hl = Math.min(255, v + 50); double hr = p[2] * .35;
            g.setColor(new Color(hl, hl, hl)); g.fill(new Ellipse2D.Double(p[0] - p[2] * .3 - hr, p[1] - p[2] * .3 - hr, 2 * hr, 2 * hr));
        }
    }

    static BufferedImage image(int bg) {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = im.createGraphics(); g.setColor(new Color(bg, bg, bg)); g.fillRect(0, 0, W, H); g.dispose();
        return im;
    }
    static AutoRoi.Result run(BufferedImage im) { return AutoRoi.detect(((DataBufferByte) im.getRaster().getDataBuffer()).getData(), W, H); }

    @Test public void roundContainer() {
        BufferedImage im = image(70); Graphics2D g = im.createGraphics();
        g.setColor(new Color(200, 200, 200)); g.fill(new Ellipse2D.Double(320 - 215, 240 - 215, 430, 430));
        g.setColor(new Color(60, 60, 60)); g.fill(new Ellipse2D.Double(320 - 200, 240 - 200, 400, 400));
        pellets(g, (x, y) -> Math.hypot(x - 320, y - 240) < 188, 9, 13, 600, 1); g.dispose();
        AutoRoi.Result r = run(im);
        assertEquals(r.reason, AutoRoi.Kind.ROUND_CONTAINER, r.kind);
        assertTrue(r.usable());
        assertEquals(InterestRegion.Shape.CIRCLE, r.region.shape);
        assertEquals(320, r.region.cx(), 8); assertEquals(240, r.region.cy(), 8);
        // Dentro da parede (r 200) e cobrindo a maior parte do material (r ~188 + raio da pelota).
        assertTrue("raio " + r.region.width() / 2, r.region.width() / 2 > 160 && r.region.width() / 2 < 200);
    }

    @Test public void rectangularTrayExcludesWalls() {
        BufferedImage im = image(45); Graphics2D g = im.createGraphics();
        g.setColor(new Color(180, 180, 180)); g.fillRect(110, 70, 430, 340);
        g.setColor(new Color(85, 85, 85)); g.fillRect(122, 82, 406, 316);
        pellets(g, (x, y) -> x > 128 && x < 522 && y > 88 && y < 392, 8, 12, 700, 2); g.dispose();
        AutoRoi.Result r = run(im);
        assertEquals(r.reason, AutoRoi.Kind.RECT_CONTAINER, r.kind);
        assertTrue(r.usable());
        assertEquals(InterestRegion.Shape.RECTANGLE, r.region.shape);
        // Não inclui a parede (110–122 / 528–540) e pega quase toda a bandeja.
        assertTrue(r.region.left >= 122 && r.region.right <= 528 && r.region.top >= 82 && r.region.bottom <= 398);
        assertTrue(r.region.width() > 340 && r.region.height() > 250);
    }

    @Test public void beltLoadedBandOnly() {
        BufferedImage im = image(40); Graphics2D g = im.createGraphics();
        g.setColor(new Color(95, 95, 95)); g.fillRect(0, 110, W, 261);
        pellets(g, (x, y) -> y > 150 && y < 330, 8, 12, 900, 3); g.dispose();
        AutoRoi.Result r = run(im);
        assertEquals(r.reason, AutoRoi.Kind.BELT_LOAD, r.kind);
        assertTrue(r.usable());
        assertEquals(0, r.region.left, 1); assertEquals(W - 1, r.region.right, 1);
        // Só o trecho carregado: correia vazia (110–150 e 330–370) fica de fora.
        assertTrue("topo " + r.region.top, r.region.top > 135 && r.region.top < 165);
        assertTrue("base " + r.region.bottom, r.region.bottom > 315 && r.region.bottom < 345);
    }

    @Test public void fullFieldAndEmptyScene() {
        BufferedImage full = image(70); Graphics2D g = full.createGraphics();
        pellets(g, (x, y) -> true, 10, 14, 1500, 4); g.dispose();
        AutoRoi.Result f = run(full);
        assertEquals(f.reason, AutoRoi.Kind.FULL_FIELD, f.kind);
        assertTrue(f.region.width() > W * .9);

        AutoRoi.Result e = run(image(120));
        assertEquals(AutoRoi.Kind.NONE, e.kind);
        assertFalse(e.usable());
        assertNull(e.region);
    }

    @Test public void stabilizerSmoothsAndNeedsAgreementToSwitch() {
        AutoRoi.Stabilizer st = new AutoRoi.Stabilizer(.5, 3);
        AutoRoi.Result a = new AutoRoi.Result(AutoRoi.Kind.ROUND_CONTAINER, AutoRoi.circle(320, 240, 100, W, H), .9, .4, 5, "a");
        AutoRoi.Result b = new AutoRoi.Result(AutoRoi.Kind.ROUND_CONTAINER, AutoRoi.circle(340, 240, 120, W, H), .9, .4, 5, "b");
        st.update(a, W, H);
        AutoRoi.Result m = st.update(b, W, H);
        assertEquals(330, m.region.cx(), 1e-6); assertEquals(110, m.region.width() / 2, 1e-6);
        AutoRoi.Result belt = new AutoRoi.Result(AutoRoi.Kind.BELT_LOAD, AutoRoi.rect(0, 100, W - 1, 300, W, H), .9, .4, 5, "c");
        assertEquals(AutoRoi.Kind.ROUND_CONTAINER, st.update(belt, W, H).kind);
        assertEquals(AutoRoi.Kind.ROUND_CONTAINER, st.update(belt, W, H).kind);
        assertEquals(AutoRoi.Kind.BELT_LOAD, st.update(belt, W, H).kind);
    }

    @Test public void scaledToFullResolution() {
        AutoRoi.Result a = new AutoRoi.Result(AutoRoi.Kind.ROUND_CONTAINER, AutoRoi.circle(320, 180, 160, 640, 360), .9, .4, 5, "a");
        AutoRoi.Result s = a.scaled(3, 3, 1920, 1080);
        assertEquals(960, s.region.cx(), 1e-6); assertEquals(480, s.region.width() / 2, 1e-6);
    }
}
