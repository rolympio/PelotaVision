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
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * Recursos do Android 0.11.0 levados ao Windows: calibracao por objeto (item 2) e pela
 * correia (item 3), revisao de trincas por pelota (item 4), comparacao de ajustes (item 5),
 * escala por media conhecida (item 6) e detalhes de forma (item 10).
 */
final class Features {
    private Features() {}

    private static JButton beltButton, scaleObjectButton, compareButton, searchButton, crackButton, meanButton, shapeButton;
    private static JSpinner minimum;

    static void install(MainFrame f) {
        beltButton = new JButton("Calibrar pela largura da correia…");
        scaleObjectButton = new JButton("Editar referencia selecionada…");
        meanButton = new JButton("Estimar por media conhecida…");
        compareButton = new JButton("Comparar ajustes…");
        searchButton = new JButton("Buscar melhor ajuste");
        crackButton = new JButton("Trincas…");
        shapeButton = new JButton("Forma projetada…");
        minimum = new JSpinner(new SpinnerNumberModel(f.prefsMinimum(), 0.0, 100.0, 0.5));
        f.extraToolbar.add(searchButton);
        f.extraToolbar.add(compareButton);
        f.extraToolbar.add(new JLabel("Minimo esperado (mm):"));
        f.extraToolbar.add(minimum);
        f.extraToolbar.add(crackButton);
        f.extraToolbar.add(shapeButton);
        f.scalePanel.add(MainFrame.row(beltButton));
        JButton containerButton = new JButton("Calibrar pelo diametro do recipiente…");
        containerButton.setToolTipText("Detecta a borda de um recipiente redondo e usa o diametro interno conhecido (plano da borda ou nivel das pelotas).");
        f.scalePanel.add(MainFrame.row(containerButton));
        containerButton.addActionListener(e -> ContainerFeature.open(f));
        f.scalePanel.add(MainFrame.row(scaleObjectButton));
        f.scalePanel.add(MainFrame.row(meanButton));
        f.scalePanel.add(MainFrame.row(new JLabel("<html><i>Calibrar por objeto: clique numa pelota inteira ou esfera e escolha \"Calibrar\".</i></html>")));

        beltButton.addActionListener(e -> belt(f));
        scaleObjectButton.addActionListener(e -> {
            ObjectCalibration.Reference r = f.session == null ? null : f.session.reference();
            if (r == null) { f.info("Nenhuma referencia selecionada. Clique numa pelota inteira e escolha Calibrar."); return; }
            objectCalibration(f, r.geometry, r.fullImageId, r.displayIdAtSelection);
        });
        meanButton.addActionListener(e -> estimatedMean(f));
        compareButton.addActionListener(e -> compare(f));
        searchButton.addActionListener(e -> f.analyze(true, null));
        crackButton.addActionListener(e -> crackSettings(f));
        shapeButton.addActionListener(e -> shape(f));
        minimum.addChangeListener(e -> {
            double v = ((Number) minimum.getValue()).doubleValue();
            f.savePrefsMinimum(v);
            if (f.session != null) { f.session.setMinimumExpectedMm(v); f.refreshPhoto(); }
        });
    }

    static double minimumMm() { return minimum == null ? 5 : ((Number) minimum.getValue()).doubleValue(); }

    static void refresh(MainFrame f) {
        if (f.session != null && f.session.minimumExpectedMm() != minimumMm()) f.session.setMinimumExpectedMm(minimumMm());
    }

    static void updateControls(MainFrame f, boolean busy, boolean photo) {
        if (beltButton == null) return;
        boolean ready = !busy && photo && f.session.analyzed() && !f.session.needsAnalysis();
        beltButton.setEnabled(ready);
        meanButton.setEnabled(ready);
        scaleObjectButton.setEnabled(ready && f.session.reference() != null);
        compareButton.setEnabled(ready);
        searchButton.setEnabled(!busy && photo);
        crackButton.setEnabled(!busy && photo && f.session.analyzed());
        shapeButton.setEnabled(ready);
    }

    // ---------------- Clique numa pelota (reviewCrackAt) ----------------

    static void pelletClicked(MainFrame f, Point2D p) {
        PhotoSession s = f.session;
        if (s == null || !s.analyzed() || s.needsAnalysis() || f.busy()) return;
        double x = p.getX(), y = p.getY();
        ObjectCalibration.Reference ref = s.reference();
        if (ref != null && (ref.excludeFromStatistics || (s.region() != null && !s.region().includes(ref.geometry))) && ref.geometry.normalizedDistance(x, y) < 1) {
            objectCalibration(f, ref.geometry, ref.fullImageId, ref.displayIdAtSelection);
            return;
        }
        int index = s.activeAt(x, y);
        if (index < 0) return;
        PelletDetector.Detection d = s.active().circles.get(index);
        if (s.activeCracks() == null) { objectCalibration(f, d, s.fullIndex(index) + 1, index + 1); return; }
        CrackAnalysis.Entry entry = s.activeCracks().entries.get(index);
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(new JLabel(new javax.swing.ImageIcon(zoom(s.analyzedImage, d, 260))));
        if (Double.isFinite(entry.modelScore)) content.add(new JLabel(String.format(Locale.ROOT, "Escore RF: %.3f · nao e probabilidade", entry.modelScore)));
        content.add(new JLabel("Estado: " + label(entry.status) + (entry.manuallyReviewed ? " (revisado; automatico: " + label(entry.automatic) + ")" : "")));
        content.add(new JLabel(String.format(Locale.ROOT, "Diametro equivalente: %.1f px%s · b/a %s", 2 * d.radius,
                s.distribution() != null && !s.distribution().settings.pixelsOnly() ? String.format(Locale.ROOT, " = %.2f mm", s.distribution().diameterMm(2 * d.radius)) : "",
                d.ellipseFitted ? String.format(Locale.ROOT, "%.3f", d.aspectRatio()) : "—")));
        content.add(new JLabel("<html>A revisao muda a contagem; nao desenha uma linha que o detector nao localizou.</html>"));
        String[] options = {"Trinca visivel", "Sem trinca visivel", "Inconclusiva", "Restaurar automatico", "Calibrar por esta pelota", "Fechar"};
        int choice = JOptionPane.showOptionDialog(f, content, "Pelota " + entry.pelletId, JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[5]);
        if (choice >= 0 && choice <= 3) {
            CrackAnalysis.Status status = choice == 0 ? CrackAnalysis.Status.POSSIBLE : choice == 1 ? CrackAnalysis.Status.NO_INDICATION : choice == 2 ? CrackAnalysis.Status.INCONCLUSIVE : null;
            s.reviewCrack(index, status);
            f.refreshPhoto();
        } else if (choice == 4) {
            if (!d.ellipseFitted || d.partial || d.needsReview) { f.info("Escolha uma elipse inteira, ajustada e sem alerta para calibrar."); return; }
            objectCalibration(f, d, s.fullIndex(index) + 1, index + 1);
        }
    }

    private static String label(CrackAnalysis.Status st) {
        return st == CrackAnalysis.Status.POSSIBLE ? "possivel trinca" : st == CrackAnalysis.Status.NO_INDICATION ? "sem indicacao" : "inconclusiva";
    }

    static BufferedImage zoom(BufferedImage photo, PelletDetector.Detection d, int target) {
        int x0 = Math.max(0, (int) (d.x - d.extentX() * 1.15)), y0 = Math.max(0, (int) (d.y - d.extentY() * 1.15));
        int w = Math.min(photo.getWidth() - x0, Math.max(1, (int) (d.extentX() * 2.3))), h = Math.min(photo.getHeight() - y0, Math.max(1, (int) (d.extentY() * 2.3)));
        BufferedImage crop = photo.getSubimage(x0, y0, w, h);
        double k = target / (double) Math.max(w, h);
        BufferedImage out = new BufferedImage(Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(crop.getScaledInstance(out.getWidth(), out.getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }

    // ---------------- Item 2: calibracao por objeto ----------------

    static void objectCalibration(MainFrame f, PelletDetector.Detection selected, int fullId, int displayId) {
        PhotoSession s = f.session;
        if (s == null || !s.analyzed()) return;
        try { ObjectCalibration.eligible(selected); } catch (IllegalArgumentException e) { f.info(e.getMessage()); return; }
        final PelletDetector.Result snapshot = s.output().result;
        ObjectCalibration.Reference previous = s.reference();
        boolean editing = previous != null && previous.fullImageId == fullId && previous.sameGeometry(selected);
        JDialog dialog = new JDialog(f, editing ? "Editar referencia de escala" : "Calibrar pela pelota " + displayId, true);
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        c.add(new JLabel("<html>Voce escolheu esta referencia. Informe a medida externa em mm, no sentido da linha destacada.<br>"
                + "Use a referencia no mesmo plano das demais pelotas.</html>"));
        JLabel preview = new JLabel();
        c.add(preview);
        ButtonGroup group = new ButtonGroup();
        ObjectCalibration.Measure[] measures = ObjectCalibration.Measure.values();
        JRadioButton[] radios = new JRadioButton[measures.length];
        for (int i = 0; i < measures.length; i++) { radios[i] = new JRadioButton(measures[i].label); group.add(radios[i]); c.add(radios[i]); }
        JLabel dimension = new JLabel("Medida real (mm)");
        JTextField real = new JTextField(editing ? Double.toString(previous.realMm) : "", 8);
        JCheckBox exclude = new JCheckBox("Excluir referencia das estatisticas");
        JLabel note = new JLabel();
        JTextField bin = new JTextField(HistogramView.fmt(s.scale() == null ? 1 : s.scale().binWidthMm), 6);
        JLabel result = new JLabel(" ");
        result.setForeground(new Color(0, 120, 100));
        c.add(MainFrame.row(dimension, real));
        c.add(exclude);
        c.add(new JLabel("<html><small>A esfera padrao fica fora da contagem, das trincas, da forma media e do histograma por padrao. "
                + "A pelota medida pode continuar na amostra. A marca REF continua visivel.</small></html>"));
        c.add(note);
        c.add(MainFrame.row(new JLabel("Largura de cada classe (mm)"), bin));
        c.add(result);
        Runnable[] update = new Runnable[1];
        java.util.function.Supplier<ObjectCalibration.Measure> chosen = () -> { for (int i = 0; i < radios.length; i++) if (radios[i].isSelected()) return measures[i]; return ObjectCalibration.Measure.MAJOR_AXIS; };
        java.util.function.Supplier<Granulometry.Settings> input = () -> {
            ObjectCalibration.Reference ref = ObjectCalibration.select(selected, fullId, displayId, chosen.get(), positive(real.getText()), exclude.isSelected(),
                    s.originalWidth, s.originalHeight, snapshot.width, snapshot.height);
            return Granulometry.Settings.selectedObject(ref, positive(bin.getText()), s.scale() == null || s.scale().excludePartial, s.scale() != null && s.scale().excludeReview);
        };
        update[0] = () -> {
            ObjectCalibration.Measure m = chosen.get();
            preview.setIcon(new javax.swing.ImageIcon(referencePreview(s.analyzedImage, selected, m)));
            try {
                Granulometry.Settings cfg = input.get();
                ObjectCalibration.Reference ref = cfg.selectedObject;
                Granulometry.Distribution measured = s.preview(cfg);
                result.setText(String.format(Locale.ROOT, "<html>Escala calculada: %s px/mm na foto original.<br>Referencia: maior %s mm · menor %s mm · equivalente %s mm.<br>%s</html>",
                        HistogramView.fmt4(cfg.originalPixelsPerMm), HistogramView.fmt(ref.majorMm()), HistogramView.fmt(ref.minorMm()), HistogramView.fmt(ref.equivalentMm()),
                        measured.included == 0 ? "Nenhuma pelota incluida na distribuicao." : "Histograma: media " + HistogramView.fmt(measured.meanMm) + " mm · n=" + measured.included + " · " + measured.binCount() + " classes."));
            } catch (IllegalArgumentException e) {
                result.setText("Informe a medida real e a classe para conferir a escala.");
            }
        };
        for (int i = 0; i < radios.length; i++) {
            final ObjectCalibration.Measure m = measures[i];
            radios[i].addActionListener(e -> {
                dimension.setText(m == ObjectCalibration.Measure.STANDARD_SPHERE ? "Diametro conhecido da esfera (mm)" : m == ObjectCalibration.Measure.MAJOR_AXIS ? "Eixo maior medido com paquimetro (mm)" : "Eixo menor medido com paquimetro (mm)");
                exclude.setSelected(editing && m == previous.measure ? previous.excludeFromStatistics : m == ObjectCalibration.Measure.STANDARD_SPHERE);
                note.setText("<html><small>" + (m == ObjectCalibration.Measure.STANDARD_SPHERE ? "Esfera: usa o diametro equivalente a area da elipse. Confira se o contorno corresponde ao padrao conhecido."
                        + (selected.aspectRatio() < .9 ? " A projecao esta alongada; isso pode enviesar a escala." : "")
                        : "Meca a mesma dimensao destacada pela linha. O diametro equivalente do histograma pode diferir do eixo medido.") + "</small></html>");
                update[0].run();
            });
        }
        DocumentListener dl = docListener(update[0]);
        real.getDocument().addDocumentListener(dl);
        bin.getDocument().addDocumentListener(dl);
        exclude.addActionListener(e -> update[0].run());
        radios[editing ? previous.measure.ordinal() : 0].doClick();
        JButton cancel = new JButton("Cancelar"), apply = new JButton("Aplicar escala");
        cancel.addActionListener(e -> dialog.dispose());
        apply.addActionListener(e -> {
            if (f.session != s || s.output().result != snapshot) { dialog.dispose(); f.info("A foto mudou. Selecione a referencia novamente."); return; }
            try { f.setScale(input.get()); dialog.dispose(); f.setStatus("Escala aplicada a foto pela referencia escolhida."); }
            catch (IllegalArgumentException ex) { result.setText(ex.getMessage()); }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel); actions.add(apply);
        dialog.getContentPane().add(new JScrollPane(c), BorderLayout.CENTER);
        dialog.getContentPane().add(actions, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(apply);
        dialog.setSize(560, 720);
        dialog.setLocationRelativeTo(f);
        dialog.setVisible(true);
    }

    /** Recorte da referencia com o eixo a medir destacado (ReferencePreviewView). */
    static BufferedImage referencePreview(BufferedImage photo, PelletDetector.Detection d, ObjectCalibration.Measure m) {
        BufferedImage z = zoom(photo, d, 220);
        int x0 = Math.max(0, (int) (d.x - d.extentX() * 1.15)), y0 = Math.max(0, (int) (d.y - d.extentY() * 1.15));
        int w = Math.min(photo.getWidth() - x0, Math.max(1, (int) (d.extentX() * 2.3)));
        int h = Math.min(photo.getHeight() - y0, Math.max(1, (int) (d.extentY() * 2.3)));
        double k = z.getWidth() / (double) w;
        Graphics2D g = z.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(k, z.getHeight() / (double) h);
        g.translate(-x0, -y0);
        g.setColor(new Color(255, 214, 0));
        g.setStroke(new java.awt.BasicStroke((float) (2 / k)));
        g.draw(ImageView.outline(d));
        double t = Math.toRadians(d.angleDeg), ax, ay;
        if (m == ObjectCalibration.Measure.MINOR_AXIS) { ax = -Math.sin(t) * d.semiMinor; ay = Math.cos(t) * d.semiMinor; }
        else if (m == ObjectCalibration.Measure.MAJOR_AXIS) { ax = Math.cos(t) * d.semiMajor; ay = Math.sin(t) * d.semiMajor; }
        else { double r = Math.sqrt(d.semiMajor * d.semiMinor); ax = Math.cos(t) * r; ay = Math.sin(t) * r; }
        g.setStroke(new java.awt.BasicStroke((float) (3 / k)));
        g.draw(new java.awt.geom.Line2D.Double(d.x - ax, d.y - ay, d.x + ax, d.y + ay));
        g.dispose();
        return z;
    }

    // ---------------- Item 3: largura da correia ----------------

    static void belt(MainFrame f) {
        PhotoSession s = f.session;
        if (s == null || !s.analyzed()) return;
        final int ow = s.originalWidth, oh = s.originalHeight, w = s.width, h = s.height;
        BeltCalibration.Reference previous = s.scale() == null ? null : s.scale().beltReference;
        JDialog dialog = new JDialog(f, "Largura da correia", true);
        BeltEditor editor = new BeltEditor(s.analyzedImage, previous == null ? BeltCalibration.initial(w, h, false) : previous.edges);
        JTextField real = new JTextField(previous == null ? "" : Double.toString(previous.realWidthMm), 8);
        JTextField bin = new JTextField(HistogramView.fmt(s.scale() == null ? 1 : s.scale().binWidthMm), 6);
        JLabel status = new JLabel(" ");
        status.setForeground(new Color(0, 120, 100));
        JCheckBox confirmed = new JCheckBox("Conferi as bordas e a largura real da correia");
        JButton suggest = new JButton("Sugerir bordas"), rotate = new JButton("Girar guia"), fit = new JButton("Enquadrar");
        JButton cancel = new JButton("Cancelar"), apply = new JButton("Aplicar largura");
        Runnable update = () -> {
            try {
                BeltCalibration.Reference ref = BeltCalibration.select(editor.edges, positive(real.getText()), ow, oh, w, h);
                positive(bin.getText());
                status.setText(String.format(Locale.ROOT, "%s px na original / %s mm = %s px/mm · variacao %s%%", HistogramView.fmt(ref.originalWidthPixels),
                        HistogramView.fmt(ref.realWidthMm), HistogramView.fmt4(ref.originalPixelsPerMm), HistogramView.fmt(100 * ref.widthVariation)));
                apply.setEnabled(confirmed.isSelected());
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
                apply.setEnabled(false);
            }
        };
        editor.onChange(() -> { confirmed.setSelected(false); update.run(); });
        confirmed.addActionListener(e -> update.run());
        DocumentListener dl = docListener(() -> { confirmed.setSelected(false); update.run(); });
        real.getDocument().addDocumentListener(dl);
        bin.getDocument().addDocumentListener(dl);
        final boolean[] horizontal = {false};
        rotate.addActionListener(e -> { horizontal[0] = !horizontal[0]; editor.edges = BeltCalibration.initial(w, h, horizontal[0]); editor.fit(); editor.fireChange(); });
        fit.addActionListener(e -> editor.fit());
        suggest.addActionListener(e -> {
            suggest.setEnabled(false);
            confirmed.setSelected(false);
            status.setText("Buscando linhas persistentes; confira a sugestao antes de aplicar.");
            f.worker().execute(() -> {
                BeltCalibration.Edges found = suggestEdges(s);
                SwingUtilities.invokeLater(() -> {
                    suggest.setEnabled(true);
                    if (!dialog.isShowing()) return;
                    if (found != null) { editor.edges = found; editor.repaint(); update.run(); status.setText("<html>Bordas sugeridas: confira as faixas sem material e ajuste.<br>" + status.getText() + "</html>"); }
                    else status.setText("Sem sugestao confiavel. Ajuste as duas linhas manualmente.");
                });
            });
        });
        cancel.addActionListener(e -> dialog.dispose());
        apply.addActionListener(e -> {
            try {
                if (!confirmed.isSelected()) return;
                BeltCalibration.Reference ref = BeltCalibration.select(editor.edges, positive(real.getText()), ow, oh, w, h);
                Granulometry.Settings cfg = Granulometry.Settings.belt(ref, positive(bin.getText()), s.scale() == null || s.scale().excludePartial, s.scale() != null && s.scale().excludeReview);
                s.preview(cfg);
                f.setScale(cfg);
                dialog.dispose();
                if (JOptionPane.showConfirmDialog(f, "Escala pela correia aplicada. Definir agora a area de interesse (ROI) sobre o material?", "Correia",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) f.editRegionPublic();
            } catch (IllegalArgumentException ex) {
                status.setText(ex.getMessage());
            }
        });
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(new JLabel("<html>Marque as DUAS bordas da correia, incluindo as faixas sem material. Arraste as quatro alcas ou as linhas. "
                + "Roda = zoom; botao direito = mover a foto.</html>"));
        top.add(MainFrame.row(new JLabel("Largura real (mm):"), real, new JLabel("Classe (mm):"), bin, suggest, rotate, fit));
        top.add(MainFrame.row(status));
        top.add(MainFrame.row(confirmed));
        top.add(new JLabel("<html><small>Use trecho plano e camera perpendicular. A largura nominal de correia abaulada e a altura do material podem enviesar a escala. "
                + "Variacao entre as larguras marcadas acima de 8% bloqueia a calibracao. Sem correcao de perspectiva.</small></html>"));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel); actions.add(apply);
        dialog.getContentPane().add(top, BorderLayout.NORTH);
        dialog.getContentPane().add(editor, BorderLayout.CENTER);
        dialog.getContentPane().add(actions, BorderLayout.SOUTH);
        dialog.setSize(1100, 820);
        dialog.setLocationRelativeTo(f);
        update.run();
        if (previous == null) SwingUtilities.invokeLater(suggest::doClick);
        dialog.setVisible(true);
    }

    /** Mesma reducao do Android (lado maior 480 px, luminancia 77/150/29) antes do BeltEdgeDetector. */
    static BeltCalibration.Edges suggestEdges(PhotoSession s) {
        int w = s.width, h = s.height;
        double k = Math.min(1, 480. / Math.max(w, h));
        int tw = Math.max(32, (int) Math.round(w * k)), th = Math.max(32, (int) Math.round(h * k));
        BufferedImage small = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(s.analyzedImage, 0, 0, tw, th, null);
        g.dispose();
        byte[] gray = new byte[tw * th];
        for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) {
            int c = small.getRGB(x, y);
            gray[y * tw + x] = (byte) ((77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8);
        }
        try {
            BeltCalibration.Edges edge = BeltEdgeDetector.suggest(gray, tw, th);
            if (edge == null) return null;
            double[] p = new double[8];
            for (int i = 0; i < 4; i++) { p[2 * i] = edge.x(i) * (w - 1) / (tw - 1.); p[2 * i + 1] = edge.y(i) * (h - 1) / (th - 1.); }
            return new BeltCalibration.Edges(p, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Editor das bordas: alcas 0..3 (pontas) e 4/5 (linhas inteiras), como BeltEditorView. */
    static final class BeltEditor extends EditorCanvas {
        BeltCalibration.Edges edges, start;
        int handle = -1;
        Point2D down;

        BeltEditor(BufferedImage photo, BeltCalibration.Edges initial) { super(photo); edges = initial; }

        void fireChange() { repaint(); fire(); }

        static int hit(BeltCalibration.Edges e, Point2D p, double radius) {
            int best = -1;
            double bestD = radius;
            for (int i = 0; i < 4; i++) { double d = Math.hypot(e.x(i) - p.getX(), e.y(i) - p.getY()); if (d < bestD) { bestD = d; best = i; } }
            if (best >= 0) return best;
            for (int line = 0; line < 2; line++) {
                double d = java.awt.geom.Line2D.ptSegDist(e.x(2 * line), e.y(2 * line), e.x(2 * line + 1), e.y(2 * line + 1), p.getX(), p.getY());
                if (d < bestD) { bestD = d; best = 4 + line; }
            }
            return best;
        }

        @Override protected void press(Point2D p, double radius) { start = edges; down = p; handle = hit(edges, p, radius); }
        @Override protected boolean drag(Point2D p) {
            if (handle < 0 || start == null) return false;
            edges = start.moved(handle, p.getX() - down.getX(), p.getY() - down.getY(), photo.getWidth(), photo.getHeight());
            return true;
        }
        @Override protected void release() { handle = -1; start = null; }
        @Override protected void paintOverlay(Graphics2D g) {
            DesktopExport.drawBelt(g, edges, stroke());
            for (int i = 0; i < 4; i++) handle(g, edges.x(i), edges.y(i));
        }
    }

    // ---------------- Item 4: sensibilidade e reanalise de trincas ----------------

    static void crackSettings(MainFrame f) {
        PhotoSession s = f.session;
        if (s == null || !s.analyzed()) return;
        JSlider slider = new JSlider(0, 100, f.crackSensitivity);
        slider.setMajorTickSpacing(25); slider.setPaintTicks(true); slider.setPaintLabels(true);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel("<html>Sensibilidade das trincas (padrao 50). Reanalisar refaz a triagem com o mesmo ajuste de deteccao;<br>"
                + "revisoes manuais de trinca serao descartadas.</html>"), BorderLayout.NORTH);
        p.add(slider, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(f, p, "Sensibilidade das trincas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        f.crackSensitivity = slider.getValue();
        f.analyze(false, s.output().applied);
    }

    // ---------------- Item 5: comparacao de ajustes ----------------

    static void compare(MainFrame f) {
        PhotoSession s = f.session;
        if (s == null || !s.analyzed() || s.needsAnalysis()) return;
        DetectionTuner.Report report = s.rankedSuggestions();
        if (report == null) {
            if (JOptionPane.showConfirmDialog(f, "Nao ha busca de ajustes para esta foto/ROI. Buscar agora (ate 30 s)?", "Comparacao de ajustes",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) f.analyze(true, null);
            return;
        }
        List<DetectionTuner.Trial> trials = report.ordered();
        DetectionTuner.Trial best = report.best();
        String applied = s.output().applied.id;
        String unit = report.context.physical() ? "mm" : "px";
        String[] cols = {"", "Ajuste", "Pontos", "Propostas", "Validas", "Parciais", "Revisao", "Sobrepostas", "Abaixo min.", "Qualidade", "Cobertura", "Pureza", "Weibull k (" + unit + ")", "Erro"};
        AbstractTableModel model = new AbstractTableModel() {
            public int getRowCount() { return trials.size(); }
            public int getColumnCount() { return cols.length; }
            public String getColumnName(int c) { return cols[c]; }
            public Object getValueAt(int r, int c) {
                DetectionTuner.Trial t = trials.get(r);
                DetectionAssessment.Metrics m = t.metrics;
                switch (c) {
                    case 0: return (t.option.id.equals(applied) ? "APLICADO " : "") + (t == best ? "RECOMENDADO" : "");
                    case 1: return t.option.label();
                    case 2: return String.format(Locale.ROOT, "%.1f", t.score);
                    case 3: return t.result == null ? "—" : Integer.toString(t.result.circles.size());
                    case 4: return m == null ? "—" : Integer.toString(m.valid);
                    case 5: return m == null ? "—" : Integer.toString(m.partial);
                    case 6: return m == null ? "—" : Integer.toString(m.review);
                    case 7: return m == null ? "—" : Integer.toString(m.overlapping);
                    case 8: return m == null ? "—" : Integer.toString(m.belowMinimum);
                    case 9: return m == null ? "—" : String.format(Locale.ROOT, "%.2f", m.fitQuality);
                    case 10: return t.coverage == null || !t.coverage.available ? "—" : String.format(Locale.ROOT, "%.0f%%", 100 * t.coverage.covered);
                    case 11: return t.coverage == null || !t.coverage.available ? "—" : String.format(Locale.ROOT, "%.0f%%", 100 * t.coverage.purity);
                    case 12: return m == null || !Double.isFinite(m.weibull.shape) ? "—" : String.format(Locale.ROOT, "%.2f", m.weibull.shape);
                    default: return t.error == null ? "" : t.error;
                }
            }
        };
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        JDialog dialog = new JDialog(f, "Comparacao de ajustes", true);
        JLabel head = new JLabel(String.format(Locale.ROOT, "<html>%d de %d opcoes · %.1f s%s · minimo esperado %s mm %s.<br>"
                        + "Pontos sao comparacao heuristica, nao probabilidade de acerto; cobertura de pixels nao e taxa de deteccao. "
                        + "Re-ranquear apos calibrar nunca troca os contornos sozinho.%s</html>",
                report.trials.size(), report.planned, report.durationMs / 1000.0, report.cancelled ? " (cancelada)" : report.timeLimited ? " (limite de tempo)" : "",
                HistogramView.fmt(report.context.expectedMinimumMm), report.context.minimumActive() ? "(ativo)" : "(inativo: exige escala fisica independente)",
                report.weak() ? "<br><b>Sugestao fraca para esta imagem: confira as marcacoes.</b>" : ""));
        head.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JButton apply = new JButton("Aplicar selecionado"), close = new JButton("Fechar");
        close.addActionListener(e -> dialog.dispose());
        apply.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            DetectionTuner.Trial t = trials.get(row);
            if (t.result == null) { f.info("Esta opcao falhou: " + t.error); return; }
            if (s.pendingReviews() > 0 && JOptionPane.showConfirmDialog(dialog, "Aplicar outro ajuste descarta " + s.pendingReviews() + " revisao(oes) de trinca. Continuar?",
                    "Aplicar", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            dialog.dispose();
            applyTrial(f, s, report, t);
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(apply); actions.add(close);
        dialog.getContentPane().add(head, BorderLayout.NORTH);
        dialog.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.getContentPane().add(actions, BorderLayout.SOUTH);
        dialog.setSize(new Dimension(1200, 560));
        dialog.setLocationRelativeTo(f);
        dialog.setVisible(true);
    }

    private static void applyTrial(MainFrame f, PhotoSession s, DetectionTuner.Report report, DetectionTuner.Trial t) {
        f.setBusy(true);
        f.setStatus("Aplicando " + t.option.label() + "…");
        DesktopAnalysis.Output current = s.output();
        f.worker().execute(() -> {
            try {
                DesktopAnalysis.Output out = DesktopAnalysis.applyTrial(s.analyzed, s.originalWidth, s.originalHeight, s.sample, current.window, current.suggestions, t, f.crackSensitivity);
                SwingUtilities.invokeLater(() -> {
                    f.setBusy(false);
                    if (f.session != s) return;
                    String warning = s.accept(out);
                    f.refreshPhoto();
                    f.setStatus("Ajuste aplicado: " + t.option.label() + " · " + s.active().circles.size() + " propostas.");
                    if (warning != null) JOptionPane.showMessageDialog(f, warning, "Escala", JOptionPane.WARNING_MESSAGE);
                });
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> { f.setBusy(false); f.error("Aplicar ajuste", ex); });
            }
        });
    }

    // ---------------- Item 6: media conhecida ----------------

    static void estimatedMean(MainFrame f) {
        PhotoSession s = f.session;
        if (s == null || !s.analyzed()) return;
        JTextField mean = new JTextField(s.scale() != null && Double.isFinite(s.scale().knownMeanMm) ? Double.toString(s.scale().knownMeanMm) : "", 8);
        JTextField bin = new JTextField(HistogramView.fmt(s.scale() == null ? 1 : s.scale().binWidthMm), 6);
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(new JLabel("<html><b>Hipotese:</b> a escala sera deduzida assumindo que a media das pelotas incluidas<br>"
                + "tem o valor informado. Nao e referencia independente: o histograma fica forcado a essa media,<br>"
                + "e o minimo esperado da busca nao usa esta escala. Use apenas para visualizacao.</html>"));
        p.add(MainFrame.row(new JLabel("Media conhecida (mm):"), mean));
        p.add(MainFrame.row(new JLabel("Classe (mm):"), bin));
        if (JOptionPane.showConfirmDialog(f, p, "Estimar por media conhecida", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            f.setScale(Granulometry.Settings.estimatedMean(positive(mean.getText()), positive(bin.getText()), f.excludePartial(), f.excludeReview()));
            f.setStatus("Escala ESTIMADA pela media informada (hipotese), marcada na tela, no PNG e no JSON.");
        } catch (IllegalArgumentException ex) {
            f.error("Media conhecida", ex);
        }
    }

    // ---------------- Item 10: forma projetada ----------------

    static void shape(MainFrame f) {
        PhotoSession s = f.session;
        if (s == null || s.shape() == null) return;
        ShapeStatistics sh = s.shape();
        int fitted = 0, partial = 0, review = 0;
        for (PelletDetector.Detection d : s.active().circles) { if (d.ellipseFitted) fitted++; if (d.partial) partial++; if (d.needsReview) review++; }
        f.info(String.format(Locale.ROOT, "Esfericidade aparente 2D (b/a): %s\nIncluidas: %d · excluidas: %d\nElipses ajustadas: %d · parciais na borda: %d · com alerta: %d\n\n"
                        + "Criterio: elipse ajustada, inteira na imagem, sem alerta, dentro da ROI; referencia excluida fica fora.\n"
                        + "Media aritmetica por numero de b/a (eixo menor / eixo maior). 1 = projecao circular; nao mede esfericidade 3D.",
                Double.isFinite(sh.mean) ? String.format(Locale.ROOT, "%.3f", sh.mean) : "—", sh.included, sh.excluded, fitted, partial, review));
    }

    // ---------------- Utilidades ----------------

    static double positive(String value) {
        try {
            double r = Double.parseDouble(value.trim().replace(',', '.'));
            if (!Double.isFinite(r) || r <= 0) throw new NumberFormatException();
            return r;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Informe numeros positivos, sem separador de milhar.");
        }
    }

    static DocumentListener docListener(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

}
