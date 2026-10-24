package br.com.pelotavision;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/** Editor da area de interesse (equivalente a showRegionEditor/RegionEditorView do Android). */
final class RegionEditorDialog extends JDialog {
    /** Resultado: aplicado e regiao (null = imagem inteira). */
    boolean applied;
    InterestRegion chosen;

    /** Modelo do editor sem tela (testavel): alcas NW,N,NE,E,SE,S,SW,W; -1 = mover. */
    static final class Model {
        final int w, h;
        InterestRegion region, start;
        int handle = -2;
        Point2D down;

        Model(int w, int h, InterestRegion initial) {
            this.w = w; this.h = h;
            region = initial == null ? InterestRegion.initial(InterestRegion.Shape.RECTANGLE, w, h) : initial;
        }

        /** Mesmo minimo do Android: 20% do menor lado, mas pelo menos 32 px para ser analisavel. */
        double minimum() { return Math.max(32, Math.min(Math.min(w, h) * .2, 64)); }

        static double[] handlePoint(InterestRegion r, int i) {
            double x = i == 0 || i == 6 || i == 7 ? r.left : i == 1 || i == 5 ? r.cx() : r.right;
            double y = i <= 2 ? r.top : i == 3 || i == 7 ? r.cy() : r.bottom;
            return new double[]{x, y};
        }

        static boolean visibleHandle(InterestRegion r, int i) { return r.shape == InterestRegion.Shape.RECTANGLE || i % 2 == 1; }

        void press(Point2D p, double radius) {
            start = region; down = p; handle = -2;
            double best = radius;
            for (int i = 0; i < 8; i++) if (visibleHandle(region, i)) {
                double[] q = handlePoint(region, i);
                double d = Math.hypot(q[0] - p.getX(), q[1] - p.getY());
                if (d < best) { best = d; handle = i; }
            }
            if (handle == -2 && region.contains(p.getX(), p.getY())) handle = -1;
        }

        boolean drag(Point2D p) {
            if (start == null || handle < -1) return false;
            double dx = p.getX() - down.getX(), dy = p.getY() - down.getY();
            region = handle == -1 ? start.moved(dx, dy, w, h) : start.resized(handle, dx, dy, w, h, minimum());
            return true;
        }

        void release() { start = null; handle = -2; }

        void shape(InterestRegion.Shape shape) {
            if (region.shape == shape) return;
            double size = Math.min(region.width(), region.height());
            region = shape == InterestRegion.Shape.CIRCLE
                    ? new InterestRegion(shape, region.cx() - size / 2, region.cy() - size / 2, region.cx() + size / 2, region.cy() + size / 2)
                    : new InterestRegion(shape, region.left, region.top, region.right, region.bottom);
        }

        void reset() { region = InterestRegion.initial(region.shape, w, h); }
    }

    RegionEditorDialog(JFrame owner, BufferedImage photo, InterestRegion current) {
        super(owner, "Area de interesse (ROI)", true);
        Model model = new Model(photo.getWidth(), photo.getHeight(), current);
        JLabel size = new JLabel();
        EditorCanvas canvas = new EditorCanvas(photo) {
            @Override protected void press(Point2D p, double radius) { model.press(p, radius); }
            @Override protected boolean drag(Point2D p) { return model.drag(p); }
            @Override protected void release() { model.release(); }
            @Override protected void paintOverlay(Graphics2D g) {
                InterestRegion r = model.region;
                java.awt.Shape s = r.shape == InterestRegion.Shape.CIRCLE ? new Ellipse2D.Double(r.left, r.top, r.width(), r.height())
                        : new Rectangle2D.Double(r.left, r.top, r.width(), r.height());
                Area outside = new Area(new Rectangle2D.Double(0, 0, photo.getWidth(), photo.getHeight()));
                outside.subtract(new Area(s));
                g.setColor(new Color(0, 0, 0, 150));
                g.fill(outside);
                g.setColor(new Color(0, 200, 255));
                g.setStroke(new BasicStroke(stroke()));
                g.draw(s);
                for (int i = 0; i < 8; i++) if (Model.visibleHandle(r, i)) { double[] q = Model.handlePoint(r, i); handle(g, q[0], q[1]); }
            }
        };
        Runnable update = () -> size.setText(String.format(java.util.Locale.ROOT, "%s %.0f x %.0f px (imagem analisada)",
                model.region.shape == InterestRegion.Shape.CIRCLE ? "Circulo" : "Retangulo", model.region.width(), model.region.height()));
        canvas.onChange(update);
        update.run();

        JButton rect = new JButton("Retangulo"), circle = new JButton("Circulo"), reset = new JButton("Restaurar"), fit = new JButton("Enquadrar");
        rect.addActionListener(e -> { model.shape(InterestRegion.Shape.RECTANGLE); update.run(); canvas.repaint(); });
        circle.addActionListener(e -> { model.shape(InterestRegion.Shape.CIRCLE); update.run(); canvas.repaint(); });
        reset.addActionListener(e -> { model.reset(); update.run(); canvas.repaint(); });
        fit.addActionListener(e -> canvas.fit());
        JButton cancel = new JButton("Cancelar"), whole = new JButton("Imagem inteira"), apply = new JButton("Aplicar");
        cancel.addActionListener(e -> dispose());
        whole.addActionListener(e -> { applied = true; chosen = null; dispose(); });
        apply.addActionListener(e -> { applied = true; chosen = model.region; dispose(); });

        JPanel top = new JPanel(new BorderLayout());
        JLabel help = new JLabel("<html>Arraste dentro para mover; use as alcas para redimensionar. Roda do mouse = zoom; botao direito = mover a foto. "
                + "Pelotas que cruzam o limite ficam fora da analise. Aplicar mostra so o recorte e exige nova analise.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        top.add(help, BorderLayout.CENTER);
        JPanel shapes = new JPanel(new FlowLayout(FlowLayout.LEFT));
        shapes.add(rect); shapes.add(circle); shapes.add(reset); shapes.add(fit); shapes.add(size);
        top.add(shapes, BorderLayout.SOUTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel); actions.add(whole); actions.add(apply);
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(canvas, BorderLayout.CENTER);
        getContentPane().add(actions, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(apply);
        setSize(1100, 800);
        setLocationRelativeTo(owner);
    }
}
