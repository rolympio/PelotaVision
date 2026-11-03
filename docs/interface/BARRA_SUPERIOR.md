# Barra superior nativa no Windows

`StudioWindowChrome.apply(janela)` tenta ajustar a decoracao nativa sem retirar
os controles do sistema. Chamar depois de criar o peer, por exemplo, depois de
`pack()` e antes de `setVisible(true)`. A chamada na EDT e imediata; fora dela e
agendada na EDT. Uma janela ainda sem peer ou ja descartada e ignorada. Se o
peer for recriado, e necessario chamar novamente.

O DWM recebe o atributo de modo escuro 20, com fallback para 19. A cor da barra
usa o atributo 35 quando suportado (Windows 11 a partir do build 22000), com
`StudioTheme.BACKGROUND` (`#0b1220`). No Windows 10 esse atributo nao esta
disponivel: a barra pode ficar escura, mas nao necessariamente nessa cor. A
aparencia final tambem depende do sistema e de suas configuracoes. Em caso de
erro ou API indisponivel, a decoracao nativa continua funcionando; nenhuma
janela e tornada `undecorated`.

O acesso nativo usa [JNA 5.15.0](https://github.com/java-native-access/jna/tree/5.15.0),
compativel com Java 17, sob LGPL 2.1 ou posterior **ou** Apache 2.0, a escolha
do distribuidor ([POM oficial](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.15.0/jna-5.15.0.pom)).
O JAR da JNA e dependencia de execucao, copiada por `runtimeLibs` e incluida
no `jpackage` do workflow Windows. O JAR contem `META-INF/LICENSE`,
`META-INF/AL2.0` e `META-INF/LGPL2.1`. Ao distribuir, manter o JAR e observar
as condicoes e avisos da licenca escolhida.
