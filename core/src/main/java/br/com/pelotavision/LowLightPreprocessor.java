package br.com.pelotavision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

/**
 * Offline, cross-platform preprocessing for difficult illumination.
 * Input ROI remains unchanged. Returned Mats belong to the caller.
 * This does NOT estimate segmentation accuracy or replace physical calibration.
 */
public final class LowLightPreprocessor {
    private LowLightPreprocessor() {}
    public enum Mode { ORIGINAL, GAMMA_CLAHE, DENOISE_CLAHE, RETINEX }

    public static final class Quality {
        public final double mean, standardDeviation, clippedBlackFraction, clippedWhiteFraction;
        Quality(double mean, double sd, double black, double white) {
            this.mean=mean; standardDeviation=sd;
            clippedBlackFraction=black; clippedWhiteFraction=white;
        }
        public boolean needsCorrection() {
            return mean < 75 || standardDeviation < 22 || clippedBlackFraction > 0.08;
        }
    }

    public static Quality assess(Mat input) {
        Mat gray = gray8(input);
        try {
            MatOfDouble mean = new MatOfDouble(), std = new MatOfDouble();
            Core.meanStdDev(gray, mean, std);
            double black=0, white=0, total=gray.total();
            byte[] row = new byte[gray.cols()];
            for (int y=0;y<gray.rows();y++) {
                gray.get(y,0,row);
                for(byte b:row) {
                    int v=b&255;
                    if(v<=5)black++;
                    if(v>=250)white++;
                }
            }
            return new Quality(mean.toArray()[0],std.toArray()[0],black/total,white/total);
        } finally { gray.release(); }
    }

    /** Returns new single-channel 8-bit ROI, never modifies input. */
    public static Mat apply(Mat input, Mode mode) {
        Mat gray=gray8(input);
        if(mode==Mode.ORIGINAL) return gray;
        try {
            switch(mode) {
                case GAMMA_CLAHE:
                    Mat gamma = new Mat();
                    byte[] lut=new byte[256];
                    double exponent=0.65;
                    for(int i=0;i<256;i++)
                        lut[i]=(byte)Math.min(255,Math.round(255*Math.pow(i/255.0,exponent)));
                    Mat lookup=new Mat(1,256,CvType.CV_8UC1);
                    try {
                        lookup.put(0,0,lut);
                        Core.LUT(gray,lookup,gamma);
                        return clahe(gamma,2.0);
                    } finally { lookup.release();gamma.release(); }
                case DENOISE_CLAHE:
                    Mat filtered=new Mat();
                    try {
                        Imgproc.bilateralFilter(gray,filtered,7,28,7);
                        return clahe(filtered,1.8);
                    } finally {filtered.release();}
                case RETINEX:
                    Mat f=new Mat(),blur=new Mat(),logA=new Mat(),logB=new Mat(),diff=new Mat(),normalized=new Mat(),out=new Mat();
                    try {
                        gray.convertTo(f,CvType.CV_32F);
                        Core.add(f,org.opencv.core.Scalar.all(1),f);
                        Imgproc.GaussianBlur(f,blur,new Size(0,0),24);
                        Core.log(f,logA);Core.log(blur,logB);
                        Core.subtract(logA,logB,diff);
                        Core.normalize(diff,normalized,0,255,Core.NORM_MINMAX);
                        normalized.convertTo(out,CvType.CV_8U);
                        return out;
                    } finally {f.release();blur.release();logA.release();logB.release();diff.release();normalized.release();}
                default: throw new IllegalArgumentException("Unknown mode");
            }
        } finally {gray.release();}
    }

    private static Mat clahe(Mat gray,double clip) {
        CLAHE clahe=Imgproc.createCLAHE(clip,new Size(8,8));
        Mat out=new Mat();
        try {clahe.apply(gray,out);return out;}
        finally {clahe.collectGarbage();}
    }

    private static Mat gray8(Mat input) {
        if(input==null||input.empty())throw new IllegalArgumentException("Empty ROI");
        Mat gray=new Mat(),out=new Mat();
        try {
            if(input.channels()==1) input.copyTo(gray);
            else if(input.channels()==3)Imgproc.cvtColor(input,gray,Imgproc.COLOR_BGR2GRAY);
            else if(input.channels()==4)Imgproc.cvtColor(input,gray,Imgproc.COLOR_BGRA2GRAY);
            else throw new IllegalArgumentException("Unsupported channels");
            if(gray.depth()==CvType.CV_8U)return gray.clone();
            Core.normalize(gray,out,0,255,Core.NORM_MINMAX);
            Mat converted=new Mat();
            out.convertTo(converted,CvType.CV_8U);
            return converted;
        } finally {gray.release();out.release();}
    }
}
