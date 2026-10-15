package com.wordtrail.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
class FileImportTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun contentUriReadFailureEmptyFileAndTemplateExport() {
        rule.waitUntil(30000) { rule.onAllNodesWithText("点击展开释义与例句").fetchSemanticsNodes().isNotEmpty() }
        lateinit var vm: StudyViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[StudyViewModel::class.java] }
        rule.waitUntil(30000) { vm.ready.value && !vm.busy.value }
        val file = File(rule.activity.cacheDir, "provider-data.csv")
        file.writeText("\uFEFFenglish,meaning\napple,\"苹果,水果\"", Charsets.UTF_8)
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/csv"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("词库.csv")) }
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
        val uri = Uri.parse("content://wordtrail.test.documents/document/42")
        rule.runOnIdle { vm.beginBookEditor("file"); vm.bookName("文件测试"); vm.previewFile(uri) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes(); !vm.busy.value }
        assertNotNull("URI 读取预览为空：${vm.error.value}", vm.importPreview.value)
        assertEquals("苹果,水果", vm.importPreview.value!!.parsed.words.single().meaning)
        rule.runOnIdle { vm.previewFile(Uri.parse("content://wordtrail.test.documents/missing")) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("文件无法读取", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertNull(vm.importPreview.value)
        file.writeText("")
        rule.runOnIdle { vm.previewFile(uri) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes(); !vm.busy.value }
        assertTrue(vm.importPreview.value!!.parsed.words.isEmpty())
        rule.runOnIdle { vm.exportTemplate(uri) }
        rule.waitUntil(30000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes(); !vm.busy.value }
        assertTrue(file.readText().contains("english,meaning"))
        file.delete()
    }
}
