package com.finecat.pimobile.android

import com.finecat.pimobile.android.platform.OkHttpGatewayTransport
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import com.finecat.pimobile.shared.gateway.GatewayRequest
import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.gateway.GatewayRuntime
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import com.finecat.pimobile.shared.gateway.GatewayRuntimeState
import com.finecat.pimobile.shared.protocol.inputCompletions
import com.finecat.pimobile.shared.platform.GatewayAttachmentCache
import com.finecat.pimobile.shared.platform.GatewayClock
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import com.finecat.pimobile.shared.platform.GatewayNetworkMonitor
import com.finecat.pimobile.shared.platform.GatewayNetworkState
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 跨仓库协议一致性测试（loopback-only）：真实网关进程（pi-mobile-gateway 的
 * 生产 server/router/mgmt/credentials 代码 + 真 PiRuntime + 脚本化假 pi 会话）
 * 对抗本 App 的生产协议栈（OkHttpGatewayTransport 真握手 + GatewayRuntime +
 * AndroidGatewayProjection），把"新建对话 → 发消息 → 流式 → 历史"的完整用户
 * 动线按 AndroidSharedStateHolder 的真实时序重放。
 *
 * 头号断言：全程零 `{kind:"error"}` 帧。此前真机联调暴露的 session-stats
 * unknown-command 弹窗、新会话 history 不引导、握手串漂移（fb5935e）在这个
 * 层面全部可观测。
 *
 * 场景：完整发消息动线、中途停止生成（abort）、状态帧、历史分页、断线重连、
 * 错误配对码拒绝、工作区选择器（目录浏览/新建文件夹/新建工作区/以新 cwd 开会话）、
 * 附件按需取回（§4.22：history 剥图后按 entryId+imageIndex 取回原图）。
 *
 * 隔离：网关与客户端都只绑 127.0.0.1；设备注册表/会话目录在临时目录；
 * 不触 ~/.pi、真实模型与非回环网络。仓库缺失或无 node 时跳过。
 */
class GatewayConformanceTest {

    @Test
    fun worktreeListsCreatesAndContinuesThroughProductionProtocol() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val cwd = File(fixture.info.fixtureCwd)
            fun git(vararg args: String) {
                val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false") + args)
                    .directory(cwd).redirectErrorStream(true).apply {
                        environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                        environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                    }.start()
                val text = process.inputStream.bufferedReader().readText()
                assertTrue("git未及时结束", process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(text, 0, process.exitValue())
            }
            git("init", "-b", "main")
            cwd.resolve("README.md").writeText("Synthetic worktree fixture")
            git("add", "README.md")
            git("-c", "user.name=Synthetic", "-c", "user.email=synthetic@example.invalid", "commit", "-m", "seed")
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            assertTrue("worktree-v1" in runtime.state.value.capabilities)
            assertTrue(runtime.sendRequest(GatewayRequests.worktreeList("list-test", sessionId = fixture.info.fixtureSessionId)))
            val listing = awaitFrame(frames) { it.frame.kind == "worktree-list" }.frame
            assertEquals("list-test", listing.queryId)
            assertEquals(cwd.canonicalPath, listing.cwd)
            assertEquals(1, listing.worktrees?.size)
            assertEquals("main", listing.worktrees?.single()?.branch)
            val target = cwd.resolve("linked-worktree-target").absolutePath
            val createId = UUID.randomUUID().toString()
            assertTrue(runtime.sendRequest(GatewayRequests.worktreeCreate(createId, cwd.absolutePath, "fix/mobile", "HEAD", target)))
            val created = awaitFrame(frames) { it.frame.operationId == createId }.frame
            assertEquals("succeeded", created.status)
            val continueId = UUID.randomUUID().toString()
            assertTrue(runtime.sendRequest(GatewayRequests.worktreeContinue(continueId, fixture.info.fixtureSessionId, target)))
            val continued = awaitFrame(frames) { it.frame.operationId == continueId }.frame
            assertEquals("succeeded", continued.status)
            assertEquals(fixture.info.fixtureSessionId, continued.sessionId)
            assertEquals(continueId, continued.targetSessionId)
            assertEquals(File(target).canonicalPath, continued.cwd)
            frames.clear()
            assertTrue(runtime.sendRequest(GatewayRequests.worktreeStatus(continueId)))
            val confirmed = awaitFrame(frames) { it.frame.operationId == continueId }.frame
            assertEquals(continued.targetSessionId, confirmed.targetSessionId)
            assertEquals("succeeded", confirmed.status)
            assertNoErrorFrames(frames, runtime)
        } finally {
            fixture.process.destroy()
            fixture.process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun providerProbeCarriesSafeDiagnosticsAndRequiresModelResponse() = runBlocking {
        // 真实 HTTP → 网关探针/router → Kotlin 解码，完全回环，不访问真实上游。
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val upstream = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val attempt = calls.getAndIncrement()
            val body = when (attempt) {
                0 -> """{"error":{"message":"synthetic-private-key-and-url"}}"""
                1 -> "{}"
                else -> """{"choices":[{"message":{"content":"pong"}}]}"""
            }.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (attempt == 0) 401 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        upstream.start()
        val fixture = try { startFixtureProcess() } catch (error: Throwable) { upstream.stop(0); throw error }
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            assertTrue("新版探针需回显请求标识", "provider-test-id-v1" in runtime.state.value.capabilities)
            listOf("auth-rejected", "invalid-response", null).forEachIndexed { index, expectedCode ->
                frames.clear()
                assertTrue(runtime.sendRequest(GatewayRequests.providerTest(
                    baseUrl = "http://127.0.0.1:${upstream.address.port}/v1",
                    apiKey = "synthetic-key", model = "synthetic-model", api = "openai-completions",
                    providerTestId = "probe-$index",
                )))
                val result = awaitFrame(frames) { it.frame.kind == "provider-test-result" }.frame
                assertEquals(index == 2, result.ok)
                assertEquals(expectedCode, result.code)
                assertEquals("probe-$index", result.providerTestId)
                assertEquals(if (index == 0) 401 else 200, result.httpStatus)
                assertTrue(requireNotNull(result.latencyMs) >= 0)
                assertNull("探针不回显服务地址", result.url)
                assertTrue(result.error?.contains("synthetic-private") != true)
                assertEquals(if (index == 2) null else "config", result.failureKind)
            }
            assertEquals(3, calls.get())
            assertNoErrorFrames(frames, runtime)
        } finally {
            upstream.stop(0)
            fixture.process.destroy()
            fixture.process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun handledInputUsesRealGatewayAckAndDoesNotWaitForInventedRun() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            assertTrue("新网关必须声明输入处置", "prompt-disposition-v1" in runtime.state.value.capabilities)
            assertTrue(runtime.sendMessage("fixture:handled", emptyList(), fixture.info.fixtureSessionId))
            val sent = awaitFrame(frames) { it.frame.kind == "sent" }.frame
            assertEquals("handled", sent.disposition)
            assertEquals(false, sent.isStreaming)
            awaitState(runtime) { !it.shouldKeepAliveInBackground }
            assertTrue(frames.none { it.normalizedEvents.any { record -> record.event.type == "turn/end" } })
            assertNoErrorFrames(frames, runtime)
        } finally {
            fixture.process.destroy()
            fixture.process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun extensionStatusFramesBridgePiSetStatusAcrossRealGatewayAndClient() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            assertTrue(
                "hello.capabilities 必须宣告 extension-status：${runtime.state.value.capabilities}",
                "extension-status" in runtime.state.value.capabilities
            )

            // fixture 会话在收到该文本时调用 ctx.ui.setStatus，80ms 后清除。
            assertTrue(runtime.sendMessage("fixture:status", emptyList(), fixture.info.fixtureSessionId))
            val live = awaitFrame(frames) {
                it.frame.kind == "extension-status" &&
                    it.frame.statuses.orEmpty().any { status -> status.text == "1 agent running" }
            }
            assertEquals(fixture.info.fixtureSessionId, live.frame.sessionId)
            val cleared = awaitFrame(frames) {
                it.frame.kind == "extension-status" && it.frame.statuses.orEmpty().isEmpty()
            }
            assertEquals(fixture.info.fixtureSessionId, cleared.frame.sessionId)
            assertNoErrorFrames(frames, runtime)
        } finally {
            fixture.process.destroy()
            fixture.process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    fun inputAssistPreviewAndQuotedFileUseProductionWireAgainstIsolatedGateway() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val cwd = File(fixture.info.fixtureCwd)
            cwd.resolve(".pi/prompts/evaluate.md").apply {
                parentFile.mkdirs(); writeText("---\ndescription: Synthetic evaluation\n---\nEvaluate $1")
            }
            cwd.resolve(".agents/skills/audit/SKILL.md").apply {
                parentFile.mkdirs(); writeText("---\nname: audit\ndescription: Synthetic audit\n---\nReview locally.")
            }
            cwd.resolve("my docs/guide.md").apply { parentFile.mkdirs(); writeText("synthetic file") }
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            assumeTrue("需要 input-assist 候选网关，旧网关仅验证降级", "input-resources" in runtime.state.value.capabilities)
            assertTrue(runtime.sendRequest(GatewayRequests.inputResources("preview", cwd = cwd.absolutePath)))
            val resources = awaitFrame(frames) { it.frame.kind == "input-resources" && it.frame.queryId == "preview" }.frame
            assertEquals("preview", resources.mode)
            assertEquals(false, resources.complete)
            assertTrue(resources.commands.orEmpty().any { it.source == "skill" && it.name == "skill:audit" })
            assertTrue(resources.commands.orEmpty().any { it.source == "prompt" && it.name == "evaluate" })
            val token = "@\"my docs/gu"
            assertTrue(runtime.sendRequest(GatewayRequests.inputCompletions("files", token, cwd = cwd.absolutePath)))
            val completion = awaitFrame(frames) { it.frame.kind == "input-completions" && it.frame.queryId == "files" }.frame
            assertEquals(token, completion.prefix)
            val file = completion.inputCompletions().single { it.path.endsWith("guide.md") }
            assertEquals("file", file.type)
            assertEquals("@\"my docs/guide.md\" ", file.insertText)
            assertEquals(file.insertText.length, file.cursorOffset)
            assertNoErrorFrames(frames, runtime)
        } finally {
            fixture.process.destroy()
            fixture.process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    // -- 环境探测 ---------------------------------------------------------------

    private fun gatewayDir(): File? {
        val candidates = listOfNotNull(
            System.getenv("PI_GATEWAY_DIR")?.let(::File),
            File("..").resolve("pi-mobile-gateway"),
            File("../..").resolve("pi-mobile-gateway")
        )
        val nodeCheck = runCatching {
            ProcessBuilder("node", "--version").start().waitFor()
        }
        val dir = candidates.firstOrNull {
            it.isDirectory && File(it, "scripts/dev-fixture-gateway.ts").isFile
        }
        val ok = dir != null && nodeCheck.getOrDefault(-1) == 0
        if (!ok) {
            println(
                "conformance 探测: candidates=${candidates.map { it.absolutePath }} " +
                    "nodeCheck=$nodeCheck user.dir=${System.getProperty("user.dir")}"
            )
        }
        return if (ok) dir else null
    }

    // -- 平台 fake（仅 JVM 测试用；生产实现是 Keystore/DataStore） ----------------

    private class InMemoryPreferences : GatewayPreferences {
        private val mutable = MutableStateFlow(GatewayPreferencesSnapshot())
        override val snapshots: StateFlow<GatewayPreferencesSnapshot> = mutable
        override suspend fun load(): GatewayPreferencesSnapshot = mutable.value
        override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
            mutable.value = snapshot
        }
    }

    private class InMemoryCredentials : GatewayCredentialStore {
        @Volatile private var token: String? = null
        private val deviceId: String = UUID.randomUUID().toString().lowercase()
        override suspend fun loadOrCreateDeviceId(): String = deviceId
        override suspend fun loadToken(): String? = token
        override suspend fun saveToken(token: String) {
            this.token = token
        }

        override suspend fun deleteToken() {
            token = null
        }
    }

    private class NoopAttachmentCache : GatewayAttachmentCache {
        override suspend fun read(attachmentId: String): ByteArray? = null
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean = true
        override suspend fun removeExpired() = Unit
    }

    /** 记录读写的内存缓存：验证 fetchAttachment 成功后写会话级缓存、
     *  第二次取图直接命中（不再发请求）。 */
    private class RecordingAttachmentCache : GatewayAttachmentCache {
        val values = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val readHits = CopyOnWriteArrayList<String>()
        override suspend fun read(attachmentId: String): ByteArray? {
            val value = values[attachmentId]
            if (value != null) readHits += attachmentId
            return value
        }
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean {
            values[attachmentId] = bytes
            return true
        }
        override suspend fun removeExpired() = Unit
    }

    /** 委托真实 OkHttp transport，按 wire type 统计发出的请求（观察 §4.22 取图次数）。 */
    private class CountingTransport : com.finecat.pimobile.shared.platform.GatewayTransport {
        private val delegate = OkHttpGatewayTransport()
        val sentTypes = CopyOnWriteArrayList<String>()
        override val state: StateFlow<com.finecat.pimobile.shared.platform.GatewayTransportState> = delegate.state
        override val events = delegate.events
        override suspend fun open(spec: com.finecat.pimobile.shared.platform.GatewayConnectionSpec) = delegate.open(spec)
        override suspend fun send(text: String) {
            val type = runCatching {
                Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
            }.getOrNull()
            if (type != null) sentTypes += type
            delegate.send(text)
        }
        override suspend fun close() = delegate.close()
    }

    private class AvailableNetwork : GatewayNetworkMonitor {
        override val state: StateFlow<GatewayNetworkState> =
            MutableStateFlow(GatewayNetworkState.AVAILABLE)
    }

    private class SystemClock : GatewayClock {
        override fun nowEpochMilliseconds(): Long = System.currentTimeMillis()
        override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
    }

    // -- 等待辅助（真实 socket，用真实时钟轮询） ---------------------------------

    private suspend fun awaitFrame(
        frames: CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>,
        timeoutMs: Long = 10_000,
        predicate: (GatewayRuntimeEvent.Frame) -> Boolean
    ): GatewayRuntimeEvent.Frame = withTimeout(timeoutMs) {
        var found: GatewayRuntimeEvent.Frame? = null
        while (found == null) {
            found = frames.lastOrNull(predicate)
            if (found == null) delay(25)
        }
        found
    }

    private suspend fun awaitState(
        runtime: GatewayRuntime,
        timeoutMs: Long = 10_000,
        predicate: (GatewayRuntimeState) -> Boolean
    ) = withTimeout(timeoutMs) {
        while (!predicate(runtime.state.value)) delay(25)
    }

    private suspend fun awaitCondition(timeoutMs: Long = 15_000, predicate: () -> Boolean) =
        withTimeout(timeoutMs) {
            while (!predicate()) delay(25)
        }

    /** 等待一个下标 ≥ minIndex 且满足谓词的帧，返回（新下标, 帧）；用于同一
     * 请求类型的多次往返（如历史翻页）避免旧帧误匹配。 */
    private suspend fun awaitNewFrame(
        frames: CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>,
        minIndex: Int,
        timeoutMs: Long = 10_000,
        predicate: (GatewayRuntimeEvent.Frame) -> Boolean
    ): Pair<Int, GatewayRuntimeEvent.Frame> = withTimeout(timeoutMs) {
        while (true) {
            for (i in minIndex until frames.size) {
                if (predicate(frames[i])) return@withTimeout i to frames[i]
            }
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    // -- fixture 进程管理 -------------------------------------------------------

    private data class FixtureInfo(val pairingText: String, val fixtureSessionId: String, val fixtureCwd: String)

    private fun parseFixture(line: String): FixtureInfo {
        val json = Json.parseToJsonElement(line.removePrefix("FIXTURE_READY ")).jsonObject
        return FixtureInfo(
            pairingText = json.getValue("pairingText").jsonPrimitive.content,
            fixtureSessionId = json.getValue("fixtureSessionId").jsonPrimitive.content,
            fixtureCwd = json.getValue("fixtureCwd").jsonPrimitive.content
        )
    }

    // -- 用例 -------------------------------------------------------------------

    @Test
    fun fullUserFlowAgainstRealGatewayNeverSeesErrorFrames() = runBlocking {
        val dir = gatewayDir()
        assumeTrue(
            "跳过：未找到 pi-mobile-gateway 仓库或本机无 node（可设 PI_GATEWAY_DIR 指向网关仓库）",
            dir != null
        )
        println("conformance 使用网关目录: ${dir!!.absolutePath}")
        val process = ProcessBuilder("node", "scripts/dev-fixture-gateway.ts")
            // fixture 的 HOME 已隔离，不能让宿主的显式 agent 目录覆盖它。
            .apply { environment().remove("PI_CODING_AGENT_DIR") }
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val ready = AtomicReference<FixtureInfo?>()
        val stderr = StringBuilder()
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("FIXTURE_READY ")) {
                    runCatching { ready.set(parseFixture(line)) }
                } else {
                    synchronized(stderr) { stderr.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; start() }
        Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderr) { stderr.appendLine(line) }
            }
        }.apply { isDaemon = true; start() }

        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        var projection: AndroidGatewayProjection? = null
        try {
            withTimeout(20_000) {
                while (ready.get() == null) delay(25)
            }
            val info = ready.get()!!
            val runtime = GatewayRuntime(
                OkHttpGatewayTransport(),
                InMemoryPreferences(),
                InMemoryCredentials(),
                NoopAttachmentCache(),
                AvailableNetwork(),
                SystemClock(),
                scope,
                30_000,
                60_000
            )
            // 与 AndroidSharedStateHolder 相同的投影接线：全部帧喂投影，
            // history 请求由投影效果驱动（sent 帧补拉也在其中）。
            projection = AndroidGatewayProjection { sessionId, beforeEntryId, resetLeaf ->
                scope.launch { runtime.requestHistory(sessionId, beforeEntryId, 50, resetLeaf) }
            }
            val collector = scope.launch {
                runtime.events.collect { event ->
                    if (event is GatewayRuntimeEvent.Frame) {
                        frames += event
                        projection.acceptFrame(event.rawJson, event.frame, event.correlatedSessionId, event.normalizedEvents)
                    }
                }
            }
            runtime.applicationDidBecomeActive()

            // 1) 真实配对握手：paired → hello，capabilities 必含 message
            runtime.pair(info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            awaitFrame(frames) { it.frame.kind == "hello" }

            // 2) 会话列表：fixture 会话必须出现且进入投影
            runtime.requestSessions()
            awaitFrame(frames) { it.frame.kind == "sessions" }
            awaitCondition {
                projection.snapshot().sessions.any { it.id == info.fixtureSessionId }
            }

            // 3) 新建对话 → 发消息（复刻 AndroidSharedStateHolder 动线）：
            //    prepareNewSession 的 subscribe(null) 是 no-op；sendMessage 带 cwd；
            //    sent 后 subscribe + requestSessions。sessionControl 四命令已按
            //    capabilities 门控（本网关未宣告 → 不发）。
            runtime.subscribe(null)
            assertTrue(runtime.sendMessage("你好", emptyList(), sessionId = null, cwd = info.fixtureCwd))
            val sent = awaitFrame(frames) { it.frame.kind == "sent" }
            val newSessionId = sent.frame.sessionId ?: error("sent 帧未携带 sessionId")
            runtime.subscribe(newSessionId)
            runtime.requestSessions()

            // 4) 流式：文本增量 + 回合落定（真实 SDK 的 agent_settled → turn/end）
            awaitFrame(frames) {
                it.frame.kind == "event" && it.normalizedEvents.any { it.event.type == "assistant/chunk" }
            }
            awaitFrame(frames) {
                it.frame.kind == "event" && it.normalizedEvents.any { it.event.type == "turn/end" }
            }
            awaitCondition {
                // message_end 的最终文本替换流式增量是 conversation store 的正确行为
                projection.snapshot().conversation.any { it.text.contains("回复:") }
            }

            // 5) 新会话 history 引导（sent 补拉路径）：history 帧必须回到位
            awaitFrame(frames, timeoutMs = 15_000) {
                it.frame.kind == "history" && it.correlatedSessionId == newSessionId
            }

            // 6) 既有会话：subscribe + history（fixture 会话有 2 条消息）
            runtime.subscribe(info.fixtureSessionId)
            runtime.requestHistory(info.fixtureSessionId)
            awaitFrame(frames) {
                it.frame.kind == "history" && it.correlatedSessionId == info.fixtureSessionId
            }

            // 6b) 历史页必须按时间顺序渲染：pi messages 是 oldest→newest，
            // 客户端合成负 seq 不得把页内顺序翻转。
            projection.selectSession(info.fixtureSessionId)
            awaitNewFrame(frames, frames.size) {
                it.frame.kind == "history" && it.correlatedSessionId == info.fixtureSessionId
            }
            awaitCondition {
                projection.snapshot().conversation.map { it.text } == listOf("hello", "hi there")
            }

            // 7) 头号断言：整条动线零错误帧；运行时无 lastError
            val errors = frames.filter { it.frame.kind == "error" }
            assertEquals(
                "用户动线收到错误帧：${errors.map { it.frame.code to it.frame.message }}\n" +
                    "stderr=${stderrSnapshot(stderr)}",
                emptyList<GatewayRuntimeEvent.Frame>(),
                errors
            )
            assertEquals(null, runtime.state.value.lastError)

            collector.cancel()
            runtime.disconnect()
        } catch (t: Throwable) {
            val diag = buildString {
                appendLine("=== 帧轨迹 ===")
                frames.forEach { f ->
                    appendLine(
                        "  kind=${f.frame.kind} corr=${f.correlatedSessionId} sid=${f.frame.sessionId} " +
                            "types=${f.normalizedEvents.joinToString("/") { it.event.type }} code=${f.frame.code}"
                    )
                    if (f.frame.kind in setOf("sessions", "sent", "hello", "history")) {
                        appendLine("    raw=${f.rawJson.take(500)}")
                    }
                }
                val snap = projection?.snapshot()
                if (snap != null) {
                    appendLine("=== 投影快照 ===")
                    appendLine("  selected=${snap.selectedSessionId} lastFrameKind=${snap.lastFrameKind} lastError=${snap.lastError}")
                    appendLine("  sessions=${snap.sessions.map { it.id }}")
                    appendLine("  conversation=${snap.conversation.map { it.id + ":" + it.text.take(40) }}")
                }
                appendLine("=== 网关 stderr 尾部 ===")
                appendLine(stderrSnapshot(stderr))
            }
            runCatching { File("/tmp/conformance-diag.txt").writeText(diag) }
            println(diag)
            throw t
        } finally {
            process.destroy()
            val tail = stderrSnapshot(stderr)
            if (tail.isNotBlank()) println("fixture stderr tail:\n$tail")
        }
    }

    private fun stderrSnapshot(stderr: StringBuilder): String =
        synchronized(stderr) { stderr.toString() }.takeLast(2_000)

    /** history 帧 messages[i]/entryIds[i] 的平行条目（dialogue 只读，不撞 pi 视图）。 */
    private fun historyDialogue(frame: GatewayRuntimeEvent.Frame): List<Triple<String, String, String?>> =
        runCatching {
            val obj = Json.parseToJsonElement(frame.rawJson).jsonObject
            val messages = obj.getValue("messages").let { it as kotlinx.serialization.json.JsonArray }
            val entryIds = obj.getValue("entryIds").let { it as kotlinx.serialization.json.JsonArray }
            messages.zip(entryIds).map { (message, entryId) ->
                val m = message.jsonObject
                val role = m.getValue("role").jsonPrimitive.content
                val text = (m["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: (m["content"] as? kotlinx.serialization.json.JsonArray)
                        .orEmpty()
                        .filterIsInstance<JsonObject>()
                        .filter { it["type"]?.jsonPrimitive?.content == "text" }
                        .joinToString("") { it.getValue("text").jsonPrimitive.content }
                Triple(role, text, (entryId as? JsonPrimitive)?.takeIf { it.isString }?.content)
            }
        }.getOrDefault(emptyList())

    // -- 附加场景：起站 helper 与共享断言 ---------------------------------------

    private class FixtureProcess(val process: Process, val info: FixtureInfo, val stderr: StringBuilder)

    /** 启动 fixture 网关并等 READY 行；不建连接。 */
    private suspend fun startFixtureProcess(): FixtureProcess {
        val dir = gatewayDir()
        assumeTrue(
            "跳过：未找到 pi-mobile-gateway 仓库或本机无 node（可设 PI_GATEWAY_DIR 指向网关仓库）",
            dir != null
        )
        val process = ProcessBuilder("node", "scripts/dev-fixture-gateway.ts")
            .apply { environment().remove("PI_CODING_AGENT_DIR") }
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val ready = AtomicReference<FixtureInfo?>()
        val stderr = StringBuilder()
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("FIXTURE_READY ")) {
                    runCatching { ready.set(parseFixture(line)) }
                } else {
                    synchronized(stderr) { stderr.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; start() }
        Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderr) { stderr.appendLine(line) }
            }
        }.apply { isDaemon = true; start() }
        withTimeout(20_000) { while (ready.get() == null) delay(25) }
        return FixtureProcess(process, ready.get()!!, stderr)
    }

    private fun buildRuntime(
        transport: com.finecat.pimobile.shared.platform.GatewayTransport = OkHttpGatewayTransport(),
        cache: GatewayAttachmentCache = NoopAttachmentCache()
    ): GatewayRuntime = GatewayRuntime(
        transport,
        InMemoryPreferences(),
        InMemoryCredentials(),
        cache,
        AvailableNetwork(),
        SystemClock(),
        scope,
        30_000,
        60_000,
        // 一致性测试只有 1 个候选地址（本地 fixture），不存在“这个地址黑洇所以换下一个”的场景；
        // 看门狗预算放宽到 25s，只为半开连接兜底，避免 node/fixture 冷启动抖出假失败。
        endpointProbeTimeoutMilliseconds = 25_000,
        finalHandshakeTimeoutMilliseconds = 25_000
    )

    /** 与 AndroidSharedStateHolder 相同的投影接线：全部帧喂投影，history 请求
     * 由投影效果驱动（sent 帧补拉也在其中）。 */
    private fun connectProjection(
        runtime: GatewayRuntime,
        frames: CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>
    ): AndroidGatewayProjection {
        val projection = AndroidGatewayProjection { sessionId, beforeEntryId, resetLeaf ->
            scope.launch { runtime.requestHistory(sessionId, beforeEntryId, 50, resetLeaf) }
        }
        scope.launch {
            runtime.events.collect { event ->
                if (event is GatewayRuntimeEvent.Frame) {
                    frames += event
                    projection.acceptFrame(event.rawJson, event.frame, event.correlatedSessionId, event.normalizedEvents)
                }
            }
        }
        return projection
    }

    private fun assertNoErrorFrames(frames: List<GatewayRuntimeEvent.Frame>, runtime: GatewayRuntime) {
        val errors = frames.filter { it.frame.kind == "error" }
        assertEquals(
            "收到错误帧：${errors.map { it.frame.code to it.frame.message }}",
            emptyList<GatewayRuntimeEvent.Frame>(),
            errors
        )
        assertEquals(null, runtime.state.value.lastError)
    }

    private fun dumpDiagnostics(
        frames: List<GatewayRuntimeEvent.Frame>,
        rejections: List<GatewayRuntimeEvent.RequestRejected> = emptyList(),
        stderr: StringBuilder
    ) {
        val diag = buildString {
            appendLine("=== 帧轨迹 ===")
            frames.forEach { f ->
                appendLine(
                    "  kind=${f.frame.kind} corr=${f.correlatedSessionId} sid=${f.frame.sessionId} code=${f.frame.code}"
                )
            }
            if (rejections.isNotEmpty()) {
                appendLine("=== 请求被拒 ===")
                rejections.forEach { r ->
                    appendLine("  type=${r.requestType} reason=${r.reason} sid=${r.targetSessionId}")
                }
            }
            appendLine("=== 网关 stderr 尾部 ===")
            appendLine(stderrSnapshot(stderr))
        }
        runCatching { File("/tmp/conformance-diag.txt").writeText(diag) }
        println(diag)
    }

    /** history 帧 messages[i]/entryIds[i] 中 role==user 的条目（content 保留原始 JSON）。 */
    private data class HistoryUserEntry(val entryId: String, val content: JsonArray)

    private fun historyUserEntries(frame: GatewayRuntimeEvent.Frame): List<HistoryUserEntry> =
        runCatching {
            val obj = Json.parseToJsonElement(frame.rawJson).jsonObject
            val messages = obj.getValue("messages") as? JsonArray ?: return emptyList()
            val entryIds = obj.getValue("entryIds") as? JsonArray ?: return emptyList()
            messages.zip(entryIds).mapNotNull { (message, entryId) ->
                val m = message.jsonObject
                if (m.getValue("role").jsonPrimitive.content != "user") return@mapNotNull null
                val content = m["content"] as? JsonArray ?: return@mapNotNull null
                val id = (entryId as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                HistoryUserEntry(id, content)
            }
        }.getOrDefault(emptyList())

    private fun contentBlocks(content: JsonArray, type: String): List<JsonObject> =
        content.filterIsInstance<JsonObject>().filter { it["type"]?.jsonPrimitive?.content == type }

    /** 把配对文本里的 pairingCode 换成错误值（信封与编码保持合法）。 */
    private fun corruptPairingCode(pairingText: String): String {
        val decoded = String(java.util.Base64.getUrlDecoder().decode(pairingText), Charsets.UTF_8)
        val obj = Json.parseToJsonElement(decoded).jsonObject.toMutableMap()
        obj["pairingCode"] = JsonPrimitive("bogus-pairing-code")
        val encoded = Json.encodeToString(JsonObject.serializer(), JsonObject(obj))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.toByteArray())
    }

    @Test
    fun dequeueUsesProductionWireWithExactOperationId_andDoesNotCreateOrSendMessages() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            assertTrue("dequeue" in runtime.state.value.capabilities)
            val sessionId = fixture.info.fixtureSessionId
            for (operation in listOf("recall-1", "recall-2")) {
                assertTrue(runtime.sendRequest(GatewayRequests.dequeue(sessionId, operation)))
                val ack = awaitFrame(frames) { it.frame.kind == "dequeued" && it.frame.queueRequestId == operation }.frame
                assertEquals(sessionId, ack.sessionId)
                assertEquals(emptyList<String>(), ack.queued)
                assertEquals(false, ack.isStreaming)
            }
            assertTrue(frames.none { it.frame.kind == "sent" || it.frame.kind == "aborted" })
            assertNoErrorFrames(frames, runtime)
            runtime.disconnect()
        } finally {
            fixture.process.destroy()
        }
    }

    /**
     * 树页动作（PROTOCOL §4.26/§4.27）跑真实隔离网关 + 真实 Pi SessionManager：
     * 复制取整条 entry 全文（不是 preview），标签写只追加 LabelEntry 并推进叶子，
     * 且标签在 tree-view-v1 投影里可见。错误码路径（entry-copy-empty / entry-not-found）
     * 由网关 tests/router.test.ts 覆盖：Runtime 把它们转成 RequestRejected，不进帧列表。
     */
    @Test
    fun treeEntryCopyAndLabelPutUseProductionWireAgainstIsolatedGateway() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            assertTrue("本轮网关必须声明树页动作能力", "tree-actions-v1" in runtime.state.value.capabilities)
            val sessionId = fixture.info.fixtureSessionId

            // 复制是只读的：未 subscribe 也能取（网关回落文件视图）。
            assertTrue(runtime.sendRequest(GatewayRequests.entryCopy(sessionId, "e2")))
            val copy = awaitFrame(frames) { it.frame.kind == "entry-copy" && it.frame.entryId == "e2" }.frame
            assertEquals("hi there", copy.text)
            assertEquals(null, copy.oversized)
            assertEquals("hi there".encodeToByteArray().size, copy.bytes)

            // 标签写要 live 句柄：subscribed 帧证明 openSession 已完成，再写不会撞 not-found。
            runtime.subscribe(sessionId)
            awaitFrame(frames) { it.frame.kind == "subscribed" && it.frame.sessionId == sessionId }
            assertTrue(runtime.sendRequest(GatewayRequests.labelPut(sessionId, "e1", "交付")))
            val label = awaitFrame(frames) { it.frame.kind == "label-put" && it.frame.entryId == "e1" }.frame
            assertEquals("交付", label.label)
            assertNotNull("应答必须带新 LabelEntry id", label.labelEntryId)
            assertEquals("Pi 语义：追加 LabelEntry 推进叶子", label.labelEntryId, label.leafId)

            // 标签在投影里可见：labeled-only 只留 e1；default 的 selectedId 不把隐藏的
            // LabelEntry 当当前行（显示与跳转目标不错位）。
            assertTrue(
                runtime.sendRequest(
                    GatewayRequests.tree(
                        sessionId,
                        com.finecat.pimobile.shared.protocol.GatewayTreeView(filter = "labeled-only"),
                    )
                )
            )
            val labeled = awaitFrame(frames) {
                it.frame.kind == "tree" && it.frame.view?.filter == "labeled-only"
            }.frame
            assertEquals(listOf("e1"), labeled.nodes.orEmpty().map { it.id })
            assertEquals("交付", labeled.nodes.orEmpty().single().label)
            assertTrue(
                runtime.sendRequest(
                    GatewayRequests.tree(
                        sessionId,
                        com.finecat.pimobile.shared.protocol.GatewayTreeView(filter = "default"),
                    )
                )
            )
            val defaultView = awaitFrame(frames) {
                it.frame.kind == "tree" && it.frame.view?.filter == "default"
            }.frame
            assertEquals(label.labelEntryId, defaultView.leafId)
            assertEquals("e2", defaultView.selectedId)
            assertTrue(defaultView.nodes.orEmpty().none { it.id == label.labelEntryId })

            // 清除仍追加独立LabelEntry；重拉不得继续显示旧标签，也不消费其他lane。
            assertTrue(runtime.sendRequest(GatewayRequests.labelPut(sessionId, "e1", null)))
            val cleared = awaitFrame(frames) {
                it.frame.kind == "label-put" && it.frame.entryId == "e1" &&
                    it.frame.label == null && it.frame.labelEntryId != null
            }.frame
            assertTrue(cleared.labelEntryId != label.labelEntryId)
            assertTrue(runtime.sendRequest(GatewayRequests.tree(sessionId,
                com.finecat.pimobile.shared.protocol.GatewayTreeView(filter = "labeled-only"))))
            val empty = awaitFrame(frames) {
                it.frame.kind == "tree" && it.frame.leafId == cleared.labelEntryId &&
                    it.frame.view?.filter == "labeled-only"
            }.frame
            assertTrue(empty.nodes.orEmpty().isEmpty())

            assertNoErrorFrames(frames, runtime)
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, stderr = fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    @Test
    fun abortMidTurnSettlesTurnWithoutErrorFrames() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }

            runtime.subscribe(null)
            assertTrue(runtime.sendMessage("讲个长故事", emptyList(), sessionId = null, cwd = fixture.info.fixtureCwd))
            val sent = awaitFrame(frames) { it.frame.kind == "sent" }
            val sessionId = sent.frame.sessionId ?: error("sent 帧未携带 sessionId")
            runtime.subscribe(sessionId)

            // 流进行中按下停止
            awaitFrame(frames) {
                it.frame.kind == "event" && it.normalizedEvents.any { it.event.type == "assistant/chunk" }
            }
            assertTrue(runtime.abort(sessionId))
            awaitFrame(frames) { it.frame.kind == "aborted" && it.frame.sessionId == sessionId }
            // 中止后回合仍要落定（真实 pi 的 agent_settled）
            awaitFrame(frames) {
                it.frame.kind == "event" && it.normalizedEvents.any { it.event.type == "turn/end" }
            }
            assertNoErrorFrames(frames, runtime)
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, stderr = fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    @Test
    fun stateFrameCarriesContextUsageAndModel() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }

            runtime.subscribe(fixture.info.fixtureSessionId)
            // state 在订阅加载完成前可合法返回文件态；先等 live 句柄就绪。
            awaitFrame(frames) {
                it.frame.kind == "subscribed" && it.frame.sessionId == fixture.info.fixtureSessionId
            }
            val stateStartIndex = frames.size
            assertTrue(runtime.requestState(fixture.info.fixtureSessionId))
            val state = awaitNewFrame(frames, stateStartIndex) {
                it.frame.kind == "state" && it.correlatedSessionId == fixture.info.fixtureSessionId
            }.second
            assertEquals(42.0, state.frame.contextUsage?.percent)
            assertEquals("fixture", state.frame.model?.provider)
            assertNoErrorFrames(frames, runtime)
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, stderr = fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    @Test
    fun historyPaginationWalksCursorWithoutErrorFrames() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }

            // fixture 会话有 2 条消息：limit=1 翻页直到 hasMore=false，收齐全部消息。
            // 注意游标页可能为空（前面只剩 model_change 等非消息条目），这是
            // 条目游标分页的正常形态。
            runtime.subscribe(fixture.info.fixtureSessionId)
            var cursor: String? = null
            var minIndex = 0
            var pages = 0
            var totalMessages = 0
            while (pages < 6) {
                assertTrue(runtime.sendRequest(GatewayRequests.history(fixture.info.fixtureSessionId, cursor, 1)))
                val (index, page) = awaitNewFrame(frames, minIndex) {
                    it.frame.kind == "history" && it.correlatedSessionId == fixture.info.fixtureSessionId
                }
                minIndex = index + 1
                pages++
                totalMessages += page.frame.messages?.size ?: 0
                if (page.frame.hasMore != true) break
                cursor = page.frame.oldestEntryId ?: error("hasMore 页缺少 oldestEntryId 游标")
            }
            assertEquals(2, totalMessages)
            assertTrue("limit=1 时至少要翻两页", pages >= 2)
            // 生产入口按能力使用整轮分页；同一轮的 user/assistant 不因旧 limit=1 被拆开。
            assertTrue("history-turns-v1" in runtime.state.value.capabilities)
            assertTrue(runtime.requestHistory(fixture.info.fixtureSessionId, limit = 1))
            val (_, turnPage) = awaitNewFrame(frames, minIndex) {
                it.frame.kind == "history" && it.correlatedSessionId == fixture.info.fixtureSessionId
            }
            assertEquals(2, turnPage.frame.messages?.size)
            assertEquals(false, turnPage.frame.hasMore)
            assertNoErrorFrames(frames, runtime)
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, stderr = fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    @Test
    fun reconnectWithStoredTokenResumesWithoutErrorFrames() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }

            // 断开 → 用存储的 token 重连（真机"杀掉 App 再打开"的等价物）
            runtime.disconnect()
            awaitState(runtime) { it.connection != GatewayConnectionState.CONNECTED }
            assertTrue(runtime.connectStoredIfPaired())
            awaitState(runtime, timeoutMs = 15_000) { it.connection == GatewayConnectionState.CONNECTED }
            awaitFrame(frames, timeoutMs = 15_000) { it.frame.kind == "hello" }

            runtime.subscribe(fixture.info.fixtureSessionId)
            runtime.requestHistory(fixture.info.fixtureSessionId)
            awaitFrame(frames, timeoutMs = 15_000) {
                it.frame.kind == "history" && it.correlatedSessionId == fixture.info.fixtureSessionId
            }
            assertNoErrorFrames(frames, runtime)
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, stderr = fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    @Test
    fun wrongPairingCodeFailsClosedWithoutConnecting() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            scope.launch {
                runtime.events.collect { event ->
                    if (event is GatewayRuntimeEvent.Frame) frames += event
                }
            }
            runtime.applicationDidBecomeActive()
            runtime.pair(corruptPairingCode(fixture.info.pairingText))
            awaitState(runtime, timeoutMs = 15_000) {
                it.connection == GatewayConnectionState.FAILED && it.lastError != null
            }
            assertTrue("错误配对码不得换来 hello 帧", frames.none { it.frame.kind == "hello" })
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, stderr = fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    @Test
    fun workspacePickerFlowCreatesCwdAndStartsSession() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val rejections = CopyOnWriteArrayList<GatewayRuntimeEvent.RequestRejected>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            scope.launch {
                runtime.events.collect { event ->
                    when (event) {
                        is GatewayRuntimeEvent.Frame -> frames += event
                        is GatewayRuntimeEvent.RequestRejected -> rejections += event
                        else -> Unit
                    }
                }
            }
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            try {
                awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }
            } catch (t: Throwable) {
                // 连接等待失败后的一次性白名单诊断：只打状态字段，不打异常正文与敏感信息
                runCatching {
                    val s = runtime.state.value
                    println(
                        "stage=workspaceConnectWaitFailed nanoTime=${System.nanoTime()} " +
                            "connection=${s.connection.name} reconnectAttempt=${s.reconnectAttempt} " +
                            "networkAvailable=${s.networkAvailable} hasLastError=${s.lastError != null}"
                    )
                }
                throw t
            }
            awaitFrame(frames) { it.frame.kind == "hello" }

            // 1) 目录浏览：home 必须非空、crumbs 至少含根与当前目录
            assertTrue(runtime.sendRequest(GatewayRequests.directories(null)))
            val (homeIndex, homeFrame) = awaitNewFrame(frames, 0) { it.frame.kind == "directories" }
            val home = homeFrame.frame.home ?: error("directories 帧缺少 home")
            val crumbs = homeFrame.frame.crumbs.orEmpty()
            assertTrue("crumbs 至少含根与当前目录", crumbs.size >= 2)

            // 2) 新建文件夹 → 工作区创建 → 以它为 cwd 发消息
            val folderName = "工作区-${System.currentTimeMillis()}"
            assertTrue(runtime.sendRequest(GatewayRequests.createDirectory(home, folderName)))
            val (_, createdFrame) = awaitNewFrame(frames, homeIndex + 1) {
                it.frame.kind == "directory-create"
            }
            val folderPath = createdFrame.frame.path ?: error("directory-create 帧缺少 path")
            assertTrue("新建目录应在 home 之下", folderPath.startsWith("$home/"))

            // 3) 用新建目录作为新会话的 cwd 发消息（完整闭环）
            assertTrue(runtime.sendMessage("开工", emptyList(), sessionId = null, cwd = folderPath))
            val sent = awaitFrame(frames) { it.frame.kind == "sent" }
            assertTrue(sent.frame.sessionId?.isNotBlank() == true)

            assertNoErrorFrames(frames, runtime)
            assertTrue("有请求被拒绝：${rejections.map { it.requestType + ":" + it.reason }}", rejections.isEmpty())
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, rejections, fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    /** 同文本连发两次：实时身份（message_end 帧顶层 entryId，PROTOCOL §4.5）必须两次都在、
     *  且互不相同；history.entryIds 平行同源地包含这两个 id 且各对应一条同文 user。
     *  断言禁止内容指纹推断：同文消息只能靠网关精确匹配条目 id 区分。 */
    @Test
    fun repeatedIdenticalPromptsGetDistinctEntryIdsMatchingHistory() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val fixture = startFixtureProcess()
        try {
            val runtime = buildRuntime()
            connectProjection(runtime, frames)
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }

            val sessionId = fixture.info.fixtureSessionId
            runtime.subscribe(sessionId)

            fun isMessageEnd(frame: GatewayRuntimeEvent.Frame, role: String): Boolean {
                val event = runCatching {
                    Json.parseToJsonElement(frame.rawJson).jsonObject["event"] as? JsonObject
                }.getOrNull()
                return frame.frame.kind == "event" &&
                    event?.get("type")?.jsonPrimitive?.content == "message_end" &&
                    (event?.get("message") as? JsonObject)?.get("role")?.jsonPrimitive?.content == role
            }

            // 依次发送两个同文本 user，各自从“发送前”的帧下标起等本轮 user
            // message_end + assistant message_end + turn/end，防命中旧帧。
            val prompt = "同一条消息"
            val userIds = mutableListOf<String>()
            val assistantIds = mutableListOf<String>()
            var searchStart = frames.size
            repeat(2) {
                assertTrue(runtime.sendMessage(prompt, emptyList(), sessionId = sessionId))
                val (userIdx, userEnd) = awaitNewFrame(frames, searchStart) {
                    isMessageEnd(it, "user")
                }
                val userId = userEnd.frame.entryId
                assertNotNull("user message_end 缺帧顶层 entryId（§4.5 实时身份）", userId)
                assertTrue(userId!!.isNotBlank())
                userIds += userId
                val (assistantIdx, assistantEnd) = awaitNewFrame(frames, userIdx + 1) {
                    isMessageEnd(it, "assistant")
                }
                val assistantId = assistantEnd.frame.entryId
                assertNotNull("assistant message_end 缺帧顶层 entryId", assistantId)
                assertTrue(assistantId!!.isNotBlank())
                assistantIds += assistantId
                awaitNewFrame(frames, assistantIdx + 1) {
                    it.frame.kind == "event" && it.normalizedEvents.any { it.event.type == "turn/end" }
                }
                searchStart = frames.size
            }
            assertNotEquals("同文本两次发送的 user entryId 不得相同（禁止指纹推断）", userIds[0], userIds[1])
            assertNotEquals("assistant entryId 同样要按条目区分", assistantIds[0], assistantIds[1])

            // history 平行同源：entryIds 包含两个实时 id，且对应两条同文 user
            assertTrue(runtime.requestHistory(sessionId))
            val (_, history) = awaitNewFrame(frames, searchStart) {
                it.frame.kind == "history" && it.correlatedSessionId == sessionId
            }
            val dialogue = historyDialogue(history)
            assertTrue("history 页应收齐（entryIds/messages 非空）", dialogue.isNotEmpty())
            for (userId in userIds) {
                val row = dialogue.firstOrNull { it.third == userId }
                assertNotNull("history.entryIds 缺实时 id=$userId", row)
                assertEquals("条目 $userId 的角色", "user", row!!.first)
                assertEquals("条目 $userId 的文本应与实时 prompt 同文", prompt, row.second)
            }
            assertNoErrorFrames(frames, runtime)
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, stderr = fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }

    // -- 附件按需取回（PROTOCOL §4.22） ------------------------------------------

    /** 最小合法 1×1 PNG（69 字节）。单一来源：字节一律从该 base64 解码得到；
     * 断言只用字段与字节，不引入图片解码库、不放真图资源。 */
    private val FIXTURE_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC"

    /** >512 KiB 超长文本的前缀/尾部标记：history 单消息预算（§4.6）必剥图并截文；
     * 尾部标记用于断言“截断后不存活”，不依赖截断长度细节。 */
    private val ATTACHMENT_TEXT_PREFIX = "attachment-fetch-case"
    private val ATTACHMENT_TEXT_TAIL = "TAIL-OUT-OF-BUDGET"

    /**
     * 附件按需取回闭环（§4.22）：
     *
     * 1. 生产 sendMessage 发送 >512 KiB 文本 + 一张小 PNG（新会话）；
     * 2. turn/end 后拉 history：单消息 512 KiB 预算把 image 块换成
     *    `dataOmitted:true` 占位（无 data 键），超长文本截断；
     * 3. 错误路径：fetchAttachment(bogus entryId) → 网关 error 帧（requestType
     *    +三元回显）被 runtime 消费为 null 终态，lane 释放；
     * 4. happy path：fetchAttachment(真实 entryId) → bytes 与原 PNG 一致、
     *    会话级缓存写入、attachment 帧不进 UI 事件流；
     * 5. 同 cacheAttachmentId 第二次取：缓存直接命中，不发请求。
     *
     * 全程走生产 fetchAttachment（缓存/能力门控/严格三元关联），不用裸
     * sendRequest 替代产品功能验收。fixture 忽略/透传 options.images 由网关
     * 侧保障（本仓 fixture 不改），imageIndex 语义=content 中 image 块序号。 */
    @Test
    fun attachmentFetchReturnsOriginalBytesAfterHistoryStripsData() = runBlocking {
        val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
        val cancellations = CopyOnWriteArrayList<GatewayRuntimeEvent.RequestCancelled>()
        val rejections = CopyOnWriteArrayList<GatewayRuntimeEvent.RequestRejected>()
        val fixture = startFixtureProcess()
        try {
            val transport = CountingTransport()
            val cache = RecordingAttachmentCache()
            val runtime = buildRuntime(transport, cache)
            // events 是 Channel 单播冷流（BoundedGatewayRuntimeEventQueue.flow）：
            // 只能挂一个 collector，同一路径收集帧与请求终态，避免分流丢事件。
            scope.launch {
                runtime.events.collect { event ->
                    when (event) {
                        is GatewayRuntimeEvent.Frame -> frames += event
                        is GatewayRuntimeEvent.RequestCancelled -> cancellations += event
                        is GatewayRuntimeEvent.RequestRejected -> rejections += event
                        else -> Unit
                    }
                }
            }
            runtime.applicationDidBecomeActive()
            runtime.pair(fixture.info.pairingText)
            awaitState(runtime) { it.connection == GatewayConnectionState.CONNECTED }

            // hello.capabilities 必须宣告 attachment：缺失则后续取图无协议依据
            val hello = awaitFrame(frames) { it.frame.kind == "hello" }
            assertTrue(
                "hello.capabilities 缺 attachment：${hello.frame.capabilities}",
                "attachment" in hello.frame.capabilities.orEmpty()
            )

            // 1) 发送 >512KiB 文本 + 一张小 PNG（新会话，生产 sendMessage）
            val pngBytes = java.util.Base64.getDecoder().decode(FIXTURE_PNG_BASE64)
            val bigText = ATTACHMENT_TEXT_PREFIX + "x".repeat(600_000) + ":$ATTACHMENT_TEXT_TAIL"
            val turnEndSearchStart = frames.size
            runtime.subscribe(null)
            assertTrue(
                runtime.sendMessage(
                    bigText,
                    listOf(GatewayOutgoingImage("image/png", FIXTURE_PNG_BASE64, attachmentId = "att-fetch-fixture")),
                    sessionId = null,
                    cwd = fixture.info.fixtureCwd
                )
            )
            val sent = awaitFrame(frames) { it.frame.kind == "sent" }
            val sessionId = sent.frame.sessionId ?: error("sent 帧未携带 sessionId")
            runtime.subscribe(sessionId)

            // 2) 等回合落定再显式拉 history：落定后的消息必然都已 append
            awaitNewFrame(frames, turnEndSearchStart) {
                it.frame.kind == "event" && it.normalizedEvents.any { it.event.type == "turn/end" }
            }
            val historySearchStart = frames.size
            assertTrue(runtime.requestHistory(sessionId))
            val (historyIndex, historyFrame) = awaitNewFrame(frames, historySearchStart) {
                it.frame.kind == "history" && it.correlatedSessionId == sessionId
            }

            // 新会话恰好一条 user 条目；断言剥图与截文（§4.6 单消息 512 KiB 预算）。
            // 不断言列表内容本身（含 100k 截断文本），失败消息只报条目数。
            val entries = historyUserEntries(historyFrame)
            assertTrue("新会话 history 应恰好一条 user 条目，实际 ${entries.size} 条", entries.size == 1)
            val entry = entries.first()
            assertTrue("user 条目 entryId 非空（§4.5 实时身份）", entry.entryId.isNotBlank())
            val imageBlocks = contentBlocks(entry.content, "image")
            assertEquals("user content 应恰好含 1 个 image 块", 1, imageBlocks.size)
            val image = imageBlocks.single()
            assertTrue(
                "image 块应带 dataOmitted=true 占位：${image.keys}",
                (image["dataOmitted"] as? JsonPrimitive)?.content == "true"
            )
            assertTrue("剥图后不得残留 data 键（§4.6）", image["data"] == null)
            assertEquals(
                "dataBytes 应记录被剥 base64 的字符数",
                FIXTURE_PNG_BASE64.length.toLong(),
                (image["dataBytes"] as? JsonPrimitive)?.content?.toLongOrNull()
            )
            val historyText = contentBlocks(entry.content, "text")
                .joinToString("") { it.getValue("text").jsonPrimitive.content }
            assertTrue("user 文本开头应保留", historyText.startsWith(ATTACHMENT_TEXT_PREFIX))
            assertTrue("超长文本应有截断尾标（§4.6）", "[已截断]" in historyText)
            assertTrue("尾部标记不得存活", ATTACHMENT_TEXT_TAIL !in historyText)

            // 3) 错误路径：不存在的 entryId → error 帧（requestType=attachment +
            //    完整三元回显）被 runtime 严格三元消费，等待者收 null 终态。
            val attachmentRequestsBeforeProbe = transport.sentTypes.count { it == "attachment" }
            assertNull(
                "bogus entryId 应返回 null（错误终态）",
                runtime.fetchAttachment(sessionId, "bogus-entry-id", 0, "att-fetch-bogus")
            )
            assertEquals(
                "错误探测应恰好发出一次取图请求",
                attachmentRequestsBeforeProbe + 1,
                transport.sentTypes.count { it == "attachment" }
            )
            awaitCondition {
                // error 帧本体不进 Frame 流：runtime 以 RequestCancelled 小元数据收尾。
                cancellations.any { it.requestType == "attachment" && it.targetSessionId == sessionId }
            }

            // 4) 使用生产历史投影生成的图片引用，覆盖 wire → conversation → fetch。
            // 此处验证字节与缓存，Bitmap 解码和 Compose 可见性仍需设备验收。
            val projection = AndroidGatewayProjection()
            val attachment = try {
                projection.selectSession(sessionId)
                projection.acceptFrame(
                    historyFrame.rawJson, historyFrame.frame,
                    historyFrame.correlatedSessionId, historyFrame.normalizedEvents
                )
                projection.snapshot().conversation.flatMap { it.images }.single()
            } finally {
                projection.close()
            }
            assertEquals(entry.entryId, attachment.entryId)
            assertEquals(0, attachment.imageIndex)
            assertNull(attachment.dataBase64)
            val fetched = withTimeout(15_000) {
                runtime.fetchAttachment(
                    sessionId, requireNotNull(attachment.entryId),
                    requireNotNull(attachment.imageIndex), attachment.attachmentId
                )
                    ?: error("fetchAttachment 返回 null：取图失败")
            }
            assertArrayEquals("解码字节必须与原 PNG 一致", pngBytes, fetched)
            // 成功先写会话级缓存；attachment 帧本体不进 UI 事件流。
            assertTrue(
                "会话级缓存应写入取回结果",
                cache.values[com.finecat.pimobile.shared.gateway.gatewayAttachmentCacheKey(sessionId, attachment.attachmentId)] != null
            )
            assertTrue(
                "attachment 帧不得进入 UI 事件流",
                frames.none { it.frame.kind == "attachment" }
            )

            // 5) 同 cacheAttachmentId 第二次取：缓存直接命中，不再发请求。
            val attachmentRequestsAfterFirst = transport.sentTypes.count { it == "attachment" }
            val cachedBytes = runtime.fetchAttachment(
                sessionId, requireNotNull(attachment.entryId),
                requireNotNull(attachment.imageIndex), attachment.attachmentId
            )
            assertArrayEquals("缓存命中应返回同一份字节", pngBytes, cachedBytes)
            assertEquals(
                "第二次取图不应发请求（缓存命中）",
                attachmentRequestsAfterFirst,
                transport.sentTypes.count { it == "attachment" }
            )

            assertTrue(
                "不得产生同步拒绝：${rejections.map { it.requestType + ":" + it.reason }}",
                rejections.isEmpty()
            )
            assertEquals(null, runtime.state.value.lastError)
            runtime.disconnect()
        } catch (t: Throwable) {
            dumpDiagnostics(frames, rejections, fixture.stderr)
            throw t
        } finally {
            fixture.process.destroy()
        }
    }
}
