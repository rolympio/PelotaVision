import br.com.pelotavision.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;
public class CrackDetectorCheck {
 public static void main(String[] args){nu.pattern.OpenCV.loadLocally();Mat image=new Mat(180,600,CvType.CV_8UC1,new Scalar(30));List<PelletDetector.Detection> pellets=new ArrayList<>();
  for(int i=0;i<5;i++){int x=65+i*110;Imgproc.circle(image,new Point(x,90),45,new Scalar(180),-1);pellets.add(new PelletDetector.Detection(x,90,45,Math.PI*45*45,1,i==4,false));}
  Imgproc.line(image,new Point(40,80),new Point(85,98),new Scalar(35),2);
  Imgproc.line(image,new Point(150,78),new Point(192,78),new Scalar(35),2);Imgproc.line(image,new Point(150,100),new Point(192,100),new Scalar(35),2);
  Imgproc.circle(image,new Point(275,85),4,new Scalar(35),-1);Imgproc.circle(image,new Point(297,99),4,new Scalar(35),-1);
  PelletDetector.Result p=new PelletDetector.Result(pellets,600,180,5,0,false);CrackAnalysis r=new CrackDetector().detect(image,p,50);
  if(r.count(CrackAnalysis.Status.POSSIBLE)!=2||r.count(CrackAnalysis.Status.NO_INDICATION)!=2||r.count(CrackAnalysis.Status.INCONCLUSIVE)!=1)throw new AssertionError("Synthetic crack/pores/plain/partial classification: "+r.count(CrackAnalysis.Status.POSSIBLE));
  for(int i=0;i<r.entries.size();i++)for(float value:r.entries.get(i).markedRuns())if(!Float.isFinite(value))throw new AssertionError("Invalid mark geometry");
  Mat blank=new Mat(180,600,CvType.CV_8UC1,new Scalar(180));CrackAnalysis b=new CrackDetector().detect(blank,p,50);if(b.count(CrackAnalysis.Status.POSSIBLE)!=0)throw new AssertionError("Uniform surface creates crack");
  Mat large=new Mat();Imgproc.resize(image,large,new Size(2400,720),0,0,Imgproc.INTER_NEAREST);List<PelletDetector.Detection> scaled=new ArrayList<>();for(PelletDetector.Detection d:pellets)scaled.add(new PelletDetector.Detection(d.x*4,d.y*4,d.radius*4,d.area*16,1,d.partial,d.needsReview));
  CrackAnalysis resized=new CrackDetector().detect(large,new PelletDetector.Result(scaled,2400,720,5,0,false),50);if(resized.count(CrackAnalysis.Status.POSSIBLE)!=2)throw new AssertionError("Resize loses cracks");
  image.release();blank.release();large.release();System.out.println("PASS: one crack, multiple cracks counted once, round pores rejected, smooth surface, partial pellet, finite marks and resized photo.");
 }
}
