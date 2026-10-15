package com.wordtrail.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wordtrail.app.data.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WordTrail() }
    }
}

private val lightColors = lightColorScheme(primary = Color(0xFF276654), secondary = Color(0xFF5E6D55),
    background = Color(0xFFF6F8F4), surface = Color(0xFFF6F8F4), primaryContainer = Color(0xFFD6EBD8),
    tertiary = Color(0xFF846438))
private val darkColors = darkColorScheme(primary = Color(0xFFA4D4BA), secondary = Color(0xFFBDCBB1),
    background = Color(0xFF111914), surface = Color(0xFF111914), primaryContainer = Color(0xFF234D3E))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordTrail(vm: StudyViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val words by vm.words.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val id by vm.currentId.collectAsStateWithLifecycle()
    val detailId by vm.detailId.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val dark = when(settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        onDispose { }
    }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::export) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    BackHandler(detailId != null || page != 0) { if (detailId != null) vm.dismissDetail() else vm.setPage(0) }
    MaterialTheme(colorScheme = if (dark) darkColors else lightColors) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.AutoMirrored.Filled.MenuBook, Icons.Default.History, Icons.Default.Bookmark, Icons.Default.Settings)
                listOf("学习", "历史", "收藏", "设置").forEachIndexed { index, label ->
                    NavigationBarItem(selected = page == index, onClick = { vm.setPage(index) },
                        icon = { Icon(icons[index], contentDescription = null) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listOf("拾词", "学习足迹", "我的收藏", "偏好与备份", "词库管理")[page], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(listOf("每天拾起一点进步", "看过的单词，都在这里", "把想记住的词留在身边", "离线学习，记录由你掌握", "添加、整理，找回你的词库")[page], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    if (!ready) {
                        Column(Modifier.padding(24.dp)) {
                            Text(if (busy) "正在准备本地词库…" else "词库加载失败，请重试")
                            if (!busy) Button(onClick = vm::retry) { Text("重试") }
                        }
                    } else when(page) {
                        0 -> {
                            val ids = remember(words) { words.map { it.word.id }.toSet() }
                            StudyScreen(words, days.count { it.day == today && it.wordId in ids }, id, mode, position, settings, busy, vm)
                        }
                        1, 2 -> HistoryScreen(words, page == 2, busy, vm)
                        4 -> LibraryScreen(vm)
                        else -> SettingsScreen(settings, words.size, busy, vm,
                            { export.launch("拾词备份-${java.time.LocalDate.now()}.json") }, { confirmImport = true })
                    }
                }
            }
        }
        val detail = words.find { it.word.id == detailId }
        if (detail != null) ModalBottomSheet(onDismissRequest = vm::dismissDetail) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("单词详情", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                WordContent(detail.word, true)
                WordActions(detail, busy, vm)
                detail.progress?.let {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("累计查看 ${it.viewCount} 次\n首次：${formatTime(it.firstViewed)}\n最近：${formatTime(it.lastViewed)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("导入学习备份") },
            text = { Text("将合并 JSON 中的学习记录、收藏和掌握状态，不清空现有数据。重复导入不会累加次数；冲突状态按修改时间选择。两台设备独立学习的次数取较大值。") },
            confirmButton = { TextButton(onClick = { confirmImport = false; import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("选择备份文件") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } })
    }
}

@Composable
private fun StudyScreen(words: List<WordState>, today: Int, id: String?, mode: String, position: Int,
    settings: Settings, busy: Boolean, vm: StudyViewModel) {
    val current = words.find { it.word.id == id }
    var revealed by rememberSaveable(id, position, settings.showMeaning) { mutableStateOf(settings.showMeaning) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("累计看过", words.count { (it.progress?.viewCount ?: 0) > 0 }, Modifier.weight(1f))
            Stat("已掌握", words.count { it.progress?.mastered == true && (it.progress.viewCount > 0) }, Modifier.weight(1f))
            Stat("今日学习", today, Modifier.weight(1f))
        }
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = mode == "NEW", onClick = { vm.setMode(StudyMode.NEW) }, enabled = !busy, label = { Text("学习新词") })
            FilterChip(selected = mode == "REVIEW", onClick = { vm.setMode(StudyMode.REVIEW) }, enabled = !busy, label = { Text("复习旧词") })
        }
        Card(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                if (current == null) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text(if (words.isEmpty()) "还没有可学习的单词" else if (mode == "NEW") "新词已全部看过" else "还没有学习记录", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(if (words.isEmpty()) "请添加单词或词库，也可以到回收站恢复之前的词库。" else if (mode == "NEW") "做得不错！去复习之前遇见的单词吧。" else "先学习几个新词，之后就能在这里复习。")
                    if (words.isNotEmpty()) Button(onClick = { vm.setMode(if (mode == "NEW") StudyMode.REVIEW else StudyMode.NEW) }, enabled = !busy, modifier = Modifier.padding(top = 24.dp)) {
                        Text(if (mode == "NEW") "切换到复习旧词" else "切换到学习新词")
                    }
                    if (words.isEmpty()) Button(onClick = { vm.setPage(4) }, enabled = !busy) { Text("添加或恢复词库") }
                } else {
                    Text(if (mode == "NEW") "NEW WORD · 新的遇见" else "REVIEW · 温故知新", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(20.dp))
                    WordContent(current.word, revealed)
                    if (!revealed) OutlinedButton(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text("点击展开释义与例句") }
                    else TextButton(onClick = { revealed = false }) { Text("收起释义") }
                    Spacer(Modifier.height(16.dp))
                    WordActions(current, busy, vm)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::previous, enabled = !busy && (position > 0 || (id == null && position >= 0)), modifier = Modifier.weight(1f).heightIn(min = 54.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("上一个")
            }
            Button(onClick = vm::next, enabled = !busy && current != null, modifier = Modifier.weight(1.35f).heightIn(min = 54.dp)) {
                Text("下一个"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable private fun Stat(label: String, value: Int, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun WordContent(word: Word, revealed: Boolean) {
    Text(word.english, Modifier.testTag("word-english"), fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    Text(word.phonetic, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(word.partOfSpeech, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
    if (revealed) {
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text(word.meaning, style = MaterialTheme.typography.titleLarge)
        Text("例句", Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(word.example, style = MaterialTheme.typography.bodyLarge)
        Text(word.translation, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun WordActions(state: WordState, busy: Boolean, vm: StudyViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.progress?.favorite == true, onClick = { vm.favorite(state.word.id) }, enabled = !busy, modifier = Modifier.testTag("favorite-action"),
            leadingIcon = { Icon(if (state.progress?.favorite == true) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null, Modifier.size(18.dp)) },
            label = { Text(if (state.progress?.favorite == true) "已收藏" else "收藏") })
        FilterChip(selected = state.progress?.mastered == true, onClick = { vm.mastered(state.word.id) }, enabled = !busy, modifier = Modifier.testTag("mastered-action"),
            leadingIcon = { Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(18.dp)) },
            label = { Text(if (state.progress?.mastered == true) "已掌握" else "未掌握") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HistoryScreen(words: List<WordState>, favoritesOnly: Boolean, busy: Boolean, vm: StudyViewModel) {
    var query by rememberSaveable(favoritesOnly) { mutableStateOf("") }
    var filter by rememberSaveable(favoritesOnly) { mutableStateOf("all") }
    val filtered = remember(words, query, filter, favoritesOnly) { StudyLogic.filter(words, query, filter, favoritesOnly) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
            label = { Text("搜索英文或中文释义") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清空搜索") } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "unmastered" to "未掌握", "mastered" to "已掌握", "favorite" to "收藏").filter { !favoritesOnly || it.first != "favorite" }.forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
            }
        }
        Text("${filtered.size} 个单词 · 按最近查看排序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (filtered.isEmpty()) {
            Column(Modifier.padding(vertical = 36.dp)) {
                Text(if (query.isNotBlank() || filter != "all") "没有匹配的单词" else if (favoritesOnly) "还没有收藏" else "还没有学习记录", style = MaterialTheme.typography.titleLarge)
                Text(if (query.isNotBlank() || filter != "all") "试试其他关键词或筛选条件。" else "在学习卡片中遇见单词后，这里会自动留下足迹。", Modifier.padding(top = 10.dp))
                TextButton(onClick = { vm.setPage(0) }) { Text("去学习") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.word.id }) { state ->
                Card(onClick = { vm.openDetail(state.word.id) }, enabled = !busy, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.word.english, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            if (state.progress?.favorite == true) Icon(Icons.Default.Bookmark, "已收藏", tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(state.word.meaning, Modifier.padding(top = 6.dp))
                        Text("${if (state.progress?.mastered == true) "已掌握" else "未掌握"} · ${state.progress?.viewCount ?: 0} 次 · ${formatTime(state.progress?.lastViewed ?: 0)}",
                            Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SettingsScreen(settings: Settings, count: Int, busy: Boolean, vm: StudyViewModel, export: () -> Unit, import: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Button(onClick = { vm.setPage(4) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("词库管理") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("默认显示释义", fontWeight = FontWeight.SemiBold); Text("关闭后先回忆，再展开答案", style = MaterialTheme.typography.bodySmall) }
            Switch(settings.showMeaning, vm::showMeaning, enabled = !busy)
        }
        HorizontalDivider()
        Column {
            Text("外观", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                    FilterChip(selected = settings.theme == value, onClick = { vm.theme(value) }, enabled = !busy, label = { Text(label) })
                }
            }
        }
        HorizontalDivider()
        Text("学习记录备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("导出包含词条、查看时间与次数、每日学习记录、收藏和掌握状态。保存到手机文件或自行传到新手机后导入。外观设置不包含在备份中。")
        Button(onClick = export, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("导出 JSON 备份") }
        OutlinedButton(onClick = import, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("导入并合并备份") }
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
            Text("请及时备份：卸载应用或清除应用数据会删除未备份的本地记录。正常覆盖升级会保留数据。备份文件为明文 JSON，请妥善保管。", Modifier.padding(16.dp))
        }
        Text("拾词 1.1.1 · 当前词库 $count 词\n无需登录 · 无联网权限\n复习优先未掌握词，并从最久未看的 5 个词中随机抽取。\n今日数量按学习时设备本地日期去重。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}

private fun formatTime(time: Long): String = if (time <= 0) "尚未查看" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))
