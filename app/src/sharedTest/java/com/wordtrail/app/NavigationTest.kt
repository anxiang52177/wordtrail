package com.wordtrail.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun revealNavigateBackAndSearchHistory() {
        rule.waitUntil(30000) { rule.onAllNodesWithText("点击展开释义与例句").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("点击展开释义与例句").performScrollTo().performClick()
        rule.onNodeWithText("例句").assertExists()
        val firstWord = rule.onNodeWithTag("word-english").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        rule.onNodeWithText("下一个").performClick()
        rule.waitUntil(10000) {
            rule.onNodeWithTag("word-english").fetchSemanticsNode().config[SemanticsProperties.Text].first().text != firstWord
        }
        rule.onNodeWithText("上一个").assertIsEnabled().performClick()
        rule.waitUntil(10000) {
            rule.onNodeWithTag("word-english").fetchSemanticsNode().config[SemanticsProperties.Text].first().text == firstWord
        }
        rule.onNodeWithTag("word-english").assertTextEquals(firstWord)
        rule.onNodeWithTag("favorite-action").performScrollTo().performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("已收藏").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("mastered-action").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("已掌握").fetchSemanticsNodes().size >= 2 }
        val app = rule.activity.application as WordTrailApp
        val before = runBlocking { app.repository.db.dao().progress(firstWord) }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(30000) { rule.onAllNodesWithTag("word-english").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("word-english").assertTextEquals(firstWord)
        assertEquals(before, runBlocking { app.repository.db.dao().progress(firstWord) })
        rule.onNodeWithText("历史").performClick()
        rule.onNodeWithText("搜索英文或中文释义").performTextInput("no-such-word-123")
        rule.onNodeWithText("没有匹配的单词").assertExists()
        rule.onNodeWithContentDescription("清空搜索").performClick()
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("默认显示释义").assertExists()
    }
}
