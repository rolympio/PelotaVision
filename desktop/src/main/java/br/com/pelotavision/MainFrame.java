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

    private static final String[] PROFILES = {"Pequenas", "Medias", "Grandes", "Auto"};
    private static final int[][] RESOLUTIONS = {{640, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

    private final Preferences prefs = Preferences.userNodeForPackage(MainFrame.class);
    private final ImageView view = new ImageView();
    private final HistogramView histogram = new HistogramView();
    private final JTextArea summary = new JTextArea(9, 30);
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
    private final JComboBox<String> methodBox = new JComboBox<>(new String[]{"Bordas/gradientes", "LoG/segmentacao"});
    private final JSpinner sensitivity = new JSpinner(new SpinnerNumberModel(75, 0, 100, 5));
    private final JCheckBox autoSuggest = new JCheckBox("Sugerir ajuste (busca)", true);
    private final JSpinner crackSensitivity = new JSpinner(new SpinnerNumberModel(50, 0, 100, 5));
    private final JCheckBox numbers = new JCheckBox("Numeros");
    private final JButton analyzeButton = new JButton("Analisar foto");
    private final JButton cancelButton = new JButton("Cancelar");
    private final JButton saveButton = new JButton("Salvar resultados…");

    private final JTextField pxPerMm = new JTextField(8);
    private final JTextField binWidth = new JTextField("1", 5);
    private final JCheckBox excludePartial = new JCheckBox("Excluir parciais na borda", true);
    private final JCheckBox excludeReview = new JCheckBox("Excluir marcadas para revisao", false);
    private final JButton measureButton = new JButton("Medir referencia na foto…");
    private final JButton applyScaleButton = new JButton("Aplicar escala");
    private final JButton clearScaleButton = new JButton("Sem escala (px)");
    private final JLabel scaleLabel = new JLabel("Sem escala fisica · valores em px");

    private final CameraSource camera = new CameraSource();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PelotaVision-analise"); t.setDaemon(true); return t; });
    private final ExecutorService liveWorker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PelotaVision-ao-vivo"); t.setDaemon(true); return t; });
    private final AtomicBoolean liveBusy = new AtomicBoolean();
    private volatile long lastLive;
    private volatile Mode mode = Mode.IDLE;
    private volatile AtomicBoolean cancel;

    // Foto atual
    private File photoFile;
    private BufferedImage photoFrame;
    private CameraSource.Info photoCamera;
    private Mat analyzed;          // BGR reduzido para analise
    private BufferedImage analyzedImage;
    private int originalWidth, originalHeight, sample = 1;
    private DesktopAnalysis.Output output;
    private Granulometry.Settings scale;
    private Point2D measureA;

    MainFrame() {
        super(DesktopApp.NAME + " " + DesktopApp.VERSION + " · nucleo " + DesktopApp.CORE_VERSION);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { shutdown(); } });
        for (int[] r : RESOLUTIONS) resolutionBox.addItem(r[0] + " x " + r[1]);
        resolutionBox.setSelectedIndex(Math.min(RESOLUTIONS.length - 1, prefs.getInt("resolution", 2)));
        profileBox.setSelectedIndex(prefs.getInt("profile", 3));
        methodBox.setSelectedIndex(prefs.getBoolean("legacy", false) ? 1 : 0);
        sensitivity.setValue(prefs.getInt("sensitivity", 75));
        autoSuggest.setSelected(prefs.getBoolean("auto", true));
        lockFocus.setSelected(prefs.getBoolean("lockFocus", true));
        lockExposure.setSelected(prefs.getBoolean("lockExposure", true));
        cameraBox.addItem("Procurando cameras…");

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(row(new JLabel("Camera:"), cameraBox, resolutionBox, lockFocus, lockExposure, cameraButton, driverButton, liveBox,
                Box.createHorizontalStrut(12), captureButton, openButton, backLiveButton));
        top.add(row(new JLabel("Perfil:"), profileBox, new JLabel("Metodo:"), methodBox, new JLabel("Sensibilidade %:"), sensitivity, autoSuggest,
                new JLabel("Trincas sens.:"), crackSensitivity, numbers, Box.createHorizontalStrut(12), analyzeButton, cancelButton, saveButton));

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel scalePanel = new JPanel();
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
                + "cinza = parcial na borda · tracejado = ajuste de forma sem confianca.<br>Roda do mouse = zoom · arrastar = mover · duplo clique = ajustar.</html>");
        legend.setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        side.add(legend);
        side.setPreferredSize(new Dimension(400, 600));

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
        analyzeButton.addActionListener(e -> analyze());
        cancelButton.addActionListener(e -> { AtomicBoolean c = cancel; if (c != null) c.set(true); });
        saveButton.addActionListener(e -> save());
        numbers.addActionListener(e -> view.setNumbers(numbers.isSelected()));
        measureButton.addActionListener(e -> startMeasure());
        applyScaleButton.addActionListener(e -> applyScaleFromFields());
        clearScaleButton.addActionListener(e -> { scale = null; pxPerMm.setText(""); refreshScale(); });
        liveBox.addActionListener(e -> { if (!liveBox.isSelected() && mode == Mode.LIVE) view.setOverlay(null, null); });
        updateControls();
        setStatus("Pronto. Abra a camera ou uma imagem.");
        probeCameras();
    }

    private static JPanel row(Component... cs) {
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
                        view.setBanner("Ao vivo: " + r.circles.size() + " propostas (contagem rapida; analise completa na foto)");
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
        BufferedImage original = Mats.toImage(frame);
        loadPhoto(null, original, i, frame);
        frame.release();
        if (autoAnalyzeOnLoad()) analyze();
    }

    private void backToLive() {
        if (!camera.isRunning()) { info("Abra a camera primeiro."); return; }
        mode = Mode.LIVE;
        view.setOverlay(null, null);
        view.setBanner(null);
        view.setMeasure(null, null);
        view.setClickListener(null);
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
            loadPhoto(f, null, null, bgr);
            bgr.release();
            if (autoAnalyzeOnLoad()) analyze();
        } catch (Exception ex) {
            error("Abrir imagem", ex);
        }
    }

    private boolean autoAnalyzeOnLoad() { return true; }

    private void loadPhoto(File file, BufferedImage frame, CameraSource.Info cam, Mat bgr) {
        if (analyzed != null) analyzed.release();
        photoFile = file; photoFrame = frame; photoCamera = cam;
        originalWidth = bgr.cols(); originalHeight = bgr.rows();
        sample = Mats.sampleFor(originalWidth, originalHeight);
        analyzed = Mats.downsample(bgr, sample);
        analyzedImage = Mats.toImage(analyzed);
        output = null;
        measureA = null;
        // Escala por referencia pertence a imagem onde foi medida; escala digitada continua valendo.
        if (scale != null && "reference_length".equals(scale.calibrationSource)) {
            scale = null;
            setStatus("Nova imagem: a escala medida na foto anterior foi removida. Meca de novo ou digite px/mm.");
        }
        mode = Mode.PHOTO;
        view.setBanner(null);
        view.setMeasure(null, null);
        view.setClickListener(null);
        view.setOverlay(null, null);
        view.setImage(analyzedImage, false);
        summary.setText(String.format(Locale.ROOT, "Imagem %d x %d px%s.", originalWidth, originalHeight,
                sample > 1 ? " · analisada com amostragem 1/" + sample + " (" + analyzed.cols() + " x " + analyzed.rows() + ")" : ""));
        histogram.display(null);
        refreshScale();
        updateControls();
    }

    private DesktopAnalysis.Config config() {
        DesktopAnalysis.Config c = new DesktopAnalysis.Config();
        c.profile = profileBox.getSelectedIndex();
        c.legacy = methodBox.getSelectedIndex() == 1;
        c.sensitivity = (Integer) sensitivity.getValue();
        c.autoSuggest = autoSuggest.isSelected();
        c.crackSensitivity = (Integer) crackSensitivity.getValue();
        c.scale = scale;
        return c;
    }

    private void analyze() {
        if (analyzed == null) { info("Capture uma foto ou abra uma imagem."); return; }
        savePrefs();
        DesktopAnalysis.Config cfg = config();
        Mat input = analyzed.clone();
        int ow = originalWidth, oh = originalHeight, s = sample;
        AtomicBoolean c = new AtomicBoolean(false);
        cancel = c;
        mode = Mode.BUSY;
        updateControls();
        progress.setIndeterminate(!cfg.autoSuggest);
        progress.setValue(0);
        setStatus(cfg.autoSuggest ? "Buscando o melhor ajuste (ate 30 s)…" : "Analisando…");
        worker.execute(() -> {
            try {
                DesktopAnalysis.Output out = DesktopAnalysis.analyze(input, ow, oh, s, cfg, c::get, (done, total, label) ->
                        SwingUtilities.invokeLater(() -> { progress.setIndeterminate(false); progress.setMaximum(Math.max(1, total)); progress.setValue(done); setStatus("Busca " + done + "/" + total + ": " + label); }));
                SwingUtilities.invokeLater(() -> showOutput(out));
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> { mode = Mode.PHOTO; error("Analise", ex); updateControls(); });
            } finally {
                input.release();
                SwingUtilities.invokeLater(() -> { progress.setIndeterminate(false); progress.setValue(0); cancel = null; });
            }
        });
    }

    private void showOutput(DesktopAnalysis.Output out) {
        output = out;
        mode = Mode.PHOTO;
        view.setOverlay(out.result, out.cracks);
        view.setNumbers(numbers.isSelected());
        StringBuilder s = new StringBuilder();
        for (String line : DesktopExport.summaryLines(out)) s.append(line).append('\n');
        if (out.suggestions != null) {
            s.append(String.format(Locale.ROOT, "Busca: %d de %d opcoes em %.1f s%s. Pontos nao sao probabilidade de acerto.\n",
                    out.suggestions.trials.size(), out.suggestions.planned, out.suggestions.durationMs / 1000.0,
                    out.suggestions.cancelled ? " (cancelada)" : out.suggestions.timeLimited ? " (limite de tempo)" : ""));
            if (out.suggestions.weak()) s.append("Atencao: sugestao fraca para esta imagem; confira as marcacoes.\n");
        }
        if (sample > 1) s.append("Analise na imagem reduzida 1/").append(sample).append("; escala convertida automaticamente.\n");
        s.append(String.format(Locale.ROOT, "Tempo total: %.1f s.", out.durationMs / 1000.0));
        summary.setText(s.toString());
        summary.setCaretPosition(0);
        histogram.display(out.distribution);
        setStatus("Analise concluida: " + out.result.circles.size() + " propostas.");
        updateControls();
    }

    // ---------------- Escala ----------------

    private double parse(JTextField f) {
        String t = f.getText().trim().replace(',', '.');
        if (t.isEmpty()) return Double.NaN;
        return Double.parseDouble(t);
    }

    private void applyScaleFromFields() {
        try {
            double bin = parse(binWidth);
            if (!Double.isFinite(bin) || bin <= 0) throw new IllegalArgumentException("Classe deve ser positiva.");
            double ppm = parse(pxPerMm);
            if (Double.isFinite(ppm)) {
                if (scale != null && "reference_length".equals(scale.calibrationSource) && Math.abs(ppm - scale.originalPixelsPerMm) < 1e-9)
                    scale = Granulometry.Settings.reference(scale.referencePixels, scale.referenceMm, bin, excludePartial.isSelected(), excludeReview.isSelected());
                else
                    scale = new Granulometry.Settings(ppm, bin, excludePartial.isSelected(), excludeReview.isSelected());
            } else if (scale != null) {
                scale = scale.withHistogram(bin, excludePartial.isSelected(), excludeReview.isSelected());
            }
            refreshScale();
        } catch (RuntimeException ex) {
            error("Escala", ex);
        }
    }

    private void startMeasure() {
        if (mode != Mode.PHOTO || analyzedImage == null) { info("Capture uma foto ou abra uma imagem primeiro."); return; }
        measureA = null;
        view.setMeasure(null, null);
        setStatus("Clique nas duas extremidades de uma referencia de tamanho conhecido (no mesmo plano das pelotas). Use zoom para precisao.");
        view.setClickListener(p -> {
            if (measureA == null) { measureA = p; view.setMeasure(p, null); return; }
            Point2D a = measureA, b = p;
            view.setMeasure(a, b);
            view.setClickListener(null);
            measureA = null;
            double analyzedPx = a.distance(b);
            double originalPx = analyzedPx * sample;
            String mm = JOptionPane.showInputDialog(this, String.format(Locale.ROOT,
                    "Distancia marcada: %.1f px na imagem original.\nComprimento real da referencia (mm):", originalPx), "Referencia", JOptionPane.QUESTION_MESSAGE);
            if (mm == null || mm.trim().isEmpty()) { view.setMeasure(null, null); return; }
            try {
                double real = Double.parseDouble(mm.trim().replace(',', '.'));
                double bin = parse(binWidth);
                scale = Granulometry.Settings.reference(originalPx, real, Double.isFinite(bin) ? bin : 1, excludePartial.isSelected(), excludeReview.isSelected());
                pxPerMm.setText(String.format(Locale.ROOT, "%.4f", scale.originalPixelsPerMm));
                refreshScale();
            } catch (RuntimeException ex) {
                error("Referencia", ex);
            }
        });
    }

    private void refreshScale() {
        if (scale == null) scaleLabel.setText("Sem escala fisica · valores em px");
        else scaleLabel.setText(String.format(Locale.ROOT, "<html>%.4f px/mm (%s)<br>classe %s mm</html>", scale.originalPixelsPerMm,
                "reference_length".equals(scale.calibrationSource) ? "referencia medida nesta foto" : "digitado", HistogramView.fmt(scale.binWidthMm)));
        if (output != null) {
            try {
                showOutput(output.withScale(scale));
            } catch (RuntimeException ex) {
                error("Escala", ex);
            }
        }
    }

    // ---------------- Salvar ----------------

    private void save() {
        if (output == null) { info("Analise uma foto antes de salvar."); return; }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Pasta para os resultados");
        File def = new File(FileSystemView.getFileSystemView().getDefaultDirectory(), "PelotaVision");
        chooser.setCurrentDirectory(new File(prefs.get("saveDir", def.getPath())).getParentFile());
        chooser.setSelectedFile(new File(prefs.get("saveDir", def.getPath())));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File folder = chooser.getSelectedFile();
        prefs.put("saveDir", folder.getPath());
        try {
            List<File> files = DesktopExport.save(folder, new DesktopExport.Source(photoFile, photoFrame, photoCamera), analyzedImage, output, config());
            StringBuilder s = new StringBuilder("Arquivos salvos em\n" + folder + "\n\n");
            for (File f : files) s.append(f.getName()).append('\n');
            JOptionPane.showMessageDialog(this, s.toString(), "Salvar resultados", JOptionPane.INFORMATION_MESSAGE);
            setStatus("Resultados salvos em " + folder);
        } catch (Exception ex) {
            error("Salvar", ex);
        }
    }

    // ---------------- Utilidades ----------------

    private void updateControls() {
        boolean busy = mode == Mode.BUSY;
        boolean running = camera.isRunning();
        cameraButton.setText(running ? "Fechar camera" : "Abrir camera");
        cameraButton.setEnabled(!busy && (running || selectedCamera() >= 0));
        cameraBox.setEnabled(!busy && !running);
        resolutionBox.setEnabled(!busy && !running);
        lockFocus.setEnabled(!running); lockExposure.setEnabled(!running);
        driverButton.setEnabled(running);
        captureButton.setEnabled(!busy && running);
        backLiveButton.setEnabled(!busy && running && mode != Mode.LIVE);
        openButton.setEnabled(!busy);
        analyzeButton.setEnabled(!busy && analyzed != null && mode == Mode.PHOTO);
        cancelButton.setEnabled(busy);
        saveButton.setEnabled(!busy && output != null && mode == Mode.PHOTO);
        measureButton.setEnabled(!busy && mode == Mode.PHOTO);
    }

    private void savePrefs() {
        prefs.putInt("resolution", resolutionBox.getSelectedIndex());
        prefs.putInt("profile", profileBox.getSelectedIndex());
        prefs.putBoolean("legacy", methodBox.getSelectedIndex() == 1);
        prefs.putInt("sensitivity", (Integer) sensitivity.getValue());
        prefs.putBoolean("auto", autoSuggest.isSelected());
        prefs.putBoolean("lockFocus", lockFocus.isSelected());
        prefs.putBoolean("lockExposure", lockExposure.isSelected());
        int cam = selectedCamera();
        if (cam >= 0) prefs.putInt("camera", cam);
    }

    private void setStatus(String s) { status.setText(s); }
    private void info(String s) { JOptionPane.showMessageDialog(this, s, DesktopApp.NAME, JOptionPane.INFORMATION_MESSAGE); }
    private void error(String title, Throwable e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        setStatus(title + ": " + msg);
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE);
    }

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
