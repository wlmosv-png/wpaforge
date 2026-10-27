<div align="center">

<img src="docs/banner.png" alt="WPAForge" width="100%">

**Try to recover your own router's WPA password, candidate by candidate.**

<sub>candidates built for the target · every one really associated · curated dictionaries, or bring your own</sub>

[![Release](https://img.shields.io/github/v/release/wlmosv-png/wpaforge?style=flat-square&color=FFB800&labelColor=1C1C24)](https://github.com/wlmosv-png/wpaforge/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/wlmosv-png/wpaforge/total?style=flat-square&color=FFD54F&labelColor=1C1C24&cacheSeconds=60)](https://github.com/wlmosv-png/wpaforge/releases/latest)
[![Stars](https://img.shields.io/github/stars/wlmosv-png/wpaforge?style=flat-square&color=E39A28&labelColor=1C1C24)](https://github.com/wlmosv-png/wpaforge/stargazers)
<br>
[![Website](https://img.shields.io/badge/website-wlmosv.dpdns.org-C68A00?style=flat-square&labelColor=1C1C24&logo=cloudflare&logoColor=FFD54F)](https://wlmosv.dpdns.org/wpaforge/)

<sub>[中文](README.md)</sub>

</div>

---

> ### Before you start
>
> - **Root is required** (Android 8.0+): the verdict path relies on `su + cmd wifi`.
> - **Use it only on networks you own or have written permission to test.** Association
>   attempts against networks you do not own are illegal, and the AP logs your MAC and time.
> - Everything runs on-device. Nothing is uploaded; recovered passphrases are stored
>   encrypted, locally.

## Contents

- [What it does](#what-it-does)
- [How it finds passwords](#how-it-finds-passwords)
- [Dictionaries: bring your own, or use the built-ins](#dictionaries-bring-your-own-or-use-the-built-ins)
- [Verdicts: prefer "uncertain" over a false hit](#verdicts-prefer-uncertain-over-a-false-hit)
- [Observe: see which channel to use at a glance](#observe-see-which-channel-to-use-at-a-glance)
- [Quick start](#quick-start)
- [Screenshots](#screenshots)
- [How it compares](#how-it-compares)
- [Known limits](#known-limits)
- [Install](#install)
- [Building](#building)
- [License](#license)

## What it does

- **Generates candidates on the spot, per target**: SSID, vendor, house number, names,
  birthdays, phone numbers can all be used as evidence
- **Works with the built-in dictionaries, or with your own imported / downloaded ones** —
  both run exactly the same way
- Really associates once per candidate, and gives a per-candidate verdict:
  **hit / wrong passphrase / uncertain**
- Ranking is **by your own local record**: the dictionary screen states "hit 57/73 here,
  one hit per ~2 attempts"
- Multi-target queue: a list of targets runs in order, each pausable / resumable
- **Your own Wi-Fi config is left alone**: the target's original config is encrypted and
  recorded before any destructive step, written back at the end and confirmed by read-back;
  anything unresolved shows up under Restore state and can be retried / discarded
- Observe page: spectrum radar plus channel occupancy and advice
- LAN recon: live hosts and common ports on the same subnet, grouped by vendor, exportable
- WPS: four candidate layers (vendor algorithms / built-in weak PINs / custom list / full
  enumeration), plus the half-handshake attack and resume
- Offline check of `.cap` / `.hc22000` (EAPOL and PMKID paths; 10k entries in ~37 s)
- Dictionary search, merge and editing (contains / prefix / suffix / regex)
- Keeps running with the screen locked; survives being killed (progress is persisted)

## How it finds passwords

Three ways — pick whichever suits you:

**① Generate for the target.** If the SSID is `FAST_E972`, it produces `fast_e972`,
`faste972`, `fast2026` and the like. Only when you supply hints (names, birthdays, phone
numbers) does it produce `zhangwei1990` or `138xxxxxxxx`. Vendor names come from the IEEE
OUI table shipped inside the app; when a lookup fails it stays empty rather than inventing one.

**② Use the built-in dictionaries.** Seven curated tables: common weak passwords, pure
digits, WPA top lists, Chinese high-frequency, yearly top lists. Each one states why it is
worth keeping — just open it.

**③ Use your own.** Import a txt file, or download one from the in-app online sources.
They run exactly like the built-ins, and they earn their own record.

**Every candidate costs one real association attempt.** A fast AP answers in a second;
a slow one takes five or more. A phone can only be associated with one network at a time —
that is an Android limit, not a design choice — so *which* candidate you try first matters
far more than how many you have: 100,000 blind attempts is a matter of days, a few hundred
well-ordered ones usually finish in minutes.

## Your own network is left intact

Cracking means repeatedly removing the target config and re-adding it, so the finishing step
decides whether your own network survives:

- before anything destructive, the target's original config (with its sealed passphrase) is
  written to a local record;
- at the end: a hit keeps that correct config; every other outcome removes the candidate
  leftovers and writes the original config back, then **reads back to confirm**;
- "out of range", "currently on another network", "not connected within the window" are
  **not** restore failures - success is judged only by whether the config was written back;
- if the saved-network list cannot be read, no conclusion is drawn: the record is kept,
  retryable, and treated as possibly leaving a residual config;
- unresolved records are listed under **Restore state** on the Audit page, where each one can
  be retried, inspected, or discarded (discarding removes only the local record, never the
  system network config).

Automatic retries are spaced (10 minutes) and capped (3 attempts), then archived;
a new task for the **same target** is held back with an explanation while a record is unresolved.

## Dictionaries: bring your own, or use the built-ins

The built-ins are curated, not bulk: the multi-hundred-thousand-row tables are gone,
because across 107 real hits on the author's own device none of them ever produced one.
Your imported dictionaries are first-class — they run, merge and build a record just like
the built-ins. After a run, the dictionary screen looks like this:

```text
01 built-in common weak       core · hit 57/73 · ~2 attempts per hit
xato top 10000                    hit 14/14 · ~23 attempts per hit
```

The default sort is by hit rate: the table that proved itself on *your* network runs first.
That record is read from the first few columns of the local history only — no passphrase
is ever decrypted for it.

## Verdicts: prefer "uncertain" over a false hit

Every candidate ends with one of three verdicts, each with evidence behind it:

| Verdict | Evidence |
|---|---|
| **Hit** | 4-Way Handshake completed, with SSID / supplicant state / IP **from the same output line** |
| **Wrong passphrase** | Observable failure evidence (e.g. the AP re-sends handshake frame 1) |
| **Uncertain** | The time budget expired with nothing observed — its own column, never counted as "already tried" |

netId is used as **attribution evidence only**: it downgrades to "uncertain" just when both
sides report a valid non-zero netId and they disagree; missing or zero never vetoes.

This came out of a real failure: an early build showed a system dialog "could not connect to
BTWIFL_0923" while the app reported "connected". It would rather miss a hit than report a
false one.

## Observe: see which channel to use at a glance

The dial *is* a spectrum: the upper half is the 14 channels of 2.4G, the lower half the four
5G bands; radius is signal strength, strongest nearest the centre. **The more dots share a
sector, the busier that channel is.**

Underneath it, the conclusion without counting anything yourself:

```text
2.4G 25   5G 4   busiest ch 5 · 7   open 0   best -20 dBm
```

The header is a single line — `Updated just now` / `Updated Ns ago`, extended to
`+2 new · -1 gone` when devices appear or leave.

## Quick start

```text
1. Open "Observe", scan for nearby networks, pick your target
2. Open "Crack": let it generate candidates, or load a built-in / your own dictionary
3. Hit start, lock the screen — the passphrase is flagged and can be copied with one tap
```

## Screenshots

<img src="docs/shots/v122/ui-overview-v122.gif" alt="WPAForge UI overview" width="100%">

<sub>UI overview — observe → crack → hit → dictionaries, four steps (2s per frame)</sub>

<p align="center">
  <img src="docs/shots/v122/01-observe.png" width="23%">
  <img src="docs/shots/v122/02-crack.png" width="23%">
  <img src="docs/shots/v122/03-found.png" width="23%">
  <img src="docs/shots/v122/04-dicts.png" width="23%">
</p>
<p align="center">
  <img src="docs/shots/v122/05-lan.png" width="23%">
  <img src="docs/shots/v122/06-channels.png" width="23%">
  <img src="docs/shots/v122/07-audit.png" width="23%">
  <img src="docs/shots/v122/08-settings.png" width="23%">
</p>
<p align="center"><sub>① Observe (spectrum radar) · ② Crack (progress &amp; rate) · ③ Hit (passphrase + one-tap copy) · ④ Dictionaries (ranked by hit rate, with local record)<br>⑤ LAN recon (devices &amp; open ports) · ⑥ Signal distribution &amp; channel advice · ⑦ Audit (restore state / dictionary / strength / offline check) · ⑧ Settings (permissions &amp; keep-alive)</sub></p>

## How it compares

| Approach | What you need | Speed | Effort |
|---|---|---|---|
| **WPAForge** | one rooted phone | candidates generated on the spot; a few hundred usually take minutes | tap start |
| aircrack-ng on a PC + capture | laptop, monitor-mode adapter, an already captured handshake | depends on the wordlist; big ones take days | command line, capturing |
| Termux scripts on a phone | a Linux environment, external adapter | mostly aircrack in a wrapper | fiddly setup |
| Online cracking sites | hand the handshake to someone else | queue | your capture leaves your machine |

It is good at the passwords people actually set on home routers. A random 12-character
passphrase is out of scope — that one is what WPA3 is for.

## Known limits

| Symptom | Cause | What to do |
|---|---|---|
| It keeps trying, never hits | the passphrase is not in the candidate set | import your own dictionary, or raise the candidate budget |
| Everything fails immediately | the target is WPA3 / PMF, or WPA2-WPA3 transition | association attempts alone cannot get through |
| Some APs are missing | the phone lacks 6GHz, or randomised MAC is on | turn randomised MAC off and rescan |
| Permission errors | Root not granted, or the ROM blocks `cmd wifi` | run the 6-step self-check on the settings page |
| Killed mid-run | battery optimisation | whitelist it; progress is persisted and it resumes |
| Finishing says "original passphrase unavailable" | the sealed passphrase is gone (Keystore unavailable, device change, cleared data) | reconnect manually; the record explains why under Restore state |
| WPS targets show "capability unknown" and no device name | SELinux does not let the supplicant reply to the root domain | add one allow rule, see below |

### WPS channel (root required)

Reading the reported WPS device name, configuration methods and pairing window means asking
wpa_supplicant over its control channel. On some systems (measured: ColorOS 15 / Android 15)
SELinux does not let the supplicant send the reply back to the root domain, so you get an AP
list but "capability unknown" and no device names. WPAForge says so on the WPS page instead
of showing a silent "all fine".

Fix: put an executable script into `/data/adb/service.d/` that injects the rule at boot:

```sh
#!/system/bin/sh
/data/adb/magisk/magiskpolicy --live \
  "allow hal_wifi_supplicant_default magisk unix_dgram_socket sendto"
```

Delete the script to revert.

> **Note**: a module `sepolicy.rule` file did **not** work in this environment (measured) -
> after a reboot the live policy still lacked the rule and `BSS_ENUM` fell back to 0
> (cause unknown). Check with
> `magiskpolicy --print-rules | grep "hal_wifi_supplicant_default magisk"`.

## Install

> **Root required** · Android 8.0+

[**⬇️ Download the latest APK**](https://github.com/wlmosv-png/wpaforge/releases/latest)

Why Root is mandatory: the verdict path needs `su + cmd wifi`. The Shizuku and no-root
channels were removed in 1.9.24 (including their code): Shizuku borrows a binder on every
connection, and the no-root `WifiNetworkSpecifier` path pops a system dialog per candidate —
neither survives a per-candidate dictionary sweep.

## Building

No Gradle; a two-stage manual chain:

```sh
WP_VERSION_CODE=124 WP_VERSION_NAME=1.9.28 sh 1-aapt2.sh  # device side: aapt2 resource compile (version set here)
sh 2-build-dex.sh                                       # desktop side: javac + d8 + signing (needs WP_FORGE_KS*)
```

Output: `build/out/wpaforge-signed.apk`. The version defaults in `1-aapt2.sh` are only a
fallback — releases must pass them explicitly, or you will build a version that lags behind.

## Feedback

Device compatibility, failing-AP characteristics, feature ideas → [Discussions](https://github.com/wlmosv-png/wpaforge/discussions);
confirmed bugs → [issue template](https://github.com/wlmosv-png/wpaforge/issues/new/choose).

---

## Scope of use

**Only on networks you are authorised to test** (your own router, a lab, an authorised penetration test). Association attempts against networks you do not own are illegal, and the AP will log your MAC address and the time.

## License

Apache License 2.0 — see [LICENSE](LICENSE)

Design trade-offs and the bugs behind them (config-reuse false positives, verdict timing,
`finally` overwriting results) live in [`doc/TECHNICAL-NOTES.md`](doc/TECHNICAL-NOTES.md).

---

**If this helped you, a ⭐ on the repo is the entire feature request list.**

## Star History

<p align="center">
  <a href="https://star-history.com/#wlmosv-png/wpaforge&Date">
    <img src="https://api.star-history.com/svg?repos=wlmosv-png/wpaforge&type=Date" alt="Star History" width="100%">
  </a>
</p>

## My other projects

| Project | What it is |
|---|---|
| **[TGAutoSign](https://github.com/wlmosv-png/TGAutoSign)** | Xposed module for Telegram auto check-in — account slots, group check-in verdicts, schedules, themes. Send `/jmb` in any chat to manage targets. |
| **[apk-index](https://github.com/wlmosv-png/apk-index)** | Static APK index and reverse-engineering search — AXML parsing, per-dex index, lookup hook points by string / signature / structure, MCP interface. |
