package br.com.pelotavision;
import static org.junit.Assert.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.graphics.*;import java.util.*;import java.io.*;import org.json.*;

/** 0.15.3: "Salvar foto + dados" grava numa subpasta (data/hora + teste) e registra o nome do teste. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SaveFolderTest {
 private static CrackExportTest.FakeMediaStore provider(File dir){CrackExportTest.FakeMediaStore provider=new CrackExportTest.FakeMediaStore(dir);android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";provider.attachInfo(RuntimeEnvironment.getApplication(),info);org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider);return provider;}
 private static ResultSaver.SavedResult save(File dir,String folder,String test)throws Exception{
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.DKGRAY);File source=new File(dir,"original.png");BeltDemoTest.png(photo,source);
  PelletDetector.Result result=new PelletDetector.Result(Arrays.asList(EllipseWorkflowTest.ellipse(120,140,6,6,0,false,false)),380,300,1,1,false,"test");
  try{return ResultSaver.save(RuntimeEnvironment.getApplication(),photo,source,"image/png",result,PelletDetector.Parameters.automatic(),null,null,null,0,null,null,false,null,null,null,null,null,null,folder,test);}finally{photo.recycle();}
 }
 @Test public void savesIntoTimestampedSubfolderWithTestName()throws Exception{
  File dir=java.nio.file.Files.createTempDirectory("save-folder").toFile();CrackExportTest.FakeMediaStore provider=provider(dir);
  String folder=SaveNaming.suggestedFolder(new Date(),"Disco 3 · umidade 9,2%");
  ResultSaver.SavedResult saved=save(dir,folder,"  Disco 3 · umidade 9,2%  ");
  assertEquals(folder,saved.folder);
  assertEquals("Pictures/PelotaVision/"+folder,provider.paths.get(saved.base+"_resultado.png"));
  assertEquals("Pictures/PelotaVision/"+folder,provider.paths.get(saved.base+"_original.png"));
  assertEquals("Download/PelotaVision/"+folder,provider.paths.get(saved.base+".json"));
  assertEquals("Download/PelotaVision/"+folder,provider.paths.get(saved.base+".csv"));
  JSONObject json=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir,saved.base+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  assertEquals("Disco 3 · umidade 9,2%",json.getString("test_name"));
  assertEquals(folder,json.getString("output_folder"));
  // 0.15.3: bloco Rosin–Rammler com as duas bases (1 pelota: ajuste indisponível, mas o bloco existe).
  JSONObject rr=json.getJSONObject("rosin_rammler");
  assertEquals("number",rr.getString("selected_basis"));
  assertFalse(rr.getJSONObject("number").getBoolean("available"));
  assertTrue(rr.has("estimated_mass_d3"));
 }
 @Test public void rosinRammlerChartAndFullPageFigureDraw()throws Exception{
  Random r=new Random(5);List<Granulometry.Item> items=new ArrayList<>();
  for(int i=0;i<150;i++){double mm=12.5*Math.pow(-Math.log(1-r.nextDouble()),1/4.5);items.add(new Granulometry.Item(mm*10,false,false));}
  Granulometry.Distribution d=Granulometry.compute(items,new Granulometry.Settings(10,1,true,false),1000,1000,1000,1000);
  RosinRammler.Fit fit=RosinRammler.fit(d,RosinRammler.Basis.MASS);assertTrue(fit.available);
  Bitmap b=Bitmap.createBitmap(760,440,Bitmap.Config.ARGB_8888);RosinRammlerView.draw(new Canvas(b),new RectF(0,0,760,440),fit,true);
  int lit=0;for(int x=100;x<740;x+=4)for(int y=120;y<400;y+=4)if(b.getPixel(x,y)!=RosinRammlerView.BG)lit++;
  assertTrue("curve, grid and points must be drawn",lit>50);
  List<Bitmap> owned=new ArrayList<>();JSONObject json=new JSONObject();json.put("rosin_rammler",RosinRammler.reportJson(d,RosinRammler.Basis.MASS));
  PdfReport.Figure f=PdfReport.distributionFigure(json,d,null,ReportVisuals.defaults(),RosinRammler.Basis.MASS,owned);
  assertNotNull(f);assertTrue(f.fullPage);assertTrue(f.caption.contains("F(d) = 100"));assertTrue(f.caption.contains("massa estimada"));
  String root=System.getProperty("pelotavision.validationDir");File out=root==null?java.nio.file.Files.createTempDirectory("rr").toFile():new File(root,"rosin-rammler-android");out.mkdirs();
  try(FileOutputStream o=new FileOutputStream(new File(out,"figura_relatorio.png"))){f.image.compress(Bitmap.CompressFormat.PNG,100,o);}
  for(Bitmap x:owned)x.recycle();b.recycle();
 }
 @Test public void legacyCallKeepsFlatFolderAndNullTestName()throws Exception{
  File dir=java.nio.file.Files.createTempDirectory("save-flat").toFile();CrackExportTest.FakeMediaStore provider=provider(dir);
  ResultSaver.SavedResult saved=save(dir,null,null);
  assertNull(saved.folder);
  assertEquals("Pictures/PelotaVision",provider.paths.get(saved.base+"_resultado.png"));
  assertEquals("Download/PelotaVision",provider.paths.get(saved.base+".json"));
  JSONObject json=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir,saved.base+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  assertTrue(json.isNull("test_name"));
 }
}
