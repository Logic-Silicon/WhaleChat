package com.naoh.whalechat.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 2.35「打开即说」主动消息的**全部判定与文案**。
 *
 * ## 这一版做什么（用户原话的复述）
 *
 * 你点开和大肥鱼的会话时，如果她已经很久没跟你说话了，**她会先开口**：
 * 你不说话，屏幕上已经有 1~3 条她说的话，像她一直在那儿。
 *
 * ## 明确不做（红线，别越界）
 *
 *  * **不做后台通知 / WorkManager / AlarmManager**：手表上的 Doze、耗电、通知打扰
 *    都不划算，这一版只在「你打开会话的那一刻」生效；
 *  * **不联网、不调模型**：所有句子都在这里本地拼出来，零成本零延迟。
 *    ⇒ 这个文件里**不许出现任何网络调用**，也不许 import 任何 net 包；
 *  * 不改 Ask 模式、不改人设六块、不改 `CHAT_OUTPUT_RULES`。
 *
 * ## 为什么判定写成纯函数
 *
 * [shouldSpeak] 只接收 `now` / `zone`，**函数内部不读系统时间**。
 * 这不是洁癖：跨天、凌晨、22:05、第 3 次这些边界**没法靠等真时间测**，
 * 只有把时间当参数传进来，才能在一个测试里把整张边界表跑一遍。
 * 同理 [composeLines] 也不读时间，`hour` 与 `gapMillis` 都由调用方给。
 *
 * ## 状态与内存的关系
 *
 * 「钩子」那一句（第三段）优先用长期记忆（2.37 的 `memories.json`），
 * 没有记忆才回落到 2.32 的「此刻」状态。所以 [composeLines] 的 [memoryText]
 * 是可选参数 —— 2.35 先传 null，2.37 接上。**同一个函数，不在两处各拼一套句子。**
 */
object ProactivePolicy {

    /** 距上次**用户**发言至少这么久才开口。 */
    const val GAP_HOURS: Long = 4L

    /** 静默时段：`[07:00, 22:00)`。22:00 起静默。 */
    const val WINDOW_START_HOUR: Int = 7
    const val WINDOW_END_HOUR: Int = 22

    /** 每天最多主动几次。 */
    const val DAILY_CAP: Int = 2

    /** 一次最多几条（文档 4.4：最多 3 条）。 */
    const val MAX_LINES: Int = 3

    /** 每条最多多少字（文档 4.4：每条 ≤ 30 字）。 */
    const val MAX_LINE_CHARS: Int = 30

    /** 招呼按「多久没说话」分四档（文档 4.1 的表格）。 */
    const val BUCKET_RECENT: Int = 0       // 4–8h
    const val BUCKET_HALF_DAY: Int = 1     // 8–24h
    const val BUCKET_DAYS: Int = 2         // 1–3 天
    const val BUCKET_LONG: Int = 3         // > 3 天

    /**
     * **该不该开口**（文档第三节的六条判定，全部满足才 true）。
     *
     * ⚠️ 每一条都要保留，任何一条删掉都会出现一类「她怎么又冒出来了」：
     *  * `enabled`：设置里关掉就彻底静默（[com.naoh.whalechat.data.Settings.proactiveEnabled]）；
     *  * `lastUserAt > 0`：**从没说过话就不主动** —— 否则新装的用户一进会话，
     *    开场白后面立刻跟一句「回来啦」，像在对着空气说话；
     *  * 时段：`[7, 22)`，凌晨不打扰；
     *  * 间隔 ≥ 4h；
     *  * 距**上次主动**也 ≥ 4h：防止「你开一次她说一次」；
     *  * 每天 ≤ 2 次。
     *
     * @param now           当前毫秒时间戳（调用方给，别在这里读系统时钟）
     * @param lastUserAt    会话里最后一条**用户**消息的时间；0 = 从没说过话
     * @param lastProactive 上次主动开口的时间；0 = 从没主动过
     * @param todayCount    今天已经主动过几次
     * @param enabled       设置开关
     * @param zone          设备时区（判断「几点」用）
     */
    fun shouldSpeak(
        now: Long,
        lastUserAt: Long,
        lastProactive: Long,
        todayCount: Int,
        enabled: Boolean,
        zone: ZoneId,
    ): Boolean {
        if (!enabled) return false
        if (lastUserAt <= 0L) return false

        // 时钟回拨 / 未来时间：差值会是负数。**按「不满足」处理**，
        // 不要让它落进「很久没聊」那一侧 —— 那会在改过系统时间的设备上刷屏。
        if (now < lastUserAt) return false

        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        if (hour < WINDOW_START_HOUR || hour >= WINDOW_END_HOUR) return false

        val gap = TimeUnit.HOURS.toMillis(GAP_HOURS)
        if (now - lastUserAt < gap) return false
        if (lastProactive > 0L && now - lastProactive < gap) return false
        if (todayCount >= DAILY_CAP) return false

        return true
    }

    /**
     * 招呼语：**按皮肤分语气、按间隔分档**（文档 4.1 表格逐字）。
     *
     * 三份皮肤的调性差别全在这三行里 —— 基础款平静、魔丸阴阳、贴吧带脏字。
     * **改文案时三列一起看**，只改一列最容易出现「串味」。
     *
     * ⚠️ 2.38 删了「小女儿」、2.381 删了「MC老登」，两列都**已随皮肤一起删除**，
     *    不要再补回来；枚举里也没有 `DAUGHTER` / `MC` 了，`when` 少两支正是「删干净」的证据。
     *
     * @param skinKey 当前皮肤；`null` = 用户自己造的角色（没有皮肤表）→ 用中性招呼
     */
    fun greeting(skinKey: FishSkinKey?, bucket: Int): String {
        val table = when (skinKey) {
            FishSkinKey.BASE -> listOf("回来啦", "今天还行？", "你可算来了", "好久不见")
            FishSkinKey.MOWAN -> listOf("哟 还知道回来", "今天怎么这么安静", "还以为你把我删了", "哦 想起来了")
            FishSkinKey.TIEBA -> listOf("? 又是你", "? 死了没", "? 还记得路啊", "? 还活着")
            // 用户自建角色没有皮肤表。**不要拿基础款的句子兜底** ——
            // 「回来啦」是大肥鱼的口吻，套到「周野」身上就是串味。
            null -> listOf("回来了", "今天怎么样", "好久没聊了", "好久不见")
        }
        return table[bucket.coerceIn(0, table.size - 1)]
    }

    /** 把「距上次用户发言多久」折成四档。4h 以下不会走到这里（[shouldSpeak] 已挡）。 */
    fun bucketFor(gapMillis: Long): Int {
        val hours = gapMillis.toDouble() / TimeUnit.HOURS.toMillis(1)
        return when {
            hours < 8 -> BUCKET_RECENT
            hours < 24 -> BUCKET_HALF_DAY
            hours < 72 -> BUCKET_DAYS
            else -> BUCKET_LONG
        }
    }

    /**
     * 关心句：按**当前所在的小时**取（文档 4.2 表格）。
     *
     * 同一个小时里固定取一句：`hour` 相同 ⇒ 结果相同，便于测试；
     * 换一个小时才会换句，不会「每次打开都变一句」。
     */
    fun care(hour: Int): String {
        val pool = when (hour) {
            in 7..10 -> listOf("吃早饭没", "今天起这么早")
            in 11..13 -> listOf("吃饭了吗", "别又忘了吃")
            in 14..17 -> listOf("下午犯困吧", "喝口水")
            else -> listOf("今天怎么样", "早点睡")
        }
        // 用小时数当索引：同小时内稳定，跨小时自然轮换。
        return pool[hour % pool.size]
    }

    /**
     * 记忆钩子：把一条记忆**改写成一句问句**（文档 4.3 第 1 条）。
     *
     * 为什么不在记忆里直接存问句：记忆是「值得长期记住的事实」，不是话术；
     * 存问句会让 2.37 的抽取提示词跑偏（模型会开始编寒暄）。
     * 所以这里做一次**很轻的关键词改写**，认不出来就退回一句通用问句 ——
     * 宁可像人随口一提，也不要像汇报。
     */
    fun memoryHook(memoryText: String): String {
        val t = memoryText.trim()
        return when {
            t.contains("考试") -> "对了 你那个考试准备得怎么样了"
            t.contains("面试") -> "对了 面试有消息了吗"
            t.contains("加班") || t.contains("忙") -> "最近还那么忙吗"
            t.contains("生日") || t.contains("纪念日") -> "那个日子我可记着呢"
            t.contains("不喜欢") || t.contains("别叫") -> "上次说的那事我记着了"
            else -> "对了 你之前说的那件事怎么样了"
        }.take(MAX_LINE_CHARS)
    }

    /**
     * 组装这一次要说的 1~3 句（顺序：**招呼 → 状态/记忆 → 关心**）。
     *
     * 文档 4.4 的硬约束都在这里兜住：
     *  * 最多 [MAX_LINES] 条、每条 [MAX_LINE_CHARS] 字（超了截断，不换行）；
     *  * 状态与记忆**互斥**，同一轮只出现一个（两个都报会像汇报工作）；
     *  * 关心放最后，天然留白；后两句是可选的，拿不到就少说一句。
     *
     * @param skinKey   当前皮肤；自建角色传 null
     * @param gapMillis 距上次用户发言多久
     * @param hour      当前小时（0..23）
     * @param state     2.32「此刻」的状态短句（「在摸鱼」）；空串 = 不用
     * @param memoryText 2.37 的最近一条记忆；null/空 = 没有
     */
    fun composeLines(
        skinKey: FishSkinKey?,
        gapMillis: Long,
        hour: Int,
        state: String,
        memoryText: String?,
    ): List<String> {
        val lines = mutableListOf<String>()

        lines += greeting(skinKey, bucketFor(gapMillis))

        // 钩子：记忆优先、状态其次。两个都有时**只用记忆**（同一轮不并列两件事）。
        val hook = memoryText?.takeIf { it.isNotBlank() }?.let(::memoryHook)
            ?: state.takeIf { it.isNotBlank() }
        if (hook != null) lines += hook

        lines += care(hour)

        return lines
            .map { it.trim().take(MAX_LINE_CHARS) }
            .filter { it.isNotEmpty() }
            .take(MAX_LINES)
    }
}
