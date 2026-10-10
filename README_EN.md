# WPAForge

> Android WPA passphrase cracker — real sequential connection attempts, with target-derived candidates, smart ranking, resumable runs, and a background queue.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2026%2B-green.svg)]()
[![Version](https://img.shields.io/badge/version-0.5.0-orange.svg)]()

[中文](README.md) | **English**

---

## What it is

WPAForge tries passphrases against a target network one at a time via the system's `cmd wifi` interface, and uses the **real association result** to tell whether a passphrase is correct.

**How it differs from typical WiFi cracking tools:**

| | WPAForge | Typical tools |
|---|---|---|
| **Target-derived dictionary** | Generates candidates from SSID / BSSID on the fly — a few hundred cover common cases | Blind sequential scan of a wordlist |
| **Smart ranking** | Scores and reorders candidates by target features | None |
| **Resumable** | Progress persisted continuously; resume from where it stopped | Restart from scratch |
| **Multi-target queue** | Automatic sequential execution; per-task pause/resume | One at a time |
| **Background operation** | Foreground service + notification progress, keeps running while locked | Partial |
| **Online dictionaries** | 10 public sources, one-tap download, filtered during download | Find them yourself |
| **Transparent crypto** | Hand-written implementation, validated against RFC 3174 / 2202 / 6070 | Black box |

---

## Technical highlights

### 1. Why `cmd wifi` instead of `WifiNetworkSpecifier`

Since Android 10, `WifiNetworkSpecifier.requestNetwork()` **forces a system confirmation dialog**. A normal app cannot connect silently. Running a wordlist through it means every single attempt needs a manual tap — 500 attempts, 500 taps.

Real error observed:

```
ForegroundServiceStartNotAllowedException:
Service.startForeground() not allowed due to mAllowStartForeground false
```

`cmd wifi connect-network` is a shell-privileged command and is not subject to that restriction. With root it can be invoked silently, and the result is readable.

Measured behaviour:

```
Correct passphrase → ~12s later  Wifi is connected to "<SSID>"
Wrong passphrase   → ~3s later   Wifi is not connected
```

**A trap you have to handle**: if the system already stores a configuration for the target network, `connect-network` will reuse the old credentials, producing **false positives**. Every attempt must be preceded by `forget-network` on the target.

### 2. Target derivation — adding what the wordlist lacks

Smart ranking can only **reorder existing** candidates. If `FAST_E972123` is not in the wordlist, it will never surface.

`TargetDictBuilder` generates candidates from the target itself:

```
Input:   SSID  = FAST_E972
         BSSID = AA:BB:CC:E9:72:11

Output:  FAST_E972 / fast_e972 / FASTE972       ← original + case variants
         97297297 / 97297200 / 97297288         ← digit repeat family
         e9721100 / e9721188 / E97211E9         ← BSSID tail family
         e972123e / e9721230 / e97212345       ← segment + digits
         E972E972 / e972e972                    ← segment repetition
```

**Key design point**: WPA requires at least 8 characters, but users typically want 6–7 (`972888`, `209209`). Short fragments are therefore **padded with the most likely fillers** rather than discarded.

Seven categories are merged **round-robin**, so the first few dozen entries already cover every category instead of one class monopolising the head of the list.

### 3. Smart ranking — surfacing the right entry sooner

No candidates are added or removed; only the order changes:

| Signal | Weight |
|---|---|
| SSID exact match | +900 |
| SSID prefix match | +400 |
| Digit string from SSID | +350 |
| BSSID tail | +320 |
| Common weak passphrase | +260 |
| Length near 8–11 | +60 |
| All-digits preference | +45 |

Measured (target `B209`, BSSID tail `45DCED`):

```
in:  aardvark, zzzzzzzzzz, B209123, randompass, 45dced, 12345678

out: B209123 → 45dced → 12345678 → zzzzzzzzzz → aardvark → randompass
     ↑SSID    ↑MAC tail  ↑common weak      unrelated sink to bottom
```

### 4. Why the speed is ~1 second per attempt

The AP's response time to a wrong passphrase is a physical limit. A phone can only associate with one network at a time — **parallelism is impossible**. This is not an implementation problem.

| Wordlist size | Time (~1s each) |
|---|---|
| 500 | 8 minutes |
| 5,000 | 1.4 hours |
| 50,000 | 14 hours |

**Quality matters far more than size.** That is exactly why target derivation and smart ranking exist.

---

## Cryptography

WPA key derivation is implemented from the **IEEE 802.11i** specification, without third-party crypto libraries:

```
PMK = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 256bit)
PTK = PRF-512(PMK, "Pairwise key expansion",
              min(AA,SPA) || max(AA,SPA) || min(ANonce,SNonce) || max(ANonce,SNonce))
MIC = HMAC-SHA1(KCK, EAPOL_frame_with_MIC_zeroed)[0..16)
```

### Validation

| Component | Test vectors | Result |
|---|---|---|
| SHA-1 | RFC 3174 / FIPS 180-1 | ✅ 3/3 |
| HMAC-SHA1 | RFC 2202 | ✅ 3/3 |
| PBKDF2-HMAC-SHA1 | RFC 6070 | ✅ 3/3 |
| PMK | Wi-Fi Alliance public vector | ✅ 1/1 |

Full suite (102 checks):

```
SelfTest        36 / 36   crypto (RFC vectors)
RuleTest        25 / 25   rule-based dictionary parsing
E2ETest         10 / 10   handshake comparison, end-to-end
SortTest        14 / 14   smart ranking
TargetDictTest  17 / 17   target derivation
```

---

## Features

```
Observe   Scan nearby networks · filters · signal bars · channel analysis
Audit     Built-in generator · load dictionaries · passphrase strength check
Crack     Pick target · pick dictionary · derivation toggle · ranking toggle
          · queue · live candidate stream
Dicts     Create (plain/rule) · import · download · merge · view · edit · stats
Settings  Saved-WiFi inspection · history · export
About     Project info · author links
```

**Three-layer candidate strategy on the crack screen:**

```
Layer 1  Target derivation   ~500        generated from SSID/BSSID
Layer 2  Smart ranking       full set    reordered by target features
Layer 3  Dictionary order    full set    fallback
```

---

## Building

This project **does not use Gradle**. The build chain is a manual two-stage process — because `aapt2` and `d8` ship as arm64 native binaries on Android and cannot run on the desktop, while the desktop has `javac`.

```sh
# Stage 1: on-device (aapt2 compiles resources, links base.apk)
sh 1-aapt2.sh

# Stage 2: on desktop (javac + d8 + assembly + signing)
sh 2-build-dex.sh
```

Output: `build/out/wpaforge-signed.apk`

**Rationale**: no SDK download, no network dependency, fully explicit build chain. The trade-off is having to run two stages.

---

## Permissions

| Permission | Purpose |
|---|---|
| `NEARBY_WIFI_DEVICES` | Read scan results on Android 13+ |
| `ACCESS_FINE_LOCATION` | Read scan results on Android 12 and below |
| `CHANGE_NETWORK_STATE` | Required by `requestNetwork` |
| `FOREGROUND_SERVICE` | Background cracking |
| `POST_NOTIFICATIONS` | Progress notification |

**Root** is required for the cracking feature, to invoke `cmd wifi`.

---

## Scope of use

**Use only on networks you are authorised to test** — your own router, a lab, or an authorised penetration test.

WPAForge issues **real association requests** to the target AP. On your own network this is a self-audit; on an unauthorised network it is unlawful, the AP will log failed attempts with MAC and timestamps, and sustained high-frequency attempts can pressure the AP's connection table.

Ensure you have explicit authorisation for the target network.

---

## Built-in dictionaries

The base dictionaries under `assets/builtin/` come from **publicly published weak-password statistics** and generically derived pattern tables (weak-password rankings, numeric patterns, alphanumeric combinations, common English word frequencies). These are long-standing public security research datasets, not the original work of any single project.

Online dictionary sources are fetched from [SecLists](https://github.com/danielmiessler/SecLists) (MIT) and similar public projects. Data lands on the user's device; this repository does not contain it.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)

## Author

**wlmosv**

- GitHub: https://github.com/wlmosv-png
- Telegram: [@wlmosv_bunny](https://t.me/wlmosv_bunny)

### Other projects

**[TGAutoSign](https://github.com/wlmosv-png)** — An Xposed module for automated Telegram check-ins. Multi-account slots, group check-in classification, scheduled plans, multi-theme UI. Entry point: send `/jmb` in any chat.

**[apk-index](https://github.com/wlmosv-png)** — Static APK indexing and reverse-engineering search. Parses AXML, indexes every dex, looks up hook points by string/signature/structure.
