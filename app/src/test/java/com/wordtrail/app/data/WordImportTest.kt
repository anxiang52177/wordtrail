package com.wordtrail.app.data

import org.junit.Assert.*
import org.junit.Test

class WordImportTest {
    @Test fun tabBomWhitespaceAndLineEndings() {
        val result = WordImport.parse("\uFEFF\r\n apple \t 苹果 \rBook\t书籍\n\nwrong\nempty\t   ", "tsv")
        assertEquals(listOf("apple", "Book"), result.words.map { it.english })
        assertEquals(listOf("苹果", "书籍"), result.words.map { it.meaning })
        assertEquals(listOf(5, 6), result.issues.map { it.line })
        assertEquals("English", WordImport.parse("English\t英语", "tsv").words.single().english)
    }
    @Test fun csvQuotesCommasMultilineAndEscapes() {
        val parsed = WordImport.parse("english,meaning,example\r\napple,\"苹果,水果\",\"He said \"\"apple\"\".\r\nAn apple.\"\r\nbook,书籍,Book.", "csv")
        assertTrue(parsed.issues.isEmpty())
        assertEquals("苹果,水果", parsed.words.first().meaning)
        assertEquals("He said \"apple\".\nAn apple.", parsed.words.first().example)
        assertEquals(2, parsed.words.size)
    }
    @Test fun csvErrorsKeepValidRowsAndReportPhysicalLine() {
        val parsed = WordImport.parse("apple,苹果\nbad,\"词\"oops\nbook,书籍\nx,\"未闭合\ny,后续", "csv")
        assertEquals(listOf("apple", "book"), parsed.words.map { it.english })
        assertEquals(listOf(2, 4), parsed.issues.map { it.line })
    }
    @Test fun csvTemplateRoundTrips() {
        val parsed = WordImport.parse(WordImport.TEMPLATE, "csv")
        assertEquals(2, parsed.words.size); assertTrue(parsed.issues.isEmpty())
        assertEquals("/ˈæpəl/", parsed.words.first().phonetic)
        val reordered = WordImport.parse("中文释义,单词,例句\n书籍,book,This is a book.", "csv")
        assertEquals("book", reordered.words.single().english)
        assertEquals("书籍", reordered.words.single().meaning)
    }
    @Test fun jsonAcceptsAssetsAndOptionalFieldsAndReportsBadEntry() {
        val parsed = WordImport.parse("""[{"english":"apple","meaning":"苹果"},{"english":"missing"},42]""")
        assertEquals(1, parsed.words.size); assertEquals(2, parsed.issues.size)
        assertEquals("", parsed.words.first().example)
    }
    @Test fun backupWordsAreRecognized() {
        val parsed = WordImport.parse("""{"format":"wordtrail-backup","version":1,"entries":[{"word":{"english":"apple","meaning":"苹果"}}]}""")
        assertEquals("苹果", parsed.words.single().meaning)
    }
    @Test fun encodingIsStrictAndBomIsSupported() {
        assertEquals("苹果", WordImport.decode("\uFEFF苹果".toByteArray(Charsets.UTF_8)))
        assertEquals("苹果", WordImport.decode("\uFEFF苹果".toByteArray(Charsets.UTF_16LE)))
        assertEquals("苹果", WordImport.decode("\uFEFF苹果".toByteArray(Charsets.UTF_16BE)))
        try { WordImport.decode(byteArrayOf(0xC3.toByte(), 0x28)); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("UTF-8")) }
    }
    @Test fun emptyAndLargeImportAndLimit() {
        assertTrue(WordImport.parse("\uFEFF\n\r\n").words.isEmpty())
        val large = (1..50000).joinToString("\n") { "word$it\t释义$it" }
        assertEquals(50000, WordImport.parse(large, "tsv").words.size)
        try { WordImport.parse("$large\nextra\t超限", "tsv"); fail() } catch (_: IllegalArgumentException) { }
    }
}
