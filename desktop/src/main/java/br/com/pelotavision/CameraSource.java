package br.com.pelotavision;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Webcam via OpenCV (DirectShow no Windows, com Media Foundation como alternativa).
 * Registra o que foi pedido e o que o driver realmente aceitou: foco/exposicao
 * travados sao pedidos ao driver, nao garantidos.
 */
final class CameraSource {

    static final class Info {
        int index; String backend; int requestedWidth, requestedHeight, width, height; String fourcc; double fps;
        boolean lockFocusRequested, lockExposureRequested;
        Boolean autofocusSetAccepted, autoExposureSetAccepted;
        double autofocusValue = Double.NaN, focusValue = Double.NaN, autoExposureValue = Double.NaN, exposureValue = Double.NaN;

        /** Copia imutavel para a foto capturada (o objeto vivo e atualizado pela camera). */
        Info copy() {
            Info c = new Info();
            c.index = index; c.backend = backend; c.requestedWidth = requestedWidth; c.requestedHeight = requestedHeight; c.width = width; c.height = height;
            c.fourcc = fourcc; c.fps = fps; c.lockFocusRequested = lockFocusRequested; c.lockExposureRequested = lockExposureRequested;
            c.autofocusSetAccepted = autofocusSetAccepted; c.autoExposureSetAccepted = autoExposureSetAccepted; c.autofocusValue = autofocusValue;
            c.focusValue = focusValue; c.autoExposureValue = autoExposureValue; c.exposureValue = exposureValue;
            return c;
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("camera_index", index); m.put("backend", backend);
            m.put("requested_width", requestedWidth); m.put("requested_height", requestedHeight);
            m.put("width", width); m.put("height", height); m.put("fourcc", fourcc); m.put("fps_reported", fps);
            m.put("lock_focus_requested", lockFocusRequested); m.put("autofocus_off_accepted_by_driver", autofocusSetAccepted);
            m.put("autofocus_value_read", autofocusValue); m.put("focus_value_read", focusValue);
            m.put("lock_exposure_requested", lockExposureRequested); m.put("auto_exposure_off_accepted_by_driver", autoExposureSetAccepted);
            m.put("auto_exposure_value_read", autoExposureValue); m.put("exposure_value_read", exposureValue);
            m.put("note", "Valores lidos do driver via OpenCV; nem toda webcam respeita ou informa estes controles.");
            return m;
        }

        String summary() {
            StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "Camera %d (%s) %dx%d %s", index, backend, width, height, fourcc));
            if (width != requestedWidth || height != requestedHeight) s.append(String.format(Locale.ROOT, " [pedido %dx%d]", requestedWidth, requestedHeight));
            if (lockFocusRequested) s.append(Boolean.TRUE.equals(autofocusSetAccepted) ? " | foco travado" : " | foco: driver recusou");
            if (lockExposureRequested) s.append(Boolean.TRUE.equals(autoExposureSetAccepted) ? " | exposicao travada" : " | exposicao: driver recusou");
            return s.toString();
        }
    }

    private volatile VideoCapture capture;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean reconnecting;
    private final Object lock = new Object();
    private Mat latest;
    private Info info;
    /** 0.5.2: avisos de queda/reconexão para a barra de status (chamado fora da thread da interface). */
    volatile Consumer<String> onStatus;

    /** Sem quadro por este tempo, a câmera é considerada desconectada. */
    static final long LOST_AFTER_MS = 1500;
    /** Tentativas de reabrir a mesma câmera (com os mesmos pedidos ao driver) antes de desistir. */
    static final int RECONNECT_ATTEMPTS = 15;
    static final long RECONNECT_INTERVAL_MS = 2000;

    /** Testa indices 0..max-1 e devolve os que abrem. */
    static List<Integer> probe(int max) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            VideoCapture c = new VideoCapture(i, Videoio.CAP_DSHOW);
            try { if (c.isOpened()) found.add(i); } finally { c.release(); }
        }
        return found;
    }

    /** Abre a câmera e aplica os pedidos ao driver; null se não abrir. */
    private static VideoCapture openCapture(int index, int width, int height, Info i) {
        VideoCapture c = new VideoCapture(index, Videoio.CAP_DSHOW);
        if (!c.isOpened()) { c.release(); c = new VideoCapture(index, Videoio.CAP_MSMF); }
        if (!c.isOpened()) { c.release(); return null; }
        c.set(Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc('M', 'J', 'P', 'G'));
        c.set(Videoio.CAP_PROP_FRAME_WIDTH, width);
        c.set(Videoio.CAP_PROP_FRAME_HEIGHT, height);
        if (i.lockFocusRequested) i.autofocusSetAccepted = c.set(Videoio.CAP_PROP_AUTOFOCUS, 0);
        // DirectShow no OpenCV: 0.25 = manual, 0.75 = automatico (convencao V4L/DSHOW do OpenCV).
        if (i.lockExposureRequested) i.autoExposureSetAccepted = c.set(Videoio.CAP_PROP_AUTO_EXPOSURE, 0.25);
        return c;
    }

    synchronized Info open(int index, int width, int height, boolean lockFocus, boolean lockExposure, Consumer<Mat> onFrame) {
        close();
        Info i = new Info();
        i.index = index; i.requestedWidth = width; i.requestedHeight = height;
        i.lockFocusRequested = lockFocus; i.lockExposureRequested = lockExposure;
        VideoCapture c = openCapture(index, width, height, i);
        if (c == null) throw new IllegalStateException("Nao foi possivel abrir a camera " + index + ".");
        capture = c;
        refreshInfo(i);
        info = i;
        running = true;
        reconnecting = false;
        thread = new Thread(() -> loop(index, width, height, i, onFrame), "PelotaVision-camera");
        thread.setDaemon(true);
        thread.start();
        return i;
    }

    /** Leitura contínua; se a câmera cair (cabo USB, hub, driver), tenta reabrir a mesma câmera. */
    private void loop(int index, int width, int height, Info i, Consumer<Mat> onFrame) {
        Mat frame = new Mat();
        long lastFrame = System.currentTimeMillis();
        try {
            while (running) {
                VideoCapture cap = capture;
                boolean ok = cap != null && cap.read(frame) && !frame.empty();
                if (ok) {
                    lastFrame = System.currentTimeMillis();
                    if (reconnecting) { reconnecting = false; status("Camera " + index + " reconectada."); }
                    synchronized (lock) {
                        if (latest != null) latest.release();
                        latest = frame.clone();
                    }
                    if (onFrame != null) onFrame.accept(frame);
                    continue;
                }
                if (System.currentTimeMillis() - lastFrame < LOST_AFTER_MS) {
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                    continue;
                }
                // Perdida: descarta o último quadro para nunca capturar uma imagem velha como se fosse atual.
                reconnecting = true;
                synchronized (lock) { if (latest != null) { latest.release(); latest = null; } }
                VideoCapture reopened = null;
                for (int attempt = 1; running && attempt <= RECONNECT_ATTEMPTS && reopened == null; attempt++) {
                    status("Camera " + index + " sem imagem · reconectando (" + attempt + "/" + RECONNECT_ATTEMPTS + ")…");
                    VideoCapture old = capture;
                    capture = null;
                    if (old != null) old.release();
                    try { Thread.sleep(RECONNECT_INTERVAL_MS); } catch (InterruptedException e) { return; }
                    if (!running) return;
                    reopened = openCapture(index, width, height, i);
                }
                if (reopened == null) {
                    status("Camera " + index + " desconectada. Confira o cabo e abra a camera de novo.");
                    return;
                }
                capture = reopened;
                try { refreshInfo(i); } catch (RuntimeException ignored) {}
                lastFrame = System.currentTimeMillis();
            }
        } finally {
            frame.release();
            running = false;
        }
    }

    private void status(String message) {
        Consumer<String> s = onStatus;
        if (s != null) s.accept(message);
    }

    /** true enquanto a câmera caiu e o app tenta reabri-la (capturar fica bloqueado). */
    boolean isReconnecting() { return reconnecting; }

    private void refreshInfo(Info i) {
        VideoCapture c = capture;
        if (c == null) return;
        i.backend = safeBackend(c);
        i.width = (int) c.get(Videoio.CAP_PROP_FRAME_WIDTH);
        i.height = (int) c.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        i.fps = c.get(Videoio.CAP_PROP_FPS);
        int code = (int) c.get(Videoio.CAP_PROP_FOURCC);
        i.fourcc = code == 0 ? "?" : "" + (char) (code & 0xFF) + (char) ((code >> 8) & 0xFF) + (char) ((code >> 16) & 0xFF) + (char) ((code >> 24) & 0xFF);
        i.autofocusValue = c.get(Videoio.CAP_PROP_AUTOFOCUS);
        i.focusValue = c.get(Videoio.CAP_PROP_FOCUS);
        i.autoExposureValue = c.get(Videoio.CAP_PROP_AUTO_EXPOSURE);
        i.exposureValue = c.get(Videoio.CAP_PROP_EXPOSURE);
    }

    private static String safeBackend(VideoCapture c) {
        try { return c.getBackendName(); } catch (RuntimeException e) { return "?"; }
    }

    /** Abre a janela de propriedades do driver (DirectShow). Melhor caminho para travar foco/exposicao. */
    synchronized boolean showDriverSettings() {
        return capture != null && capture.set(Videoio.CAP_PROP_SETTINGS, 1);
    }

    /** Copia do quadro mais recente (BGR) ou null. */
    Mat snapshot() {
        synchronized (lock) { return latest == null ? null : latest.clone(); }
    }

    /** Releitura das propriedades no momento da captura (o usuario pode ter mudado no driver). */
    synchronized Info currentInfo() {
        if (capture == null || info == null) return info;
        refreshInfo(info);
        return info;
    }

    boolean isRunning() { return running; }

    synchronized void close() {
        running = false;
        if (thread != null) { try { thread.join(1500); } catch (InterruptedException ignored) {} thread = null; }
        VideoCapture c = capture;
        capture = null;
        if (c != null) c.release();
        reconnecting = false;
        synchronized (lock) { if (latest != null) { latest.release(); latest = null; } }
    }
}
