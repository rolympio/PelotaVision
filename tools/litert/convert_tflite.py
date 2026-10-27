#!/usr/bin/env python3
"""Converte os modelos ONNX embarcados para LiteRT (.tflite) e mede a paridade com o ONNX.

Uso (no CI, ver .github/workflows/litert-convert.yml):
    python tools/litert/convert_tflite.py --assets app/src/main/assets --out verification/litert-0.16.0

O .tflite é só um segundo formato do MESMO modelo, para o Android executar na GPU (LiteRT + GpuDelegate).
O ONNX continua sendo a referência. Para cada modelo:
  1. onnx2tf gera <modelo>.tflite (float32);
  2. a camada de saída é comparada com o onnxruntime em entradas reais (blocos StarDist das fotos de
     verificação, recortes de pelotas para a MobileNetV3) e o layout da saída (NCHW ou NHWC) é detectado;
  3. para a StarDist, a detecção completa (ml_training/stardist/common.detect) roda com os dois motores e
     as pelotas são casadas uma a uma;
  4. grava <modelo>.tflite.json ao lado do .tflite, com o SHA-256 do ONNX de origem: o app recusa um
     .tflite cujo ONNX de origem não é o ONNX instalado (conversão desatualizada).
O GpuDelegate pode calcular em fp16 no aparelho; esse efeito não é medido aqui, e sim no próprio aparelho,
pela sonda de paridade do app (HardwareAcceleration), que recusa o motor fora da tolerância.
"""
import argparse, hashlib, json, math, shutil, subprocess, sys, tempfile, time
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ml_training' / 'stardist'))
sys.path.insert(0, str(ROOT / 'tools' / 'cracks'))
import common  # noqa: E402

MODELS = [
    dict(onnx='pellet_stardist.onnx', meta='pellet_stardist.json', kind='stardist', belt=False),
    dict(onnx='pellet_stardist_belt.onnx', meta='pellet_stardist_belt.json', kind='stardist', belt=True),
    dict(onnx='crack_mobilenetv3.onnx', meta='crack_mobilenetv3.json', kind='mobilenet'),
]
BENCH_IMAGES = ['verification/fixtures/1000411645.jpg', 'verification/fixtures/1000411646.jpg',
                'verification/fixtures/1000411647.jpg', 'verification/fixtures/crack_example.png']
BELT_IMAGES = ['verification/belt-0.9.0/input_correia.jpg', 'ml_training/data_belt/raw/img422.jpg',
               'ml_training/data_belt/raw/img919.jpg']


def sha256(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def tflite_interpreter(path):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:  # pragma: no cover
        from tensorflow.lite import Interpreter
    it = Interpreter(model_path=str(path), num_threads=2)
    it.allocate_tensors()
    return it


def tflite_runner(path, layout):
    it = tflite_interpreter(path)
    i, o = it.get_input_details()[0], it.get_output_details()[0]

    def run(x):  # x: NCHW float32
        if tuple(i['shape']) != x.shape:
            x = np.transpose(x, (0, 2, 3, 1))  # entradas de 1 canal: mesma memória, só outra forma
        it.set_tensor(i['index'], np.ascontiguousarray(x, np.float32))
        it.invoke()
        y = it.get_tensor(o['index'])
        return np.transpose(y, (0, 3, 1, 2)) if layout == 'NHWC' else y
    return run, list(map(int, i['shape'])), list(map(int, o['shape']))


def ort_runner(path):
    import onnxruntime as ort
    s = ort.InferenceSession(str(path), providers=['CPUExecutionProvider'])
    name = s.get_inputs()[0].name
    return lambda x: s.run(None, {name: x})[0]


def convert(onnx_path, work):
    out = work / onnx_path.stem
    # O onnx2tf reescreve o ONNX de entrada (simplificação): converte sempre uma CÓPIA, nunca o embarcado.
    src = work / ('src_' + onnx_path.name)
    shutil.copyfile(onnx_path, src)
    cmd = [sys.executable, '-m', 'onnx2tf', '-i', str(src), '-o', str(out), '-b', '1', '-n']
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        # versões antigas do onnx2tf não aceitam execução como módulo
        r = subprocess.run(['onnx2tf'] + cmd[3:], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError('onnx2tf falhou:\n' + (r.stdout + r.stderr)[-3000:])
    f32 = sorted(out.glob('*_float32.tflite'))
    f16 = sorted(out.glob('*_float16.tflite'))
    if not f32:
        raise RuntimeError('onnx2tf não gerou *_float32.tflite: ' + ' '.join(p.name for p in out.iterdir()))
    return f32[0], (f16[0] if f16 else None), round(time.time() - t0, 1)


def detect_layout(tfl, ref_out, x):
    """Compara a saída do .tflite com a do ONNX nas duas interpretações possíveis do layout."""
    best = None
    for layout in ('NCHW', 'NHWC'):
        try:
            run, _, _ = tflite_runner(tfl, layout)
            y = run(x)
            if y.shape != ref_out.shape:
                continue
            d = float(np.max(np.abs(y - ref_out)))
            if best is None or d < best[1]:
                best = (layout, d)
        except Exception:
            continue
    if best is None:
        raise RuntimeError('saída do .tflite incompatível com a do ONNX')
    return best[0]


# --------------------------------------------------------------------------- StarDist

def stardist_tiles(paths, belt, limit=6):
    """Blocos reais normalizados exatamente como no app (perfil auto, primeira escala)."""
    tiles = []
    for p in paths:
        g = cv2.imread(str(ROOT / p), cv2.IMREAD_GRAYSCALE)
        if g is None:
            continue
        frac = (common.BELT_RADII if belt else common.PROFILE_RADII)[3][1]
        sc = common.NOMINAL_RADIUS / (frac * g.shape[1])
        small = common.resize_for_net(g, sc)
        st = common.norm_stats(small)
        n = common.normalize(small, st)
        fill = np.float32((st[2] - st[0]) / (st[1] - st[0]))
        for y0 in common.tile_starts(n.shape[0])[:2]:
            for x0 in common.tile_starts(n.shape[1])[:2]:
                t = np.full((common.TILE, common.TILE), fill, np.float32)
                th, tw = min(common.TILE, n.shape[0] - y0), min(common.TILE, n.shape[1] - x0)
                t[:th, :tw] = n[y0:y0 + th, x0:x0 + tw]
                tiles.append(t)
                if len(tiles) >= limit * len(paths):
                    return tiles
    return tiles


def match_dets(a, b):
    used, pairs = set(), []
    for d in a:
        best, bj = None, None
        for j, e in enumerate(b):
            if j in used:
                continue
            dd = math.hypot(d['x'] - e['x'], d['y'] - e['y'])
            if dd < .25 * d['radius'] and (best is None or dd < best):
                best, bj = dd, j
        if bj is not None:
            used.add(bj)
            pairs.append((d, b[bj], best))
    return pairs


def stardist_parity(onnx_path, tfl, layout, belt):
    ort = ort_runner(onnx_path)
    run, _, _ = tflite_runner(tfl, layout)
    tiles = stardist_tiles(BELT_IMAGES if belt else BENCH_IMAGES, belt)
    dp, dd = 0., 0.
    t_ort = t_tfl = 0.
    for t in tiles:
        x = t[None, None]
        t0 = time.perf_counter(); a = ort(x); t_ort += time.perf_counter() - t0
        t0 = time.perf_counter(); b = run(x); t_tfl += time.perf_counter() - t0
        dp = max(dp, float(np.max(np.abs(a[0, 0] - b[0, 0]))))
        dd = max(dd, float(np.max(np.abs(np.maximum(a[0, 1:], 0) - np.maximum(b[0, 1:], 0)))))
    images = []
    for p in (BELT_IMAGES if belt else BENCH_IMAGES):
        g = cv2.imread(str(ROOT / p), cv2.IMREAD_GRAYSCALE)
        if g is None:
            continue
        da, _, _ = common.detect(lambda tile: ort(tile[None, None])[0], g, profile=3, sensitivity=75, belt=belt)
        db, _, _ = common.detect(lambda tile: run(tile[None, None])[0], g, profile=3, sensitivity=75, belt=belt)
        pairs = match_dets(da, db)
        rerr = max([abs(e['radius'] - d['radius']) / d['radius'] for d, e, _ in pairs] or [0.])
        cerr = max([c / d['radius'] for d, _, c in pairs] or [0.])
        images.append(dict(image=p, onnx=len(da), tflite=len(db), matched=len(pairs),
                           max_center_shift_rel_radius=round(cerr, 5), max_radius_rel_diff=round(rerr, 5)))
    n = max(1, len(tiles))
    return dict(tiles=len(tiles), max_abs_diff_prob=dp, max_abs_diff_dist_px=dd,
                ms_per_tile_onnxruntime_cpu=round(1000 * t_ort / n, 2), ms_per_tile_tflite_cpu=round(1000 * t_tfl / n, 2),
                detection=images)


# --------------------------------------------------------------------------- MobileNetV3

def mobilenet_crops(meta):
    from train_mobilenet import prep
    g = cv2.imread(str(ROOT / 'verification/fixtures/crack_example.png'), cv2.IMREAD_GRAYSCALE)
    crops = [prep(g, r['x'], r['y'], r['r']) for r in meta.get('parity_reference', [])]
    # mais recortes reais: pelotas detectadas pela StarDist de bancada nas fotos de verificação
    sd = ROOT / 'app/src/main/assets/pellet_stardist.onnx'
    if sd.is_file():
        ort = ort_runner(sd)
        for p in BENCH_IMAGES[:3]:
            im = cv2.imread(str(ROOT / p), cv2.IMREAD_GRAYSCALE)
            dets, _, _ = common.detect(lambda t: ort(t[None, None])[0], im, profile=3, sensitivity=75)
            for d in dets[:40]:
                crops.append(prep(im, d['x'], d['y'], d['radius']))
    return crops


def mobilenet_parity(onnx_path, tfl, layout, meta):
    ort = ort_runner(onnx_path)
    run, _, _ = tflite_runner(tfl, layout)
    crops = mobilenet_crops(meta)
    diffs, flips, t_ort, t_tfl = [], 0, 0., 0.
    for c in crops:
        x = c[None, None].astype(np.float32)
        t0 = time.perf_counter(); a = float(ort(x).reshape(-1)[1]); t_ort += time.perf_counter() - t0
        t0 = time.perf_counter(); b = float(run(x).reshape(-1)[1]); t_tfl += time.perf_counter() - t0
        diffs.append(abs(a - b))
        # política do app: <0,4 sem indício, 0,4-0,6 inconclusiva, >0,6 possível trinca
        cls = lambda p: 0 if p < .4 else (1 if p <= .6 else 2)
        flips += cls(a) != cls(b)
    n = max(1, len(crops))
    return dict(crops=len(crops), max_abs_diff_p_cracked=max(diffs or [0.]),
                mean_abs_diff_p_cracked=float(np.mean(diffs)) if diffs else 0., policy_class_changes=flips,
                ms_per_crop_onnxruntime_cpu=round(1000 * t_ort / n, 3), ms_per_crop_tflite_cpu=round(1000 * t_tfl / n, 3))


# --------------------------------------------------------------------------- principal

def versions():
    out = {}
    for m in ('onnx2tf', 'tensorflow', 'onnx', 'onnxruntime', 'ai_edge_litert', 'numpy'):
        try:
            mod = __import__(m)
            out[m] = getattr(mod, '__version__', '?')
        except Exception as e:  # pragma: no cover
            out[m] = 'ausente: ' + type(e).__name__
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--assets', default='app/src/main/assets')
    ap.add_argument('--out', default='verification/litert')
    a = ap.parse_args(argv)
    assets, out = ROOT / a.assets, ROOT / a.out
    out.mkdir(parents=True, exist_ok=True)
    report = dict(tool='tools/litert/convert_tflite.py', versions=versions(), models=[])
    work = Path(tempfile.mkdtemp(prefix='pv_litert_'))
    ok = True
    for m in MODELS:
        onnx = assets / m['onnx']
        if not onnx.is_file():
            report['models'].append(dict(model=m['onnx'], skipped='ONNX ausente'))
            continue
        meta = json.loads((assets / m['meta']).read_text(encoding='utf-8'))
        entry = dict(model=m['onnx'], onnx_sha256=sha256(onnx))
        try:
            f32, _, secs = convert(onnx, work)
            entry['convert_seconds'] = secs
            probe = (stardist_tiles(BELT_IMAGES if m.get('belt') else BENCH_IMAGES, m.get('belt'), 1)[0][None, None]
                     if m['kind'] == 'stardist' else mobilenet_crops(meta)[0][None, None].astype(np.float32))
            layout = detect_layout(f32, ort_runner(onnx)(probe), probe)
            dst = assets / (onnx.stem + '.tflite')
            shutil.copyfile(f32, dst)
            _, ishape, oshape = tflite_runner(dst, layout)
            entry.update(tflite=dst.name, tflite_sha256=sha256(dst), tflite_bytes=dst.stat().st_size,
                         input_shape=ishape, output_shape=oshape, output_layout=layout)
            par = (stardist_parity(onnx, dst, layout, m.get('belt')) if m['kind'] == 'stardist'
                   else mobilenet_parity(onnx, dst, layout, meta))
            entry['parity_float32'] = par
            # Critério de aceite do float32 (o que é distribuído): mesmas pelotas e mesmo escore.
            if m['kind'] == 'stardist':
                det = par['detection']
                good = (par['max_abs_diff_prob'] < 1e-3 and all(d['onnx'] == d['tflite'] == d['matched'] for d in det)
                        and all(d['max_radius_rel_diff'] < 1e-3 for d in det))
            else:
                good = par['max_abs_diff_p_cracked'] < 1e-3 and par['policy_class_changes'] == 0
            entry['accepted'] = bool(good)
            ok &= good
            sidecar = dict(source_onnx=onnx.name, onnx_sha256=entry['onnx_sha256'], tflite=dst.name,
                           tflite_sha256=entry['tflite_sha256'], input_shape=ishape, output_shape=oshape,
                           output_layout=layout, converter=report['versions'], accepted=entry['accepted'],
                           parity_float32=par, generated_by=report['tool'])
            (assets / (dst.name + '.json')).write_text(json.dumps(sidecar, indent=1, ensure_ascii=False), encoding='utf-8')
            if not good:
                dst.unlink()  # sem paridade, o app não recebe o .tflite
                (assets / (dst.name + '.json')).unlink()
        except Exception as e:
            entry['error'] = repr(e)[:3000]
            ok = False
        report['models'].append(entry)
    # Os ONNX embarcados são a referência: a conversão não pode alterá-los.
    for e in report['models']:
        if 'onnx_sha256' in e and sha256(assets / e['model']) != e['onnx_sha256']:
            e['error'] = 'ONNX embarcado foi alterado durante a conversão'
            ok = False
    (out / 'litert_conversion.json').write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(report, indent=1, ensure_ascii=False))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
