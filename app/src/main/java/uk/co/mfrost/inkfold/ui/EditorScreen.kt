package uk.co.mfrost.inkfold.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.viewinterop.AndroidView
import uk.co.mfrost.inkfold.drawing.DrawingView
import uk.co.mfrost.inkfold.drawing.EraserMode
import uk.co.mfrost.inkfold.drawing.ToolSettings
import uk.co.mfrost.inkfold.export.Exporter
import uk.co.mfrost.inkfold.model.Page
import uk.co.mfrost.inkfold.model.PageOrientation
import uk.co.mfrost.inkfold.model.PageType
import uk.co.mfrost.inkfold.model.ToolType
import uk.co.mfrost.inkfold.storage.Prefs
import kotlinx.coroutines.launch

private val PRESET_COLORS = listOf(
    Color(0xFF000000), Color(0xFF455A64), Color(0xFFD32F2F), Color(0xFFF57C00),
    Color(0xFFFBC02D), Color(0xFF388E3C), Color(0xFF1976D2), Color(0xFF7B1FA2),
)

// Hue ring for the colour wheel: 0..360° at red, yellow, green, cyan, blue, magenta, red.
private val HUE_COLORS = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)

@Composable
fun EditorScreen(vm: AppViewModel) {
    val notebook = vm.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val prefs = remember { Prefs(context) }
    var settings by remember { mutableStateOf(prefs.loadSettings()) }
    var penMode by remember { mutableStateOf(prefs.penMode) }
    var drawingView by remember { mutableStateOf<DrawingView?>(null) }
    var pageCount by remember(notebook.id) { mutableStateOf(notebook.pages.size) }
    var showRename by remember { mutableStateOf(false) }
    var editingPage by remember { mutableStateOf<Page?>(null) }
    var editingText by remember { mutableStateOf("") }
    var appliedReloadKey by remember { mutableStateOf(vm.reloadKey) }

    fun openMarkdown(page: Page) {
        editingText = page.markdown
        editingPage = page
    }

    LaunchedEffect(vm.statusMessage) {
        vm.statusMessage?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.statusMessage = null
        }
    }

    // Save a PDF to a user-chosen location via the system file picker.
    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) scope.launch {
            val pdf = Exporter.exportPdf(context, notebook)
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    pdf.inputStream().use { it.copyTo(out) }
                }
            }.isSuccess
            vm.statusMessage = if (ok) "PDF saved" else "Couldn't save PDF"
        }
    }

    fun share(mime: String, produce: suspend () -> List<java.io.File>) {
        scope.launch {
            val files = produce()
            if (files.isEmpty()) return@launch
            val uris = ArrayList(files.map { Exporter.uriFor(context, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Export ${notebook.title}"))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Full-screen drawing surface.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                DrawingView(ctx).also { view ->
                    view.onChanged = { vm.saveCurrent() }
                    view.onStructureChanged = { pageCount = view.pageCount() }
                    view.onMarkdownTap = { page -> openMarkdown(page) }
                    drawingView = view
                }
            },
            update = { view ->
                if (view.tag != notebook.id || appliedReloadKey != vm.reloadKey) {
                    view.setNotebook(notebook)
                    view.tag = notebook.id
                    appliedReloadKey = vm.reloadKey
                    pageCount = notebook.pages.size
                }
                view.settings = settings
                view.penMode = penMode
            },
        )

        // Floating top toolbar overlaid on the canvas.
        EditorTopBar(
            title = notebook.title,
            pageCount = pageCount,
            penMode = penMode,
            onTogglePenMode = { penMode = !penMode; prefs.penMode = penMode },
            onBack = { vm.close() },
            onTitleClick = { showRename = true },
            onUndo = { drawingView?.undo() },
            onRedo = { drawingView?.redo() },
            onAddPage = { drawingView?.addPage(it) },
            onAddMarkdownPage = {
                drawingView?.addPage(PageOrientation.PORTRAIT, PageType.MARKDOWN)?.let { openMarkdown(it) }
            },
            onSharePdf = { share("application/pdf") { listOf(Exporter.exportPdf(context, notebook)) } },
            onSavePdf = { savePdf.launch("${notebook.title}.pdf") },
            onExportJpg = { share("image/jpeg") { Exporter.exportJpgs(context, notebook) } },
            onSync = { vm.sync() },
            syncing = vm.syncing,
            onMakePortrait = { drawingView?.setFocusedPageOrientation(PageOrientation.PORTRAIT) },
            onMakeLandscape = { drawingView?.setFocusedPageOrientation(PageOrientation.LANDSCAPE) },
            onDeletePage = { drawingView?.deleteFocusedPage() },
            onResetZoom = { drawingView?.resetView() },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Floating tool panel overlaid at the bottom.
        ToolPanel(
            settings = settings,
            onSettingsChange = { settings = it; prefs.saveSettings(it) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp),
        )
    }

    if (showRename) {
        TextPromptDialog(
            title = "Rename notebook",
            initial = notebook.title,
            confirmLabel = "Rename",
            onConfirm = { showRename = false; vm.rename(it) },
            onDismiss = { showRename = false },
        )
    }

    if (vm.pendingRemoteDoc != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissRemoteReload() },
            title = { Text("Updated on another device") },
            text = {
                Text(
                    "This document was changed elsewhere and synced from Drive. " +
                        "Reload the latest version? Changes you've made here since will be replaced."
                )
            },
            confirmButton = { TextButton(onClick = { vm.acceptRemoteReload() }) { Text("Reload") } },
            dismissButton = { TextButton(onClick = { vm.dismissRemoteReload() }) { Text("Keep mine") } },
        )
    }

    editingPage?.let { page ->
        MarkdownEditorOverlay(
            text = editingText,
            onTextChange = { editingText = it },
            onCancel = { editingPage = null },
            onDone = {
                page.markdown = editingText
                drawingView?.refreshMarkdown()
                vm.saveCurrent()
                editingPage = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownEditorOverlay(
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Text(
                    "Edit text",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = onDone) { Text("Done") }
            }
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                placeholder = { Text("Write Markdown… # heading, **bold**, - bullet") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    pageCount: Int,
    penMode: Boolean,
    onTogglePenMode: () -> Unit,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPage: (PageOrientation) -> Unit,
    onAddMarkdownPage: () -> Unit,
    onSharePdf: () -> Unit,
    onSavePdf: () -> Unit,
    onExportJpg: () -> Unit,
    onSync: () -> Unit,
    syncing: Boolean,
    onMakePortrait: () -> Unit,
    onMakeLandscape: () -> Unit,
    onDeletePage: () -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f).clickable { onTitleClick() }) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$pageCount page${if (pageCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onTogglePenMode) {
                Icon(
                    if (penMode) Icons.Filled.Edit else Icons.Filled.TouchApp,
                    contentDescription = if (penMode) "Pen mode: stylus draws, finger scrolls" else "Finger mode: touch draws",
                    tint = if (penMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = { addMenu = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add page")
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("Add portrait page") }, onClick = {
                    addMenu = false; onAddPage(PageOrientation.PORTRAIT)
                })
                DropdownMenuItem(text = { Text("Add landscape page") }, onClick = {
                    addMenu = false; onAddPage(PageOrientation.LANDSCAPE)
                })
                DropdownMenuItem(text = { Text("Add text (Markdown) page") }, onClick = {
                    addMenu = false; onAddMarkdownPage()
                })
            }
            IconButton(onClick = onSync, enabled = !syncing) {
                Icon(Icons.Filled.CloudSync, contentDescription = "Sync")
            }
            IconButton(onClick = { overflow = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(text = { Text("Save PDF to device") }, onClick = { overflow = false; onSavePdf() })
                DropdownMenuItem(text = { Text("Share PDF") }, onClick = { overflow = false; onSharePdf() })
                DropdownMenuItem(text = { Text("Share JPG (per page)") }, onClick = { overflow = false; onExportJpg() })
                DropdownMenuItem(text = { Text("Make page portrait") }, onClick = { overflow = false; onMakePortrait() })
                DropdownMenuItem(text = { Text("Make page landscape") }, onClick = { overflow = false; onMakeLandscape() })
                DropdownMenuItem(text = { Text("Delete current page") }, onClick = { overflow = false; onDeletePage() })
                DropdownMenuItem(text = { Text("Reset zoom") }, onClick = { overflow = false; onResetZoom() })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolPanel(
    settings: ToolSettings,
    onSettingsChange: (ToolSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    // When set, shows the colour-wheel picker seeded with this ARGB value.
    var pickerSeed by remember { mutableStateOf<Int?>(null) }

    fun applyColor(argb: Int) = onSettingsChange(
        if (settings.tool == ToolType.PEN) settings.copy(penColor = argb)
        else settings.copy(highlighterColor = argb)
    )
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolChip("Pen", settings.tool == ToolType.PEN) {
                    onSettingsChange(settings.copy(tool = ToolType.PEN))
                }
                ToolChip("Highlighter", settings.tool == ToolType.HIGHLIGHTER) {
                    onSettingsChange(settings.copy(tool = ToolType.HIGHLIGHTER))
                }
                ToolChip("Eraser", settings.tool == ToolType.ERASER) {
                    onSettingsChange(settings.copy(tool = ToolType.ERASER))
                }
            }

            if (settings.tool != ToolType.ERASER) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val selected = if (settings.tool == ToolType.PEN) settings.penColor else settings.highlighterColor
                    PRESET_COLORS.forEach { c ->
                        val argb = c.toArgb()
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(c)
                                .border(
                                    width = if (argb == selected) 3.dp else 1.dp,
                                    color = if (argb == selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape,
                                )
                                .combinedClickable(
                                    onClick = { applyColor(argb) },
                                    onLongClick = { pickerSeed = argb },
                                ),
                        ) {}
                    }
                    // Custom colour: long-press any swatch, or tap this to open the wheel.
                    val custom = selected !in PRESET_COLORS.map { it.toArgb() }
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(selected))
                            .border(
                                width = if (custom) 3.dp else 1.dp,
                                color = if (custom) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape,
                            )
                            .combinedClickable(
                                onClick = { pickerSeed = selected },
                                onLongClick = { pickerSeed = selected },
                            ),
                    ) {
                        Text(
                            "+",
                            color = if (custom) Color.White else Color.Gray,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            } else {
                Row(
                    Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolChip("Delete objects", settings.eraserMode == EraserMode.OBJECT) {
                        onSettingsChange(settings.copy(eraserMode = EraserMode.OBJECT))
                    }
                    ToolChip("Rub out to white", settings.eraserMode == EraserMode.INK) {
                        onSettingsChange(settings.copy(eraserMode = EraserMode.INK))
                    }
                }
            }

            val (label, value, range) = when (settings.tool) {
                ToolType.PEN -> Triple("Pen size", settings.penSize, 1f..30f)
                ToolType.HIGHLIGHTER -> Triple("Highlighter size", settings.highlighterSize, 8f..60f)
                ToolType.ERASER, ToolType.ERASE_INK -> Triple("Eraser size", settings.eraserSize, 10f..120f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(110.dp))
                Slider(
                    value = value,
                    valueRange = range,
                    onValueChange = {
                        onSettingsChange(
                            when (settings.tool) {
                                ToolType.PEN -> settings.copy(penSize = it)
                                ToolType.HIGHLIGHTER -> settings.copy(highlighterSize = it)
                                ToolType.ERASER, ToolType.ERASE_INK -> settings.copy(eraserSize = it)
                            }
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    pickerSeed?.let { seed ->
        ColorWheelDialog(
            initial = seed,
            onDismiss = { pickerSeed = null },
            onConfirm = { picked -> applyColor(picked); pickerSeed = null },
        )
    }
}

/** Full HSV colour picker: a hue/saturation wheel plus a brightness slider. */
@Composable
private fun ColorWheelDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableStateOf(start[0]) }
    var sat by remember { mutableStateOf(start[1]) }
    var value by remember { mutableStateOf(start[2]) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val current = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun updateFrom(pos: Offset) {
        val cx = boxSize.width / 2f
        val cy = boxSize.height / 2f
        if (cx <= 0f) return
        val dx = pos.x - cx
        val dy = pos.y - cy
        val radius = min(cx, cy)
        sat = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        var h = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (h < 0f) h += 360f
        hue = h
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(current or (0xFF shl 24)) }) { Text("Select") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick colour") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(220.dp)
                        .onSizeChanged { boxSize = it }
                        .pointerInput(Unit) { detectTapGestures { updateFrom(it) } }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ -> updateFrom(change.position); change.consume() }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(Brush.sweepGradient(HUE_COLORS, center), r, center)
                        drawCircle(
                            Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r),
                            r, center,
                        )
                        if (value < 1f) drawCircle(Color.Black.copy(alpha = 1f - value), r, center)
                        // Selection ring (white over black for contrast on any hue).
                        val ang = Math.toRadians(hue.toDouble())
                        val rr = sat * r
                        val sel = Offset(
                            center.x + (rr * cos(ang)).toFloat(),
                            center.y + (rr * sin(ang)).toFloat(),
                        )
                        drawCircle(Color.Black, 8.dp.toPx(), sel, style = Stroke(width = 3.dp.toPx()))
                        drawCircle(Color.White, 8.dp.toPx(), sel, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Brightness", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(86.dp))
                    Slider(value = value, onValueChange = { value = it }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(current))
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp),
    )
}
