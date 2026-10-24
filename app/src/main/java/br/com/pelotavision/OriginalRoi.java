package br.com.pelotavision;
import android.graphics.*;import androidx.exifinterface.media.ExifInterface;import java.io.*;import org.opencv.core.Mat;
/** Region-decodes the original file before preview downsampling; all eight EXIF orientations are supported. */
final class OriginalRoi {
 static final long MAX_PIXELS=8_000_000;
 static final class Input implements AutoCloseable {final Mat gray;final DetailGeometry geometry;Input(Mat g,DetailGeometry m){gray=g;geometry=m;}public void close(){gray.release();}}
 static Matrix uprightMatrix(int orientation,int w,int h){Matrix m=new Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}RectF bounds=new RectF(0,0,w,h);m.mapRect(bounds);m.postTranslate(-bounds.left,-bounds.top);return m;}
 static final class Decoded implements AutoCloseable {final Bitmap bitmap;final DetailGeometry geometry;Decoded(Bitmap b,DetailGeometry g){bitmap=b;geometry=g;}public void close(){bitmap.recycle();}}
 static Input decode(File file,int pw,int ph,InterestRegion roi)throws IOException {try(Decoded decoded=decodeBitmap(file,pw,ph,roi)){Mat g=ImageCodec.gray(decoded.bitmap);try{decoded.geometry.mask(g);return new Input(g,decoded.geometry);}catch(RuntimeException|OutOfMemoryError e){g.release();throw e;}}}
 static Decoded decodeBitmap(File file,int pw,int ph,InterestRegion roi)throws IOException {
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.toString(),bounds);if(bounds.outWidth<1||bounds.outHeight<1)throw new IOException("Foto original inválida.");
  int orientation=1;try{orientation=new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
  int[] original=ImageCodec.originalUprightSize(file);Matrix matrix=uprightMatrix(orientation,bounds.outWidth,bounds.outHeight),inverse=new Matrix();matrix.invert(inverse);
  RectF upright=new RectF((float)((roi==null?0:roi.left)*original[0]/pw),(float)((roi==null?0:roi.top)*original[1]/ph),(float)((roi==null?pw:roi.right)*original[0]/pw),(float)((roi==null?ph:roi.bottom)*original[1]/ph));RectF raw=new RectF(upright);inverse.mapRect(raw);
  Rect request=new Rect(Math.max(0,(int)Math.floor(raw.left+1e-4)),Math.max(0,(int)Math.floor(raw.top+1e-4)),Math.min(bounds.outWidth,(int)Math.ceil(raw.right-1e-4)),Math.min(bounds.outHeight,(int)Math.ceil(raw.bottom-1e-4)));if(request.isEmpty())throw new IOException("Área original vazia.");
  int sample=1;while(((long)request.width()*request.height())/sample/sample>MAX_PIXELS)sample*=2;
  BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
  BitmapRegionDecoder decoder=BitmapRegionDecoder.newInstance(file.toString(),false);Bitmap decoded=null,rotated=null;boolean success=false;
  try{decoded=decoder.decodeRegion(request,options);if(decoded==null)throw new IOException("Não foi possível ler a ROI original.");rotated=Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);RectF actual=new RectF(request);matrix.mapRect(actual);
   DetailGeometry map=new DetailGeometry(rotated.getWidth(),rotated.getHeight(),pw,ph,actual.width()/rotated.getWidth()*pw/original[0],actual.height()/rotated.getHeight()*ph/original[1],actual.left*pw/original[0],actual.top*ph/original[1],sample,roi);
   success=true;return new Decoded(rotated,map);
  }finally{if(decoded!=null&&decoded!=rotated)decoded.recycle();if(!success&&rotated!=null)rotated.recycle();decoder.recycle();}
 }
}
