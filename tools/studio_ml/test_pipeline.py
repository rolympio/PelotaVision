"""Testes sintéticos sem dados privados; temporários somente na raiz privada."""
import unittest
import importlib.util

import cv2
import numpy as np

from .pipeline import (REPO, boundary, compare_instances, consensus, describe_instances,
                       ellipse_roi, foreground, multitask_targets, new_run, possible_cracks,
                       read_image, sha256, watershed_instances, write_image)
from .train_multitask import sample_patch, validated_supervision


class PipelineTest(unittest.TestCase):
    def scene(self, touching=False):
        gray = np.full((160, 220), 15, np.uint8)
        cv2.circle(gray, (65, 80), 29, 200, -1)
        cv2.circle(gray, (113 if touching else 155, 80), 29, 200, -1)
        return cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)

    def test_disconnected_and_touching(self):
        for touching in (False, True):
            image = self.scene(touching)
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            fg, _ = foreground(gray, np.ones(gray.shape, np.uint8))
            labels = watershed_instances(image, fg, seed_radius=15)
            self.assertEqual(labels.dtype, np.uint16)
            self.assertEqual(len(np.unique(labels))-1, 2)

    def test_empty(self):
        empty = np.zeros((40, 40), np.uint16)
        self.assertEqual(watershed_instances(np.zeros((40, 40, 3), np.uint8), empty.astype(np.uint8)).max(), 0)
        self.assertIsNone(compare_instances(empty, empty)["mean_matched_iou"])
        self.assertIsNone(compare_instances(empty, empty)["foreground_iou"])

    def test_id_permutation_and_exact_metrics(self):
        a = np.zeros((50, 50), np.uint16)
        a[5:15, 5:15], a[25:35, 25:35] = 1, 2
        b = np.where(a == 1, 500, np.where(a == 2, 1000, 0)).astype(np.uint16)
        result = compare_instances(a, b)
        self.assertEqual(result["matched_count"], 2)
        self.assertEqual(result["mean_matched_iou"], 1.)
        self.assertEqual(result["mean_matched_dice"], 1.)
        self.assertEqual(result["boundary_f1"], 1.)
        self.assertEqual(result["symmetric_contour_distance_px"], 0.)

    def test_no_overlap(self):
        a, b = np.zeros((30, 30), np.uint16), np.zeros((30, 30), np.uint16)
        a[2:8, 2:8], b[20:28, 20:28] = 1, 1
        r = compare_instances(a, b)
        self.assertEqual(r["matched_count"], 0)
        self.assertEqual(r["foreground_iou"], 0)
        self.assertEqual(r["boundary_f1"], 0)

    def test_one_to_one(self):
        a, b = np.zeros((40, 40), np.uint16), np.zeros((40, 40), np.uint16)
        a[5:30, 5:15], a[5:30, 15:25], b[5:30, 5:25] = 1, 2, 1
        self.assertEqual(compare_instances(a, b, .4)["matched_count"], 1)

    def test_uint16_unicode_roundtrip_original_hash(self):
        out = new_run("synthetic-test")
        labels = np.array([[0, 256, 65535]], np.uint16)
        path = out / "instâncias_uint16.png"
        write_image(path, labels)
        before = sha256(path)
        np.testing.assert_array_equal(read_image(path, cv2.IMREAD_UNCHANGED), labels)
        self.assertEqual(before, sha256(path))
        with self.assertRaises(FileExistsError):
            write_image(path, labels)

    def test_refuses_repo_output(self):
        with self.assertRaises(ValueError):
            new_run("unsafe", REPO / "tools" / "studio_ml")

    def test_semantic_uncertainty_cracks_and_targets(self):
        image = self.scene()
        cv2.circle(image, (65, 80), 29, (30, 60, 120), -1)
        cv2.circle(image, (155, 80), 29, (30, 60, 120), -1)
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        roi = np.ones(gray.shape, np.uint8)
        fg, _ = foreground(gray, roi)
        a = watershed_instances(image, fg, seed_radius=15)
        semantic, desc = describe_instances(a, roi, 60, image)
        self.assertTrue(set(np.unique(semantic)).issubset({1, 2, 3}))
        self.assertTrue((semantic == 3).any())
        self.assertTrue(all(d["structural_integrity"] == "nao_avaliada" for d in desc))
        self.assertTrue(all(d["calibration_reference_confirmed"] is False for d in desc))
        # Objetos sao consenso entre duas variantes, nunca verdade: objetos branco
        # candidatos nao recebem elegibilidade de calibração nem supervisionam fundo.
        self.assertTrue(all(d["semantic"] != 2 for d in desc))
        agreed, weight, uncertainty = consensus(a, a, compare_instances(a, a), desc, roi)
        self.assertGreater((agreed > 0).sum(), 0)
        self.assertTrue(np.array_equal(uncertainty, 255-weight))
        cracks, meta = possible_cracks(gray, a)
        self.assertEqual(meta["candidate_pixels"], 0)
        self.assertEqual(cracks.dtype, np.uint8)
        targets = multitask_targets(a)
        self.assertEqual(targets.shape, (3, *gray.shape))
        self.assertGreater(targets[1].sum(), 0)
        self.assertEqual(targets[2].sum(), 0)

    def test_separation_and_contact_boundary(self):
        labels = np.zeros((20, 20), np.uint16)
        labels[3:17, 3:10], labels[3:17, 10:17] = 1, 2
        self.assertGreater(multitask_targets(labels)[2].sum(), 0)
        self.assertTrue(boundary(labels)[7, 9])

    def test_discarded_foreground_not_confident_background(self):
        labels = np.zeros((20, 20), np.uint16)
        roi = np.ones(labels.shape, np.uint8)
        allowed = np.ones(labels.shape, bool)
        allowed[8:12, 8:12] = False
        _, weight, uncertainty = consensus(labels, labels, compare_instances(labels, labels), [], roi, allowed)
        self.assertEqual(weight[9, 9], 0)
        self.assertEqual(uncertainty[9, 9], 255)

    def test_roi_and_bad_comparison(self):
        roi = ellipse_roi((50, 70), (.5, .5, .3, .4))
        self.assertEqual(roi[0, 0], 0)
        self.assertEqual(roi[25, 35], 1)
        with self.assertRaises(ValueError):
            compare_instances(roi, roi[:, :-1])
        with self.assertRaises(ValueError):
            compare_instances(roi.astype(float), roi.astype(float))

    def test_unannotated_background_and_sphere_candidate(self):
        labels = np.zeros((50, 50), np.uint16)
        roi = np.ones(labels.shape, np.uint8)
        background = np.zeros(labels.shape, np.uint8)
        background[2:5, 2:5] = 255
        _, weight, uncertainty = consensus(labels, labels, compare_instances(labels, labels), [], roi, background)
        self.assertEqual(weight[3, 3], 255)
        self.assertEqual(weight[20, 20], 0)
        self.assertEqual(uncertainty[20, 20], 255)
        labels[18:32, 18:32] = 1
        image = np.full((50, 50, 3), 255, np.uint8)
        semantic, descriptions = describe_instances(labels, roi, 60, image)
        self.assertEqual(semantic[25, 25], 2)
        self.assertFalse(descriptions[0]["calibration_reference_confirmed"])

    def test_patch_padding_has_no_supervision(self):
        gray = np.full((20, 25), 120, np.uint8)
        target = np.ones((3, 20, 25), np.float32)
        weight = np.full(gray.shape, 255, np.uint8)
        x, y, w = sample_patch((gray, target, weight), 32, np.random.default_rng(12))
        self.assertEqual(x.shape, (1, 1, 32, 32))
        self.assertEqual(y.shape, (1, 3, 32, 32))
        self.assertEqual(w.sum(), 500)

    def test_uncertain_and_unreviewed_background_are_ignored(self):
        gray = np.zeros((10, 12), np.uint8)
        instances = np.zeros(gray.shape, np.uint16)
        instances[2:8, 3:9] = 1
        weight = np.full(gray.shape, 255, np.uint8)
        weight[4, 5] = 0
        targets = multitask_targets(instances)
        _, _, safe = validated_supervision(gray, targets, weight, instances)
        self.assertEqual(safe[0, 0], 0)
        self.assertEqual(safe[4, 5], 0)
        self.assertEqual(safe[5, 5], 255)
        self.assertFalse(np.any(safe[instances == 0]))

    def test_training_rejects_wrong_dtype_shape_and_unknown_target(self):
        gray = np.zeros((10, 12), np.uint8)
        labels = np.ones(gray.shape, np.uint16)
        weight = np.full(gray.shape, 255, np.uint8)
        targets = multitask_targets(labels)
        for bad_gray, bad_targets, bad_weight, bad_labels in (
                (gray.astype(np.uint16), targets, weight, labels),
                (gray, targets[:, :, :-1], weight, labels),
                (gray, np.full(targets.shape, 255), weight, labels),
                (gray, targets, weight, labels.astype(np.uint8))):
            with self.assertRaises(ValueError):
                validated_supervision(bad_gray, bad_targets, bad_weight, bad_labels)


@unittest.skipUnless(importlib.util.find_spec("torch"), "PyTorch opcional ausente")
class TorchTest(unittest.TestCase):
    def test_cpu_forward_odd_shape_and_ignored_gradient(self):
        import torch
        from .train_multitask import build_model, weighted_loss
        torch.set_num_threads(2)
        model = build_model(torch)
        self.assertEqual(tuple(model(torch.zeros(1, 1, 33, 47)).shape), (1, 3, 33, 47))
        logits = torch.zeros(1, 3, 10, 10, requires_grad=True)
        targets = torch.ones_like(logits)
        weights = torch.ones(1, 1, 10, 10)
        weights[:, :, 2:7, 3:8] = 0
        loss, _ = weighted_loss(torch, logits, targets, weights, torch.ones(3))
        loss.backward()
        self.assertEqual(float(logits.grad[:, :, 2:7, 3:8].abs().sum()), 0.)
        self.assertGreater(float(logits.grad.abs().sum()), 0.)

    def test_tiled_inference_preserves_grid_and_normalization(self):
        import torch
        from .infer import predict_tiled

        class Echo(torch.nn.Module):
            def forward(self, x):
                return torch.cat((x, x, x), dim=1)

        gray = np.arange(53*75, dtype=np.uint16).reshape(53, 75).astype(np.uint8)
        out = predict_tiled(torch, Echo(), gray, core=16, context=8)
        expected = 1/(1+np.exp(-gray.astype(np.float32)/255))
        self.assertEqual(out.shape, (3, 53, 75))
        np.testing.assert_allclose(out[0], expected, atol=1e-7)
        self.assertTrue(np.isfinite(out).all())


if __name__ == "__main__":
    unittest.main(verbosity=2)
