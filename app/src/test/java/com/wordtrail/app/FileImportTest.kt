package com.wordtrail.app

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
class FileImportTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun installProvider(file: File, fileName: String = "词库.csv") {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/csv"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
                if (uri.lastPathSegment == "no-metadata") throw UnsupportedOperationException("不提供文件名")
                return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(fileName)) }
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (uri.lastPathSegment == "missing") throw FileNotFoundException("文件无法读取")
                return ParcelFileDescriptor.open(file, if (mode.contains('w')) ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(rule.activity, ProviderInfo().apply { authority = "wordtrail.test.documents"; exported = true })
        ShadowContentResolver.registerProviderInternal("wordtrail.test.documents", provider)
    }
    private fun waitText(text: String) = rule.waitUntil(30000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitFinished(vm: StudyViewModel) = rule.waitUntil(30000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes(); !vm.busy.value }

    @Test fun contentUriReadFailureEmptyFileAndTemplateExport() {
        waitText("点击展开释义与例句")
        lateinit var vm: StudyViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[StudyViewModel::class.java] }
        val file = File(rule.activity.cacheDir, "provider-data.csv")
        file.writeText("\uFEFFenglish,meaning\napple,\"苹果,水果\"", Charsets.UTF_8)
        installProvider(file)
        val uri = Uri.parse("content://wordtrail.test.documents/document/42")
        rule.runOnIdle { vm.beginBookEditor("file"); vm.bookName("文件测试"); vm.previewFile(uri) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes(); !vm.busy.value }
        assertNotNull("URI 读取预览为空：${vm.error.value}", vm.importPreview.value)
        assertEquals("苹果,水果", vm.importPreview.value!!.parsed.words.single().meaning)
        rule.runOnIdle { vm.previewFile(Uri.parse("content://wordtrail.test.documents/no-metadata")) }
        waitFinished(vm)
        assertEquals("苹果,水果", vm.importPreview.value!!.parsed.words.single().meaning)
        rule.runOnIdle { vm.previewFile(Uri.parse("content://wordtrail.test.documents/missing")) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("文件无法读取", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertNull(vm.importPreview.value)
        val persistentError = vm.fileImportError.value
        assertTrue(persistentError!!.contains("文件无法读取"))
        rule.runOnIdle { vm.clearError() }
        rule.waitForIdle()
        assertEquals(persistentError, vm.fileImportError.value)
        file.writeBytes("test\t测试".toByteArray(java.nio.charset.Charset.forName("GB18030")))
        rule.runOnIdle { vm.fileEncoding("gb18030"); vm.previewFile(uri) }
        waitFinished(vm)
        assertEquals("测试", vm.importPreview.value!!.parsed.words.single().meaning)
        file.writeText("")
        rule.runOnIdle { vm.previewFile(uri) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes(); !vm.busy.value }
        assertTrue(vm.importPreview.value!!.parsed.words.isEmpty())
        rule.runOnIdle { vm.exportTemplate(uri) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes(); !vm.busy.value }
        assertTrue(file.readText().contains("english,meaning"))
        file.delete()
    }

    @Test fun documentPickerCancelAndJsonResultWorkWithoutManuallyNamingBook() {
        waitText("点击展开释义与例句")
        val file = File(rule.activity.cacheDir, "picker-data.json")
        file.writeText("""[{"word":"test","translations":[{"translation":"测试","type":"n"}]},{"word":"book","translations":[{"translation":"书籍","type":"n"}]}]""")
        installProvider(file, "CET6-test.json")
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("词库管理").performClick()
        waitText("搜索词库名称或来源")
        rule.onNodeWithTag("add-library").performClick()
        rule.onNodeWithText("文件导入").performClick()
        rule.onNodeWithText("选择文件并预览").performScrollTo().assertIsEnabled().performClick()
        val cancelled = shadowOf(rule.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, cancelled.intent.action)
        rule.runOnIdle { rule.activity.activityResultRegistry.dispatchResult(cancelled.requestCode, Activity.RESULT_CANCELED, null) }
        val vm = ViewModelProvider(rule.activity)[StudyViewModel::class.java]
        assertNull(vm.importPreview.value)
        val repo = (rule.activity.application as WordTrailApp).repository
        assertEquals(1, runBlocking { repo.db.dao().books().size })
        rule.onNodeWithText("选择文件并预览").performScrollTo().performClick()
        val selected = shadowOf(rule.activity).nextStartedActivityForResult
        rule.runOnIdle { rule.activity.activityResultRegistry.dispatchResult(selected.requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://wordtrail.test.documents/document/42"))) }
        waitText("有效词条 2 条")
        assertEquals("CET6-test", vm.bookName.value)
        rule.activityRule.scenario.recreate()
        waitText("有效词条 2 条")
        rule.onNodeWithText("确认导入").performScrollTo().performClick()
        waitText("导入完成")
        val book = runBlocking { repo.db.dao().books().single { it.name == "CET6-test" } }
        assertEquals(setOf("test", "book"), runBlocking { repo.db.dao().bookWords(book.id).map { it.english }.toSet() })
        file.delete()
    }
}
