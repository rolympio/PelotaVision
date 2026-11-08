"""Testes sintéticos de propostas SAM, sem checkpoint ou calibração física."""
import unittest
import json
from types import SimpleNamespace
from pathlib import Path
from tempfile import TemporaryDirectory
import numpy as np
from tools.studio_ml.sam2_candidates import grid_points, centers, filter_candidates
from tools.studio_ml.sam2_candidates import area_limits, child, checked_artifacts
from tools.studio_ml.pipeline import sha256

class TestSAM2Candidates(unittest.TestCase):
    def test_artifact_tampering(self):
        with TemporaryDirectory() as d:
            root=Path(d).resolve();folder=root/'image';folder.mkdir()
            file=folder/'labels.bin';file.write_bytes(b'original')
            (root/'artifact_hashes.json').write_text(json.dumps({'image\\labels.bin':sha256(file)}))
            self.assertEqual(checked_artifacts(root,'image',('labels.bin',)),{'image/labels.bin':sha256(file)})
            file.write_bytes(b'alterado')
            with self.assertRaises(ValueError): checked_artifacts(root,'image',('labels.bin',))
    def test_pixel_mode_without_calibration(self):
        args=SimpleNamespace(px_per_mm=None,min_area_px=60,max_area_px=10000)
        self.assertEqual(area_limits(args),(60,10000))
        args.max_area_px=float('nan')
        with self.assertRaises(ValueError): area_limits(args)
    def test_physical_scale_requires_record(self):
        args=SimpleNamespace(px_per_mm=10,calibration_record=None)
        with self.assertRaises(ValueError): area_limits(args)
    def test_path_escape(self):
        with TemporaryDirectory() as d:
            root=Path(d)/'run';root.mkdir()
            with self.assertRaises(ValueError): child(root.resolve(),'..')
            with self.assertRaises(ValueError): child(root.resolve(),str(root.resolve()))
    def test_incompatible_grid(self):
        with self.assertRaises(ValueError): centers(np.zeros((2,2)),np.ones((3,3)))
    def test_nonfinite_score(self):
        mask=np.ones((2,2),bool)
        _,info,rejected=filter_candidates([(mask,float('nan'),{})],mask,1,10)
        self.assertFalse(info);self.assertEqual(rejected,{'score_invalido':1})
    def test_nan_does_not_break_score_priority(self):
        mask=np.ones((3,3),bool)
        _,info,rejected=filter_candidates([(mask,.4,{}),(mask,float('nan'),{}),(mask,.9,{})],mask,1,10)
        self.assertEqual(info[0]['score_sam'],.9)
        self.assertEqual(rejected,{'sobreposicao':1,'score_invalido':1})
    def test_grid(self):
        roi=np.zeros((32,32),bool);roi[8:24,8:24]=True
        pts=grid_points(roi,8)
        self.assertTrue(pts)
        self.assertTrue(all(roi[y,x] for x,y in pts))
    def test_centers(self):
        a=np.zeros((20,20),np.uint16);a[3:8,3:8]=1
        pts=centers(a,np.ones_like(a,bool))
        self.assertEqual(len(pts),1);self.assertEqual(pts[0],(5,5))
    def test_overlap_score(self):
        a=np.zeros((20,20),bool);b=a.copy();a[2:10,2:10]=True;b[5:13,5:13]=True
        labels,info,reject=filter_candidates([(a,.4,{}),(b,.9,{})],np.ones_like(a),5,200)
        self.assertEqual(len(info),1);self.assertEqual(info[0]['score_sam'],.9)
        self.assertEqual(reject['sobreposicao'],1)
    def test_area_and_outside(self):
        roi=np.zeros((20,20),bool);roi[5:15,5:15]=True
        small=np.zeros_like(roi);small[7,7]=True
        outside=np.ones_like(roi)
        labels,info,reject=filter_candidates([(small,.8,{}),(outside,.9,{})],roi,5,500)
        self.assertEqual(len(info),0);self.assertEqual(reject['area'],1);self.assertEqual(reject['fora_roi'],1)

if __name__=='__main__':unittest.main()
