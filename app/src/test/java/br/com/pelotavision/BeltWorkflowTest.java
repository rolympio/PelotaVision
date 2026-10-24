package br.com.pelotavision;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.graphics.*;import android.view.*;import android.widget.*;import java.util.*;import org.json.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BeltWorkflowTest {
 static BeltCalibration.Edges vertical(){return new BeltCalibration.Edges(new double[]{50,30,50,270,330,30,330,270},false);}
 static BeltCalibration.Reference reference(){return BeltCalibration.select(vertical(),560,760,600,380,300);}
 @Test public void scaleUsesPerpendicularOriginalWidthAndSurvivesHistogramChanges(){
  BeltCalibration.Reference r=reference();assertEquals(560,r.originalWidthPixels,1e-9);assertEquals(1,r.originalPixelsPerMm,1e-9);
  Granulometry.Settings cfg=Granulometry.Settings.belt(r,1,true,false);assertSame(r,cfg.withHistogram(2,false,true).beltReference);assertNull(cfg.selectedObject);
  assertEquals(.5,cfg.analyzedPixelsPerMm(760,600,380,300),1e-9);
  Granulometry.Distribution d=Granulometry.compute(Arrays.asList(new Granulometry.Item(6,false,false)),cfg,760,600,380,300);assertEquals(12,d.meanMm,1e-9);
  try{cfg.analyzedPixelsPerMm(760,600,400,300);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void obliqueAnisotropicResizeAndHorizontalEdgesUseCorrectNormal(){
  // Original parallel lines run along (3,4); separation (40,-30) is normal, length 50.
  BeltCalibration.Edges e=new BeltCalibration.Edges(new double[]{30,40,60,80,50,25,80,65},false);
  BeltCalibration.Reference r=BeltCalibration.select(e,50,300,200,100,100);
  // Independently transformed: edge=(90,80), gap=(60,-30); |cross| / edge length.
  assertEquals(Math.abs(90*(-30)-80*60)/Math.hypot(90,80),r.originalWidthPixels,1e-9);
  BeltCalibration.Reference horizontal=BeltCalibration.select(new BeltCalibration.Edges(new double[]{10,20,90,20,10,80,90,80},false),60,100,100,100,100);assertEquals(1,horizontal.originalPixelsPerMm,1e-9);
 }
 @Test public void perspectiveCrossingsAndBadDimensionsCannotCommit(){
  for(double mm:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})try{BeltCalibration.select(vertical(),mm,760,600,380,300);fail();}catch(IllegalArgumentException expected){}
  for(double[] p:new double[][]{{50,30,50,270,330,30,270,270},{50,30,330,270,330,30,50,270},{50,30,50,30,330,30,330,270}})try{BeltCalibration.select(new BeltCalibration.Edges(p,false),560,760,600,380,300);fail();}catch(IllegalArgumentException expected){}
 }
 @Test public void automaticProposalFindsKnownFlatBeltAndRejectsUniformImage(){
  int w=200,h=280;byte[] a=new byte[w*h];Arrays.fill(a,(byte)25);for(int y=0;y<h;y++)for(int x=35;x<166;x++)a[y*w+x]=(byte)180;
  BeltCalibration.Edges e=BeltEdgeDetector.suggest(a,w,h);assertNotNull(e);assertTrue(e.automatic);
  double lo=Math.min(e.x(0),e.x(2)),hi=Math.max(e.x(0),e.x(2));assertEquals(35,lo,4);assertEquals(166,hi,4);
  Arrays.fill(a,(byte)100);assertNull(BeltEdgeDetector.suggest(a,w,h));
 }
 @Test public void draggingEndpointAndLineHonorsFitCenterAndDoesNotAlterRealScale(){
  android.content.Context c=RuntimeEnvironment.getApplication();Bitmap p=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);BeltEditorView v=new BeltEditorView(c,p,vertical());v.layout(0,0,760,800);
  float x=v.left()+50*v.scale(),y=v.top()+30*v.scale();event(v,MotionEvent.ACTION_DOWN,x,y);event(v,MotionEvent.ACTION_MOVE,x+20*v.scale(),y);event(v,MotionEvent.ACTION_UP,x+20*v.scale(),y);assertEquals(70,v.edges().x(0),1e-6);assertEquals(50,v.edges().x(1),1e-6);assertFalse(v.edges().automatic);
  v.edges(vertical());x=v.left()+50*v.scale();y=v.top()+150*v.scale();event(v,MotionEvent.ACTION_DOWN,x,y);event(v,MotionEvent.ACTION_MOVE,x+10*v.scale(),y);event(v,MotionEvent.ACTION_UP,x+10*v.scale(),y);assertEquals(60,v.edges().x(0),1e-6);assertEquals(60,v.edges().x(1),1e-6);p.recycle();
 }
 static void event(View v,int action,float x,float y){MotionEvent e=MotionEvent.obtain(0,10,action,x,y,0);v.onTouchEvent(e);e.recycle();}
 @Test public void twoFingerZoomChangesDisplayWithoutChangingBeltCoordinates(){
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);BeltEditorView view=new BeltEditorView(RuntimeEnvironment.getApplication(),photo,vertical());view.layout(0,0,760,800);float before=view.scale();
  MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,280,400,0);view.onTouchEvent(down);down.recycle();
  two(view,20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),280,480);
  for(int i=1;i<=8;i++)two(view,20+i*30,MotionEvent.ACTION_MOVE,280-i*15,480+i*15);
  two(view,300,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),160,600);
  event(view,MotionEvent.ACTION_UP,160,400);assertTrue(view.scale()>before);assertEquals(50,view.edges().x(0),0);assertEquals(330,view.edges().x(2),0);assertEquals(1,BeltCalibration.select(view.edges(),560,760,600,380,300).originalPixelsPerMm,1e-9);view.resetZoom();assertEquals(before,view.scale(),0);photo.recycle();
 }
 static void two(View view,long time,int action,float a,float b){
  MotionEvent.PointerProperties p0=new MotionEvent.PointerProperties(),p1=new MotionEvent.PointerProperties();p0.id=0;p1.id=1;p0.toolType=p1.toolType=MotionEvent.TOOL_TYPE_FINGER;
  MotionEvent.PointerCoords c0=new MotionEvent.PointerCoords(),c1=new MotionEvent.PointerCoords();c0.x=a;c1.x=b;c0.y=c1.y=400;c0.pressure=c1.pressure=1;c0.size=c1.size=1;
  MotionEvent e=MotionEvent.obtain(0,time,action,2,new MotionEvent.PointerProperties[]{p0,p1},new MotionEvent.PointerCoords[]{c0,c1},0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);view.onTouchEvent(e);e.recycle();
 }
 @Test public void roiReportTransformsBothPanelsAndKeepsHistogramUnchanged()throws Exception{
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.BLUE);Canvas c=new Canvas(photo);Paint p=new Paint();p.setColor(Color.MAGENTA);c.drawRect(100,80,280,220,p);
  PelletDetector.Result result=EllipseWorkflowTest.result(EllipseWorkflowTest.ellipse(180,140,15,10,0,false,false));
  InterestRegion roi=new InterestRegion(InterestRegion.Shape.RECTANGLE,100,80,280,220);
  Granulometry.Distribution d=Granulometry.compute(Arrays.asList(new Granulometry.Item(2*Math.sqrt(150),false,false)),Granulometry.Settings.belt(reference(),1,true,false),760,600,380,300);
  Bitmap report=PhotoComposer.compose(photo,result,null,d,roi,0,true);ReportViewport v=new ReportViewport(380,300,roi,true);PhotoComposer.Layout l=new PhotoComposer.Layout(v.width,v.height);
  assertEquals(380,report.getWidth());assertEquals(l.height,report.getHeight());assertEquals(Color.MAGENTA,report.getPixel(v.width/2,l.originalTop+v.height/2));assertEquals(Color.MAGENTA,report.getPixel(80,l.annotatedTop+80));
  assertEquals(2*Math.sqrt(150)/.5,d.meanMm,1e-9);JSONObject j=ResultSaver.viewportJson(v);assertEquals(100,j.getInt("crop_left"));assertFalse(j.getBoolean("physical_scale_changed_by_zoom"));JSONObject b=(JSONObject)ResultSaver.beltReferenceJson(reference());assertTrue(b.getBoolean("user_confirmed"));assertFalse(b.getBoolean("perspective_rectified"));assertEquals(560,b.getDouble("real_width_mm"),0);photo.recycle();report.recycle();
 }
 @Test public void circularRoiMasksPixelsOutsideSelectionAndNeverLeaksSurroundingPhoto(){
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.MAGENTA);PelletDetector.Result empty=new PelletDetector.Result(Collections.emptyList(),380,300,0,0,false);
  InterestRegion roi=new InterestRegion(InterestRegion.Shape.CIRCLE,100,60,280,240);Bitmap report=PhotoComposer.compose(photo,empty,null,null,roi,0,true);ReportViewport v=new ReportViewport(380,300,roi,true);PhotoComposer.Layout l=new PhotoComposer.Layout(v.width,v.height);
  assertEquals(Color.rgb(16,26,35),report.getPixel(1,l.originalTop+1));assertEquals(Color.MAGENTA,report.getPixel(v.width/2,l.originalTop+v.height/2));photo.recycle();report.recycle();
 }
 @Test public void realCalibrationDialogRequiresUserConfirmationAndCommitsBeltReference()throws Exception{
  try(org.robolectric.android.controller.ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup()){
   MainActivity a=ctl.get();ObjectCalibrationTest.setup(a);ObjectCalibrationTest.set(a,"scaleSettings",Granulometry.Settings.belt(reference(),1,true,false));ObjectCalibrationTest.call(a,"showBeltCalibration",new Class[]{});
   android.app.Dialog d=org.robolectric.shadows.ShadowDialog.getLatestDialog();View root=d.getWindow().getDecorView();Button apply=root.findViewWithTag("belt_apply");assertFalse(apply.isEnabled());CheckBox confirm=root.findViewWithTag("belt_confirm");confirm.setChecked(true);assertTrue(apply.isEnabled());
   EditText real=root.findViewWithTag("belt_width_mm");real.setText("280");assertFalse(confirm.isChecked());assertFalse(apply.isEnabled());confirm.setChecked(true);apply.performClick();
   Granulometry.Settings cfg=(Granulometry.Settings)ObjectCalibrationTest.get(a,"scaleSettings");assertNotNull(cfg.beltReference);assertEquals(2,cfg.originalPixelsPerMm,1e-9);org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss();
  }
 }
 @Test public void savedPngJsonAndCsvAgreeAfterBeltCalibrationAndRoiZoom()throws Exception{
  java.io.File directory=java.nio.file.Files.createTempDirectory("belt-export").toFile();CrackExportTest.FakeMediaStore provider=new CrackExportTest.FakeMediaStore(directory);android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";provider.attachInfo(RuntimeEnvironment.getApplication(),info);org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider);
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.MAGENTA);Bitmap original=Bitmap.createScaledBitmap(photo,760,600,false);java.io.File source=new java.io.File(directory,"source.png");BeltDemoTest.png(original,source);original.recycle();
  PelletDetector.Result result=EllipseWorkflowTest.result(EllipseWorkflowTest.ellipse(180,140,3,3,0,false,false));InterestRegion roi=new InterestRegion(InterestRegion.Shape.RECTANGLE,100,80,280,220);
  Granulometry.Distribution distribution=Granulometry.compute(Arrays.asList(new Granulometry.Item(6,false,false)),Granulometry.Settings.belt(reference(),1,true,false),760,600,380,300);CrackAnalysis cracks=new CrackAnalysis(380,300,50,0,Arrays.asList(new CrackAnalysis.Entry(1,CrackAnalysis.Status.POSSIBLE,new float[]{177,140,183,140})));
  ResultSaver.SavedResult saved=ResultSaver.save(RuntimeEnvironment.getApplication(),photo,source,"image/png",result,PelletDetector.Parameters.automatic(),cracks,distribution,roi,0,null,null,true);
  JSONObject json=new JSONObject(new String(java.nio.file.Files.readAllBytes(new java.io.File(directory,saved.base+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(560,json.getJSONObject("physical_scale").getJSONObject("belt_reference").getDouble("real_width_mm"),0);assertEquals(12,json.getJSONObject("histogram").getDouble("mean_mm"),1e-9);JSONObject crop=json.getJSONObject("report_viewport");assertTrue(crop.getBoolean("roi_cropped"));
  Bitmap output=BitmapFactory.decodeFile(new java.io.File(directory,saved.base+"_resultado.png").toString());int x=(int)Math.round((180-crop.getInt("crop_left"))*crop.getDouble("render_scale")),y=json.getInt("annotated_panel_top")+(int)Math.round((140-crop.getInt("crop_top"))*crop.getDouble("render_scale"));assertEquals(Color.rgb(255,72,83),output.getPixel(x,y));assertEquals(380,output.getWidth());
  assertArrayEquals(java.nio.file.Files.readAllBytes(source.toPath()),java.nio.file.Files.readAllBytes(new java.io.File(directory,saved.base+"_original.png").toPath()));String csv=new String(java.nio.file.Files.readAllBytes(new java.io.File(directory,saved.base+".csv").toPath()),java.nio.charset.StandardCharsets.UTF_8);String[] row=csv.split("\n")[1].split(";",-1);assertEquals(32,row.length);assertEquals("12.000000",row[9]);assertEquals("1.000000",row[30]);output.recycle();photo.recycle();
 }
}
