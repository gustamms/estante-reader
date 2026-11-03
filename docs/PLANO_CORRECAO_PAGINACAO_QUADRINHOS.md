# Correção da virada de páginas dos quadrinhos

Data: 03/11/2026. Diagnóstico da alpha.10 (`9caebbf`) e correção da disputa entre zoom e virada nos quadrinhos.

## Causa identificada

A alpha.10 introduziu zoom em `PageCanvas`, usado pelos quadrinhos e pelo PDF. O modificador `transformable(state = transformState, enabled = !selectionMode)` está ativo também na escala 1×. Ele reconhece deslocamento com um dedo e consome o gesto antes de o `PageCurl` pai utilizá-lo. A escala continua em 1×, o deslocamento visual é zerado e a folha não se dobra: o gesto parece não fazer nada.

O bloqueio de paginação por `zoomedPages` não resolve isso: ele observa a escala, enquanto o consumo do gesto já ocorre em 1×. O EPUB encaminha os eventos para uma superfície de PageCurl com imagens estáticas, sem esse modificador concorrente.

O histórico confirma que o modificador entrou no commit da alpha.10. É uma regressão introduzida ao ampliar o suporte de zoom durante o alinhamento dos leitores.

## Evidência

No diagnóstico anterior à correção, o teste instrumentado no emulador TrilhaTablet, Android 16/API 36, ARM64, mediu:

| Mesmo arraste, dedo ainda pressionado | Progresso do PageCurl |
| --- | --- |
| PageCurl com página estática | 0,35000002 |
| PageCurl com transformable ativo como no quadrinho | 0,0 |

Resultado: a medição reproduziu o defeito. A suíte atual foi convertida em teste de paridade. Os testes exercitam a hierarquia de gestos com um PageCurl e uma página de quadrinho sintética; não reproduzem uma importação CBZ/CBR nem comprovam a aparência de todos os estados na tela completa.

A inspeção mostrou que ambos os motores já usam `PageCurl`, `PointerBehavior.PageEdge`, as mesmas metades de início/fim do gesto, verso com alpha 0,08 e sombra com alpha 0,28, raio 18 dp e deslocamento −6 dp. Portanto, esses parâmetros não explicavam a perda do arraste. A configuração duplicada foi extraída para um helper compartilhado.

Outros pontos a validar, ainda não demonstrados como defeitos: recomeço do estado quando `pageIndex` ou modo duplo muda, chegada tardia da página vizinha, interação com balões/lupa, direção RTL e cancelamento quando o segundo dedo entra durante uma virada.

## Plano e status

### 1. Corrigir a disputa entre zoom e virada — concluído

Em `MainActivity.kt`, no `PageCanvas`, permitir deslocamento por um dedo apenas quando a escala estiver acima de 1×, usando o controle de pan oferecido pela versão de Compose instalada. Manter a pinça de dois dedos disponível em 1×. Se a API não permitir essa distinção, implementar um reconhecedor que só consuma deslocamento quando ampliado ou quando reconhecer pinça.

Regras:

- Um dedo em 1× pertence à virada de página.
- Dois dedos pertencem à pinça; não confirmam mudança de página.
- Um dedo com zoom ativo desloca a imagem; não vira página.
- Voltar a 1× zera o deslocamento e libera imediatamente a paginação.
- Entrada do segundo dedo durante uma virada cancela a prévia e mantém a página original.
- Seleção de região, lupa e painéis continuam impedindo viradas acidentais.

Aceite: no teste comparativo, as duas páginas passam a dobrar enquanto o dedo permanece pressionado. Arrastar de volta desfaz a dobra, e cancelar não salva o destino.

### 2. Compartilhar a configuração e o contrato de gestos — configuração concluída; contrato pendente

Uma configuração comum de PageCurl agora é usada por `BookPageTurn.kt` e `ComicReaderScreen`: verso, sombra, comportamento da borda, zonas de início/fim e direção de leitura. Os adaptadores continuam específicos para conteúdo WebView ou imagens.

Definir uma única precedência entre seleção/zoom, virada e toques nos controles. Revisar os toques em balões para que não interceptem um arraste. Em modo duplo, aplicar as zonas à largura do livro aberto inteiro.

Aceite: EPUB/MOBI e CBZ/CBR/PDF em escala normal respondem ao mesmo movimento com a mesma direção, dobra e possibilidade de retornar. A disponibilidade de zoom e seleção pode variar pelo formato.

### 3. Preservar a sessão de virada até terminar — revisão ampla pendente

Revisar `remember(state.title, state.pageIndex, doublePage)`, a pré-carga de páginas/spreads e a confirmação em `LaunchedEffect(pageTurnState.current)`. Não trocar a imagem de origem ou reinicializar a animação enquanto o dedo controla a folha. Confirmar o índice somente ao concluir; ao cancelar, manter índice e progresso anteriores.

Tratar página vizinha ainda indisponível sem apresentar uma folha vazia ou substituir a animação por uma troca abrupta. Cobrir primeiro/último destino, capa separada, panorâmica e modo duplo.

### 4. Validar a tela real e evitar nova regressão — cobertura parcial concluída

O teste de caracterização foi convertido em teste de paridade, que exige progresso comparável em ambas as hierarquias. Também foram acrescentados testes de reversão, pan ampliado e pinça. O teste da tela real com CBZ visualmente distinto e a comparação com EPUB equivalente continuam pendentes.

Matriz mínima:

- Arraste lento/rápido, dedo parado, reversão, soltura e ACTION_CANCEL.
- Ida/volta, LTR/RTL, página simples/dupla e retrato/paisagem.
- Pinça em 1×, deslocamento ampliado e retorno a 1×; segundo dedo durante a virada.
- Toques laterais/centrais, balões, lupa, seleção e painéis.
- Pré-carga lenta, limites do documento e transição de spreads.

Medir progresso durante o gesto, não apenas o índice final. Conferir capturas intermediárias de dobra, verso e página sob a folha; executar no Fold real quando disponível. Rodar regressões de seleção/grifo e EPUB para impedir que a correção prejudique novamente outro formato.

Critério de entrega: experiência equivalente em escala normal, zoom funcional, cancelamento sem avanço e nenhum quadro vazio durante a virada. Publicar em nova versão apenas após esses critérios; não substituir o APK da alpha.10.

## Validação adicional

O teste existente `BookReaderRegressionAndroidTest.draggingTracksTheHeldPointerAndCanReturnToTheOriginalPage` também foi executado após a correção e passou: o EPUB acompanha o dedo, mantém a prévia enquanto pressionado e retorna à página original ao cancelar.

## Correção implementada

`PageCanvas` agora usa `pageCanvasTransformable`, que mantém a pinça disponível em qualquer escala, mas só deixa o gesto de pan consumir movimentos de um dedo quando a escala é maior que 1×. Assim, em escala normal, o `PageCurl` pai recebe o arraste e acompanha o dedo; ampliada, a imagem recebe o arraste e não vira a página. Ao voltar a 1×, o pan volta a ficar bloqueado e o código já existente zera o deslocamento. Livros e quadrinhos também usam `rememberReaderPageCurlConfig`, com os mesmos parâmetros visuais e zonas de gesto.

Testes instrumentados executados no emulador TrilhaTablet, Android 16/API 36:

- A virada em 1× volta a acompanhar o dedo e mantém progresso próximo ao PageCurl sem zoom.
- Reverter o arraste antes de soltar retorna a dobra e mantém a página original.
- Com zoom, arrastar desloca a página e não dobra a folha.
- A pinça continua ampliando a página a partir de 1×.
- A regressão de arraste/cancelamento do EPUB continua aprovada.

A correção cobre a regressão reproduzida e compartilha o contrato visual da dobra entre os leitores. A comparação visual na tela completa do quadrinho e a matriz mais ampla da etapa 4 (RTL, página dupla, cancelamento quando um segundo dedo entra, controles e Fold físico) continuam como validação complementar; não foram necessárias para corrigir a disputa de gestos observada.
