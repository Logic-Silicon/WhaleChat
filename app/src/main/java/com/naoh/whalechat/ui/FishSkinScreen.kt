package com.naoh.whalechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.naoh.whalechat.data.ChatEngine
import com.naoh.whalechat.data.FishSkin
import com.naoh.whalechat.data.FishSkins

/**
 * 大肥鱼皮肤选择页（2.34）。
 *
 * ```
 * ── 挑一条鱼 ──
 * 大肥鱼            当前
 * 毒舌串子，爱吃白米饭
 * 「在」
 * 魔丸
 * 更毒的那条，嘴下不留情
 * 「哟」
 * ...
 * ```
 *
 * ## 三种模式，同一页
 *
 *  * **首启模式**（[restartPersonaId] 为 null、没有待定的升级选择）：第一次点通讯录里的
 *    大肥鱼，或者从预设页「添加」还没选过皮肤的那条鱼。标题「挑一条鱼」。
 *  * **升级保留模式**（2.39，`ChatEngine.fishUpgradeChoicePending()` 为 true）：
 *    老用户已经聊过、且从没选过皮肤 —— 人设保持原样，标题「大肥鱼更新了」，
 *    最上面一张「保留原来的大肥鱼」，下面才是「或者换个性格」。
 *  * **重开模式**（[restartPersonaId] 非 null）：「重新开始」长按菜单进来的。
 *    标题「换个性格，重新开始」。选完会**清空聊天记录**再进会话。
 *
 * 判据只看这个参数**在不在**，不看它的具体值：这一页永远只服务大肥鱼
 * （四个皮肤共用一个 id，换皮肤 = 原地改字段），拿别的角色 id 进来也只会改鱼。
 * 真正的改动在 [ChatEngine.applyFishSkin] / [ChatEngine.keepLegacyFish] 里，
 * 这里只负责选和导航。
 *
 * ## 为什么不二次确认
 *
 * 文档口径：**点卡片立即生效**。理由站得住 —— 皮肤随时能再换（再点一次就是了），
 * 而「重新开始」本来就是一个用户自己按下的动作，再弹一层确认只是多一次点击。
 * 但「重开模式会清空记录」这件事**必须写在页面上**，不能只藏在按钮名里 ——
 * 见下面那句 [ListSubHeader] 之后的说明文案。
 *
 * ## 返回
 *
 * 什么都不做：手势返回就是退出这一页。首启模式下会回到通讯录，下次点鱼**还会**
 * 再进这一页（因为 `fish_skin_chosen` 仍是 false）；重开模式下聊天记录原样不动。
 *
 * ## M3E 形态
 *
 * 与项目其它列表页同一套：`ScreenScaffold` + `ScalingLazyColumn`（圆屏自动缩放、
 * 滚动指示器）+ `surfaceContainer` 卡片 + 56dp 以上的圆角容器。
 * 没给每张卡做独立配色/图标：几张卡要让人一眼比的是**性格文案**，
 * 五颜六色反而抢戏，也和设置页那组卡片不是一套语言。
 */
@Composable
fun FishSkinScreen(
    restartPersonaId: String?,
    onPicked: (conversationId: String) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val restart = restartPersonaId != null
    // ★ 2.39：老用户（已有聊天记录、没选过皮肤）会看到第三种模式 ——
    //   标题「大肥鱼更新了」，最上面多一张「保留原来的大肥鱼」。
    //   注意：这个标记只在升级路径上出现一次，用户做过选择后就清掉了。
    val legacyPending = ChatEngine.fishUpgradeChoicePending()
    // 「当前」角标只对**已经选过**的用户有意义。没选过时 prefs 里读出来是基础款，
    // 但那个不是用户的选择，标「当前」会误导（好像默认已经生效了）。
    val currentKey = if (ChatEngine.fishSkinChosen()) ChatEngine.currentFishSkin().key else null

    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = { ScrollIndicator(state = listState) },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            // 不做自动居中：几张卡 + 标题已经超过一屏，居中会让滚动位置来回跳
            // （和预设详情页同一条判据）。
            autoCentering = null,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") {
                ListSubHeader {
                    Text(
                        when {
                            legacyPending -> "大肥鱼更新了"
                            restart -> "换个性格，重新开始"
                            else -> "挑一条鱼"
                        },
                    )
                }
            }

            // 2.39：只有「升级上来、已经聊过、还没选过皮肤」的老用户会看到这张卡。
            // 点它 = 人设一个字都不改，直接进会话（见 ChatEngine.keepLegacyFish）。
            if (legacyPending) {
                item(key = "keep_legacy") {
                    Card(
                        onClick = { ChatEngine.keepLegacyFish()?.let(onPicked) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = "保留原来的大肥鱼",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "不换性格，继续用现在的人设",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item(key = "legacy_hint") {
                    // 这句是**产品承诺**：用户最担心的就是「更新把我的人设/记录弄没了」。
                    HintText("选这个不会改人设，也不会删聊天记录。")
                }

                item(key = "or_header") { ListSubHeader { Text("或者换个性格") } }
            }

            FishSkins.ALL.forEach { skin ->
                item(key = "skin_${skin.key.name}") {
                    SkinCard(
                        skin = skin,
                        isCurrent = skin.key == currentKey,
                        onClick = {
                            // applyFishSkin 负责：改六块 → 落盘 → 记 prefs →（可选）清空重开。
                            // 返回 null 只有「鱼已经被删掉」这一种可能，那就什么都不做。
                            ChatEngine.applyFishSkin(skin, restart = restart)?.let(onPicked)
                        },
                    )
                }
            }

            item(key = "hint") {
                HintText(
                    when {
                        legacyPending -> "换了性格只改人设和开场白；聊天记录不会删。"
                        restart -> "点了就换。聊天记录会清空，TA 会用新的开场白重新开始。"
                        else -> "点了就换，随时能再换。之后长按「重新开始」还能改。"
                    },
                )
            }

            item(key = "bottom_gap") { GapItem(28.dp) }
        }
    }
}

/**
 * 一张皮肤卡：**名字 / 副标题 / 开场白第一句**。
 *
 * 三行的分工是刻意的：
 *  * 名字——「这是谁」；
 *  * 副标题——「什么味道」（官方给的一句话定位）；
 *  * 开场白第一句——「实际开口是什么样」。前两行都是**描述**，只有它是一句真话，
 *    用户扫这一行才知道自己挑的是「在 / 说吧」还是「来了 / 又是哪个包出问题了」。
 *
 * 当前皮肤那张卡右上角挂一个「当前」药丸（只在重开模式下会出现，
 * 首启模式还没选过，见 [FishSkinScreen] 里的 `currentKey`）。
 */
@Composable
private fun SkinCard(
    skin: FishSkin,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = skin.name,
                    style = MaterialTheme.typography.titleMedium,
                    // 名字有 6 字上限（[PERSONA_NAME_MAX]），但用户可能改过名字、
                    // 也可能将来有人把皮肤名写长了 —— 这里不让它把「当前」顶出圆屏。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isCurrent) {
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(percent = 50),
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = skin.tagline,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "「${skin.openingLine}」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
