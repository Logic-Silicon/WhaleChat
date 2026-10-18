#!/usr/bin/env bash
# 出两版：**Release（干净，可外发）+ Debug（带预置凭证，只自测）**。
#
# ⚠️ 1.908 起**不再出「-with-xfyun」那一版**（用户拍板：以后只有 Release 和 Debug）。
# 原来那两版 release（干净/带凭证）**字节数完全相同**，只能靠审计 dex 分辨，
# 而"带凭证的 release"本身就是个不该存在的形态 —— 要带凭证自测就用 Debug。
#
# 干净版**不改 keystore.properties**，而是用命令行 -P 把预置键覆盖成空串 ——
# 改文件容易忘改回来，而那一步的代价是把讯飞三串一起发出去。
#
# ============================================================================
# ⚠️⚠️ 2026-10-18（2.0）：这个脚本从「会话目录」搬进了工程内 `tools/`，
#     并且**版本号改成从 build.gradle.kts 自动读**。
#
# 为什么搬：它原来放在 `C:\Users\chao\WorkBuddy\2026-09-26-16-08-47\` 那个
# **会话目录**里 —— 换一个会话就找不到了，而它是唯一正确的出包入口。
#
# 为什么自动读：原来这里写死 `VERSION="1.991"`，交接文档要求「两处同改」，
# 靠人记。忘改的后果不是报错，而是**包名和包里真实的版本号对不上**，
# 属于那种「看起来一切正常」的事故。现在只有一处真相来源。
#
# ⚠️ 解析规则必须和 `app/build.gradle.kts` 里那行 `val appVersionName = "…"`
#    保持一致：抓第一个 `appVersionName = "x.y"` 的字面量。
# ============================================================================
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$PROJ/dist"
LOGDIR="$PROJ/tools/logs"
GRADLE_FILE="$PROJ/app/build.gradle.kts"
OUT_RELEASE="$PROJ/app/build/outputs/apk/release/app-release.apk"
OUT_DEBUG="$PROJ/app/build/outputs/apk/debug/app-debug.apk"

# 版本号：**唯一的真相来源是 build.gradle.kts**，这里只读不写。
VERSION="$(grep -oE 'val appVersionName = "[0-9]+\.[0-9]+"' "$GRADLE_FILE" \
  | head -1 | grep -oE '[0-9]+\.[0-9]+')"
if [ -z "$VERSION" ]; then
  echo "!!! 读不出版本号：$GRADLE_FILE 里找不到 val appVersionName = \"x.y\"" >&2
  exit 1
fi
echo "=== 版本号（读自 build.gradle.kts）：$VERSION ==="

# ⚠️ 防呆：版本号与已有的 dist 包重名时**停下**。
# 覆盖一个已交付的包是不可逆的 —— 而它几乎总是「忘了改版本号就出包」导致的。
if [ -f "$DIST/WhaleChat-$VERSION-release.apk" ]; then
  echo "!!! dist 里已经有 WhaleChat-$VERSION-release.apk 了。" >&2
  echo "!!! 先确认 build.gradle.kts 的版本号是否该 +0.001，再重跑。" >&2
  exit 1
fi

cd "$PROJ"
mkdir -p "$DIST" "$LOGDIR"
LOG_TAG="build_${VERSION//./}"

# ---------------------------------------------------------------- 1/2 Release
echo "=== 1/2 Release（干净，可外发）==="
./gradlew assembleRelease \
  -PpresetXfyAppId= -PpresetXfyApiKey= -PpresetXfyApiSecret= -PpresetApiKey= \
  > "$LOGDIR/${LOG_TAG}_release.log" 2>&1
# ⚠️ 看 gradle 自己的退出码。管道的退出码是 tail 的（永远 0），
#    编译失败也会照常往下走 —— 那会把上一轮的包当成这一轮交付（真出过）。
#    `set -e` 在这里不够：它在 `cmd > log` 失败时确实会退出，但我们要明确报出来。
echo "  exit=$?"
tail -5 "$LOGDIR/${LOG_TAG}_release.log"
cp "$OUT_RELEASE" "$DIST/WhaleChat-$VERSION-release.apk"
echo "  -> $DIST/WhaleChat-$VERSION-release.apk"

# ------------------------------------------------------------------ 2/2 Debug
echo "=== 2/2 Debug（自测，用 keystore.properties 里的预置凭证）==="
./gradlew assembleDebug > "$LOGDIR/${LOG_TAG}_debug.log" 2>&1
echo "  exit=$?"
tail -5 "$LOGDIR/${LOG_TAG}_debug.log"
cp "$OUT_DEBUG" "$DIST/WhaleChat-$VERSION-debug.apk"
echo "  -> $DIST/WhaleChat-$VERSION-debug.apk"

# ------------------------------------------------------------------ 交付前的闸
# 不再有「两版互相印证」这件事了（只有一版 release），所以**必须靠工具审计 dex**：
# Release 必须「干净」（审计从 keystore.properties 读真值精确比对，
# 不是裸搜 sk- —— 后者会被 R8 的 lambda 合成名误伤）。
echo "=== 凭证审计（Release 必须为「干净」）==="
python "$PROJ/tools/audit_apk.py" "$DIST/WhaleChat-$VERSION-release.apk" || {
  echo "!!! Release 包里检出凭证，立刻停下，不要外发"
  exit 1
}

# ------------------------------------------------------------------ 包内版本自证
# 「包名对得上版本号」这件事以前靠人记，现在机器验一遍。
# `aapt dump badging` 在 build-tools 里；找不到就跳过（不阻断，只提示）。
AAPT="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | tail -1 || true)"
if [ -n "$AAPT" ] && [ -x "$AAPT" ]; then
  echo "=== 包内版本自证 ==="
  "$AAPT" dump badging "$DIST/WhaleChat-$VERSION-release.apk" 2>/dev/null \
    | grep -E "^package:" | head -1
else
  echo "（跳过包内版本自证：没找到 aapt2）"
fi

echo "=== 产物 ==="
ls -l "$DIST"/*"$VERSION"*.apk
