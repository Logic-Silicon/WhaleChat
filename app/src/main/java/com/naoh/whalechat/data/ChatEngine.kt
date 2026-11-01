package com.naoh.whalechat.data

import android.content.Context
import android.content.SharedPreferences
import com.naoh.whalechat.net.DeepSeekClient
import com.naoh.whalechat.net.DeepSeekException
import com.naoh.whalechat.net.SearchClient
import com.naoh.whalechat.net.SearchHit
import com.naoh.whalechat.net.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 应用级会话引擎。
 *
 * 放在 Application 作用域而不是 ViewModel 里，是为了让「生成」这件事能跨页面活着：
 * 在聊天页发出问题后可以直接返回首页，回答照样流式写进会话，回来就是完整的。
 *
 * 线程约定：所有 [_conversations] 的读写都发生在主线程（UI 调用 + 主线程作用域），
 * 所以不需要额外加锁；真正的磁盘写才丢给 IO 作用域。
 */
object ChatEngine {

    private lateinit var store: ConversationStore
    private lateinit var personaStore: PersonaStore
    private val client = DeepSeekClient()

    /** 联网搜索（1.983）。独立于 [client]：它是一次性 HTTP 请求，不是流。 */
    private val searchClient = SearchClient()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val personaWriteLock = Mutex()

    lateinit var settings: SettingsStore
        private set

    /** 余额缓存。独立于 [settings]（独立 prefs），见 [UsageStore] 的注释。 */
    lateinit var usage: UsageStore
        private set

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** 通讯录（chat 模式的人）。**它的存亡与会话无关**，见 [Persona] 的注释。 */
    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    /**
     * 「基于预设创建」时正在编辑的那四个字段。
     *
     * 为什么放在这里而不是 `rememberSaveable`：预设详情页 → 文字输入页 → 返回，中间
     * 隔了一次导航，值得跨页面活着；而它又是个**纯页面级草稿**，不进文件、不进历史。
     * 所以按 `notice` 那种「一次性状态」的路子放引擎里，用完由页面清掉。
     *
     * 它跟 `deleted` 那种内存簿记完全是两回事：这里存的是用户正在打字的界面状态，
     * 没有它只是要重敲一遍，不会让任何判定出错。
     */
    private val _presetDraft = MutableStateFlow<PresetDraft?>(null)
    val presetDraft: StateFlow<PresetDraft?> = _presetDraft.asStateFlow()

    /**
     * 「自己说一句」那条路上，用户在输入页敲下的那句描述。
     *
     * 为什么不让输入页直接把文字带进等待页：`InputScreen` 的规矩是「**离开 = 提交**」，
     * 而它离开时唯一的动作通道是 `onDispose`，那里**只能写数据、不能做导航**
     * （onDispose 阶段再 popBackStack / navigate 是未定义行为，代码里早写着这条）。
     * 所以文字先落到这儿，由**新建页**观察到它再往前导航 —— 这也顺带把
     * 「划走输入页 = 提交」和「划走等待页 = 取消」这两条相反的规矩分得干干净净。
     *
     * 和 [presetDraft] 一样是页面级草稿，不落盘。
     */
    private val _personaDraft = MutableStateFlow<String?>(null)
    val personaDraft: StateFlow<String?> = _personaDraft.asStateFlow()

    /** 正在流式输出的会话 id；null 表示空闲 */
    private val _streamingId = MutableStateFlow<String?>(null)
    val streamingId: StateFlow<String?> = _streamingId.asStateFlow()

    /** 一次性提示（缺 Key、没有可重试内容等），UI 展示后调 consumeNotice() 清掉 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var streamJob: Job? = null

    /**
     * 被**明确删掉、并且当时已经有过 DS 回复**的会话 id（[deleteConversation] / [clearAll]）。
     *
     * 这是「这个对话已经不在了」这句话唯一站得住的依据。对话页以前是拿
     * 「在当前列表里找不到」当依据的，那条判据太粗 —— 「找不到」的原因至少有三条，
     * 只有一条真的该提示：
     *  1. （本集合）被删除、被清空 —— 该提示、该退回去；
     *  2. 刚点「＋」进来、DS 还没回过话的空壳 —— 用户只是进出了一下，
     *     在他心里**根本不存在「一个对话」**，没有任何东西「消失」过；
     *  3. **重建后的空窗期** —— 这条最阴：Wear 的边缘滑动（SwipeDismissableNavHost）
     *     会让这个页面短暂重建，重建那一瞬间 `conversations` 还没读回来、列表是空的，
     *     于是「找不到」成立、弹一句「已经不在了」，而实际上用户只是正常返回了一次。
     *     屏幕上的表现就是**返回首页时中间劈下来一句红卡片**。
     *
     * 所以判据从「找不到」换成「**被删过**」：要正面证据，不要缺席证据。
     *
     * 入册还多一道闸：**只有 [Conversation.hasReply] 的会话才登记** ——
     * 规则是「有 ds 回复才算新对话」。第 2 类之所以要单独拎出来
     * 正是因为它最容易被漏掉：点「＋」会真的往列表里塞一条壳，
     * 万一它在别的路径上被清掉（比如从对话页绕去设置里点了「清空所有对话」），
     * 光看「被删过」是拦不住它的。拿「DS 回过话没有」当闸门，它就被挡在外面了 ——
     * 而且这条规则不依赖任何内存记账，消息本身就是证据。
     */
    private val deleted = mutableSetOf<String>()

    /**
     * 角色侧的 SharedPreferences（2.1 新增，与设置那套 prefs 分开）。
     *
     * 存三样东西：
     *  * [KEY_FISH_DEFAULTS_APPLIED_23] / [KEY_FISH_SKINS_APPLIED_34] —— 两次「整份换人设」
     *    的一次性强覆盖凭据，各只跑一次；
     *  * [KEY_FISH_SKIN_KEY] / [KEY_FISH_SKIN_CHOSEN] —— 用户当前选中的皮肤、以及
     *    「他到底主动选过没有」（2.34 首启选择页的判据，见 [fishSkinChosen]）。
     *
     * ⚠️ 这些键**只在 [seedBuiltinPersonasIfNeeded] 和 [applyFishSkin] 里读写**，
     * 不进任何 personas 落盘路径；必须真正落盘（而不是内存），否则每次冷启动都会把
     * 用户的人设改回去。
     */
    private lateinit var personaPrefs: SharedPreferences

    /**
     * 「此刻」状态的选择与保持（2.32 / P1），见 [PresenceStore]。
     * 只有角色会话用得到；Ask 模式那条链路不碰它。
     */
    private lateinit var presence: PresenceStore

    /**
     * 长期记忆（2.37 / P3）：一张本地的「要点卡片」，落盘 `files/memories.json`。
     *
     * 全量常驻内存（上限 40 条 × 40 字，最多 1.6KB），读写都便宜，
     * 所以**没有加状态流**：它只影响下一次发送时拼什么 prompt，界面不直接读它。
     */
    private lateinit var memoryStore: MemoryStore

    /** 记忆内存副本。所有增删都在主线程做（与 `_personas` 同一套约定）。 */
    private var memories: List<MemoryItem> = emptyList()

    /**
     * 记忆抽取的记账 prefs（`whalechat_memory`），与设置、角色两套 prefs **分开**。
     *
     * 只存一个键：`extracted_<conversationId>` = 上次抽取时**用户消息**的条数。
     * 用它算「这 6 条是不是新的」；不落盘的话每次冷启动都会重复抽一遍，白花钱。
     */
    private lateinit var memoryPrefs: SharedPreferences

    /** 大肥鱼 2.3 强覆盖是否已经跑过。落盘标记位，见 [KEY_FISH_DEFAULTS_APPLIED_23]。 */
    private var fishDefaultsApplied23: Boolean
        get() = ::personaPrefs.isInitialized &&
            personaPrefs.getBoolean(KEY_FISH_DEFAULTS_APPLIED_23, false)
        set(value) {
            if (::personaPrefs.isInitialized) {
                personaPrefs.edit()
                    .putBoolean(KEY_FISH_DEFAULTS_APPLIED_23, value)
                    .apply()
            }
        }

    /**
     * 「老用户还没决定：保留原人设，还是换个新性格」的标记（2.39）。
     *
     * 只有一种情况会置位：升级上来时**已经和这条鱼聊过**、且从未主动选过皮肤
     * （见 `seedBuiltinPersonasIfNeeded` 里 `preserveLegacy` 那段）。
     * 置位期间人设**保持原样**，第一次点鱼会进选择页，最上面多一张
     * 「保留原来的大肥鱼」的卡。
     *
     * 用户在页面上做了任何选择（保留 / 换皮肤）都会把它清掉 —— 它只该出现一次。
     */
    private var fishUpgradeChoicePending: Boolean
        get() = ::personaPrefs.isInitialized &&
            personaPrefs.getBoolean(KEY_FISH_UPGRADE_CHOICE_PENDING, false)
        set(value) {
            if (::personaPrefs.isInitialized) {
                personaPrefs.edit()
                    .putBoolean(KEY_FISH_UPGRADE_CHOICE_PENDING, value)
                    .apply()
            }
        }

    /**
     * 是不是「老用户还没决定」（2.39）。UI 用它决定：
     *  * 点大肥鱼先不直接进会话，而是进选择页；
     *  * 选择页标题改成「大肥鱼更新了」，并在最上面加一张「保留原来的大肥鱼」。
     */
    fun fishUpgradeChoicePending(): Boolean = fishUpgradeChoicePending

    /**
     * 老用户选择「保留原来的大肥鱼」：**人设一个字都不改**，只把「选过皮肤」记上，
     * 然后进入会话。
     *
     * ⚠️ 不调 applyFishSkin、不调 restartPersona：前者会换六块，后者会清消息。
     * 这里唯一的副作用是 prefs 里的两个布尔（`chosen=true`、`pending=false`）。
     *
     * @return 会话 id（没有就现建一条，开场白仍是原来那句）
     */
    fun keepLegacyFish(): String? {
        val persona = findPersona(BuiltinPersonas.BUILTIN_FISH_ID) ?: return null
        // key 归一到「当前皮肤」：老用户可能根本没有这个键，写一个能让读取路径稳定。
        writeFishSkin(currentFishSkin(), chosen = true)
        fishUpgradeChoicePending = false
        return openPersonaChat(persona.id)
    }

    /**
     * 「MC老登皮肤已下架」这次一次性迁移是否跑过（2.381）。见 [KEY_FISH_MC_REMOVED_381]。
     *
     * 和 [fishDaughterRemoved38] 同一个模式：already-落盘的六块文本不会因为删代码而消失，
     * 所以必须主动覆盖一次；标记位保证只跑一次。
     */
    private var fishMcRemoved381: Boolean
        get() = ::personaPrefs.isInitialized &&
            personaPrefs.getBoolean(KEY_FISH_MC_REMOVED_381, false)
        set(value) {
            if (::personaPrefs.isInitialized) {
                personaPrefs.edit()
                    .putBoolean(KEY_FISH_MC_REMOVED_381, value)
                    .apply()
            }
        }

    /** 大肥鱼「2.34 换成五皮肤」那次强覆盖是否已经跑过。见 [KEY_FISH_SKINS_APPLIED_34]。 */
    private var fishSkinsApplied34: Boolean
        get() = ::personaPrefs.isInitialized &&
            personaPrefs.getBoolean(KEY_FISH_SKINS_APPLIED_34, false)
        set(value) {
            if (::personaPrefs.isInitialized) {
                personaPrefs.edit()
                    .putBoolean(KEY_FISH_SKINS_APPLIED_34, value)
                    .apply()
            }
        }

    /**
     * 「小女儿皮肤已下架」这次一次性迁移是否跑过（2.38）。见 [KEY_FISH_DAUGHTER_REMOVED_38]。
     *
     * 为什么需要一个标记位：这个迁移做的是「把已经落盘的六块文本覆盖回基础款」。
     * 不记标记位的话，**每次冷启动都会再覆盖一次** —— 用户之后手动选/改过的设定
     * 会被反复抹掉（和 2.3 强覆盖当初踩的是同一个坑）。
     */
    private var fishDaughterRemoved38: Boolean
        get() = ::personaPrefs.isInitialized &&
            personaPrefs.getBoolean(KEY_FISH_DAUGHTER_REMOVED_38, false)
        set(value) {
            if (::personaPrefs.isInitialized) {
                personaPrefs.edit()
                    .putBoolean(KEY_FISH_DAUGHTER_REMOVED_38, value)
                    .apply()
            }
        }

    /**
     * 用户有没有**主动选过**皮肤。
     *
     * ⚠️ 这是首启选择页唯一的判据，**不能**用「prefs 里有 fish_skin_key」代替：
     * 那个键在强覆盖时会被写成 `base`，可用户一次都没选过 —— 用键存在性判，
     * 老用户升级上来会被直接放进基础款会话，永远看不到五皮肤。
     *
     * `personaPrefs` 还没初始化（`init` 之前）时返回 false：那种调用点本来也不该有，
     * 兜底成「没选过」比崩掉好。
     */
    fun fishSkinChosen(): Boolean = ::personaPrefs.isInitialized &&
        personaPrefs.getBoolean(KEY_FISH_SKIN_CHOSEN, false)

    /**
     * 用户当前皮肤。读不出来（没选过 / 键被清掉 / 值不认识）时用 [FishSkins.DEFAULT]。
     *
     * **这不代表「已经选过」**：种子和「重新加回」用它决定造哪一条鱼，
     * 是否弹选择页仍然只看 [fishSkinChosen]。
     */
    fun currentFishSkin(): FishSkin = FishSkins.fromPrefs(
        if (::personaPrefs.isInitialized) {
            personaPrefs.getString(KEY_FISH_SKIN_KEY, null)
        } else {
            null
        },
    ) ?: FishSkins.DEFAULT

    /** 写皮肤状态。[chosen] 单独给：强覆盖时要把「选过」打回 false。 */
    private fun writeFishSkin(skin: FishSkin, chosen: Boolean) {
        if (!::personaPrefs.isInitialized) return
        personaPrefs.edit()
            .putString(KEY_FISH_SKIN_KEY, skin.key.prefsValue)
            .putBoolean(KEY_FISH_SKIN_CHOSEN, chosen)
            .apply()
    }

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = ConversationStore(File(context.filesDir, "conversations.json"))
        personaStore = PersonaStore(File(context.filesDir, "personas.json"))
        personaPrefs = context.getSharedPreferences("whalechat_persona", Context.MODE_PRIVATE)
        presence = PresenceStore(context)
        settings = SettingsStore(context)
        usage = UsageStore(context)
        _conversations.value = store.load().sortedByDescending { it.sortKey }
        _personas.value = personaStore.load().sortedByDescending { it.sortKey }
        seedBuiltinPersonasIfNeeded()

        // ★ 2.37：长期记忆。顺序在 personas 之后 —— 孤儿清理要知道「现在有哪些角色」。
        memoryStore = MemoryStore(File(context.filesDir, "memories.json"))
        memoryPrefs = context.getSharedPreferences("whalechat_memory", Context.MODE_PRIVATE)
        memories = memoryStore.load()
        val alivePersonaIds = _personas.value.map { it.id }.toSet()
        val cleaned = MemoryExtract.cleanupOrphans(memories, alivePersonaIds)
        if (cleaned.size != memories.size) {
            // 孤儿 = 角色已经被删、但 memories.json 里还留着（进程被杀等边界）。
            // 删完立刻写回；失败静默 —— 下次启动还会再清一次。
            memories = cleaned
            io.launch { runCatching { memoryStore.save(cleaned) } }
        }
    }

    /**
     * 装完就带一条「大肥鱼」。
     *
     * ## 为什么在 [init] 里做，而不是 `if (personas.isEmpty())`
     *
     * 判据是**「这个固定 id 在不在」**，不是「列表是不是空的」。
     * 后者有两个致命后果：
     *  1. 用户把大肥鱼删掉之后，只要通讯录里还有别的角色，它就永远不回来 ——
     *     于是「内置联系人」这件事变成了「只在第一次装的时候成立」；
     *  2. 用户把**所有**角色删光，它又会自己长回来 —— 用户会觉得删不掉。
     *
     * 用固定 id 判存在性，就变成了一个**幂等**的补种：每次冷启动检查一遍，
     * 不在就补，在就什么都不做。用户删掉它之后不会在**同一次会话里**复活
     * （我们只在 init 跑），下次启动才补 —— 这个时间差是有意的：
     * 立刻复活会让人以为删除没生效。要彻底不要它，目前只能删完就习惯它再出现一次，
     * 这是「内置」这件事本身的代价，写在这里免得后来者以为是 bug。
     *
     * ## 为什么 `createdAt` 取 `now` 而不是编译期常量
     *
     * 排序键见 [Persona.sortKey]：没聊过的角色按 `createdAt` 排。给一个编译期时间戳
     * 会让它在「刚装完」的那一刻就沉到列表最底 —— 用户会以为没装上。
     *
     * ## 2.34：种哪一条鱼由「上次选的皮肤」决定
     *
     * 补种时用 [currentFishSkin]（prefs 里的 `fish_skin_key`），读不到就用基础款。
     * 但**「用了哪个皮肤」和「用户选过没有」是两件事**：种下来的这条鱼可能是基础款，
     * 而 `fish_skin_chosen` 仍是 false —— 那种情况下用户点开它，先被带去选择页
     * （判据在 [fishSkinChosen]）。
     *
     * ## 落盘时机
     *
     * 直接调 [commitPersonas]（异步写），**不阻塞启动**。这一步失败也只是下次启动再补一遍，
     * 没有数据损失 —— 所以不需要 [savePersonaFromPhone] 那种同步写。
     */
    private fun seedBuiltinPersonasIfNeeded() {
        val existing = findPersona(BuiltinPersonas.BUILTIN_FISH_ID)
        if (existing == null) {
            _personas.value = _personas.value + BuiltinPersonas.fish(skin = currentFishSkin())
            // ⚠️ 种下去的同时要把两个「老用户强覆盖」的标记位记上。
            //
            // 不记的后果（2.34 实测踩到）：新装的用户第一次启动走这条路、直接 return，
            // 标记位仍是 false；他选完皮肤（比如魔丸）之后**第二次冷启动**，
            // 下面那两个 if 就会把他当成「2.33 升上来的老用户」，把皮肤冲回基础款、
            // 把 `fish_skin_chosen` 打回 false —— 表现是「选的皮肤重启一次就变回去了，
            // 而且又被弹回选择页」。所以「新种一条鱼」本身就是「已经是最新状态」的证据。
            //
            // 皮肤 prefs **不动**：这里可能是「用户选过、之后删了鱼再加回来」，
            // 那种情况要沿用他上次选的皮肤（currentFishSkin()），不能打回默认。
            fishDefaultsApplied23 = true
            fishSkinsApplied34 = true
            // 2.38：新种的鱼不可能是「小女儿」，也把下架迁移标记为已完成。
            fishDaughterRemoved38 = true
            // 2.381：同理，「MC老登」也不会被新种出来。
            fishMcRemoved381 = true
            commitPersonas()
            return
        }
        // ★ 2.39：老用户可以选择「保留原人设」。
        //
        // 背景（用户 2026-11-01 反馈）：升级包对老用户做「整份换成新皮肤」，
        // 用户会觉得「我原来是那条鱼，现在被动换掉了」，还容易误以为聊天记录也被覆盖。
        // 所以：只要**已经和这条鱼聊过**（会话里至少有一条用户消息），
        // 且**从未主动选过皮肤**，就**不覆盖人设**，改为第一次点她时给一张
        // 「保留原来的大肥鱼」的卡（见 `FishSkinScreen`）。
        //  * 选「保留」→ 什么都不改，只把 chosen 置 true（以后不再弹）；
        //  * 选新皮肤 → 走 applyFishSkin，把六块换掉（那是用户自己的选择）。
        // ⚠️ 两个覆盖的标记位照样记上：否则下次冷启动又会把它们当成「没跑过」。
        // ⚠️ 聊天记录本来就一个字都不动；这一步只是让「人设」也不动。
        val chattedWithFish = personaConversation(BuiltinPersonas.BUILTIN_FISH_ID)
            ?.messages?.any { it.role == ChatRole.USER } == true
        val preserveLegacy = chattedWithFish && !fishSkinChosen()
        if (preserveLegacy) {
            fishDefaultsApplied23 = true
            fishSkinsApplied34 = true
            fishUpgradeChoicePending = true
            // 人设没动，不需要 commitPersonas()。
        } else {
            // ★ 2.3：一次性强行把人设换成新版（用户 2026-10-31 拍板「以 QQ 配置包那份为主」）。
            //    2.1 的旧「定稿」整份作废 —— 2.3 把大肥鱼整份换成了「手表版」人设，
            //    老用户装机后需要重新覆盖一次，所以标记位从 `_21` 升到 `_23`。
            //    不做「只覆盖没改过的」判据 —— 用户改过的名字也会被覆盖回「大肥鱼」
            //    （这会一次性抹掉用户给大肥鱼改过的名字；2.1 也是这么做的，已获用户同意）。
            //    ⚠️ 只跑一次：标记位落盘（prefs）之后永不再覆盖，否则用户每改一次名字、
            //    重启就被抹掉一次。标记位在 whalechat_persona prefs 里读写，不进任何落盘路径。
            //    ⚠️ 覆盖时保留 existing.id / createdAt / lastChatAt（会话靠 personaId 找角色，
            //    通讯录靠 lastChatAt 排序）；**聊天记录（conversations.json）一个字都不动**。
            if (!fishDefaultsApplied23) {
                _personas.value = _personas.value.map {
                    if (it.id == BuiltinPersonas.BUILTIN_FISH_ID) {
                        BuiltinPersonas.fish(id = existing.id)
                            .copy(createdAt = existing.createdAt, lastChatAt = existing.lastChatAt)
                    } else {
                        it
                    }
                }
                fishDefaultsApplied23 = true
                commitPersonas()
            }
            // ★ 2.34：把 2.3 的「单份手表版」整份换成**基础款皮肤**，并把
            //    `fish_skin_chosen` 打回 false —— 于是 2.33 升上来的老用户第一次点大肥鱼
            //    会走一次选择流程（不这么做，他永远只会看到基础款）。
            //
            //    ⚠️ 顺序**必须**在 2.3 那一段之后：2.3 的覆盖写的是「手表版」，
            //       2.34 的覆盖写的是「基础款皮肤」。反过来就又把基础款盖回手表版了。
            //    ⚠️ 用 [BuiltinPersonas.withSkin] 而不是 `fish()`：保留 id / createdAt /
            //       lastChatAt / source，通讯录排序和会话关联都不会跳。
            //    ⚠️ 聊天记录不动。用户之前和「手表版大肥鱼」聊的内容原样留着 ——
            //       人设换了不等于历史要删，「重新开始」才是清空历史的那条路。
            if (!fishSkinsApplied34) {
                _personas.value = _personas.value.map {
                    if (it.id == BuiltinPersonas.BUILTIN_FISH_ID) {
                        BuiltinPersonas.withSkin(it, FishSkins.DEFAULT)
                    } else {
                        it
                    }
                }
                fishSkinsApplied34 = true
                writeFishSkin(FishSkins.DEFAULT, chosen = false)
                commitPersonas()
            }
        }
        // ★ 2.38：「小女儿」皮肤下架（用户 2026-11-01 拍板）。
        //
        // 只删代码是不够的：已经在用电设备的 `personas.json` 里，六块文本是**当时复制进去的**，
        // 删掉 [FishSkins] 里那份定义不会动到盘上的旧文本。所以这里做一次性的强覆盖：
        //  * 判据两条 —— 存盘皮肤 key 是 `daughter`，**或者**角色名就是「小女儿」。
        //    两道都要：用户可能在手机上把名字改过（第一条还在），
        //    也可能之前切到了别的皮肤但名字没改回来（第二条兜底）。
        //  * 覆盖成**基础款**（`FishSkins.DEFAULT`），保留 id / createdAt / lastChatAt；
        //  * `chosen` 保持原值：已经选过皮肤的人不该被弹回选择页；
        //  * 换完把 key 写成 `base`，避免 prefs 里永远留着一个已删除的枚举值。
        //  * ⚠️ 只跑一次（标记位），否则用户之后改的设定会被每次冷启动抹掉。
        if (!fishDaughterRemoved38) {
            val storedKey = personaPrefs.getString(KEY_FISH_SKIN_KEY, null)
            val isDaughter = storedKey == "daughter" ||
                findPersona(BuiltinPersonas.BUILTIN_FISH_ID)?.name == "小女儿"
            if (isDaughter) {
                _personas.value = _personas.value.map {
                    if (it.id == BuiltinPersonas.BUILTIN_FISH_ID) {
                        BuiltinPersonas.withSkin(it, FishSkins.DEFAULT)
                    } else {
                        it
                    }
                }
                writeFishSkin(FishSkins.DEFAULT, chosen = fishSkinChosen())
                commitPersonas()
            }
            fishDaughterRemoved38 = true
        }
        // ★ 2.381：「MC老登」皮肤下架（用户 2026-11-01 拍板）。
        //
        // 和「小女儿」同一个处理：只删代码不够，盘上已经复制进去的六块文本要主动覆盖。
        // 判据同样是两道（存盘 key / 角色名），覆盖成基础款，chosen 保持原值。
        if (!fishMcRemoved381) {
            val storedKey = personaPrefs.getString(KEY_FISH_SKIN_KEY, null)
            val isMc = storedKey == "mc" ||
                findPersona(BuiltinPersonas.BUILTIN_FISH_ID)?.name == "MC老登"
            if (isMc) {
                _personas.value = _personas.value.map {
                    if (it.id == BuiltinPersonas.BUILTIN_FISH_ID) {
                        BuiltinPersonas.withSkin(it, FishSkins.DEFAULT)
                    } else {
                        it
                    }
                }
                writeFishSkin(FishSkins.DEFAULT, chosen = fishSkinChosen())
                commitPersonas()
            }
            fishMcRemoved381 = true
        }
    }

    /**
     * 落一条「成品预设」角色（目前只有大肥鱼），并返回会话 id。
     *
     * ## ⚠️ 为什么不能直接调 [createPersonaChat]
     *
     * [createPersonaChat] 是**无条件追加**：它假定调用方给的是一条新角色
     * （走生成管道时确实如此 —— 每次 [PersonaFactory.generate] 都给一个新 UUID）。
     *
     * 但成品预设的 id 是**写死的常量**（[BuiltinPersonas.BUILTIN_FISH_ID]）。
     * 2026-10-18 实测踩到：在预设页把「添加」按两次，`personas.json` 里就出现
     * **两条 id 一模一样的大肥鱼**，通讯录里两张同名卡片，点哪张都进同一条会话，
     * 删掉一张另一张还在 —— 因为删除是按 id 过滤的，`filterNot` 会把两条一起删掉，
     * 于是「删一个消失两个」。
     *
     * 所以这里按 id 去重：**已经在通讯录里就直接复用它的会话**，不新增。
     * 语义上也更对 —— 「添加」一个已经在通讯录里的人，本来就该是「进 TA 的会话」。
     */
    fun addBuiltinPersona(persona: Persona): String {
        findPersona(persona.id)?.let { existing ->
            return openPersonaChat(existing.id).orEmpty()
        }
        return createPersonaChat(persona)
    }

    /**
     * 「重新加回」内置的大肥鱼。
     *
     * 和 [seedBuiltinPersonasIfNeeded] 的区别只有一个，但很关键：**用新 id**。
     *
     * 用户是主动再要一条的，那就该是一条全新的、没聊过天的鱼 —— 沿用固定 id 的话，
     * 万一他的旧会话还躺在 `conversations.json` 里（删角色会连会话一起删，
     * 但极端情况下可能有残留），新角色会**接上那段旧对话**，看起来像见了鬼。
     *
     * ⚠️ 注意：**预设页的「添加」不走这里**（那条路走 [addBuiltinPersona]，
     * 用固定 id + 去重）。这个方法目前没有调用点，是留给「用户删掉之后想再加一条
     * 全新的」那个场景的 —— 到那时再接一个入口，别直接把它删掉。
     */
    fun addBuiltinFish(): String {
        findPersona(BuiltinPersonas.BUILTIN_FISH_ID)?.let { existing ->
            return openPersonaChat(existing.id).orEmpty()
        }
        return createPersonaChat(
            BuiltinPersonas.fish(
                skin = currentFishSkin(),
                id = BuiltinPersonas.freshFishId(),
            ),
        )
    }

    fun find(id: String?): Conversation? =
        id?.let { target -> _conversations.value.firstOrNull { it.id == target } }

    /**
     * 这条会话是不是被**明确删掉了**？这是「已经不在了」唯一站得住的依据。
     *
     * 注意两件事：
     *  * 它刻意不看当前列表里有没有 —— 「列表里没有」至少有三条原因，
     *    只有「被删过」这一条该提示用户（理由见 [deleted]）；
     *  * 它也不可能对一条**从没被 DS 回复过**的会话为真 —— 那种会话压根不会进
     *    这个集合（见 [deleteConversation]）。
     *
     * 被删过的会话不会复活（id 是 UUID，不会重用），所以这个集合只增不减，
     * 不清理也不会误伤。进程重启后这个集合本来就空了，导航栈里恢复出来的那条 id
     * 就落回「不在集合里」的一侧，[isGone] 为 false。
     */
    fun wasDeleted(id: String?): Boolean = id != null && id in deleted

    /**
     * 对话页该不该为「看不到内容」而自己退回去？
     *
     * 只有**被明确删掉、且那时已经有过 DS 回复**才算数。其余情况一律静默 ——
     * 点「＋」进来的空壳、页面重建的空窗期，用户都什么都没做错，
     * 不该被一句「已经不在了」吓一下。
     *
     * 代价说清楚：进程被回收、导航栈恢复出一个从没落过盘的 id 时，这一页会停在空白
     * 而不是自动退回。这是刻意接受的一侧 —— 那种情况下屏幕上的东西和刚点「＋」
     * 进来长得一模一样，返回一下就走了；比给每个正常返回的人都劈一张红卡片好得多。
     */
    fun isGone(id: String?): Boolean = wasDeleted(id)

    val isBusy: Boolean get() = _streamingId.value != null

    fun hasApiKey(): Boolean = settings.value.apiKey.isNotBlank()

    // ------------------------------------------------------------------ 会话

    /**
     * 给首页那个「＋」一个会话 id，**刻意不落盘**。
     *
     * 会话真正的诞生点是第一句话（见 [send]）。如果在这里就写进历史，用户在空白
     * 会话页什么都没问就返回，列表里就会多出一条「新对话」—— 点几次＋就攒几条，
     * 久而久之历史列表被一串空壳占满。所以这里只在内存里放一个占位，
     * 让对话页有个能渲染的对象；没说话的话，[discardEmpty] 会把内存里这份也收走。
     *
     * 这个空壳**还不算一条对话**：[Conversation.hasReply] 为 false。
     * 它不出现在首页列表里，也不该在对话页上被当成「已经不在了」的对象 ——
     * 这两件事都是靠 `hasReply` 这一条线判的，不需要额外记账。
     */
    fun createConversation(): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        _conversations.value = listOf(
            Conversation(id = id, createdAt = now, updatedAt = now)
        ) + _conversations.value
        return id
    }

    /**
     * 丢掉所有还没说过话的会话（内存里的空壳）。
     *
     * 首页在每次进入时调用一次：从空白会话返回首页，那条占位就该消失，
     * 而不是留在内存里等下一次「＋」再叠一层。
     *
     * 对正在流式输出的会话是安全的 —— 它至少已经有一条用户消息，
     * `messages.isEmpty()` 不成立，不会被误伤。
     *
     * ⚠️ 2.1 的「删除此信息」把会话删成零条消息后也依赖这条收尾 +
     * `commit()` 里「空会话不写进文件」的过滤（[saveConversationsNow]）：
     * 两个空壳来源用同一个口径，不在删除函数里另造一套。
     */
    fun discardEmpty() {
        _conversations.value = _conversations.value.filterNot { it.messages.isEmpty() }
    }

    fun deleteConversation(id: String) {
        if (_streamingId.value == id) stop()
        // 只有 DS 回过话的会话才登记进「删除名单」—— 见 [deleted] 的注释。
        // 「点＋进来、一个字没打就被清掉」这种壳不该让对话页弹提示，因为它从没存在过。
        if (find(id)?.hasReply == true) deleted += id
        _conversations.value = _conversations.value.filterNot { it.id == id }
        commit()
    }

    fun rename(id: String, title: String) {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return
        update(id) { it.copy(title = cleaned.take(TITLE_MAX), titleGenerated = true) }
        commit()
    }

    /**
     * 正在重算标题的那条会话 id；null = 没有在跑。
     *
     * 给界面用：重命名可能来回好几秒（一次 `complete()` 调用），中间必须有可见反馈，
     * 否则用户点完菜单那一下就什么都没发生，只能猜。首页拿它决定哪一行挂变形进度条。
     *
     * **必须是 Compose 状态**（`StateFlow` 而不是普通字段）：被界面读取的字段不是
     * 状态的话，改了不会重组 —— 提示永远不出现也不消失。
     */
    private val _renamingId = MutableStateFlow<String?>(null)
    val renamingId: StateFlow<String?> = _renamingId.asStateFlow()

    /**
     * **强制**重算标题，交给模型总结。用户长按 →「重新命名」走这条。
     *
     * ## 与 [generateTitleIfNeeded] 的分工
     *
     * 那个是**发送后自动跑**的，第一道闸就是 `if (conversation.titleGenerated) return` ——
     * 会话已经有了标题就永远不再算。用户要的「重新命名」恰恰就是**绕过这道闸**，
     * 所以不能复用它（也叫不动它），只能另开一个入口；两边的取数、清洗、
     * 兜底逻辑则共用同一套私有函数，避免出现「自动生成的标题和手动重新生成的不一样」。
     *
     * ## 硬约束
     *
     *  * **只对 ask 会话生效。** 角色会话的标题恒等于角色名
     *    （见 [openPersonaChat]，建会话时就 `titleGenerated = true`），
     *    叫模型去总结一遍会把角色名冲掉 —— 那不是「重命名」，那是把人物改名。
     *    这里直接 `return`，界面上也不给这个入口（两处判据都是 `personaId == null`）。
     *  * **失败要留得住东西**：模型没返回、返回空、或者网络挂了的时候，
     *    **保留原标题**；原标题本来也是空的才退回第一句提问的截断（`provisionalTitle`）。
     *    绝不把标题清成空 —— 那会让列表上那一条突然变成「新对话」。
     *  * **一次只跑一个**：`complete()` 是花钱的调用，连点两下不该发两次。
     *    已经在跑就直接忽略（顺带也让界面上的进度条只有一个）。
     */
    fun regenerateTitle(id: String) {
        val conversation = find(id) ?: return
        // 角色会话不参与，理由见上面 KDoc
        if (conversation.personaId != null) return
        val (question, answer) = titleSource(conversation) ?: return
        if (_renamingId.value != null) return

        _renamingId.value = id
        scope.launch {
            try {
                // ⚠️ 1.985：取材范围从「第一条一问一答」改为「**第一条提问 + 最新回答**」。
                //
                // 用户原话：「ask对话重新总结功能貌似没有用，多个长对话多次重新生成均没有变化」。
                // 根因不是「没跑」，而是**每次都算出同一个标题**：
                //  * 取材是 `titleSource()` 的**第一条**一问一答 —— 不管对话后来聊到哪，
                //    重算的输入永远是开头那段 ⇒ 模型给出的概括自然每次都一样；
                //  * 长对话里第一条回答往往很长，`answer.take(600)` 截完,
                //    剩下的信息量更少 ⇒ 更没变化。
                //
                // 「重新总结」的语义应该是**重新概括这条会话**，而长对话概括的重心
                // 本来就该跟着最新的交流走 ⇒ 输入给「第一条提问（话题起点）+
                // **最后一条有效回答**（当前话题）」，模型看到的两端信息,
                // 重算结果才会随对话演进而变。
                val lastAnswer = conversation.messages
                    .lastOrNull { it.role == ChatRole.ASSISTANT && !it.error && it.content.isNotBlank() }
                    ?.content ?: answer
                val generated = runCatching {
                    client.complete(
                        apiKey = settings.value.apiKey,
                        systemPrompt = TITLE_PROMPT,
                        userContent = "用户：${question.take(300)}\n助手：${lastAnswer.take(600)}",
                    )
                }.getOrNull()

                // 三级兜底：模型给的 → 原来那个 → 第一句提问。
                val title = cleanTitle(generated)
                    ?: conversation.title.takeIf { it.isNotBlank() }
                    ?: provisionalTitle(question)
                update(id) { it.copy(title = title, titleGenerated = true) }
                commit()
            } finally {
                // 一定要落回 null：抛异常时不复位的话，界面上那条进度条会一直转下去，
                // 而且后面再也点不动「重新命名」（上面那道「一次只跑一个」的闸）
                _renamingId.value = null
            }
        }
    }

    fun clearAll() {
        stop()
        // 同上：没被 DS 回复过的空壳不入册。清空列表里躺着的壳不该让谁收到「已经不在了」。
        deleted += _conversations.value.filter { it.hasReply }.map { it.id }
        _conversations.value = emptyList()
        commit()
    }

    // ------------------------------------------------------------------ 角色

    fun findPersona(id: String?): Persona? =
        id?.let { target -> _personas.value.firstOrNull { it.id == target } }

    /** 这个角色名下的那条会话。一个角色最多一条会话 —— 通讯录点进去永远是「和 TA 的那个聊天」。 */
    fun personaConversation(personaId: String): Conversation? =
        _conversations.value.firstOrNull { it.personaId == personaId }

    /**
     * 造完人之后落地：**存角色 + 建会话（greeting 当场上架）**，返回会话 id。
     *
     * greeting 必须在这一刻写进去，**不能等用户开口**。理由有两条，都是硬的：
     *
     *  1. `discardEmpty()` 会把「一句消息都没有」的会话从内存里收走。角色会话要是空着
     *     从这一页走过去，可能还没进聊天页就被收掉了；
     *  2. 成功之后是**直接进聊天页**的，用户最想看到的就是 TA 开口说话。
     *     要是进了页面还是空的，那这一步就白走了。
     *
     * 注意这里**不碰** `hasReply` 的判据 —— greeting 是第一条 assistant 消息，
     * 它让会话天然非空，但这是结果，不是用来判「角色还在不在」的依据。
     * 角色在不在只有一个来源：[findPersona]。
     */
    fun createPersonaChat(persona: Persona): String {
        _personas.value = _personas.value + persona
        commitPersonas()
        return openPersonaChat(persona.id) ?: run {
            // openPersonaChat 只在角色不存在时返回 null，这里刚写完，理论上到不了。
            // 真到了也不能返回一个假 id 让页面去撞空白页 —— 直接把刚放进来的角色撤掉。
            _personas.value = _personas.value.filterNot { it.id == persona.id }
            commitPersonas()
            ""
        }
    }

    /**
     * 进某个角色的会话：已有就复用，没有就现建一条并把 greeting 上架。
     *
     * 返回 null 表示角色已经不在了（被删/进程重启后导航栈恢复出的假 id）——
     * 调用方什么都不该做，静静待着就行。
     */
    fun openPersonaChat(personaId: String): String? {
        val persona = findPersona(personaId) ?: return null
        personaConversation(personaId)?.let { return it.id }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val conversation = Conversation(
            id = id,
            // 角色会话的标题恒等于角色名，所以 titleGenerated 直接置位：
            // 谁都不该再去叫模型总结一遍
            title = persona.name,
            createdAt = now,
            updatedAt = now,
            titleGenerated = true,
            messages = listOf(ChatMessage(ChatRole.ASSISTANT, persona.openingLine)),
            personaId = personaId,
        )
        _conversations.value = listOf(conversation) + _conversations.value
        // 带着 greeting 建出来就是非空的，commit 的「空会话不落盘」过滤不会收走它
        commit()
        return id
    }

    /**
     * 「重新开始」：**人设不动，清空这个角色的全部消息，把存的 greeting 再上架一次。**
     *
     * 不联网、不重新生成人设，所以是瞬间完成的。TA 说的第一句和上次一字不差，
     * 因为 [Persona.greeting] 是固定值 —— 要每次换一句就得让模型现编，那要联网、会失败。
     *
     * ## 为什么必须先判「会话还在不在」
     *
     * 角色的存活性**不依赖会话**：一个角色完全可能没有会话。最常见的一条路是
     * [createPersonaChat] 分两次落盘（先 `commitPersonas()` 再 `commit()`），进程正好死在
     * 中间就是这个状态。更根本地说，`personaId` 是**会话指向人**的外键，箭头不能反过来，
     * 所以这里不能假定「有角色就一定有会话」。
     *
     * 没有会话时**现建一条**，而不是 `?: return` 静默退出：静默退出会让用户长按 →
     * 点「重新开始」→ 确认之后**屏幕上什么都没发生**，那是个死胡同。而「重新开始」的
     * 语义本来就是「回到那句开场白」——没有会话时把它建出来，正好就是这句话的实现。
     */
    fun restartPersona(personaId: String) {
        val persona = findPersona(personaId) ?: return
        val conversation = personaConversation(personaId)
        if (conversation == null) {
            openPersonaChat(personaId)
            return
        }
        if (_streamingId.value == conversation.id) stop()
        update(conversation.id) {
            it.copy(
                messages = listOf(ChatMessage(ChatRole.ASSISTANT, persona.openingLine)),
                updatedAt = System.currentTimeMillis(),
            )
        }
        commit()
    }

    /**
     * 换皮肤：**原地改那条大肥鱼的字段**，可选地顺手「重新开始」。
     *
     * 五个皮肤共用一个 id（[BuiltinPersonas.BUILTIN_FISH_ID]），所以换皮肤不是
     * 新建角色、也不是删了重建 —— 会话、聊天记录、通讯录排序全都不用动。
     * 保留的字段见 [BuiltinPersonas.withSkin]（改 id 会话就找不到人，
     * 改 createdAt/lastChatAt 列表排序会跳）。
     *
     * ## ⚠️ 顺序不能反
     *
     * 先把六块写进 persona 并落盘，**再**调 [restartPersona] —— 那个函数读的是
     * `findPersona(...).openingLine`，写之前调它，新会话上架的还是旧皮肤的开场白。
     *
     * ## [restart] = false 时也会刷新开场白的两种情况
     *
     *  * **从没聊过**（`lastChatAt == 0`）：会话里除了一句开场白什么都没有，
     *    换掉它不是改历史。这和 [updatePersonaField] 改开场白时用的是同一条口径。
     *  * 从预设页「添加」进来的新鱼：`addBuiltinPersona` 已经先建了一条带基础款
     *    开场白的会话，用户这时才去选皮肤，不刷新就会「选的是贴吧老哥、
     *    开口却是『在 / 说吧』」。
     *
     * 真的聊过之后，开场白就是历史的一部分，只有 [restart] = true（长按「重新开始」）
     * 才会清空重来 —— 这条是产品口径，别为了「让新开场白看得见」去改它。
     *
     * ## 返回
     *
     * 会话 id（没有就现建一条）。返回 null 只有一种可能：大肥鱼已经不在通讯录里了
     * （用户删过、而选择页还停在导航栈上）。调用方不该拿 null 去撞空白页。
     *
     * @param restart true = 清空消息、把新皮肤的开场白重新上架（「重新开始」那条路）
     */
    fun applyFishSkin(skin: FishSkin, restart: Boolean): String? {
        val persona = findPersona(BuiltinPersonas.BUILTIN_FISH_ID) ?: return null
        val updated = BuiltinPersonas.withSkin(persona, skin)

        // 1) 六块 + 名字 + 副标题先落地。restartPersona 要读这条最新的 persona。
        _personas.value = _personas.value.map {
            if (it.id == persona.id) updated else it
        }
        commitPersonas()

        // 2) 皮肤状态落盘。**chosen = true**：走到这里说明用户真的点了一张卡。
        writeFishSkin(skin, chosen = true)
        // 2.39：只要用户主动选了新皮肤，就说明「保留原人设」那个选择已经做完了。
        fishUpgradeChoicePending = false

        // 3) 会话侧：标题（角色会话标题恒等于角色名）和「还没聊过的开场白」跟着换。
        //    流式输出中不能改消息，先停掉 —— 但只有 restart 那条路会真的换掉整个列表。
        personaConversation(persona.id)?.let { conversation ->
            val refreshGreeting = !restart && persona.lastChatAt == 0L
            if (restart && _streamingId.value == conversation.id) stop()
            if (conversation.title != skin.name || refreshGreeting) {
                update(conversation.id) {
                    it.copy(
                        title = skin.name,
                        titleGenerated = true,
                        messages = if (refreshGreeting) {
                            listOf(ChatMessage(ChatRole.ASSISTANT, updated.openingLine))
                        } else {
                            it.messages
                        },
                    )
                }
                commit()
            }
        }

        // 4) 最后才「重新开始」：它负责清空消息 + 用 updated.openingLine 重新上架。
        if (restart) restartPersona(persona.id)

        // 5) 没有会话时现建一条（greeting 上架），把 id 交给调用方去导航。
        return openPersonaChat(persona.id)
    }

    // ------------------------------------------------------- 主动消息（2.35）

    /**
     * 「打开即说」：打开一条**角色会话**时调一次，满足条件就先替 TA 说 1~3 句。
     *
     * 判定全在 [ProactivePolicy.shouldSpeak]（纯函数，六道闸），这里只负责：
     * 取数据 → 调判定 → 拼句子 → 插消息 → 记账。**任何一条不满足就什么都不做。**
     *
     * ## 为什么在这里而不是在 ChatScreen 里判
     *
     * 页面只该说「打开了」，不该知道「隔了 4 小时 / 今天第几次 / 现在是不是凌晨」——
     * 那些是产品规则，写进 UI 就没法单独测，也会和 Ask 页面纠缠。
     * 而且插入消息要动 `_conversations`，本来就只有引擎能安全地做（主线程约定）。
     *
     * ## 硬性顺序（别调换）
     *
     * 1. `isBusy` → 正在流式绝不插话（否则会把正在生成的回复挤开，
     *    而且 `update{}` 是「读一遍再整体写回」，和流式写回会互相覆盖）；
     * 2. `personaId == null` → Ask 会话不参与（红线：不动 Ask）；
     * 3. 判定通过才取状态、拼句子（读 prefs 失败不该影响判定）；
     * 4. **先把消息写进会话并落盘，再写记账**：反过来（先记「说过了」再插消息）
     *    一旦插消息失败，用户就永远等不到那句，而系统以为说过了 —— 这一轮就白白丢掉。
     *
     * ## 为什么每条是独立的 assistant 消息
     *
     * 2.35 时聊天页还是「一条消息一个气泡」（多气泡是 2.36）。拆成独立消息，
     * **不用等 2.36 就能看到「她连发两句」**；2.36 之后也不会因此变成两层拆分。
     * 时间戳都用同一个 `now`，排序稳定、观感是「一口气发出来」。
     */
    fun onChatOpened(conversationId: String) {
        if (!::personaPrefs.isInitialized) return
        if (isBusy) return

        val conversation = find(conversationId) ?: return
        val personaId = conversation.personaId ?: return
        val persona = findPersona(personaId) ?: return

        val settingsNow = settings.value
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()

        // 「上次说话」取会话里最后一条**用户**消息，不是角色自己的 lastChatAt：
        // 主动消息刚插进来的那几条也是 assistant，用它们会让间隔永远差 0。
        val lastUserAt = conversation.messages
            .lastOrNull { it.role == ChatRole.USER }
            ?.timestamp ?: 0L

        val lastProactive = personaPrefs.getLong(proactiveLastKey(personaId), 0L)
        val todayKey = proactiveCountKey(now, zone)
        val todayCount = personaPrefs.getInt(todayKey, 0)

        if (!ProactivePolicy.shouldSpeak(
                now = now,
                lastUserAt = lastUserAt,
                lastProactive = lastProactive,
                todayCount = todayCount,
                enabled = settingsNow.proactiveEnabled,
                zone = zone,
            )
        ) {
            return
        }

        // 皮肤决定招呼语气；用户自建角色没有皮肤表 → null → 中性招呼（别套大肥鱼的口吻）。
        val skinKey = if (persona.id == BuiltinPersonas.BUILTIN_FISH_ID) {
            currentFishSkin().key
        } else {
            null
        }
        // 状态读取包一层 runCatching：这是「可选装饰」，读不到就只发招呼 + 关心（红线 7 失败静默）。
        val state = runCatching { presence.stateFor(LocalDateTime.now(zone)) }.getOrDefault("")
        val lines = ProactivePolicy.composeLines(
            skinKey = skinKey,
            gapMillis = now - lastUserAt,
            hour = LocalDateTime.now(zone).hour,
            state = state,
            // ★ 2.37：有长期记忆就用它当钩子（记忆优先于状态，两者互斥）。
            //   取这个角色**最近一条**记忆；没有就回落成上面那句状态。
            memoryText = MemoryExtract.latestFor(memories, personaId)?.text,
        )
        if (lines.isEmpty()) return

        val inserted = lines.map {
            // ⚠️ 时间戳必须**具名**传：ChatMessage 第三个位置参数是 `reasoning`（String），
            //    位置传 Long 会编译不过；就算将来签名变了，具名也不容易悄悄错位。
            ChatMessage(role = ChatRole.ASSISTANT, content = it, timestamp = now)
        }
        update(conversation.id) {
            it.copy(messages = it.messages + inserted, updatedAt = now)
        }
        commit()

        personaPrefs.edit()
            .putLong(proactiveLastKey(personaId), now)
            .putInt(todayKey, todayCount + 1)
            .apply()
        // ⚠️ 刻意不调 generateTitleIfNeeded：角色会话的标题恒等于角色名，
        //    叫模型总结一次会把名字冲掉（同 renamePersona 的注释）。
    }

    /** 每个角色各自的「上次主动开口」时间。键里带 personaId，换角色互不影响。 */
    private fun proactiveLastKey(personaId: String) = "proactive_last_$personaId"

    /** 当天的主动计数键：`proactive_count_yyyyMMdd`。按**本地时区**的日期，跨天自动换键。 */
    private fun proactiveCountKey(now: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return "proactive_count_" + DateTimeFormatter.BASIC_ISO_DATE.format(date)
    }

    /**
     * 给角色改名。会话标题跟着一起改 —— 角色会话的标题就是角色名，两处必须同步。
     *
     * 只改 [Persona.name]，**不顺手去重写人设六块**：里面提到名字的地方（比如示例对话
     * 每行的说话人）是模型生成时写死的，静态替换会留下改不干净的残留，让模型重写一遍
     * 又要联网。名字是用户在界面和气泡标签上看到的那一层，改它立刻生效 ——
     * 要连人设一起换，那是「重新描述一个人再生成」的事。
     */
    fun renamePersona(personaId: String, name: String) {
        val cleaned = name.trim().take(PERSONA_NAME_MAX)
        if (cleaned.isEmpty()) return
        _personas.value = _personas.value.map {
            if (it.id == personaId) it.copy(name = cleaned) else it
        }
        personaConversation(personaId)?.let { conversation ->
            update(conversation.id) { it.copy(title = cleaned, titleGenerated = true) }
        }
        commitPersonas()
        commit()
    }

    /**
     * 手机端网页提交的角色，**整对象覆盖**，并且**返回时已经在磁盘上**。
     *
     * 和手表上那几条路的区别只有两点，都是网页那边的承诺逼出来的：
     *
     *  1. **同步落盘。** `commitPersonas()` 是把写盘丢进 IO 作用域就返回的，
     *     而网页上写完就显示「已保存到手表」—— 用户看到这四个字的下一个动作往往就是
     *     切走或者杀掉应用。必须等 `PersonaStore.save` 真的返回（里面有 `fd.sync()`）
     *     才让 HTTP 响应出去，那句「已保存」才是真的。
     *  2. **整对象覆盖，不是增量合并。** 网页给的是六块的真值表，留空就是清空。
     *
     * 返回 null 表示成功；非 null 是人话错误，直接显示在网页上。
     *
     * 名改了的话顺带把会话标题也改掉（角色会话的标题恒等于角色名），并且一起同步落盘 ——
     * 否则会出现「角色名是新的、聊天页标题还是旧的」，而且要等下次 renamePersona 才自愈。
     *
     * @param persona 已由调用方补好 `id` / `createdAt` / `lastChatAt` / `source`；
     *   这里只做裁剪和落盘，不猜任何身份信息。
     */
    suspend fun savePersonaFromPhone(persona: Persona): String? {
        val cleaned = persona.copy(
            name = persona.name.trim().take(PERSONA_NAME_MAX),
            tagline = persona.tagline.trim().take(PERSONA_TAGLINE_MAX),
            soul = persona.soul.trim(),
            style = persona.style.trim(),
            rules = persona.rules.trim(),
            scenario = persona.scenario.trim(),
            examples = persona.examples.trim(),
            greeting = persona.greeting.trim(),
        )
        if (cleaned.name.isEmpty()) return "角色名不能空着"

        val nameChanged = findPersona(cleaned.id)?.name != cleaned.name

        _personas.value = (_personas.value.filterNot { it.id == cleaned.id } + cleaned)
            .sortedByDescending { it.sortKey }

        if (nameChanged && personaConversation(cleaned.id) != null) {
            // `_conversations` 的读写约定在主线程（见类注释里的线程约定），
            // 而这里是从 HTTP 的 IO 协程调进来的，所以这一步必须回主线程做。
            // `update` 是「读一遍再整体写回」，跨线程并发调用会丢更新。
            withContext(Dispatchers.Main.immediate) {
                personaConversation(cleaned.id)?.let { conversation ->
                    update(conversation.id) { it.copy(title = cleaned.name, titleGenerated = true) }
                    _conversations.value = _conversations.value.sortedByDescending { it.sortKey }
                }
            }
        }

        return runCatching {
            personaWriteLock.withLock { personaStore.save(_personas.value) }
            if (nameChanged) writeLock.withLock { saveConversationsNow() }
        }.exceptionOrNull()?.let { "写盘失败：${it.message ?: "未知错误"}" }
    }

    /**
     * 改角色的一个**短字段**（名字 / 简介 / 开场白）。
     *
     * 这三格是手表上唯一能改的：六块里其余五块都是大段文字，在这块表上敲不现实。
     * 名字那条路另有讲究（会话标题要跟着改），所以直接转给 [renamePersona] ——
     * 一处裁剪、一处同步，不在这里再写一遍。
     */
    fun updatePersonaField(personaId: String, field: PersonaField, value: String) {
        if (field == PersonaField.NAME) {
            renamePersona(personaId, value)
            return
        }

        val persona = findPersona(personaId) ?: return
        val text = value.trim()
        val updated = when (field) {
            PersonaField.TAGLINE -> persona.copy(tagline = text.take(PERSONA_TAGLINE_MAX))
            PersonaField.GREETING -> persona.copy(greeting = text)
            // 上面已经分流走了；写在这里是为了让 when 是全穷尽的
            PersonaField.NAME -> return
        }
        _personas.value = _personas.value.map { if (it.id == personaId) updated else it }

        // 改了开场白，**那个还没聊过的会话**里上架的那句要跟着换 ——
        // 否则用户改完回到聊天页，看到的还是旧的那句，只会以为没生效。
        //
        // 判据用 `lastChatAt == 0`，也就是「从没在 TA 的会话里说过话」。
        // 那种会话里除了一句开场白什么都没有，换掉它不是改历史 —— 而真的聊过之后
        // 这就是历史，历史不该被一次编辑改掉（要换就得点「重新开始」）。
        if (field == PersonaField.GREETING && persona.lastChatAt == 0L) {
            personaConversation(personaId)?.let { conversation ->
                if (_streamingId.value == conversation.id) stop()
                update(conversation.id) {
                    it.copy(messages = listOf(ChatMessage(ChatRole.ASSISTANT, updated.openingLine)))
                }
                commit()
            }
        }
        commitPersonas()
    }

    /**
     * 删角色 —— **连带它的会话一起删**。
     *
     * 会话也照 [deleted] 的老规矩登记：如果用户当时正停在这条角色会话的聊天页上
     * （比如从设置里绕了一圈），聊天页会靠 `isGone()` 自己退回去。
     * 角色会话的 `hasReply` 必然为 true（greeting 就是 assistant 消息），所以在册。
     */
    fun deletePersona(personaId: String) {
        val conversation = personaConversation(personaId)
        if (conversation != null) {
            if (_streamingId.value == conversation.id) stop()
            if (conversation.hasReply) deleted += conversation.id
            _conversations.value = _conversations.value.filterNot { it.id == conversation.id }
        }
        _personas.value = _personas.value.filterNot { it.id == personaId }
        // ★ 2.37：删角色 = 连**长期记忆**一起删（P3.6 的隐私约定）。
        //   和会话一样是级联删除；启动时的 orphan 清理只是兜底，不是主路径。
        val keptMemories = memories.filterNot { it.personaId == personaId }
        if (keptMemories.size != memories.size) {
            memories = keptMemories
            io.launch { runCatching { memoryStore.save(keptMemories) } }
        }
        commitPersonas()
        commit()
    }

    /** 在 TA 的会话里说过话 → 刷新「最后聊天时间」，通讯录按它排序。 */
    private fun touchPersona(personaId: String, at: Long) {
        var changed = false
        val next = _personas.value.map {
            if (it.id == personaId && it.lastChatAt != at) {
                changed = true
                it.copy(lastChatAt = at)
            } else {
                it
            }
        }
        if (!changed) return
        _personas.value = next
        commitPersonas()
    }

    private fun commitPersonas() {
        _personas.value = _personas.value.sortedByDescending { it.sortKey }
        val snapshot = _personas.value
        io.launch {
            personaWriteLock.withLock { runCatching { personaStore.save(snapshot) } }
        }
    }

    // -------------------------------------------------------- 预设草稿（页面级）

    /**
     * 进预设详情页时铺一份草稿。
     *
     * **同一个预设已经有草稿就不重铺**：这一页去一趟文字输入页再回来，中间会被销毁重建，
     * 无条件重铺会把用户刚改的那几个字段全冲回默认值 —— 而他只是去改了其中一行。
     */
    fun beginPresetDraft(presetIndex: Int) {
        val preset = personaPresets().getOrNull(presetIndex) ?: return
        if (_presetDraft.value?.presetIndex == presetIndex) return
        _presetDraft.value = PresetDraft.from(preset, presetIndex)
    }

    fun setPersonaDraft(text: String) {
        _personaDraft.value = text.trim().takeIf { it.isNotEmpty() }
    }

    fun consumePersonaDraft() {
        _personaDraft.value = null
    }

    fun updatePresetDraft(transform: (PresetDraft) -> PresetDraft) {
        _presetDraft.value = _presetDraft.value?.let(transform)
    }

    fun clearPresetDraft() {
        _presetDraft.value = null
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * 让页面给用户留一句话（首页那块的提示卡会显示它）。
     *
     * 场景是「动作发生在这个页面、但用户马上会被送到另一个页面」：对话页发现
     * 会话已经不存在、自己退回首页时，得让首页有句话解释他为什么被弹回来了。
     */
    fun postNotice(text: String) {
        _notice.value = text
    }

    // ------------------------------------------------------------------ 发送

    fun send(conversationId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (isBusy) {
            _notice.value = "上一条还在回答，先等它说完"
            return
        }
        if (!hasApiKey()) {
            _notice.value = "请先在设置里填写 DeepSeek API Key"
            return
        }

        val now = System.currentTimeMillis()
        // 会话从第一句话开始存在。首页「＋」拿到的只是一个还没落地的 id，
        // 走到这里就该把它补进列表 —— 用户从空白会话页发出的第一条消息就是这条路。
        //
        // 两种情况都要能落地：会话**完全不在了**（进程重启后恢复的假 id），
        // 以及它还在内存里、只是还没有消息（刚点的＋）。前者的 id 从没进过列表，
        // 后者正在列表里。分支写好，别让「补一条空的」把后者那条覆盖掉。
        if (find(conversationId) == null) {
            _conversations.value = _conversations.value +
                Conversation(id = conversationId, createdAt = now, updatedAt = now)
        }
        // 说过话的这一刻，它才算一条真正的对话（见 [Conversation.hasReply]）——
        // 下面那条助手占位消息就是「DS 回过话」的证据，判据全靠它，不再需要别的记账。
        update(conversationId) { conversation ->
            conversation.copy(
                // 占位消息，流式内容直接往最后这条上灌
                messages = conversation.messages + ChatMessage(ChatRole.USER, body) +
                    ChatMessage(ChatRole.ASSISTANT, ""),
                // 先给个临时标题，首页列表不至于一排「新对话」；真正的标题随后由模型补上
                title = conversation.title.ifBlank { provisionalTitle(body) },
                updatedAt = now,
            )
        }
        // 在 TA 的会话里说过话 → 刷新通讯录的排序时间。放在这里而不是 commit() 里：
        // commit 在流式的每个消息边界都会被调，放那儿等于每次回复都重排一遍列表。
        find(conversationId)?.personaId?.let { touchPersona(it, now) }
        commit()
        startStream(conversationId)
    }

    /**
     * 编辑重发：改掉第 [index] 条消息（必须是用户消息），用它重新问一遍。
     *
     * 这是「截断再发」，不是「插一条新的」—— 它和它之后的一切（原来那条回答，
     * 以及更往后的所有追问）全部作废。理由：问句换了，后面那些回答回答的是**另一个**
     * 问题，留在上下文里只会让模型自相矛盾。
     *
     * ★ 2.1：**允许改任意一条提问**，不再限制「最新那一问」——用户 2026-10-24 明确要求
     * 「所有用户发的都可以重编辑了」。代价是它后面已经生成过的内容全部作废，
     * 这个后果由 UI 层先弹警告（显示真实条数 N）、用户确认后才走到这里；
     * 数据层不再设第二道闸。
     *
     * 所有前置检查都在截断之前完成。顺序反了就会出现「对话少了一半、新消息却没发出去」
     * 这种最坏的中间态 —— send 内部还会再查一遍，这里提前拦是为了不让截断白做。
     */
    fun rewrite(conversationId: String, index: Int, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        if (isBusy) {
            _notice.value = "上一条还在回答，先等它说完"
            return
        }
        if (!hasApiKey()) {
            _notice.value = "请先在设置里填写 DeepSeek API Key"
            return
        }
        val conversation = find(conversationId) ?: return
        val target = conversation.messages.getOrNull(index) ?: return
        if (target.role != ChatRole.USER) return

        val kept = conversation.messages.take(index)
        // 被改掉的正好是这个会话的第一句：标题本来就是照着它起的，留着就成了一个
        // 和内容对不上的标题。清空并允许重新生成，列表里的标题才会跟着问题走。
        val firstQuestionGone = kept.none { it.role == ChatRole.USER }
        update(conversationId) {
            it.copy(
                messages = kept,
                title = if (firstQuestionGone) "" else it.title,
                titleGenerated = if (firstQuestionGone) false else it.titleGenerated,
            )
        }
        send(conversationId, body)
    }

    /** 把最后一条出错的回答抹掉重来。 */
    fun retry(conversationId: String) {
        if (isBusy) return
        val conversation = find(conversationId) ?: return
        if (!hasApiKey()) {
            _notice.value = "请先在设置里填写 DeepSeek API Key"
            return
        }

        val kept = conversation.messages.dropLastWhile {
            it.role == ChatRole.ASSISTANT && (it.error || it.content.isBlank())
        }
        if (kept.lastOrNull()?.role != ChatRole.USER) {
            _notice.value = "没有可以重试的问题"
            return
        }
        update(conversationId) {
            it.copy(messages = kept + ChatMessage(ChatRole.ASSISTANT, ""))
        }
        startStream(conversationId)
    }

    /**
     * 删掉「最后一条用户消息 + 它对应的回复」。界面上叫「删除此信息」。
     * 只对最后一条用户消息有意义；不是最后一条就什么也不做并说明原因。
     *
     * ★ 2.1 新增（用户原话：「只有最后一条用户所发信息支持，一带把所对应生成返回的
     * 信息删除，不留上下文历史」）。删除范围 = 那条用户消息**起直到末尾**的全部消息
     * （失败重试可能留下多条助手消息，所以是「到末尾全删」而不是「只删一条」）。
     *
     * ⚠️ 必须落盘（commit）：这是删除，不落盘下次启动又回来。
     * ⚠️ 标题处理照 [rewrite] 的老规矩：删完一条用户消息都不剩时
     * 清空标题（标题是照着第一句起的，第一句没了标题就该重新生成）。
     * ⚠️ 流式中不许调用（isBusy 拦截）。
     * 空会话的收尾交给既有的 discardEmpty / 「空会话不落盘」过滤，不在判断据里另造一套。
     */
    fun deleteLastUserExchange(conversationId: String) {
        if (isBusy) {
            _notice.value = "上一条还在回答，先等它说完"
            return
        }
        val conversation = find(conversationId) ?: return
        val index = conversation.messages.indexOfLast { it.role == ChatRole.USER }
        if (index < 0) return
        // 按定义它必然是最后一条用户消息，但要防御：不成立就说明并返回。
        if (conversation.messages.drop(index + 1).any { it.role == ChatRole.USER }) {
            _notice.value = "只有最后一条发送的消息可以删除"
            return
        }
        val kept = conversation.messages.take(index)
        val noQuestionLeft = kept.none { it.role == ChatRole.USER }
        update(conversationId) {
            it.copy(
                messages = kept,
                title = if (noQuestionLeft) "" else it.title,
                titleGenerated = if (noQuestionLeft) false else it.titleGenerated,
            )
        }
        commit()
    }

    /**
     * 重新生成第 [index] 条助手消息。
     * index 必须是 ASSISTANT 且内容为非空（空/失败的那些走 [retry]）。
     *
     * ★ 2.1 新增（用户要求「任意一条助手消息都能重新生成」）。
     * 截断 = `take(index)`（把这条及其后全删），再补一条空的助手占位，
     * 然后流式灌入 —— 与 [retry] 同样的形状（末尾必须是那条空占位，顺序别搞反）。
     * ⚠️ 标题不要重置：重新生成的是回答，不是第一句提问。
     * 后面还有内容时要不要警告由 UI 决定（数据层不弹窗）。
     */
    fun regenerate(conversationId: String, index: Int) {
        if (isBusy) {
            _notice.value = "上一条还在回答，先等它说完"
            return
        }
        if (!hasApiKey()) {
            _notice.value = "请先在设置里填写 DeepSeek API Key"
            return
        }
        val conversation = find(conversationId) ?: return
        val target = conversation.messages.getOrNull(index) ?: return
        if (target.role != ChatRole.ASSISTANT) {
            _notice.value = "只能重新生成回答"
            return
        }
        if (target.content.isBlank()) {
            _notice.value = "这条还没有内容，用「重试」再试一次"
            return
        }
        val kept = conversation.messages.take(index)
        update(conversationId) {
            it.copy(messages = kept + ChatMessage(ChatRole.ASSISTANT, ""))
        }
        startStream(conversationId)
    }

    fun stop() {
        client.cancel()
        streamJob?.cancel()
        streamJob = null
        _streamingId.value = null
    }

    // ------------------------------------------------------------ 流式实现

    private fun startStream(conversationId: String) {
        val snapshot = find(conversationId) ?: return
        val current = settings.value
        // 这条会话是不是角色会话？是的话，system prompt 换成「人设六块 + 硬编码输出规则」，
        // **全局设置里那套不再参与**。两段缺一不可：
        // 前面的 [Persona.buildCorePrompt] 随角色变，后面的 CHAT_OUTPUT_RULES 谁也改不动。
        //
        // 每次发送都现拼，**不缓存也不落库**：手机端改完 `soul` 回到手表，
        // 下一次发送必须直接用新的。存一份拼好的结果就会出现「改了没效果」，
        // 而那是最难查的一类不一致（理由也写在 buildCorePrompt 上）。
        val persona = snapshot.personaId?.let(::findPersona)
        // ★ 2.32 / P1：中间插一行【此刻】（几点 / 多久没聊 / 它此刻在干嘛），
        //   消灭「永远在线、永远在等你」的感觉。三段顺序固定：人设 → 此刻 → 输出规矩。
        //   ⚠️ 间隔取「会话里最后一条**用户**消息」的时间；取不到才退回 persona.lastChatAt
        //   （那个字段是「上次在 TA 的会话里说过话」，和「上一条用户消息」不是一回事）。
        //   ⚠️ 失败静默：PresenceStore 自己兜住异常，这里再 runCatching 一层，
        //   最坏情况 = 这一行不出现，绝不影响发送。
        val presenceLine = persona?.let { p ->
            runCatching {
                val lastUserAt = snapshot.messages
                    .lastOrNull { it.role == ChatRole.USER }?.timestamp
                presence.lineFor(
                    now = java.time.LocalDateTime.now(),
                    lastChatAt = lastUserAt ?: p.lastChatAt.takeIf { it > 0L },
                )
            }.getOrDefault("")
        }.orEmpty()
        // ★ 2.37：长期记忆块，插在**人设之后、此刻之前**（P3.5 指定的位置）。
        //   为什么在这个位置：先让模型知道「这个人是谁、你是什么性格」，再告诉它
        //   「你记得他哪些事」，最后才是此刻与输出规矩 —— 记忆夹在两段「设定」中间
        //   读起来才是背景知识，放到规矩后面会被当成指令。
        //   没有条目时是空串，`filter { isNotBlank }` 会整块跳过（不留空标题）。
        val memoryBlock = persona
            ?.let { MemoryExtract.rememberBlock(memories, it.id) }
            .orEmpty()
        val systemPrompt = persona?.let {
            listOf(it.buildCorePrompt(), memoryBlock, presenceLine, CHAT_OUTPUT_RULES)
                .filter { part -> part.isNotBlank() }
                .joinToString("\n\n")
        } ?: current.systemPrompt

        // 联网搜索开关**按会话类型取** —— 判据复用上面那个 `persona`，
        // **不另造一个**（和 `modeAsk` / `modeChat`、设置页齿轮分叉用的是同一条判据）。
        // `persona != null` ⇒ 角色会话 ⇒ 读 `searchChat`；否则是一次性问答 ⇒ 读 `searchAsk`。
        val searchOn = if (persona != null) current.searchChat else current.searchAsk

        // 上下文不包含最后那条空的助手占位消息；再按「最近 N 条 + 总字数预算」截一刀。
        // 手表上的会话很容易越滚越长，整段历史照发早晚会撞上模型的上下文上限（400），
        // 而且每一轮都在为很早以前的内容重复付费。
        //
        // ★ 1.983：开了搜索时，**把搜索资料要占的那块从历史预算里预先扣掉**
        //（「在预算里给它留位置」那条做法）。这样「历史 + 搜索资料」的总量
        // **恒 ≤ `HISTORY_MAX_CHARS`**，不会因为多注入了搜索资料就把总长度顶穿。
        // 不这么做的话，注入之后总量会到 8000 + 2000 = 10000 字，
        // 要么超预算、要么得在注入后再裁一刀（那会把刚搜来的资料又裁掉，白花一次钱）。
        val budget = HISTORY_MAX_CHARS - if (searchOn) SEARCH_CONTEXT_CHARS else 0
        val history = trimToBudget(
            snapshot.messages.dropLast(1),
            persona = persona != null,
            budgetChars = budget,
        )
        if (history.isEmpty()) return

        // ⚠️ `_streamingId` **在搜索之前就置位**：搜索是额外 0.2~0.5 秒 + 一次网络往返，
        // 不置位的话界面在这段时间里什么都不显示，看着就是卡住了。
        // 置位之后现有的「正在思考…」卡片（`WavyProgressIndicator`）会立刻出现 ——
        // **复用现有的进度 UI，不新造一套**。
        _streamingId.value = conversationId
        streamJob = scope.launch {
            val reply = StringBuilder()
            val reason = StringBuilder()
            var failure: String? = null
            var stopped = false

            // ★ 联网搜索（1.983）：**只搜这一次，且整段包在 try/catch 里**。
            //
            // 三条硬规则都落在这个函数里（见 [searchContext] 的注释）：
            //  1. 失败**静默降级** —— 搜不到就当没开搜索，继续走原来的对话；
            //  2. 只搜「最后一条用户消息」当 query，不每轮都搜（省钱 + query 准）；
            //  3. 结果**不写回 `conversations.json`**，只影响这一次发送。
            val searchBlock = if (searchOn) searchContext(history, current.apiKey) else ""

            try {
                client.stream(
                    apiKey = current.apiKey,
                    // ★ 注入方式选 A（提示词 §1.4 给的两个选项之一）：
                    // 把搜索资料拼在**最后那条 user 消息前面**，构造一份**临时副本**。
                    // ⚠️ `injectSearch()` 只改这个 List，**绝不碰 store**
                    // ⇒ 搜索资料不进 `conversations.json`。理由：
                    // 它每轮都会变，落盘会造成「历史里存了一堆过期网页」，
                    // 而且下一轮会被当成上下文重发、**重复计费**。
                    history = injectSearch(history, searchBlock),
                    systemPrompt = systemPrompt,
                    // 思考模式**按会话类型取档**：角色会话用 `modeChat`，普通会话用 `modeAsk`。
                    // 两档在设置页**分开存、且一次只显示一组**（设置页按来源分叉，
                    // 从哪一页进来就调哪一档），改一个不动另一个 —— 判据就是上面那个
                    // `persona`（`snapshot.personaId != null` 即角色会话），不另造一个。
                    //
                    // ⚠️ 这里读的是**两个字段**，与设置页摆几组按钮**无关**：
                    // UI 藏掉一组只是不给入口，判据仍然是这个 `persona`。
                    // 把取档也改成「看当前 UI 选中哪组」就成了隐藏功能改行为的路子，没那回事。
                    //
                    // **仍然不强制给角色会话关掉思考。** 曾经想过「角色会话一律关思考」，
                    // 理由是「思维链打到屏幕上破坏沉浸」—— 那条是错的：MessageCard 把
                    // reasoning 做成默认收起的折叠区（「思考过程 · 点击展开」），用户不点就看不到。
                    // 剩下的「首包慢」是真的，但那是用户自己在设置里给 chat 选的档位，
                    // 他愿意等就让他等。深度思考本来就是接入这个模型的价值所在，别替他决定。
                    thinking = (if (persona != null) current.modeChat else current.modeAsk) ==
                        ChatMode.THINKING,
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            reply.append(event.text)
                            patchAssistant(conversationId, reply.toString(), reason.toString())
                        }

                        is StreamEvent.Reasoning -> {
                            reason.append(event.text)
                            patchAssistant(conversationId, reply.toString(), reason.toString())
                        }

                        is StreamEvent.Usage -> {
                            // 累加进会话，不覆盖。usage 只在最后一个 chunk 出现一次，
                            // 所以这里一定是「加」，不会是「重复加」。
                            //
                            // 用户中途按「停止」时**没有 usage chunk**，这个分支根本不会
                            // 走到 —— 于是会话的 token 数停在打断前的旧值，而不是被记成 0。
                            // 这正是要的行为：没拿到不等于用了 0 个。
                            addUsage(
                                conversationId,
                                event.promptTokens,
                                event.completionTokens,
                                event.cacheHitTokens,
                                event.cacheMissTokens,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 用户点了「停止」或切走了会话。这里故意不再抛出：
                // 主线程作用域永远不会被整体取消，吞掉它才能把已收到的内容落盘。
                stopped = true
            } catch (e: DeepSeekException) {
                failure = e.hint
            } catch (e: Exception) {
                failure = "出错了：${e.message ?: "未知错误"}"
            }

            val text = reply.toString()
            val problem = failure

            when {
                problem != null && text.isEmpty() -> {
                    replaceLastAssistant(
                        conversationId,
                        ChatMessage(ChatRole.ASSISTANT, problem, error = true),
                    )
                }

                problem != null -> {
                    // 已经收到一部分，保留正文，把错误另起一条
                    patchAssistant(conversationId, text, reason.toString(), done = true)
                    appendMessage(
                        conversationId,
                        ChatMessage(ChatRole.ASSISTANT, problem, error = true),
                    )
                }

                // 一个字都没收到就被停掉：把空占位悄悄删掉，别留下一条像报错的东西
                stopped && text.isEmpty() -> dropTrailingEmptyAssistant(conversationId)

                text.isEmpty() -> {
                    replaceLastAssistant(
                        conversationId,
                        ChatMessage(ChatRole.ASSISTANT, "模型没有返回内容，可以重试", error = true),
                    )
                }

                else -> {
                    patchAssistant(conversationId, text, reason.toString(), done = true)
                }
            }

            streamJob = null
            _streamingId.value = null
            commit()
            generateTitleIfNeeded(conversationId)
            // ★ 2.37：收尾后看看要不要抽一次长期记忆。
            //   放在 commit 之后：抽取读的是**已经落盘的消息**，避免读到半截状态；
            //   而且它内部整个包在 runCatching 里、只影响后台，不会拖慢这次回复的收尾。
            maybeExtractMemories(conversationId)
        }
    }

    /**
     * 长期记忆抽取（2.37 / P3.3）：**一个后台小任务，失败完全静默**。
     *
     * ## 什么时候抽
     *
     * 只对**角色会话**；并且「上次抽取之后新增的用户消息 ≥ 6 条」才抽一次。
     * 记账用 `whalechat_memory` prefs 的 `extracted_<conversationId>`（存用户消息条数）：
     *  * 存条数而不是时间戳：条数是单调递增的，重启后还能对得上；
     *  * **先记账再发请求**：这次抽失败也不重试 —— 重试只会每 6 条多花一次钱，
     *    而且失败静默本来就是红线（P3 红线 7）。少记几条不影响对话。
     *
     * ## 花多少钱
     *
     * 一次 `complete()`：最近 12 条消息（≤2000 字）+ 120 tokens 输出。
     * 比一轮正常对话便宜得多；而且**每 6 条用户消息才一次**。
     *
     * ## 失败怎么办
     *
     * 什么都**不**做：不弹窗、不重试、不写错误消息、不动会话。
     * 最坏情况 = 这次没记住，下 6 条再说。
     */
    private fun maybeExtractMemories(conversationId: String) {
        if (!::memoryPrefs.isInitialized || !::memoryStore.isInitialized) return
        val conversation = find(conversationId) ?: return
        val personaId = conversation.personaId ?: return   // Ask 会话不抽（红线 1）
        val apiKey = settings.value.apiKey
        if (apiKey.isBlank()) return

        val userCount = conversation.messages.count { it.role == ChatRole.USER }
        val extractedCount = memoryPrefs.getInt(memoryExtractKey(conversationId), 0)
        if (userCount - extractedCount < MemoryExtract.EXTRACT_EVERY_USER_MESSAGES) return

        // 先记账：见上面「先记账再发请求」。
        memoryPrefs.edit().putInt(memoryExtractKey(conversationId), userCount).apply()

        // 只把最近 12 条给模型；「对方 / 你」的标签比 role 字段更好读，也避免模型把
        // 说话人搞反（示例对白那种「你 / TA」视角问题在这个项目里踩过）。
        val recent = conversation.messages
            .takeLast(MemoryExtract.RECENT_MESSAGES)
            .joinToString("\n") { message ->
                val who = if (message.role == ChatRole.USER) "对方" else "你"
                "$who：${message.content}"
            }
            .take(MemoryExtract.RECENT_MAX_CHARS)

        scope.launch {
            runCatching {
                client.complete(
                    apiKey = apiKey,
                    systemPrompt = MemoryExtract.PROMPT,
                    userContent = recent,
                    maxTokens = MemoryExtract.EXTRACT_MAX_TOKENS,
                    temperature = MemoryExtract.EXTRACT_TEMPERATURE,
                    readTimeoutSeconds = MemoryExtract.EXTRACT_TIMEOUT_SECONDS,
                )
            }.onSuccess { raw ->
                val texts = MemoryExtract.parse(raw)
                if (texts.isNotEmpty()) {
                    val updated = MemoryExtract.merge(
                        existing = memories,
                        personaId = personaId,
                        newTexts = texts,
                        now = System.currentTimeMillis(),
                    )
                    if (updated != memories) {
                        memories = updated
                        io.launch { runCatching { memoryStore.save(updated) } }
                    }
                }
            }
            // onFailure 故意什么都不做（失败静默）。
        }
    }

    /** 记账键：每条会话各自记「抽到第几条用户消息」。 */
    private fun memoryExtractKey(conversationId: String) = "extracted_$conversationId"

    /**
     * 联网搜索 → 一段可以拼进上下文的「搜索资料」文本。**1.983 新增。**
     *
     * ## ⚠️⚠️ 这个函数永远不抛异常
     *
     * 搜索是**锦上添花**：它挂了绝不能把对话也带下水。
     * 所以整段包在 `try/catch (Throwable)` 里（`CancellationException` 单独放行），
     * 任何失败都**静默降级成空串** ⇒ 调用方拿到的空串就等于「没开搜索」，
     * 照旧走原来的对话。**不许弹窗、不许中断、不许给用户报错。**
     *
     * ## 五条设计决定（都写在提示词 §1.4 的规则里）
     *
     * 1. **只搜「最后一条用户消息」**：省钱（按次计费）、也让 query 准。
     *    ⚠️ **已知局限**：多轮对话里最后一句常是「那呢？」这种指代，
     *    直接拿去搜会搜歪。官方有 `ori_question` 字段可以补充原始问题 ——
     *    **本版没用它**，理由见下。
     * 2. **质量闸门 `rerank_score >= 1.5`**（`SEARCH_MIN_SCORE`）。
     *    官方文档明说「≥1.5 为优质」，**实测对得上**（好结果 1.8~2.3、
     *    乱码冷门词只有 0.47~0.52）。全是低分就说明这次没搜到有用的
     *    ⇒ **干脆不拼进上下文**，比硬塞一堆不相关网页干净得多
     *    （那些网页还会白烧 token，**反而更贵**）。
     * 3. **`summary` 必须截断**：实测最长 **3409 字**（远超文档说的 600~800）。
     *    每条截到 `SEARCH_SUMMARY_CHARS`、总量封顶 `SEARCH_CONTEXT_CHARS`。
     *    ⚠️ 截断在**字符边界**上做 —— Kotlin 的 `String` 是 UTF-16，
     *    `take(n)` 按 **code unit** 切，对中文（BMP 内、单 code unit）是安全的；
     *    只有 emoji 这类代理对才可能被切成两半，所以下面用
     *    [safeTruncate] 再挡一道（**不许把 UTF-8 切坏**）。
     * 4. **`date` 可能为 `null`** ⇒ 拼文本时要优雅降级（**别输出 "null"**）。
     *    `SearchHit.date` 那里已经把显式 null 收敛成 `null` 了，这里只管不显示。
     * 5. **失败静默**：见上。
     *
     * ## 为什么没用 `ori_question`
     *
     * 它的用途是「把多轮对话里的原始问题一起发过去，帮搜索引擎理解指代」。
     * 本版没用的理由：要正确使用它，得先判断「这一句是不是指代」——
     * 而那个判断本身就需要一次模型调用（或一套启发式规则），
     * **成本与收益不成比例**（多一次调用比省下的那点搜索质量贵得多）。
     * 这条局限**如实写进报告**，等用户反馈「搜歪」再针对性解决。
     *
     * @param history 已经过预算裁剪的历史（用来取最后一条用户消息当 query）。
     * @return 拼好的搜索资料；任何失败 / 没有优质结果 ⇒ **空串**。
     */
    private suspend fun searchContext(history: List<ChatMessage>, apiKey: String): String {
        val query = history.lastOrNull { it.role == ChatRole.USER }?.content?.trim().orEmpty()
        if (query.isEmpty()) return ""

        return try {
            val hits = searchClient.search(apiKey = apiKey, query = query, count = SEARCH_COUNT)
            // ★ 质量闸门：只留官方认定「优质」的（≥1.5）。
            val good = hits.filter { it.rerankScore >= SEARCH_MIN_SCORE }
            if (good.isEmpty()) return ""
            buildSearchBlock(query, good)
        } catch (e: CancellationException) {
            // 用户切走 / 停止 ⇒ 照常往上传，别吞掉取消信号
            throw e
        } catch (e: Throwable) {
            // ★ 静默降级：搜不到就当没开搜索。**这一行是这个功能安全性的全部。**
            ""
        }
    }

    /** 把优质结果拼成一段给模型读的「搜索资料」。**总量封顶 [SEARCH_CONTEXT_CHARS]。** */
    private fun buildSearchBlock(query: String, hits: List<SearchHit>): String {
        val body = StringBuilder()
        for ((i, hit) in hits.withIndex()) {
            val piece = buildString {
                append("【${i + 1}】")
                if (hit.title.isNotBlank()) append(hit.title.trim())
                // ⚠️ date 可能为 null ⇒ 整段跳过，**绝不输出字面量 "null"**
                hit.date?.takeIf { it.isNotBlank() }?.let { append("（").append(it.trim()).append("）") }
                append("\n")
                append(safeTruncate(hit.summary.trim(), SEARCH_SUMMARY_CHARS))
                append("\n")
            }
            // 封顶：加了这一段会超预算就不加了（宁可少给几条，也不超预算）
            if (body.length + piece.length > SEARCH_CONTEXT_CHARS) break
            body.append(piece)
        }
        if (body.isEmpty()) return ""
        return buildString {
            append("以下是与问题相关的联网搜索结果，供参考（可能不完整或有误，")
            append("请结合你自己的知识判断，不要照抄）：\n")
            append(body)
            append("\n用户的问题：")
            append(query)
        }
    }

    /**
     * 按**字符边界**截断，且不让代理对（emoji 之类）被切成两半。
     *
     * ⚠️ 直接 `s.take(n)` 对中文是安全的（BMP 内单 code unit），
     * 但结尾正好是 emoji 的**高位代理**时会切出一个孤立代理 ——
     * 那玩意拼进 JSON 会变成替换字符。所以末尾若落在代理对中间就再退一位。
     */
    private fun safeTruncate(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end -= 1
        return s.substring(0, end) + "…"
    }

    /**
     * 把搜索资料拼在**最后那条 user 消息前面**（提示词 §1.4 的选项 A）。
     *
     * ⚠️⚠️ **返回的是一份新 List，绝不写回 store** —— 这是「不落盘」的实现点。
     * `ChatMessage` 是 data class，`copy` 出来的是新对象；
     * `history` 本身是 `trimToBudget` 的返回值（已经是新 List），
     * 所以这里连「改动到了别处」的可能都没有。
     *
     * 为什么选 A 而不是 B（插一条额外 system 消息）：
     *  * **A 只动一条消息** ⇒ 消息条数不变，`history` 的结构与不发搜索时一致；
     *  * B 会多插一条消息，而 `buildBody` 里 system 只认第一个参数、
     *    并不遍历 history 里的 system —— 要支持 B 得改 `buildBody`，
     *    那会碰到**对话主链路**（风险大得多）。
     *  * A 在语义上也更贴切：「资料 + 这个问题」本来就是一条用户输入。
     */
    private fun injectSearch(
        history: List<ChatMessage>,
        searchBlock: String,
    ): List<ChatMessage> {
        if (searchBlock.isBlank() || history.isEmpty()) return history
        val lastUserIndex = history.indexOfLast { it.role == ChatRole.USER }
        if (lastUserIndex < 0) return history
        return history.mapIndexed { i, m ->
            if (i != lastUserIndex) m
            else m.copy(content = searchBlock + "\n\n" + m.content)
        }
    }

    /**
     * 模型总结标题。用便宜的非推理模型 + 低温度，失败了就用首条问题的截断兜底。
     * 整个流程刻意「静默」：标题拿不到不该影响聊天。
     *
     * 只在「这条会话还没有过标题」时跑（`titleGenerated` 是那道闸）。
     * 用户主动要求重算走 [regenerateTitle]。
     */
    private fun generateTitleIfNeeded(conversationId: String) {
        val conversation = find(conversationId) ?: return
        if (conversation.titleGenerated) return
        // 角色会话**直接跳过**：它的标题恒等于角色名（建会话时就置位了），
        // 叫模型去总结一遍纯属白烧钱，而且总结出来的标题还会把角色名冲掉。
        if (conversation.personaId != null) return

        val (question, answer) = titleSource(conversation) ?: return

        val apiKey = settings.value.apiKey
        scope.launch {
            val generated = runCatching {
                client.complete(
                    apiKey = apiKey,
                    systemPrompt = TITLE_PROMPT,
                    userContent = "用户：${question.take(300)}\n助手：${answer.take(600)}",
                )
            }.getOrNull()

            val title = cleanTitle(generated) ?: provisionalTitle(question)
            update(conversationId) { it.copy(title = title, titleGenerated = true) }
            commit()
        }
    }

    /**
     * 从一条会话里取出「拿给标题生成器看的一问一答」。
     *
     * 取的是**第一条提问**和**第一条真的回答了的内容**（跳过错误提示和空回答）——
     * 会话往后越聊越远，标题该概括的是开头那个话题，不是最后一句。
     *
     * ⚠️ **1.985：只有 [generateTitleIfNeeded]（自动、首算）还用它。**
     * [regenerateTitle]（用户手动重算）改成了「第一条提问 + **最后一条有效回答**」，
     * 理由见那边 —— 用户反馈「多次重新生成均没有变化」，根因就是两个入口
     * 都只看开头那段，输入不变 ⇒ 输出也不变。
     *
     * 两处共用的是**兜底与清洗**逻辑（[cleanTitle] / [provisionalTitle]），
     * 取材分开是 1.985 有意为之，**不是漏改**。
     *
     * 任何一半取不到就返回 null ⇒ 调用方放弃（没提问 / 没有有效回答时都不该起标题）。
     */
    private fun titleSource(conversation: Conversation): Pair<String, String>? {
        val question = conversation.messages.firstOrNull { it.role == ChatRole.USER }?.content
            ?: return null
        val answer = conversation.messages
            .firstOrNull { it.role == ChatRole.ASSISTANT && !it.error && it.content.isNotBlank() }
            ?.content
            ?: return null
        return question to answer
    }

    // ------------------------------------------------------------- 状态修改

    private fun patchAssistant(
        conversationId: String,
        content: String,
        reasoning: String,
        done: Boolean = false,
    ) {
        update(conversationId) { conversation ->
            val index = conversation.messages.indexOfLast { it.role == ChatRole.ASSISTANT }
            if (index < 0) return@update conversation
            val patched = conversation.messages.toMutableList()
            patched[index] = patched[index].copy(content = content, reasoning = reasoning)
            conversation.copy(
                messages = patched,
                updatedAt = if (done) System.currentTimeMillis() else conversation.updatedAt,
            )
        }
    }

    private fun replaceLastAssistant(conversationId: String, message: ChatMessage) {
        update(conversationId) { conversation ->
            val index = conversation.messages.indexOfLast { it.role == ChatRole.ASSISTANT }
            if (index < 0) return@update conversation
            val patched = conversation.messages.toMutableList()
            patched[index] = message
            conversation.copy(messages = patched, updatedAt = System.currentTimeMillis())
        }
    }

    private fun appendMessage(conversationId: String, message: ChatMessage) {
        update(conversationId) { conversation ->
            conversation.copy(
                messages = conversation.messages + message,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun dropTrailingEmptyAssistant(conversationId: String) {
        update(conversationId) { conversation ->
            val last = conversation.messages.lastOrNull()
            if (last == null || last.role != ChatRole.ASSISTANT || last.content.isNotEmpty()) {
                conversation
            } else {
                conversation.copy(messages = conversation.messages.dropLast(1))
            }
        }
    }

    /**
     * 把一条 usage 累加进会话。
     *
     * 这里**只做累加、绝不覆盖**：覆盖的话，一条流里万一服务端发两次 usage
     * （某些实现会在中间也带一次），第二次就把第一次冲掉了；累加天然幂等——
     * 一次加到一次的量，两次加到两次的量。
     *
     * cache 两个字段同理：它们只在带真数据的那个 chunk 上有值，
     * 中间的全零占位在 `parseDelta` 那道 `prompt > 0 || completion > 0` 上
     * 就已经被挡掉了，所以这里加到的永远是「这一轮真实命中/未命中」的量。
     *
     * 注意它**不主动 commit**：usage 是在流式收尾时到的，紧跟着外面那套
     * 「结束时统一 commit」走，这里改内存态就够了，别在流中间触发写盘。
     */
    private fun addUsage(
        conversationId: String,
        prompt: Long,
        completion: Long,
        cacheHit: Long,
        cacheMiss: Long,
    ) {
        update(conversationId) { conversation ->
            conversation.copy(
                promptTokens = conversation.promptTokens + prompt,
                completionTokens = conversation.completionTokens + completion,
                cacheHitTokens = conversation.cacheHitTokens + cacheHit,
                cacheMissTokens = conversation.cacheMissTokens + cacheMiss,
            )
        }
    }

    /**
     * 就地更新一条会话（命中才改、命中才发射）。
     *
     * ## ⚠️ 隐式契约（1.992 写下来，之前没人写）
     *
     * 1. **命中才发射、未命中完全不发射**。
     *    旧的 `.map {}` 写法在未命中时也返回内容相同的新 List，
     *    靠 `MutableStateFlow` 的 `equals` 判等（`Conversation` 是 data class，
     *    逐元素比较）兜住「不发射」；1.992 起 `index < 0 return` 把它变成显式行为。
     *    ⚠️ 若哪天 `Conversation` 的 `equals` 语义变了（如实现成引用比较），
     *    「改前 `.map{}` 会发射、改后不发射」就会分叉 —— 那时这里要重新审。
     * 2. **[transform] 不许就地修改 `messages`**，必须 `copy()` 出新实例。
     *    `Conversation` **没有标 `@Immutable`**（1.992 明确决定不加，理由见
     *    whalechat-1.992-实现-prompt.md §2.5：commit() 每次新建容器，加了也白加，
     *    还欠一笔「就地改就 UI 停更且不报错」的永久债）⇒
     *    Compose 靠**实例变化**判断重组，就地改 = UI 停更。
     *
     * ## 为什么从 `.map {}` 改成 `indexOfFirst`（1.992，S-1-B）
     *
     * **纯可读性收益，性能收益可忽略**（如实写，别夸大）：
     * 两种写法都 O(n) 分配新容器；未命中的实例在两种写法下都不拷贝；
     * 省下的只是「命中后不再继续遍历」——会话数几十条，纳秒级。
     * 换 `indexOfFirst` 的真实价值是语义直白（「找到那一条，改它」）
     * 且 `transform` 的调用点显式。
     */
    private inline fun update(conversationId: String, transform: (Conversation) -> Conversation) {
        val current = _conversations.value
        val index = current.indexOfFirst { it.id == conversationId }
        if (index < 0) return
        val list = current.toMutableList()
        list[index] = transform(list[index])
        _conversations.value = list
    }

    /**
     * 会话落盘的实体部分，**调用方必须已经持有 [writeLock]**。
     *
     * 抽出来是因为除了 [commit] 这条「异步写」的路，还有一条**同步写**的路：
     * [savePersonaFromPhone] 改了角色名之后要连着会话标题一起在返回 HTTP 响应之前
     * 落盘。两边共用同一个过滤和同一把锁，不然「空会话不写进文件」这条规则
     * 迟早只在其中一边生效。
     */
    private fun saveConversationsNow() {
        // 空会话不写进文件：它是内存里的过渡态，不是历史记录。
        // 不在这过滤的话，别的会话随便触发一次 commit，就会顺手把首页点过＋
        // 但没说话的占位一起写进 conversations.json —— 下次启动它们又回来了。
        runCatching { store.save(_conversations.value.filter { it.messages.isNotEmpty() }) }
    }

    /** 排序 + 落盘，只在消息边界调用，流式过程中不写盘。 */
    private fun commit() {
        _conversations.value = _conversations.value.sortedByDescending { it.sortKey }
        io.launch {
            writeLock.withLock { saveConversationsNow() }
        }
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 兜底标题（第一句提问的截断）。**同样走词边界**（理由见 [cleanTitle]），
     * 否则英文提问的兜底标题也会出现半截词。
     */
    private fun provisionalTitle(question: String): String {
        val cleaned = question.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length <= TITLE_MAX) return cleaned
        val head = cleaned.take(TITLE_MAX)
        val lastSpace = head.lastIndexOf(' ')
        return if (lastSpace > 0) head.take(lastSpace).trimEnd() else head
    }

    /**
     * 把模型可能带上来的引号、序号、句末标点都削掉，并按 [TITLE_MAX] 截断。
     *
     * ## ⚠️ 1.985：截断改成**词边界优先**（「Apple Intell」bug 的修法）
     *
     * 用户原话：「标题是 apple intelligence 却显示不全」。实测根因：
     * 英文标题按 `take(TITLE_MAX=12)` **字符截** ⇒ `"Apple Intelligence"(18)` 变
     * **`"Apple Intell"`** —— 单词被拦腰切断。中文没有这个问题（字就是词），
     * 所以这刀只对**含空格的（英文类）标题**做词边界处理：
     *
     *  * 截断点正好落在词中间 ⇒ **退到上一个空格**（`"Apple Intelligence"` → `"Apple"`）；
     *  * 截断点在空格/词尾 ⇒ 原样保留。
     *
     * 配套把 [TITLE_PROMPT] 也改了（「英文不超过 3 个词、在完整单词边界结束」），
     * 双保险：模型尽量给短的，万一给了长的也不会出现半截词。
     */
    private fun cleanTitle(raw: String?): String? {
        val firstLine = raw?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
        val stripped = firstLine
            .replace(Regex("^(标题|主题)\\s*[:：]\\s*"), "")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '《', '》', '。', '，', '、', '.', ',', ':', '：')
            .trim()
        if (stripped.isEmpty()) return null
        if (stripped.length <= TITLE_MAX) return stripped

        // 含空格 ⇒ 词边界截断（英文/混排）；退到上一个空格，别把词切一半。
        val head = stripped.take(TITLE_MAX)
        val lastSpace = head.lastIndexOf(' ')
        return if (lastSpace > 0) head.take(lastSpace).trimEnd() else head
    }

    /** 手表列表宽度有限，标题超过这个字数就会被截断成省略号。 */
    private const val TITLE_MAX = 12

    /**
     * 大肥鱼「2.3 强覆盖只跑一次」的标记位键。
     *
     * 存在 `whalechat_persona` prefs 里（[personaPrefs]），只被
     * [seedBuiltinPersonasIfNeeded] 读写。
     *
     * ⚠️ 键名里的 `_23` 是版本号，不是序号：它和 2.1 的 `_21` 是**两个不同的键**，
     * 所以老用户盘上那个旧键还在、但已经不读了 —— 覆盖会**再跑一次**，
     * 这正是升版本号的目的。下次再整份换人设时同样要升成 `_24`，别复用旧键。
     *
     * 2.34 起它**仍然保留**：2.2 直接升到 2.34 的用户要先把人设换成 2.3 的手表版，
     * 再被 [KEY_FISH_SKINS_APPLIED_34] 换成五皮肤的基础款（顺序见种子函数）。
     */
    private const val KEY_FISH_DEFAULTS_APPLIED_23 = "fish_defaults_applied_23"

    /**
     * 大肥鱼「2.34 换成五皮肤」强覆盖只跑一次的标记位键。
     *
     * 和上面那个是**两个独立的键**，理由相同：升版本号就是为了让覆盖再跑一次。
     * 覆盖动作 = 把那条鱼换成基础款皮肤 + 把 [KEY_FISH_SKIN_CHOSEN] 打回 false，
     * 于是 2.33 的老用户第一次点大肥鱼会走一次选择流程。
     */
    private const val KEY_FISH_SKINS_APPLIED_34 = "fish_skins_applied_34"

    /**
     * 「小女儿已下架」这次一次性迁移的标记位（2.38）。
     *
     * 每次冷启动检查一次「这条鱼是不是小女儿」，是就覆盖成基础款，然后记下这件事。
     * 用**独立的键**（不是复用 `_34`）：`_34` 只表示「换过五皮肤」，
     * 而这次是「删掉其中一份」 —— 两件事的先后顺序也不一样，合并会互相干扰。
     * 下次再有皮肤下架，照这个模式加 `_39` / `_xx`，不要复用本键。
     */
    private const val KEY_FISH_DAUGHTER_REMOVED_38 = "fish_daughter_removed_38"

    /**
     * 「MC老登已下架」这次一次性迁移的标记位（2.381）。
     *
     * 独立键、独立标记：两次下架是两次产品决定，合并会互相干扰；
     * 下次再删皮肤照这个模式加新键，**不要复用**。
     */
    private const val KEY_FISH_MC_REMOVED_381 = "fish_mc_removed_381"

    /**
     * 「老用户还没决定：保留原人设 / 换新性格」的标记位（2.39）。
     *
     * 值只在「升级上来、已有聊天记录、从未选过皮肤」时置 true；
     * 用户在第一次选择页上做了选择（保留或换皮肤）后立刻清掉。
     * **不要**把它当成「用户选过皮肤」的替代 —— 那个仍然是 `fish_skin_chosen`。
     */
    private const val KEY_FISH_UPGRADE_CHOICE_PENDING = "fish_upgrade_choice_pending"

    /** 当前皮肤，存 [FishSkinKey.prefsValue]（`base` / `mowan` / …）。默认基础款。 */
    private const val KEY_FISH_SKIN_KEY = "fish_skin_key"

    /**
     * 用户有没有**主动选过**皮肤。
     *
     * ⚠️ 和 [KEY_FISH_SKIN_KEY] 是**两个问题**，别合并成一个「键存在就算选过」：
     * 强覆盖会写 `fish_skin_key = base`，但用户一次都没点过选择页。
     * 首启选择页的判据只能是这个布尔值（见 [fishSkinChosen]）。
     */
    private const val KEY_FISH_SKIN_CHOSEN = "fish_skin_chosen"

    /**
     * 带上文的条数上限（一问一答算两条）。
     *
     * 取 20 条 ≈ 最近十轮对话，对手机表上的追问场景够用；
     * 再往上加只会让首包更慢、更贵，而手表屏幕也回看不了那么多。
     */
    private const val HISTORY_MAX_MESSAGES = 20

    /**
     * 角色会话的条数上限，比 ask 放宽到 30 条。
     *
     * 情感陪伴比问答更依赖连贯的记忆：TA 该记得住你上周说过什么，而 ask 模式
     * 只关心眼下这一问。字数预算不动 —— 真正的上限是它，条数只是顺手放的闸。
     */
    private const val PERSONA_HISTORY_MAX_MESSAGES = 30

    /** 角色名在列表标题位和气泡标签上的长度上限，跟 [Persona.name] 的约定一致。 */
    private const val PERSONA_NAME_MAX = 6

    /** 带上文的字符预算。约等于 5k token 量级，离上下文上限还很远，不会触发 400。 */
    private const val HISTORY_MAX_CHARS = 8_000

    // ------------------------------------------------------------ 联网搜索（1.983）

    /**
     * 一次搜索要几条。官方范围 1~50，默认 10。
     *
     * 取 5 的理由：真正进上下文的还要过 `rerank_score >= 1.5` 的质量闸门
     * （实测一提问通常只有 1~3 条优质结果），要再多也只会被闸门滤掉，
     * 却已经**按次计过费**了。
     */
    private const val SEARCH_COUNT = 5

    /**
     * **质量闸门**：官方文档明说「`rerank_score` ≥ 1.5 为优质」，**实测对得上**
     * （好结果 1.8~2.3；乱码冷门词只有 0.47~0.52）。
     *
     * ⚠️ 低于这个分的一律**不拼进上下文** —— 全是低分说明这次没搜到有用的，
     * 硬塞一堆不相关网页不只会让回答跑偏，还会**白烧 token（反而更贵）**。
     */
    private const val SEARCH_MIN_SCORE = 1.5

    /**
     * 每条 `summary` 的截断长度。**实测最长 3409 字**（远超文档说的 600~800），
     * 不截的话一次搜索就能吃掉几千 token。
     *
     * 取 500：约 3~4 段摘要的量，够模型读出要点。
     */
    private const val SEARCH_SUMMARY_CHARS = 500

    /**
     * 整块搜索资料的字符上限（**同时是给历史预算让出来的那一块**，见 [trimToBudget]）。
     *
     * 取 2000 的理由：`HISTORY_MAX_CHARS` 是 8000，让出 1/4 给搜索资料，
     * 历史仍有 6000 字（≈10 轮对话），够用；
     * 而 2000 字能装下 4 条 500 字摘要 —— 比一次问到的优质结果还多一点。
     *
     * ⚠️ 这个值**必须与 `trimToBudget` 里扣的那一笔一致**，否则「历史 + 搜索」
     * 的总量就不是 `HISTORY_MAX_CHARS` 了。两处都读这个常量，**别各写一个字面量**。
     */
    private const val SEARCH_CONTEXT_CHARS = 2_000

    /**
     * 从最新往回留，直到用满条数或字数预算。
     *
     * 至少保留一条：哪怕单条就超预算（比如粘了一大段代码），
     * 也得把这句话发出去，否则用户按了发送却永远得不到回答。
     *
     * @param persona 角色会话放宽条数到 [PERSONA_HISTORY_MAX_MESSAGES]、字数预算不变。
     * @param budgetChars 字数预算覆盖。**1.983 联网搜索用**：开了搜索时要给它留出位置
     *   （见 [SEARCH_CONTEXT_CHARS]），所以历史这边得把预算让出来一块，
     *   否则「历史 8000 字 + 搜索 2000 字」= 10000 字，超出原来的预算。
     *   ⚠️ 默认值仍是 [HISTORY_MAX_CHARS]，**不开搜索时行为与 1.982 逐字相同**。
     */
    private fun trimToBudget(
        messages: List<ChatMessage>,
        persona: Boolean = false,
        budgetChars: Int = HISTORY_MAX_CHARS,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val maxMessages =
            if (persona) PERSONA_HISTORY_MAX_MESSAGES else HISTORY_MAX_MESSAGES
        var budget = budgetChars
        val kept = ArrayDeque<ChatMessage>()
        for (message in messages.asReversed()) {
            if (kept.size >= maxMessages) break
            val size = message.content.length
            if (kept.isNotEmpty() && budget - size < 0) break
            budget -= size
            kept.addFirst(message)
        }
        return kept.toList()
    }

    private const val TITLE_PROMPT =
        "你是会话标题生成器。读一段对话，输出一个概括其主题的中文短标题。" +
            "要求：不超过 12 个字；英文标题不超过 3 个单词；" +
            "不要引号、书名号、标点或句号；不要任何解释、前缀或序号；只输出标题本身。" +
            "如果对话主题是英文，输出英文标题；必须在完整的单词边界处结束，不要截断单词。"
}
