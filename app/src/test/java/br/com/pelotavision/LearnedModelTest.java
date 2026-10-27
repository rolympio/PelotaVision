package br.com.pelotavision;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.graphics.*;import java.nio.file.*;import java.io.*;import java.util.*;import org.json.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LearnedModelTest {
 private File dir(){return new File(System.getProperty("pelotavision.validationDir"),"ml-0.5.0");}
 @Test public void packagedForestMatchesAllTrainingReferenceVectors()throws Exception{
  CrackModel model;try(InputStream in=RuntimeEnvironment.getApplication().getAssets().open("crack_forest.pvrf")){model=new CrackModel(in);}
  int n=0;for(String line:Files.readAllLines(new File(new File(System.getProperty("pelotavision.validationDir"),"ml-0.15.5"),"reference_vectors.csv").toPath())){String[] a=line.split(",");float[] f=new float[72];for(int j=0;j<72;j++)f[j]=Float.parseFloat(a[j]);assertEquals(Double.parseDouble(a[72]),model.score(f),1e-12);n++;}assertEquals(209,n);
  try{new CrackModel(new ByteArrayInputStream(new byte[16]));fail("Corrupt model accepted");}catch(IOException expected){}
 }
 @Test public void learnedScoreAndDetectorMethodSurviveRoiReviewAndExport()throws Exception{
  CrackAnalysis a=new CrackAnalysis(400,300,50,3,Arrays.asList(new CrackAnalysis.Entry(1,CrackAnalysis.Status.POSSIBLE,new float[]{90,90,110,90},.83,CrackModel.ID)));
  CrackAnalysis b=a.review(0,CrackAnalysis.Status.NO_INDICATION).select(Arrays.asList(0));assertEquals(.83,b.entries.get(0).modelScore,0);assertTrue(b.entries.get(0).manuallyReviewed);assertEquals(CrackAnalysis.Status.POSSIBLE,b.review(0,null).entries.get(0).status);
  JSONObject j=ResultSaver.crackJson(b);assertTrue(j.getBoolean("trained_model"));assertEquals(CrackModel.ID,j.getString("method"));assertEquals(.83,j.getJSONArray("pellets").getJSONObject(0).getDouble("model_score"),0);
  PelletDetector.Result r=new PelletDetector.Result(Arrays.asList(new PelletDetector.Detection(100,100,30,Math.PI*900,1,false,false)),400,300,1,2,false,"gradient_circle_CLAHE_v1");assertEquals(r.method,InterestRegion.select(r,Arrays.asList(0)).method);
  android.content.Context context=RuntimeEnvironment.getApplication();File root=new File(context.getCacheDir(),"learned_export");root.mkdirs();CrackExportTest.FakeMediaStore provider=new CrackExportTest.FakeMediaStore(root);android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";provider.attachInfo(context,info);org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider);
  Bitmap photo=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.GRAY);File source=new File(root,"input.png");try(FileOutputStream out=new FileOutputStream(source)){photo.compress(Bitmap.CompressFormat.PNG,100,out);}
  ResultSaver.SavedResult saved=ResultSaver.save(context,photo,source,"image/png",r,PelletDetector.Parameters.automatic(),a,null);JSONObject result=new JSONObject(new String(Files.readAllBytes(new File(root,saved.base+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(r.method,result.getString("method"));assertTrue(result.getJSONObject("histogram").getString("diameter_method").startsWith("fitted_circle"));assertEquals(.83,result.getJSONArray("detections").getJSONObject(0).getDouble("crack_score"),0);
  String[] row=Files.readAllLines(new File(root,saved.base+".csv").toPath()).get(1).split(";",-1);assertEquals(32,row.length);assertEquals("0.830000",row[14]);assertEquals(CrackModel.ID,row[15]);photo.recycle();
 }
 @Test public void sevenSuppliedScenesRenderOriginalMarkersAndHistogramUsingAppComposer()throws Exception{
  for(String scene:new String[]{"brown40","gray42","gray20","dense","cluster","spaced","paired40"}){
   JSONObject input=new JSONObject(new String(Files.readAllBytes(new File(dir(),scene+"_analysis.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));Bitmap photo=BitmapFactory.decodeFile(new File(dir(),"inputs/"+scene+".png").getAbsolutePath());assertNotNull(photo);
   List<PelletDetector.Detection> circles=new ArrayList<>();List<CrackAnalysis.Entry> entries=new ArrayList<>();JSONArray ds=input.getJSONArray("detections");
   for(int i=0;i<ds.length();i++){JSONObject d=ds.getJSONObject(i);circles.add(new PelletDetector.Detection(d.getDouble("x"),d.getDouble("y"),d.getDouble("radius"),d.getDouble("area"),d.getDouble("roundness"),d.getBoolean("partial"),d.getBoolean("review")));JSONArray v=d.getJSONArray("runs");float[] runs=new float[v.length()];for(int j=0;j<v.length();j++)runs[j]=(float)v.getDouble(j);entries.add(new CrackAnalysis.Entry(i+1,CrackAnalysis.Status.valueOf(d.getString("status")),runs,d.getDouble("score"),CrackModel.ID));}
   PelletDetector.Result result=new PelletDetector.Result(circles,photo.getWidth(),photo.getHeight(),circles.size(),0,false,input.getString("method"));CrackAnalysis cracks=new CrackAnalysis(photo.getWidth(),photo.getHeight(),50,0,entries);Bitmap composed=PhotoComposer.compose(photo,result,cracks,null);PhotoComposer.Layout layout=new PhotoComposer.Layout(photo.getWidth(),photo.getHeight());
   for(int y=0;y<photo.getHeight();y+=19)for(int x=0;x<photo.getWidth();x+=19)assertEquals(photo.getPixel(x,y),composed.getPixel(x,layout.originalTop+y));assertEquals(Color.rgb(16,26,35),composed.getPixel(2,layout.histogramTop+2));
   try(FileOutputStream out=new FileOutputStream(new File(dir(),scene+"_report.png"))){assertTrue(composed.compress(Bitmap.CompressFormat.PNG,100,out));}
   composed.recycle();photo.recycle();
  }
 }
}
