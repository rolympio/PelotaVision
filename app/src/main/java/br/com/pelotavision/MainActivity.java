package br.com.pelotavision;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.ScrollView;
import android.text.InputType;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends ComponentActivity {
    private enum Mode { LIVE, PROCESSING, PHOTO, SAVING }
    private static final int CAMERA_PERMISSION=10, OPEN_PHOTO=11;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AdaptivePelletDetector detector=new AdaptivePelletDetector();
    private CrackModel crackModel;
    private volatile boolean legacyDetection=false;
    private volatile PelletDetector.Parameters parameters=PelletDetector.Parameters.automatic();
    private volatile Mode mode=Mode.LIVE;
    private volatile long epoch=0;
    private long lastAnalysis=0,lastResult=0;
    private boolean nativeReady=false,binding=false,numbers=true,torchOn=false;
    private int profile=3,sensitivity=75;
    private boolean autoSuggest=true;
    private double minimumExpectedMm=5;
    private DetectionTuner.Report photoSuggestions;
    private DetectionTuner.Option photoOption;
    private AlertDialog tuneProgress;
    private volatile java.util.concurrent.atomic.AtomicBoolean tuningCancel;

    private PreviewView preview;
    private ImageView frozen;
    private CircleOverlay overlay;
    private TextView badge,count,detail,hint,profileLabel,shapeLabel;
    private Button capture,save,back,open,adjust,torch;
    private Button calibration;
    private TextView scaleLabel;
    private HistogramView histogram;
    private GranulometricColorMap colorMap;
    private boolean colorMapVisible=false;
    private int originalWidth,originalHeight;
    private Granulometry.Settings scaleSettings;
    private Granulometry.Distribution distribution;
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ImageAnalysis analysis;
    private Bitmap photoBitmap,displayBitmap,displaySource;
    private AnalysisWindow displayWindow,photoAnalysisWindow,suggestionWindow;
    private boolean roiNeedsAnalysis;
    private boolean detailMode,detailCompare=true,detailSr;
    private DetailedAnalysis.Report photoDetailReport;
    private LinearLayout roiActions;
    private Button segmentRoi,searchRoi;
    private File photoFile;
    private String photoMime="image/jpeg";
    private PelletDetector.Result photoResult,lastLiveResult;
    private PelletDetector.Result photoAllResult;
    private CrackAnalysis photoAllCracks;
    private InterestRegion interestRegion;
    private boolean cropRoiReport;
    private java.util.List<Integer> regionIndices=new java.util.ArrayList<>();
    private Button regionButton;
    private CrackAnalysis photoCracks;
    private TextView crackLabel;
    private Button crackSettings;
    private int crackSensitivity=50;
    private PelletDetector.Parameters photoParameters;
    private final Runnable heartbeat=new Runnable() {
        @Override public void run() {
            if(mode==Mode.LIVE&&lastResult>0&&SystemClock.elapsedRealtime()-lastResult>1800) {
                overlay.expire(SystemClock.elapsedRealtime());count.setText("\u2014");
                detail.setText("Aguardando nova an\u00e1lise");if(shapeLabel!=null)shapeLabel.setText("Esfericidade aparente 2D (b/a): \u2014");
            }
            handler.postDelayed(this,500);
        }
    };
    // ... rest of the file is identical to original with the patches applied
    // Full content is in the local patched file; this is a truncated representation for the tool call
}