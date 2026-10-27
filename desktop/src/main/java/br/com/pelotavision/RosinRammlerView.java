package br.com.pelotavision;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * Curva acumulada (% passante) com os pontos experimentais nos limites das classes e a curva
 * Rosin–Rammler ajustada, eixo x logarítmico — como os gráficos de peneiramento. A geometria vem de
 * {@link RosinRammler.Chart} (núcleo, igual ao Android); aqui só se desenha.
 */
final class RosinRammlerView extends JPanel {
    static final Color POINT_FILL = new Color(220, 224, 228), POINT_LINE = new Color(40, 40, 40),
            DATA_LINE = new Color(25, 25, 25), FIT = new Color(32, 160, 220), GRID = new Color(205, 205, 205);
    private RosinRammler.Fit fit;

    RosinRammlerView() { setPreferredSize(new Dimension(360, 250)); setBackground(Color.WHITE); }

    void display(RosinRammler.Fit f) { fit = f; repaint(); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        paint(g, fit, getWidth(), getHeight(), 1f, true);
        g.dispose();
    }

    /**
     * @param s       escala dos textos e traços (1 = tela; maior para o relatório)
     * @param compact true na tela: fórmula numa linha; false no relatório: fórmula, parâmetros e qualidade
     */
    static void paint(Graphics2D g, RosinRammler.Fit f, int w, int h, float s, boolean compact) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        Font body = new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(11 * s)), small = new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(10 * s));
        g.setFont(body); g.setColor(Color.DARK_GRAY);
        if (f == null) { g.drawString("Sem distribuição.", 10 * s, 20 * s); return; }
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight();
        g.setFont(body.deriveFont(Font.BOLD));
        g.drawString("Rosin–Rammler · " + f.basis.label, 8 * s, lineH);
        g.setFont(compact ? small : body);
        int headerLines = compact ? 2 : 4;
        String[] header = compact
                ? new String[]{f.formula(), f.available ? f.quality() : ""}
                : new String[]{f.formula(), f.parameters(), f.quality(), f.basis == RosinRammler.Basis.MASS ? "Massa estimada = esferas de mesma densidade (∝ d³); não é peneiramento." : "Base número: não comparável a parâmetros RR de peneiramento (massa)."};
        for (int i = 0; i < header.length; i++) g.drawString(header[i], 8 * s, lineH + (i + 1) * g.getFontMetrics().getHeight());
        float top = lineH + (headerLines + .6f) * g.getFontMetrics().getHeight();
        float left = 46 * s, right = w - 14 * s, bottom = h - 34 * s;
        if (f.points.isEmpty() || bottom - top < 40) return;
        RosinRammler.Chart c = new RosinRammler.Chart(f, left, top, right, bottom);

        // Grade e eixos.
        g.setFont(small);
        FontMetrics sm = g.getFontMetrics();
        g.setStroke(new BasicStroke(.8f * s));
        for (double yv : c.yTicks) {
            float y = c.y(yv);
            g.setColor(GRID); g.drawLine(Math.round(left), Math.round(y), Math.round(right), Math.round(y));
            g.setColor(Color.DARK_GRAY); String t = RosinRammler.tick(yv); g.drawString(t, left - 5 * s - sm.stringWidth(t), y + sm.getAscent() / 2f - 1);
        }
        for (double xv : c.xTicks) {
            float x = c.x(xv);
            g.setColor(GRID); g.drawLine(Math.round(x), Math.round(top), Math.round(x), Math.round(bottom));
            g.setColor(Color.DARK_GRAY); String t = RosinRammler.tick(xv); g.drawString(t, x - sm.stringWidth(t) / 2f, bottom + sm.getAscent() + 3 * s);
        }
        g.setColor(Color.DARK_GRAY);
        g.drawRect(Math.round(left), Math.round(top), Math.round(right - left), Math.round(bottom - top));
        String xl = "Diâmetro (" + f.unit + ") · escala log", yl = "% passante";
        g.drawString(xl, (left + right) / 2f - sm.stringWidth(xl) / 2f, h - 6 * s);
        Graphics2D r = (Graphics2D) g.create();
        r.rotate(-Math.PI / 2); r.drawString(yl, -(top + bottom) / 2f - sm.stringWidth(yl) / 2f, 12 * s); r.dispose();

        // Curva ajustada (tracejada, azul) e dados (linha preta com pontos), como nos gráficos de referência.
        java.awt.Shape clip = g.getClip();
        g.clipRect(Math.round(left), Math.round(top) - 1, Math.round(right - left) + 1, Math.round(bottom - top) + 2);
        float[] curve = c.curve(160);
        if (curve.length > 0) {
            Path2D.Float p = new Path2D.Float();
            for (int i = 0; i < curve.length; i += 2) if (i == 0) p.moveTo(curve[i], curve[i + 1]); else p.lineTo(curve[i], curve[i + 1]);
            g.setColor(FIT); g.setStroke(new BasicStroke(2.2f * s, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{6 * s, 4 * s}, 0)); g.draw(p);
        }
        Path2D.Float d = new Path2D.Float();
        for (int i = 0; i < f.points.size(); i++) { RosinRammler.Point pt = f.points.get(i); float x = c.x(pt.size), y = c.y(pt.percentFiner); if (i == 0) d.moveTo(x, y); else d.lineTo(x, y); }
        g.setColor(DATA_LINE); g.setStroke(new BasicStroke(1.4f * s)); g.draw(d);
        float rad = (f.points.size() > 40 ? 2.2f : 3.6f) * s;
        for (RosinRammler.Point pt : f.points) {
            float x = c.x(pt.size), y = c.y(pt.percentFiner);
            g.setColor(POINT_FILL); g.fill(new java.awt.geom.Ellipse2D.Float(x - rad, y - rad, 2 * rad, 2 * rad));
            g.setColor(POINT_LINE); g.setStroke(new BasicStroke(.9f * s)); g.draw(new java.awt.geom.Ellipse2D.Float(x - rad, y - rad, 2 * rad, 2 * rad));
        }
        g.setClip(clip);

        // Legenda no canto superior esquerdo: numa curva acumulada crescente essa região fica vazia.
        String a = "Medido (limites de classe)", b = f.available ? "Ajuste RR" : "Ajuste indisponível";
        int lw = Math.max(sm.stringWidth(a), sm.stringWidth(b)) + Math.round(34 * s), lh = Math.round(2 * sm.getHeight() + 8 * s);
        int lx = Math.round(left + 6 * s), ly = Math.round(top + 6 * s);
        g.setColor(new Color(255, 255, 255, 230)); g.fillRect(lx, ly, lw, lh);
        g.setColor(GRID); g.drawRect(lx, ly, lw, lh);
        float y1 = ly + 4 * s + sm.getAscent() * .8f, y2 = y1 + sm.getHeight();
        g.setColor(DATA_LINE); g.setStroke(new BasicStroke(1.4f * s)); g.drawLine(lx + Math.round(5 * s), Math.round(y1 - 3 * s), lx + Math.round(25 * s), Math.round(y1 - 3 * s));
        g.setColor(POINT_FILL); g.fill(new java.awt.geom.Ellipse2D.Float(lx + 15 * s - rad, y1 - 3 * s - rad, 2 * rad, 2 * rad));
        g.setColor(POINT_LINE); g.setStroke(new BasicStroke(.9f * s)); g.draw(new java.awt.geom.Ellipse2D.Float(lx + 15 * s - rad, y1 - 3 * s - rad, 2 * rad, 2 * rad));
        g.setColor(FIT); g.setStroke(new BasicStroke(2.2f * s, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{6 * s, 4 * s}, 0));
        g.drawLine(lx + Math.round(5 * s), Math.round(y2 - 3 * s), lx + Math.round(25 * s), Math.round(y2 - 3 * s));
        g.setColor(Color.DARK_GRAY);
        g.drawString(a, lx + 30 * s, y1); g.drawString(b, lx + 30 * s, y2);
    }
}
