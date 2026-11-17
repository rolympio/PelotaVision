# PelotaVision 0.18.0 (Android) / 0.10.0 (Windows) — relatório de laudo e ROI automática

Branch: `versao/0.18.0-relatorio-roi-auto` (base: `feature/rede-borda-roboflow-agente-local`).

## O que muda

### Relatório PDF em formato de laudo
- Cabeçalho da **Diretoria de Pelotização · Gerência Enga. Processo e Sustentabilidade · Planta Piloto da Pelotização**,
  título "Relatório de Análise Granulométrica de Pelotas por Imagem".
- **Página 1:**
  - identificação da amostra;
  - 6 indicadores: pelotas, diâmetro médio, D50, D10–D90 com span, possíveis trincas, esfericidade 2D;
  - imagem original e imagem segmentada lado a lado;
  - tabela de classes;
  - histograma vetorial (todas × trincadas) com D10, D50 e D90.
- **Página 2:**
  - mapa granulométrico e imagem tratada;
  - curva acumulada com o ajuste Rosin–Rammler;
  - estatísticas e tabela Rosin–Rammler nas bases número e massa estimada;
  - triagem de trincas com recortes, encaixada no espaço livre.
- **Última página — rastreabilidade:**
  - documento e programa, imagem e captura;
  - tratamento de iluminação, calibração;
  - região de interesse, incluindo se foi automática;
  - localização/segmentação com SHA-256 dos modelos, refinamento SAM 2, triagem de trincas, distribuição e forma;
  - limitações.
- Layout decidido no núcleo (`ReportLayout`, métricas Helvetica em `ReportFont`): Windows (PDFBox) e Android (Canvas)
  desenham as mesmas páginas. O exemplo real de 11/11 passou de 8 para 3 páginas.

### ROI automática (`AutoRoi`, núcleo)
Identifica sozinha onde estão as pelotas:

| Tipo | Como reconhece | Região |
|---|---|---|
| Recipiente redondo | aro (`ContainerRimDetector`) + nível das pelotas | círculo recuado da parede |
| Recipiente retangular / bandeja | mancha de material com preenchimento retangular | maior retângulo ≥97% material |
| Correia: trecho carregado | material atravessando o quadro + bordas da correia | só a faixa carregada; correia vazia fica fora |
| Campo cheio | textura de pelotas no quadro inteiro | quadro com margem de 2% |

- Separa material de fundo pela **densidade de bordas isotrópica**: pelotas têm bordas em todas as direções.
  Parede de bandeja, borda de correia e aro são linhas de direção única e não entram.
- Na foto, a ROI é aplicada **antes** da primeira análise, quando a confiança é ≥55%. O operador pode ajustar em ROI/Área.
- No ao vivo, a ROI é refeita a cada ~1 s e **estabilizada**: média exponencial, e troca de tipo só depois de 3 leituras
  concordantes. A foto capturada herda a ROI.
- O JSON registra em `region_of_interest`:
  - `automatic`: true só se a região usada foi a proposta sem edição;
  - `detected_as` / `detected_label`;
  - `auto_confidence`;
  - `auto_reason`.
- Opção "ROI automática" (Windows, painel Segmentação) e "Área automática" (Android, Ajustes), ligadas por padrão.
- Validação:
  - cenas sintéticas (`AutoRoiTest`);
  - foto real de 16/11: raio igual ao da ROI desenhada pelo operador (489 px), centro a ~30 px.

## Compilar localmente (PowerShell)

```powershell
cd D:\Projetos\PelotaVision
git fetch origin
git checkout versao/0.18.0-relatorio-roi-auto
git pull
.\COMPILAR_PELOTAVISION.bat                     # testes + JAR Windows 0.10.0
.\COMPILAR_PELOTAVISION.bat -Platform Android   # testes, lint e APK debug 0.18.0
# ou tudo de uma vez (SAM 2 já exportado na 0.17.0 é reaproveitado):
.\COMPILAR_PELOTAVISION_COMPLETO.bat
```

Não usar `tools\windows\compilar_tudo.bat`.

### O que conferir na primeira execução
1. Abrir uma foto de recipiente sem ROI e confirmar na barra de status, por exemplo
   "ROI automática: recipiente redondo · confiança 87%".
2. Gerar o PDF e conferir:
   - 3 a 5 páginas;
   - a última página é a de rastreabilidade;
   - a linha "Detectada como" aparece em Região de interesse.
3. No ao vivo, apontar para a correia vazia e depois carregada. A área deve acompanhar o trecho carregado sem "pular".
4. Android: os testes Robolectric (`CrackExportTest`, `ManualCapturesTest`) desenham o PDF novo em bitmaps de
   `build/validation/pdf-0.15.x/`. Abrir essas imagens e conferir o layout.
