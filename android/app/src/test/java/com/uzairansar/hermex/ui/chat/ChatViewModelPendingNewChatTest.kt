package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.MainDispatcherRule
import com.uzairansar.hermex.core.model.ModelSummary
import com.uzairansar.hermex.core.model.ProfileSummary
import com.uzairansar.hermex.core.network.HermesApiClient
import com.uzairansar.hermex.core.network.SseStreamClient
import com.uzairansar.hermex.data.db.ServerCacheOwnership
import com.uzairansar.hermex.data.repository.ChatRepository
import com.uzairansar.hermex.data.repository.RecordingCacheDao
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPendingNewChatTest {
    private val viewModels = mutableListOf<ChatViewModel>()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @After
    fun tearDownViewModels() {
        viewModels.forEach { viewModel ->
            clearViewModel(viewModel)
        }
        viewModels.clear()
    }

    @Test
    fun pendingComposerLoadsPickersWithoutCreatingASessionAndKeepsExplicitSelections() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val modelsCalls = AtomicInteger()
        val server = pendingChatServer(requests, modelsCalls)
        try {
            val viewModel = pendingViewModel(server)
            val initial = awaitComposer(viewModel)

            assertNull(initial.selectedModel)
            assertEquals("default", initial.selectedProfile?.name)
            assertFalse(requests.any { it.url.encodedPath == "/api/session/new" })

            val selectedModel = ModelSummary(id = "gpt-5", label = "GPT-5", provider = "openai")
            val selectedProfile = ProfileSummary(name = "work", displayName = "Work", provider = "openai")
            viewModel.selectModel(selectedModel)
            viewModel.selectProfile(selectedProfile)

            val selected = withTimeout(5_000) {
                viewModel.state.first {
                    it.selectedModel?.id == "gpt-5" && it.selectedProfile?.name == "work"
                }
            }
            assertEquals("gpt-5", selected.selectedModel?.id)
            assertEquals("work", selected.selectedProfile?.name)
            awaitReasoningRequest(requests, expectedCount = 1)

            viewModel.loadComposerConfig()
            val afterDelayedReload = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    viewModel.state.first {
                        !it.isLoadingComposerConfig &&
                            it.selectedModel?.id == "gpt-5" &&
                            it.selectedProfile?.name == "work"
                    }
                }
            }
            assertTrue(modelsCalls.get() >= 2)
            assertEquals("gpt-5", afterDelayedReload.selectedModel?.id)
            assertEquals("work", afterDelayedReload.selectedProfile?.name)
            awaitReasoningRequest(requests, expectedCount = 2)
            assertFalse(requests.any { it.url.encodedPath == "/api/session/new" })
        } finally {
            closeTestServer(server)
        }
    }

    @Test
    fun rememberedPendingModelIsImmediatelySelectableWithoutCreatingASession() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger())
        try {
            val viewModel = pendingViewModel(server)
            awaitComposer(viewModel)

            viewModel.applyRememberedModel(ModelSummary(id = "gpt-5", label = "GPT-5", provider = "openai"))

            val state = withTimeout(5_000) {
                viewModel.state.first { it.selectedModel?.id == "gpt-5" }
            }
            assertEquals("gpt-5", state.selectedModel?.id)
            assertFalse(state.pendingExplicitModelPick)
            assertFalse(requests.any { it.url.encodedPath == "/api/session/new" })
        } finally {
            closeTestServer(server)
        }
    }

    @Test
    fun noncePendingComposerRetryNeverLoadsTemporarySessionFromServer() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger())
        try {
            val viewModel = pendingViewModel(server, newPendingNewChatSessionId())
            awaitComposer(viewModel)

            viewModel.load()
            delay(100)

            assertFalse(requests.any { it.url.encodedPath == "/api/session" })
            assertFalse(viewModel.state.value.isLoading)
            assertNull(viewModel.state.value.error)
        } finally {
            closeTestServer(server)
        }
    }

    @Test
    fun firstSendCreatesTheSelectedSessionBeforeStartingChatWithTheReturnedId() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger())
        try {
            val viewModel = pendingViewModel(server)
            awaitComposer(viewModel)
            viewModel.selectModel(ModelSummary(id = "gpt-5", label = "GPT-5", provider = "openai"))
            viewModel.selectProfile(ProfileSummary(name = "work", displayName = "Work", provider = "openai"))
            viewModel.updateDraft("hello")
            viewModel.send()

            awaitRequestCount(requests, "/api/chat/start", 1)
            val sessionRequest = requests.first { it.url.encodedPath == "/api/session/new" }
            val chatRequest = requests.first { it.url.encodedPath == "/api/chat/start" }
            val sessionIndex = requests.indexOf(sessionRequest)
            val chatIndex = requests.indexOf(chatRequest)
            assertTrue(sessionIndex < chatIndex)
            assertEquals(
                """{"workspace":"/workspace","model":"gpt-5","model_provider":"openai","profile":"work"}""",
                sessionRequest.body?.utf8(),
            )
            assertEquals(
                """{"session_id":"created-1","message":"hello","workspace":"/workspace","model":"gpt-5","model_provider":"openai","profile":"work","explicit_model_pick":true}""",
                chatRequest.body?.utf8(),
            )
            awaitResponseCompletion(viewModel)
            assertEquals("stream-1", requests.first { it.url.encodedPath == "/api/chat/stream" }.url.queryParameter("stream_id"))
            assertFalse(requests.any { it.url.encodedPath == "/api/profile/switch" })
        } finally {
            closeTestServer(server)
        }
    }

    @Test
    fun anImplicitPendingModelDoesNotLeakTheServerDefault() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger())
        try {
            val viewModel = pendingViewModel(server)
            val state = awaitComposer(viewModel)
            assertNull(state.selectedModel)
            viewModel.updateDraft("without an explicit model")
            viewModel.send()

            awaitRequestCount(requests, "/api/chat/start", 1)
            val sessionBody = requests.first { it.url.encodedPath == "/api/session/new" }.body?.utf8().orEmpty()
            val chatBody = requests.first { it.url.encodedPath == "/api/chat/start" }.body?.utf8().orEmpty()
            assertFalse(sessionBody.contains("Ornith"))
            assertFalse(chatBody.contains("Ornith"))
            assertFalse(sessionBody.contains("\"model\""))
            assertFalse(chatBody.contains("\"model\""))
            awaitResponseCompletion(viewModel)
        } finally {
            closeTestServer(server)
        }
    }

    @Test
    fun workspaceSelectionStaysLocalUntilCreateAndReachesCreateAndStart() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger())
        try {
            val viewModel = pendingViewModel(server)
            awaitComposer(viewModel)
            viewModel.selectWorkspace("/chosen-workspace")
            withTimeout(5_000) { viewModel.state.first { it.selectedWorkspacePath == "/chosen-workspace" } }
            viewModel.updateDraft("workspace test")
            viewModel.send()
            awaitRequestCount(requests, "/api/chat/start", 1)

            assertEquals(0, requests.count { it.url.encodedPath == "/api/session/update" })
            assertTrue(requests.first { it.url.encodedPath == "/api/session/new" }.body?.utf8().orEmpty().contains("/chosen-workspace"))
            assertTrue(requests.first { it.url.encodedPath == "/api/chat/start" }.body?.utf8().orEmpty().contains("/chosen-workspace"))
            awaitResponseCompletion(viewModel)
        } finally {
            closeTestServer(server)
        }
    }

    @Test
    fun rapidDuplicateSendCreatesAndStartsOnlyOnce() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger(), chatStartDelayMillis = 150)
        try {
            val viewModel = pendingViewModel(server)
            awaitComposer(viewModel)
            viewModel.updateDraft("send once")
            viewModel.send()
            viewModel.send()
            awaitRequestCount(requests, "/api/chat/start", 1)
            awaitResponseCompletion(viewModel)
            assertEquals(1, requests.count { it.url.encodedPath == "/api/session/new" })
            assertEquals(1, requests.count { it.url.encodedPath == "/api/chat/start" })
        } finally {
            closeTestServer(server)
        }
    }

    @Test
    fun deferredLocalAttachmentIsConsumedOnceAcrossTwoSends() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger())
        val file = File.createTempFile("hermex-pending-", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val viewModel = pendingViewModel(server)
            awaitComposer(viewModel)
            viewModel.attachCapturedPhoto(file)
            awaitPendingLocalAttachment(viewModel)

            viewModel.updateDraft("first with attachment")
            viewModel.send()
            awaitRequestCount(requests, "/api/chat/start", 1)
            val firstTrigger = awaitResponseCompletion(viewModel).responseCompletionTrigger
            val firstBody = requests.first { it.url.encodedPath == "/api/chat/start" }.body?.utf8().orEmpty()
            assertEquals(1, firstBody.windowed("/uploads/deferred.jpg".length).count { it == "/uploads/deferred.jpg" })
            assertEquals(0, viewModel.state.value.pendingAttachments.size)
            assertEquals(0, viewModel.state.value.pendingLocalUploadCount)

            viewModel.updateDraft("second without attachment")
            viewModel.send()
            awaitRequestCount(requests, "/api/chat/start", 2)
            awaitResponseCompletion(viewModel, afterTrigger = firstTrigger)
            val secondBody = requests.filter { it.url.encodedPath == "/api/chat/start" }[1].body?.utf8().orEmpty()
            assertFalse(secondBody.contains("/uploads/deferred.jpg"))
            assertEquals(
                setOf("stream-1", "stream-2"),
                requests.filter { it.url.encodedPath == "/api/chat/stream" }
                    .mapNotNull { it.url.queryParameter("stream_id") }
                    .toSet(),
            )
        } finally {
            file.delete()
            closeTestServer(server)
        }
    }

    @Test
    fun abandoningPendingComposerDeletesItsDeferredLocalFile() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = pendingChatServer(requests, AtomicInteger())
        val file = File.createTempFile("hermex-abandon-", ".jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        try {
            val viewModel = pendingViewModel(server)
            awaitComposer(viewModel)
            viewModel.attachCapturedPhoto(file)
            awaitPendingLocalAttachment(viewModel)

            viewModel.abandonPendingComposer()

            assertFalse(file.exists())
            assertEquals(0, viewModel.state.value.pendingLocalUploadCount)
            assertEquals(0, viewModel.state.value.pendingAttachments.size)
        } finally {
            file.delete()
            closeTestServer(server)
        }
    }

    @Test
    fun retryAfterChatStartFailureReusesCreatedSession() = runTest {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val chatStarts = AtomicInteger()
        val server = pendingChatServer(requests, AtomicInteger(), chatStarts = chatStarts, failFirstChatStart = true)
        try {
            val viewModel = pendingViewModel(server)
            awaitComposer(viewModel)
            viewModel.updateDraft("retry me")
            viewModel.send()
            awaitRequestCount(requests, "/api/chat/start", 1)
            awaitSendFailure(viewModel)

            viewModel.send()
            awaitRequestCount(requests, "/api/chat/start", 2)
            awaitResponseCompletion(viewModel)
            assertEquals(1, requests.count { it.url.encodedPath == "/api/session/new" })
            assertEquals(2, chatStarts.get())
            assertTrue(requests.filter { it.url.encodedPath == "/api/chat/start" }.all { it.body?.utf8().orEmpty().contains("created-1") })
        } finally {
            closeTestServer(server)
        }
    }

    private suspend fun awaitRequestCount(
        requests: CopyOnWriteArrayList<RecordedRequest>,
        path: String,
        expectedCount: Int,
    ) = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            while (requests.count { it.url.encodedPath == path } < expectedCount) delay(10)
        }
    }

    private suspend fun awaitComposer(viewModel: ChatViewModel): ChatUiState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            viewModel.state.first { !it.isLoadingComposerConfig && it.modelOptions.isNotEmpty() }
        }
    }

    private suspend fun awaitResponseCompletion(
        viewModel: ChatViewModel,
        afterTrigger: Int = 0,
    ): ChatUiState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            viewModel.state.first { it.responseCompletionTrigger > afterTrigger && !it.isStreaming }
        }
    }

    private suspend fun awaitSendFailure(viewModel: ChatViewModel): ChatUiState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            viewModel.state.first { !it.isStreaming && !it.error.isNullOrBlank() }
        }
    }

    private suspend fun awaitPendingLocalAttachment(viewModel: ChatViewModel): ChatUiState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            viewModel.state.first { it.pendingLocalUploadCount == 1 && !it.isUploadingAttachment }
        }
    }

    private suspend fun awaitReasoningRequest(
        requests: CopyOnWriteArrayList<RecordedRequest>,
        expectedCount: Int,
    ) = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) {
            while (requests.count { it.url.encodedPath == "/api/reasoning" } < expectedCount) delay(10)
        }
    }

    private fun clearViewModel(viewModel: ChatViewModel) {
        val scopeJob = viewModel.viewModelScope.coroutineContext[Job]
        androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").apply { isAccessible = true }.invoke(viewModel)
        runBlocking {
            withTimeout(5_000) { scopeJob?.join() }
        }
    }

    private fun closeTestServer(server: MockWebServer) {
        viewModels.toList().forEach { viewModel ->
            clearViewModel(viewModel)
        }
        viewModels.clear()
        server.close()
    }

    private fun pendingViewModel(
        server: MockWebServer,
        sessionId: String = PENDING_NEW_CHAT_SESSION_ID,
    ): ChatViewModel {
        val client = HermesApiClient(server.url("/"), OkHttpClient())
        val repository = ChatRepository(
            client = client,
            cacheDao = RecordingCacheDao(),
            cacheOwnership = ServerCacheOwnership(),
            sse = SseStreamClient(server.url("/"), OkHttpClient()) { emptyList() },
        )
        return ChatViewModel(sessionId, repository).also(viewModels::add)
    }

    private fun pendingChatServer(
        requests: CopyOnWriteArrayList<RecordedRequest>,
        modelsCalls: AtomicInteger,
        chatStarts: AtomicInteger = AtomicInteger(),
        failFirstChatStart: Boolean = false,
        chatStartDelayMillis: Long = 0,
    ): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.url.encodedPath) {
                    "/api/models" -> {
                        if (modelsCalls.incrementAndGet() > 1) {
                            MockResponse.Builder().bodyDelay(150, TimeUnit.MILLISECONDS).buildJson(
                                """{"models":[{"id":"Ornith 1.5","label":"Ornith 1.5","provider":"openai"},{"id":"gpt-5","label":"GPT-5","provider":"openai"}]}""",
                            )
                        } else {
                            MockResponse.Builder().buildJson(
                                """{"models":[{"id":"Ornith 1.5","label":"Ornith 1.5","provider":"openai"},{"id":"gpt-5","label":"GPT-5","provider":"openai"}]}""",
                            )
                        }
                    }
                    "/api/providers" -> json("""{"providers":[]}""")
                    "/api/profiles" -> json(
                        """{"active":"default","single_profile_mode":false,"profiles":[{"name":"default","display_name":"Default"},{"name":"work","display_name":"Work"}]}""",
                    )
                    "/api/workspaces" -> json("""{"last":"/workspace","workspaces":[{"path":"/workspace","name":"workspace"}]}""")
                    "/api/skills" -> json("""{"skills":[]}""")
                    "/api/commands" -> json("""{"commands":[]}""")
                    "/api/reasoning" -> json("""{"supported_efforts":[]}""")
                    "/api/session/new" -> json(
                        """{"ok":true,"session":{"session_id":"created-1"}}""",
                    )
                    "/api/upload" -> json(
                        """{"filename":"deferred.jpg","path":"/uploads/deferred.jpg","mime":"image/jpeg","size":3,"is_image":true}""",
                    )
                    "/api/chat/start" -> {
                        val attempt = chatStarts.incrementAndGet()
                        val response = if (failFirstChatStart && attempt == 1) {
                            MockResponse.Builder().code(500).body("{\"error\":\"temporary\"}")
                        } else {
                            MockResponse.Builder().code(200).body("{\"stream_id\":\"stream-$attempt\",\"session_id\":\"created-1\"}")
                        }
                        if (chatStartDelayMillis > 0) response.bodyDelay(chatStartDelayMillis, TimeUnit.MILLISECONDS)
                        response.setHeader("Content-Type", "application/json").build()
                    }
                    "/api/chat/stream" -> MockResponse.Builder()
                        .setHeader("Content-Type", "text/event-stream")
                        .body("event: done\ndata: {\"session_id\":\"created-1\"}\n\nevent: stream_end\ndata: {}\n\n")
                        .build()
                    else -> MockResponse.Builder().code(404).body("{\"error\":\"unexpected\"}").build()
                }
            }
        }
        start()
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

    private fun MockResponse.Builder.buildJson(body: String): MockResponse =
        code(200).setHeader("Content-Type", "application/json").body(body).build()
}
