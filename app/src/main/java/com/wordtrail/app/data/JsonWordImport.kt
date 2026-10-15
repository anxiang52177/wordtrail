package com.wordtrail.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object JsonWordImport {
    fun parse(text: String, jsonLines: Boolean): ParsedImport {
        val words = mutableListOf<Word>()
        val issues = mutableListOf<ImportIssue>()
        fun add(item: JsonElement, line: Int) {
            try {
                require(item.isJsonObject) { "JSON 词条必须是对象" }
                val outer = item.asJsonObject
                // 备份的 word 是对象，常见词库的 word 是英文字符串，两者不能混用。
                val nested = outer.objectOrNull("content")?.objectOrNull("word") ?: outer.objectOrNull("word")
                val obj = nested?.objectOrNull("content") ?: nested ?: outer
                val english = string(outer, "headWord", "wordHead").ifBlank {
                    nested?.let { string(it, "wordHead", "headWord", "english") }.orEmpty()
                }
                WordImport.addWord(values(obj, english), line, words, issues)
            } catch (e: RuntimeException) {
                issues += ImportIssue(line, e.message ?: "JSON 词条结构不正确")
            }
        }
        val firstLine = text.lineSequence().first { it.isNotBlank() }.trim()
        val useLines = jsonLines || (firstLine.startsWith("{") && firstLine.endsWith("}") && text.trim().length > firstLine.length)
        if (useLines) {
            var count = 0
            text.lineSequence().forEachIndexed { index, line ->
                if (line.isNotBlank()) {
                    require(++count <= WordImport.MAX_ROWS) { "最多导入 50000 个词条，请拆分文件" }
                    val item = try { JsonParser.parseString(line) } catch (_: Exception) { null }
                    if (item == null) issues += ImportIssue(index + 1, "此行不是有效的 JSON 对象") else add(item, index + 1)
                }
            }
        } else {
            val root = try { JsonParser.parseString(text) } catch (e: Exception) {
                throw IllegalArgumentException("JSON 格式损坏，请检查括号、引号；逐行 JSON 请使用 .jsonl 文件", e)
            }
            val entries: List<JsonElement> = when {
                root.isJsonArray -> root.asJsonArray.toList()
                root.isJsonObject -> {
                    val obj = root.asJsonObject
                    if (string(obj, "format") == "wordtrail-backup") {
                        require(obj.get("version")?.toString() == "1") { "暂不支持此备份版本" }
                        require(obj.get("entries")?.isJsonArray == true) { "JSON 备份缺少 entries 词条数组" }
                        obj.getAsJsonArray("entries").toList()
                    } else {
                        val array = listOf("words", "data", "entries").firstNotNullOfOrNull { obj.get(it)?.takeIf { value -> value.isJsonArray } }
                        when {
                            array != null -> array.asJsonArray.toList()
                            obj.has("english") || obj.has("word") || obj.has("headWord") -> listOf(obj)
                            else -> error("JSON 中没有找到词条。支持 english/meaning、word/translations、words 数组或拾词备份")
                        }
                    }
                }
                else -> error("JSON 必须是词条数组或词条对象")
            }
            require(entries.size <= WordImport.MAX_ROWS) { "最多导入 50000 个词条，请拆分文件" }
            entries.forEachIndexed { index, item -> add(item, index + 1) }
        }
        return ParsedImport(words, issues)
    }

    private fun string(obj: JsonObject, vararg names: String): String {
        for (name in names) {
            val value = obj.get(name)?.takeUnless { it.isJsonNull } ?: continue
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name 字段应为文本" }
            if (value.asString.isNotBlank()) return value.asString.trim()
        }
        return ""
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun values(obj: JsonObject, englishFallback: String): Map<String, String> {
        val directMeaning = string(obj, "meaning", "definition", "chinese")
        val translations = (obj.get("translations") ?: obj.get("trans"))?.takeUnless { it.isJsonNull }?.let { value ->
            when {
                value.isJsonArray -> value.asJsonArray.map { item ->
                    if (item.isJsonObject) string(item.asJsonObject, "type", "pos") to string(item.asJsonObject, "translation", "meaning", "text", "tranCn")
                    else {
                        require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "translations 中的释义应为文本或对象" }
                        "" to item.asString.trim()
                    }
                }
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf("" to value.asString.trim())
                else -> error("translations 字段应为释义数组或文本")
            }
        }.orEmpty().filter { it.second.isNotBlank() }
        val meaning = directMeaning.ifBlank {
            translations.joinToString("；") { (type, text) -> if (type.isBlank()) text else "${type.trimEnd('.')}. $text" }
        }.ifBlank { string(obj, "translation") }
        val sentence = (obj.get("sentences") ?: obj.objectOrNull("sentence")?.get("sentences"))?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull { it.isJsonObject }?.asJsonObject
        return mapOf(
            "english" to string(obj, "english", "word", "term", "headWord", "wordHead").ifBlank { englishFallback },
            "meaning" to meaning,
            "phonetic" to string(obj, "phonetic", "usphone", "us", "ukphone", "uk"),
            "partOfSpeech" to string(obj, "partOfSpeech", "pos", "type").ifBlank { translations.map { it.first }.filter { it.isNotBlank() }.distinct().joinToString(" / ") },
            "example" to string(obj, "example").ifBlank { sentence?.let { string(it, "sentence", "english", "text", "sContent") }.orEmpty() },
            "translation" to string(obj, "exampleTranslation", "sentenceTranslation").ifBlank {
                sentence?.let { string(it, "translation", "chinese", "sCn") }.orEmpty()
            }.ifBlank { if (directMeaning.isNotBlank() || translations.isNotEmpty()) string(obj, "translation") else "" }
        )
    }
}
