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
    // 0.5.3: curva acumulada com ajuste Rosin–Rammler (mesmas pelotas e classes do histograma).
    private final RosinRammlerView rrView = new RosinRammlerView();
    final ReportPreview reportPreview = new ReportPreview();
    private final JComboBox<String> rrBasisBox = new JComboBox<>(new String[]{RosinRammler.Basis.NUMBER.label, RosinRammler.Basis.MASS.label});
    private final JButton scaleButton = new JButton("Escala e histograma…");
    private javax.swing.JDialog scaleDialog;
    private final JTextArea summary = new JTextArea(10, 30);
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private StudioShell studio;
    private final javax.swing.DefaultListModel<String> historyModel = new javax.swing.DefaultListModel<>();

    private final JComboBox<String> cameraBox = new JComboBox<>();
    private final JComboBox<String> resolutionBox = new JComboBox<>();
    private final JButton cameraButton = new JButton("Abrir camera");
    private final JButton driverButton = new JButton("Ajustes do driver…");
    private final JCheckBox lockFocus = new JCheckBox("Travar foco");
    private final JCheckBox lockExposure = new JCheckBox("Travar exposicao");
    private final JCheckBox liveBox = new JCheckBox("Contagem ao vivo", true);
    // 0.5.5: cena parada -> busca genética do ajuste + consenso temporal (menos fantasmas, medida estável).
    private final JCheckBox liveTuneBox = new JCheckBox("Otimizar quando parado", true);
    /** 0.16.0: estratégia da otimização ao vivo; a rápida é o padrão (a genética da 0.5.5 continua disponível). */
    private final JComboBox<String> liveSearchBox = new JComboBox<>(new String[]{"Busca rapida", "Busca genetica"});
    /** 0.16.0: filtro de luz e escala também no ao vivo. */
    private volatile LiveFrameAnalysis.Light liveLight = LiveFrameAnalysis.Light.OFF;
    private volatile boolean liveShowFiltered = true;
    private volatile LiveFrameAnalysis.ScaleTransfer liveScaleOverride;
    private final JButton liveScaleButton = new JButton("Escala ao vivo…");
    private final LiveStabilizer stabilizer = new LiveStabilizer();
    private volatile long lastThumb;
    private final JButton captureButton = new JButton("Capturar foto");
    private final JButton openButton = new JButton("Abrir imagem…");
    private final JButton backLiveButton = new JButton("Voltar ao vivo");

    private final JComboBox<String> profileBox = new JComboBox<>(PROFILES);
    private final JComboBox<String> sceneBox = new JComboBox<>(new String[]{"Bancada", "Correia"});
    private final JComboBox<String> methodBox = new JComboBox<>(new String[]{"Bordas/gradientes", "LoG/segmentacao", "Rede neural StarDist (demonstracao)"});
    private final JSpinner sensitivity = new JSpinner(new SpinnerNumberModel(75, 0, 100, 5));
    private final JCheckBox autoSuggest = new JCheckBox("Sugerir ajuste ao abrir/capturar", true);
    /** 0.18.0: ROI automática (recipiente redondo/retangular, trecho carregado da correia, campo cheio). */
    private final JCheckBox autoRoiBox = new JCheckBox("ROI automática", true);
    private final AutoRoi.Stabilizer liveAuto = new AutoRoi.Stabilizer();
    private volatile long lastAutoRoi;
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
    private final JComboBox<String> viewBox = new JComboBox<>(new String[]{"Original", "Segmentacao", "Mapa granulometrico", "Instâncias (elipses)", "Classes visíveis (elipses)", "Possíveis trincas", "IA multitarefa experimental", "Incerteza IA (não calibrada)", "IA: bordas candidatas", "IA: separação candidata"});
    private final javax.swing.JSlider opacitySlider = new javax.swing.JSlider(0, 90, 55);
    private final JCheckBox mapLabels = new JCheckBox("Diametros");
    private final JCheckBox mapFixed = new JCheckBox("Escala fixa");
    private final JTextField mapMin = new JTextField("8", 4), mapMax = new JTextField("16", 4);
    private final JComboBox<String> crackBox = new JComboBox<>(new String[]{CrackClassifierChoice.RANDOM_FOREST.label, CrackClassifierChoice.MOBILENET.label, CrackClassifierChoice.COMPARISON.label});
    private final JComboBox<String> segExtra = new JComboBox<>(new String[]{"YOLO11-seg: indisponivel", "Hibrida: indisponivel"});
    private final JButton chartButton = new JButton("Grafico…");
    // 0.5.6: aceleracao por hardware das redes (ONNX Runtime: CUDA, depois CPU). Desligada por padrao.
    private final JCheckBox accelBox = new JCheckBox("Aceleracao GPU");
    private final JButton accelInfo = new JButton("Estado…");
    // 0.9.0: refino por mascaras SAM 2.1 (mesmo codigo e modelo do Android). Ligado por padrao quando o modelo esta no pacote.
    private final JCheckBox sam2Box = new JCheckBox("Mascaras SAM 2 (tamanho pela area)");
    private final JButton previewAiButton = new JButton("Testar IA multitarefa…");
    ReportVisuals visuals = HistogramView.DEFAULTS;

    final CameraSource camera = new CameraSource();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PelotaVision-analise"); t.setDaemon(true); return t; });
    private final ExecutorService liveWorker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PelotaVision-ao-vivo"); t.setDaemon(true); return t; });
    private final AtomicBoolean liveBusy = new AtomicBoolean();
    /** 0.5.4: ROI do ao vivo, em pixels do quadro da câmera; null = quadro inteiro. Vai junto para a foto capturada. */
    private volatile InterestRegion liveRoi;
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
        setIconImages(appIcons());
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { shutdown(); } });
        for (int[] r : RESOLUTIONS) resolutionBox.addItem(r[0] + " x " + r[1]);
        resolutionBox.setSelectedIndex(Math.min(RESOLUTIONS.length - 1, prefs.getInt("resolution", 2)));
        profileBox.setSelectedIndex(prefs.getInt("profile", 3));
        sceneBox.setSelectedIndex(prefs.getBoolean("belt", false) ? 1 : 0);
        sceneBox.setToolTipText("Correia: pelotas pequenas e numerosas (raio 0,4-1,6% da largura, ate 5000 objetos, ate 1600 px). Trincas tendem a ficar inconclusivas; modo detalhado indisponivel.");
        sceneBox.addActionListener(e -> updateProfileLabels());
        updateProfileLabels();
        migrateDefaults018();
        int savedMethod = prefs.getInt("method", prefs.getBoolean("legacy", false) ? 1 : DetectionTuner.NEURAL);
        methodBox.setSelectedIndex(savedMethod < 0 || savedMethod > 2 ? 0 : savedMethod);
        methodBox.setToolTipText("<html>Bancada: " + NeuralPelletDetector.status(false) + "<br>Correia: " + NeuralPelletDetector.status(true)
                + "<br>A rede so propoe objetos; as elipses sao medidas nas bordas. Modelo de correia treinado com pseudo-rotulos sem revisao humana.</html>");
        sensitivity.setValue(prefs.getInt("sensitivity", 75));
        restoreVisuals();
        autoSuggest.setSelected(prefs.getBoolean("auto", true));
        autoRoiBox.setSelected(prefs.getBoolean("autoRoi", true));
        autoRoiBox.setToolTipText("Identifica recipiente redondo/retangular, trecho carregado da correia ou campo cheio e limita a análise a ele.");
        autoRoiBox.addActionListener(e -> { liveAuto.reset(); savePrefs(); });
        lockFocus.setSelected(prefs.getBoolean("lockFocus", true));
        lockExposure.setSelected(prefs.getBoolean("lockExposure", true));
        cameraBox.addItem("Procurando cameras…");

        extraToolbar = new JPanel(new java.awt.GridLayout(0, 1, 0, 8));
        extraToolbar.setPreferredSize(new Dimension(260, 280));
        segExtra.setEnabled(false);
        segExtra.setToolTipText("<html>YOLO11-seg: sem mascaras de instancia com rotulo humano; codigo e pesos Ultralytics sao AGPL-3.0 (revisao de licenca antes de distribuir).<br>Hibrida: sem regra de fusao validada.</html>");
        crackBox.setToolTipText("<html>MobileNetV3 e o padrao: acertou mais que o Random Forest em dois testes independentes (foto de 7 trincas; 57 fotos de bancada nunca vistas).<br>RF fica como reserva. Ainda sem validacao em producao.<br>Recortes dos pixels originais (nunca da super-resolucao). Escore nao calibrado. U-Net de trincas: indisponivel (sem mascaras reais).</html>");
        opacitySlider.setPreferredSize(new Dimension(110, 24));
        opacitySlider.setToolTipText("Opacidade da cor no mapa (0-90%; padrao 55%)");
        accelBox.setSelected(HardwareAcceleration.enabled());
        accelBox.setToolTipText("<html>Executa as redes neurais (StarDist e MobileNetV3) no ONNX Runtime: GPU NVIDIA com CUDA; sem CUDA, CPU.<br>"
                + "Cada rede so passa a usar o motor novo se der o mesmo resultado do OpenCV DNN numa sonda de paridade; senao continua no OpenCV.<br>"
                + "A segmentacao classica (bordas, LoG) e a super-resolucao continuam na CPU. Desligada por padrao.</html>");
        accelInfo.setToolTipText("Mostra em que motor cada rede esta rodando e os tempos medidos na sonda.");
        accelBox.addActionListener(e -> toggleAcceleration(accelBox.isSelected()));
        accelInfo.addActionListener(e -> JOptionPane.showMessageDialog(this, accelerationReport(), "Aceleracao por hardware", JOptionPane.INFORMATION_MESSAGE));
        sam2Box.setSelected(Sam2Refiner.enabled() && Sam2Refiner.available());
        sam2Box.setEnabled(Sam2Refiner.available());
        sam2Box.setToolTipText("<html>Cada pelota encontrada pelo metodo escolhido vira um prompt (caixa + centro) para o SAM 2.1 Tiny;<br>"
                + "o tamanho passa a ser a area da mascara (elipse de mesmos momentos). Mascaras ruins mantem a elipse do detector.<br>"
                + "Mesmo modelo e mesmo motor (ONNX Runtime) do Android. Sem ajuste fino em pelotas: compare antes de usar.<br>"
                + Sam2Refiner.status().replace("<", "&lt;") + "</html>");
        sam2Box.addActionListener(e -> {
            Sam2Refiner.setEnabled(sam2Box.isSelected());
            prefs.putBoolean(DesktopAnalysis.SAM2_PREF, sam2Box.isSelected());
            setStatus(sam2Box.isSelected() ? "SAM 2 ligado: vale a partir da proxima analise." : "SAM 2 desligado: tamanhos pelas elipses do detector.");
        });

        JPanel side = new StudioScrollablePanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(StudioTheme.BACKGROUND);
        side.setBorder(BorderFactory.createEmptyBorder(0, 4, 8, 8));
        scalePanel = new JPanel();
        scalePanel.setLayout(new BoxLayout(scalePanel, BoxLayout.Y_AXIS));
        scalePanel.setBorder(BorderFactory.createTitledBorder("Escala e classes do histograma"));
        scalePanel.add(row(new JLabel("px/mm (imagem original):"), pxPerMm));
        scalePanel.add(row(new JLabel("Classe (mm):"), binWidth));
        scalePanel.add(row(excludePartial));
        scalePanel.add(row(excludeReview));
        scalePanel.add(row(measureButton));
        scalePanel.add(row(applyScaleButton, clearScaleButton));
        // 0.5.3: o painel de escala abre por botão; o espaço lateral fica para histograma + Rosin–Rammler.
        scaleButton.setToolTipText("px/mm, classe, exclusões, referência, correia, recipiente, objeto e média");
        scaleButton.addActionListener(e -> showScaleDialog());
        JPanel scaleRow = new JPanel(new BorderLayout(4, 6));
        scaleRow.setOpaque(false);
        scaleRow.add(scaleLabel, BorderLayout.CENTER);
        scaleRow.add(scaleButton, BorderLayout.SOUTH);
        scaleLabel.setForeground(StudioTheme.TEXT);
        addCard(side, StudioShell.card("Escala", scaleRow));
        summary.setEditable(false);
        summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        summary.setRows(7);
        summary.setBackground(StudioTheme.BACKGROUND);
        summary.setForeground(StudioTheme.TEXT);
        summary.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane sp = new JScrollPane(summary);
        sp.setBorder(BorderFactory.createEmptyBorder());
        addCard(side, StudioShell.card("Resultado", sp));
        addCard(side, StudioShell.card("Histograma por número", histogram));
        // Fonte lógica com substituição de glifos: Segoe UI não tem "∝".
        rrBasisBox.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        rrBasisBox.setSelectedIndex(RosinRammler.Basis.fromId(prefs.get("rrBasis", "number")).ordinal());
        rrBasisBox.setToolTipText("<html>Numero: o que a imagem mede (contagem de pelotas).<br>Massa estimada: pondera cada pelota por d³ (esferas de mesma densidade) — aproxima a base das curvas de peneira, sem substituir o peneiramento.</html>");
        rrBasisBox.addActionListener(e -> { prefs.put("rrBasis", rrBasis().id); refreshCharts(session); });
        JPanel rrPanel = new JPanel(new BorderLayout(0, 6));
        rrPanel.setOpaque(false);
        JPanel basisRow = new JPanel(new BorderLayout(6, 0));
        basisRow.setOpaque(false);
        basisRow.add(new JLabel("Base:"), BorderLayout.WEST);
        basisRow.add(rrBasisBox, BorderLayout.CENTER);
        rrPanel.add(basisRow, BorderLayout.NORTH);
        rrPanel.add(rrView, BorderLayout.CENTER);
        addCard(side, StudioShell.card("Curva acumulada · Rosin–Rammler", rrPanel));
        JLabel legend = new JLabel("<html><body style='width:280px'>verde = aceita · amarelo = revisar/inconclusivo · vermelho = possível trinca · "
                + "cinza = parcial na borda · tracejado = forma sem confiança · ciano = REF.<br>Clique numa pelota para revisar/calibrar.</body></html>");
        legend.setForeground(StudioTheme.MUTED);
        addCard(side, StudioShell.card("Legenda", legend));
        // BoxLayout calcula a altura natural dos graficos; JScrollPane permite rolar.
        side.setMinimumSize(new Dimension(200, 1));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        bottom.add(status, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(220, 16));
        bottom.add(row(cancelButton, progress), BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        JPanel acquisition = StudioShell.stack(openButton, new JLabel("Câmera e resolução"), cameraBox, resolutionBox,
                cameraButton, captureButton, backLiveButton, driverButton, lockFocus, lockExposure,
                liveBox, liveTuneBox, liveSearchBox, liveScaleButton);
        JButton calibrate = new JButton("Referências e escala…");
        calibrate.addActionListener(e -> showScaleDialog());
        JPanel calibration = StudioShell.stack(StudioShell.note("Prefira a esfera branca de 11 mm quando identificada com confiança. Referências fora do plano não corrigem perspectiva monocular perfeitamente."),
                calibrate, StudioShell.note("Clique numa pelota na imagem para revisar a referência e sua exclusão das estatísticas. Sem escala confiável, os valores permanecem em pixels."));
        JPanel segmentation = StudioShell.stack(new JLabel("Cena"), sceneBox, new JLabel("Perfil de tamanho"), profileBox,
                new JLabel("Método de segmentação"), methodBox, new JLabel("Sensibilidade (%)"), sensitivity,
                sam2Box, autoSuggest, autoRoiBox, roiButton, analyzeButton, detailButton, lightButton);
        previewAiButton.setToolTipText("Carrega ONNX experimental por SHA-256 e gera apenas prévia; não altera medições nem PDF.");
        previewAiButton.addActionListener(e -> runExperimentalPreview());
        JPanel analysis = StudioShell.stack(new JLabel("Revisão e comparação"), extraToolbar,
                new JLabel("Classificador de trincas"), crackBox, accelBox, accelInfo,
                StudioShell.note("Sem indicação de trinca não comprova integridade estrutural. Escores de rede não são probabilidades calibradas."),
                new JLabel("Segmentação multitarefa experimental"), previewAiButton,
                StudioShell.note("Prévia em CPU: verde interior, vermelho borda, azul separação. Não influencia medições ou relatório."), segExtra);
        JPanel results = StudioShell.stack(new JLabel("Camada de visualização"), viewBox, numbers,
                new JLabel("Transparência da sobreposição"), opacitySlider, mapLabels, mapFixed,
                new JLabel("Faixa fixa (mm)"), mapMin, mapMax, chartButton,
                StudioShell.note("Histogramas por número e massa estimada não substituem peneiramento. Objetos incertos devem ser revisados."));
        javax.swing.JList<String> history = new javax.swing.JList<>(historyModel);
        history.setVisibleRowCount(7);
        // Mostra o nome da pasta; o caminho completo fica na dica.
        history.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                File f = new File(String.valueOf(value));
                setText(f.getName()); setToolTipText(f.getPath());
                return this;
            }
        });
        for (int i = 0; i < 15; i++) { String path = prefs.get("history." + i, ""); if (!path.isEmpty()) historyModel.addElement(path); }
        JButton openHistory = new JButton("Abrir pasta selecionada");
        openHistory.addActionListener(e -> { String path = history.getSelectedValue(); if (path != null) {
            try { java.awt.Desktop.getDesktop().open(new File(path)); } catch (Exception ex) { error("Histórico", ex); }
        } });
        JButton previewHistory = new JButton("Ver PDF da exportação");
        previewHistory.addActionListener(e -> { String path = history.getSelectedValue(); if (path != null) previewLatestPdf(new File(path)); });
        JPanel report = StudioShell.stack(StudioShell.note("Exporta imagem original, segmentação, mapa, CSV, JSON e relatório PDF. Revise calibração, contornos e advertências antes de salvar. O PDF salvo aparece ao lado."),
                saveButton, new JLabel("Histórico local de exportações"), new JScrollPane(history), previewHistory, openHistory);
        JScrollPane inspector = new JScrollPane(side);
        inspector.setBorder(BorderFactory.createEmptyBorder());
        inspector.getViewport().setBackground(StudioTheme.BACKGROUND);
        inspector.getVerticalScrollBar().setUnitIncrement(20);
        inspector.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        studio = new StudioShell(view, inspector, new JPanel[]{acquisition, calibration, segmentation, analysis, results, report}, reportPreview);
        getContentPane().add(studio, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        pack();
        StudioWindowChrome.apply(this);
        setMinimumSize(new Dimension(980, 680));
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
            prefs.put("crackChoice2", CrackClassifierChoice.values()[crackBox.getSelectedIndex()].name());
            // Mesmos contornos, so a triagem de trincas e refeita com o classificador escolhido.
            if (mode == Mode.PHOTO && session != null && session.analyzed() && !session.needsAnalysis() && session.output() != null) analyze(false, session.output().applied);
        });
        measureButton.addActionListener(e -> startMeasure());
        applyScaleButton.addActionListener(e -> applyScaleFromFields());
        clearScaleButton.addActionListener(e -> { pxPerMm.setText(""); setScale(null); });
        liveBox.addActionListener(e -> { if (!liveBox.isSelected() && mode == Mode.LIVE) { view.setOverlay(null, null); view.setBanner(null); } });
        liveTuneBox.setToolTipText("<html>Quando nada se mexe por ~1 s: busca do ajuste (metodo, perfil, sensibilidade) em 3 quadros parados,<br>"
                + "pontuando qualidade e concordancia entre quadros. Depois so mostra pelotas confirmadas em varios quadros.<br>"
                + "Qualquer movimento cancela e recomeca. A aptidao e heuristica, nao acuracia.</html>");
        liveTuneBox.addActionListener(e -> { stabilizer.enabled = liveTuneBox.isSelected(); stabilizer.restart(); });
        liveSearchBox.setSelectedIndex(prefs.getBoolean("liveGenetic", false) ? 1 : 0);
        stabilizer.genetic = liveSearchBox.getSelectedIndex() == 1;
        liveSearchBox.setToolTipText("<html>Busca rapida (padrao): triagem metodo x perfil e refino da sensibilidade, em paralelo; o LoG reaproveita o espaco de escala.<br>"
                + "Busca genetica: a da 0.5.5 (8 individuos, ate 6 geracoes, uma avaliacao por vez). Mesma aptidao nas duas.</html>");
        liveSearchBox.addActionListener(e -> { stabilizer.genetic = liveSearchBox.getSelectedIndex() == 1; prefs.putBoolean("liveGenetic", stabilizer.genetic); stabilizer.restart(); });
        liveLight = LiveFrameAnalysis.Light.fromName(prefs.get("liveLight", "OFF"));
        liveShowFiltered = prefs.getBoolean("liveShowFiltered", true);
        liveScaleButton.setToolTipText("Escala do ao vivo: borda do recipiente detectada no quadro atual, px/mm do quadro, ou a da ultima foto.");
        liveScaleButton.addActionListener(e -> liveScaleDialog());
        view.setClickListener(this::onImageClick);
        installExtensions();
        updateControls();
        setStatus("Pronto. Abra a camera ou uma imagem.");
        if (!Boolean.getBoolean("pelotavision.smoke")) probeCameras();
    }

    /** Ícone da janela e da barra de tarefas (mesmo desenho do .ico do instalador e do Android). */
    static List<java.awt.Image> appIcons() {
        List<java.awt.Image> out = new java.util.ArrayList<>();
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            try (java.io.InputStream in = MainFrame.class.getResourceAsStream("/icon/pelotavision_" + size + ".png")) {
                if (in != null) out.add(javax.imageio.ImageIO.read(in));
            } catch (java.io.IOException ignored) { }
        }
        return out;
    }

    RosinRammler.Basis rrBasis() { return RosinRammler.Basis.values()[Math.max(0, rrBasisBox.getSelectedIndex())]; }

    /** Curva RR da distribuição exibida (null = sem gráfico). */
    private void refreshCharts(PhotoSession s) {
        Granulometry.Distribution d = s == null ? null : s.distribution();
        rrView.display(d == null ? null : RosinRammler.fit(d, rrBasis()));
    }

    /** Janela (não modal) com o antigo painel "Escala e histograma"; reaproveita os mesmos controles. */
    private void showScaleDialog() {
        if (scaleDialog == null) {
            scaleDialog = new javax.swing.JDialog(this, "Escala e histograma", false);
            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            content.add(new JScrollPane(scalePanel), BorderLayout.CENTER);
            JButton close = new JButton("Fechar");
            close.addActionListener(e -> scaleDialog.setVisible(false));
            content.add(row(close), BorderLayout.SOUTH);
            scaleDialog.setContentPane(content);
            scaleDialog.pack();
            scaleDialog.setSize(Math.max(460, scaleDialog.getWidth()), Math.min(scaleDialog.getHeight(), 720));
            scaleDialog.setLocationRelativeTo(scaleButton);
        }
        scaleDialog.setVisible(true);
        scaleDialog.toFront();
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
        // 0.5.5: chave nova para o novo padrao (MobileNetV3) valer uma vez; a antiga era gravada sempre.
        crackBox.setSelectedIndex(CrackClassifierChoice.parse(prefs.get("crackChoice2", CrackClassifierChoice.DEFAULT.name())).ordinal());
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
        refreshCharts(session);
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

    private static void addCard(JPanel side, JPanel card) {
        side.add(card);
        side.add(Box.createVerticalStrut(10));
    }

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
                    stabilizer.resetAll();
                    view.setViewMode(viewBox.getSelectedIndex());
                    liveRoi = null; // outra camera/resolucao: a ROI anterior nao vale mais
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
        boolean filteredView = liveBox.isSelected() && liveLight != LiveFrameAnalysis.Light.OFF && liveShowFiltered;
        if (!filteredView) SwingUtilities.invokeLater(() -> { if (mode == Mode.LIVE) view.setImage(img, true); });
        long now = System.currentTimeMillis();
        if (now - lastThumb >= 100) {
            lastThumb = now;
            try { stabilizer.onThumb(LiveStabilizer.thumbnail(frame), now); } catch (RuntimeException ignored) { }
        }
        if (!liveBox.isSelected()) return;
        if (stabilizer.shouldTune(liveTuneBox.isSelected()) && liveBusy.compareAndSet(false, true)) {
            startLiveTuning(frame.clone());
            return;
        }
        if (now - lastLive > 300 && liveBusy.compareAndSet(false, true)) {
            lastLive = now;
            Mat copy = frame.clone();
            DesktopAnalysis.Config cfg = config();
            liveWorker.execute(() -> {
                Mat gray = null;
                try {
                    gray = Mats.gray(copy);
                    InterestRegion roi = liveRoi(gray.cols(), gray.rows());
                    if (roi == null && autoRoiBox.isSelected()) roi = liveAutoRoi(gray);
                    LiveFrameAnalysis.Light light = liveLight;
                    PelletDetector.Result raw;
                    if (light == LiveFrameAnalysis.Light.OFF) raw = DesktopAnalysis.live(gray, cfg, roi);
                    else {
                        // 0.16.0: mesmo filtro de luz da foto; propostas no quadro filtrado, elipses nos pixels originais.
                        LiveFrameAnalysis.Request req = new LiveFrameAnalysis.Request(cfg.option(), LiveFrameAnalysis.toFraction(roi, gray.cols(), gray.rows()),
                                light, null, cfg.rrBasis, liveShowFiltered);
                        try (LiveFrameAnalysis.Output out = LiveFrameAnalysis.run(gray, req)) {
                            raw = out.result;
                            if (out.filtered != null) {
                                BufferedImage f = Mats.toImage(out.filtered);
                                SwingUtilities.invokeLater(() -> { if (mode == Mode.LIVE) view.setImage(f, true); });
                            }
                        }
                    }
                    PelletDetector.Result shown = stabilizer.consolidate(raw);
                    showLive(raw, shown, cfg, roi != null);
                } catch (RuntimeException ignored) {
                } finally {
                    copy.release();
                    if (gray != null) gray.release();
                    liveBusy.set(false);
                }
            });
        }
    }

    /**
     * 0.18.0: ROI automática no ao vivo, refeita a cada ~1 s e estabilizada entre quadros (não pula).
     * Só vale enquanto o operador não desenhar uma ROI; aparece na tela como a ROI comum.
     */
    private InterestRegion liveAutoRoi(Mat gray) {
        int w = gray.cols(), h = gray.rows();
        long now = System.currentTimeMillis();
        if (now - lastAutoRoi > 1000) {
            lastAutoRoi = now;
            byte[] g = new byte[w * h];
            Mat c = gray.isContinuous() ? gray : gray.clone();
            try { c.get(0, 0, g); } finally { if (c != gray) c.release(); }
            AutoRoi.Result r = AutoRoi.detectAnySize(g, w, h);
            AutoRoi.Result s = liveAuto.update(r.usable() ? r : null, w, h);
            if (s != null && s.region != null) {
                InterestRegion reg = s.region;
                SwingUtilities.invokeLater(() -> {
                    if (mode != Mode.LIVE || liveRoi != null) return;
                    view.setViewport(new AnalysisWindow(w, h, reg));
                    setStatus("Ao vivo · ROI automática: " + s.describe());
                });
            }
        }
        AutoRoi.Result cur = liveAuto.current();
        if (cur == null || cur.region == null || cur.region.right > w + 1e-7 || cur.region.bottom > h + 1e-7) return null;
        return cur.region;
    }

    /**
     * 0.18.0: uma única vez por instalação, passa aos modelos mais assertivos — StarDist (volta ao gradiente
     * sozinho se a rede não estiver instalada), máscaras SAM 2 e MobileNetV3 de trincas — e liga a ROI automática.
     * Depois disso a escolha do operador é respeitada.
     */
    private void migrateDefaults018() {
        if (prefs.getBoolean("defaults018", false)) return;
        prefs.putInt("method", DetectionTuner.NEURAL);
        prefs.putBoolean(DesktopAnalysis.SAM2_PREF, true);
        Sam2Refiner.setEnabled(true);
        prefs.put("crackChoice2", CrackClassifierChoice.DEFAULT.name());
        prefs.putBoolean("autoRoi", true);
        prefs.putBoolean("defaults018", true);
    }

    /** ROI do ao vivo válida para este quadro (a resolução pode ter mudado). */
    private InterestRegion liveRoi(int w, int h) {
        InterestRegion roi = liveRoi;
        if (roi != null && (roi.right > w + 1e-7 || roi.bottom > h + 1e-7)) { liveRoi = null; roi = null; }
        return roi;
    }

    /** Escala válida para o quadro da câmera (referência de outra imagem/resolução: null, gráfico em px). */
    static Granulometry.Settings liveScale(Granulometry.Settings scale, int w, int h) {
        if (scale == null || scale.pixelsOnly()) return null;
        try { scale.analyzedPixelsPerMm(w, h, w, h); return scale; } catch (RuntimeException e) { return null; }
    }

    /** Atualiza marcações, mapa, gráficos e faixa com o resultado exibido (estável ou do quadro). */
    private void showLive(PelletDetector.Result raw, PelletDetector.Result shown, DesktopAnalysis.Config cfg, boolean inRoi) {
        LiveFrameAnalysis.ScaleTransfer transfer = liveScaleTransfer(cfg);
        Granulometry.Distribution dist = transfer != null ? LiveFrameAnalysis.distribution(shown, transfer) : liveDistribution(shown, cfg.scale);
        RosinRammler.Fit fit = dist == null ? null : RosinRammler.fit(dist, cfg.rrBasis);
        if (dist != null && dist.included > 0) stabilizer.recordD50(dist.d50);
        String banner = stabilizer.banner(raw, shown, dist, inRoi);
        SwingUtilities.invokeLater(() -> {
            if (mode != Mode.LIVE || !liveBox.isSelected()) return;
            view.setOverlay(shown, null);
            // 0.5.5: o mapa granulométrico também acompanha o ao vivo (antes o ao vivo forçava as marcações).
            view.setMap(dist, visuals);
            histogram.display(dist, null, visuals);
            rrView.display(fit);
            view.setBanner(banner);
        });
    }

    /** Coleta quadros parados e roda a busca genética no worker do ao vivo; aplica o melhor ajuste aos controles. */
    private void startLiveTuning(Mat first) {
        DesktopAnalysis.Config cfg = config();
        DetectionTuner.Option start = cfg.option();
        liveWorker.execute(() -> {
            List<Mat> grays = new java.util.ArrayList<>();
            try {
                grays.add(Mats.gray(first));
                for (int i = 1; i < LiveStabilizer.TUNE_FRAMES; i++) {
                    Thread.sleep(LiveStabilizer.TUNE_FRAME_GAP_MS);
                    if (!stabilizer.still()) return;
                    Mat f = camera.snapshot();
                    if (f == null) return;
                    try { grays.add(Mats.gray(f)); } finally { f.release(); }
                }
                int w = grays.get(0).cols(), h = grays.get(0).rows();
                InterestRegion roi = liveRoi(w, h);
                DetectionAssessment.Context ctx = new DetectionAssessment.Context(liveScale(cfg.scale, w, h), w, h, roi, cfg.minimumExpectedMm);
                SwingUtilities.invokeLater(() -> { if (mode == Mode.LIVE) view.setBanner(stabilizer.progress()); });
                List<Mat> owned = grays;
                grays = new java.util.ArrayList<>(); // tune() libera os quadros
                LiveTuner.Run run = stabilizer.tune(owned, start, roi, ctx,
                        () -> SwingUtilities.invokeLater(() -> { if (mode == Mode.LIVE) view.setBanner(stabilizer.progress()); }));
                if (run == null) return;
                DetectionTuner.Option best = run.best.option;
                SwingUtilities.invokeLater(() -> {
                    // O melhor ajuste vira o ajuste atual: o ao vivo passa a usá-lo e a foto capturada parte dele.
                    profileBox.setSelectedIndex(best.profile);
                    methodBox.setSelectedIndex(best.method);
                    sensitivity.setValue(best.sensitivity);
                    setStatus(String.format(Locale.ROOT, "Busca genetica concluida em %.1f s (%d ajustes, %d geracoes): %s. Ajuste aplicado aos controles.",
                            run.durationMs / 1000.0, run.evaluated.size(), run.generations, run.best.summary()));
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> setStatus("Busca ao vivo interrompida: " + e.getMessage()));
            } finally {
                first.release();
                for (Mat g : grays) g.release();
                liveBusy.set(false);
            }
        });
    }

    /** Escala do ao vivo: a calibrada no próprio ao vivo, senão a da foto aberta (transferida pela resolução). */
    private LiveFrameAnalysis.ScaleTransfer liveScaleTransfer(DesktopAnalysis.Config cfg) {
        LiveFrameAnalysis.ScaleTransfer o = liveScaleOverride;
        if (o != null) return o;
        PhotoSession s = session;
        if (cfg.scale == null || cfg.scale.pixelsOnly() || s == null) return null;
        try { return new LiveFrameAnalysis.ScaleTransfer(cfg.scale, s.originalWidth, s.originalHeight, "da ultima foto"); }
        catch (RuntimeException e) { return null; }
    }

    /** 0.16.0: escala no ao vivo (recipiente no quadro, px/mm do quadro, ou voltar à da foto). */
    private void liveScaleDialog() {
        if (!camera.isRunning()) { info("Abra a camera primeiro."); return; }
        String[] options = {"Recipiente: detectar a borda neste quadro", "Informar px/mm do quadro", "Usar a escala da foto aberta", "Sem escala (px)"};
        LiveFrameAnalysis.ScaleTransfer cur = liveScaleOverride;
        Object c = JOptionPane.showInputDialog(this, cur == null ? "Escala atual: a da foto aberta (se houver) ou pixels." : "Escala atual: " + cur.origin,
                "Escala ao vivo", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (c == null) return;
        if (c == options[3]) { liveScaleOverride = new LiveFrameAnalysis.ScaleTransfer(Granulometry.Settings.pixels(10), 1, 1, "pixels"); setStatus("Ao vivo em pixels."); return; }
        if (c == options[2]) { liveScaleOverride = null; setStatus("Ao vivo usa a escala da foto aberta (se a proporcao do quadro for a mesma)."); return; }
        Mat frame = camera.snapshot();
        if (frame == null) { info("Ainda nao ha quadro da camera."); return; }
        Mat gray = Mats.gray(frame);
        frame.release();
        try {
            double bin = parseOr(binWidth.getText(), 1);
            if (c == options[1]) {
                String v = JOptionPane.showInputDialog(this, "px/mm no quadro " + gray.cols() + "x" + gray.rows() + ":");
                if (v == null) return;
                double ppm = Double.parseDouble(v.trim().replace(',', '.'));
                liveScaleOverride = new LiveFrameAnalysis.ScaleTransfer(new Granulometry.Settings(ppm, bin, excludePartial.isSelected(), excludeReview.isSelected()),
                        gray.cols(), gray.rows(), "px/mm informado no ao vivo");
                setStatus("Escala do ao vivo: " + liveScaleOverride.describe(gray.cols(), gray.rows()));
                return;
            }
            String v = JOptionPane.showInputDialog(this, "Diametro interno do recipiente (mm):", prefs.get("containerMm", ""));
            if (v == null) return;
            double mm = Double.parseDouble(v.trim().replace(',', '.'));
            prefs.put("containerMm", v.trim());
            LiveFrameAnalysis.ScaleTransfer t = LiveFrameAnalysis.containerOnFrame(gray, mm, ContainerCalibration.Plane.RIM, bin, excludePartial.isSelected(), excludeReview.isSelected());
            if (t == null) { info("Borda nao encontrada com seguranca. Enquadre o recipiente (borda visivel em lados opostos) ou informe px/mm."); return; }
            BufferedImage img = Mats.toImage(gray);
            java.awt.Graphics2D g2 = img.createGraphics();
            g2.setColor(new java.awt.Color(82, 216, 190));
            g2.setStroke(new java.awt.BasicStroke(Math.max(2f, img.getWidth() / 400f)));
            g2.drawOval((int) Math.round(t.circle.cx - t.circle.r), (int) Math.round(t.circle.cy - t.circle.r), (int) Math.round(2 * t.circle.r), (int) Math.round(2 * t.circle.r));
            g2.dispose();
            double f = Math.min(640.0 / img.getWidth(), 420.0 / img.getHeight());
            JLabel pic = new JLabel(new javax.swing.ImageIcon(img.getScaledInstance((int) (img.getWidth() * f), (int) (img.getHeight() * f), java.awt.Image.SCALE_SMOOTH)));
            int ok = JOptionPane.showConfirmDialog(this, new Object[]{pic, String.format(Locale.ROOT, "%.3f px/mm no quadro (%s). Usar?", t.pixelsPerMm(gray.cols(), gray.rows()), t.origin)},
                    "Confirme a borda", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) { liveScaleOverride = t; setStatus("Escala do ao vivo pelo recipiente: " + t.describe(gray.cols(), gray.rows())); }
        } catch (RuntimeException e) {
            error("Escala ao vivo", e);
        } finally {
            gray.release();
        }
    }

    private static double parseOr(String text, double fallback) {
        try { double v = Double.parseDouble(text.trim().replace(',', '.')); return v > 0 && Double.isFinite(v) ? v : fallback; } catch (RuntimeException e) { return fallback; }
    }

    /**
     * Distribuição da contagem ao vivo: na escala vigente (mesma resolução da câmera) ou em pixels.
     * Referências de outra imagem/resolução recusam a escala; aí o gráfico fica em px, sem erro.
     */
    static Granulometry.Distribution liveDistribution(PelletDetector.Result r, Granulometry.Settings scale) {
        try {
            if (scale == null || scale.pixelsOnly()) return ReportTables.pixelHistogram(r);
            return Granulometry.compute(PhotoSession.items(r), scale, r.width, r.height, r.width, r.height);
        } catch (RuntimeException e) {
            try { return ReportTables.pixelHistogram(r); } catch (RuntimeException ignored) { return null; }
        }
    }

    private void capture() {
        Mat frame = camera.snapshot();
        if (frame == null) { info("Ainda nao ha quadro da camera."); return; }
        CameraSource.Info i = camera.currentInfo();
        int fw = frame.cols(), fh = frame.rows();
        PhotoSession s = new PhotoSession(null, Mats.toImage(frame), i == null ? null : i.copy(), frame);
        frame.release();
        // 0.5.4: a ROI do ao vivo vale para a foto capturada (mesmas coordenadas, ou reduzidas se a foto foi amostrada).
        InterestRegion roi = liveRoi;
        AutoRoi.Result auto = liveAuto.current();
        if (roi == null && autoRoiBox.isSelected() && auto != null && auto.region != null) {
            // 0.18.0: a foto herda a ROI automática do ao vivo, registrada como automática no JSON.
            double k = s.width / (double) fw;
            try { s.useAutoRoi(auto.scaled(k, k, s.width, s.height)); } catch (IllegalArgumentException ignored) { }
            roi = null;
        }
        if (roi != null) {
            double k = s.width / (double) fw;
            try { s.setRegion(new InterestRegion(roi.shape, roi.left * k, roi.top * k, roi.right * k, roi.bottom * k)); }
            catch (IllegalArgumentException ignored) { }
        }
        loadPhoto(s);
    }

    /** ROI sobre o quadro atual da câmera; a contagem e os gráficos ao vivo passam a usar só essa área. */
    private void editLiveRegion() {
        Mat frame = camera.snapshot();
        if (frame == null) { info("Ainda nao ha quadro da camera."); return; }
        BufferedImage img = Mats.toImage(frame);
        int w = frame.cols(), h = frame.rows();
        frame.release();
        InterestRegion current = liveRoi;
        if (current != null && (current.right > w + 1e-7 || current.bottom > h + 1e-7)) current = null;
        RegionEditorDialog d = new RegionEditorDialog(this, img, current);
        d.setVisible(true);
        if (!d.applied) return;
        liveRoi = d.chosen;
        view.setViewport(liveRoi == null ? null : new AnalysisWindow(w, h, liveRoi));
        view.setOverlay(null, null);
        setStatus(liveRoi == null ? "Ao vivo: quadro inteiro." : "Ao vivo: contagem e graficos so na ROI. A foto capturada herda esta ROI.");
        updateControls();
    }

    private void backToLive() {
        if (!camera.isRunning()) { info("Abra a camera primeiro."); return; }
        mode = Mode.LIVE;
        stabilizer.resetAll();
        InterestRegion roi = liveRoi;
        BufferedImage current = view.image();
        view.setViewport(roi == null || current == null || roi.right > current.getWidth() + 1e-7 || roi.bottom > current.getHeight() + 1e-7 ? null
                : new AnalysisWindow(current.getWidth(), current.getHeight(), roi));
        view.setReference(null);
        view.setOverlay(null, null);
        view.setMap(null, visuals);
        view.setViewMode(viewBox.getSelectedIndex()); // 0.5.5: ao vivo respeita Original / Segmentacao / Mapa
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
        view.setExperimental(null);
        if (viewBox.getSelectedIndex() >= 6) viewBox.setSelectedIndex(1);
        // Escala digitada continua valendo; referencias medidas pertencem a foto anterior.
        if (keep != null && "pixels_per_mm".equals(keep.calibrationSource)) session.setScale(keep);
        else if (keep != null) setStatus("Nova imagem: a escala medida na foto anterior foi removida. Calibre de novo ou digite px/mm.");
        mode = Mode.PHOTO;
        measureA = null; click = Click.PELLET;
        view.setBanner(null);
        view.setMeasure(null, null);
        view.setImage(s.analyzedImage, false);
        // 0.18.0: sem ROI vinda do ao vivo, propõe e aplica a ROI automática antes da primeira análise.
        if (autoRoiBox.isSelected() && s.region() == null) {
            try {
                AutoRoi.Result r = s.applyAutoRoi();
                setStatus(r.usable() ? "ROI automática: " + r.describe() + ". Ajuste em ROI se precisar."
                        : "ROI automática não aplicada (" + r.reason + "): analisando a imagem inteira.");
            } catch (RuntimeException ex) { setStatus("ROI automática indisponível: " + ex.getMessage()); }
        }
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
        c.rrBasis = rrBasis();
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
                            + (cr == null ? "" : " Trincas: " + cr.effective.label + (cr.message != null ? " — " + cr.message : "") + ".")
                            + (out.result.method.contains("+sam2_") && Sam2Refiner.lastReport() != null ? " " + Sam2Refiner.lastReport().summary() + "." : ""));
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
        if (mode == Mode.LIVE) { liveLightDialog(); return; }
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

    /** 0.16.0: filtro de luz no ao vivo, com prévia no quadro atual da câmera. */
    private void liveLightDialog() {
        Mat frame = camera.snapshot();
        if (frame == null) { info("Ainda nao ha quadro da camera."); return; }
        Mat small = new Mat();
        {
            Mat g = Mats.gray(frame);
            frame.release();
            double f = Math.min(1, 900.0 / Math.max(g.cols(), g.rows()));
            org.opencv.imgproc.Imgproc.resize(g, small, new org.opencv.core.Size(Math.max(1, g.cols() * f), Math.max(1, g.rows() * f)), 0, 0, org.opencv.imgproc.Imgproc.INTER_AREA);
            g.release();
        }
        LiveFrameAnalysis.Light[] lights = LiveFrameAnalysis.Light.values();
        String[] names = new String[lights.length];
        for (int i = 0; i < lights.length; i++) names[i] = lights[i].label();
        JComboBox<String> box = new JComboBox<>(names);
        box.setSelectedIndex(liveLight.ordinal());
        JCheckBox show = new JCheckBox("Mostrar a imagem filtrada no ao vivo", liveShowFiltered);
        JLabel preview = new JLabel();
        preview.setPreferredSize(new Dimension(640, 400));
        preview.setHorizontalAlignment(JLabel.CENTER);
        LowLightPreprocessor.Quality q = LowLightPreprocessor.assess(small);
        Runnable render = () -> {
            LiveFrameAnalysis.Light l = lights[box.getSelectedIndex()];
            LowLightPreprocessor.Mode m = l == LiveFrameAnalysis.Light.AUTO ? (q.needsCorrection() ? LowLightPreprocessor.Mode.GAMMA_CLAHE : LowLightPreprocessor.Mode.ORIGINAL)
                    : l.fixedMode() == null ? LowLightPreprocessor.Mode.ORIGINAL : l.fixedMode();
            Mat out = LowLightPreprocessor.apply(small, m);
            try {
                BufferedImage img = Mats.toImage(out);
                double f = Math.min(640.0 / img.getWidth(), 400.0 / img.getHeight());
                preview.setIcon(new javax.swing.ImageIcon(img.getScaledInstance(Math.max(1, (int) (img.getWidth() * f)), Math.max(1, (int) (img.getHeight() * f)), java.awt.Image.SCALE_SMOOTH)));
            } finally { out.release(); }
        };
        box.addActionListener(e -> render.run());
        render.run();
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(new JLabel("<html><body style='width:620px'>Filtro de luz no ao vivo. Ele so propoe as pelotas; elipses e medidas usam os pixels originais do quadro, como na foto. "
                + String.format(Locale.ROOT, "Quadro atual: nivel medio %.0f, contraste %.0f.", q.mean, q.standardDeviation) + "</body></html>"), BorderLayout.NORTH);
        p.add(preview, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(4, 4)); south.add(box, BorderLayout.NORTH); south.add(show, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        int r;
        try { r = JOptionPane.showConfirmDialog(this, p, "Luz no ao vivo", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE); }
        finally { small.release(); }
        if (r != JOptionPane.OK_OPTION) return;
        liveLight = lights[box.getSelectedIndex()];
        liveShowFiltered = show.isSelected();
        prefs.put("liveLight", liveLight.name());
        prefs.putBoolean("liveShowFiltered", liveShowFiltered);
        updateControls();
    }

    double prefsMinimum() { return prefs.getDouble("minimumMm", 5); }
    void savePrefsMinimum(double v) { prefs.putDouble("minimumMm", v); }
    void editRegionPublic() { editRegion(); }
    /** Abre o editor de ROI com uma proposta (ex.: interior do recipiente); nada muda sem Aplicar. */
    void editRegionPublic(InterestRegion proposal) { editRegion(proposal); }

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
    void studioStage(int index) { studio.select(index); }

    private void editRegion() {
        if (mode == Mode.LIVE) { editLiveRegion(); return; }
        editRegion(session == null ? null : session.region());
    }

    private void editRegion(InterestRegion initial) {
        PhotoSession s = session;
        if (s == null || mode != Mode.PHOTO) return;
        RegionEditorDialog d = new RegionEditorDialog(this, s.analyzedImage, initial);
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
        refreshCharts(s);
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
        String source = s.beltReference != null ? "Correia" : s.containerReference != null ? s.containerReference.summary() : s.selectedObject != null ? "REF " + s.selectedObject.summary()
                : s.estimated() ? "ESTIMATIVA pela media informada (hipotese)" : "reference_length".equals(s.calibrationSource) ? "referencia de comprimento" : "digitado";
        scaleLabel.setText(String.format(Locale.ROOT, "<html>%.4f px/mm · %s<br>classe %s mm</html>", s.originalPixelsPerMm, source, HistogramView.fmt(s.binWidthMm)));
    }

    // ---------------- Salvar ----------------

    private void save() {
        PhotoSession s = session;
        if (s == null || !s.canSave()) { info(s != null && s.needsAnalysis() ? "A area mudou: segmente antes de salvar." : "Analise uma foto antes de salvar."); return; }
        // 0.5.3: cada gravação vai para uma pasta própria, sugerida com data/hora do momento + nome do teste.
        File def = new File(FileSystemView.getFileSystemView().getDefaultDirectory(), "PelotaVision");
        File root = new File(prefs.get("saveRoot", def.getPath()));
        SaveDialog.Choice choice = SaveDialog.show(this, root, prefs.get("lastTestName", ""));
        if (choice == null) return;
        File folder = choice.folder;
        prefs.put("saveRoot", choice.parent.getPath());
        prefs.put("lastTestName", choice.testName == null ? "" : choice.testName);
        try {
            List<File> files = DesktopExport.save(folder, s, config(), choice.testName);
            rememberExport(folder);
            File pdf = files.stream().filter(f -> f.getName().endsWith("_relatorio.pdf")).findFirst().orElse(null);
            if (pdf != null) {
                showReport(pdf);
                setStatus("Resultados salvos em " + folder + " · relatório aberto na etapa Relatório");
            } else {
                StringBuilder t = new StringBuilder("Arquivos salvos em\n" + folder + "\n\nO PDF não foi gerado; consulte o JSON.\n\n");
                for (File f : files) t.append(f.getName()).append('\n');
                JOptionPane.showMessageDialog(this, t.toString(), "Resultado salvo", JOptionPane.WARNING_MESSAGE);
                setStatus("Resultados salvos em " + folder);
            }
        } catch (Exception ex) {
            error("Salvar", ex);
        }
    }

    void showReport(File pdf) {
        reportPreview.open(pdf);
        studio.select(StudioShell.STAGES.length - 1);
    }

    private void previewLatestPdf(File folder) {
        File[] pdfs = folder.listFiles((dir, name) -> name.endsWith("_relatorio.pdf"));
        if (pdfs == null || pdfs.length == 0) { info("Nenhum relatório PDF nesta pasta."); return; }
        java.util.Arrays.sort(pdfs, java.util.Comparator.comparing(File::getName));
        showReport(pdfs[pdfs.length - 1]);
    }

    private void runExperimentalPreview() {
        PhotoSession selected = session;
        if (selected == null || mode != Mode.PHOTO) { info("Abra uma imagem antes de testar a IA."); return; }
        String note = "Modelo de pesquisa treinado com poucas imagens e pseudo-rótulos. "
                + "Pode errar fortemente, inclusive marcar o fundo. Não foi validado.\n\n"
                + "A prévia não altera contagem, granulometria, trincas, calibração nem relatórios. Continuar?";
        if (JOptionPane.showConfirmDialog(this, note, "IA multitarefa experimental", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecionar modelo multitarefa experimental ONNX (SHA-256 conferido)");
        chooser.setFileFilter(new FileNameExtensionFilter("Modelo ONNX", "onnx"));
        File suggested = new File(System.getProperty("user.home"), "source/repos/PelotaVision-local/experiments/studio-instances-20261103/multitask-20261103T184116Z-71782b5b42/tiny_multitask_cpu_128.onnx");
        if (suggested.isFile()) chooser.setSelectedFile(suggested);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        startExperimentalPreview(chooser.getSelectedFile());
    }

    /** Mesma execução após o seletor/confirmação; usada também em teste do pacote. */
    void startExperimentalPreview(File model) {
        PhotoSession selected = session;
        if (selected == null || mode != Mode.PHOTO) throw new IllegalStateException("Imagem indisponível para prévia");
        Mat pixels = selected.analyzed.clone();
        AtomicBoolean request = new AtomicBoolean(false);
        cancel = request;
        mode = Mode.BUSY;
        progress.setIndeterminate(true);
        setStatus("IA experimental em CPU: processando blocos; sem alterar a análise…");
        updateControls();
        worker.execute(() -> {
            ExperimentalMultitask.Result result = null;
            Throwable failure = null;
            try { result = ExperimentalMultitask.infer(model, pixels, request::get); }
            catch (Exception | OutOfMemoryError ex) { failure = ex; }
            finally { pixels.release(); }
            final ExperimentalMultitask.Result completed = result;
            final Throwable problem = failure;
            SwingUtilities.invokeLater(() -> {
                if (session != selected) return;
                progress.setIndeterminate(false); progress.setValue(0); cancel = null;
                mode = Mode.PHOTO;
                if (problem == null && !request.get()) {
                    view.setExperimental(completed);
                    viewBox.setSelectedIndex(6);
                    setStatus(String.format(Locale.ROOT, "IA experimental: grade %d×%d · entropia não calibrada %.3f · sem efeito nas estatísticas.",
                            completed.processingWidth, completed.processingHeight, completed.meanEntropy));
                } else if (problem instanceof java.util.concurrent.CancellationException || request.get()) {
                    setStatus("Prévia de IA cancelada; análise anterior preservada.");
                } else {
                    error("IA experimental", problem == null ? new RuntimeException("Nenhum resultado") : problem);
                }
                updateControls();
            });
        });
    }

    void rememberExport(File folder) {
        String path = folder.getAbsolutePath();
        historyModel.removeElement(path);
        historyModel.add(0, path);
        while (historyModel.size() > 15) historyModel.remove(historyModel.size() - 1);
        for (int i = 0; i < 15; i++) prefs.put("history." + i, i < historyModel.size() ? historyModel.get(i) : "");
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
        roiButton.setEnabled(!busy && (photo || (mode == Mode.LIVE && running)));
        roiButton.setText(mode == Mode.LIVE ? (liveRoi == null ? "ROI ao vivo…" : "ROI ao vivo ✓…") : "ROI…");
        analyzeButton.setEnabled(!busy && photo);
        analyzeButton.setText(session != null && session.region() != null ? "Segmentar ROI" : "Segmentar foto");
        detailButton.setEnabled(!busy && photo);
        detailButton.setText(session != null && session.output() != null && session.output().detailed != null ? "Detalhada ✓…" : "Detalhada…");
        lightButton.setEnabled(!busy && (photo || (mode == Mode.LIVE && running)));
        previewAiButton.setEnabled(!busy && photo);
        if (mode == Mode.LIVE) lightButton.setText(liveLight == LiveFrameAnalysis.Light.OFF ? "Luz ao vivo…" : "Luz ao vivo: " + liveLight.label() + "…");
        else lightButton.setText(lowLightMode == null ? "Luz / imagem escura…" : "Luz: " + LowLightInfo.label(lowLightMode) + "…");
        liveScaleButton.setEnabled(!busy && running && mode == Mode.LIVE);
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
        prefs.putBoolean("autoRoi", autoRoiBox.isSelected());
        prefs.putBoolean("lockFocus", lockFocus.isSelected());
        prefs.putBoolean("lockExposure", lockExposure.isSelected());
        int cam = selectedCamera();
        if (cam >= 0) prefs.putInt("camera", cam);
    }

    void setStatus(String s) { status.setText(s); }

    /** Recarrega as redes no motor escolhido, na fila de analise (nunca no meio de uma analise). */
    private void toggleAcceleration(boolean on) {
        prefs.putBoolean(DesktopAnalysis.ACCEL_PREF, on);
        accelBox.setEnabled(false);
        setStatus(on ? "Ligando a aceleracao: carregando as redes no ONNX Runtime e conferindo a paridade…" : "Desligando a aceleracao: redes voltam ao OpenCV DNN…");
        worker.submit(() -> {
            String st;
            try { st = DesktopAnalysis.setAcceleration(on); } catch (Throwable t) { st = "Falha ao trocar o motor: " + t; }
            final String report = st;
            SwingUtilities.invokeLater(() -> {
                accelBox.setEnabled(true);
                setStatus(report.replace('\n', ' ').replace("Aceleração por hardware: ", "Aceleracao: "));
                if (on && !report.contains("ONNX Runtime ·"))
                    JOptionPane.showMessageDialog(this, report, "Aceleracao por hardware", JOptionPane.WARNING_MESSAGE);
            });
        });
    }

    private String accelerationReport() {
        StringBuilder s = new StringBuilder(HardwareAcceleration.status());
        s.append("\n").append(Sam2Refiner.status());
        Sam2Refiner.Report sr = Sam2Refiner.lastReport();
        if (sr != null) s.append("\nUltima analise: ").append(sr.summary());
        s.append("\n\nCUDA requer GPU NVIDIA, driver atualizado e CUDA + cuDNN instalados no Windows (nao vao no pacote).");
        s.append("\nSem CUDA, o ONNX Runtime roda na CPU. Sem paridade com o OpenCV DNN, a rede continua no OpenCV.");
        return s.toString();
    }
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
