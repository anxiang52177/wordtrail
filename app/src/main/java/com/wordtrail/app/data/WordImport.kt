package com.wordtrail.app.data

import com.google.gson.JsonParser
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class ImportIssue(val line: Int, val reason: String)
data class ParsedImport(val words: List<Word>, val issues: List<ImportIssue>)
data class ImportPreview(val parsed: ParsedImport, val duplicates: Int, val target: String?)

object WordImport {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_ROWS = 50000
    const val TEMPLATE = "english,meaning,phonetic,partOfSpeech,example,translation\r\napple,苹果,/ˈæpəl/,n.,I ate an apple.,我吃了一个苹果。\r\nbook,书籍,,n.,This is a book.,这是一本书。\r\n"

    fun read(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "文件超过 20 MB，请拆分后导入" }
            output.write(buffer, 0, count)
        }
        return decode(output.toByteArray())
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "文件超过 20 MB，请拆分后导入" }
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").also {
                    require('\u0000' !in it) { "文件编码无法识别，请另存为 UTF-8 后重试" }
                }
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("文件编码无法识别，请另存为 UTF-8 后重试", e)
        }
    }

    fun parse(raw: String, format: String = "auto"): ParsedImport {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "内容超过 20 MB，请拆分后导入" }
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return ParsedImport(emptyList(), emptyList())
        if (format != "tsv" && (text.trimStart().startsWith("[") || text.trimStart().startsWith("{"))) return json(text)
        val delimiter = if (format == "tsv" || (format != "csv" && text.lineSequence().first { it.isNotBlank() }.contains('\t'))) '\t' else ','
        val records = records(text, delimiter)
        val words = mutableListOf<Word>()
        val issues = mutableListOf<ImportIssue>()
        var header: List<String>? = null
        records.forEachIndexed { index, record ->
            if (record.error != null) { issues += ImportIssue(record.line, record.error); return@forEachIndexed }
            val fields = record.fields.map { it.trim() }
            val aliases = fields.map { alias(it) }
            if (index == 0 && "english" in aliases && "meaning" in aliases) {
                header = aliases
                require("meaning" in header!!) { "表头缺少 meaning（中文释义）列" }
                require(aliases.size in 2..32 && aliases.distinct().size == aliases.size) { "表头列重复或数量不正确" }
                return@forEachIndexed
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
        "english", "英文", "单词" -> "english"
        "meaning", "中文", "中文释义", "释义" -> "meaning"
        "phonetic", "音标" -> "phonetic"
        "partofspeech", "词性" -> "partOfSpeech"
        "example", "例句" -> "example"
        "translation", "翻译", "例句翻译" -> "translation"
        else -> name
    }

    private fun addWord(values: Map<String, String>, line: Int, words: MutableList<Word>, issues: MutableList<ImportIssue>) {
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

    private fun json(text: String): ParsedImport {
        val root = try { JsonParser.parseString(text) } catch (e: Exception) { throw IllegalArgumentException("JSON 格式损坏，请检查文件", e) }
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.get("format")?.asString == "wordtrail-backup" -> {
                require(root.asJsonObject.get("version")?.asInt == 1) { "暂不支持此备份版本" }
                root.asJsonObject.getAsJsonArray("entries")
            }
            else -> error("不支持的 JSON：请选择项目 words.json 数组或拾词备份")
        } ?: error("JSON 缺少词条数组")
        require(array.size() <= MAX_ROWS) { "最多导入 50000 个词条，请拆分文件" }
        val words = mutableListOf<Word>(); val issues = mutableListOf<ImportIssue>()
        array.forEachIndexed { index, item ->
            try {
                val obj = item.asJsonObject.let { it.getAsJsonObject("word") ?: it }
                val values = listOf("english", "meaning", "phonetic", "partOfSpeech", "example", "translation").associateWith {
                    obj.get(it)?.takeUnless { value -> value.isJsonNull }?.asString.orEmpty()
                }
                addWord(values, index + 1, words, issues)
            } catch (_: Exception) { issues += ImportIssue(index + 1, "JSON 词条结构不正确") }
        }
        return ParsedImport(words, issues)
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
