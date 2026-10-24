package br.com.pelotavision;

import org.opencv.core.Core;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;

/** PelotaVision para Windows: ponto de entrada. Usa o mesmo nucleo de analise do Android. */
public final class DesktopApp {
    public static final String NAME = "PelotaVision Windows";
    public static final String VERSION = "0.3.0";
    /** Versao do nucleo de analise compartilhado com o Android. */
    public static final String CORE_VERSION = "0.12.0";

    private DesktopApp() {}

    public static void main(String[] args) {
        if (args.length >= 3 && "--autoteste".equals(args[0])) { System.exit(selfTest(new File(args[1]), new File(args[2]))); return; }
        try {
            loadOpenCv();
        } catch (Throwable e) {
            JOptionPane.showMessageDialog(null, "Nao foi possivel carregar o OpenCV (" + Core.NATIVE_LIBRARY_NAME + ").\n" + e,
                    NAME, JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            new MainFrame().setVisible(true);
        });
    }

    /**
     * Autoteste do pacote instalado (usado no CI): carrega a DLL e os modelos da pasta do
     * pacote, analisa uma imagem e exporta. Grava autoteste.txt com o resultado.
     */
    static int selfTest(File image, File outDir) {
        StringBuilder log = new StringBuilder();
        int code = 0;
        try {
            loadOpenCv();
            log.append("opencv=").append(Core.VERSION).append('\n');
            log.append("appDir=").append(appDir()).append('\n');
            log.append("crackModel=").append(model("crack_forest.pvrf")).append(' ').append(model("crack_forest.pvrf").isFile()).append('\n');
            log.append("neuralModel=").append(NeuralPelletDetector.status()).append('\n');
            org.opencv.core.Mat bgr = Mats.readUpright(image);
            PhotoSession session = new PhotoSession(image, null, null, bgr);
            bgr.release();
            DesktopAnalysis.Config cfg = new DesktopAnalysis.Config();
            DesktopAnalysis.Output out = DesktopAnalysis.analyze(session.analyzed, session.originalWidth, session.originalHeight, session.sample, null, cfg, null, () -> false, (d, t, l) -> {});
            session.accept(out);
            log.append("count_full=").append(session.active().circles.size()).append('\n');
            log.append("applied=").append(out.applied.id).append('\n');
            // ROI retangular central + reanalise com o mesmo ajuste + escala digitada.
            session.setRegion(InterestRegion.initial(InterestRegion.Shape.RECTANGLE, session.width, session.height));
            boolean pending = session.needsAnalysis() && !session.canSave();
            cfg.autoSuggest = false;
            DesktopAnalysis.Output roi = DesktopAnalysis.analyze(session.analyzed, session.originalWidth, session.originalHeight, session.sample, session.region(), cfg, out.applied, () -> false, (d, t, l) -> {});
            session.accept(roi);
            session.setScale(new Granulometry.Settings(10, 1, true, false));
            log.append("roi_pending_before_analysis=").append(pending).append('\n');
            log.append("count_roi=").append(session.active().circles.size()).append('\n');
            java.util.List<File> files = DesktopExport.save(outDir, session, cfg);
            String json = new String(java.nio.file.Files.readAllBytes(files.get(4).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            boolean jsonOk = json.contains("\"region_of_interest\"") && json.contains("\"RECTANGLE\"") && json.contains("\"physical_scale\"") && json.contains("\"processing_scope\"");
            session.release();
            log.append("crackModelLoaded=").append(DesktopAnalysis.crackModel() != null).append('\n');
            log.append("files=").append(files.size()).append('\n');
            log.append("json_android_keys=").append(jsonOk).append('\n');
            if (session.active().circles.isEmpty() || out.result.circles.isEmpty() || !pending || !jsonOk) code = 3;
            if (DesktopAnalysis.crackModel() == null || files.size() != 5) code = 2;
        } catch (Throwable e) {
            log.append("error=").append(e).append('\n');
            code = 1;
        }
        log.append("exit=").append(code).append('\n');
        try {
            outDir.mkdirs();
            java.nio.file.Files.write(new File(outDir, "autoteste.txt").toPath(), log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        System.out.print(log);
        return code;
    }

    private static boolean loaded;

    /** Carrega a DLL ao lado do .jar (pacote portatil) ou pelo java.library.path (testes). */
    public static synchronized void loadOpenCv() {
        if (loaded) return;
        File dll = new File(appDir(), System.mapLibraryName(Core.NATIVE_LIBRARY_NAME));
        if (dll.isFile()) System.load(dll.getAbsolutePath());
        else System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        loaded = true;
        DesktopAnalysis.installNeuralModel();
    }

    /** Pasta do .jar da aplicacao. */
    public static File appDir() {
        try {
            File location = new File(DesktopApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.isFile() ? location.getParentFile() : location;
        } catch (Exception e) {
            return new File(".");
        }
    }

    /** Modelo embarcado: pasta "modelos" do pacote ou -Dpelotavision.modelDir (testes). */
    public static File model(String name) {
        File packaged = new File(new File(appDir(), "modelos"), name);
        if (packaged.isFile()) return packaged;
        String dir = System.getProperty("pelotavision.modelDir");
        return dir == null ? packaged : new File(dir, name);
    }
}
