package br.com.pelotavision;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Size;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 0.16.0: escolha da câmera (traseira, frontal ou externa USB/OTG).
 *
 * Câmeras USB (UVC) só aparecem aqui quando o próprio Android as publica no Camera2 como
 * LENS_FACING_EXTERNAL (nível de hardware EXTERNAL). Isso depende do fabricante: o kernel precisa do
 * driver UVC e o sistema do provedor de câmera externa. Quando o aparelho não publica a câmera, o
 * diagnóstico mostra o dispositivo USB de vídeo conectado e explica a limitação, em vez de falhar calado.
 */
final class CameraChoice {
    private CameraChoice() {}

    static final int FACING_FRONT = 0, FACING_BACK = 1, FACING_EXTERNAL = 2, FACING_UNKNOWN = -1;

    static final class Entry {
        final String id;
        final int facing;
        final String hardwareLevel;
        final Size maxJpeg;
        final CameraInfo info;

        Entry(String id, int facing, String level, Size maxJpeg, CameraInfo info) {
            this.id = id; this.facing = facing; hardwareLevel = level; this.maxJpeg = maxJpeg; this.info = info;
        }

        boolean external() { return facing == FACING_EXTERNAL; }

        String label() {
            String kind = facing == FACING_BACK ? "Traseira" : facing == FACING_FRONT ? "Frontal" : facing == FACING_EXTERNAL ? "Externa (USB)" : "Outra";
            return kind + " · id " + id + (maxJpeg == null ? "" : " · até " + maxJpeg.getWidth() + "×" + maxJpeg.getHeight());
        }
    }

    /** Câmeras que o CameraX consegue abrir, na ordem: traseiras, externas, frontais. */
    @androidx.annotation.OptIn(markerClass = ExperimentalCamera2Interop.class)
    static List<Entry> list(ProcessCameraProvider provider) {
        List<Entry> out = new ArrayList<>();
        for (CameraInfo info : provider.getAvailableCameraInfos()) {
            try {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
                Integer facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING);
                Integer level = c2.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                StreamConfigurationMap map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                out.add(new Entry(c2.getCameraId(), facing == null ? FACING_UNKNOWN : facing, levelName(level), largestJpeg(map), info));
            } catch (RuntimeException ignored) {
                // Câmera que o interop não descreve: fica de fora da lista (a padrão continua disponível).
            }
        }
        Collections.sort(out, (a, b) -> Integer.compare(order(a.facing), order(b.facing)));
        return out;
    }

    private static int order(int facing) {
        return facing == FACING_BACK ? 0 : facing == FACING_EXTERNAL ? 1 : facing == FACING_FRONT ? 2 : 3;
    }

    /** Escolhe pelo id salvo; se ele sumiu (USB desconectada), cai para a primeira traseira, depois a primeira da lista. */
    static Entry pick(List<Entry> entries, String savedId) {
        if (entries.isEmpty()) return null;
        if (savedId != null) for (Entry e : entries) if (e.id.equals(savedId)) return e;
        for (Entry e : entries) if (e.facing == FACING_BACK) return e;
        return entries.get(0);
    }

    /** Seletor que só aceita a câmera com este id do Camera2 (filtro estável do CameraX). */
    @androidx.annotation.OptIn(markerClass = ExperimentalCamera2Interop.class)
    static CameraSelector selector(String id) {
        return new CameraSelector.Builder().addCameraFilter(infos -> {
            List<CameraInfo> keep = new ArrayList<>();
            for (CameraInfo i : infos) {
                try { if (id.equals(Camera2CameraInfo.from(i).getCameraId())) keep.add(i); } catch (RuntimeException ignored) { }
            }
            return keep;
        }).build();
    }

    static Size largestJpeg(StreamConfigurationMap map) {
        if (map == null) return null;
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        for (Size s : sizes) if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    static String levelName(Integer level) {
        if (level == null) return "desconhecido";
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return "nível " + level;
        }
    }

    /** Dispositivos USB de vídeo (classe UVC 0x0E no dispositivo ou em alguma interface). */
    static List<String> usbVideoDevices(Context context) {
        List<String> out = new ArrayList<>();
        UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usb == null) return out;
        for (UsbDevice d : usb.getDeviceList().values()) {
            boolean video = d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO;
            for (int i = 0; i < d.getInterfaceCount() && !video; i++) video = d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO;
            if (!video) continue;
            String name = d.getProductName();
            out.add(String.format(Locale.ROOT, "%s (VID %04X, PID %04X)", name == null || name.isEmpty() ? "Câmera USB" : name, d.getVendorId(), d.getProductId()));
        }
        return out;
    }

    /** Ids publicados pelo Camera2 agora (inclui câmeras externas que o CameraX ainda não conhece). */
    static List<String> camera2Ids(Context context) {
        List<String> out = new ArrayList<>();
        try {
            CameraManager m = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (m != null) Collections.addAll(out, m.getCameraIdList());
        } catch (Exception ignored) { }
        return out;
    }

    /** Texto de diagnóstico para o usuário (e para o suporte): o que o aparelho publica e o que está no USB. */
    static String diagnostics(Context context, List<Entry> entries) {
        PackageManager pm = context.getPackageManager();
        boolean host = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        boolean external = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_EXTERNAL);
        List<String> usb = usbVideoDevices(context);
        StringBuilder b = new StringBuilder();
        b.append("USB host (OTG): ").append(host ? "sim" : "não").append('\n');
        b.append("Câmera externa no Android (FEATURE_CAMERA_EXTERNAL): ").append(external ? "sim" : "não").append('\n');
        b.append("Câmeras no Camera2: ").append(camera2Ids(context)).append('\n');
        b.append("Câmeras abríveis pelo app:\n");
        for (Entry e : entries) b.append("  • ").append(e.label()).append(" · ").append(e.hardwareLevel).append('\n');
        b.append("Dispositivos USB de vídeo conectados: ").append(usb.isEmpty() ? "nenhum" : String.join("; ", usb)).append('\n');
        boolean listedExternal = false;
        for (Entry e : entries) listedExternal |= e.external();
        if (!usb.isEmpty() && !listedExternal) {
            b.append("\nHá uma câmera USB conectada, mas este aparelho não a publica para os aplicativos (falta suporte UVC no sistema do fabricante). ");
            b.append(external ? "O aparelho declara suporte: desconecte e reconecte o cabo OTG e toque em \"Atualizar lista\"." :
                    "Neste aparelho a câmera USB não pode ser usada pela API de câmera do Android.");
        } else if (usb.isEmpty() && !listedExternal) {
            b.append("\nNenhuma câmera USB detectada. Confira o cabo OTG, a alimentação da câmera e se o aparelho tem USB host.");
        }
        return b.toString();
    }
}
