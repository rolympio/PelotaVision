package br.com.pelotavision;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import androidx.camera.core.ImageProxy;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

final class ImageCodec {
    private ImageCodec() {}
    static int[] originalUprightSize(File file) throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<=0||options.outHeight<=0)throw new IOException("Dimensões inválidas.");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try{orientation=new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,orientation);}catch(IOException ignored){}
        boolean swap=orientation==ExifInterface.ORIENTATION_TRANSPOSE||orientation==ExifInterface.ORIENTATION_ROTATE_90||
            orientation==ExifInterface.ORIENTATION_TRANSVERSE||orientation==ExifInterface.ORIENTATION_ROTATE_270;
        return swap?new int[]{options.outHeight,options.outWidth}:new int[]{options.outWidth,options.outHeight};
    }
    /** Copies only the luma crop. rowStride/pixelStride and buffer position are respected. */
    static Mat luma(ImageProxy image) {
        Rect crop=image.getCropRect();int w=crop.width(),h=crop.height();
        ImageProxy.PlaneProxy plane=image.getPlanes()[0];
        ByteBuffer buffer=plane.getBuffer().duplicate();int base=buffer.position();
        int rowStride=plane.getRowStride(),pixelStride=plane.getPixelStride();
        byte[] pixels=new byte[w*h];
        for(int y=0;y<h;y++) {
            int offset=base+(crop.top+y)*rowStride+crop.left*pixelStride;
            if(pixelStride==1) {buffer.position(offset);buffer.get(pixels,y*w,w);}
            else for(int x=0;x<w;x++)pixels[y*w+x]=buffer.get(offset+x*pixelStride);
        }
        Mat source=new Mat(h,w,CvType.CV_8UC1);source.put(0,0,pixels);
        int rotation=image.getImageInfo().getRotationDegrees();
        if(rotation==0)return source;
        Mat rotated=new Mat();
        try {
            if(rotation==90)Core.rotate(source,rotated,Core.ROTATE_90_CLOCKWISE);
            else if(rotation==180)Core.rotate(source,rotated,Core.ROTATE_180);
            else if(rotation==270)Core.rotate(source,rotated,Core.ROTATE_90_COUNTERCLOCKWISE);
            else throw new IllegalArgumentException("Unsupported camera rotation");
            return rotated;
        } catch(RuntimeException e) {rotated.release();throw e;}
        finally {source.release();}
    }
    static Mat gray(Bitmap bitmap) {
        Mat rgba=new Mat(),gray=new Mat();
        try {Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);return gray;}
        catch(RuntimeException e){gray.release();throw e;}
        finally {rgba.release();}
    }
    /** Upright preview/export image capped at 2400 pixels. Original file remains unchanged. */
    static Bitmap upright(File file) throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Não foi possível abrir a foto.");
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>3000)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap decoded=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(decoded==null)throw new IOException("Foto inválida.");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try {orientation=new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,orientation);}
        catch(IOException ignored) { /* Some imported formats have no EXIF. */ }
        Matrix matrix=new Matrix();
        switch(orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;
        }
        Bitmap upright=matrix.isIdentity()?decoded:Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);
        if(upright!=decoded)decoded.recycle();
        int side=Math.max(upright.getWidth(),upright.getHeight());
        if(side>2400) {
            double s=2400./side;Bitmap small=Bitmap.createScaledBitmap(upright,
                    (int)Math.round(upright.getWidth()*s),(int)Math.round(upright.getHeight()*s),true);
            if(small!=upright)upright.recycle();upright=small;
        }
        return upright;
    }
}
