import br.com.pelotavision.*;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;import java.util.*;import java.nio.file.*;
class Diagnose {
 static String num(double n){return Double.isFinite(n)?Double.toString(n):"null";}
 static void save(PelletDetector.Result r,DetectionTuner.Option o,Path file)throws Exception{
  StringBuilder j=new StringBuilder("{\"option\":\""+o.id+"\",\"width\":"+r.width+",\"height\":"+r.height+",\"method\":\""+r.method+"\",\"objects\":[");for(int i=0;i<r.circles.size();i++){PelletDetector.Detection d=r.circles.get(i);if(i>0)j.append(',');j.append(String.format(Locale.ROOT,"{\"x\":%.9f,\"y\":%.9f,\"a\":%.9f,\"b\":%.9f,\"radius\":%.9f,\"angle\":%.9f,\"partial\":%s,\"review\":%s,\"fitted\":%s,\"support\":%s,\"rms\":%s,\"roundness\":%.9f}",d.x,d.y,d.semiMajor,d.semiMinor,d.radius,d.angleDeg,d.partial,d.needsReview,d.ellipseFitted,num(d.fitSupport),num(d.fitRms),d.roundness));}j.append("]}");Files.writeString(file,j.toString());
 }
 public static void main(String[] args)throws Exception{
  nu.pattern.OpenCV.loadLocally();Core.setNumThreads(2);Path output=Path.of(args[1]);Files.createDirectories(output);Mat original=Imgcodecs.imread(args[0],0);
  for(int size:new int[]{1536,600}){Mat gray=new Mat();Imgproc.resize(original,gray,new Size(size,Math.round(original.rows()*size/(double)original.cols())),0,0,Imgproc.INTER_AREA);Imgcodecs.imwrite(output.resolve("input_"+size+".png").toString(),gray);List<DetectionTuner.Option> options=DetectionTuner.options(new DetectionTuner.Option(3,75,false));for(int p=0;p<4;p++)for(int s:new int[]{55,75,90})options.add(new DetectionTuner.Option(p,s,true));options.add(new DetectionTuner.Option(1,74,true));
   for(DetectionTuner.Option option:options){long start=System.nanoTime();PelletDetector.Result r=DetectionTuner.detect(gray,option);DetectionAssessment.Metrics m=DetectionAssessment.measure(r,new DetectionAssessment.Context(null,size,gray.rows(),null,5));save(r,option,output.resolve(size+"_"+option.id+".json"));System.out.printf(Locale.ROOT,"%d,%s,count=%d,valid=%d,quality=%.4f,overlap=%d,time=%d%n",size,option.id,m.total,m.valid,m.fitQuality,m.overlapping,(System.nanoTime()-start)/1000000);}gray.release();
  }original.release();
 }
}
