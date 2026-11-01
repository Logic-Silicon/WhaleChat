package com.naoh.whalechat.data

import java.util.UUID

/**
 * 内置角色「大肥鱼」的**三皮肤**版（2.381 起：五份删掉两份）。
 *
 * ## 内容来源（2.34，2026-11-01；2.38 删「小女儿」、2.381 删「MC老登」）
 *
 * 六块文本**不在这里**，而是集中在 [FishSkins]：三个皮肤
 * （基础款 / 魔丸 / 贴吧老哥）各一份，全部逐字来自
 * `whalechat/docs/大肥鱼-五皮肤人设.md`（那份文档里还留着已删除的
 * 「小女儿」「MC老登」原文，仅作历史存档），文档本身改编自 5 份 AstrBot 人格卡
 * （`大肥鱼.docx` / `魔丸大肥鱼.docx` / `贴吧老哥大肥鱼.docx` /
 * `小女儿大肥鱼v2.docx` / `爱玩MC大肥鱼.docx`）。
 *
 * ## ⚠️ 历史：2.3 曾经是「单一手表版」，不要再改回去
 *
 * 2.3 在这里放的是**一份**「QQ 配置包版大肥鱼 → 手表适配」的人设。2.34 起它被
 * **多皮肤**取代：同一个角色（同一个 [BUILTIN_FISH_ID]）可以有不同的性格，用户
 * 第一次点大肥鱼时挑一个，「重新开始」时可以再换。
 *
 * 所以这里**没有**任何 `FISH_SOUL` / `FISH_STYLE` 之类的常量了 —— 看到有人把它们
 * 加回来，或者把某一份皮肤的文本抄回这个文件，那是在退回到 2.3 的单人设结构。
 * 要改文本，改 [FishSkins]；要加皮肤，也是加在 [FishSkins]。
 *
 * 手表适配删掉的东西（四份皮肤共同遵守，对照 `docs/大肥鱼-五皮肤人设.md` 开头）：
 *  * QQ 群 / 群友 / 群主 / 「群里插话」—— 手表没有群，场景统一成「圆屏里只有你们两个人」；
 *  * 认主 / `{OWNER_QQ}` / 防冒充管理员 —— 手表只有一个用户，没有「别人」可防；
 *  * QQ 个性签名、表情包与语音 —— 没有这个载体，只留文字；
 *  * 「联网查一下再答」—— 联网搜索是用户自己开的开关，人设不能假设它一定开着，
 *    统一改成「拿不准就说不确定，别编」。
 *
 * 保留的东西（用户 2026-11-01 拍板）：
 *  * 「贴吧老哥」的祖安脏话 —— **原样保留**，公开发布的渠道风险由用户承担；
 *  * 梗、黑话、口头禅、「?放句首」「括号里写真心话」这些写法。
 *
 * ⚠️ 2.38（2026-11-01 用户拍板）：原「小女儿」皮肤**整份删除**（「爸爸」称呼 +
 *    成人向调侃）。已选过她的设备由 [com.naoh.whalechat.data.ChatEngine] 里
 *    `fish_daughter_removed_38` 那一次性强覆盖换回基础款。**不要再加回来。**
 *
 * ## 示例对话的说话人视角（抄错这一处，模型会把整段对话学反）
 *
 * 项目约定：`你：` = **用户**说的、`大肥鱼：` = **她**说的。
 * 五份皮肤都照抄成了「大肥鱼：」——**这是源文件的写法，不要按皮肤名去逐个替换**。
 * 真觉得「名字和示例标签不一致」有问题，那是要用户拍板的文案改动，不是实现者顺手改的。
 *
 * ## 为什么 id 是固定的
 *
 * [BUILTIN_FISH_ID] 是写死的 UUID 而不是随机生成：这样「装完自动出现在通讯录」
 * 可以用**幂等**的方式做 —— 每次启动检查「这个 id 在不在」，不在就补上。
 * 用随机 id 的话，用户删掉它之后下次启动又会长回来一条新的，而且会重复长。
 *
 * 而且五个皮肤**必须共用这一个 id**：`ChatEngine.addBuiltinPersona` 按 id 去重，
 * 2026-10-18 踩过「两条同 id 的鱼，删一个消失两个」。换皮肤 = 原地改字段，
 * 不是新建角色。
 */
object BuiltinPersonas {

    /** 大肥鱼的固定 id。**改了它等于让老用户再收到一条新鱼**，别动。 */
    const val BUILTIN_FISH_ID = "6f2a1c40-9d3e-4b57-8a11-7c5e2d9f0a31"

    /**
     * 造一条新的大肥鱼，六块取自 [skin]。
     *
     * [now] 由调用方给，方便测试；[id] 也开放出来是为了「重新加回」时能换一个新 id
     * （用户主动删掉再加回来，应该是一条全新的、没聊过天的鱼，而不是把旧 id 复活
     * 之后跟残留会话对上）。
     *
     * [skin] 默认是基础款 —— 但**「默认皮肤」不等于「用户选过了」**：
     * 用户是否主动选过由 `ChatEngine.fishSkinChosen()` 单独判，两者不能混。
     * 第一次装完种下来的就是基础款，用户点开还没选，会先被带去选择页。
     */
    fun fish(
        skin: FishSkin = FishSkins.DEFAULT,
        now: Long = System.currentTimeMillis(),
        id: String = BUILTIN_FISH_ID,
    ): Persona = Persona(
        id = id,
        name = skin.name,
        tagline = skin.tagline,
        soul = skin.soul,
        style = skin.style,
        rules = skin.rules,
        scenario = skin.scenario,
        examples = skin.examples,
        greeting = skin.greeting,
        // 说明里带上皮肤名，详情页 / 手机端能看出「这是哪一种大肥鱼」。
        sourceDescription = "内置角色：大肥鱼五皮肤（${skin.name}）",
        source = PersonaSource.PRESET,
        createdAt = now,
        lastChatAt = 0L,
    )

    /**
     * 换皮肤用：把 [Persona] 的六块 + name/tagline 原地替换成 [skin]。
     *
     * **保留 `id / createdAt / lastChatAt / source`**（还有 `sourceDescription` 由调用方决定）：
     * 改 id 会话就找不到角色了；改 createdAt/lastChatAt 通讯录排序会跳。
     * 单拎成一个函数是因为「种子覆盖」和「用户主动换皮肤」两条路要保证改的字段完全一致。
     */
    fun withSkin(persona: Persona, skin: FishSkin): Persona = persona.copy(
        name = skin.name,
        tagline = skin.tagline,
        soul = skin.soul,
        style = skin.style,
        rules = skin.rules,
        scenario = skin.scenario,
        examples = skin.examples,
        greeting = skin.greeting,
        sourceDescription = "内置角色：大肥鱼五皮肤（${skin.name}）",
    )

    /** 「重新加回」时用的新 id。 */
    fun freshFishId(): String = UUID.randomUUID().toString()
}
