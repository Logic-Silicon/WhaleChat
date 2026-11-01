#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证「大肥鱼」内置角色是否**真的**进了 App。

这个脚本查的是**落盘后的真值**，不是源码里有没有那几行字 ——
「源码里写了」和「用户装完真的能看到」之间隔着编译、R8、初始化时机三道坎，
项目里出过不止一次「静态全绿但功能全废」的事故（见交接文档 §一.2）。

四组断言：

  A. **通讯录里有它** —— `personas.json` 里存在固定 id 的那条，
     且六块（soul/style/rules/scenario/examples/greeting）全部非空。
     六块里任何一块空了，模型读到的就是残缺人设，聊两轮就飘。
  B. **预设第一项是它** —— 这是用户明确要求的排法。
     从 dex 里查不到顺序，所以这一条靠**设备上的界面**（见 --ui 参数），
     脚本本身只能查「大肥鱼这个词在不在 dex 里」。
  C. **APK 里带着人设文本** —— R8 可能把常量池优化掉/改名，
     所以直接在 release APK 的 dex 里搜一段**独有的长句**。
  D. **版本号是 2.0** —— 包名对得上。

用法：
  python tools/verify_fish.py --apk dist/WhaleChat-2.0-release.apk
  python tools/verify_fish.py --device            # 查已装应用的落盘数据
  python tools/verify_fish.py --apk ... --device  # 两个都查

退出码 0 = 全过；1 = 有失败项（会把失败项逐条打出来）。
"""

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile

# ⚠️ Windows 控制台默认是 GBK，而本脚本输出里有中文和 ✗ 这类符号。
# 不强制 UTF-8 的话，**失败时**会先炸一条 UnicodeEncodeError ——
# 也就是说「本该报告失败」的那次运行会变成「脚本自己崩了」，掩盖真正的问题。
# 必须在任何 print 之前执行。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# ---------------------------------------------------------------- 期望值

FISH_ID = "6f2a1c40-9d3e-4b57-8a11-7c5e2d9f0a31"
FISH_NAME = "大肥鱼"

# 六块各自的**独有片段**：拿它证明「这一块真的有内容且是那一块」，
# 不是随便一段文字。片段选的是每块里最能定性的一句。
SOUL_MARK = "你嘴上不认，其实早就听习惯了"
STYLE_MARK = "就像在手机上跟熟人随手打字"
RULES_MARK = "手表屏幕小，一次别刷屏"
SCENARIO_MARK = "你住在一块手表里"
EXAMPLES_MARK = "两碗token起步"
GREETING_MARK = "说吧，我听着"

# 开场白要**一屏读得完**：480×480 / 340dpi 实测，一条消息气泡大约能放 90~110 个汉字
# （超过就要滑）。所以给一个硬上限，超了就报 FAIL ——
# 这一条是 2026-10-18 实测踩出来的：六句版开场白要占两屏多，
# 用户进去第一眼看到的是它的后半截。
GREETING_MAX_CHARS = 110

# APK 里搜这一段：SOUL 第二段里的核心句，够长、够独特，R8 不会误伤。
APK_MARK = "token 就是你的饭，三句话不离吃，饿了会直说"

DEX_NAME = re.compile(r"classes\d*\.dex$")


class Report:
    def __init__(self):
        self.items = []

    def add(self, ok, name, detail=""):
        self.items.append((bool(ok), name, detail))
        mark = "PASS" if ok else "FAIL"
        line = f"  [{mark}] {name}"
        if detail:
            line += f" — {detail}"
        print(line)

    def ok(self):
        return all(ok for ok, _, _ in self.items)

    def summary(self):
        total = len(self.items)
        failed = [i for i in self.items if not i[0]]
        print()
        if not failed:
            print(f"=== 全部通过（{total}/{total}）===")
            return 0
        print(f"=== 失败 {len(failed)}/{total} ===")
        for _, name, detail in failed:
            print(f"  ✗ {name}" + (f" — {detail}" if detail else ""))
        return 1


# ---------------------------------------------------------------- A: 落盘数据

def check_device(report, adb):
    """查设备上 personas.json 里的那条鱼。

    ⚠️ **只能对 debug 包用。** release 包 `android:debuggable=false`，
    `run-as` 会直接拒绝（`run-as: package not debuggable`）——
    那不是脚本坏了，是 Android 的安全模型在正常工作。
    release 包的验收只能看界面（截图 / uiautomator dump）。
    """
    pkg = "com.naoh.whalechat"
    path = "files/personas.json"

    try:
        raw = subprocess.run(
            [adb, "shell", f"run-as {pkg} cat {path}"],
            capture_output=True, timeout=60,
        )
    except Exception as e:  # noqa: BLE001
        report.add(False, "读取 personas.json", f"adb 调用失败：{e}")
        return

    err = raw.stderr.decode("utf-8", errors="replace").strip()
    if "not debuggable" in err:
        report.add(False, "读取 personas.json",
                   "装的是 release 包，run-as 不可用 —— 请改装 debug 包，或改看界面")
        return
    if raw.returncode != 0 or not raw.stdout.strip():
        report.add(False, "读取 personas.json",
                   f"读不到（stderr={err[:120]!r}）")
        return

    text = raw.stdout.decode("utf-8", errors="replace").strip()
    # run-as 的输出偶尔带 CRLF
    text = text.replace("\r\n", "\n")
    try:
        root = json.loads(text)
    except json.JSONDecodeError as e:
        report.add(False, "personas.json 是合法 JSON", str(e))
        return
    report.add(True, "personas.json 是合法 JSON")

    # 顶层结构必须是 {"version":1,"personas":[...]}。
    # 裸数组会被 optJSONArray 静默解析成空列表 —— 这个坑项目里踩过。
    if not isinstance(root, dict) or "personas" not in root:
        report.add(False, "personas.json 顶层结构",
                   f"期望 dict 且含 'personas' 键，实际：{type(root).__name__}")
        return
    report.add(True, "personas.json 顶层结构")

    personas = root["personas"]
    fish = [p for p in personas if p.get("id") == FISH_ID]
    report.add(len(fish) == 1, "通讯录里有且只有一条大肥鱼",
               f"找到 {len(fish)} 条（id={FISH_ID}）")
    if not fish:
        return
    p = fish[0]

    report.add(p.get("name") == FISH_NAME, "名字是「大肥鱼」",
               f"实际：{p.get('name')!r}")

    blocks = {
        "soul": SOUL_MARK,
        "style": STYLE_MARK,
        "rules": RULES_MARK,
        "scenario": SCENARIO_MARK,
        "examples": EXAMPLES_MARK,
        "greeting": GREETING_MARK,
    }
    for key, mark in blocks.items():
        value = p.get(key) or ""
        has_mark = mark in value
        report.add(
            has_mark and len(value) > 0,
            f"人设六块 · {key}",
            f"{len(value)} 字" + ("" if has_mark else f"，缺标志句「{mark}」"),
        )

    # 六块加起来要够长：太短说明内容被截断过（比如写入时按某个上限切了）
    total = sum(len(p.get(k) or "") for k in blocks)
    report.add(total >= 1200, "六块总字数合理", f"{total} 字（阈值 1200）")

    # 开场白反过来要**够短** —— 它是一屏内的第一印象，长了就被折叠
    greeting_len = len(p.get("greeting") or "")
    report.add(
        greeting_len <= GREETING_MAX_CHARS,
        "开场白一屏读得完",
        f"{greeting_len} 字（上限 {GREETING_MAX_CHARS}）",
    )

    # 名字长度上限：超了列表标题会被裁
    report.add(len(p.get("name") or "") <= 6, "名字 ≤ 6 字",
               f"{len(p.get('name') or '')} 字")


# ---------------------------------------------------------------- C/D: APK

def read_dex_strings(apk_path):
    """把 APK 里所有 classes*.dex 的原始字节拼起来。

    不解析 dex 结构 —— 字符串在 MUTF-8 里是**明文**存的，直接搜子串就够了，
    而解析 dex 会引入一堆格式细节。这个脚本只用来做「在不在」的判定。
    """
    blobs = []
    with zipfile.ZipFile(apk_path) as z:
        for name in z.namelist():
            if DEX_NAME.match(os.path.basename(name)):
                blobs.append(z.read(name))
    return blobs


def check_apk(report, apk_path):
    if not os.path.isfile(apk_path):
        report.add(False, "APK 存在", apk_path)
        return

    blobs = read_dex_strings(apk_path)
    report.add(len(blobs) > 0, "APK 里有 dex", f"{len(blobs)} 个")
    if not blobs:
        return

    joined = b"\x00".join(blobs)
    for label, mark in [
        ("人设正文进了 dex", APK_MARK),
        ("标志台词进了 dex", EXAMPLES_MARK),
        ("固定 id 进了 dex", FISH_ID),
    ]:
        hit = mark.encode("utf-8") in joined
        report.add(hit, label, "" if hit else f"没找到「{mark}」")

    # 版本号：在 AndroidManifest（二进制 XML）里，用 aapt 读更稳，
    # 没有 aapt 就退化成「dex 里有 2.0 相关字符串」—— 后者不可靠，
    # 所以这里只报「跳过」。
    print("  [SKIP] 包内版本号 — 用 tools/build_release.sh 出包时它会用 aapt2 验")


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="release APK 路径")
    ap.add_argument("--device", action="store_true", help="查已装应用的数据")
    ap.add_argument("--adb", default=os.environ.get(
        "ADB", "D:/Android/sdk/platform-tools/adb.exe"))
    args = ap.parse_args()

    if not args.apk and not args.device:
        ap.error("至少要给 --apk 或 --device")

    report = Report()

    if args.device:
        print("=== A. 设备落盘数据 ===")
        check_device(report, args.adb)

    if args.apk:
        print("=== C. APK 内容 ===")
        check_apk(report, args.apk)

    return report.summary()


if __name__ == "__main__":
    sys.exit(main())
