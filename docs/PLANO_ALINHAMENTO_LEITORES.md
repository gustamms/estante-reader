# Plano de alinhamento dos leitores

Data: 03/11/2026. Status: primeira etapa implementada localmente; recursos próprios de PDF e a matriz completa de validação continuam pendentes.

## Implementado nesta entrega

- Padronizadas as zonas laterais e centrais de toque entre os leitores. Quadrinhos/PDF agora viram ao tocar nas laterais, respeitam LTR/RTL e calculam a zona central sobre a tela inteira em página dupla. Livros usam as mesmas zonas e direção.
- Alinhados tela cheia, altura dos painéis, amostras de fundo acessíveis e navegação pelos controles sem fechar o painel ao mudar de página.
- O slider de livros agora pré-visualiza durante o arraste e navega uma vez ao soltar. O progresso global usa o tamanho aproximado do texto por capítulo, salvo no manifesto v3 com leitura compatível dos manifestos anteriores.
- Corrigido MOBI para habilitar colunas também em bibliotecas já importadas e ocultar o sumário quando o arquivo não tem estrutura de capítulos.
- Adicionados marcadores de página para quadrinhos e PDF, com abertura, remoção e persistência por documento.
- A análise de balões em PDF deixou de iniciar sozinha; pode ser ativada pelo controle “Detectar balões” e sua preferência fica salva por documento.
- A configuração de leitura direita → esquerda também está disponível em EPUB/MOBI e muda o sentido do gesto de virada.
- Páginas de quadrinhos/PDF aceitam pinça para ampliar até 4× e arraste para mover; a virada fica bloqueada enquanto a página visível está ampliada.
- PDFs textuais têm busca com resultados por página e realce das ocorrências em Android 15 ou posterior. A interface informa a limitação em versões anteriores e em páginas digitalizadas.

Compilação `:app:compileDebugKotlin` concluída. A suíte de testes e a matriz de aparelhos/formato abaixo ainda não foram executadas nesta entrega.

## Objetivo e base da revisão

Oferecer os mesmos gestos de navegação, controles, feedback e recuperação de leitura em CBZ, CBR, PDF, EPUB e MOBI. Ajustes específicos devem depender do conteúdo: fonte e entrelinha para texto fluido; ampliação e enquadramento para páginas fixas; balões para quadrinhos.

A revisão original ocorreu após `v0.1.0-alpha.9`, que não continha a correção do arraste EPUB/MOBI. A release `v0.1.0-alpha.10` publica essa correção e os alinhamentos descritos neste documento. Este documento não representa uma nova rodada de testes em todos os formatos nem comprova igualdade de desempenho entre eles.

Existem dois caminhos de apresentação:

- CBZ, CBR e PDF → `ComicReaderScreen`, páginas como imagens, `PageCurl` diretamente sobre o conteúdo.
- EPUB e MOBI → `BookReaderScreen`, texto em WebView, `PageCurl` sobre capturas preparadas para a virada.

O PDF já compartilha a navegação dos quadrinhos. A diferença central está entre páginas fixas e texto fluido; há também diferenças de importação entre EPUB e MOBI.

## Diferenças confirmadas

| Tema | Quadrinhos e PDF | EPUB e MOBI | Ajuste proposto |
| --- | --- | --- | --- |
| Virada interativa | Páginas vizinhas pré-carregadas; plugin recebe o gesto diretamente. | Gesto encaminhado ao plugin após preparar capturas; há custo extra de preparação. | O comportamento visível e o cancelamento foram alinhados; falta medir a latência em dispositivos reais e reduzir qualquer atraso observado. |
| Toque lateral | Os 25% laterais avançam/voltam quando controles e seleção estão fechados, em LTR/RTL. | Mesmas zonas e direção configurável. | Verificar prioridade em links, texto selecionado e balões nos formatos reais. |
| Toque central | Área de 30–70% na horizontal e vertical sobre a área total, inclusive spreads; balões continuam interativos. | Mesmas proporções sobre a WebView; texto pode iniciar seleção. | Manter a seleção de texto e as ações dos balões como exceções contextuais. |
| Pressionar e segurar | Abre os controles. | Permite seleção de texto, que salva grifo automaticamente. | Seleção contextual quando disponível; acesso aos controles sempre por toque central e botão acessível. |
| Tela cheia | Esconde as barras do sistema e permite revelá-las por gesto. | Agora usa a mesma política; mantém o recuo de área segura. | Confirmar recortes e teclado em diferentes proporções de tela. |
| Navegação com painel aberto | Botões viram e o painel permanece aberto. | Mesmo comportamento. | Confirmar fluxos com diálogo, seleção e leitor de tela. |
| Direção | LTR/RTL altera o sentido do arraste e a ordem das páginas. | Seletor adicionado para alterar o sentido da virada sem inverter a escrita. | Respeitar metadados de direção EPUB e conferir layouts com conteúdo RTL. |
| Modo automático | Duas páginas a partir de 680 dp. | Duas colunas a partir de 680 dp. | Verificar largura útil, margens mínimas legíveis e painel lateral. |
| Capa e panorâmicas | Capa sozinha e panorâmicas sozinhas configuráveis. | Não possui regras equivalentes. | Preservar opções para páginas fixas; não tratar uma coluna de texto como página física. |
| Controles e acessibilidade | Painel de até 380 dp/60% da altura; paleta de fundos e alvos de 48 dp com descrição. | Altura e opções de fundo alinhadas; painel ainda tem conteúdo e aparência próprios. | Compartilhar a estrutura visual, sem esconder ferramentas específicas do formato. |
| Barra de progresso | Prévia durante o arraste; navega ao soltar; posição por página. | Mesmo gesto; o progresso global agora considera o texto aproximado de cada capítulo. | Validar a estimativa de texto e a restauração em livros longos e MOBI. |
| Posição e retomada | Página física salva. | Capítulo e fração interna; busca estima a posição pela proporção de caracteres. | Localizador próprio para cada conteúdo e comando único para navegar até ele. |
| Recursos de leitura | Sem busca textual, marcadores de livro ou grifos equivalentes; tem lupa de região e OCR. | Busca, marcadores e grifos; seleção nativa. | Marcadores comuns; busca/seleção/grifos conforme suporte real, com apresentação consistente. |
| PDF textual | Continua rasterizado em fundo branco; análise de balões é opcional; zoom e busca textual em Android 15 ou posterior. | Texto fluido permite ajustar fonte, entrelinha e cores e selecionar/grifar trechos. | OCR para PDF digitalizado, seleção/grifo de texto PDF e suporte de busca abaixo de Android 15 permanecem pendentes. |
| Fundo | Muda a área externa à imagem e o verso da virada; não recolore a página rasterizada. | Muda o fundo e a cor do texto renderizado. | Mesmas opções visuais, deixando claro o efeito sobre conteúdo fixo; filtros opcionais separados. |
| Erro/carregamento | Indicador inicial e mensagens no painel. | Erro de capítulo com botão de tentar novamente; sem o mesmo indicador inicial. | Estados e recuperação consistentes, preservando a página atual em falhas. |
| Preferências | Modo de página tem padrão global e valor por publicação. | Fonte, margens, entrelinha e colunas por livro; fundo compartilhado globalmente. | Padrões globais com substituições por publicação e migração das escolhas existentes. |

Referências de implementação: [MainActivity.kt](../app/src/main/java/com/estante/reader/MainActivity.kt), [BookReader.kt](../app/src/main/java/com/estante/reader/BookReader.kt), [BookPageTurn.kt](../app/src/main/java/com/estante/reader/BookPageTurn.kt), [ReaderControls.kt](../app/src/main/java/com/estante/reader/ReaderControls.kt), [EstanteViewModel.kt](../app/src/main/java/com/estante/reader/EstanteViewModel.kt) e [PdfPageDocument.kt](../app/src/main/java/com/estante/reader/core/archive/PdfPageDocument.kt).

### Inconsistências adicionais do MOBI

O importador retorna um capítulo único e declara `columns=false` e `tableOfContents=false`. A tela esconde a escolha de colunas, mas calcula o modo automático independentemente dessa capacidade: um MOBI pode aparecer em duas colunas sem permitir mudar a escolha. O botão de sumário, por sua vez, é exibido mesmo sem capacidade de sumário.

Imagens internas, capa e estrutura de capítulos não são recuperadas pelo importador MOBI atual. Isso afeta a experiência mesmo usando a mesma tela do EPUB. Corrigir essas limitações exige trabalho no importador, não apenas no componente visual. KF8/HUFF e DRM continuam fora do escopo deste alinhamento.

Referências: [StandardPublicationImporters.kt](../app/src/main/java/com/estante/reader/core/publication/StandardPublicationImporters.kt), [PublicationImport.kt](../app/src/main/java/com/estante/reader/core/publication/PublicationImport.kt) e [arquitetura de formatos](FORMATOS_PUBLICACAO.md).

## Contrato de experiência desejado

1. Arrastar acompanha o dedo, mostra o verso e o destino e permite voltar sem alterar a posição salva. Nas extremidades da publicação, não deve simular uma página inexistente.
2. Toques laterais navegam e o toque central abre/fecha controles. Usar coordenadas da área total de leitura. Links e balões interativos têm prioridade quando tocados diretamente; seleção e ampliação bloqueiam a virada até serem encerradas. Com painel aberto, toque externo apenas fecha o painel.
3. Botões Anterior/Próximo têm o mesmo comportamento e ficam indisponíveis durante transições incompatíveis. Arrastar a barra mostra o destino; soltar confirma uma única navegação.
4. Voltar fecha primeiro o diálogo/painel mais recente, depois seleção/ampliação e por último sai do leitor. Cancelar uma virada deve restaurar o estado antes de sair.
5. Cabeçalho, progresso, aparência, navegação e feedback ocupam os mesmos lugares. As opções extras dependem das capacidades reais do conteúdo.
6. Alterar fonte, orientação ou tamanho da janela preserva o trecho de leitura. Páginas fixas preservam página e enquadramento válido.
7. A redução de movimento do sistema tem uma política comum: gesto manual continua controlável e navegação automática usa transição reduzida. Verificar o comportamento efetivo do plugin em ambos os caminhos.

## Etapas de implementação

### 1. Base compartilhada e capacidades — prioridade alta

- Extrair a estrutura visual do leitor, cabeçalho, painel, fundo e política de tela cheia para componentes comuns.
- Criar um contrato de navegação com anterior/próximo, ir para localizador, disponibilidade e estados de transição/carregamento/erro. Manter adaptadores separados para WebView e páginas fixas.
- Centralizar configurações do PageCurl, zonas de toque e direção. Não forçar os dois motores a renderizar da mesma maneira.
- Expandir `PublicationCapabilities` para descrever texto selecionável, grifos, ampliação e direção, e utilizá-lo também no caminho PDF/quadrinhos. Respeitar as capacidades em todos os botões.
- Corrigir colunas do MOBI: como o texto já passa pelo mesmo paginador, declarar e testar suporte real a colunas; ocultar sumário quando não houver estrutura navegável.

Aceite: as cinco extensões abrem a mesma estrutura de controles; nenhum botão promete uma função indisponível; MOBI permite escolher o modo que está sendo aplicado. Páginas e fontes continuam sendo renderizadas pelos motores atuais.

### 2. Gestos e ciclo de virada — prioridade alta; depende da etapa 1

- Integrar a correção local de arraste EPUB/MOBI e cobrir também PDF/CBZ/CBR com a mesma suíte de comportamento.
- Implementar as zonas e prioridades do contrato; eliminar a diferença de coordenadas no modo duplo dos quadrinhos.
- Unificar conclusão/cancelamento, bloqueio de comandos repetidos e comportamento dos controles após navegar.
- Implementar direção nos adaptadores de texto, incluindo fronteiras de capítulos e capturas. Distinguir direção de virada de direção tipográfica e ler os metadados compatíveis.
- Medir tempo entre início do gesto e folha visível, tempo de preparação de capítulos e uso de memória. Se necessário, preparar vizinhos em cache limitado, invalidado por fonte, largura, tema e grifos.
- Unificar a política de animações reduzidas e a recuperação após erro, interrupção ou redimensionamento.

Aceite: segurar o dedo por dois segundos não completa a virada sozinho; mover para frente/trás altera a curvatura; cancelar mantém página, capítulo e posição persistida; completar avança uma vez. Testar ambos os sentidos, início/fim, capítulos, LTR/RTL, uma/duas páginas e animações do sistema desativadas. A imagem sob a folha deve ser o destino correto, sem flash branco.

### 3. Progresso, retomada e preferências — prioridade alta; depende da etapa 1

- Separar comandos de navegação de notificações de posição; criar um caminho único para slider, busca, marcador, grifo e sumário, inclusive destinos dentro do capítulo já aberto.
- Introduzir localizadores versionados: página e, quando necessário, enquadramento para conteúdo fixo; capítulo e âncora de texto com contexto para conteúdo fluido. Usar fração como fallback e migrar os dados existentes.
- Padronizar slider com estado temporário e confirmação ao soltar. Cancelar prévias não deve salvar o destino.
- Apresentar página física para PDF/quadrinhos e página do capítulo mais progresso global para livros. Calcular o progresso global por unidades de conteúdo, sem atribuir peso igual a capítulos muito diferentes; indicar que a estimativa pode variar com o layout.
- Salvar e restaurar preferências com padrão global e substituição por publicação, preservando escolhas e grifos já existentes. Garantir gravação da última posição ao sair.
- Adicionar marcadores de página para PDF/CBZ/CBR com a mesma interface de lista, abertura e remoção usada nos livros.

Aceite: soltar o slider gera uma navegação; reabrir retorna ao local salvo; busca e marcadores no mesmo capítulo deslocam efetivamente a tela; mudança de fonte/rotação mantém a âncora; migrar uma biblioteca existente não perde anotações nem configurações.

### 4. Layout adaptável e acessibilidade — prioridade alta; depende das etapas 1–3

- Criar uma regra de modo automático baseada na largura disponível após margens e painéis, com largura mínima legível por página/coluna. Usar os mesmos nomes e seletor; documentar exceções para capas e panorâmicas.
- Compartilhar cores, tamanhos, alturas de painel, botões de fundo e descrições acessíveis. Garantir alvos de pelo menos 48 dp.
- Aplicar tela cheia e áreas seguras de forma comum, sem sobreposição com teclado, recortes ou barras transitórias.
- Verificar TalkBack, ordem de foco, anúncio de mudança de página e alternativas de navegação que não dependam de arraste. Definir o aumento de fonte do sistema em conjunto com o ajuste do livro, sem duplicá-lo.

Aceite: mesmas ações têm mesmos rótulos e posições em todos os formatos; menus cabem em telas pequenas; alteração de largura no Fold não corta conteúdo nem perde posição; leitura e navegação permanecem possíveis com TalkBack.

### 5. Experiência própria para PDF e importação MOBI — prioridade seguinte

- Separar perfil de quadrinho e documento: PDF abre com controles neutros; leitura guiada continua disponível quando escolhida. Não iniciar análise de balões como comportamento obrigatório de todo PDF.
- Medir a qualidade do zoom e do deslocamento em páginas fixas e spreads, incluindo limites de memória e interação enquanto ampliado. Expor ampliação de imagem dentro de EPUB quando viável.
- Avaliar uma estratégia de busca em PDF abaixo de Android 15. A busca nativa atual funciona em PDF textual; PDF digitalizado ainda precisa de OCR e indicação do estado de indexação. Implementar seleção/grifo de texto PDF sem confundir com os marcadores de página.
- Manter cores originais de imagens/PDF por padrão. Qualquer tratamento noturno do conteúdo fixo deve ser opcional e separado do fundo externo.
- Recuperar recursos de imagem e capa do MOBI clássico suportado, com testes de integridade e limites de tamanho. Recuperar sumário/capítulos somente quando houver estrutura confiável no arquivo.

Aceite: PDF de texto não recebe instruções de balões por padrão; páginas ampliadas permanecem legíveis; busca só aparece com suporte funcional; MOBI de teste preserva imagens e informa claramente a ausência de sumário quando aplicável. Não ampliar silenciosamente a promessa de compatibilidade para KF8/DRM.

### 6. Validação integrada e publicação — depende das etapas incluídas na entrega

Executar os testes existentes de paginação, regressão de livros, importação e spreads. Acrescentar uma suíte compartilhada que execute as mesmas ações sobre os dois motores e valide estado, conteúdo visível e persistência. Verificações somente da posição final não comprovam acompanhamento do dedo.

| Eixo | Casos obrigatórios |
| --- | --- |
| Formatos | CBZ, CBR, PDF textual, PDF digitalizado, EPUB com CSS editorial e MOBI clássico com imagens. |
| Tela | Compacta, tablet/Fold, retrato/paisagem, modo simples/duplo/automático, painel lateral aberto. |
| Gestos | Toque lateral/central, arraste lento/rápido, dedo parado, reversão, cancelamento do sistema, múltiplos comandos, seleção e zoom. |
| Navegação | Primeiro/último destino, fronteira de capítulo, slider, sumário, busca, marcador e grifo dentro e fora do capítulo atual. |
| Ciclo de vida | Sair/reabrir, segundo plano, recriação da tela, redimensionamento e falha no carregamento do destino. |
| Visual e acesso | Quatro fundos, fonte mínima/máxima, fonte do sistema ampliada, TalkBack, animações reduzidas, verso e página sob a folha. |
| Desempenho | Abertura fria/quente, capítulo longo, PDF pesado, várias viradas consecutivas, memória e ausência de travamento. |

Usar fixtures pequenas próprias ou com licença compatível no repositório. O EPUB fornecido pelo usuário permanece como validação local, sem incorporar seu conteúdo aos testes publicados. Validar em emulador e no Fold real quando disponível; registrar explicitamente qualquer aparelho ou formato ainda não testado.

Critério de publicação: testes pertinentes aprovados, capturas/vídeo do arraste intermediário conferidos, migração validada e nenhuma diferença não documentada nas ações comuns. Publicar uma versão nova com notas específicas; não substituir o APK de uma release já publicada.

## Sequência de entregas

1. **Paridade de navegação e controles:** etapas 1–4, incluindo o arraste local já corrigido, o problema de colunas MOBI e marcadores comuns. Validar pela etapa 6.
2. **Recursos de conteúdo:** etapa 5 em entregas menores para PDF e MOBI, cada uma com seus próprios critérios e validação da etapa 6.

Busca e seleção em páginas rasterizadas não são pré-requisitos para corrigir gestos e controles. São capacidades adicionais que precisam de implementação e validação próprias. O alinhamento estará completo para as ações comuns quando trocar o formato não exigir reaprender como navegar, ajustar a visualização, recuperar a posição ou fechar o leitor.
