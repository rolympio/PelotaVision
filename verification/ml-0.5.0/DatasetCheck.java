import br.com.pelotavision.*;import java.nio.file.*;import java.io.*;import java.util.*;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;
/** Desktop JNI uses the exact app detector, model and trace code. No Android camera performance claim. */
public class DatasetCheck{
 public static void main(String[] args)throws Exception{
  nu.pattern.OpenCV.loadLocally();Core.setNumThreads(2);CrackModel model;try(InputStream in=new FileInputStream(args[0])){model=new CrackModel(in);}Path data=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
  try(PrintWriter csv=new PrintWriter(Files.newBufferedWriter(out.resolve("detections.csv")))){
   csv.println("scene;lighting;detector;id;x;y;radius;partial;review;crack_score;crack_status;duration_ms;method");
   for(String scene:new String[]{"brown40","gray42","gray20","dense","cluster","spaced","paired40"})for(int light=0;light<6;light++){
    if(args.length>3&&light>0)continue;
    Mat gray=Imgcodecs.imread(data.resolve(scene+"_light"+light+".png").toString(),0);
    for(int kind=(args.length>3?1:0);kind<2;kind++){
     PelletDetector.Result r=kind==0?new PelletDetector().detect(gray,PelletDetector.Parameters.automatic(),1024,12):new AdaptivePelletDetector().detect(gray,PelletDetector.Parameters.automatic(),1024,12);
     CrackAnalysis c=kind==1&&light==0?new CrackDetector(model).detect(gray,r,50):null;int id=0;
     StringBuilder json=new StringBuilder("{\"width\":"+gray.cols()+",\"height\":"+gray.rows()+",\"method\":\""+r.method+"\",\"detections\":[");
     for(PelletDetector.Detection d:r.circles){id++;CrackAnalysis.Entry e=c==null?null:c.entries.get(id-1);csv.printf(Locale.ROOT,"%s;%d;%s;%d;%.8f;%.8f;%.8f;%s;%s;%s;%s;%d;%s%n",scene,light,kind==0?"legacy":"adaptive",id,d.x,d.y,d.radius,d.partial,d.needsReview,e==null?"":Double.toString(e.modelScore),e==null?"":e.status.name(),r.durationMs,r.method);
      if(id>1)json.append(',');json.append(String.format(Locale.ROOT,"{\"id\":%d,\"x\":%.8f,\"y\":%.8f,\"radius\":%.8f,\"area\":%.8f,\"roundness\":%.8f,\"partial\":%s,\"review\":%s",id,d.x,d.y,d.radius,d.area,d.roundness,d.partial,d.needsReview));
      if(e!=null){json.append(",\"score\":"+e.modelScore+",\"status\":\""+e.status.name()+"\",\"runs\":[");float[] runs=e.markedRuns();for(int j=0;j<runs.length;j++){if(j>0)json.append(',');json.append(runs[j]);}json.append(']');}json.append('}');
     }
     json.append("]}");if(kind==1&&light==0)Files.writeString(out.resolve(scene+"_analysis.json"),json.toString());
    }
    gray.release();System.out.println(scene+" lighting="+light);
   }
  }
  Mat blank=Mat.zeros(300,400,CvType.CV_8UC1);if(new AdaptivePelletDetector().detect(blank,PelletDetector.Parameters.automatic(),800,12).circles.size()!=0)throw new AssertionError("Blank frame creates circles");blank.release();
 }
}
