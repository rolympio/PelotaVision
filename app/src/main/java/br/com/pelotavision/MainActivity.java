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
    /** Visualização: 0 original (só a foto), 1 segmentação (marcações), 2 mapa granulométrico. */
    private int viewMode=1;private Button mapButton;
    private ReportVisuals visuals=ReportVisuals.defaults();
    private CrackHistogram crackHistogram;
    private volatile CrackClassifierChoice crackChoice=CrackClassifierChoice.RANDOM_FOREST;
    private volatile CrackClassifierChoice.Resolution crackResolution;
    private volatile MobileNetCrackModel crackNetwork;
    private int originalWidth,originalHeight;
    private Granulometry.Settings scaleSettings;
    private Granulometry.Distribution distribution;
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
        nativeReady=OpenCVLoader.initLocal();
        if(!nativeReady){hint.setText("O processamento não iniciou neste aparelho.");capture.setEnabled(false);open.setEnabled(false);return;}
        org.opencv.core.Core.setNumThreads(2);
        try(InputStream model=getAssets().open("crack_forest.pvrf")){crackModel=new CrackModel(model);}
        catch(Exception e){toast("Modelo de trincas indisponível: usando triagem heurística.");}
        installNeuralModel();restoreVisuals();
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
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(16,26,35));
        root.setPadding(dp(18),dp(8),dp(18),dp(12));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)-> {
            int top,bottom,left,right;
            if(Build.VERSION.SDK_INT>=30){Insets i=insets.getInsets(WindowInsets.Type.systemBars());top=i.top;bottom=i.bottom;left=i.left;right=i.right;}
            else {top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();left=insets.getSystemWindowInsetLeft();right=insets.getSystemWindowInsetRight();}
            root.setPadding(dp(18)+left,dp(8)+top,dp(12)+right,dp(12)+bottom);return insets;
        });
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
        FrameLayout frame=new FrameLayout(this);frame.setBackground(shape(Color.BLACK,18));frame.setClipToOutline(true);root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
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
        regionButton=button("Área",false);regionButton.setTextSize(11);regionButton.setOnClickListener(v->showRegionEditor());scaleRow.addView(regionButton,new LinearLayout.LayoutParams(dp(66),dp(40)));
        calibration=button("Escala / mm",false);calibration.setTextSize(11);calibration.setEnabled(false);
        calibration.setOnClickListener(v->showCalibration());scaleRow.addView(calibration,new LinearLayout.LayoutParams(dp(108),dp(40)));
        roiActions=row();roiActions.setVisibility(View.GONE);root.addView(roiActions);
        segmentRoi=button("Segmentar foto",false);segmentRoi.setTag("segment_active_roi");segmentRoi.setTextSize(11);segmentRoi.setOnClickListener(v->showSegmentationOptions());roiActions.addView(segmentRoi,new LinearLayout.LayoutParams(0,dp(42),1));
        searchRoi=button("Buscar melhor ajuste",false);searchRoi.setTag("search_active_roi");searchRoi.setTextSize(11);searchRoi.setOnClickListener(v->startActiveAnalysis(true));roiActions.addView(searchRoi,new LinearLayout.LayoutParams(0,dp(42),1));
        histogram=new HistogramView(this);histogram.setVisibility(View.GONE);histogram.setOnClickListener(v->showHistogramOptions());root.addView(histogram,new LinearLayout.LayoutParams(-1,dp(180)));
        LinearLayout tools=row();root.addView(tools);
        profileLabel=text("Pelotas médias",11,Color.rgb(151,174,187));profileLabel.setTag("suggestion_label");profileLabel.setOnClickListener(v->{if(photoDetailReport!=null&&!roiNeedsAnalysis)showDetailSummary();else showSuggestions();});tools.addView(profileLabel,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox numberBox=new CheckBox(this);numberBox.setText("Números");numberBox.setTextSize(11);numberBox.setTextColor(Color.WHITE);numberBox.setChecked(true);
        numberBox.setOnCheckedChangeListener((button,checked)-> {numbers=checked;if(mode==Mode.PHOTO&&photoResult!=null)overlay.display(photoResult,numbers);else if(mode==Mode.LIVE&&lastLiveResult!=null)overlay.display(lastLiveResult,numbers);});tools.addView(numberBox);
        Button mapBtn=button("Seg.",false);mapBtn.setTag("view_mode");mapBtn.setTextSize(11);mapBtn.setOnClickListener(v->toggleColorMap());mapBtn.setOnLongClickListener(v->{showMapOptions();return true;});mapButton=mapBtn;tools.addView(mapBtn,new LinearLayout.LayoutParams(dp(64),dp(40)));
        torch=button("Luz",false);torch.setTextSize(11);torch.setOnClickListener(v->{if(mode==Mode.PHOTO)showLowLightDialog();else toggleTorch();});tools.addView(torch,new LinearLayout.LayoutParams(dp(58),dp(40)));
        adjust=button("Ajustes",false);adjust.setTextSize(11);adjust.setOnClickListener(v->showAdjustments());tools.addView(adjust,new LinearLayout.LayoutParams(dp(77),dp(40)));
        cameraResolutionLabel=text("Câmera: verificando resolução…",11,Color.rgb(151,174,187));
        cameraResolutionLabel.setOnClickListener(v->showCameraSettings());root.addView(cameraResolutionLabel);
        LinearLayout actions=row();actions.setPadding(0,dp(7),0,0);root.addView(actions);
        capture=button("Fotografar",true);capture.setEnabled(false);capture.setOnClickListener(v->capturePhoto());actions.addView(capture,new LinearLayout.LayoutParams(0,dp(49),1));
        save=button("Salvar foto + dados",true);save.setOnClickListener(v->savePhoto());save.setVisibility(View.GONE);actions.addView(save,new LinearLayout.LayoutParams(0,dp(49),1));
        open=button("Abrir foto",false);open.setOnClickListener(v->openPhoto());LinearLayout.LayoutParams other=new LinearLayout.LayoutParams(dp(112),dp(49));other.leftMargin=dp(8);actions.addView(open,other);
        back=button("Câmera",false);back.setOnClickListener(v->returnToCamera());back.setVisibility(View.GONE);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(95),dp(49));bp.leftMargin=dp(8);actions.addView(back,bp);
        TextView foot=text("Contagem automática · requer revisão",10,Color.rgb(151,174,187));foot.setGravity(Gravity.CENTER);foot.setPadding(0,dp(8),0,0);root.addView(foot);
    }
    private void bindCamera() {
        if(binding||!nativeReady||isDestroyed()||isFinishing())return;
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        if(preview.getWidth()==0||preview.getHeight()==0){preview.postDelayed(this::bindCamera,100);return;}
        binding=true;ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()-> {
            binding=false;if(isDestroyed()||isFinishing())return;
            try {
                provider=future.get();provider.unbindAll();
                Preview usePreview=new Preview.Builder().setTargetRotation(Surface.ROTATION_0).build();usePreview.setSurfaceProvider(preview.getSurfaceProvider());
                analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480)).setTargetRotation(Surface.ROTATION_0)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(worker,image-> {
                    Mat gray=null;
                    try {
                        long now=SystemClock.elapsedRealtime();if(mode!=Mode.LIVE||now-lastAnalysis<220)return;
                        lastAnalysis=now;long revision=epoch;PelletDetector.Parameters cfg=parameters;
                        gray=ImageCodec.luma(image);PelletDetector.Result result=detectPellets(gray,cfg,640,8);
                        runOnUiThread(()-> {
                            if(isDestroyed()||mode!=Mode.LIVE||revision!=epoch)return;
                            lastLiveResult=result;lastResult=SystemClock.elapsedRealtime();overlay.display(result,numbers);
                            showCount(result,false);
                        });
                    } catch(Exception e) {runOnUiThread(()-> {if(!isDestroyed()&&mode==Mode.LIVE)hint.setText("Falha na análise. Tente fotografar ou abrir uma imagem.");});}
                    finally {if(gray!=null)gray.release();image.close();}
                });
                requestedPhotoSize=maximumJpegResolution();
                imageCapture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(PHOTO_JPEG_QUALITY)
                        .setTargetResolution(requestedPhotoSize).setTargetRotation(Surface.ROTATION_0).build();
                UseCaseGroup.Builder group=new UseCaseGroup.Builder().addUseCase(usePreview).addUseCase(analysis).addUseCase(imageCapture);
                if(preview.getViewPort()!=null)group.setViewPort(preview.getViewPort());
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,group.build());
                capture.setEnabled(true);torch.setEnabled(camera.getCameraInfo().hasFlashUnit());
                cameraResolutionLabel.setText("Foto: máxima solicitada "+requestedPhotoSize.getWidth()+"×"+requestedPhotoSize.getHeight()+" · JPEG 95% · toque para detalhes");
            } catch(Exception e) {capture.setEnabled(false);hint.setText("Câmera indisponível. Você pode abrir uma foto.");cameraResolutionLabel.setText("Câmera: configuração não suportada neste dispositivo");}
        },ContextCompat.getMainExecutor(this));
    }
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
        if(mode!=Mode.LIVE||imageCapture==null)return;
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
        try {startActivityForResult(intent,OPEN_PHOTO);}catch(Exception e){toast("Nenhum seletor de fotos disponível.");}
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=OPEN_PHOTO||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();setBusy(Mode.PROCESSING,"Abrindo foto…");epoch++;
        worker.execute(()-> {
            File copy=new File(getCacheDir(),"importada_"+System.currentTimeMillis()+".img");
            try {
                try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(copy)) {
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
    private void processPhoto(File file,String mime,PelletDetector.Parameters cfg) {processPhoto(file,mime,cfg,false,null);}
    private void processPhoto(File file,String mime,PelletDetector.Parameters cfg,boolean forceSearch,DetectionTuner.Trial cached) {
        if(detailMode&&photoFile!=null&&photoFile.equals(file)){processDetailedPhoto(file,mime,cfg,forceSearch,cached==null?null:cached.option);return;}
        Bitmap bitmap=null;Mat gray=null,fullGray=null;
        final boolean replacingCurrent=photoFile!=null&&photoFile.equals(file);
        try {
            int[] sourceSize=ImageCodec.originalUprightSize(file);
            bitmap=ImageCodec.upright(file);
            AnalysisWindow window=new AnalysisWindow(bitmap.getWidth(),bitmap.getHeight(),replacingCurrent?interestRegion:null);window.requireDetectable();
            fullGray=ImageCodec.gray(bitmap);gray=window.region==null?fullGray:window.cropGray(fullGray);
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
                if(replacingCurrent)photoFile=null;
                releasePreviousPhoto();photoBitmap=ready;photoFiltered=filteredReady;photoFile=file;photoMime=mime;photoResult=result;photoCracks=cracks;photoParameters=selectedOption.parameters;photoOption=selectedOption;photoSuggestions=searchReport;
                photoAnalysisWindow=window;suggestionWindow=searchReport==null?null:window;roiNeedsAnalysis=false;
                originalWidth=sourceSize[0];originalHeight=sourceSize[1];scaleSettings=previousScale;
                photoAllResult=result;photoAllCracks=cracks;photoLowLight=analyzedLight;interestRegion=window.region;cropRoiReport=interestRegion!=null;
                frozen.setVisibility(View.VISIBLE);mode=Mode.PHOTO;applyRegion(interestRegion);
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
    /** Copia os ONNX dos assets (sempre substituídos) e instala: bancada e, em slot separado, correia. Ausência ou falha só desativa o modelo afetado. */
    private void installNeuralModel(){
        try{NeuralPelletDetector.install(copyAsset(NeuralPelletDetector.MODEL),readAsset(NeuralPelletDetector.METADATA));}catch(Exception e){android.util.Log.w("PelotaVision","Rede neural indisponível",e);}
        try{NeuralPelletDetector.installBelt(copyAsset(NeuralPelletDetector.BELT_MODEL),readAsset(NeuralPelletDetector.BELT_METADATA));}catch(Exception e){android.util.Log.w("PelotaVision","Rede neural de correia indisponível; correia usa o modelo de bancada",e);}
    }
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
                photoFile=null;releasePreviousPhoto();photoBitmap=displayed;photoFile=file;photoMime=mime;originalWidth=source[0];originalHeight=source[1];scaleSettings=previous;photoAllResult=analyzed.result;photoAllCracks=analyzed.cracks;photoParameters=chosen.parameters;photoOption=chosen;photoSuggestions=null;suggestionWindow=null;photoAnalysisWindow=scope;photoDetailReport=detailedReport;photoLowLight=null;detailMode=true;detailCompare=compare;detailSr=sr;interestRegion=scope.region;cropRoiReport=scope.region!=null;roiNeedsAnalysis=false;frozen.setVisibility(View.VISIBLE);mode=Mode.PHOTO;applyRegion(interestRegion);
                hint.setText(analyzed.report.summary()+". Toque em Detalhada para comparar. "+(changed?"O contorno da referência mudou; calibre novamente.":"Confira os contornos antes de salvar."));if(changed)toast("Referência alterada; escala removida.");
            });
        }catch(Exception|OutOfMemoryError e){if(ready!=null)ready.recycle();final String reason=e instanceof java.util.concurrent.CancellationException?"Processamento cancelado; resultado anterior preservado.":e instanceof IllegalArgumentException&&e.getMessage()!=null?e.getMessage():"Não foi possível concluir a análise detalhada. Reduza a ROI ou desative SR e tente novamente.";runOnUiThread(()->{if(!isDestroyed()){closeTuneProgress();mode=Mode.PHOTO;applyRegion(interestRegion);hint.setText(reason);toast(reason);}});
        }finally{if(input!=null)input.close();if(small!=null&&small!=previewGray)small.release();if(previewGray!=null)previewGray.release();}
    }
    private void savePhoto() {
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
        InterestRegion exportedRegion=interestRegion;int outside=regionExcludedCount();final boolean exportedCrop=cropRoiReport;final DetailedAnalysis.Report exportedDetail=photoDetailReport;final LowLightInfo exportedLight=photoLowLight;
        DetectionTuner.Report exportedSuggestions=rankedSuggestions();DetectionTuner.Option exportedOption=photoOption;
        setBusy(Mode.SAVING,"Salvando…");
        worker.execute(()-> {
            try {
                ResultSaver.SavedResult saved=ResultSaver.save(getApplicationContext(),copy,source,mime,result,cfg,exportedCracks,exportedDistribution,exportedRegion,outside,exportedSuggestions,exportedOption,exportedCrop,exportedDetail,exportedVisuals,exportedChoice,exportedNetwork,exportedLight,exportedFiltered);
                runOnUiThread(()-> {if(!isDestroyed()){
                    mode=Mode.PHOTO;updateControls();hint.setText(saved.pdf!=null?"Relatório PDF salvo em Downloads / PelotaVision.":"Histograma salvo no arquivo _resultado.png. Dados em Downloads / PelotaVision.");
                    new AlertDialog.Builder(this).setTitle("Resultado salvo")
                        .setMessage((saved.pdf!=null?"Relatório PDF: Downloads/PelotaVision/"+saved.base+"_relatorio.pdf (resumo, iluminação, modelos, classes e uma figura por imagem: original, filtrada, segmentada, mapa e histograma).\n\n":"O PDF não pôde ser gerado; os demais arquivos foram salvos.\n\n")+"O relatório "+saved.base+"_resultado.png contém a imagem sem marcações, a imagem com elipses e possíveis trincas e o histograma (todas × trincadas). O mapa granulométrico com legenda fica em "+saved.base+"_mapa.png. Quando selecionada, a ROI é ampliada nos dois painéis. O arquivo _original preserva os bytes da entrada."+(exportedFiltered!=null?"\n\nFiltro de iluminação aplicado: a imagem filtrada está em "+saved.base+"_filtrada.png e a segmentação e o mapa foram desenhados sobre ela. As medidas seguem nos pixels originais.":""))
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
        epoch++;mode=Mode.LIVE;lastResult=0;lastLiveResult=null;releasePreviousPhoto();hideColorMap(true);
        frozen.setVisibility(View.GONE);overlay.clear();badge.setText("AO VIVO");count.setText("—");shapeLabel.setText("Esfericidade aparente 2D (b/a): —");detail.setText("Aguardando análise");
        hint.setText("Mantenha a câmera parada. Toque na imagem para focar.");updateControls();
    }
    private void releasePreviousPhoto() {
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
        if(histogram!=null){histogram.display(null);histogram.setVisibility(View.GONE);}
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
        adjust.setEnabled(!busy);torch.setEnabled(!busy&&(photo||(camera!=null&&camera.getCameraInfo().hasFlashUnit())));torch.setText(photo?"Luz/ROI":torchOn?"Luz ✓":"Luz");
        calibration.setEnabled(!busy&&photoResult!=null&&mode==Mode.PHOTO);
        if(regionButton!=null){regionButton.setEnabled(!busy&&mode==Mode.PHOTO&&photoBitmap!=null);regionButton.setText(interestRegion==null?"Área":"Área ✓");}
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
        TextView info=text("Prévia de iluminação. A segmentação usa a ROI selecionada e refina as medidas na imagem original.",13,Color.DKGRAY);body.addView(info);
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
        for(PelletDetector.Detection d:photoResult.circles)items.add(new Granulometry.Item(d.radius*2,d.partial,d.needsReview));
        return items;
    }
    private java.util.List<Granulometry.Item> measurementItems(Granulometry.Settings prospective) {
        if(photoAllResult==null)return measurementItems();
        java.util.List<Granulometry.Item> items=new java.util.ArrayList<>();
        for(int i:ObjectCalibration.sampleIndices(photoAllResult,interestRegion,prospective.selectedObject)){PelletDetector.Detection d=photoAllResult.circles.get(i);items.add(new Granulometry.Item(d.radius*2,d.partial,d.needsReview));}
        return items;
    }
    private void refreshDistribution() {
        distribution=null;crackHistogram=null;histogram.display(null);histogram.setVisibility(View.GONE);hideColorMap(false);
        if(roiNeedsAnalysis){scaleLabel.setText(scaleSettings==null?"ROI sem escala física":"Escala preservada · aguardando segmentação");updateControls();return;}
        if(photoResult==null){scaleLabel.setText("Sem escala · toque em Escala / mm");updateControls();return;}
        if(scaleSettings==null){distribution=PhotoComposer.pixelHistogram(photoResult);showHistogram();histogram.setVisibility(View.VISIBLE);if(colorMap!=null){colorMap.display(photoResult,distribution);colorMap.visuals(visuals);colorMap.viewport(displayWindow);}applyViewMode();scaleLabel.setText("Sem escala física · histograma em px");hint.setText("Toque numa pelota para revisar ou calibrar pela medida real. Escala / mm mantém as opções manuais.");updateControls();return;}
        try {
            distribution=Granulometry.compute(measurementItems(),scaleSettings,originalWidth,originalHeight,photoResult.width,photoResult.height);
            scaleSettings=distribution.settings;
            scaleLabel.setText((scaleSettings.beltReference!=null?"Correia · ":scaleSettings.selectedObject!=null?"REF · ":scaleSettings.estimated()?"Estimativa · ":"")+HistogramView.number(scaleSettings.originalPixelsPerMm)+" px/mm · classe "+HistogramView.number(distribution.settings.binWidthMm)+" mm");
            showHistogram();histogram.setVisibility(View.VISIBLE);
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
    private void showRegionEditor(){
        if(mode!=Mode.PHOTO||photoBitmap==null||photoAllResult==null)return;
        android.app.Dialog dialog=new android.app.Dialog(this);dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(Color.rgb(16,26,35));
        root.addView(text("Área de interesse",22,Color.WHITE));
        root.addView(text("Arraste dentro para mover. Use as alças nos lados/cantos ou dois dedos para redimensionar. Pelotas que cruzam o limite ficam fora da análise.",13,Color.LTGRAY));
        RegionEditorView editor=new RegionEditorView(this,photoBitmap,interestRegion);
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
        TextView preview=text("",13,Color.rgb(82,216,190));preview.setTag("reference_scale_preview");content.addView(preview);TextView error=text("",13,Color.rgb(255,146,146));error.setTag("reference_error");error.setVisibility(View.GONE);content.addView(error);
        java.util.function.Supplier<ObjectCalibration.Measure> chosen=()->{for(int i=0;i<ids.length;i++)if(measures.getCheckedRadioButtonId()==ids[i])return ObjectCalibration.Measure.values()[i];return ObjectCalibration.Measure.MAJOR_AXIS;};
        java.util.function.Supplier<Granulometry.Settings> input=()->{
            ObjectCalibration.Reference reference=ObjectCalibration.select(selected,fullId,displayId,chosen.get(),parsePositive(real.getText().toString()),exclude.isChecked(),originalWidth,originalHeight,snapshot.width,snapshot.height);
            return Granulometry.Settings.selectedObject(reference,parsePositive(bin.getText().toString()),scaleSettings==null||scaleSettings.excludePartial,scaleSettings!=null&&scaleSettings.excludeReview);
        };
        Runnable update=()->{try{
            Granulometry.Settings cfg=input.get();ObjectCalibration.Reference ref=cfg.selectedObject;
            java.util.List<Integer> sample=ObjectCalibration.sampleIndices(snapshot,interestRegion,ref);java.util.List<Granulometry.Item> items=new java.util.ArrayList<>();for(int i:sample){PelletDetector.Detection d=snapshot.circles.get(i);items.add(new Granulometry.Item(2*d.radius,d.partial,d.needsReview));}
            Granulometry.Distribution measured=Granulometry.compute(items,cfg,originalWidth,originalHeight,snapshot.width,snapshot.height);
            preview.setText("Escala calculada: "+HistogramView.number(cfg.originalPixelsPerMm)+" px/mm na foto original.\nReferência: maior "+HistogramView.number(ref.majorMm())+" mm · menor "+HistogramView.number(ref.minorMm())+" mm · equivalente "+HistogramView.number(ref.equivalentMm())+" mm.\n"+(measured.included==0?"Nenhuma pelota incluída na distribuição.":"Histograma: média "+HistogramView.number(measured.meanMm)+" mm · n="+measured.included+" · "+measured.binCount()+" classes."));
        }catch(IllegalArgumentException e){preview.setText("Informe a medida real e a classe para conferir a escala.");}};
        measures.setOnCheckedChangeListener((g,checked)->{ObjectCalibration.Measure measure=chosen.get();referenceImage.measure(measure);dimensionLabel.setText(measure==ObjectCalibration.Measure.STANDARD_SPHERE?"Diâmetro conhecido da esfera (mm)":measure==ObjectCalibration.Measure.MAJOR_AXIS?"Eixo maior medido com paquímetro (mm)":"Eixo menor medido com paquímetro (mm)");exclude.setChecked(editing&&measure==previous.measure?previous.excludeFromStatistics:measure==ObjectCalibration.Measure.STANDARD_SPHERE);
            note.setText(measure==ObjectCalibration.Measure.STANDARD_SPHERE?"Esfera: usa o diâmetro equivalente à área da elipse. Confira se o contorno corresponde ao padrão conhecido."+(selected.aspectRatio()<.9?" A projeção está alongada; isso pode enviesar a escala.":""):"Meça a mesma dimensão destacada pela linha amarela. O diâmetro equivalente do histograma pode diferir do eixo medido.");update.run();});
        android.text.TextWatcher watcher=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){update.run();}public void afterTextChanged(android.text.Editable e){}};real.addTextChangedListener(watcher);bin.addTextChangedListener(watcher);exclude.setOnCheckedChangeListener((v,c)->update.run());
        measures.check(ids[editing?previous.measure.ordinal():0]);update.run();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(editing?"Editar referência de escala":"Calibrar pela pelota "+displayId).setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar escala",null).setOnDismissListener(d->referenceImage.detach()).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(mode!=Mode.PHOTO||photoAllResult!=snapshot||photoBitmap!=snapshotPhoto){dialog.dismiss();toast("A foto mudou. Selecione a referência novamente.");return;}
            try{Granulometry.Settings settings=input.get();ObjectCalibration.Reference reference=settings.selectedObject;
                java.util.List<Granulometry.Item> items=new java.util.ArrayList<>();for(int i:ObjectCalibration.sampleIndices(snapshot,interestRegion,reference)){PelletDetector.Detection d=snapshot.circles.get(i);items.add(new Granulometry.Item(2*d.radius,d.partial,d.needsReview));}
                Granulometry.compute(items,settings,originalWidth,originalHeight,snapshot.width,snapshot.height);commitScale(settings);dialog.dismiss();toast("Escala aplicada à foto pela referência escolhida.");
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
        android.widget.RadioGroup methods=new android.widget.RadioGroup(this);
        int[] ids={View.generateViewId(),View.generateViewId(),View.generateViewId(),View.generateViewId(),View.generateViewId()};
        String[] labels={"Informar escala em px/mm","Calcular por referência de comprimento","Estimar por média conhecida (mm)","Usar objeto selecionado na foto","Usar largura da correia confirmada"};
        for(int i=0;i<ids.length;i++){android.widget.RadioButton option=new android.widget.RadioButton(this);option.setId(ids[i]);option.setText(labels[i]);option.setTextColor(Color.WHITE);option.setTextSize(13);if(i==3)option.setEnabled(activeReference()!=null);if(i==4)option.setEnabled(scaleSettings!=null&&scaleSettings.beltReference!=null);methods.addView(option);}
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
            if((methods.getCheckedRadioButtonId()==ids[3]&&activeReference()!=null)||(methods.getCheckedRadioButtonId()==ids[4]&&scaleSettings!=null&&scaleSettings.beltReference!=null))return scaleSettings.withHistogram(bin,edges.isChecked(),review.isChecked());
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
            boolean reference=checked==ids[1],estimate=checked==ids[2],selected=checked==ids[3]||checked==ids[4];
            valueLabel.setVisibility(selected?View.GONE:View.VISIBLE);pixels.setVisibility(selected?View.GONE:View.VISIBLE);
            lengthLabel.setVisibility(reference?View.VISIBLE:View.GONE);length.setVisibility(reference?View.VISIBLE:View.GONE);
            valueLabel.setText(reference?"Comprimento da referência na foto original (px)":estimate?"Média conhecida das pelotas incluídas (mm)":"Pixels por milímetro (px/mm)");
            pixels.setHint(reference?"Ex.: 192":estimate?"Ex.: 12":"Ex.: 16 ou 16,5");
            methodNote.setText(checked==ids[4]&&scaleSettings!=null&&scaleSettings.beltReference!=null?"Largura confirmada: "+HistogramView.number(scaleSettings.beltReference.realWidthMm)+" mm. Use o botão da correia para editar as bordas.":selected&&activeReference()!=null?"REF: "+activeReference().summary()+". Para trocar, toque no objeto na foto.":reference?"Ex.: 192 px / 12 mm = 16 px/mm. Meça uma referência no mesmo plano das pelotas.":estimate?"Use somente se a média desta amostra foi obtida externamente. A média informada define a escala; não será uma medição independente. A faixa não será forçada a 8–16 mm.":"px/mm = comprimento em pixels / comprimento real em mm. Não informe o diâmetro médio neste campo.");
            boolean match=scaleSettings!=null&&(reference?"reference_length".equals(scaleSettings.calibrationSource):estimate?scaleSettings.estimated():"pixels_per_mm".equals(scaleSettings.calibrationSource));
            pixels.setText(match?Double.toString(reference?scaleSettings.referencePixels:estimate?scaleSettings.knownMeanMm:scaleSettings.originalPixelsPerMm):"");
            length.setText(match&&reference?Double.toString(scaleSettings.referenceMm):"");updatePreview.run();
        });
        methods.check(ids[scaleSettings==null?0:scaleSettings.beltReference!=null?4:scaleSettings.selectedObject!=null?3:scaleSettings.estimated()?2:"reference_length".equals(scaleSettings.calibrationSource)?1:0]);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Escala e granulometria").setView(scroll)
            .setNegativeButton("Cancelar",null).setNeutralButton("Remover escala",(d,w)->commitScale(null))
            .setPositiveButton("Aplicar",null).create();
        belt.setOnClickListener(v->{dialog.dismiss();showBeltCalibration();});
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
        content.addView(text("Diâmetro mínimo esperado (mm; 0 desativa)",13,Color.WHITE));EditText minimum=new EditText(this);minimum.setTextColor(Color.WHITE);minimum.setSingleLine();minimum.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);minimum.setText(Double.toString(minimumExpectedMm));minimum.setTag("minimum_expected_mm");content.addView(minimum);
        content.addView(text("O mínimo só pontua com calibração física independente. Não apaga detecções nem recorta o histograma. Rosin–Rammler não é normal e não é imposta à amostra.",12,Color.LTGRAY));
        content.addView(text("Cena",14,Color.WHITE));android.widget.RadioGroup scenes=new android.widget.RadioGroup(this);int[] sceneIds={View.generateViewId(),View.generateViewId()};String[] sceneLabels={"Bancada · amostra espalhada, pelotas grandes na foto","Correia · pelotas pequenas e numerosas (até 5000 por foto)"};for(int i=0;i<2;i++){android.widget.RadioButton radio=new android.widget.RadioButton(this);radio.setId(sceneIds[i]);radio.setText(sceneLabels[i]);radio.setTextColor(Color.WHITE);radio.setTextSize(12);scenes.addView(radio);}scenes.check(sceneIds[beltScene?1:0]);content.addView(scenes);
        content.addView(text("Na correia, a análise usa até 1600 px (sem o modo detalhado), a triagem de trincas tende a ficar inconclusiva (pelotas pequenas demais na foto) e pelotas encobertas são medidas só pela borda visível.",12,Color.LTGRAY));
        content.addView(text("Ajuste manual de tamanho aparente",14,Color.WHITE));android.widget.RadioGroup sizes=new android.widget.RadioGroup(this);
        int[] optionIds={View.generateViewId(),View.generateViewId(),View.generateViewId(),View.generateViewId()};
        String[] labels={"Pequenas · diâmetro 2,6–7,6% da largura","Médias · diâmetro 4,2–12% da largura","Grandes · diâmetro 8–21% da largura","Automático · tamanhos variados e iluminação"};
        String[] beltLabels={"Finas · diâmetro 0,8–1,6% da largura","Médias · diâmetro 1,2–2,4% da largura","Grossas · diâmetro 1,8–3,2% da largura","Automático · diâmetro 0,8–3,2% da largura"};
        for(int i=0;i<labels.length;i++){android.widget.RadioButton radio=new android.widget.RadioButton(this);radio.setId(optionIds[i]);radio.setText((beltScene?beltLabels:labels)[i]);radio.setTextColor(Color.WHITE);radio.setTextSize(12);sizes.addView(radio);}sizes.check(optionIds[profile]);content.addView(sizes);
        content.addView(text("Método de localização",14,Color.WHITE));android.widget.RadioGroup methods=new android.widget.RadioGroup(this);int[] methodIds={View.generateViewId(),View.generateViewId(),View.generateViewId()};String[] methodLabels={"Bordas · gradientes com CLAHE","LoG · segmentação alternativa",NeuralPelletDetector.available()?"Rede neural · StarDist (modelo de demonstração)":"Rede neural · indisponível"};for(int i=0;i<3;i++){android.widget.RadioButton radio=new android.widget.RadioButton(this);radio.setId(methodIds[i]);radio.setText(methodLabels[i]);radio.setTextColor(Color.WHITE);radio.setTextSize(12);radio.setEnabled(i!=DetectionTuner.NEURAL||NeuralPelletDetector.available());methods.addView(radio);}methods.check(methodIds[method()]);content.addView(methods);
        TextView neuralNote=text("",12,Color.LTGRAY);Runnable neuralText=()->{boolean belt=scenes.getCheckedRadioButtonId()==sceneIds[1];neuralNote.setText(NeuralPelletDetector.status(belt)+(belt?". Correia: rede treinada com pseudo-rótulos de consenso (sem revisão humana).":". Treinada sem fotos da usina.")+" A rede só propõe objetos; as elipses continuam medidas nas bordas da foto. Compare com os outros métodos antes de usar.");};neuralText.run();content.addView(neuralNote);
        scenes.setOnCheckedChangeListener((group,id)->{String[] l=id==sceneIds[1]?beltLabels:labels;for(int i=0;i<optionIds.length;i++)((android.widget.RadioButton)sizes.findViewById(optionIds[i])).setText(l[i]);neuralText.run();});
        TextView sensitivityLabel=text("Sensibilidade: "+sensitivity+"%",14,Color.WHITE);content.addView(sensitivityLabel);SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(sensitivity);content.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){sensitivityLabel.setText("Sensibilidade: "+p+"%");}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        content.addView(text("Aplicar usa o ajuste manual nesta foto. A busca automática fica para a próxima foto. Maior sensibilidade pode criar falsas marcações. Alterar contornos pode exigir nova calibração e descarta revisões de trincas.",12,Color.LTGRAY));
        TextView error=text("",13,Color.rgb(255,146,146));error.setTag("adjustment_error");content.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ajustes de detecção").setView(scroll).setNegativeButton("Cancelar",null).setNeutralButton(mode==Mode.PHOTO?"Ver sugestão":"Como funciona",null).setPositiveButton("Aplicar manual",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{minimumExpectedMm=minimumInput(minimum);getPreferences(MODE_PRIVATE).edit().putString("expected_minimum_mm",Double.toString(minimumExpectedMm)).apply();refreshSuggestionLabel();dialog.dismiss();if(mode==Mode.PHOTO)showSuggestions();else new AlertDialog.Builder(this).setTitle("Sugestão na foto").setMessage("Ao abrir ou fotografar, são comparados quatro perfis e três sensibilidades nos métodos de bordas e LoG; com a rede neural escolhida como método, ela também entra com os quatro perfis. LoG também é testado quando não era o ajuste atual. A melhor pontuação inicia a análise. Toque em Sugestão para comparar ou manter um ajuste manual. Sem escala física, a comparação é em pixels; depois de calibrar, o limite em mm pode mudar a recomendação, sem trocar os contornos automaticamente.").setPositiveButton("Entendi",null).show();}catch(IllegalArgumentException e){error.setText(e.getMessage());}});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                minimumExpectedMm=minimumInput(minimum);autoSuggest=auto.isChecked();for(int i=0;i<optionIds.length;i++)if(sizes.getCheckedRadioButtonId()==optionIds[i])profile=i;for(int i=0;i<methodIds.length;i++)if(methods.getCheckedRadioButtonId()==methodIds[i])detectionMethod=i;beltScene=scenes.getCheckedRadioButtonId()==sceneIds[1];sensitivity=slider.getProgress();applyParameters();epoch++;
                getPreferences(MODE_PRIVATE).edit().putInt("adaptive_profile",profile).putInt("sensitivity",sensitivity).putBoolean("legacy_detection",detectionMethod==DetectionTuner.LOG).putInt("detection_method",detectionMethod).putBoolean("belt_scene",beltScene).putBoolean("auto_suggest",autoSuggest).putString("expected_minimum_mm",Double.toString(minimumExpectedMm)).apply();dialog.dismiss();
                if(mode==Mode.PHOTO&&photoFile!=null){File file=photoFile;String mime=photoMime;PelletDetector.Parameters cfg=parameters;setBusy(Mode.PROCESSING,"Reanalisando com ajuste manual…");worker.execute(()->processPhoto(file,mime,cfg));}
            }catch(IllegalArgumentException e){error.setText(e.getMessage());}});
        });dialog.show();
    }
    private void restoreParameters() {
        autoSuggest=getPreferences(MODE_PRIVATE).getBoolean("auto_suggest",true);
        try{minimumExpectedMm=Double.parseDouble(getPreferences(MODE_PRIVATE).getString("expected_minimum_mm","5"));if(!Double.isFinite(minimumExpectedMm)||minimumExpectedMm<0||minimumExpectedMm>1000)minimumExpectedMm=5;}catch(NumberFormatException e){minimumExpectedMm=5;}
        detectionMethod=getPreferences(MODE_PRIVATE).getInt("detection_method",getPreferences(MODE_PRIVATE).getBoolean("legacy_detection",false)?DetectionTuner.LOG:DetectionTuner.GRADIENT);if(detectionMethod<0||detectionMethod>2)detectionMethod=DetectionTuner.GRADIENT;beltScene=getPreferences(MODE_PRIVATE).getBoolean("belt_scene",false);
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
    @Override protected void onResume(){super.onResume();if(nativeReady&&camera==null&&preview!=null&&ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)preview.post(this::bindCamera);}
    @Override protected void onDestroy() {
        epoch++;if(tuningCancel!=null)tuningCancel.set(true);closeTuneProgress();handler.removeCallbacksAndMessages(null);
        if(analysis!=null)analysis.clearAnalyzer();if(provider!=null)provider.unbindAll();
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
        if(mode!=Mode.PHOTO||photoResult==null||distribution==null){toast("Analise uma foto para alternar a visualização. Toque longo: opções do mapa.");return;}
        setViewMode(viewMode==1?2:viewMode==2?0:1);
        toast(viewMode==2?"Mapa granulométrico · toque longo no botão para opacidade e escala":viewMode==0?"Visualização original (sem marcações)":"Visualização de segmentação");
    }
    private void setViewMode(int value){viewMode=value;colorMapVisible=value==2;applyViewMode();}
    private void applyViewMode(){
        if(mapButton!=null)mapButton.setText(viewMode==2?"Mapa":viewMode==0?"Orig.":"Seg.");
        if(colorMap==null||overlay==null)return;
        boolean photo=mode==Mode.PHOTO&&photoResult!=null&&distribution!=null;
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
        }else crackNetwork=null; // não manter a rede carregada sem uso
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
        crackChoice=CrackClassifierChoice.parse(p.getString("crack_classifier","RANDOM_FOREST"));
    }
    private void saveVisuals(){
        getPreferences(MODE_PRIVATE).edit().putFloat("map_opacity_v2",(float)visuals.mapOpacity).putBoolean("map_labels",visuals.mapLabels).putBoolean("map_fixed",visuals.mapFixed)
            .putFloat("map_fixed_min",(float)visuals.mapFixedMin).putFloat("map_fixed_max",(float)visuals.mapFixedMax).putBoolean("hist_all",visuals.showAll).putBoolean("hist_cracked",visuals.showCracked)
            .putBoolean("hist_values",visuals.showValues).putString("hist_normalization",visuals.normalization.name()).putString("crack_classifier",crackChoice.name()).apply();
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
        String[] segLabels={"Clássica · bordas (gradientes com CLAHE)","Clássica · LoG",NeuralPelletDetector.available(beltScene)?"Neural · StarDist (experimental, demonstração)":"Neural · StarDist — indisponível","Neural · YOLO11-seg — indisponível","Híbrida — indisponível"};
        int[] segIds=new int[segLabels.length];
        for(int i=0;i<segLabels.length;i++){android.widget.RadioButton r=new android.widget.RadioButton(this);segIds[i]=View.generateViewId();r.setId(segIds[i]);r.setText(segLabels[i]);r.setTextColor(Color.WHITE);r.setTextSize(12);r.setEnabled(i<2||(i==2&&NeuralPelletDetector.available(beltScene)));seg.addView(r);}
        seg.check(segIds[method()]);content.addView(seg);
        content.addView(text("YOLO11-seg: sem máscaras de instância com rótulo humano para treinar e validar; código e pesos da Ultralytics são AGPL-3.0 (exige revisão de licença antes de distribuir). Híbrida: não há regra de fusão validada. Segmentação mais bonita não prova diâmetro mais exato.",11,Color.LTGRAY));
        content.addView(text("Classificação de trincas",15,Color.WHITE));android.widget.RadioGroup cls=new android.widget.RadioGroup(this);
        CrackClassifierChoice[] choices=CrackClassifierChoice.values();int[] clsIds=new int[choices.length];
        boolean assetPresent;try{getAssets().open(MobileNetCrackModel.MODEL).close();assetPresent=true;}catch(Exception e){assetPresent=false;}
        for(int i=0;i<choices.length;i++){android.widget.RadioButton r=new android.widget.RadioButton(this);clsIds[i]=View.generateViewId();r.setId(clsIds[i]);r.setText(choices[i].label+(choices[i].needsNetwork()&&!assetPresent?" — indisponível":""));r.setTextColor(Color.WHITE);r.setTextSize(12);r.setEnabled(!choices[i].needsNetwork()||assetPresent);cls.addView(r);if(choices[i]==crackChoice)cls.check(clsIds[i]);}
        content.addView(cls);
        content.addView(text("O Random Forest continua o padrão. A MobileNetV3 foi treinada com poucas trincas reais (rótulos provisórios) e testada em uma única foto: use para comparar, não para decidir. Os recortes vêm dos pixels originais, nunca da super-resolução. O escore não é probabilidade calibrada. Localização de trincas por U-Net: indisponível (não há máscaras reais de trinca).",11,Color.LTGRAY));
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

