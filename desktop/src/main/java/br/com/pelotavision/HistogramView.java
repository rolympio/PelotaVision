package br.com.pelotavision;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/**
 * Histograma por numero (mesmas classes de Granulometry). Com a serie de trincadas ligada, as duas series
 * usam as mesmas classes e eixos e sao sobrepostas com transparencia (azul-petroleo 55%, laranja 65%),
 * nunca lado a lado nem somadas. Sem a serie, desenha o grafico original.
 */
final class HistogramView extends JPanel {
    static final Color ALL = new Color(38, 140, 171), CRACKED = new Color(255, 140, 26), ORIGINAL = new Color(0, 122, 135);
    private Granulometry.Distribution distribution;
    private CrackHistogram cracks;
    /** Grafico original do Windows: contagens absolutas; a serie de trincadas desligada preserva esse grafico. */
    static final ReportVisuals DEFAULTS = ReportVisuals.defaults().withHistogram(true, true, false, CrackHistogram.Normalization.ABSOLUTE);
    private ReportVisuals visuals = DEFAULTS;

    HistogramView() { setPreferredSize(new Dimension(360, 240)); setBackground(Color.WHITE); }

    void display(Granulometry.Distribution d) { display(d, null, visuals); }
    void display(Granulometry.Distribution d, CrackHistogram h, ReportVisuals v) { distribution = d; cracks = h; if (v != null) visuals = v; repaint(); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // A moldura com título ocupa as bordas do componente: desenha só na área interna (antes o texto
        // da 1ª linha ficava por cima do título "Histograma por numero").
        java.awt.Insets in = getInsets();
        g.translate(in.left, in.top);
        g.clipRect(0, 0, getWidth() - in.left - in.right, getHeight() - in.top - in.bottom);
        paint(g, distribution, cracks, visuals, getWidth() - in.left - in.right, getHeight() - in.top - in.bottom);
        g.dispose();
    }

    static void paint(Graphics2D g, Granulometry.Distribution d, int w, int h) { paint(g, d, null, null, w, h); }

    static void paint(Graphics2D g, Granulometry.Distribution d, CrackHistogram ch, ReportVisuals v, int w, int h) {
        if (v == null) v = DEFAULTS;
        boolean second = ch != null && v.showCracked, showAll = v.showAll || !second;
        CrackHistogram.Normalization n = second || v.normalization != CrackHistogram.Normalization.CONDITIONAL ? v.normalization : CrackHistogram.Normalization.PERCENT_OF_ALL;
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        if (d == null || d.binCount() == 0) { g.drawString("Sem histograma.", 10, 20); return; }
        String unit = d.settings.unit();
        int left = 40, right = 10, bottom = 34;
        int nb = d.binCount();
        double max = 0;
        for (int i = 0; i < nb; i++) {
            double all = value(d, ch, i, n);
            if (showAll) max = Math.max(max, all);
            if (second && ch.available) max = Math.max(max, ch.crackedValue(i, n));
        }
        if (max <= 0) max = 1;
        double bw = (w - left - right) / (double) nb;
        int y = 12;
        String title = String.format(Locale.ROOT, "n=%d (excl. %d) · classe %s %s · %s", d.included, d.excluded, fmt(d.settings.binWidthMm), unit,
                n == CrackHistogram.Normalization.ABSOLUTE ? "por numero" : n == CrackHistogram.Normalization.PERCENT_OF_ALL ? "% do total" : "% da propria serie");
        for (String line : ChartText.wrap(title, g.getFontMetrics(), w - left - right)) { g.drawString(line, left, y); y += 14; }
        if (second) {
            for (String line : ChartText.wrap(indicators(ch), g.getFontMetrics(), w - left - right)) { g.drawString(line, left, y); y += 14; }
            int x = left;
            if (showAll) { g.setColor(alpha(ALL, .55f)); g.fillRect(x, y - 9, 10, 10); g.setColor(Color.DARK_GRAY); g.drawString("Todas as pelotas", x + 14, y); y += 14; }
            g.setColor(alpha(CRACKED, .65f)); g.fillRect(x, y - 9, 10, 10); g.setColor(ch.available ? Color.DARK_GRAY : new Color(170, 80, 0));
            String label = ch.available ? (n == CrackHistogram.Normalization.CONDITIONAL ? "Trincadas (distribuicao condicional)" : "Pelotas trincadas") : "Trincadas: " + ch.unavailableReason;
            for (String line : ChartText.wrap(label, g.getFontMetrics(), w - x - right - 14)) { g.drawString(line, x + 14, y); y += 14; }
        }
        int top = y + (v.showValues ? 12 : 2);
        if (h - top - bottom < 20 || w - left - right < 20) return;
        Font small = new Font(Font.SANS_SERIF, Font.PLAIN, 9);
        for (int i = 0; i < nb; i++) {
            int x = (int) Math.round(left + i * bw), bwPx = Math.max(1, (int) Math.round(bw) - 1);
            if (showAll) {
                double all = value(d, ch, i, n);
                int bh = (int) Math.round((h - top - bottom) * all / max);
                g.setColor(second ? alpha(ALL, .55f) : ORIGINAL);
                g.fillRect(x, h - bottom - bh, bwPx, bh);
                if (v.showValues && all > 0 && bw >= 14) { g.setColor(Color.DARK_GRAY); g.setFont(small); g.drawString(compact(all, n), x + 1, h - bottom - bh - 2); }
            }
            if (second && ch.available) {
                double c = ch.crackedValue(i, n);
                int bh = (int) Math.round((h - top - bottom) * c / max), inset = (int) Math.min(bw * .18, 4);
                g.setColor(alpha(CRACKED, .65f));
                if (c > 0) g.fillRect(x + inset, h - bottom - bh, Math.max(1, bwPx - 2 * inset), bh);
                if (v.showValues && c > 0 && bw >= 14) { g.setColor(new Color(150, 70, 0)); g.setFont(small); g.drawString(compact(c, n), x + 1, Math.min(h - bottom - 2, h - bottom - bh + 10)); }
            }
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(Color.DARK_GRAY);
        g.drawLine(left, h - bottom, w - right, h - bottom);
        g.drawString(compact(max, n), 2, top + 8);
        g.drawString("0", 4, h - bottom);
        int labels = Math.max(1, nb / 6);
        for (int i = 0; i <= nb; i += labels) {
            int x = (int) Math.round(left + i * bw);
            g.drawLine(x, h - bottom, x, h - bottom + 3);
            g.drawString(fmt(d.lower(0) + i * d.settings.binWidthMm), x - 8, h - bottom + 15);
        }
        g.drawString("diametro (" + unit + ")", w / 2 - 30, h - 4);
    }

    private static double value(Granulometry.Distribution d, CrackHistogram ch, int i, CrackHistogram.Normalization n) {
        if (ch != null) return ch.allValue(i, n);
        return n == CrackHistogram.Normalization.ABSOLUTE ? d.count(i) : d.percent(i);
    }

    static String indicators(CrackHistogram h) {
        if (!h.available) return String.format(Locale.ROOT, "Pelotas: %d · trincadas: classificacao indisponivel · incertas: %d", h.total, h.uncertainTotal);
        return String.format(Locale.ROOT, "Pelotas: %d · trincadas: %d (%.1f%% das %d avaliaveis) · incertas: %d", h.total, h.crackedTotal, h.crackedPercentOfEvaluable(), h.evaluable(), h.uncertainTotal);
    }

    static Color alpha(Color c, float a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(a * 255)); }

    private static String compact(double v, CrackHistogram.Normalization n) {
        return n == CrackHistogram.Normalization.ABSOLUTE ? Long.toString(Math.round(v)) : v >= 10 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
    }

    static String fmt4(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : "—"; }

    static String fmt(double v) {
        if (!Double.isFinite(v)) return "—";
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Barra continua da escala de cores (tela e PNG exportado). */
    static void legend(Graphics2D g, GranulometryColorScale scale, ReportVisuals v, int x, int y, int width, Color text) {
        int bh = 12;
        for (int i = 0; i < width; i++) { g.setColor(new Color(GranulometryColorScale.colorAt(i / (double) Math.max(1, width - 1)))); g.fillRect(x + i, y, 1, bh); }
        g.setColor(text);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString(String.format(Locale.ROOT, "Mapa granulometrico · escala %s · cor %d%% sobre foto %d%%", scale.label(), Math.round(v.mapOpacity * 100), Math.round((1 - v.mapOpacity) * 100)), x, y - 4);
        double[] t = scale.ticks(scale.equalRange() ? 2 : 5);
        for (int i = 0; i < t.length; i++) {
            String s = fmt2(t[i]) + (i == t.length - 1 ? " " + scale.unit : "");
            int sw = g.getFontMetrics().stringWidth(s), px = x + (int) Math.round(width * i / (t.length - 1.0));
            g.drawString(s, i == 0 ? px : i == t.length - 1 ? px - sw : px - sw / 2, y + bh + 13);
        }
    }

    static String fmt2(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "") : "—"; }

}
