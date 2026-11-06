"""Políticas exploratórias de ROI, geometria e conflitos; não ground truth."""
from __future__ import annotations

import cv2
import numpy as np


def propose_roi(bgr):
    """Sugestão fotométrica poligonal; exige inspeção, não presume círculo."""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    _, dark = cv2.threshold(cv2.GaussianBlur(gray, (9, 9), 0), 0, 255,
                            cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    dark = cv2.morphologyEx(dark, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    contours, _ = cv2.findContours(dark, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    h, w = gray.shape
    def rank(c):
        x, y, cw, ch = cv2.boundingRect(c)
        return cv2.contourArea(c) / (1 + ((x+cw/2-w/2)/w)**2 + ((y+ch/2-h/2)/h)**2)
    if not contours:
        raise ValueError('Sem sugestão ROI; desenhe polígono manualmente')
    hull = cv2.convexHull(max(contours, key=rank))
    return cv2.approxPolyDP(hull, .015*cv2.arcLength(hull, True), True).reshape(-1, 2).tolist()


def roi_mask(shape, spec, source_hash):
    if spec['source_sha256'] != source_hash or spec['shape_hw'] != list(shape):
        raise ValueError('ROI de outra imagem/grade')
    def polygon(points):
        pts = np.asarray(points, dtype=np.float64)
        if pts.ndim != 2 or pts.shape[1] != 2 or len(pts) < 3 or not np.isfinite(pts).all():
            raise ValueError('Polígono inválido')
        if np.any(pts < 0) or np.any(pts[:, 0] >= shape[1]) or np.any(pts[:, 1] >= shape[0]):
            raise ValueError('Polígono fora da grade')
        result = np.zeros(shape, np.uint8)
        cv2.fillPoly(result, [np.round(pts).astype(np.int32)], 1)
        return result
    mask = polygon(spec['include_xy'])
    for exclusion in spec.get('exclude_xy', []):
        mask[polygon(exclusion) > 0] = 0
    margin = spec.get('border_exclusion_px', 3)
    if not isinstance(margin, int) or not 0 <= margin <= 50:
        raise ValueError('Margem inválida')
    mask = cv2.erode(mask, np.ones((2*margin+1, 2*margin+1), np.uint8),
                     borderType=cv2.BORDER_CONSTANT, borderValue=0)
    if not mask.any():
        raise ValueError('ROI vazia')
    return mask.astype(bool)


def geometry(mask):
    m = np.asarray(mask, np.uint8)
    area = int(m.sum())
    n, _, stats, _ = cv2.connectedComponentsWithStats(m, 8)
    contours, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return dict(area_px=0, components=0, circularity=0., solidity=0., aspect=0.)
    c = max(contours, key=cv2.contourArea)
    ca = cv2.contourArea(c); perimeter = cv2.arcLength(c, True)
    hull = cv2.contourArea(cv2.convexHull(c))
    x, y, w, h = cv2.boundingRect(m)
    return dict(area_px=area, components=n-1, circularity=min(1., 4*np.pi*ca/perimeter**2) if perimeter else 0.,
                solidity=ca/hull if hull else 0., aspect=min(w,h)/max(w,h), bbox_xywh=[x,y,w,h])


def calibrated_scale(record, digest, shape):
    """Registro de medição explícita; cor branca nunca confirma esfera padrão."""
    if record is None:
        return None
    required = ('operator', 'measured_at', 'method', 'evidence', 'references')
    if any(not record.get(k) for k in required) or record.get('status') != 'human_verified':
        raise ValueError('Calibração sem rastreabilidade humana')
    if record.get('source_sha256') != digest or record.get('shape_hw') != list(shape):
        raise ValueError('Calibração de outra imagem/grade')
    scales = []
    for ref in record['references']:
        if ref.get('diameter_mm') != 11 or not ref.get('confirmed_standard') or not ref.get('exclude_polygon_xy'):
            raise ValueError('Referência 11 mm não confirmada/excluída')
        px = ref.get('measured_diameter_px', 0)
        uncertainty = ref.get('uncertainty_px', -1)
        if not np.isfinite([px, uncertainty]).all() or px <= 0 or not 0 <= uncertainty < px:
            raise ValueError('Medição/incerteza inválida')
        scales.append(px/11)
    scale = float(np.median(scales))
    if max(abs(s-scale)/scale for s in scales) > .1:
        raise ValueError('Referências discordam mais de 10%; revisar perspectiva')
    return scale


def robust_area(candidates, roi, scale=None, diameter_mm=(5.,25.)):
    if scale is not None:
        return {'mode':'calibrated', 'min':float(np.pi*(diameter_mm[0]*scale/2)**2),
                'max':float(np.pi*(diameter_mm[1]*scale/2)**2), 'px_per_mm':scale}
    # Um voto por prompt, evitando contar as três alternativas como três objetos.
    best = {}
    for mask, score, meta in candidates:
        g = geometry(mask); area = g['area_px']
        if (area >= 20 and area < roi.sum()*.02 and g['components']==1 and g['circularity']>=.55
                and g['solidity']>=.8 and g['aspect']>=.55 and score>=.7
                and (mask & ~roi).sum()/area<=.01):
            key = tuple(meta['prompt_xy'])
            if key not in best or score>best[key][0]:best[key]=(score,area)
    areas = np.array([v[1] for v in best.values()])
    if len(areas)<8:
        return {'mode':'insufficient_evidence','min':None,'max':None,'samples':len(areas)}
    median = float(np.median(areas)); mad = float(np.median(np.abs(areas-median)))
    spread = max(mad, median*.15)  # evita intervalo degenerado quando MAD=0
    return {'mode':'exploratory_per_method','median_px2':median,'mad_px2':mad,'samples':len(areas),
            'min':max(20., median*.3, median-4*1.4826*spread),
            'max':min(median*2.8, median+4*1.4826*spread),
            'warning':'Escala de área heurística; não é pixels/mm nem distribuição granulométrica'}


def resolve_pair(a, b, gray, duplicate_iou=.65, containment=.9, small_overlap=.08):
    """Classifica conflito e propõe divisão apenas em bordas com evidência óptica."""
    inter = a & b; ni = int(inter.sum()); aa = int(a.sum()); ab = int(b.sum())
    metrics = {'intersection_px':ni,'iou':ni/(aa+ab-ni) if aa+ab-ni else 0.,
               'containment':ni/min(aa,ab) if min(aa,ab) else 0.}
    if not ni:return 'disjoint', a, b, metrics
    if metrics['iou']>=duplicate_iou or metrics['containment']>=containment:
        return 'duplicate_or_nested', a, b, metrics
    if ni/min(aa,ab)>small_overlap:return 'ambiguous_overlap',a,b,metrics
    da=cv2.distanceTransform(a.astype(np.uint8),cv2.DIST_L2,5)
    db=cv2.distanceTransform(b.astype(np.uint8),cv2.DIST_L2,5)
    grad=cv2.magnitude(cv2.Sobel(gray,cv2.CV_32F,1,0),cv2.Sobel(gray,cv2.CV_32F,0,1))
    values=grad[a|b]; threshold=max(10.,float(np.percentile(values,60)))
    metrics.update(edge_strength=float(grad[inter].mean()),edge_threshold=threshold)
    if not np.all((da[inter]<=3)&(db[inter]<=3)) or metrics['edge_strength']<threshold:
        return 'ambiguous_overlap',a,b,metrics
    na=a.copy();nb=b.copy()
    na[inter & (db>da)]=False;nb[inter & (da>=db)]=False
    if not na.any() or not nb.any() or geometry(na)['components']!=1 or geometry(nb)['components']!=1:
        return 'ambiguous_overlap',a,b,metrics
    return 'border_partition',na,nb,metrics


def select_candidates(candidates, roi, bgr, scale=None, reference_mask=None, overlap_policy='edge'):
    if overlap_policy not in ('edge','any_pixel','iou_only'):
        raise ValueError('Política de sobreposição desconhecida')
    gray=cv2.cvtColor(bgr,cv2.COLOR_BGR2GRAY)
    limits=robust_area(candidates,roi,scale)
    accepted=[]; decisions=[]; queue=[]
    edge=roi & ~cv2.erode(roi.astype(np.uint8),np.ones((5,5),np.uint8)).astype(bool)
    hsv=cv2.cvtColor(bgr,cv2.COLOR_BGR2HSV)
    for index in sorted(range(len(candidates)),key=lambda i: -candidates[i][1] if np.isfinite(candidates[i][1]) else float('inf')):
        mask,score,meta=candidates[index];g=geometry(mask);area=g['area_px'];reasons=[]
        outside=float((mask & ~roi).sum()/area) if area else 1.
        if not np.isfinite(score):reasons.append('invalid_score')
        if outside>.02:reasons.append('outside_roi')
        if reference_mask is not None and (mask & reference_mask).any():reasons.append('confirmed_reference_excluded')
        if limits['min'] is None:reasons.append('scale_estimate_unavailable')
        elif not limits['min']<=area<=limits['max']:reasons.append('area_small' if area<limits['min'] else 'area_large_or_composite')
        if g['components']!=1:reasons.append('disconnected')
        if g['circularity']<.45 or g['solidity']<.75 or g['aspect']<.45:reasons.append('irregular_or_composite')
        if (mask & edge).any():reasons.append('partial_at_roi')
        if score<.7:reasons.append('low_sam_score')
        if area and float(((hsv[:,:,1]<45)&(hsv[:,:,2]>210)&mask).sum()/area)>.7:
            reasons.append('possible_reference_unconfirmed')
        decision={'candidate_index':index,'score_sam':float(score) if np.isfinite(score) else None,
                  **meta,**g,'outside_fraction':outside,'reasons':reasons,'conflicts':[]}
        work=mask.copy(); updates={}
        if not reasons:
            for k,previous in enumerate(accepted):
                state,old,new,metrics=resolve_pair(previous['mask'],work,gray)
                if state=='disjoint':continue
                if overlap_policy=='any_pixel':state='any_pixel_rejection'
                elif overlap_policy=='iou_only' and state=='border_partition':state='ambiguous_overlap'
                decision['conflicts'].append({'with_candidate':previous['index'],'state':state,**metrics})
                if state!='border_partition':reasons.append(state);break
                updates[k]=old;work=new
        if reasons:
            decision['status']='review_pending'
            # Duplicações também são preservadas; contenção pode ser objeto composto.
            priority=100 if any('composite' in v or v=='ambiguous_overlap' for v in reasons) else 80 if 'partial_at_roi' in reasons else 60 if 'possible_reference_unconfirmed' in reasons else 20
            queue.append({'priority':priority,**decision})
        else:
            for k,old in updates.items():accepted[k]['mask']=old
            accepted.append({'mask':work,'index':index})
            decision['status']='accepted_exploratory'
        decisions.append(decision)
    if len(accepted)>65535:raise ValueError('Limite uint16')
    labels=np.zeros(roi.shape,np.uint16)
    for ident,item in enumerate(accepted,1):
        labels[item['mask']]=ident
        next(d for d in decisions if d['candidate_index']==item['index'])['output_id']=ident
    return labels,decisions,sorted(queue,key=lambda d:(-d['priority'],d['candidate_index'])),limits
