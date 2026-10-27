package br.com.pelotavision;
import static org.junit.Assert.*;
import android.graphics.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CrackExportTest {
 @Test public void candidateCountIsPerPelletAndReviewChangesPersistInJson()throws Exception{
  CrackAnalysis automatic=new CrackAnalysis(380,300,50,1,Arrays.asList(
   new CrackAnalysis.Entry(1,CrackAnalysis.Status.POSSIBLE,new float[]{40,40,60,40,40,50,65,50}),
   new CrackAnalysis.Entry(2,CrackAnalysis.Status.NO_INDICATION,new float[0]),
   new CrackAnalysis.Entry(3,CrackAnalysis.Status.INCONCLUSIVE,new float[0])));
  assertEquals(1,automatic.count(CrackAnalysis.Status.POSSIBLE));
  CrackAnalysis revised=automatic.review(0,CrackAnalysis.Status.NO_INDICATION).review(1,CrackAnalysis.Status.POSSIBLE);
  assertEquals(1,revised.count(CrackAnalysis.Status.POSSIBLE));assertEquals(2,revised.reviewedCount());assertEquals(1,automatic.count(CrackAnalysis.Status.POSSIBLE));
  JSONObject json=ResultSaver.crackJson(revised);assertFalse(json.getBoolean("trained_model"));assertEquals(1,json.getInt("possible_crack_pellets"));
  assertEquals(0,json.getJSONArray("pellets").getJSONObject(0).getJSONArray("marked_runs_x1_y1_x2_y2_px").length());
  assertEquals(0,revised.review(1,null).count(CrackAnalysis.Status.POSSIBLE));
 }
 @Test public void exportedReportPreservesOriginalAboveMarkedPanelAndHistogram()throws Exception{
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(65,75,85));
  PelletDetector.Result pellets=new PelletDetector.Result(Arrays.asList(new PelletDetector.Detection(100,100,40,Math.PI*1600,1,false,false)),380,300,1,1,false);
  CrackAnalysis cracks=new CrackAnalysis(380,300,50,1,Arrays.asList(new CrackAnalysis.Entry(1,CrackAnalysis.Status.POSSIBLE,new float[]{80,85,120,85})));
  Bitmap composed=PhotoComposer.compose(photo,pellets,cracks,null);PhotoComposer.Layout layout=new PhotoComposer.Layout(380,300);
  ByteArrayOutputStream stream=new ByteArrayOutputStream();assertTrue(composed.compress(Bitmap.CompressFormat.PNG,100,stream));Bitmap saved=BitmapFactory.decodeByteArray(stream.toByteArray(),0,stream.size());
  assertEquals(layout.height,saved.getHeight());assertEquals(Color.rgb(65,75,85),saved.getPixel(90,layout.originalTop+85));
  assertEquals(Color.rgb(255,72,83),saved.getPixel(90,layout.annotatedTop+85));assertEquals(Color.rgb(16,26,35),saved.getPixel(2,layout.histogramTop+2));
  // Original panel is pixel-for-pixel unchanged, independent of all markers below it.
  for(int y=0;y<300;y+=7)for(int x=0;x<380;x+=7)assertEquals(photo.getPixel(x,y),saved.getPixel(x,layout.originalTop+y));
  saved.recycle();composed.recycle();photo.recycle();
 }
 @Test public void suppliedPhotoGeneratesNativeAndroidCompositionWithPixelHistogram()throws Exception{
  String verification=System.getProperty("pelotavision.validationDir");assertNotNull(verification);
  File dir=new File(verification);JSONObject input=new JSONObject(new String(Files.readAllBytes(new File(dir,"fixtures/crack_example_analysis.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  List<PelletDetector.Detection> marks=new ArrayList<>();JSONArray circles=input.getJSONArray("circles");
  for(int i=0;i<circles.length();i++){JSONObject d=circles.getJSONObject(i);marks.add(new PelletDetector.Detection(d.getDouble("x"),d.getDouble("y"),d.getDouble("radius"),d.getDouble("area"),d.getDouble("roundness"),d.getBoolean("partial"),d.getBoolean("review")));}
  List<CrackAnalysis.Entry> entries=new ArrayList<>();JSONArray cracks=input.getJSONArray("cracks");for(int i=0;i<cracks.length();i++){JSONObject e=cracks.getJSONObject(i);JSONArray values=e.getJSONArray("runs");float[] runs=new float[values.length()];for(int n=0;n<runs.length;n++)runs[n]=(float)values.getDouble(n);entries.add(new CrackAnalysis.Entry(e.getInt("id"),CrackAnalysis.Status.valueOf(e.getString("status")),runs));}
  Bitmap photo=BitmapFactory.decodeFile(new File(dir,"fixtures/crack_example.png").getAbsolutePath());assertNotNull(photo);
  PelletDetector.Result pellets=new PelletDetector.Result(marks,photo.getWidth(),photo.getHeight(),marks.size(),0,false);CrackAnalysis result=new CrackAnalysis(photo.getWidth(),photo.getHeight(),50,0,entries);
  assertEquals(marks.size(),result.count(CrackAnalysis.Status.POSSIBLE)+result.count(CrackAnalysis.Status.NO_INDICATION)+result.count(CrackAnalysis.Status.INCONCLUSIVE));assertTrue(result.count(CrackAnalysis.Status.POSSIBLE)>0);
  Bitmap report=PhotoComposer.compose(photo,pellets,result,null);try(FileOutputStream out=new FileOutputStream(new File(dir,"PelotaVision_Exemplo_Trincas.png"))){assertTrue(report.compress(Bitmap.CompressFormat.PNG,100,out));}
  try(FileWriter out=new FileWriter(new File(dir,"crack-example-results.json"))){out.write(ResultSaver.crackJson(result).toString(2));}
  report.recycle();photo.recycle();
 }
 @Test public void realSaverWritesStackedPngCrackCountsAndPixelUnits()throws Exception {
  android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();File root=new File(context.getCacheDir(),"export_provider");root.mkdirs();
  FakeMediaStore provider=new FakeMediaStore(root);android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";provider.attachInfo(context,info);org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider);
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(65,75,85));
  File source=new File(root,"input.png");try(FileOutputStream f=new FileOutputStream(source)){assertTrue(photo.compress(Bitmap.CompressFormat.PNG,100,f));}
  PelletDetector.Result pellets=new PelletDetector.Result(Arrays.asList(new PelletDetector.Detection(100,100,40,Math.PI*1600,1,false,false)),380,300,1,1,false);
  CrackAnalysis cracks=new CrackAnalysis(380,300,50,1,Arrays.asList(new CrackAnalysis.Entry(1,CrackAnalysis.Status.POSSIBLE,new float[]{80,85,120,85})));
  ResultSaver.SavedResult saved=ResultSaver.save(context,photo,source,"image/png",pellets,PelletDetector.Parameters.medium(),cracks,null);
  // 0.15.0: original, resultado, mapa, granulometria, json, csv, relatorio.pdf
  JSONObject json=new JSONObject(new String(Files.readAllBytes(new File(root,saved.base+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  // Robolectric nao implementa o PdfDocument nativo: aqui o JSON registra a falha e os 6 demais arquivos ficam validos.
  // O layout do PDF e conferido abaixo desenhando as mesmas paginas em bitmaps (PdfReport.draw).
  boolean pdfOk=!json.isNull("pdf_file");int expected=pdfOk?7:6;
  assertEquals(expected,provider.files.size());assertEquals(expected,provider.published);assertTrue(new File(root,saved.base+"_mapa.png").isFile());
  if(pdfOk){byte[] pdf=Files.readAllBytes(new File(root,saved.base+"_relatorio.pdf").toPath());assertEquals("%PDF",new String(pdf,0,4,java.nio.charset.StandardCharsets.US_ASCII));}
  else{assertNull(saved.pdf);assertTrue(json.has("pdf_error"));}
  final List<Bitmap> pages=new ArrayList<>();
  Bitmap annotated=BitmapFactory.decodeFile(new File(root,saved.base+"_resultado.png").getAbsolutePath()),map=BitmapFactory.decodeFile(new File(root,saved.base+"_mapa.png").getAbsolutePath());
  json.put("low_light",LowLightInfo.manual(LowLightPreprocessor.Mode.GAMMA_CLAHE,new LowLightPreprocessor.Quality(40,15,.12,0)).toJson());
  int n=PdfReport.draw(new PdfReport.Sink(){Bitmap b;public Canvas begin(int k){b=Bitmap.createBitmap(PdfReport.W,PdfReport.H,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);return new Canvas(b);}public void end(){pages.add(b);}},json,annotated,map);
  // 0.15.2: figuras individuais (original, segmentada, mapa, resumo/histograma), duas por página quando cabem.
  assertTrue(n>=2);assertEquals(n,pages.size());
  // pagina 1 tem texto (pixels escuros) e as paginas de imagem tem a foto desenhada
  for(Bitmap pg:pages){int dark=0;for(int y=0;y<PdfReport.H;y+=3)for(int x=0;x<PdfReport.W;x+=3)if(Color.red(pg.getPixel(x,y))<128)dark++;assertTrue(dark>50);}
  File dumps=new File(System.getProperty("pelotavision.validationDir"),"pdf-0.15.0");dumps.mkdirs();
  for(int i=0;i<pages.size();i++)try(FileOutputStream f=new FileOutputStream(new File(dumps,"pagina_"+(i+1)+".png"))){pages.get(i).compress(Bitmap.CompressFormat.PNG,100,f);}
  for(Bitmap pg:pages)pg.recycle();annotated.recycle();map.recycle();json.put("low_light",JSONObject.NULL);
  assertTrue(json.isNull("low_light"));assertEquals("PelotaVision 0.15.2",json.getString("app"));
  assertEquals(2,json.getInt("report_schema_version"));assertEquals(saved.base+"_mapa.png",json.getJSONObject("granulometric_map").getString("file"));assertTrue(json.getJSONObject("crack_histogram").getBoolean("available"));
  assertTrue(json.isNull("physical_scale"));assertEquals("px",json.getJSONObject("histogram").getString("unit"));assertEquals(1,json.getJSONObject("cracks").getInt("possible_crack_pellets"));
  String grain=new String(Files.readAllBytes(new File(root,saved.base+"_granulometria.csv").toPath()),java.nio.charset.StandardCharsets.UTF_8);assertTrue(grain.contains("lower_px;upper_px"));assertFalse(grain.contains("lower_mm"));
  String csv=new String(Files.readAllBytes(new File(root,saved.base+".csv").toPath()),java.nio.charset.StandardCharsets.UTF_8);assertTrue(csv.contains("crack_status;crack_manually_reviewed"));String row=csv.split("\n")[1];String[] columns=row.split(";",-1);assertEquals(32,columns.length);assertEquals("",columns[9]);assertEquals("",columns[10]);assertEquals("POSSIBLE",columns[12]);
  Bitmap output=BitmapFactory.decodeFile(new File(root,saved.base+"_resultado.png").getAbsolutePath());assertNotNull(output);assertEquals(Color.rgb(255,72,83),output.getPixel(90,json.getInt("annotated_panel_top")+85));
  assertArrayEquals(Files.readAllBytes(source.toPath()),Files.readAllBytes(new File(root,saved.base+"_original.png").toPath()));
  output.recycle();photo.recycle();
 }
 @Test public void filteredPhotoReplacesOnlyTheAnalyzedWindow()throws Exception {
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(30,35,40));
  AnalysisWindow window=new AnalysisWindow(380,300,new InterestRegion(InterestRegion.Shape.RECTANGLE,60,50,300,250));
  byte[] gray=new byte[window.width*window.height];Arrays.fill(gray,(byte)200);
  Bitmap filtered=FilteredPhoto.compose(photo,gray,window);
  assertEquals(Color.rgb(200,200,200),filtered.getPixel(150,150));assertEquals(Color.rgb(30,35,40),filtered.getPixel(10,10));
  assertEquals(Color.rgb(30,35,40),photo.getPixel(150,150));
  try{FilteredPhoto.compose(photo,new byte[3],window);fail();}catch(IllegalArgumentException expected){}
  filtered.recycle();photo.recycle();
 }
 @Test public void filteredAnalysisSavesFilteredImageAndSeparatePdfFigures()throws Exception {
  android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();File root=new File(context.getCacheDir(),"export_filtered");root.mkdirs();
  FakeMediaStore provider=new FakeMediaStore(root);android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";provider.attachInfo(context,info);org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider);
  int dark=Color.rgb(30,35,40),light=Color.rgb(200,200,200);
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(dark);
  File source=new File(root,"input.png");try(FileOutputStream f=new FileOutputStream(source)){assertTrue(photo.compress(Bitmap.CompressFormat.PNG,100,f));}
  InterestRegion roi=new InterestRegion(InterestRegion.Shape.RECTANGLE,60,50,300,250);AnalysisWindow window=new AnalysisWindow(380,300,roi);
  byte[] gray=new byte[window.width*window.height];Arrays.fill(gray,(byte)200);Bitmap filtered=FilteredPhoto.compose(photo,gray,window);
  PelletDetector.Result pellets=new PelletDetector.Result(Arrays.asList(new PelletDetector.Detection(180,150,40,Math.PI*1600,1,false,false)),380,300,1,1,false,"test+active_roi_crop_v1");
  LowLightInfo lowLight=LowLightInfo.manual(LowLightPreprocessor.Mode.DENOISE_CLAHE,new LowLightPreprocessor.Quality(40,15,.12,0));
  ResultSaver.SavedResult saved=ResultSaver.save(context,photo,source,"image/png",pellets,PelletDetector.Parameters.medium(),null,null,roi,0,null,null,true,null,ReportVisuals.defaults(),null,null,lowLight,filtered);
  JSONObject json=new JSONObject(new String(Files.readAllBytes(new File(root,saved.base+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  JSONObject images=json.getJSONObject("report_images");
  assertEquals(saved.base+"_filtrada.png",images.getString("filtered_file"));assertEquals("filtered",images.getString("segmented_base"));assertEquals("filtered",images.getString("map_base"));
  File filteredFile=new File(root,saved.base+"_filtrada.png");assertTrue(filteredFile.isFile());
  // Painel 1 (original) segue original; painel 2 (segmentado) é desenhado sobre a filtrada.
  Bitmap composite=BitmapFactory.decodeFile(new File(root,saved.base+"_resultado.png").getAbsolutePath());
  ReportViewport v=new ReportViewport(380,300,roi,true);int px=Math.round((270-v.left)*v.scale),py=Math.round((230-v.top)*v.scale);
  assertEquals(dark,composite.getPixel(px,json.getInt("original_panel_top")+py));assertEquals(light,composite.getPixel(px,json.getInt("annotated_panel_top")+py));
  Bitmap filteredPanel=BitmapFactory.decodeFile(filteredFile.getAbsolutePath());assertEquals(light,filteredPanel.getPixel(px,py));
  Bitmap map=BitmapFactory.decodeFile(new File(root,saved.base+"_mapa.png").getAbsolutePath());assertEquals(light,map.getPixel(px,py));
  // Figuras do PDF: original, filtrada, segmentada, mapa e resumo/histograma, cada uma separada.
  List<Bitmap> owned=new ArrayList<>();List<PdfReport.Figure> figures=PdfReport.figures(json,composite,filteredPanel,map,owned);
  assertEquals(5,figures.size());
  assertEquals("Imagem original",figures.get(0).title);assertTrue(figures.get(1).title.startsWith("Imagem filtrada · Redução de ruído"));
  assertEquals("Imagem segmentada",figures.get(2).title);assertEquals("Mapa granulométrico",figures.get(3).title);assertEquals("Resumo da imagem e histograma",figures.get(4).title);
  assertEquals(dark,figures.get(0).image.getPixel(px,py));assertEquals(light,figures.get(2).image.getPixel(px,py));
  final List<Bitmap> pages=new ArrayList<>();
  int n=PdfReport.draw(new PdfReport.Sink(){Bitmap b;public Canvas begin(int k){b=Bitmap.createBitmap(PdfReport.W,PdfReport.H,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);return new Canvas(b);}public void end(){pages.add(b);}},json,figures);
  assertEquals(n,pages.size());assertTrue(n>=3);
  File dumps=new File(System.getProperty("pelotavision.validationDir"),"pdf-0.15.2");dumps.mkdirs();
  for(int i=0;i<pages.size();i++)try(FileOutputStream f=new FileOutputStream(new File(dumps,"pagina_"+(i+1)+".png"))){pages.get(i).compress(Bitmap.CompressFormat.PNG,100,f);}
  for(Bitmap pg:pages)pg.recycle();for(Bitmap b:owned)b.recycle();
  composite.recycle();filteredPanel.recycle();map.recycle();filtered.recycle();photo.recycle();
 }
 @Test public void lowLightBlockRecordsSelectionModeAndIllumination()throws Exception {
  {
   LowLightPreprocessor.Quality q=new LowLightPreprocessor.Quality(20,5,.3,0);assertTrue(q.needsCorrection());
   JSONObject j=LowLightInfo.manual(LowLightPreprocessor.Mode.RETINEX,q).toJson();
   assertEquals("manual",j.getString("selection"));assertEquals("RETINEX",j.getString("mode"));assertTrue(j.getBoolean("enhanced"));
   assertEquals(20,j.getJSONObject("illumination").getDouble("mean_gray"),1e-6);assertTrue(j.isNull("original_score"));
   JSONObject n=LowLightInfo.notApplied(q,"x").toJson();assertFalse(n.getBoolean("enhanced"));assertEquals("not_applied",n.getString("selection"));
  }
 }
 @SuppressWarnings({"rawtypes","unchecked"})
 @Test public void tappingPelletAndClassifyingChangesActualActivityCount()throws Exception {
  try(org.robolectric.android.controller.ActivityController<MainActivity> controller=org.robolectric.Robolectric.buildActivity(MainActivity.class).setup()){
   MainActivity activity=controller.get();Class mode=Class.forName("br.com.pelotavision.MainActivity$Mode");setField(activity,"mode",Enum.valueOf(mode,"PHOTO"));
   Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);setField(activity,"photoBitmap",photo);
   PelletDetector.Result pellets=new PelletDetector.Result(Arrays.asList(new PelletDetector.Detection(100,100,40,Math.PI*1600,1,false,false)),380,300,1,1,false);setField(activity,"photoResult",pellets);
   CrackAnalysis cracks=new CrackAnalysis(380,300,50,1,Arrays.asList(new CrackAnalysis.Entry(1,CrackAnalysis.Status.POSSIBLE,new float[]{80,85,120,85})));setField(activity,"photoCracks",cracks);
   java.lang.reflect.Field overlayField=MainActivity.class.getDeclaredField("overlay");overlayField.setAccessible(true);android.view.View overlay=(android.view.View)overlayField.get(activity);overlay.layout(0,0,380,300);((CircleOverlay)overlay).display(pellets,true);
   java.lang.reflect.Method touch=MainActivity.class.getDeclaredMethod("reviewCrackAt",float.class,float.class);touch.setAccessible(true);touch.invoke(activity,100f,100f);
   org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
   dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
   dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();dialog.getListView().performItemClick(null,1,1);
   java.lang.reflect.Field field=MainActivity.class.getDeclaredField("photoCracks");field.setAccessible(true);CrackAnalysis reviewed=(CrackAnalysis)field.get(activity);
   assertEquals(0,reviewed.count(CrackAnalysis.Status.POSSIBLE));assertEquals(1,reviewed.count(CrackAnalysis.Status.NO_INDICATION));assertEquals(1,reviewed.reviewedCount());
   field=MainActivity.class.getDeclaredField("crackLabel");field.setAccessible(true);assertTrue(((android.widget.TextView)field.get(activity)).getText().toString().contains("Possíveis: 0"));
  }
 }
 private static void setField(MainActivity activity,String name,Object value)throws Exception{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
 public static class FakeMediaStore extends android.content.ContentProvider {
  final File root;final Map<android.net.Uri,File> files=new HashMap<>();int published=0;
  /** 0.15.3: RELATIVE_PATH de cada arquivo, pelo nome. */
  final Map<String,String> paths=new HashMap<>();
  FakeMediaStore(File root){this.root=root;}
  @Override public boolean onCreate(){return true;}
  @Override public android.net.Uri insert(android.net.Uri uri,android.content.ContentValues values){android.net.Uri item=android.content.ContentUris.withAppendedId(uri,files.size()+1);files.put(item,new File(root,values.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)));paths.put(values.getAsString(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),values.getAsString(android.provider.MediaStore.MediaColumns.RELATIVE_PATH));return item;}
  @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri uri,String mode)throws FileNotFoundException{return android.os.ParcelFileDescriptor.open(files.get(uri),android.os.ParcelFileDescriptor.MODE_READ_WRITE|android.os.ParcelFileDescriptor.MODE_CREATE|android.os.ParcelFileDescriptor.MODE_TRUNCATE);}
  @Override public int update(android.net.Uri uri,android.content.ContentValues values,String selection,String[] args){published++;return 1;}
  @Override public int delete(android.net.Uri uri,String selection,String[] args){File file=files.remove(uri);if(file!=null)file.delete();return 1;}
  @Override public android.database.Cursor query(android.net.Uri uri,String[] projection,String selection,String[] args,String sort){return null;}
  @Override public String getType(android.net.Uri uri){return "application/octet-stream";}
 }

}
