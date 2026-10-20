package com.uzairansar.hermex

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uzairansar.hermex.ui.chat.ChatActivityProgressCard
import com.uzairansar.hermex.ui.chat.ChatActivityProgressModel
import com.uzairansar.hermex.ui.chat.ChatToolActivityItem
import com.uzairansar.hermex.ui.chat.ChatToolActivityStatus
import com.uzairansar.hermex.ui.theme.HermexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatActivityProgressComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val completed = ChatToolActivityItem(
        key = "call:1",
        displayName = "shell",
        detail = null,
        status = ChatToolActivityStatus.Completed,
        durationSeconds = 1.25,
    )

    @Test
    fun cardIsCollapsedByDefaultAndExpandsToShowRecentActivity() {
        composeRule.setContent {
            HermexTheme {
                ChatActivityProgressCard(ChatActivityProgressModel(recent = listOf(completed)))
            }
        }

        composeRule.onNodeWithTag("chat_activity_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_activity_progress_details").assertDoesNotExist()
        composeRule.onNodeWithTag("chat_activity_progress_toggle").assertHasClickAction()
        composeRule.onNodeWithTag("chat_activity_progress_toggle").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expand section"),
        )
        composeRule.onNodeWithTag("chat_activity_progress_toggle").performClick()
        composeRule.onNodeWithTag("chat_activity_progress_toggle").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapse section"),
        )
        composeRule.onNodeWithTag("chat_activity_progress_details").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_activity_progress_item_call:1").assertIsDisplayed()
        composeRule.onNodeWithText("Completed").assertIsDisplayed()
        composeRule.onNodeWithText("shell (1.3s)").assertIsDisplayed()
    }

    @Test
    fun runningAndFailedRowsRemainVisibleAfterExpansion() {
        val running = completed.copy(
            key = "call:2",
            displayName = "browser.search",
            status = ChatToolActivityStatus.Running,
            durationSeconds = null,
        )
        val failed = completed.copy(
            key = "call:3",
            displayName = "shell.failed",
            status = ChatToolActivityStatus.Failed,
        )
        composeRule.setContent {
            HermexTheme {
                ChatActivityProgressCard(
                    ChatActivityProgressModel(running = listOf(running), recent = listOf(failed)),
                )
            }
        }

        composeRule.onNodeWithTag("chat_activity_progress_toggle").performClick()
        composeRule.onNodeWithTag("chat_activity_progress_details").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_activity_progress_item_call:2").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_activity_progress_item_call:3").assertIsDisplayed()
        composeRule.onNodeWithText("Running").assertIsDisplayed()
        composeRule.onNodeWithText("Failed").assertIsDisplayed()
        composeRule.onNodeWithText("shell.failed (1.3s)").assertIsDisplayed()
    }

    @Test
    fun expansionSurvivesLiveModelUpdatesAndResetsForANewTurn() {
        val initial = ChatActivityProgressModel(recent = listOf(completed))
        lateinit var update: (ChatActivityProgressModel, String) -> Unit
        composeRule.setContent {
            var model by remember { mutableStateOf(initial) }
            var resetKey by remember { mutableStateOf("turn-1") }
            update = { next, nextKey ->
                model = next
                resetKey = nextKey
            }
            HermexTheme {
                ChatActivityProgressCard(progress = model, resetKey = resetKey)
            }
        }

        composeRule.onNodeWithTag("chat_activity_progress_toggle").performClick()
        update(
            ChatActivityProgressModel(
                running = listOf(
                    completed.copy(
                        key = "call:2",
                        displayName = "browser.search",
                        status = ChatToolActivityStatus.Running,
                        durationSeconds = null,
                    ),
                ),
                recent = listOf(completed),
            ),
            "turn-1",
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_activity_progress_details").assertIsDisplayed()

        update(ChatActivityProgressModel(), "turn-2")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_activity_progress_details").assertDoesNotExist()
    }
}
