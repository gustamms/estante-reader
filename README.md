# Estante

Biblioteca e leitor digital para quadrinhos, EPUBs e PDFs, com leitura guiada e OCR local.

## Estado atual

O MVP Android abre quadrinhos em PDF, CBZ e CBR e inicia a detecção local de balões automaticamente. Durante a leitura, uma lupa pequena amplia o balão ativo sobre a própria página; é possível tocar em outro balão ou avançar e voltar. A análise salva resultados por página para reutilizá-los quando o quadrinho for aberto novamente. OCR em português continua opcional.

A estante mostra capas de vários títulos, guarda progresso por quadrinho e aceita importação CBZ/CBR. No leitor, a página abre limpa; tocar em um balão mostra a lupa e tocar no centro vazio revela os controles. A extração CBR é local e prepara as páginas em cache privado.

O leitor também oferece página única, dupla ou automática, com capa separada, ordem LTR/RTL e exibição individual de páginas panorâmicas. O modo automático usa duas páginas em janelas largas e uma em janelas compactas.

A detecção é experimental; qualidade e velocidade ainda precisam ser medidas em quadrinhos reais e no Galaxy Z Fold 6. A análise automática continua ligada ao leitor aberto, sem fila durável após encerrar o app. EPUB e PDF permanecem no roadmap.

## Compilar

Abra o projeto no Android Studio ou use o Gradle Wrapper:

```sh
./gradlew :app:assembleDebug
```

Requisitos: JDK 17 e Android SDK 36. A compilação gera APKs separados por arquitetura em `app/build/outputs/apk/debug/`; para aparelhos ARM64, use `app-arm64-v8a-debug.apk`.

## Planejamento

[Plano executável de leitura automática de balões](docs/PLANO_LEITURA_AUTOMATICA.md) — especificação, estado das etapas e critérios que faltam para validar a entrega automática.

[Plano de implementação Android](docs/PLANO_IMPLEMENTACAO.md) — arquitetura, roadmap, critérios de aceitação, testes, leitura guiada, OCR, EPUB e PDF.
