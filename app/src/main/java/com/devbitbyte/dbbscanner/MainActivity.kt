package com.devbitbyte.pdfworkspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as NativeColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

private val Sky = Color(0xFF53BCEB)
private val Sunny = Color(0xFFFFD64D)
private val Ink = Color(0xFF14283D)

data class SourcePdf(val id: String, val name: String, val file: File, val count: Int)
data class PagePick(val id: String, val sourceId: String, val index: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent { PdfWorkspace() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfWorkspace() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sources = remember { mutableStateListOf<SourcePdf>() }
    val output = remember { mutableStateListOf<PagePick>() }
    var selected by remember { mutableStateOf<String?>(null) }
    var pageTab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf("") }
    var clearOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    var removePdfId by remember { mutableStateOf<String?>(null) }
    var longPressed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var previewPage by remember { mutableStateOf<Pair<String, Int>?>(null) }

    fun togglePage(sourceId: String, index: Int) {
        val matches = output.filter { it.sourceId == sourceId && it.index == index }
        if (matches.isEmpty()) {
            output.add(PagePick(UUID.randomUUID().toString(), sourceId, index))
        } else {
            output.removeAll(matches.toSet())
        }
    }


    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { target ->
        if (target != null) {
            val copy = sources.toList()
            val pages = output.toList()
            scope.launch {
                busy = true
                val result = runCatching {
                    withContext(Dispatchers.IO) { writePdf(context, target, copy, pages) }
                }
                busy = false
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { "PDF saved successfully: " + pages.size + " pages" },
                        onFailure = { "Save failed: " + (it.message ?: "Unknown error") }
                    )
                )
            }
        }
    }
    val openPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = true
                var imported = 0
                var failed = 0
                for (uri in uris) {
                    try {
                        val doc = withContext(Dispatchers.IO) { cacheSource(context, uri) }
                        sources.add(doc)
                        if (selected == null) selected = doc.id
                        imported++
                    } catch (_: Exception) {
                        failed++
                    }
                }
                busy = false
                snackbar.showSnackbar("Imported " + imported + " PDF(s)" +
                    if (failed > 0) "; " + failed + " could not be opened" else "")
            }
        }
    }
    val scheme = if (dark) darkColorScheme(primary = Sunny, secondary = Sky)
                 else lightColorScheme(primary = Ink, secondary = Sky, background = Color(0xFFF5FAFE))
    MaterialTheme(colorScheme = scheme) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Row(
                    Modifier.fillMaxWidth().background(if (dark) Color(0xFF173044) else Sky)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = Sunny, shape = RoundedCornerShape(12.dp)) {
                        Text("DBB", Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            color = Ink, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PDF Workspace", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = if (dark) Color.White else Ink)
                        Text("CGPSC paper preparation", fontSize = 12.sp,
                            color = if (dark) Color.White else Ink)
                    }
                    TextButton(onClick = { dark = !dark }) {
                        Text(if (dark) "Light" else "Dark",
                            color = if (dark) Sunny else Ink)
                    }
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openPicker.launch(arrayOf("application/pdf")) },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Sunny, contentColor = Ink)
                        ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add PDFs") }
                        OutlinedButton(onClick = { renameOpen = true }, enabled = !busy) {
                            Text("Smart Rename")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (output.isEmpty()) scope.launch { snackbar.showSnackbar("Add pages first") }
                                else savePicker.launch(savedName.ifBlank { "DBB_PDF_Workspace.pdf" })
                            },
                            enabled = !busy && output.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Save PDF") }
                        OutlinedButton(
                            onClick = { clearOpen = true }, enabled = !busy && output.isNotEmpty()
                        ) { Text("Clear") }
                        OutlinedButton(
                            onClick = { resetOpen = true }, enabled = !busy && sources.isNotEmpty()
                        ) { Text("Reset") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (savedName.isNotBlank()) {
                        Text(savedName, fontSize = 11.sp, maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 7.dp))
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = pageTab == 0, onClick = { pageTab = 0 },
                        label = { Text("Source PDFs (" + sources.size + ")") }
                    )
                    FilterChip(
                        selected = pageTab == 1, onClick = { pageTab = 1 },
                        label = { Text("Final pages (" + output.size + ")") }
                    )
                }
                if (pageTab == 0) {
                    if (sources.isEmpty()) {
                        EmptyState("Import your question paper and answer-key PDFs to begin.")
                    } else {
                        LazyRow(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sources.size) { ix ->
                                val doc = sources[ix]
                                FilterChip(
                                    selected = selected == doc.id,
                                    onClick = { selected = doc.id },
                                    label = {
                                        Text(doc.name + "  ·  " + doc.count + "p",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                )
                            }
                        }
                        val src = sources.firstOrNull { it.id == selected }
                        if (src != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(src.name, Modifier.weight(1f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                TextButton(onClick = { removePdfId = src.id }) {
                                    Icon(Icons.Default.Delete, "Remove this PDF")
                                    Spacer(Modifier.width(4.dp))
                                    Text("Remove PDF")
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Tap to select/unselect · hold to preview",
                                    Modifier.weight(1f), fontSize = 11.sp)
                                TextButton(onClick = {
                                    repeat(src.count) { n ->
                                        if (output.none { it.sourceId == src.id && it.index == n }) {
                                            output.add(PagePick(UUID.randomUUID().toString(), src.id, n))
                                        }
                                    }
                                    pageTab = 1
                                }) { Text("Add all") }
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(src.count) { index ->
                                    val isSelected = output.any {
                                        it.sourceId == src.id && it.index == index
                                    }
                                    Column(
                                        Modifier.padding(5.dp)
                                            .border(if (isSelected) 2.dp else 1.dp,
                                                if (isSelected) Sky else Color.LightGray,
                                                RoundedCornerShape(10.dp))
                                            .background(if (isSelected) Sunny.copy(alpha = 0.20f)
                                                        else Color.Transparent, RoundedCornerShape(10.dp))
                                            .combinedClickable(
                                                onClick = { togglePage(src.id, index) },
                                                onLongClick = { longPressed = src.id to index }
                                            )
                                            .padding(5.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        PdfThumb(src.file, index, Modifier.fillMaxWidth().height(132.dp))
                                        Text("Page " + (index + 1) +
                                            if (isSelected) "  ✓" else "",
                                            fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (output.isEmpty()) {
                        EmptyState("Your final PDF is empty. Tap pages in Source PDFs, or use Add all pages.")
                    } else {
                        Text(
                            "Pages in final order · use arrows to rearrange",
                            Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            fontSize = 13.sp
                        )
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(output, key = { _, p -> p.id }) { index, item ->
                                val source = sources.firstOrNull { it.id == item.sourceId }
                                if (source != null) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        tonalElevation = 2.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            PdfThumb(source.file, item.index,
                                                Modifier.width(65.dp).height(90.dp))
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text("#" + (index + 1) + " · Page " + (item.index + 1),
                                                    fontWeight = FontWeight.Bold)
                                                Text(source.name, fontSize = 11.sp, maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis)
                                            }
                                            Column {
                                                IconButton(
                                                    onClick = {
                                                        if (index > 0) {
                                                            val a = output[index - 1]
                                                            output[index - 1] = output[index]
                                                            output[index] = a
                                                        }
                                                    }, enabled = index > 0
                                                ) { Icon(Icons.Default.ArrowUpward, "Move up") }
                                                IconButton(
                                                    onClick = {
                                                        if (index < output.size - 1) {
                                                            val a = output[index + 1]
                                                            output[index + 1] = output[index]
                                                            output[index] = a
                                                        }
                                                    }, enabled = index < output.size - 1
                                                ) { Icon(Icons.Default.ArrowDownward, "Move down") }
                                            }
                                            IconButton(onClick = { output.remove(item) }) {
                                                Icon(Icons.Default.Delete, "Remove page")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (renameOpen) {
            SmartRenameDialog(
                onDismiss = { renameOpen = false },
                onApply = {
                    savedName = it
                    renameOpen = false
                    scope.launch { snackbar.showSnackbar("Filename ready — tap Save PDF") }
                }
            )
        }
        if (clearOpen) {
            AlertDialog(
                onDismissRequest = { clearOpen = false },
                title = { Text("Clear final pages?") },
                text = { Text("This clears the working page sequence. Imported PDFs and original files remain unchanged.") },
                confirmButton = {
                    TextButton(onClick = { output.clear(); clearOpen = false }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { clearOpen = false }) { Text("Cancel") }
                }
            )
        }
        if (resetOpen) {
            AlertDialog(
                onDismissRequest = { resetOpen = false },
                title = { Text("Reset PDF Workspace?") },
                text = { Text("Remove all imported PDFs, selected pages and the prepared filename? Original files stored on your phone will not be deleted.") },
                confirmButton = {
                    TextButton(onClick = {
                        sources.forEach { it.file.delete() }
                        sources.clear()
                        output.clear()
                        selected = null
                        previewPage = null
                        longPressed = null
                        savedName = ""
                        pageTab = 0
                        dark = false
                        resetOpen = false
                    }) { Text("Reset everything") }
                },
                dismissButton = {
                    TextButton(onClick = { resetOpen = false }) { Text("Cancel") }
                }
            )
        }
        val removeId = removePdfId
        if (removeId != null) {
            val toRemove = sources.firstOrNull { it.id == removeId }
            AlertDialog(
                onDismissRequest = { removePdfId = null },
                title = { Text("Remove this PDF?") },
                text = { Text("Remove " + (toRemove?.name ?: "selected PDF") +
                    " and all its selected pages from this workspace? The original file will remain untouched.") },
                confirmButton = {
                    TextButton(onClick = {
                        if (toRemove != null) {
                            output.removeAll { it.sourceId == removeId }
                            sources.remove(toRemove)
                            toRemove.file.delete()
                            if (selected == removeId) selected = sources.firstOrNull()?.id
                            if (sources.isEmpty()) pageTab = 0
                            if (previewPage?.first == removeId) previewPage = null
                        }
                        removePdfId = null
                    }) { Text("Remove PDF") }
                },
                dismissButton = {
                    TextButton(onClick = { removePdfId = null }) { Text("Cancel") }
                }
            )
        }
        val pressed = longPressed
        if (pressed != null) {
            val source = sources.firstOrNull { it.id == pressed.first }
            val isPicked = output.any {
                it.sourceId == pressed.first && it.index == pressed.second
            }
            AlertDialog(
                onDismissRequest = { longPressed = null },
                title = { Text("Page " + (pressed.second + 1)) },
                text = { Text(source?.name ?: "PDF page") },
                confirmButton = {
                    TextButton(onClick = {
                        previewPage = pressed
                        longPressed = null
                    }) { Text("View large") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            togglePage(pressed.first, pressed.second)
                            longPressed = null
                        }) { Text(if (isPicked) "Unselect" else "Select") }
                        TextButton(onClick = { longPressed = null }) { Text("Cancel") }
                    }
                }
            )
        }
        val fullView = previewPage
        if (fullView != null) {
            val previewSource = sources.firstOrNull { it.id == fullView.first }
            if (previewSource != null) {
                val picked = output.any { it.sourceId == fullView.first && it.index == fullView.second }
                Dialog(
                    onDismissRequest = { previewPage = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Page " + (fullView.second + 1) + " of " + previewSource.count,
                                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(previewSource.name, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                }
                                IconButton(onClick = { previewPage = null }) {
                                    Icon(Icons.Default.Close, "Close full-page preview")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            PdfThumb(previewSource.file, fullView.second,
                                Modifier.fillMaxWidth().weight(1f), renderWidth = 1100)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { togglePage(fullView.first, fullView.second) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (picked) MaterialTheme.colorScheme.error else Sunny,
                                    contentColor = if (picked) MaterialTheme.colorScheme.onError else Ink
                                )
                            ) {
                                Text(if (picked) "Unselect page" else "Select page")
                            }
                            TextButton(
                                onClick = { previewPage = null },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Back to pages") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PdfThumb(file: File, index: Int, modifier: Modifier = Modifier, renderWidth: Int = 280) {
    var bitmap by remember(file.absolutePath, index, renderWidth) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.absolutePath, index, renderWidth) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { drawPreview(file, index, renderWidth).asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier.background(Color.White, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Text("PDF", fontSize = 14.sp, color = Ink)
        } else {
            Image(bitmap = bitmap!!, contentDescription = "PDF page " + (index + 1),
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun SmartRenameDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var exam by remember { mutableStateOf("SSE") }
    var year by remember { mutableStateOf("") }
    var set by remember { mutableStateOf("A") }
    var answer by remember { mutableStateOf("AMA") }
    var day by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CGPSC Smart Rename") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = exam, onValueChange = { exam = it.uppercase(Locale.ROOT) },
                    label = { Text("Exam code (SSE by default)") }, singleLine = true
                )
                OutlinedTextField(
                    value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = { Text("Exam year") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text("Set", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in listOf("A", "B", "C", "D")) {
                        FilterChip(selected = set == option, onClick = { set = option },
                            label = { Text(option) })
                    }
                }
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter { c ->
                        c.isDigit() || c == '/' || c == '-' || c == '_'
                    }.take(12) },
                    label = { Text("Exam date (DD/MM/YYYY)") },
                    placeholder = { Text("22/02/2026") },
                    supportingText = { Text("Type 22/02/2026 or 22022026") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text("Answer-key type", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = answer == "AMA",
                        onClick = { answer = "AMA" }, label = { Text("AMA") })
                    FilterChip(selected = answer == "MA",
                        onClick = { answer = "MA" }, label = { Text("MA") })
                }
                Text("MA = model answer · AMA = amended model answer", fontSize = 11.sp)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val code = exam.trim().uppercase(Locale.ROOT)
                    .replace(Regex("[^A-Z0-9]+"), "_").trim('_')
                val digits = day.filter(Char::isDigit)
                val formattedDate = if (digits.length == 8) {
                    val dd = digits.substring(0, 2).toInt()
                    val mm = digits.substring(2, 4).toInt()
                    val yyyy = digits.substring(4, 8).toInt()
                    if (runCatching { LocalDate.of(yyyy, mm, dd) }.isSuccess) {
                        String.format(Locale.ROOT, "%02d_%02d_%04d", dd, mm, yyyy)
                    } else null
                } else null
                if (code.isBlank() || year.length != 4 || formattedDate == null) {
                    error = "Enter an exam code, 4-digit year and valid DD/MM/YYYY date."
                } else {
                    onApply("CGPSC_" + code + "_" + year + "_SET_" +
                        set + "_DATE_" + formattedDate + "_" + answer + ".pdf")
                }
            }) { Text("Use filename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun cacheSource(context: Context, uri: Uri): SourcePdf {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { cur ->
        if (cur.moveToFirst()) cur.getString(0) else null
    } ?: "Imported.pdf"
    val file = File(context.cacheDir, "dbb_" + UUID.randomUUID() + ".pdf")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to read document")
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pages = PdfRenderer(fd).use { it.pageCount }
        if (pages < 1) error("The PDF contains no pages")
        return SourcePdf(UUID.randomUUID().toString(), name, file, pages)
    } catch (e: Exception) {
        file.delete()
        throw e
    }
}

private fun drawPreview(file: File, pageIndex: Int, requestedWidth: Int = 280): Bitmap {
    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return PdfRenderer(fd).use { renderer ->
        renderer.openPage(pageIndex).use { page ->
            val width = requestedWidth.coerceIn(180, 1400)
            val height = (width.toFloat() * page.height / page.width)
                .toInt().coerceIn(120, 2200)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(NativeColor.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }
}

private fun writePdf(context: Context, uri: Uri,
                     sources: List<SourcePdf>, picks: List<PagePick>) {
    require(picks.isNotEmpty()) { "Choose at least one page" }
    val byId = sources.associateBy { it.id }
    val documents = mutableMapOf<String, PDDocument>()
    try {
        PDDocument().use { target ->
            for (pick in picks) {
                val source = byId[pick.sourceId] ?: error("Source PDF missing")
                val doc = documents.getOrPut(source.id) { PDDocument.load(source.file) }
                val page = doc.getPage(pick.index)
                val imported = target.importPage(page)
                // PDFBox importPage does not automatically include inherited resources.
                if (page.resources != null) imported.resources = page.resources
            }
            context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                target.save(stream)
            } ?: error("Couldn't open destination")
        }
    } finally {
        documents.values.forEach { runCatching { it.close() } }
    }
}
