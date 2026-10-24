package br.com.pelotavision;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Mostra a foto/quadro com as marcacoes. Roda do mouse = zoom, arrastar = mover,
 * duplo clique = ajustar a janela. O zoom e so de exibicao: nao altera a escala fisica.
 */
final class ImageView extends JPanel {
    static final Color OK = new Color(46, 204, 113);
    static final Color REVIEW = new Color(241, 196, 15);
    static final Color CRACK = new Color(231, 76, 60);
    static final Color PARTIAL = new Color(149, 165, 166);

    private BufferedImage image;
    private PelletDetector.Result result;
    private CrackAnalysis cracks;
    private boolean numbers;
    private double zoom = 1, panX, panY;
    private boolean fitted = true;
    private Point2D measureA, measureB;
    private Consumer<Point2D> clickListener;
    private String banner;

    ImageView() {
        setBackground(new Color(30, 30, 30));
        setPreferredSize(new Dimension(960, 640));
        MouseAdapter mouse = new MouseAdapter() {
            private java.awt.Point drag;
            @Override public void mousePressed(MouseEvent e) { drag = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (drag == null || image == null) return;
                fitted = false; panX += e.getX() - drag.x; panY += e.getY() - drag.y; drag = e.getPoint(); repaint();
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (image == null) return;
                if (e.getClickCount() == 2) { fitted = true; repaint(); return; }
                if (clickListener != null && e.getButton() == MouseEvent.BUTTON1) clickListener.accept(toImage(e.getX(), e.getY()));
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (image == null) return;
                ensureTransform();
                double factor = Math.pow(1.15, -e.getPreciseWheelRotation());
                double nz = Math.max(0.05, Math.min(40, zoom * factor));
                Point2D p = toImage(e.getX(), e.getY());
                zoom = nz; panX = e.getX() - p.getX() * zoom; panY = e.getY() - p.getY() * zoom; fitted = false; repaint();
            }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse);
    }

    void setImage(BufferedImage image, boolean keepView) {
        boolean sameSize = this.image != null && image != null && this.image.getWidth() == image.getWidth() && this.image.getHeight() == image.getHeight();
        this.image = image;
        if (!keepView || !sameSize) fitted = true;
        repaint();
    }

    void setOverlay(PelletDetector.Result result, CrackAnalysis cracks) { this.result = result; this.cracks = cracks; repaint(); }
    void setNumbers(boolean on) { numbers = on; repaint(); }
    void setBanner(String text) { banner = text; repaint(); }
    void setClickListener(Consumer<Point2D> listener) { clickListener = listener; setCursor(java.awt.Cursor.getPredefinedCursor(listener == null ? java.awt.Cursor.DEFAULT_CURSOR : java.awt.Cursor.CROSSHAIR_CURSOR)); }
    void setMeasure(Point2D a, Point2D b) { measureA = a; measureB = b; repaint(); }
    BufferedImage image() { return image; }

    private void ensureTransform() {
        if (!fitted || image == null) return;
        zoom = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
        panX = (getWidth() - image.getWidth() * zoom) / 2; panY = (getHeight() - image.getHeight() * zoom) / 2;
    }

    private Point2D toImage(int x, int y) { ensureTransform(); return new Point2D.Double((x - panX) / zoom, (y - panY) / zoom); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (image == null) {
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("Abra a camera ou uma imagem para comecar.", 20, 30);
            g.dispose();
            return;
        }
        ensureTransform();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, zoom < 1 ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        AffineTransform at = new AffineTransform(zoom, 0, 0, zoom, panX, panY);
        g.drawImage(image, at, null);
        if (result != null) {
            double sx = image.getWidth() / (double) result.width, sy = image.getHeight() / (double) result.height;
            Graphics2D o = (Graphics2D) g.create();
            o.transform(at);
            o.scale(sx, sy);
            float w = (float) (2 / (zoom * Math.max(sx, sy)));
            drawDetections(o, result, cracks, w, numbers);
            o.dispose();
        }
        if (measureA != null) {
            g.setColor(Color.CYAN);
            g.setStroke(new BasicStroke(2f));
            Point2D a = at.transform(measureA, null);
            g.fill(new Ellipse2D.Double(a.getX() - 4, a.getY() - 4, 8, 8));
            if (measureB != null) {
                Point2D b = at.transform(measureB, null);
                g.fill(new Ellipse2D.Double(b.getX() - 4, b.getY() - 4, 8, 8));
                g.draw(new Line2D.Double(a, b));
            }
        }
        if (banner != null) {
            g.setFont(getFont().deriveFont(Font.BOLD, 14f));
            int tw = g.getFontMetrics().stringWidth(banner);
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRoundRect(8, 8, tw + 16, 26, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(banner, 16, 26);
        }
        g.dispose();
    }

    /** Desenho comum a tela e ao PNG exportado (coordenadas da imagem analisada). */
    static void drawDetections(Graphics2D o, PelletDetector.Result result, CrackAnalysis cracks, float lineWidth, boolean numbers) {
        Stroke solid = new BasicStroke(lineWidth);
        Stroke dashed = new BasicStroke(lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4 * lineWidth, 3 * lineWidth}, 0f);
        o.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, Math.round(lineWidth * 6))));
        int id = 0;
        for (PelletDetector.Detection d : result.circles) {
            id++;
            CrackAnalysis.Entry crack = cracks == null || cracks.entries.size() < id ? null : cracks.entries.get(id - 1);
            Color c = d.partial ? PARTIAL : d.needsReview ? REVIEW : OK;
            if (crack != null && crack.status == CrackAnalysis.Status.POSSIBLE) c = CRACK;
            else if (crack != null && crack.status == CrackAnalysis.Status.INCONCLUSIVE) c = REVIEW;
            o.setColor(c);
            o.setStroke(d.ellipseFitted && !d.needsReview ? solid : dashed);
            if (d.ellipseFitted) {
                AffineTransform keep = o.getTransform();
                o.translate(d.x, d.y);
                o.rotate(Math.toRadians(d.angleDeg));
                o.draw(new Ellipse2D.Double(-d.semiMajor, -d.semiMinor, 2 * d.semiMajor, 2 * d.semiMinor));
                o.setTransform(keep);
            } else {
                o.draw(new Ellipse2D.Double(d.x - d.radius, d.y - d.radius, 2 * d.radius, 2 * d.radius));
            }
            if (numbers) o.drawString(Integer.toString(id), (float) d.x - lineWidth * 3, (float) d.y + lineWidth * 3);
        }
    }
}
