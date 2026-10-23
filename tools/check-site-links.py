#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：官网 / README 里的版本与下载地址必须指向「最新」。

为什么需要它（2026-10-23 定下）：
  以前每次发版都要手动去改 README 的出包示例、落地页的下载按钮。
  漏一处就会出现「页面写着 v1.9.21、Release 已经是 v1.9.23」——
  对投稿、对读者都是硬伤。这个脚本把这件事变成可判红的检查。

检查项：
  1. 构建脚本 `1-aapt2.sh` 的 VER_CODE / VER_NAME 是唯一真相，对应到 `v<VER_NAME>`。
  2. README 两份里的出包示例版本号必须等于它。
  3. `docs/index.html` / README 里**不允许出现版本钉死的 Release 地址**
     （`/releases/tag/vX` 或 `/releases/download/vX/...`）——
     一律用 `/releases/latest` 或 `/releases/latest/download/WPAForge.apk`。
  4. 落地页必须保留那条「永远最新」的永久直链，不能被悄悄删掉。
  5. `RELEASE.md` 里注释写明的 tag 必须等于本版。

用法：
  python3 tools/check-site-links.py            # 离线检查（门禁跑这个）
  python3 tools/check-site-links.py --online   # 额外核对 GitHub 上是否真有这个 Release 与两个资产
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALIAS = "https://github.com/wlmosv-png/wpaforge/releases/latest/download/WPAForge.apk"
LATEST = "https://github.com/wlmosv-png/wpaforge/releases/latest"

fails = []

def rd(p):
    with io.open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()

def bad(msg):
    fails.append(msg)
    print("  FAIL " + msg)

def ok(msg):
    print("  OK   " + msg)

# ---- 1) 版本真相 ----
sh = rd("1-aapt2.sh")
m = re.search(r'VER_CODE="\$\{WP_VERSION_CODE:-([0-9]+)\}', sh)
n = re.search(r'VER_NAME="\$\{WP_VERSION_NAME:-([0-9.]+)\}', sh)
if not m or not n:
    bad("1-aapt2.sh 里找不到 WP_VERSION_CODE / WP_VERSION_NAME 默认值")
    sys.exit(1)
code, name = m.group(1), n.group(1)
ok("构建脚本版本 = %s (%s)" % (name, code))
if not re.match(r"^\d+\.\d+\.\d+$", name):
    bad("VER_NAME 不是 x.y.z 形式：%s" % name)

# ---- 2) README 出包示例 ----
for f in ("README.md", "README_EN.md"):
    body = rd(f)
    hits = re.findall(r"WP_VERSION_CODE=([0-9]+)\s+WP_VERSION_NAME=([0-9.]+)", body)
    if not hits:
        bad("%s 里找不到出包示例（WP_VERSION_CODE / WP_VERSION_NAME）" % f)
    else:
        for c, v in hits:
            if c != code or v != name:
                bad("%s 出包示例是 %s/%s，应为 %s/%s" % (f, c, v, code, name))
            else:
                ok("%s 出包示例一致" % f)

# ---- 3) 不许出现版本钉死的 Release 地址 ----
PIN = re.compile(r"releases/(?:tag|download)/v?[0-9]")
for f in ("docs/index.html", "README.md", "README_EN.md"):
    body = rd(f)
    pinned = PIN.findall(body)
    if pinned:
        bad("%s 里有版本钉死的 Release 地址 %d 处（应用 /releases/latest）" % (f, len(pinned)))
    else:
        ok("%s 无版本钉死地址" % f)

# ---- 4) 永久直链必须在 ----
page = rd("docs/index.html")
if ALIAS not in page:
    bad("落地页缺少永久直链 %s" % ALIAS)
else:
    ok("落地页含永久直链（%d 处）" % page.count(ALIAS))
if LATEST not in page:
    bad("落地页缺少「更新日志」入口 %s" % LATEST)

# ---- 5) RELEASE.md 的 tag ----
rel = rd("RELEASE.md")
mt = re.search(r"tag:\s*(v[0-9.]+)", rel)
if not mt:
    print("  --   RELEASE.md 没写 tag 注释，跳过")
elif mt.group(1) != "v" + name:
    bad("RELEASE.md 的 tag 是 %s，应为 v%s" % (mt.group(1), name))
else:
    ok("RELEASE.md tag = v%s" % name)

# ---- 6) 链接预览卡片：og:image 必须是绝对地址 ----
# Telegram / Twitter / 微信抓链都要求绝对 URL；写成 content="og.png"
# 会导致**投稿出去链接不出图**（点进去的人少一大截），而且不报错、很难发现。
og = re.search(r'<meta property="og:image" content="([^"]+)"', page)
if not og:
    bad("落地页缺 og:image（投稿抓链不会出图）")
elif not og.group(1).startswith("http"):
    bad("og:image 是相对地址（%s）—— 抓链不认，必须绝对 URL" % og.group(1))
else:
    ok("og:image 是绝对地址")
if 'property="og:title"' not in page:
    bad("落地页缺 og:title")
if 'property="og:url"' not in page:
    bad("落地页缺 og:url")

# ---- 7) 可选：联网核对 Release 与资产 ----
if "--online" in sys.argv:
    import json, urllib.request
    tok = ""
    try:
        tok = open("/data/local/tmp/.gh_token").read().strip()
    except Exception:
        pass
    url = "https://api.github.com/repos/wlmosv-png/wpaforge/releases/tags/v" + name
    req = urllib.request.Request(url)
    if tok:
        req.add_header("Authorization", "token " + tok)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            d = json.loads(r.read().decode())
        assets = [a["name"] for a in d.get("assets", [])]
        for want in ("WPAForge-%s.apk" % name, "WPAForge.apk"):
            if want in assets:
                ok("Release 资产存在：%s" % want)
            else:
                bad("Release v%s 缺资产 %s" % (name, want))
    except Exception as e:
        bad("拿不到 Release v%s：%s" % (name, e))

print()
if fails:
    print("站点链接门禁: %d 处不合格" % len(fails))
    sys.exit(1)
print("站点链接门禁: 全部通过（版本 %s / %s）" % (name, code))
