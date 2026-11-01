package com.naoh.whalechat.ui

/**
 * 把模型回复里常见的 **Markdown / LaTeX 记号**收成「手表上直接能读」的纯文本（2.31）。
 *
 * ## 为什么需要它
 *
 * 用户 2026-11-01 的截图：问化学题时模型回了
 * `\(\mathrm{S}^{2-} + 4H_2O \rightarrow SO_4^{2-} + 8H^+ + 8e^- \)` 这种东西，
 * 手表上就是一堆反斜杠和大括号，完全没法读；回复末尾还留着 `**` 这类 Markdown 记号。
 *
 * ## 为什么不用 LaTeX 渲染库
 *
 *  * 手表屏 226dp、正文 `bodyMedium`，真正需要的是**短式子能读**（S²⁻、H₂O、→），
 *    不是排版级公式；行间公式体一屏放不下，渲染出来反而更糟；
 *  * 新依赖会把包撑大，而绝大多数回复根本不含公式 —— 为偶尔一道化学题引一整个排版引擎不划算；
 *  * 转换在本地、只作用于**显示**：消息原文、复制出来的内容、发给模型的上下文都是未处理的原文。
 *
 * ## 覆盖范围（够用为止，不做通用 LaTeX 解析器）
 *
 * | 输入 | 输出 |
 * |---|---|
 * | `\( … \)` `\[ … \]` `$$ … $$` | 去掉定界符（保留内容） |
 * | `\mathrm{X}` `\text{X}` `\mathbf{X}` 等 | `X` |
 * | `\frac{a}{b}` / `\sqrt{x}` | `a/b` / `√x` |
 * | `^{2-}` `^+` `H_2O` `SO_4` | `²⁻` `⁺` `H₂O` `SO₄`（Unicode 上下标；映射不到就退成 `^(…)`） |
 * | `\rightarrow` `\times` `\Delta` `\le` … | `→` `×` `Δ` `≤` … |
 * | `\alpha` 这类没收录的命令 | 去掉反斜杠、保留字母 |
 * | `**粗体**` `` `代码` `` `# 标题` `- 列表` `> 引用` `[文字](url)` | 去掉记号（列表变 `· `，链接只留文字） |
 *
 * ⚠️ 只用于显示（`ChatScreen.MessageCard` 的正文）；别把它挪到数据层。
 */
fun formatForWatch(raw: String): String {
    if (raw.isBlank()) return raw
    var s = raw.replace("\r\n", "\n")

    val hasMath = s.contains("\\(") || s.contains("\\[") || s.contains("$$") ||
        Regex("\\\\[a-zA-Z]+").containsMatchIn(s)

    // 1) 数学定界符
    s = s.replace("\\(", "").replace("\\)", "")
    s = s.replace("\\[", "").replace("\\]", "")
    s = s.replace("$$", "\n")

    if (hasMath) {
        // 2) LaTeX 换行 / 空白命令
        s = s.replace("\\\\", "\n")
        s = s.replace("\\,", " ").replace("\\;", " ").replace("\\!", "")
        s = s.replace("\\ ", " ")
    }

    // 3) 分数 / 根号（只处理一层，够用）
    s = Regex("\\\\[dt]?frac\\{([^{}]*)\\}\\{([^{}]*)\\}").replace(s) { m ->
        m.groupValues[1] + "/" + m.groupValues[2]
    }
    s = Regex("\\\\sqrt\\{([^{}]*)\\}").replace(s) { m -> "√" + m.groupValues[1] }

    // 4) 只做样式的包裹命令 → 去掉外壳
    s = Regex(
        "\\\\(?:mathrm|mathbf|mathit|mathsf|mathtt|text|textrm|textbf|textit|operatorname|ce)\\s*\\{([^{}]*)\\}",
    ).replace(s) { m -> m.groupValues[1] }

    // 5) 上下标
    s = convertBracedScript(s, '^', SUPERSCRIPTS)
    s = convertBracedScript(s, '_', SUBSCRIPTS)
    s = Regex("\\^([0-9A-Za-z+\\-])").replace(s) { m ->
        val c = m.groupValues[1][0]
        (SUPERSCRIPTS[c]?.toString() ?: "^" + c)
    }
    // 裸下划线只在大写字母/数字/右括号后面才转（H_2O、SO_4 会转；
    // snake_case 这种小写标识符不会被动，避免把代码变量名改坏）
    s = Regex("(?<=[A-Z0-9)])_([0-9+\\-])").replace(s) { m ->
        val c = m.groupValues[1][0]
        (SUBSCRIPTS[c]?.toString() ?: "_" + c)
    }

    // 6) 符号命令
    s = Regex("\\\\[a-zA-Z]+").replace(s) { m ->
        val name = m.value.removePrefix("\\")
        SYMBOLS[name] ?: name
    }

    // 7) 残余大括号（只在确实有数学记号时才清，避免误伤普通文本里的花括号）
    if (hasMath) {
        s = s.replace("{", "").replace("}", "")
    }

    // 8) Markdown 记号
    s = s.replace("**", "").replace("__", "").replace("`", "")
    s = Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE).replace(s, "")
    s = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE).replace(s, "· ")
    s = Regex("^\\s*>\\s?", RegexOption.MULTILINE).replace(s, "")
    s = Regex("^\\s*([-*_])\\1{2,}\\s*$", RegexOption.MULTILINE).replace(s, "")
    s = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(s) { m -> m.groupValues[1] }

    // 9) 收尾：行尾空白、连续空行压到最多一行、整体 trim
    s = s.lines().joinToString("\n") { it.trimEnd() }
    s = Regex("\n{3,}").replace(s, "\n\n")
    return s.trim()
}

/** `^{…}` / `_{…}`：整段能映射就整体转 Unicode，映射不到就退成 `^(…)`，保证信息不丢。 */
private fun convertBracedScript(text: String, mark: Char, table: Map<Char, Char>): String {
    val head = if (mark == '^') "\\^" else "_"
    return Regex(head + "\\{([^{}]*)\\}").replace(text) { g ->
        val inner = g.groupValues[1]
        val sb = StringBuilder()
        var ok = true
        for (c in inner) {
            val v = table[c]
            if (v == null) {
                ok = false
                break
            }
            sb.append(v)
        }
        if (ok) sb.toString() else mark + "(" + inner + ")"
    }
}

private val SUPERSCRIPTS: Map<Char, Char> = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ',
)

private val SUBSCRIPTS: Map<Char, Char> = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ',
    'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ',
    's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
)

/** LaTeX 符号命令 → Unicode。没收录的会按第 6 步去掉反斜杠保留单词。 */
private val SYMBOLS: Map<String, String> = mapOf(
    "times" to "×", "cdot" to "·", "pm" to "±", "mp" to "∓", "div" to "÷",
    "rightarrow" to "→", "to" to "→", "longrightarrow" to "⟶", "leftarrow" to "←",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔",
    "uparrow" to "↑", "downarrow" to "↓",
    "Delta" to "Δ", "delta" to "δ", "alpha" to "α", "beta" to "β", "gamma" to "γ",
    "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ",
    "sigma" to "σ", "tau" to "τ", "phi" to "φ", "omega" to "ω", "Omega" to "Ω",
    "sum" to "Σ", "int" to "∫", "partial" to "∂", "nabla" to "∇",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
    "infty" to "∞", "degree" to "°", "circ" to "∘", "in" to "∈", "notin" to "∉",
    "subset" to "⊂", "supset" to "⊃", "cup" to "∪", "cap" to "∩", "emptyset" to "∅",
    "forall" to "∀", "exists" to "∃", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
    "ldots" to "…", "cdots" to "…", "dots" to "…",
    "quad" to " ", "qquad" to " ",
    "left" to "", "right" to "", "big" to "", "Big" to "", "bigl" to "", "bigr" to "",
    "displaystyle" to "", "textstyle" to "", "limits" to "",
    "begin" to "", "end" to "", "hline" to "",
)
