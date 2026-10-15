package com.wordtrail.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import com.wordtrail.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate

class StudyViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as WordTrailApp
    private val repo = app.repository
    val words = repo.words.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val books = repo.books.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val importPreview = MutableStateFlow<ImportPreview?>(null)
    val importResult = MutableStateFlow<ImportResult?>(null)
    // 大段草稿留在 ViewModel，旋转保留，避免写入 Bundle 导致 TransactionTooLargeException。
    val importText = MutableStateFlow("")
    val editor = saved.getStateFlow("bookEditor", "")
    val targetBook = saved.getStateFlow<String?>("targetBook", null)
    val bookName = saved.getStateFlow("bookName", "")
    val bookDescription = saved.getStateFlow("bookDescription", "")
    val importSource = saved.getStateFlow("importSource", "粘贴导入")
    val fileEncoding = saved.getStateFlow("fileEncoding", "auto")
    val fileImportError = MutableStateFlow<String?>(null)
    val days = repo.days.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = app.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val mode = saved.getStateFlow("mode", StudyMode.NEW.name)
    val currentId = saved.getStateFlow<String?>("current", null)
    val detailId = saved.getStateFlow<String?>("detail", null)
    val position = saved.getStateFlow("position", -1)
    val page = saved.getStateFlow("page", 0)
    val busy = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val today = MutableStateFlow(LocalDate.now().toString())
    private val mutex = Mutex()
    private var trail: ArrayList<String>
        get() = saved.get<ArrayList<String>>("trail") ?: arrayListOf()
        set(value) { saved["trail"] = value }
    init {
        action {
            repo.seed()
            val activeIds = repo.all().map { it.word.id }.toSet()
            if (currentId.value != null && currentId.value !in activeIds) resetTrail()
            if (currentId.value == null && position.value == -1) nextInternal()
            ready.value = true
        }
        viewModelScope.launch { while (isActive) { today.value = LocalDate.now().toString(); delay(15000) } }
    }
    // tryLock 在点击时同步获取，避免快速连点排队后跳过多个词。
    private fun action(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = "操作未完成：${e.message ?: "请重试"}" }
            finally { busy.value = false; mutex.unlock() }
        }
    }
    fun retry() = action { repo.seed(); nextInternal(); ready.value = true }
    fun setPage(value: Int) { saved["page"] = value; saved["detail"] = null }
    fun dismissDetail() { saved["detail"] = null }
    fun clearError() { error.value = null }
    private fun resetTrail() {
        trail = arrayListOf(); saved["position"] = -1; saved["current"] = null; saved["detail"] = null
    }
    fun beginBookEditor(kind: String, target: String? = null) {
        if (busy.value) return
        saved["bookEditor"] = kind; saved["targetBook"] = target
        saved["bookName"] = ""; saved["bookDescription"] = ""
        importText.value = ""
        saved["fileEncoding"] = "auto"; fileImportError.value = null
        importPreview.value = null; importResult.value = null
    }
    fun closeBookEditor() { if (!busy.value) { saved["bookEditor"] = ""; importPreview.value = null } }
    fun bookName(value: String) { saved["bookName"] = value }
    fun bookDescription(value: String) { saved["bookDescription"] = value }
    fun targetBook(value: String?) { if (!busy.value) { saved["targetBook"] = value; importPreview.value = null } }
    fun fileEncoding(value: String) {
        if (!busy.value && value in setOf("auto", "gb18030")) {
            saved["fileEncoding"] = value; importPreview.value = null; fileImportError.value = null
        }
    }
    fun createBook() = action {
        val id = repo.createBook(bookName.value, bookDescription.value)
        saved["targetBook"] = id; saved["bookEditor"] = "single"
        error.value = "词库已创建，可添加单词或批量导入"
    }
    fun renameBook(id: String, name: String) = action { repo.renameBook(id, name); error.value = "已重命名" }
    fun selectBook(id: String) = action { repo.selectBook(id); resetTrail(); nextInternal(); saved["page"] = 0 }
    fun changeBooks(ids: Set<String>, restore: Boolean = false, permanent: Boolean = false) = action {
        val before = repo.db.dao().activeBookId()
        repo.trashBooks(ids, restore, permanent)
        if (before != repo.db.dao().activeBookId()) { resetTrail(); nextInternal() }
        error.value = if (permanent) "已永久删除所选词库的独占数据" else if (restore) "词库已恢复，学习进度已保留" else "已移入回收站，学习进度已保留"
    }
    fun previewText(text: String) = action {
        importPreview.value = null
        val target = targetBook.value
        val parsed = withContext(Dispatchers.Default) { WordImport.parse(text, "tsv") }
        importPreview.value = repo.previewImport(parsed, target)
        saved["importSource"] = "粘贴导入"
    }
    fun previewFile(uri: Uri) = action {
        importPreview.value = null
        fileImportError.value = null
        val target = targetBook.value
        val encoding = fileEncoding.value
        try {
            val (preview, fileName) = withContext(Dispatchers.IO) {
                val resolver = app.contentResolver
                val name = runCatching { resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    val column = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (it.moveToFirst() && column >= 0) it.getString(column) else ""
                } }.getOrNull().orEmpty()
                val text = resolver.openInputStream(uri)?.use { WordImport.read(it, encoding) } ?: error("无法读取所选文件，请重新选择")
                val format = when {
                    name.endsWith(".jsonl", true) || name.endsWith(".ndjson", true) -> "jsonl"
                    else -> "auto"
                }
                repo.previewImport(WordImport.parse(text, format), target) to name
            }
            if (target == null && bookName.value.isBlank()) {
                saved["bookName"] = fileName.substringBeforeLast('.', fileName).trim().take(80).ifBlank { "导入词库" }
            }
            importPreview.value = preview
            saved["importSource"] = "文件导入"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is SecurityException -> "文件访问权限已失效，请重新选择文件；云盘文件请先下载到本机再选择"
                is java.io.FileNotFoundException -> "文件无法读取，请确认文件已下载且未被移动或删除，再重新选择"
                is java.io.IOException -> "读取文件失败，请确认文件已完整下载；可复制到手机的下载目录后重新选择"
                else -> e.message ?: "导入文件失败，请检查格式后重新选择"
            }
            fileImportError.value = message
            throw IllegalArgumentException(message, e)
        }
    }
    fun clearPreview() { if (!busy.value) importPreview.value = null }
    fun commitImport(update: Boolean) = action {
        val preview = importPreview.value ?: return@action
        val result = repo.commitImport(preview, bookName.value, bookDescription.value, update, importSource.value)
        importPreview.value = null; importResult.value = result; saved["bookEditor"] = ""
        // 空词库中追加后可直接学习；已有学习卡片不产生额外查看次数。
        if (currentId.value == null && repo.db.dao().activeBookId() == result.bookId) { resetTrail(); nextInternal() }
    }
    fun addSingle(english: String, meaning: String) = action {
        require(listOf(english, meaning).none { '\t' in it || '\n' in it || '\r' in it }) { "逐条添加不支持 Tab 或换行，请使用批量导入" }
        val parsed = withContext(Dispatchers.Default) { WordImport.parse("$english\t$meaning", "tsv") }
        require(parsed.issues.isEmpty() && parsed.words.size == 1) { "请填写单行英文和中文释义" }
        val preview = repo.previewImport(parsed, targetBook.value)
        require(preview.target != null) { "请先创建或选择自建词库" }
        require(preview.duplicates == 0) { "此词已存在，可通过批量导入更新" }
        val result = repo.commitImport(preview, "", "", false, "手动添加")
        error.value = "已添加 ${parsed.words.single().english}"
        if (currentId.value == null && repo.db.dao().activeBookId() == result.bookId) { resetTrail(); nextInternal() }
    }
    fun dismissImportResult() { importResult.value = null }
    fun exportTemplate(uri: Uri) = action {
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write("\uFEFF" + WordImport.TEMPLATE) }
                ?: error("无法写入模板文件")
        }
        error.value = "CSV 示例模板已导出"
    }
    fun setMode(value: StudyMode) = action {
        saved["mode"] = value.name
        resetTrail()
        nextInternal()
    }
    fun next() = action { nextInternal() }
    private suspend fun nextInternal() {
        val oldTrail = trail
        val nextPosition = position.value + 1
        val id = if (nextPosition < oldTrail.size) oldTrail[nextPosition]
        else StudyLogic.pick(repo.all(), StudyMode.valueOf(mode.value), currentId.value)?.word?.id
        if (id == null) { saved["current"] = null; return }
        repo.view(id)
        if (nextPosition >= oldTrail.size) { oldTrail.add(id); trail = oldTrail }
        saved["position"] = nextPosition
        saved["current"] = id
    }
    fun previous() = action {
        // 词库耗尽提示也可退回最后一张卡片。
        val p = if (currentId.value == null) position.value else position.value - 1
        if (p >= 0) {
            val id = trail[p]; repo.view(id)
            saved["position"] = p; saved["current"] = id
        }
    }
    fun openDetail(id: String) = action { repo.view(id); saved["detail"] = id }
    fun favorite(id: String) = action { repo.favorite(id) }
    fun mastered(id: String) = action { repo.mastered(id) }
    fun showMeaning(value: Boolean) = action { app.settings.showMeaning(value) }
    fun theme(value: String) = action { app.settings.theme(value) }
    fun export(uri: Uri) = action {
        withContext(Dispatchers.IO) {
            val content = repo.export()
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                ?: error("无法写入所选文件")
        }
        error.value = "备份已导出，请妥善保存 JSON 文件"
    }
    fun import(uri: Uri) = action {
        val count = withContext(Dispatchers.IO) {
            val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= BackupCodec.MAX_BYTES) { "备份文件超过 10 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
                ?: error("无法读取所选文件")
            require(bytes.size <= BackupCodec.MAX_BYTES) { "备份文件超过 10 MB" }
            repo.import(bytes.toString(Charsets.UTF_8))
        }
        error.value = "已合并 $count 个已学习词条，现有记录已保留"
    }
}
