package com.naoh.whalechat.ui

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.PersonaField
import com.naoh.whalechat.data.PresetField
import com.naoh.whalechat.data.personaPresets
import com.naoh.whalechat.voice.VoicePhase
import com.naoh.whalechat.voice.rememberVoiceInput

/** 全屏文字输入页服务的各种目标。 */
sealed interface InputTarget {
    data object ApiKey : InputTarget
    data object SystemPrompt : InputTarget

    /** 通用接口（OpenAI 兼容 ASR）的接口地址。1.982 加回来 */
    data object AsrUrl : InputTarget

    /** 通用接口的 Key。**允许留空** —— 本机 whisper.cpp 一类的服务不鉴权 */
    data object AsrKey : InputTarget

    /** 通用接口的模型名 */
    data object AsrModel : InputTarget

    /** 讯飞的 AppID */
    data object XfyAppId : InputTarget

    /** 讯飞的 APIKey */
    data object XfyApiKey : InputTarget

    /** 讯飞的 APISecret */
    data object XfyApiSecret : InputTarget

    /**
     * 发消息。
     *
     * 从对话页那个键盘按钮进来，或者在设置里手打目标时用。
     * 这一页的麦克风是**另一条路**：它跟随设置里的「识别出字之后」，
     * 开着就先填字让你改，关掉就说完即发（见下方 rememberVoiceInput）。
     *
     * [draft] 是从对话页带过来的待改文字 —— 录音还没发出去时按了键盘按钮，
     * 那句识别结果就落在这儿，用户接着改。空串表示没有草稿
     * （直接按键盘按钮进来），这一页从空开始。
     *
     * [rewriteIndex] 非 null 表示这次是**编辑重发**：进来的是某条已经发过的提问
     * （[draft] 就是它的原文），发送时要把那条及其之后的全部内容作废重来，
     * 而不是当成一句新话追加进去。空串草稿在这儿不成立，所以两件事不会混。
     * ⚠️ 2.1 起不限于「最新那一问」：任意一条用户消息都能编辑重发，
     * 后果警告（后面会删掉几条）由 ChatScreen 的弹窗先给过。
     */
    data class Chat(
        val conversationId: String,
        val draft: String = "",
        val rewriteIndex: Int? = null,
    ) : InputTarget

    /**
     * 「自己说一句」：把想要的角色描述出来。
     *
     * 提交之后**不在这里导航**，只把文字交给 `ChatEngine.personaDraft` ——
     * 这一页离开时那个 `onDispose` 兜底只能写数据、不能导航（那里 navigate 是未定义行为）。
     * 往前去等待页的那一步由新建页观察到草稿之后发起，见 [ChatEngine.personaDraft]。
     */
    data object PersonaDescription : InputTarget

    /** 预设详情页里改某一格。[fieldKey] 对应 `PresetField.key`。 */
    data class PresetFieldEdit(val presetIndex: Int, val fieldKey: String) : InputTarget

    /**
     * 改已有角色的一个**短字段**（名字 / 简介 / 开场白）。
     *
     * 只有这三格走这里：六块里的其余五块是大段文字，在这块表上敲不现实，
     * 它们只在手机那一页改（见 `PersonaDetailScreen`）。
     *
     * 名字叫 `PersonaFieldEdit` 而不是 `PersonaField`，是为了不和
     * `data.PersonaField` 那个枚举撞名 —— 它就在上面几行，撞了以后
     * 这里每一处都得写全限定名。
     */
    data class PersonaFieldEdit(val personaId: String, val field: PersonaField) : InputTarget
}

/**
 * 手表上的文字输入 —— **不再有内联输入框**，全部走系统的全屏输入界面。
 *
 * ## 为什么整条换掉（2.001）
 *
 * 2.0 之前这里是 Compose `BasicTextField`，在三星 HoneyBoard 上「打进去的字不刷新」。
 * 1.987~1.991 试过三条修法（永远写回 / 三星不上 Send / 顶部回显条），**真机全部无效**，
 * 2.0 因此整条回滚、把问题挂成 GitHub issue #1。
 *
 * 结论是应用侧无解：问题出在 Compose 的 `InputConnection` 同步路径与 HoneyBoard 的
 * composing 行为之间，键盘是整屏接管的，连「回显」都没地方放。
 *
 * 现在改成 Google 为 Wear 推荐的 `ACTION_REMOTE_INPUT`：输入交给**系统自己的
 * 全屏输入 Activity**，应用只负责把结果拿回来。这条路上根本没有 Compose 的编辑控件，
 * 也就没有任何 IME 兼容问题 —— Keep Notes 走的就是这条路。
 *
 * ## 代价（用户已明确接受）
 *
 * 1. **输入不再内联**：点这一页的输入卡 → 系统输入界面盖上来 → 确认后才回到本页看结果。
 *    ⚠️ 2.33 起**不再自动拉起**（见下面「黑屏」一段），进页面看到的是那张「点这里开始输入」卡。
 * 2. **拿不到预填文本**：`RemoteInput` 协议不支持带初始值进去，改 Key 这类场景
 *    只能整段重输（原值仍显示在预览卡上，方便抄写/对照）。
 * 3. **「离开即提交」的意义变了**：以前它是唯一的提交路径（Gboard 的回车和发送箭头
 *    都是坏的），现在它只是**防误触兜底** —— 用户已经有一次明确的系统界面确认，
 *    真不想发就点「取消」。
 *
 * ## 交互（两段式）
 *
 * 进页面 → **点输入卡** → 系统输入界面 → 确认 → 回到本页看到文字 → 「发送 / 保存」或「取消」。
 * 对话目标在确认那一刻就直接发出（和语音识别完自动发同一套语义），
 * 设置类目标停在页面上等用户复核 —— Key 这种长串输错一位就得重来。
 *
 * 卡片本身就是「输入 / 重新输入」按钮：想改就再点一次。
 *
 * ## ⚠️ 2.33：为什么不再自动拉起（用户实测反馈）
 *
 * 2.001 的取舍是「进页面就把系统输入界面拉起来，少点一次是一次」。但用户 2026-11-01 反馈：
 * **进输入页唤起输入法时大概率黑屏，得返回再点一次输入框才能正常输入**。
 *
 * 机理：这一页拉起的是**两层独立的系统界面** —— 先是系统的 `RemoteInputActivity`
 * （「问点什么 / 表情 / 语音 / 键盘」选择页），再是输入法自己的全屏 Activity
 * （Gboard 的 `WearRemoteInputActivity`，手表上通常是三星/系统键盘）。
 * 两者都是**冷启动**的进程/窗口：在导航转场还没结束时就拉起，会先出现一帧还没画好的
 * 黑色窗口；等用户退回来再点一次时，进程已经是热的，就正常了。
 *
 * 修法（用户选定 A）：**去掉自动拉起，只保留「点输入卡」这一条**。这条路径天然发生在
 * 页面已经稳定、用户主动点击之后，也就是原本「返回再点一次」能成功的那条路。
 * 代价是每次输入多一次点击 —— 换来的是「不再黑屏、不用退出去重来」。
 *
 * ⚠️ 不要为了「少一点一次」把自动拉起加回来；真要加，至少等到
 * `WindowInfo.isWindowFocused` 为真之后再延迟 300ms（但这仍挡不住键盘冷启动）。
 */
@Composable
fun InputScreen(target: InputTarget, onDone: () -> Unit) {
    val settings by ChatEngine.settings.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val isChat = target is InputTarget.Chat

    val title = when (target) {
        // 全名而不是「API Key」：语音那一侧也有 Key，进来只看这一行得能分清是哪一套。
        InputTarget.ApiKey -> "DeepSeek API Key"
        InputTarget.SystemPrompt -> "系统提示词"
        // 三格都带「识别」二字：这一页上还有 DeepSeek 的 Key 和讯飞的 Key，
        // 只说「接口地址」「Key」在手表这一条窄标题上分不出是哪一套。
        InputTarget.AsrUrl -> "识别接口地址"
        InputTarget.AsrKey -> "识别接口 Key"
        InputTarget.AsrModel -> "识别模型名"
        InputTarget.XfyAppId -> "讯飞 AppID"
        InputTarget.XfyApiKey -> "讯飞 APIKey"
        InputTarget.XfyApiSecret -> "讯飞 APISecret"
        // 「问点什么」是主动进来的（对话页那个键盘按钮），「改这句」是长按某条提问来的。
        // 两件事的动作不同，标题也该不同 —— 否则用户看不出这次点「发送」会不会覆盖旧内容。
        is InputTarget.Chat -> if (target.rewriteIndex != null) "改这句重发" else "问点什么"
        InputTarget.PersonaDescription -> "想要什么样的人"
        // 标题直接用这一行的字段名（「称呼」「背景」…），用户改的时候一眼知道在改哪一项
        is InputTarget.PresetFieldEdit -> target.field()?.label ?: "改一改"
        // 同样用字段名当标题：从详情页点进来时，用户刚看到的就是这几个字
        is InputTarget.PersonaFieldEdit -> when (target.field) {
            PersonaField.NAME -> "角色名"
            PersonaField.TAGLINE -> "一句简介"
            PersonaField.GREETING -> "开场白"
        }
    }
    val placeholder = when (target) {
        InputTarget.ApiKey -> "sk-…"
        InputTarget.SystemPrompt -> "留空表示不加设定"
        // 地址要写全：这一格填什么就直接 POST 什么，App 不会帮你补 /v1/audio/transcriptions
        // 那一段（各家路径不一样）。占位符里给个完整样例，省得用户只填主机名然后收 404。
        InputTarget.AsrUrl -> "https://…/v1/audio/transcriptions"
        // 「可留空」必须写出来：本机跑的服务通常不鉴权，而「Key」两个字会让人以为必填
        InputTarget.AsrKey -> "可留空"
        InputTarget.AsrModel -> "FunAudioLLM/SenseVoiceSmall"
        // 讯飞控制台里三样都是十六进制串，长度固定，写出来省得用户怀疑自己抄错了
        InputTarget.XfyAppId -> "8 位十六进制"
        InputTarget.XfyApiKey -> "32 位十六进制"
        InputTarget.XfyApiSecret -> "32 位十六进制"
        is InputTarget.Chat -> "点这里开始输入"
        InputTarget.PersonaDescription -> "比如：一个嘴硬心软的剑修理匠"
        is InputTarget.PresetFieldEdit -> target.field()?.placeholder ?: "可以留空"
        // 长度上限写出来，用户才知道为什么会被截断（手表列表的标题位就这么宽）
        is InputTarget.PersonaFieldEdit -> when (target.field) {
            PersonaField.NAME -> "不超过 6 个字"
            PersonaField.TAGLINE -> "不超过 12 个字"
            PersonaField.GREETING -> "TA 进来说的第一句话"
        }
    }
    // 「生成」而不是「保存」：这一格按下去换来的是一次造人，不是一个存下来的设置项。
    val confirmLabel = when (target) {
        is InputTarget.Chat -> "发送"
        InputTarget.PersonaDescription -> "生成"
        else -> "保存"
    }

    /**
     * 当前内容。
     *
     * ⚠️ 这里**不再需要光标**：文字只在系统输入界面里编辑，本页只负责显示和交付。
     * 所以从 `TextFieldValue` 退回裸 `String` —— 留着那个类型只会让人以为这儿还能编辑。
     */
    var text by remember(target) {
        mutableStateOf(
            when (target) {
                InputTarget.ApiKey -> settings.apiKey
                InputTarget.SystemPrompt -> settings.systemPrompt
                InputTarget.AsrUrl -> settings.asrUrl
                InputTarget.AsrKey -> settings.asrKey
                InputTarget.AsrModel -> settings.asrModel
                InputTarget.XfyAppId -> settings.xfyAppId
                InputTarget.XfyApiKey -> settings.xfyApiKey
                InputTarget.XfyApiSecret -> settings.xfyApiSecret
                // 带草稿进来（录音还没发就按了键盘按钮）就先填上，用户接着改就是；
                // 没草稿则是空的，跟以前完全一样
                is InputTarget.Chat -> target.draft
                // 「自己说一句」永远是空：这一句是从零开始想，没有可继承的旧值
                InputTarget.PersonaDescription -> ""
                // 预设那几格从页面级草稿里取，用户之前改过什么就接着改什么
                is InputTarget.PresetFieldEdit -> ChatEngine.presetDraft.value
                    ?.takeIf { it.presetIndex == target.presetIndex }
                    ?.valueOf(target.fieldKey)
                    .orEmpty()

                is InputTarget.PersonaFieldEdit -> when (target.field) {
                    PersonaField.NAME -> ChatEngine.findPersona(target.personaId)?.name
                    PersonaField.TAGLINE -> ChatEngine.findPersona(target.personaId)?.tagline
                    PersonaField.GREETING -> ChatEngine.findPersona(target.personaId)?.greeting
                }.orEmpty()
            },
        )
    }

    /** 已经提交过一次就别再自动提交（成功路径会把这面旗立起来）。 */
    var submitted by remember { mutableStateOf(false) }

    /** 本地提示（识别失败之类）。 */
    var hint by remember { mutableStateOf<String?>(null) }

    /**
     * 系统输入界面拉不起来时置 true，退回内联输入。
     *
     * 理论上 Wear OS 一定有这个 Activity（`ACTION_REMOTE_INPUT` 从 Wear 1.0 就在），
     * 但这里必须留退路：拉不起来的话用户会面对一张点不出任何反应的卡片，那比
     * 「三星上看得见看不见」糟糕得多。降级路径用的是 2.0 那套内联实现。
     */
    var inlineFallback by remember { mutableStateOf(false) }

    /** 把内容写进对应的目标（发消息 / 存 Key / 存提示词 / 存识别配置），不碰导航。 */
    fun applyTarget(value: String) {
        when (target) {
            InputTarget.ApiKey -> ChatEngine.settings.setApiKey(value)
            InputTarget.SystemPrompt -> ChatEngine.settings.setSystemPrompt(value)
            InputTarget.AsrUrl -> ChatEngine.settings.setAsrUrl(value)
            InputTarget.AsrKey -> ChatEngine.settings.setAsrKey(value)
            InputTarget.AsrModel -> ChatEngine.settings.setAsrModel(value)
            InputTarget.XfyAppId -> ChatEngine.settings.setXfyAppId(value)
            InputTarget.XfyApiKey -> ChatEngine.settings.setXfyApiKey(value)
            InputTarget.XfyApiSecret -> ChatEngine.settings.setXfyApiSecret(value)
            // 编辑重发走的是另一条路：不是往下追加，而是把这条提问及其之后的内容
            // 一起作废再重来（ChatEngine.rewrite 里有理由）。
            is InputTarget.Chat -> {
                val index = target.rewriteIndex
                if (index != null) {
                    ChatEngine.rewrite(target.conversationId, index, value)
                } else {
                    ChatEngine.send(target.conversationId, value)
                }
            }

            // 只落到草稿里，导航由新建页接力 —— 理由见 InputTarget.PersonaDescription
            InputTarget.PersonaDescription -> ChatEngine.setPersonaDraft(value)

            is InputTarget.PresetFieldEdit ->
                ChatEngine.updatePresetDraft { it.with(target.fieldKey, value) }

            is InputTarget.PersonaFieldEdit ->
                ChatEngine.updatePersonaField(target.personaId, target.field, value)
        }
    }

    /**
     * 提交。raw 缺省取当前内容。
     */
    fun submit(raw: String = text) {
        val value = raw.replace('\n', ' ').trim()
        // 空值一律不提交 —— 只有一个例外：改预设的某一格时，**清空就是「这一项不要了」**。
        // 拼生成描述时空白字段本来就会被略过，所以空串在这一处是有意义的输入，
        // 不能被当成「什么都没输入」挡回去（否则预设自带的默认值永远删不掉）。
        //
        // 角色的短字段（名字 / 简介 / 开场白）**不吃这个例外**：这一页的规矩是
        // 「离开 = 提交」，而离开时框里是空的最常见的成因就是手滑 ——
        // 让那一下顺手把简介或开场白抹掉，代价比「手表上清不掉」大得多。
        if (value.isEmpty() && target !is InputTarget.PresetFieldEdit) return
        submitted = true
        applyTarget(value)
        keyboard?.hide()
        onDone()
    }

    /**
     * 系统输入界面的回传。
     *
     * 拿到文字后的处理分两种：
     *  * 对话目标本来就是「说完就发」，直接 submit —— 用户在系统界面按的那一下确认，
     *    和语音识别完自动发是同一套语义；
     *  * 设置类目标先填回本页让用户看一眼再点保存。
     */
    val remoteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // 刻意不判 resultCode：有实现即使确认了也不置 RESULT_OK，只把 data 带回来。
        // 「有没有拿到字」用文字本身判断最靠得住。
        val got = result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(REMOTE_INPUT_KEY)
            ?.toString()
            ?.replace('\n', ' ')
            ?.trim()
            .orEmpty()
        // 取消（没拿到字）就停在本页。用户可以再点卡片重来，或者直接取消离开。
        if (got.isEmpty()) return@rememberLauncherForActivityResult
        text = got
        if (isChat) submit(got)
    }

    /** 拉起系统输入界面；拉不起来就降级成内联输入。 */
    fun launchRemoteInput() {
        // ⚠️ `INPUT_ACTION_TYPE` 这个 extra 是必需的，不是可选项。
        // 不传它，Gboard 的输入界面不知道该把右下角那颗键画成「发送」还是「换行」，
        // 表现是：字能打出来、点了键也清空，但**结果不回传**，页面停在原地。
        // 模拟器实测就卡在这里。取值见 WearableRemoteInputExtender 的反编译结果：
        //   0=SEND 1=SEARCH 2=DONE 3=GO
        // 对话是「发送」，设置类是「保存」，各自对上。
        val actionType = if (isChat) INPUT_ACTION_SEND else INPUT_ACTION_DONE
        val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(title)
            .addExtras(
                Bundle().apply {
                    putInt(EXTRA_INPUT_ACTION_TYPE, actionType)
                },
            )
            .build()
        val intent = Intent(ACTION_REMOTE_INPUT).apply {
            putExtra(EXTRA_REMOTE_INPUTS, arrayOf(input))
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_CONFIRM_LABEL, confirmLabel)
        }
        if (runCatching { remoteLauncher.launch(intent) }.isFailure) {
            inlineFallback = true
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    /**
     * 这一页的麦克风：**说完先出字、你改完再发**，和对话页那支刻意不一样。
     *
     * 差别来自语境：用户已经站在编辑页上，识别错字是常态（「骁龙8gen3」听成「8阵3」），
     * 这一页本来就给了改字的机会，不如先用上。
     */
    val voice = rememberVoiceInput(
        onResult = { spoken -> text = spoken },
        onMessage = { message -> hint = message },
        // 边说边把识别到的字填进来：识别得对不对，当场就能看出来
        onPartial = { partial -> text = partial },
    )

    // ---------------------------------------------------------------- 提交兜底
    //
    // ⚠️ 2.001：提交路径现在是**明确的**（系统界面确认 → 本页按钮），这套兜底的意义
    // 从「唯一的提交方式」降级成「防误触兜底」：在手表这块屏上，边缘滑动很容易被误触，
    // 用户敲完一段话却因为手一滑丢掉，比多存一条更难受。
    //
    // 所以语义保持不变：**有内容、没提交，离开就交出去**。想放弃必须点「取消」
    // （它先把 submitted 立起来，跳过下面两道兜底），或者把内容清空。
    BackHandler(enabled = !submitted && text.isNotBlank()) { submit() }

    // 再兜一层：SwipeDismissableNavHost 的边缘滑动在部分系统上不走 BackHandler。
    // 只写数据、不做导航 —— onDispose 阶段再 popBackStack 是未定义行为。
    DisposableEffect(target) {
        onDispose {
            // 这里读 text 而不是某个快照值：onDispose 只在离开这一页时跑一次，
            // 而 DisposableEffect 的 key 是 target、中途不会重建 —— 任何在组合期
            // 捕获下来的字符串都会是「刚进页面时」的旧值。
            if (!submitted && text.isNotBlank()) {
                submitted = true
                applyTarget(text.trim())
            }
        }
    }

    // 这一页按官方「滚动应用布局」做成可滚的：输入卡 + 状态行 + 操作行三个元素
    // 竖排在一个 ScalingLazyColumn 里。此前是固定 Column —— 预览文本一长
    // （系统提示词这种 119 字的），卡片被撑高，「保存/取消」那行就被顶出圆屏下沿，
    // 按钮只露一半（2.1.1 用户截图）。官方对这类「内容可能超高」的页的答案就是
    // 滚动布局：内容按顺序滚，「按钮列表」排在内容末尾、天然跟着进来。
    //
    // ⚠️ ScreenScaffold 的 scrollState + ScalingLazyColumn 的 autoCentering 组合：
    //   * autoCentering = null + 顶部/底部 GapItem：内容少时不强推到圆心，
    //     首屏和旧版 Column 的观感一致；内容多时能滚到底、按钮完整可点。
    //   * 滚动指示器（ScrollIndicator）交给 ScreenScaffold 的槽，滚起来才出现。
    val scrollState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = scrollState,
        scrollIndicator = { ScrollIndicator(state = scrollState) },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            state = scrollState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 0.dp, bottom = 28.dp),
        ) {
            // 顶部留白：避开时间胶囊。ScreenScaffold 的 contentPadding 已含
            // 顶部 10% 屏高，这里只是给首项一点呼吸（旧版 Column 的 8dp 行距等价物）。
            item(key = "top_gap") { GapItem(4.dp) }

            item(key = "input") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Card(
                        // 这张卡就是「重新输入」：本页没有可编辑的输入框，想改只能回系统界面。
                        onClick = { launchRemoteInput() },
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (inlineFallback) {
                                // 极少数设备才走这里（拉不起系统输入界面）。实现与 2.0 相同，
                                // 包括那条「换行也写回 state」的修法 —— 它是纯行为改进，
                                // 对任何 IME 都无害。
                                BasicTextField(
                                    value = TextFieldValue(text, TextRange(text.length)),
                                    onValueChange = { value ->
                                        val raw = value.text
                                        text = if ('\n' in raw) raw.replace('\n', ' ') else raw
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        imeAction = if (isChat) ImeAction.Send else ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSend = { submit() },
                                        onDone = { submit() },
                                    ),
                                    decorationBox = { innerTextField ->
                                        if (text.isEmpty()) {
                                            Text(
                                                text = placeholder,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        innerTextField()
                                    },
                                )
                            } else {
                                // 只读预览。⚠️ 不设 maxLines：这块屏上长内容宁可撑高一点，
                                // 也别把用户刚确认的那句话截掉一半 —— 他就是要复核它。
                                // 整页现在可滚，卡片撑高不再挤走按钮。
                                Text(
                                    text = text.ifEmpty { placeholder },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (text.isEmpty()) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    FilledIconButton(
                        onClick = {
                            if (voice.phase == VoicePhase.RECORDING) voice.stop() else voice.start()
                        },
                        // 上传识别中再点没有意义（取消不了已经发出去的那次请求），
                        // 与其让它看起来能点、点了没反应，不如明确置灰
                        enabled = voice.phase != VoicePhase.TRANSCRIBING,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (voice.phase == VoicePhase.RECORDING) {
                                AppIcons.Stop
                            } else {
                                AppIcons.Mic
                            },
                            contentDescription = if (voice.phase == VoicePhase.RECORDING) {
                                "结束录音并识别"
                            } else {
                                "语音输入"
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            item(key = "status") {
                // 一行状态说明，三种情况按优先级：
                //  * 本地提示（识别失败等）—— 红字，必须被看见；
                //  * 系统界面拉不起来 —— 说明为什么多出一个内联框；
                //  * 常态 —— 告诉用户这张卡可以点（否则一个不能编辑的框会让人以为坏了）。
                val statusLine = hint ?: when {
                    inlineFallback -> "系统输入界面不可用，已切换为直接输入"
                    else -> "点上方卡片可以修改"
                }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hint != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 官方「元素之间」间距 4dp 的档位；再给操作行留出
                        // 与状态的呼吸空间，滚动到底时按钮完整落在圆内
                        // （ScalingLazyColumn 的 contentPadding.bottom 再兜一层）。
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(
                        onClick = { submit() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp),
                        )
                        Text(
                            text = when {
                                voice.phase == VoicePhase.TRANSCRIBING -> "识别中"
                                voice.listening -> "聆听中"
                                else -> confirmLabel
                            },
                            maxLines = 1,
                        )
                    }
                    TextButton(
                        // 取消 = 明确放弃：先把旗立起来，跳过 BackHandler/onDispose 两道
                        // 提交兜底 —— 否则「取消」也会把内容偷偷存进去，取消就名存实亡
                        onClick = {
                            submitted = true
                            onDone()
                        },
                        modifier = Modifier.width(64.dp),
                    ) {
                        Text("取消", maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/**
 * 找到这次要改的那一行预设字段的定义。
 *
 * 标题、占位文案都从这儿来，不在导航参数里传一份 —— 传一份就多一个会和
 * `PersonaPresets` 对不上的地方。拿不到就返回 null，调用方各自有兜底文案。
 */
private fun InputTarget.PresetFieldEdit.field(): PresetField? =
    personaPresets().getOrNull(presetIndex)?.fields?.firstOrNull { it.key == fieldKey }

// ------------------------------------------------------------------ 系统输入界面的协议
//
// 为什么是「手抄协议」而不是加 `androidx.wear:wear-input` 依赖：
// 那个库只干一件事 —— 拼这几个字符串常量。为了 4 个字符串引入一个 aar，
// 在这台长期离线构建的机器上要额外解决依赖解析，不值。常量取值直接从
// wear-input 1.1.0 的 aar 里反编译核对过（`RemoteInputIntentHelper`）：
//   * ACTION_REMOTE_INPUT —— Intent 的 action
//   * EXTRA_REMOTE_INPUTS —— **RemoteInput 的数组**（不是 ArrayList）
//   * EXTRA_TITLE / EXTRA_CONFIRM_LABEL —— 系统界面上的标题与确认键
// 这三个是 Wear 平台的跨进程契约（命名空间还是 Wear 1.0 时代的
// `android.support.wearable`），不随 AndroidX 版本变。
private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
private const val EXTRA_TITLE = "android.support.wearable.input.extra.TITLE"
private const val EXTRA_CONFIRM_LABEL = "android.support.wearable.input.extra.CONFIRM_LABEL"

/**
 * 输入法的「动作类型」。
 *
 * ⚠️ 这个 extra 挂在 **`RemoteInput` 自己的 extras** 上（`Builder.addExtras`），
 * 不是 Intent 上 —— 反编译 `WearableRemoteInputExtender.get()` 确认过。
 */
private const val EXTRA_INPUT_ACTION_TYPE = "android.support.wearable.input.extra.INPUT_ACTION_TYPE"

/** `INPUT_ACTION_TYPE` 的取值（同一份反编译结果）。 */
private const val INPUT_ACTION_SEND = 0
private const val INPUT_ACTION_DONE = 2

/** 结果回传用的 key，自己定；系统界面只负责原样带回来。 */
private const val REMOTE_INPUT_KEY = "whalechat.input"
