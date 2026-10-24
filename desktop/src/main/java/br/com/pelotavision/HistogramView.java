package br.com.pelotavision;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/** Histograma por numero de pelotas (mesmas classes de Granulometry). */
final class HistogramView extends JPanel {
    private Granulometry.Distribution distribution;

    HistogramView() { setPreferredSize(new Dimension(360, 220)); setBackground(Color.WHITE); }

    void display(Granulometry.Distribution d) { distribution = d; repaint(); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        paint(g, distribution, getWidth(), getHeight());
        g.dispose();
    }

    static void paint(Graphics2D g, Granulometry.Distribution d, int w, int h) {
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        if (d == null || d.binCount() == 0) { g.drawString("Sem histograma.", 10, 20); return; }
        String unit = d.settings.unit();
        int left = 36, right = 10, top = 22, bottom = 34;
        int max = 1;
        for (int i = 0; i < d.binCount(); i++) max = Math.max(max, d.count(i));
        int n = d.binCount();
        double bw = (w - left - right) / (double) n;
        g.drawString(String.format(Locale.ROOT, "n=%d (excl. %d) · classe %s %s · por numero", d.included, d.excluded, fmt(d.settings.binWidthMm), unit), left, 14);
        for (int i = 0; i < n; i++) {
            int bh = (int) Math.round((h - top - bottom) * d.count(i) / (double) max);
            int x = (int) Math.round(left + i * bw);
            g.setColor(new Color(0, 122, 135));
            g.fillRect(x, h - bottom - bh, Math.max(1, (int) Math.round(bw) - 1), bh);
        }
        g.setColor(Color.DARK_GRAY);
        g.drawLine(left, h - bottom, w - right, h - bottom);
        g.drawString(Integer.toString(max), 4, top + 8);
        g.drawString("0", 4, h - bottom);
        int labels = Math.max(1, n / 6);
        for (int i = 0; i <= n; i += labels) {
            int x = (int) Math.round(left + i * bw);
            g.drawLine(x, h - bottom, x, h - bottom + 3);
            g.drawString(fmt(d.lower(0) + i * d.settings.binWidthMm), x - 8, h - bottom + 15);
        }
        g.drawString("diametro (" + unit + ")", w / 2 - 30, h - 4);
    }

    static String fmt4(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : "—"; }

    static String fmt(double v) {
        if (!Double.isFinite(v)) return "—";
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
