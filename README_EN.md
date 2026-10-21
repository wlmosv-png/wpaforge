<div align="center">

# WPAForge ⚡

**Crack the WPA password of the router in front of you — right on your phone.**

[![Release](https://img.shields.io/github/v/release/wlmosv-png/wpaforge?style=flat-square&color=orange)](https://github.com/wlmosv-png/wpaforge/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/wlmosv-png/wpaforge/total?style=flat-square&color=blue)](https://github.com/wlmosv-png/wpaforge/releases/latest)
[![Stars](https://img.shields.io/github/stars/wlmosv-png/wpaforge?style=flat-square&color=yellow)](https://github.com/wlmosv-png/wpaforge/stargazers)
[![License](https://img.shields.io/badge/license-Apache--2.0-green?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square)]()
[![Root](https://img.shields.io/badge/requires-root-red?style=flat-square)]()

**English** · [中文](README.md)

<img src="docs/banner.svg" alt="WPAForge" width="100%">

</div>

---

## Why WPAForge

Typical WiFi crackers grind through a dictionary **top to bottom**. WPAForge is different — it builds the most likely passwords **for the one target right in front of you**.

- 🎯 **Target derivation** — generates hundreds of "most likely for this router" candidates from SSID / BSSID, filling what your dictionary lacks
- ⚡ **Smart ranking** — scores and reorders candidates so the right one lands in the first few dozen, not the fifty-thousandth
- 🔄 **Resume** — progress persisted continuously; runs with the screen locked (holding a WakeLock + WifiLock), resumes where it stopped
- 🧵 **Multi-target queue** — runs queued jobs automatically, each pausable / resumable
- 🏭 **Vendor & channel** — built-in IEEE OUI table shows the router brand; the channel-occupancy chart suggests a better channel, bandwidth and Wi-Fi generation are listed, APs filterable by WPS
- 🛰 **LAN recon** — after associating, sweeps the subnet for live hosts and common ports, groups them by vendor via the same OUI table, and writes a report
- 🧭 **Connection control** — pin a single AP with `-b` when several share one SSID; optionally rotate to a fresh random MAC per attempt to ease AP-side rate limiting
- 🔐 **Self-audit** — reads the WiFi networks saved on this phone and ranks their passphrases against common-password lists
- 🔒 **Real-association detection** — one signal only: a completed 4-Way Handshake, four same-source proofs, no false positives

**In one line: dictionary quality beats dictionary size — WPAForge puts "the right one" first.**

## Install

> **Root required** · Android 8.0+

[**⬇️ Download latest APK**](https://github.com/wlmosv-png/wpaforge/releases/latest)

No root, no luck — detection relies on `su + cmd wifi`. The Shizuku and rootless channels were removed
in 1.8.x: Shizuku needs a binder call per association, and the rootless path (`WifiNetworkSpecifier`)
pops a system dialog for every candidate — neither can carry a dictionary walk.

## Quick start

```text
1. Open "Observe", scan for nearby networks, pick your target
2. Open "Crack", load a dictionary (or let it derive one on the spot)
3. Hit start, lock the screen — the found password gets flagged
```

## Screenshots

<p align="center">
  <img src="docs/screenshots/shot04.jpg" width="88%">
  <br>
  <img src="docs/screenshots/shot06.jpg" width="88%">
</p>

Audit · Crack — the rest you'll see once you install it.

## Building

No Gradle, two manual stages:

```sh
WP_VERSION_CODE=79 WP_VERSION_NAME=1.9.9 sh 1-aapt2.sh   # device: aapt2 resource compile (version set here)
sh 2-build-dex.sh                                       # desktop: javac + d8 + sign (needs WP_FORGE_KS* vars)
```

Output `build/out/wpaforge-signed.apk`, fully explicit build chain. The defaults inside `1-aapt2.sh`
are only a fallback — always pass the version explicitly for a release, or you will ship a lower version
than what is already online.

---

## Usage boundary

**Use only on networks you are authorized to access** (your own router, labs, authorized pentests). Association attempts against unauthorized networks are illegal, and the router logs your MAC and time.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)

Design trade-offs and pitfalls (config-reuse false positives, detection timing, `finally` clobbering results) live in [`doc/TECHNICAL-NOTES.md`](doc/TECHNICAL-NOTES.md).

## Star History

<p align="center">
  <a href="https://star-history.com/#wlmosv-png/wpaforge&Date">
    <img src="https://api.star-history.com/svg?repos=wlmosv-png/wpaforge&type=Date" alt="Star History" width="100%">
  </a>
</p>

## My other projects

| Project | What it does |
|---|---|
| **[TGAutoSign](https://github.com/wlmosv-png/TGAutoSign)** | Xposed module for Telegram auto check-in — multi-account slots, group sign-in detection, scheduled plans, themed UI. Manage targets with `/jmb` in any chat. |
| **[apk-index](https://github.com/wlmosv-png/apk-index)** | APK static indexing & reverse-engineering search — parses AXML, indexes each dex, finds hook points by string / signature / structure. MCP-ready. |
