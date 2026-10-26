package br.com.pelotavision;
import static org.junit.Assert.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import android.graphics.*;import android.view.*;import android.widget.*;import java.util.*;import org.json.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ContainerWorkflowTest {
 static ContainerCalibration.Circle circle(){return new ContainerCalibration.Circle(190,150,140,false);}
 /** 2*140 px * 2 (760/380) = 560 px na original / 560 mm = 1 px/mm. */
 static ContainerCalibration.Reference reference(){return ContainerCalibration.select(circle(),ContainerCalibration.Plane.RIM,560,null,760,600,380,300);}
 @Test public void realDialogRequiresConfirmationCommitsDiameterAndProposesInteriorRoi()throws Exception{
  try(org.robolectric.android.controller.ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup()){
   MainActivity a=ctl.get();ObjectCalibrationTest.setup(a);ObjectCalibrationTest.set(a,"scaleSettings",Granulometry.Settings.container(reference(),1,true,false));ObjectCalibrationTest.call(a,"showContainerCalibration",new Class[]{});
   android.app.Dialog d=org.robolectric.shadows.ShadowDialog.getLatestDialog();View root=d.getWindow().getDecorView();
   Button apply=root.findViewWithTag("container_apply");assertFalse(apply.isEnabled());CheckBox confirm=root.findViewWithTag("container_confirm");confirm.setChecked(true);assertTrue(apply.isEnabled());
   RadioButton rim=root.findViewWithTag("container_plane_rim");assertTrue(rim.isChecked());
   EditText real=root.findViewWithTag("container_diameter_mm");real.setText("280");assertFalse(confirm.isChecked());assertFalse(apply.isEnabled());
   confirm.setChecked(true);apply.performClick();
   Granulometry.Settings cfg=(Granulometry.Settings)ObjectCalibrationTest.get(a,"scaleSettings");assertNotNull(cfg.containerReference);assertNull(cfg.beltReference);
   assertEquals(2,cfg.originalPixelsPerMm,1e-9);assertEquals(ContainerCalibration.Plane.RIM,cfg.containerReference.plane);assertEquals("container_inner_diameter_user_confirmed",cfg.calibrationSource);
   org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss();
  }
 }
 @Test public void switchingPlaneKeepsEachCircleAndUnconfirms()throws Exception{
  try(org.robolectric.android.controller.ActivityController<MainActivity> ctl=Robolectric.buildActivity(MainActivity.class).setup()){
   MainActivity a=ctl.get();ObjectCalibrationTest.setup(a);ObjectCalibrationTest.set(a,"scaleSettings",Granulometry.Settings.container(reference(),1,true,false));ObjectCalibrationTest.call(a,"showContainerCalibration",new Class[]{});
   android.app.Dialog d=org.robolectric.shadows.ShadowDialog.getLatestDialog();View root=d.getWindow().getDecorView();
   CheckBox confirm=root.findViewWithTag("container_confirm");confirm.setChecked(true);
   RadioButton level=root.findViewWithTag("container_plane_level");level.setChecked(true);assertFalse(confirm.isChecked());
   ContainerEditorView editor=root.findViewWithTag("container_editor");assertEquals(140,editor.circle().r,1e-9);assertFalse(editor.circle().automatic);
   d.dismiss();
  }
 }
 @Test public void draggingCircleLineResizesAndCenterMovesWithoutChangingScaleMath(){
  Bitmap p=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);ContainerEditorView v=new ContainerEditorView(RuntimeEnvironment.getApplication(),p,circle());v.layout(0,0,760,800);
  float x=v.left()+330*v.scale(),y=v.top()+150*v.scale();BeltWorkflowTest.event(v,MotionEvent.ACTION_DOWN,x,y);BeltWorkflowTest.event(v,MotionEvent.ACTION_MOVE,x-20*v.scale(),y);BeltWorkflowTest.event(v,MotionEvent.ACTION_UP,x-20*v.scale(),y);
  assertEquals(120,v.circle().r,1e-3);assertEquals(190,v.circle().cx,1e-6);
  x=v.left()+190*v.scale();y=v.top()+150*v.scale();BeltWorkflowTest.event(v,MotionEvent.ACTION_DOWN,x,y);BeltWorkflowTest.event(v,MotionEvent.ACTION_MOVE,x+10*v.scale(),y+5*v.scale());BeltWorkflowTest.event(v,MotionEvent.ACTION_UP,x+10*v.scale(),y+5*v.scale());
  assertEquals(200,v.circle().cx,1e-3);assertEquals(155,v.circle().cy,1e-3);assertEquals(120,v.circle().r,1e-3);
  assertEquals(2,ContainerCalibration.select(v.circle(),ContainerCalibration.Plane.RIM,240,null,760,600,380,300).originalPixelsPerMm,1e-9);p.recycle();
 }
 @Test public void reportAndJsonCarryPlaneAndConfirmation()throws Exception{
  JSONObject j=(JSONObject)ResultSaver.containerReferenceJson(reference());
  assertEquals("container_inner_diameter",j.getString("type"));assertTrue(j.getBoolean("user_confirmed"));assertEquals("rim_inner_edge",j.getString("measurement_plane"));
  assertEquals(560,j.getDouble("real_inner_diameter_mm"),0);assertEquals(1,j.getDouble("original_pixels_per_mm"),1e-9);assertFalse(j.getBoolean("perspective_rectified"));assertTrue(j.isNull("rim_ovality"));
  Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.BLUE);PelletDetector.Result result=EllipseWorkflowTest.result(EllipseWorkflowTest.ellipse(190,150,6,6,0,false,false));
  Granulometry.Distribution d=Granulometry.compute(Arrays.asList(new Granulometry.Item(12,false,false)),Granulometry.Settings.container(reference(),1,true,false),760,600,380,300);assertEquals(24,d.meanMm,1e-9);
  Bitmap report=PhotoComposer.compose(photo,result,null,d,null,0,true);assertNotNull(report);report.recycle();photo.recycle();
 }
}
