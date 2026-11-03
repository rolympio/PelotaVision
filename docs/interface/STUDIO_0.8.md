# PelotaVision Studio 0.8 — escopo experimental

## Interface e janela

- Cabeçalho e fundo usam a paleta azul-escura do Studio; os controles nativos
  de minimizar, maximizar e fechar são preservados.
- JNA solicita ao Windows DWM a barra de título escura e a cor do fundo do Studio.
  Windows 11 aceita cor de título explícita quando disponível; no Windows 10 o
  DWM oferece apenas modo escuro e a tonalidade exata depende do sistema.
- Painel de resultados acompanha a largura da janela; textos longos de gráficos
  são quebrados e os indicadores passam a permitir rolagem vertical.
- O ensaio de tela também grava captura a 1280×800 para inspeção visual; não
  presume que uma captura automaticamente aprovada substitua teste com operador.

## Nova rede multitarefa

O treinamento exploratório usa pseudo-rótulos de apenas três imagens; veja
`docs/ia/EXPERIMENTO_STUDIO.md` na branch do experimento (PR #29). A quarta imagem
não é teste independente; a rede encontrou oito propostas com concordância fraca.
Portanto, o modelo é oferecido somente como **prévia experimental**, sem modificar
as pelotas clássicas, calibração, histograma, exportações ou relatórios. Não se
afirma que a rede seja mais precisa nem que detecte trincas ou referências de 11 mm.

O arquivo do modelo de teste continua privado, não é incorporado automaticamente
ao Git nem ao pacote. O usuário deve selecioná-lo explicitamente. A versão publicada
como release exige revisão de licença, artefatos versionados, dados independentes,
avaliação de erro dimensional/oclusões e aprovação do responsável.

Na etapa **Análise → Testar IA multitarefa…**, confirmar o aviso e selecionar o
ONNX. O aplicativo verifica o SHA-256, processa em CPU e permite cancelar entre
blocos. Em **Resultados**, selecionar a visualização multitarefa ou o mapa de
entropia não calibrada. A barra de transparência vale para a prévia. Cada nova
imagem invalida a prévia anterior. O processo não depende de Python no executável.

## Itens que continuam pendentes

- Edição livre de contornos e operações de dividir/unir instâncias com rastreabilidade.
- Detecção confiável de múltiplas esferas, incerteza física e paridade Android.
- Comparação independente entre OpenCV, StarDist, U-Net e modelos de instâncias;
  sem imagens com rótulos especializados, métricas entre pseudo-rótulos são
  consistência, não acurácia.
- Distribuição por GitHub Actions depende de liberar cota de armazenamento. Nenhum
  artefato anterior será excluído automaticamente. Release depende de aprovação.
