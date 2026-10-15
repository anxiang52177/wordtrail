package com.wordtrail.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun waitText(text: String) = rule.waitUntil(30000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    @Test fun pastePreviewSurvivesRecreationAndDuplicateCommitDoesNotRepeat() {
        waitText("点击展开释义与例句")
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("词库管理").performClick()
        waitText("搜索词库名称或来源")
        rule.onNodeWithTag("add-library").performClick()
        rule.onNodeWithText("粘贴导入").performClick()
        rule.onNodeWithText("词库名称（必填）").performTextInput("旋转测试词库")
        // 输入 Tab 使用 ViewModel 草稿，避免测试输入法将 Tab 解释为焦点导航。
        val vm = ViewModelProvider(rule.activity)[StudyViewModel::class.java]
        rule.runOnIdle { vm.importText.value = "apple\t苹果\nAPPLE\t重复\nbad-line" }
        rule.onNodeWithText("预览导入").performScrollTo().performClick()
        waitText("有效词条 2 条")
        rule.activityRule.scenario.recreate()
        waitText("有效词条 2 条")
        rule.onNodeWithText("确认导入").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("确认忽略错误行，仅导入有效词条").performScrollTo().performClick()
        rule.onNodeWithText("确认导入").performScrollTo().assertIsEnabled()
        rule.runOnIdle { vm.commitImport(false); vm.commitImport(false) }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitText("导入完成")
        val app = rule.activity.application as WordTrailApp
        val books = runBlocking { app.repository.db.dao().books() }
        val book = books.single { it.name == "旋转测试词库" }
        assertEquals(1, runBlocking { app.repository.db.dao().bookWords(book.id).size })
        rule.onNodeWithTag("import-start-study").performClick()
        waitText("apple")
        rule.onNodeWithTag("word-english").assertTextEquals("apple")
        rule.activityRule.scenario.recreate()
        waitText("apple")
        assertEquals(1L, runBlocking { app.repository.all().single().progress!!.viewCount })
    }

    @Test fun createSingleRenameDeleteAndRestoreFromUi() {
        waitText("点击展开释义与例句")
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("词库管理").performClick()
        waitText("搜索词库名称或来源")
        rule.onNodeWithTag("add-library").performClick()
        rule.onNodeWithText("新建词库").performClick()
        rule.onNodeWithText("词库名称（必填）").performTextInput("我的新词库")
        rule.onNodeWithText("描述（可选）").performTextInput("中文描述")
        rule.onNodeWithText("创建并添加单词").performScrollTo().performClick()
        waitText("英文单词（必填）")
        rule.onNodeWithText("英文单词（必填）").performTextInput("libraryword")
        rule.onNodeWithText("中文释义（必填）").performTextInput("测试中文释义")
        rule.onNodeWithTag("single-add").performScrollTo().performClick()
        waitText("已添加 libraryword")
        rule.onNodeWithText("关闭").performClick()
        val repo = (rule.activity.application as WordTrailApp).repository
        val book = runBlocking { repo.db.dao().books().single { it.name == "我的新词库" } }
        val word = runBlocking { repo.db.dao().bookWords(book.id).single() }
        runBlocking { repo.view(word.id, 100, "2026-10-15") }
        val inCard = hasAnyAncestor(hasTestTag("book-${book.id}"))
        rule.onNodeWithTag("library-list").performScrollToNode(hasTestTag("book-${book.id}"))
        // 小屏需继续滚动到列表末端，使操作区位于悬浮按钮上方。
        rule.onNodeWithTag("library-list").performTouchInput { swipeUp() }
        rule.onNode(hasText("重命名") and inCard).performScrollTo().performClick()
        waitText("重命名词库")
        rule.onNodeWithText("词库名称").performTextClearance()
        rule.onNodeWithText("词库名称").performTextInput("改名后的词库")
        rule.onNodeWithText("保存").performClick()
        waitText("改名后的词库")
        rule.onNode(hasText("删除") and inCard).performScrollTo().performClick()
        rule.onNodeWithText("确认移入回收站").performClick()
        waitText("回收站 (1)")
        rule.onNodeWithText("回收站 (1)").performClick()
        rule.onNodeWithText("恢复").performClick()
        waitText("回收站 (0)")
        assertFalse(runBlocking { repo.db.dao().book(book.id)!!.deleted })
        assertEquals("改名后的词库", runBlocking { repo.db.dao().book(book.id)!!.name })
        assertEquals(1L, runBlocking { repo.db.dao().progress(word.id)!!.viewCount })
    }
}
