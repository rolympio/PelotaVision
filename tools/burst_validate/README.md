# Validação da rajada (0.19.0)

O recurso só vale depois de medido. Aqui ficam as ferramentas dos ensaios do prompt master (§5) e o
experimento de origem.

| Arquivo | Para quê |
|---|---|
| `../../RAJADA.bat` → `rajada.ps1` → `BurstCli` | Laudo + empilhada (+ SR 2×) de uma pasta de fotos, sem interface |
| `simular_rajada.py` | Rajada simulada a partir de uma foto real, com offsets e verdade conhecidos (ensaio C e software) |
| `avaliar_rajada.py` | Compara a saída do `BurstCli` com a verdade da simulação (offsets, PSNR) |
| `ensaio_a_repetibilidade.py` | Resume exportações do app por modo: contagem/CV, D10/D50/D90, CV de D50, erro contra referência |
| `pesquisa/` | Código e dados do experimento de 20/11 (`burst_sr_demo.py`, `burst_sr_probe.py`, JSON, figuras) |

Dependências Python: `numpy` e `opencv-python`, as mesmas dos outros scripts de `tools/`.

## Ensaio A — repetibilidade em cena parada (decide o recurso)
1. Monte a câmera como no uso real: mesmo suporte, mesma luz. No Windows, trave foco e exposição.
2. Separe a amostra. Meça ≥ 30 pelotas marcadas no paquímetro ou passe a amostra na peneira de
   referência, e anote o D50 de referência.
3. Faça **20 rajadas** de 20 fotos da mesma amostra, sem mexer nela. Use o diálogo Rajada → "Escolher
   fotos" ou `RAJADA.bat`.
4. Para N = 1, 4, 10 e 20, analise no app com o mesmo perfil, escala e ROI e exporte. N = 1 é a foto
   base sem tratamento. Separe as exportações em `RAIZ/sem_tratamento`, `RAIZ/mediana_n4`,
   `RAIZ/mediana_n10`, `RAIZ/mediana_n20`.
5. `python tools/burst_validate/ensaio_a_repetibilidade.py --raiz RAIZ --d50-referencia <mm> --saida resumo.csv`
6. **Aceite:** −20% no erro de diâmetro **ou** −30% no CV de D50 contra `sem_tratamento`, com
   `fracao_revisar` sem aumento. Se não passar, registre isso em `docs/rajada-burst.md` e a rajada fica
   como opção manual.

## Ensaio B — sensibilidade ao tremor
Repita o A, com N = 10, em três montagens:
- (i) tripé rígido;
- (ii) mão apoiada;
- (iii) deslocamento deliberado da base em passos de ~⅓ a ½ px, com calço ou parafuso micrométrico.

Anote no laudo de cada rajada o tremor p90, a cobertura 2× e o veredito. Se (iii) for viável na
montagem, ele vira a recomendação de produto para quem quiser a SR 2×.

## Ensaio C — custo do erro de registro (software)
```bash
python tools/burst_validate/simular_rajada.py --foto ml_training/data_belt/raw/20170619_09-52-13.jpg \
       --saida /tmp/sim --n 12 --tremor 0.03 --ruido 10 --grade
RAJADA.bat --saida /tmp/res --sr --parada /tmp/sim/quadros        # Windows
python tools/burst_validate/avaliar_rajada.py --simulacao /tmp/sim --resultado /tmp/res
```
- `--forcar-sr` roda a SR mesmo quando o laudo nega. O JSON registra `forced_for_testing`. Serve para
  ver o que se perderia.
- Resultados da 0.19.0 em três fotos do repositório: `verification/burst-0.19.0/`.

## Observações
- O ruído do laudo é medido no **cinza (luma)**. Ruído independente por canal aparece ~0,67× menor no
  cinza (pesos 0,299/0,587/0,114). O limite de 10 níveis da SR é em luma.
- Saídas grandes e fotos de ensaio ficam em `..\PelotaVision-local\results\`, fora do Git.
