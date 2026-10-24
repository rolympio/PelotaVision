package br.com.pelotavision;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class ResultSaver {
    private ResultSaver() {}
    static final class SavedResult {
        final String base;final Uri image;
        SavedResult(String base,Uri image){this.base=base;this.image=image;}
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,
                       Granulometry.Distribution distribution) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,null,distribution,null,0);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,null,0);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,null,null);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied) throws Exception {
        return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,suggestions,applied,false);
    }
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,
                       PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,
                       Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied,boolean cropRoi) throws Exception {return save(context,photo,original,originalMime,result,parameters,cracks,distribution,region,excludedByRegion,suggestions,applied,cropRoi,null);}
    static SavedResult save(Context context,Bitmap photo,File original,String originalMime,PelletDetector.Result result,PelletDetector.Parameters parameters,CrackAnalysis cracks,Granulometry.Distribution distribution,InterestRegion region,int excludedByRegion,DetectionTuner.Report suggestions,DetectionTuner.Option applied,boolean cropRoi,DetailedAnalysis.Report detailed) throws Exception {
        if(distribution==null)distribution=PhotoComposer.pixelHistogram(result);
        boolean physical=!distribution.settings.pixelsOnly();
        ContentResolver resolver=context.getContentResolver();List<Uri> pending=new ArrayList<>();
        String base="PelotaVision_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.ROOT).format(new Date());
        Bitmap annotated=null;
        try {
            String extension="image/png".equals(originalMime)?"png":"image/webp".equals(originalMime)?"webp":"jpg";
            Uri source=create(resolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    base+"_original."+extension,originalMime,"Pictures/PelotaVision");pending.add(source);
            try(InputStream input=new FileInputStream(original);OutputStream output=stream(resolver,source)) {
                byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
            }
            annotated=PhotoComposer.compose(photo,result,cracks,distribution,region,excludedByRegion,cropRoi);
            Uri image=create(resolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    base+"_resultado.png","image/png","Pictures/PelotaVision");pending.add(image);
            try(OutputStream output=stream(resolver,image)) {if(!annotated.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Falha ao gravar a imagem.");}
            JSONObject json=new JSONObject();json.put("app","PelotaVision 0.11.0");json.put("created_at_epoch_ms",System.currentTimeMillis());
            json.put("region_of_interest",regionJson(region,excludedByRegion));json.put("detection_suggestion",suggestionJson(suggestions,applied));
            JSONObject processing=processingScopeJson(new AnalysisWindow(result.width,result.height,result.method.contains("active_roi_crop_v1")?region:null));
            if(detailed!=null){processing.put("input_width",detailed.geometry.width);processing.put("input_height",detailed.geometry.height);processing.put("crop_resizes_input",detailed.geometry.sample>1);processing.put("coordinate_mapping","detailed_analysis.local_to_preview; detections retain preview coordinates");processing.put("outside_roi_original_pixels_analyzed",region==null);}
            json.put("processing_scope",processing);json.put("detailed_analysis",detailJson(detailed));
            json.put("method",result.method);json.put("shape_analysis",shapeJson(result));json.put("count",result.circles.size());
            json.put("image_width",annotated.getWidth());json.put("image_height",annotated.getHeight());
            json.put("analysis_photo_width",result.width);json.put("analysis_photo_height",result.height);
            ReportViewport viewport=new ReportViewport(photo.getWidth(),photo.getHeight(),region,cropRoi);
            PhotoComposer.Layout layout=new PhotoComposer.Layout(viewport.width,viewport.height);
            json.put("report_viewport",viewportJson(viewport));json.put("photo_panel_width",viewport.width);json.put("photo_panel_height",viewport.height);
            json.put("coordinates","detections in full upright analyzed photo pixels; composite x=(x-crop_left)*render_scale; composite y=annotated_panel_top+(y-crop_top)*render_scale");
            json.put("original_panel_top",layout.originalTop);json.put("annotated_panel_top",layout.annotatedTop);json.put("histogram_top",layout.histogramTop);
            json.put("cracks",crackJson(cracks));
            json.put("original_file",base+"_original."+extension);json.put("annotated_file",base+"_resultado.png");
            json.put("analysis_ms",result.durationMs);json.put("reviewed",false);json.put("candidate_limit",result.candidateLimit);
            json.put("physical_scale",JSONObject.NULL);json.put("histogram",JSONObject.NULL);
            json.put("note","Experimental 2D ellipse fit: area=pi*a*b, equivalent diameter=2*sqrt(a*b). Unfitted objects retain seed estimates and require review. Aspect ratio b/a is apparent 2D shape, not 3D sphericity. Roundness on fitted objects uses ellipse perimeter, not measured contour perimeter. Distribution by number, not validated sieve granulometry.");
            if(distribution!=null) {
                JSONObject scale=new JSONObject();scale.put("original_pixels_per_mm",distribution.settings.originalPixelsPerMm);
                scale.put("analyzed_pixels_per_mm",distribution.analyzedPixelsPerMm);
                scale.put("original_upright_width",distribution.originalWidth);scale.put("original_upright_height",distribution.originalHeight);
                scale.put("source",distribution.settings.calibrationSource);
                scale.put("estimated",distribution.settings.estimated());
                scale.put("belt_reference",beltReferenceJson(distribution.settings.beltReference));
                scale.put("selected_object",objectReferenceJson(distribution.settings.selectedObject,region));
                scale.put("assumed_mean_mm",Double.isFinite(distribution.settings.knownMeanMm)?distribution.settings.knownMeanMm:JSONObject.NULL);
                scale.put("reference_pixels",Double.isFinite(distribution.settings.referencePixels)?distribution.settings.referencePixels:JSONObject.NULL);
                scale.put("reference_mm",Double.isFinite(distribution.settings.referenceMm)?distribution.settings.referenceMm:JSONObject.NULL);if(physical)json.put("physical_scale",scale);
                String suffix=physical?"_mm":"_px";
                JSONObject histogram=new JSONObject();histogram.put("unit",physical?"mm":"px");histogram.put("basis","number");histogram.put("diameter_method",result.method.contains("ellipse_boundary")?"ellipse_equivalent_area_diameter=2*sqrt(semiMajor*semiMinor); unfitted_seed_area_fallback":result.method.startsWith("gradient_circle")?"fitted_circle_diameter; area=pi*r^2":"2*sqrt(projected_area/pi)");
                histogram.put("mean"+suffix,Double.isFinite(distribution.meanMm)?distribution.meanMm:JSONObject.NULL);
                histogram.put("min"+suffix,Double.isFinite(distribution.minMm)?distribution.minMm:JSONObject.NULL);
                histogram.put("max"+suffix,Double.isFinite(distribution.maxMm)?distribution.maxMm:JSONObject.NULL);
                histogram.put("bin_width"+suffix,distribution.settings.binWidthMm);histogram.put("included",distribution.included);histogram.put("excluded",distribution.excluded);
                histogram.put("requested_bin_width"+suffix,distribution.requestedBinWidthMm);
                histogram.put("bin_width_auto_adjusted",distribution.settings.binWidthMm!=distribution.requestedBinWidthMm);
                histogram.put("exclude_partial_edge",distribution.settings.excludePartial);histogram.put("exclude_needs_review",distribution.settings.excludeReview);
                histogram.put("class_convention","lower inclusive, upper exclusive except inclusive final upper edge; displayed classes span observed diameters; boundaries are multiples of bin width");
                histogram.put("percentile_method","linear interpolation at (n-1)*q; distribution by number");
                histogram.put("d10"+suffix,Double.isFinite(distribution.d10)?distribution.d10:JSONObject.NULL);
                histogram.put("d50"+suffix,Double.isFinite(distribution.d50)?distribution.d50:JSONObject.NULL);
                histogram.put("d90"+suffix,Double.isFinite(distribution.d90)?distribution.d90:JSONObject.NULL);
                JSONArray bins=new JSONArray();String grainCsv=ReportTables.histogramCsv(distribution);
                for(int i=0;i<distribution.binCount();i++) {
                    JSONObject bin=new JSONObject();bin.put("lower"+suffix,distribution.lower(i));bin.put("upper"+suffix,distribution.upper(i));
                    bin.put("count",distribution.count(i));bin.put("percent_by_number",distribution.percent(i));bins.put(bin);
                }
                histogram.put("bins",bins);json.put("histogram",histogram);
                Uri grainUri=create(resolver,MediaStore.Downloads.EXTERNAL_CONTENT_URI,base+"_granulometria.csv","text/csv","Download/PelotaVision");pending.add(grainUri);
                try(OutputStream output=stream(resolver,grainUri)){output.write(grainCsv.getBytes(StandardCharsets.UTF_8));}
            }
            JSONObject cfg=new JSONObject();cfg.put("min_radius_fraction",parameters.minRadiusFraction);cfg.put("max_radius_fraction",parameters.maxRadiusFraction);cfg.put("log_threshold",parameters.threshold);json.put("parameters",cfg);
            JSONArray detections=new JSONArray();String csv=ReportTables.pelletCsv(result,cracks,distribution);
            int id=0;for(PelletDetector.Detection d:result.circles) {
                id++;JSONObject item=new JSONObject();item.put("id",id);item.put("x_px",d.x);item.put("y_px",d.y);item.put("radius_px",d.radius);
                item.put("equivalent_diameter_px",d.radius*2);item.put("area_px2",d.area);item.put("roundness",d.roundness);item.put("partial_edge",d.partial);item.put("needs_review",d.needsReview);detections.put(item);
                double diameter=!physical?Double.NaN:distribution.diameterMm(2*d.radius);
                double area=!physical?Double.NaN:d.area/(distribution.analyzedPixelsPerMm*distribution.analyzedPixelsPerMm);
                CrackAnalysis.Entry crack=cracks==null?null:cracks.entries.get(id-1);
                item.put("crack_status",crack==null?"not_analyzed":crack.status.name());item.put("crack_manually_reviewed",crack!=null&&crack.manuallyReviewed);
                item.put("ellipse_major_axis_px",d.ellipseFitted?2*d.semiMajor:JSONObject.NULL);item.put("ellipse_minor_axis_px",d.ellipseFitted?2*d.semiMinor:JSONObject.NULL);item.put("ellipse_angle_deg",d.ellipseFitted?d.angleDeg:JSONObject.NULL);item.put("apparent_sphericity_2d",finite(d.aspectRatio()));item.put("ellipse_fitted",d.ellipseFitted);item.put("included_in_shape_mean",ShapeStatistics.includes(d));
                item.put("ellipse_fit_support",finite(d.fitSupport));item.put("ellipse_fit_rms",finite(d.fitRms));item.put("area_method",d.ellipseFitted?"ellipse_pi_a_b":"seed_area_fallback");item.put("crack_crop_x_px",d.crackX);item.put("crack_crop_y_px",d.crackY);item.put("crack_crop_radius_px",d.crackRadius);
                ObjectCalibration.Axes originalAxes=physical&&d.ellipseFitted?ObjectCalibration.originalAxes(d,distribution.originalWidth,distribution.originalHeight,result.width,result.height):null;
                item.put("ellipse_major_axis_mm",originalAxes!=null?originalAxes.major/distribution.settings.originalPixelsPerMm:JSONObject.NULL);item.put("ellipse_minor_axis_mm",originalAxes!=null?originalAxes.minor/distribution.settings.originalPixelsPerMm:JSONObject.NULL);
                item.put("crack_score",crack!=null&&Double.isFinite(crack.modelScore)?crack.modelScore:JSONObject.NULL);item.put("crack_method",crack==null?"not_analyzed":crack.method);
                boolean included=distribution!=null&&distribution.includes(new Granulometry.Item(2*d.radius,d.partial,d.needsReview));
                item.put("equivalent_diameter_mm",Double.isFinite(diameter)?diameter:JSONObject.NULL);
                item.put("area_mm2",Double.isFinite(area)?area:JSONObject.NULL);item.put("included_in_histogram",included);
            }
            json.put("detections",detections);
            Uri jsonUri=create(resolver,MediaStore.Downloads.EXTERNAL_CONTENT_URI,base+".json","application/json","Download/PelotaVision");pending.add(jsonUri);
            try(OutputStream output=stream(resolver,jsonUri)){output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));}
            Uri csvUri=create(resolver,MediaStore.Downloads.EXTERNAL_CONTENT_URI,base+".csv","text/csv","Download/PelotaVision");pending.add(csvUri);
            try(OutputStream output=stream(resolver,csvUri)){output.write(csv.getBytes(StandardCharsets.UTF_8));}
            ContentValues visible=new ContentValues();visible.put(MediaStore.MediaColumns.IS_PENDING,0);
            for(Uri uri:pending)if(resolver.update(uri,visible,null,null)!=1)throw new IOException("Não foi possível concluir a gravação.");
            return new SavedResult(base,image);
        } catch(Exception|OutOfMemoryError e) {for(Uri uri:pending)try{resolver.delete(uri,null,null);}catch(Exception ignored){}throw e;}
        finally {if(annotated!=null)annotated.recycle();}
    }
    static Object detailJson(DetailedAnalysis.Report d)throws Exception {
        if(d==null)return JSONObject.NULL;DetailGeometry m=d.geometry;JSONObject j=new JSONObject();j.put("method","original_roi_tiles_v1");j.put("source_pixels","original file region decode before preview reduction");j.put("input_width",m.width);j.put("input_height",m.height);j.put("decode_sample_size",m.sample);j.put("native_resolution_used",m.sample==1);j.put("tile_side",d.superResolution?TilePlan.SR_SIDE:TilePlan.NORMAL_SIDE);j.put("tile_count",d.tiles);j.put("overlap_fraction",.35);j.put("ownership_rule","midpoint of neighboring tile centers; ellipse NMS at 0.28*(r1+r2), radius ratio 0.65..1/0.65");j.put("profile_radius_basis","current tile width, in source pixels; SR proposals are mapped back before original-pixel refinement");JSONArray scores=new JSONArray();for(double score:d.tileScores)scores.put(score);j.put("selected_tile_heuristic_scores",scores);j.put("baseline_count",d.baselineCount);j.put("selected_count",d.selectedCount);j.put("configuration_search_scope","representative original-resolution tile; same pixel/radius basis as detail tiles");j.put("configuration_search",suggestionJson(d.settingSearch,d.settingSearch==null||d.settingSearch.find(d.optionId)==null?null:d.settingSearch.find(d.optionId).option));j.put("setting_option_id",d.optionId);j.put("duration_ms",d.durationMs);j.put("treatments_compared",d.compared);JSONArray counts=new JSONArray();for(int n:d.treatments)counts.put(n);j.put("selected_tile_treatments_original_clahe_bilateral_sr",counts);j.put("super_resolution_tested",d.superResolution);j.put("super_resolution_model_sha256",d.superResolution?FsrcnnUpscaler.MODEL_SHA256:JSONObject.NULL);j.put("super_resolution_model",d.superResolution?"FSRCNN x2; T91/General100 pretrained; not pellet trained":JSONObject.NULL);j.put("ellipse_refinement_pixels","original grayscale");j.put("crack_inference_pixels","original grayscale");j.put("additional_sr_proposals_require_review",true);j.put("improved_accuracy_validated",false);j.put("partial_processing_saved",false);JSONObject a=new JSONObject();a.put("scale_x",m.sx);a.put("scale_y",m.sy);a.put("translate_x",m.tx);a.put("translate_y",m.ty);j.put("local_to_preview",a);return j;
    }
    static JSONObject processingScopeJson(AnalysisWindow w)throws Exception {
        JSONObject j=new JSONObject();j.put("roi_input",w.region!=null);j.put("full_upright_preview_width",w.fullWidth);j.put("full_upright_preview_height",w.fullHeight);j.put("input_width",w.width);j.put("input_height",w.height);j.put("local_to_full_x",w.left);j.put("local_to_full_y",w.top);j.put("outside_roi_original_pixels_analyzed",w.region==null);j.put("crop_resizes_input",false);j.put("changes_physical_scale",false);j.put("circular_outside_fill",w.region!=null&&w.region.shape==InterestRegion.Shape.CIRCLE?"median of selected pixels only":JSONObject.NULL);return j;
    }
    static JSONObject viewportJson(ReportViewport v)throws Exception {
        JSONObject j=new JSONObject();j.put("roi_cropped",v.cropped);j.put("circular_mask",v.circular);j.put("crop_left",v.left);j.put("crop_top",v.top);j.put("crop_right",v.right);j.put("crop_bottom",v.bottom);j.put("render_scale",v.scale);j.put("render_width",v.width);j.put("render_height",v.height);j.put("physical_scale_changed_by_zoom",false);return j;
    }
    static Object beltReferenceJson(BeltCalibration.Reference ref)throws Exception {
        if(ref==null)return JSONObject.NULL;JSONObject j=new JSONObject();j.put("type","belt_width");j.put("user_confirmed",true);j.put("automatically_suggested_edges",ref.edges.automatic);j.put("real_width_mm",ref.realWidthMm);j.put("original_width_px",ref.originalWidthPixels);j.put("original_pixels_per_mm",ref.originalPixelsPerMm);j.put("relative_width_variation",ref.widthVariation);j.put("created_at_epoch_ms",ref.createdAt);j.put("perspective_rectified",false);j.put("analyzed_width",ref.width);j.put("analyzed_height",ref.height);j.put("original_width",ref.originalWidth);j.put("original_height",ref.originalHeight);JSONArray edges=new JSONArray();for(int i=0;i<4;i++){JSONObject p=new JSONObject();p.put("x_px",ref.edges.x(i));p.put("y_px",ref.edges.y(i));edges.put(p);}j.put("edge_endpoints_analyzed_px",edges);return j;
    }
    private static Object finite(double v){return Double.isFinite(v)?v:JSONObject.NULL;}
    private static String number(double v){return Double.isFinite(v)?String.format(Locale.ROOT,"%.6f",v):"";}
    static Object suggestionScopeJson(DetectionTuner.Report report)throws Exception {
        if(report.context.window==null)return JSONObject.NULL;JSONObject j=processingScopeJson(report.context.window);
        if(report.context.mappedInput()){PelletDetector.Result input=null;for(DetectionTuner.Trial t:report.trials)if(t.result!=null){input=t.result;break;}double[] a=report.context.inputMapping();j.put("type","original_resolution_representative_tile");j.put("roi_input",report.context.hasInputRoi());j.put("input_width",input==null?JSONObject.NULL:input.width);j.put("input_height",input==null?JSONObject.NULL:input.height);j.put("scale_x_to_preview",a[0]);j.put("scale_y_to_preview",a[1]);j.put("local_to_full_x",a[2]);j.put("local_to_full_y",a[3]);j.put("outside_roi_original_pixels_analyzed",!report.context.hasInputRoi());j.put("analyzed_pixels_per_mm_at_search",input!=null&&report.context.physical()?report.context.pixelsPerMm(input):JSONObject.NULL);}return j;
    }
    static Object suggestionJson(DetectionTuner.Report report,DetectionTuner.Option applied)throws Exception {
        if(report==null)return JSONObject.NULL;JSONObject json=new JSONObject();json.put("method",DetectionTuner.METHOD);json.put("basis","heuristic comparison, not probability or validated detection accuracy");json.put("evaluated",report.trials.size());json.put("planned",report.planned);json.put("duration_ms",report.durationMs);json.put("cancelled",report.cancelled);json.put("time_limited",report.timeLimited);json.put("weak_evidence",report.weak());json.put("applied_option_id",applied==null?JSONObject.NULL:applied.id);json.put("recommended_option_id",report.best()==null?JSONObject.NULL:report.best().option.id);json.put("minimum_expected_mm",report.context.expectedMinimumMm);json.put("minimum_rule_active",report.context.minimumActive());json.put("independent_physical_scale_available",report.context.physical());json.put("minimum_rule_filters_detections",false);json.put("diameters_modified_to_fit_distribution",false);json.put("rr_model","two-parameter Weibull MLE; number-weighted equivalent-area diameters; minimum n=12; fitted KS is descriptive, not a calibrated p-value");json.put("weights","with image evidence: 45 union foreground coverage +25 valid ellipse foreground coverage +15 area-weighted fit quality +10 agreement +5 foreground purity +1 weak RR bonus -20 overlapping fraction -35 below minimum fraction -15 candidate limit; bounded 0..100. Without evidence: geometry-only v1 fallback");
        json.put("input_scope",suggestionScopeJson(report));json.put("foreground_evidence_available",report.evidence!=null&&report.evidence.available);if(report.evidence!=null){json.put("evidence_width",report.evidence.width);json.put("evidence_height",report.evidence.height);json.put("bright_foreground",report.evidence.brightForeground);json.put("foreground_threshold",report.evidence.threshold);json.put("foreground_polarity_contrast",report.evidence.polarityContrast);json.put("illumination_corrected",report.evidence.illuminationCorrected);json.put("background_plane_span",report.evidence.backgroundPlaneSpan);}

        JSONArray trials=new JSONArray();for(DetectionTuner.Trial trial:report.ordered()){JSONObject item=new JSONObject();item.put("option_id",trial.option.id);item.put("profile",trial.option.profile);item.put("sensitivity",trial.option.sensitivity);item.put("legacy_log",trial.option.legacy);item.put("min_radius_fraction",trial.option.parameters.minRadiusFraction);item.put("max_radius_fraction",trial.option.parameters.maxRadiusFraction);item.put("threshold",trial.option.parameters.threshold);item.put("score",trial.score);item.put("agreement",trial.stability);item.put("image_coverage_available",trial.coverage!=null&&trial.coverage.available);if(trial.coverage!=null){item.put("foreground_covered_fraction",trial.coverage.covered);item.put("valid_foreground_covered_fraction",trial.coverage.validated);item.put("foreground_purity",trial.coverage.purity);item.put("area_weighted_fit_quality",trial.coverage.weightedFitQuality);item.put("foreground_pixels_in_scoring_region",trial.coverage.foregroundPixels);}item.put("error",trial.error==null?JSONObject.NULL:trial.error);
            DetectionAssessment.Metrics m=trial.metrics;if(m!=null){item.put("total_in_scoring_sample",m.total);item.put("valid",m.valid);item.put("partial",m.partial);item.put("review",m.review);item.put("overlapping",m.overlapping);item.put("below_minimum",m.belowMinimum);item.put("fit_quality",m.fitQuality);item.put("weibull_n",m.weibull.n);item.put("weibull_shape",finite(m.weibull.shape));item.put("weibull_scale",finite(m.weibull.scale));item.put("weibull_ks",finite(m.weibull.ks));item.put("weibull_unit",report.context.physical()?"mm":"px");}trials.put(item);
        }json.put("trials",trials);json.put("note","Same active input crop for all trials; illumination correction, segmentation and agreement use this scope. Changing ROI invalidates the search cache. Pixel coverage uses union masks in the ROI and is not count recall; overlapping markers never multiply covered area. Excluded reference footprint is ignored for scoring only, never relinked to another detection. Re-ranking after calibration never switches contours automatically.");return json;
    }
    static Object objectReferenceJson(ObjectCalibration.Reference ref,InterestRegion region)throws Exception {
        if(ref==null)return JSONObject.NULL;
        JSONObject json=new JSONObject();json.put("reference_id",ref.id);json.put("selected_by_user",true);json.put("selected_at_epoch_ms",ref.selectedAt);json.put("full_image_detection_id",ref.fullImageId);json.put("display_id_at_selection",ref.displayIdAtSelection);json.put("id_note","id at user selection time before filtering; historical reference snapshot, not an index into a later ROI analysis; displayed ids may be renumbered");json.put("measure",ref.measure.name());json.put("real_dimension_mm",ref.realMm);json.put("original_reference_length_px",ref.originalReferencePixels);json.put("original_pixels_per_mm",ref.originalPixelsPerMm);json.put("original_upright_width",ref.originalWidth);json.put("original_upright_height",ref.originalHeight);json.put("analyzed_width",ref.width);json.put("analyzed_height",ref.height);json.put("exclude_from_statistics",ref.excludeFromStatistics);boolean inside=region==null||region.includes(ref.geometry);json.put("reference_inside_roi",inside);json.put("reference_excluded_inside_roi",inside&&ref.excludeFromStatistics?1:0);json.put("length_basis","ellipse axes mapped to original upright image; sphere uses equivalent-area diameter");
        PelletDetector.Detection d=ref.geometry;JSONObject analyzed=new JSONObject();analyzed.put("x_px",d.x);analyzed.put("y_px",d.y);analyzed.put("major_axis_px",2*d.semiMajor);analyzed.put("minor_axis_px",2*d.semiMinor);analyzed.put("angle_deg",d.angleDeg);json.put("analyzed_ellipse",analyzed);
        JSONObject original=new JSONObject();original.put("x_px",d.x*ref.originalWidth/ref.width);original.put("y_px",d.y*ref.originalHeight/ref.height);original.put("major_axis_px",ref.originalAxes.major);original.put("minor_axis_px",ref.originalAxes.minor);original.put("angle_deg",ref.originalAxes.angleDeg);json.put("original_ellipse",original);json.put("note","Single user-selected scale reference; assumes comparable plane/depth. Does not correct perspective or lens distortion.");return json;
    }
    static JSONObject shapeJson(PelletDetector.Result result)throws Exception {
        ShapeStatistics shape=ShapeStatistics.of(result);JSONObject json=new JSONObject();json.put("metric","ellipse_minor_major_axis_ratio");json.put("label","Esfericidade aparente 2D (b/a)");json.put("formula","minor_axis/major_axis");json.put("true_3d_sphericity",false);json.put("weighting","arithmetic_mean_by_number");json.put("mean",finite(shape.mean));json.put("included",shape.included);json.put("excluded",shape.excluded);json.put("inclusion_rule","fitted ellipse, full image boundary, no needs_review, inside selected ROI; manually excluded calibration reference absent from sample");json.put("units","dimensionless");json.put("range","0 < ratio <= 1; 1 means circular projection, not proof of 3D sphere");return json;
    }
    static JSONObject crackJson(CrackAnalysis cracks)throws Exception {
        JSONObject json=new JSONObject();if(cracks==null){json.put("status","not_analyzed");return json;}
        boolean trained=false;for(CrackAnalysis.Entry e:cracks.entries)if(Double.isFinite(e.modelScore)){trained=true;break;}
        json.put("method",trained?CrackModel.ID:"experimental_blackhat_dark_line_geometry");json.put("trained_model",trained);json.put("model_scope","experimental; provisional labels; synthetic or unverified scenes; no real production validation");json.put("score_type","uncalibrated forest score, not a probability");json.put("trace_method","blackhat connected-line geometry; not trained crack segmentation");json.put("sensitivity",cracks.sensitivity);json.put("analysis_ms",cracks.durationMs);
        json.put("possible_crack_pellets",cracks.count(CrackAnalysis.Status.POSSIBLE));json.put("no_indication_pellets",cracks.count(CrackAnalysis.Status.NO_INDICATION));json.put("inconclusive_pellets",cracks.count(CrackAnalysis.Status.INCONCLUSIVE));json.put("manually_reviewed_pellets",cracks.reviewedCount());
        json.put("note","Visible-surface screening; no indication is not proof of no crack. Each pellet counted once.");
        JSONArray entries=new JSONArray();for(CrackAnalysis.Entry e:cracks.entries){JSONObject item=new JSONObject();item.put("pellet_id",e.pelletId);item.put("automatic_status",e.automatic.name());item.put("status",e.status.name());item.put("manually_reviewed",e.manuallyReviewed);
            item.put("model_score",Double.isFinite(e.modelScore)?e.modelScore:JSONObject.NULL);item.put("method",e.method);JSONArray runs=new JSONArray();if(e.status==CrackAnalysis.Status.POSSIBLE)for(float value:e.markedRuns())runs.put(value);item.put("marked_runs_x1_y1_x2_y2_px",runs);entries.put(item);}
        json.put("pellets",entries);return json;
    }
    static Object regionJson(InterestRegion region,int excluded)throws Exception {
        if(region==null)return JSONObject.NULL;
        JSONObject roi=new JSONObject();roi.put("shape",region.shape.name());roi.put("left_px",region.left);roi.put("top_px",region.top);roi.put("right_px",region.right);roi.put("bottom_px",region.bottom);
        roi.put("coordinates","full upright preview coordinates; active analysis may be cropped at 1:1 pixel scale, recorded in processing_scope");roi.put("inclusion_rule","entire detected ellipse inside ROI; unfitted seed boundary retained");roi.put("excluded_outside_or_crossing",excluded);return roi;
    }
    private static OutputStream stream(ContentResolver resolver,Uri uri)throws IOException {
        OutputStream output=resolver.openOutputStream(uri,"w");if(output==null)throw new IOException("Não foi possível abrir o destino.");return output;
    }
    private static Uri create(ContentResolver resolver,Uri collection,String name,String mime,String path)throws IOException {
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,path);values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=resolver.insert(collection,values);if(uri==null)throw new IOException("Não foi possível criar o arquivo.");return uri;
    }
}
