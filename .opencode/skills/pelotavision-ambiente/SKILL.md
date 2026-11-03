---
name: Ambiente PelotaVision
description: Preparar e verificar ambiente Windows, Git, Gradle e atualizacao segura do PelotaVision.
---

1. Ler `../../../docs/ambiente-windows.md` e `../../../AGENTS.md`.
2. Confirmar raiz Git, origin, branch e estado antes de qualquer mudança.
3. Usar `VERIFICAR_AMBIENTE.bat -CheckRemote` para diagnóstico. Para simulação,
   passar `-DryRun`; não confundir simulação com compilação executada.
4. Atualização só em main limpa, acompanhando origin/main, sem commits locais divergentes.
5. Não usar o script legado destrutivo nem desativar validação de certificados.
6. Registrar comando, código de saída e log, distinguindo Windows/Android não testados.
