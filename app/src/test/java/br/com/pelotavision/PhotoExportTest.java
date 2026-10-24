package br.com.pelotavision;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoExportTest {
    private static PelletDetector.Result circles() {
        return new PelletDetector.Result(Arrays.asList(
            new PelletDetector.Detection(80,60,20,Math.PI*400,1,false,false),
            new PelletDetector.Detection(180,100,24,Math.PI*576,1,false,false),
            new PelletDetector.Detection(280,200,28,Math.PI*784,1,false,false)),380,300,3,10,false);
    }
    @Test public void encodedPngContainsPhotoAndHistogramFooter() throws Exception {
        Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(45,45,45));
        Granulometry.Distribution d=Granulometry.compute(Arrays.asList(
            new Granulometry.Item(40,false,false),new Granulometry.Item(48,false,false),new Granulometry.Item(56,false,false)),
            new Granulometry.Settings(8,2,true,false),760,600,380,300);
        Bitmap composed=PhotoComposer.compose(photo,circles(),d);
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(composed.compress(Bitmap.CompressFormat.PNG,100,out));
        byte[] encoded=out.toByteArray();Bitmap decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.length);
        assertNotNull(decoded);assertEquals(380,decoded.getWidth());PhotoComposer.Layout layout=new PhotoComposer.Layout(380,300);assertEquals(layout.height,decoded.getHeight());
        assertEquals(Color.rgb(45,45,45),decoded.getPixel(4,layout.originalTop+4));
        assertEquals(Color.rgb(16,26,35),decoded.getPixel(2,layout.summaryTop+2));
        int bars=0;for(int y=layout.histogramTop+90;y<layout.histogramTop+170;y++)for(int x=44;x<366;x++)if(decoded.getPixel(x,y)==Color.rgb(82,216,190))bars++;
        assertTrue("PNG must contain visible bars, not just an empty footer",bars>800);
        String destination=System.getProperty("pelotavision.validationDir");
        if(destination!=null){File file=new File(destination,"resultado_regressao.png");file.getParentFile().mkdirs();try(FileOutputStream f=new FileOutputStream(file)){f.write(encoded);}}
        decoded.recycle();composed.recycle();photo.recycle();
    }
    @Test public void zeroIncludedRegionsStillExportsExplicitFooter() {
        Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);
        Granulometry.Distribution d=Granulometry.compute(Collections.emptyList(),new Granulometry.Settings(8,2,true,false),760,600,380,300);
        Bitmap composed=PhotoComposer.compose(photo,circles(),d);
        PhotoComposer.Layout layout=new PhotoComposer.Layout(380,300);assertEquals(layout.height,composed.getHeight());assertEquals(Color.rgb(16,26,35),composed.getPixel(2,layout.summaryTop+2));
        composed.recycle();photo.recycle();
    }
    @Test public void uncalibratedHistogramIsExplicitlyPixels() {
        Granulometry.Distribution d=PhotoComposer.pixelHistogram(circles());assertTrue(d.settings.pixelsOnly());assertEquals("px",d.settings.unit());assertEquals(48,d.meanMm,1e-9);
        Bitmap photo=Bitmap.createBitmap(380,300,Bitmap.Config.ARGB_8888);Bitmap composed=PhotoComposer.compose(photo,circles(),null);assertNotNull(composed);composed.recycle();photo.recycle();
    }
    @Test public void LargeDiameterUsesObservedRangeWithoutChangingOneMmClasses() {
        Granulometry.Distribution d=Granulometry.compute(Arrays.asList(new Granulometry.Item(1500,false,false)),
            new Granulometry.Settings(1,1,true,false),1944,1944,1944,1944);
        assertEquals(1,d.included);assertEquals(1,d.binCount());assertEquals(1,d.settings.binWidthMm,0);assertEquals(1500,d.lower(0),0);
        assertEquals(1,d.requestedBinWidthMm,0);assertEquals(1500,d.d50,0);
        int count=0;for(int i=0;i<d.binCount();i++)count+=d.count(i);assertEquals(1,count);
    }
    @Test public void encodedChartHasEightOneMmBarsOverEightToSixteen() throws Exception {
        Bitmap photo=Bitmap.createBitmap(760,400,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(45,45,45));
        List<Granulometry.Item> items=new ArrayList<>();List<PelletDetector.Detection> marks=new ArrayList<>();
        double[] diameters={8.2,9.2,10.2,11.2,12.8,13.8,14.8,15.8};
        for(int i=0;i<diameters.length;i++){
            double radius=diameters[i]*10/2;items.add(new Granulometry.Item(radius*2,false,false));
            marks.add(new PelletDetector.Detection(90+(i%4)*180,95+(i/4)*200,radius,Math.PI*radius*radius,1,false,false));
        }
        Granulometry.Distribution d=Granulometry.compute(items,new Granulometry.Settings(20,1,true,false),1520,800,760,400);
        assertEquals(8,d.binCount());assertEquals(8,d.lower(0),0);assertEquals(16,d.upper(7),0);assertEquals(12,d.meanMm,1e-9);
        Bitmap composed=PhotoComposer.compose(photo,new PelletDetector.Result(marks,760,400,8,10,false),d);
        ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(composed.compress(Bitmap.CompressFormat.PNG,100,out));
        Bitmap decoded=BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size());
        // Every bar must survive actual PNG encoding; their centers use the same data interval as the axis.
        int spans=0;boolean prior=false;
        for(int x=88;x<732;x++){boolean teal=decoded.getPixel(x,new PhotoComposer.Layout(760,400).histogramTop+250)==Color.rgb(82,216,190);if(teal&&!prior)spans++;prior=teal;}
        assertEquals("Exactly eight bars, not one bar or a zero-based index axis",8,spans);
        String destination=System.getProperty("pelotavision.validationDir");
        if(destination!=null)try(FileOutputStream f=new FileOutputStream(new File(destination,"histograma_8_a_16_mm.png"))){f.write(out.toByteArray());}
        decoded.recycle();composed.recycle();photo.recycle();
    }
    @Test public void tooManyBinsFailExplicitlyWithoutChangingWidth() {
        Granulometry.Settings cfg=new Granulometry.Settings(1,1,true,false);
        try{Granulometry.compute(Arrays.asList(new Granulometry.Item(1,false,false),new Granulometry.Item(6000,false,false)),cfg,100,100,100,100);fail("Must preserve selected width");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("5000"));assertEquals(1,cfg.binWidthMm,0);}
    }
    @Test public void assumedMeanIsMarkedAndPreservesRelativeSizes() {
        Granulometry.Distribution d=Granulometry.compute(Arrays.asList(new Granulometry.Item(40,false,false),new Granulometry.Item(60,false,false),new Granulometry.Item(80,false,false)),
            Granulometry.Settings.estimatedMean(12,1,true,false),760,600,380,300);
        assertEquals(12,d.meanMm,1e-9);assertEquals(8,d.minMm,1e-9);assertEquals(16,d.maxMm,1e-9);
        assertTrue(d.settings.estimated());assertEquals(10,d.settings.originalPixelsPerMm,1e-9);
    }
    private static Field field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void edits(View v,List<EditText> out){if(v instanceof EditText)out.add((EditText)v);if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)edits(group.getChildAt(i),out);}}
    @SuppressWarnings({"rawtypes","unchecked"})
    @Test public void actualCalibrationDialogAppliesCommaDecimalAndShowsInvalidInput()throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();Class mode=Class.forName("br.com.pelotavision.MainActivity$Mode");
            field("mode").set(activity,Enum.valueOf(mode,"PHOTO"));
            field("originalWidth").setInt(activity,760);field("originalHeight").setInt(activity,600);
            field("photoResult").set(activity,new PelletDetector.Result(Arrays.asList(
                new PelletDetector.Detection(190,150,140,Math.PI*140*140,1,false,false)),380,300,1,10,false));
            Method show=MainActivity.class.getDeclaredMethod("showCalibration");show.setAccessible(true);show.invoke(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idle();AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);List<EditText> fields=new ArrayList<>();edits(dialog.getWindow().getDecorView(),fields);assertEquals(3,fields.size());
            fields.get(0).setText("0");fields.get(2).setText("0,1");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue("Invalid values must leave the form open",dialog.isShowing());assertNull(field("scaleSettings").get(activity));
            fields.get(0).setText("16,5");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(dialog.isShowing());Granulometry.Distribution d=(Granulometry.Distribution)field("distribution").get(activity);
            assertNotNull("Applying a valid scale must generate the histogram",d);assertEquals(16.5,d.settings.originalPixelsPerMm,0);
            assertEquals(.1,d.settings.binWidthMm,0);assertEquals(1,d.binCount());
            assertEquals(View.VISIBLE,((View)field("histogram").get(activity)).getVisibility());
            assertTrue(((TextView)field("scaleLabel").get(activity)).getText().toString().contains("classe"));
        }
    }
    private static void radios(View v,List<android.widget.RadioButton> out){if(v instanceof android.widget.RadioButton)out.add((android.widget.RadioButton)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)radios(g.getChildAt(i),out);}}
    @SuppressWarnings({"rawtypes","unchecked"})
    @Test public void actualDialogReferenceAndEstimatedMeanModes()throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();Class mode=Class.forName("br.com.pelotavision.MainActivity$Mode");
            field("mode").set(activity,Enum.valueOf(mode,"PHOTO"));field("originalWidth").setInt(activity,760);field("originalHeight").setInt(activity,600);field("photoResult").set(activity,circles());
            Method show=MainActivity.class.getDeclaredMethod("showCalibration");show.setAccessible(true);show.invoke(activity);
            Shadows.shadowOf(Looper.getMainLooper()).idle();AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            List<EditText> values=new ArrayList<>();edits(dialog.getWindow().getDecorView(),values);
            List<android.widget.RadioButton> options=new ArrayList<>();radios(dialog.getWindow().getDecorView(),options);assertEquals(5,options.size());assertFalse(options.get(3).isEnabled());assertFalse(options.get(4).isEnabled());
            options.get(1).performClick();values.get(0).setText("96");values.get(1).setText("12");values.get(2).setText("1");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Granulometry.Distribution d=(Granulometry.Distribution)field("distribution").get(activity);
            assertEquals(8,d.settings.originalPixelsPerMm,0);assertEquals(12,d.meanMm,1e-9);assertEquals("reference_length",d.settings.calibrationSource);
            show.invoke(activity);Shadows.shadowOf(Looper.getMainLooper()).idle();dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            values.clear();options.clear();edits(dialog.getWindow().getDecorView(),values);radios(dialog.getWindow().getDecorView(),options);
            options.get(2).performClick();values.get(0).setText("12");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            d=(Granulometry.Distribution)field("distribution").get(activity);assertTrue(d.settings.estimated());assertEquals(12,d.meanMm,1e-9);
            assertTrue(((TextView)field("scaleLabel").get(activity)).getText().toString().contains("Estimativa"));
        }
    }

}
