package br.com.pelotavision;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Window;
import java.util.Locale;

/** Ajusta a barra nativa sem substituir a decoracao ou os controles da janela. */
final class StudioWindowChrome {
    private static final int DARK_MODE = 20;
    private static final int DARK_MODE_LEGACY = 19;
    private static final int CAPTION_COLOR = 35;
    private static volatile String lastStatus = "nao solicitado";

    private StudioWindowChrome() {}

    /** Chamar apos a criacao do peer (por exemplo, apos pack), antes de exibir a janela.
     * Fora da EDT, a tentativa e agendada nela; uma janela sem peer e ignorada. */
    static void apply(Window window) {
        if (window == null) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> apply(window));
            return;
        }
        tryNative(() -> {
            if (!isWindows(System.getProperty("os.name", "")) || !window.isDisplayable()) return;
            Pointer hwnd = Native.getWindowPointer(window);
            if (hwnd == null || Pointer.nativeValue(hwnd) == 0) return;
            DwmApi dwm = Native.load("dwmapi", DwmApi.class);
            lastStatus = applyAttributes((attribute, value) -> dwm.DwmSetWindowAttribute(
                    hwnd, attribute, new IntByReference(value), Integer.BYTES), StudioTheme.BACKGROUND);
            java.util.logging.Logger.getLogger("PelotaVision").info("Barra Windows: " + lastStatus);
        });
    }

    static void tryNative(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            lastStatus = "decoracao padrao; adaptador indisponivel: " + ignored.getClass().getSimpleName();
            // JNA, DLL, simbolo ou peer indisponivel: manter a decoracao nativa padrao.
        } catch (Error error) {
            if (error instanceof VirtualMachineError || error instanceof ThreadDeath) throw error;
            lastStatus = "decoracao padrao; adaptador indisponivel: " + error.getClass().getSimpleName();
            // JNA, DLL, simbolo ou peer indisponivel: manter a decoracao nativa padrao.
        }
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** COLORREF usa 0x00BBGGRR, nao o 0xAARRGGBB de java.awt.Color. */
    static int colorRef(Color color) {
        return color.getRed() | (color.getGreen() << 8) | (color.getBlue() << 16);
    }

    @FunctionalInterface
    interface AttributeSetter {
        int set(int attribute, int value);
    }

    static String applyAttributes(AttributeSetter setter, Color background) {
        // HRESULT negativo significa falha. A versao antiga do atributo era 19.
        int dark = setter.set(DARK_MODE, 1);
        if (dark < 0) dark = setter.set(DARK_MODE_LEGACY, 1);
        // Disponivel a partir do Windows 11 build 22000; versoes anteriores rejeitam com HRESULT.
        int caption = setter.set(CAPTION_COLOR, colorRef(background));
        return "modo_escuro=" + (dark >= 0 ? "aceito" : "indisponivel")
                + "; cor_exata=" + (caption >= 0 ? "aceita" : "nao suportada pelo sistema");
    }

    static String status() { return lastStatus; }

    private interface DwmApi extends StdCallLibrary {
        int DwmSetWindowAttribute(Pointer hwnd, int attribute, IntByReference value, int size);
    }
}
