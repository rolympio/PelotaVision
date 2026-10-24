package br.com.pelotavision;
import org.opencv.core.*;import org.opencv.dnn.*;
/** Stock FSRCNN x2 (T91/General100), not trained on pellets. Output shuffle/bias exactly match source graph. */
public final class FsrcnnUpscaler {
 public static final String MODEL_SHA256="8672ea3a739eb0c2e23c4d21f7d48f3826eb517320bb98879294c602b9c3806d";
 public static final String MODEL="FSRCNN_x2_features.pb";public static final float BIAS=0.20388786494731903f;
 private final Net net;
 public FsrcnnUpscaler(String path){net=Dnn.readNetFromTensorflow(path);net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);net.setPreferableTarget(Dnn.DNN_TARGET_CPU);if(net.empty())throw new IllegalArgumentException("Modelo SR indisponível.");}
 public Mat upsample(Mat gray){Mat blob=Dnn.blobFromImage(gray,1./255),out=null;try{net.setInput(blob);out=net.forward("conv8");if(out.dims()!=4||out.size(1)!=4||out.size(2)!=gray.rows()||out.size(3)!=gray.cols())throw new IllegalStateException("Saída SR inesperada.");float[] features=new float[4*gray.rows()*gray.cols()];out.get(new int[]{0,0,0,0},features);byte[] pixels=shuffle(features,gray.cols(),gray.rows(),BIAS);Mat enlarged=new Mat(gray.rows()*2,gray.cols()*2,CvType.CV_8UC1);enlarged.put(0,0,pixels);return enlarged;}finally{blob.release();if(out!=null)out.release();}}
 static byte[] shuffle(float[] features,int w,int h,float bias){if(features.length!=4*w*h)throw new IllegalArgumentException("Features inválidas.");byte[] pixels=new byte[4*w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){float value=features[(dy*2+dx)*w*h+y*w+x]+bias;pixels[(y*2+dy)*(w*2)+x*2+dx]=(byte)Math.round(Math.max(0,Math.min(255,value*255)));}return pixels;}
}
