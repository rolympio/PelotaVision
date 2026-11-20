package br.com.pelotavision;

import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Rajada anexada a uma foto do Windows (0.19.0): o resultado do {@link BurstStack} e as fontes (arquivos
 * escolhidos, copiados byte a byte na exportação, ou quadros da webcam, gravados em PNG sem perdas).
 * A foto analisada da sessão é a empilhada; os quadros originais nunca são alterados.
 */
final class BurstRecord {
    final BurstStack.Result result;
    final List<File> files;
    final List<BufferedImage> frames;
    final CameraSource.Info camera;

    BurstRecord(BurstStack.Result result, List<File> files, List<BufferedImage> frames, CameraSource.Info camera) {
        this.result = result;
        this.files = files == null ? Collections.emptyList() : files;
        this.frames = frames == null ? Collections.emptyList() : frames;
        this.camera = camera;
    }

    int count() { return files.isEmpty() ? frames.size() : files.size(); }

    void release() { result.release(); }

    /**
     * Grava os quadros (prefixo {@code base_rajada_quadro_XX}) e a SR 2× ({@code base_rajada_sr2x.png},
     * se houver) e devolve o bloco {@code "burst"}. {@code fusedName}: arquivo da empilhada já gravado.
     */
    JSONObject export(File folder, String base, List<File> written, String fusedName) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count(); i++) {
            File out;
            if (!files.isEmpty()) {
                File src = files.get(i);
                String n = src.getName();
                String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "img";
                out = new File(folder, String.format(Locale.ROOT, "%s_rajada_quadro_%02d.%s", base, i + 1, ext));
                Files.copy(src.toPath(), out.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            } else {
                out = new File(folder, String.format(Locale.ROOT, "%s_rajada_quadro_%02d.png", base, i + 1));
                if (!ImageIO.write(frames.get(i), "png", out)) throw new IOException("Falha ao gravar o quadro " + (i + 1) + " da rajada.");
            }
            written.add(out);
            names.add(out.getName());
        }
        String srName = null;
        if (result.sr != null) {
            File sr = new File(folder, base + "_rajada_sr2x.png");
            BurstCli.write(sr, result.sr);
            written.add(sr);
            srName = sr.getName();
        }
        JSONObject j = result.toJson(names, fusedName, srName, true);
        if (!files.isEmpty()) {
            List<String> src = new ArrayList<>();
            for (File f : files) src.add(f.getName());
            j.put("source_file_names", src);
        }
        if (camera != null) j.put("camera", new JSONObject(camera.asMap()));
        return j;
    }
}
