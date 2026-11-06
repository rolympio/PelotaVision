import unittest
import cv2
import numpy as np
from .sam2_quality import roi_mask, calibrated_scale, robust_area, resolve_pair, select_candidates


class QualityTest(unittest.TestCase):
    def test_overlap_policy_ablation(self):
        roi=np.ones((200,250),bool);bgr=np.full((200,250,3),100,np.uint8);raw=[]
        for y in (30,70,110):
            for x in (30,70,110):
                m=np.zeros(roi.shape,np.uint8);cv2.circle(m,(x,y),10,1,-1)
                raw.append((m.astype(bool),.9,{'prompt_xy':[x,y]}))
        a=np.zeros_like(roi);b=a.copy();a[150:170,150:171]=1;b[150:170,170:191]=1
        bgr[:,171:]=180
        raw.extend([(a,.95,{'prompt_xy':[160,160]}),(b,.94,{'prompt_xy':[180,160]})])
        strict=select_candidates(raw,roi,bgr,overlap_policy='any_pixel')[0]
        edge=select_candidates(raw,roi,bgr,overlap_policy='edge')[0]
        self.assertEqual(edge.max(),strict.max()+1)
    def test_non_circular_roi_hole_and_margin(self):
        spec={'source_sha256':'x','shape_hw':[40,60],'include_xy':[[2,2],[55,2],[40,35],[2,35]],
              'exclude_xy':[[[10,10],[20,10],[20,20],[10,20]]],'border_exclusion_px':2}
        m=roi_mask((40,60),spec,'x')
        self.assertFalse(m[15,15]);self.assertFalse(m[2,2]);self.assertTrue(m[25,25]);self.assertFalse(m[35,50])
        with self.assertRaises(ValueError):roi_mask((40,60),spec,'other')
    def test_duplicate_containment_and_ambiguity(self):
        a=np.zeros((50,50),bool);a[5:30,5:30]=1;b=a.copy();gray=np.zeros(a.shape,np.uint8)
        self.assertEqual(resolve_pair(a,b,gray)[0],'duplicate_or_nested')
        b[:]=0;b[10:20,10:20]=1
        self.assertEqual(resolve_pair(a,b,gray)[0],'duplicate_or_nested')
        b[:]=0;b[20:45,20:45]=1
        self.assertEqual(resolve_pair(a,b,gray)[0],'ambiguous_overlap')
    def test_small_overlap_requires_edge(self):
        a=np.zeros((40,60),bool);b=a.copy();a[10:30,5:26]=1;b[10:30,25:46]=1
        gray=np.zeros(a.shape,np.uint8)
        self.assertEqual(resolve_pair(a,b,gray)[0],'ambiguous_overlap')
        gray[:,26:]=255
        state,na,nb,metrics=resolve_pair(a,b,gray)
        self.assertEqual(state,'border_partition');self.assertFalse((na&nb).any())
        np.testing.assert_array_equal(na|nb,a|b)
        self.assertGreater(metrics['edge_strength'],0)
    def test_robust_area_rejects_background(self):
        roi=np.ones((160,160),bool);raw=[]
        for y in (30,70,110):
            for x in (30,70,110):
                mask=np.zeros(roi.shape,np.uint8);cv2.circle(mask,(x,y),10,1,-1)
                raw.append((mask.astype(bool),.9,{'prompt_xy':[x,y]}))
        raw.append((roi.copy(),.99,{'prompt_xy':[0,0]}))
        bounds=robust_area(raw,roi)
        self.assertEqual(bounds['samples'],9);self.assertLess(bounds['max'],1000)
        labels,decisions,queue,_=select_candidates(raw,roi,np.full((160,160,3),100,np.uint8))
        self.assertEqual(labels.max(),9);self.assertTrue(any('area_large_or_composite' in d['reasons'] for d in queue))
    def test_no_scale_evidence_preserves_candidates(self):
        m=np.zeros((40,40),bool);m[10:20,10:20]=1
        labels,decisions,queue,_=select_candidates([(m,.9,{'prompt_xy':[15,15]})],np.ones_like(m),np.zeros((40,40,3),np.uint8))
        self.assertFalse(labels.any());self.assertEqual(len(queue),1)
        self.assertIn('scale_estimate_unavailable',queue[0]['reasons'])
    def test_calibration_requires_measurement_not_color(self):
        self.assertIsNone(calibrated_scale(None,'x',(40,40)))
        with self.assertRaises(ValueError):calibrated_scale({'px_per_mm':10},'x',(40,40))
        record={'source_sha256':'x','shape_hw':[40,40],'status':'human_verified','operator':'tester',
                'measured_at':'2026-11-06','method':'diameter','evidence':'traceable-test',
                'references':[{'diameter_mm':11,'confirmed_standard':True,'measured_diameter_px':22,
                               'uncertainty_px':1,'exclude_polygon_xy':[[1,1],[5,1],[5,5]]}]}
        self.assertEqual(calibrated_scale(record,'x',(40,40)),2)
        with self.assertRaises(ValueError):calibrated_scale(record,'y',(40,40))

if __name__=='__main__':unittest.main()
