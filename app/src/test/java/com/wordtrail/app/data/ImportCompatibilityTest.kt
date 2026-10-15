package com.wordtrail.app.data

import org.junit.Assert.*
import org.junit.Test

class ImportCompatibilityTest {
    @Test fun commonWordTranslationsJsonKeepsMeaningsAndExample() {
        val parsed = WordImport.parse("""[{"word":"test","translations":[{"translation":"测试","type":"n"},{"translation":"检验","type":"v"}],"usphone":"test","sentences":[{"sentence":"This is a test.","translation":"这是一次测试。"}]}]""")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        val word = parsed.words.single()
        assertEquals("test", word.english)
        assertTrue(word.meaning.contains("测试") && word.meaning.contains("检验"))
        assertTrue(word.partOfSpeech.contains("n") && word.partOfSpeech.contains("v"))
        assertEquals("test", word.phonetic)
        assertEquals("This is a test.", word.example)
        assertEquals("这是一次测试。", word.translation)
    }
    @Test fun plainTabTextKeepsLiteralQuotes() {
        val parsed = WordImport.parse("test\t表示 \"测试\"\nbook\t书籍", "tsv")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals("表示 \"测试\"", parsed.words.first().meaning)
        assertEquals(2, parsed.words.size)
    }
    @Test fun csvExtensionDoesNotOverrideActualTabDelimiter() {
        val parsed = WordImport.parse("english\tmeaning\ntest\t测试", "csv")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals("测试", parsed.words.single().meaning)
    }
    @Test fun semicolonCsvAndCommonHeaders() {
        val parsed = WordImport.parse("word;definition\ntest;\"测试;检验\"", "csv")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals("测试;检验", parsed.words.single().meaning)
        assertEquals("书籍", WordImport.parse("word,translation\nbook,书籍", "csv").words.single().meaning)
    }
    @Test fun jsonLinesReportsBadLineWithoutDiscardingValidEntries() {
        val text = "{\"word\":\"test\",\"translations\":[{\"translation\":\"测试\",\"type\":\"n\"}]}\nnot-json\n{\"english\":\"book\",\"meaning\":\"书籍\"}"
        val parsed = WordImport.parse(text, "jsonl")
        assertEquals(listOf("test", "book"), parsed.words.map { it.english })
        assertEquals(listOf(2), parsed.issues.map { it.line })
    }
    @Test fun wordAliasAndWrappedArrayAreRecognized() {
        val parsed = WordImport.parse("""{"words":[{"word":"book","meaning":"书籍"},{"english":"bad","meaning":{}}]}""")
        assertEquals("book", parsed.words.single().english)
        assertEquals(1, parsed.issues.size)
    }
    @Test fun dictionaryTabLayoutsKeepMeaningAndPhoneticInCorrectColumns() {
        val simple = WordImport.parse("test\tn::测试¦v::检验\t", "tsv").words.single()
        assertTrue(simple.meaning.contains("测试") && simple.meaning.contains("检验"))
        val fields = listOf("test", "test", "test", "n::测试", "", "A test.::一次测试。") + List(7) { "" }
        val full = WordImport.parse(fields.joinToString("\t"), "tsv")
        assertTrue(full.issues.toString(), full.issues.isEmpty())
        assertEquals("n. 测试", full.words.single().meaning)
        assertEquals("test", full.words.single().phonetic)
        assertEquals("A test.", full.words.single().example)
        assertEquals("一次测试。", full.words.single().translation)
        val compoundPos = WordImport.parse("test\t\ttest\tn&vt::测试\t\t", "tsv").words.single()
        assertEquals("n&vt. 测试", compoundPos.meaning)
    }
    @Test fun gbkMustBeSelectedExplicitlyAndBinaryWorkbooksHaveClearMessage() {
        val bytes = "test\t测试".toByteArray(java.nio.charset.Charset.forName("GB18030"))
        try { WordImport.decode(bytes); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("UTF-8")) }
        assertEquals("测试", WordImport.parse(WordImport.decode(bytes, "gb18030"), "tsv").words.single().meaning)
        try { WordImport.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04)); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("另存为 CSV")) }
    }
    @Test fun nestedDictionaryJsonLinesKeepsHeadwordAndTranslations() {
        val text = """{"headWord":"test","content":{"word":{"wordHead":"test","content":{"usphone":"test","trans":[{"tranCn":"测试","pos":"n"}],"sentence":{"sentences":[{"sContent":"A test.","sCn":"一次测试。"}]}}}}} """
        val parsed = WordImport.parse(text, "jsonl")
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        val word = parsed.words.single()
        assertEquals("test", word.english)
        assertEquals("n. 测试", word.meaning)
        assertEquals("test", word.phonetic)
        assertEquals("A test.", word.example)
        assertEquals("一次测试。", word.translation)
    }
}
