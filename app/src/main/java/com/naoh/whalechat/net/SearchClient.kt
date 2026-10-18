package com.naoh.whalechat.net

import com.naoh.whalechat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 一条搜索结果。**只留真正要用的字段** —— 官方返回里还有 `snippet` / `content` /
 * `authority` / `url` 之类，这个 App 一个都不用（`summary` 已经是「给模型读的长摘要」，
 * `snippet` 是给人扫一眼的短版、`content` 可能是整页正文）。
 *
 * 留 [url] 是因为以后要在答案里标出处时会用到（现在只拼进上下文，不展示）。
 */
data class SearchHit(
    val title: String,
    val url: String,
    /** 给模型读的长摘要。⚠️ 实测长度 **147~3409 字**（远超文档说的 600~800）⇒ 必须截断 */
    val summary: String,
    /** 官方相关度分，0~3。**实测：好结果 1.8~2.3，乱码冷门词只有 0.47~0.52** */
    val rerankScore: Double,
    /** ⚠️ **可能为 null**（官方没给这个字段时）。别当必有字段。 */
    val date: String?,
)

/**
 * DeepSeek 官方**网页搜索**接口。
 *
 * ```
 * POST https://api.deepseek.com/v1/tools/search
 * ```
 *
 * ## 为什么不用官方那个「内置 web_search 工具」
 *
 * 官方另有一个**服务端自动搜**的内置工具（`tools:[{"type":"web_search"}]`），
 * 但官方原文写着：**「`deepseek-v4-pro` 支持……`deepseek-flash` 下忽略」**，
 * 而且**只在 Responses API** 里可用。本项目**硬约定只用 `deepseek-flash`**、
 * [DeepSeekClient] 是**手写 SSE 解析 `/chat/completions`** ⇒ 那条路走不通。
 * 所以用这个**独立的一次性 HTTP 请求**，搜完把结果拼进上下文。
 *
 * ## 事实基础（2026-10-16 用真实 Key 实测过 7 次，全成功、耗时 0.23~0.52s）
 *
 * | 项 | 事实 |
 * |---|---|
 * | 鉴权 | **同一个 DeepSeek Key**（`Authorization: Bearer`）⇒ 不需要新凭证 |
 * | 计费 | 官方按次收：**5 元 / 1000 次**（≈0.005 元/次）；**只有 200 且 `results` 非空才计费** |
 * | 限速 | 5 QPS（按账号） |
 * | 请求体 | `query`（必填，1~128 字符）、`count`（默认 10，1~50）、`need_content`（默认 false） |
 *
 * ## 两条与 [SpeechClient] 一致的取舍
 *
 * 1. **超时必须比对话短**：这是手表，搜索是**额外一次网络往返**，
 *    让用户盯着转圈比搜不到更糟。`connect 15s` / `read 20s` 封顶。
 * 2. **失败不往外抛重异常**：搜索是**锦上添花**，它挂了绝不能把对话也带下水。
 *    [search] 只在拿到 **HTTP 200** 时返回结果；其余情况**抛 [SearchException]**，
 *    由调用方（`ChatEngine`）整段 catch 掉、静默降级成「没开搜索」。
 *    ⇒ 这里保留一个**轻量**异常只是为了让调用方能区分「失败」与「空结果」，
 *    不是要往界面上报错。**没有重复定义 `DeepSeekException` / `SpeechException`。**
 */
class SearchClient {

    private val http = OkHttpClient.Builder()
        // 手表上网络本来就慢；但这是**额外**一次往返，超时给人短一点。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 搜一次。**返回空列表是正常结果**（搜到了但 `results` 为空 ⇒ 不是错误）。
     *
     * @param count 要几条，官方范围 1~50。默认 5 —— 再多也只是白花 token，
     *   真正进上下文的还要过 `rerank_score` 闸门（见 `ChatEngine`）。
     */
    suspend fun search(apiKey: String, query: String, count: Int = 5): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()

            val payload = JSONObject()
                .put("query", q.take(MAX_QUERY_CHARS))
                .put("count", count.coerceIn(1, 50))
                // need_content 保持 false：content 是整页正文（可能几万字），
                // 而 summary 就是「给模型读的长摘要」—— 要正文干什么？
                .put("need_content", false)
                .toString()

            val request = Request.Builder()
                .url(searchUrl())
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw SearchException(httpHint(response))
                    parseHits(response.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                throw SearchException(networkHint(e), e.message)
            }
        }

    // ---------------------------------------------------------------- internals

    /**
     * 搜索地址。
     *
     * ⚠️ **它是 `BuildConfig` 里的编译期常量，不是运行时读设置。**
     * 这样做的两个理由：
     *  1. **release 包里永远是官方地址**，用户改不到、也没有多一个可被改坏的输入；
     *  2. `.debug` 联调时可以用 `-PsearchBaseUrl=http://…` 把它指到假服务上
     *     （见 `app/build.gradle.kts` 里那一项），**不用改一行源码**。
     *     ⚠️ 出正式包时**不带这个 -P 参数**，dex 里就是官方地址（已用抽查核对）。
     */
    private fun searchUrl(): String = BuildConfig.SEARCH_BASE_URL.trimEnd('/') + SEARCH_PATH

    /**
     * 解析 `results[]`。
     *
     * ⚠️ **`optString` 遇显式 `null` 会返回字面量 `"null"`。**
     * 这个坑在 `DeepSeekClient.parseDelta` 和 `SpeechClient.parseText` 上各踩过一次，
     * 这里是第三处 —— 尤其 **`date` 官方就可能给 `null`**，
     * 不挡的话拼进上下文会出现「日期：null」。
     */
    private fun parseHits(raw: String): List<SearchHit> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val summary = o.stringOrNull("summary")
            // 摘要为空的条目没有价值（模型读不到东西），直接丢掉。
            if (summary.isBlank()) return@mapNotNull null
            SearchHit(
                title = o.stringOrNull("title"),
                url = o.stringOrNull("url"),
                summary = summary,
                rerankScore = o.optDouble("rerank_score", 0.0),
                // ★ `date` 允许为 null：显式 JSON null 与「键不存在」都收敛成 null。
                date = o.stringOrNull("date").takeIf { it.isNotBlank() },
            )
        }
    }

    /** `isNull` 先挡显式 null，再取字符串。见 [parseHits] 的注释。 */
    private fun JSONObject.stringOrNull(key: String): String =
        if (isNull(key)) "" else optString(key).orEmpty()

    /**
     * HTTP 错误映射成人话（照 [SpeechClient.httpError] 的写法）。
     * ⚠️ 这些文案**基本不会给用户看到** —— 调用方会静默降级。
     * 留着是为了 debug 时能从日志一眼看出是哪种失败。
     */
    private fun httpHint(response: Response): String = when (response.code) {
        400, 422 -> "搜索请求不合法"
        401, 403 -> "API Key 无效"
        429 -> "搜索请求太频繁，稍后再试"
        in 500..599 -> "搜索服务暂时不可用"
        else -> "搜索失败（HTTP ${response.code}）"
    }

    private fun networkHint(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "连不上搜索服务，检查网络"
        is java.net.SocketTimeoutException -> "搜索超时"
        is java.net.UnknownServiceException -> "系统不允许明文 HTTP"
        is javax.net.ssl.SSLException -> "证书校验失败"
        else -> "网络错误"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 官方接口路径。拼在 `BuildConfig.SEARCH_BASE_URL` 后面。 */
        const val SEARCH_PATH = "/v1/tools/search"

        /** `query` 官方上限 128 字符；超了会被拒，这里先截断。 */
        const val MAX_QUERY_CHARS = 128
    }
}

/**
 * 搜索失败。**轻量、且调用方一定会吞掉它**（静默降级成「没开搜索」）。
 *
 * ⚠️ 刻意**不**复用 [DeepSeekException]：那个的语义是「对话失败、要告诉用户」，
 * 而搜索失败是**可忽略**的。混用会让调用方没法区分「该不该提示用户」。
 */
class SearchException(val hint: String, val detail: String? = null) :
    Exception(if (detail.isNullOrBlank()) hint else "$hint：$detail")
