package com.wordtrail.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wordtrail.app.data.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(vm: StudyViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val result by vm.importResult.collectAsStateWithLifecycle()
    var trash by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var rename by rememberSaveable { mutableStateOf("") }
    var deleteIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var permanent by rememberSaveable { mutableStateOf(false) }
    var secondConfirm by rememberSaveable { mutableStateOf(false) }
    val template = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportTemplate) }
    BackHandler(editor.isNotEmpty() || selected.isNotEmpty()) {
        if (editor.isNotEmpty()) vm.closeBookEditor() else selected = emptyList()
    }
    Scaffold(floatingActionButton = {
        if (!trash) ExtendedFloatingActionButton(onClick = { if (!busy) addMenu = true }, modifier = Modifier.testTag("add-library"),
            icon = { Icon(Icons.Default.Add, "添加词库") }, text = { Text("添加词库") })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!trash, { trash = false; selected = emptyList() }, label = { Text("词库") })
                FilterChip(trash, { trash = true; selected = emptyList() }, label = { Text("回收站 (${books.count { it.book.deleted }})") })
                TextButton(onClick = { template.launch("拾词导入示例.csv") }, enabled = !busy) { Text("导出模板") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索词库名称或来源") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (selected.isNotEmpty()) FlowRow {
                Text("已选 ${selected.size} 个", Modifier.padding(12.dp))
                TextButton(onClick = { selected = emptyList() }) { Text("取消多选") }
                if (trash) TextButton(onClick = { vm.changeBooks(selected.toSet(), restore = true); selected = emptyList() }, enabled = !busy) { Text("批量恢复") }
                else TextButton(onClick = { deleteIds = selected; permanent = false }, enabled = !busy) { Text("批量删除") }
            }
            val visible = books.filter { it.book.deleted == trash && (it.book.name.contains(query.trim(), true) || it.book.source.contains(query.trim(), true)) }
            if (visible.isEmpty()) {
                Text(if (query.isNotBlank()) "没有匹配的词库" else if (trash) "回收站为空。删除的词库会保留词条与进度，可随时恢复。" else "还没有词库，点击右下角添加词库，或到回收站恢复。", Modifier.padding(vertical = 24.dp))
            }
            LazyColumn(Modifier.weight(1f).testTag("library-list"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(visible, key = { it.book.id }) { item ->
                    val book = item.book
                    fun toggle() { selected = if (book.id in selected) selected - book.id else selected + book.id }
                    Card(Modifier.fillMaxWidth().testTag("book-${book.id}").combinedClickable(enabled = !busy,
                        onLongClickLabel = "多选词库", onLongClick = { toggle() }, onClick = {
                            if (selected.isNotEmpty()) toggle() else if (!trash) vm.selectBook(book.id)
                        })) {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                if (selected.isNotEmpty()) Checkbox(book.id in selected, { toggle() }, enabled = !busy)
                                Column(Modifier.weight(1f)) {
                                    Text(book.name, style = MaterialTheme.typography.titleLarge)
                                    Text("${item.wordCount} 个单词 · ${book.source}${if (item.active) " · 当前学习" else ""}", style = MaterialTheme.typography.bodyMedium)
                                    if (book.description.isNotBlank()) Text(book.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (trash) {
                                    TextButton(onClick = { vm.changeBooks(setOf(book.id), restore = true) }, enabled = !busy) { Text("恢复") }
                                    if (!book.builtIn) TextButton(onClick = { deleteIds = listOf(book.id); permanent = true }, enabled = !busy) { Text("永久删除") }
                                    else Text("内置词库仅隐藏，可随时恢复", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                } else {
                                    TextButton(onClick = { vm.selectBook(book.id) }, enabled = !busy) { Text(if (item.active) "开始学习" else "切换学习") }
                                    TextButton(onClick = { renameId = book.id; rename = book.name }, enabled = !busy) { Text("重命名") }
                                    if (!book.builtIn) TextButton(onClick = { vm.beginBookEditor("single", book.id) }, enabled = !busy) { Text("添加单词") }
                                    TextButton(onClick = { deleteIds = listOf(book.id); permanent = false }, enabled = !busy) { Text(if (book.builtIn) "隐藏" else "删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("添加词库") }, text = {
        Column {
            listOf("create" to "新建词库", "paste" to "粘贴导入", "file" to "文件导入").forEach { (kind, label) ->
                TextButton(onClick = { addMenu = false; vm.beginBookEditor(kind) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }, confirmButton = { TextButton(onClick = { addMenu = false }) { Text("取消") } })
    if (renameId != null) AlertDialog(onDismissRequest = { if (!busy) renameId = null }, title = { Text("重命名词库") },
        text = { OutlinedTextField(rename, { rename = it.take(80) }, label = { Text("词库名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.renameBook(renameId!!, rename); renameId = null }, enabled = !busy && rename.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renameId = null }, enabled = !busy) { Text("取消") } })
    if (deleteIds.isNotEmpty()) {
        val deleting = books.filter { it.book.id in deleteIds }
        AlertDialog(onDismissRequest = { if (!busy) { deleteIds = emptyList(); secondConfirm = false } },
            title = { Text(if (secondConfirm) "再次确认永久删除" else if (permanent) "永久删除词库" else "删除 / 隐藏词库") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    deleting.forEach { Text("${it.book.name}：${it.wordCount} 个单词") }
                    Text(if (permanent) "将永久清除这些词库，以及它们独占的单词、查看次数、每日学习记录、收藏与掌握状态。其他词库共享的数据保留。此操作无法恢复。"
                        else "将移入回收站（内置词库为隐藏）。所有词条、查看记录、收藏和掌握状态均保留，恢复后可继续学习。删除当前词库后会自动切换；没有可用词库时显示添加引导。")
                }
            }, confirmButton = { TextButton(onClick = {
                if (permanent && !secondConfirm) secondConfirm = true
                else { vm.changeBooks(deleteIds.toSet(), permanent = permanent); deleteIds = emptyList(); selected = emptyList(); secondConfirm = false }
            }, enabled = !busy) { Text(if (permanent && !secondConfirm) "继续" else if (permanent) "确认永久删除" else "确认移入回收站") } },
            dismissButton = { TextButton(onClick = { deleteIds = emptyList(); secondConfirm = false }, enabled = !busy) { Text("取消") } })
    }
    if (editor.isNotEmpty()) BookEditor(vm, books, busy, editor)
    result?.let { value ->
        AlertDialog(onDismissRequest = vm::dismissImportResult, title = { Text("导入完成") },
            text = { Text("新增 ${value.added} 条，更新 ${value.updated} 条，跳过 ${value.skipped} 条。已有学习进度已保留。") },
            confirmButton = { TextButton(onClick = { vm.dismissImportResult(); vm.selectBook(value.bookId) }, enabled = !busy, modifier = Modifier.testTag("import-start-study")) { Text("开始学习") } },
            dismissButton = { TextButton(onClick = vm::dismissImportResult) { Text("完成") } })
    }
}

@Composable
private fun BookEditor(vm: StudyViewModel, books: List<BookSummary>, busy: Boolean, editor: String) {
    val name by vm.bookName.collectAsStateWithLifecycle()
    val description by vm.bookDescription.collectAsStateWithLifecycle()
    val target by vm.targetBook.collectAsStateWithLifecycle()
    val preview by vm.importPreview.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fileError by vm.fileImportError.collectAsStateWithLifecycle()
    val encoding by vm.fileEncoding.collectAsStateWithLifecycle()
    val text by vm.importText.collectAsStateWithLifecycle()
    var english by rememberSaveable { mutableStateOf("") }
    var meaning by rememberSaveable { mutableStateOf("") }
    var update by rememberSaveable { mutableStateOf(false) }
    var acceptErrors by rememberSaveable(preview) { mutableStateOf(false) }
    var targetMenu by remember { mutableStateOf(false) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewFile) }
    Dialog(onDismissRequest = vm::closeBookEditor, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(when(editor) { "create" -> "新建词库"; "single" -> "添加单词"; "file" -> "文件导入"; else -> "粘贴导入" }, style = MaterialTheme.typography.headlineSmall)
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)); Text("正在处理，请稍候…") }
                if (editor != "file") error?.let { Text(it, color = if (it.startsWith("操作未完成")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editor == "file" && fileError != null) {
                        Text(fileError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("file-import-error"))
                    }
                    if (preview != null) {
                        val p = preview!!
                        Text("导入预览", style = MaterialTheme.typography.titleLarge)
                        Text("有效词条 ${p.parsed.words.size} 条（其中重复 ${p.duplicates} 条），错误 ${p.parsed.issues.size} 行。")
                        Text("目标：${books.find { it.book.id == p.target }?.book?.name ?: "新建：$name"}")
                        Row { RadioButton(!update, { update = false }, enabled = !busy); TextButton(onClick = { update = false }, enabled = !busy) { Text("重复项：跳过") } }
                        Row { RadioButton(update, { update = true }, enabled = !busy); TextButton(onClick = { update = true }, enabled = !busy) { Text("重复项：更新内容") } }
                        Text("同词库英文忽略大小写去重。更新保留学习进度，空的可选字段保留原内容。")
                        p.parsed.words.take(8).forEach { Text("${it.english}  ·  ${it.meaning}") }
                        if (p.parsed.words.size > 8) Text("仅展示前 8 条")
                        p.parsed.issues.take(100).forEach { Text("第 ${it.line} 行 / 条：${it.reason}", color = MaterialTheme.colorScheme.error) }
                        if (p.parsed.issues.size > 100) Text("另有 ${p.parsed.issues.size - 100} 行错误，请修正原文件后重新预览。")
                        if (p.parsed.issues.isNotEmpty()) Row {
                            Checkbox(acceptErrors, { acceptErrors = it }, enabled = !busy)
                            TextButton(onClick = { acceptErrors = !acceptErrors }, enabled = !busy) { Text("确认忽略错误行，仅导入有效词条") }
                        }
                        if (p.parsed.words.isEmpty()) Text("没有有效词条，请返回修改。空文件不会创建词库。")
                        Button(onClick = { vm.commitImport(update) }, enabled = !busy && p.parsed.words.isNotEmpty() && (p.parsed.issues.isEmpty() || acceptErrors), modifier = Modifier.fillMaxWidth()) { Text("确认导入") }
                        OutlinedButton(onClick = vm::clearPreview, enabled = !busy) { Text("返回修改") }
                    } else if (editor == "single") {
                        Text("词库：${books.find { it.book.id == target }?.book?.name.orEmpty()}")
                        OutlinedTextField(english, { english = it }, label = { Text("英文单词（必填）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(meaning, { meaning = it }, label = { Text("中文释义（必填）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { vm.addSingle(english, meaning) }, enabled = !busy && english.isNotBlank() && meaning.isNotBlank(), modifier = Modifier.testTag("single-add")) { Text("添加单词") }
                        OutlinedButton(onClick = { vm.beginBookEditor("paste", target) }, enabled = !busy) { Text("批量粘贴导入") }
                        OutlinedButton(onClick = { vm.beginBookEditor("file", target) }, enabled = !busy) { Text("批量文件导入") }
                    } else {
                        if (editor != "create") {
                            Box {
                                OutlinedButton(onClick = { targetMenu = true }, enabled = !busy) { Text("目标：${books.find { it.book.id == target }?.book?.name ?: "新建词库"} ▾") }
                                DropdownMenu(targetMenu, { targetMenu = false }) {
                                    DropdownMenuItem(text = { Text("新建词库") }, onClick = { vm.targetBook(null); targetMenu = false })
                                    books.filter { !it.book.deleted && !it.book.builtIn }.forEach { item ->
                                        DropdownMenuItem(text = { Text("追加到 ${item.book.name}") }, onClick = { vm.targetBook(item.book.id); targetMenu = false })
                                    }
                                }
                            }
                        }
                        if (target == null) {
                            OutlinedTextField(name, { vm.bookName(it.take(80)) }, label = { Text(if (editor == "file") "词库名称（留空使用文件名）" else "词库名称（必填）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(description, { vm.bookDescription(it.take(1000)) }, label = { Text("描述（可选）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        }
                        when(editor) {
                            "create" -> Button(onClick = vm::createBook, enabled = !busy && name.isNotBlank()) { Text("创建并添加单词") }
                            "file" -> {
                                Text("支持 CSV、Tab 分隔 TXT/TSV、JSON/JSONL 词库（english/meaning 或 word/translations），以及拾词备份中的词条。恢复学习记录请使用设置中的备份导入。")
                                Text("默认 UTF-8（可带 BOM）或带 BOM 的 UTF-16。最大 20 MB、50000 条。CSV 自动识别逗号、分号或 Tab，支持引号及字段内换行。")
                                Text("文件编码", style = MaterialTheme.typography.titleSmall)
                                FilterChip(encoding == "auto", { vm.fileEncoding("auto") }, enabled = !busy, label = { Text("UTF-8 / BOM 自动") })
                                FilterChip(encoding == "gb18030", { vm.fileEncoding("gb18030") }, enabled = !busy, label = { Text("GBK / GB18030（Excel ANSI）") })
                                Text("编码报错时可切换编码后重新选择文件。选择文件后先预览，确认才会写入。", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { file.launch(arrayOf("*/*")) }, enabled = !busy) { Text("选择文件并预览") }
                            }
                            else -> {
                                Text("每行：英文单词 + Tab + 中文释义。请手动长按输入框粘贴，不会主动读取剪贴板。")
                                Text("示例（两列之间为 Tab）：\napple\t苹果\nbook\t书籍")
                                OutlinedTextField(text, { vm.importText.value = it }, label = { Text("在此粘贴多行词条") }, minLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                Button(onClick = { vm.previewText(text) }, enabled = !busy && (target != null || name.isNotBlank())) { Text("预览导入") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                TextButton(onClick = vm::closeBookEditor, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}
