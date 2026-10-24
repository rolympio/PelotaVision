package br.com.pelotavision;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * Base dos editores (ROI, correia): foto ajustada a janela, roda = zoom, botao direito ou
 * do meio = mover a foto, botao esquerdo = editar (subclasse). Coordenadas da imagem.
 */
abstract class EditorCanvas extends JPanel {
    protected final BufferedImage photo;
    private double zoom = 1, panX, panY;
    private boolean fitted = true;
    private Runnable changed;

    EditorCanvas(BufferedImage photo) {
        this.photo = photo;
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(1000, 640));
        MouseAdapter m = new MouseAdapter() {
            private java.awt.Point pan;
            @Override public void mousePressed(MouseEvent e) {
                ensure();
                if (e.getButton() == MouseEvent.BUTTON1) press(toImage(e.getX(), e.getY()), handleRadius());
                else pan = e.getPoint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (pan != null) { fitted = false; panX += e.getX() - pan.x; panY += e.getY() - pan.y; pan = e.getPoint(); repaint(); return; }
                if (javax.swing.SwingUtilities.isLeftMouseButton(e) && drag(toImage(e.getX(), e.getY()))) { repaint(); fire(); }
            }
            @Override public void mouseReleased(MouseEvent e) { pan = null; release(); }
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && e.getButton() != MouseEvent.BUTTON1) fit(); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                ensure();
                Point2D p = toImage(e.getX(), e.getY());
                zoom = Math.max(0.05, Math.min(40, zoom * Math.pow(1.15, -e.getPreciseWheelRotation())));
                panX = e.getX() - p.getX() * zoom; panY = e.getY() - p.getY() * zoom; fitted = false; repaint();
            }
        };
        addMouseListener(m); addMouseMotionListener(m); addMouseWheelListener(m);
    }

    void onChange(Runnable r) { changed = r; }
    protected void fire() { if (changed != null) changed.run(); }
    void fit() { fitted = true; repaint(); }

    /** Raio de captura das alcas, em pixels da imagem (12 px de tela). */
    protected double handleRadius() { return 12 / zoom; }
    protected float stroke() { return (float) (2 / zoom); }

    private void ensure() {
        if (!fitted) return;
        zoom = Math.min(getWidth() / (double) photo.getWidth(), getHeight() / (double) photo.getHeight());
        panX = (getWidth() - photo.getWidth() * zoom) / 2; panY = (getHeight() - photo.getHeight() * zoom) / 2;
    }

    private Point2D toImage(int x, int y) { return new Point2D.Double((x - panX) / zoom, (y - panY) / zoom); }

    protected abstract void press(Point2D p, double radius);
    protected abstract boolean drag(Point2D p);
    protected void release() {}
    protected abstract void paintOverlay(Graphics2D g);

    protected void handle(Graphics2D g, double x, double y) {
        double r = 6 / zoom;
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke((float) (1 / zoom)));
        g.draw(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        ensure();
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.transform(new AffineTransform(zoom, 0, 0, zoom, panX, panY));
        g.drawImage(photo, 0, 0, null);
        paintOverlay(g);
        g.dispose();
    }
}
