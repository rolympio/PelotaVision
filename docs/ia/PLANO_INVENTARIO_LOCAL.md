# Plano e ferramenta de inventário das fotografias locais

## Fonte e preservação

Fonte atual: `C:\Users\rolym\OneDrive\Documentos\PelotaVision`.
Há fotos `*_original.png/jpeg` acompanhadas de saídas do app. São **candidatos a
originais exportados**, não prova de arquivo bruto do sensor. Não repetir a
afirmação anterior de que não existem originais.

`SINCRONIZAR_DADOS.bat` faz leitura recursiva e grava um manifesto **privado** fora
do Git. ZIP/7Z não são extraídos automaticamente; links de arquivos/diretórios
não são seguidos. OneDrive pode precisar baixar arquivos sob demanda ao lê-los.

## Campos disponíveis agora

- Caminho relativo e SHA-256 por arquivo; ID de conteúdo `sha256:<hash>`.
- Tamanho, papel do arquivo (original exportado, visualização derivada, desconhecido).
- PNG/JPEG: resolução, profundidade por amostra, canais e formato lidos do cabeçalho.
- EXIF PNG/JPEG: presença e subconjunto IFD0 (fabricante, modelo, orientação e data).
  Sem GPS/número de série. Não é extração completa de EXIF ou validação de decodificação.
- Grupos de duplicatas **exatas** por SHA-256; originais duplicados não são removidos.
- Pasta como hipótese de aquisição, marcada **não confirmada**; amostra física desconhecida.
- Erros por arquivo explícitos, retorno não zero em erro e bloqueio de treino desses arquivos.

TIFF/BMP/WebP entram no inventário/hash, mas seus metadados ficam pendentes.
Condições ópticas, iluminação, sobreposição, referência de 11 mm e contagem estimada
ficam `null` até inspeção real. Elegibilidade de treino fica pendente; nenhuma
classe de integridade é inferida pelo nome ou pelo JSON do classificador.

## Etapas seguintes

1. Revisar originais e confirmar captura bruta versus exportada; imagens `_resultado`,
   `_mapa` e `_filtrada` não podem entrar como fotos originais de treinamento.
2. Comparar perceptualmente quase duplicatas e recapturas; SHA-256 não detecta isso.
3. Confirmar aquisição e **amostra física** com o responsável. Pastas diferentes não
   garantem amostras independentes; remexer pelotas não cria novo material.
4. Preencher qualidade óptica, referências de 11 mm e oclusão sem inventar rótulos.
5. Criar plano de divisão por componente conectado de duplicação/aquisição/amostra,
   deixando imagens relacionadas juntas. Não atribuir treino/teste automaticamente agora.
6. Só então gerar máscaras candidatas por métodos independentes, mantendo proveniência,
   concordância e incerteza; imagens e pseudo-rótulos não devem ser enviados ao GitHub.

## Aceite do inventário

Manifesto legível, erros explícitos, fontes intactas, arquivos privados fora do Git,
copias opcionais verificadas por hash, nenhuma divisão automática sem grupos
confirmados e nenhuma classificação científica inventada. Isso não equivale a
dataset anotado nem a treinamento concluído.
