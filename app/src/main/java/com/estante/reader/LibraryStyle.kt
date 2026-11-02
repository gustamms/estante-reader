package com.estante.reader

import androidx.compose.ui.graphics.Color

enum class LibraryLayout {
    GRID,
    WOODEN_SHELVES,
    GALLERY,
    COMICS,
}

enum class LibraryStyle(
    val id: String,
    val title: String,
    val description: String,
    val background: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val coverSurface: Color,
    val shelf: Color,
    val layout: LibraryLayout = LibraryLayout.GRID,
    val serif: Boolean = false,
) {
    EDITORIAL(
        "editorial", "Editorial", "Papel claro e composição de revista.",
        Color(0xFFF6F2EA), Color(0xFFFFFDF8), Color(0xFF292722), Color(0xFF817D73), Color(0xFFA95138),
        Color(0xFFE8DFD0), Color(0xFFCEBDA6), serif = true,
    ),
    LIGHT_WOOD(
        "light_wood", "Madeira clara", "Livros organizados em prateleiras de carvalho.",
        Color(0xFFF2E6D2), Color(0xFFFBF6EC), Color(0xFF392B1F), Color(0xFF81705C), Color(0xFF9A572F),
        Color(0xFFE3D2B8), Color(0xFFBD8B57), LibraryLayout.WOODEN_SHELVES, serif = true,
    ),
    NIGHT_LIBRARY(
        "night_library", "Biblioteca noturna", "Carvão, madeira escura e luz quente.",
        Color(0xFF201D1A), Color(0xFF2D2823), Color(0xFFF2E8D9), Color(0xFFB5A797), Color(0xFFD0A65E),
        Color(0xFF40362D), Color(0xFF594333), LibraryLayout.WOODEN_SHELVES, serif = true,
    ),
    GALLERY(
        "gallery", "Galeria", "Capas em destaque com bastante respiro.",
        Color(0xFFF8F8F6), Color(0xFFFFFFFF), Color(0xFF20201E), Color(0xFF777773), Color(0xFF343430),
        Color(0xFFEEEEEB), Color(0xFFD8D8D3), LibraryLayout.GALLERY,
    ),
    PAPER_INK(
        "paper_ink", "Papel e tinta", "Detalhes de uma editora artesanal.",
        Color(0xFFF1EBDD), Color(0xFFFAF7EF), Color(0xFF25333A), Color(0xFF68777D), Color(0xFF315968),
        Color(0xFFE5DDC9), Color(0xFFB7AD98), serif = true,
    ),
    SAGE(
        "sage", "Sálvia", "Tons naturais e uma leitura tranquila.",
        Color(0xFFE9EBDD), Color(0xFFF7F7EF), Color(0xFF30382E), Color(0xFF747B6C), Color(0xFF68755D),
        Color(0xFFE2E5D8), Color(0xFFADB59B),
    ),
    COMIC_SHELF(
        "comic_shelf", "Quadrinoteca", "Uma estante gráfica para quadrinhos.",
        Color(0xFFFFE34D), Color(0xFFFFF3A8), Color(0xFF171713), Color(0xFF514A2C), Color(0xFF171713),
        Color(0xFFFFF7CD), Color(0xFF171713), LibraryLayout.COMICS,
    ),
    CLASSIC(
        "classic", "Clássica", "Bordô, marfim e o charme de um sebo.",
        Color(0xFF4A1F28), Color(0xFFF5EBDD), Color(0xFF321E20), Color(0xFF806A62), Color(0xFF8B3741),
        Color(0xFFE8DCC8), Color(0xFF9A6860), serif = true,
    ),
    MINIMAL(
        "minimal", "Minimalista", "Poucos elementos, capas em primeiro plano.",
        Color(0xFFF5F3EF), Color(0xFFFFFEFC), Color(0xFF252421), Color(0xFF8A8780), Color(0xFF56534D),
        Color(0xFFEAE7E1), Color(0xFFD7D3CC),
    ),
    BLUE_EDITORIAL(
        "blue_editorial", "Azul editorial", "Azul profundo com uma estante luminosa.",
        Color(0xFFE9E8E2), Color(0xFFFAF8F1), Color(0xFF202A37), Color(0xFF777B81), Color(0xFF243C59),
        Color(0xFFE4E1D9), Color(0xFFB8B1A3), LibraryLayout.GALLERY, serif = true,
    );

    companion object {
        val default: LibraryStyle = LIGHT_WOOD

        fun fromId(id: String?): LibraryStyle = entries.firstOrNull { it.id == id } ?: default
    }
}
