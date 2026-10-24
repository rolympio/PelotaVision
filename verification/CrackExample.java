import br.com.pelotavision.PelletDetector;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import java.util.*;
public class CrackExample {
 public static void main(String[] args)throws Exception{
  nu.pattern.OpenCV.loadLocally();Core.setNumThreads(2);
  Mat gray=Imgcodecs.imread(args[0],Imgcodecs.IMREAD_GRAYSCALE);
  PelletDetector.Result r=new PelletDetector().detect(gray,new PelletDetector.Parameters(.021,.060,.045),1024,12);
  StringBuilder s=new StringBuilder("{\"width\":"+r.width+",\"height\":"+r.height+",\"circles\":[");int i=0;
  for(PelletDetector.Detection d:r.circles){if(i++>0)s.append(',');s.append(String.format(Locale.ROOT,"{\"id\":%d,\"x\":%.6f,\"y\":%.6f,\"radius\":%.6f,\"area\":%.6f,\"roundness\":%.6f,\"partial\":%s,\"review\":%s}",i,d.x,d.y,d.radius,d.area,d.roundness,d.partial,d.needsReview));}
  s.append("],\"cracks\":[");br.com.pelotavision.CrackAnalysis cracks=new br.com.pelotavision.CrackDetector().detect(gray,r,50);int j=0;
  for(br.com.pelotavision.CrackAnalysis.Entry entry:cracks.entries){if(j++>0)s.append(',');s.append("{\"id\":"+entry.pelletId+",\"status\":\""+entry.status+"\",\"runs\":[");float[] runs=entry.markedRuns();for(int n=0;n<runs.length;n++){if(n>0)s.append(',');s.append(runs[n]);}s.append("]}");}s.append("]}");
  System.out.println("Candidates: "+cracks.count(br.com.pelotavision.CrackAnalysis.Status.POSSIBLE)+"; no indication: "+cracks.count(br.com.pelotavision.CrackAnalysis.Status.NO_INDICATION)+"; inconclusive: "+cracks.count(br.com.pelotavision.CrackAnalysis.Status.INCONCLUSIVE));java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]),s.toString());gray.release();System.out.println(r.circles.size()+" pelotas");
 }
}
