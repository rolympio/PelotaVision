package br.com.pelotavision;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rajada no Android (0.19.0): várias fotos escolhidas no seletor viram uma empilhada (Trilha 1, mesma
 * grade e escala da foto base) que é analisada como foto comum. Mesmo núcleo do Windows
 * ({@link BurstStack}). A SR 2× fica desligada no Android nesta versão (custo de CPU ainda não medido
 * em aparelho); o laudo e o bloco JSON {@code "burst"} são os mesmos.
 *
 * <p>Os arquivos escolhidos são cópias no cache do app; os originais da galeria não são tocados e as
 * cópias vão byte a byte para a exportação ({@code _rajada_quadro_XX}).</p>
 */
final class BurstImport {
    final BurstStack.Result result;
    final List<File> frames;
    final List<String> mimes;
    /** PNG da empilhada no cache: é o "photoFile" da análise. */
    final File fused;

    private BurstImport(BurstStack.Result result, List<File> frames, List<String> mimes, File fused) {
        this.result = result; this.frames = frames; this.mimes = mimes; this.fused = fused;
    }

    /** Decodifica (orientação EXIF e teto de 3000 px iguais aos da foto comum), empilha e grava a empilhada. */
    static BurstImport run(List<File> frames, List<String> mimes, File cacheDir) throws IOException {
        if (frames.size() < 2) throw new IOException("Escolha pelo menos 2 fotos da mesma cena.");
        if (frames.size() > BurstStack.MAX_FRAMES) throw new IOException("Rajada limitada a " + BurstStack.MAX_FRAMES + " fotos.");
        List<Mat> mats = new ArrayList<>();
        BurstStack.Result r = null;
        try {
            int w = -1, h = -1;
            for (File f : frames) {
                Bitmap b = ImageCodec.upright(f);
                Mat rgba = new Mat(), bgr = new Mat();
                try { Utils.bitmapToMat(b, rgba); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); }
                finally { rgba.release(); b.recycle(); }
                if (w < 0) { w = bgr.cols(); h = bgr.rows(); }
                else if (bgr.cols() != w || bgr.rows() != h) { bgr.release(); throw new IOException("As fotos da rajada precisam ter o mesmo tamanho e orientação."); }
                mats.add(bgr);
            }
            BurstStack.Options o = new BurstStack.Options();
            o.sr = false;
            r = BurstStack.run(mats, o);
            File out = new File(cacheDir, "rajada_empilhada_" + System.currentTimeMillis() + ".png");
            if (r.fused != null) {
                MatOfByte buf = new MatOfByte();
                try {
                    if (!Imgcodecs.imencode(".png", r.fused, buf)) throw new IOException("Falha ao codificar a empilhada.");
                    try (FileOutputStream os = new FileOutputStream(out)) { os.write(buf.toArray()); }
                } finally { buf.release(); }
            } else out = null;
            BurstImport done = new BurstImport(r, frames, mimes, out);
            r.release(); // as Mats não são mais necessárias; o modo e o laudo ficam
            r = null;
            return done;
        } finally {
            for (Mat m : mats) m.release();
            if (r != null) r.release();
        }
    }

    /** Apaga as cópias em cache (a empilhada é apagada junto com a foto da sessão). */
    void deleteFrames() { for (File f : frames) f.delete(); }
}
