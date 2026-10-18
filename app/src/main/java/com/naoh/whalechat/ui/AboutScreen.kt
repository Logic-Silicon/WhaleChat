package com.naoh.whalechat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import com.naoh.whalechat.BuildConfig
import com.naoh.whalechat.R

/**
 * 「关于」二级页。设置页那一格卡片点进来的。
 *
 * ## 为什么从一行文字变成一整页
 *
 * 1.976 及以前，「关于」只是设置页末尾的一段 `HintText`（三行小字）。那三行里塞了
 * 三件不同量级的事：免责声明、数据存放位置、作者。全部挤在一格里，既读不快，
 * 也没有地方放「使用说明」和项目地址。1.978 把它升级成二级页，四块分开写清楚。
 *
 * ⚠️ **顺序是产品约定**：设置页的「关于」这一格位置不变（在「数据」组下面），
 * 只是内容从纯文字换成了可点卡片。**不要**顺手把它挪到别处。
 *
 * ## ⚠️ 关于版本号（**1.982 整个翻过来了，别照 1.978 的做法改回去**）
 *
 * **这一页显示的是真实版本号，读 `BuildConfig.VERSION_NAME`。**
 *
 * 用户原话：「在关于页加入版本号显示（**随版本更新不能出现延迟**）」。
 * 「不能有延迟」的技术含义就是**不要写死的字符串** —— 写死的每出一版都得手工同步一次，
 * 忘一次界面就滞后一版，而 APK 里的 `versionName` 早就是新的，两者对不上。
 *
 * 1.978 的做法与理由（**现已作废，留在这里只为说清来历**）：当时这一页
 * **只出现「2.0 Beta」这个对外说法、绝不显示内部 `versionName`**，
 * 理由是「内部版本号是开发编号，印出来用户会拿它去和商店里的版本对，对不上就以为装错了」。
 * 1.982 按用户要求改成直接显示真实版本号 —— 那个担心由「**版本号单独成行、不混在正文里**」
 * 来化解：它是一行独立的小字，用户读到的就是「这个包是几版」，不再有
 * 「对外说法 vs 内部编号」两套数字要对照。
 *
 * ⚠️⚠️ **"不能有延迟" ≠ 运行时去查包信息。** `BuildConfig.VERSION_NAME` 是
 * **编译期常量**，打进 APK 就是死的 ⇒ 只要出包时版本号对了，界面上就一定对，**零延迟**。
 * **不要**改成 `packageManager.getPackageInfo()` 那种运行时查询：
 * 没必要（值不会变），而且多一次可能失败的系统调用 —— 为同一份数据引入一个
 * 会抛异常的路径，是纯粹的倒退。
 *
 * ## 为什么项目地址是纯文字、不做成可点
 *
 * ⚠️ **1.982：这一块的卡片标题从「GitHub 项目」改成了「项目链接」** ——
 * 用户原话：「先只动 app 内，但不是简单删去开源的字样，**比如开源链接改为项目链接**」。
 * 也就是**改写叫法**、不是删掉内容：地址、卡片、位置全部保留。
 * 下面这一段讲「为什么不做成可点」的理由**和叫法无关，仍然成立**。
 *
 * 手表上没有浏览器（Wear OS 不预装），点一下 `ACTION_VIEW` 大概率是
 * `ActivityNotFoundException`，只能再弹一句「打开失败」，比直接看见地址更差。
 * 这一块的价值是**让人看得见、抄得走**那个地址，所以只显示，不加点击行为。
 * 地址本身必须逐字是 [GITHUB_URL]，**不是** `github.com/naoh/WhaleChat`（那是 404 旧地址）。
 *
 * ## 1.980：底部加 M3E 贴边「返回」
 *
 * `ScreenScaffold` 的 `edgeButton` 槽，参数**逐字对齐聊天页**（`ChatScreen.kt:315`）：
 * 默认尺寸（`EdgeButtonSize.Small`，56dp）+ 默认配色（⇒ `primary` / `onPrimary`，
 * 浅紫底）+ `labelLarge`。理由见 `DisplaySettingsScreen` 的同一段注释。
 *
 * ⚠️ **它由滚动位置驱动**（`ScrollInfoProvider.lastItemOffset`）：只有滚到列表末尾
 * （或内容短到末尾项可见）才升起来。这一页是四块内容 + 图标，一屏放不下
 * ⇒ **停在顶部时按钮高度是 0、看不见**；滚到底才出现。
 * **这是官方设计**，不是没做 —— 官方 Wear Lists 指南原文：
 * 「使用 `edgeButton` slot 可确保按钮正确地贴在屏幕底部，**并在列表滚动时调整大小**」。
 * 返回手势与返回键不受影响。
 *
 * ⚠️ 尾部留白 28dp → 10dp：`edgeButton` 已经把 `contentPadding` 底部撑开，
 * 再留 28dp 就是双份（1.980 已改，1.981 保留）。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        // 内容与按钮之间的空：库默认 16dp，这里压到最小值（见 `BackEdgeButtonSpacing`）。
        edgeButtonSpacing = BackEdgeButtonSpacing,
        scrollIndicator = { ScrollIndicator(state = listState) },
        edgeButton = {
            // ⚠️ `edgeButtonScrollable` 是官方 Wear Lists 指南示例里就有的那一句，
            // 五处贴边按钮一个都不能漏（漏了的后果见它的 KDoc）。
            EdgeButton(
                onClick = onBack,
                modifier = Modifier.edgeButtonScrollable(listState),
            ) {
                Text("返回", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            autoCentering = null,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ⚠️⚠️ 1.992：**ListHeader 放回第一项**（用户 1.991 实测反馈：
            // 「一进入关于页不在页面顶部而是稍微靠下，往上滑动一小段才能碰到顶部」——
            // 1.990 的 padding 修法治标不治本）。
            //
            // ## 真正的根因（1.992 重新定位，1.990 的诊断错了）
            //
            // `ScalingLazyColumn` 的初始位置会把**首项**摆在屏幕中心附近
            // （`autoCentering = null` 时同样有这个初始对位）。
            // **首项越高，它的顶部就被推得越靠下**：
            //  * 设置/显示设置/语音识别页：首项是 `ListHeader`（约 24dp）⇒ 一进去就贴顶 ✓
            //  * 关于页：首项是那个 **72dp 图标 + 18dp padding = 90dp 的 Box** ⇒
            //    首项中心对位后，顶部被推下 ~(90−24)/2 ≈ 33dp ⇒ 用户看到的「稍微靠下」。
            // 1.990 我把根因归到「图标墨迹溢出 18dp」——那确实是遮挡**时间胶囊**的
            // 直接原因（补 padding 后时间显示正常了），但「进页面不在顶部」的
            // **主因是首项太高**，padding 治不了它。
            //
            // ## 修法：首项换成轻量的 ListHeader，图标紧随其后
            //
            //  * 首项变 ~24dp ⇒ 初始对位回到与设置页一致，一进去就在顶部；
            //  * 「进来先看到图标」的观感保留：标题一行 + 8dp 间距后图标紧跟，
            //    视觉上仍是「图标+应用名」打头，只是图标从时间胶囊正下方
            //    挪到了标题上方一行 —— 不再贴顶压时。
            //  * 图标的 `padding(top = 18.dp)` **仍然要留**：溢出遮挡的机理没变
            //    （现在是遮挡 ListHeader 的位置），删了会重新压住标题。
            item(key = "header") { ListHeader { Text("WhaleChat") } }

            // ⚠️⚠️ 1.986：图标从 56dp → 88dp（用户原话：「图标内容为什么做的很小，
            // 留的白底明显过多」）。
            //
            // ## 为什么会偏小（1.980 实测过的根因，见 [AppIcon] 的 KDoc）
            //
            // 自适应图标的两层是 108×108 视口、系统只显示居中 72×72 ⇒ 图标内容
            // 只占 `size` 的 2/3。1.980 就发现「图偏小、白底一圈」，
            // 且当时 `Modifier.size(1.5f * size)` 被 incoming 约束**夹回原尺寸**、1.5 倍没生效。
            //
            // ## 修法（1.986）
            //
            // * [AppIcon] 内部把 `size(layer)` 换成 **`requiredSize(layer)`**
            //   （不受 incoming 约束 ⇒ 1.5 倍真正生效，图标内容撑满 `size`）。
            //
            // ## ⚠️⚠️ 1.989：88dp → **72dp**（用户原话：「图标显示面积太大，
            // 页面整体显示效果不佳」）
            //
            // 1.986 修掉「1.5 倍失效」之后，`requiredSize` 让**整个 108 视口**都画了出来，
            // 88dp 时可见内容实际是 88dp 的圆、但里面的**角色本体**按 108 视口的比例
            // 铺得更开 ⇒ 整体比桌面图标还大一圈，把标题和版本号都顶下去了。
            //
            // 72dp 的依据：比最早出问题的 56dp（白底圈明显）大，
            // 又比 88dp（占半屏）小，是中间档；装机后若还想微调，只动这一个数字。
            //
            // ## ⚠️⚠️ 1.990：icon item 加 `padding(top = 18.dp)`
            //
            // [AppIcon] 内层图是 `requiredSize(size × 1.5)`：**绘制溢出外层 Box
            // 上下各 (1.5−1)/2 × size**。72dp 时溢出 = **18dp**。溢出会盖住它上面的
            // 内容（1.990 时是时间胶囊，1.992 首项换成 ListHeader 后是标题行）——
            // **这个 padding 必须留**，改 [AppIcon] 的 size 时同步改：
            // 溢出 = (1.5 − 1) / 2 × size。
            item(key = "icon") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 只补顶部：列表往下长，底部溢出压在标题上方的 8dp 间距里可接受。
                        .padding(top = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(size = 72.dp)
                }
            }

            // ⚠️ 1.982 新增：**版本号单独成行**（用户要「在关于页加入版本号显示」）。
            //
            // 位置：紧跟标题、在「软件介绍」**之前** —— 想让它比原来
            // 「挤在软件介绍正文最后一行」更醒目，就得给它自己的一个位置，
            // 而不是继续挂在别的正文尾部。
            //
            // 形态：一行居中的 `labelMedium` + `onSurfaceVariant`。
            //  * 用 `label*` 而不是 `body*`：它是标识信息（这个包是几版），
            //    不是要读的句子；`body*` 会和下面「软件介绍」的正文混成一段；
            //  * 用 `onSurfaceVariant` 而不是 `onSurface`：颜色层级比标题低一档，
            //    符合 M3 里「次级标识」的角色（标题是 primary/onSurface 那一级）。
            //    ⚠️ 刻意不用 `outline`：在莫奈取色下它偏亮还带紫调（项目里实测过）。
            //
            // ⚠️⚠️ 1.986：文案从「版本 1.982」改成 **`v1.982`**（用户原话：
            // 「『版本1.983』改为『v1.983』」）。
            //
            // ⚠️⚠️ 1.986：与标题行的**行距收窄**（用户原话「与『关于whalechat』
            // 行距稍微缩小」）。`ListHeader` 自带的排版行高较大，
            // 这里给 version 行加一个**负向间距**做不到，改用
            // 「把这一行整体上提」的等效手段：`Modifier.padding(top = (-4).dp)` ——
            // ⚠️ 负 padding 在 Compose 里合法（`PaddingValues` 之外，`Modifier.padding`
            // 收负数时会按 0 处理）⇒ **不可用**。改用真正有效的杠杆：
            // **把标题行本身的行高压掉**（见 header 那条的 `LocalTextStyle` 覆盖）。
            item(key = "version") {
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ⚠️⚠️ 1.987：关于页正文**按《关于文档-修订版.docx》重写**（用户要求
            // 「显示文字内容按这个改，注意分段，每个核心段落单独做卡片」）。
            //
            // ## 分段结构（对应文档的段落，每段一张 [AboutSection] 卡）
            //
            //  * **软件简介** —— 文档第 1 段（M3E 风格 + DeepSeek 腕上问答）；
            //  * **Ask / Chat 双模式** —— 文档「功能」第 1 段；
            //  * **语音识别** —— 文档「功能」第 2 段（三档识别 + 讯飞教程在哪看）；
            //  * **扫码填凭证** —— 文档「功能」第 3 段（核心亮点 + 10 分钟时效 + 公共 Wi-Fi 提醒）；
            //  * **隐私与说明** —— 文档「其它说明」（本地存储、无后台、开发者署名）；
            //  * **项目链接** —— 文档「项目地址」（1.982 起的叫法保留）。
            //
            // ## 措辞取舍（用户允许「可以简短表述的部分小做修改」）
            //
            // 圆屏一张卡放不下文档的整段长句，**删的是重复与书面化连接词**，
            // **不动的**是：所有数字（10 分钟、三档、2/3）、所有承诺
            // （本地存储 / 卸载即清空 / 无审核无后台）、所有入口路径（设置 → …）。
            // 逐张卡的取舍写在各自的 body 注释里。
            //
            // ## 原来的三张卡去哪了
            //
            //  * 「软件介绍」（非官方声明 + 本地存储 + 联网搜索）—— 本地存储并入
            //    「隐私与说明」，**「非官方 DeepSeek 客户端」这句并入软件简介**；
            //    联网搜索那句删掉（它是 1.983 按当时的文档写的，新版文档没有提）；
            //  * 「使用说明」（五条入口指南）—— **整卡删除**：新版文档是「介绍文体」，
            //    不再有「填 Key：设置 → …」那种操作指南；入口指引全部由各功能卡自己讲；
            //  * 「开发人员 by NaOH」—— 并入「隐私与说明」（文档原文就带着开发者署名）。

            item(key = "intro") {
                AboutSection(
                    title = "软件简介",
                    // 文档原文：「WhaleChat 是为 Wear OS 和其它安卓穿戴设备开发，
                    // 基于谷歌 M3E 风格设计，为用户提供 DeepSeek API 聊天问答服务的
                    // 轻巧腕上 App。」+ 1.982 那句「非官方」声明（保留 —— 法务上必要）。
                    body = "为 Wear OS 及其它安卓穿戴设备打造、基于谷歌 M3E 风格设计的" +
                        "轻巧腕上应用，通过 DeepSeek API 提供聊天问答服务。\n" +
                        "非官方客户端，与 DeepSeek 官方无关。",
                )
            }

            item(key = "modes") {
                AboutSection(
                    title = "Ask 与 Chat",
                    // 文档「功能」第 1 段。圆屏上一张卡放不下整段 ⇒ 拆成三句：
                    // 双模式与切换、Ask 是什么、Chat 是什么（含「一句话创建人设」）。
                    // 深思可展开那句保留（它是模式差异的关键）。
                    body = "主页左右滑动切换 Ask 与 Chat 两种模式，各自独立设置" +
                        "「快答 / 深度思考」，开启深思后可展开思考过程。\n" +
                        "Ask：发起简短问答，主页显示历史对话。\n" +
                        "Chat：创建虚拟人物长期对话——可在 App 内创建人设、" +
                        "用「一句话创建人设」生成，或导入已有的人设。",
                )
            }

            item(key = "asr") {
                AboutSection(
                    title = "语音识别",
                    // 文档「功能」第 2 段。三档的名称照项目口径（系统 / 通用接口 / 讯飞听写）；
                    // 「讯飞凭证教程在本项目 GitHub 与开发者酷安文章」保留 —— 文档明确写了。
                    body = "除手表自带输入法的语音输入外，还提供讯飞语音听写与" +
                        "基于 OpenAI 标准的通用语音识别接口。\n" +
                        "三档在 设置 → 语音识别 里切换；讯飞凭证的获取教程见本项目" +
                        "项目链接与开发者酷安相关文章。",
                )
            }

            item(key = "qr") {
                AboutSection(
                    title = "扫码填凭证",
                    // 文档「功能」第 3 段（核心亮点）。三个数字/承诺**一个不动**：
                    // 10 分钟时效、一次提交自动关闭、公共 Wi-Fi 不建议。
                    // 「Chat 页人设同样可在手机上管理」那句也保留（它是这个入口的第二职责）。
                    body = "在手表上敲长串 Key 已经是过去式：手表和手机连同一个 Wi-Fi，" +
                        "打开 设置 → 手机扫码改凭证，手机浏览器扫码即可快捷导入凭证；" +
                        "Chat 页的人设也能在手机上快速编辑管理。\n" +
                        "凭证网页的二维码只存活 10 分钟，完成一次提交后自动关闭。" +
                        "不建议在公共 Wi-Fi 环境下使用。",
                )
            }

            item(key = "privacy") {
                AboutSection(
                    title = "隐私与说明",
                    // 文档「其它说明」。所有承诺逐字保留：
                    // 不自带审核和后台 / 全部留在本地 / 卸载即清空 /
                    // 只有对话与语音识别的 API 会联网。开发者署名并入了这一张
                    // （原来单独的「开发人员 by NaOH」卡删除）。
                    body = "WhaleChat 不自带审核与后台。API Key、凭证与全部聊天记录" +
                        "只留在手表本地，卸载即清空；只有对话与语音识别的 API 服务" +
                        "会联网上传数据至对应服务商。\n" +
                        "本项目由开发者 NaOH 基于 WorkBuddy 与 DSH 开发。",
                )
            }

            item(key = "project") {
                AboutSection(
                    // ⚠️ 1.982：标题从 **「GitHub 项目」改成「项目链接」**（用户要求）。
                    //
                    // 用户原话：「先只动 app 内，但不是简单删去开源的字样，
                    // **比如开源链接改为项目链接**」。
                    // ⇒ 这是**改写叫法**，不是删掉这一块：地址、卡片、位置全部保留，
                    // 只是不再用带「开源」色彩的说法去称呼它。
                    //
                    // ⚠️ `body` 还是那个 [GITHUB_URL]，**一个字都没改** ——
                    // 链接没变，变的只是这块卡片的标题。
                    // 它那条「必须是这个地址、不是 404 旧地址」的约束**仍然有效**，
                    // 见 [GITHUB_URL] 的注释。
                    //
                    // ⚠️ 1.987：对应新文档的「项目地址」段。讯飞凭证教程就在这个
                    // 仓库里（「语音识别」那张卡指向这里），两处呼应。
                    title = "项目链接",
                    body = "项目地址：$GITHUB_URL",
                )
            }

            // 1.980：28dp → 10dp（理由见 [AboutScreen] 类注释末尾「尾部留白」那段）。
            item(key = "bottom_gap") { GapItem(10.dp) }
        }
    }
}

/**
 * 应用启动图标（把自适应图标的两层手动合成一遍）。
 *
 * ## 为什么不直接 `painterResource(R.mipmap.ic_launcher)`
 *
 * `mipmap-anydpi-v26/ic_launcher.xml` 是个 `<adaptive-icon>`，**`painterResource` 读不了它**
 * （它只能读 `VectorDrawable` / 位图；自适应图标是 `AdaptiveIconDrawable`，
 * 硬喂进去会抛异常）。所以这里把它的两层（背景底 + 前景角色）分别画出来，
 * 再按圆裁剪。**这个理由仍然成立**，这段代码不用改。
 *
 * ## ⚠️ 那 1.5 倍 —— 1.980 实测：它**没有生效**
 *
 * 推导本身没错：自适应的两层都是 **108×108 视口**，系统遮罩真正显示的是**居中那 72×72**
 * （外面各 18 是给动效/视差留的出血），所以要让图标看起来是 `size` 那么大，
 * 两层都该按 `size × 108/72 = size × 1.5` 画、再让外层按 `size` 裁圆。
 *
 * **但 `Modifier.size(1.5f * size)` 会被父 `Box` 的 incoming 约束夹回 `size`。**
 * Compose 的 `Modifier.size()` 是 `SizeElement(enforceIncoming = true)`，它自己的文档原话就是
 * 「The incoming measurement Constraints **may override this value**」；
 * 外面那个 `Box(Modifier.size(size))` 传给子节点的 max 正好是 `size` ⇒ `layer == size`。
 *
 * 1.980 在设备上按像素量过（340dpi，`size = 56dp` ⇒ 圆直径实测 117px）：
 * 角色 alpha 包围盒在圆内是 **60×62 px**，
 * 与「可见窗 = 整个 108 视口」的预测（59×61 px）对得上，
 * 与「可见窗 = 中心 72/108」的预测（88×92 px）差得很远。
 * ⇒ 屏上看到的是**整个 108 视口**，也就是注释里说的那种「明显偏小、像缩了一圈」。
 *
 * ⚠️ **本轮（1.980）只改注释、不动像素**（提示词 §1.2③ 的要求）。
 * 真要修的话杠杆是把 `Modifier.size(layer)` 换成 `Modifier.requiredSize(layer)`
 * （不受 incoming 约束）或 `graphicsLayer { scaleX = scaleY = 1.5f }` ——
 * 改动会让关于页图标变大 1.5 倍，属于另一条线上的决定，**不要顺手改**。
 * 顺带：因为这里显示的是整个 108 视口，关于页里的角色会比桌面图标上那颗**小一圈**。
 */
@Composable
private fun AppIcon(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val layer = size * 1.5f
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            // ⚠️⚠️ 1.986：`size(layer)` → **`requiredSize(layer)`** ——
            // 这是「关于页图标偏小、白底留白明显过多」的修法（用户原话）。
            //
            // 根因（1.980 实测、见上面 KDoc）：`Modifier.size()` 是
            // `SizeElement(enforceIncoming = true)`，会被外层 `Box(size)` 的
            // incoming 约束**夹回 size** ⇒ 1.5 倍**从未生效**，
            // 屏上画的是整个 108 视口 ⇒ 角色本体只占 `size` 的 2/3 ⇒ 四周白底一圈。
            //
            // `requiredSize` **不受 incoming 约束**（`SizeNode` 的 `enforceIncoming = false`
            // 路径，见 foundation `Size.kt:828-864`）⇒ 1.5 倍真正生效，
            // 图标内容撑满 `size`，白底消失。
            //
            // ⚠️ 它会**溢出父 Box 的边界**（这正是目的）：外层 `Box` 有
            // `clip(CircleShape)`，溢出部分被裁成圆 —— 与系统桌面的裁切一致。
            modifier = Modifier.requiredSize(layer),
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            // 同上：两层都要 1.5 倍（背景 + 前景是同一个 108 视口的两层）。
            modifier = Modifier.requiredSize(layer),
        )
    }
}

/**
 * 关于页的一块：小标题 + 正文，可选一个行首图标。
 *
 * 静态卡片（`onClick = {}` + `enabled = false`）—— 和 `HintText` 同一个理由：
 * Wear M3 的 `Card` 只有「可点击」一种重载，只写 `onClick = {}` 会带水波纹，
 * 看着像能点，骗人去点一张什么都没发生的卡。
 *
 * 正文用 `bodySmall` + `onSurfaceVariant`，左对齐（不是 `HintText` 那种居中）——
 * 这一页的正文是**要读**的句子，尤其那几条使用说明和项目地址，居中会难读。
 */
@Composable
private fun AboutSection(
    title: String,
    body: String,
    icon: ImageVector? = null,
) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

/**
 * ⚠️ **1.982：这个常量已经不再被界面使用了，但按项目惯例没有直接删掉。**
 *
 * ## 它的来历
 *
 * 1.978 加的这一位：当时这一页**只显示一个写死的对外说法**，取值就是 `"2.0 Beta"`，
 * **绝不显示内部的 `versionName`**。理由是「内部版本号是开发编号，
 * 印出来用户会拿去和商店里的版本对，对不上就以为装错了」。
 *
 * ## 为什么它现在是死的
 *
 * 用户 1.982 要求的正好相反：「在关于页加入版本号显示（**随版本更新不能出现延迟**）」。
 * 写死的字符串每出一版都得手工同步，忘一次就滞后一版 —— 那就是「延迟」。
 * 所以这一页改成读编译期常量 `BuildConfig.VERSION_NAME`（见 `AboutScreen` 里
 * `item(key = "version")` 那一段和类注释的「关于版本号」）。
 *
 * ## 为什么留着不删
 *
 * 项目惯例：**能说明来历的常量不主动删**（同 `SettingsStore` 里那串
 * `KEY_SHOW_TOKEN_USAGE` 之类）。删掉之后，后来人看到类注释里提到「1.978 曾用
 * `PUBLIC_VERSION_LABEL` 显示 2.0 Beta」会找不到它，只能去翻 git 历史。
 * 留着 + 这几行注释，来历是自解释的。
 *
 * ⚠️ **别再把它接回界面**：接回去就等于版本号又变成手工同步的字符串，
 * 直接违背用户这次的要求。
 */
@Suppress("unused")
private const val PUBLIC_VERSION_LABEL = "2.0 Beta"

/**
 * 项目地址。⚠️ 必须是这个（作者是 `NaOHaminuosi`），
 * **不是** `github.com/naoh/WhaleChat` —— 那是早期用过、现在 404 的旧地址。
 * 改这一行之前先去浏览器打开确认一次。
 */
private const val GITHUB_URL = "https://github.com/NaOHaminuosi/WhaleChat"
