package com.finecat.pimobile.android

import com.finecat.pimobile.android.platform.OkHttpGatewayTransport
import com.finecat.pimobile.android.ui.finecat.FineCatCompat
import com.finecat.pimobile.android.ui.finecat.FineCatProviderForm
import com.finecat.pimobile.android.ui.ProviderModelDraft
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayRequest
import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.gateway.GatewayRuntime
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import com.finecat.pimobile.shared.gateway.GatewayRuntimeState
import com.finecat.pimobile.shared.platform.GatewayAttachmentCache
import com.finecat.pimobile.shared.platform.GatewayClock
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import com.finecat.pimobile.shared.platform.GatewayNetworkMonitor
import com.finecat.pimobile.shared.platform.GatewayNetworkState
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.protocol.JsonValue
import java.io.File
import java.util.UUID
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * F12 兼容参数在**真实网关链路**上的合并/清除语义（`DEC-F09-COMPAT`）。
 *
 * 为什么必须是真实网关：手机侧只能看到 `providers` 帧，而那一帧里
 * **宿主私有键根本不存在**（`toWireModel` 用 `normalizeCompat` 过滤过）。所以
 * 「编辑一个字段不丢宿主配置」「`{}` 只清手机管理的键」这两条，光看手机回读是**证不出来**的
 * ——必须去 models.json 里看。
 *
 * 判据链是 **磁盘上的 models.json → 客户端真实表单解析 → 生产 providerPut 序列化 →
 * 真实网关 put/合并 → 独立 providers 回读 + 再次直读磁盘**，两侧都比。
 *
 * 隔离：网关进程用 `scripts/dev-fixture-gateway.ts`（生产 server/router/mgmt 代码 +
 * 脚本化假 pi 会话），它把 HOME 重定向到临时目录，设备注册表/会话目录也都在临时目录；
 * 不触 `~/.pi`、不调真实模型、不出回环。仓库缺失或无 node 时整类跳过。
 */
class ProviderCompatGatewayTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 环境探测（与 GatewayConformanceTest 同法） ----

    private fun gatewayDir(): File? {
        val candidates = listOfNotNull(
            System.getenv("PI_GATEWAY_DIR")?.let(::File),
            File("..").resolve("pi-mobile-gateway"),
            File("../..").resolve("pi-mobile-gateway"),
        )
        val nodeOk = runCatching { ProcessBuilder("node", "--version").start().waitFor() }.getOrDefault(-1) == 0
        val dir = candidates.firstOrNull {
            it.isDirectory && File(it, "scripts/dev-fixture-gateway.ts").isFile
        }
        return if (nodeOk) dir else null
    }

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
        override suspend fun saveToken(value: String) {
            token = value
        }

        override suspend fun deleteToken() {
            token = null
        }
    }

    private class NoopCache : GatewayAttachmentCache {
        override suspend fun read(attachmentId: String): ByteArray? = null
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean = true
        override suspend fun removeExpired() = Unit
    }

    private class AvailableNetwork : GatewayNetworkMonitor {
        override val state: StateFlow<GatewayNetworkState> =
            MutableStateFlow(GatewayNetworkState.AVAILABLE)
    }

    private class SystemClock : GatewayClock {
        override fun nowEpochMilliseconds(): Long = System.currentTimeMillis()
        override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
    }

    // ---- 磁盘上的宿主配置（fixture 基线） ----

    private companion object {
        const val PROVIDER = "f12-compat"
        const val MODEL = "m-1"

        /**
         * 落盘的 models.json。compat 里混了 3 个手机管理键与 2 个宿主私有键
         * （对象类型，手机永远不会在 wire 上看到它们）。
         */
        fun seedModelsJson(): String = """
            {
            	"providers": {
            		"$PROVIDER": {
            			"baseUrl": "https://relay.example/v1",
            			"apiKey": "sk-host-side",
            			"models": [
            				{
            					"id": "$MODEL",
            					"name": "M1",
            					"api": "openai-completions",
            					"input": ["text"],
            					"reasoning": true,
            					"contextWindow": 128000,
            					"maxTokens": 8192,
            					"compat": {
            						"supportsStore": true,
            						"supportsDeveloperRole": false,
            						"thinkingFormat": "deepseek",
            						"openRouterRouting": { "sort": "throughput" },
            						"chatTemplateArgs": { "thinking": true }
            					}
            				}
            			]
            		}
            	}
            }
        """.trimIndent()

        /** 宿主私有键：网关 editableKeys 之外的那两个，手机端永远看不见。 */
        val HOST_KEYS = listOf("chatTemplateArgs", "openRouterRouting").sorted()
    }

    private fun modelsPath(home: File) = File(File(home, ".pi/agent"), "models.json")

    private fun diskCompat(home: File): Map<String, String> {
        val root = Json.parseToJsonElement(modelsPath(home).readText()).jsonObject
        val model = root.getValue("providers").jsonObject.getValue(PROVIDER).jsonObject
            .getValue("models").jsonArray.single().jsonObject
        val compat = model["compat"]?.jsonObject ?: return emptyMap()
        // 宿主私有键是对象，按 JSON 原文记下来（只看键名与归一化后的原文）。
        return compat.entries.associate { (key, value) ->
            key to when {
                value is JsonObject -> value.toString()
                else -> value.jsonPrimitive.content
            }
        }
    }

    // ---- 用例 ----

    @Test
    fun compatThreeWriteFormsMatchGatewayMergeSemantics() = runBlocking {
        val dir = gatewayDir()
        assumeTrue(
            "跳过：未找到 pi-mobile-gateway 仓库或本机无 node（可设 PI_GATEWAY_DIR）",
            dir != null,
        )
        val process = ProcessBuilder("node", "scripts/dev-fixture-gateway.ts")
            // fixture 的 HOME 已隔离，不能让宿主的显式 agent 目录覆盖它。
            .apply { environment().remove("PI_CODING_AGENT_DIR") }
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val ready = AtomicReference<String?>(null)
        val home = AtomicReference<File?>(null)
        val logs = StringBuilder()
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("FIXTURE_READY ")) runCatching { ready.set(line) }
                else synchronized(logs) { logs.appendLine("stdout: $line") }
            }
        }.apply { isDaemon = true; start() }
        // fixture 的 log() 写的是 stderr 且带 `[fixture] ` 前缀（FIXTURE_READY 走 stdout）。
        Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                when {
                    // fixture 把 HOME 重定向到自己的临时目录；我们要往那儿写宿主配置。
                    line.startsWith("[fixture] HOME redirected to ") ->
                        home.set(File(line.removePrefix("[fixture] HOME redirected to ").trim()))
                    else -> synchronized(logs) { logs.appendLine("stderr: $line") }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            withTimeout(30_000) { while (ready.get() == null || home.get() == null) delay(25) }
            val fixtureHome = home.get()!!
            modelsPath(fixtureHome).apply { parentFile.mkdirs() }.writeText(seedModelsJson())

            val frames = CopyOnWriteArrayList<GatewayRuntimeEvent.Frame>()
            val runtime = GatewayRuntime(
                OkHttpGatewayTransport(),
                InMemoryPreferences(),
                InMemoryCredentials(),
                NoopCache(),
                AvailableNetwork(),
                SystemClock(),
                scope,
                30_000,
                60_000,
            )
            val collector = scope.launch {
                runtime.events.collect { event ->
                    if (event is GatewayRuntimeEvent.Frame) frames += event
                }
            }
            runtime.applicationDidBecomeActive()
            val pairing = Json.parseToJsonElement(ready.get()!!.removePrefix("FIXTURE_READY ")).jsonObject
            runtime.pair(pairing.getValue("pairingText").jsonPrimitive.content)
            withTimeout(20_000) {
                while (runtime.state.value.connection != GatewayConnectionState.CONNECTED) delay(25)
            }

            /** 独立 providers 回读：走一次真实读请求，不复用上一次的结果。 */
            suspend fun readProviders(): GatewayProvider {
                val from = frames.size
                assertTrue("providers 请求必须被接受", runtime.sendRequest(GatewayRequests.providers()))
                val frame = withTimeout(20_000) {
                    while (true) {
                        frames.drop(from).firstOrNull { it.frame.kind == "providers" }?.let { return@withTimeout it }
                        delay(25)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                return frame.frame.providers.orEmpty().firstOrNull { it.id == PROVIDER }
                    ?: error("providers 帧里没有 $PROVIDER")
            }

            /** 按客户端真实路径提交：表单草稿 → parseModels → 生产 providerPut 序列化。 */
            suspend fun submit(rows: List<ProviderModelDraft>, existing: GatewayProvider?) {
                val parsed = FineCatProviderForm.parseModels(rows, existing)
                assertNull("提交前不得有行内错误：${parsed.error}", parsed.error)
                val from = frames.size
                val request: GatewayRequest = GatewayRequests.providerPut(
                    id = PROVIDER,
                    baseUrl = existing?.baseUrl,
                    apiKey = null,
                    models = parsed.rows,
                    name = existing?.name,
                )
                assertTrue("provider-put 必须被接受", runtime.sendRequest(request))
                withTimeout(20_000) {
                    while (frames.drop(from).none { it.frame.kind == "provider-saved" }) delay(25)
                }
            }

            // ---- 0) 基线：wire 回显里只有手机管理的三个键，宿主私有键不可见 ----
            val baseline = readProviders()
            val baseModel = baseline.models.single()
            assertEquals("openai-completions", baseModel.api)
            assertEquals(
                "wire 回显只能有手机可编辑的键，宿主私有键不得下发到手机",
                "supportsDeveloperRole=false|supportsStore=true|thinkingFormat=deepseek",
                compatSorted(baseline),
            )

            // ---- 1) 只改显示名、不碰 compat：字段整个不下发 → 网关保留原配置 ----
            val rows = FineCatProviderForm.initialRows(baseline)
            submit(
                rows.map { it.copy(name = "M1 改名") },
                baseline,
            )
            var after = readProviders()
            assertEquals("M1 改名", after.models.single().name)
            assertEquals(
                "没动过 compat 就必须一个键都不变",
                "supportsDeveloperRole=false|supportsStore=true|thinkingFormat=deepseek",
                compatSorted(after),
            )
            assertEquals(
                "宿主私有键原样留在磁盘上",
                setOf("chatTemplateArgs", "openRouterRouting", "supportsDeveloperRole", "supportsStore", "thinkingFormat"),
                diskCompat(fixtureHome).keys,
            )

            // ---- 2) 删掉一个手机管理键：只清这一项，其余手机键与宿主键都不动 ----
            submit(
                rows.map {
                    it.copy(
                        name = "M1 改名",
                        compat = FineCatCompat.setValue(it.compat, "thinkingFormat", null),
                        compatTouched = true,
                    )
                },
                baseline,
            )
            after = readProviders()
            assertEquals(
                "省略某个手机管理键 = 清除该项覆盖，其余键不动",
                "supportsDeveloperRole=false|supportsStore=true",
                compatSorted(after),
            )
            assertEquals(
                listOf("chatTemplateArgs", "openRouterRouting", "supportsDeveloperRole", "supportsStore"),
                diskCompat(fixtureHome).keys.sorted(),
            )

            // ---- 3) 清空手机管理的兼容参数：下发 {} → 手机键全没，宿主键还在 ----
            submit(
                rows.map { it.copy(compat = FineCatCompat.cleared(), compatTouched = true) },
                baseline,
            )
            after = readProviders()
            // 清空后的回显有两种等价表示，取决于宿主私有键还在不在：
            //  - 宿主私有键被合并保留 → compat 键留着但内容为空 → wire 回显 `{}`；
            //  - 合并后整个 compat 键被网关删掉 → wire 回显 `null`（没有手机键，也没有宿主键）。
            // 两种都表示「一个手机管理的键都不剩」，这里按「键集合为空」判定。
            val afterClearCompat: Map<String, JsonValue> =
                after.models.single().compat?.objectValue ?: emptyMap()
            assertEquals("清空后不得再有任何手机管理的键", emptyMap<String, JsonValue>(), afterClearCompat)
            val diskAfterClear = diskCompat(fixtureHome)
            assertEquals(
                "清空只删手机管理的键，宿主私有键必须保留",
                HOST_KEYS.sorted(),
                diskAfterClear.keys.sorted(),
            )
            assertEquals(
                "宿主私有键的值一个字节都不能被手机改掉",
                "{\"sort\":\"throughput\"}",
                diskAfterClear["openRouterRouting"],
            )
            assertEquals(
                "另一项宿主私有键同样原样保留",
                "{\"thinking\":true}",
                diskAfterClear["chatTemplateArgs"],
            )
            // 模型的其他字段也不能因为只编辑 compat 而丢
            assertEquals(128000L, after.models.single().contextWindow)
            assertEquals(8192L, after.models.single().maxTokens)

            // ---- 4) 重新逐项设置（布尔 true/false + 枚举改值）：落盘即回显值 ----
            submit(
                rows.map {
                    it.copy(
                        compat = linkedMapOf(
                            "supportsStore" to "false",
                            "supportsDeveloperRole" to "true",
                            "thinkingFormat" to "zai",
                        ),
                        compatTouched = true,
                    )
                },
                baseline,
            )
            after = readProviders()
            assertEquals(
                "提交的具体值必须原样落盘并回显（含 false，不能被当成未设置）",
                "supportsDeveloperRole=true|supportsStore=false|thinkingFormat=zai",
                compatSorted(after),
            )
            assertEquals(HOST_KEYS.sorted(), diskCompat(fixtureHome).keys.filter { it in HOST_KEYS }.sorted())

            collector.cancel()
            runtime.disconnect()
        } finally {
            process.destroy()
        }
    }

    /** 回显 compat 的按键排序规范写法，供逐字比对。 */
    private fun compatSorted(provider: GatewayProvider): String {
        val objectValue = provider.models.single().compat?.objectValue ?: return ""
        return objectValue.entries
            .sortedBy { it.key }
            .joinToString("|") { (key, value) ->
                val rendered = when (value) {
                    is JsonValue.BooleanValue -> value.value.toString()
                    is JsonValue.StringValue -> value.value
                    else -> value.toString()
                }
                "$key=$rendered"
            }
    }
}
