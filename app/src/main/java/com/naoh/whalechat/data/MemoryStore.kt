package com.naoh.whalechat.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条长期记忆：**关于对方的、稳定的信息**（2.37 / P3）。
 *
 * 例：「下周三是考试，最近在复习」「不喜欢被叫主人」。
 *
 * 字段都很小，但每一个都有用途：
 *  * [personaId]：记忆是**按角色**记的 —— 大肥鱼记得你下周三考试，不代表「周野」也知道；
 *  * [text]：一条一句话，≤ 40 字（抽取时截断）。**不存问句**：存事实，话术在
 *    [ProactivePolicy.memoryHook] 和 prompt 注入时现拼；
 *  * [createdAt]：什么时候记下的（排查「这条是哪来的」用）；
 *  * [lastUsedAt]：**淘汰顺序**的依据 —— 超过上限时删最久没被「重新抽到」的那条。
 *    ⚠️ 「被注入进 prompt」**不更新**它（见 [MemoryExtract] 的注释），
 *    否则「最近提到的」会退化成「最近聊的」，语义就错了。
 */
data class MemoryItem(
    val personaId: String,
    val text: String,
    val createdAt: Long,
    val lastUsedAt: Long,
)

/**
 * 记忆落盘：`files/memories.json`，单文件 JSON。
 *
 * 写法**照抄 `ConversationStore`**（临时文件 + rename；rename 失败再原地覆盖）。
 * 为什么不能「先删再写」：删完到写回之间断电，用户的记忆就全没了 ——
 * 聊天记录可以重来，记下来的事重来不了。
 *
 * 文件结构（version 字段留给以后迁移，现在只读不判断）：
 * ```json
 * { "version": 1, "items": [ { "personaId": "...", "text": "...", "createdAt": 0, "lastUsedAt": 0 } ] }
 * ```
 */
class MemoryStore(private val file: File) {

    fun load(): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray(KEY) ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val personaId = o.optString("personaId")
                val text = o.optString("text")
                // 半截条目（没 id 或没 text）直接丢：留着只会在注入时拼出空块。
                if (personaId.isBlank() || text.isBlank()) return@mapNotNull null
                MemoryItem(
                    personaId = personaId,
                    text = text,
                    createdAt = o.optLong("createdAt", 0L),
                    lastUsedAt = o.optLong("lastUsedAt", 0L),
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(items: List<MemoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("personaId", item.personaId)
                    .put("text", item.text)
                    .put("createdAt", item.createdAt)
                    .put("lastUsedAt", item.lastUsedAt),
            )
        }
        val text = JSONObject()
            .put(FORMAT_VERSION, 1)
            .put(KEY, array)
            .toString()

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // 某些文件系统上 rename 到已存在目标会失败；**绝不能用「先 delete 再 rename」**。
            runCatching { tmp.copyTo(file, overwrite = true) }
            tmp.delete()
        }
    }

    private companion object {
        const val KEY = "items"
        const val FORMAT_VERSION = "version"
    }
}
