package br.com.pelotavision;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;
/** Exactly the normalized 72-feature pipeline used by training/features.py. */
public final class CrackFeatures {
 public static final int N=96;
 private static final boolean[] INSIDE=new boolean[N*N];
 private static final int PIXELS;
 static {int n=0;for(int y=0;y<N;y++)for(int x=0;x<N;x++){double dx=x-(N-1)/2.,dy=y-(N-1)/2.;if(dx*dx+dy*dy<Math.pow(.78*(N-1)/2.,2)){INSIDE[y*N+x]=true;n++;}}PIXELS=n;}
 public static final class Value {
  public final float[] features;public final byte[] marks,normalized;public final double spread,mean;
  Value(float[] f,byte[] m,byte[] patch,double s,double l){features=f;marks=m;normalized=patch;spread=s;mean=l;}
 }
 private static int quantile(int[] hist,double q){double target=PIXELS*q;int sum=0;for(int i=0;i<256;i++){sum+=hist[i];if(sum>=target)return i;}return 255;}
 public Value extract(Mat gray,double x,double y,double radius){
  if(gray.empty()||gray.type()!=CvType.CV_8UC1||!Double.isFinite(radius)||radius<=0)throw new IllegalArgumentException("Recorte inválido");
  List<Mat> owned=new ArrayList<>();
  try {
   Mat transform=own(owned);transform.create(2,3,CvType.CV_64F);double step=2*radius/(N-1.);transform.put(0,0,new double[]{step,0,x-radius,0,step,y-radius});
   Mat raw=own(owned),norm=own(owned),bh=own(owned),mask=own(owned),labels=own(owned),stats=own(owned),centers=own(owned);
   Imgproc.warpAffine(gray,raw,transform,new Size(N,N),Imgproc.INTER_LINEAR|Imgproc.WARP_INVERSE_MAP,Core.BORDER_REFLECT_101,new Scalar(0));
   byte[] pixels=new byte[N*N];raw.get(0,0,pixels);int[] hist=new int[256];double light=0;
   for(int i=0;i<pixels.length;i++)if(INSIDE[i]){hist[pixels[i]&255]++;light+=pixels[i]&255;}
   int lo=quantile(hist,.05),hi=quantile(hist,.95),median=quantile(hist,.5);double spread=Math.max(16,hi-lo);
   byte fill=(byte)clamp(Math.rint((median-lo)*200/spread+25));
   for(int i=0;i<pixels.length;i++)pixels[i]=INSIDE[i]?(byte)clamp(Math.rint(((pixels[i]&255)-lo)*200/spread+25)):fill;
   norm.create(N,N,CvType.CV_8UC1);norm.put(0,0,pixels);Imgproc.GaussianBlur(norm,norm,new Size(3,3),.7);
   float[] f=new float[72];byte[] marks=new byte[N*N];int index=0;
   Mat closing=own(owned);closing.create(3,3,CvType.CV_8UC1);closing.setTo(new Scalar(1));
   for(int k:new int[]{5,9,15}){
    Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(k,k));owned.add(kernel);Imgproc.morphologyEx(norm,bh,Imgproc.MORPH_BLACKHAT,kernel);
    byte[] b=new byte[N*N];bh.get(0,0,b);Arrays.fill(hist,0);double total=0;
    for(int i=0;i<b.length;i++)if(INSIDE[i]){hist[b[i]&255]++;total+=b[i]&255;}
    f[index++]=(float)(total/PIXELS/255);f[index++]=quantile(hist,.95)/255f;f[index++]=quantile(hist,.99)/255f;
    for(int threshold:new int[]{10,20,30}){
     byte[] binary=new byte[N*N];for(int i=0;i<binary.length;i++)if(INSIDE[i]&&(b[i]&255)>=threshold)binary[i]=1;
     mask.create(N,N,CvType.CV_8UC1);mask.put(0,0,binary);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,closing);mask.get(0,0,binary);int binaryN=0;
     for(int i=0;i<binary.length;i++){if(!INSIDE[i])binary[i]=0;if(binary[i]!=0)binaryN++;}mask.put(0,0,binary);
     int count=Imgproc.connectedComponentsWithStats(mask,labels,stats,centers,8,CvType.CV_32S);int[] lab=new int[N*N];labels.get(0,0,lab);
     double[] a=new double[count],sx=new double[count],sy=new double[count],sxx=new double[count],syy=new double[count],sxy=new double[count],intensity=new double[count];
     for(int yy=0;yy<N;yy++)for(int xx=0;xx<N;xx++){int i=yy*N+xx,c=lab[i];if(c==0)continue;a[c]++;sx[c]+=xx;sy[c]+=yy;sxx[c]+=xx*xx;syy[c]+=yy*yy;sxy[c]+=xx*yy;intensity[c]+=b[i]&255;}
     double bestLen=0,bestArea=0,totalArea=0,components=0,bestElong=0,bestIntensity=0;boolean[] accept=new boolean[count];
     for(int c=1;c<count;c++){
      double area=a[c];if(area<6)continue;
      double vx=(sxx[c]-sx[c]*sx[c]/area)/(area-1),vy=(syy[c]-sy[c]*sy[c]/area)/(area-1),cov=(sxy[c]-sx[c]*sy[c]/area)/(area-1);
      double root=Math.sqrt(Math.max(0,(vx-vy)*(vx-vy)+4*cov*cov)),large=(vx+vy+root)/2,small=(vx+vy-root)/2;
      double length=Math.sqrt(Math.max(0,large)*12),elong=Math.sqrt((large+1)/(Math.max(0,small)+1)),thin=area/Math.max(1,length);
      if(elong<1.6||thin>9||area/PIXELS>.20)continue;
      bestLen=Math.max(bestLen,length/N);bestArea=Math.max(bestArea,area/PIXELS);totalArea+=area/PIXELS;components++;bestElong=Math.max(bestElong,Math.min(20,elong)/20);bestIntensity=Math.max(bestIntensity,intensity[c]/area/255);
      if(k==9&&threshold==20&&length>=N*.18&&thin<8)accept[c]=true;
     }
     f[index++]=(float)bestLen;f[index++]=(float)bestArea;f[index++]=(float)totalArea;f[index++]=(float)(Math.min(30,components)/30);f[index++]=(float)bestElong;f[index++]=(float)bestIntensity;f[index++]=(float)binaryN/PIXELS;
     for(int i=0;i<marks.length;i++)if(accept[lab[i]])marks[i]=1;
    }
   }
   byte[] patch=new byte[N*N];norm.get(0,0,patch);return new Value(f,marks,patch,hi-lo,light/PIXELS);
  }finally{for(Mat m:owned)m.release();}
 }
 private static int clamp(double v){return (int)Math.max(0,Math.min(255,v));}
 private static Mat own(List<Mat> mats){Mat m=new Mat();mats.add(m);return m;}
}
