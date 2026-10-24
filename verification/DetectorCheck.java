import br.com.pelotavision.PelletDetector;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgcodecs.Imgcodecs;
import java.util.*;
public class DetectorCheck {
    public static void main(String[] args){
        nu.pattern.OpenCV.loadLocally();Core.setNumThreads(2);
        PelletDetector detector=new PelletDetector();
        PelletDetector.Parameters cfg=new PelletDetector.Parameters(.045,.1125,.045);
        Mat blank=Mat.zeros(300,400,CvType.CV_8UC1);
        if(detector.detect(blank,cfg,512,12).circles.size()!=0)throw new AssertionError("Blank image");blank.release();
        Mat synthetic=new Mat(300,400,CvType.CV_8UC1,new Scalar(30));
        double[][] points={{85,85},{150,85},{240,185},{305,185}};
        for(double[] p:points)Imgproc.circle(synthetic,new Point(p[0],p[1]),33,new Scalar(180),-1);
        PelletDetector.Result result=detector.detect(synthetic,cfg,512,12);
        if(result.circles.size()!=4)throw new AssertionError("Expected four circles, got "+result.circles.size());
        for(double[] p:points){List<PelletDetector.Detection> found=new ArrayList<>();for(PelletDetector.Detection d:result.circles)if(Math.hypot(d.x-p[0],d.y-p[1])<6)found.add(d);
            if(found.size()!=1||Math.abs(found.get(0).radius-33)>5)throw new AssertionError("Position/radius");}
        synthetic.release();System.out.println("PASS: blank and four touching disks; positions and radii.");
        for(int i=0;i<args.length;i++) {
            Mat img=Imgcodecs.imread(args[i],Imgcodecs.IMREAD_GRAYSCALE);if(img.empty())throw new AssertionError("Image unavailable");
            int[] margins=i==0?new int[]{0,146,1536,1020}:i==1?new int[]{5,204,1529,1135}:new int[]{0,0,img.cols(),img.rows()};
            Mat crop=img.submat(new Rect(margins[0],margins[1],margins[2],margins[3]));
            PelletDetector.Parameters p=i==0?new PelletDetector.Parameters(32./1536,90./1536,.045):i==1?new PelletDetector.Parameters(60./1529,145./1529,.045):new PelletDetector.Parameters(20./1536,58./1536,.045);
            PelletDetector.Result r=detector.detect(crop,p,768,12);
            for(PelletDetector.Detection d:r.circles)if(!Double.isFinite(d.radius)||d.radius<=0||d.x<0||d.y<0||d.x>=crop.cols()||d.y>=crop.rows())throw new AssertionError("Invalid result geometry");
            System.out.println(args[i]+": "+r.circles.size()+" circles in "+r.durationMs+"ms (desktop, not phone)");
            crop.release();img.release();
        }
    }
}
