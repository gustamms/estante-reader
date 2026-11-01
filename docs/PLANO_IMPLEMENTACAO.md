# Estante — plano de implementação Android

Versão 1.1 • 1 de novembro de 2026 • Repositório: gustamms/estante-reader

**Estado atual:** uma primeira fatia experimental da leitura automática está implementada. O [plano executável de leitura automática de balões](PLANO_LEITURA_AUTOMATICA.md) registra o que entrou no código e os critérios A00–A09 que ainda faltam para concluir a alpha; este documento continua sendo o roteiro geral do produto multiformato.

## 1. Objetivo e limites deste plano

Construir uma biblioteca pessoal e leitor Android para quadrinhos, EPUBs e PDFs. O diferencial inicial é ampliar balões de quadrinhos com navegação guiada e oferecer OCR em português brasileiro. O Galaxy Z Fold 6 é o aparelho principal de validação, acompanhado de emuladores e um perfil de hardware mais limitado.

### Comportamento central definido pelo usuário

Ao adicionar um quadrinho, o app inicia automaticamente a identificação dos balões de todas as páginas e salva suas posições. Durante a leitura, oferece uma pequena ampliação localizada do balão ativo para facilitar a leitura. O fluxo habitual dispensa desenhar uma seleção.

A apresentação prevista é uma lupa localizada: o balão ampliado aparece sobre a página, com margem suficiente para preservar seu contorno e contexto imediato. A página mantém sua escala e posição ao fundo. O balão não deve ocupar automaticamente a tela inteira; a intensidade da ampliação será moderada, ajustável e limitada ao espaço disponível. Essa definição substitui o recorte em tela cheia do protótipo 0.1.

Ao entrar no modo guiado, o primeiro balão disponível é destacado e ampliado. Toque ou controles de anterior/próximo percorrem os balões na ordem de leitura; tocar em um balão detectado permite focá-lo diretamente. O avanço depende do leitor, sem temporizador. Ao terminar a página, o controle de avanço conduz à próxima. Sair do modo guiado remove a lupa e preserva a posição da página.

Identificar e ampliar um balão não depende de reconhecer suas palavras. O OCR é uma ação opcional para copiar ou usar o texto. A seleção manual permanece disponível para corrigir regiões ausentes ou incorretas.

**Estado da fatia atual:** importação/abertura de CBZ dispara análise local, resultados são gravados por página e a leitura oferece lupa localizada e navegação LTR/RTL. O modelo está integrado como candidato experimental; ainda não há evidências de qualidade ou benchmark no Fold 6.

Este documento define decisões propostas, entregas, dependências e critérios de aprovação. Metas de desempenho e qualidade abaixo são objetivos de engenharia a validar, não resultados medidos nem garantias. O plano deve ser atualizado com evidências de cada etapa; versões de ferramentas serão fixadas após verificar sua compatibilidade.

### Marco implementado: 0.1 — leitor CBZ

- Estrutura Android nativa em Kotlin, Compose, Gradle Wrapper e Version Catalog; mínimo Android API 26 e compilação/alvo API 36.
- Importação pelo Storage Access Framework, cópia limitada ao cache privado, validação das entradas do ZIP e acesso aleatório às páginas por `ZipFile`.
- Leitura de página, seleção manual de região, ampliação do recorte, OCR local do ML Kit e cópia explícita do texto.
- Retomada do último CBZ e da página usando DataStore.
- Verificação executada em 1 de novembro de 2026: `:app:assembleDebug` concluiu com sucesso. Os testes unitários existentes foram executados na etapa anterior; devem ser repetidos após mudanças relevantes no núcleo.

O marco 0.1 descreve a base manual inicial. A implementação atual adiciona estante de capas com Room, progresso por título, importação gerenciada de CBZ/CBR, leitor limpo com controles sobrepostos e lupa acionada pelo toque no balão. Permanecem pendentes EPUB/PDF, correções persistentes de regiões, fila de análise durável após encerramento, pesquisa/filtros avançados, validação de RAR real, acessibilidade e medições no Fold 6. A cópia de cada arquivo compactado é limitada a 512 MiB; cache, política de erro e recuperação seguem sujeitos à matriz de testes do plano.

### Marco experimental: leitura guiada automática de CBZ

- Detector ONNX executado no dispositivo, fixado a uma revisão do modelo e protegido por verificação de integridade; os avisos de licença acompanham o artefato.
- Análise automática sequencial com prioridade da página atual e das próximas duas; resultados por página são persistidos em armazenamento atômico e reutilizados pela versão do modelo.
- Lupa localizada na página, seleção do balão por toque, navegação anterior/próximo e ordem geométrica LTR/RTL. OCR permanece uma ação opcional.
- APKs debug são divididos por ABI para limitar o pacote ao runtime nativo da arquitetura correspondente.
- Limites conhecidos: análise ligada ao ciclo de vida do leitor (sem WorkManager/retomada garantida após encerramento do processo); não há biblioteca Room para vários títulos nem inserção/correção persistente de regiões. Qualidade do detector, geometria em bordas, desempenho e consumo ainda não foram validados em corpus ou no Fold 6.

### Estado verificado em 1 de novembro de 2026

- Repositório local: `/Users/gustavomendes/Documents/develop/estante-reader`.
- Ambiente: Java 17, SDK Android 36 e emulador ARM64. A compilação com Gradle Wrapper gerou APKs debug por arquitetura; o APK ARM64 tem aproximadamente 66 MiB.
- O projeto integra um detector ONNX local com revisão fixada, licença/avisos e conferência de SHA-256 do modelo. A inferência ainda precisa ser exercitada com um CBZ real.
- Ao abrir um CBZ, a análise prioriza a página atual e as duas seguintes, depois percorre as demais. Cada página concluída é persistida atomicamente e identificada pelo conteúdo do arquivo e versão do modelo.
- A interface já contém lupa sobre a página, foco por toque, anterior/próximo, escolha LTR/RTL e OCR opcional.
- A estante usa Room para vários títulos, capas geradas localmente, retomada por publicação e remoção confirmada. O importador identifica CBZ/CBR pela assinatura e guarda cópia privada.
- O APK ARM64 desta etapa foi instalado no emulador Android API 36 e a Activity principal ficou em primeiro plano, sem exceção fatal de inicialização. A validação no Fold 6 e a execução com um CBZ real permanecem pendentes; não foram medidos desempenho, consumo ou qualidade de detecção.

## 2. Produto e estratégia de entrega

### Princípios

1. Ler o primeiro conteúdo rapidamente, sem aguardar o processamento do livro inteiro.
2. Funcionar localmente, sem conta obrigatória e sem envio de páginas a servidores.
3. Preservar posição e preferências ao fechar o app, girar ou dobrar o aparelho.
4. Oferecer seleção manual e página completa quando a análise automática falhar.
5. Mostrar claramente quais formatos e recursos estão disponíveis em cada versão.
6. Separar reconhecimento de regiões, ordem de leitura, OCR e renderização: ampliar balões não exige transcrever o texto.

### Escopo por versão

| Entrega | Escopo | Condição de conclusão |
| --- | --- | --- |
| Prova técnica | Detecção de balões, OCR PT-BR e renderização EPUB/PDF em exemplos | Relatório comparativo com evidências e decisões |
| Alpha de quadrinhos | CBZ/ZIP, biblioteca, análise automática na importação, lupa localizada, sequência de balões e progresso; OCR opcional | Importar → balões detectados → leitura com ampliação localizada sem seleção manual; fluxo validado no Fold 6, com qualidade experimental declarada quando necessário |
| Beta guiada | Melhorias de detecção e ordem de leitura, correções persistidas, CBR após avaliação | Metas do conjunto de avaliação atendidas ou recurso explicitamente experimental |
| Beta multiformato | EPUB refluível e PDF, biblioteca comum e posições específicas por formato | Matriz de compatibilidade e recuperação de estado aprovada |
| Versão 1.0 | Estabilidade, acessibilidade, importação/exportação de dados, distribuição assinada | Todos os critérios de lançamento atendidos |

Ficam para versões posteriores: sincronização entre dispositivos, contas, servidores de biblioteca, tradução automática, leitura em voz alta, anotações avançadas, catálogos online, DRM, outros sistemas operacionais e suporte amplo a EPUB de layout fixo. Arquivos protegidos devem produzir mensagem compreensível. Não prometer compatibilidade com todos os formatos ou variantes.

### Jornadas essenciais

- Adicionar arquivo → iniciar detecção automática com progresso → salvar regiões por página → abrir → ler → fechar → retomar.
- Abrir quadrinho → ativar guia → ampliar localmente o primeiro balão → avançar/voltar balão → retornar à página → corrigir região quando necessário.
- Selecionar região → reconhecer texto → revisar → copiar/compartilhar por ação explícita.
- Abrir EPUB → alterar tamanho de fonte → continuar no mesmo trecho.
- Abrir PDF → ampliar página → selecionar texto existente quando suportado; usar OCR para imagem quando necessário.
- Recuperar acesso a arquivo movido ou removido sem perder marcadores e progresso.

## 3. Decisões técnicas

| Área | Decisão proposta | Justificativa e validação |
| --- | --- | --- |
| Plataforma | Android nativo, Kotlin, Activity única e Compose | Integração com arquivos, ciclo de vida e telas adaptáveis |
| SDK mínimo | API 26 inicialmente | Proposta sujeita ao piso das bibliotecas escolhidas; documentar qualquer aumento |
| SDK alvo/compilação | Versão estável suportada pela cadeia de build | Verificar requisitos de distribuição no momento de publicar; SDK 36 está disponível localmente |
| Build | Gradle Wrapper, Kotlin DSL, Version Catalog, Compose BOM | Fixar versões e checksums; sem dependências dinâmicas |
| Estado | ViewModel, StateFlow, fluxo unidirecional | UI emite ações e observa estado imutável com coleta vinculada ao lifecycle |
| Concorrência | Coroutines, cancelamento estruturado, dispatchers injetáveis | I/O e análise fora da thread principal, tarefas canceláveis |
| Persistência | Room para dados relacionais; DataStore para preferências | Migrações explícitas; estado durável separado do estado visual |
| Injeção | Hilt para componentes Android e serviços compartilhados | Construtores simples em lógica pura; evitar service locator |
| Navegação | Biblioteca Jetpack estável compatível com a stack escolhida | Validar restauração de pilha e integração de navegadores de terceiros no primeiro spike |
| OCR | Avaliar ML Kit latino empacotado contra Tesseract `por` | ML Kit lista PT-BR; qualidade em lettering precisa ser medida [S6] |
| Balões | Detector local intercambiável | Avaliar modelos existentes, licenças, CPU, memória e qualidade antes de fixar runtime |
| EPUB | Avaliar Readium Kotlin como primeira opção | Parser e navegador especializado; não construir motor HTML/EPUB do zero [S7] |
| PDF | Comparar renderer Android e integração Readium | Escolher conforme zoom, texto, pesquisa, memória, versão mínima e dependências |
| CBR | Avaliar biblioteca RAR mantida | Exigir cobertura RAR4/RAR5 declarada, licença e compatibilidade nativa verificadas |

Preferir versões estáveis. Dependência experimental só entra com justificativa, risco documentado e alternativa de substituição. Compose pode hospedar Views ou Fragments exigidos por um motor de leitura; validar lifecycle, navegação e acessibilidade dessa integração.

### Organização do código

Começar com modularização pequena: `:app`, `:core:reader`, `:core:data`, `:core:designsystem` e `:feature:reader`. Biblioteca e configurações podem iniciar como pacotes em `:app`. Separar módulos adicionais quando houver fronteira real de dependências ou custo de build que justifique.

- `app`: composição, navegação, configuração e injeção.
- `core:reader`: identificadores, capacidades, locators e contratos independentes da UI.
- `core:data`: Room, DataStore, acesso a documentos, importação e repositórios.
- `core:designsystem`: tema Material 3, componentes e semântica acessível.
- `feature:reader`: apresentação e coordenação dos motores; adaptadores de quadrinhos, EPUB e PDF.
- Após o spike: separar análise/OCR e motores pesados em módulos próprios para isolar dependências e facilitar substituição.

Dependências seguem da apresentação para contratos/repositórios e implementações injetadas. Composables não acessam banco, arquivos ou modelos diretamente. Use cases apenas para regras compartilhadas ou fluxos complexos; evitar uma camada por operação trivial. ViewModels não guardam Activity, Views ou bitmaps grandes. Essas decisões seguem a separação de responsabilidades recomendada pelo Android [S1].

### Contratos centrais

- `DocumentSource`: URI persistida ou cópia gerenciada, capacidade de acesso aleatório, tamanho conhecido/opcional.
- `ReaderEngine`: abrir/fechar documento, fornecer capacidades e navegar por locator.
- Capacidades explícitas: paginação, reflow, seleção de texto, pesquisa, zoom, análise de regiões, marcadores.
- `FixedPageLocator`: identidade da página, região opcional, centro e escala relativos à imagem.
- `EpubLocator`: recurso/spine, progressão e localizador estruturado do motor, mais contexto textual quando disponível.
- `RegionDetector`: imagem normalizada → regiões, tipos, confiança e versão do detector.
- `ReadingOrderResolver`: regiões + direção + configuração → sequência editável.
- `OcrEngine`: região de imagem → texto e geometria quando suportada; confiança opcional, nunca fabricada.

Não transformar EPUB refluível em páginas de imagens para caber na abstração dos quadrinhos. Progresso percentual é uma projeção do locator; não é a posição canônica.

## 4. Modelo de dados e durabilidade

| Entidade | Dados principais |
| --- | --- |
| Publication | UUID local, título, formato, metadados, capa, origem, estado de disponibilidade |
| Source | URI/cópia interna, nome, tamanho, modificação, permissões e fingerprint gradual |
| ReadingProgress | Documento, locator tipado/versionado, direção, timestamp |
| Bookmark | Documento, locator, rótulo, data |
| PageAnalysis | Identidade de página, dimensões/orientação, hash, versão do modelo/configuração, estado |
| Region | Coordenadas normalizadas, categoria, confiança opcional, sequência |
| RegionOverride | Alteração manual, região original, ordem e versão; separada da inferência |
| OcrResult | Região/crop, hash da imagem, motor/modelo/configuração, idioma, texto |
| ProcessingJob | Documento/página, tipo, checkpoint, estado, tentativa e erro categorizado |

Índices por documento, página e versão; relações com política explícita de exclusão. Nunca colocar livros ou bitmaps no Room. Salvar resultados e checkpoints atomicamente. Trabalho retomado deve ser idempotente. Mudança de arquivo invalida análises relacionadas; mudança de modelo não apaga correções humanas silenciosamente.

Persistir progresso em mudanças semânticas (página/região/trecho) e com debounce em movimentos contínuos. `SavedStateHandle` e estado salvável ajudam a reconstruir a tela; Room sustenta retomada após morte do processo. Não depender apenas de `onPause` ou `onDestroy`.

Migrações do Room devem preservar dados, exportar schemas versionados e ter testes. Proibir migração destrutiva em produção. Exportação de biblioteca deve possuir formato versionado, validação e prévia de conflitos; URIs exportadas podem precisar ser vinculadas novamente em outro aparelho.

## 5. Importação, armazenamento e arquivos

Usar Storage Access Framework e `ACTION_OPEN_DOCUMENT`, persistindo a permissão quando suportada [S3]. Não exigir acesso geral a todos os arquivos. Preparar `ACTION_VIEW` e compartilhamento de arquivo após a importação básica funcionar.

Oferecer dois comportamentos claros: vincular ao arquivo selecionado ou manter uma cópia offline gerenciada. Provedores podem oferecer streams sem seek e conteúdo remoto: usar cache temporário quando necessário, mostrar progresso e permitir cancelamento. A promessa offline aplica-se a arquivos realmente presentes no dispositivo.

Pipeline: identificar assinatura/MIME → validar limites → registrar origem → indexar páginas/metadados → gerar capa → iniciar automaticamente análise de balões de todas as páginas → persistir resultados conforme ficam prontos. Disponibilizar a leitura durante o processamento, priorizando a página atual e as próximas. Mostrar progresso por páginas concluídas e permitir pausar/retomar a análise. O usuário não precisa solicitar a detecção em cada página; OCR integral não faz parte da importação.

- Ordenação natural de nomes, diretórios internos, páginas duplicadas e metadados opcionais.
- Ignorar entradas de sistema e arquivos não reconhecidos; validar dimensões antes da decodificação.
- Limitar entradas, bytes descompactados, dimensão máxima, razão de expansão e tempo de processamento. Fixar limites após corpus inicial e permitir erro recuperável.
- Bloquear path traversal, caminhos absolutos, links e extração fora do diretório privado [S4].
- Falta de espaço, arquivo truncado, senha, extensão falsa e cancelamento precisam de tratamento próprio.
- Reimportação oferece reutilizar ou criar nova entrada. Deduplicação por nome isolado é proibida; hashing completo ocorre quando necessário, sem bloquear a primeira leitura de arquivos enormes.
- Remover da biblioteca preserva original externo. Apagar cópia interna é uma ação distinta e explícita.
- Cache com quota, LRU e limpeza de órfãos; nunca remover progresso ou correções para liberar cache.

## 6. Renderização e experiência no Fold 6

Carregar imagens ajustadas ao viewport; para páginas enormes, avaliar tiles/region decoding. Pré-carregar apenas vizinhos dentro de um orçamento de memória. Cancelar downloads de provedor, decoding e inferências obsoletos ao mudar rapidamente de página.

A transformação de coordenadas deve ser única e testável: imagem original → orientação → entrada do modelo → crop → viewport. Usar coordenadas normalizadas para persistência. O zoom precisa manter o ponto sob o gesto, respeitar limites e restaurar o contexto após sair de um balão.

No guia automático, manter a página na transformação atual e desenhar a lupa em uma camada sobre ela. Enquadrar o balão ativo com uma pequena margem; usar a imagem em resolução adequada para ampliar sem depender da miniatura usada pelo detector. Limitar a lupa à área útil da janela, deslocando-a junto às bordas para evitar cortes e sobreposição aos controles. Um realce discreto liga a ampliação à posição original. Tamanho e ampliação devem se adaptar às telas interna e externa do Fold; valores iniciais serão calibrados visualmente no aparelho. Oferecer ajuste pelo leitor para balões grandes e fontes pequenas, sem forçar a lupa a preencher a tela.

As transições entre balões devem ser curtas, canceláveis e respeitar a preferência por movimento reduzido. Em uma página ainda em análise, mostrar o estado e priorizá-la; em uma página sem regiões detectadas, preservar a leitura normal e oferecer correção manual. Esses estados não podem prender a navegação.

A interface se adapta ao tamanho da janela e à postura, não ao nome do aparelho. Cobrir tela externa/interna, paisagem, janela dividida, redimensionamento e fontes ampliadas. Biblioteca pode mostrar lista e detalhes lado a lado; leitor usa uma ou duas páginas conforme preferência e espaço disponível, respeitando capa e direção RTL. Dobradiças não devem encobrir conteúdo ou controles [S2].

Preservar documento, posição, seleção e configurações ao dobrar/desdobrar. Não bloquear orientação para contornar bugs. Implementar edge-to-edge com insets, gesto de voltar compatível com a plataforma e comportamento coerente de tela cheia.

Acessibilidade: alvos de toque de pelo menos 48 dp, contraste, rótulos e ordem de foco, TalkBack, navegação por teclado, foco visível, alternativa a gestos, animações reduzidas e botões anteriores/próximos. Texto transcrito acessível não substitui descrição da arte. Strings em recursos PT-BR com fallback definido; datas/plurais/localização sem concatenações rígidas.

## 7. Leitura guiada e OCR: pesquisa com critérios objetivos

### Avaliar o risco antes da implementação completa

Montar um corpus inicial de 50–100 páginas autorizadas para avaliação, com balões, caixas de narração, layouts irregulares e imagens de diferentes resoluções. Incluir PT-BR e uma amostra secundária de outros idiomas/RTL. É um conjunto inicial de avaliação, insuficiente para prometer treinamento robusto do zero.

Registrar origem, licença/autorização, gênero visual, dimensões e anotações. Separar treino/ajuste e teste por obra ou edição para reduzir vazamento. Conjunto de teste fica congelado; exemplos difíceis não podem ser removidos apenas para melhorar métricas.

Comparar pelo menos um detector existente executável no Android e uma alternativa simples de localização de texto/regiões. Esta última pode servir de fallback; texto detectado não equivale automaticamente a balão. Comparar OCR ML Kit e Tesseract em crops idênticos com texto de referência e acentos preservados. Registrar versões, parâmetros, aparelho, tempo, memória e falhas.

### Pipeline de produção proposto

1. Ao importar um quadrinho, enfileirar automaticamente suas páginas; priorizar a página em leitura e as próximas. Decodificar numa resolução limitada e registrar transformações.
2. Detectar regiões; tratar sobreposição, duplicatas, balões unidos e caixas narrativas.
3. Resolver sequência usando contexto de painéis e direção configurável. Ordenar apenas por coordenada vertical/horizontal é baseline, não solução final.
4. Persistir regiões e ordem por identidade da página, versão do modelo e configuração. Retomar páginas pendentes após interrupção; reutilizar análises válidas nas próximas aberturas.
5. Mostrar uma ampliação localizada moderada do balão ativo sobre a página, com margem configurável e transição cancelável. Preservar escala/posição da página ao fundo e oferecer anterior/próximo e foco por toque.
6. Executar OCR somente quando solicitado, em resolução apropriada, e armazenar resultado por fingerprint e versão. A navegação guiada funciona com o OCR desativado.
7. Permitir adicionar, redimensionar, remover e reordenar regiões; persistir alterações separadas do modelo.

Texto original do OCR permanece disponível; normalização de espaços e hifenização precisa ser reversível. Não corrigir palavras silenciosamente. Modelo e idioma de OCR não devem determinar direção de leitura automaticamente sem possibilidade de ajuste.

### Critérios propostos para liberar o modo guiado

- Importar um CBZ inicia a detecção sem exigir seleção manual ou comando individual por página; progresso e retomada são observáveis.
- Uma página já analisada reutiliza suas regiões ao reabrir o quadrinho, enquanto arquivo/modelo/configuração forem compatíveis.
- Ativar o guia amplia um balão detectado em uma lupa localizada. A transformação da página permanece estável ao avançar, voltar e fechar a lupa; balões nas bordas continuam visíveis.
- O fluxo de leitura guiada funciona com OCR desativado. Testar balões pequenos/grandes, zero detecções, ordem LTR/RTL e janela redimensionada.

- Recall de regiões ≥95% e precisão ≥90%, usando IoU ≥0,5 como ponto inicial; publicar resultados por categoria de conteúdo.
- ≥90% dos pares consecutivos anotados em ordem correta; medir também a proporção de páginas com sequência inteiramente correta.
- Para liberar o OCR opcional: CER ≤5% em lettering regular PT-BR; registrar separadamente fontes estilizadas e baixa resolução. Medir CER com regras de normalização publicadas e também reportar acentos. Esse critério não bloqueia a leitura guiada sem OCR.
- Proposta no Fold 6: análise de página p95 ≤2 s na resolução de entrada definida; OCR por região p95 ≤1 s após aquecimento. Medir primeira execução separadamente.
- Baixa qualidade mantém fallback manual e rótulo experimental. Se metas não forem atingidas, priorizar seleção por toque e melhoria do detector; não prometer navegação automática confiável.

Treinamento/fine-tuning só será aprovado como etapa técnica após medir a lacuna, verificar licença dos pesos/dataset e estimar anotação/compute. A licença do repositório não prova que pesos e dados podem ser redistribuídos. Registrar procedência e hash dos modelos.

## 8. EPUB e PDF

### EPUB

Spike Readium: importar EPUB 2/3 sem DRM, abrir sumário e links internos, ajustar fonte/tema/margens, selecionar texto e restaurar locator após reflow. Validar integração Compose, recursos empacotados, fontes embutidas e acessibilidade. Cobertura inicial: EPUB refluível; layout fixo terá status próprio.

Conteúdo HTML é entrada não confiável: JavaScript desativado quando possível, recursos locais confinados à publicação, navegação externa mediada pelo app e carregamento remoto desativado por padrão. Links, imagens remotas e scripts não podem contornar o comportamento offline. Registrar limitações do motor escolhido.

### PDF

Avaliar renderização paginada, tiles, rotação, páginas de tamanhos diferentes, documento escaneado, metadados, texto existente e grandes arquivos. Priorizar extração de texto quando disponível; OCR será fallback de imagem. Pesquisa de texto e seleção só entram quando a API escolhida suportar corretamente a faixa de Android declarada.

Fechar recursos nativos e descritores em todos os caminhos, inclusive erro e cancelamento. Renderizadores com dependências nativas entram na matriz de ABI e páginas de memória de 16 KB [S8]. Senhas/DRM e formulários/anotações avançadas ficam fora da primeira cobertura, com mensagens específicas.

## 9. Processamento, desempenho e energia

Tarefas necessárias à página visível usam coroutines vinculadas à sessão de leitura. Processamento persistente adiável usa WorkManager em unidades pequenas, com checkpoints por página, cancelamento e deduplicação. A importação agenda automaticamente a detecção do novo quadrinho, com pausa/retomada pelo usuário. A leitura pode priorizar uma página na mesma fila, evitando inferências duplicadas. Limitar concorrência e dar prioridade à leitura; reprocessar em lote toda a biblioteca existente após trocar o modelo é opt-in.

WorkManager não garante execução contínua. Android 16 aplica quotas que também afetam workers longos; um foreground service não é solução universal [S5]. Validar notificações, tipos de serviço e restrições na versão alvo antes de introduzir processamento prolongado.

Orçamentos propostos para um CBZ local de referência de até 100 páginas, com dimensões e compressão registradas:

| Métrica | Meta inicial | Método |
| --- | --- | --- |
| Primeira página | p95 ≤2 s após seleção, sem cópia remota | Marcadores de tempo e Macrobenchmark quando aplicável |
| Página adjacente em cache | p95 ≤150 ms | Tempo até apresentação |
| Fluidez | Frames lentos <5% em sessão de referência | Frame timing/Perfetto, incluindo telas de 60/120 Hz |
| Memória | Sem OOM no corpus de imagens grandes | Heap/PSS medidos; cache de imagens com teto inicial de min(64 MiB, 20% do heap disponível) |
| Retomada | Locator consistente após encerramento forçado | Teste automatizado e sessão no Fold |
| Estabilidade | Sem ANR/crash nos fluxos de aceitação | Stress de navegação e sessões prolongadas |

Esses valores serão recalibrados na prova técnica, com justificativa registrada. Decoding, OCR e buffers nativos somam memória além do cache. Medir cold/warm runs, temperatura, bateria e dispositivo intermediário; não extrapolar tempos por pontuação Geekbench. Criar Baseline Profiles para início, biblioteca e leitura e comprovar ganho em build release [S9].

## 10. Segurança, privacidade e manutenção

- Manifesto mínimo, componentes exportados apenas quando necessários e validação de URIs/intents de entrada.
- Não registrar páginas, texto OCR, caminhos privados ou conteúdo de livros em logs de produção.
- Diagnóstico local com códigos de erro; exportação voluntária com revisão dos dados pelo usuário.
- Sem analytics ou serviços remotos obrigatórios no primeiro lançamento.
- Configurar explicitamente backup Android: excluir cópias de livros, cache, modelos e URIs sensíveis; decidir/documentar se metadados entram. Oferecer exportação local consciente.
- Nunca executar arquivos importados. Validar arquivos compactados e XML/HTML, bloqueando entidades externas e recursos externos indevidos.
- Segredos e chave de assinatura fora do Git; manter cópia segura da chave e documentar recuperação e rotação suportada.
- Arquivos originais externos não são alterados pelo leitor.
- Inventariar dependências, licenças, avisos e modelos; revisar atualizações e vulnerabilidades periodicamente.
- Bibliotecas nativas devem funcionar em arm64-v8a e no ambiente de testes escolhido; validar alinhamento e runtime em 16 KB, não só instalação [S8].

## 11. Estratégia de testes

| Nível | Cobertura prioritária |
| --- | --- |
| Unitário | Ordenação natural, coordenadas/zoom, locators, ordem de regiões, invalidação de cache, regras de progresso |
| Integração | Importação SAF, streams sem seek, Room/migrações, archives, adaptadores OCR e motores |
| Interface | Importar/abrir/retomar, tema, fonte, seleção, zoom guiado, fallback e acessibilidade |
| Lifecycle | Rotação, mudança de janela, folding, recriação de Activity, morte real do processo e reabertura |
| Robustez | Arquivos corrompidos, zip bombs controladas, path traversal, memória/armazenamento baixos, URI revogada |
| ML | Corpus congelado, métricas por classe/obra, precisão de OCR e regressões entre versões |
| Desempenho | Build release, Macrobenchmark, Perfetto e Baseline Profiles |

`Activity.recreate()` não substitui teste de morte de processo. Confirmar progresso persistido após interrupção real e recuperar permissões/arquivos indisponíveis. Usar StateRestorationTester para estado Compose onde aplicável [S10].

Matriz mínima: Android no SDK mínimo selecionado, uma versão intermediária, versão alvo atual; emulador compacto, tablet, foldable, ambiente 16 KB quando houver código nativo; Fold 6 fechado/aberto/paisagem/multiwindow; fonte ampliada e TalkBack. Emuladores não substituem avaliação de bateria, temperatura e resposta no aparelho físico.

Fixtures no Git devem ser pequenas, sintéticas ou redistribuíveis. Dados privados de teste ficam fora do repositório e dos artefatos CI. Testes validam comportamento e recuperação, evitando repetir detalhes internos de implementação.

## 12. GitHub, CI e distribuição

Branch principal protegida quando disponível, PRs pequenos por entrega vertical, descrição de comportamento e evidência de validação. Cada tarefa tem critério de aceitação; mudanças arquiteturais relevantes recebem ADR curto com contexto, alternativas, decisão e consequências.

### CI em pull requests

1. Validar Gradle Wrapper e integridade/versions das dependências.
2. Compilar, executar testes unitários e Android Lint; formatter/análise estática com configuração versionada.
3. Executar testes instrumentados essenciais em emulador; suites mais caras por caminho alterado ou execução agendada.
4. Publicar relatórios e APK de teste com retenção limitada, sem chaves de produção.
5. Impedir merge de checks obrigatórios falhos; usar permissões mínimas e Actions fixadas por commit revisado.

### Preparação de lançamento

- Gerar APK assinado para teste privado; AAB para Play quando a distribuição for decidida.
- Chave, applicationId e política de versionCode definidos antes da primeira instalação duradoura.
- Testar atualização da versão anterior preservando banco, progresso e preferências.
- R8 habilitado no release; verificar reflection/JNI/motores e manter mapping por versão.
- Testar artefato assinado final, inclusive OCR offline após instalação limpa e reabertura sem rede.
- Changelog, checksum, licenças, política de privacidade, formatos suportados e limitações conhecidos.
- Publicação em loja: confirmar requisitos vigentes de target SDK, declaração de dados, conta de desenvolvedor e assinatura. Não é necessário publicar para validar no Fold.
- Rollback: corrigir com versionCode superior; desabilitar recurso problemático por configuração local quando possível. Não presumir que downgrade de banco/APK seja seguro.

## 13. Backlog executável e dependências

| ID | Entrega | Depende de | Aceitação verificável |
| --- | --- | --- | --- |
| E00 | Ambiente e bootstrap | — | Build debug e release reproduzíveis, Wrapper fixado, instalação em emulador e caminho documentado para Fold |
| E01 | Corpus e benchmark inicial | — | Páginas anotadas, autorização registrada, splits por obra e script de avaliação |
| E02 | Spikes de detector/OCR | E00, E01 | Resultados de qualidade, latência, memória e licenças; ADR de escolha/fallback |
| E03 | Spike EPUB/PDF | E00 | Exemplos renderizados, locators recuperados, compatibilidade e segurança avaliadas |
| E04 | Base de dados e design system | E00 | Schema, tema, strings, navegação e testes de restauração |
| E05 | Importador CBZ/ZIP | E04 | Capa/lista de páginas, erros recuperáveis, limites de extração e cancelamento |
| E06 | Leitor de páginas | E05 | Zoom/pan, direção, progresso, troca rápida e leitura fluida no Fold |
| E07 | Seleção manual e OCR | E02, E06 | Crop correto após zoom/rotação, PT-BR copiável, erro/cancelamento sem bloquear leitura |
| E08 | Detecção na importação e lupa localizada | E02, E04, E06 | Importação inicia análise de todas as páginas com checkpoint; regiões persistidas; lupa moderada por balão sem alterar escala da página; anterior/próximo, correções e retomada; funciona sem OCR; métricas reportadas e modo experimental se necessário |
| E09 | CBR | E05, E06 | RAR4/RAR5 testados conforme suporte anunciado; arquivos inválidos e ABI tratados |
| E10 | EPUB | E03, E04 | Sumário, reflow, fonte/tema, links seguros, bookmarks e locator estável |
| E11 | PDF | E03, E06 | Renderização com memória limitada, retomada, seleção/pesquisa conforme capacidades e OCR de crop |
| E12 | Biblioteca multiformato | E09–E11 | Busca de metadados, filtros, favoritos, marcadores e continuidade entre sessões |
| E13 | Backup/exportação e recuperação | E12 | Export/import versionados, conflitos e relink de fontes sem corrupção |
| E14 | Qualidade e release | E08–E13 | Matriz de dispositivos, acessibilidade, performance, migrações e APK assinado aprovados |

CI básica entra em E00 e acompanha todas as entregas. Segurança, acessibilidade, restauração e testes são parte de cada tarefa, não uma fase final de correção. E01 pode avançar enquanto o ambiente é preparado; EPUB/PDF só começam após o spike confirmar os contratos compartilhados. E08 é prioridade para a próxima alpha e independe da conclusão de E07 (OCR); o protótipo manual não satisfaz sua aceitação.

### Primeiro ciclo de execução

1. Preparar Gradle Wrapper, fixar matriz JDK/AGP/Kotlin/SDK e CI; documentar comandos.
2. Criar shell adaptável e schemas iniciais com migração de referência.
3. Abrir um CBZ de fixture e renderizar uma página, salvando e restaurando posição.
4. Rodar a prova de OCR/detector no corpus e selecionar motor com relatório.
5. Integrar detecção automática na importação, persistência por página e lupa localizada com navegação entre balões.
6. Entregar APK de alpha e roteiro de validação do Fold 6; incorporar resultados antes de expandir formatos.

## 14. Definition of Done

Uma entrega só termina quando seu fluxo de aceitação funciona, os estados de loading/vazio/erro/cancelamento estão definidos, o estado é recuperável, os testes relevantes passam e o código segue as dependências arquiteturais. Novas bibliotecas exigem licença e manutenção avaliadas. Fluxos afetados por folding são validados nas duas telas. Documentação descreve recursos reais e limitações; funcionalidades pendentes não são anunciadas como prontas.

Para 1.0: todos os formatos anunciados têm fixtures e matriz aprovada; nenhum defeito conhecido causa perda de progresso, corrupção de dados ou acesso indevido a arquivos; análise guiada atende metas ou mantém indicação experimental; atualização assinada foi ensaiada; primeira leitura funciona offline com arquivos locais; bugs remanescentes estão registrados com impacto e alternativa.

## 15. Riscos e decisões pendentes

| Risco/decisão | Como resolver | Momento |
| --- | --- | --- |
| Detector insuficiente em HQs PT-BR | Corpus, comparação e fallback manual; fine-tuning só após evidência | E02 |
| Ordem de leitura errada | Separar geometria/ordenação, medir por obra e permitir edição | E08 |
| Modelos com licença inadequada | Verificar código, pesos e datasets individualmente | Antes de incorporar |
| EPUB/PDF incompatíveis com stack | Spike antes de estabilizar contratos e SDK mínimo | E03 |
| Falta de memória em scans | Decode amostrado/tiles, cache limitado e testes adversos | E06 |
| Permissão SAF perdida | Estado indisponível e relink preservando biblioteca | E05/E13 |
| Quotas de background | Detecção incremental do quadrinho importado, checkpoints e pausa/retomada; reanálise global opt-in | E08 |
| Dependência RAR abandonada | Avaliar manutenção, formato e alternativa; documentar suporte parcial | E09 |
| Manutenção de formato e schema | Adaptadores, migrações testadas e fixtures por versão | Contínuo |
| Versões de ferramentas | Fixar combinação compatível e registrar atualização | E00 |
| Identidade e licença do produto | Definir applicationId final, licença do código e política de distribuição | Antes do release assinado |
| Hardware de validação | Resolver execução do ADB e conectar Fold; usar emulador para desenvolvimento | E00 |

Não há necessidade de conta de cloud para o MVP. Uso de compute externo para treinar modelo ou de serviços pagos será uma decisão posterior com custo e dados envolvidos explícitos.

## 16. Estimativa e acompanhamento

Estimativas anteriores da conversa eram aproximações para um escopo menor. O produto multiformato exige uma nova linha de base após E00–E03. Usar esforço relativo: E00/E04 pequenos a médios; E05–E07 médios; E08 alto e incerto; E09–E11 médios a altos conforme motores; E12–E14 médios.

Planejar por marcos demonstráveis, sem prometer datas com base na velocidade de geração de código. Assistência de IA acelera implementação, mas aprovação de qualidade exige builds, corpus, revisão e aparelho real. Após cada marco: atualizar backlog, registrar métricas e recalcular previsão. Reduzir escopo opcional antes de sacrificar durabilidade ou declarar detecção confiável sem evidência.

## 17. Referências técnicas

Fontes oficiais consultadas em 01/11/2026. As escolhas propostas neste plano são decisões de projeto; os links fundamentam capacidades e restrições e não comprovam resultados do Estante.

- [S1 — Android: arquitetura](https://developer.android.com/topic/architecture)
- [S2 — Android: qualidade adaptável](https://developer.android.com/docs/quality-guidelines/adaptive-app-quality)
- [S3 — Android: Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [S4 — Android: Zip Path Traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)
- [S5 — Android: workers de longa duração e quotas](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [S6 — ML Kit: idiomas de OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- [S7 — Readium Kotlin Toolkit](https://readium.org/kotlin-toolkit/latest/)
- [S8 — Android: compatibilidade com páginas de memória de 16 KB](https://developer.android.com/guide/practices/page-sizes)
- [S9 — Android: Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [S10 — Android: testes de janelas e restauração](https://developer.android.com/training/testing/different-screens)
