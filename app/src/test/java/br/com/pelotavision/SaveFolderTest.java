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
