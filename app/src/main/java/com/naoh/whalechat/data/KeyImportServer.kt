package com.naoh.whalechat.data

import android.util.Log
import com.naoh.whalechat.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** 导入流程的状态，给 UI 看。 */
enum class ImportStatus { WAITING, DONE, FAILED }

/**
 * 手机上提交上来的一屏设置。**字段就是表单上那些格子，一个不多一个不少。**
 *
 * 提交的语义是**整屏覆盖**（用户看到的是一张真值表，清空某一格 = 删掉那一项），
 * 所以这里没有任何「空串表示没填」的妥协 —— 空串在这里是有含义的输入。
 *
 * ## 1.982：加回通用接口那三格
 *
 * [asrUrl] / [asrKey] / [asrModel] 是「通用接口」（OpenAI 兼容 ASR）那一档的参数，
 * 1.0 时代就在这张表上，后来随整档一起被删掉，1.982 按用户要求加回来。
 *
 * ⚠️ **加字段 = 一条五处的联动链，少一处就是「存了但没生效」**：
 * [ImportedCredentials] 本体 → [KeyImportServer.FORM_FIELDS] 白名单
 * → `handlePost` 里那几行 `form[...]` → 表单 HTML 的输入格
 * → [SettingsStore.importFromPhone] 的 `copy(...)`。
 * 这五处漏掉任何一处，症状都是「手机上说保存成功、手表上那一格还是空的」。
 */
data class ImportedCredentials(
    val apiKey: String = "",
    val asrEngine: String = AsrEngine.SYSTEM.key,
    /** 通用接口的接口地址。默认值和设置里的兜底一致，见 `Settings.DEFAULT_ASR_URL` */
    val asrUrl: String = Settings.DEFAULT_ASR_URL,
    /** 通用接口的 Key。**默认留空** —— 本机服务不鉴权，空 = 不发 `Authorization` 头 */
    val asrKey: String = "",
    val asrModel: String = Settings.DEFAULT_ASR_MODEL,
    val xfyAppId: String = "",
    val xfyApiKey: String = "",
    val xfyApiSecret: String = "",
) {
    /**
     * 除识别引擎外，所有格都空着。引擎是个下拉框，永远有值，不参与「空不空」的判断。
     *
     * ⚠️ 通用接口那三格**不参与**这个判断，理由有两条，都别顺手加进来：
     *  1. [asrUrl] / [asrModel] 的默认值**非空**（预填硅基流动那套），
     *     把它们算进来，这个「整页都空」的闸门就**永远不会触发** ——
     *     而那正是防「手滑把凭证全清掉」的唯一一道闸；
     *  2. 就算按「用户实际填了什么」算，清空地址也不等于清掉凭证 ——
     *     它只是让通用接口那一档暂时不可用，不会丢任何东西。
     */
    val allFieldsBlank: Boolean
        get() = listOf(apiKey, xfyAppId, xfyApiKey, xfyApiSecret).all { it.isBlank() }

    /** 讯飞三样是否齐全。缺一不可 —— 少一样讯飞那边一律鉴权失败。 */
    val xfyunComplete: Boolean
        get() = xfyAppId.isNotBlank() && xfyApiKey.isNotBlank() && xfyApiSecret.isNotBlank()
}

/**
 * 网页那一侧要用的「角色能力」。做成接口而不是让 [KeyImportServer] 直接抓
 * [ChatEngine] 单例，是为了让「网页 + HTTP」这一层不知道角色是怎么存的、
 * 也不知道生成是怎么发的；顺带单测时能塞一个假的进去。
 *
 * 实现见 `ImportKeyScreen`（它本来就是把网页和引擎缝在一起的那一层）。
 */
interface PersonaBridge {
    fun list(): List<Persona>
    fun find(id: String): Persona?

    /** 整对象覆盖 + **同步落盘**；返回 null 表示成功，非 null 是人话错误。 */
    suspend fun save(persona: Persona): String?
}

/**
 * 手表端临时 HTTP 服务：手机浏览器扫二维码进来，**看和改**这块表上的东西。
 *
 * 设计取舍（贴合「简单 / 跨平台 / 不过度工程」）：
 *  - 不引第三方 server 库，直接用 `ServerSocket` 手搓一个单端点服务；
 *  - 手机端只是个普通浏览器网页（iOS/Android 都行），**不再养一个手机 App**；
 *  - 一次性 token 写在 URL 里，带超时强关；
 *  - 只在「手机扫码」那一页存活，凭证照旧只存这块手表。
 *
 * ## 两个入口，一套 token 校验
 *
 *  * **凭证**（`/$token`）：GET 出表单 / POST 收下并**关停服务**。
 *    它是一次性动作（改完就关，把暴露窗口压到最小），所以行为一个字都没改。
 *  * **角色**（`/$token/p…`）：角色管理是**多次交互** —— 改完一个字段要能接着改下一个，
 *    所以这一条路提交完**不关停**，响应是 303 跳回列表页，服务继续活着。
 *
 * token 校验复用同一份（两条路都以 `/$token` 开头，路由匹配不上就是 404），
 * 没有为角色另写一套门禁。
 *
 * ## 安全边界（写清楚，免得后来人误判）
 *
 * 这是**局域网明文 HTTP**，而且这个 URL 等于**整份凭证的读句柄** —— 谁拿到它、
 * 在它还活着的那段时间里打开，就能看到全部 Key。靠「64 位随机 token + 只在一页存活 +
 * 凭证提交即停」把暴露面压到极小。
 *
 * **超时从 2 分钟放宽到了 10 分钟**，这是为角色那一页让的路：手机上手填六块人设是苦力活，
 * 填到一半服务断了等于白填。代价是凭证的暴露窗口跟着变长 —— 所以不要在公共 Wi-Fi 下
 * 用这个入口，也不要把服务做成常驻。凭证那条路提交完照样立刻关停，这一步没变。
 */
class KeyImportServer(
    /** 取手表当前的值，用来预填表单。每次 GET 现取 —— 用户可能在别的页面刚改过。 */
    private val snapshot: () -> ImportedCredentials,
    private val onSubmit: (ImportedCredentials) -> Unit,
    /** 角色那一侧的能力，见 [PersonaBridge]。 */
    private val personas: PersonaBridge,
) : CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

    private val _status = MutableStateFlow(ImportStatus.WAITING)
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private val token = randomToken()
    private var stopped = false

    var url: String? = null
        private set

    /**
     * 所有路由的前缀。角色那几页的链接和表单 action 全部写成**绝对路径**
     * （`/$token/p`…），不用相对路径 —— 相对 URL 会相对于「当前路径去掉最后一段」
     * 解析，`/$token` 下面没有目录可退，写 `p` 会跳到 `/p` 上去。
     */
    private val base get() = "/$token"

    private val listPath get() = "$base/p"

    /** 起服务；拿不到网络地址、或端口绑不上，就返回 false。 */
    fun start(): Boolean {
        val ip = lanIp() ?: return false
        return try {
            // 必须显式绑 IPv4 通配：默认的 ServerSocket(0) 在部分内核上落到 ::（v6 栈），
            // 而 bindv6only 开着时 IPv4 连接（手机浏览器连手表 IP）会全被拒。
            val ss = ServerSocket(0, BACKLOG, InetAddress.getByName("0.0.0.0"))
            serverSocket = ss
            url = "http://$ip:${ss.localPort}$base"
            if (BuildConfig.DEBUG) Log.d("WhaleChatImport", "listening $url")
            launch { acceptLoop() }
            launch {
                delay(TIMEOUT_MS)
                if (!stopped) {
                    _status.value = ImportStatus.FAILED
                    stop()
                }
            }
            true
        } catch (e: Exception) {
            Log.w("WhaleChatImport", "start failed", e)
            false
        }
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        try {
            while (!ss.isClosed) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: continue
                // 每个连接单独一条协程：慢客户端（浏览器预连接、探测包）不会把
                // 后续正常的导入请求堵在 accept 循环里。
                launch { handle(socket) }
            }
        } catch (_: Exception) {
            // 关闭时 accept 会抛，忽略
        }
    }

    /**
     * 处理一个请求。
     *
     * 是 `suspend` 的：角色那页的「保存到手表」走 `PersonaBridge.save()`，
     * 它是**同步落盘**的挂起调用（写 prefs + 文件 IO）。运行它的仍然是
     * 每连接一条的 IO 协程，所以等一下不会堵住别人。
     * （1.987 之前还有一个更慢的理由 —— 「用 AI 生成」要调模型；那条链路已删。）
     */
    private suspend fun handle(sock: Socket) {
        try {
            sock.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            // 兼容个别浏览器/代理附加的 query 与尾斜杠
            val raw = parts[1]
            val route = raw.substringBefore("?").removeSuffix("/")
            val query = parseQuery(raw.substringAfter("?", ""))

            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.startsWith("Content-Length", ignoreCase = true)) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // 不给 Content-Length 设上限的话，局域网里谁发一个 Content-Length: 2000000000
            // 就能让下面那个 CharArray 直接 OOM。上限本身要够大 —— 六块人设加上好几轮
            // 示例对话，一个表单轻松超过 8KB，卡在原来那个数上手机上点保存直接 400。
            if (contentLength > MAX_BODY_BYTES) {
                respond(sock, "请求体过大", 400)
                return
            }

            val body = if (contentLength > 0) {
                // read 可能一次读不满，必须循环读够 Content-Length
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            if (method == "GET") {
                handleGet(sock, route, query)
                return
            }
            if (method == "POST") {
                handlePost(sock, route, body)
                return
            }
            respond(sock, "404 Not Found", 404)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d("WhaleChatImport", "handle failed: ${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    // ---------------------------------------------------------------- 凭证

    private fun handleGet(sock: Socket, route: String, query: Map<String, String>) {
        when {
            route == base -> respond(sock, formHtml(snapshot()))

            // `?saved=1` 是 POST 保存完 303 跳回来的，用于在列表上留一句「已保存」。
            // 用查询参数而不是直接渲染列表页：POST 的响应留住的话，
            // 用户在手机上往下拉一把就会撞上「要重新提交表单吗」。
            route == listPath -> respond(sock, personaListHtml(saved = query["saved"] == "1"))

            route == "$listPath/new" ->
                respond(sock, personaFormHtml(blankPersona()))

            // 角色 id 是 UUID（十六进制 + 连字符），不会有需要解码的字符，
            // 所以这里直接切下来用，不做 URL 解码。
            route.startsWith("$listPath/") -> {
                val id = route.removePrefix("$listPath/")
                val persona = personas.find(id)
                if (persona == null) {
                    respond(sock, personaGoneHtml())
                } else {
                    respond(sock, personaFormHtml(persona))
                }
            }

            else -> respond(sock, "404 Not Found", 404)
        }
    }

    private suspend fun handlePost(sock: Socket, route: String, body: String) {
        // 凭证那条路：行为与 2.0 第一版逐字一致（提交完关停服务）
        if (route == base) {
            val form = parseForm(body, FORM_FIELDS)
            val submitted = ImportedCredentials(
                apiKey = form[FIELD_API_KEY].orEmpty(),
                asrEngine = form[FIELD_ASR_ENGINE].orEmpty(),
                asrUrl = form[FIELD_ASR_URL].orEmpty(),
                asrKey = form[FIELD_ASR_KEY].orEmpty(),
                asrModel = form[FIELD_ASR_MODEL].orEmpty(),
                xfyAppId = form[FIELD_XFY_APP_ID].orEmpty(),
                xfyApiKey = form[FIELD_XFY_API_KEY].orEmpty(),
                xfyApiSecret = form[FIELD_XFY_API_SECRET].orEmpty(),
            )
            val before = snapshot()
            val effective = withAutoEngine(before, submitted)
            val engineSwitched = effective.asrEngine != submitted.asrEngine

            // 不能用的情况要在网页上指出**是哪一格**的问题，不然用户只能瞎猜。
            // 回显的是**用户刚提交的内容**（不是 snapshot）：改错一格不该让他把
            // 另外七格重新填一遍 —— 这一点在表单预填之后尤其要紧。
            val complaint = validate(effective)
            if (complaint != null) {
                respond(sock, formHtml(effective, error = complaint))
                return
            }
            // 先把成功页写回手机，再落盘/关停 —— 顺序反了浏览器会看到连接被掐断
            respond(sock, successHtml(effective, engineSwitched))
            runCatching { onSubmit(effective) }
            _status.value = ImportStatus.DONE
            stop()
            return
        }

        // ---- 角色 ----

        // ⚠️⚠️ 1.987：**「用 AI 生成一份」整条链路删除**（用户原话：
        // 「手机端『用ai生成人设』功能直接删除，不管是新建还是管理已有」）。
        //
        // 原来这里有两个生成入口，全删：
        //  * `route == "$listPath/new"` 的 POST 里 `act == ACT_GENERATE` 的分支；
        //  * `route.startsWith("$listPath/")` 的 POST 里 `isGenerate`（/gen 路由）的分支；
        // 配套删除的还有：表单 HTML 上那颗按钮 + 说明文字、`generateAndRender()`、
        // `PersonaBridge.generate()`、`ImportKeyScreen` 那边的实现、
        // 以及 `ACT_GENERATE` / `SEG_GENERATE` / `FIELD_ACT` 三个常量。
        //
        // **手表端「一句话创建人设」（PersonaFactory / PersonaWaitScreen）不受影响** ——
        // 用户删的是**手机网页上的生成**，手表上那条路还在。
        //
        // ⚠️ **保留 `/gen` 路由的 404 行为**：老浏览器书签 / 历史记录如果还存着
        // 编辑页带 formaction 的旧 URL，点进去会 404 —— 这是对的
        //（功能没了就该 404，不要渲染出一个「按钮失效」的页面）。
        if (route == "$listPath/new") {
            val submitted = parseForm(body, PERSONA_FORM_FIELDS).toPersona(blankPersona())
            val created = submitted.persona.copy(
                id = UUID.randomUUID().toString(),
                // 「当初那句原始描述」在手机端没有单独一格，最接近的就是用户写的
                // 「人格内核」—— 它本来就是这一页上唯一的自由描述。
                sourceDescription = submitted.persona.soul,
                source = PersonaSource.CUSTOM,
                createdAt = System.currentTimeMillis(),
                lastChatAt = 0L,
            )
            saveOrRender(sock, created)
            return
        }

        if (route.startsWith("$listPath/")) {
            val id = route.removePrefix("$listPath/")

            // 角色已经不在了（手表上删过）：给他一条回得去的路，而不是 404。
            val existing = personas.find(id)
            if (existing == null) {
                respond(sock, personaGoneHtml())
                return
            }

            // base 用 existing：整对象覆盖的是**表单上那七格**（角色名 + 人设六块），
            // 身份信息（id / 建立时间 / 排序时间 / 来源 / 那句简介）不在表单上，
            // 也就不是「用户提交的内容」，原样留着。
            val submitted = parseForm(body, PERSONA_FORM_FIELDS).toPersona(existing)
            saveOrRender(sock, submitted.persona)
            return
        }

        respond(sock, "404 Not Found", 404)
    }

    /** 存角色。成功就 303 跳回列表（服务**不关停**），失败把原因写在表单上让用户接着改。 */
    private suspend fun saveOrRender(sock: Socket, persona: Persona) {
        val error = personas.save(persona)
        if (error != null) {
            // 回显的是**用户刚提交的那份**，不是手表上的旧值 —— 写盘失败不该让他把
            // 六块重新打一遍。
            respond(sock, personaFormHtml(persona, error = error))
            return
        }
        // 303 而不是直接把列表页当 POST 的响应：POST 的响应留住的话，
        // 用户在手机上往下拉一把就会触发「要重新提交表单吗」。
        redirect(sock, "$listPath?saved=1")
    }

    /**
     * 表单上那几格 → 一份完整角色。**这是唯一的「表单字段名 ↔ 人设字段」映射表。**
     *
     * [base] 提供表单上没有的那些信息（`id` / `createdAt` / `tagline` / 来源…）：
     * 新增时是 [blankPersona]，编辑时是手表上现在那一份。
     */
    private fun Map<String, String>.toPersona(base: Persona): PersonaSubmission = PersonaSubmission(
        persona = base.copy(
            name = this["name"].orEmpty(),
            // 简介（tagline）表单上**没有这一格**了 —— 它到处都不显示（列表只放时间和用量，
            // 详情页之外的任何地方都不出现），让用户在这里填一格「显示在角色名下面」的东西
            // 就是在骗他。所以这一项只能从 base 透传。
            //
            // ⚠️ 这里绝对不能写 `this["tagline"].orEmpty()`：这一页的语义是「整屏覆盖」，
            // 而白名单里已经没有 tagline 了，POST 里不会带这个 key ⇒ `.orEmpty()` 每次
            // 都返回空串 ⇒ **用户每次在手机上编辑一个角色，简介就被清空一次**。
            // 整轮最容易翻车的一处，改之前先想清楚「这个字段在表单上存在吗」。
            tagline = base.tagline,
            soul = this["soul"].orEmpty(),
            style = this["style"].orEmpty(),
            rules = this["rules"].orEmpty(),
            scenario = this["scenario"].orEmpty(),
            examples = this["examples"].orEmpty(),
            greeting = this["greeting"].orEmpty(),
        ),
    )

    /** 新增时那份空表单。`id` 为空 == 还没存到手表上。 */
    private fun blankPersona() = Persona(id = "", name = "", createdAt = 0L)

    /**
     * 读取表单。只认白名单里的字段名，其余一律忽略 ——
     * 这个端点不该被塞进任何意料之外的东西。
     *
     * 白名单**按页分开传**（凭证一套、角色一套），不是合成一个大的：
     * 合成之后「角色那页漏加了一个字段」这件事就没人能发现了，症状会是
     * 「保存成功但那一格是空的」，很难查。
     */
    private fun parseForm(body: String, allowed: Set<String>): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        for (pair in body.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0] in allowed) {
                fields[kv[0]] = runCatching { URLDecoder.decode(kv[1], "UTF-8") }
                    .getOrDefault(kv[1])
                    .trim()
            }
        }
        return fields
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                kv[0] to runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
            } else {
                null
            }
        }.toMap()
    }

    /**
     * 讯飞三项填齐、而用户**没有主动改过引擎**（提交上来仍是「系统」、之前也是「系统」）
     * 时，顺手把引擎切到讯飞听写。
     *
     * 为什么允许这个自动动作：只填凭证不切引擎，识别还是走系统那条路 —— 用户会觉得
     * 「我明明导进去了怎么没用」。所以表单里那句提示之外，再兜一次底。
     *
     * 为什么限定得这么死：用户自己选过别的档（之前不是「系统」）就绝不覆盖；
     * 而且这个动作会**明写在成功页上**（「另外把识别引擎切到了讯飞听写」），不是暗改。
     *
     * ⚠️ **1.982 加回 `GENERIC` 之后，这里要不要给通用接口也加一条同样的自动切换？**
     * **不加**，理由三条：
     *  1. [asrUrl] / [asrModel] **永远有默认值**（预填硅基流动那套），所以「地址非空」
     *     这件事**恒真** —— 拿它当触发条件，等于用户一打开这一页、什么都不改点保存，
     *     引擎就被悄悄从「系统」改成「通用接口」，而那个地址他多半用不了。
     *     这是「暗改」，正好踩在这段注释要防的那条线上；
     *  2. 讯飞那条能自动切，是因为「三样齐全」是一个**用户刚刚亲手做出来的、
     *     信息量很高的动作**；通用接口没有对应强度的信号；
     *  3. 用户要的是「**能选**三档」，不是「自动替他选」。档位在下拉框里明摆着，
     *     他自己选一下就好。
     */
    private fun withAutoEngine(
        before: ImportedCredentials,
        submitted: ImportedCredentials,
    ): ImportedCredentials {
        if (submitted.asrEngine != AsrEngine.SYSTEM.key) return submitted
        if (before.asrEngine != AsrEngine.SYSTEM.key) return submitted
        return if (submitted.xfyunComplete) {
            submitted.copy(asrEngine = AsrEngine.XFYUN.key)
        } else {
            submitted
        }
    }

    /**
     * 逐项宽松校验，返回第一条人话错误（没问题就返回 null）。
     *
     * 刻意**不用正则卡格式**：讯飞那三样确实长度固定（8/32/32 位十六进制），
     * 但用户在手机上从控制台复制粘贴时经常带上前后的空格或换行（上面已经 trim），
     * 卡太死只会把能用的凭证拒之门外 —— 真正的判据是「像不像粘到一半的半截串」。
     * 存进去能不能用，用户点一次识别就知道了，代价远小于「明明粘贴对了却不让导入」。
     *
     * 反过来，「引擎和凭证对不上」这种**结构性**问题必须拦：选了讯飞听写却没填齐三样，
     * 页面上不报错的话，用户回到手表上只会看到语音一直失败，且完全不知道为什么。
     */
    private fun validate(c: ImportedCredentials): String? {
        val engine = AsrEngine.fromKey(c.asrEngine)
        return when {
            // 整页清空多半是手滑（这一下会把手表上的凭证全抹掉）。
            // 真要逐项删除是可以做到的：只清那一格、其余保持原样提交，最后车库里
            // 总还剩着别的项，不会走到这个分支。
            c.allFieldsBlank ->
                "整页都是空的 —— 这一下会把手表上的凭证全清掉。只想删某一项的话，清那一格、其余保持原样再保存。"

            !looksLikeDeepSeekKey(c.apiKey) ->
                "DeepSeek API Key 看着不完整，应以 sk- 开头（不要这一项就清空它）。"
            !looksLikeToken(c.xfyAppId) -> "讯飞 AppID 看着不完整，请回去复制完整的一串。"
            !looksLikeToken(c.xfyApiKey) -> "讯飞 APIKey 看着不完整，请回去复制完整的一串。"
            !looksLikeToken(c.xfyApiSecret) -> "讯飞 APISecret 看着不完整，请回去复制完整的一串。"

            engine == AsrEngine.XFYUN && !c.xfyunComplete ->
                "选了「讯飞听写」，但三样凭证没填齐（缺 ${missingXfyun(c)}）。" +
                    "不用讯飞的话，把上面的「识别引擎」改成「系统」再保存。"

            // ---- 通用接口（1.982 加回来）----
            // 这一档**只校验地址格式**，两项刻意不校验：
            //  * Key 可以是空的（本机 whisper.cpp / faster-whisper 不鉴权，
            //    卡它会直接把这条路堵死）；
            //  * 模型名可以是空的（服务端有自己的默认值，而且各家模型名天差地别，
            //    这里没有一张能拿来说「你这个不对」的表）。
            engine == AsrEngine.GENERIC && !looksLikeAsrUrl(c.asrUrl) ->
                "「通用接口」的地址要以 http:// 或 https:// 开头，而且要带全路径" +
                    "（比如结尾的 /v1/audio/transcriptions）。换成别的档的话，" +
                    "把上面的「识别引擎」改一下再保存。"

            else -> null
        }
    }

    /**
     * 通用接口的地址。判据**刻意只做两件事**，和讯飞那三串同一个尺度
     * （「看着不像半截串」就行，真能不能用点一次识别就知道）：
     *
     *  1. 非空 —— 空地址这一档根本没法用（`Settings.speechReady` 也是这么判的），
     *     而它在表单上是一格**预填了默认值**的输入框，清空它是一个明确的动作；
     *  2. 以 `http://` 或 `https://` 开头 —— 这是 `SpeechClient` **真的会抛错**的那一条
     *     （它会抛「接口地址要以 http:// 或 https:// 开头」）。在这里先拦，
     *     用户看到的是「哪一格填错了」，而不是回到手表上按麦克风才发现。
     *
     * ⚠️ 不查主机名能不能解析、不查路径对不对 —— 那要发请求才知道，
     * 而这一页的职责只是「把表上的字收下来」。
     */
    private fun looksLikeAsrUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.isEmpty() ||
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
    }

    /** DeepSeek 的 Key 形如 `sk-` + 32 位十六进制；放宽到「sk- 开头且够长」即可。空串跳过。 */
    private fun looksLikeDeepSeekKey(key: String): Boolean =
        key.isEmpty() || (key.startsWith("sk-") && key.length in 20..200 && key.none { it.isWhitespace() })

    /**
     * 讯飞的三串都是十六进制 token。同样只做「不像半截串」的判断：
     * 非空时要求长度在合理区间内、且不含空白（含空白说明复制时带上了别的东西）。
     */
    private fun looksLikeToken(token: String): Boolean =
        token.isEmpty() || (token.length in 6..128 && token.none { it.isWhitespace() })

    /** 拼出「缺 AppID、APISecret」这样的清单，用户不用自己一格一格对。 */
    private fun missingXfyun(c: ImportedCredentials): String = listOfNotNull(
        "AppID".takeIf { c.xfyAppId.isBlank() },
        "APIKey".takeIf { c.xfyApiKey.isBlank() },
        "APISecret".takeIf { c.xfyApiSecret.isBlank() },
    ).joinToString("、")

    // ---------------------------------------------------------------- 页面

    /**
     * 手机端页面。同样贯彻 Material You：M3 基线色板 + 明暗双主题 +
     * 系统字体 + 胶囊按钮，让网页和 Material 系的 app 摆在一起不违和。
     *
     * [error] 不为 null 时是**校验没过重新渲染**，[values] 这时是用户刚提交的内容，
     * 不能改回 snapshot：让他连同其它七格一起重填是纯粹的折磨。
     */
    private fun formHtml(values: ImportedCredentials, error: String? = null): String {
        val err = if (error == null) "" else """<p class="err">${escapeHtml(error)}</p>"""
        return """
            ${htmlHead("手表里的凭证")}
            <div class="card">
            <div class="badge">⌚ WhaleChat</div>
            <h1>手表里的凭证</h1>
            <p class="sub">下面是手表上<b>现在存着</b>的内容。直接在上面改，改完点最下面的按钮保存。
            <b>清空某一格再保存，就等于删掉手表上的那一项。</b></p>
            <a class="btn tonal" href="$listPath">管理角色 →</a>
            <form method="post" action="">
            <h2>模型 · 对话用</h2>
            ${field(FIELD_API_KEY, "DeepSeek API Key", values.apiKey, "sk-…", "对话用的 Key，不填就没法聊天。")}
            <h2>语音识别 · 说话用</h2>
            <label for="$FIELD_ASR_ENGINE">识别引擎</label>
            ${engineSelect(values.asrEngine)}
            <p class="hint">三档选一个。「系统」不用配置，直接用设备自带的识别服务；
            「通用接口」要自己填一个兼容 OpenAI 的识别地址（有实现在本机跑的，那种不用 Key）；
            「讯飞听写」要把下面三串填齐。三串一旦填齐、这一栏又还停在「系统」，
            保存时会自动切到「讯飞听写」（结果页会写出来）。</p>
            <h3>通用接口 <span class="opt">（选了它才需要填）</span></h3>
            <p class="hint">录完整段再上传到你填的接口，所以它需要一个<b>能用的</b>识别接口。
            地址要带全路径，结尾通常是 /v1/audio/transcriptions。</p>
            ${field(FIELD_ASR_URL, "接口地址", values.asrUrl, "https://…/v1/audio/transcriptions")}
            ${field(FIELD_ASR_KEY, "识别 Key", values.asrKey, "可留空（本机服务通常不鉴权）")}
            ${field(FIELD_ASR_MODEL, "模型名", values.asrModel, "FunAudioLLM/SenseVoiceSmall")}
            <h3>讯飞听写 <span class="opt">（选了它才需要填）</span></h3>
            <p class="hint">边说边出字，说完立刻有结果。三串都在讯飞控制台「我的应用」里。</p>
            ${field(FIELD_XFY_APP_ID, "AppID", values.xfyAppId, "8 位十六进制")}
            ${field(FIELD_XFY_API_KEY, "APIKey", values.xfyApiKey, "32 位十六进制")}
            ${field(FIELD_XFY_API_SECRET, "APISecret", values.xfyApiSecret, "32 位十六进制")}
            $err
            <button type="submit">保存到手表</button>
            </form>
            <p class="foot">此页面由手表直接提供 · 只有同一个 Wi-Fi 下打得开<br>
            页面会明文显示已保存的凭证，别在公共 Wi-Fi 下使用</p>
            </div></body></html>
        """.trimIndent()
    }

    /** 角色列表。点一个进编辑页，也可以新增。改动是**立刻**写进手表的。 */
    private fun personaListHtml(saved: Boolean): String = buildString {
        val list = personas.list()
        append(htmlHead("角色"))
        append(
            """
            <div class="card">
            <div class="badge">⌚ WhaleChat</div>
            <h1>角色</h1>
            <p class="sub">手表上有 <b>${list.size}</b> 个角色。点一个进去改它的人设六块 ——
            改完点「保存到手表」，手表上立刻生效，不用重启应用。</p>
            """.trimIndent(),
        )
        if (saved) append("""<p class="banner">✓ 已保存到手表</p>""")
        append("""<a class="btn" href="$listPath/new">＋ 新增角色</a>""")
        if (list.isEmpty()) {
            append(
                """<p class="empty">手表上还没有角色。<br>可以用上面的按钮建一个，或者在手表上点 ＋ 造一个。</p>""",
            )
        } else {
            list.forEach { p ->
                append(
                    // 这张卡只留角色名。**不显示简介** —— 手表端列表早就不显示了
                    // （副标题只有时间和用量），网页上再摆一句「还没有一句简介」，
                    // 等于反过来提醒用户有一个他根本看不到的东西。
                    """<a class="item" href="$listPath/${escapeHtml(p.id)}">""" +
                        """<span class="t">${escapeHtml(p.name)}</span>""" +
                        """</a>""",
                )
            }
        }
        append(
            """
            <a class="btn tonal" href="$base">← 改凭证</a>
            <p class="foot">此页面由手表直接提供 · 只有同一个 Wi-Fi 下打得开</p>
            </div></body></html>
            """.trimIndent(),
        )
    }

    /**
     * 角色表单：新增与编辑共用。
     *
     * ## ⚠️ 1.987：「用 AI 生成一份」按钮已删除（用户要求，见 [handlePost] 那条长注释）
     *
     * 原来表单顶部有第二颗提交按钮（`name=$FIELD_ACT value=$ACT_GENERATE`，
     * 编辑页还带 `formaction` 指向 /gen），和「保存到手表」在同一个 form 里、靠 `act` 区分。
     * 删掉之后 form 里只剩「保存到手表」一颗按钮 ⇒ 那套「act 区分两个动作」的
     * 机制整个退场，`FIELD_ACT` 常量也删了（见 [PERSONA_FORM_FIELDS] 的注释）。
     */
    private fun personaFormHtml(
        form: Persona,
        error: String? = null,
        notice: String? = null,
    ): String {
        val editing = form.id.isNotEmpty()
        val action = if (editing) "$listPath/${escapeHtml(form.id)}" else "$listPath/new"
        val err = if (error == null) "" else """<p class="err">${escapeHtml(error)}</p>"""
        val note = if (notice == null) {
            ""
        } else {
            """<p class="banner">✦ ${escapeHtml(notice)}</p>"""
        }
        val title = if (editing) "编辑「${escapeHtml(form.name.ifBlank { "这个角色" })}」" else "新增角色"

        return """
            ${htmlHead(if (editing) "编辑角色" else "新增角色")}
            <div class="card">
            <div class="badge">⌚ WhaleChat</div>
            <h1>$title</h1>
            <p class="sub">这里是 TA 的<b>全部人设</b>。六块都要写，缺一块扮演质量就掉一档。
            <b>留空哪一格，手表上那一格就变空</b>（不是「保持原样」）。</p>
            <form method="post" action="$action">

            ${field("name", "角色名", form.name, "周野（手表列表上显示的就是它）", "必填。手表列表的标题位只够 $PERSONA_NAME_MAX 个字，超了会被截断。")}

            <h2>人设六块</h2>
            ${textArea("soul", "人格内核", form.soul, SOUL_HINT, "TA 的性格、在意什么、怕什么、遇到事会怎么反应。")}
            ${textArea("style", "说话风格", form.style, STYLE_HINT, "语气、用词习惯、口头禅、句子长短。这一块最影响「听起来像不像 TA」。")}
            ${textArea("rules", "行为规则", form.rules, RULES_HINT, "会做什么、不会做什么、边界在哪。不写的话聊久了会飘。")}
            ${textArea("scenario", "场景", form.scenario, SCENARIO_HINT, "此刻在哪、你们是什么关系、为什么会聊起来。")}
            ${textArea("examples", "示例对话", form.examples, EXAMPLES_HINT, "2 到 4 轮，一行一句：用户的句子用「你：」开头，角色的句子用 TA 的名字开头。这是六块里性价比最高的一块。", tall = true)}
            ${textArea("greeting", "开场白", form.greeting, GREETING_HINT, "进聊天页时 TA 说的第一句。不要自我介绍，直接进入状态。留空会用一句「嗯，我在，你说。」。")}
            $err
            $note
            <button type="submit">保存到手表</button>
            </form>
            <a class="btn tonal" href="$listPath">← 回到角色列表</a>
            <p class="foot">保存之后服务不会关，可以接着改下一个角色</p>
            </div></body></html>
        """.trimIndent()
    }

    /** 目标角色已经不在了（多半是在手表上删过）—— 给一条回得去的路，不要甩 404。 */
    private fun personaGoneHtml(): String = """
        ${htmlHead("角色不在了")}
        <div class="card">
        <div class="badge">⌚ WhaleChat</div>
        <h1>这个角色不在了</h1>
        <p class="sub">它多半是刚在手表上被删掉了。下面的列表是手表上现在的样子。</p>
        <a class="btn" href="$listPath">回到角色列表</a>
        </div></body></html>
    """.trimIndent()

    /** 一行输入框。名字用常量，免得 HTML 和 [FORM_FIELDS] 两处改名改漏一处。 */
    private fun field(
        name: String,
        label: String,
        value: String,
        placeholder: String = "",
        hint: String? = null,
    ): String = buildString {
        // 这里刻意用普通字符串而不是三引号：属性值最后就是一个引号，
        // 三引号写作 `…"${v}""""` 会多出一个游离的引号，直接编不过。
        append("<label for=\"$name\">${escapeHtml(label)}</label>")
        append("<input id=\"$name\" name=\"$name\" type=\"text\" value=\"${escapeHtml(value)}\"")
        if (placeholder.isNotEmpty()) append(" placeholder=\"${escapeHtml(placeholder)}\"")
        append(" autocomplete=\"off\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">")
        if (hint != null) append("<p class=\"hint\">${escapeHtml(hint)}</p>")
    }

    /**
     * 角色表单里的多行输入。
     *
     * 六块人设每一块都是多行文本，用单行 `input` 会让人以为「随便写一句就行」——
     * 而这一页的全部价值就在这几格的详略。
     */
    private fun textArea(
        name: String,
        label: String,
        value: String,
        placeholder: String,
        hint: String? = null,
        tall: Boolean = false,
    ): String = buildString {
        append("<label for=\"$name\">${escapeHtml(label)}</label>")
        append("<textarea id=\"$name\" name=\"$name\"")
        if (tall) append(" class=\"tall\"")
        append(" placeholder=\"${escapeHtml(placeholder)}\"")
        append(" autocomplete=\"off\" autocapitalize=\"sentences\" spellcheck=\"false\">")
        append(escapeHtml(value))
        append("</textarea>")
        if (hint != null) append("<p class=\"hint\">${escapeHtml(hint)}</p>")
    }

    /**
     * 引擎下拉框。取值就是 [AsrEngine.key]，和服务端 `AsrEngine.fromKey` 一一对应；
     * 认不出来的值会落回「系统」，所以这里不需要额外的白名单校验。
     */
    private fun engineSelect(current: String): String {
        val selected = AsrEngine.fromKey(current).key
        val options = AsrEngine.entries.joinToString("") { engine ->
            val mark = if (engine.key == selected) " selected" else ""
            """<option value="${engine.key}"$mark>${escapeHtml(engine.label)}</option>"""
        }
        return """<select id="$FIELD_ASR_ENGINE" name="$FIELD_ASR_ENGINE">$options</select>"""
    }

    /** 网页上的错误提示要回显，先挡掉拼 HTML 的可能（内容是本地常量，仍不省这一步）。 */
    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * 成功页把**保存后的完整状态**摊开：哪几项有值、哪几项是空的、引擎在哪一档。
     *
     * 这一页比「已导入」重要得多：整屏覆盖的语义下，用户的担心从「我是不是漏填了一格」
     * 变成了「我是不是不小心清掉了什么」—— 把结果摆出来他自己就能核对。
     */
    private fun successHtml(c: ImportedCredentials, engineSwitched: Boolean): String {
        val rows = listOf(
            "DeepSeek API Key" to c.apiKey.isNotBlank(),
            "识别引擎：${AsrEngine.fromKey(c.asrEngine).label}" to true,
            // 通用接口三格（1.982 加回来）。这一页的价值就是「把保存后的完整状态摊开」，
            // 少了这几行，选了通用接口的用户在这一页上**看不到自己那三格**，
            // 也就失去了「核对有没有存错」的能力。
            //
            // ⚠️ 三行拆开列，**不做成「通用接口」一行**：Key 允许留空，
            // 合并成一行的话「这一档配好了没有」就看不出来了 ——
            // 而用户真正要核对的是「地址在不在」「Key 是不是空的（预期之内）」。
            "通用接口 · 地址" to c.asrUrl.isNotBlank(),
            "通用接口 · Key" to c.asrKey.isNotBlank(),
            "通用接口 · 模型名" to c.asrModel.isNotBlank(),
            "讯飞 AppID" to c.xfyAppId.isNotBlank(),
            "讯飞 APIKey" to c.xfyApiKey.isNotBlank(),
            "讯飞 APISecret" to c.xfyApiSecret.isNotBlank(),
        )
        val list = rows.joinToString("") { (label, kept) ->
            if (kept) """<li>${escapeHtml(label)}</li>""" else """<li class="off">${escapeHtml(label)}</li>"""
        }
        val cleared = rows.any { !it.second }
        val notes = buildString {
            if (engineSwitched) {
                append(
                    """<p class="note">讯飞三项填齐了，识别引擎一并切到了「讯飞听写」。手表设置里可以改。</p>""",
                )
            }
            if (cleared) {
                // ⚠️ 1.982 更正了这句话的适用范围：**「通用接口 · Key」那一行
                // 留空是正常的**（本机服务不鉴权），它变成灰字并不代表出了问题。
                // 原来只有讯飞三格时「灰色 = 空的 = 你少填了」是成立的，
                // 加上通用接口之后不再成立，所以这里补一句把它说清 ——
                // 否则用户会为了那一行不被标灰，硬去填一个他其实不需要的 Key。
                append("""<p class="note">灰色那几项在手表上是空的。</p>""")
                append(
                    """<p class="note">其中「通用接口 · Key」空着是正常的 —— """ +
                        """不填就不会发鉴权头，本机跑的识别服务通常正需要这样。</p>""",
                )
            }
        }
        return """
        ${htmlHead("已保存", bodyClass = "center")}
        <div class="card">
        <div class="ok">✓</div><h1>已保存到手表</h1><p>现在可以关闭这个页面了。</p>
        <ul>$list</ul>
        $notes
        <a class="btn tonal" href="$listPath">管理角色</a>
        </div></body></html>
    """.trimIndent()
    }

    /** 所有页面共用的头部：meta + 标题 + 共用样式表。`<body>` 也在这里开，各页只管里面的卡片。 */
    private fun htmlHead(title: String, bodyClass: String = ""): String {
        val bodyTag = if (bodyClass.isEmpty()) "<body>" else """<body class="$bodyClass">"""
        return """
        <!doctype html><html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        <meta name="theme-color" content="#F7F2FA" media="(prefers-color-scheme:light)">
        <meta name="theme-color" content="#141218" media="(prefers-color-scheme:dark)">
        <title>${escapeHtml(title)}</title>
        $PAGE_STYLE
        </head>
        $bodyTag
        """.trimIndent()
    }

    /** 303 跳转。用在「角色存好了 → 回列表」那条路上，见 [saveOrRender]。 */
    private fun redirect(sock: Socket, location: String) {
        runCatching {
            val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            out.write("HTTP/1.1 303 See Other\r\n")
            out.write("Location: $location\r\n")
            out.write("Cache-Control: no-store\r\n")
            out.write("Content-Length: 0\r\n")
            out.write("Connection: close\r\n\r\n")
            out.flush()
        }
    }

    private fun respond(sock: Socket, html: String, code: Int = 200) {
        runCatching {
            val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val text = when (code) {
                200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "OK"
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 $code $text\r\n")
            out.write("Content-Type: text/html; charset=UTF-8\r\n")
            out.write("Content-Length: ${bytes.size}\r\n")
            // 这页里带着一次性 token，而且现在连凭证本身都在页面上 ——
            // 不让浏览器/中间代理留下任何副本
            out.write("Cache-Control: no-store\r\n")
            out.write("Connection: close\r\n\r\n")
            out.write(html)
            out.flush()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { serverSocket?.close() }
        cancel() // 取消 SupervisorJob → acceptLoop / 超时协程一并结束
    }

    /**
     * 取局域网 IPv4。真机上可能有多个网卡（Wi-Fi / 蜂窝 / 蓝牙），
     * 优先叫 wlan/ap 的，避免把蜂窝内网地址编进二维码。
     */
    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("ap") }
            ?.firstNotNullOfOrNull { ni ->
                ni.inetAddresses.toList().firstNotNullOfOrNull { addr ->
                    (addr as? Inet4Address)?.takeIf { !it.isLoopbackAddress }?.hostAddress
                }
            }
    }.getOrNull()

    private fun randomToken(): String = SecureRandom().let { r ->
        (1..16).joinToString("") { "0123456789abcdef"[r.nextInt(16)].toString() }
    }

    private companion object {
        /** 表单字段名。手表端解析和手机端 HTML 两处必须一致，写成常量免得改一处漏一处。 */
        const val FIELD_API_KEY = "key"
        const val FIELD_ASR_ENGINE = "asr_engine"

        /**
         * 通用接口那三格的字段名。**1.982 随那一档一起加回来。**
         *
         * ⚠️ 加字段时容易漏的两处（症状都是「保存成功但那一格是空的」）：
         *  1. [FORM_FIELDS] 白名单 —— 不在里面的 key 会被 [parseForm] **静默丢掉**；
         *  2. 表单 HTML 里的 [field] 调用 —— 没有输入格，POST 里根本不会有这个 key。
         */
        const val FIELD_ASR_URL = "asr_url"
        const val FIELD_ASR_KEY = "asr_key"
        const val FIELD_ASR_MODEL = "asr_model"

        const val FIELD_XFY_APP_ID = "xfy_app_id"
        const val FIELD_XFY_API_KEY = "xfy_api_key"
        const val FIELD_XFY_API_SECRET = "xfy_api_secret"

        // ⚠️ 1.987：FIELD_ACT / ACT_SAVE / ACT_GENERATE / SEG_GENERATE 四个常量
        // 随「用 AI 生成一份」整条链路删除 —— 表单里只剩「保存到手表」一颗按钮，
        // 不再需要 `act` 来区分（见 [handlePost] 与 [PersonaSubmission] 的注释）。

        val FORM_FIELDS = setOf(
            FIELD_API_KEY,
            FIELD_ASR_ENGINE,
            // 通用接口三格（1.982 加回来）。⚠️ 漏加 = 手机上说保存成功、
            // 手表上那三格纹丝不动（parseForm 只认白名单，其余静默丢掉）。
            FIELD_ASR_URL,
            FIELD_ASR_KEY,
            FIELD_ASR_MODEL,
            FIELD_XFY_APP_ID,
            FIELD_XFY_API_KEY,
            FIELD_XFY_API_SECRET,
        )

        /**
         * 角色表单的字段名 —— 就是 [Persona] 里**表单上有格子**的那几项，一一对应。
         *
         * **加了新的方块就必须往这里加一行**，漏加的症状是「保存成功但那一格是空的」：
         * [parseForm] 只认白名单，白名单外的 key 会被静默丢掉。
         *
         * 这里**没有 `tagline`**：简介不在网页上填。它到处都不显示（列表只有时间和用量），
         * 保留这一格只会让人填一个他永远看不到的东西；真要改，去手表的角色详情页。
         * ⚠️ 白名单和表单必须同步 —— 只藏格子不删白名单，POST 里带一个 `tagline=`
         * 仍能把值清空。
         */
        val PERSONA_FORM_FIELDS = setOf(
            "name",
            "soul",
            "style",
            "rules",
            "scenario",
            "examples",
            "greeting",
            // ⚠️ 1.987：原来的 FIELD_ACT（act）随「用 AI 生成」删除而移出白名单 ——
            // 表单里只剩「保存到手表」一颗按钮，POST 里不会再有 act=… 这个 key。
        )

        /**
         * 请求体上限。
         *
         * 原来是 8KB，注释写的是「这一页有八格、每格最多几十个字符」。那是凭证页的算法；
         * 角色那一页完全不同 —— 六块人设加好几轮示例对话，一个表单轻松超过 8KB，
         * 卡在 8KB 上手机点保存会直接 400「请求体过大」。
         *
         * 256KB 是「够用」和「还能挡住 OOM」之间取的数：正文全填满也就几十 KB，
         * 而下面那个 CharArray 是按 Content-Length 一次性分配的，不设上限的话
         * 局域网里谁发一个 Content-Length: 2000000000 就能把进程撑爆。这道闸必须留着。
         */
        const val MAX_BODY_BYTES = 256 * 1024

        /** 单连接读超时：挡住连上就不发数据的慢客户端 */
        const val SOCKET_TIMEOUT_MS = 10_000

        /**
         * 页面停留多久后自动关服。
         *
         * 从 2 分钟放宽到 10 分钟，是为角色那一页让的路：手机上手填六块人设是苦力活，
         * 填到一半服务断了等于白填。凭证那条路提交完立刻 stop()，不受这个数影响 ——
         * 但**「打开页面到提交」这段窗口确实变长了**，不要因此把服务常驻化。
         */
        const val TIMEOUT_MS = 10 * 60 * 1000L

        const val BACKLOG = 50

        // 每一格的一句话引导。用户不知道「人格内核」该写什么，没引导的表等于没给。
        // 写得像真人填过的样子，不要写成标签的复述。
        //
        // **给示范，不给形容。** 原来那组（「嘴上不饶人但真会替你着急」「短句，爱用反问」）
        // 只是描述性格，照抄会得到一段空泛的人设；下面这组每一句都是**可执行的行为**，
        // 用户照着这个颗粒度写，就不会产出「性格开朗、为人友善」这种废话。
        const val SOUL_HINT =
            "例：对外人客气，对自己人刻薄。嘴上说「随你」，但会记住你上周随口提过的事。\n" +
            "你难过的时候他不劝，只是陪着，等你先开口。"
        const val STYLE_HINT =
            "例：一次只说一句，句子很短。习惯用问句接话。从不用感叹号，\n" +
            "也从来不说「加油」「你可以的」这种话。"
        const val RULES_HINT =
            "例：不主动问隐私；被问到自己的事就岔开话题；不说教、不列点、不总结；\n" +
            "你不说话的时候他也不催你。"
        const val SCENARIO_HINT =
            "例：放学后的操场看台，天快黑了。你俩同班三年，他知道你家里的事，\n" +
            "你也知道他为什么不想回家。"
        const val EXAMPLES_HINT =
            "例：\n" +
            "你：今天不想回家。\n" +
            "周野：那就再坐会儿。\n" +
            "你：你不问为什么？\n" +
            "周野：想说的时候你会说。"
        const val GREETING_HINT = "例：来了？坐这儿吧，这儿没人。"
    }
}

/**
 * 一次角色表单提交：**表单上那七格（角色名 + 人设六块）铺成的一份完整角色**。
 *
 * 值直接装在 [Persona] 里，不另立一个「表单值」类型：多一个中间类型就多一张
 * 「字段清单」，而两张清单迟早会有一张忘了改 —— 症状是「保存成功但那一格是空的」，
 * 很难查。映射只有一处，就是 [KeyImportServer] 里那个 `Map.toPersona`。
 *
 * [persona]`.id` 为空表示**这次是新增**（还没存到手表上）。
 *
 * ## ⚠️ 1.987：`act` 字段删除
 *
 * 原来还有第二个字段 `act`（用户按的是哪颗按钮：「保存」还是「AI 生成」）——
 * 随「用 AI 生成一份」整条链路删除（见 [handlePost] 那条长注释），
 * 现在表单里只剩「保存到手表」一颗按钮，`act` 没有存在的意义了。
 */
private data class PersonaSubmission(val persona: Persona)

/**
 * 所有手机页共用的样式表。原来只有凭证表单和成功页两张，各写一份还能忍；
 * 现在多了角色列表和角色表单，四份抄下去迟早有一份对不上。
 *
 * M3 基线色板 + 明暗双主题 + 系统字体 + 胶囊按钮，让网页和 Material 系的 app
 * 摆在一起不违和。
 */
private val PAGE_STYLE = """
    <style>
    :root{color-scheme:light dark;
      --primary:#6750A4;--on-primary:#FFFFFF;--primary-ctn:#EADDFF;--on-primary-ctn:#21005D;
      --bg:#F7F2FA;--card:#FFFFFF;--on-bg:#1C1B1F;--on-bg-var:#49454F;
      --outline:#CAC4D0;--err:#B3261E;--surface:#F3EDF7}
    @media (prefers-color-scheme:dark){:root{
      --primary:#D0BCFF;--on-primary:#381E72;--primary-ctn:#4F378B;--on-primary-ctn:#EADDFF;
      --bg:#141218;--card:#1D1B20;--on-bg:#E6E0E9;--on-bg-var:#CAC4D0;
      --outline:#49454F;--err:#F2B8B5;--surface:#211F26}}
    *{box-sizing:border-box}
    body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
         background:var(--bg);color:var(--on-bg);display:flex;min-height:100vh;
         align-items:center;justify-content:center;padding:24px 20px;line-height:1.5}
    body.center{text-align:center;align-items:center}
    .card{background:var(--card);border-radius:28px;padding:28px 24px;width:100%;max-width:420px;
          box-shadow:0 2px 6px rgba(0,0,0,.08),0 12px 32px rgba(0,0,0,.12)}
    .badge{display:inline-block;font-size:12px;font-weight:600;letter-spacing:.4px;
           color:var(--on-primary-ctn);background:var(--primary-ctn);
           border-radius:999px;padding:6px 14px;margin:0 0 14px}
    h1{font-size:22px;font-weight:600;margin:0 0 6px}
    p.sub{color:var(--on-bg-var);font-size:14px;margin:0 0 20px}
    h2{font-size:12px;font-weight:600;letter-spacing:.6px;text-transform:none;
       color:var(--on-bg-var);margin:22px 0 10px;padding-top:18px;
       border-top:1px solid var(--outline)}
    h3{font-size:13px;font-weight:600;color:var(--on-bg);margin:20px 0 12px}
    h3 .opt{color:var(--on-bg-var);font-weight:400}
    label{display:block;font-size:12px;font-weight:600;letter-spacing:.6px;
          color:var(--on-bg-var);margin:0 0 8px}
    input,select,textarea{width:100%;padding:16px;font-size:16px;color:var(--on-bg);
          background:transparent;border:1px solid var(--outline);border-radius:16px;
          margin-bottom:16px;outline:none;transition:border-color .15s;
          font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
    select,input{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}
    textarea{font-size:15px;line-height:1.55;min-height:110px;resize:vertical;
             font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}
    textarea.tall{min-height:170px}
    input:focus,select:focus,textarea:focus{border-color:var(--primary);border-width:2px;padding:15px}
    .hint{color:var(--on-bg-var);font-size:12px;margin:-8px 0 16px;line-height:1.45;white-space:pre-line}
    button{width:100%;padding:16px;border:0;border-radius:999px;background:var(--primary);
           color:var(--on-primary);font-size:16px;font-weight:600;cursor:pointer;
           transition:filter .15s;margin-top:4px;font-family:inherit}
    button:active{filter:brightness(.92)}
    a.btn{display:block;width:100%;padding:16px;border-radius:999px;background:var(--primary);
          color:var(--on-primary);font-size:16px;font-weight:600;text-decoration:none;
          text-align:center;margin:8px 0 0}
    a.btn.tonal{background:var(--primary-ctn);color:var(--on-primary-ctn)}
    a.btn:active{filter:brightness(.92)}
    a.item{display:block;text-decoration:none;color:var(--on-bg);background:var(--surface);
           border:1px solid var(--outline);border-radius:20px;padding:14px 16px;margin:10px 0 0}
    a.item:active{filter:brightness(.96)}
    a.item .t{display:block;font-size:16px;font-weight:600}
    a.item .s{display:block;font-size:13px;color:var(--on-bg-var);margin-top:2px}
    .empty{color:var(--on-bg-var);font-size:14px;text-align:center;padding:22px 0 6px}
    .err{color:var(--err);font-size:13px;margin:-4px 0 14px}
    .banner{background:var(--primary-ctn);color:var(--on-primary-ctn);font-size:13px;
            border-radius:16px;padding:12px 14px;margin:0 0 14px;line-height:1.45}
    .foot{color:var(--on-bg-var);font-size:12px;margin:18px 0 0;text-align:center;line-height:1.6}
    b{font-weight:600}
    .ok{width:96px;height:96px;border-radius:999px;background:var(--primary-ctn);
        color:var(--on-primary-ctn);display:flex;align-items:center;justify-content:center;
        font-size:48px;margin:0 auto 18px}
    ul{list-style:none;padding:0;margin:18px 0 0;text-align:left;display:inline-block;min-width:240px}
    li{font-size:14px;color:var(--on-bg);padding:5px 0;border-bottom:1px solid var(--outline)}
    li:before{content:"✓  ";color:var(--primary)}
    li.off{color:var(--on-bg-var)}
    li.off:before{content:"○  "}
    .note{color:var(--on-bg-var);font-size:13px;margin:16px 0 0;text-align:left}
    </style>
""".trimIndent()
