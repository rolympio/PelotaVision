package br.com.pelotavision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

final class StudioLog {
    private static boolean installed;
    private StudioLog() {}
    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            String local = System.getenv("LOCALAPPDATA");
            Path dir = Path.of(local == null ? System.getProperty("user.home") : local, "PelotaVision", "logs");
            Files.createDirectories(dir);
            FileHandler handler = new FileHandler(dir.resolve("studio-%g.log").toString(), 2_000_000, 4, true);
            handler.setFormatter(new SimpleFormatter());
            Logger.getLogger("").addHandler(handler);
            Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                    Logger.getLogger("PelotaVision").log(java.util.logging.Level.SEVERE, thread.getName(), error));
            Logger.getLogger("PelotaVision").info("Inicio " + DesktopApp.NAME + " " + DesktopApp.VERSION
                    + "; nucleo=" + DesktopApp.CORE_VERSION + "; java=" + System.getProperty("java.version"));
        } catch (Exception e) { System.err.println("Log indisponivel: " + e); }
    }
}
