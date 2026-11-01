package com.naoh.whalechat.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * P1「此刻」一行：**现在几点 / 你们多久没聊了 / 它此刻在干嘛**（2.32）。
 *
 * ## 为什么要有它（P1 的目标）
 *
 * 在这之前，模型只知道「人设 + 最近 30 条消息」，**完全不知道现在几点**。
 * 于是它的默认状态是「永远在线、永远在等你」—— 半夜三点也精神抖擞，好像没别的事干。
 * 这一行就是用来消灭那个感觉的：40~60 字，说明时间、间隔、它自己在忙什么。
 *
 * ## 设计约束（P1 提示词原文）
 *
 *  * 纯本地计算，**零网络**；
 *  * 整行 **≤ 70 个字符**：超了先砍「状态」，绝不砍时间；
 *  * 状态里**不许出现**「等你」「一直在」「随时」这类词 —— 那正是要消灭的感觉；
 *  * 不确定就少写：只给时间和间隔也是有效的。
 *
 * [line] 是**纯函数**（`now` 由调用方注入），方便验证各分支；
 * 真正读取系统时间、选状态、写 prefs 的是 [PresenceStore]。
 */
object Presence {

    /** 状态池按时段取（P1.2 表格逐字）。 */
    fun poolFor(hour: Int): List<String> = when (hour) {
        in 0..5 -> listOf("还没睡", "在熬夜刷手机", "有点迷糊")
        in 6..9 -> listOf("刚睡醒", "在吃早饭", "还没完全醒")
        in 10..13 -> listOf("在摸鱼", "在吃午饭", "在发呆")
        in 14..17 -> listOf("在摸鱼", "在收拾屋子", "在发呆", "刚睡醒")
        in 18..20 -> listOf("在吃晚饭", "刚吃完饭", "在瘫着")
        else -> listOf("瘫着刷手机", "有点困了", "在发呆")
    }

    /** 整行最长多少字符（含标点）。超了就丢状态，保留时间和间隔。 */
    const val MAX_CHARS = 70

    /**
     * 拼出「【此刻】HH:mm 周X。……你们多久没聊。你<状态>。」这一行。
     *
     * @param now 调用方给的「现在」（**不要**在函数里读系统时间，否则测不了）
     * @param lastChatAt 这个角色会话里最后一条**用户**消息的时间（毫秒）；null = 没聊过
     * @param state 状态短句，例如「在发呆」「刚吃完饭」；传空串就只写时间和间隔
     */
    fun line(now: LocalDateTime, lastChatAt: Long?, state: String): String {
        val head = "【此刻】" + timeText(now) + "。"
        val gap = gapText(now, lastChatAt)
        val base = head + gap

        // 状态里出现「等你 / 一直在 / 随时」这种词就直接丢掉 —— 它是这一行要消灭的东西，
        // 池子是我们自己写的、不会中，但把判据放在这里，防止以后有人往池子里加词时忘掉。
        val cleanState = state.trim().takeUnless { forbidden(it) }.orEmpty()
        if (cleanState.isEmpty()) return base

        val full = base + "你" + cleanState + "。"
        return if (full.length <= MAX_CHARS) full else base
    }

    /** `22:47 周四`。周几用「一二三四五六日」。 */
    fun timeText(now: LocalDateTime): String {
        val hh = now.hour.toString().padStart(2, '0')
        val mm = now.minute.toString().padStart(2, '0')
        val week = "一二三四五六日"[now.dayOfWeek.value - 1]
        return "$hh:$mm 周$week"
    }

    /** 间隔文案（P1.2 表格逐字）。 */
    fun gapText(now: LocalDateTime, lastChatAt: Long?): String {
        if (lastChatAt == null || lastChatAt <= 0L) return "你们第一次聊天。"
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val diff = nowMillis - lastChatAt
        if (diff < 0L) return "你们刚刚还在聊。"  // 时钟回拨/未来时间：按「刚刚」算，别显示负数
        return when {
            diff < TimeUnit.HOURS.toMillis(1) -> "你们刚刚还在聊。"
            diff < TimeUnit.HOURS.toMillis(24) -> "你们今天早些时候聊过。"
            diff < TimeUnit.HOURS.toMillis(48) -> "你们昨天聊过。"
            else -> "你们上次聊天是 ${diff / TimeUnit.DAYS.toMillis(1)} 天前。"
        }
    }

    private fun forbidden(state: String): Boolean =
        state.contains("等你") || state.contains("一直在") || state.contains("随时")
}

/**
 * 状态的**选择与保持**（2–3 小时不变），落盘在 `SharedPreferences("whalechat_scene")`。
 *
 * 为什么不每次现挑：刚说「在吃晚饭」，下一句又变成「刚睡醒」，比没有状态更假。
 * 所以选一次、记住选的时间，`now - since > 2h ± 30min` 才重挑。
 *
 * ⚠️ 失败静默（P1 红线 7）：选状态/读 prefs 出任何问题都不抛、不影响发送 ——
 * [lineFor] 内部自己兜住，最坏情况只少一句状态。
 */
class PresenceStore(
    context: Context,
    private val random: Random = Random(),
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("whalechat_scene", Context.MODE_PRIVATE)

    /** 给一次发送拼「此刻」一行；任何异常都返回「只有时间与间隔」的最短版本，或空串。 */
    fun lineFor(now: LocalDateTime, lastChatAt: Long?): String = runCatching {
        Presence.line(now, lastChatAt, stateFor(now))
    }.getOrElse { runCatching { Presence.line(now, lastChatAt, "") }.getOrDefault("") }

    /** 当前时段的状态；没选过、或已超过 2h ± 30min 就重挑一次并落盘。 */
    fun stateFor(now: LocalDateTime): String {
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val current = prefs.getString(KEY_STATE, null)
        val since = prefs.getLong(KEY_SINCE, 0L)
        if (!current.isNullOrBlank() && since > 0L) {
            // ±30 分钟抖动：避免所有人/所有时段的切换点都卡在同一个整点
            val ttl = TTL_MILLIS + (random.nextInt(JITTER_MILLIS * 2) - JITTER_MILLIS)
            val age = nowMillis - since
            if (age in 0..ttl) return current
        }
        val pool = Presence.poolFor(now.hour)
        val picked = pool[random.nextInt(pool.size)]
        prefs.edit().putString(KEY_STATE, picked).putLong(KEY_SINCE, nowMillis).apply()
        return picked
    }

    private companion object {
        const val KEY_STATE = "state"
        const val KEY_SINCE = "state_since"

        /** 保持 2 小时 ± 30 分钟。 */
        const val TTL_MILLIS: Long = 2L * 60 * 60 * 1000
        const val JITTER_MILLIS: Int = 30 * 60 * 1000
    }
}
