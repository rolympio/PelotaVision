package br.com.pelotavision;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;
/** Experimental dark-line screening inside each detected pellet; no trained classifier. */
final class HeuristicCrackDetector {
 public CrackAnalysis detect(Mat gray,PelletDetector.Result pellets,int sensitivity){
  if(gray.empty()||gray.type()!=CvType.CV_8UC1||gray.cols()!=pellets.width||gray.rows()!=pellets.height)throw new IllegalArgumentException("Foto e detecções incompatíveis.");
  sensitivity=Math.max(0,Math.min(100,sensitivity));long start=System.nanoTime();
  double factor=Math.min(1,1800./Math.max(gray.cols(),gray.rows()));int width=(int)Math.round(gray.cols()*factor),height=(int)Math.round(gray.rows()*factor);
  Mat work=new Mat();Imgproc.resize(gray,work,new Size(width,height),0,0,Imgproc.INTER_AREA);
  double sx=(double)width/gray.cols(),sy=(double)height/gray.rows(),radScale=Math.sqrt(sx*sy);
  List<CrackAnalysis.Entry> entries=new ArrayList<>();int id=0;
  try{for(PelletDetector.Detection d:pellets.circles){
   id++;double cx=d.x*sx,cy=d.y*sy,r=d.radius*radScale;
   int x0=Math.max(0,(int)(cx-r)),y0=Math.max(0,(int)(cy-r));
   int x1=Math.min(width,(int)(cx+r)+1),y1=Math.min(height,(int)(cy+r)+1),w=x1-x0,h=y1-y0;
   if(w<3||h<3||r<10){entries.add(new CrackAnalysis.Entry(id,CrackAnalysis.Status.INCONCLUSIVE,new float[0]));continue;}
   Mat crop=work.submat(new Rect(x0,y0,w,h)),smooth=new Mat(),blackhat=new Mat(),mask=new Mat(),labels=new Mat(),stats=new Mat(),centroids=new Mat();
   Mat kernel=null,closing=null;
   try{
    Imgproc.GaussianBlur(crop,smooth,new Size(3,3),.7);
    int k=Math.max(5,(int)Math.round(r*.17)|1);kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(k,k));
    Imgproc.morphologyEx(smooth,blackhat,Imgproc.MORPH_BLACKHAT,kernel);
    byte[] bh=new byte[w*h],brightness=new byte[w*h],inside=new byte[w*h];blackhat.get(0,0,bh);smooth.get(0,0,brightness);
    List<PelletDetector.Detection> neighbors=new ArrayList<>();for(PelletDetector.Detection other:pellets.circles)if(other!=d&&Math.hypot(other.x-d.x,other.y-d.y)<other.radius+d.radius)neighbors.add(other);
    int n=0;double sum=0,sum2=0,lightSum=0;int[] lightHistogram=new int[256];
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
     int index=y*w+x;double dx=x+x0-cx,dy=y+y0-cy,normalized=(dx*dx+dy*dy)/(r*r);boolean valid=normalized<.88*.88;
     if(valid)for(PelletDetector.Detection other:neighbors){double ox=(x+x0)/sx-other.x,oy=(y+y0)/sy-other.y;if((ox*ox+oy*oy)/(other.radius*other.radius)<normalized){valid=false;break;}}
     if(valid){inside[index]=1;n++;int response=bh[index]&255,l=brightness[index]&255;sum+=response;sum2+=(double)response*response;lightSum+=l;lightHistogram[l]++;}
    }
    int median=0,acc=0;for(;median<255;median++){acc+=lightHistogram[median];if(acc>=n/2)break;}
    double mean=n==0?0:sum/n,std=n==0?0:Math.sqrt(Math.max(0,sum2/n-mean*mean));
    double threshold=Math.max(5,9+(50-sensitivity)*.08);threshold=Math.max(threshold,mean+(2+(50-sensitivity)*.012)*std);
    byte[] binary=new byte[w*h];for(int j=0;j<binary.length;j++){double dx=j%w+x0-cx,dy=j/w+y0-cy;boolean center=dx*dx+dy*dy<r*r*.72*.72;if(inside[j]!=0&&(bh[j]&255)>=threshold&&(center||(brightness[j]&255)>median*.55))binary[j]=(byte)255;}
    mask.create(h,w,CvType.CV_8UC1);mask.put(0,0,binary);closing=Mat.ones(3,3,CvType.CV_8UC1);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,closing);
    // Closing may extend into a neighboring pellet; clip again after morphology.
    mask.get(0,0,binary);for(int j=0;j<binary.length;j++)if(inside[j]==0)binary[j]=0;mask.put(0,0,binary);
    int count=Imgproc.connectedComponentsWithStats(mask,labels,stats,centroids,8,CvType.CV_32S);int[] component=new int[w*h];labels.get(0,0,component);
    double[] nx=new double[count],sx1=new double[count],sy1=new double[count],sxx=new double[count],syy=new double[count],sxy=new double[count];
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=component[y*w+x];if(c==0)continue;nx[c]++;sx1[c]+=x;sy1[c]+=y;sxx[c]+=(double)x*x;syy[c]+=(double)y*y;sxy[c]+=(double)x*y;}
    boolean[] accept=new boolean[count];boolean found=false;
    for(int c=1;c<count;c++){
     double area=nx[c];if(area<Math.max(9,r*.18))continue;
     double vx=(sxx[c]-sx1[c]*sx1[c]/area)/(area-1),vy=(syy[c]-sy1[c]*sy1[c]/area)/(area-1),cov=(sxy[c]-sx1[c]*sy1[c]/area)/(area-1);
     double root=Math.sqrt(Math.max(0,(vx-vy)*(vx-vy)+4*cov*cov)),large=(vx+vy+root)/2,small=(vx+vy-root)/2;
     double length=Math.sqrt(Math.max(0,large)*12),thin=area/Math.max(length,1),elongation=Math.sqrt((large+1)/(Math.max(0,small)+1));
     if(length<Math.max(10,r*.32)||elongation<2||thin>Math.max(6,r*.18)||area>Math.PI*r*r*.09)continue;
     accept[c]=true;found=true;
    }
    List<Float> runs=new ArrayList<>();for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(accept[component[y*w+x]]){int first=x;while(x+1<w&&accept[component[y*w+x+1]])x++;runs.add((float)((first+x0)/sx));runs.add((float)((y+y0)/sy));runs.add((float)((x+x0+1)/sx));runs.add((float)((y+y0)/sy));}
    float[] marks=new float[runs.size()];for(int j=0;j<marks.length;j++)marks[j]=runs.get(j);
    boolean uncertain=d.partial||d.needsReview||r<15||n<80||lightSum/Math.max(1,n)<25;
    CrackAnalysis.Status status=found?CrackAnalysis.Status.POSSIBLE:uncertain?CrackAnalysis.Status.INCONCLUSIVE:CrackAnalysis.Status.NO_INDICATION;
    entries.add(new CrackAnalysis.Entry(id,status,marks));
   }finally{crop.release();smooth.release();blackhat.release();mask.release();labels.release();stats.release();centroids.release();if(kernel!=null)kernel.release();if(closing!=null)closing.release();}
  }}finally{work.release();}
  return new CrackAnalysis(pellets.width,pellets.height,sensitivity,(System.nanoTime()-start)/1_000_000,entries);
 }
}
