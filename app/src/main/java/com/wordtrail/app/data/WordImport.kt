package com.wordtrail.app.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class ImportIssue(val line: Int, val reason: String)
data class ParsedImport(val words: List<Word>, val issues: List<ImportIssue>)
data class ImportPreview(val parsed: ParsedImport, val duplicates: Int, val target: String?)

object WordImport {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_ROWS = 50000
    const val TEMPLATE = "english,meaning,phonetic,partOfSpeech,example,translation\r\napple,苹果,/ˈæpəl/,n.,I ate an apple.,我吃了一个苹果。\r\nbook,书籍,,n.,This is a book.,这是一本书。\r\n"

    fun read(input: InputStream, encoding: String = "auto"): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "文件超过 20 MB，请拆分后导入" }
            output.write(buffer, 0, count)
        }
        return decode(output.toByteArray(), encoding)
    }

    fun decode(bytes: ByteArray, encoding: String = "auto"): String {
        require(bytes.size <= MAX_BYTES) { "文件超过 20 MB，请拆分后导入" }
        val signature = bytes.take(4).map { it.toInt() and 255 }
        require(signature != listOf(0x50, 0x4B, 0x03, 0x04) && signature != listOf(0xD0, 0xCF, 0x11, 0xE0)) {
            "这是 Excel 工作簿或压缩文件，请先另存为 CSV UTF-8 或 Tab 分隔 TXT 再导入"
        }
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            bytes.size >= 3 && bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) -> Charsets.UTF_8
            encoding == "gb18030" -> Charset.forName("GB18030")
            else -> Charsets.UTF_8
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").also {
                    require('\u0000' !in it) { "文件编码无法识别，请另存为 UTF-8 后重试" }
                }
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("文件编码无法识别，请另存为 UTF-8；若是 Excel 的 ANSI 文件，可选择 GBK / GB18030 后重新选择文件", e)
        }
    }

    fun parse(raw: String, format: String = "auto"): ParsedImport {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "内容超过 20 MB，请拆分后导入" }
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return ParsedImport(emptyList(), emptyList())
        if (format == "jsonl" || (format != "tsv" && (text.trimStart().startsWith("[") || text.trimStart().startsWith("{")))) {
            return JsonWordImport.parse(text, format == "jsonl")
        }
        val delimiter = if (format == "tsv") '\t' else detectDelimiter(text)
        // 普通 TXT/TSV 中的引号是文本；只有 CSV 按引号、逗号和跨行字段解析。
        val records = if (delimiter == '\t') tabRecords(text) else records(text, delimiter)
        val words = mutableListOf<Word>()
        val issues = mutableListOf<ImportIssue>()
        var header: List<String>? = null
        records.forEachIndexed { index, record ->
            if (record.error != null) { issues += ImportIssue(record.line, record.error); return@forEachIndexed }
            val fields = record.fields.map { it.trim() }
            var aliases = fields.map { alias(it) }
            if ("english" in aliases && "meaning" !in aliases && "translation" in aliases) {
                aliases = aliases.map { if (it == "translation") "meaning" else it }
            }
            if (index == 0 && "english" in aliases && "meaning" in aliases) {
                header = aliases
                require(aliases.size in 2..32 && aliases.distinct().size == aliases.size) { "表头列重复或数量不正确" }
                return@forEachIndexed
            }
            if (header == null && delimiter == '\t') {
                dictionaryTabValues(fields)?.let {
                    addWord(it, record.line, words, issues)
                    return@forEachIndexed
                }
            }
            if (if (header == null) fields.size !in 2..6 else fields.size != header!!.size) {
                issues += ImportIssue(record.line, "字段数量不正确，需要英文、中文释义，可附加音标、词性、例句、翻译")
            } else {
                val names = header ?: listOf("english", "meaning", "phonetic", "partOfSpeech", "example", "translation")
                val values = names.zip(fields).toMap()
                addWord(values, record.line, words, issues)
            }
        }
        require(words.size + issues.size <= MAX_ROWS) { "最多导入 50000 个词条，请拆分文件" }
        return ParsedImport(words, issues)
    }

    private fun alias(name: String): String = when (name.trim().lowercase()) {
        "english", "word", "term", "英文", "单词" -> "english"
        "meaning", "definition", "chinese", "translations", "中文", "中文释义", "释义" -> "meaning"
        "phonetic", "音标" -> "phonetic"
        "partofspeech", "词性" -> "partOfSpeech"
        "example", "例句" -> "example"
        "translation", "翻译", "例句翻译" -> "translation"
        else -> name
    }

    internal fun addWord(values: Map<String, String>, line: Int, words: MutableList<Word>, issues: MutableList<ImportIssue>) {
        val english = values["english"].orEmpty().trim()
        val meaning = values["meaning"].orEmpty().trim()
        val reason = when {
            english.isBlank() || meaning.isBlank() -> "英文单词和中文释义必填"
            english.length > 200 || values.values.any { it.length > 2000 } -> "字段过长（英文最多 200 字符，其余最多 2000 字符）"
            values.values.any { '\u0000' in it } -> "包含不支持的空字符，请检查文件编码"
            else -> null
        }
        if (reason != null) issues += ImportIssue(line, reason)
        else words += Word("pending", english, values["phonetic"].orEmpty().trim(), values["partOfSpeech"].orEmpty().trim(),
            meaning, values["example"].orEmpty().trim(), values["translation"].orEmpty().trim())
    }

    private fun detectDelimiter(text: String): Char {
        val counts = linkedMapOf('\t' to 0, ',' to 0, ';' to 0)
        var quoted = false
        for (character in text.trimStart('\n')) {
            if (character == '"') quoted = !quoted
            if (!quoted && character == '\n') break
            if (!quoted && character in counts) counts[character] = counts.getValue(character) + 1
        }
        return counts.maxBy { it.value }.takeIf { it.value > 0 }?.key ?: ','
    }

    private fun tabRecords(text: String): List<Record> {
        val result = mutableListOf<Record>()
        text.lineSequence().forEachIndexed { index, line ->
            if (line.isNotBlank()) {
                require(result.size < MAX_ROWS + 1) { "最多导入 50000 个词条，请拆分文件" }
                result += Record(index + 1, line.split('\t'), null)
            }
        }
        return result
    }

    // 常见词典 TSV：3 列（词/释义/短语）或 6/13 列（词/美音/英音/释义/短语/例句/…）。
    private fun dictionaryTabValues(fields: List<String>): Map<String, String>? {
        if (fields.size !in setOf(3, 6, 13)) return null
        val definitions = fields[if (fields.size == 3) 1 else 3]
        if (!Regex("^[a-zA-Z. /&-]*::").containsMatchIn(definitions)) return null
        val meanings = definitions.split('¦').map { it.split("::", limit = 3) }.filter { it.size >= 2 }
        val sentence = if (fields.size >= 6) fields[5].substringBefore('¦').split("::", limit = 2) else emptyList()
        return mapOf(
            "english" to fields[0],
            "meaning" to meanings.joinToString("；") { if (it[0].isBlank()) it[1] else "${it[0].trimEnd('.')}. ${it[1]}" },
            "phonetic" to if (fields.size >= 6) fields[1].ifBlank { fields[2] } else "",
            "partOfSpeech" to meanings.map { it[0] }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
            "example" to if (sentence.size == 2) sentence[0] else "",
            "translation" to if (sentence.size == 2) sentence[1] else ""
        )
    }

    private data class Record(val line: Int, val fields: List<String>, val error: String?)
    // CSV 状态机：双引号转义、字段内逗号/换行均按完整记录处理，保留物理行号。
    private fun records(text: String, delimiter: Char): List<Record> {
        val result = mutableListOf<Record>(); val fields = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var closed = false; var line = 1; var start = 1; var error: String? = null; var i = 0
        fun finish() {
            fields += field.toString(); field.setLength(0)
            if (fields.any { it.isNotBlank() } || error != null) {
                require(result.size < MAX_ROWS + 1) { "最多导入 50000 个词条，请拆分文件" }
                result += Record(start, fields.toList(), error)
            }
            fields.clear(); closed = false; error = null
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' -> if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else { quoted = false; closed = true }
                quoted -> { field.append(c); if (c == '\n') line++ }
                c == delimiter -> { fields += field.toString(); field.setLength(0); closed = false }
                c == '\n' -> { finish(); line++; start = line }
                c == '"' && field.isBlank() && !closed -> { field.setLength(0); quoted = true }
                c == '"' -> { error = "引号位置不正确（字段内引号请写成两个双引号）"; field.append(c) }
                closed && !c.isWhitespace() -> { error = "引号结束后有多余字符"; field.append(c) }
                !closed -> field.append(c)
            }
            i++
        }
        if (quoted) error = "双引号未闭合，请修复此行及后续内容后重新导入"
        finish()
        return result
    }
}
