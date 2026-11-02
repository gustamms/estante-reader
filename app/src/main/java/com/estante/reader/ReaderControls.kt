package com.estante.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estante.reader.core.guided.ReadingDirection
import com.estante.reader.core.reader.PageDisplayMode
import kotlin.math.roundToInt

enum class ReaderCanvasBackground(val id: String, val label: String, val color: Color) {
    DARK("dark", "Escuro", Color(0xFF181816)),
    PAPER("paper", "Papel", Color(0xFFF3EBDD)),
    GRAY("gray", "Cinza", Color(0xFF777873)),
    LIGHT("light", "Claro", Color(0xFFFDFDFC));

    companion object {
        val default = DARK
        fun fromId(id: String?): ReaderCanvasBackground = entries.firstOrNull { it.id == id } ?: default
    }
}

@Composable
internal fun ReaderControlsOverlay(
    state: ReaderUiState,
    isGuidedMode: Boolean,
    activeRegionIndex: Int,
    regionCount: Int,
    isAnalysisRunning: Boolean,
    analyzedPageCount: Int,
    totalAnalysisPageCount: Int,
    canTurnBackward: Boolean,
    canTurnForward: Boolean,
    pageLabel: String,
    onCloseControls: () -> Unit,
    onCloseReader: () -> Unit,
    onPageChange: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSetPageDisplayMode: (PageDisplayMode) -> Unit,
    onToggleDirection: () -> Unit,
    onSetGuidedMode: (Boolean) -> Unit,
    onPreviousRegion: () -> Unit,
    onNextRegion: () -> Unit,
    onSetSeparateCover: (Boolean) -> Unit,
    onSetPanoramasAlone: (Boolean) -> Unit,
    onSetCanvasBackground: (ReaderCanvasBackground) -> Unit,
    onSelectRegion: (Boolean) -> Unit,
    onZoom: () -> Unit,
    onRecognize: () -> Unit,
    onCancelSelection: () -> Unit,
    onExitZoom: () -> Unit,
    onCopyText: (String) -> Unit,
) {
    var advancedExpanded by remember(state.title) { mutableStateOf(false) }
    var sliderPage by remember(state.title, state.pageIndex) { mutableFloatStateOf(state.pageIndex.toFloat()) }
    val background = ReaderCanvasBackground.fromId(state.readerCanvasBackgroundId)
    val direction = if (state.readingDirection == ReadingDirection.LEFT_TO_RIGHT) "Esquerda → direita" else "Direita → esquerda"

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelMaxHeight = minOf(380.dp, maxHeight * 0.60f)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onCloseControls),
        )
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            color = Color(0xFF211F1B),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCloseReader, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("‹ Estante", color = Color.White, fontSize = 14.sp)
                }
                Text(
                    state.title.orEmpty(),
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                )
                TextButton(onClick = onCloseControls, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("Fechar", color = Color(0xFFF2D28A), fontSize = 13.sp)
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = panelMaxHeight)
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = {}),
            color = Color(0xFFFFF8EC),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 14.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.selectionMode) {
                    SelectionActions(
                        hasSelection = state.selection != null,
                        isRecognizing = state.isRecognizing,
                        isPreparingZoom = state.isPreparingZoom,
                        onCancel = onCancelSelection,
                        onZoom = onZoom,
                        onRecognize = onRecognize,
                    )
                } else {
                    PageProgressControl(
                        state = state,
                        sliderPage = sliderPage,
                        pageLabel = pageLabel,
                        canTurnBackward = canTurnBackward,
                        canTurnForward = canTurnForward,
                        onSliderPageChange = { sliderPage = it },
                        onPageChange = onPageChange,
                        onPrevious = onPreviousPage,
                        onNext = onNextPage,
                    )
                    SectionLabel("Modo de página")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageModeChoice("Uma página", PageDisplayMode.SINGLE_PAGE, state.pageDisplayMode, Modifier.weight(1f), onSetPageDisplayMode)
                        PageModeChoice("Duas páginas", PageDisplayMode.DOUBLE_PAGE, state.pageDisplayMode, Modifier.weight(1f), onSetPageDisplayMode)
                        PageModeChoice("Automático", PageDisplayMode.AUTOMATIC, state.pageDisplayMode, Modifier.weight(1f), onSetPageDisplayMode)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        DirectionChoice(direction, Modifier.weight(1f), onToggleDirection)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Zoom dos balões", color = ReaderInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Switch(checked = isGuidedMode, onCheckedChange = onSetGuidedMode, modifier = Modifier.semantics { contentDescription = "Zoom dos balões" })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Fundo da leitura", color = ReaderInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(background.label, color = ReaderMuted, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReaderCanvasBackground.entries.forEach { option ->
                                BackgroundChoice(option, selected = option == background, onClick = { onSetCanvasBackground(option) })
                            }
                        }
                    }
                    TextButton(onClick = { advancedExpanded = !advancedExpanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 2.dp)) {
                        Text(if (!advancedExpanded) "Mais ajustes  ﹀" else "Mais ajustes  ︿", color = ReaderMuted, fontSize = 12.sp)
                    }
                    if (advancedExpanded) {
                        if (state.pageDisplayMode != PageDisplayMode.SINGLE_PAGE) {
                            PreferenceSwitchRow("Mostrar capa sozinha", state.separateCover, onSetSeparateCover)
                            PreferenceSwitchRow("Mostrar panorâmicas sozinhas", state.panoramasAlone, onSetPanoramasAlone)
                        }
                        if (regionCount > 0 && isGuidedMode) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Balão ${activeRegionIndex.coerceAtLeast(0) + 1} de $regionCount", color = ReaderInk, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = onPreviousRegion) { Text("Anterior", color = ReaderInk) }
                                    Button(onClick = onNextRegion, colors = ButtonDefaults.buttonColors(containerColor = ReaderAccent)) { Text("Próximo") }
                                }
                            }
                        }
                        TextButton(onClick = { onSelectRegion(true) }, enabled = state.bitmap != null && !state.isLoading) {
                            Text("Selecionar balão manualmente", color = ReaderAccent)
                        }
                    }
                    if (isAnalysisRunning) {
                        Text(
                            if (totalAnalysisPageCount > 0) "Preparando balões · $analyzedPageCount/$totalAnalysisPageCount" else "Preparando balões…",
                            color = ReaderMuted,
                            fontSize = 12.sp,
                        )
                    } else if (regionCount == 0 && analyzedPageCount > 0) {
                        Text("Nenhum balão encontrado nesta página. Use “Mais ajustes” para selecionar uma região.", color = ReaderMuted, fontSize = 12.sp)
                    }
                    state.error?.let { InlineReaderMessage(it) }
                    state.analysisError?.let { InlineReaderMessage(it) }
                }
                if (state.isLoading && state.bitmap != null) InlineReaderMessage("Carregando página…")
                if (state.isPreparingZoom) InlineReaderMessage("Preparando ampliação…")
                if (state.recognizedText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.recognizedText, Modifier.weight(1f), color = ReaderInk, fontSize = 13.sp)
                        if (state.guidedBitmap != null) TextButton(onClick = onExitZoom) { Text("Voltar") }
                        TextButton(onClick = { onCopyText(state.recognizedText) }) { Text("Copiar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageProgressControl(
    state: ReaderUiState,
    sliderPage: Float,
    pageLabel: String,
    canTurnBackward: Boolean,
    canTurnForward: Boolean,
    onSliderPageChange: (Float) -> Unit,
    onPageChange: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onPrevious, enabled = canTurnBackward, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("‹", color = ReaderInk, fontSize = 24.sp) }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Progresso")
                Text(pageLabel, color = ReaderInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = sliderPage.coerceIn(0f, (state.pageCount - 1).coerceAtLeast(0).toFloat()),
                onValueChange = onSliderPageChange,
                onValueChangeFinished = { onPageChange(sliderPage.roundToInt().coerceIn(0, state.pageCount - 1)) },
                valueRange = 0f..(state.pageCount - 1).coerceAtLeast(1).toFloat(),
                enabled = state.pageCount > 1 && !state.isLoading,
            )
        }
        TextButton(onClick = onNext, enabled = canTurnForward, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text("›", color = ReaderInk, fontSize = 24.sp) }
    }
}

@Composable
private fun PageModeChoice(label: String, mode: PageDisplayMode, selected: PageDisplayMode, modifier: Modifier, onSelect: (PageDisplayMode) -> Unit) {
    val isSelected = mode == selected
    Surface(
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = label; role = Role.RadioButton }
            .clickable(role = Role.RadioButton, onClick = { onSelect(mode) }),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) ReaderAccent else Color(0xFFF1EADF),
        border = BorderStroke(1.dp, if (isSelected) ReaderAccent else Color(0xFFD9D0C2)),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (isSelected) Color.White else ReaderInk, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun DirectionChoice(label: String, modifier: Modifier, onToggle: () -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = "Direção da leitura: $label. Toque para alternar." },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF1EADF),
        border = BorderStroke(1.dp, Color(0xFFD9D0C2)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text("Direção", color = ReaderMuted, fontSize = 10.sp)
            Text(label, color = ReaderInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun BackgroundChoice(option: ReaderCanvasBackground, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) ReaderAccent else Color(0xFFBDB5A9)
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Fundo ${option.label}${if (selected) ", selecionado" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(Modifier.size(32.dp), shape = CircleShape, color = option.color, border = BorderStroke(if (selected) 3.dp else 1.dp, borderColor)) {}
    }
}

@Composable
private fun PreferenceSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = ReaderInk, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SelectionActions(hasSelection: Boolean, isRecognizing: Boolean, isPreparingZoom: Boolean, onCancel: () -> Unit, onZoom: () -> Unit, onRecognize: () -> Unit) {
    SectionLabel("Selecionar balão")
    Text(if (hasSelection) "Região selecionada" else "Arraste sobre um balão na página", color = ReaderInk, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar", color = ReaderInk) }
        Button(onClick = onZoom, enabled = hasSelection && !isPreparingZoom, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ReaderAccent)) { Text("Ampliar") }
        Button(onClick = onRecognize, enabled = hasSelection && !isRecognizing, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ReaderInk)) { Text(if (isRecognizing) "Lendo…" else "Ler texto") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = ReaderMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
}

@Composable
private fun InlineReaderMessage(message: String) {
    Surface(Modifier.fillMaxWidth(), color = Color(0xFFF4E8D6), shape = RoundedCornerShape(10.dp)) {
        Text(message, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = ReaderInk, fontSize = 12.sp)
    }
}

private val ReaderInk = Color(0xFF292722)
private val ReaderMuted = Color(0xFF706B62)
private val ReaderAccent = Color(0xFFA95138)
