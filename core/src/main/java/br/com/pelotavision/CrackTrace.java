package br.com.pelotavision;
import org.opencv.core.*;import org.opencv.imgproc.Imgproc;
/** Heuristic localization of strong dark connected lines, applied only to RF-positive pellets. */
public final class CrackTrace {
 public byte[] locate(byte[] normalized){
  int n=CrackFeatures.N;if(normalized.length!=n*n)throw new IllegalArgumentException("Patch 96 x 96 necessário");
  Mat source=new Mat(n,n,CvType.CV_8UC1),bh=new Mat(),mask=new Mat(),labels=new Mat(),stats=new Mat(),centers=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(15,15));
  try{
   source.put(0,0,normalized);Imgproc.morphologyEx(source,bh,Imgproc.MORPH_BLACKHAT,kernel);byte[] values=new byte[n*n];bh.get(0,0,values);int inside=0;
   for(int y=0;y<n;y++)for(int x=0;x<n;x++){int i=y*n+x;boolean valid=Math.pow(x-(n-1)/2.,2)+Math.pow(y-(n-1)/2.,2)<Math.pow(.78*(n-1)/2.,2);if(valid)inside++;values[i]=(byte)(valid&&(values[i]&255)>=80?1:0);}
   mask.create(n,n,CvType.CV_8UC1);mask.put(0,0,values);int count=Imgproc.connectedComponentsWithStats(mask,labels,stats,centers,8,CvType.CV_32S);boolean[] accept=new boolean[count];
   for(int c=1;c<count;c++){double width=stats.get(c,Imgproc.CC_STAT_WIDTH)[0],height=stats.get(c,Imgproc.CC_STAT_HEIGHT)[0],area=stats.get(c,Imgproc.CC_STAT_AREA)[0],diag=Math.hypot(width,height);accept[c]=area>=12&&area<.23*inside&&diag>=.32*n&&Math.max(width,height)>=.26*n&&area/diag<10;}
   int[] lab=new int[n*n];labels.get(0,0,lab);byte[] marks=new byte[n*n];for(int i=0;i<marks.length;i++)if(accept[lab[i]])marks[i]=1;return marks;
  }finally{source.release();bh.release();mask.release();labels.release();stats.release();centers.release();kernel.release();}
 }
}
