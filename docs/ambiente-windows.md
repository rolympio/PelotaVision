# Ambiente Windows e pareamento seguro

## Estrutura

```text
%USERPROFILE%\source\repos\
  PelotaVision\          # Git oficial: codigo, modelos aprovados, docs, skills
  PelotaVision-local\    # privado, fora do Git e fora do OneDrive
    backups\            # bundles Git anteriores a atualizacoes
    datasets\           # manifestos e copias explicitamente solicitadas
    logs\               # transcripts e hashes dos builds
    models\             # experimentos locais, nao publicados automaticamente
    results\            # pacotes Windows em pastas unicas
```

As fotos permanecem no diretório original do OneDrive. Cópias/instalações anteriores
em outros discos não são alteradas. O clone local não é uma release instalada.

## Primeiro uso

Pré-requisitos: Windows PowerShell 5.1, Git for Windows autenticado no repositório
privado e JDK **17 completo**, com `JAVA_HOME`. Não requer administrador.

Para outro computador, escolher uma pasta **inexistente**, fora do OneDrive:

```powershell
git clone https://github.com/rolympio/PelotaVision.git "$env:USERPROFILE\source\repos\PelotaVision"
```

Se houver erro de acesso, autenticar-se pelo Git Credential Manager no terminal
do usuário e repetir. Para terceiro sem permissão: o proprietário deve convidar
a conta em **Settings → Collaborators**. Não enviar tokens pelo chat. Neste
computador, `git ls-remote` e o clone autenticado já funcionaram; convite adicional
não é necessário para leitura. Permissão de escrita não foi testada por push.

Copiar `.pelotavision.local.json.example` para `.pelotavision.local.json` e ajustar
os caminhos. O arquivo pessoal é ignorado pelo Git. Alternativamente passar
`-LocalRoot`, `-DataSource` e `-OpenCvJava` a cada comando.

OpenCV: usar Java **4.12.0** do pacote oficial (Apache-2.0), contendo
`opencv-4120.jar` e `x64/opencv_java4120.dll`. O script verifica sua presença,
não baixa/executa instaladores nem presume integridade de uma DLL local. Registrar
origem/hash antes de redistribuir. Gradle baixa as dependências já declaradas no
projeto, não instala Python/SDK/GPU nem modifica configurações globais.
Os testes históricos Windows/Android gravam exemplos em `verification/`. O novo script
usa `tools/windows/isolar-testes.gradle` para executar sobre cópias de fixtures
em `desktop/build/fixtures-locais/` ou `app/build/fixtures-locais/`, sem regravar as referências versionadas.

## Atalhos na raiz (duplo clique)

| Atalho | Comportamento |
|---|---|
| `VERIFICAR_AMBIENTE.bat` | Verifica Git/JDK, informa OpenCV e SDK; `-CheckRemote` testa leitura autenticada |
| `INSTALAR_PELOTAVISION.bat` | Prepara pastas, executa testes e gera JAR/dependências Windows |
| `ATUALIZAR_PELOTAVISION.bat` | Só `main` limpa, upstream `origin/main`, sem commits locais; bundle + merge `--ff-only` + testes/build |
| `COMPILAR_PELOTAVISION.bat` | Testes + JAR Windows; `-Platform Android` executa testes, lint e APK debug |
| `COMPILAR_PELOTAVISION_COMPLETO.bat` | SAM 2 (baixa/exporta na 1a vez), testes, pacote Windows e APK Android; ver `docs/compilar-0.17.0-sam2.md` |
| `SINCRONIZAR_DADOS.bat` | Inventário somente leitura; não sincroniza fotos com GitHub |

Opções:

```powershell
.\INSTALAR_PELOTAVISION.bat -PrepareOnly
.\VERIFICAR_AMBIENTE.bat -CheckRemote
.\COMPILAR_PELOTAVISION.bat -OpenCvJava "E:\PelotaVision\build\opencv-4.12.0\opencv\build\java"
.\COMPILAR_PELOTAVISION.bat -Package
.\COMPILAR_PELOTAVISION.bat -Platform Android
.\SINCRONIZAR_DADOS.bat -CopyData
.\ATUALIZAR_PELOTAVISION.bat -DryRun
```

`-Package` gera uma **nova pasta** com executável via `jpackage` e exige autoteste
`exit=0`; não chama o empacotador legado que apaga `pkg/`. Não gera instalador Inno
nem publica release. APK requer SDK platform 35 e build-tools 35.0.0; chave debug
local não atualiza necessariamente instalações assinadas por outra chave.

`-CopyData` copia somente candidatos `*_original.*`, sem recompressão, usando hash
SHA-256 e verificação da cópia. Saída nova por execução, nunca espelhamento com
exclusões; nada é movido, nada é enviado ao GitHub. Python 3.11+ é necessário só
para o inventário (biblioteca padrão, sem instalação de pacotes).

`-DryRun` valida identidade/caminhos e descreve a ação sem escrever ou baixar;
não comprova compilação, autenticação, disponibilidade de dependências ou avanço
direto remoto. A atualização real revalida tudo após `fetch`.

Retorno: `0` sucesso, `1` falha. Alertas de componentes opcionais não são sucesso
de compilação. Duplo clique mantém a janela aberta; automação deve definir
`PV_NONINTERACTIVE=1` para não pausar. Logs ficam em `PelotaVision-local/logs/`.

## Atualização com um clique e trabalho de desenvolvimento

A atualização destina-se a uma cópia operacional da **main**. Na branch de
desenvolvimento, ela recusa corretamente atualizar ou trocar branch. Integre
por PR antes de usar o atalho na cópia operacional. Não oculta alterações com
stash nem publica commits automaticamente.

Quando há alterações locais: criar uma branch/commit e revisar arquivos novos
antes de qualquer atualização. Nunca `git add .` em diretório com fotos/dados
privados. Publicação local → GitHub deve selecionar arquivos, revisar diff,
commit, push de branch e PR separadamente.

## Backup e recuperação

Antes do merge, o script cria e verifica `backups/antes-atualizar-*.bundle`.
O bundle guarda histórico/refs Git; não inclui arquivos ignorados ou dados.
Sem árvore limpa, não atualiza. Se testes falharem **após** o avanço direto,
o novo HEAD permanece para diagnóstico; o erro não vira sucesso e não há rollback
destrutivo. Recuperar em **outra pasta**:

```powershell
git clone "C:\caminho\antes-atualizar-ID.bundle" "C:\recuperacao\PelotaVision-ID"
```

A cópia recuperada aponta para o bundle; configurar e verificar o remoto oficial
antes de usá-la com estes atalhos. Backups em outro disco são recomendados;
esta estrutura não substitui backup físico nem controla retenção automaticamente.

## Certificados

`UNABLE_TO_GET_ISSUER_CERT_LOCALLY` significa que um cliente não conseguiu montar
a cadeia de confiança TLS. É diferente de permissão GitHub/404. Em rede corporativa,
obter a CA/intermediária com a TI e configurar o trust store apropriado ao cliente
(Git, Node/OpenCode ou Java). Nunca `http.sslVerify=false`, `curl -k` ou
`NODE_TLS_REJECT_UNAUTHORIZED=0`. Não foi necessário desativar TLS neste clone.

## Testes da infraestrutura

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/windows/test_seguranca.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/windows/test_integracao.ps1
python3 -m unittest discover -s tools/dataset -p "test_*.py" -v
```

Esses testes não substituem testes científicos do Gradle nem testes num Android real.
`ExecutionPolicy Bypass` aplica-se somente ao processo invocado, não altera a
política global do computador nem substitui a revisão do conteúdo dos scripts.
