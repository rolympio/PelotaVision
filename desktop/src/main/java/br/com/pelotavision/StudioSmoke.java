package br.com.pelotavision;

import org.json.JSONObject;
import org.opencv.core.Mat;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Teste estrito da JANELA do pacote; abre foto, analisa, navega, calibra e exporta PDF. */
final class StudioSmoke {
    private StudioSmoke() {}
    static int run(File image, File output) {
        return run(image, output, null);
    }
    static int run(File image, File output, File experimentalModel) {
        JSONObject report = new JSONObject().put("app_version", DesktopApp.VERSION).put("input", image.getName())
                .put("scientific_validation", false).put("scale_note", "10 px/mm fornecidos somente ao ensaio de software");
        AtomicReference<MainFrame> ref = new AtomicReference<>();
        int code = 1;
        try {
            if (output.exists() || !output.mkdirs()) throw new IllegalArgumentException("Saida deve ser nova");
            System.setProperty("pelotavision.smoke", "true");
            DesktopApp.loadOpenCv();
            SwingUtilities.invokeAndWait(() -> {
                StudioTheme.install();
                MainFrame frame = new MainFrame();
                frame.setExtendedState(javax.swing.JFrame.NORMAL);
                frame.setSize(1600, 1000); frame.setVisible(true); ref.set(frame);
            });
            Mat bgr = Mats.readUpright(image);
            PhotoSession session;
            try { session = new PhotoSession(image, null, null, bgr); } finally { bgr.release(); }
            SwingUtilities.invokeAndWait(() -> ref.get().manualLoad(session));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(240);
            boolean[] busy = {true};
            do {
                Thread.sleep(250);
                SwingUtilities.invokeAndWait(() -> busy[0] = ref.get().busy());
            } while (busy[0] && System.nanoTime() < deadline);
            if (busy[0] || !session.canSave()) throw new IllegalStateException("Analise nao terminou ou nao permite exportacao");
            SwingUtilities.invokeAndWait(() -> ref.get().setScale(new Granulometry.Settings(10, 1, true, true)));
            if (session.active().circles.isEmpty()) throw new IllegalStateException("Nenhum objeto detectado");
            report.put("detected_objects", session.active().circles.size());
            if (experimentalModel != null) {
                PelletDetector.Result before = session.active();
                Granulometry.Distribution beforeDistribution = session.distribution();
                SwingUtilities.invokeAndWait(() -> ref.get().startExperimentalPreview(experimentalModel));
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(240);
                do {
                    Thread.sleep(250);
                    SwingUtilities.invokeAndWait(() -> busy[0] = ref.get().busy());
                } while (busy[0] && System.nanoTime() < end);
                if (busy[0]) throw new IllegalStateException("IA experimental excedeu tempo limite");
                SwingUtilities.invokeAndWait(() -> {
                    MainFrame f = ref.get();
                    if (!f.view.experimentalAvailable() || session.active() != before || session.distribution() != beforeDistribution)
                        throw new IllegalStateException("Prévia indisponível ou alterou resultados científicos");
                    f.studioStage(4); f.validate();
                    BufferedImage shot = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = shot.createGraphics(); f.getRootPane().printAll(g); g.dispose();
                    try { ImageIO.write(shot, "png", new File(output, "ia-experimental.png")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                report.put("experimental_ai", "preview_cpu_sha_verificado_sem_alteracao_de_medicoes");
            }
            for (int i = 0; i < StudioShell.STAGES.length; i++) {
                final int index = i;
                SwingUtilities.invokeAndWait(() -> {
                    MainFrame f = ref.get(); f.studioStage(index);
                    if (index == 4) f.manual("mapa");
                    f.validate();
                    BufferedImage shot = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = shot.createGraphics(); f.getRootPane().printAll(g); g.dispose();
                    try { ImageIO.write(shot, "png", new File(output, "etapa-" + (index + 1) + ".png")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            SwingUtilities.invokeAndWait(() -> {
                MainFrame f = ref.get();
                f.setSize(1280, 800); f.studioStage(4); f.validate();
                BufferedImage shot = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = shot.createGraphics(); f.getRootPane().printAll(g); g.dispose();
                try { ImageIO.write(shot, "png", new File(output, "layout-resultados-1280.png")); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            List<File> files = DesktopExport.save(new File(output, "analise"), session, ref.get().config(), "Ensaio do pacote Studio");
            File pdf = files.stream().filter(f -> f.getName().endsWith("_relatorio.pdf")).findFirst().orElseThrow();
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                if (doc.getNumberOfPages() < 1) throw new IllegalStateException("PDF vazio");
                report.put("pdf_pages", doc.getNumberOfPages());
            }
            SwingUtilities.invokeAndWait(() -> ref.get().showReport(pdf));
            long previewEnd = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            int[] shown = {0};
            do {
                Thread.sleep(250);
                SwingUtilities.invokeAndWait(() -> shown[0] = ref.get().reportPreview.pageCount());
            } while (shown[0] == 0 && System.nanoTime() < previewEnd);
            if (shown[0] < 1) throw new IllegalStateException("Prévia do PDF não apareceu na etapa Relatório");
            SwingUtilities.invokeAndWait(() -> {
                MainFrame f = ref.get(); f.validate();
                BufferedImage shot = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = shot.createGraphics(); f.getRootPane().printAll(g); g.dispose();
                try { ImageIO.write(shot, "png", new File(output, "relatorio-pdf-na-tela.png")); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            report.put("pdf_preview_pages", shown[0]);
            report.put("pdf_bytes", pdf.length()).put("exported_files", files.size()).put("stages_rendered", 6)
                    .put("extra_layout", "1280x800; captura exige revisao visual")
                    .put("title_bar_request", StudioWindowChrome.status());
            code = 0;
        } catch (Throwable e) { report.put("error", e.toString()); e.printStackTrace(); }
        finally {
            try { SwingUtilities.invokeAndWait(() -> {
                MainFrame f = ref.get(); if (f != null) { f.camera.close(); if (f.session != null) f.session.release(); }
                for (Window w : Window.getWindows()) w.dispose();
            }); } catch (Exception ignored) { }
            report.put("exit", code);
            try { if (output.isDirectory()) Files.writeString(new File(output, "studio-smoke.json").toPath(), report.toString(2), StandardCharsets.UTF_8); }
            catch (Exception ignored) { }
        }
        return code;
    }
}
