"""Editor local de propostas do pipeline; revisão leiga não é ground truth."""
from __future__ import annotations

import argparse
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np

from .pipeline import PRIVATE, boundary, read_image, sha256, write_image

KINDS = ("indeterminada", "objeto_nao_identificado", "esfera_11mm_confirmada_sem_medicao",
         "segmentacao_ambigua", "ignorar", "excluir")
CONFIDENCE = ("desconhecida", "baixa", "media", "alta")
OCCLUSION = ("desconhecida", "sim", "nao")
STATES = ("pendente", "revisada_leiga")
WARNING = ("Revisao leiga, nao especialista e nao ground truth. Ausencia de trinca "
           "nao demonstra integridade estrutural. Esfera 11mm e declaracao humana, nao medicao.")


def defaults(origin):
    return {"classe": "indeterminada", "confianca": "desconhecida",
            "oclusao": "desconhecida", "estado": "pendente", "origem": origin,
            "integridade_estrutural": "nao_avaliada"}


def checked_metadata(values, origin):
    result = defaults(origin)
    for key, allowed in (("classe", KINDS), ("confianca", CONFIDENCE),
                         ("oclusao", OCCLUSION), ("estado", STATES)):
        value = values.get(key)
        if value not in allowed:
            raise ValueError(f"Valor inválido para {key}: {value}")
        result[key] = value
    return result


def stroke_mask(shape, points, radius):
    if not points or not 1 <= int(radius) <= 500:
        raise ValueError("Traço vazio ou raio inválido")
    h, w = shape
    pts = [(int(x), int(y)) for x, y in points]
    if any(not (0 <= x < w and 0 <= y < h) for x, y in pts):
        raise ValueError("Coordenada fora da grade")
    mask = np.zeros(shape, np.uint8)
    for p in pts:
        cv2.circle(mask, p, int(radius), 1, -1)
    for a, b in zip(pts, pts[1:]):
        cv2.line(mask, a, b, 1, 2 * int(radius) + 1)
    return mask.astype(bool)


class Review:
    def __init__(self, gray, labels, source_hashes=None, record=None, candidate=None):
        if gray.ndim != 2 or gray.dtype != np.uint8 or labels.shape != gray.shape or labels.dtype != np.uint16:
            raise ValueError("Imagem deve ser uint8 e instâncias uint16 na mesma grade 2D")
        self.gray, self.labels = gray.copy(), labels.copy()
        self.background = np.zeros(gray.shape, np.uint8)
        self.cracks = np.zeros(gray.shape, np.uint8)
        origin = "pipeline_candidata"
        if record and record.get("candidate_model") and record.get("candidate_method"):
            origin = f"{record['candidate_model']}:{record['candidate_method']}"
        self.meta = {int(i): defaults(origin) for i in np.unique(labels) if i}
        self.history = []
        self.source_hashes = source_hashes or {}
        self.record = record or {}
        self.candidate = Path(candidate).resolve() if candidate else None

    def require_id(self, ident):
        if int(ident) not in self.meta or not np.any(self.labels == ident):
            raise ValueError("Selecione uma instância existente")

    def new_id(self):
        # IDs apagados nunca são reutilizados nesta sessão.
        used = set(self.meta) | set(int(i) for i in np.unique(self.labels))
        for ident in range(1, 65536):
            if ident not in used:
                return ident
        raise ValueError("Limite de IDs uint16 atingido")

    def create(self):
        ident = self.new_id()
        self.meta[ident] = defaults("criada_manualmente")
        self.history.append({"acao": "criar_instancia", "id": ident})
        return ident

    def paint(self, mode, ident, points, radius):
        m = stroke_mask(self.gray.shape, points, radius)
        if mode in ("pintar", "apagar", "trinca", "apagar_trinca"):
            if mode == "pintar" and ident in self.meta:
                pass  # Uma instância nova ainda pode não ter pixels.
            else:
                self.require_id(ident)
        if mode == "pintar":
            m &= (self.labels == 0) | (self.labels == ident)
            self.labels[m] = ident
            self.background[m] = 0
        elif mode == "apagar":
            m &= self.labels == ident
            self.labels[m] = 0
            self.cracks[m] = 0
            # Apagar não é anotação de fundo.
        elif mode == "fundo":
            m &= self.labels == 0
            self.background[m] = 255
        elif mode == "apagar_fundo":
            self.background[m] = 0
        elif mode == "trinca":
            m &= self.labels == ident
            self.cracks[m] = 255
        elif mode == "apagar_trinca":
            m &= self.labels == ident
            self.cracks[m] = 0
        else:
            raise ValueError("Modo desconhecido")
        if mode in ("pintar", "apagar") and m.any():
            self.meta[ident]["estado"] = "pendente"
        self.history.append({"acao": mode, "id": ident, "pontos_xy": points,
                             "raio_px": int(radius), "pixels_afetados": int(m.sum())})

    def split(self, ident, points, radius):
        self.require_id(ident)
        cut = stroke_mask(self.gray.shape, points, radius) & (self.labels == ident)
        if cut.sum() == 0:
            raise ValueError("O traço não corta a instância selecionada")
        remaining = ((self.labels == ident) & ~cut).astype(np.uint8)
        count, components = cv2.connectedComponents(remaining, connectivity=8)
        if count < 3:
            raise ValueError("O traço deve deixar pelo menos uma região não cortada")
        new = self.new_id()
        self.labels[cut] = 0
        self.cracks[cut] = 0
        self.labels[components == 2] = new
        self.meta[ident] = defaults(f"separada_de_{ident}")
        self.meta[new] = defaults(f"separada_de_{ident}")
        self.history.append({"acao": "separar", "id": ident, "novo_id": new,
                             "pontos_xy": points, "raio_px": int(radius), "pixels_corte": int(cut.sum())})
        return new

    def merge(self, target, other):
        self.require_id(target)
        self.require_id(other)
        if target == other:
            raise ValueError("Selecione duas instâncias diferentes")
        self.labels[self.labels == other] = target
        self.meta[target] = defaults(f"uniao_{target}_{other}")
        self.history.append({"acao": "unir", "id": target, "id_absorvido": other})

    def set_metadata(self, ident, values):
        self.require_id(ident)
        self.meta[ident] = checked_metadata(values, self.meta[ident]["origem"])
        self.history.append({"acao": "atributos", "id": ident, "valores": dict(self.meta[ident])})


def load_candidate(folder):
    folder = Path(folder).resolve(strict=True)
    paths = {name: folder / name for name in ("record.json", "instances_uint16.png", "training_dev.npz")}
    hashes = {name: sha256(path) for name, path in paths.items()}
    with paths["record.json"].open(encoding="utf-8") as f:
        record = json.load(f)
    labels = read_image(paths["instances_uint16.png"], cv2.IMREAD_UNCHANGED)
    # Somente o canal gray; nunca carregar objetos pickle ou alvos/pesos heurísticos.
    with np.load(paths["training_dev.npz"], allow_pickle=False) as data:
        gray = data["gray"].copy()
    if hashes != {name: sha256(path) for name, path in paths.items()}:
        raise ValueError("Candidata mudou durante a leitura")
    if list(gray.shape) != record.get("mask_shape_hw"):
        raise ValueError("Grade não corresponde ao record.json")
    result = Review(gray, labels, hashes, record, folder)
    result._output_root = Path(PRIVATE).resolve(strict=True)
    return result


def export_review(review, reviewer, output_root=None):
    reviewer = reviewer.strip()
    if not reviewer:
        raise ValueError("Informe o revisor")
    if output_root is None:
        output_root = getattr(review, '_output_root', None) or PRIVATE
    root = Path(output_root).resolve(strict=True)
    private_root = Path(PRIVATE).resolve(strict=True)
    if not root.is_relative_to(private_root):
        raise ValueError("Saída deve ficar na raiz privada autorizada")
    if review.candidate is None or not review.source_hashes:
        raise ValueError("Candidata e hashes de origem obrigatórios")
    if root.is_relative_to(review.candidate):
        raise ValueError("Saída não pode ficar dentro da candidata")
    if any(sha256(review.candidate / name) != digest for name, digest in review.source_hashes.items()):
        raise ValueError("Candidata mudou desde a abertura; exportação recusada")
    if np.any((review.background > 0) & (review.labels > 0)) or np.any((review.cracks > 0) & (review.labels == 0)):
        raise ValueError("Máscaras inconsistentes")
    out = root / f"revisao-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:12]}"
    out.mkdir(parents=True, exist_ok=False)
    for name, image in (("imagem_cinza.png", review.gray), ("instancias_uint16.png", review.labels),
                        ("fundo_humano_uint8.png", review.background), ("trinca_humana_uint8.png", review.cracks)):
        write_image(out / name, image)
    if any(sha256(review.candidate / name) != digest for name, digest in review.source_hashes.items()):
        raise ValueError("Candidata mudou durante a exportação; pasta incompleta não é revisão válida")
    instances = []
    for ident in (int(i) for i in np.unique(review.labels) if i):
        mask = (review.labels == ident).astype(np.uint8)
        contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        instances.append({"id": ident, "area_px": int(mask.sum()),
                          "bbox_xywh": list(map(int, cv2.boundingRect(mask))),
                          "contornos_xy": [p.reshape(-1, 2).tolist() for p in contours],
                          **review.meta[ident]})
    manifest = {"schema": "pelotavision.studio.review.v1", "aviso": WARNING,
                "criado_utc": datetime.now(timezone.utc).isoformat(), "revisor": reviewer,
                "estado_revisao": "leiga_nao_ground_truth", "candidata": str(review.candidate),
                "hashes_entrada_sha256": review.source_hashes,
                "codigo_review_sha256": sha256(Path(__file__)),
                "origem_pipeline": {k: review.record.get(k) for k in
                                     ("source", "source_sha256", "source_shape_hw", "mask_shape_hw", "transform",
                                      "candidate_model", "candidate_method", "watershed_record_sha256")},
                "proveniencia_fila": {k: review.record.get(k) for k in
                                      ("queue_candidate", "queue_source", "queue_input_hashes", "roi_sha256")},
                "excluir_granulometria_ids": [i for i in review.meta if np.any(review.labels == i)
                    and review.meta[i]['classe'] in ('esfera_11mm_confirmada_sem_medicao', 'ignorar', 'excluir')],
                "calibracao_fisica": "nao_derivada_da_classificacao; exige registro rastreavel separado",
                "coordenadas": "xy em pixels da grade processada; origem superior esquerda",
                "classes_instancia": list(KINDS), "zero_instancia": "sem proposta; nao significa fundo",
                "fundo": "255 somente pincel humano; 0 desconhecido ou objeto; nao derivado do pipeline",
                "trinca": "255 somente pincel humano; 0 nao anotado, nao significa ausencia",
                "supervisao": "nenhum peso ou alvo de treino e produzido automaticamente",
                "instancias": instances, "historico": review.history,
                "hashes_artefatos_sha256": {p.name: sha256(p) for p in sorted(out.iterdir()) if p.is_file()}}
    with (out / "revisao.json").open("x", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2, allow_nan=False)
    with (out / "hashes.json").open("x", encoding="utf-8") as f:
        json.dump({"revisao.json": sha256(out / "revisao.json"), **manifest["hashes_artefatos_sha256"]},
                  f, ensure_ascii=False, indent=2)
    return out


class Editor:
    def __init__(self, model, reviewer, output_root):
        import tkinter as tk
        from tkinter import messagebox, ttk
        self.tk, self.messagebox, self.model = tk, messagebox, model
        self.reviewer, self.output_root = reviewer, output_root
        self.root = tk.Tk()
        self.root.title("PelotaVision — revisão leiga local")
        self.zoom = 1.0
        self.selected = None
        self.points = []
        self.mode = tk.StringVar(value="selecionar")
        self.radius = tk.IntVar(value=3)
        self.status = tk.StringVar(value="Clique para selecionar. Roda: zoom; botão central: mover. Sem ground truth.")
        panel = ttk.Frame(self.root, padding=4)
        panel.pack(side="left", fill="y")
        ttk.Label(panel, text="Revisão LEIGA; não é ground truth\nFundo só por pincel. Sem medição.", justify="left").pack(anchor="w")
        for mode, text in (("selecionar", "Selecionar"), ("pintar", "Pintar instância"),
                           ("apagar", "Apagar instância"), ("fundo", "Pintar fundo confiável"),
                           ("apagar_fundo", "Apagar fundo"), ("trinca", "Anotar possível trinca"),
                           ("apagar_trinca", "Apagar anotação de trinca"), ("separar", "Separar pelo traço"),
                           ("unir", "Unir com instância clicada")):
            ttk.Radiobutton(panel, text=text, value=mode, variable=self.mode).pack(anchor="w")
        ttk.Label(panel, text="Raio do pincel (pixels da grade)").pack(anchor="w")
        ttk.Spinbox(panel, from_=1, to=100, textvariable=self.radius, width=8).pack(anchor="w")
        ttk.Button(panel, text="Nova instância", command=self.create).pack(fill="x")
        self.fields = {}
        for key, choices in (("classe", KINDS), ("confianca", CONFIDENCE),
                             ("oclusao", OCCLUSION), ("estado", STATES)):
            ttk.Label(panel, text=key).pack(anchor="w")
            var = tk.StringVar(value=choices[0])
            ttk.Combobox(panel, textvariable=var, values=choices, state="readonly", width=35).pack(anchor="w")
            self.fields[key] = var
        ttk.Button(panel, text="Aplicar atributos à seleção", command=self.apply).pack(fill="x")
        ttk.Button(panel, text="Exportar nova revisão", command=self.export).pack(fill="x")
        ttk.Label(panel, textvariable=self.status, wraplength=270).pack(anchor="w")
        frame = ttk.Frame(self.root)
        frame.pack(side="right", fill="both", expand=True)
        self.canvas = tk.Canvas(frame, background="#303030", width=900, height=700)
        xs, ys = ttk.Scrollbar(frame, orient="horizontal", command=self.canvas.xview), ttk.Scrollbar(frame, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(xscrollcommand=xs.set, yscrollcommand=ys.set)
        xs.pack(side="bottom", fill="x"); ys.pack(side="right", fill="y"); self.canvas.pack(fill="both", expand=True)
        self.canvas.bind("<ButtonPress-1>", self.down)
        self.canvas.bind("<B1-Motion>", self.drag)
        self.canvas.bind("<ButtonRelease-1>", self.up)
        self.canvas.bind("<MouseWheel>", self.wheel)
        self.canvas.bind("<ButtonPress-2>", lambda e: self.canvas.scan_mark(e.x, e.y))
        self.canvas.bind("<B2-Motion>", lambda e: self.canvas.scan_dragto(e.x, e.y, gain=1))
        self.render()

    def render(self):
        a = self.model.labels
        rgb = cv2.cvtColor(self.model.gray, cv2.COLOR_GRAY2RGB)
        colors = np.stack([(a.astype(np.uint32)*211) % 255, (a.astype(np.uint32)*139) % 255,
                           (a.astype(np.uint32)*73) % 255], axis=-1).astype(np.uint8)
        present = a > 0
        rgb[present] = (rgb[present].astype(np.uint16)*2//3 + colors[present].astype(np.uint16)//3).astype(np.uint8)
        rgb[self.model.background > 0] = (30, 190, 100)
        rgb[boundary(a) > 0] = (255, 220, 0)
        if self.selected is not None:
            rgb[(boundary(a) > 0) & (a == self.selected)] = (255, 60, 60)
        rgb[self.model.cracks > 0] = (255, 0, 255)
        h, w = a.shape
        size = (max(1, round(w*self.zoom)), max(1, round(h*self.zoom)))
        rgb = cv2.resize(rgb, size, interpolation=cv2.INTER_NEAREST)
        ok, data = cv2.imencode(".ppm", cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
        if not ok:
            raise RuntimeError("Erro ao renderizar")
        self.photo = self.tk.PhotoImage(data=data.tobytes(), format="PPM")
        self.canvas.delete("all")
        self.canvas.create_image(0, 0, anchor="nw", image=self.photo)
        for ident in np.unique(a):
            if not ident: continue
            ys, xs = np.where(a == ident)
            self.canvas.create_text(float(xs.mean())*self.zoom, float(ys.mean())*self.zoom,
                                    text=str(ident), fill="white", font=("Arial", 10, "bold"))
        self.canvas.configure(scrollregion=(0, 0, *size))

    def point(self, event):
        x, y = int(self.canvas.canvasx(event.x)/self.zoom), int(self.canvas.canvasy(event.y)/self.zoom)
        h, w = self.model.gray.shape
        return (x, y) if 0 <= x < w and 0 <= y < h else None

    def down(self, event):
        p = self.point(event)
        if p is None: return
        mode = self.mode.get()
        if mode in ("selecionar", "unir"):
            ident = int(self.model.labels[p[1], p[0]])
            if mode == "unir":
                try:
                    self.model.merge(self.selected, ident)
                    self.select(self.selected)
                except (ValueError, TypeError) as exc: self.messagebox.showerror("União", str(exc))
            else:
                self.select(ident if ident else None)
        else:
            self.points = [p]

    def drag(self, event):
        p = self.point(event)
        if self.points and p is not None and p != self.points[-1]: self.points.append(p)

    def up(self, event):
        if not self.points: return
        p = self.point(event)
        if p is not None and p != self.points[-1]: self.points.append(p)
        try:
            radius = int(self.radius.get())
            if self.mode.get() == "separar":
                self.model.split(self.selected, self.points, radius)
                self.select(self.selected)
            else:
                self.model.paint(self.mode.get(), self.selected, self.points, radius)
            self.render()
        except (ValueError, TypeError, self.tk.TclError) as exc:
            self.messagebox.showerror("Traço", str(exc))
        finally: self.points = []

    def wheel(self, event):
        self.zoom = max(.25, min(6., self.zoom * (1.25 if event.delta > 0 else .8)))
        self.render()

    def select(self, ident):
        self.selected = ident
        if ident is not None:
            for key, var in self.fields.items(): var.set(self.model.meta[ident][key])
        self.status.set(f"Selecionada: {ident}. Aplique atributos antes de exportar. Zero não é fundo.")
        self.render()

    def create(self):
        try:
            ident = self.model.create()
            self.select(ident)
            self.mode.set("pintar")
        except ValueError as exc: self.messagebox.showerror("Criar", str(exc))

    def apply(self):
        try:
            self.model.set_metadata(self.selected, {key: var.get() for key, var in self.fields.items()})
            self.status.set(f"Atributos aplicados à instância {self.selected}.")
        except (ValueError, TypeError) as exc: self.messagebox.showerror("Atributos", str(exc))

    def export(self):
        try:
            out = export_review(self.model, self.reviewer, self.output_root)
            self.messagebox.showinfo("Revisão salva", f"Pasta privada criada:\n{out}\nAtributos só são salvos após Aplicar.")
        except (ValueError, OSError) as exc: self.messagebox.showerror("Exportação recusada", str(exc))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidata", type=Path, help="Pasta image-* do pipeline ou subpasta grid/stardist_centers do SAM")
    parser.add_argument("--revisor", required=True, help="Identificador local do revisor")
    parser.add_argument("--output-root", type=Path, default=PRIVATE)
    args = parser.parse_args()
    if not args.revisor.strip(): parser.error("--revisor não pode estar vazio")
    editor = Editor(load_candidate(args.candidata), args.revisor, args.output_root)
    editor.root.mainloop()


if __name__ == "__main__":
    main()
