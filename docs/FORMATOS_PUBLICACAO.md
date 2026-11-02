# Arquitetura de formatos de publicação

O leitor separa a escolha do formato de arquivo da experiência visual de leitura. O registro `PublicationImportRegistry` escolhe um `PublicationImporter` pela extensão ou por assinaturas internas. Cada importador valida e prepara o formato, então devolve metadados, capítulos, capa e capacidades disponíveis.

## Fluxo de importação

1. O seletor Android entrega uma URI. O app copia o arquivo para uma área temporária privada e calcula o SHA-256.
2. `PublicationImportRegistry` procura uma estratégia compatível.
3. EPUB e MOBI são preparados em uma pasta temporária. O original é preservado em `files/library/comics/`; recursos de leitura ficam em `files/library/publications/{id}/`.
4. O app grava metadados e capa na estante só depois de validar o documento. A posição do livro é salva por capítulo e progresso dentro dele.
5. O leitor de texto apresenta capítulos numa WebView offline. Cada solicitação de recurso é limitada à pasta privada daquela publicação.

Os formatos de páginas continuam usando `PageDocumentFactory`. Para integrar um futuro formato, implemente `PublicationImporter`, acrescente a estratégia ao `PublicationImportRegistry` e devolva `ImportedPublication`. Se o formato produzir páginas, ele pode continuar no fluxo `PageDocument`; se produzir texto fluido, pode reaproveitar o leitor de livros.

## Limites e segurança

- EPUB de texto fluido é extraído com limite de quantidade e tamanho total de recursos. Caminhos que escapem da pasta privada são rejeitados.
- O pacote XHTML remove scripts, eventos HTML, formulários, frames e esquemas de URI ativos. A WebView bloqueia solicitações fora da origem local do leitor.
- EPUB de layout fixo e DRM de conteúdo são recusados com uma mensagem legível.
- MOBI aceita PDB/Mobipocket sem DRM com texto sem compressão ou compressão PalmDOC. HUFF/CDIC, KF8/AZW3 e recursos de imagem do MOBI ainda não são suportados.
- MOBI clássico é apresentado como um capítulo único; o formato não expõe uma estrutura de sumário compatível neste importador.
- Busca e posição de leitura são locais. O localizador atual combina o índice do capítulo com a porcentagem de rolagem; alterações de fonte ou largura podem deslocar um pouco o trecho restaurado.

## Capacidades

`PublicationCapabilities` permite construir menus a partir do que o conteúdo oferece. Quadrinhos usam navegação por página, página dupla e leitura guiada. EPUB/MOBI usam sumário, busca, marcadores e ajustes tipográficos; EPUB também permite colunas no modo automático, simples ou duplo.
