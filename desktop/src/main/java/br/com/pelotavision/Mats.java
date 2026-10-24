package br.com.pelotavision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Conversoes de imagem para o app desktop. */
final class Mats {
    private Mats() {}

    /** Mesmo teto do Android (ImageCodec.upright): lado maior ate 3000 px, reduzindo em potencias de dois. */
    static final int MAX_ANALYSIS_SIDE = 3000;

    /**
     * Le a imagem pelos bytes (funciona com acentos no caminho do Windows) e aplica a
     * orientacao EXIF. Retorna BGR 8 bits.
     */
    static Mat readUpright(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        MatOfByte buffer = new MatOfByte(bytes);
        Mat bgr;
        try { bgr = Imgcodecs.imdecode(buffer, Imgcodecs.IMREAD_COLOR); } finally { buffer.release(); }
        if (bgr == null || bgr.empty()) throw new IOException("Nao foi possivel abrir a imagem: " + file.getName());
        return bgr;
    }

    /** Fator de amostragem em potencia de dois, como inSampleSize do Android. */
    static int sampleFor(int w, int h) {
        int sample = 1;
        while (Math.max(w, h) / sample > MAX_ANALYSIS_SIDE) sample *= 2;
        return sample;
    }

    static Mat downsample(Mat bgr, int sample) {
        if (sample <= 1) return bgr.clone();
        Mat out = new Mat();
        Imgproc.resize(bgr, out, new Size(bgr.cols() / sample, bgr.rows() / sample), 0, 0, Imgproc.INTER_AREA);
        return out;
    }

    static Mat gray(Mat bgr) {
        Mat gray = new Mat();
        if (bgr.channels() == 1) bgr.copyTo(gray);
        else if (bgr.channels() == 4) Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGRA2GRAY);
        else Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        return gray;
    }

    static BufferedImage toImage(Mat mat) {
        Mat src = mat;
        boolean temp = false;
        if (mat.type() != CvType.CV_8UC3 && mat.type() != CvType.CV_8UC1) {
            src = new Mat();
            if (mat.channels() == 4) Imgproc.cvtColor(mat, src, Imgproc.COLOR_BGRA2BGR);
            else mat.convertTo(src, CvType.CV_8U);
            temp = true;
        }
        if (!src.isContinuous()) { Mat c = src.clone(); if (temp) src.release(); src = c; temp = true; }
        int type = src.channels() == 1 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR;
        BufferedImage image = new BufferedImage(src.cols(), src.rows(), type);
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        src.get(0, 0, target);
        if (temp) src.release();
        return image;
    }
}
