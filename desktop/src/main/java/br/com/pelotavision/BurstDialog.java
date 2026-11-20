package br.com.pelotavision;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * Diálogo "Rajada" do Windows (0.19.0). Captura N quadros da câmera aberta (ou recebe N arquivos),
 * mostra o laudo da Trilha 0 e oferece abrir a empilhada (Trilha 1) para análise. A SR 2× (Trilha 2)
 * vem desmarcada, só roda se o laudo disser {@code SR_2X_PLAUSIVEL} e gera arquivo derivado que não
 * entra na medição.
 */
final class BurstDialog extends JDialog {
    /** O que o MainFrame fornece: câmera e estado de movimento do ao vivo. */
    interface Host {
        boolean cameraRunning();
        Mat snapshot();
        CameraSource.Info cameraInfo();
        /** TRUE parada, FALSE em movimento, null desconhecido. */
        Boolean liveStill();
        /** ROI da foto/ao vivo em pixels do quadro, ou null. */
        Rect roi(int width, int height);
    }

    private final Host host;
    private final Consumer<BurstRecord> open;
    private final Preferences prefs;
    private final JComboBox<Integer> count = new JComboBox<>(new Integer[]{4, 8, 16, 24});
    private final JSpinner interval = new JSpinner(new SpinnerNumberModel(200, 40, 2000, 20));
    private final JCheckBox sr = new JCheckBox("Tentar SR 2× se o laudo permitir (arquivo derivado, não é medida)");
    private final JTextArea report = new JTextArea(16, 70);
    private final JButton fromCamera = new JButton("Capturar da câmera e analisar");
    private final JButton fromFiles = new JButton("Escolher fotos e analisar…");
    private final JButton openStack = new JButton("Abrir empilhada para análise");
    private final JButton saveSr = new JButton("Salvar SR 2×…");
    private final JButton close = new JButton("Fechar");
    private BurstRecord last;
    private boolean handedOver;

    BurstDialog(JFrame owner, Host host, Preferences prefs, Consumer<BurstRecord> open) {
        super(owner, "Rajada · empilhar várias fotos da mesma cena", true);
        this.host = host; this.open = open; this.prefs = prefs;
        count.setSelectedItem(prefs.getInt("burstCount", BurstStack.DEFAULT_FRAMES));
        interval.setValue(prefs.getInt("burstIntervalMs", 200));
        report.setEditable(false);
        report.setLineWrap(true); report.setWrapStyleWord(true);
        report.setText("Rajada: N fotos da mesma cena parada. O laudo mede tremor, cobertura de fases, erro de registro, "
                + "deriva de brilho e ruído antes de qualquer fusão.\n\n"
                + "· Empilhar (mediana robusta) reduz ruído ≈ √N sem mudar a grade nem a escala px/mm.\n"
                + "· SR 2× só é liberada com N ≥ 8, tremor p90 entre 0,15 e 2 px, cobertura ≥ 60%, erro de registro ≤ 0,15 px, "
                + "deriva ≤ 1,5 nível, ruído ≤ 10 níveis e cena parada. Câmera em tripé sem tremor não dá SR: dá mediana.\n"
                + "· Trave foco e exposição antes (caixas do painel de câmera); rajada com exposição variando é rejeitada.");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(new JLabel("Quadros (N)")); top.add(count);
        top.add(new JLabel("Intervalo (ms)")); top.add(interval);
        top.add(sr);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        buttons.add(fromCamera); buttons.add(fromFiles); buttons.add(saveSr); buttons.add(openStack); buttons.add(close);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(top, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(report);
        sp.setPreferredSize(new Dimension(760, 330));
        root.add(sp, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(owner);

        fromCamera.setEnabled(host.cameraRunning());
        fromCamera.setToolTipText(host.cameraRunning() ? "Usa a câmera aberta; mantenha a cena parada." : "Abra a câmera primeiro.");
        openStack.setEnabled(false); saveSr.setEnabled(false);
        fromCamera.addActionListener(e -> run(true, null));
        fromFiles.addActionListener(e -> chooseFiles());
        openStack.addActionListener(e -> { if (last != null && last.result.fused != null) { handedOver = true; open.accept(last); dispose(); } });
        saveSr.addActionListener(e -> saveSr());
        close.addActionListener(e -> dispose());
    }

    @Override public void dispose() {
        if (last != null && !handedOver) last.release();
        last = null;
        super.dispose();
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser(prefs.get("burstDir", prefs.get("openDir", null)));
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Imagens (jpg, png, webp, bmp, tif)", "jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] files = chooser.getSelectedFiles();
        if (files.length < 2) { report.setText("Escolha pelo menos 2 fotos da mesma cena."); return; }
        prefs.put("burstDir", files[0].getParent());
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        if (list.size() > BurstStack.MAX_FRAMES) list = new ArrayList<>(list.subList(0, BurstStack.MAX_FRAMES));
        run(false, list);
    }

    private void setBusy(boolean busy) {
        fromCamera.setEnabled(!busy && host.cameraRunning()); fromFiles.setEnabled(!busy);
        count.setEnabled(!busy); interval.setEnabled(!busy); sr.setEnabled(!busy);
        if (busy) { openStack.setEnabled(false); saveSr.setEnabled(false); }
    }

    private void run(boolean camera, List<File> files) {
        int n = (Integer) count.getSelectedItem();
        int gap = (Integer) interval.getValue();
        prefs.putInt("burstCount", n); prefs.putInt("burstIntervalMs", gap);
        if (last != null) { last.release(); last = null; }
        boolean wantSr = sr.isSelected();
        setBusy(true);
        report.setText(camera ? "Capturando " + n + " quadros… mantenha a cena parada." : "Lendo " + files.size() + " fotos…");
        Supplier<Boolean> still = host::liveStill;
        new SwingWorker<BurstRecord, String>() {
            @Override protected BurstRecord doInBackground() throws Exception {
                List<Mat> mats = new ArrayList<>();
                List<BufferedImage> frames = new ArrayList<>();
                CameraSource.Info info = null;
                Boolean liveStill = null;
                try {
                    if (camera) {
                        info = host.cameraInfo();
                        liveStill = still.get();
                        for (int i = 0; i < n; i++) {
                            if (i > 0) Thread.sleep(gap);
                            Mat m = host.snapshot();
                            if (m == null) throw new IllegalStateException("A câmera parou de entregar quadros.");
                            mats.add(m);
                            frames.add(Mats.toImage(m));
                            publish("Quadro " + (i + 1) + " de " + n);
                        }
                        Boolean after = still.get();
                        if (liveStill != null && after != null) liveStill = liveStill && after;
                    } else {
                        for (File f : files) mats.add(Mats.readUpright(f));
                    }
                    publish("Registrando e empilhando…");
                    BurstStack.Options o = new BurstStack.Options();
                    o.liveStill = liveStill;
                    o.sr = wantSr;
                    o.roi = host.roi(mats.get(0).cols(), mats.get(0).rows());
                    BurstStack.Result r = BurstStack.run(mats, o);
                    return new BurstRecord(r, files, camera ? frames : null, info == null ? null : info.copy());
                } finally { for (Mat m : mats) m.release(); }
            }
            @Override protected void process(List<String> chunks) { report.setText(chunks.get(chunks.size() - 1)); }
            @Override protected void done() {
                setBusy(false);
                try {
                    last = get();
                    StringBuilder t = new StringBuilder(last.result.describe());
                    if (last.camera != null) t.append("\n\nCâmera: ").append(last.camera.summary());
                    if (wantSr && !last.result.diagnostics.srAllowed()) t.append("\n\nSR 2× pedida, mas não liberada pelo laudo: fica a mediana (resultado correto, não defeito).");
                    report.setText(t.toString());
                    report.setCaretPosition(0);
                    openStack.setEnabled(last.result.fused != null);
                    saveSr.setEnabled(last.result.sr != null);
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    report.setText("Rajada não concluída: " + c.getMessage());
                }
            }
        }.execute();
    }

    private void saveSr() {
        if (last == null || last.result.sr == null) return;
        JFileChooser chooser = new JFileChooser(prefs.get("burstDir", null));
        chooser.setSelectedFile(new File("rajada_sr2x.png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) f = new File(f.getParentFile(), f.getName() + ".png");
        try {
            BurstCli.write(f, last.result.sr);
            report.append("\n\nSR 2× salva em " + f.getPath() + " (recorte " + last.result.srInfo.rect.width + "×" + last.result.srInfo.rect.height
                    + " px da foto, ampliado 2×; px/mm = 2 × px/mm da foto). Não use para medir.");
        } catch (Exception ex) { report.append("\n\nFalha ao salvar: " + ex.getMessage()); }
    }
}
