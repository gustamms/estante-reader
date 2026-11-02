package com.estante.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estante.reader.core.data.BookHighlightEntity
import com.estante.reader.core.publication.BookHighlightColor
import kotlinx.coroutines.launch

@Composable
internal fun BookHighlightsPanel(
    modifier: Modifier = Modifier,
    bookTitle: String,
    chapters: List<String>,
    highlights: List<BookHighlightEntity>,
    defaultColorId: String,
    onClose: () -> Unit,
    onDefaultColorChange: (String) -> Unit,
    onHighlightColorChange: (String, String) -> Unit,
    onOpenHighlight: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRestoreHighlight: (BookHighlightEntity) -> Unit,
) {
    var query by remember(bookTitle) { mutableStateOf("") }
    var filterColorId by remember(bookTitle) { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val filtered = remember(highlights, chapters, query, filterColorId) {
        highlights.asSequence()
            .filter { filterColorId == null || it.colorId == filterColorId }
            .filter { item ->
                query.isBlank() || item.selectedText.contains(query, ignoreCase = true) ||
                    chapters.getOrNull(item.chapterIndex).orEmpty().contains(query, ignoreCase = true)
            }
            .toList()
    }

    Surface(
        modifier = modifier,
        color = Color(0xFFF7F3EB),
        contentColor = Color(0xFF292722),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        shadowElevation = 14.dp,
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Grifos", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "$bookTitle · ${highlights.size} ${if (highlights.size == 1) "trecho" else "trechos"}",
                        color = Color(0xFF706B62), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onClose) { Text("Fechar", color = Color(0xFFA95138)) }
            }
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
                placeholder = { Text("Buscar nos grifos") },
                shape = RoundedCornerShape(14.dp),
            )
            Text("Cor dos próximos grifos", modifier = Modifier.padding(top = 15.dp, bottom = 7.dp), color = Color(0xFF706B62), fontSize = 12.sp)
            ColorPickerRow(selectedColorId = defaultColorId, onSelect = onDefaultColorChange)
            HorizontalDivider(Modifier.padding(vertical = 13.dp), color = Color(0xFFE5DED2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtrar", color = Color(0xFF706B62), fontSize = 12.sp)
                TextButton(onClick = { filterColorId = null }) {
                    Text("Todas", color = if (filterColorId == null) Color(0xFFA95138) else Color(0xFF706B62), fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                BookHighlightColor.entries.forEach { color ->
                    ColorDot(
                        color = color,
                        selected = filterColorId == color.id,
                        modifier = Modifier.padding(start = 5.dp),
                        onClick = { filterColorId = if (filterColorId == color.id) null else color.id },
                    )
                }
            }
            if (filtered.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (highlights.isEmpty()) "Selecione um trecho do texto para grifá-lo automaticamente."
                        else "Nenhum grifo encontrado.",
                        color = Color(0xFF706B62), fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(filtered, key = { _, item -> item.id }) { _, item ->
                        HighlightCard(
                            highlight = item,
                            chapterTitle = chapters.getOrNull(item.chapterIndex) ?: "Capítulo ${item.chapterIndex + 1}",
                            onOpen = { onOpenHighlight(item.id) },
                            onColorChange = { colorId -> onHighlightColorChange(item.id, colorId) },
                            onRemove = {
                                onRemoveHighlight(item.id)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar("Grifo removido", "Desfazer")
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onRestoreHighlight(item)
                                }
                            },
                        )
                    }
                }
            }
            SnackbarHost(snackbarHostState)
        }
    }
}

@Composable
private fun HighlightCard(
    highlight: BookHighlightEntity,
    chapterTitle: String,
    onOpen: () -> Unit,
    onColorChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val color = BookHighlightColor.fromId(highlight.colorId)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(start = 13.dp, end = 10.dp, top = 11.dp, bottom = 9.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 2.dp).size(width = 4.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)).background(Color(android.graphics.Color.parseColor(color.hex))))
                Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                    Text(
                        highlight.selectedText,
                        color = Color(0xFF292722), fontSize = 14.sp, lineHeight = 20.sp,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    Text(chapterTitle, modifier = Modifier.padding(top = 7.dp), color = Color(0xFF8B6253), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onRemove, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("Remover", color = Color(0xFF8A5546), fontSize = 11.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 13.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("Cor", color = Color(0xFF8B867C), fontSize = 11.sp)
                BookHighlightColor.entries.forEach { option ->
                    ColorDot(option, selected = option.id == highlight.colorId, onClick = { onColorChange(option.id) })
                }
                Spacer(Modifier.weight(1f))
                Text("Abrir", color = Color(0xFFA95138), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ColorPickerRow(selectedColorId: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookHighlightColor.entries.forEach { color ->
            ColorDot(color, selected = color.id == selectedColorId, onClick = { onSelect(color.id) }, showLabel = true)
        }
    }
}

@Composable
private fun ColorDot(
    color: BookHighlightColor,
    selected: Boolean,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick).semantics {
            contentDescription = "Cor ${color.label}"
            this.selected = selected
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(if (showLabel) 30.dp else 23.dp)
                .clip(CircleShape)
                .background(Color(android.graphics.Color.parseColor(color.hex)))
                .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFFA95138) else Color(0xFFC8C1B5), CircleShape)
        )
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(color.label, color = if (selected) Color(0xFFA95138) else Color(0xFF706B62), fontSize = 10.sp)
        }
    }
}
