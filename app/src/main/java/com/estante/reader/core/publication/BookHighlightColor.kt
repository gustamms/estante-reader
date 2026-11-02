package com.estante.reader.core.publication

/** Stable, user-facing palette IDs are stored with highlights and per-book defaults. */
enum class BookHighlightColor(val id: String, val label: String, val hex: String) {
    AMBER("amber", "Âmbar", "#F5D77A"),
    SAGE("sage", "Sálvia", "#B9D7B0"),
    LAVENDER("lavender", "Lavanda", "#D3C1E7"),
    CORAL("coral", "Coral", "#E9AA97"),
    SKY("sky", "Azul", "#A9CFE5");

    companion object {
        val default: BookHighlightColor = AMBER
        fun fromId(id: String?): BookHighlightColor = entries.firstOrNull { it.id == id } ?: default
    }
}
