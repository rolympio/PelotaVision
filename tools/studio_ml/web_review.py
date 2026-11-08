#!/usr/bin/env python3
"""PelotaVision — Studio de Revisão Visual (web, sem display nativo).
Evolui o editor tkinter existente. Não declara ground truth.
Uso Windows: python -m tools.studio_ml.web_review --folder <pasta-candidata> --revisor <nome>
"""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import cv2
import numpy as np

from .review import Review, load_candidate, export_review, KINDS, CONFIDENCE, OCCLUSION, STATES


class WebReview:
    def __init__(self, model, reviewer):
        self.model = model
        self.reviewer = reviewer
        self.selected = None

    def overlay(self):
        a = self.model.labels
        rgb = cv2.cvtColor(self.model.gray, cv2.COLOR_GRAY2RGB)
        colors = np.stack([(a.astype(np.uint32)*211)%255, (a.astype(np.uint32)*139)%255,
                           (a.astype(np.uint32)*73)%255], axis=-1).astype(np.uint8)
        present = a > 0
        rgb[present] = (rgb[present].astype(np.uint16)*2//3 + colors[present].astype(np.uint16)//3).astype(np.uint8)
        from .pipeline import boundary
        rgb[boundary(a) > 0] = (255, 220, 0)
        if self.selected is not None:
            rgb[(boundary(a) > 0) & (a == self.selected)] = (255, 60, 60)
        return rgb

    def candidates(self):
        # Prioriza instâncias com metadados pendentes ou origem ambígua
        items = []
        for ident, meta in sorted(self.model.meta.items()):
            if meta.get("estado") == "pendente" or "ambigua" in meta.get("origem", ""):
                items.append({"id": ident, "classe": meta.get("classe"), "origem": meta.get("origem"), "estado": meta.get("estado")})
        return items[:20]


def make_handler(studio):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            p = urlparse(self.path).path
            if p == "/":
                html = INDEX_HTML.replace("{{REVIEWER}}", studio.reviewer)
                self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.end_headers()
                self.wfile.write(html.encode())
            elif p == "/overlay.png":
                ok, buf = cv2.imencode(".png", cv2.cvtColor(studio.overlay(), cv2.COLOR_RGB2BGR))
                self.send_response(200); self.send_header("Content-Type", "image/png"); self.end_headers()
                self.wfile.write(buf.tobytes())
            elif p == "/candidates":
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(json.dumps(studio.candidates()).encode())
            else:
                self.send_response(404); self.end_headers()
        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
            action = body.get("action")
            try:
                if action == "select":
                    studio.selected = int(body["id"]) if body.get("id") else None
                elif action == "accept":
                    studio.model.set_metadata(int(body["id"]), {"classe": "indeterminada", "confianca": "media", "oclusao": "nao", "estado": "revisada_leiga"})
                elif action == "exclude":
                    studio.model.set_metadata(int(body["id"]), {"classe": "excluir", "confianca": "alta", "oclusao": "desconhecida", "estado": "revisada_leiga"})
                elif action == "sphere":
                    studio.model.set_metadata(int(body["id"]), {"classe": "esfera_11mm_confirmada_sem_medicao", "confianca": "alta", "oclusao": "nao", "estado": "revisada_leiga"})
                elif action == "export":
                    out = export_review(studio.model, studio.reviewer)
                    self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                    self.wfile.write(json.dumps({"ok": True, "path": str(out)}).encode()); return
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(json.dumps({"ok": True}).encode())
            except Exception as exc:
                self.send_response(400); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(json.dumps({"ok": False, "error": str(exc)}).encode())
    return H


INDEX_HTML = """<!DOCTYPE html>
<html lang="pt-BR"><head><meta charset="utf-8"><title>PelotaVision — Revisão Visual</title>
<style>body{font-family:system-ui;margin:0;background:#1e1e1e;color:#eee}
header{padding:12px 16px;background:#2d2d2d;border-bottom:1px solid #444}
main{display:flex;height:calc(100vh - 50px)}
#view{flex:1;overflow:auto;background:#111}
#view img{max-width:100%;display:block}
aside{width:320px;padding:12px;background:#252525;overflow:auto}
button{display:block;width:100%;margin:6px 0;padding:8px;background:#3a3a3a;color:#fff;border:1px solid #555;cursor:pointer}
button:hover{background:#4a4a4a}
.cand{padding:6px;border-bottom:1px solid #333;cursor:pointer}
.cand:hover{background:#333}
.warn{color:#f90;font-size:13px}
</style></head><body>
<header><b>PelotaVision — Revisão Visual</b> · Revisor: {{REVIEWER}} · <span class="warn">Revisão leiga, não é ground truth</span></header>
<main>
  <div id="view"><img id="ov" src="/overlay.png" alt="overlay"></div>
  <aside>
    <div class="warn">Clique um candidato. Decisões não sobrescrevem originais.</div>
    <div id="cands"></div>
    <hr>
    <button onclick="act('accept')">Aceitar</button>
    <button onclick="act('exclude')">Excluir (fundo/recipiente)</button>
    <button onclick="act('sphere')">Esfera 11 mm</button>
    <button onclick="act('export')">Exportar revisão</button>
    <div id="msg" style="margin-top:10px;font-size:13px"></div>
  </aside>
</main>
<script>
let sel=null;
async function load(){const r=await fetch('/candidates');const d=await r.json();
  document.getElementById('cands').innerHTML=d.map(c=>`<div class="cand" onclick="select(${c.id})">#${c.id} · ${c.classe} · ${c.estado}</div>`).join('');}
async function select(id){sel=id;await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'select',id})});
  document.getElementById('ov').src='/overlay.png?'+Date.now();}
async function act(a){if(!sel&&a!=='export'){alert('Selecione um candidato');return;}
  const r=await fetch('/',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:a,id:sel})});
  const d=await r.json();document.getElementById('msg').textContent=d.ok?(d.path||'ok'):d.error;load();}
load();
</script></body></html>"""


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--folder", type=Path, required=True, help="Pasta candidata com record.json e instances_uint16.png")
    p.add_argument("--revisor", required=True)
    p.add_argument("--port", type=int, default=8765)
    args = p.parse_args()
    model = load_candidate(args.folder)
    studio = WebReview(model, args.revisor)
    httpd = HTTPServer(("127.0.0.1", args.port), make_handler(studio))
    print(f"Studio em http://127.0.0.1:{args.port}  (revisor={args.revisor})")
    print("Revisão leiga. Não é ground truth. Ctrl+C para encerrar.")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
