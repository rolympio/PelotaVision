# Validação metrológica — esfera de 11 mm, borda e oclusão

## Evidência de 30/10/2026

A mesma fotografia gerou 6,9648 px/mm pela esfera de 11 mm e 4,4240 px/mm pela borda de recipiente de 250 mm. A razão das escalas é 1,5743. A diferença de diâmetros não demonstra por si só qual contorno foi detectado incorretamente. O ajuste Rosin–Rammler não é critério de qualidade metrológica.

## Regras de segurança implementadas na branch

- `SphereCalibrationAssistant.robustPixelsPerMm`: mediana e rejeição MAD, somente para esferas **confirmadas**.
- `validatedPelletIndices`: retira esferas confirmadas e detecções parciais/inconclusivas, sem selecionar por aderência à curva granulométrica.
- `ContainerCalibration.calibrateLevel`: confronta escala da borda e das esferas na mesma imagem; NÃO infere altura física nem fornece fator universal para outras fotos.
- `PhotoSession.setConfirmedSphereIndices` e `setValidatedOnly`: APIs de integração Windows, ainda sem controle visual de confirmação.

## Restrições que devem permanecer

Não identificar esferas apenas por circularidade: pelotas também são circulares. A cor branca, a geometria, o diâmetro consistente e a confirmação do operador são evidências complementares. O núcleo branco saturado pode ser menor que o contorno físico da esfera. Uma fotografia monocular sem geometria conhecida não mede a altura absoluta do leito.

## Critérios de aceitação para distribuição

1. Confirmar visualmente todas as esferas de referência, inclusive rejeições e falsos positivos.
2. Excluir todas as esferas confirmadas de contagem, trincas, histograma, esfericidade e exportações.
3. Comparar D10, D50, D90, número de pelotas e histograma bruto/validado com ensaio físico.
4. Rejeitar oclusões por evidência independente da curva Rosin–Rammler; contabilizar rejeições por motivo.
5. Verificar Android e Windows com as mesmas imagens, ROI, escala, contagem e relatório PDF/CSV/JSON.
6. Executar testes Java, Android, Windows, regressões dos classificadores e compilação APK/EXE.
7. Validar imagens com alturas de enchimento, distâncias de câmera e iluminações diferentes.
8. Não liberar a correção de borda como calibração automática sem referência local validada.

**Status:** implementação parcial; não representa release nem validação industrial.
