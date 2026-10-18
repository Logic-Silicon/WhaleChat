package com.naoh.whalechat.net

import com.naoh.whalechat.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 把一段 WAV 交给**用户在设置里自己填的**语音识别接口。
 *
 * 刻意只依赖 OpenAI 那套 `/v1/audio/transcriptions` 的形状
 * （`multipart/form-data`，字段就 `model` + `file`），因为从硅基流动、
 * 阿里 DashScope、Azure，到自建的 whisper.cpp / faster-whisper，大家都是照这个做的。
 * 结果是换服务商只要改设置页那三个字段，一行代码都不用动。
 *
 * 关于 DeepSeek：它**没有**音频能力。官方 chat/completions 的
 * `messages[].content` 是 `string required`，请求体里没有 audio/speech 字段。
 * 所以音频注定要先在这里变成文字，DeepSeek 才看得见 —— 这条链路省不掉。
 *
 * ## 来历（1.982）：这是**恢复**，不是新写
 *
 * 1.0 时代这个文件就存在（`net/SpeechClient.kt`），后来随「通用接口」那一档一起
 * 被整体删除；1.982 按用户要求「把基于 OpenAI 规范接口的语音识别 API 服务加回来」
 * 原样恢复。恢复时只动了三处，逐条列在这里，方便和 `_oldasr_原始实现参考/weards_SpeechClient.kt`
 * 逐字节比对：
 *
 *  1. **包名** `com.naoh.weards.net` → `com.naoh.whalechat.net`；
 *  2. **删掉了文件里那份重复的 `SpeechException` 定义** —— 工程里已有
 *     [com.naoh.whalechat.net.SpeechException]（`net/SpeechException.kt`），
 *     两份定义会直接编译不过（redeclaration）。直接复用现有那份。
 *     ⚠️ 两份的 message 拼法不同（原版是 `hint：detail`，现有是 `detail ?: hint`），
 *     **以现有那份为准**，界面只读 `hint`，不受影响；
 *  3. **加了 [transcribe] 的 `Settings` 便利重载**（见下），省得每个调用点
 *     各自把三个字段摊开 —— 摊开写就多三处会和设置字段脱节的地方。
 *
 * 其余（超时值、错误映射、三级解析兜底、`isNull` 那一道）**一字未改**。
 */
class SpeechClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 60 秒的录音在慢一点的服务上也要几十秒，读超时必须给足
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 用当前设置里的那一组「通用接口」参数识别一段录音。
     *
     * 加这个重载的理由：调用点（`VoiceInput.runGeneric`）手上只有一份 `Settings`，
     * 让它把 `asrUrl` / `asrKey` / `asrModel` 三个字段摊开传，就多了一处
     * 「设置里加了字段、这里没跟上」的机会 —— 那正是这个版本要防的那类错。
     */
    suspend fun transcribe(config: Settings, wav: ByteArray): String = transcribe(
        url = config.asrUrl,
        apiKey = config.asrKey,
        model = config.asrModel,
        wav = wav,
    )

    /**
     * @param url 用户在设置页填的完整地址（要带 `/v1/audio/transcriptions` 那一段）。
     * @param apiKey 允许空 —— 空的就不发 `Authorization` 头（见下）。
     * @param model 空的就回落到 [DEFAULT_MODEL]。
     * @param wav 带 44 字节 WAV 头的完整字节（`AudioRecorder.record()` 的返回值）。
     */
    suspend fun transcribe(
        url: String,
        apiKey: String,
        model: String,
        wav: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            throw SpeechException("接口地址要以 http:// 或 https:// 开头")
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model.trim().ifBlank { DEFAULT_MODEL })
            .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV_MEDIA))
            .build()

        val request = Request.Builder()
            .url(target)
            .post(body)
            .apply {
                // 自建的本地服务通常不带鉴权，所以 Key 空着就不发这个头，
                // 免得某些实现见到空 Bearer 反而判 401
                val key = apiKey.trim()
                if (key.isNotEmpty()) addHeader("Authorization", "Bearer $key")
            }
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
                val text = parseText(response.body?.string().orEmpty())
                if (text.isBlank()) throw SpeechException("没听清，再说一次")
                text
            }
        } catch (e: IOException) {
            throw SpeechException(networkHint(e), e.message)
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * 结果解析。主流是 `{"text": "..."}`，但也见过裸文本和 `data.text` 套一层。
     * 都不中就当成裸文本返回 —— 与其在这里报错，不如把原文交给用户判断。
     */
    private fun parseText(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return trimmed
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return trimmed
        return listOf(
            json.stringOrNull("text"),
            json.optJSONObject("data")?.stringOrNull("text").orEmpty(),
            json.stringOrNull("result"),
        ).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /**
     * 和 DeepSeekClient.parseDelta 同一个坑：字段值是显式 JSON `null` 时，
     * `optString` 会走 `String.valueOf(JSONObject.NULL)` 返回字面量 "null"，
     * 于是识别结果里会凭空多出一段 "null"。必须先 isNull 挡一下。
     */
    private fun JSONObject.stringOrNull(key: String): String =
        if (isNull(key)) "" else optString(key).orEmpty()

    private fun httpError(response: Response): SpeechException {
        val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val detail = runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
            json.stringOrNull("message")
                .ifBlank { json.stringOrNull("error") }
                .ifBlank { json.optJSONObject("error")?.stringOrNull("message").orEmpty() }
        }?.takeIf { it.isNotBlank() }

        val hint = when (response.code) {
            400, 422 -> "接口不认这个请求，检查模型名"
            401, 403 -> "语音识别 Key 无效"
            404 -> "接口地址不对（404）"
            413 -> "录音太长了"
            429 -> "请求太频繁，稍后再试"
            in 500..599 -> "语音识别服务暂时不可用"
            else -> "识别失败（HTTP ${response.code}）"
        }
        return SpeechException(hint, detail)
    }

    /**
     * 网络侧失败的人话映射。**这一条本版特意保留**，因为它就是
     * 「失败要立刻说、不要让用户等超时」那条铁律的落点：断网、域名解析不了、
     * 明文 HTTP 被系统拦下，都必须当场变成一句用户看得懂的中文，
     * 而不是让界面在「正在聆听…」上干等二十几秒（1.0 实测过那个症状）。
     */
    private fun networkHint(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "连不上识别服务，检查网络"
        is java.net.SocketTimeoutException -> "识别超时，请重试"
        // targetSdk 28+ 默认禁明文 HTTP，用户填了 http:// 的自建地址就会撞这个
        is java.net.UnknownServiceException -> "系统不允许明文 HTTP，接口请用 https"
        is javax.net.ssl.SSLException -> "证书校验失败，检查接口地址"
        else -> "网络错误"
    }

    private companion object {
        val WAV_MEDIA = "audio/wav".toMediaType()

        /** 与设置页默认值（`Settings.DEFAULT_ASR_MODEL`）保持一致，兜底用 */
        const val DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"
    }
}
