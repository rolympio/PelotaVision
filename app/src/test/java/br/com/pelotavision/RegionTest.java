package br.com.pelotavision;
import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import android.graphics.*;
import android.view.*;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import org.json.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RegionTest {
 static PelletDetector.Detection d(double x,double y,double r){return new PelletDetector.Detection(x,y,r,Math.PI*r*r,1,false,false);}
 static PelletDetector.Result all(){return new PelletDetector.Result(Arrays.asList(d(100,100,20),d(200,100,40),d(320,200,20)),400,300,3,1,false);}
 @Test public void wholeCircumferenceRuleForRectangleAndCircle(){
  InterestRegion r=new InterestRegion(InterestRegion.Shape.RECTANGLE,70,60,240,150);assertEquals(Arrays.asList(0,1),r.indices(all()));assertFalse(r.includes(d(80,100,20)));
  InterestRegion c=new InterestRegion(InterestRegion.Shape.CIRCLE,50,50,150,150);assertTrue(c.includes(d(100,100,50)));assertFalse(c.includes(d(130,130,10)));assertTrue(c.contains(130,130));
 }
 @Test public void allRectangleHandlesResizeAndClamp(){
  InterestRegion r=new InterestRegion(InterestRegion.Shape.RECTANGLE,100,80,300,220);
  for(int i=0;i<8;i++){InterestRegion e=r.resized(i,15,12,400,300,30);assertTrue(Math.abs(e.left-r.left)+Math.abs(e.right-r.right)+Math.abs(e.top-r.top)+Math.abs(e.bottom-r.bottom)>0);}
  InterestRegion clamped=r.moved(500,-500,400,300);assertEquals(400,clamped.right,1e-8);assertEquals(0,clamped.top,1e-8);
  InterestRegion tiny=r.resized(0,900,900,400,300,30);assertEquals(30,tiny.width(),1e-8);assertEquals(30,tiny.height(),1e-8);
 }
 @Test public void circleStaysRoundFromEverySideAndPinch(){
  InterestRegion c=new InterestRegion(InterestRegion.Shape.CIRCLE,100,50,300,250);
  for(int i:new int[]{1,3,5,7}){InterestRegion e=c.resized(i,30,-25,400,300,20);assertEquals(e.width(),e.height(),1e-8);assertTrue(e.left>=0&&e.top>=0&&e.right<=400&&e.bottom<=300);}
  InterestRegion e=c.scaled(9,400,300,20);assertEquals(e.width(),e.height(),1e-8);assertEquals(0,e.top,1e-8);assertEquals(300,e.bottom,1e-8);
 }
 private static void event(RegionEditorView v,int action,float x,float y){MotionEvent e=MotionEvent.obtain(0,20,action,x,y,0);v.onTouchEvent(e);e.recycle();}
 @Test public void fingerDragUsesPhotoCoordinatesThroughLetterbox(){
  Bitmap photo=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);RegionEditorView v=new RegionEditorView(RuntimeEnvironment.getApplication(),photo,new InterestRegion(InterestRegion.Shape.RECTANGLE,100,75,300,225));v.layout(0,0,800,800);
  assertEquals(2,v.scale(),1e-8);assertEquals(100,v.top(),1e-8);
  event(v,MotionEvent.ACTION_DOWN,400,400);event(v,MotionEvent.ACTION_MOVE,440,420);event(v,MotionEvent.ACTION_UP,440,420);
  assertEquals(120,v.region().left,1e-8);assertEquals(85,v.region().top,1e-8);
  event(v,MotionEvent.ACTION_DOWN,240,270);event(v,MotionEvent.ACTION_MOVE,200,250);event(v,MotionEvent.ACTION_UP,200,250);
  assertEquals(100,v.region().left,1e-8);assertEquals(75,v.region().top,1e-8);assertEquals(320,v.region().right,1e-8);photo.recycle();
 }
 @Test public void filteringAndRevisitingPreserveCrackReviewAndDiameters(){
  CrackAnalysis a=new CrackAnalysis(400,300,50,1,Arrays.asList(new CrackAnalysis.Entry(1,CrackAnalysis.Status.POSSIBLE,new float[]{90,90,110,90}),new CrackAnalysis.Entry(2,CrackAnalysis.Status.NO_INDICATION,new float[0]),new CrackAnalysis.Entry(3,CrackAnalysis.Status.INCONCLUSIVE,new float[0]))).review(2,CrackAnalysis.Status.POSSIBLE);
  List<Integer> ids=Arrays.asList(2);assertEquals(20,InterestRegion.select(all(),ids).circles.get(0).radius,0);CrackAnalysis selected=a.select(ids);assertTrue(selected.entries.get(0).manuallyReviewed);assertEquals(1,selected.entries.get(0).pelletId);assertEquals(1,selected.count(CrackAnalysis.Status.POSSIBLE));assertEquals(3,a.entries.size());
 }
 @Test public void estimatedScaleDoesNotChangeWhenPopulationChanges(){
  List<Granulometry.Item> items=Arrays.asList(new Granulometry.Item(80,false,false),new Granulometry.Item(160,false,false));
  Granulometry.Distribution a=Granulometry.compute(items,Granulometry.Settings.estimatedMean(12,1,true,false),800,600,400,300);
  Granulometry.Distribution b=Granulometry.compute(Collections.singletonList(items.get(0)),a.settings,800,600,400,300);
  assertEquals(a.analyzedPixelsPerMm,b.analyzedPixelsPerMm,0);assertEquals(8,b.meanMm,1e-8);assertTrue(b.settings.estimated());
 }
 @Test @SuppressWarnings({"unchecked","rawtypes"}) public void activityRoiUpdatesCountAndHistogramAndCanBeRemoved()throws Exception {
  try(org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
   MainActivity activity=controller.get();Class mode=Class.forName("br.com.pelotavision.MainActivity$Mode");set(activity,"mode",Enum.valueOf(mode,"PHOTO"));set(activity,"photoAllResult",all());set(activity,"originalWidth",800);set(activity,"originalHeight",600);set(activity,"scaleSettings",new Granulometry.Settings(20,1,true,false));
   java.lang.reflect.Method apply=MainActivity.class.getDeclaredMethod("applyRegion",InterestRegion.class);apply.setAccessible(true);apply.invoke(activity,new InterestRegion(InterestRegion.Shape.RECTANGLE,70,70,130,130));
   assertEquals(1,((PelletDetector.Result)get(activity,"photoResult")).circles.size());Granulometry.Distribution grain=(Granulometry.Distribution)get(activity,"distribution");assertEquals(4,grain.meanMm,1e-8);
   apply.invoke(activity,(Object)null);assertEquals(3,((PelletDetector.Result)get(activity,"photoResult")).circles.size());assertEquals(10,((Granulometry.Distribution)get(activity,"distribution")).analyzedPixelsPerMm,0);
  }
 }
 @Test public void saverRecordsRoiAndPreservesOriginalPanel()throws Exception {
  android.content.Context context=RuntimeEnvironment.getApplication();File root=new File(context.getCacheDir(),"roi_export");root.mkdirs();CrackExportTest.FakeMediaStore provider=new CrackExportTest.FakeMediaStore(root);android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";provider.attachInfo(context,info);org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider);
  Bitmap photo=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(80,90,100));File source=new File(root,"input.png");try(FileOutputStream f=new FileOutputStream(source)){photo.compress(Bitmap.CompressFormat.PNG,100,f);}
  InterestRegion roi=new InterestRegion(InterestRegion.Shape.CIRCLE,50,50,150,150);PelletDetector.Result selected=InterestRegion.select(all(),roi.indices(all()));ResultSaver.SavedResult result=ResultSaver.save(context,photo,source,"image/png",selected,PelletDetector.Parameters.medium(),null,null,roi,2);
  JSONObject json=new JSONObject(new String(Files.readAllBytes(new File(root,result.base+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(1,json.getInt("count"));assertEquals("CIRCLE",json.getJSONObject("region_of_interest").getString("shape"));assertEquals(2,json.getJSONObject("region_of_interest").getInt("excluded_outside_or_crossing"));assertEquals(1,json.getJSONObject("histogram").getInt("included"));
  Bitmap report=BitmapFactory.decodeFile(new File(root,result.base+"_resultado.png").getAbsolutePath());assertEquals(photo.getPixel(10,10),report.getPixel(10,json.getInt("original_panel_top")+10));assertNotEquals(photo.getPixel(10,10),report.getPixel(10,json.getInt("annotated_panel_top")+10));assertEquals(Color.rgb(64,220,242),report.getPixel(100,json.getInt("annotated_panel_top")+50));photo.recycle();report.recycle();
 }
 static Object get(Object o,String name)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 @Test public void regionShadePreservesInsidePixelsForBothShapes(){
  Bitmap photo=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(80,90,100));PelletDetector.Result empty=new PelletDetector.Result(Collections.emptyList(),400,300,0,0,false);
  for(InterestRegion.Shape shape:InterestRegion.Shape.values()){
   InterestRegion roi=InterestRegion.initial(shape,400,300);Bitmap output=PhotoComposer.compose(photo,empty,null,null,roi,0);PhotoComposer.Layout layout=new PhotoComposer.Layout(400,300);
   assertEquals(photo.getPixel(200,150),output.getPixel(200,layout.annotatedTop+150));assertNotEquals(photo.getPixel(5,5),output.getPixel(5,layout.annotatedTop+5));output.recycle();
  }photo.recycle();
 }
 @Test public void exampleReportsFromProvidedPhoto()throws Exception {
  File root=new File(System.getProperty("pelotavision.validationDir"));JSONObject data=new JSONObject(new String(Files.readAllBytes(new File(root,"fixtures/crack_example_analysis.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  JSONArray detections=data.getJSONArray("circles"),cracks=data.getJSONArray("cracks");List<PelletDetector.Detection> pellets=new ArrayList<>();List<CrackAnalysis.Entry> entries=new ArrayList<>();
  for(int i=0;i<detections.length();i++){
   JSONObject p=detections.getJSONObject(i),c=cracks.getJSONObject(i);pellets.add(new PelletDetector.Detection(p.getDouble("x"),p.getDouble("y"),p.getDouble("radius"),p.getDouble("area"),p.getDouble("roundness"),p.getBoolean("partial"),p.getBoolean("review")));
   JSONArray values=c.getJSONArray("runs");float[] runs=new float[values.length()];for(int j=0;j<runs.length;j++)runs[j]=(float)values.getDouble(j);entries.add(new CrackAnalysis.Entry(i+1,CrackAnalysis.Status.valueOf(c.getString("status")),runs));
  }
  Bitmap photo=BitmapFactory.decodeFile(new File(root,"fixtures/crack_example.png").getAbsolutePath());PelletDetector.Result all=new PelletDetector.Result(pellets,photo.getWidth(),photo.getHeight(),pellets.size(),0,false);CrackAnalysis full=new CrackAnalysis(all.width,all.height,50,0,entries);
  JSONObject results=new JSONObject();
  for(InterestRegion.Shape shape:InterestRegion.Shape.values()){
   InterestRegion region=InterestRegion.initial(shape,all.width,all.height);List<Integer> ids=region.indices(all);PelletDetector.Result selected=InterestRegion.select(all,ids);CrackAnalysis marked=full.select(ids);
   assertTrue(ids.size()>0&&ids.size()<pellets.size());Bitmap output=PhotoComposer.compose(photo,selected,marked,null,region,pellets.size()-ids.size());
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(output.compress(Bitmap.CompressFormat.PNG,100,bytes));byte[] encoded=bytes.toByteArray();
   assertTrue(encoded.length>12);assertArrayEquals(new byte[]{73,69,78,68},Arrays.copyOfRange(encoded,encoded.length-8,encoded.length-4));
   File path=new File(root,"PelotaVision_ROI_"+shape.name()+".png");File temp=new File(root,"roi-writing-"+shape.name()+".tmp");Files.write(temp.toPath(),encoded);Files.move(temp.toPath(),path.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
   assertArrayEquals(encoded,Files.readAllBytes(path.toPath()));output.recycle();
   JSONObject entry=new JSONObject();entry.put("selected",ids.size());entry.put("excluded_outside_or_crossing",pellets.size()-ids.size());entry.put("cracks",ResultSaver.crackJson(marked));entry.put("region",ResultSaver.regionJson(region,pellets.size()-ids.size()));results.put(shape.name(),entry);
  }
  photo.recycle();Files.write(new File(root,"roi-example-results.json").toPath(),results.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 static void set(Object o,String name,Object value)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
