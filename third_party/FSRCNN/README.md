# FSRCNN x2 — third-party model

Author/implementation: Saafke, created during GSoC 2019 with OpenCV. Source: https://github.com/Saafke/FSRCNN_Tensorflow . Apache-2.0; see LICENSE and upstream readme. The author reports T91 training and General100 finetuning; this is not a pellet model.

The complete original frozen graph is FSRCNN_x2_upstream.pb. The packaged app asset FSRCNN_x2_features.pb has exactly the same learned parameters, ending at conv8. Java FsrcnnUpscaler performs the source graph's x2 DepthToSpace shuffle and adds its unchanged scalar b8 afterward. This avoids requiring the contrib module's custom layer registration in the Android OpenCV artifact. No weights were trained or modified.

MODEL_PROVENANCE.json records hashes and source URL. Run `python3 tools/prepare_fsrcnn.py third_party/FSRCNN/FSRCNN_x2_upstream.pb app/src/main/assets/FSRCNN_x2_features.pb` from the project root to reproduce the asset. FsrcnnUpscaler.shuffle is covered by channel-order/bias tests; the native harness also runs the actual network and checks x2 dimensions and a flat field. This verifies implementation behavior, not industrial reconstruction accuracy.

The shuffle ordering follows OpenCV's dnn_superres reference implementation (Apache-2.0), https://github.com/opencv/opencv_contrib/blob/4.12.0/modules/dnn_superres/src/dnn_superres.cpp . Grayscale input is normalized by 255, matching its luminance-network preprocessing. The model is used only for optional contour proposals. Final ellipse fits and crack inference use original grayscale pixels.
