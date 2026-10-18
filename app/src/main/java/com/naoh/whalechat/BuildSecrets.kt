package com.naoh.whalechat

/**
 * 编译期注入的预置凭证。
 *
 * 手表屏幕太小，35 位 `sk-…` 在圆屏输入法上敲一遍基本是自虐；讯飞那边更狠，
 * 要给 AppID + APIKey + APISecret（两个 32 位字符串）。所以留一个
 * 「出包时把凭证烤进去」的口子：值来自工程根的 `keystore.properties`
 * （和签名口令放一起的那个文件，本来就不进版本库）。
 *
 * 里面是空串时行为完全不变，还是走设置页手填的老路。
 *
 * 注意：这只是为了自用调试方便。凭证会以明文形式躺在 APK 里，
 * 谁拿到包谁就能读出来 —— 别把带凭证的包发出去。
 */
object BuildSecrets {

    /** 出包时烤进来的 DeepSeek Key；空串表示没有预置。 */
    const val PRESET_API_KEY: String = BuildConfig.PRESET_API_KEY

    /** 出包时烤进来的讯飞凭证；空串表示没有预置。 */
    const val PRESET_XFY_APPID: String = BuildConfig.PRESET_XFY_APPID
    const val PRESET_XFY_APIKEY: String = BuildConfig.PRESET_XFY_APIKEY
    const val PRESET_XFY_APISECRET: String = BuildConfig.PRESET_XFY_APISECRET

    /**
     * 预置时顺带把识别引擎切过去，`system` / `generic` / `xfyun`。
     *
     * 留空时：[hasPresetXfyun] 为真就切 `xfyun`，否则保持 App 自己的默认值。
     *
     * ## ⚠️ 1.982：通用接口**不做预置**（提示词 §1.4 第 27 项那条「看情况」的答复）
     *
     * 这一版把「通用接口」那一档加回来了，但没有跟着加
     * `presetAsrUrl` / `presetAsrKey` / `presetAsrModel` 三个烤包口子。理由：
     *
     *  1. **没有可预置的东西**。讯飞要预置是因为那三串是**用户自己控制台里的私密凭证**，
     *     在手表上敲一遍是自虐；而通用接口那边，地址和模型名**本来就是公开的默认值**
     *     （已经在 `Settings.DEFAULT_ASR_URL` / `DEFAULT_ASR_MODEL` 里预填了），
     *     唯一算得上私密的是 Key —— 而它**允许留空**（本机服务不鉴权），
     *     留空这件事本身不是缺陷，是正常用法。
     *  2. **预置 Key 会改变默认行为的方向**。`asrKey` 默认空 ⇒ 不发 `Authorization` 头；
     *     一旦烤进去一个 Key，这个包在**所有**用户那儿都会带上那个头，
     *     而它属于「把凭证明文写进 APK」那一类（见本文件顶部那段警告）。
     *     为一个「本来就允许空」的字段引入这个代价，不划算。
     *  3. **用户手上没有可用的识别 API**（他原话：「我没有这种 api」）⇒
     *     现在预置任何东西都只会是**一个他用不了的地址**，反而多一处要解释的地方。
     *
     * 真到了要预置的那天，照 [PRESET_XFY_APPID] 那三行的样子加即可，
     * 然后**记得**也要在 `SettingsStore.applyPresetsIfNeeded` 的指纹列表里加一行
     * （那个函数是靠指纹判断「包里的预置内容变了没有」的，漏加 = 换了凭证不生效）。
     */
    const val PRESET_ASR_ENGINE: String = BuildConfig.PRESET_ASR_ENGINE

    val hasPresetApiKey: Boolean get() = PRESET_API_KEY.isNotBlank()

    /** 三样齐全才算预置了讯飞凭证，缺一样都不生效 */
    val hasPresetXfyun: Boolean
        get() = PRESET_XFY_APPID.isNotBlank() &&
            PRESET_XFY_APIKEY.isNotBlank() &&
            PRESET_XFY_APISECRET.isNotBlank()
}
