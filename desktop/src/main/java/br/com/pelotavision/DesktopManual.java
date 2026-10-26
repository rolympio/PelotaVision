package br.com.pelotavision;

import org.opencv.core.Mat;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Roteiro de capturas do manual (0.5.2): abre a janela real do PelotaVision Windows, percorre o fluxo do usuário
 * com uma foto real e grava cada tela e diálogo em PNG, além das páginas do PDF salvo.
 * Uso (CI): PelotaVision.exe --capturas foto.jpg pasta. Cada etapa é independente; falhas vão para capturas.txt.
 */
final class DesktopManual {
    private DesktopManual() {}

    private static final List<String> log = new ArrayList<>();
    private static File out;
    private static MainFrame frame;

    interface Step { void run() throws Exception; }

    static int run(File image, File outDir) {
        out = outDir;
        out.mkdirs();
        try {
            DesktopApp.loadOpenCv();
            onEdt(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
                frame = new MainFrame();
                frame.setSize(1500, 960);
                frame.setLocation(0, 0);
                frame.setVisible(true);
            });
            pause(2500);
            step("01_janela_inicial", () -> window("01_janela_inicial"));
            step("02_foto_analisada", () -> {
                Mat bgr = Mats.readUpright(image);
                PhotoSession s;
                try { s = new PhotoSession(image, null, null, bgr); } finally { bgr.release(); }
                onEdt(() -> frame.manualLoad(s));
                waitIdle(150);
                window("02_foto_analisada");
            });
            step("03_comparar_ajustes", () -> modal("03_comparar_ajustes", () -> Features.compare(frame), null, false));
            step("04_mapa_granulometrico", () -> { onEdt(() -> frame.manual("mapa")); pause(800); window("04_mapa_granulometrico"); });
            step("05_somente_original", () -> { onEdt(() -> frame.manual("original")); pause(800); window("05_somente_original"); onEdt(() -> frame.manual("segmentacao")); });
            step("06_grafico_opcoes", () -> modal("06_grafico_opcoes", () -> frame.manual("grafico"), null, false));

            // Pelota de referência para revisão de trinca e calibração.
            PelletDetector.Detection[] pick = new PelletDetector.Detection[1];
            int[] pickIndex = {0};
            onEdt(() -> {
                PelletDetector.Result r = frame.session.active();
                for (int i = 0; i < r.circles.size(); i++) {
                    PelletDetector.Detection d = r.circles.get(i);
                    if (d.ellipseFitted && !d.partial && !d.needsReview) { pick[0] = d; pickIndex[0] = i; break; }
                }
                if (pick[0] == null && !r.circles.isEmpty()) pick[0] = r.circles.get(0);
            });
            step("07_toque_na_pelota", () -> modal("07_toque_na_pelota", () -> Features.pelletClicked(frame, new Point2D.Double(pick[0].x, pick[0].y)), null, false));
            step("08_calibrar_pela_pelota", () -> modal("08_calibrar_pela_pelota", () -> Features.objectCalibration(frame, pick[0], frame.session.fullIndex(pickIndex[0]) + 1, pickIndex[0] + 1), null, false));
            step("09_calibrar_pela_correia", () -> modal("09_calibrar_pela_correia", () -> Features.belt(frame), null, false));
            step("10_media_conhecida", () -> modal("10_media_conhecida", () -> Features.estimatedMean(frame), null, false));
            step("11_trincas_sensibilidade", () -> modal("11_trincas_sensibilidade", () -> Features.crackSettings(frame), null, false));
            step("12_forma_projetada", () -> modal("12_forma_projetada", () -> Features.shape(frame), null, false));

            // Área de interesse: editor, recorte aguardando análise, ROI segmentada.
            step("13_editor_de_area", () -> modal("13_editor_de_area", () -> frame.manual("roi"), null, false));
            step("14_area_aguardando_segmentacao", () -> {
                onEdt(() -> {
                    PhotoSession s = frame.session;
                    s.setRegion(new InterestRegion(InterestRegion.Shape.RECTANGLE, s.width * .2, s.height * .2, s.width * .8, s.height * .8));
                    frame.refreshPhoto();
                    frame.updateControls();
                });
                pause(800);
                window("14_area_aguardando_segmentacao");
            });
            step("15_area_segmentada", () -> {
                onEdt(() -> frame.analyze(false, frame.session.output().applied));
                waitIdle(120);
                window("15_area_segmentada");
            });

            // Segmentação detalhada: opções, resultado e resumo.
            step("16_detalhada_opcoes", () -> modal("16_detalhada_opcoes", () -> frame.manual("detalhada"), null, true));
            step("17_detalhada_resultado", () -> { waitIdle(300); window("17_detalhada_resultado"); });
            step("18_detalhada_resumo", () -> modal("18_detalhada_resumo", () -> frame.manual("detalhada"), null, false));

            // Imagem escura: prévia com Redução de ruído + CLAHE, aplicar, tela filtrada.
            step("19_luz_previa", () -> modal("19_luz_previa", () -> frame.manual("luz"), w -> {
                JComboBox<?> box = find(w, JComboBox.class);
                if (box != null) box.setSelectedIndex(3);
            }, true));
            step("20_tela_filtrada", () -> { waitIdle(120); window("20_tela_filtrada"); });

            // Salvar na pasta (sem o seletor de arquivos) e desenhar as páginas do PDF.
            step("21_relatorio_pdf", () -> {
                File saida = new File(out, "saida");
                AtomicReference<List<File>> files = new AtomicReference<>();
                AtomicReference<Exception> err = new AtomicReference<>();
                onEdt(() -> { try { files.set(DesktopExport.save(saida, frame.session, frame.config())); } catch (Exception e) { err.set(e); } });
                if (err.get() != null) throw err.get();
                StringBuilder names = new StringBuilder();
                for (File f : files.get()) {
                    names.append(f.getName()).append('\n');
                    String n = f.getName();
                    if (n.endsWith("_resultado.png")) Files.copy(f.toPath(), new File(out, "22_arquivo_resultado.png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (n.endsWith("_mapa.png")) Files.copy(f.toPath(), new File(out, "23_arquivo_mapa.png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (n.endsWith("_filtrada.png")) Files.copy(f.toPath(), new File(out, "24_arquivo_filtrada.png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (n.endsWith("_relatorio.pdf")) {
                        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(f)) {
                            org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(d);
                            for (int i = 0; i < d.getNumberOfPages(); i++)
                                ImageIO.write(r.renderImageWithDPI(i, 110), "png", new File(out, "21_relatorio_pdf_pagina_" + (i + 1) + ".png"));
                        }
                    }
                }
                Files.write(new File(out, "arquivos_salvos.txt").toPath(), names.toString().getBytes(StandardCharsets.UTF_8));
            });
        } catch (Throwable t) {
            log.add("FALHA geral :: " + t);
        } finally {
            try { Files.write(new File(out, "capturas.txt").toPath(), (String.join("\n", log) + "\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
            try { onEdt(() -> { for (Window w : Window.getWindows()) w.dispose(); }); } catch (Exception ignored) {}
        }
        long ok = log.stream().filter(l -> l.startsWith("ok")).count();
        return ok >= 12 ? 0 : 4;
    }

    private static void step(String name, Step s) {
        try { s.run(); log.add("ok    " + name); }
        catch (Throwable t) { log.add("FALHA " + name + " :: " + t); closeDialogs(); }
    }

    private static void onEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
    }

    private static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    /** Espera a análise terminar (botão Cancelar desabilitado e nenhum trabalho pendente). */
    private static void waitIdle(int seconds) throws Exception {
        pause(1200);
        long end = System.currentTimeMillis() + seconds * 1000L;
        boolean[] busy = {true};
        while (System.currentTimeMillis() < end) {
            onEdt(() -> busy[0] = frame.busy());
            if (!busy[0]) { pause(1200); onEdt(() -> busy[0] = frame.busy()); if (!busy[0]) return; }
            pause(500);
        }
        throw new IllegalStateException("análise não terminou em " + seconds + " s");
    }

    private static void window(String name) throws Exception {
        AtomicReference<BufferedImage> img = new AtomicReference<>();
        onEdt(() -> img.set(paint(frame)));
        ImageIO.write(img.get(), "png", new File(out, name + ".png"));
    }

    private static BufferedImage paint(Window w) {
        Component c = w instanceof javax.swing.RootPaneContainer ? ((javax.swing.RootPaneContainer) w).getRootPane() : w;
        BufferedImage img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.printAll(g);
        g.dispose();
        return img;
    }

    /**
     * Abre um diálogo modal (que bloqueia o EDT no próprio laço de eventos), espera ele aparecer, aplica um ajuste
     * opcional, fotografa e fecha (accept=true: confirma a primeira opção, como OK / Aplicar).
     */
    private static void modal(String name, Runnable opener, Consumer<Window> adjust, boolean accept) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Timer[] watch = new Timer[1];
        long start = System.currentTimeMillis();
        watch[0] = new Timer(250, e -> {
            Window dialog = null;
            for (Window w : Window.getWindows()) if (w != frame && w.isShowing() && w instanceof JDialog) dialog = w;
            if (dialog == null) {
                if (System.currentTimeMillis() - start > 20000) { watch[0].stop(); error.set(new IllegalStateException("diálogo não abriu")); done.countDown(); }
                return;
            }
            watch[0].stop();
            Window d = dialog;
            Timer later = new Timer(900, e2 -> {
                try {
                    if (adjust != null) adjust.accept(d);
                } catch (Throwable t) { error.set(t); }
                Timer shoot = new Timer(adjust != null ? 1500 : 200, e3 -> {
                    try {
                        ImageIO.write(paint(d), "png", new File(out, name + ".png"));
                        JOptionPane pane = find(d, JOptionPane.class);
                        if (accept && pane != null) {
                            Object[] opts = pane.getOptions();
                            pane.setValue(opts != null && opts.length > 0 ? opts[0] : Integer.valueOf(JOptionPane.OK_OPTION));
                        } else d.dispose();
                    } catch (Throwable t) { error.set(t); d.dispose(); }
                    done.countDown();
                });
                shoot.setRepeats(false);
                shoot.start();
            });
            later.setRepeats(false);
            later.start();
        });
        SwingUtilities.invokeAndWait(() -> watch[0].start());
        SwingUtilities.invokeLater(() -> { try { opener.run(); } catch (Throwable t) { error.set(t); done.countDown(); } });
        if (!done.await(60, TimeUnit.SECONDS)) { closeDialogs(); throw new IllegalStateException("tempo esgotado no diálogo"); }
        pause(600);
        if (error.get() != null) throw new IllegalStateException(error.get());
    }

    private static void closeDialogs() {
        try { onEdt(() -> { for (Window w : Window.getWindows()) if (w != frame && w.isShowing()) w.dispose(); }); } catch (Exception ignored) {}
    }

    static <T> T find(Container c, Class<T> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) return type.cast(k);
            if (k instanceof Container) { T t = find((Container) k, type); if (t != null) return t; }
        }
        return null;
    }
}
