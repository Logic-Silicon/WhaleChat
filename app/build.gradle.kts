import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 本地私密配置都放工程根的 keystore.properties：签名口令 + 可选的预置 API Key。
 * 这个文件不进版本库。读不到就跳过签名配置，只影响 assembleRelease，不影响 assembleDebug。
 *
 * 之所以借这个文件装 API Key：手表圆屏上敲 35 位 sk- 太痛苦，
 * 出个自带 Key 的包能省掉这件事。文件不存在或没写这一项时，App 行为完全不变。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** 命令行走 -PpresetApiKey=… 可以临时覆盖，优先级高于 properties 文件。 */
val presetApiKey: String =
    (project.findProperty("presetApiKey") as String?)
        ?: keystoreProps.getProperty("presetApiKey")
        ?: ""

/**
 * 讯飞语音听写的凭证，同样是「出包时烤进去」。
 *
 * 讯飞要给三样东西（AppID / APIKey / APISecret），两个 32 位字符串加一个数字，
 * 在手表圆屏输入法上敲一遍比 DeepSeek 那个 sk- 还难受，所以一视同仁地预置。
 *
 * 讯飞那边固定走「语音听写（流式版）」（iat-api.xfyun.cn/v2/iat）：控制台里另外
 * 几档大模型要么只对历史已购用户开放，要么连端点都不可用，所以不留档位开关。
 */
fun prop(name: String): String =
    (project.findProperty(name) as String?) ?: keystoreProps.getProperty(name) ?: ""

val presetXfyAppId: String = prop("presetXfyAppId")
val presetXfyApiKey: String = prop("presetXfyApiKey")
val presetXfyApiSecret: String = prop("presetXfyApiSecret")

/**
 * 预置凭证时要不要顺手把识别引擎也切过去。
 *
 * 只预置凭证、不给引擎，用户装完还得自己去设置里把「识别引擎」从「系统」改成
 * 「讯飞听写」—— 那预置这件事就白做了。留空时：给了讯飞凭证就切讯飞，
 * 没给就保持不动。取值 system / xfyun。
 */
val presetAsrEngine: String = prop("presetAsrEngine")

/**
 * **联网搜索的基地址**（1.983 新增）。
 *
 * 为什么不写死在 Kotlin 里：`SearchClient` 需要一个**可测**的地址 ——
 * 本地联调时得把它指到宿主上的假搜索服务，否则那条链路端到端根本没法验。
 * 而它又**不该**变成用户能改的运行时设置（那是多一个可以被改坏的输入，
 * 而且用户没有任何理由去改官方地址）。
 *
 * ⇒ 折中是**出包时注入的编译期常量**：
 *  * **默认（不传参）= 官方地址**，release 与 debug 一样；
 *  * 联调时 `-PsearchBaseUrl=http://127.0.0.1:8901` 指到假服务，**不用改源码**。
 *
 * ⚠️ **正式出包绝对不要带这个 -P 参数**（`build_release.sh` 里没有它）。
 * 交付时会用 dex 字符串抽查核对包内是官方地址。
 */
val searchBaseUrl: String =
    (project.findProperty("searchBaseUrl") as String?)?.takeIf { it.isNotBlank() }
        ?: "https://api.deepseek.com"

/**
 * **对话 API 的基地址**（1.983 新增，同样是为了「可测」）。
 *
 * 默认 = 官方地址。存在的理由与 [searchBaseUrl] 一样：
 * 要证明「搜索资料确实拼进了发给模型的请求」，就得让一个**假 chat 服务**
 * 把收到的 body 原样打出来 —— 那是机理级证明，比看截图硬得多。
 *
 * ⚠️ 它**同时影响** `DeepSeekClient`（对话）与 `UsageStore`（余额查询）——
 * 两者本来就共用同一个 base，一起指过去是符合预期的。
 * ⚠️ 正式出包**绝不带**（`build_release.sh` 里没有它）。
 */
val chatBaseUrl: String =
    (project.findProperty("chatBaseUrl") as String?)?.takeIf { it.isNotBlank() }
        ?: "https://api.deepseek.com"

/** 生成的 Java 字符串字面量。凭证里万一有引号/反斜杠，不转义会直接编不过。 */
fun String.asLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * 版本号。
 *
 * ## 递进规则（2026-11-01 第二次调整，用户要求：改回 +0.001）
 *
 * **每次改动 +0.001**：`2.38` → `2.381` → `2.382` → … → `2.389` → `2.39`。
 * 2.31 → 2.38 那一小段用过 **+0.01**；用户 2026-11-01 明确要求改回 1.9xx 时代的 +0.001。
 * 大版本（动第一位，例如 `3.0`）留给人看得出来是阶段变化的时刻。
 *
 * ## versionCode 由 versionName 算，别手写
 *
 * `主×1000 + 次右侧补 0 到 3 位`：
 * `2.3` → 2300、`2.31` → 2310、`2.38` → 2380、`2.381` → 2381、`2.389` → 2389、
 * `2.39` → 2390、`3.0` → 3000。
 * 这个公式能保证**按版本号顺序递增**，覆盖安装不会 `INSTALL_FAILED_VERSION_DOWNGRADE`。
 *
 * ⚠️ **次版本最多 3 位**：写 4 位（`2.3811`）会算出 23811，虽然还单调，但和
 *    `2.39`(2390) 的间距语义就乱了；更重要的是**别写反顺序**（出了 2.381 之后又回 `2.38`），
 *    versionCode 会降，装覆盖包直接失败 —— 典型症状是「明明改了代码、装机后画面还是旧的」。
 *
 * ## 历史（不要删）
 *
 *  * **2.0 是正式版**（2026-10-18），从 1.991 直接跳过来：2.0 是「阶段变化」那一档。
 *  * **2.1（2026-10-24）**：中间的「2.001」版本号作废（用户原话「代码保留，只作废版本号」），
 *    之后第一个真正的功能更新走 2.1。
 *  * **2.3（2026-10-31）**：P0 对话节奏改造（输出规矩 8 条 + 大肥鱼手表版人设 + 强覆盖 _23）。
 *  * **2.31（2026-11-01）**：回复里的公式/Markdown 显示改造 + 长按菜单按官方样例回归；
 *    从这一版起版本号按 **+0.01** 递进。
 *  * **2.32（2026-11-01）**：P1「此刻」—— 角色会话每轮注入一行【此刻】
 *    （几点 / 多久没聊 / 它此刻在干嘛）。
 *  * **2.33（2026-11-01）**：输入页不再自动拉起系统输入界面（黑屏修法，用户选 A）。
 *  * **2.34（2026-11-01）**：大肥鱼换成五皮肤 + 首启选择页（强覆盖 _34，选过才进会话）。
 *  * **2.35（2026-11-01）**：打开即说 —— 隔 4 小时以上打开角色会话，TA 先开口
 *    说 1~3 句（本地拼，不联网、不调模型）。
 *  * **2.36（2026-11-01）**：多气泡 —— 模型回复按行拆成多个气泡（只改渲染，数据仍是一条）。
 *  * **2.37（2026-11-01）**：长期记忆 —— 每 6 条用户消息抽一次「值得记住的事」，每个角色 ≤10 条、全局 ≤40 条。
 *  * **2.38（2026-11-01）**：删除「小女儿」皮肤（用户拍板），其余四份保留；已选过她的设备会一次性换回基础款。
 *  * **2.381（2026-11-01）**：删「MC老登」皮肤（剩三份）；老用户升级不再强改人设，
 *    先给「保留原来的大肥鱼 / 换个性格」的选择。从这一版起版本号改回 **+0.001** 递进。
 *  * **2.382（2026-11-01）**：多气泡**修好后回归** —— 名字移到气泡组上方、
 *    气泡用显式内边距并按内容收缩（解决首泡更高 / 短句大空泡）。
 *  * **2.383（2026-11-01）**：多气泡**只作用于角色会话**；Ask 回复恢复单卡
 *    （2.382 的拆气泡把 Ask 也牵连了）。
 *
 * （别用 `java.math.BigDecimal`：Kotlin DSL 里 `java` 这个名字被 JavaPluginExtension
 * 占了，`java.math` 会报 `Unresolved reference: math`。）
 */
val appVersionName = "2.383"
val appVersionCode = appVersionName.split(".").let { parts ->
    require(parts.size == 2 && parts[0].toIntOrNull() != null) {
        "versionName「$appVersionName」不是「主.次」的写法（约定是 2.31 / 2.32 / 3.0 …）"
    }
    parts[0].toInt() * 1000 + parts[1].padEnd(3, '0').toInt()
}

android {
    namespace = "com.naoh.whalechat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naoh.whalechat"
        // Wear OS 3.0 起可用；莫奈取色在 API 31+ 才拿得到系统色板，低版本自动回退基线主题
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // 预置 Key 走 BuildConfig 而不是 resources：resources 里的值更容易被 aapt 之外
        // 的工具 dump 出来，放 BuildConfig 是常规做法，也方便 R8 处理。
        // 默认空串 = 不预置，App 行为与「设置页手填」完全一致。
        buildConfigField("String", "PRESET_API_KEY", "\"$presetApiKey\"")

        // 讯飞凭证，同样默认空串 = 不预置，App 行为与「设置页手填」完全一致
        buildConfigField("String", "PRESET_XFY_APPID", presetXfyAppId.asLiteral())
        buildConfigField("String", "PRESET_XFY_APIKEY", presetXfyApiKey.asLiteral())
        buildConfigField("String", "PRESET_XFY_APISECRET", presetXfyApiSecret.asLiteral())
        buildConfigField("String", "PRESET_ASR_ENGINE", presetAsrEngine.asLiteral())

        // 联网搜索的基地址（1.983）。默认官方地址；联调时用 -PsearchBaseUrl 覆盖。
        // ⚠️ 用 asLiteral() 转义：用户传进来的 URL 里万一有引号/反斜杠，不转义会编不过。
        buildConfigField("String", "SEARCH_BASE_URL", searchBaseUrl.asLiteral())

        // 对话 API 的基地址（1.983）。同上，默认官方地址、联调时 -PchatBaseUrl 覆盖。
        buildConfigField("String", "CHAT_BASE_URL", chatBaseUrl.asLiteral())
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // debug 包在手表上会明显卡：debuggable=true 时 ART 关掉了大部分编译优化。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
