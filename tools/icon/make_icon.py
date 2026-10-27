"""Ícone do PelotaVision a partir de UMA geometria (viewport 108×108):
pelotas de minério vistas pelo visor da câmera, a da frente contornada pela elipse de detecção (turquesa do app)
e com uma trinca fina. Gera: vetor Android (ic_app.xml), SVG de referência, .ico do Windows (16–256) e PNG da janela.
Uso: python3 tools/icon/make_icon.py  (na raiz do repositório)"""
from PIL import Image, ImageDraw
import os

NAVY, TEAL, WHITE = "#101A23", "#52D8BE", "#FFFFFF"
# (cx, cy, r, base, sombra, brilho) — leito irregular de pelotas de tamanhos diferentes (assimétrico de propósito);
# a da frente (última) é a detectada, contornada pela elipse turquesa e com uma trinca fina.
PELLETS = [
    (35, 36, 9.5, "#5E4F46", "#483C35", "#7C6C62"),
    (54, 34, 10, "#564840", "#433831", "#74665C"),
    (73, 37, 9, "#5A4C43", "#463A33", "#786A60"),
    (28, 56, 8.5, "#54473F", "#413730", "#726459"),
    (45, 54, 10.5, "#5C4E45", "#473B34", "#7A6B61"),
    (65, 56, 9, "#52453D", "#40362F", "#706258"),
    (82, 57, 8, "#5A4C43", "#463A33", "#786A60"),
    (33, 76, 10, "#564840", "#433831", "#74665C"),
    (51, 75, 9, "#5E4F46", "#483C35", "#7C6C62"),
    (70, 73, 12.5, "#8A7568", "#6E5C51", "#AE9A8F"),
]
RING = (70, 73, 16.5, 15.5, 3.2)          # elipse de detecção: cx, cy, rx, ry, traço
CRACK = [(65, 68), (69, 72), (67, 76), (73, 80)]
CORNERS = "M16,32V16h16M76,16h16v16M92,76v16h-16M32,92h-16v-16"
CORNER_SEGS = [((16, 32), (16, 16), (32, 16)), ((76, 16), (92, 16), (92, 32)), ((92, 76), (92, 92), (76, 92)), ((32, 92), (16, 92), (16, 76))]
RADIUS = 22  # cantos arredondados do fundo (Windows); no Android o launcher aplica a própria máscara


def circle_path(cx, cy, r):
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0,1 0,{2 * r:g},0a{r:g},{r:g} 0,1 0,{-2 * r:g},0z"


def ellipse_path(cx, cy, rx, ry):
    return f"M{cx - rx:g},{cy:g}a{rx:g},{ry:g} 0,1 0,{2 * rx:g},0a{rx:g},{ry:g} 0,1 0,{-2 * rx:g},0z"


def android_vector():
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
           '    <!-- Gerado por tools/icon/make_icon.py: pelotas no visor, a da frente com a elipse de detecção. -->',
           f'    <path android:fillColor="{NAVY}" android:pathData="M0,0h108v108h-108z" />',
           f'    <path android:strokeColor="{WHITE}" android:strokeWidth="3" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{CORNERS}" />']
    for cx, cy, r, base, shade, light in PELLETS:
        out.append(f'    <path android:fillColor="{shade}" android:pathData="{circle_path(cx, cy, r)}" />')
        out.append(f'    <path android:fillColor="{base}" android:pathData="{circle_path(cx - r * .08, cy - r * .08, r * .9)}" />')
        out.append(f'    <path android:fillColor="{light}" android:pathData="{circle_path(cx - r * .38, cy - r * .40, r * .28)}" />')
    d = "M" + "L".join(f"{x:g},{y:g}" for x, y in CRACK)
    out.append(f'    <path android:strokeColor="#3A2E27" android:strokeWidth="1.6" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />')
    cx, cy, rx, ry, sw = RING
    out.append(f'    <path android:strokeColor="{TEAL}" android:strokeWidth="{sw:g}" android:fillColor="#00000000" android:pathData="{ellipse_path(cx, cy, rx, ry)}" />')
    out.append('</vector>')
    return "\n".join(out) + "\n"


def svg():
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">',
           f'  <rect width="108" height="108" rx="{RADIUS}" fill="{NAVY}"/>',
           f'  <path d="{CORNERS}" stroke="{WHITE}" stroke-width="3" fill="none" stroke-linecap="round" stroke-linejoin="round"/>']
    for cx, cy, r, base, shade, light in PELLETS:
        out.append(f'  <circle cx="{cx}" cy="{cy}" r="{r}" fill="{shade}"/>')
        out.append(f'  <circle cx="{cx - r * .08:g}" cy="{cy - r * .08:g}" r="{r * .9:g}" fill="{base}"/>')
        out.append(f'  <circle cx="{cx - r * .38:g}" cy="{cy - r * .40:g}" r="{r * .28:g}" fill="{light}"/>')
    out.append('  <polyline points="' + " ".join(f"{x},{y}" for x, y in CRACK) + '" stroke="#3A2E27" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
    cx, cy, rx, ry, sw = RING
    out.append(f'  <ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" stroke="{TEAL}" stroke-width="{sw}" fill="none"/>')
    out.append('</svg>')
    return "\n".join(out) + "\n"


def raster(size, rounded=True):
    S = 2048
    k = S / 108
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    g = ImageDraw.Draw(img)
    if rounded:
        g.rounded_rectangle((0, 0, S - 1, S - 1), radius=int(RADIUS * k), fill=NAVY)
    else:
        g.rectangle((0, 0, S, S), fill=NAVY)
    # Em tamanhos pequenos, traços mais grossos para não sumirem.
    boost = 1.0 if size >= 48 else 1.3
    simple = size <= 24
    w = int(3 * k * boost)
    # Até 24 px (barra de tarefas): sem visor, só a pelota detectada ampliada e duas vizinhas.
    pellets = PELLETS
    ring = RING
    if simple:
        pellets = [(30, 40, 17, *PELLETS[1][3:]), (40, 78, 15, *PELLETS[3][3:]), (64, 60, 26, *PELLETS[-1][3:])]
        ring = (64, 60, 33, 32, 7)
    for seg in ([] if simple else CORNER_SEGS):
        pts = [(x * k, y * k) for x, y in seg]
        g.line(pts, fill=WHITE, width=w, joint="curve")
        for x, y in (pts[0], pts[-1]):
            g.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=WHITE)
    for cx, cy, r, base, shade, light in pellets:
        g.ellipse(((cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k), fill=shade)
        bx, by, br = cx - r * .08, cy - r * .08, r * .9
        g.ellipse(((bx - br) * k, (by - br) * k, (bx + br) * k, (by + br) * k), fill=base)
        lx, ly, lr = cx - r * .38, cy - r * .40, r * .28
        g.ellipse(((lx - lr) * k, (ly - lr) * k, (lx + lr) * k, (ly + lr) * k), fill=light)
    if size >= 48:
        g.line([(x * k, y * k) for x, y in CRACK], fill="#3A2E27", width=int(1.6 * k), joint="curve")
    cx, cy, rx, ry, sw = ring
    g.ellipse(((cx - rx) * k, (cy - ry) * k, (cx + rx) * k, (cy + ry) * k), outline=TEAL, width=int(sw * k * (1 if simple else boost)))
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    root = os.getcwd()
    open(os.path.join(root, "app/src/main/res/drawable/ic_app.xml"), "w", encoding="utf-8").write(android_vector())
    open(os.path.join(root, "tools/icon/pelotavision_icon.svg"), "w", encoding="utf-8").write(svg())
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    imgs = [raster(s) for s in sizes]
    imgs[-1].save(os.path.join(root, "desktop/installer/PelotaVision.ico"), sizes=[(s, s) for s in sizes], append_images=imgs[:-1])
    os.makedirs(os.path.join(root, "desktop/src/main/resources/icon"), exist_ok=True)
    for s in (16, 32, 48, 64, 128, 256):
        raster(s).save(os.path.join(root, f"desktop/src/main/resources/icon/pelotavision_{s}.png"))
    raster(512).save(os.path.join(root, "tools/icon/pelotavision_512.png"))
    print("ok")
