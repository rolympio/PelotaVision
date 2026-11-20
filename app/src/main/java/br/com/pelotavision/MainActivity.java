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
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
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
    /** 0.19.0: rajada importada (a foto da sessão é a empilhada enquanto photoFile == photoBurst.fused). */
    private BurstImport photoBurst;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AdaptivePelletDetector detector=new AdaptivePelletDetector();
    private CrackModel crackModel;
    private volatile int detectionMethod=DetectionTuner.GRADIENT;
    /** Cena: bancada (padrão) ou correia (pelotas pequenas e numerosas). */
    private volatile boolean beltScene=false;
    private volatile LowLightPreprocessor.Mode selectedLowLightMode=null; // null = automatic
    private int method(){return detectionMethod==DetectionTuner.NEURAL&&!NeuralPelletDetector.available(beltScene)?DetectionTuner.GRADIENT:detectionMethod;}
    private volatile PelletDetector.Parameters parameters=PelletDetector.Parameters.automatic();
    private volatile Mode mode=Mode.LIVE;
    private volatile long epoch=0;
    private long lastAnalysis=0,lastResult=0;
    private boolean nativeReady=false,binding=false,numbers=true,torchOn=false;
    private int profile=3,sensitivity=75;
    private boolean autoSuggest=true;
    /** 0.18.0: ROI automática (recipiente redondo/retangular, trecho carregado de correia, campo cheio). */
    private boolean autoRoi=true;
    private final AutoRoi.Stabilizer liveAutoRoi=new AutoRoi.Stabilizer();
    private volatile long lastAutoRoi;
    /** Proposta da ROI automática da foto atual (vai para o JSON). */
    private AutoRoi.Result photoAutoRoi;
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
    /** 0.15.3: curva acumulada + ajuste Rosin–Rammler, abaixo do histograma (mesma visibilidade). */
    private RosinRammlerView rosinRammler;
    private LinearLayout chartTabs;private TextView histogramTab,rrTab;private android.widget.FrameLayout chartSlot;
    private GranulometricColorMap colorMap;
    private boolean colorMapVisible=false;
    /** Visualização: 0 original (só a foto), 1 segmentação (marcações), 2 mapa granulométrico. */
    private int viewMode=1;private Button mapButton;
    private ReportVisuals visuals=ReportVisuals.defaults();
    private CrackHistogram crackHistogram;
    private volatile CrackClassifierChoice crackChoice=CrackClassifierChoice.DEFAULT;
    private volatile CrackClassifierChoice.Resolution crackResolution;
    private volatile MobileNetCrackModel crackNetwork;
    private int originalWidth,originalHeight;
    private Granulometry.Settings scaleSettings;
    private Granulometry.Distribution distribution;
    // 0.16.0: câmera escolhida e ao vivo com ROI, filtro de luz, escala, gráficos e mapa.
    private java.util.List<CameraChoice.Entry> cameraEntries=new java.util.ArrayList<>();
    private CameraChoice.Entry activeCamera;
    private volatile boolean captureFromFrame;
    private final java.util.concurrent.atomic.AtomicBoolean frameCaptureRequested=new java.util.concurrent.atomic.AtomicBoolean(false);
    private boolean liveScaleMismatchNotified;
    private volatile LiveFrameAnalysis.Light liveLight=LiveFrameAnalysis.Light.OFF;
    private volatile boolean liveShowFiltered=true,liveCharts=true;
    private volatile LiveFrameAnalysis.RoiFraction liveRoiFraction;
    private volatile LiveFrameAnalysis.ScaleTransfer liveScale;
    private final Object liveFrameLock=new Object();
    private Mat lastLiveGray;
    private Granulometry.Distribution liveDistribution;
    private android.content.BroadcastReceiver usbReceiver;
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private Size requestedPhotoSize=new Size(1920,1440);
    private Size effectivePhotoSize;
    private TextView cameraResolutionLabel;
    private static final int PHOTO_JPEG_QUALITY=95;
    private ImageAnalysis analysis;
    private Bitmap photoBitmap,displayBitmap,displaySource;
    private AnalysisWindow displayWindow,photoAnalysisWindow,suggestionWindow;
    private boolean roiNeedsAnalysis;
    private boolean detailMode,detailCompare=true,detailSr;
    private DetailedAnalysis.Report photoDetailReport;
    private Bitmap photoFiltered; // 0.15.2: foto com o filtro de iluminação aplicado na janela analisada (só exibição/relatório)
    private LowLightInfo photoLowLight; // 0.15.0: tratamento de baixa iluminação usado na análise atual (relatório PDF/JSON)
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
                overlay.expire(SystemClock.elapsedRealtime());count.setText("—");
                detail.setText("Aguardando nova análise");if(shapeLabel!=null)shapeLabel.setText("Esfericidade aparente 2D (b/a): —");
            }
            handler.postDelayed(this,500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        restoreParameters();buildInterface();applyParameters();
        liveLight=LiveFrameAnalysis.Light.fromName(getPreferences(MODE_PRIVATE).getString("live_light","OFF"));
        liveShowFiltered=getPreferences(MODE_PRIVATE).getBoolean("live_show_filtered",true);liveCharts=getPreferences(MODE_PRIVATE).getBoolean("live_charts",true);
        nativeReady=OpenCVLoader.initLocal();
        if(!nativeReady){hint.setText("O processamento não iniciou neste aparelho.");capture.setEnabled(false);open.setEnabled(false);return;}
        org.opencv.core.Core.setNumThreads(2);
        try(InputStream model=getAssets().open("crack_forest.pvrf")){crackModel=new CrackModel(model);}
        catch(Exception e){toast("Modelo de trincas indisponível: usando triagem heurística.");}
        // 0.16.0: aceleração por hardware (LiteRT: GPU, depois CPU), desligada por padrão. Ligada, a GPU e a sonda de
        // paridade rodam fora da thread da interface; desligada, nada muda em relação à 0.15.5.
        HardwareAcceleration.setProvider(new LiteRtProvider(this));
        HardwareAcceleration.setEnabled(getPreferences(MODE_PRIVATE).getBoolean("hw_accel",false));
        if(HardwareAcceleration.enabled())worker.execute(this::installNeuralModel);else installNeuralModel();
        worker.execute(this::installSam2); // 0.17.0: SAM 2 (≈125 MB) sempre fora da thread da tela
        restoreVisuals();
        crackLabel.setText(crackModel==null?"Trincas: heurística de reserva":"Trincas: modelo experimental · fotografe");
        handler.post(heartbeat);
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){
                if(mode==Mode.SAVING||mode==Mode.PROCESSING){toast("Aguarde a conclusão da análise ou gravação.");return;}
                if(mode==Mode.PHOTO)returnToCamera();else finish();
            }
        });
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)preview.post(this::bindCamera);
        else requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
    }
    private void buildInterface() {
        // 0.18.0: a foto não encolhe mais quando gráficos e botões aparecem. Ela ocupa toda a altura visível
        // abaixo do cabeçalho; gráficos e ajustes ficam logo abaixo e se chega a eles rolando a tela. A barra
        // Fotografar/Salvar fica fixa no rodapé.
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(Color.rgb(16,26,35));
        shell.setPadding(dp(18),dp(8),dp(18),dp(12));setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v,insets)-> {
            int top,bottom,left,right;
            if(Build.VERSION.SDK_INT>=30){Insets i=insets.getInsets(WindowInsets.Type.systemBars());top=i.top;bottom=i.bottom;left=i.left;right=i.right;}
            else {top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();}
            shell.setPadding(dp(18)+left,dp(8)+top,dp(12)+right,dp(12)+bottom);return insets;
        });
        ScrollView mainScroll=new ScrollView(this);mainScroll.setTag("main_scroll");mainScroll.setFillViewport(true);mainScroll.setVerticalScrollBarEnabled(true);this.mainScroll=mainScroll;
        shell.addView(mainScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        mainScroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        mainScroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob)->{if(b-t!=ob-ot||r-l!=orr-ol)v.post(this::sizeImageFrame);});
        LinearLayout titleRow=row();root.addView(titleRow);
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("PelotaVision",24,Color.WHITE);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);brand.addView(title);
        brand.addView(text("IDENTIFICAÇÃO DE PELOTAS",10,Color.rgb(151,174,187)));
        titleRow.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        badge=text("AO VIVO",11,Color.rgb(82,216,190));badge.setPadding(dp(10),dp(7),dp(10),dp(7));badge.setBackground(shape(Color.rgb(30,58,58),18));titleRow.addView(badge);
        LinearLayout metrics=row();metrics.setPadding(0,dp(10),0,dp(8));root.addView(metrics);
        count=text("—",42,Color.WHITE);count.setTypeface(Typeface.DEFAULT,Typeface.BOLD);metrics.addView(count);
        LinearLayout metricLabels=new LinearLayout(this);metricLabels.setOrientation(LinearLayout.VERTICAL);metricLabels.setPadding(dp(12),0,0,0);
        metricLabels.addView(text("pelotas detectadas",14,Color.WHITE));detail=text("Posicione a câmera",12,Color.rgb(151,174,187));metricLabels.addView(detail);metrics.addView(metricLabels);
        shapeLabel=text("Esfericidade aparente 2D (b/a): —",12,Color.rgb(181,198,208));shapeLabel.setPadding(0,0,0,dp(6));shapeLabel.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Forma projetada das pelotas").setMessage("Índice b/a = eixo menor / eixo maior da elipse. Próximo de 1 significa projeção mais circular. É uma estimativa 2D, não esfericidade 3D.\n\nA média aritmética por número usa apenas elipses ajustadas, inteiras e sem alerta, dentro da área de interesse. Contornos inconclusivos e pelotas de borda são excluídos. A razão não depende de px/mm. Perspectiva, sombras e oclusões podem alterar a estimativa.").setPositiveButton("Entendi",null).show());root.addView(shapeLabel);
        FrameLayout frame=new FrameLayout(this);frame.setTag("image_frame");frame.setBackground(shape(Color.BLACK,18));frame.setClipToOutline(true);root.addView(frame,new LinearLayout.LayoutParams(-1,dp(360)));this.imageFrame=frame;
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        frozen=new ImageView(this);frozen.setScaleType(ImageView.ScaleType.FIT_CENTER);frozen.setBackgroundColor(Color.BLACK);frozen.setVisibility(View.GONE);frame.addView(frozen,new FrameLayout.LayoutParams(-1,-1));
        overlay=new CircleOverlay(this);frame.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        colorMap=new GranulometricColorMap(this);colorMap.setVisibility(View.GONE);frame.addView(colorMap,new FrameLayout.LayoutParams(-1,-1));
        preview.setOnTouchListener((v,event)-> {
            if(event.getAction()==MotionEvent.ACTION_UP&&camera!=null&&mode==Mode.LIVE) {
                try {camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(preview.getMeteringPointFactory().createPoint(event.getX(),event.getY())).setAutoCancelDuration(3,TimeUnit.SECONDS).build());}
                catch(RuntimeException ignored) {}
                v.performClick();return true;
            }
            return true;
        });
        hint=text("Mantenha a câmera parada. Toque na imagem para focar.",12,Color.rgb(181,198,208));hint.setPadding(0,dp(8),0,dp(4));root.addView(hint);
        TextView legend=text("Na foto: vermelho = possível trinca · amarelo = inconclusiva",10,Color.rgb(155,180,189));root.addView(legend);
        LinearLayout crackRow=row();root.addView(crackRow);
        crackLabel=text("Trincas: analisadas ao fotografar",11,Color.rgb(181,198,208));crackRow.addView(crackLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button models=button("Modelos",false);models.setTag("analysis_models");models.setTextSize(11);models.setOnClickListener(v->showAnalysisModels());crackRow.addView(models,new LinearLayout.LayoutParams(dp(80),dp(36)));
        crackSettings=button("Trincas",false);crackSettings.setTextSize(11);crackSettings.setOnClickListener(v->showCrackSettings());crackRow.addView(crackSettings,new LinearLayout.LayoutParams(dp(85),dp(36)));
        overlay.setOnTouchListener((v,event)->{if(mode!=Mode.PHOTO||photoResult==null)return false;if(event.getAction()==MotionEvent.ACTION_UP){v.performClick();reviewCrackAt(event.getX(),event.getY());}return true;});
        LinearLayout scaleRow=row();root.addView(scaleRow);
        scaleLabel=text("Fotografe para calibrar em px/mm",11,Color.rgb(151,174,187));scaleRow.addView(scaleLabel,new LinearLayout.LayoutParams(0,-2,1));
        regionButton=button("Área",false);regionButton.setTextSize(11);regionButton.setOnClickListener(v->{if(mode==Mode.LIVE)showLiveRegionEditor();else showRegionEditor();});scaleRow.addView(regionButton,new LinearLayout.LayoutParams(dp(66),dp(40)));
        calibration=button("Escala / mm",false);calibration.setTextSize(11);calibration.setEnabled(false);
        calibration.setOnClickListener(v->{if(mode==Mode.LIVE)showLiveScale();else showCalibration();});scaleRow.addView(calibration,new LinearLayout.LayoutParams(dp(108),dp(40)));
        roiActions=row();roiActions.setVisibility(View.GONE);root.addView(roiActions);
        segmentRoi=button("Segmentar foto",false);segmentRoi.setTag("segment_active_roi");segmentRoi.setTextSize(11);segmentRoi.setOnClickListener(v->showSegmentationOptions());roiActions.addView(segmentRoi,new LinearLayout.LayoutParams(0,dp(42),1));
        searchRoi=button("Buscar melhor ajuste",false);searchRoi.setTag("search_active_roi");searchRoi.setTextSize(11);searchRoi.setOnClickListener(v->startActiveAnalysis(true));roiActions.addView(searchRoi,new LinearLayout.LayoutParams(0,dp(42),1));
        // 0.15.3: histograma e curva Rosin–Rammler dividem o mesmo espaço, escolhidos por abas (a foto não encolhe).
        chartTabs=row();chartTabs.setVisibility(View.GONE);root.addView(chartTabs);
        histogramTab=text("Histograma",12,Color.WHITE);rrTab=text("Rosin–Rammler",12,Color.WHITE);rrTab.setTag("chart_tab_rr");histogramTab.setTag("chart_tab_histogram");
        for(TextView tab:new TextView[]{histogramTab,rrTab}){tab.setPadding(dp(10),dp(5),dp(10),dp(5));tab.setGravity(android.view.Gravity.CENTER);chartTabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));}
        histogramTab.setOnClickListener(v->selectChart(false));rrTab.setOnClickListener(v->selectChart(true));
        android.widget.FrameLayout chartSlot=new android.widget.FrameLayout(this);this.chartSlot=chartSlot;chartSlot.setVisibility(View.GONE);root.addView(chartSlot,new LinearLayout.LayoutParams(-1,dp(180)));
        histogram=new HistogramView(this);histogram.setOnClickListener(v->showHistogramOptions());chartSlot.addView(histogram,new android.widget.FrameLayout.LayoutParams(-1,-1));
        rosinRammler=new RosinRammlerView(this);rosinRammler.setTag("rosin_rammler_chart");rosinRammler.setOnClickListener(v->showRosinRammlerOptions());chartSlot.addView(rosinRammler,new android.widget.FrameLayout.LayoutParams(-1,-1));
        setChartsVisibility(View.GONE);
        LinearLayout tools=row();root.addView(tools);
        profileLabel=text("Pelotas médias",11,Color.rgb(151,174,187));profileLabel.setTag("suggestion_label");profileLabel.setOnClickListener(v->{if(photoDetailReport!=null&&!roiNeedsAnalysis)showDetailSummary();else showSuggestions();});tools.addView(profileLabel,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox numberBox=new CheckBox(this);numberBox.setText("Números");numberBox.setTextSize(11);numberBox.setTextColor(Color.WHITE);numberBox.setChecked(true);
        numberBox.setOnCheckedChangeListener((button,checked)-> {numbers=checked;if(mode==Mode.PHOTO&&photoResult!=null)overlay.display(photoResult,numbers);else if(mode==Mode.LIVE&&lastLiveResult!=null)overlay.display(lastLiveResult,numbers);});tools.addView(numberBox);
        Button mapBtn=button("Seg.",false);mapBtn.setTag("view_mode");mapBtn.setTextSize(11);mapBtn.setOnClickListener(v->toggleColorMap());mapBtn.setOnLongClickListener(v->{showMapOptions();return true;});mapButton=mapBtn;tools.addView(mapBtn,new LinearLayout.LayoutParams(dp(64),dp(40)));
        torch=button("Luz",false);torch.setTextSize(11);torch.setOnClickListener(v->{if(mode==Mode.PHOTO)showLowLightDialog();else showLiveLight();});tools.addView(torch,new LinearLayout.LayoutParams(dp(58),dp(40)));
        adjust=button("Ajustes",false);adjust.setTextSize(11);adjust.setOnClickListener(v->showAdjustments());tools.addView(adjust,new LinearLayout.LayoutParams(dp(77),dp(40)));
        cameraResolutionLabel=text("Câmera: verificando resolução…",11,Color.rgb(151,174,187));
        cameraResolutionLabel.setOnClickListener(v->showCameraPicker());root.addView(cameraResolutionLabel);
        TextView foot=text("Contagem automática · requer revisão",10,Color.rgb(151,174,187));foot.setGravity(Gravity.CENTER);foot.setPadding(0,dp(8),0,dp(4));root.addView(foot);
        LinearLayout actions=row();actions.setPadding(0,dp(7),0,0);shell.addView(actions);
        capture=button("Fotografar",true);capture.setEnabled(false);capture.setOnClickListener(v->capturePhoto());actions.addView(capture,new LinearLayout.LayoutParams(0,dp(49),1));
        save=button("Salvar foto + dados",true);save.setOnClickListener(v->savePhoto());save.setVisibility(View.GONE);actions.addView(save,new LinearLayout.LayoutParams(0,dp(49),1));
        open=button("Abrir foto",false);open.setOnClickListener(v->openPhoto());LinearLayout.LayoutParams other=new LinearLayout.LayoutParams(dp(112),dp(49));other.leftMargin=dp(8);actions.addView(open,other);
        back=button("Câmera",false);back.setOnClickListener(v->returnToCamera());back.setVisibility(View.GONE);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(95),dp(49));bp.leftMargin=dp(8);actions.addView(back,bp);
    }

    private ScrollView mainScroll;
    private FrameLayout imageFrame;
    /**
     * Altura da área da foto: toda a altura visível da tela rolável abaixo do cabeçalho, menos uma faixa de
     * {@code dp(30)} que deixa à mostra a linha seguinte e indica que há mais conteúdo abaixo. Com foto
     * congelada, não passa da altura em que ela ocupa a largura inteira, para não sobrar faixa preta.
     */
    private void sizeImageFrame(){
        if(mainScroll==null||imageFrame==null)return;
        int viewport=mainScroll.getHeight(),width=imageFrame.getWidth();
        if(viewport<=0||width<=0)return;
        int target=Math.max(dp(240),viewport-imageFrame.getTop()-dp(30));
        Bitmap shown=mode==Mode.PHOTO&&photoBitmap!=null&&!photoBitmap.isRecycled()?photoBitmap:null;
        if(shown!=null&&shown.getWidth()>0){
            int fitted=(int)Math.ceil(width*(double)shown.getHeight()/shown.getWidth());
            target=Math.max(dp(240),Math.min(target,fitted));
        }
        LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)imageFrame.getLayoutParams();
        if(lp.height!=target||lp.weight!=0){lp.height=target;lp.weight=0;imageFrame.setLayoutParams(lp);}
    }
    /** Depois de mostrar uma foto analisada: ajusta a altura e volta ao topo, para a foto aparecer inteira. */
    private void showImageAtTop(){
        if(mainScroll==null)return;
        mainScroll.post(()->{sizeImageFrame();mainScroll.scrollTo(0,0);});
    }
    private void bindCamera() {
        if(binding||!nativeReady||isDestroyed()||isFinishing())return;
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        if(preview.getWidth()==0||preview.getHeight()==0){preview.postDelayed(this::bindCamera,100);return;}
        binding=true;ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()-> {
            binding=false;if(isDestroyed()||isFinishing())return;
            try {
                provider=future.get();provider.unbindAll();camera=null;
                // 0.16.0: câmera escolhida pelo usuário (traseira, frontal ou externa USB/OTG), lembrada entre sessões.
                cameraEntries=CameraChoice.list(provider);
                activeCamera=CameraChoice.pick(cameraEntries,getPreferences(MODE_PRIVATE).getString("camera_id",null));
                if(activeCamera==null)throw new IllegalStateException("Nenhuma câmera disponível.");
                CameraSelector selector=CameraChoice.selector(activeCamera.id);
                // PreviewView espelha a frontal; a análise não. Desfaz o espelho para as marcações coincidirem.
                preview.setScaleX(activeCamera.facing==CameraChoice.FACING_FRONT?-1f:1f);
                Preview usePreview=new Preview.Builder().setTargetRotation(Surface.ROTATION_0).build();usePreview.setSurfaceProvider(preview.getSurfaceProvider());
                // Externa: quadro de análise maior, porque pode virar a própria foto (ver captureFromFrame).
                analysis=new ImageAnalysis.Builder().setTargetResolution(activeCamera.external()?new Size(1280,720):new Size(640,480)).setTargetRotation(Surface.ROTATION_0)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(worker,this::analyzeLiveFrame);
                requestedPhotoSize=activeCamera.maxJpeg!=null?activeCamera.maxJpeg:maximumJpegResolution();
                imageCapture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(PHOTO_JPEG_QUALITY)
                        .setTargetResolution(requestedPhotoSize).setTargetRotation(Surface.ROTATION_0).build();
                captureFromFrame=false;
                try {
                    camera=provider.bindToLifecycle(this,selector,useCases(usePreview,analysis,imageCapture));
                } catch(IllegalArgumentException combination) {
                    // Câmeras EXTERNAL/LEGACY podem não aceitar prévia + análise + foto ao mesmo tempo:
                    // a foto passa a ser o quadro de análise (resolução menor, registrada no rótulo).
                    provider.unbindAll();imageCapture=null;captureFromFrame=true;
                    camera=provider.bindToLifecycle(this,selector,useCases(usePreview,analysis,null));
                }
                capture.setEnabled(true);torch.setEnabled(camera.getCameraInfo().hasFlashUnit());
                cameraResolutionLabel.setText(cameraLabel());
            } catch(Exception e) {capture.setEnabled(false);hint.setText("Câmera indisponível. Você pode abrir uma foto.");cameraResolutionLabel.setText("Câmera: configuração não suportada neste dispositivo · toque para escolher outra");}
        },ContextCompat.getMainExecutor(this));
    }
    private UseCaseGroup useCases(Preview p,ImageAnalysis a,ImageCapture c){
        UseCaseGroup.Builder group=new UseCaseGroup.Builder().addUseCase(p).addUseCase(a);if(c!=null)group.addUseCase(c);
        if(preview.getViewPort()!=null)group.setViewPort(preview.getViewPort());return group.build();
    }
    private String cameraLabel(){
        String name=activeCamera==null?"Câmera":activeCamera.label();
        return name+(captureFromFrame?" · foto = quadro ao vivo":" · foto máx. "+requestedPhotoSize.getWidth()+"×"+requestedPhotoSize.getHeight()+" · JPEG 95%")+" · toque para trocar";
    }
    /** Troca de câmera: desliga a atual e religa com a escolhida (mesmos casos de uso). */
    private void switchCamera(String id){
        getPreferences(MODE_PRIVATE).edit().putString("camera_id",id).apply();
        if(provider!=null)provider.unbindAll();camera=null;liveScaleMismatchNotified=false;
        if(mode!=Mode.LIVE)returnToCamera();
        bindCamera();
    }
    /** O CameraX lê a lista de câmeras ao iniciar; para ver uma USB recém-conectada é preciso reiniciá-lo. */
    @android.annotation.SuppressLint({"VisibleForTests","RestrictedApi"})
    private void refreshCameraList(){
        if(provider==null){bindCamera();return;}
        provider.unbindAll();camera=null;
        ListenableFuture<Void> done;
        try{done=provider.shutdownAsync();}catch(RuntimeException e){toast("Feche e reabra o app para ver a nova câmera.");bindCamera();return;}
        provider=null;done.addListener(this::bindCamera,ContextCompat.getMainExecutor(this));
    }
    // ---------------------------------------------------------------- 0.16.0: ao vivo com tudo da foto

    /** Analisador do ao vivo: mesmo pipeline do Windows (LiveFrameAnalysis): ROI, filtro de luz, escala, gráficos e mapa. */
    private void analyzeLiveFrame(androidx.camera.core.ImageProxy image) {
        Mat gray=null;
        try {
            if(frameCaptureRequested.getAndSet(false)){saveFrameAsPhoto(image);return;}
            long now=SystemClock.elapsedRealtime();if(mode!=Mode.LIVE||now-lastAnalysis<220)return;
            lastAnalysis=now;long revision=epoch;
            DetectionTuner.Option option=new DetectionTuner.Option(profile,sensitivity,method(),beltScene);
            gray=ImageCodec.luma(image);
            synchronized(liveFrameLock){if(lastLiveGray==null)lastLiveGray=new Mat();gray.copyTo(lastLiveGray);}
            LiveFrameAnalysis.RoiFraction roiFraction=liveRoiFraction;
            if(roiFraction==null&&autoRoi)roiFraction=liveAutoFraction(gray,now);
            LiveFrameAnalysis.Request request=new LiveFrameAnalysis.Request(option,roiFraction,liveLight,liveScale,rrBasis(),liveShowFiltered);
            Bitmap filtered=null;
            LiveFrameAnalysis.Output out=LiveFrameAnalysis.run(gray,request);
            try{if(out.filtered!=null){filtered=Bitmap.createBitmap(out.filtered.cols(),out.filtered.rows(),Bitmap.Config.ARGB_8888);org.opencv.android.Utils.matToBitmap(out.filtered,filtered);}}
            finally{out.close();}
            final Bitmap shown=filtered;
            runOnUiThread(()-> {if(!isDestroyed()&&mode==Mode.LIVE&&revision==epoch)showLiveOutput(out,shown);});
        } catch(Exception e) {runOnUiThread(()-> {if(!isDestroyed()&&mode==Mode.LIVE)hint.setText("Falha na análise. Tente fotografar ou abrir uma imagem.");});}
        finally {if(gray!=null)gray.release();image.close();}
    }
    /** 0.18.0: ROI automática no ao vivo, refeita a cada ~1 s e estabilizada entre quadros; vale enquanto o operador não desenhar uma área. */
    private LiveFrameAnalysis.RoiFraction liveAutoFraction(Mat gray,long now){
        int w=gray.cols(),h=gray.rows();
        if(now-lastAutoRoi>1000){
            lastAutoRoi=now;
            try{
                byte[] g=new byte[w*h];Mat c=gray.isContinuous()?gray:gray.clone();
                try{c.get(0,0,g);}finally{if(c!=gray)c.release();}
                AutoRoi.Result r=AutoRoi.detectAnySize(g,w,h);
                liveAutoRoi.update(r.usable()?r:null,w,h);
            }catch(RuntimeException ignored){}
        }
        AutoRoi.Result cur=liveAutoRoi.current();
        if(cur==null||cur.region==null||cur.region.right>w+1e-7||cur.region.bottom>h+1e-7)return null;
        try{return LiveFrameAnalysis.toFraction(cur.region,w,h);}catch(RuntimeException e){return null;}
    }
    private void showLiveOutput(LiveFrameAnalysis.Output out,Bitmap filtered){
        lastLiveResult=out.result;lastResult=SystemClock.elapsedRealtime();
        overlay.display(out.result,numbers);overlay.region(out.roi);
        showCount(out.result,false);
        // Filtro de luz visível: o quadro filtrado cobre a prévia (as marcações continuam nos pixels originais).
        if(filtered!=null){frozen.setImageBitmap(filtered);frozen.setVisibility(View.VISIBLE);}
        else if(frozen.getVisibility()==View.VISIBLE){frozen.setImageDrawable(null);frozen.setVisibility(View.GONE);}
        liveDistribution=out.distribution;
        if(out.distribution!=null&&out.distribution.included>0){
            histogram.display(out.distribution,null,visuals);if(rosinRammler!=null)rosinRammler.display(out.fit);
            if(liveCharts&&chartSlot.getVisibility()!=View.VISIBLE)setChartsVisibility(View.VISIBLE);
            colorMap.display(out.result,out.distribution);colorMap.visuals(visuals);colorMap.viewport(null);
            Granulometry.Distribution d=out.distribution;String u=d.settings.unit();
            detail.setText(String.format(Locale.getDefault(),"D10 %s · D50 %s · D90 %s %s · %d ms",HistogramView.number(d.d10),HistogramView.number(d.d50),HistogramView.number(d.d90),u,out.result.durationMs));
        }
        if(!liveCharts&&chartSlot.getVisibility()==View.VISIBLE)setChartsVisibility(View.GONE);
        String light=out.appliedLight==LowLightPreprocessor.Mode.ORIGINAL?"":" · luz: "+LowLightInfo.label(out.appliedLight);
        AutoRoi.Result auto=liveRoiFraction==null&&autoRoi?liveAutoRoi.current():null;
        scaleLabel.setText((liveRoiFraction!=null?"ROI · ":auto!=null&&auto.region!=null?"ROI auto: "+auto.kind.label+" · ":"")+out.scaleNote+light);
        if(liveScale!=null&&!out.physical()&&!liveScale.settings.pixelsOnly()&&!liveScaleMismatchNotified){liveScaleMismatchNotified=true;toast("A escala não vale para este enquadramento. Recalibre em Escala / mm.");}
        applyViewMode();
    }
    /** Foto a partir do quadro de análise, para câmeras que não aceitam foto simultânea (EXTERNAL/LEGACY). */
    private void saveFrameAsPhoto(androidx.camera.core.ImageProxy image){
        File output=new File(getCacheDir(),"captura_"+System.currentTimeMillis()+".jpg");
        try{
            Bitmap b=image.toBitmap();
            android.graphics.Rect crop=image.getCropRect();
            if(crop!=null&&(crop.width()!=b.getWidth()||crop.height()!=b.getHeight())){Bitmap c=Bitmap.createBitmap(b,crop.left,crop.top,crop.width(),crop.height());b.recycle();b=c;}
            int rotation=image.getImageInfo().getRotationDegrees();
            if(rotation!=0){android.graphics.Matrix m=new android.graphics.Matrix();m.postRotate(rotation);Bitmap r=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);b.recycle();b=r;}
            try(FileOutputStream o=new FileOutputStream(output)){b.compress(Bitmap.CompressFormat.JPEG,PHOTO_JPEG_QUALITY,o);}
            effectivePhotoSize=new Size(b.getWidth(),b.getHeight());b.recycle();
            runOnUiThread(()->cameraResolutionLabel.setText("Foto do quadro ao vivo: "+effectivePhotoSize.getWidth()+"×"+effectivePhotoSize.getHeight()+" · câmera sem foto simultânea"));
            processPhoto(output,"image/jpeg",parameters);
        }catch(Exception e){output.delete();runOnUiThread(()->{if(!isDestroyed()){returnToCamera();toast("Não foi possível fotografar pelo quadro: "+e.getMessage());}});}
    }
    /** Cópia do último quadro analisado (cinza, para ROI e calibração no ao vivo); null se ainda não houve quadro. */
    private Mat liveSnapshot(){synchronized(liveFrameLock){return lastLiveGray==null||lastLiveGray.empty()?null:lastLiveGray.clone();}}
    private Bitmap grayBitmap(Mat gray){Bitmap b=Bitmap.createBitmap(gray.cols(),gray.rows(),Bitmap.Config.ARGB_8888);org.opencv.android.Utils.matToBitmap(gray,b);return b;}
    /** Ao voltar da foto: ROI e escala definidas nela passam a valer no ao vivo (se a foto não tinha, mantém as do ao vivo). */
    private void carryPhotoSettingsToLive(){
        try{
            if(interestRegion!=null&&photoBitmap!=null)liveRoiFraction=LiveFrameAnalysis.toFraction(interestRegion,photoBitmap.getWidth(),photoBitmap.getHeight());
            if(scaleSettings!=null&&!scaleSettings.pixelsOnly()&&originalWidth>0&&originalHeight>0){
                liveScale=new LiveFrameAnalysis.ScaleTransfer(scaleSettings,originalWidth,originalHeight,"da última foto");liveScaleMismatchNotified=false;
            }
        }catch(RuntimeException ignored){}
    }
    private void showLiveRegionEditor(){
        Mat gray=liveSnapshot();if(gray==null){toast("Aguarde o primeiro quadro da câmera.");return;}
        Bitmap frame;try{frame=grayBitmap(gray);}finally{gray.release();}
        InterestRegion initial=LiveFrameAnalysis.roiInFrame(liveRoiFraction,frame.getWidth(),frame.getHeight());
        android.app.Dialog dialog=new android.app.Dialog(this);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(Color.rgb(16,26,35));
        root.addView(text("Área de interesse · ao vivo",22,Color.WHITE));
        root.addView(text("Contagem, gráficos e mapa ao vivo passam a usar só esta área. Ela é guardada em proporção do quadro e vale também ao voltar de uma foto.",13,Color.LTGRAY));
        RegionEditorView editor=new RegionEditorView(this,frame,initial);
        LinearLayout shapes=row();Button rectangle=button("Retângulo",false),circle=button("Círculo",false),reset=button("Restaurar",false);
        shapes.addView(rectangle,new LinearLayout.LayoutParams(0,dp(44),1));shapes.addView(circle,new LinearLayout.LayoutParams(0,dp(44),1));shapes.addView(reset,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(shapes);
        rectangle.setOnClickListener(v->editor.shape(InterestRegion.Shape.RECTANGLE));circle.setOnClickListener(v->editor.shape(InterestRegion.Shape.CIRCLE));reset.setOnClickListener(v->editor.reset());
        root.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=row();Button cancel=button("Cancelar",false),whole=button("Quadro inteiro",false),apply=button("Aplicar",true);
        actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(whole,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(apply,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        cancel.setOnClickListener(v->dialog.dismiss());
        whole.setOnClickListener(v->{liveRoiFraction=null;overlay.region(null);updateControls();dialog.dismiss();});
        apply.setOnClickListener(v->{liveRoiFraction=LiveFrameAnalysis.toFraction(editor.region(),frame.getWidth(),frame.getHeight());updateControls();dialog.dismiss();});
        dialog.setContentView(root);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    /** "Luz" no ao vivo: lanterna e o mesmo filtro de iluminação da foto, com prévia no quadro atual. */
    private void showLiveLight(){
        final LiveFrameAnalysis.Light[] lights=LiveFrameAnalysis.Light.values();
        String[] names=new String[lights.length];for(int i=0;i<lights.length;i++)names[i]=lights[i].label();
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(8),dp(12),dp(8));
        body.addView(text("O filtro só PROPÕE as pelotas; as elipses e as medidas usam os pixels originais do quadro, como na foto.",13,Color.LTGRAY));
        ImageView previewImage=new ImageView(this);previewImage.setAdjustViewBounds(true);body.addView(previewImage,new LinearLayout.LayoutParams(-1,dp(240)));
        android.widget.Spinner spinner=new android.widget.Spinner(this);
        spinner.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));spinner.setSelection(liveLight.ordinal());body.addView(spinner);
        CheckBox showFiltered=new CheckBox(this);showFiltered.setText("Mostrar a imagem filtrada na tela");showFiltered.setTextColor(Color.WHITE);showFiltered.setChecked(liveShowFiltered);body.addView(showFiltered);
        CheckBox charts=new CheckBox(this);charts.setText("Histograma e Rosin–Rammler no ao vivo");charts.setTextColor(Color.WHITE);charts.setChecked(liveCharts);body.addView(charts);
        TextView quality=text("",12,Color.rgb(151,174,187));body.addView(quality);
        Mat snap=liveSnapshot();
        final Runnable render=()->{
            if(snap==null){quality.setText("Sem quadro da câmera ainda.");return;}
            LiveFrameAnalysis.Light l=lights[spinner.getSelectedItemPosition()];
            LowLightPreprocessor.Quality q=LowLightPreprocessor.assess(snap);
            LowLightPreprocessor.Mode m=l==LiveFrameAnalysis.Light.AUTO?(q.needsCorrection()?LowLightPreprocessor.Mode.GAMMA_CLAHE:LowLightPreprocessor.Mode.ORIGINAL):l.fixedMode()==null?LowLightPreprocessor.Mode.ORIGINAL:l.fixedMode();
            Mat out=LowLightPreprocessor.apply(snap,m);
            try{previewImage.setImageBitmap(grayBitmap(out));}finally{out.release();}
            quality.setText(String.format(Locale.getDefault(),"Nível médio %.0f · contraste %.0f · %s",q.mean,q.standardDeviation,q.needsCorrection()?"escuro ou pouco contraste":"iluminação adequada"));
        };
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){try{render.run();}catch(RuntimeException|LinkageError ignored){}}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("Luz e filtro · ao vivo").setView(body)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Aplicar",(d,w)->{liveLight=lights[spinner.getSelectedItemPosition()];liveShowFiltered=showFiltered.isChecked();liveCharts=charts.isChecked();
                getPreferences(MODE_PRIVATE).edit().putString("live_light",liveLight.name()).putBoolean("live_show_filtered",liveShowFiltered).putBoolean("live_charts",liveCharts).apply();updateControls();});
        if(camera!=null&&camera.getCameraInfo().hasFlashUnit())b.setNeutralButton(torchOn?"Desligar lanterna":"Ligar lanterna",(d,w)->toggleTorch());
        AlertDialog dialog=b.create();dialog.setOnDismissListener(d->{if(snap!=null)snap.release();});dialog.show();
    }
    /** "Escala / mm" no ao vivo: usar a da última foto, calibrar pelo recipiente neste quadro, px/mm manual ou remover. */
    private void showLiveScale(){
        String now=liveScale==null?"Sem escala física (histograma em px).":"Atual: "+liveScale.describe(lastLiveResult==null?640:lastLiveResult.width,lastLiveResult==null?480:lastLiveResult.height);
        String[] options={"Calibrar pelo recipiente neste quadro","Informar px/mm deste quadro","Remover escala (voltar a px)"};
        TextView title=text("Escala no ao vivo\n"+now,15,Color.WHITE);title.setPadding(dp(20),dp(16),dp(20),dp(8));
        new AlertDialog.Builder(this).setCustomTitle(title)
            .setItems(options,(d,which)->{
                if(which==0)liveContainerCalibration();
                else if(which==1)liveManualScale();
                else{liveScale=null;toast("Ao vivo em pixels.");}
            }).setNegativeButton("Fechar",null).show();
    }
    private void liveContainerCalibration(){
        Mat gray=liveSnapshot();if(gray==null){toast("Aguarde o primeiro quadro da câmera.");return;}
        EditText mm=new EditText(this);mm.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);mm.setHint("Diâmetro interno (mm)");
        String saved=getPreferences(MODE_PRIVATE).getString("container_mm","");mm.setText(saved);
        new AlertDialog.Builder(this).setTitle("Recipiente no quadro ao vivo").setMessage("A borda interna é detectada automaticamente neste quadro. Use a mesma câmera, altura e zoom das medições.").setView(mm)
            .setNegativeButton("Cancelar",(d,w)->gray.release())
            .setPositiveButton("Detectar",(d,w)->{
                try{
                    double v=parsePositive(mm.getText().toString());getPreferences(MODE_PRIVATE).edit().putString("container_mm",mm.getText().toString()).apply();
                    LiveFrameAnalysis.ScaleTransfer t=LiveFrameAnalysis.containerOnFrame(gray,v,ContainerCalibration.Plane.RIM,binWidthForLive(),true,false);
                    if(t==null){toast("Borda não encontrada com segurança. Enquadre o recipiente inteiro ou use px/mm.");return;}
                    Bitmap shown=grayBitmap(gray);android.graphics.Canvas c=new android.graphics.Canvas(shown);android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(Math.max(2,shown.getWidth()/320f));p.setColor(Color.rgb(82,216,190));
                    double ppm=t.pixelsPerMm(shown.getWidth(),shown.getHeight());
                    if(t.circle!=null)c.drawCircle((float)t.circle.cx,(float)t.circle.cy,(float)t.circle.r,p);
                    ImageView iv=new ImageView(this);iv.setAdjustViewBounds(true);
                    iv.setImageBitmap(shown);
                    new AlertDialog.Builder(this).setTitle("Confirme a borda").setView(iv)
                        .setMessage(String.format(Locale.getDefault(),"%.3f px/mm neste quadro (%s). A escala vale enquanto a câmera não mudar de posição ou zoom.",ppm,t.origin))
                        .setNegativeButton("Descartar",null).setPositiveButton("Usar",(d2,w2)->{liveScale=t;liveScaleMismatchNotified=false;toast("Escala aplicada ao ao vivo.");}).show();
                }catch(RuntimeException e){toast(e.getMessage()==null?"Não foi possível calibrar.":e.getMessage());}
                finally{gray.release();}
            }).show();
    }
    private void liveManualScale(){
        int w=lastLiveResult==null?0:lastLiveResult.width,h=lastLiveResult==null?0:lastLiveResult.height;
        if(w<=0){toast("Aguarde o primeiro quadro da câmera.");return;}
        EditText ppm=new EditText(this);ppm.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);ppm.setHint("px/mm no quadro "+w+"×"+h);
        new AlertDialog.Builder(this).setTitle("px/mm do quadro ao vivo").setView(ppm).setNegativeButton("Cancelar",null)
            .setPositiveButton("Usar",(d,which)->{try{double v=parsePositive(ppm.getText().toString());
                liveScale=new LiveFrameAnalysis.ScaleTransfer(new Granulometry.Settings(v,binWidthForLive(),true,false),w,h,"informada no ao vivo");liveScaleMismatchNotified=false;}
                catch(RuntimeException e){toast(e.getMessage());}}).show();
    }
    /** Classe do histograma do ao vivo: a da última escala usada, senão 1 mm. */
    private double binWidthForLive(){return liveScale!=null&&!liveScale.settings.pixelsOnly()?liveScale.settings.binWidthMm:1.0;}

    /** Diálogo de câmeras: lista, troca, atualizar (USB recém-conectada) e diagnóstico USB/OTG. */
    private void showCameraPicker(){
        java.util.List<CameraChoice.Entry> list=new java.util.ArrayList<>(cameraEntries);
        String[] names=new String[list.size()];int checked=-1;
        for(int i=0;i<list.size();i++){names[i]=list.get(i).label();if(activeCamera!=null&&list.get(i).id.equals(activeCamera.id))checked=i;}
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("Câmera");
        if(names.length==0)b.setMessage("Nenhuma câmera disponível para o app.");
        else b.setSingleChoiceItems(names,checked,(d,which)->{d.dismiss();CameraChoice.Entry e=list.get(which);if(activeCamera==null||!e.id.equals(activeCamera.id))switchCamera(e.id);});
        b.setNeutralButton("Diagnóstico USB",(d,w)->new AlertDialog.Builder(this).setTitle("Câmera USB (OTG)").setMessage(CameraChoice.diagnostics(this,cameraEntries))
                .setPositiveButton("Fechar",null).setNeutralButton("Atualizar lista",(d2,w2)->refreshCameraList()).show());
        b.setNegativeButton("Detalhes da foto",(d,w)->showCameraSettings());
        b.setPositiveButton("Atualizar lista",(d,w)->refreshCameraList());
        b.show();
    }
    /** Conectar/desconectar câmera USB: atualiza a lista; se a ativa saiu, volta para a traseira. */
    private void registerUsbReceiver(){
        if(usbReceiver!=null)return;
        usbReceiver=new android.content.BroadcastReceiver(){@Override public void onReceive(android.content.Context c,Intent i){
            boolean attached=android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction());
            handler.postDelayed(()->{
                if(isDestroyed())return;
                java.util.List<String> usb=CameraChoice.usbVideoDevices(MainActivity.this);
                if(attached&&!usb.isEmpty())toast("Câmera USB conectada: "+usb.get(0)+". Escolha em Câmera (rótulo abaixo da imagem).");
                if(attached||activeCamera!=null&&activeCamera.external())refreshCameraList();
            },1500);
        }};
        android.content.IntentFilter f=new android.content.IntentFilter();
        f.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this,usbReceiver,f,ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    private void unregisterUsbReceiver(){if(usbReceiver!=null){try{unregisterReceiver(usbReceiver);}catch(RuntimeException ignored){}usbReceiver=null;}}
    /** Enumerates actual JPEG stream sizes; CameraX can negotiate a smaller size when combined with preview/analysis. */
    private Size maximumJpegResolution() {
        Size fallback=new Size(1920,1440);
        try {
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
            for(String id:manager.getCameraIdList()) {
                CameraCharacteristics c=manager.getCameraCharacteristics(id);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing==null||facing!=CameraCharacteristics.LENS_FACING_BACK)continue;
                StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map==null)continue;
                Size[] sizes=map.getOutputSizes(ImageFormat.JPEG);
                if(sizes==null)continue;
                for(Size candidate:sizes)
                    if((long)candidate.getWidth()*candidate.getHeight()>(long)fallback.getWidth()*fallback.getHeight())fallback=candidate;
                break;
            }
        }catch(Exception ignored) { /* CameraX will still attempt the safe default. */ }
        return fallback;
    }
    private void showCameraSettings() {
        String actual=effectivePhotoSize==null?"Ainda não medida; fotografe para verificar":effectivePhotoSize.getWidth()+"×"+effectivePhotoSize.getHeight();
        new AlertDialog.Builder(this).setTitle("Configurações da câmera")
                .setMessage("Modo: fotografia (qualidade máxima)\nJPEG: 95%\nResolução solicitada: "+requestedPhotoSize.getWidth()+"×"+requestedPhotoSize.getHeight()+
                        "\nResolução efetiva da última captura: "+actual+
                        "\n\nA prévia e a análise ao vivo usam 640×480 como alvo independente. A análise da foto pode reduzir a imagem para proteger memória; a original é preservada. A resolução máxima anunciada pelo sensor pode não estar disponível para uso simultâneo com a prévia e análise.")
                .setPositiveButton("Fechar",null).show();
    }
    private void showCount(PelletDetector.Result result,boolean photo) {
        count.setText(Integer.toString(result.circles.size()));
        ShapeStatistics shape=ShapeStatistics.of(result);shapeLabel.setText(shape.label()+" · excl.="+shape.excluded);
        int alerts=0;for(PelletDetector.Detection d:result.circles)if(d.needsReview||d.partial)alerts++;
        detail.setText(String.format(Locale.getDefault(),"%d alertas · %d ms%s",alerts,result.durationMs,photo?"":" / análise"));
        hint.setText(result.candidateLimit?"Muitos candidatos. Aproxime a câmera ou ajuste o tamanho.":photo?"Confira as elipses antes de usar a contagem.":"Mantenha a câmera parada. Toque na imagem para focar.");
    }
    private void capturePhoto() {
        if(mode!=Mode.LIVE)return;
        if(captureFromFrame){setBusy(Mode.PROCESSING,"Fotografando pelo quadro…");epoch++;frameCaptureRequested.set(true);return;}
        if(imageCapture==null)return;
        setBusy(Mode.PROCESSING,"Fotografando…");epoch++;
        File output=new File(getCacheDir(),"captura_"+System.currentTimeMillis()+".jpg");
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(output).build(),worker,new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults ignored){
                try {int[] size=ImageCodec.originalUprightSize(output);effectivePhotoSize=new Size(size[0],size[1]);
                    runOnUiThread(()->cameraResolutionLabel.setText("Foto capturada: "+size[0]+"×"+size[1]+" · análise independente · JPEG 95%"));
                } catch(Exception ignoredSize) {effectivePhotoSize=null;}
                processPhoto(output,"image/jpeg",parameters);
            }
            @Override public void onError(ImageCaptureException error){output.delete();runOnUiThread(()-> {if(!isDestroyed()){returnToCamera();toast("Não foi possível fotografar. Tente novamente.");}});}
        });
    }
    private void openPhoto() {
        if(mode==Mode.PROCESSING||mode==Mode.SAVING)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 0.19.0: escolher 2 ou mais fotos da mesma cena abre a rajada (empilhada); 1 foto segue o fluxo normal.
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try {startActivityForResult(intent,OPEN_PHOTO);}catch(Exception e){toast("Nenhum seletor de fotos disponível.");}
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=OPEN_PHOTO||result!=RESULT_OK||data==null)return;
        android.content.ClipData clip=data.getClipData();
        if(clip!=null&&clip.getItemCount()>=2){
            java.util.List<Uri> uris=new java.util.ArrayList<>();for(int i=0;i<clip.getItemCount();i++)uris.add(clip.getItemAt(i).getUri());
            importBurst(uris);return;
        }
        Uri uri=data.getData();if(uri==null&&clip!=null&&clip.getItemCount()==1)uri=clip.getItemAt(0).getUri();
        if(uri==null)return;
        final Uri chosen=uri;setBusy(Mode.PROCESSING,"Abrindo foto…");epoch++;
        worker.execute(()-> {
            File copy=new File(getCacheDir(),"importada_"+System.currentTimeMillis()+".img");
            try {
                try(InputStream in=getContentResolver().openInputStream(chosen);FileOutputStream out=new FileOutputStream(copy)) {
                    if(in==null)throw new java.io.IOException("Imagem indisponível.");
                    byte[] bytes=new byte[65536];long total=0;int n;
                    while((n=in.read(bytes))!=-1){total+=n;if(total>100L*1024*1024)throw new java.io.IOException("Imagem muito grande.");out.write(bytes,0,n);}
                }
                android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(copy.getAbsolutePath(),bounds);
                String mime=bounds.outMimeType;
                if(!"image/jpeg".equals(mime)&&!"image/png".equals(mime)&&!"image/webp".equals(mime))throw new java.io.IOException("Use uma foto JPEG, PNG ou WebP.");
                processPhoto(copy,mime,parameters);
            } catch(Exception e) {copy.delete();runOnUiThread(()-> {if(!isDestroyed()){returnToCamera();toast("Não foi possível abrir a foto: "+e.getMessage());}});}
        });
    }
    /** 0.19.0: rajada escolhida no seletor → laudo + empilhada analisada como foto comum. */
    private void importBurst(java.util.List<Uri> uris) {
        if(uris.size()>BurstStack.MAX_FRAMES){toast("Rajada limitada a "+BurstStack.MAX_FRAMES+" fotos; usando as primeiras.");uris=uris.subList(0,BurstStack.MAX_FRAMES);}
        final java.util.List<Uri> list=new java.util.ArrayList<>(uris);
        setBusy(Mode.PROCESSING,"Rajada: registrando e empilhando "+list.size()+" fotos…");epoch++;
        worker.execute(()-> {
            java.util.List<File> copies=new java.util.ArrayList<>();java.util.List<String> mimes=new java.util.ArrayList<>();
            try {
                long stamp=System.currentTimeMillis();
                for(int i=0;i<list.size();i++){
                    File copy=new File(getCacheDir(),"rajada_"+stamp+"_"+i+".img");copies.add(copy);
                    try(InputStream in=getContentResolver().openInputStream(list.get(i));FileOutputStream out=new FileOutputStream(copy)) {
                        if(in==null)throw new java.io.IOException("Imagem indisponível.");
                        byte[] bytes=new byte[65536];long total=0;int n;
                        while((n=in.read(bytes))!=-1){total+=n;if(total>100L*1024*1024)throw new java.io.IOException("Imagem muito grande.");out.write(bytes,0,n);}
                    }
                    android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(copy.getAbsolutePath(),bounds);
                    String mime=bounds.outMimeType;
                    if(!"image/jpeg".equals(mime)&&!"image/png".equals(mime)&&!"image/webp".equals(mime))throw new java.io.IOException("Use fotos JPEG, PNG ou WebP.");
                    mimes.add(mime);
                }
                BurstImport b=BurstImport.run(copies,mimes,getCacheDir());
                final String laudo=b.result.describe();
                if(b.fused==null){b.deleteFrames();runOnUiThread(()->{if(!isDestroyed()){returnToCamera();new AlertDialog.Builder(this).setTitle("Rajada: nada a empilhar").setMessage(laudo).setPositiveButton("Entendi",null).show();}});return;}
                BurstImport old=photoBurst;photoBurst=b;if(old!=null)old.deleteFrames();
                processPhoto(b.fused,"image/png",parameters);
                runOnUiThread(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle("Rajada · "+BurstStack.label(b.result.diagnostics.verdict)).setMessage(laudo+"\n\nA análise usa a empilhada (mesma grade e escala da foto base). Salvar grava também os quadros e o laudo no JSON.").setPositiveButton("Entendi",null).show();});
            } catch(Exception e) {for(File f:copies)f.delete();runOnUiThread(()-> {if(!isDestroyed()){returnToCamera();toast("Rajada não concluída: "+e.getMessage());}});}
        });
    }
    private void processPhoto(File file,String mime,PelletDetector.Parameters cfg) {processPhoto(file,mime,cfg,false,null);}
    private void processPhoto(File file,String mime,PelletDetector.Parameters cfg,boolean forceSearch,DetectionTuner.Trial cached) {
        if(detailMode&&photoFile!=null&&photoFile.equals(file)){processDetailedPhoto(file,mime,cfg,forceSearch,cached==null?null:cached.option);return;}
        Bitmap bitmap=null;Mat gray=null,fullGray=null;
        final boolean replacingCurrent=photoFile!=null&&photoFile.equals(file);
        try {
            int[] sourceSize=ImageCodec.originalUprightSize(file);
            bitmap=ImageCodec.upright(file);
            fullGray=ImageCodec.gray(bitmap);
            // 0.18.0: foto nova sem área definida recebe a ROI automática antes da primeira análise.
            InterestRegion startRegion=replacingCurrent?interestRegion:null;AutoRoi.Result proposal=replacingCurrent?photoAutoRoi:null;
            if(!replacingCurrent&&autoRoi){
                try{
                    byte[] g=new byte[fullGray.cols()*fullGray.rows()];Mat c=fullGray.isContinuous()?fullGray:fullGray.clone();
                    try{c.get(0,0,g);}finally{if(c!=fullGray)c.release();}
                    proposal=AutoRoi.detectAnySize(g,fullGray.cols(),fullGray.rows());
                    if(proposal.usable()){new AnalysisWindow(bitmap.getWidth(),bitmap.getHeight(),proposal.region).requireDetectable();startRegion=proposal.region;}
                }catch(RuntimeException ignored){startRegion=null;}
            }
            final AutoRoi.Result autoProposal=proposal;
            AnalysisWindow window=new AnalysisWindow(bitmap.getWidth(),bitmap.getHeight(),startRegion);window.requireDetectable();
            gray=window.region==null?fullGray:window.cropGray(fullGray);
            DetectionTuner.Option current=replacingCurrent&&photoParameters==cfg&&photoOption!=null?photoOption:new DetectionTuner.Option(profile,sensitivity,method(),beltScene);
            DetectionTuner.Report suggestions=replacingCurrent&&window.sameScope(suggestionWindow)?photoSuggestions:null;
            DetectionTuner.Option selected=current;PelletDetector.Result local;
            if(cached!=null){if(!window.sameScope(suggestionWindow))throw new IllegalArgumentException("A área mudou. Execute a busca novamente.");selected=cached.option;local=cached.result;}
            else if(forceSearch||(!replacingCurrent&&autoSuggest)){
                java.util.concurrent.atomic.AtomicBoolean cancel=new java.util.concurrent.atomic.AtomicBoolean(false);tuningCancel=cancel;
                runOnUiThread(()->showTuneProgress(cancel));
                DetectionAssessment.Context context=new DetectionAssessment.Context(replacingCurrent?scaleSettings:null,sourceSize[0],sourceSize[1],null,minimumExpectedMm,window);
                suggestions=DetectionTuner.search(gray,current,context,()->cancel.get()||isDestroyed(),(done,total,label)->runOnUiThread(()->updateTuneProgress(done,total,label)));
                DetectionTuner.Trial chosen=suggestions.cancelled?suggestions.find(current.id):suggestions.best();
                if(chosen!=null&&chosen.result!=null){selected=chosen.option;local=chosen.result;}else local=DetectionTuner.detect(gray,current,window);
            }else local=DetectionTuner.detect(gray,current,window);
            // Adaptive low-light proposals only on a user-selected ROI; original pixels remain
            // the sole source for final ellipse fitting and crack classification.
            LowLightPreprocessor.Quality lightQuality=LowLightPreprocessor.assess(gray);
            LowLightInfo lightInfo=LowLightInfo.notApplied(lightQuality,cached!=null?"result reused from suggestion search":window.region==null?"low-light recovery runs only on a selected ROI":"illumination adequate; original kept");
            if(cached==null&&window.region!=null&&(selectedLowLightMode!=null||lightQuality.needsCorrection())){
                DetectionAssessment.Context lowContext=new DetectionAssessment.Context(replacingCurrent?scaleSettings:null,sourceSize[0],sourceSize[1],null,minimumExpectedMm,window);
                if(selectedLowLightMode==null){
                    LowLightRecovery.Result recovered=LowLightRecovery.analyze(gray,selected,window,lowContext);
                    local=recovered.pellets;lightInfo=LowLightInfo.automatic(recovered);
                }else if(selectedLowLightMode==LowLightPreprocessor.Mode.ORIGINAL){lightInfo=LowLightInfo.manual(LowLightPreprocessor.Mode.ORIGINAL,lightQuality);
                }else if(selectedLowLightMode!=LowLightPreprocessor.Mode.ORIGINAL){lightInfo=LowLightInfo.manual(selectedLowLightMode,lightQuality);
                    Mat enhanced=LowLightPreprocessor.apply(gray,selectedLowLightMode);
                    try {
                        PelletDetector.Result seeds=DetectionTuner.seeds(enhanced,selected,selected.parameters.workSide(1024),12);
                        local=window.filterLocal(new EllipseFitter().refine(gray,seeds,selected.belt));
                    } finally {enhanced.release();}
                }
            }
            local=Sam2Refiner.applyIfEnabled(gray,local,()->isDestroyed()); // 0.17.0: máscaras SAM 2 (igual ao Windows)
            CrackAnalysis cracks=window.toGlobal(crackDetector().detect(gray,local,crackSensitivity));
            PelletDetector.Result result=window.toGlobal(local);Bitmap ready=bitmap;
            // 0.15.2: com realce aplicado, a tela, a imagem segmentada, o mapa e o PDF mostram a imagem filtrada.
            Bitmap filteredView=null;
            if(lightInfo.enhanced())try{filteredView=FilteredPhoto.render(bitmap,gray,window,lightInfo.mode);}catch(RuntimeException|OutOfMemoryError e){filteredView=null;}
            final Bitmap filteredReady=filteredView;
            final DetectionTuner.Report searchReport=suggestions;final DetectionTuner.Option selectedOption=selected;final LowLightInfo analyzedLight=lightInfo;
            runOnUiThread(()-> {
                closeTuneProgress();
                if(isDestroyed()||isFinishing()){ready.recycle();if(filteredReady!=null)filteredReady.recycle();if(!replacingCurrent)file.delete();return;}
                Granulometry.Settings previousScale=replacingCurrent?scaleSettings:null;
                boolean referenceInvalidated=previousScale!=null&&previousScale.selectedObject!=null&&!window.keepsReference(previousScale.selectedObject,result,sourceSize[0],sourceSize[1]);
                if(referenceInvalidated)previousScale=null;
                if(previousScale!=null&&previousScale.beltReference!=null&&!previousScale.beltReference.matches(sourceSize[0],sourceSize[1],result.width,result.height))previousScale=null;
                if(previousScale!=null&&previousScale.containerReference!=null&&!previousScale.containerReference.matches(sourceSize[0],sourceSize[1],result.width,result.height))previousScale=null;
                if(replacingCurrent)photoFile=null;
                releasePreviousPhoto();photoBitmap=ready;photoFiltered=filteredReady;photoFile=file;photoMime=mime;photoResult=result;photoCracks=cracks;photoParameters=selectedOption.parameters;photoOption=selectedOption;photoSuggestions=searchReport;
                photoAnalysisWindow=window;suggestionWindow=searchReport==null?null:window;roiNeedsAnalysis=false;
                originalWidth=sourceSize[0];originalHeight=sourceSize[1];scaleSettings=previousScale;
                photoAllResult=result;photoAllCracks=cracks;photoLowLight=analyzedLight;interestRegion=window.region;cropRoiReport=interestRegion!=null;photoAutoRoi=autoProposal;
                frozen.setVisibility(View.VISIBLE);mode=Mode.PHOTO;applyRegion(interestRegion);showImageAtTop();
                if(!replacingCurrent&&autoProposal!=null&&autoProposal.usable()&&AnalysisWindow.sameRegion(window.region,autoProposal.region))toast("Área automática: "+autoProposal.describe());
                if(searchReport!=null)hint.setText((window.region==null?"Foto":"ROI")+": "+(searchReport.cancelled?"busca cancelada; aplicado o ajuste atual.":searchReport.weak()?"sugestão com pouca evidência; confira os contornos.":"ajuste sugerido aplicado. Toque em Sugestão para comparar.")+(minimumExpectedMm>0&&scaleSettings==null?" Calibre pela medida real para verificar "+HistogramView.number(minimumExpectedMm)+" mm.":""));
                if(referenceInvalidated){hint.setText("O contorno da referência mudou. Toque novamente no objeto para calibrar.");toast("Referência alterada pela reanálise; escala removida.");}
                if(camera!=null&&torchOn){camera.getCameraControl().enableTorch(false);torchOn=false;torch.setText("Luz");}
            });
        } catch(Exception|OutOfMemoryError e) {
            if(bitmap!=null)bitmap.recycle();if(!replacingCurrent)file.delete();
            runOnUiThread(()-> {if(!isDestroyed()){closeTuneProgress();if(replacingCurrent){mode=Mode.PHOTO;applyRegion(interestRegion);hint.setText("Não foi possível analisar. Sua foto e a ROI foram mantidas; tente novamente.");}else returnToCamera();toast("Não foi possível analisar a foto.");}});
        } finally {if(gray!=null&&gray!=fullGray)gray.release();if(fullGray!=null)fullGray.release();}
    }
    private void showSegmentationOptions(){
        if(mode!=Mode.PHOTO||photoBitmap==null)return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(10),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        content.addView(text("Detalhada lê a área da foto original e analisa blocos sobrepostos. Pode demorar mais. A escala e as coordenadas do relatório são preservadas.",13,Color.LTGRAY));
        CheckBox compare=new CheckBox(this);compare.setTag("detail_compare");compare.setText("Comparar original, contraste local e redução leve de ruído");compare.setTextColor(Color.WHITE);compare.setChecked(detailCompare);content.addView(compare);
        CheckBox search=new CheckBox(this);search.setTag("detail_search");search.setText("Buscar configuração de detecção antes dos blocos");search.setTextColor(Color.WHITE);search.setChecked(true);content.addView(search);
        CheckBox sr=new CheckBox(this);sr.setTag("detail_sr");sr.setText("Testar super-resolução FSRCNN 2× (experimental)");sr.setTextColor(Color.WHITE);sr.setChecked(detailSr);content.addView(sr);
        content.addView(text("SR só propõe contornos. Elipses são reajustadas e trincas são analisadas nos pixels originais. Propostas extras de SR ficam com alerta. Mais marcações não provam maior precisão.",12,Color.LTGRAY));
        content.addView(text("Leitura limitada a 8 megapixels por área para proteger a memória; reduções são informadas. Nenhum resultado parcial é salvo ao cancelar.",12,Color.LTGRAY));
        new AlertDialog.Builder(this).setTitle("Como segmentar esta área?").setView(scroll).setNegativeButton("Cancelar",null).setNeutralButton("Rápida",(d,w)->{detailMode=false;startActiveAnalysis(false);}).setPositiveButton("Detalhada",(d,w)->{detailMode=true;detailCompare=compare.isChecked();detailSr=sr.isChecked();startActiveAnalysis(search.isChecked());}).show();
    }
    private void showDetailSummary(){
        DetailedAnalysis.Report r=photoDetailReport;if(r==null)return;
        String message=r.summary()+"\nAjuste: "+r.optionId+"\nSem tratamento: "+r.baselineCount+" marcações; combinação escolhida: "+r.selectedCount+".\nBlocos escolhidos: original "+r.treatments[0]+", contraste "+r.treatments[1]+", ruído leve "+r.treatments[2]+", SR "+r.treatments[3]+".\n\nComparação heurística, sem contagem verdadeira de referência. Os contornos e as trincas usam a foto original; SR não é uma medição independente.";
        new AlertDialog.Builder(this).setTitle("Segmentação detalhada").setMessage(message).setPositiveButton("Fechar",null).setNeutralButton("Segmentar novamente",(d,w)->showSegmentationOptions()).show();
    }
    /** Liga/desliga a aceleração e recarrega as redes na fila de análise (nunca no meio de uma análise). */
    private void setAcceleration(boolean on,TextView note){
        getPreferences(MODE_PRIVATE).edit().putBoolean("hw_accel",on).apply();
        HardwareAcceleration.setEnabled(on);
        note.setText(on?"Carregando as redes no LiteRT e conferindo a paridade…":"Voltando ao OpenCV DNN…");
        worker.execute(()->{
            installNeuralModel();
            MobileNetCrackModel net=crackNetwork;crackNetwork=null;if(net!=null)net.close(); // recarrega no próximo uso
            final String st=HardwareAcceleration.status();
            runOnUiThread(()->note.setText(accelerationText(st)));
        });
    }
    private static String sam2Text(){
        Sam2Refiner.Report r=Sam2Refiner.lastReport();
        return Sam2Refiner.status()+(r==null?"":"\nÚltima análise: "+r.summary())+"\n\nO método escolhido acima localiza as pelotas; o SAM 2 desenha a máscara de cada uma e o tamanho passa a ser a área dela. Máscaras ruins mantêm a elipse do detector. Mesmo modelo e mesmo motor (ONNX Runtime, CPU) do Windows. Leva alguns segundos por foto e não roda no ao vivo.";
    }
    private static String accelerationText(String status){
        return status+"\n\nSó as redes neurais (StarDist e MobileNetV3) mudam de motor, e só se derem o mesmo resultado do OpenCV DNN numa sonda de paridade. Bordas, LoG e super-resolução continuam na CPU.";
    }
    /** Copia os ONNX dos assets (sempre substituídos) e instala: bancada e, em slot separado, correia. Ausência ou falha só desativa o modelo afetado. */
    private void installNeuralModel(){
        try{NeuralPelletDetector.install(copyAsset(NeuralPelletDetector.MODEL),readAsset(NeuralPelletDetector.METADATA));}catch(Exception e){android.util.Log.w("PelotaVision","Rede neural indisponível",e);}
        try{NeuralPelletDetector.installBelt(copyAsset(NeuralPelletDetector.BELT_MODEL),readAsset(NeuralPelletDetector.BELT_METADATA));}catch(Exception e){android.util.Log.w("PelotaVision","Rede neural de correia indisponível; correia usa o modelo de bancada",e);}
    }
    /**
     * 0.17.0: instala o SAM 2 (mesmo ONNX e mesmo ONNX Runtime do Windows). Procura nos assets do APK; se não
     * estiver lá, em Android/data/br.com.pelotavision/files/modelos (cópia por USB). Ausente: refino indisponível.
     */
    private void installSam2(){
        String meta="{}";File enc=new File(getFilesDir(),"sam2/"+Sam2Refiner.ENCODER),dec=new File(getFilesDir(),"sam2/"+Sam2Refiner.DECODER);
        try{
            if(assetExists(Sam2Refiner.METADATA)){meta=readAsset(Sam2Refiner.METADATA);enc=copyAssetOnce(Sam2Refiner.ENCODER);dec=copyAssetOnce(Sam2Refiner.DECODER);}
            else{File ext=getExternalFilesDir("modelos");if(ext!=null&&new File(ext,Sam2Refiner.METADATA).isFile()){meta=new String(java.nio.file.Files.readAllBytes(new File(ext,Sam2Refiner.METADATA).toPath()),java.nio.charset.StandardCharsets.UTF_8);enc=new File(ext,Sam2Refiner.ENCODER);dec=new File(ext,Sam2Refiner.DECODER);}}
        }catch(Exception e){android.util.Log.w("PelotaVision","SAM 2: falha ao copiar o modelo",e);}
        Sam2Refiner.install(enc,dec,meta,OrtEngine.factory(false));
        Sam2Refiner.setEnabled(getPreferences(MODE_PRIVATE).getBoolean("sam2",true));
        android.util.Log.i("PelotaVision",Sam2Refiner.status());
    }
    private boolean assetExists(String name){try(InputStream ignored=getAssets().open(name)){return true;}catch(java.io.IOException e){return false;}}
    /** Copia um asset grande (não comprimido) só quando o tamanho mudou; o SHA-256 é conferido na instalação. */
    private File copyAssetOnce(String name)throws java.io.IOException{File dir=new File(getFilesDir(),"sam2");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("sem pasta "+dir);File target=new File(dir,name);
        long length=-1;try(android.content.res.AssetFileDescriptor fd=getAssets().openFd(name)){length=fd.getLength();}catch(java.io.IOException ignored){}
        if(length>0&&target.isFile()&&target.length()==length)return target;
        File tmp=new File(dir,name+".tmp");
        try(InputStream input=getAssets().open(name);java.io.OutputStream output=new java.io.FileOutputStream(tmp)){byte[] buffer=new byte[1<<16];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
        if(target.exists()&&!target.delete())throw new java.io.IOException("não apagou "+target);if(!tmp.renameTo(target))throw new java.io.IOException("não renomeou "+tmp);return target;}
    private File copyAsset(String name)throws java.io.IOException{File target=new File(getCacheDir(),name);
        try(InputStream input=getAssets().open(name);java.io.OutputStream output=new java.io.FileOutputStream(target)){byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}return target;}
    private String readAsset(String name)throws java.io.IOException{try(InputStream input=getAssets().open(name)){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);return new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}}
    private String superResolutionModel()throws java.io.IOException {
        File target=new File(getCacheDir(),FsrcnnUpscaler.MODEL);
        // The file is replaced from the packaged asset, so no stale/downloaded model can silently change inference.
        try(InputStream input=getAssets().open(FsrcnnUpscaler.MODEL);java.io.OutputStream output=new java.io.FileOutputStream(target)){byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}return target.toString();
    }
    private void processDetailedPhoto(File file,String mime,PelletDetector.Parameters cfg,boolean search,DetectionTuner.Option supplied){
        Bitmap ready=null;OriginalRoi.Input input=null;Mat previewGray=null,small=null;final boolean compare=detailCompare,sr=detailSr;java.util.concurrent.atomic.AtomicBoolean cancel=new java.util.concurrent.atomic.AtomicBoolean(false);tuningCancel=cancel;
        try{
            int[] source=ImageCodec.originalUprightSize(file);ready=ImageCodec.upright(file);AnalysisWindow scope=new AnalysisWindow(ready.getWidth(),ready.getHeight(),interestRegion);scope.requireDetectable();
            DetectionTuner.Option selected=supplied!=null?supplied:photoParameters==cfg&&photoOption!=null?photoOption:new DetectionTuner.Option(profile,sensitivity,method(),beltScene);DetectionTuner.Report report=null;
            runOnUiThread(()->{showTuneProgress(cancel);if(tuneProgress!=null){tuneProgress.setTitle("Segmentação detalhada");tuneProgress.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Cancelar processamento");((TextView)tuneProgress.getWindow().getDecorView().findViewWithTag("tuning_budget_note")).setText("Original + blocos sobrepostos. Até 3 minutos nos blocos, verificados entre etapas. Cancelar mantém o resultado anterior.");}});
            input=OriginalRoi.decode(file,ready.getWidth(),ready.getHeight(),scope.region);
            if(search){final DetailGeometry map=input.geometry;java.util.List<TilePlan.Tile> nativeTiles=TilePlan.create(map.width,map.height,sr);TilePlan.Tile representative=nativeTiles.get(0);double distance=Double.POSITIVE_INFINITY;for(TilePlan.Tile tile:nativeTiles){double d=Math.hypot(tile.left+tile.width/2.-map.width/2.,tile.top+tile.height/2.-map.height/2.);if(d<distance){distance=d;representative=tile;}}
                Mat view=input.gray.submat(representative.top,representative.top+representative.height,representative.left,representative.left+representative.width);small=view.clone();view.release();report=DetectionTuner.search(small,selected,DetectionAssessment.Context.tile(scaleSettings,source[0],source[1],scope.region,minimumExpectedMm,map,representative),()->cancel.get()||isDestroyed(),(done,total,label)->runOnUiThread(()->updateTuneProgress(done,total,"Pré-ajuste no bloco original · "+label)));if(cancel.get())throw new java.util.concurrent.CancellationException();if(report.best()!=null)selected=report.best().option;}
            FsrcnnUpscaler upscaler=sr?new FsrcnnUpscaler(superResolutionModel()):null;
            DetailedAnalysis.Output analyzed=DetailedAnalysis.run(input.gray,input.geometry,selected,compare,upscaler,scaleSettings,source[0],source[1],minimumExpectedMm,crackDetector(),crackSensitivity,()->cancel.get()||isDestroyed(),(done,total,label)->runOnUiThread(()->updateTuneProgress(done,total,label)));
            final Bitmap displayed=ready;final DetailedAnalysis.Report detailedReport=analyzed.report.withSearch(report);final DetectionTuner.Option chosen=selected;
            runOnUiThread(()->{closeTuneProgress();if(isDestroyed()||isFinishing()){displayed.recycle();return;}if(cancel.get()){displayed.recycle();mode=Mode.PHOTO;applyRegion(interestRegion);hint.setText("Processamento cancelado; resultado anterior preservado.");return;}Granulometry.Settings previous=scaleSettings;boolean changed=previous!=null&&previous.selectedObject!=null&&!scope.keepsReference(previous.selectedObject,analyzed.result,source[0],source[1]);if(changed)previous=null;
                photoFile=null;releasePreviousPhoto();photoBitmap=displayed;photoFile=file;photoMime=mime;originalWidth=source[0];originalHeight=source[1];scaleSettings=previous;photoAllResult=analyzed.result;photoAllCracks=analyzed.cracks;photoParameters=chosen.parameters;photoOption=chosen;photoSuggestions=null;suggestionWindow=null;photoAnalysisWindow=scope;photoDetailReport=detailedReport;photoLowLight=null;detailMode=true;detailCompare=compare;detailSr=sr;interestRegion=scope.region;cropRoiReport=scope.region!=null;roiNeedsAnalysis=false;frozen.setVisibility(View.VISIBLE);mode=Mode.PHOTO;applyRegion(interestRegion);showImageAtTop();
                hint.setText(analyzed.report.summary()+". Toque em Detalhada para comparar. "+(changed?"O contorno da referência mudou; calibre novamente.":"Confira os contornos antes de salvar."));if(changed)toast("Referência alterada; escala removida.");
            });
        }catch(Exception|OutOfMemoryError e){if(ready!=null)ready.recycle();final String reason=e instanceof java.util.concurrent.CancellationException?"Processamento cancelado; resultado anterior preservado.":e instanceof IllegalArgumentException&&e.getMessage()!=null?e.getMessage():"Não foi possível concluir a análise detalhada. Reduza a ROI ou desative SR e tente novamente.";runOnUiThread(()->{if(!isDestroyed()){closeTuneProgress();mode=Mode.PHOTO;applyRegion(interestRegion);hint.setText(reason);toast(reason);}});
        }finally{if(input!=null)input.close();if(small!=null&&small!=previewGray)small.release();if(previewGray!=null)previewGray.release();}
    }
    /** 0.15.3: antes de gravar, pede o nome do teste e sugere a pasta com a data e a hora deste momento. */
    private void savePhoto() {
        if(mode!=Mode.PHOTO||photoBitmap==null||photoResult==null||photoFile==null)return;
        if(roiNeedsAnalysis){toast("Segmente a área ativa antes de salvar.");return;}
        final java.util.Date[] stamp={new java.util.Date()};
        final boolean[] edited={false},programmatic={false};
        String lastTest=getPreferences(MODE_PRIVATE).getString("last_test_name","");
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        content.addView(text("Nome do teste (opcional)",14,Color.WHITE));
        EditText test=new EditText(this);test.setTag("save_test_name");test.setSingleLine();test.setTextColor(Color.WHITE);test.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);test.setHint("Ex.: Disco 3 · umidade 9,2%");test.setText(lastTest);content.addView(test);
        content.addView(text("Pasta desta gravação",14,Color.WHITE));
        EditText folder=new EditText(this);folder.setTag("save_folder");folder.setSingleLine();folder.setTextColor(Color.WHITE);folder.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);folder.setText(SaveNaming.suggestedFolder(stamp[0],lastTest));content.addView(folder);
        TextView where=text("",12,Color.LTGRAY);where.setTag("save_where");content.addView(where);
        Runnable preview=()->{try{String f=SaveNaming.sanitizeFolder(folder.getText().toString());where.setText("Imagens: Pictures/PelotaVision/"+f+"\nDados e PDF: Download/PelotaVision/"+f);}catch(IllegalArgumentException e){where.setText(e.getMessage());}};
        Runnable suggest=()->{programmatic[0]=true;folder.setText(SaveNaming.suggestedFolder(stamp[0],test.getText().toString()));programmatic[0]=false;preview.run();};
        test.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence c,int a,int b,int d){}public void onTextChanged(CharSequence c,int a,int b,int d){}public void afterTextChanged(android.text.Editable e){if(!edited[0])suggest.run();}});
        folder.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence c,int a,int b,int d){}public void onTextChanged(CharSequence c,int a,int b,int d){}public void afterTextChanged(android.text.Editable e){if(!programmatic[0])edited[0]=true;preview.run();}});
        preview.run();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Salvar foto + dados").setView(scroll)
            .setNegativeButton("Cancelar",null).setNeutralButton("Hora atual",null).setPositiveButton("Salvar",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{stamp[0]=new java.util.Date();edited[0]=false;suggest.run();});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String name;try{name=SaveNaming.sanitizeFolder(folder.getText().toString());}catch(IllegalArgumentException e){where.setText(e.getMessage());return;}
                String testName=SaveNaming.cleanTestName(test.getText().toString());
                getPreferences(MODE_PRIVATE).edit().putString("last_test_name",testName==null?"":testName).apply();
                dialog.dismiss();savePhoto(name,testName);});
        });
        dialog.show();
    }
    private void savePhoto(String outputFolder,String testName) {
        if(mode!=Mode.PHOTO||photoBitmap==null||photoResult==null||photoFile==null)return;
        if(roiNeedsAnalysis){toast("Segmente a área ativa antes de salvar.");return;}
        refreshDistribution();
        if(distribution==null){toast("Não foi possível montar o histograma. Confira a escala.");return;}
        Bitmap copy,filteredCopy=null;
        try {copy=photoBitmap.copy(Bitmap.Config.ARGB_8888,false);}
        catch(OutOfMemoryError e){toast("Memória insuficiente para salvar. Feche outros aplicativos.");return;}
        Bitmap shown=shownPhoto();
        if(shown!=null&&shown!=photoBitmap)try{filteredCopy=shown.copy(Bitmap.Config.ARGB_8888,false);}catch(OutOfMemoryError e){filteredCopy=null;toast("Memória insuficiente para a imagem filtrada; o relatório usará a original.");}
        final Bitmap exportedFiltered=filteredCopy;
        File source=photoFile;String mime=photoMime;PelletDetector.Result result=photoResult;PelletDetector.Parameters cfg=photoParameters;
        Granulometry.Distribution exportedDistribution=distribution;CrackAnalysis exportedCracks=photoCracks;final ReportVisuals exportedVisuals=visuals;final CrackClassifierChoice.Resolution exportedChoice=crackResolution;final MobileNetCrackModel net=crackNetwork;final String exportedNetwork=net==null?null:net.id;
        InterestRegion exportedRegion=interestRegion;final AutoRoi.Result exportedAuto=photoAutoRoi;int outside=regionExcludedCount();final boolean exportedCrop=cropRoiReport;final DetailedAnalysis.Report exportedDetail=photoDetailReport;final LowLightInfo exportedLight=photoLowLight;
        DetectionTuner.Report exportedSuggestions=rankedSuggestions();DetectionTuner.Option exportedOption=photoOption;
        final RosinRammler.Basis exportedBasis=rrBasis();
        final BurstImport exportedBurst=photoBurst!=null&&photoBurst.fused!=null&&photoBurst.fused.equals(source)?photoBurst:null;
        setBusy(Mode.SAVING,"Salvando…");
        worker.execute(()-> {
            try {
                ResultSaver.SavedResult saved=ResultSaver.save(getApplicationContext(),copy,source,mime,result,cfg,exportedCracks,exportedDistribution,exportedRegion,outside,exportedSuggestions,exportedOption,exportedCrop,exportedDetail,exportedVisuals,exportedChoice,exportedNetwork,exportedLight,exportedFiltered,outputFolder,testName,exportedBasis,exportedAuto,exportedBurst);
                runOnUiThread(()-> {if(!isDestroyed()){
                    mode=Mode.PHOTO;updateControls();final String dir=saved.folder==null?"PelotaVision":"PelotaVision/"+saved.folder;
                    hint.setText(saved.pdf!=null?"Relatório PDF salvo em Downloads / "+dir+".":"Histograma salvo no arquivo _resultado.png. Dados em Downloads / "+dir+".");
                    new AlertDialog.Builder(this).setTitle("Resultado salvo")
                        .setMessage((testName==null?"":"Teste: "+testName+"\n")+"Imagens em Pictures/"+dir+"; dados e PDF em Download/"+dir+".\n\n"+(saved.pdf!=null?"Relatório PDF: "+saved.base+"_relatorio.pdf (resumo, iluminação, modelos, classes e uma figura por imagem: original, filtrada, segmentada, mapa e histograma).\n\n":"O PDF não pôde ser gerado; os demais arquivos foram salvos.\n\n")+"O relatório "+saved.base+"_resultado.png contém a imagem sem marcações, a imagem com elipses e possíveis trincas e o histograma (todas × trincadas). O mapa granulométrico com legenda fica em "+saved.base+"_mapa.png. Quando selecionada, a ROI é ampliada nos dois painéis. O arquivo _original preserva os bytes da entrada."+(exportedFiltered!=null?"\n\nFiltro de iluminação aplicado: a imagem filtrada está em "+saved.base+"_filtrada.png e a segmentação e o mapa foram desenhados sobre ela. As medidas seguem nos pixels originais.":""))
                        .setNegativeButton("Fechar",null).setNeutralButton("Abrir imagem",(d,w)->openSavedImage(saved.image)).setPositiveButton(saved.pdf!=null?"Abrir PDF":"OK",(d,w)->{if(saved.pdf!=null)openSavedPdf(saved.pdf);}).show();
                }});
            } catch(Exception|OutOfMemoryError e) {runOnUiThread(()-> {if(!isDestroyed()){mode=Mode.PHOTO;updateControls();toast("Falha ao salvar: "+e.getMessage());}});}
            finally {copy.recycle();if(exportedFiltered!=null)exportedFiltered.recycle();if(isDestroyed())source.delete();}
        });
    }
    private void openSavedPdf(Uri pdf) {
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(pdf,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(intent);}catch(android.content.ActivityNotFoundException e){toast("Nenhum leitor de PDF instalado. O arquivo está em Downloads / PelotaVision.");}
    }
    private void openSavedImage(Uri image) {
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(image,"image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(intent);}catch(android.content.ActivityNotFoundException e){toast("Abra o arquivo _resultado.png na Galeria / PelotaVision.");}
    }
    private void returnToCamera() {
        epoch++;mode=Mode.LIVE;lastResult=0;lastLiveResult=null;liveDistribution=null;releasePreviousPhoto();hideColorMap(true);
        frozen.setVisibility(View.GONE);overlay.clear();showImageAtTop();badge.setText("AO VIVO");count.setText("—");shapeLabel.setText("Esfericidade aparente 2D (b/a): —");detail.setText("Aguardando análise");
        hint.setText("Mantenha a câmera parada. Toque na imagem para focar.");updateControls();
    }
    private void releasePreviousPhoto() {
        carryPhotoSettingsToLive();
        frozen.setImageDrawable(null);
        if(displayBitmap!=null&&displayBitmap!=photoBitmap&&displayBitmap!=photoFiltered&&!displayBitmap.isRecycled())displayBitmap.recycle();displayBitmap=displaySource=null;
        if(photoFiltered!=null){if(!photoFiltered.isRecycled())photoFiltered.recycle();photoFiltered=null;}displayWindow=photoAnalysisWindow=suggestionWindow=null;roiNeedsAnalysis=false;photoDetailReport=null;detailMode=false;
        if(overlay!=null)overlay.viewport(null);
        if(photoBitmap!=null){photoBitmap.recycle();photoBitmap=null;}
        if(photoFile!=null){photoFile.delete();photoFile=null;}
        photoResult=null;photoCracks=null;photoParameters=null;
        photoAllResult=null;photoAllCracks=null;interestRegion=null;cropRoiReport=false;regionIndices.clear();photoSuggestions=null;photoOption=null;
        if(overlay!=null){overlay.region(null);overlay.reference(null);}
        if(overlay!=null)overlay.cracks(null);if(crackLabel!=null)crackLabel.setText("Trincas: analisadas ao fotografar");
        scaleSettings=null;distribution=null;originalWidth=originalHeight=0;
        if(histogram!=null){histogram.display(null);setChartsVisibility(View.GONE);}
        if(scaleLabel!=null)scaleLabel.setText("Fotografe para calibrar em px/mm");
    }
    private void setBusy(Mode newMode,String message) {
        mode=newMode;badge.setText(newMode==Mode.SAVING?"SALVANDO":"ANALISANDO");hint.setText(message);
        if(newMode!=Mode.SAVING){overlay.clear();count.setText("—");shapeLabel.setText("Esfericidade aparente 2D (b/a): —");detail.setText(message);}
        updateControls();
    }
    private void updateControls() {
        boolean busy=mode==Mode.PROCESSING||mode==Mode.SAVING,photo=mode==Mode.PHOTO||mode==Mode.SAVING;
        capture.setVisibility(photo?View.GONE:View.VISIBLE);capture.setEnabled(!busy&&camera!=null);
        save.setVisibility(photo?View.VISIBLE:View.GONE);save.setEnabled(!busy&&photoResult!=null&&!roiNeedsAnalysis);
        save.setText(roiNeedsAnalysis?"Segmente antes de salvar":"Salvar relatório");save.setAlpha(save.isEnabled()?1f:.45f);
        if(crackSettings!=null)crackSettings.setEnabled(!busy&&mode==Mode.PHOTO&&photoCracks!=null);
        back.setVisibility(photo?View.VISIBLE:View.GONE);back.setEnabled(!busy);
        open.setVisibility(photo?View.GONE:View.VISIBLE);open.setEnabled(!busy&&nativeReady);
        profileLabel.setEnabled(!busy&&mode==Mode.PHOTO);
        adjust.setEnabled(!busy);torch.setEnabled(!busy&&(photo||(camera!=null&&camera.getCameraInfo().hasFlashUnit())));torch.setText(photo?"Luz/ROI":torchOn||liveLight!=LiveFrameAnalysis.Light.OFF?"Luz ✓":"Luz");torch.setEnabled(!busy&&(photo||camera!=null));
        calibration.setEnabled(!busy&&(photoResult!=null&&mode==Mode.PHOTO||mode==Mode.LIVE&&camera!=null));
        if(regionButton!=null){regionButton.setEnabled(!busy&&(mode==Mode.PHOTO&&photoBitmap!=null||mode==Mode.LIVE&&camera!=null));regionButton.setText((mode==Mode.LIVE?liveRoiFraction:interestRegion)==null?"Área":"Área ✓");}
        if(roiActions!=null){roiActions.setVisibility(photo?View.VISIBLE:View.GONE);segmentRoi.setEnabled(!busy&&mode==Mode.PHOTO&&photoBitmap!=null);searchRoi.setEnabled(segmentRoi.isEnabled());segmentRoi.setText(interestRegion==null?"Segmentar foto":"Segmentar ROI");searchRoi.setText(interestRegion==null?"Buscar melhor ajuste":"Buscar ajuste na ROI");}
        if(mode==Mode.PHOTO)badge.setText(interestRegion==null?"FOTO":"ROI");
    }
    /** Preview is illustrative: only the selected ROI is used for final segmentation. */
    private void showLowLightDialog() {
        if(photoBitmap==null){toast("Abra uma fotografia primeiro.");return;}
        final LowLightPreprocessor.Mode[] modes=LowLightPreprocessor.Mode.values();
        final String[] names={"Original (sem filtro)","Gamma + CLAHE","Redução de ruído + CLAHE","Retinex"};
        final String[] choices={"Automático","Original (sem filtro)","Gamma + CLAHE","Redução de ruído + CLAHE","Retinex"};
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(8),dp(12),dp(8));
        TextView info=text("Prévia de iluminação. A segmentação usa a ROI selecionada e refina as medidas na imagem original.",13,Color.LTGRAY);body.addView(info);
        ImageView previewImage=new ImageView(this);previewImage.setAdjustViewBounds(true);body.addView(previewImage,new LinearLayout.LayoutParams(-1,dp(300)));
        final int[] choice={selectedLowLightMode==null?0:selectedLowLightMode.ordinal()+1};
        android.widget.Spinner spinner=new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);
        spinner.setAdapter(adapter);spinner.setSelection(choice[0]);body.addView(spinner);
        final Runnable render=()->{
            Bitmap previewBitmap=Bitmap.createScaledBitmap(photoBitmap,Math.min(900,photoBitmap.getWidth()),Math.max(1,(int)(photoBitmap.getHeight()*Math.min(900,photoBitmap.getWidth())/(double)photoBitmap.getWidth())),true);
            Mat source=null,out=null;
            try {
                source=new Mat();
                org.opencv.android.Utils.bitmapToMat(previewBitmap,source);
                int index=spinner.getSelectedItemPosition();
                out=LowLightPreprocessor.apply(source,index<=1?LowLightPreprocessor.Mode.ORIGINAL:modes[index-1]);
                Bitmap shown=Bitmap.createBitmap(out.cols(),out.rows(),Bitmap.Config.ARGB_8888);
                org.opencv.android.Utils.matToBitmap(out,shown);
                previewImage.setImageBitmap(shown);
            }catch(Exception|LinkageError ex){toast("Não foi possível gerar a prévia.");}
            finally {try{if(source!=null)source.release();if(out!=null)out.release();}catch(LinkageError ignored){}previewBitmap.recycle();}
        };
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){choice[0]=pos;render.run();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        new AlertDialog.Builder(this).setTitle("Recuperação de imagem escura").setView(body)
          .setNegativeButton("Cancelar",(d,w)->{})
          .setPositiveButton("Aplicar e analisar",(d,w)->{
              if(interestRegion==null){toast("Selecione primeiro uma ROI em Área.");return;}
              selectedLowLightMode=choice[0]==0?null:modes[choice[0]-1];
              startActiveAnalysis(false);
          }).show();
    }

    private void toggleTorch() {
        if(camera==null||!camera.getCameraInfo().hasFlashUnit())return;
        torchOn=!torchOn;camera.getCameraControl().enableTorch(torchOn);torch.setText(torchOn?"Luz ✓":"Luz");
    }
    private java.util.List<Granulometry.Item> measurementItems() {
        java.util.List<Granulometry.Item> items=new java.util.ArrayList<>();
        for(PelletDetector.Detection d:photoResult.circles)items.add(Granulometry.Item.of(d));
        return items;
    }
    private java.util.List<Granulometry.Item> measurementItems(Granulometry.Settings prospective) {
        if(photoAllResult==null)return measurementItems();
        java.util.List<Granulometry.Item> items=new java.util.ArrayList<>();
        for(int i:ObjectCalibration.sampleIndices(photoAllResult,interestRegion,prospective.selectedObject)){PelletDetector.Detection d=photoAllResult.circles.get(i);items.add(Granulometry.Item.of(d));}
        return items;
    }
    private void refreshDistribution() {
        distribution=null;crackHistogram=null;histogram.display(null);setChartsVisibility(View.GONE);hideColorMap(false);
        if(roiNeedsAnalysis){scaleLabel.setText(scaleSettings==null?"ROI sem escala física":"Escala preservada · aguardando segmentação");updateControls();return;}
        if(photoResult==null){scaleLabel.setText("Sem escala · toque em Escala / mm");updateControls();return;}
        if(scaleSettings==null){distribution=PhotoComposer.pixelHistogram(photoResult);showHistogram();setChartsVisibility(View.VISIBLE);if(colorMap!=null){colorMap.display(photoResult,distribution);colorMap.visuals(visuals);colorMap.viewport(displayWindow);}applyViewMode();scaleLabel.setText("Sem escala física · histograma em px");hint.setText("Toque numa pelota para revisar ou calibrar pela medida real. Escala / mm mantém as opções manuais.");updateControls();return;}
        try {
            distribution=Granulometry.compute(measurementItems(),scaleSettings,originalWidth,originalHeight,photoResult.width,photoResult.height);
            scaleSettings=distribution.settings;
            scaleLabel.setText((scaleSettings.beltReference!=null?"Correia · ":scaleSettings.containerReference!=null?"Recipiente · ":scaleSettings.selectedObject!=null?"REF · ":scaleSettings.estimated()?"Estimativa · ":"")+HistogramView.number(scaleSettings.originalPixelsPerMm)+" px/mm · classe "+HistogramView.number(distribution.settings.binWidthMm)+" mm");
            showHistogram();setChartsVisibility(View.VISIBLE);
            if(colorMap!=null){colorMap.display(photoResult,distribution);colorMap.visuals(visuals);colorMap.viewport(displayWindow);}applyViewMode();
            hint.setText(scaleSettings.selectedObject!=null?"Escala pela referência escolhida. Toque em REF para editar a medida.":scaleSettings.estimated()?"Escala estimada pela média informada. Use uma referência para medir em mm.":
                "Escala aplicada. Confira a faixa em mm antes de salvar; largura da classe não é calibração.");
        } catch(IllegalArgumentException e){scaleLabel.setText("Ajuste a escala ou a classe");hint.setText(e.getMessage());}
        updateControls();
    }
    private void applyRegion(InterestRegion region){
        if(photoAllResult==null)return;
        interestRegion=region;if(region==null)cropRoiReport=false;regionIndices=ObjectCalibration.sampleIndices(photoAllResult,region,activeReference());
        photoResult=InterestRegion.select(photoAllResult,regionIndices);
        photoCracks=photoAllCracks==null?null:photoAllCracks.select(regionIndices);
        if(roiNeedsAnalysis){photoResult=new PelletDetector.Result(java.util.Collections.emptyList(),photoAllResult.width,photoAllResult.height,0,0,false,"pending_roi_analysis");photoCracks=null;}
        updateActivePhotoDisplay();overlay.display(photoResult,numbers);overlay.viewport(displayWindow);overlay.cracks(photoCracks);overlay.region(region);overlay.reference(activeReference());
        showCount(photoResult,true);updateCrackCount();refreshDistribution();
        refreshSuggestionLabel();
        if(photoDetailReport!=null&&!roiNeedsAnalysis)detail.setText("Detalhada · "+photoDetailReport.tiles+" blocos · "+photoDetailReport.durationMs+" ms");
        if(roiNeedsAnalysis){count.setText("—");detail.setText("Área alterada · aguardando análise");shapeLabel.setText("Esfericidade aparente 2D (b/a): —");crackLabel.setText("Trincas: aguardando segmentação");hint.setText("Recorte aplicado. Toque em Segmentar "+(region==null?"foto":"ROI")+" ou Buscar ajuste para analisar esta imagem.");}
        else if(region!=null)hint.setText("ROI ativa na tela e no relatório. Use Segmentar ROI ou Buscar ajuste na ROI para reanalisar a iluminação deste recorte.");
    }
    private void updateActivePhotoDisplay(){
        if(photoBitmap==null)return;AnalysisWindow wanted=new AnalysisWindow(photoBitmap.getWidth(),photoBitmap.getHeight(),interestRegion);
        Bitmap source=shownPhoto();
        if(displaySource==source&&wanted.sameScope(displayWindow)&&displayBitmap!=null&&!displayBitmap.isRecycled())return;
        Bitmap next=ActivePhoto.render(source,wanted);frozen.setImageBitmap(next);
        if(displayBitmap!=null&&displayBitmap!=photoBitmap&&displayBitmap!=photoFiltered&&displayBitmap!=next&&!displayBitmap.isRecycled())displayBitmap.recycle();
        displayBitmap=next;displaySource=source;displayWindow=wanted;
    }
    /**
     * 0.15.2: imagem mostrada na tela e usada na segmentação/mapa do relatório. É a filtrada quando houve realce
     * e a ROI na tela ainda é a mesma da análise; senão, a original. Nunca é usada para medir.
     */
    private Bitmap shownPhoto(){
        if(photoBitmap==null)return null;
        if(photoFiltered==null||photoFiltered.isRecycled()||roiNeedsAnalysis||photoAnalysisWindow==null)return photoBitmap;
        AnalysisWindow current=new AnalysisWindow(photoBitmap.getWidth(),photoBitmap.getHeight(),interestRegion);
        return current.sameScope(photoAnalysisWindow)?photoFiltered:photoBitmap;
    }
    private void activateRoi(InterestRegion region){
        if(photoBitmap==null)return;AnalysisWindow wanted=new AnalysisWindow(photoBitmap.getWidth(),photoBitmap.getHeight(),region);
        if(!AnalysisWindow.sameRegion(interestRegion,region)){roiNeedsAnalysis=!wanted.sameScope(photoAnalysisWindow);photoSuggestions=null;suggestionWindow=null;}
        cropRoiReport=region!=null;applyRegion(region);
    }
    private void startActiveAnalysis(boolean search){
        if(mode!=Mode.PHOTO||photoFile==null||photoBitmap==null)return;
        try{new AnalysisWindow(photoBitmap.getWidth(),photoBitmap.getHeight(),interestRegion).requireDetectable();}catch(IllegalArgumentException e){toast(e.getMessage());return;}
        File file=photoFile;String mime=photoMime;PelletDetector.Parameters cfg=photoParameters;
        setBusy(Mode.PROCESSING,(search?"Buscando ajuste pela iluminação da ":"Segmentando ")+(interestRegion==null?"foto…":"ROI…"));worker.execute(()->processPhoto(file,mime,cfg,search,null));
    }
    private void showRegionEditor(){showRegionEditor(interestRegion);}
    private void showRegionEditor(InterestRegion initial){
        if(mode!=Mode.PHOTO||photoBitmap==null||photoAllResult==null)return;
        android.app.Dialog dialog=new android.app.Dialog(this);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(Color.rgb(16,26,35));
        root.addView(text("Área de interesse",22,Color.WHITE));
        root.addView(text("Arraste dentro para mover. Use as alças nos lados/cantos ou dois dedos para redimensionar. Pelotas que cruzam o limite ficam fora da análise.",13,Color.LTGRAY));
        RegionEditorView editor=new RegionEditorView(this,photoBitmap,initial);
        LinearLayout shapes=row();Button rectangle=button("Retângulo",false),circle=button("Círculo",false),reset=button("Restaurar",false);
        shapes.addView(rectangle,new LinearLayout.LayoutParams(0,dp(44),1));shapes.addView(circle,new LinearLayout.LayoutParams(0,dp(44),1));shapes.addView(reset,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(shapes);
        rectangle.setOnClickListener(v->editor.shape(InterestRegion.Shape.RECTANGLE));circle.setOnClickListener(v->editor.shape(InterestRegion.Shape.CIRCLE));reset.setOnClickListener(v->editor.reset());
        root.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(text("Aplicar mostra somente o recorte na tela e no relatório. Depois, segmente ou busque o melhor ajuste para a ROI.",12,Color.LTGRAY));
        Button zoom=button("Visualizar ROI com zoom",false);zoom.setTag("roi_zoom");zoom.setOnClickListener(v->showRoiZoom(editor.region()));root.addView(zoom,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout actions=row();Button cancel=button("Cancelar",false),whole=button("Imagem inteira",false),apply=button("Aplicar",true);
        apply.setTag("roi_apply");whole.setTag("roi_whole");actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(whole,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(apply,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        cancel.setOnClickListener(v->dialog.dismiss());whole.setOnClickListener(v->{activateRoi(null);dialog.dismiss();});apply.setOnClickListener(v->{activateRoi(editor.region());dialog.dismiss();});
        dialog.setContentView(root);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    private void updateCrackCount(){
        if(photoCracks==null)return;
        int both=0,netPossible=0;for(CrackAnalysis.Entry e:photoCracks.entries)if(e.comparisonMethod!=null&&Double.isFinite(e.comparisonScore)){both++;if(e.comparisonScore>=.6)netPossible++;}
        crackLabel.setText(crackMethodLabel()+"Possíveis: "+photoCracks.count(CrackAnalysis.Status.POSSIBLE)+" · sem indicação: "+photoCracks.count(CrackAnalysis.Status.NO_INDICATION)+" · inconclusivas: "+photoCracks.count(CrackAnalysis.Status.INCONCLUSIVE)+(both>0?" · MobileNetV3 indicaria "+netPossible+" de "+both:""));
    }
    private void reviewCrackAt(float vx,float vy){
        if(photoResult==null||photoBitmap==null)return;
        double[] point=overlay.toPhoto(vx,vy);if(point==null||roiNeedsAnalysis)return;double x=point[0],y=point[1];
        ObjectCalibration.Reference active=activeReference();
        if(active!=null&&(active.excludeFromStatistics||(interestRegion!=null&&!interestRegion.includes(active.geometry)))&&active.geometry.normalizedDistance(x,y)<1){showObjectCalibrationForGeometry(active.geometry,active.fullImageId,active.displayIdAtSelection);return;}
        int chosen=-1;double closest=1;
        for(int i=0;i<photoResult.circles.size();i++){PelletDetector.Detection d=photoResult.circles.get(i);double distance=d.normalizedDistance(x,y);if(distance<closest){closest=distance;chosen=i;}}
        if(chosen<0)return;final int index=chosen;
        if(photoCracks==null){showObjectCalibration(index);return;}
        PelletDetector.Detection d=photoResult.circles.get(index);
        int x0=Math.max(0,(int)(d.x-d.extentX()*1.15)),y0=Math.max(0,(int)(d.y-d.extentY()*1.15));
        int w=Math.min(photoBitmap.getWidth()-x0,Math.max(1,(int)(d.extentX()*2.3))),h=Math.min(photoBitmap.getHeight()-y0,Math.max(1,(int)(d.extentY()*2.3)));
        Bitmap zoom=Bitmap.createBitmap(photoBitmap,x0,y0,w,h);ImageView enlarged=new ImageView(this);enlarged.setImageBitmap(zoom);enlarged.setAdjustViewBounds(true);enlarged.setMaxHeight(dp(240));
        CrackAnalysis.Entry entry=photoCracks.entries.get(index);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),0,dp(16),0);content.addView(enlarged);
        if(Double.isFinite(entry.modelScore))content.addView(text(String.format(Locale.ROOT,"Escore RF: %.3f · não é probabilidade",entry.modelScore),12,Color.LTGRAY));
        content.addView(text("Estado: "+(entry.status==CrackAnalysis.Status.POSSIBLE?"possível trinca":entry.status==CrackAnalysis.Status.NO_INDICATION?"sem indicação":"inconclusiva")+". A revisão muda a contagem; não desenha uma linha que o detector não localizou.",12,Color.LTGRAY));
        AlertDialog reviewDialog=new AlertDialog.Builder(this).setTitle("Pelota "+entry.pelletId).setView(content).setNeutralButton("Calibrar",(dialog,which)->showObjectCalibration(index))
            .setPositiveButton("Classificar",(dialog,which)->new AlertDialog.Builder(this).setTitle("Pelota "+entry.pelletId)
                .setItems(new String[]{"Trinca visível","Sem trinca visível","Inconclusiva","Restaurar automático"},(choice,item)->{
                    if(mode!=Mode.PHOTO||photoCracks==null)return;
                    CrackAnalysis.Status status=item==0?CrackAnalysis.Status.POSSIBLE:item==1?CrackAnalysis.Status.NO_INDICATION:item==2?CrackAnalysis.Status.INCONCLUSIVE:null;
                    if(photoAllCracks!=null&&regionIndices.size()==photoCracks.entries.size()){
                        photoAllCracks=photoAllCracks.review(regionIndices.get(index),status);photoCracks=photoAllCracks.select(regionIndices);
                    }else photoCracks=photoCracks.review(index,status);
                    overlay.cracks(photoCracks);updateCrackCount();if(distribution!=null)showHistogram();
                }).show())
            .setNegativeButton("Fechar",null).setOnDismissListener(dialog->{enlarged.setImageDrawable(null);if(zoom!=photoBitmap)zoom.recycle();}).create();
        reviewDialog.show();reviewDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(d.ellipseFitted&&!d.partial&&!d.needsReview);
    }
    private ObjectCalibration.Reference activeReference(){return scaleSettings==null?null:scaleSettings.selectedObject;}
    private int regionExcludedCount(){return photoAllResult==null||interestRegion==null?0:photoAllResult.circles.size()-interestRegion.indices(photoAllResult).size();}
    private void commitScale(Granulometry.Settings settings){scaleSettings=settings;if(photoAllResult!=null)applyRegion(interestRegion);else refreshDistribution();}
    private void showObjectCalibration(int displayIndex){
        if(photoResult==null||displayIndex<0||displayIndex>=photoResult.circles.size())return;
        int fullIndex=regionIndices.size()==photoResult.circles.size()?regionIndices.get(displayIndex):displayIndex;
        showObjectCalibrationAtFullIndex(fullIndex,displayIndex+1);
    }
    private void showObjectCalibrationAtFullIndex(int fullIndex,int displayId){
        if(mode!=Mode.PHOTO||photoBitmap==null||photoAllResult==null||fullIndex<0||fullIndex>=photoAllResult.circles.size())return;
        showObjectCalibrationForGeometry(photoAllResult.circles.get(fullIndex),fullIndex+1,displayId);
    }
    private void showObjectCalibrationForGeometry(PelletDetector.Detection selected,int fullId,int displayId){
        if(mode!=Mode.PHOTO||photoBitmap==null||photoAllResult==null)return;
        final PelletDetector.Result snapshot=photoAllResult;final Bitmap snapshotPhoto=photoBitmap;
        try{ObjectCalibration.eligible(selected);}catch(IllegalArgumentException e){toast(e.getMessage());return;}
        ObjectCalibration.Reference previous=activeReference();boolean editing=previous!=null&&previous.fullImageId==fullId&&previous.sameGeometry(selected);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        content.addView(text("Você escolheu esta referência. Informe a medida externa em mm, no sentido da linha amarela. Use a referência no mesmo plano das demais pelotas.",13,Color.LTGRAY));
        ReferencePreviewView referenceImage=new ReferencePreviewView(this,photoBitmap,selected,originalWidth,originalHeight);referenceImage.setTag("reference_preview");content.addView(referenceImage,new LinearLayout.LayoutParams(-1,dp(190)));
        android.widget.RadioGroup measures=new android.widget.RadioGroup(this);measures.setTag("reference_measure");int[] ids=new int[ObjectCalibration.Measure.values().length];
        for(int i=0;i<ids.length;i++){ObjectCalibration.Measure value=ObjectCalibration.Measure.values()[i];android.widget.RadioButton radio=new android.widget.RadioButton(this);radio.setId(ids[i]=View.generateViewId());radio.setText(value.label);radio.setTextColor(Color.WHITE);radio.setTextSize(13);radio.setTag(value.name());measures.addView(radio);}content.addView(measures);
        TextView dimensionLabel=text("Medida real (mm)",14,Color.WHITE);content.addView(dimensionLabel);
        EditText real=new EditText(this);real.setTag("reference_mm");real.setSingleLine();real.setTextColor(Color.WHITE);real.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);real.setHint("Ex.: 12 ou 12,5");if(editing)real.setText(Double.toString(previous.realMm));content.addView(real);
        CheckBox exclude=new CheckBox(this);exclude.setTag("exclude_reference");exclude.setText("Excluir referência das estatísticas");exclude.setTextColor(Color.WHITE);content.addView(exclude);
        content.addView(text("A esfera padrão fica fora da contagem, das trincas, da forma média e do histograma por padrão. A pelota medida pode continuar na amostra. A marca REF continua visível.",12,Color.LTGRAY));
        TextView note=text("",12,Color.LTGRAY);content.addView(note);
        content.addView(text("Largura de cada classe (mm)",14,Color.WHITE));EditText bin=new EditText(this);bin.setTag("reference_bin_mm");bin.setSingleLine();bin.setTextColor(Color.WHITE);bin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);bin.setText(Double.toString(scaleSettings==null?1:scaleSettings.binWidthMm));content.addView(bin);
        // 0.17.0: várias esferas padrão e correção de perspectiva (1 + k·ρ²), iguais ao Windows.
        boolean canAdd=previous!=null&&!editing&&previous.measure==ObjectCalibration.Measure.STANDARD_SPHERE&&!previous.covers(selected);
        CheckBox addSphere=new CheckBox(this);addSphere.setTag("add_reference_sphere");addSphere.setTextColor(Color.WHITE);addSphere.setText(canAdd?"Somar às "+previous.spheres().size()+" esfera(s) já marcada(s) (média + ajuste de perspectiva)":"Somar às esferas já marcadas");addSphere.setChecked(canAdd);addSphere.setVisibility(canAdd?View.VISIBLE:View.GONE);content.addView(addSphere);
        content.addView(text("Correção de perspectiva k (0 = desligada)",14,Color.WHITE));EditText kField=new EditText(this);kField.setTag("perspective_k");kField.setSingleLine();kField.setTextColor(Color.WHITE);kField.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        kField.setText(String.format(java.util.Locale.ROOT,"%.3f",editing?previous.perspectiveK:(double)getPreferences(MODE_PRIVATE).getFloat("perspective_k",.07f)));content.addView(kField);
        content.addView(text("O tamanho aparente cresce (1 + k·ρ²) do centro (ρ=0) ao canto (ρ=1) da foto. Com 3+ esferas espalhadas (ou 2, uma no centro e outra na borda) o k é ajustado pelas próprias esferas. Teste de 11/11/2026 com o celular a ~12 cm: k entre 0,06 e 0,09.",12,Color.LTGRAY));
        TextView preview=text("",13,Color.rgb(82,216,190));preview.setTag("reference_scale_preview");content.addView(preview);TextView error=text("",13,Color.rgb(255,146,146));error.setTag("reference_error");error.setVisibility(View.GONE);content.addView(error);
        java.util.function.Supplier<ObjectCalibration.Measure> chosen=()->{for(int i=0;i<ids.length;i++)if(measures.getCheckedRadioButtonId()==ids[i])return ObjectCalibration.Measure.values()[i];return ObjectCalibration.Measure.MAJOR_AXIS;};
        java.util.function.Supplier<Granulometry.Settings> input=()->{
            double k;try{k=Double.parseDouble(kField.getText().toString().trim().replace(',','.'));}catch(NumberFormatException e){throw new IllegalArgumentException("k inválido.");}
            ObjectCalibration.Reference reference;
            if(canAdd&&addSphere.isChecked()&&chosen.get()==ObjectCalibration.Measure.STANDARD_SPHERE){java.util.List<PelletDetector.Detection> extras=new java.util.ArrayList<>(previous.extraSpheres);extras.add(selected);
                reference=ObjectCalibration.select(previous.geometry,previous.fullImageId,previous.displayIdAtSelection,previous.measure,parsePositive(real.getText().toString()),exclude.isChecked(),originalWidth,originalHeight,snapshot.width,snapshot.height,extras,k);}
            else reference=ObjectCalibration.select(selected,fullId,displayId,chosen.get(),parsePositive(real.getText().toString()),exclude.isChecked(),originalWidth,originalHeight,snapshot.width,snapshot.height,editing?previous.extraSpheres:null,k);
            return Granulometry.Settings.selectedObject(reference,parsePositive(bin.getText().toString()),scaleSettings==null||scaleSettings.excludePartial,scaleSettings!=null&&scaleSettings.excludeReview);
        };
        Runnable update=()->{try{
            Granulometry.Settings cfg=input.get();ObjectCalibration.Reference ref=cfg.selectedObject;
            java.util.List<Integer> sample=ObjectCalibration.sampleIndices(snapshot,interestRegion,ref);java.util.List<Granulometry.Item> items=new java.util.ArrayList<>();for(int i:sample){PelletDetector.Detection d=snapshot.circles.get(i);items.add(Granulometry.Item.of(d));}
            Granulometry.Distribution measured=Granulometry.compute(items,cfg,originalWidth,originalHeight,snapshot.width,snapshot.height);
            preview.setText("Escala calculada: "+HistogramView.number(cfg.originalPixelsPerMm)+" px/mm na foto original.\nReferência: maior "+HistogramView.number(ref.majorMm())+" mm · menor "+HistogramView.number(ref.minorMm())+" mm · equivalente "+HistogramView.number(ref.equivalentMm())+" mm.\n"+(measured.included==0?"Nenhuma pelota incluída na distribuição.":"Histograma: média "+HistogramView.number(measured.meanMm)+" mm · n="+measured.included+" · "+measured.binCount()+" classes.")+"\n"+ref.perspectiveSummary());
        }catch(IllegalArgumentException e){preview.setText("Informe a medida real e a classe para conferir a escala.");}};
        measures.setOnCheckedChangeListener((g,checked)->{ObjectCalibration.Measure measure=chosen.get();referenceImage.measure(measure);dimensionLabel.setText(measure==ObjectCalibration.Measure.STANDARD_SPHERE?"Diâmetro conhecido da esfera (mm)":measure==ObjectCalibration.Measure.MAJOR_AXIS?"Eixo maior medido com paquímetro (mm)":"Eixo menor medido com paquímetro (mm)");exclude.setChecked(editing&&measure==previous.measure?previous.excludeFromStatistics:measure==ObjectCalibration.Measure.STANDARD_SPHERE);
            note.setText(measure==ObjectCalibration.Measure.STANDARD_SPHERE?"Esfera: usa o diâmetro equivalente à área da elipse. Confira se o contorno corresponde ao padrão conhecido."+(selected.aspectRatio()<.9?" A projeção está alongada; isso pode enviesar a escala.":""):"Meça a mesma dimensão destacada pela linha amarela. O diâmetro equivalente do histograma pode diferir do eixo medido.");update.run();});
        android.text.TextWatcher watcher=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){update.run();}public void afterTextChanged(android.text.Editable e){}};real.addTextChangedListener(watcher);bin.addTextChangedListener(watcher);exclude.setOnCheckedChangeListener((v,c)->update.run());kField.addTextChangedListener(watcher);addSphere.setOnCheckedChangeListener((v,c)->update.run());
        measures.check(ids[editing?previous.measure.ordinal():0]);update.run();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(editing?"Editar referência de escala":"Calibrar pela pelota "+displayId).setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar escala",null).setOnDismissListener(d->referenceImage.detach()).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(mode!=Mode.PHOTO||photoAllResult!=snapshot||photoBitmap!=snapshotPhoto){dialog.dismiss();toast("A foto mudou. Selecione a referência novamente.");return;}
            try{Granulometry.Settings settings=input.get();ObjectCalibration.Reference reference=settings.selectedObject;
                java.util.List<Granulometry.Item> items=new java.util.ArrayList<>();for(int i:ObjectCalibration.sampleIndices(snapshot,interestRegion,reference)){PelletDetector.Detection d=snapshot.circles.get(i);items.add(Granulometry.Item.of(d));}
                Granulometry.compute(items,settings,originalWidth,originalHeight,snapshot.width,snapshot.height);getPreferences(MODE_PRIVATE).edit().putFloat("perspective_k",(float)Double.parseDouble(kField.getText().toString().trim().replace(',','.'))).apply();commitScale(settings);dialog.dismiss();toast("Escala aplicada. "+reference.perspectiveSummary());
            }catch(IllegalArgumentException e){error.setText(e.getMessage());error.setVisibility(View.VISIBLE);}
        }));dialog.show();
    }
    private void showCrackSettings(){
        if(mode!=Mode.PHOTO||photoCracks==null)return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        content.addView(text(crackModel==null?"Modelo indisponível. Triagem heurística de reserva, sem classificador treinado.":"Modelo experimental Random Forest treinado com as imagens enviadas. Não validado na usina. O escore não é probabilidade calibrada. Maior sensibilidade pode confundir poros e sombras com trincas. A análise ocorre na foto.",13,Color.LTGRAY));
        SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(crackSensitivity);content.addView(slider);
        content.addView(text("Reanalisar descarta revisões manuais desta foto. Toque em cada pelota para conferir a contagem.",12,Color.LTGRAY));
        new AlertDialog.Builder(this).setTitle("Sensibilidade das trincas").setView(content).setNegativeButton("Cancelar",null)
            .setPositiveButton("Reanalisar",(dialog,which)->{crackSensitivity=slider.getProgress();File file=photoFile;String mime=photoMime;PelletDetector.Parameters cfg=photoParameters;setBusy(Mode.PROCESSING,"Reanalisando trincas…");worker.execute(()->processPhoto(file,mime,cfg));}).show();
    }
    private void showCalibration(){showCalibration(false);}
    private void showCalibration(boolean saveAfterApply) {
        if(mode!=Mode.PHOTO||photoResult==null)return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        TextView note=text("Toque numa pelota na foto e use Calibrar para informar a medida real. A escala converte pixels em mm. A classe apenas agrupa diâmetros: 1 mm gera barras como 8–9, 9–10 mm. Use pixels da foto ORIGINAL ("+originalWidth+" × "+originalHeight+" px), não pixels da tela. O redimensionamento interno é corrigido.",13,Color.LTGRAY);content.addView(note);
        if(activeReference()!=null){Button edit=button("Editar referência selecionada",false);edit.setTag("edit_selected_reference");edit.setOnClickListener(v->{ObjectCalibration.Reference ref=activeReference();if(ref!=null)showObjectCalibrationForGeometry(ref.geometry,ref.fullImageId,ref.displayIdAtSelection);});content.addView(edit);}
        Button belt=button("Calibrar pela largura da correia",false);belt.setTag("belt_calibration");content.addView(belt);
        Button container=button("Calibrar pelo diâmetro interno do recipiente",false);container.setTag("container_calibration");content.addView(container);
        android.widget.RadioGroup methods=new android.widget.RadioGroup(this);
        int[] ids={View.generateViewId(),View.generateViewId(),View.generateViewId(),View.generateViewId(),View.generateViewId(),View.generateViewId()};
        String[] labels={"Informar escala em px/mm","Calcular por referência de comprimento","Estimar por média conhecida (mm)","Usar objeto selecionado na foto","Usar largura da correia confirmada","Usar diâmetro do recipiente confirmado"};
        for(int i=0;i<ids.length;i++){android.widget.RadioButton option=new android.widget.RadioButton(this);option.setId(ids[i]);option.setText(labels[i]);option.setTextColor(Color.WHITE);option.setTextSize(13);if(i==3)option.setEnabled(activeReference()!=null);if(i==4)option.setEnabled(scaleSettings!=null&&scaleSettings.beltReference!=null);if(i==5)option.setEnabled(scaleSettings!=null&&scaleSettings.containerReference!=null);methods.addView(option);}
        content.addView(methods);
        TextView valueLabel=text("Pixels por milímetro (px/mm)",14,Color.WHITE);content.addView(valueLabel);
        EditText pixels=new EditText(this);pixels.setSingleLine();pixels.setTextColor(Color.WHITE);pixels.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);content.addView(pixels);
        TextView lengthLabel=text("Comprimento real da referência (mm)",14,Color.WHITE);content.addView(lengthLabel);
        EditText length=new EditText(this);length.setSingleLine();length.setTextColor(Color.WHITE);length.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);length.setHint("Ex.: 12");content.addView(length);
        TextView methodNote=text("",12,Color.LTGRAY);content.addView(methodNote);
        content.addView(text("Largura de cada classe (mm)",14,Color.WHITE));
        EditText classes=new EditText(this);classes.setSingleLine();classes.setTextColor(Color.WHITE);classes.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        classes.setText(scaleSettings==null?"1":Double.toString(scaleSettings.binWidthMm));content.addView(classes);
        content.addView(text("A largura escolhida é preservada. O eixo cobre os diâmetros incluídos; classes vazias entre eles também aparecem.",12,Color.LTGRAY));
        CheckBox edges=new CheckBox(this);edges.setText("Excluir regiões cortadas na borda");edges.setTextColor(Color.WHITE);edges.setChecked(scaleSettings==null||scaleSettings.excludePartial);content.addView(edges);
        CheckBox review=new CheckBox(this);review.setText("Excluir também regiões com alerta");review.setTextColor(Color.WHITE);review.setChecked(scaleSettings!=null&&scaleSettings.excludeReview);content.addView(review);
        TextView preview=text("",13,Color.rgb(82,216,190));preview.setPadding(0,dp(8),0,dp(8));content.addView(preview);
        content.addView(text("Distribuição por NÚMERO, não por massa. A área de interesse não altera px/mm. Recalibre para outra foto, distância ou zoom. Diâmetro equivalente à área da elipse; contornos sem ajuste usam a estimativa anterior e exigem revisão.",12,Color.LTGRAY));
        TextView error=text("",13,Color.rgb(255,146,146));error.setPadding(0,dp(8),0,dp(8));error.setVisibility(View.GONE);content.addView(error);
        java.util.function.Supplier<Granulometry.Settings> input=()-> {
            double bin=parsePositive(classes.getText().toString());
            if((methods.getCheckedRadioButtonId()==ids[3]&&activeReference()!=null)||(methods.getCheckedRadioButtonId()==ids[4]&&scaleSettings!=null&&scaleSettings.beltReference!=null)||(methods.getCheckedRadioButtonId()==ids[5]&&scaleSettings!=null&&scaleSettings.containerReference!=null))return scaleSettings.withHistogram(bin,edges.isChecked(),review.isChecked());
            double value=parsePositive(pixels.getText().toString());
            if(methods.getCheckedRadioButtonId()==ids[1])return Granulometry.Settings.reference(value,parsePositive(length.getText().toString()),bin,edges.isChecked(),review.isChecked());
            if(methods.getCheckedRadioButtonId()==ids[2])return Granulometry.Settings.estimatedMean(value,bin,edges.isChecked(),review.isChecked());
            return new Granulometry.Settings(value,bin,edges.isChecked(),review.isChecked());
        };
        Runnable updatePreview=()-> {
            try {
                Granulometry.Settings prospective=input.get();Granulometry.Distribution d=Granulometry.compute(measurementItems(prospective),prospective,originalWidth,originalHeight,photoResult.width,photoResult.height);
                preview.setText(d.included==0?"Nenhuma região incluída.":"Prévia: média "+HistogramView.number(d.meanMm)+" mm · faixa "+HistogramView.number(d.minMm)+"–"+HistogramView.number(d.maxMm)+" mm · "+d.binCount()+" classes.\nEscala: "+HistogramView.number(d.settings.originalPixelsPerMm)+" px/mm"+(d.settings.estimated()?" (ESTIMADA)":"")+(d.binCount()==1?"\nTodos os diâmetros cabem em uma classe. Confira a escala; reduzir a classe não corrige uma escala incorreta.":""));
            } catch(IllegalArgumentException e){preview.setText("Preencha os valores para conferir a faixa em mm.");}
        };
        android.text.TextWatcher watcher=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){updatePreview.run();}public void afterTextChanged(android.text.Editable e){}};
        pixels.addTextChangedListener(watcher);length.addTextChangedListener(watcher);classes.addTextChangedListener(watcher);
        edges.setOnCheckedChangeListener((v,checked)->updatePreview.run());review.setOnCheckedChangeListener((v,checked)->updatePreview.run());
        methods.setOnCheckedChangeListener((group,checked)-> {
            boolean reference=checked==ids[1],estimate=checked==ids[2],selected=checked==ids[3]||checked==ids[4]||checked==ids[5];
            valueLabel.setVisibility(selected?View.GONE:View.VISIBLE);pixels.setVisibility(selected?View.GONE:View.VISIBLE);
            lengthLabel.setVisibility(reference?View.VISIBLE:View.GONE);length.setVisibility(reference?View.VISIBLE:View.GONE);
            valueLabel.setText(reference?"Comprimento da referência na foto original (px)":estimate?"Média conhecida das pelotas incluídas (mm)":"Pixels por milímetro (px/mm)");
            pixels.setHint(reference?"Ex.: 192":estimate?"Ex.: 12":"Ex.: 16 ou 16,5");
            methodNote.setText(checked==ids[5]&&scaleSettings!=null&&scaleSettings.containerReference!=null?"Recipiente confirmado: "+scaleSettings.containerReference.summary()+". Use o botão do recipiente para editar a borda.":checked==ids[4]&&scaleSettings!=null&&scaleSettings.beltReference!=null?"Largura confirmada: "+HistogramView.number(scaleSettings.beltReference.realWidthMm)+" mm. Use o botão da correia para editar as bordas.":selected&&activeReference()!=null?"REF: "+activeReference().summary()+". Para trocar, toque no objeto na foto.":reference?"Ex.: 192 px / 12 mm = 16 px/mm. Meça uma referência no mesmo plano das pelotas.":estimate?"Use somente se a média desta amostra foi obtida externamente. A média informada define a escala; não será uma medição independente. A faixa não será forçada a 8–16 mm.":"px/mm = comprimento em pixels / comprimento real em mm. Não informe o diâmetro médio neste campo.");
            boolean match=scaleSettings!=null&&(reference?"reference_length".equals(scaleSettings.calibrationSource):estimate?scaleSettings.estimated():"pixels_per_mm".equals(scaleSettings.calibrationSource));
            pixels.setText(match?Double.toString(reference?scaleSettings.referencePixels:estimate?scaleSettings.knownMeanMm:scaleSettings.originalPixelsPerMm):"");
            length.setText(match&&reference?Double.toString(scaleSettings.referenceMm):"");updatePreview.run();
        });
        methods.check(ids[scaleSettings==null?0:scaleSettings.containerReference!=null?5:scaleSettings.beltReference!=null?4:scaleSettings.selectedObject!=null?3:scaleSettings.estimated()?2:"reference_length".equals(scaleSettings.calibrationSource)?1:0]);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Escala e granulometria").setView(scroll)
            .setNegativeButton("Cancelar",null).setNeutralButton("Remover escala",(d,w)->commitScale(null))
            .setPositiveButton("Aplicar",null).create();
        belt.setOnClickListener(v->{dialog.dismiss();showBeltCalibration();});
        container.setOnClickListener(v->{dialog.dismiss();showContainerCalibration();});
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                Granulometry.Settings prospective=input.get();Granulometry.Distribution measured=Granulometry.compute(measurementItems(prospective),prospective,originalWidth,originalHeight,photoResult.width,photoResult.height);
                commitScale(measured.settings);dialog.dismiss();
                if(saveAfterApply)savePhoto();
            } catch(IllegalArgumentException e){
                error.setText(e.getMessage()==null?"Informe números positivos.":e.getMessage());error.setVisibility(View.VISIBLE);
                android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(keyboard!=null)keyboard.hideSoftInputFromWindow(pixels.getWindowToken(),0);
                scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
            }
        }));dialog.show();
    }
    private void showRoiZoom(InterestRegion roi){
        if(mode!=Mode.PHOTO||photoBitmap==null||photoAllResult==null||roi==null)return;
        android.app.Dialog dialog=new android.app.Dialog(this);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(Color.rgb(16,26,35));
        root.addView(text("ROI ampliada",22,Color.WHITE));root.addView(text("Aproxime com dois dedos e arraste para inspecionar. O relatório usa a ROI inteira selecionada, com a mesma escala física.",13,Color.LTGRAY));
        java.util.List<Integer> indices=roiNeedsAnalysis?new java.util.ArrayList<>():ObjectCalibration.sampleIndices(photoAllResult,roi,activeReference());
        RoiZoomView zoom=new RoiZoomView(this,shownPhoto(),InterestRegion.select(photoAllResult,indices),photoAllCracks==null?null:photoAllCracks.select(indices),roi,activeReference());root.addView(zoom,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=row();Button reset=button("Enquadrar ROI",false),close=button("Voltar",true);actions.addView(reset,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(close,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);reset.setOnClickListener(v->zoom.reset());close.setOnClickListener(v->dialog.dismiss());
        dialog.setContentView(root);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
    }
    private void showBeltCalibration(){
        if(mode!=Mode.PHOTO||photoBitmap==null||photoAllResult==null)return;
        final Bitmap snapshot=photoBitmap;final int ow=originalWidth,oh=originalHeight,w=snapshot.getWidth(),h=snapshot.getHeight();
        BeltCalibration.Reference previous=scaleSettings==null?null:scaleSettings.beltReference;
        android.app.Dialog dialog=new android.app.Dialog(this);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(this);root.setTag("belt_dialog");root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(Color.rgb(16,26,35));
        root.addView(text("Largura da correia",22,Color.WHITE));
        root.addView(text("Marque as DUAS bordas da correia, incluindo as faixas sem material. Arraste as quatro alças ou as linhas. Dois dedos ampliam; arraste fora das linhas para mover a foto.",12,Color.LTGRAY));
        LinearLayout labels=row();labels.addView(text("Largura real (mm)",13,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));labels.addView(text("Classe (mm)",13,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));root.addView(labels);
        LinearLayout values=row();EditText real=new EditText(this),bin=new EditText(this);real.setTag("belt_width_mm");bin.setTag("belt_bin_mm");
        for(EditText input:new EditText[]{real,bin}){input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);input.setOnEditorActionListener((v,action,event)->{android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(v.getWindowToken(),0);v.clearFocus();return true;});input.setTextColor(Color.WHITE);input.setSingleLine();input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);values.addView(input,new LinearLayout.LayoutParams(0,dp(46),1));}root.addView(values);
        real.setHint("Largura medida");if(previous!=null)real.setText(Double.toString(previous.realWidthMm));bin.setText(scaleSettings==null?"1":Double.toString(scaleSettings.binWidthMm));
        BeltEditorView editor=new BeltEditorView(this,snapshot,previous==null?BeltCalibration.initial(w,h,false):previous.edges);editor.setTag("belt_editor");
        LinearLayout tools=row();Button automatic=button("Sugerir bordas",false),rotate=button("Girar guia",false),fit=button("Enquadrar",false);automatic.setTag("belt_auto");for(Button b:new Button[]{automatic,rotate,fit})tools.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(tools);root.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        TextView status=text("",12,Color.rgb(82,216,190));status.setTag("belt_status");root.addView(status);
        CheckBox confirmed=new CheckBox(this);confirmed.setTag("belt_confirm");confirmed.setText("Conferi as bordas e a largura real da correia");confirmed.setTextColor(Color.WHITE);confirmed.setTextSize(12);root.addView(confirmed);
        root.addView(text("Use trecho plano e câmera perpendicular. A largura nominal de correia abaulada e a altura do material podem enviesar a escala. Variação entre as larguras marcadas acima de 8% bloqueia a calibração.",11,Color.LTGRAY));
        LinearLayout actions=row();Button cancel=button("Cancelar",false),apply=button("Aplicar largura",true);apply.setTag("belt_apply");actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(apply,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        Runnable update=()->{try{BeltCalibration.Reference ref=BeltCalibration.select(editor.edges(),parsePositive(real.getText().toString()),ow,oh,w,h);parsePositive(bin.getText().toString());status.setText(HistogramView.number(ref.originalWidthPixels)+" px na original / "+HistogramView.number(ref.realWidthMm)+" mm = "+HistogramView.number(ref.originalPixelsPerMm)+" px/mm · variação "+HistogramView.number(100*ref.widthVariation)+"%");apply.setEnabled(confirmed.isChecked());}catch(IllegalArgumentException e){status.setText(e.getMessage());apply.setEnabled(false);}};
        editor.changed(()->{confirmed.setChecked(false);update.run();});confirmed.setOnCheckedChangeListener((v,c)->update.run());
        android.text.TextWatcher watcher=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){confirmed.setChecked(false);update.run();}public void afterTextChanged(android.text.Editable e){}};real.addTextChangedListener(watcher);bin.addTextChangedListener(watcher);
        final boolean[] horizontal={false};rotate.setOnClickListener(v->{horizontal[0]=!horizontal[0];editor.edges(BeltCalibration.initial(w,h,horizontal[0]));editor.resetZoom();});fit.setOnClickListener(v->editor.resetZoom());
        automatic.setOnClickListener(v->{
            automatic.setEnabled(false);confirmed.setChecked(false);status.setText("Buscando linhas persistentes; confira a sugestão antes de aplicar.");
            int tw=Math.max(32,(int)Math.round(w*Math.min(1,480./Math.max(w,h)))),th=Math.max(32,(int)Math.round(h*Math.min(1,480./Math.max(w,h))));Bitmap small=Bitmap.createScaledBitmap(snapshot,tw,th,true);final Bitmap scan=small==snapshot?small.copy(Bitmap.Config.ARGB_8888,false):small;
            worker.execute(()->{BeltCalibration.Edges found=null;try{int[] pixels=new int[tw*th];scan.getPixels(pixels,0,tw,0,0,tw,th);byte[] gray=new byte[pixels.length];for(int i=0;i<pixels.length;i++){int c=pixels[i];gray[i]=(byte)((77*Color.red(c)+150*Color.green(c)+29*Color.blue(c))>>8);}BeltCalibration.Edges edge=BeltEdgeDetector.suggest(gray,tw,th);if(edge!=null){double[] p=new double[8];for(int i=0;i<4;i++){p[2*i]=edge.x(i)*(w-1)/(tw-1.);p[2*i+1]=edge.y(i)*(h-1)/(th-1.);}found=new BeltCalibration.Edges(p,true);}}catch(RuntimeException ignored){}finally{scan.recycle();}final BeltCalibration.Edges suggestion=found;runOnUiThread(()->{if(!dialog.isShowing()||isDestroyed()||photoBitmap!=snapshot)return;automatic.setEnabled(true);if(suggestion!=null){editor.edges(suggestion);update.run();status.setText("Bordas sugeridas: confira as faixas sem material e ajuste.\n"+status.getText());}else status.setText("Sem sugestão confiável. Ajuste as duas linhas manualmente.");});});
        });
        cancel.setOnClickListener(v->dialog.dismiss());apply.setOnClickListener(v->{try{if(!confirmed.isChecked())return;BeltCalibration.Reference ref=BeltCalibration.select(editor.edges(),parsePositive(real.getText().toString()),ow,oh,w,h);Granulometry.Settings cfg=Granulometry.Settings.belt(ref,parsePositive(bin.getText().toString()),scaleSettings==null||scaleSettings.excludePartial,scaleSettings!=null&&scaleSettings.excludeReview);Granulometry.compute(measurementItems(cfg),cfg,ow,oh,w,h);commitScale(cfg);dialog.dismiss();showRegionEditor();}catch(IllegalArgumentException e){status.setText(e.getMessage());}});
        dialog.setContentView(root);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);update.run();if(previous==null)automatic.performClick();
    }

    /** Escala pelo diâmetro interno conhecido de um recipiente redondo; a borda é sugerida automaticamente e confirmada pelo usuário. */
    private void showContainerCalibration(){
        if(mode!=Mode.PHOTO||photoBitmap==null||photoAllResult==null)return;
        final Bitmap snapshot=photoBitmap;final int ow=originalWidth,oh=originalHeight,w=snapshot.getWidth(),h=snapshot.getHeight();
        ContainerCalibration.Reference previous=scaleSettings==null?null:scaleSettings.containerReference;
        final ContainerCalibration.Detection[] detection={previous==null?null:previous.detection};
        final ContainerCalibration.Circle[] circles=new ContainerCalibration.Circle[2];
        final ContainerCalibration.Plane[] plane={previous==null?ContainerCalibration.Plane.RIM:previous.plane};
        if(detection[0]!=null){circles[0]=detection[0].rim;circles[1]=detection[0].pelletLevel;}
        if(previous!=null)circles[previous.plane.ordinal()]=previous.circle;
        android.app.Dialog dialog=new android.app.Dialog(this);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(this);root.setTag("container_dialog");root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(Color.rgb(16,26,35));
        root.addView(text("Diâmetro interno do recipiente",22,Color.WHITE));
        root.addView(text("A borda é detectada automaticamente. Confira o círculo amarelo; arraste a linha ou as alças para o raio e a cruz central para mover. Dois dedos ampliam.",12,Color.LTGRAY));
        LinearLayout labels=row();labels.addView(text("Diâmetro interno (mm)",13,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));labels.addView(text("Classe (mm)",13,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));root.addView(labels);
        LinearLayout values=row();EditText real=new EditText(this),bin=new EditText(this);real.setTag("container_diameter_mm");bin.setTag("container_bin_mm");
        for(EditText input:new EditText[]{real,bin}){input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);input.setOnEditorActionListener((v,action,event)->{android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(v.getWindowToken(),0);v.clearFocus();return true;});input.setTextColor(Color.WHITE);input.setSingleLine();input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);values.addView(input,new LinearLayout.LayoutParams(0,dp(46),1));}root.addView(values);
        real.setHint("Medido por dentro");if(previous!=null)real.setText(Double.toString(previous.realInnerDiameterMm));bin.setText(scaleSettings==null?"1":Double.toString(scaleSettings.binWidthMm));
        android.widget.RadioGroup planes=new android.widget.RadioGroup(this);planes.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton level=new android.widget.RadioButton(this),rim=new android.widget.RadioButton(this);level.setId(View.generateViewId());rim.setId(View.generateViewId());level.setTag("container_plane_level");rim.setTag("container_plane_rim");
        level.setText("Nível das pelotas");rim.setText("Borda superior");for(android.widget.RadioButton b:new android.widget.RadioButton[]{level,rim}){b.setTextColor(Color.WHITE);b.setTextSize(12);planes.addView(b,new android.widget.RadioGroup.LayoutParams(0,-2,1f));}root.addView(planes);
        ContainerCalibration.Circle start=circles[plane[0].ordinal()]!=null?circles[plane[0].ordinal()]:ContainerCalibration.initial(w,h);
        ContainerEditorView editor=new ContainerEditorView(this,snapshot,start);editor.setTag("container_editor");
        LinearLayout tools=row();Button automatic=button("Detectar borda",false),fit=button("Enquadrar",false);automatic.setTag("container_auto");for(Button b:new Button[]{automatic,fit})tools.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(tools);root.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        TextView status=text("",12,Color.rgb(82,216,190));status.setTag("container_status");root.addView(status);
        TextView warning=text("",12,Color.rgb(255,196,92));warning.setTag("container_warning");root.addView(warning);
        CheckBox confirmed=new CheckBox(this);confirmed.setTag("container_confirm");confirmed.setText("Conferi o círculo, o plano e o diâmetro interno real");confirmed.setTextColor(Color.WHITE);confirmed.setTextSize(12);root.addView(confirmed);
        root.addView(text("A escala só vale no plano do círculo. Recipiente fundo: a borda fica mais perto da câmera que as pelotas, que sairiam menores. Use câmera paralela à boca, camada nivelada e recipiente cilíndrico. Borda ovalizada acima de 6% bloqueia a calibração.",11,Color.LTGRAY));
        LinearLayout actions=row();Button cancel=button("Cancelar",false),apply=button("Aplicar diâmetro",true);apply.setTag("container_apply");actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(apply,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        final boolean[] quiet={false};
        Runnable update=()->{
            ContainerCalibration.Detection d=detection[0];double bias=d==null?Double.NaN:d.rimOverPelletScale();
            warning.setText(Double.isFinite(bias)?(plane[0]==ContainerCalibration.Plane.RIM&&bias>.02?"Atenção: na borda a escala é "+HistogramView.number(100*bias)+"% maior que no nível das pelotas; as pelotas sairiam cerca de "+HistogramView.number(100*bias/(1+bias))+"% menores.":"Diferença de escala borda/nível das pelotas: "+HistogramView.number(100*bias)+"%."+(plane[0]==ContainerCalibration.Plane.PELLET_LEVEL?" Supõe parede cilíndrica e pelotas encostadas nela.":"")):plane[0]==ContainerCalibration.Plane.PELLET_LEVEL&&d!=null?d.pelletNote:"");
            try{ContainerCalibration.Reference ref=ContainerCalibration.select(editor.circle(),plane[0],parsePositive(real.getText().toString()),d,ow,oh,w,h);parsePositive(bin.getText().toString());status.setText("Ø "+HistogramView.number(ref.originalDiameterPixels)+" px na original / "+HistogramView.number(ref.realInnerDiameterMm)+" mm = "+HistogramView.number(ref.originalPixelsPerMm)+" px/mm"+(Double.isFinite(ref.rimOvality)?" · ovalização "+HistogramView.number(100*ref.rimOvality)+"%":""));apply.setEnabled(confirmed.isChecked());}catch(IllegalArgumentException e){status.setText(e.getMessage());apply.setEnabled(false);}
        };
        editor.changed(()->{circles[plane[0].ordinal()]=editor.circle();if(!quiet[0])confirmed.setChecked(false);update.run();});confirmed.setOnCheckedChangeListener((v,c)->update.run());
        android.text.TextWatcher watcher=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){confirmed.setChecked(false);update.run();}public void afterTextChanged(android.text.Editable e){}};real.addTextChangedListener(watcher);bin.addTextChangedListener(watcher);
        Runnable showPlane=()->{ContainerCalibration.Plane p=plane[0];ContainerCalibration.Circle active=circles[p.ordinal()];if(active==null){ContainerCalibration.Circle o=circles[1-p.ordinal()];active=o==null?ContainerCalibration.initial(w,h):o.manual();circles[p.ordinal()]=active;}editor.circles(active,circles[1-p.ordinal()]);};
        planes.setOnCheckedChangeListener((g,id)->{plane[0]=id==rim.getId()?ContainerCalibration.Plane.RIM:ContainerCalibration.Plane.PELLET_LEVEL;confirmed.setChecked(false);showPlane.run();});
        fit.setOnClickListener(v->editor.resetZoom());
        automatic.setOnClickListener(v->{
            automatic.setEnabled(false);confirmed.setChecked(false);status.setText("Procurando a borda circular; confira a sugestão antes de aplicar.");
            double k=Math.min(1,640./Math.max(w,h));int tw=Math.max(64,(int)Math.round(w*k)),th=Math.max(64,(int)Math.round(h*k));Bitmap small=Bitmap.createScaledBitmap(snapshot,tw,th,true);final Bitmap scan=small==snapshot?small.copy(Bitmap.Config.ARGB_8888,false):small;
            worker.execute(()->{ContainerCalibration.Detection found=null;try{int[] pixels=new int[tw*th];scan.getPixels(pixels,0,tw,0,0,tw,th);byte[] gray=new byte[pixels.length];for(int i=0;i<pixels.length;i++){int c=pixels[i];gray[i]=(byte)((77*Color.red(c)+150*Color.green(c)+29*Color.blue(c))>>8);}ContainerCalibration.Detection d=ContainerRimDetector.detect(gray,tw,th);if(d!=null)found=d.scaled((w-1)/(tw-1.),(h-1)/(th-1.));}catch(RuntimeException ignored){}finally{scan.recycle();}final ContainerCalibration.Detection suggestion=found;
                runOnUiThread(()->{if(!dialog.isShowing()||isDestroyed()||photoBitmap!=snapshot)return;automatic.setEnabled(true);
                    if(suggestion==null){status.setText("Borda não encontrada. Enquadre o recipiente inteiro ou ajuste o círculo manualmente.");return;}
                    detection[0]=suggestion;circles[0]=suggestion.rim;circles[1]=suggestion.pelletLevel;plane[0]=ContainerCalibration.suggestedPlane(suggestion);
                    quiet[0]=true;planes.check(plane[0]==ContainerCalibration.Plane.RIM?rim.getId():level.getId());showPlane.run();quiet[0]=false;update.run();
                    status.setText((suggestion.pelletLevel==null?"Borda detectada; nível das pelotas não encontrado, confira o plano.":"Borda e nível das pelotas detectados. Amarelo: plano em uso; tracejado: o outro.")+"\n"+status.getText());});});
        });
        cancel.setOnClickListener(v->dialog.dismiss());
        apply.setOnClickListener(v->{try{if(!confirmed.isChecked())return;ContainerCalibration.Reference ref=ContainerCalibration.select(editor.circle(),plane[0],parsePositive(real.getText().toString()),detection[0],ow,oh,w,h);Granulometry.Settings cfg=Granulometry.Settings.container(ref,parsePositive(bin.getText().toString()),scaleSettings==null||scaleSettings.excludePartial,scaleSettings!=null&&scaleSettings.excludeReview);Granulometry.compute(measurementItems(cfg),cfg,ow,oh,w,h);commitScale(cfg);dialog.dismiss();
            ContainerCalibration.Circle inner=circles[0]!=null?circles[0]:editor.circle();showRegionEditor(interestRegion==null?ContainerCalibration.interiorRegion(inner,w,h):interestRegion);}catch(IllegalArgumentException e){status.setText(e.getMessage());}});
        dialog.setContentView(root);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
        quiet[0]=true;planes.check(plane[0]==ContainerCalibration.Plane.RIM?rim.getId():level.getId());quiet[0]=false;update.run();if(previous==null)automatic.performClick();
    }
    private static double parsePositive(String value) {
        try {double result=Double.parseDouble(value.trim().replace(',','.'));
            if(!Double.isFinite(result)||result<=0)throw new NumberFormatException();return result;
        } catch(NumberFormatException e){throw new IllegalArgumentException("Informe números positivos, sem separador de milhar.");}
    }
    private DetectionAssessment.Context suggestionContext(){return new DetectionAssessment.Context(scaleSettings,originalWidth,originalHeight,interestRegion,minimumExpectedMm,suggestionWindow);}
    private DetectionTuner.Report rankedSuggestions(){return photoSuggestions==null||roiNeedsAnalysis||displayWindow!=null&&!displayWindow.sameScope(suggestionWindow)?null:photoSuggestions.rank(suggestionContext());}
    private void refreshSuggestionLabel(){
        if(profileLabel==null||mode!=Mode.PHOTO)return;
        if(photoDetailReport!=null&&!roiNeedsAnalysis){profileLabel.setText("Detalhada · ver comparação");return;}
        DetectionTuner.Report report=rankedSuggestions();
        if(report==null){profileLabel.setText("Testar ajustes · toque");return;}
        DetectionTuner.Trial best=report.best();
        profileLabel.setText(best==null?"Sem sugestão · revisar":report.weak()?"Sugestão · revisar":photoOption!=null&&photoOption.id.equals(best.option.id)?"Sugestão aplicada · ver":"Novo ajuste sugerido · ver");
    }
    private void showTuneProgress(java.util.concurrent.atomic.AtomicBoolean cancel){
        if(isDestroyed()||isFinishing())return;closeTuneProgress();tuningCancel=cancel;
        android.widget.ProgressBar progress=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(25);progress.setTag("tuning_progress");
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView label=text("Comparando ajustes na mesma foto…",13,Color.WHITE);label.setTag("tuning_label");content.addView(label);content.addView(progress);
        TextView budget=text("A busca tem orçamento de 30 s, verificado entre opções. Cancelar usa o ajuste atual. A análise de trincas será feita depois.",12,Color.LTGRAY);budget.setTag("tuning_budget_note");content.addView(budget);
        tuneProgress=new AlertDialog.Builder(this).setTitle("Buscando ajuste inicial").setView(content).setNegativeButton("Cancelar busca",(d,w)->cancel.set(true)).setOnCancelListener(d->cancel.set(true)).create();tuneProgress.show();
    }
    private void updateTuneProgress(int done,int total,String label){if(tuneProgress==null||!tuneProgress.isShowing())return;View root=tuneProgress.getWindow().getDecorView();android.widget.ProgressBar bar=root.findViewWithTag("tuning_progress");bar.setMax(total);bar.setProgress(done);((TextView)root.findViewWithTag("tuning_label")).setText(done+" / "+total+" · "+label);}
    private void closeTuneProgress(){if(tuneProgress!=null){tuneProgress.dismiss();tuneProgress=null;}}
    private void startSuggestionSearch(){
        startActiveAnalysis(true);
    }
    private void showSuggestions(){
        if(mode!=Mode.PHOTO)return;DetectionTuner.Report report=rankedSuggestions();if(report==null){startSuggestionSearch();return;}
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(8),dp(18),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        content.addView(text("Atual: "+(photoOption==null?"manual":photoOption.label())+"\nComparadas: "+report.trials.size()+" / "+report.planned+" · "+String.format(Locale.getDefault(),"%.1f",report.durationMs/1000.)+" s"+(report.cancelled?" · busca cancelada":report.timeLimited?" · orçamento atingido":""),13,Color.WHITE));
        content.addView(text(report.context.minimumActive()?"Limite esperado: "+HistogramView.number(minimumExpectedMm)+" mm. Detecções menores penalizam a pontuação; continuam visíveis e no histograma.":"Limite de "+HistogramView.number(minimumExpectedMm)+" mm "+(minimumExpectedMm==0?"desativado.":"inativo: calibre pela medida real. Escala estimada pela média não serve para este critério."),12,Color.rgb(82,216,190)));
        content.addView(text("Pontuação heurística, não probabilidade. Cobertura é área visual explicada, não percentual de pelotas corretamente identificadas. Prioriza a cobertura visual da foto, a área com elipses confiáveis, bordas e poucas sobreposições. Mais marcações, sozinhas, não aumentam a cobertura. Rosin–Rammler (Weibull), por número, é apenas um critério auxiliar com pelo menos 12 elipses válidas; a distribuição pode não seguir esse modelo.",12,Color.LTGRAY));
        if(report.weak())content.addView(text("Pouca evidência para sugerir com segurança. Confira a imagem e mantenha o ajuste manual se necessário.",13,Color.rgb(255,211,118)));
        android.widget.RadioGroup options=new android.widget.RadioGroup(this);options.setTag("suggestion_options");java.util.Map<Integer,DetectionTuner.Trial> choices=new java.util.LinkedHashMap<>();int selected=-1;
        for(DetectionTuner.Trial trial:report.ordered()){
            android.widget.RadioButton option=new android.widget.RadioButton(this);option.setId(View.generateViewId());option.setTextColor(Color.WHITE);option.setTextSize(12);option.setTag("suggestion_option_"+trial.option.id);option.setEnabled(trial.result!=null);choices.put(option.getId(),trial);
            DetectionAssessment.Metrics m=trial.metrics;
            String rr=m==null||!m.weibull.available()?"RR: n insuficiente/sem variação":"RR KS="+String.format(Locale.getDefault(),"%.3f",m.weibull.ks);
            option.setText(trial.option.label()+" · "+String.format(Locale.getDefault(),"%.1f",trial.score)+" pts"+(photoOption!=null&&photoOption.id.equals(trial.option.id)?" · ATUAL":"")+"\n"+(m==null?"Falha nesta opção: "+trial.error:"Válidas "+m.valid+" / "+m.total+(trial.coverage!=null&&trial.coverage.available?" · cobertura "+Math.round(trial.coverage.covered*100)+"%":"")+" · sobrepostas "+m.overlapping+(report.context.minimumActive()?" · < "+HistogramView.number(minimumExpectedMm)+" mm: "+m.belowMinimum:"")+" · "+rr));
            options.addView(option);if(selected<0&&trial.result!=null)selected=option.getId();
        }content.addView(options);if(selected>=0)options.check(selected);
        content.addView(text("Aplicar outro ajuste reanalisa as trincas e descarta as revisões manuais desta foto. Se o contorno da referência mudar, será necessário escolhê-la e calibrar novamente. Os tamanhos medidos nunca são ajustados para caber numa curva.",12,Color.LTGRAY));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Comparação de ajustes").setView(scroll).setNegativeButton("Manter atual",null).setNeutralButton("Testar novamente",(d,w)->startSuggestionSearch()).setPositiveButton("Usar selecionado",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            DetectionTuner.Trial chosen=choices.get(options.getCheckedRadioButtonId());if(chosen==null||chosen.result==null)return;
            if(photoOption!=null&&chosen.option.id.equals(photoOption.id)){dialog.dismiss();toast("Este ajuste já está aplicado.");return;}
            if(mode!=Mode.PHOTO||photoFile==null){dialog.dismiss();return;}
            File file=photoFile;String mime=photoMime;dialog.dismiss();setBusy(Mode.PROCESSING,"Aplicando ajuste escolhido…");worker.execute(()->processPhoto(file,mime,chosen.option.parameters,false,chosen));
        }));dialog.show();
    }
    private double minimumInput(EditText input){String raw=input.getText().toString().trim().replace(',','.');try{double value=Double.parseDouble(raw);if(Double.isFinite(value)&&value>=0&&value<=1000)return value;}catch(NumberFormatException ignored){}throw new IllegalArgumentException("Informe mínimo entre 0 e 1000 mm; zero desativa o critério.");}

    private PelletDetector.Result detectPellets(Mat gray,PelletDetector.Parameters cfg,int size,int scales){int m=method();NeuralPelletDetector nn=NeuralPelletDetector.forScene(cfg.belt);size=cfg.workSide(size);PelletDetector.Result seeds=m==DetectionTuner.NEURAL&&nn!=null?nn.detect(gray,profile,sensitivity,cfg):m==DetectionTuner.LOG?new PelletDetector().detect(gray,cfg,size,scales):detector.detect(gray,cfg,size,scales);return new EllipseFitter().refine(gray,seeds,cfg.belt);}
    private void showAdjustments() {
        if(mode==Mode.PROCESSING||mode==Mode.SAVING)return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(10),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        CheckBox auto=new CheckBox(this);auto.setText("Sugerir ajuste ao abrir ou fotografar");auto.setTextColor(Color.WHITE);auto.setChecked(autoSuggest);auto.setTag("auto_suggest");content.addView(auto);
        CheckBox autoArea=new CheckBox(this);autoArea.setText("Área automática (recipiente, bandeja ou correia carregada)");autoArea.setTextColor(Color.WHITE);autoArea.setChecked(autoRoi);autoArea.setTag("auto_roi");content.addView(autoArea);
        content.addView(text("Diâmetro mínimo esperado (mm; 0 desativa)",13,Color.WHITE));EditText minimum=new EditText(this);minimum.setTextColor(Color.WHITE);minimum.setSingleLine();minimum.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);minimum.setText(Double.toString(minimumExpectedMm));minimum.setTag("minimum_expected_mm");content.addView(minimum);
        content.addView(text("O mínimo só pontua com calibração física independente. Não apaga detecções nem recorta o histograma. Rosin–Rammler não é normal e não é imposta à amostra.",12,Color.LTGRAY));
        content.addView(text("Cena",14,Color.WHITE));android.widget.RadioGroup scenes=new android.widget.RadioGroup(this);int[] sceneIds={View.generateViewId(),View.generateViewId()};String[] sceneLabels={"Bancada · amostra espalhada, pelotas grandes na foto","Correia · pelotas pequenas e numerosas (até 5000 por foto)"};for(int i=0;i<2;i++){android.widget.RadioButton radio=new android.widget.RadioButton(this);radio.setId(sceneIds[i]);radio.setText(sceneLabels[i]);radio.setTextColor(Color.WHITE);radio.setTextSize(12);scenes.addView(radio);}scenes.check(sceneIds[beltScene?1:0]);content.addView(scenes);
        content.addView(text("Na correia, a análise usa até 1600 px (sem o modo detalhado), a triagem de trincas tende a ficar inconclusiva (pelotas pequenas demais na foto) e pelotas encobertas são medidas só pela borda visível.",12,Color.LTGRAY));
        content.addView(text("Ajuste manual de tamanho aparente",14,Color.WHITE));android.widget.RadioGroup sizes=new android.widget.RadioGroup(this);
        int[] optionIds={View.generateViewId(),View.generateViewId(),View.generateViewId(),View.generateViewId()};
        String[] labels={"Pequenas · diâmetro 2,6–7,6% da largura","Médias · diâmetro 4,2–12% da largura","Grandes · diâmetro 8–21% da largura","Automático · tamanhos variados e iluminação"};
        String[] beltLabels={"Finas · diâmetro 0,8–1,6% da largura","Médias · diâmetro 1,2–2,4% da largura","Grossas · diâmetro 1,8–3,2% da largura","Automático · diâmetro 0,8–3,2% da largura"};
        for(int i=0;i<labels.length;i++){android.widget.RadioButton radio=new android.widget.RadioButton(this);radio.setId(optionIds[i]);radio.setText((beltScene?beltLabels:labels)[i]);radio.setTextColor(Color.WHITE);radio.setTextSize(12);sizes.addView(radio);}sizes.check(optionIds[profile]);content.addView(sizes);
        content.addView(text("Método de localização",14,Color.WHITE));android.widget.RadioGroup methods=new android.widget.RadioGroup(this);int[] methodIds={View.generateViewId(),View.generateViewId(),View.generateViewId()};String[] methodLabels={"Bordas · gradientes com CLAHE","LoG · segmentação alternativa",NeuralPelletDetector.available()?"Rede neural · StarDist (experimental)":"Rede neural · indisponível"};for(int i=0;i<3;i++){android.widget.RadioButton radio=new android.widget.RadioButton(this);radio.setId(methodIds[i]);radio.setText(methodLabels[i]);radio.setTextColor(Color.WHITE);radio.setTextSize(12);radio.setEnabled(i!=DetectionTuner.NEURAL||NeuralPelletDetector.available());methods.addView(radio);}methods.check(methodIds[method()]);content.addView(methods);
        TextView neuralNote=text("",12,Color.LTGRAY);Runnable neuralText=()->{boolean belt=scenes.getCheckedRadioButtonId()==sceneIds[1];neuralNote.setText(NeuralPelletDetector.status(belt)+(belt?". Correia: rede treinada com pseudo-rótulos de consenso (sem revisão humana).":". Treinada sem fotos da usina.")+" A rede só propõe objetos; as elipses continuam medidas nas bordas da foto. Compare com os outros métodos antes de usar.");};neuralText.run();content.addView(neuralNote);
        scenes.setOnCheckedChangeListener((group,id)->{String[] l=id==sceneIds[1]?beltLabels:labels;for(int i=0;i<optionIds.length;i++)((android.widget.RadioButton)sizes.findViewById(optionIds[i])).setText(l[i]);neuralText.run();});
        TextView sensitivityLabel=text("Sensibilidade: "+sensitivity+"%",14,Color.WHITE);content.addView(sensitivityLabel);SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(sensitivity);content.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){sensitivityLabel.setText("Sensibilidade: "+p+"%");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        content.addView(text("Aplicar usa o ajuste manual nesta foto. A busca automática fica para a próxima foto. Maior sensibilidade pode criar falsas marcações. Alterar contornos pode exigir nova calibração e descarta revisões de trincas.",12,Color.LTGRAY));
        content.addView(text("Aceleração por hardware",14,Color.WHITE));
        CheckBox accel=new CheckBox(this);accel.setText("Rodar as redes neurais na GPU (LiteRT) · experimental");accel.setTextColor(Color.WHITE);accel.setTextSize(12);accel.setChecked(HardwareAcceleration.enabled());accel.setTag("hw_accel");content.addView(accel);
        TextView accelNote=text(accelerationText(HardwareAcceleration.status()),12,Color.LTGRAY);accelNote.setTag("hw_accel_status");content.addView(accelNote);
        accel.setOnCheckedChangeListener((b,on)->setAcceleration(on,accelNote));
        content.addView(text("Máscaras SAM 2",14,Color.WHITE));
        CheckBox sam2=new CheckBox(this);sam2.setText("Medir o tamanho pela máscara SAM 2.1 de cada pelota");sam2.setTextColor(Color.WHITE);sam2.setTextSize(12);sam2.setChecked(Sam2Refiner.enabled()&&Sam2Refiner.available());sam2.setEnabled(Sam2Refiner.available());sam2.setTag("sam2");content.addView(sam2);
        TextView sam2Note=text(sam2Text(),12,Color.LTGRAY);sam2Note.setTag("sam2_status");content.addView(sam2Note);
        sam2.setOnCheckedChangeListener((b,on)->{getPreferences(MODE_PRIVATE).edit().putBoolean("sam2",on).apply();Sam2Refiner.setEnabled(on);sam2Note.setText(sam2Text());});
        TextView error=text("",13,Color.rgb(255,146,146));error.setTag("adjustment_error");content.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ajustes de detecção").setView(scroll).setNegativeButton("Cancelar",null).setNeutralButton(mode==Mode.PHOTO?"Ver sugestão":"Como funciona",null).setPositiveButton("Aplicar manual",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{minimumExpectedMm=minimumInput(minimum);getPreferences(MODE_PRIVATE).edit().putString("expected_minimum_mm",Double.toString(minimumExpectedMm)).apply();refreshSuggestionLabel();dialog.dismiss();if(mode==Mode.PHOTO)showSuggestions();else new AlertDialog.Builder(this).setTitle("Sugestão na foto").setMessage("Ao abrir ou fotografar, são comparados quatro perfis e três sensibilidades nos métodos de bordas e LoG; com a rede neural escolhida como método, ela também entra com os quatro perfis. LoG também é testado quando não era o ajuste atual. A melhor pontuação inicia a análise. Toque em Sugestão para comparar ou manter um ajuste manual. Sem escala física, a comparação é em pixels; depois de calibrar, o limite em mm pode mudar a recomendação, sem trocar os contornos automaticamente.").setPositiveButton("Entendi",null).show();}catch(IllegalArgumentException e){error.setText(e.getMessage());}});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                minimumExpectedMm=minimumInput(minimum);autoSuggest=auto.isChecked();if(autoRoi!=autoArea.isChecked()){autoRoi=autoArea.isChecked();liveAutoRoi.reset();}for(int i=0;i<optionIds.length;i++)if(sizes.getCheckedRadioButtonId()==optionIds[i])profile=i;for(int i=0;i<methodIds.length;i++)if(methods.getCheckedRadioButtonId()==methodIds[i])detectionMethod=i;beltScene=scenes.getCheckedRadioButtonId()==sceneIds[1];sensitivity=slider.getProgress();applyParameters();epoch++;
                getPreferences(MODE_PRIVATE).edit().putInt("adaptive_profile",profile).putInt("sensitivity",sensitivity).putBoolean("legacy_detection",detectionMethod==DetectionTuner.LOG).putInt("detection_method",detectionMethod).putBoolean("belt_scene",beltScene).putBoolean("auto_suggest",autoSuggest).putBoolean("auto_roi",autoRoi).putString("expected_minimum_mm",Double.toString(minimumExpectedMm)).apply();dialog.dismiss();
                if(mode==Mode.PHOTO&&photoFile!=null){File file=photoFile;String mime=photoMime;PelletDetector.Parameters cfg=parameters;setBusy(Mode.PROCESSING,"Reanalisando com ajuste manual…");worker.execute(()->processPhoto(file,mime,cfg));}
            }catch(IllegalArgumentException e){error.setText(e.getMessage());}});
        });dialog.show();
    }
    private void restoreParameters() {
        // 0.18.0: uma vez por instalação, modelos mais assertivos (StarDist; volta ao gradiente se a rede faltar), SAM 2, MobileNetV3 e área automática.
        if(!getPreferences(MODE_PRIVATE).getBoolean("defaults018",false)){
            getPreferences(MODE_PRIVATE).edit().putInt("detection_method",DetectionTuner.NEURAL).putBoolean("sam2",true).putString("crack_classifier_v2",CrackClassifierChoice.DEFAULT.name()).putBoolean("auto_roi",true).putBoolean("defaults018",true).apply();Sam2Refiner.setEnabled(Sam2Refiner.available());
        }
        autoSuggest=getPreferences(MODE_PRIVATE).getBoolean("auto_suggest",true);
        autoRoi=getPreferences(MODE_PRIVATE).getBoolean("auto_roi",true);
        try{minimumExpectedMm=Double.parseDouble(getPreferences(MODE_PRIVATE).getString("expected_minimum_mm","5"));if(!Double.isFinite(minimumExpectedMm)||minimumExpectedMm<0||minimumExpectedMm>1000)minimumExpectedMm=5;}catch(NumberFormatException e){minimumExpectedMm=5;}
        detectionMethod=getPreferences(MODE_PRIVATE).getInt("detection_method",getPreferences(MODE_PRIVATE).getBoolean("legacy_detection",false)?DetectionTuner.LOG:DetectionTuner.NEURAL);if(detectionMethod<0||detectionMethod>2)detectionMethod=DetectionTuner.GRADIENT;beltScene=getPreferences(MODE_PRIVATE).getBoolean("belt_scene",false);
        profile=Math.max(0,Math.min(3,getPreferences(MODE_PRIVATE).getInt("adaptive_profile",3)));
        sensitivity=Math.max(0,Math.min(100,getPreferences(MODE_PRIVATE).getInt("sensitivity",75)));
        applyParameters();
    }
    private void applyParameters() {
        parameters=new DetectionTuner.Option(profile,sensitivity,method(),beltScene).parameters;
        if(profileLabel!=null)profileLabel.setText((beltScene?"Correia · ":"")+new String[]{"","LoG · ","Rede neural · "}[method()]+(beltScene?new String[]{"Pelotas finas","Pelotas médias","Pelotas grossas","Auto · correia"}:new String[]{"Elipses pequenas","Elipses médias","Elipses grandes","Auto · elipses e iluminação"})[profile]);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] granted) {
        super.onRequestPermissionsResult(request,permissions,granted);
        if(request!=CAMERA_PERMISSION)return;
        if(granted.length>0&&granted[0]==PackageManager.PERMISSION_GRANTED)preview.post(this::bindCamera);
        else {
            capture.setEnabled(false);hint.setText("Permita a câmera nas configurações ou abra uma foto.");
            new AlertDialog.Builder(this).setTitle("Acesso à câmera").setMessage("A câmera é necessária para analisar ao vivo e fotografar. Abrir fotos continua disponível.")
                    .setNegativeButton("Abrir fotos",null).setPositiveButton("Configurações",(dialog,which)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
        }
    }
    @Override protected void onPause(){unregisterUsbReceiver();super.onPause();}
    @Override protected void onResume(){super.onResume();registerUsbReceiver();if(nativeReady&&camera==null&&preview!=null&&ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)preview.post(this::bindCamera);}
    @Override protected void onDestroy() {
        epoch++;if(tuningCancel!=null)tuningCancel.set(true);closeTuneProgress();handler.removeCallbacksAndMessages(null);
        if(analysis!=null)analysis.clearAnalyzer();if(provider!=null)provider.unbindAll();
        synchronized(liveFrameLock){if(lastLiveGray!=null){lastLiveGray.release();lastLiveGray=null;}}
        worker.shutdown();
        // In-flight workers hold their own bitmap. Avoid deleting their source during a save/reanalysis.
        if(mode!=Mode.SAVING&&mode!=Mode.PROCESSING)releasePreviousPhoto();
        super.onDestroy();
    }
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,float size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private Button button(String label,boolean primary){Button v=new Button(this);v.setText(label);v.setAllCaps(false);v.setTextSize(14);v.setTextColor(primary?Color.rgb(16,26,35):Color.WHITE);v.setBackground(shape(primary?Color.rgb(82,216,190):Color.rgb(32,48,61),12));v.setMinWidth(0);v.setMinimumWidth(0);v.setPadding(dp(8),0,dp(8),0);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    
    /** Botão de visualização: Segmentação → Mapa → Original → Segmentação. Toque longo: opções do mapa. */
    private void toggleColorMap() {
        boolean live=mode==Mode.LIVE&&liveDistribution!=null&&lastLiveResult!=null;
        if(!live&&(mode!=Mode.PHOTO||photoResult==null||distribution==null)){toast("Aguarde a contagem ao vivo ou analise uma foto. Toque longo: opções do mapa.");return;}
        setViewMode(viewMode==1?2:viewMode==2?0:1);
        toast(viewMode==2?"Mapa granulométrico · toque longo no botão para opacidade e escala":viewMode==0?"Visualização original (sem marcações)":"Visualização de segmentação");
    }
    private void setViewMode(int value){viewMode=value;colorMapVisible=value==2;applyViewMode();}
    private void applyViewMode(){
        if(mapButton!=null)mapButton.setText(viewMode==2?"Mapa":viewMode==0?"Orig.":"Seg.");
        if(colorMap==null||overlay==null)return;
        boolean photo=mode==Mode.PHOTO&&photoResult!=null&&distribution!=null||mode==Mode.LIVE&&liveDistribution!=null&&lastLiveResult!=null;
        colorMap.setVisibility(photo&&viewMode==2?View.VISIBLE:View.GONE);
        overlay.setVisibility(!photo||viewMode==1?View.VISIBLE:View.INVISIBLE);
    }

    /** Oculta o mapa e devolve o overlay (marcações, toque para revisar trincas e calibrar). */
    private void hideColorMap(boolean resetToggle) {
        if(resetToggle){colorMapVisible=false;viewMode=1;}
        if(mapButton!=null&&resetToggle)mapButton.setText("Seg.");
        if(colorMap==null)return;
        colorMap.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
    }

    private void showHistogram(){
        if(distribution==null||histogram==null)return;
        crackHistogram=CrackHistogram.compute(distribution,measurementItems(),photoCracks);
        histogram.display(distribution,crackHistogram,visuals);
        if(rosinRammler!=null)rosinRammler.display(RosinRammler.fit(distribution,rrBasis()));
    }
    private void setChartsVisibility(int visibility){
        if(chartSlot!=null)chartSlot.setVisibility(visibility);if(chartTabs!=null)chartTabs.setVisibility(visibility);
        // Oculto: os dois gráficos ficam GONE (contrato anterior); visível: só a aba escolhida aparece.
        if(visibility==View.VISIBLE)selectChart(getPreferences(MODE_PRIVATE).getBoolean("chart_rr",false));
        else{histogram.setVisibility(visibility);if(rosinRammler!=null)rosinRammler.setVisibility(visibility);}
    }
    private void selectChart(boolean rr){
        getPreferences(MODE_PRIVATE).edit().putBoolean("chart_rr",rr).apply();
        histogram.setVisibility(rr?View.INVISIBLE:View.VISIBLE);rosinRammler.setVisibility(rr?View.VISIBLE:View.INVISIBLE);
        histogramTab.setBackgroundColor(rr?Color.TRANSPARENT:Color.rgb(38,140,171));rrTab.setBackgroundColor(rr?Color.rgb(38,140,171):Color.TRANSPARENT);
        histogramTab.setTextColor(rr?Color.rgb(151,174,187):Color.WHITE);rrTab.setTextColor(rr?Color.WHITE:Color.rgb(151,174,187));
    }
    private RosinRammler.Basis rrBasis(){return RosinRammler.Basis.fromId(getPreferences(MODE_PRIVATE).getString("rr_basis","number"));}
    /** Base da curva: número (o que a foto mede) ou massa estimada ∝ d³ (aproxima a base das curvas de peneira). */
    private void showRosinRammlerOptions(){
        RosinRammler.Basis[] bases=RosinRammler.Basis.values();String[] labels=new String[bases.length];for(int i=0;i<bases.length;i++)labels[i]=bases[i].label;
        new AlertDialog.Builder(this).setTitle("Rosin–Rammler · base da curva").setSingleChoiceItems(labels,rrBasis().ordinal(),(d,which)->{getPreferences(MODE_PRIVATE).edit().putString("rr_basis",bases[which].id).apply();showHistogram();d.dismiss();})
            .setNegativeButton("Fechar",null).show();
    }

    private String crackMethodLabel(){
        CrackClassifierChoice.Resolution r=crackResolution;CrackClassifierChoice c=r==null?CrackClassifierChoice.RANDOM_FOREST:r.effective;
        if(crackModel==null&&c==CrackClassifierChoice.RANDOM_FOREST)return "Heurística · ";
        return c==CrackClassifierChoice.MOBILENET?"MobileNetV3 experimental · ":c==CrackClassifierChoice.COMPARISON?"RF (comparação com MobileNetV3) · ":"RF experimental · ";
    }

    /** Resolved detector for the selected classifier; the network is loaded only when it is requested. */
    private CrackDetector crackDetector(){
        CrackClassifierChoice requested=crackChoice;MobileNetCrackModel net=null;
        if(requested.needsNetwork()){
            net=crackNetwork;
            if(net==null){try{net=MobileNetCrackModel.load(copyAsset(MobileNetCrackModel.MODEL),readAsset(MobileNetCrackModel.METADATA));}catch(Exception e){MobileNetCrackModel.lastError=e.getMessage();net=null;}crackNetwork=net;}
        }else if(crackNetwork!=null){crackNetwork.close();crackNetwork=null;} // não manter a rede carregada sem uso (libera a GPU)
        CrackClassifierChoice.Resolution r=CrackClassifierChoice.resolve(requested,crackModel!=null,net!=null,MobileNetCrackModel.lastError);
        crackResolution=r;
        if(r.message!=null){final String m=r.message;runOnUiThread(()->toast(m));}
        return new CrackDetector(crackModel,r.effective.needsNetwork()?net:null,r.effective);
    }

    private void restoreVisuals(){
        android.content.SharedPreferences p=getPreferences(MODE_PRIVATE);
        CrackHistogram.Normalization n;try{n=CrackHistogram.Normalization.valueOf(p.getString("hist_normalization","PERCENT_OF_ALL"));}catch(IllegalArgumentException e){n=CrackHistogram.Normalization.PERCENT_OF_ALL;}
        visuals=new ReportVisuals(p.getFloat("map_opacity_v2",(float)GranulometryColorScale.DEFAULT_OPACITY),p.getBoolean("map_labels",false),p.getBoolean("map_fixed",false),
            p.getFloat("map_fixed_min",8),p.getFloat("map_fixed_max",16),p.getBoolean("hist_all",true),p.getBoolean("hist_cracked",true),p.getBoolean("hist_values",false),n);
        // 0.15.5: chave nova para o novo padrão valer uma vez (a antiga era gravada junto com os gráficos, mesmo sem escolha).
        crackChoice=CrackClassifierChoice.parse(p.getString("crack_classifier_v2",CrackClassifierChoice.DEFAULT.name()));
    }
    private void saveVisuals(){
        getPreferences(MODE_PRIVATE).edit().putFloat("map_opacity_v2",(float)visuals.mapOpacity).putBoolean("map_labels",visuals.mapLabels).putBoolean("map_fixed",visuals.mapFixed)
            .putFloat("map_fixed_min",(float)visuals.mapFixedMin).putFloat("map_fixed_max",(float)visuals.mapFixedMax).putBoolean("hist_all",visuals.showAll).putBoolean("hist_cracked",visuals.showCracked)
            .putBoolean("hist_values",visuals.showValues).putString("hist_normalization",visuals.normalization.name()).putString("crack_classifier_v2",crackChoice.name()).apply();
    }
    private void refreshVisuals(){saveVisuals();if(colorMap!=null)colorMap.visuals(visuals);showHistogram();applyViewMode();}

    private CheckBox check(LinearLayout content,String label,boolean checked,String tag){CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(Color.WHITE);c.setTextSize(13);c.setChecked(checked);c.setTag(tag);content.addView(c);return c;}

    /** Opções do mapa granulométrico: opacidade 0–70%, diâmetros sobre as pelotas, escala relativa ou fixa em mm. */
    private void showMapOptions(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        TextView opacityLabel=text("",14,Color.WHITE);content.addView(opacityLabel);
        SeekBar opacity=new SeekBar(this);opacity.setMax(90);opacity.setProgress((int)Math.round(visuals.mapOpacity*100));opacity.setTag("map_opacity");content.addView(opacity);
        Runnable label=()->opacityLabel.setText("Opacidade da cor: "+opacity.getProgress()+"% (foto "+(100-opacity.getProgress())+"%)");label.run();
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int v,boolean u){label.run();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        CheckBox labels=check(content,"Mostrar o diâmetro sobre cada pelota",visuals.mapLabels,"map_labels");
        content.addView(text("Rótulos sobrepostos ou em pelotas pequenas na tela são omitidos para manter a leitura.",11,Color.LTGRAY));
        CheckBox fixed=check(content,"Escala fixa (comparar fotos diferentes)",visuals.mapFixed,"map_fixed");
        LinearLayout range=row();EditText min=new EditText(this),max=new EditText(this);
        for(EditText e:new EditText[]{min,max}){e.setTextColor(Color.WHITE);e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);range.addView(e,new LinearLayout.LayoutParams(0,-2,1));}
        min.setText(HistogramView.number(visuals.mapFixedMin).replace(',','.'));max.setText(HistogramView.number(visuals.mapFixedMax).replace(',','.'));min.setTag("map_min");max.setTag("map_max");
        content.addView(text("Mínimo e máximo ("+(distribution==null?"mm":distribution.settings.unit())+")",12,Color.LTGRAY));content.addView(range);
        content.addView(text("Escala relativa: do menor ao maior diâmetro incluído nesta foto. Fixa: cores comparáveis entre fotos; valores fora da faixa ficam nas cores das pontas. Todos iguais: cor intermediária.",11,Color.LTGRAY));
        TextView error=text("",12,Color.rgb(255,146,146));content.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Mapa granulométrico").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            double lo=parseNumber(min.getText().toString()),hi=parseNumber(max.getText().toString());
            if(fixed.isChecked())GranulometryColorScale.fixed(lo,hi,"mm");
            visuals=visuals.withMap(opacity.getProgress()/100.0,labels.isChecked(),fixed.isChecked(),lo,hi);refreshVisuals();
            if(mode==Mode.PHOTO&&photoResult!=null&&distribution!=null)setViewMode(2);dialog.dismiss();
        }catch(IllegalArgumentException e){error.setText(e.getMessage());}}));dialog.show();
    }
    private static double parseNumber(String value){try{return Double.parseDouble(value.trim().replace(',','.'));}catch(NumberFormatException e){throw new IllegalArgumentException("Número inválido.");}}

    /** Opções do gráfico: séries, valores nas barras, frequência absoluta ou percentual. */
    private void showHistogramOptions(){
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        CheckBox all=check(content,"Mostrar todas as pelotas",visuals.showAll,"hist_all");
        CheckBox cracked=check(content,"Mostrar pelotas trincadas",visuals.showCracked,"hist_cracked");
        CheckBox values=check(content,"Exibir valores nas barras",visuals.showValues,"hist_values");
        content.addView(text("Frequência",14,Color.WHITE));android.widget.RadioGroup group=new android.widget.RadioGroup(this);
        CrackHistogram.Normalization[] modes=CrackHistogram.Normalization.values();String[] labels={"Absoluta · número de pelotas","Percentual do total de pelotas válidas (trincadas como subconjunto)","Condicional · cada série soma 100% (distribuição entre as trincadas)"};int[] ids=new int[modes.length];
        for(int i=0;i<modes.length;i++){android.widget.RadioButton r=new android.widget.RadioButton(this);ids[i]=View.generateViewId();r.setId(ids[i]);r.setText(labels[i]);r.setTextColor(Color.WHITE);r.setTextSize(12);group.addView(r);if(modes[i]==visuals.normalization)group.check(ids[i]);}
        content.addView(group);
        content.addView(text("A série laranja usa as mesmas classes e eixos e nunca é somada ao total. Sem classificação válida, aparece \"classificação indisponível\", não zero. Percentual de trincadas numa faixa ≠ percentual da população total.",11,Color.LTGRAY));
        new AlertDialog.Builder(this).setTitle("Gráfico granulométrico").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",(d,w)->{
            CrackHistogram.Normalization n=visuals.normalization;for(int i=0;i<ids.length;i++)if(group.getCheckedRadioButtonId()==ids[i])n=modes[i];
            visuals=visuals.withHistogram(all.isChecked()||!cracked.isChecked(),cracked.isChecked(),values.isChecked(),n);refreshVisuals();}).show();
    }

    /** Modelos de análise: segmentação, classificação de trincas e visualização (modos sem validação aparecem como experimentais/indisponíveis). */
    private void showAnalysisModels(){
        if(mode==Mode.PROCESSING||mode==Mode.SAVING)return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        content.addView(text("Segmentação",15,Color.WHITE));android.widget.RadioGroup seg=new android.widget.RadioGroup(this);
        String[] segLabels={"Clássica · bordas (gradientes com CLAHE)","Clássica · LoG",NeuralPelletDetector.available(beltScene)?"Neural · StarDist (experimental)":"Neural · StarDist — indisponível","Neural · YOLO11-seg — indisponível","Híbrida — indisponível"};
        int[] segIds=new int[segLabels.length];
        for(int i=0;i<segLabels.length;i++){android.widget.RadioButton r=new android.widget.RadioButton(this);segIds[i]=View.generateViewId();r.setId(segIds[i]);r.setText(segLabels[i]);r.setTextColor(Color.WHITE);r.setTextSize(12);r.setEnabled(i<2||(i==2&&NeuralPelletDetector.available(beltScene)));seg.addView(r);}
        seg.check(segIds[method()]);content.addView(seg);
        content.addView(text("YOLO11-seg: sem máscaras de instância com rótulo humano para treinar e validar; código e pesos da Ultralytics são AGPL-3.0 (exige revisão de licença antes de distribuir). Híbrida: não há regra de fusão validada. Segmentação mais bonita não prova diâmetro mais exato.",11,Color.LTGRAY));
        content.addView(text("Classificação de trincas",15,Color.WHITE));android.widget.RadioGroup cls=new android.widget.RadioGroup(this);
        CrackClassifierChoice[] choices=CrackClassifierChoice.values();int[] clsIds=new int[choices.length];
        boolean assetPresent;try{getAssets().open(MobileNetCrackModel.MODEL).close();assetPresent=true;}catch(Exception e){assetPresent=false;}
        for(int i=0;i<choices.length;i++){android.widget.RadioButton r=new android.widget.RadioButton(this);clsIds[i]=View.generateViewId();r.setId(clsIds[i]);r.setText(choices[i].label+(choices[i].needsNetwork()&&!assetPresent?" — indisponível":""));r.setTextColor(Color.WHITE);r.setTextSize(12);r.setEnabled(!choices[i].needsNetwork()||assetPresent);cls.addView(r);if(choices[i]==crackChoice)cls.check(clsIds[i]);}
        content.addView(cls);
        content.addView(text("A MobileNetV3 é o padrão: em dois testes independentes (foto de 7 trincas e 57 fotos de bancada nunca vistas) acertou mais que o Random Forest, que fica como reserva. Ainda não foi validada em produção nem em pelotas cruas na panela. Os recortes vêm dos pixels originais, nunca da super-resolução. O escore não é probabilidade calibrada. Localização de trincas por U-Net: indisponível (não há máscaras reais de trinca).",11,Color.LTGRAY));
        content.addView(text("Visualização",15,Color.WHITE));android.widget.RadioGroup view=new android.widget.RadioGroup(this);
        String[] viewLabels={"Original","Segmentação","Mapa granulométrico"};int[] viewIds=new int[3];
        for(int i=0;i<3;i++){android.widget.RadioButton r=new android.widget.RadioButton(this);viewIds[i]=View.generateViewId();r.setId(viewIds[i]);r.setText(viewLabels[i]);r.setTextColor(Color.WHITE);r.setTextSize(12);view.addView(r);}
        view.check(viewIds[viewMode]);content.addView(view);
        Button mapOptions=button("Opções do mapa…",false);mapOptions.setTextSize(12);content.addView(mapOptions);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Modelos de análise").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",null).create();
        mapOptions.setOnClickListener(v->{dialog.dismiss();showMapOptions();});
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            int newMethod=detectionMethod;for(int i=0;i<3;i++)if(seg.getCheckedRadioButtonId()==segIds[i])newMethod=i;
            CrackClassifierChoice newChoice=crackChoice;for(int i=0;i<choices.length;i++)if(cls.getCheckedRadioButtonId()==clsIds[i])newChoice=choices[i];
            int newView=viewMode;for(int i=0;i<3;i++)if(view.getCheckedRadioButtonId()==viewIds[i])newView=i;
            boolean segChanged=newMethod!=method(),clsChanged=newChoice!=crackChoice;
            detectionMethod=newMethod;crackChoice=newChoice;if(!newChoice.needsNetwork())crackNetwork=null;
            getPreferences(MODE_PRIVATE).edit().putInt("detection_method",detectionMethod).putBoolean("legacy_detection",detectionMethod==DetectionTuner.LOG).apply();saveVisuals();
            applyParameters();refreshSuggestionLabel();dialog.dismiss();setViewMode(newView);
            if((segChanged||clsChanged)&&mode==Mode.PHOTO&&photoFile!=null){epoch++;File file=photoFile;String mime=photoMime;PelletDetector.Parameters cfg=segChanged?parameters:photoParameters;setBusy(Mode.PROCESSING,"Reanalisando com os modelos escolhidos…");worker.execute(()->processPhoto(file,mime,cfg));}
        }));dialog.show();
    }

    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}

