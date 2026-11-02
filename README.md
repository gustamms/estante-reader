# Estante

Biblioteca e leitor digital para quadrinhos, EPUBs e PDFs, com leitura guiada e OCR local.

## Estado atual

O app Android abre quadrinhos em PDF, CBZ e CBR e inicia a detecção local de balões automaticamente. Durante a leitura, uma lupa pequena amplia o balão ativo sobre a própria página; é possível tocar em outro balão ou avançar e voltar. A análise salva resultados por página para reutilizá-los quando o quadrinho for aberto novamente. OCR em português continua opcional.

A estante mostra capas de vários títulos, guarda progresso por publicação e aceita importação de PDF, CBZ, CBR, EPUB e MOBI. Livros EPUB de texto fluido e MOBI clássico abrem em um leitor de texto com sumário, busca, marcadores, tamanho de fonte, entrelinha, margens e fundo ajustáveis. O modo automático usa uma coluna em janelas compactas e duas em janelas largas, como a tela interna do Fold 6. Os arquivos e a busca ficam no aparelho; o leitor bloqueia recursos remotos.

O suporte MOBI cobre arquivos sem DRM com compressão PalmDOC ou texto sem compressão. Arquivos Kindle KF8/HUFF/CDIC e outros MOBI protegidos exibem uma mensagem explicando a incompatibilidade; imagens internas de MOBI ainda não são extraídas. EPUB de layout fixo e publicações com DRM também não são aceitos nesta versão. EPUB de texto fluido mantém seus estilos e recursos locais.

No leitor EPUB/MOBI, selecionar um trecho salva o grifo automaticamente no livro atual. Há cinco cores, lista pesquisável e filtrável, navegação até o trecho e remoção com opção de desfazer. A cor padrão dos novos grifos é salva separadamente por livro.

São dez estilos de aparência para a estante, com Madeira clara como padrão; a escolha fica salva nas configurações e a prévia pode ser examinada antes de aplicar. A biblioteca inclui busca, leitura recente e prateleiras adaptáveis. No leitor de quadrinhos, a página abre limpa; tocar em um balão mostra a lupa e tocar no centro vazio revela os controles. A extração CBR é local e prepara as páginas em cache privado.

O leitor também oferece página única, dupla ou automática, com capa separada, ordem LTR/RTL e exibição individual de páginas panorâmicas. O modo automático usa duas páginas em janelas largas e uma em janelas compactas.

A detecção é experimental; qualidade e velocidade ainda precisam ser medidas em quadrinhos reais e no Galaxy Z Fold 6. A análise automática continua ligada ao leitor aberto, sem fila durável após encerrar o app.

## Compilar

Abra o projeto no Android Studio ou use o Gradle Wrapper:

```sh
./gradlew :app:assembleDebug
```

Requisitos: JDK 17 e Android SDK 36. A compilação gera APKs separados por arquitetura em `app/build/outputs/apk/debug/`; para aparelhos ARM64, use `app-arm64-v8a-debug.apk`.

## Planejamento

[Plano executável de leitura automática de balões](docs/PLANO_LEITURA_AUTOMATICA.md) — especificação, estado das etapas e critérios que faltam para validar a entrega automática.

[Plano de implementação Android](docs/PLANO_IMPLEMENTACAO.md) — arquitetura, roadmap, critérios de aceitação, testes, leitura guiada, OCR, EPUB e PDF.

[Arquitetura dos formatos de publicação](docs/FORMATOS_PUBLICACAO.md) — importadores, limites atuais e caminho para adicionar novos formatos.
