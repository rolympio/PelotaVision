package br.com.pelotavision;

import org.opencv.core.Mat;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

final class MainFrame extends JFrame {
    private enum Mode { IDLE, LIVE, PHOTO, BUSY }
    private enum Click { PELLET, MEASURE }

    private static final String[] PROFILES = {"Pequenas", "Medias", "Grandes", "Auto"};
    private static final String[] BELT_PROFILES = {"Finas", "Medias", "Grossas", "Auto"};

    /** Troca os nomes dos perfis conforme a cena, mantendo o indice escolhido. */
    private void updateProfileLabels() {
        int selected = Math.max(0, profileBox.getSelectedIndex());
        String[] names = sceneBox.getSelectedIndex() == 1 ? BELT_PROFILES : PROFILES;
        profileBox.setModel(new javax.swing.DefaultComboBoxModel<>(names));
        profileBox.setSelectedIndex(selected);
    }
    private static final int[][] RESOLUTIONS = {{640, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

    private final Preferences prefs = Preferences.userNodeForPackage(MainFrame.class);
    final ImageView view = new ImageView();
    private final HistogramView histogram = new HistogramView();
    private final JTextArea summary = new JTextArea(10, 30);
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();

    private final JComboBox<String> cameraBox = new JComboBox<>();
    private final JComboBox<String> resolutionBox = new JComboBox<>();
    private final JButton cameraButton = new JButton("Abrir camera");
    private final JButton driverButton = new JButton("Ajustes do driver…");
    private final JCheckBox lockFocus = new JCheckBox("Travar foco");
    private final JCheckBox lockExposure = new JCheckBox("Travar exposicao");
    private final JCheckBox liveBox = new JCheckBox("Contagem ao vivo", true);
    private final JButton captureButton = new JButton("Capturar foto");
    private final JButton openButton = new JButton("Abrir imagem…");
    private final JButton backLiveButton = new JButton("Voltar ao vivo");

    private final JComboBox<String> profileBox = new JComboBox<>(PROFILES);
    private final JComboBox<String> sceneBox = new JComboBox<>(new String[]{"Bancada", "Correia"});
    private final JComboBox<String> methodBox = new JComboBox<>(new String[]{"Bordas/gradientes", "LoG/segmentacao", "Rede neural StarDist (demonstracao)"});
    private final JSpinner sensitivity = new JSpinner(new SpinnerNumberModel(75, 0, 100, 5));
    private final JCheckBox autoSuggest = new JCheckBox("Sugerir ajuste ao abrir/capturar", true);
    private final JCheckBox numbers = new JCheckBox("Numeros");
    private final JButton roiButton = new JButton("ROI…");
    private final JButton analyzeButton = new JButton("Segmentar");
    private final JButton cancelButton = new JButton("Cancelar");
    // 0.5.2: paridade com o Android — segmentação detalhada e escolha do realce de imagem escura.
    private final JButton detailButton = new JButton("Detalhada…");
    private final JButton lightButton = new JButton("Luz / imagem escura…");
    private DesktopDetail.Options detailOptions = new DesktopDetail.Options();
    /** null = automático. Não é persistido (como no Android): cada sessão começa no automático. */
    LowLightPreprocessor.Mode lowLightMode;
    private final JButton saveButton = new JButton("Salvar foto + dados…");

    private final JTextField pxPerMm = new JTextField(8);
    private final JTextField binWidth = new JTextField("1", 5);
    private final JCheckBox excludePartial = new JCheckBox("Excluir parciais na borda", true);
    private final JCheckBox excludeReview = new JCheckBox("Excluir marcadas para revisao", false);
    private final JButton measureButton = new JButton("Medir referencia (2 cliques)…");
    private final JButton applyScaleButton = new JButton("Aplicar escala");
    private final JButton clearScaleButton = new JButton("Sem escala (px)");
    private final JLabel scaleLabel = new JLabel("Sem escala fisica · valores em px");
    // ---- Modelos de analise (0.5.0): visualizacao, mapa, classificador de trincas e grafico ----
    private final JComboBox<String> viewBox = new JComboBox<>(new String[]{"Original", "Segmentacao", "Mapa granulometrico"});
    private final javax.swing.JSlider opacitySlider = new javax.swing.JSlider(0, 90, 55);
    private final JCheckBox mapLabels = new JCheckBox("Diametros");
    private final JCheckBox mapFixed = new JCheckBox("Escala fixa");
    private final JTextField mapMin = new JTextField("8", 4), mapMax = new JTextField("16", 4);
    private final JComboBox<String> crackBox = new JComboBox<>(new String[]{CrackClassifierChoice.RANDOM_FOREST.label, CrackClassifierChoice.MOBILENET.label, CrackClassifierChoice.COMPARISON.label});
    private final JComboBox<String> segExtra = new JComboBox<>(new String[]{"YOLO11-seg: indisponivel", "Hibrida: indisponivel"});
    private final JButton chartButton = new JButton("Grafico…");
    ReportVisuals visuals = HistogramView.DEFAULTS;

    final CameraSource camera = new CameraSource();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PelotaVision-analise"); t.setDaemon(true); return t; });
    private final ExecutorService liveWorker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PelotaVision-ao-vivo"); t.setDaemon(true); return t; });
    private final AtomicBoolean liveBusy = new AtomicBoolean();
    private volatile long lastLive;
    private volatile Mode mode = Mode.IDLE;
    private volatile AtomicBoolean cancel;
    private Click click = Click.PELLET;
    private Point2D measureA;

    PhotoSession session;
    int crackSensitivity = 50;

    MainFrame() {
        super(DesktopApp.NAME + " " + DesktopApp.VERSION + " · nucleo " + DesktopApp.CORE_VERSION);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { shutdown(); } });
        for (int[] r : RESOLUTIONS) resolutionBox.addItem(r[0] + " x " + r[1]);
        resolutionBox.setSelectedIndex(Math.min(RESOLUTIONS.length - 1, prefs.getInt("resolution", 2)));
        profileBox.setSelectedIndex(prefs.getInt("profile", 3));
        sceneBox.setSelectedIndex(prefs.getBoolean("belt", false) ? 1 : 0);
        sceneBox.setToolTipText("Correia: pelotas pequenas e numerosas (raio 0,4-1,6% da largura, ate 5000 objetos, ate 1600 px). Trincas tendem a ficar inconclusivas; modo detalhado indisponivel.");
        sceneBox.addActionListener(e -> updateProfileLabels());
        updateProfileLabels();
        int savedMethod = prefs.getInt("method", prefs.getBoolean("legacy", false) ? 1 : 0);
        methodBox.setSelectedIndex(savedMethod < 0 || savedMethod > 2 ? 0 : savedMethod);
        methodBox.setToolTipText("<html>Bancada: " + NeuralPelletDetector.status(false) + "<br>Correia: " + NeuralPelletDetector.status(true)
                + "<br>A rede so propoe objetos; as elipses sao medidas nas bordas. Modelo de correia treinado com pseudo-rotulos sem revisao humana.</html>");
        sensitivity.setValue(prefs.getInt("sensitivity", 75));
        restoreVisuals();
        autoSuggest.setSelected(prefs.getBoolean("auto", true));
        lockFocus.setSelected(prefs.getBoolean("lockFocus", true));
        lockExposure.setSelected(prefs.getBoolean("lockExposure", true));
        cameraBox.addItem("Procurando cameras…");

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(row(new JLabel("Camera:"), cameraBox, resolutionBox, lockFocus, lockExposure, cameraButton, driverButton, liveBox,
                Box.createHorizontalStrut(12), captureButton, openButton, backLiveButton));
        top.add(row(new JLabel("Cena:"), sceneBox, new JLabel("Perfil:"), profileBox, new JLabel("Metodo:"), methodBox, new JLabel("Sensibilidade %:"), sensitivity, autoSuggest,
                numbers, Box.createHorizontalStrut(12), roiButton, analyzeButton, detailButton, lightButton, cancelButton, saveButton));
        extraToolbar = row();
        top.add(extraToolbar);
        segExtra.setEnabled(false);
        segExtra.setToolTipText("<html>YOLO11-seg: sem mascaras de instancia com rotulo humano; codigo e pesos Ultralytics sao AGPL-3.0 (revisao de licenca antes de distribuir).<br>Hibrida: sem regra de fusao validada.</html>");
        crackBox.setToolTipText("<html>Random Forest e o padrao. MobileNetV3 e experimental: poucas trincas reais (rotulos provisorios), testada em uma foto.<br>Recortes dos pixels originais (nunca da super-resolucao). Escore nao calibrado. U-Net de trincas: indisponivel (sem mascaras reais).</html>");
        opacitySlider.setPreferredSize(new Dimension(110, 24));
        opacitySlider.setToolTipText("Opacidade da cor no mapa (0-90%; padrao 55%)");
        top.add(row(new JLabel("Modelos de analise ·"), new JLabel("Segmentacao neural extra:"), segExtra, new JLabel("Trincas:"), crackBox,
                new JLabel("Visualizacao:"), viewBox, new JLabel("Opacidade:"), opacitySlider, mapLabels, mapFixed, mapMin, new JLabel("a"), mapMax, chartButton));

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        scalePanel = new JPanel();
        scalePanel.setLayout(new BoxLayout(scalePanel, BoxLayout.Y_AXIS));
        scalePanel.setBorder(BorderFactory.createTitledBorder("Escala e histograma"));
        scalePanel.add(row(new JLabel("px/mm (imagem original):"), pxPerMm));
        scalePanel.add(row(new JLabel("Classe (mm):"), binWidth));
        scalePanel.add(row(excludePartial));
        scalePanel.add(row(excludeReview));
        scalePanel.add(row(measureButton));
        scalePanel.add(row(applyScaleButton, clearScaleButton));
        scalePanel.add(row(scaleLabel));
        side.add(scalePanel);
        summary.setEditable(false);
        summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(summary);
        sp.setBorder(BorderFactory.createTitledBorder("Resultado"));
        side.add(sp);
        histogram.setBorder(BorderFactory.createTitledBorder("Histograma por numero"));
        side.add(histogram);
        JLabel legend = new JLabel("<html><b>Legenda:</b> verde = aceita · amarelo = revisar/inconclusivo · vermelho = possivel trinca · "
                + "cinza = parcial na borda · tracejado = forma sem confianca · ciano = REF.<br>Clique numa pelota para revisar/calibrar. "
                + "Roda = zoom · arrastar = mover · duplo clique = ajustar.</html>");
        legend.setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        side.add(legend);
        side.setPreferredSize(new Dimension(420, 600));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        bottom.add(status, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(220, 16));
        bottom.add(progress, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(view, BorderLayout.CENTER);
        getContentPane().add(side, BorderLayout.EAST);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH);

        cameraButton.addActionListener(e -> toggleCamera());
        driverButton.addActionListener(e -> { if (!camera.showDriverSettings()) info("Este driver nao oferece a janela de ajustes pelo DirectShow."); });
        captureButton.addActionListener(e -> capture());
        openButton.addActionListener(e -> openImage());
        backLiveButton.addActionListener(e -> backToLive());
        roiButton.addActionListener(e -> editRegion());
        analyzeButton.addActionListener(e -> analyze(false, null));
        camera.onStatus = msg -> SwingUtilities.invokeLater(() -> { setStatus(msg); updateControls(); });
        detailButton.addActionListener(e -> detailed());
        lightButton.addActionListener(e -> lowLightDialog());
        detailButton.setToolTipText("Lê a área nos pixels ORIGINAIS e analisa em blocos sobrepostos (até 3 min). Opcional: contraste local, ruído leve e FSRCNN 2×.");
        lightButton.setToolTipText("Escolhe o realce de imagem escura (só na ROI) com prévia. As medidas continuam nos pixels originais.");
        cancelButton.addActionListener(e -> { AtomicBoolean c = cancel; if (c != null) c.set(true); });
        saveButton.addActionListener(e -> save());
        numbers.addActionListener(e -> view.setNumbers(numbers.isSelected()));
        viewBox.addActionListener(e -> { view.setViewMode(viewBox.getSelectedIndex()); });
        opacitySlider.addChangeListener(e -> applyVisuals());
        mapLabels.addActionListener(e -> applyVisuals());
        mapFixed.addActionListener(e -> applyVisuals());
        mapMin.addActionListener(e -> applyVisuals());
        mapMax.addActionListener(e -> applyVisuals());
        chartButton.addActionListener(e -> chartOptions());
        crackBox.addActionListener(e -> {
            prefs.put("crackChoice", CrackClassifierChoice.values()[crackBox.getSelectedIndex()].name());
            // Mesmos contornos, so a triagem de trincas e refeita com o classificador escolhido.
            if (mode == Mode.PHOTO && session != null && session.analyzed() && !session.needsAnalysis() && session.output() != null) analyze(false, session.output().applied);
        });
        measureButton.addActionListener(e -> startMeasure());
        applyScaleButton.addActionListener(e -> applyScaleFromFields());
        clearScaleButton.addActionListener(e -> { pxPerMm.setText(""); setScale(null); });
        liveBox.addActionListener(e -> { if (!liveBox.isSelected() && mode == Mode.LIVE) { view.setOverlay(null, null); view.setBanner(null); } });
        view.setClickListener(this::onImageClick);
        installExtensions();
        updateControls();
        setStatus("Pronto. Abra a camera ou uma imagem.");
        probeCameras();
    }

    final JPanel extraToolbar;
    final JPanel scalePanel;

    private void restoreVisuals() {
        CrackHistogram.Normalization n;
        try { n = CrackHistogram.Normalization.valueOf(prefs.get("histNorm", "ABSOLUTE")); } catch (IllegalArgumentException e) { n = CrackHistogram.Normalization.ABSOLUTE; }
        visuals = new ReportVisuals(prefs.getDouble("mapOpacity2", GranulometryColorScale.DEFAULT_OPACITY), prefs.getBoolean("mapLabels", false), prefs.getBoolean("mapFixed", false),
                prefs.getDouble("mapMin", 8), prefs.getDouble("mapMax", 16), prefs.getBoolean("histAll", true), prefs.getBoolean("histCracked", true), prefs.getBoolean("histValues", false), n);
        opacitySlider.setValue((int) Math.round(visuals.mapOpacity * 100));
        mapLabels.setSelected(visuals.mapLabels); mapFixed.setSelected(visuals.mapFixed);
        mapMin.setText(HistogramView.fmt2(visuals.mapFixedMin)); mapMax.setText(HistogramView.fmt2(visuals.mapFixedMax));
        crackBox.setSelectedIndex(CrackClassifierChoice.parse(prefs.get("crackChoice", "RANDOM_FOREST")).ordinal());
        viewBox.setSelectedIndex(1);
    }

    /** Aplica opacidade, rotulos e escala do mapa (escala fixa invalida e recusada sem alterar a anterior). */
    private void applyVisuals() {
        double lo = visuals.mapFixedMin, hi = visuals.mapFixedMax;
        try { lo = Double.parseDouble(mapMin.getText().trim().replace(',', '.')); hi = Double.parseDouble(mapMax.getText().trim().replace(',', '.')); if (mapFixed.isSelected()) GranulometryColorScale.fixed(lo, hi, "mm"); }
        catch (IllegalArgumentException e) { if (mapFixed.isSelected()) { setStatus("Escala fixa invalida: informe minimo < maximo."); mapFixed.setSelected(false); } }
        visuals = visuals.withMap(opacitySlider.getValue() / 100.0, mapLabels.isSelected(), mapFixed.isSelected(), lo, hi);
        prefs.putDouble("mapOpacity2", visuals.mapOpacity); prefs.putBoolean("mapLabels", visuals.mapLabels); prefs.putBoolean("mapFixed", visuals.mapFixed);
        prefs.putDouble("mapMin", visuals.mapFixedMin); prefs.putDouble("mapMax", visuals.mapFixedMax);
        refreshVisuals();
    }

    private void refreshVisuals() {
        if (session == null) return;
        histogram.display(session.distribution(), session.crackHistogram(), visuals);
        view.setMap(session.distribution(), visuals);
    }

    /** Opcoes do grafico: series, valores nas barras, frequencia absoluta / percentual / condicional. */
    private void chartOptions() {
        JCheckBox all = new JCheckBox("Mostrar todas as pelotas", visuals.showAll), cracked = new JCheckBox("Mostrar pelotas trincadas", visuals.showCracked),
                values = new JCheckBox("Exibir valores nas barras", visuals.showValues);
        JComboBox<String> norm = new JComboBox<>(new String[]{"Frequencia absoluta (numero de pelotas)", "Percentual do total de pelotas validas (subconjunto)", "Condicional: cada serie soma 100%"});
        norm.setSelectedIndex(visuals.normalization.ordinal());
        Object[] content = {all, cracked, values, norm, new JLabel("<html>A serie laranja usa as mesmas classes e eixos e nunca e somada ao total.<br>Sem classificacao valida aparece \"classificacao indisponivel\", nao zero.</html>")};
        if (JOptionPane.showConfirmDialog(this, content, "Grafico granulometrico", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        visuals = visuals.withHistogram(all.isSelected() || !cracked.isSelected(), cracked.isSelected(), values.isSelected(), CrackHistogram.Normalization.values()[norm.getSelectedIndex()]);
        prefs.putBoolean("histAll", visuals.showAll); prefs.putBoolean("histCracked", visuals.showCracked); prefs.putBoolean("histValues", visuals.showValues); prefs.put("histNorm", visuals.normalization.name());
        refreshVisuals();
    }

    /** Pontos de extensao para recursos adicionais (calibracoes, trincas, ajustes). */
    private void installExtensions() { Features.install(this); }

    static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        for (Component c : cs) p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    // ---------------- Camera ----------------

    private void probeCameras() {
        worker.execute(() -> {
            List<Integer> found = CameraSource.probe(6);
            SwingUtilities.invokeLater(() -> {
                cameraBox.removeAllItems();
                for (int i : found) cameraBox.addItem("Camera " + i);
                if (found.isEmpty()) cameraBox.addItem("Nenhuma camera encontrada");
                int saved = prefs.getInt("camera", 0);
                for (int k = 0; k < cameraBox.getItemCount(); k++) if (cameraBox.getItemAt(k).equals("Camera " + saved)) cameraBox.setSelectedIndex(k);
                updateControls();
            });
        });
    }

    private int selectedCamera() {
        Object s = cameraBox.getSelectedItem();
        if (s == null || !s.toString().startsWith("Camera ")) return -1;
        return Integer.parseInt(s.toString().substring(7).trim());
    }

    private void toggleCamera() {
        if (camera.isRunning()) {
            camera.close();
            if (mode == Mode.LIVE) { mode = Mode.IDLE; view.setOverlay(null, null); view.setBanner(null); }
            updateControls();
            setStatus("Camera fechada.");
            return;
        }
        int index = selectedCamera();
        if (index < 0) { info("Nenhuma camera selecionada."); return; }
        int[] res = RESOLUTIONS[resolutionBox.getSelectedIndex()];
        savePrefs();
        setStatus("Abrindo camera " + index + "…");
        worker.execute(() -> {
            try {
                CameraSource.Info i = camera.open(index, res[0], res[1], lockFocus.isSelected(), lockExposure.isSelected(), this::onFrame);
                SwingUtilities.invokeLater(() -> {
                    mode = Mode.LIVE;
                    view.setViewport(null);
                    view.setReference(null);
                    view.setOverlay(null, null);
                    setStatus(i.summary() + ". Use \"Ajustes do driver\" para fixar foco e exposicao se a camera permitir.");
                    updateControls();
                });
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> { error("Camera", ex); updateControls(); });
            }
        });
    }

    private void onFrame(Mat frame) {
        if (mode != Mode.LIVE) return;
        BufferedImage img = Mats.toImage(frame);
        SwingUtilities.invokeLater(() -> { if (mode == Mode.LIVE) view.setImage(img, true); });
        long now = System.currentTimeMillis();
        if (liveBox.isSelected() && now - lastLive > 300 && liveBusy.compareAndSet(false, true)) {
            lastLive = now;
            Mat copy = frame.clone();
            DesktopAnalysis.Config cfg = config();
            liveWorker.execute(() -> {
                Mat gray = null;
                try {
                    gray = Mats.gray(copy);
                    PelletDetector.Result r = DesktopAnalysis.live(gray, cfg);
                    SwingUtilities.invokeLater(() -> {
                        if (mode != Mode.LIVE || !liveBox.isSelected()) return;
                        view.setOverlay(r, null);
                        view.setBanner("AO VIVO: " + r.circles.size() + " propostas (contagem rapida; analise completa na foto)");
                    });
                } catch (RuntimeException ignored) {
                } finally {
                    copy.release();
                    if (gray != null) gray.release();
                    liveBusy.set(false);
                }
            });
        }
    }

    private void capture() {
        Mat frame = camera.snapshot();
        if (frame == null) { info("Ainda nao ha quadro da camera."); return; }
        CameraSource.Info i = camera.currentInfo();
        loadPhoto(new PhotoSession(null, Mats.toImage(frame), i == null ? null : i.copy(), frame));
        frame.release();
    }

    private void backToLive() {
        if (!camera.isRunning()) { info("Abra a camera primeiro."); return; }
        mode = Mode.LIVE;
        view.setViewport(null);
        view.setReference(null);
        view.setOverlay(null, null);
        view.setMap(null, visuals);
        view.setViewMode(1); // ao vivo: sempre marcacoes
        view.setBanner(null);
        view.setMeasure(null, null);
        updateControls();
    }

    // ---------------- Foto ----------------

    private void openImage() {
        JFileChooser chooser = new JFileChooser(prefs.get("openDir", FileSystemView.getFileSystemView().getDefaultDirectory().getPath()));
        chooser.setFileFilter(new FileNameExtensionFilter("Imagens (jpg, png, webp, bmp, tif)", "jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        prefs.put("openDir", f.getParent());
        try {
            Mat bgr = Mats.readUpright(f);
            loadPhoto(new PhotoSession(f, null, null, bgr));
            bgr.release();
        } catch (Exception ex) {
            error("Abrir imagem", ex);
        }
    }

    private void loadPhoto(PhotoSession s) {
        if (session != null) session.release();
        Granulometry.Settings keep = session == null ? null : session.scale();
        session = s;
        // Escala digitada continua valendo; referencias medidas pertencem a foto anterior.
        if (keep != null && "pixels_per_mm".equals(keep.calibrationSource)) session.setScale(keep);
        else if (keep != null) setStatus("Nova imagem: a escala medida na foto anterior foi removida. Calibre de novo ou digite px/mm.");
        mode = Mode.PHOTO;
        measureA = null; click = Click.PELLET;
        view.setBanner(null);
        view.setMeasure(null, null);
        view.setImage(s.analyzedImage, false);
        refreshPhoto();
        if (autoSuggest.isSelected()) analyze(true, null);
        else analyze(false, null);
    }

    DesktopAnalysis.Config config() {
        DesktopAnalysis.Config c = new DesktopAnalysis.Config();
        c.profile = profileBox.getSelectedIndex();
        c.belt = sceneBox.getSelectedIndex() == 1;
        c.method = methodBox.getSelectedIndex();
        c.sensitivity = (Integer) sensitivity.getValue();
        c.autoSuggest = autoSuggest.isSelected();
        c.crackSensitivity = crackSensitivity;
        c.crackChoice = CrackClassifierChoice.values()[Math.max(0, crackBox.getSelectedIndex())];
        c.visuals = visuals;
        c.scale = session == null ? null : session.scale();
        c.minimumExpectedMm = session == null ? 5 : session.minimumExpectedMm();
        c.lowLightMode = lowLightMode;
        return c;
    }

    /**
     * Segmenta a foto/ROI. search=true faz a busca de ajuste; fixed != null reaplica um ajuste
     * (reanalise de trincas). Como no Android, reanalisar descarta revisoes manuais (com aviso).
     */
    void analyze(boolean search, DetectionTuner.Option fixed) {
        PhotoSession s = session;
        if (s == null) { info("Capture uma foto ou abra uma imagem."); return; }
        if (s.pendingReviews() > 0 && JOptionPane.showConfirmDialog(this, "Reanalisar descarta " + s.pendingReviews() + " revisao(oes) manual(is) de trinca. Continuar?",
                "Reanalisar", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        savePrefs();
        DesktopAnalysis.Config cfg = config();
        cfg.autoSuggest = search;
        InterestRegion region = s.region();
        AtomicBoolean c = new AtomicBoolean(false);
        cancel = c;
        mode = Mode.BUSY;
        updateControls();
        progress.setIndeterminate(!search);
        progress.setValue(0);
        setStatus(search ? "Buscando ajuste pela iluminacao da " + (region == null ? "foto" : "ROI") + " (ate 30 s)…" : "Segmentando " + (region == null ? "foto…" : "ROI…"));
        worker.execute(() -> {
            try {
                DesktopAnalysis.Output out = DesktopAnalysis.analyze(s.analyzed, s.originalWidth, s.originalHeight, s.sample, region, cfg, fixed, c::get,
                        (done, total, label) -> SwingUtilities.invokeLater(() -> { progress.setIndeterminate(false); progress.setMaximum(Math.max(1, total)); progress.setValue(done); setStatus("Busca " + done + "/" + total + ": " + label); }));
                SwingUtilities.invokeLater(() -> {
                    if (session != s) return;
                    if (c.get() && out.suggestions == null) { mode = Mode.PHOTO; setStatus("Processamento cancelado; resultado anterior preservado."); updateControls(); return; }
                    String warning = s.accept(out);
                    mode = Mode.PHOTO;
                    refreshPhoto();
                    CrackClassifierChoice.Resolution cr = DesktopAnalysis.lastResolution;
                    setStatus("Analise concluida: " + s.active().circles.size() + " propostas" + (out.suggestions != null && out.suggestions.cancelled ? " (busca cancelada; ajuste atual)" : "") + "."
                            + (cr == null ? "" : " Trincas: " + cr.effective.label + (cr.message != null ? " — " + cr.message : "") + "."));
                    if (cr != null && cr.fellBack()) JOptionPane.showMessageDialog(this, cr.message, "Classificador de trincas", JOptionPane.WARNING_MESSAGE);
                    if (warning != null) JOptionPane.showMessageDialog(this, warning, "Escala", JOptionPane.WARNING_MESSAGE);
                });
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> { mode = Mode.PHOTO; error("Analise", ex); updateControls(); });
            } finally {
                SwingUtilities.invokeLater(() -> { progress.setIndeterminate(false); progress.setValue(0); cancel = null; });
            }
        });
    }

    // ---------------- Segmentação detalhada (0.5.2) ----------------

    /** Opções e execução da segmentação detalhada; com resultado detalhado, mostra antes o resumo. */
    private void detailed() {
        PhotoSession s = session;
        if (s == null || mode != Mode.PHOTO) return;
        if (s.output() != null && s.output().detailed != null && !s.needsAnalysis()) {
            Object[] choices = {"Segmentar novamente", "Fechar"};
            int r = JOptionPane.showOptionDialog(this, DesktopDetail.summary(s.output().detailed), "Segmentação detalhada", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, choices, choices[1]);
            if (r != 0) return;
        }
        if (sceneBox.getSelectedIndex() == 1) { info("Na correia use a análise rápida: ela já trabalha na resolução nativa (até 1600 px) e o modo detalhado mede os perfis pelo bloco, não pela foto."); return; }
        JCheckBox compare = new JCheckBox("Comparar original, contraste local e redução leve de ruído", detailOptions.compare);
        JCheckBox search = new JCheckBox("Buscar configuração de detecção antes dos blocos", detailOptions.search);
        JCheckBox sr = new JCheckBox("Testar super-resolução FSRCNN 2× (experimental)", detailOptions.superResolution);
        boolean srAvailable = DesktopApp.model(FsrcnnUpscaler.MODEL).isFile();
        if (!srAvailable) { sr.setSelected(false); sr.setEnabled(false); sr.setText(sr.getText() + " — modelo ausente"); }
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel intro = new JLabel("<html><body style='width:420px'>Detalhada lê a área da foto ORIGINAL (antes da redução para análise) e analisa blocos sobrepostos. Pode demorar até 3 minutos. "
                + "A escala e as coordenadas do relatório são preservadas.<br><br>SR só propõe contornos: elipses são reajustadas e trincas analisadas nos pixels originais. "
                + "Mais marcações não provam maior precisão. Leitura limitada a 8 megapixels por área.</body></html>");
        p.add(intro); p.add(compare); p.add(search); p.add(sr);
        if (JOptionPane.showConfirmDialog(this, p, "Como segmentar esta área?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (s.pendingReviews() > 0 && JOptionPane.showConfirmDialog(this, "Reanalisar descarta " + s.pendingReviews() + " revisao(oes) manual(is) de trinca. Continuar?",
                "Reanalisar", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        DesktopDetail.Options opt = new DesktopDetail.Options();
        opt.compare = compare.isSelected(); opt.search = search.isSelected(); opt.superResolution = sr.isSelected() && srAvailable;
        detailOptions = opt;
        savePrefs();
        DesktopAnalysis.Config cfg = config();
        DetectionTuner.Option current = s.output() != null && !s.needsAnalysis() ? s.output().applied : null;
        AtomicBoolean c = new AtomicBoolean(false);
        cancel = c;
        mode = Mode.BUSY;
        updateControls();
        progress.setIndeterminate(true);
        setStatus("Segmentação detalhada: lendo a " + (s.region() == null ? "foto" : "ROI") + " original…");
        worker.execute(() -> {
            try {
                DesktopAnalysis.Output out = DesktopDetail.run(s, cfg, current, opt, c::get,
                        (done, total, label) -> SwingUtilities.invokeLater(() -> { progress.setIndeterminate(false); progress.setMaximum(Math.max(1, total)); progress.setValue(done); setStatus("Detalhada " + done + "/" + total + ": " + label); }));
                SwingUtilities.invokeLater(() -> {
                    if (session != s) return;
                    String warning = s.accept(out);
                    mode = Mode.PHOTO;
                    refreshPhoto();
                    setStatus(out.detailed.summary() + " · " + s.active().circles.size() + " propostas. Confira os contornos antes de salvar.");
                    if (warning != null) JOptionPane.showMessageDialog(this, warning, "Escala", JOptionPane.WARNING_MESSAGE);
                });
            } catch (java.util.concurrent.CancellationException ex) {
                SwingUtilities.invokeLater(() -> { mode = Mode.PHOTO; setStatus("Processamento cancelado; resultado anterior preservado."); updateControls(); });
            } catch (Exception | OutOfMemoryError ex) {
                SwingUtilities.invokeLater(() -> { mode = Mode.PHOTO; error("Segmentação detalhada", ex instanceof Exception ? (Exception) ex : new RuntimeException("Memória insuficiente: reduza a ROI.", ex)); updateControls(); });
            } finally {
                SwingUtilities.invokeLater(() -> { progress.setIndeterminate(false); progress.setValue(0); cancel = null; });
            }
        });
    }

    // ---------------- Imagem escura (0.5.2) ----------------

    /** Escolha do realce com prévia, como "Recuperação de imagem escura" do Android. Aplicar exige ROI. */
    private void lowLightDialog() {
        PhotoSession s = session;
        if (s == null || mode != Mode.PHOTO) return;
        LowLightPreprocessor.Mode[] modes = LowLightPreprocessor.Mode.values();
        String[] choices = {"Automático", "Original (sem filtro)", "Gama + CLAHE", "Redução de ruído + CLAHE", "Retinex"};
        JComboBox<String> box = new JComboBox<>(choices);
        box.setSelectedIndex(lowLightMode == null ? 0 : lowLightMode.ordinal() + 1);
        JLabel preview = new JLabel();
        preview.setPreferredSize(new Dimension(640, 400));
        preview.setHorizontalAlignment(JLabel.CENTER);
        // Prévia ilustrativa sobre a área mostrada (ROI ou foto), reduzida a 900 px; a análise usa a ROI inteira.
        AnalysisWindow w = new AnalysisWindow(s.width, s.height, s.region());
        Mat small;
        {
            Mat full = Mats.gray(s.analyzed), crop = null;
            try {
                crop = w.region == null ? full : w.cropGray(full);
                double f = Math.min(1, 900.0 / Math.max(crop.cols(), crop.rows()));
                small = new Mat();
                org.opencv.imgproc.Imgproc.resize(crop, small, new org.opencv.core.Size(Math.max(1, crop.cols() * f), Math.max(1, crop.rows() * f)), 0, 0, org.opencv.imgproc.Imgproc.INTER_AREA);
            } finally {
                if (crop != null && crop != full) crop.release();
                full.release();
            }
        }
        LowLightPreprocessor.Quality q = LowLightPreprocessor.assess(small);
        JLabel quality = new JLabel(String.format(Locale.ROOT, "Nível médio de cinza %.0f · contraste %.0f · %s", q.mean, q.standardDeviation,
                q.needsCorrection() ? "imagem escura ou de baixo contraste" : "iluminação adequada"));
        Runnable render = () -> {
            int i = box.getSelectedIndex();
            Mat out = LowLightPreprocessor.apply(small, i <= 1 ? LowLightPreprocessor.Mode.ORIGINAL : modes[i - 1]);
            try {
                BufferedImage img = Mats.toImage(out);
                double f = Math.min(640.0 / img.getWidth(), 400.0 / img.getHeight());
                preview.setIcon(new javax.swing.ImageIcon(img.getScaledInstance(Math.max(1, (int) (img.getWidth() * f)), Math.max(1, (int) (img.getHeight() * f)), java.awt.Image.SCALE_SMOOTH)));
            } finally {
                out.release();
            }
        };
        box.addActionListener(e -> render.run());
        render.run();
        JPanel p = new JPanel(new BorderLayout(6, 6));
        JLabel note = new JLabel("<html><body style='width:620px'>Prévia de iluminação. A segmentação usa a ROI selecionada e refina as medidas na imagem original. "
                + "Automático só aplica realce quando a ROI está escura ou com pouco contraste.</body></html>");
        p.add(note, BorderLayout.NORTH); p.add(preview, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(4, 4)); south.add(box, BorderLayout.NORTH); south.add(quality, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        Object[] options = {"Aplicar e analisar", "Cancelar"};
        int r;
        try {
            r = JOptionPane.showOptionDialog(this, p, "Recuperação de imagem escura", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        } finally {
            small.release();
        }
        if (r != 0) return;
        if (s.region() == null) { info("Selecione primeiro uma ROI (botão ROI…). O realce de imagem escura só é aplicado numa área selecionada."); return; }
        int i = box.getSelectedIndex();
        lowLightMode = i == 0 ? null : modes[i - 1];
        analyze(false, s.output() != null && !s.needsAnalysis() ? s.output().applied : null);
    }

    double prefsMinimum() { return prefs.getDouble("minimumMm", 5); }
    void savePrefsMinimum(double v) { prefs.putDouble("minimumMm", v); }
    void editRegionPublic() { editRegion(); }

    /** Ganchos do roteiro de capturas do manual (DesktopManual): mesmas ações dos botões, sem seletor de arquivo. */
    void manual(String action) {
        switch (action) {
            case "detalhada": detailed(); break;
            case "luz": lowLightDialog(); break;
            case "roi": editRegion(); break;
            case "grafico": chartOptions(); break;
            case "mapa": viewBox.setSelectedIndex(2); break;
            case "original": viewBox.setSelectedIndex(0); break;
            case "segmentacao": viewBox.setSelectedIndex(1); break;
            default: throw new IllegalArgumentException(action);
        }
    }
    void manualLoad(PhotoSession s) { loadPhoto(s); }

    private void editRegion() {
        PhotoSession s = session;
        if (s == null || mode != Mode.PHOTO) return;
        RegionEditorDialog d = new RegionEditorDialog(this, s.analyzedImage, s.region());
        d.setVisible(true);
        if (!d.applied) return;
        try {
            s.setRegion(d.chosen);
            refreshPhoto();
            if (s.needsAnalysis()) setStatus("Recorte aplicado. Toque em Segmentar (ou busque o ajuste) para analisar " + (d.chosen == null ? "a foto." : "a ROI."));
            else setStatus(d.chosen == null ? "Imagem inteira." : "ROI ativa na tela e no relatorio.");
        } catch (RuntimeException ex) {
            error("ROI", ex);
        }
    }

    /** Atualiza tela, resumo, histograma e escala a partir da sessao. */
    void refreshPhoto() {
        PhotoSession s = session;
        if (s == null) return;
        view.setImage(s.shownImage(), true); // 0.5.2: filtrada quando a análise aplicou realce de iluminação
        view.setViewport(s.displayWindow());
        view.setOverlay(s.active(), s.activeCracks());
        view.setReference(s.reference());
        view.setNumbers(numbers.isSelected());
        StringBuilder t = new StringBuilder();
        t.append(String.format(Locale.ROOT, "Imagem %d x %d px%s.\n", s.originalWidth, s.originalHeight,
                s.sample > 1 ? " · analisada a 1/" + s.sample + " (" + s.width + " x " + s.height + ")" : ""));
        if (s.region() != null) t.append(String.format(Locale.ROOT, "ROI %s %.0f x %.0f px%s.\n", s.region().shape == InterestRegion.Shape.CIRCLE ? "circular" : "retangular",
                s.region().width(), s.region().height(), s.regionExcluded() > 0 ? " · " + s.regionExcluded() + " fora/cruzando o limite" : ""));
        if (!s.analyzed()) t.append("Aguardando analise.\n");
        else if (s.filteredImage() != null) t.append("Iluminacao: " + LowLightInfo.label(s.lowLight().mode) + " aplicado na ROI (exibicao e relatorio; medidas nos pixels originais).\n");
        else if (s.needsAnalysis()) t.append("Area alterada · aguardando analise. Salvar fica bloqueado ate segmentar.\n");
        else {
            for (String line : DesktopExport.summaryLines(s)) t.append(line).append('\n');
            if (s.distributionError() != null) t.append("Escala/classe: ").append(s.distributionError()).append('\n');
        }
        summary.setText(t.toString());
        summary.setCaretPosition(0);
        histogram.display(s.distribution(), s.crackHistogram(), visuals);
        view.setMap(s.distribution(), visuals);
        view.setViewMode(viewBox.getSelectedIndex());
        refreshScaleLabel();
        Features.refresh(this);
        updateControls();
    }

    // ---------------- Cliques na foto ----------------

    private void onImageClick(Point2D p) {
        if (mode != Mode.PHOTO || session == null) return;
        if (click == Click.MEASURE) { measureClick(p); return; }
        Features.pelletClicked(this, p);
    }

    // ---------------- Escala ----------------

    double parse(JTextField f) {
        String t = f.getText().trim().replace(',', '.');
        if (t.isEmpty()) return Double.NaN;
        return Double.parseDouble(t);
    }

    double binWidth() {
        double bin = parse(binWidth);
        if (!Double.isFinite(bin) || bin <= 0) throw new IllegalArgumentException("Classe deve ser positiva.");
        return bin;
    }
    boolean excludePartial() { return excludePartial.isSelected(); }
    boolean excludeReview() { return excludeReview.isSelected(); }

    void setScale(Granulometry.Settings s) {
        if (session == null) { info("Abra uma foto primeiro."); return; }
        try {
            session.setScale(s);
            if (s != null && Double.isFinite(session.scale().originalPixelsPerMm)) pxPerMm.setText(String.format(Locale.ROOT, "%.4f", session.scale().originalPixelsPerMm));
            if (s != null) binWidth.setText(HistogramView.fmt(s.binWidthMm));
            refreshPhoto();
        } catch (RuntimeException ex) {
            error("Escala", ex);
        }
    }

    private void applyScaleFromFields() {
        if (session == null) { info("Abra uma foto primeiro."); return; }
        try {
            double bin = binWidth();
            double ppm = parse(pxPerMm);
            Granulometry.Settings cur = session.scale();
            if (cur != null && Double.isFinite(ppm) && Math.abs(ppm - cur.originalPixelsPerMm) < 5e-5) setScale(cur.withHistogram(bin, excludePartial(), excludeReview()));
            else if (Double.isFinite(ppm)) setScale(new Granulometry.Settings(ppm, bin, excludePartial(), excludeReview()));
            else if (cur != null) setScale(cur.withHistogram(bin, excludePartial(), excludeReview()));
            else info("Informe px/mm ou use uma das calibracoes.");
        } catch (RuntimeException ex) {
            error("Escala", ex);
        }
    }

    private void startMeasure() {
        if (mode != Mode.PHOTO || session == null) { info("Capture uma foto ou abra uma imagem primeiro."); return; }
        measureA = null;
        click = Click.MEASURE;
        view.setMeasure(null, null);
        setStatus("Clique nas duas extremidades de uma referencia de comprimento conhecido (no mesmo plano das pelotas). Use zoom para precisao.");
    }

    private void measureClick(Point2D p) {
        if (measureA == null) { measureA = p; view.setMeasure(p, null); return; }
        Point2D a = measureA;
        view.setMeasure(a, p);
        measureA = null;
        click = Click.PELLET;
        double originalPx = a.distance(p) * session.sample;
        String mm = JOptionPane.showInputDialog(this, String.format(Locale.ROOT,
                "Distancia marcada: %.1f px na imagem original.\nComprimento real da referencia (mm):", originalPx), "Referencia de comprimento", JOptionPane.QUESTION_MESSAGE);
        view.setMeasure(null, null);
        if (mm == null || mm.trim().isEmpty()) return;
        try {
            double real = Double.parseDouble(mm.trim().replace(',', '.'));
            setScale(Granulometry.Settings.reference(originalPx, real, binWidth(), excludePartial(), excludeReview()));
        } catch (RuntimeException ex) {
            error("Referencia", ex);
        }
    }

    private void refreshScaleLabel() {
        Granulometry.Settings s = session == null ? null : session.scale();
        if (s == null) { scaleLabel.setText("Sem escala fisica · valores em px"); return; }
        String source = s.beltReference != null ? "Correia" : s.selectedObject != null ? "REF " + s.selectedObject.summary()
                : s.estimated() ? "ESTIMATIVA pela media informada (hipotese)" : "reference_length".equals(s.calibrationSource) ? "referencia de comprimento" : "digitado";
        scaleLabel.setText(String.format(Locale.ROOT, "<html>%.4f px/mm · %s<br>classe %s mm</html>", s.originalPixelsPerMm, source, HistogramView.fmt(s.binWidthMm)));
    }

    // ---------------- Salvar ----------------

    private void save() {
        PhotoSession s = session;
        if (s == null || !s.canSave()) { info(s != null && s.needsAnalysis() ? "A area mudou: segmente antes de salvar." : "Analise uma foto antes de salvar."); return; }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Pasta para os resultados");
        File def = new File(FileSystemView.getFileSystemView().getDefaultDirectory(), "PelotaVision");
        File last = new File(prefs.get("saveDir", def.getPath()));
        chooser.setCurrentDirectory(last.getParentFile());
        chooser.setSelectedFile(last);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File folder = chooser.getSelectedFile();
        prefs.put("saveDir", folder.getPath());
        try {
            List<File> files = DesktopExport.save(folder, s, config());
            StringBuilder t = new StringBuilder("Arquivos salvos em\n" + folder + "\n\n");
            for (File f : files) t.append(f.getName()).append('\n');
            JOptionPane.showMessageDialog(this, t.toString(), "Resultado salvo", JOptionPane.INFORMATION_MESSAGE);
            setStatus("Resultados salvos em " + folder);
        } catch (Exception ex) {
            error("Salvar", ex);
        }
    }

    // ---------------- Utilidades ----------------

    boolean busy() { return mode == Mode.BUSY; }
    boolean photoMode() { return mode == Mode.PHOTO && session != null; }

    void updateControls() {
        boolean busy = mode == Mode.BUSY;
        boolean running = camera.isRunning();
        boolean photo = mode == Mode.PHOTO && session != null;
        cameraButton.setText(running ? "Fechar camera" : "Abrir camera");
        cameraButton.setEnabled(!busy && (running || selectedCamera() >= 0));
        cameraBox.setEnabled(!busy && !running);
        resolutionBox.setEnabled(!busy && !running);
        lockFocus.setEnabled(!running); lockExposure.setEnabled(!running);
        driverButton.setEnabled(running);
        captureButton.setEnabled(!busy && running && !camera.isReconnecting());
        backLiveButton.setEnabled(!busy && running && mode != Mode.LIVE);
        openButton.setEnabled(!busy);
        roiButton.setEnabled(!busy && photo);
        analyzeButton.setEnabled(!busy && photo);
        analyzeButton.setText(session != null && session.region() != null ? "Segmentar ROI" : "Segmentar foto");
        detailButton.setEnabled(!busy && photo);
        detailButton.setText(session != null && session.output() != null && session.output().detailed != null ? "Detalhada ✓…" : "Detalhada…");
        lightButton.setEnabled(!busy && photo);
        lightButton.setText(lowLightMode == null ? "Luz / imagem escura…" : "Luz: " + LowLightInfo.label(lowLightMode) + "…");
        cancelButton.setEnabled(busy);
        saveButton.setEnabled(!busy && photo && session.canSave());
        measureButton.setEnabled(!busy && photo);
        applyScaleButton.setEnabled(!busy && photo);
        clearScaleButton.setEnabled(!busy && photo);
        Features.updateControls(this, busy, photo);
    }

    private void savePrefs() {
        prefs.putInt("resolution", resolutionBox.getSelectedIndex());
        prefs.putInt("profile", profileBox.getSelectedIndex());
        prefs.putBoolean("belt", sceneBox.getSelectedIndex() == 1);
        prefs.putBoolean("legacy", methodBox.getSelectedIndex() == 1);
        prefs.putInt("method", methodBox.getSelectedIndex());
        prefs.putInt("sensitivity", (Integer) sensitivity.getValue());
        prefs.putBoolean("auto", autoSuggest.isSelected());
        prefs.putBoolean("lockFocus", lockFocus.isSelected());
        prefs.putBoolean("lockExposure", lockExposure.isSelected());
        int cam = selectedCamera();
        if (cam >= 0) prefs.putInt("camera", cam);
    }

    void setStatus(String s) { status.setText(s); }
    void info(String s) { JOptionPane.showMessageDialog(this, s, DesktopApp.NAME, JOptionPane.INFORMATION_MESSAGE); }
    void error(String title, Throwable e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        setStatus(title + ": " + msg);
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE);
    }
    ExecutorService worker() { return worker; }
    JProgressBar progress() { return progress; }
    void setBusy(boolean b) { mode = b ? Mode.BUSY : (session != null ? Mode.PHOTO : Mode.IDLE); updateControls(); }
    void setCancel(AtomicBoolean c) { cancel = c; }

    private void shutdown() {
        savePrefs();
        AtomicBoolean c = cancel;
        if (c != null) c.set(true);
        camera.close();
        worker.shutdownNow();
        liveWorker.shutdownNow();
        dispose();
        System.exit(0);
    }
}
