# Correção da confirmação de grifos

Data: 03/11/2026. Base analisada: `63f1198`, release `v0.1.0-alpha.11`.
Status: correção implementada e validada ponta a ponta em emulador, inclusive com o EPUB fornecido. Ainda não foi testada em aparelho físico.

Este documento complementa o [plano anterior](PLANO_AJUSTE_GRIFOS.md) e trata do relato de que confirmar a seleção não cria o grifo. O motor afetado é o compartilhado por EPUB/MOBI. Não acrescenta seleção de texto a PDF ou quadrinhos.

## Implementado nesta rodada

- Corrigido o mapeamento dos limites DOM em `offsetAt()` e calculado o texto selecionado a partir da mesma sequência filtrada usada para os offsets.
- Captura inválida, cancelada ou vazia deixa de reutilizar a seleção anterior.
- A confirmação recebe um identificador; a WebView mantém a seleção enquanto o ViewModel grava o registro. Só limpa e fecha o menu depois do resultado. Em falha, mantém o trecho e permite tentar novamente.
- O ViewModel retorna resultados explícitos, não cria marcações otimistas antes da gravação e distingue intervalo inválido, já existente, salvo e erro. A interface mostra o resultado ao terminar a gravação.
- A solicitação carrega o identificador do livro que originou a seleção; o ViewModel rejeita confirmações atrasadas que chegarem depois da troca de livro.
- A visibilidade das ações para grifos existentes agora deriva do estado guardado pelo menu, mesmo após `invalidate()`. O listener de seleção consulta sempre a implementação mais recente.
- Ampliada a cobertura de seleções parciais no início e no meio do texto, limites em elementos aninhados, falha/retentativa, invalidação de captura, troca de livro e ação do `ActionMode`.
- No EPUB real, a opção do `ActionMode` passa pela ponte de produção, ViewModel e Room; a marca salva também é conferida na WebView em coluna simples e dupla.

A mudança de cor e a edição de um grifo enquanto a seleção está ativa ainda precisam de testes próprios. No teste do EPUB, o `ActionMode` é iniciado e sua opção é acionada pelo menu instrumentado; não há um toque físico no item do menu em cima do livro.

## 1. Causa principal comprovada

O cálculo das posições inicial e final da seleção está errado dentro de um nó de texto. Em `BookReader.kt`, `offsetAt()` usa `Range.START_TO_END` para decidir se já ultrapassou todo o nó. Nessa comparação, o fim do intervalo atual é comparado com o início do outro intervalo. Como o intervalo atual está colapsado na posição selecionada, um ponto dentro do texto acaba contando o nó inteiro antes de chegar ao tratamento de `node === containerNode`.

O efeito foi reproduzido no emulador `TrilhaTablet`, Android 16 / API 36, ARM64, com HTML sintético e o código da alpha.11:

| Ação | Intervalo esperado, fim exclusivo | Intervalo enviado | Resultado |
| --- | --- | --- | --- |
| Selecionar “Primeiro trecho” no início do parágrafo | `[0, 15)` | `[0, 64)` | Texto de 15 unidades enviado com extensão de 64. |
| Selecionar “trecho” dentro do mesmo parágrafo | `[9, 15)` | `[64, 64)` | Intervalo vazio apesar da seleção não vazia. |
| Toque longo em “Primeiro” e confirmação pela ação do menu nativo | `[0, 8)` | `[0, 64)` | O caminho do menu também envia posições incorretas. |

Foram executados **3 testes de diagnóstico, com 3 falhas nas expectativas de posições corretas**. São reproduções do defeito, não testes aprovados da correção.

Consequências no código de produção:

1. `BookSelectionBridge.onTextSelection()` descarta intervalos com `end <= start`; isso elimina o segundo caso antes de chegar ao ViewModel.
2. `EstanteViewModel.addBookHighlight()` descarta intervalos cujo tamanho difere do texto recebido; isso elimina o primeiro e o terceiro casos.
3. Os descartes não retornam motivo ao leitor. `estanteConfirmHighlight()` limpa a seleção logo após enviar o evento, sem saber se foi aceito ou salvo.

Assim, a seleção desaparece e nenhum grifo é criado. As validações da ponte e do ViewModel estão protegendo a persistência contra dados inconsistentes; removê-las não corrige o cálculo.

### Limites e reprodução do diagnóstico

O terceiro teste abriu a seleção com eventos de toque reais no emulador e acionou `ActionMode.menu.performIdentifierAction(7102, 0)`, passando pelo callback de **Grifar seleção**. Não foi um toque por coordenadas no botão do menu. Nos três casos, a ponte JavaScript foi substituída por um coletor dos argumentos enviados. A rejeição posterior foi verificada por inspeção das condições da ponte e do ViewModel, não por uma execução completa com Room.

Fixture utilizada: `<html><body><p>Primeiro trecho para selecionar e continuar ajustando com calma.</p></body></html>`, carregada pelo `ReaderHarness` existente, fonte 24 e coluna simples. Nos dois primeiros casos, criar um `Range` no primeiro nó de texto do parágrafo, com limites `(0, 15)` e `(9, 15)`, e executar `estanteConfirmHighlight()`. Conferir o texto e ambos os offsets enviados a `EstanteHighlights.onTextSelection`.

A classe temporária `HighlightSelectionPlanDiagnosisTest` foi executada com `:app:connectedDebugAndroidTest` e removida após registrar os resultados. O código do aplicativo não foi alterado nesta etapa. O EPUB do usuário e um aparelho físico não foram exercitados neste diagnóstico.

## 2. Por que os testes anteriores não detectaram

- `textOffsetsExcludeEmbeddedStylesAndRestoreTheExactQuote` confere o início, mas não exige que o fim seja 15 nem que `end - start` corresponda ao texto. Depois constrói uma entidade diretamente, sem passar pela validação do ViewModel.
- `locateItem()` recupera a marca por busca da citação quando os offsets estão incorretos. Essa recuperação faz o teste de pintura passar mesmo com um intervalo que o aplicativo real recusaria ao criar o grifo.
- O teste anterior de toque longo verifica a presença do menu, mas confirma chamando JavaScript diretamente; não aciona a opção nativa nem comprova persistência.

Os testes novos devem verificar o contrato completo e impedir que a recuperação de grifos antigos esconda erros na criação de novos.

## 3. Riscos adicionais identificados por inspeção

Estes pontos precisam de testes próprios; não foram necessários para reproduzir a causa principal:

- `ReaderWebView` chama `evaluateJavascript(action, null)` e imediatamente `mode.finish()`. A execução de JavaScript é assíncrona: o encerramento do menu pode competir com a captura da seleção. Esse comportamento intermitente ainda não foi reproduzido.
- `reportSelection()` retorna ao encontrar `end <= start` sem invalidar explicitamente uma captura anterior. Uma seleção nova inválida pode deixar uma captura antiga disponível para confirmação.
- O listener de `selectionchange` é instalado uma única vez, mas suas funções e `flatText` são recriados ao reaplicar os estilos. É necessário evitar referências a estado antigo.
- A atualização dos grifos remove e recria marcações junto com a aplicação do layout. Isso pode alterar os nós usados por uma seleção ativa, especialmente ao atravessar elementos ou parágrafos.
- A ponte torna **Remover grifo** e **Mudar cor** visíveis e chama `invalidate()`. `onPrepareActionMode()` reconstrói o menu com essas opções ocultas, descartando o estado recém-definido.

## 4. Plano de implementação, em ordem

### Etapa 1 — Corrigir o mapeamento da seleção

Arquivos principais: `BookReader.kt` e testes de seleção.

- Centralizar a sequência de nós de texto usada por captura, restauração e pintura, mantendo os mesmos filtros de conteúdo não legível já adotados pelo leitor.
- Corrigir `offsetAt()`: tratar a posição dentro do próprio nó antes de somar seu comprimento e usar a comparação correta ao verificar limites entre nós. Para comparar o início do intervalo atual com o fim do outro, a constante é `Range.END_TO_START`.
- Tratar também limites cujo contêiner seja um elemento, nós vazios, extremidades exatas de nós e seleções que atravessam elementos.
- Manter offsets em unidades UTF-16, compatíveis com DOM e strings de JavaScript/Kotlin. Não remover espaços nem normalizar o texto depois de calcular as posições. A citação, prefixo e sufixo devem vir da mesma sequência filtrada; caracteres excluídos dessa sequência não podem entrar somente na citação.
- Validar `0 <= start < end <= texto.length`, `texto.slice(start, end) === citação` e a coerência do tamanho antes do envio. Não mascarar o problema com `end = start + citação.length` quando o início também pode estar errado.
- Invalidar capturas anteriores quando a seleção atual for cancelada, inválida ou pertencer a outro documento.
- Manter a validação do ViewModel e a recuperação por citação/contexto para grifos existentes. Não apagar ou regravar registros antigos como efeito da correção.

Aceite alcançado nos casos instrumentados: início `[0,15)`, meio `[9,15)` e limite de elemento `[0,8)` enviam a extensão correta e a citação correspondente. No EPUB fornecido, o intervalo salvo foi reaberto no DOM e a citação pintada comparada com a selecionada. Emoji e seleção atravessando múltiplos parágrafos ainda precisam de casos dedicados.

### Etapa 2 — Confirmar a operação e tratar rejeições

Arquivos principais: `ReaderWebView`/`BookSelectionBridge` em `BookReader.kt`, `EstanteViewModel.kt` e ligação dos callbacks na tela do leitor.

- Capturar uma seleção imutável ao confirmar, vinculada a livro, capítulo, geração do documento e identificador da operação. Usar o intervalo atual; uma captura anterior só pode ser reutilizada se ainda corresponder à mesma sessão.
- Criar resultados explícitos para salvo, já existente, seleção inválida, sessão expirada e erro de persistência. Substituir os retornos silenciosos por resultados consumidos pela interface.
- Não encerrar o `ActionMode` antes de confirmar a captura. Diferenciar retorno de `evaluateJavascript` de confirmação de gravação: executar o script com sucesso não significa que Room salvou o grifo.
- Preservar a captura até o resultado final e encerrar/limpar a seleção após sucesso. Em erro recuperável, manter ou restaurar o trecho no mesmo documento e permitir nova tentativa, com mensagem clara. Após navegação, descartar a sessão sem restaurar seleção no livro errado.
- Mostrar sucesso após a gravação. Caso mantenha pintura otimista, reverter somente a operação que falhou.
- Impedir confirmação dupla enquanto salva; selecionar um intervalo já marcado deve tratar o grifo existente de forma explícita, sem criar duplicata nem parecer um botão sem efeito.

Aceite alcançado no fluxo principal: o teste do EPUB usa a ação do menu, ponte de produção, ViewModel e Room, aguarda a resposta persistida e confere a marca na tela. Uma confirmação com identificador de outro livro é rejeitada. Falha/retentativa também está coberta com resultado de gravação simulado. Trocar capítulo enquanto há uma gravação continua pendente.

### Etapa 3 — Preservar seleção, marcação e ações do menu

Arquivo principal: `BookReader.kt`.

- Atualizar grifos sem repaginar todo o capítulo; adiar mutações dos nós enquanto a pessoa ajusta a seleção. Renderizar trechos por segmentos de texto, preservando a estrutura de parágrafos, links e estilos.
- Manter um controlador/listener estável que consulte o estado atual, ou remover o listener anterior antes de substituí-lo. Limpar temporizadores e sessões ao trocar de documento.
- Guardar no controlador do menu o estado do grifo selecionado. `populateMenu()` e `onPrepareActionMode()` devem derivar a visibilidade desse estado, inclusive após `invalidate()`.
- Preservar Copiar, mudar cor, Remover grifo e Desfazer; confirmar que criar um grifo não quebra a remoção e que remover permite grifar o mesmo trecho novamente.
- Preservar o bloqueio de virada de página durante a seleção e a posição de leitura ao confirmar.

Aceite parcial: estado das ações após `invalidate()`, pintura após salvar e regressões de seleção/paginação estão cobertos. Alterar cor com seleção ativa ainda precisa de teste específico.

### Etapa 4 — Fechar a lacuna dos testes

Atualizar `HighlightDiagnosticsAndroidTest` e acrescentar testes integrados com a ponte real, o ViewModel e um banco Room isolado.

| Camada | Casos obrigatórios | O que comprovar |
| --- | --- | --- |
| Intervalos | Início, meio e fim de um nó; limite entre nós; contêiner elemento; nós vazios; seleção invertida; espaços; acentos; emoji; `style` no corpo; negrito/link; múltiplos parágrafos | Posições, citação e contexto corretos, sem depender da recuperação por busca. |
| Limites e validade | Seleção vazia; limites de 2 e 5.000 unidades; acima do limite; seleção fora do conteúdo; captura anterior após seleção inválida | Aceitação ou rejeição explícita, sem corte silencioso nem reutilização de outro trecho. |
| Caminho completo | Toque longo, ajuste das alças e toque em **Grifar seleção** pela interface | Ponte de produção → ViewModel → Room → marcação no DOM, com o mesmo trecho em todas as etapas. |
| Persistência | Fechar/reabrir livro e relançar aplicativo; confirmação dupla; falha de gravação e nova tentativa; troca de livro/capítulo durante operação | Um registro correto, isolamento por livro, resultado coerente e recuperação em erro. |
| Edição | Mudar cor, remover no texto e no painel, desfazer, selecionar novamente; invalidar menu | Texto intacto, ações visíveis e banco/painel/marcação sincronizados. |
| Leitura | Coluna simples/dupla, fonte grande, rotação, tema, mudança de página; EPUB real fornecido | Mesmo trecho após mudança de layout e nenhum conflito entre seleção e paginação. |

Para o teste ponta a ponta, não substituir `EstanteHighlights`, não chamar `estanteConfirmHighlight()` diretamente e não construir a entidade de grifo manualmente. Essas técnicas continuam úteis apenas nos testes isolados de cálculo/pintura.

Ambiente executado: emulador `TrilhaTablet`, Android 16 / API 36, ARM64. O EPUB real foi copiado temporariamente para os assets de instrumentação e removido em seguida; não foi adicionado ao repositório. Sem preparar esse arquivo, o caso real continuará opcional. A versão mínima suportada e o aparelho físico ainda não foram testados.

Resultado observado nesta rodada: `HighlightDiagnosticsAndroidTest` — **9 testes passaram**; `BookReaderRegressionAndroidTest` — **7 testes passaram**. No teste do EPUB real, duas seleções distintas foram confirmadas pelo item **Grifar seleção**, salvas pelo ViewModel/Room e conferidas no DOM, primeiro em uma coluna e depois em duas; uma chamada com identificador de outro livro foi rejeitada. As regressões também cobrem importação, tipografia, virada entre páginas/capítulos e gestos. Ambos os comandos terminaram com `BUILD SUCCESSFUL`. Nenhum teste em aparelho físico foi executado.

### Etapa 5 — Validar a entrega

1. Executar os testes de intervalos e o fluxo completo de criação/persistência.
2. Executar as regressões de grifos, leitura EPUB e gestos de quadrinhos já existentes. Não alterar a configuração compartilhada de virada para corrigir seleção.
3. Repetir no livro fornecido: selecionar parte de um parágrafo longo, confirmar, conferir o trecho no painel, reabrir o livro, remover e desfazer.
4. Registrar resultados, limitações e comportamento diante de falha; atualizar o plano anterior com o que efetivamente foi concluído.

Critério de conclusão: o intervalo selecionado é o intervalo salvo e pintado; o grifo persiste ao reabrir; nenhuma confirmação é descartada sem retorno; remover/desfazer continua funcionando; seleção e virada de página não disputam o gesto.

## Referências técnicas

- [DOM Standard — comparação de limites de Range](https://dom.spec.whatwg.org/#dom-range-compareboundarypoints): semântica das constantes usadas em `offsetAt()`.
- [Android WebView — evaluateJavascript](https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E)): execução assíncrona e callback de resultado; não oferece confirmação de persistência da aplicação.
- Código inspecionado: `app/src/main/java/com/estante/reader/BookReader.kt`, `app/src/main/java/com/estante/reader/EstanteViewModel.kt` e `app/src/androidTest/java/com/estante/reader/HighlightDiagnosticsAndroidTest.kt`.
