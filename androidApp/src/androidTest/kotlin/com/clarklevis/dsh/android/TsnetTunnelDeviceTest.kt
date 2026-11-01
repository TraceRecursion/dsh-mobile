package com.clarklevis.dsh.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.platform.OkHttpGatewayTransport
import com.clarklevis.dsh.android.platform.TsnetTunnel
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tsnetgo.Tsnetgo
import java.net.NetworkInterface
import android.util.Log

/**
 * 端到端验证：app 进程内的 tailscale 节点能不能把 WebSocket 握手送到网关监听器。
 *
 * 断言的不是"连上会话"，而是"到达网关并得到它对未认证握手的答复"（401/400/403）。
 * httpStatus 为 null 才说明隧道没通——这是这个 spike 真正要回答的问题。
 *
 * 运行：
 *   ./gradlew :androidApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.clarklevis.dsh.android.TsnetTunnelDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.tskey=$(cat ~/.config/dsh-tsnet/authkey)
 * 可选：tshost / tsport（默认 100.107.181.66:3081）。
 */
class TsnetTunnelDeviceTest {
    private val startedAt = System.currentTimeMillis()

    /**
     * 阶段打点走 instrumentation 协议：`am instrument -w` 会实时打印成
     * `INSTRUMENTATION_STATUS:` 行。真机上日志会被厂商丢掉、进程不退出时结果也拿不到，
     * 但这条通道两条都不受影响——用它才能看清"到底停在哪一步"。
     */
    private fun mark(phase: String) {
        val bundle = android.os.Bundle()
        bundle.putString("phase", phase)
        bundle.putString("elapsedMs", (System.currentTimeMillis() - startedAt).toString())
        InstrumentationRegistry.getInstrumentation().sendStatus(-1, bundle)
    }
    @Test
    fun embeddedNodeReachesGatewayWebSocketListener() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val authKey = args.getString("tskey").orEmpty()
        assertTrue("需要 -e tskey <可重复使用的 auth key>", authKey.isNotBlank())
        val host = args.getString("tshost") ?: "100.107.181.66"
        val port = (args.getString("tsport") ?: "3081").toInt()

        val context: Context = ApplicationProvider.getApplicationContext()
        val tunnel = TsnetTunnel(context)

        val interfaces = interfacesFromJava()
        mark("interfaces-enumerated len=${interfaces.length}")
        // 二分定位用：真机上连一次都没跑通过，就逐段早退，看卡在哪一步。
        when (args.getString("tsstop")) {
            "interfaces" -> return@runBlocking
            "getter" -> {
                Tsnetgo.setInterfacesJSON(interfaces)
                return@runBlocking
            }
        }
        // 变体 C：接口列表由 Java 侧枚举后经 JSON 注册（必须在 bringUp 之前）。
        if (args.getString("tsgetter") == "c") {
            assertTrue("Java 侧没枚举到任何接口：$interfaces", interfaces.startsWith("[{"))
            Tsnetgo.setInterfacesJSON(interfaces)
        }
        mark("getter-registered")

        mark("bring-up-start")
        // 注意：真机上不要用无头 instrumentation 测这条链路——HyperOS 会在进程进入后台
        // 约 4 秒后 freezeUid（实测 reason=new process / tobg），任何超时都不会触发；
        // 想在前台条件外测通，就把它放回模拟器（无厂商冻结，已验证可跑到结论）。
        // 看门狗：从另一个协程读节点自己的日志。它能返回就说明主线程不是卡在锁或 JNI 上，
        // 顺带把 tsnet 停在哪一行带回来；它也不返回，就是同一条路被吊住的证据。
        val watchdog = launch(Dispatchers.IO) {
            delay(40_000L)
            mark("watchdog-logs ${tunnel.logsJson().take(400)}")
        }
        val bringUp = tunnel.bringUp(authKey, timeoutSeconds = 45L, stage = ::mark)
        watchdog.cancel()
        mark("bring-up-done ok=${bringUp.contains("\"ok\":true")} bytes=${bringUp.length}")
        assertTrue(
            "节点没有进入 Running：bringUp=${bringUp.take(300)}｜接口快照=${interfaces.take(900)}｜" +
                "节点日志=${tunnel.logsJson().take(1400)}",
            bringUp.contains("\"ok\":true")
        )

        // Running 之后路由未必就绪（实测：peers 会从 0 变成 N）。给自己一个明确上限，
        // 而不是把等待推给外层超时——那只会得到一次挂死的运行。
        var peers = 0
        withTimeoutOrNull(45_000L) {
            while (peers == 0) {
                delay(2_000L)
                mark("status-attempt")
                val status = tunnel.statusJson()
                mark("status-returned len=${status.length} stage=${Regex("\"stage\":\"([^\"]*)\"").find(status)?.groupValues?.getOrNull(1) ?: "-"} state=${Regex("\"state\":\"([^\"]*)\"").find(status)?.groupValues?.getOrNull(1) ?: "-"}")
                peers = Regex("\"peers\":(\\d+)").find(status)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }
        }

        // G1：绕开 OkHttp/SOCKS，直接问"节点自己的数据网通不通"。
        // 通 → 问题在我的传输实现；不通 → 问题在节点的 netcheck/端点发现。
        mark("peers-wait-ended peers=$peers")
        if (args.getString("tsprobe") == "1") {
            mark("probe-start")
            // probeHTTP 走节点自己的 netstack，本身没有超时：不通时会一直吊着。
            // 给它一个死线，否则整场测试变成挂死，什么结论都拿不到。
            val probe = withTimeoutOrNull(25_000L) {
                kotlinx.coroutines.Dispatchers.IO.let {
                    kotlinx.coroutines.withContext(it) { tunnel.probeHttp("http://$host:$port/ws/mobile", 300) }
                }
            } ?: "{\"ok\":false,\"error\":\"probe-timeout-25s\",\"peers\":$peers}"
            mark("probe-returned bytes=${probe.length}")
            val evidence = "probe=${probe.take(600)}｜最后状态=${tunnel.statusJson().take(400)}｜" +
                "节点日志=${tunnel.logsJson().take(1800)}｜接口快照=${interfaces.take(900)}"
            // 顺序要紧：先把证据（probe/状态/日志/接口）取完，再关节点。
            // 不关进程就不会退出，而 am instrument 要等进程退出才输出结果——
            // 上一轮"测试跑完了却没有任何输出"就是这么来的。
            // 现在 Close() 在 Go 侧有 10 秒死线，不会再吊住整场测试。
            mark("before-shutdown")
            tunnel.shutdown()
            mark("after-shutdown")
            assertTrue("经节点 netstack 探测网关（peers=$peers）：$evidence", probe.contains("\"ok\":true"))
            return@runBlocking
        }

        // 与生产路径同一个机制：客户端由"连接路径策略"提供，经内部节点的 SOCKS5。
        val transport = OkHttpGatewayTransport(clientProvider = {
            val proxy = tunnel.proxyInfo() ?: error("tsnet-proxy-unavailable")
            OkHttpGatewayTransport.proxiedClient(proxy.host, proxy.port, proxy.user, proxy.pass, callTimeoutSeconds = 30)
        })
        val states = CopyOnWriteArrayList<GatewayTransportState>()
        val watch: Job = launch(Dispatchers.Default) {
            transport.state.collect { states.add(it) }
        }
        withTimeoutOrNull(25_000L) {
            runCatching {
                transport.open(
                    GatewayConnectionSpec(
                        generation = 1L,
                        endpoint = "ws://$host:$port/ws/mobile",
                        deviceId = "tsnet-spike"
                    )
                )
            }
        }
        withTimeoutOrNull(45_000L) {
            while (states.none { it is GatewayTransportState.Open || it is GatewayTransportState.Failed }) {
                delay(500L)
            }
        }
        watch.cancel()
        runCatching { transport.close() }
        tunnel.shutdown()

        val terminal = states.lastOrNull { it is GatewayTransportState.Open || it is GatewayTransportState.Failed }
        val reached = when (terminal) {
            is GatewayTransportState.Open -> true
            is GatewayTransportState.Failed -> terminal.httpStatus in setOf(400, 401, 403)
            else -> false
        }
        assertTrue(
            "没有到达网关（终态=$terminal｜状态序列=${states.map { it::class.simpleName }}｜peers=$peers｜" +
                "最后状态=${tunnel.statusJson().take(400)}｜节点日志=${tunnel.logsJson().take(1600)}｜" +
                "接口快照=${interfaces.take(900)}）——httpStatus 为 null 表示连 TCP 都没通",
            reached
        )
    }

    /**
     * 走 Java/Bionic 允许的路径枚举接口——和官方 Android 客户端、社区实现同一形态。
     * 注意 flags 里 broadcast 是近似值：Java 没有 isBroadcast，按
     * up && 非点对点 && 非回环 给出（netmon 只用它做过滤提示）。
     */
    private fun interfacesFromJava(): String {
        val nics = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val entries = nics.map { nic ->
            val flags = buildList {
                if (nic.isUp) add("up")
                if (nic.isLoopback) add("loopback")
                if (nic.isPointToPoint) add("pointtopoint")
                if (nic.supportsMulticast()) add("multicast")
                if (nic.isUp && !nic.isPointToPoint && !nic.isLoopback) add("broadcast")
            }
            val addrs = nic.interfaceAddresses.mapNotNull { entry ->
                val host = entry.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                "\"$host/${entry.networkPrefixLength}\""
            }
            "{\"name\":\"${nic.name}\",\"index\":${nic.index},\"mtu\":${nic.mtu}," +
                "\"flags\":[${flags.joinToString(",") { "\"$it\"" }}]," +
                "\"addrs\":[${addrs.joinToString(",")}]}"
        }
        Log.i("TsnetSpike", "java-enumerated interface count=${entries.size}")
        return "[" + entries.joinToString(",") + "]"
    }
}
