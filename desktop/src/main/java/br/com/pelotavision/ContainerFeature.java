package br.com.pelotavision;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

/** Calibracao pelo diametro interno de um recipiente redondo, com borda detectada automaticamente (mesma logica do Android). */
final class ContainerFeature {
    private ContainerFeature() {}

    static void open(MainFrame f) {
        PhotoSession s = f.session;
        if (s == null || !s.analyzed()) return;
        final int ow = s.originalWidth, oh = s.originalHeight, w = s.width, h = s.height;
        ContainerCalibration.Reference previous = s.scale() == null ? null : s.scale().containerReference;
        final ContainerCalibration.Detection[] detection = {previous == null ? null : previous.detection};
        final ContainerCalibration.Circle[] circles = new ContainerCalibration.Circle[2];
        final ContainerCalibration.Plane[] plane = {previous == null ? ContainerCalibration.Plane.RIM : previous.plane};
        if (detection[0] != null) { circles[0] = detection[0].rim; circles[1] = detection[0].pelletLevel; }
        if (previous != null) circles[previous.plane.ordinal()] = previous.circle;
        JDialog dialog = new JDialog(f, "Diametro interno do recipiente", true);
        ContainerCalibration.Circle start = circles[plane[0].ordinal()] != null ? circles[plane[0].ordinal()] : ContainerCalibration.initial(w, h);
        Editor editor = new Editor(s.analyzedImage, start);
        JTextField real = new JTextField(previous == null ? "" : Double.toString(previous.realInnerDiameterMm), 8);
        JTextField bin = new JTextField(HistogramView.fmt(s.scale() == null ? 1 : s.scale().binWidthMm), 6);
        JRadioButton level = new JRadioButton("Nivel das pelotas"), rim = new JRadioButton("Borda superior");
        ButtonGroup group = new ButtonGroup(); group.add(level); group.add(rim);
        JLabel status = new JLabel(" "), warning = new JLabel(" ");
        status.setForeground(new Color(0, 120, 100));
        warning.setForeground(new Color(170, 90, 0));
        JCheckBox confirmed = new JCheckBox("Conferi o circulo, o plano e o diametro interno real");
        JButton detect = new JButton("Detectar borda"), fit = new JButton("Enquadrar");
        JButton cancel = new JButton("Cancelar"), apply = new JButton("Aplicar diametro");
        final boolean[] quiet = {false};
        Runnable update = () -> {
            ContainerCalibration.Detection d = detection[0];
            warning.setText("<html>" + warningText(d, plane[0]) + "</html>");
            try {
                ContainerCalibration.Reference ref = ContainerCalibration.select(editor.circle, plane[0], positive(real.getText()), d, ow, oh, w, h);
                positive(bin.getText());
                status.setText(String.format(Locale.ROOT, "Ø %s px na original / %s mm = %s px/mm%s", HistogramView.fmt(ref.originalDiameterPixels),
                        HistogramView.fmt(ref.realInnerDiameterMm), HistogramView.fmt4(ref.originalPixelsPerMm),
                        Double.isFinite(ref.rimOvality) ? " · ovalizacao " + HistogramView.fmt(100 * ref.rimOvality) + "%" : ""));
                apply.setEnabled(confirmed.isSelected());
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
                apply.setEnabled(false);
            }
        };
        Runnable showPlane = () -> {
            int i = plane[0].ordinal();
            if (circles[i] == null) { ContainerCalibration.Circle o = circles[1 - i]; circles[i] = o == null ? ContainerCalibration.initial(w, h) : o.manual(); }
            editor.circle = circles[i]; editor.other = circles[1 - i]; editor.repaint(); update.run();
        };
        editor.onChange(() -> { circles[plane[0].ordinal()] = editor.circle; if (!quiet[0]) confirmed.setSelected(false); update.run(); });
        confirmed.addActionListener(e -> update.run());
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { confirmed.setSelected(false); update.run(); }
            public void removeUpdate(DocumentEvent e) { confirmed.setSelected(false); update.run(); }
            public void changedUpdate(DocumentEvent e) { confirmed.setSelected(false); update.run(); }
        };
        real.getDocument().addDocumentListener(dl);
        bin.getDocument().addDocumentListener(dl);
        level.addActionListener(e -> { plane[0] = ContainerCalibration.Plane.PELLET_LEVEL; confirmed.setSelected(false); showPlane.run(); });
        rim.addActionListener(e -> { plane[0] = ContainerCalibration.Plane.RIM; confirmed.setSelected(false); showPlane.run(); });
        fit.addActionListener(e -> editor.fit());
        detect.addActionListener(e -> {
            detect.setEnabled(false);
            confirmed.setSelected(false);
            status.setText("Procurando a borda circular; confira a sugestao antes de aplicar.");
            f.worker().execute(() -> {
                ContainerCalibration.Detection found = suggest(s);
                SwingUtilities.invokeLater(() -> {
                    detect.setEnabled(true);
                    if (!dialog.isShowing()) return;
                    if (found == null) { status.setText("Borda nao encontrada. Enquadre o recipiente inteiro ou ajuste o circulo manualmente."); return; }
                    detection[0] = found; circles[0] = found.rim; circles[1] = found.pelletLevel;
                    plane[0] = ContainerCalibration.suggestedPlane(found);
                    (plane[0] == ContainerCalibration.Plane.RIM ? rim : level).setSelected(true);
                    quiet[0] = true; showPlane.run(); quiet[0] = false;
                    status.setText("<html>" + (found.pelletLevel == null ? "Borda detectada; nivel das pelotas nao encontrado, confira o plano."
                            : "Borda e nivel das pelotas detectados. Amarelo: plano em uso; tracejado: o outro.") + "<br>" + status.getText() + "</html>");
                });
            });
        });
        cancel.addActionListener(e -> dialog.dispose());
        apply.addActionListener(e -> {
            try {
                if (!confirmed.isSelected()) return;
                ContainerCalibration.Reference ref = ContainerCalibration.select(editor.circle, plane[0], positive(real.getText()), detection[0], ow, oh, w, h);
                Granulometry.Settings cfg = Granulometry.Settings.container(ref, positive(bin.getText()), s.scale() == null || s.scale().excludePartial, s.scale() != null && s.scale().excludeReview);
                s.preview(cfg);
                f.setScale(cfg);
                dialog.dispose();
                InterestRegion proposal = s.region() != null ? s.region() : ContainerCalibration.interiorRegion(circles[0] != null ? circles[0] : ref.circle, w, h);
                if (JOptionPane.showConfirmDialog(f, "Escala pelo recipiente aplicada. Definir a area de interesse (ROI) no interior do recipiente?", "Recipiente",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) f.editRegionPublic(proposal);
            } catch (IllegalArgumentException ex) {
                status.setText(ex.getMessage());
            }
        });
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(new JLabel("<html>A borda e detectada automaticamente. Confira o circulo amarelo: arraste a linha ou as alcas para o raio e a cruz central para mover. "
                + "Roda = zoom; botao direito = mover a foto.</html>"));
        top.add(MainFrame.row(new JLabel("Diametro interno real (mm):"), real, new JLabel("Classe (mm):"), bin, detect, fit));
        top.add(MainFrame.row(new JLabel("Plano da medida:"), level, rim));
        top.add(MainFrame.row(status));
        top.add(MainFrame.row(warning));
        top.add(MainFrame.row(confirmed));
        top.add(new JLabel("<html><small>A escala so vale no plano do circulo. Em recipiente fundo a borda fica mais perto da camera que as pelotas, que sairiam menores. "
                + "Use camera paralela a boca, camada nivelada e recipiente cilindrico. Borda ovalizada acima de 6% bloqueia a calibracao. Sem correcao de perspectiva.</small></html>"));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel); actions.add(apply);
        dialog.getContentPane().add(top, BorderLayout.NORTH);
        dialog.getContentPane().add(editor, BorderLayout.CENTER);
        dialog.getContentPane().add(actions, BorderLayout.SOUTH);
        dialog.setSize(1100, 860);
        dialog.setLocationRelativeTo(f);
        (plane[0] == ContainerCalibration.Plane.RIM ? rim : level).setSelected(true);
        quiet[0] = true; showPlane.run(); quiet[0] = false;
        if (previous == null) SwingUtilities.invokeLater(detect::doClick);
        dialog.setVisible(true);
    }

    static String warningText(ContainerCalibration.Detection d, ContainerCalibration.Plane plane) {
        double bias = d == null ? Double.NaN : d.rimOverPelletScale();
        if (Double.isFinite(bias)) {
            if (plane == ContainerCalibration.Plane.RIM && bias > .02)
                return "Atencao: na borda a escala e " + HistogramView.fmt(100 * bias) + "% maior que no nivel das pelotas; as pelotas sairiam cerca de "
                        + HistogramView.fmt(100 * bias / (1 + bias)) + "% menores.";
            return "Diferenca de escala borda/nivel das pelotas: " + HistogramView.fmt(100 * bias) + "%."
                    + (plane == ContainerCalibration.Plane.PELLET_LEVEL ? " Supoe parede cilindrica e pelotas encostadas nela." : "");
        }
        return plane == ContainerCalibration.Plane.PELLET_LEVEL && d != null ? d.pelletNote : " ";
    }

    /** Mesma reducao do Android (lado maior 640 px, luminancia 77/150/29) antes do ContainerRimDetector. */
    static ContainerCalibration.Detection suggest(PhotoSession s) {
        return suggest(s.analyzedImage);
    }

    static ContainerCalibration.Detection suggest(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        double k = Math.min(1, 640. / Math.max(w, h));
        int tw = Math.max(64, (int) Math.round(w * k)), th = Math.max(64, (int) Math.round(h * k));
        BufferedImage small = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, tw, th, null);
        g.dispose();
        byte[] gray = new byte[tw * th];
        for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) {
            int c = small.getRGB(x, y);
            gray[y * tw + x] = (byte) ((77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8);
        }
        try {
            ContainerCalibration.Detection d = ContainerRimDetector.detect(gray, tw, th);
            return d == null ? null : d.scaled((w - 1) / (tw - 1.), (h - 1) / (th - 1.));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double positive(String text) {
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            if (!Double.isFinite(v) || v <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Informe numeros positivos, sem separador de milhar.");
        }
    }

    /** Circulo da borda: linha ou alcas = raio; cruz central = mover. */
    static final class Editor extends EditorCanvas {
        ContainerCalibration.Circle circle, other, start;
        int handle = -1;
        Point2D down;

        Editor(BufferedImage photo, ContainerCalibration.Circle initial) { super(photo); circle = initial; }

        static int hit(ContainerCalibration.Circle c, Point2D p, double radius) {
            if (Math.hypot(p.getX() - c.cx, p.getY() - c.cy) < radius * 1.2) return 0;
            for (int i = 0; i < 4; i++) if (Math.hypot(p.getX() - c.handleX(i), p.getY() - c.handleY(i)) < radius) return 1;
            return Math.abs(Math.hypot(p.getX() - c.cx, p.getY() - c.cy) - c.r) < radius ? 1 : -1;
        }

        @Override protected void press(Point2D p, double radius) { start = circle; down = p; handle = hit(circle, p, radius); }
        @Override protected boolean drag(Point2D p) {
            if (handle < 0 || start == null) return false;
            circle = handle == 0 ? start.moved(p.getX() - down.getX(), p.getY() - down.getY(), photo.getWidth(), photo.getHeight())
                    : start.resizedTo(p.getX(), p.getY(), 8, photo.getWidth(), photo.getHeight());
            return true;
        }
        @Override protected void release() { handle = -1; start = null; }
        @Override protected void paintOverlay(Graphics2D g) {
            DesktopExport.drawContainer(g, circle, other, stroke());
            float arm = stroke() * 6;
            g.draw(new java.awt.geom.Line2D.Double(circle.cx - arm, circle.cy, circle.cx + arm, circle.cy));
            g.draw(new java.awt.geom.Line2D.Double(circle.cx, circle.cy - arm, circle.cx, circle.cy + arm));
            for (int i = 0; i < 4; i++) handle(g, circle.handleX(i), circle.handleY(i));
        }
    }
}
