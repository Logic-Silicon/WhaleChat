package com.naoh.whalechat.data

/**
 * 2.37 长期记忆的**纯逻辑**：抽取提示词、解析、去重、上限淘汰、注入块拼装。
 *
 * 为什么单独一个文件：
 *  * 这些规则（每条 ≤40 字、每个角色 ≤10 条、全局 ≤40 条、去重要「相等或互相包含」）
 *    都是**可离线验证**的纯函数，混进 `ChatEngine` 就没法单独核对；
 *  * `ChatEngine` 那边只负责「什么时候调、失败怎么办」。
 *
 * ## 设计边界（P3 提示词的红线）
 *
 *  * **不做向量库、不做摘要压缩、不引入依赖** —— 一张本地要点卡片就够；
 *  * 记忆**只记关于对方的事**（称呼 / 喜好 / 计划 / 日期 / 边界），
 *    不记一次性情绪、闲聊、对对方的评价、任何猜测；
 *  * 抽取失败一律静默（`ChatEngine` 那边 `runCatching`），绝不影响对话。
 */
object MemoryExtract {

    /** 每个角色最多几条。超了淘汰 [MemoryItem.lastUsedAt] 最旧的。 */
    const val MAX_PER_PERSONA = 10

    /** 全局最多几条（所有角色加起来）。 */
    const val MAX_TOTAL = 40

    /** 一条记忆最多多少字（落盘前的硬截断）。 */
    const val MAX_TEXT_CHARS = 40

    /** 一次抽取最多收几条（提示词要求模型最多给 3 条；解析时再兜一次）。 */
    const val MAX_PER_BATCH = 3

    /** 注入 prompt 时最多带几条（按 [MemoryItem.lastUsedAt] 倒序）。 */
    const val INJECT_MAX = 8

    /** 抽取时把最近多少条消息发给模型。 */
    const val RECENT_MESSAGES = 12

    /** 最近消息拼起来的字符上限（防止老会话把一次抽取变成一次大请求）。 */
    const val RECENT_MAX_CHARS = 2000

    /** 距上次抽取至少新增几条**用户**消息才再抽一次。 */
    const val EXTRACT_EVERY_USER_MESSAGES = 6

    /** 抽取请求的参数（P3.3 给的值，别随手改大：这是后台小任务，用户不会等）。 */
    const val EXTRACT_MAX_TOKENS = 120
    const val EXTRACT_TEMPERATURE = 0.3
    const val EXTRACT_TIMEOUT_SECONDS = 30L

    /**
     * 抽取提示词（P3.4 **原样使用**）。
     *
     * ⚠️ 改提示词等于改「什么值得记」，会直接影响用户看到的「她记不记得我」。
     *    真要改，先想清楚：哪些是**稳定信息**、哪些只是当时的情绪。
     */
    const val PROMPT: String =
        "你在整理一段聊天记录里「值得长期记住的事」。\n" +
            "\n" +
            "只记关于对方的、稳定的信息：称呼、喜好、正在进行的计划、重要日期、明确说过的边界。\n" +
            "不记：一次性的情绪、闲聊、你对他的评价、任何猜测。\n" +
            "\n" +
            "输出格式：每行一条，最多 3 条，每条不超过 30 字。\n" +
            "用第三人称写（「他」「她」，或直接省略主语）。\n" +
            "没有值得记的就只输出 NONE。"

    /**
     * 解析模型输出：按行切 → 去掉 `-`/`·` 前缀 → 过滤空行与 `NONE` → 截到 40 字 → 去重。
     *
     * 去重只看**本批**（同一批里模型偶尔会把同一件事写两遍）；
     * 和已有记忆的比较在 [merge] 里做（那里才知道现有条目）。
     */
    fun parse(raw: String): List<String> = raw.lineSequence()
        .map { line -> line.trim().trimStart('-', '·', '•', '*').trim() }
        .filter { it.isNotEmpty() && !it.equals("NONE", ignoreCase = true) }
        .map { it.take(MAX_TEXT_CHARS) }
        .distinctBy(::normalized)
        .take(MAX_PER_BATCH)
        .toList()

    /**
     * 归一化：只留字母和数字，去掉标点、空格、符号。
     *
     * 中文用 `isLetterOrDigit()` 也能覆盖（汉字是 letter）。
     * 用途只有去重比较 —— **不要拿它去改落盘文本**（标点是原话的一部分）。
     */
    fun normalized(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 判重：**相等，或者互相包含**（P3.4 的口径）。
     *
     * 为什么允许「包含」：同一件事会被不同轮次说成
     * 「下周三是考试」「下周三考试」「他下周三有场考试」——
     * 严格相等会全放过，同一件事就记三四条，10 条上限很快被一件小事占满。
     *
     * 为什么要归一化：标点/空格/全半角差异不该算两条。
     */
    fun isDuplicate(candidate: String, existingTexts: Collection<String>): Boolean {
        val c = normalized(candidate)
        if (c.isEmpty()) return true
        return existingTexts.any { e ->
            val en = normalized(e)
            en.isNotEmpty() && (en == c || en.contains(c) || c.contains(en))
        }
    }

    /**
     * 把一批新记忆合并进现有列表，并**执行上限淘汰**。
     *
     * 顺序（别调换）：
     *  1. 先和**同一角色**的已有条目去重（不同角色之间不去重 —— 各记各的）；
     *  2. 新条目 `createdAt = lastUsedAt = now`；
     *  3. 每个角色只留 `lastUsedAt` 最新的 [MAX_PER_PERSONA] 条；
     *  4. 全局只留最新的 [MAX_TOTAL] 条。
     *
     * @return 合并后的完整列表（调用方负责落盘）
     */
    fun merge(
        existing: List<MemoryItem>,
        personaId: String,
        newTexts: List<String>,
        now: Long,
    ): List<MemoryItem> {
        val samePersona = existing.filter { it.personaId == personaId }
        val fresh = newTexts
            .map { it.trim().take(MAX_TEXT_CHARS) }
            .filter { it.isNotEmpty() }
            .filterNot { isDuplicate(it, samePersona.map { item -> item.text }) }
            // 同一批里也可能有互相包含的两条
            .fold(mutableListOf<String>()) { acc, text ->
                if (!isDuplicate(text, acc)) acc += text
                acc
            }

        if (fresh.isEmpty()) return existing

        val added = fresh.map {
            MemoryItem(personaId = personaId, text = it, createdAt = now, lastUsedAt = now)
        }
        var all = existing + added

        // 每个角色保留最近「用过/重新抽到」的 10 条
        val keptPerPersona = all
            .groupBy { it.personaId }
            .flatMap { (_, items) ->
                items.sortedByDescending { it.lastUsedAt }.take(MAX_PER_PERSONA)
            }
        all = keptPerPersona
        // 再按全局上限收口。两次淘汰都按 lastUsedAt，不会出现「刚记下的立刻被删」
        //（新条目的 lastUsedAt = now 最大，一定先留下）。
        return all.sortedByDescending { it.lastUsedAt }.take(MAX_TOTAL)
    }

    /**
     * 拼注入块：`【你记得】` + 每个角色的最近几条。
     *
     *  * 只取**当前角色**的条目；
     *  * 按 [MemoryItem.lastUsedAt] 倒序、最多 [INJECT_MAX] 条；
     *  * **没有条目就返回 null**（调用方整块跳过）——
     *    留一个空的「【你记得】」会让模型以为「这里本来该有内容」；
     *  * ⚠️ 注入**不更新** `lastUsedAt`：那会让「最近提到的」变成「最近聊的」，
     *    语义就错了（更新只发生在**重新抽到同一条**时，而同一件事按去重规则不会重复入库）。
     */
    fun rememberBlock(items: List<MemoryItem>, personaId: String): String? {
        val list = items
            .filter { it.personaId == personaId }
            .sortedByDescending { it.lastUsedAt }
            .take(INJECT_MAX)
            .map { it.text }
        if (list.isEmpty()) return null
        return "【你记得】\n" + list.joinToString("\n") { "- $it" }
    }

    /** 当前角色最近的一条记忆（主动消息的钩子用）。没有就 null。 */
    fun latestFor(items: List<MemoryItem>, personaId: String): MemoryItem? =
        items.filter { it.personaId == personaId }.maxByOrNull { it.lastUsedAt }

    /**
     * 启动时清理孤儿：`personaId` 已经不在 `personas.json` 里的条目直接丢。
     *
     * 为什么需要兜底（删角色时已经级联删过一遍）：
     *  * 用户可能在「删角色」与「写 memories.json」之间杀掉进程；
     *  * 也可能从手机端/清数据等路径删了角色，那条代码路径没经过 [ChatEngine.deletePersona]。
     * 启动时对齐一次，孤儿条目就不会永远占着 10/40 的名额。
     */
    fun cleanupOrphans(items: List<MemoryItem>, alivePersonaIds: Set<String>): List<MemoryItem> =
        items.filter { it.personaId in alivePersonaIds }
}
