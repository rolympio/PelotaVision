# Integração de datasets externos — PelotaVision

Branch de trabalho: `feature/external-pellet-datasets`. Issue: #5.

## Fontes candidatas
| Fonte | URL | Uso pretendido | Status |
|---|---|---|---|
| Pellet Size Distribution | https://github.com/aryadeo/Pellet_Size_Distribution | Segmentação e granulometria | Pendente de auditoria de arquivos e licença |
| Iron Pellets (Roboflow) | https://universe.roboflow.com/hamitouche/iron-pellets | Segmentação de instâncias | Pendente de verificação da licença e download |
| Crack Detection of Iron Ore Green Pellets (ISIJ) | https://www.jstage.jst.go.jp/article/isijinternational/62/8/62_ISIJINT-2022-108/_html/-char/en | Trincas em pelotas verdes | Artigo; dataset original não confirmado como disponível |
| Manufacturing Defect Detection | https://github.com/Akki-Maharaj/Manufacturing-Defect-Detection | Experimentos exploratórios de transferência | Não usar para validação de trincas em pelotas |

## Procedimento obrigatório
1. Verificar conteúdo, formato de anotação, licença, atribuição, permissões de redistribuição e hashes de cada origem. Não baixar ou redistribuir dados com licença incerta.
2. Importar somente datasets autorizados para `data/external/<source_id>/` (não versionar arquivos grandes nem material com restrições). Criar manifesto com `source_id, original_url, license, image_sha256, material_type, task, split_group, annotation_status, synthetic`.
3. Normalizar anotações de segmentação (máscaras/boxes) e trincas (`cracked`, `intact`, `uncertain`). Não converter ausência de rótulo em `intact`.
4. Distinguir pelota verde de pelota queimada; não misturar sem testes estratificados.
5. Criar divisão de treino/validação/teste agrupada por foto original, campanha e fonte. Imagens sintéticas não entram no teste final.
6. Treinar e comparar baseline, Random Forest atualizado e modelo neural leve opcional; medir recall de trincas, falsos negativos, precisão, F1, latência e memória em Android e Windows offline.
7. Não alterar o modelo de produção sem evidência de ganho em teste real, revisão e PR. Manter rollback.

## Próximos passos para o agente
- Auditar os links e obter somente dados permitidos.
- Implementar script de ingestão reproduzível e testes automatizados.
- Publicar inventário de imagens efetivamente importadas, quantidades e motivos de exclusão.
- Abrir PR para revisão, sem merge automático.

**Observação:** este arquivo registra o plano de integração. Ele não significa que as imagens já foram baixadas ou incorporadas ao treinamento.
