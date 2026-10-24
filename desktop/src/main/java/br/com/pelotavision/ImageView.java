package br.com.pelotavision;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Mostra a foto (ou so a ROI ativa) com as marcacoes. Roda do mouse = zoom, arrastar =
 * mover, duplo clique = ajustar. O zoom e so de exibicao: nao altera a escala fisica.
 * Todas as coordenadas de entrada/saida sao da imagem analisada inteira.
 */
final class ImageView extends JPanel {
    static final Color OK = new Color(46, 204, 113);
    static final Color REVIEW = new Color(241, 196, 15);
    static final Color CRACK = new Color(231, 76, 60);
    static final Color PARTIAL = new Color(149, 165, 166);
    static final Color REF = new Color(0, 200, 255);

    private BufferedImage image;
    private AnalysisWindow viewport;
    private PelletDetector.Result result;
    private CrackAnalysis cracks;
    private ObjectCalibration.Reference reference;
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
            private boolean moved;
            @Override public void mousePressed(MouseEvent e) { drag = e.getPoint(); moved = false; }
            @Override public void mouseDragged(MouseEvent e) {
                if (drag == null || image == null) return;
                if (Math.abs(e.getX() - drag.x) + Math.abs(e.getY() - drag.y) > 2) moved = true;
                fitted = false; panX += e.getX() - drag.x; panY += e.getY() - drag.y; drag = e.getPoint(); repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (image == null || moved) return;
                if (e.getClickCount() == 2) { fitted = true; repaint(); return; }
                if (clickListener != null && e.getButton() == MouseEvent.BUTTON1) clickListener.accept(toImage(e.getX(), e.getY()));
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (image == null) return;
                ensureTransform();
                double factor = Math.pow(1.15, -e.getPreciseWheelRotation());
                double nz = Math.max(0.05, Math.min(40, zoom * factor));
                Point2D p = toContent(e.getX(), e.getY());
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

    void setViewport(AnalysisWindow w) {
        boolean changed = !(viewport == null ? w == null : w != null && viewport.sameScope(w));
        viewport = w;
        if (changed) fitted = true;
        repaint();
    }

    void setOverlay(PelletDetector.Result result, CrackAnalysis cracks) { this.result = result; this.cracks = cracks; repaint(); }
    void setReference(ObjectCalibration.Reference ref) { reference = ref; repaint(); }
    void setNumbers(boolean on) { numbers = on; repaint(); }
    void setBanner(String text) { banner = text; repaint(); }
    void setClickListener(Consumer<Point2D> listener) { clickListener = listener; }
    void setMeasure(Point2D a, Point2D b) { measureA = a; measureB = b; repaint(); }
    BufferedImage image() { return image; }

    private int vx() { return viewport == null ? 0 : viewport.left; }
    private int vy() { return viewport == null ? 0 : viewport.top; }
    private int vw() { return viewport == null ? image.getWidth() : viewport.width; }
    private int vh() { return viewport == null ? image.getHeight() : viewport.height; }

    private void ensureTransform() {
        if (!fitted || image == null) return;
        zoom = Math.min(getWidth() / (double) vw(), getHeight() / (double) vh());
        panX = (getWidth() - vw() * zoom) / 2; panY = (getHeight() - vh() * zoom) / 2;
    }

    private Point2D toContent(int x, int y) { ensureTransform(); return new Point2D.Double((x - panX) / zoom, (y - panY) / zoom); }
    private Point2D toImage(int x, int y) { Point2D c = toContent(x, y); return new Point2D.Double(c.getX() + vx(), c.getY() + vy()); }

    /** Transformacao imagem inteira -> tela. */
    private AffineTransform imageToScreen() {
        AffineTransform at = new AffineTransform(zoom, 0, 0, zoom, panX, panY);
        at.translate(-vx(), -vy());
        return at;
    }

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
        AffineTransform at = imageToScreen();
        Graphics2D gi = (Graphics2D) g.create();
        gi.transform(at);
        Shape clip = new Rectangle2D.Double(vx(), vy(), vw(), vh());
        gi.clip(clip);
        gi.drawImage(image, 0, 0, null);
        if (viewport != null && viewport.region != null && viewport.region.shape == InterestRegion.Shape.CIRCLE) {
            InterestRegion r = viewport.region;
            Area outside = new Area(clip);
            outside.subtract(new Area(new Ellipse2D.Double(r.left, r.top, r.width(), r.height())));
            gi.setColor(new Color(0, 0, 0, 190));
            gi.fill(outside);
        }
        float w = (float) (2 / zoom);
        if (result != null) drawDetections(gi, result, cracks, w, numbers);
        if (reference != null) drawReference(gi, reference, w);
        gi.dispose();
        if (measureA != null) {
            g.setColor(REF);
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
            o.draw(outline(d));
            if (crack != null && crack.status == CrackAnalysis.Status.POSSIBLE) {
                float[] runs = crack.markedRuns();
                o.setStroke(new BasicStroke(lineWidth * 1.2f));
                for (int i = 0; i + 3 < runs.length; i += 4) o.draw(new Line2D.Float(runs[i], runs[i + 1], runs[i + 2], runs[i + 3]));
            }
            if (crack != null && crack.manuallyReviewed) {
                o.setColor(Color.WHITE);
                o.drawString("R", (float) (d.x + d.radius * 0.6), (float) (d.y - d.radius * 0.6));
            }
            if (numbers) { o.setColor(c); o.drawString(Integer.toString(id), (float) d.x - lineWidth * 3, (float) d.y + lineWidth * 3); }
        }
    }

    static Shape outline(PelletDetector.Detection d) {
        if (!d.ellipseFitted) return new Ellipse2D.Double(d.x - d.radius, d.y - d.radius, 2 * d.radius, 2 * d.radius);
        AffineTransform t = new AffineTransform();
        t.translate(d.x, d.y);
        t.rotate(Math.toRadians(d.angleDeg));
        return t.createTransformedShape(new Ellipse2D.Double(-d.semiMajor, -d.semiMinor, 2 * d.semiMajor, 2 * d.semiMinor));
    }

    /** Referencia escolhida: contorno ciano grosso, eixo medido e "REF" (sempre visivel). */
    static void drawReference(Graphics2D o, ObjectCalibration.Reference ref, float lineWidth) {
        PelletDetector.Detection d = ref.geometry;
        o.setColor(REF);
        o.setStroke(new BasicStroke(lineWidth * 2));
        o.draw(outline(d));
        double t = Math.toRadians(d.angleDeg);
        double ax, ay;
        if (ref.measure == ObjectCalibration.Measure.MINOR_AXIS) { ax = -Math.sin(t) * d.semiMinor; ay = Math.cos(t) * d.semiMinor; }
        else { ax = Math.cos(t) * d.semiMajor; ay = Math.sin(t) * d.semiMajor; }
        o.draw(new Line2D.Double(d.x - ax, d.y - ay, d.x + ax, d.y + ay));
        o.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, Math.round(lineWidth * 8))));
        o.drawString("REF" + (ref.excludeFromStatistics ? " (excl.)" : ""), (float) (d.x - d.semiMajor), (float) (d.y - d.semiMajor - lineWidth * 2));
    }
}
