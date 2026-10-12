# WPAForge

> Android WPA passphrase cracker — real sequential association attempts, with target-derived candidates, smart ranking, resumable runs, and a background queue.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2026%2B-green.svg)]()
[![Version](https://img.shields.io/badge/version-1.9.0%20(70)-orange.svg)]()

[中文](README.md) | **English**

---

<p align="center">
  <img src="docs/screenshots/all.png" alt="WPAForge UI" width="100%">
</p>

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
| **Vendor identification** | Built-in IEEE OUI table (15,913 entries) — shows the router brand | None |
| **Aggregation** | Multiple BSSIDs on the same SSID are grouped | Flat duplicates |
| **Transparent crypto** | Hand-written implementation, validated against RFC 3174 / 2202 / 6070 | Black box |

---

## Technical highlights

### 1. Single channel, one decision path

This version **ships with the root channel only** (`su -c "cmd wifi ..."`).
Shizuku and root-free were removed; the reasons are below.

All logic shares one **`CrackEngine`**. The channel only sends a candidate out;
the verdict is decided entirely inside the engine — so there is exactly one copy
of the decision rules, and fixing it fixes every path.

```
                     ┌──────────────────┐
   Root channel ────→│   CrackEngine    │ ←── single verdict entry point
   (su + cmd wifi)   │   (decides)      │
                     └────────┬─────────┘
                              │
                     ShellChannel (su)
```

**Why the other two were dropped**

| Channel | Reason for removal |
|---|---|
| Shizuku | `cmd wifi` string commands get eaten by shell escaping and ROM differences,
so the netId is never created and the verdict always fails. Binder calls could work around
it, but that is a second implementation to maintain. |
| Root-free | `WifiNetworkSpecifier` **forces a system confirmation dialog for every single candidate**.
That is an Android security boundary, not an implementation defect — it cannot be disabled,
which makes the mode practically useless. |

Rather than let users trip over three channels, only the one that **actually works on a real device** is kept.

### 1.1 The verdict: four conditions from one source, plus netId

No single signal is reliable — that conclusion came from a round of false positives.

The current rule requires **four conditions from the same `WifiInfo` line**:

```
① SSID             == target
② Supplicant state == COMPLETED     ← 4-Way Handshake completed
③ IP               valid (not 0.0.0.0)
④ Net ID           == the netId created by this very attempt
```

Requiring all four on one line is what excludes "the system reconnected to some other network" —
a neighbour's AP or a previously saved network can satisfy two or three of them, but never all four.

**One deliberate compromise on condition ④**: `netId` is assigned by the system but is
timing-sensitive — right after the command is issued the configuration may not exist yet,
so the value can read `-1`. Enforcing it strictly would make **correct passphrases never
register** (observed on a real device: correct passphrase, 12-second timeout, 33 status
reads, not a single hit).

So it is implemented **leniently**: the two sides must match only when both carry a valid
netId; if either is `-1` the condition is treated as inconclusive and the other three decide.
On a hit, the evidence is recorded in `confirmedBy` (`supplicant+netId` means the netId matched too).

### 1.2 Measured timings

Per-candidate cost on a real device (OnePlus PJX110 / Android 15, root channel):

```
forget + list-networks   209 ms
cmd wifi status          100 ms
connect-network issued    87 ms
logcat read              239 ms
--------------------------------
one polling round        ~300 ms
```

```
Correct passphrase → T+1s   handshake completes, IP obtained
Wrong passphrase   → T+1~6s the system gives up
                     T+7s   falls back to another saved AP
```

This is why a wrong passphrase still takes seconds — it is the AP's response time,
not slow code.

### 1.3 Three traps you must handle

**Trap 1 — configuration reuse causes false positives**

`cmd wifi connect-network` **reuses existing configuration** for a network with the same name.
If the target was connected before, the system reconnects with the stored credentials
regardless of the passphrase supplied → it connects → reported as success.
Measured: supplying `00000000` still "hit".

Handling: forget the configuration before every attempt and **read back to verify**;
and if the target is already connected when the run starts, disconnect first to establish
a clean baseline.

**Trap 2 — PMKSA cache**

WPA2 keeps a key cache for fast reconnection. Even after the configuration is forgotten,
`wpa_supplicant` may briefly reconnect with the cached key — **so a wrong passphrase also connects**.

Handling: restart Wi-Fi once (off → on) when a run begins, forcing `wpa_supplicant` to start over.
Done once per run, so it does not affect per-attempt speed.

**Trap 3 — a hit overwritten as a miss**

A `finally` block still runs after `return`, so the completion callback fired twice;
the second call carried a `null` passphrase and overwrote `DONE_HIT` with `DONE_MISS`.

Handling: a `finished` flag.

> The first two traps originally existed **once per channel** (because the verdict logic was
duplicated). That is the direct reason all channels were collapsed into a single engine.

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

### 4. Speed: why it cannot go faster

The AP's response time to a wrong passphrase is a physical limit. A phone can only be
associated with one network at a time — **parallelism is impossible**. This is not an
implementation problem.

Per-candidate cost breakdown (measured, root channel):

```
forget + list-networks   209 ms
cmd wifi status          100 ms
connect-network issued    87 ms
logcat read              239 ms
--------------------------------
one polling round        ~300 ms
```

A correct passphrase resolves in about 1.1 s; a wrong one takes **1–6 s**, depending on
how long the system keeps trying.

| Wordlist size | Time (at ~1.2 s each) |
|---|---|
| 500 | ~10 minutes |
| 5,000 | ~1.7 hours |
| 50,000 | ~16.7 hours |

**Quality matters far more than size.** That is exactly why target derivation and smart
ranking exist — to push the right entry into the first few dozen positions instead of
position 50,000.

> The UI shows "~X s per attempt" rather than "X attempts/s": at 0.1 the latter looks
> like nothing is happening, while the former is the number users actually care about.

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

Full suite (**346 checks across 20 test classes**), all passing:

```
SelfTest             crypto (SHA-1 / HMAC-SHA1 / PBKDF2, RFC vectors)
SupplicantParserTest WifiInfo line parsing (COMPLETED / IP / netId)
StatusParseTest      cmd wifi status parsing, including the decoy line
DecoyLineTest        decoy-line regression (<unknown ssid> + COMPLETED + Net ID: -1)
FalsePositiveTest    false-positive defences (reconnect elsewhere / netId mismatch)
NetIdLineTest        list-networks parsing (SSIDs containing spaces)
PrivScanTest         privileged scan output (CJK SSIDs, spaces, hidden networks)
RuleTest             rule-based dictionary expansion
SortTest             smart ranking
TargetDictTest       target derivation
SsidGroupTest        multi-AP aggregation on one SSID
OuiTest              OUI lookup (randomised-MAC detection, BSSID normalisation)
E2ETest              handshake comparison, end-to-end
VerdictFlowTest      verdict flow
WatcherLogicTest     network event decisions
WifiStateLogTest     state-machine log parsing
WpaLogTest           wpa_supplicant log parsing
SpecifierTest        verdict semantics (timeout / SSID mismatch must NEVER pass)
PersistTest          candidate generation → persist → read-back consistency
AttributionTest      attribution (which layer confirmed the result)
```

The logic is decoupled from Android and **runs directly on a desktop JVM**
(javac + java, no emulator required).

`SpecifierTest` and `FalsePositiveTest` are the key regression guards — they freeze the
verdict semantics as tests so rules like "a timeout is not a success" or
"reconnecting to another network is not a hit" cannot be broken by later changes.

---

## Features

```
Observe   Scan nearby networks · filters (all/secured/open/hidden/WPS)
          Radar sweep · signal bars · channel load and channel advice
          Bandwidth and Wi-Fi generation · vendor identification (IEEE OUI)
          Multi-AP aggregation per SSID · saved-WiFi inspection
Audit     Built-in generator · derive from SSID · load dictionaries
          Passphrase strength check
Crack     Pick target · pick dictionary · queue · three-layer progress bar
          Per-line coloured attack stream · pause/resume/stop · resumable
Dicts     Create (plain/rule) · import · download · merge · view · edit · stats
Settings  Channel status · permissions · connection self-test · export log
          Language · PIN lock · long-press the channel row for full evidence
About     Project info · author links · scope of use
```

**UI language**: follows the system locale (Chinese systems show Chinese, others English),
and can be switched manually in Settings.

**Three-layer candidate strategy on the crack screen:**

```
Layer 1  Target derivation   ~500        generated from SSID/BSSID
Layer 2  Smart ranking       full set    reordered by target features
Layer 3  Dictionary order    full set    fallback
```

**Progress and recovery**: `offset` is persisted after each candidate resolves
(throttled to at most once every 2 s or every 20 entries); a hit, pause or stop persists
immediately. After the process is reclaimed, a run resumes from where it stopped.

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
| `FOREGROUND_SERVICE` | Background cracking |
| `POST_NOTIFICATIONS` | Progress notification |

**Root** is required for the cracking feature, to invoke `cmd wifi`.
Runtime permissions are requested automatically on launch; if permanently denied, the
Settings page offers an "Allow in system settings" entry.

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

`assets/builtin/oui.txt` comes from the **IEEE public OUI registry**
([standards-oui.ieee.org](https://standards-oui.ieee.org/oui/oui.txt), MA-L block),
trimmed to 15,913 network-equipment entries. It is used only to display the NIC vendor.
Addresses not present in the table are shown blank; nothing is guessed.

---

## Known limitations

| Item | Note |
|---|---|
| **Root only** | The channel is fixed to `su + cmd wifi`; Shizuku and root-free are not supported |
| **6 GHz unverified** | The band-check path exists in code, but the test device cannot see 6 GHz networks |
| **WPS is read-only** | Identification and filtering only; no PIN brute force |
| **Vendor names depend on the IEEE table** | OUIs not present are shown blank, never guessed |
| **Randomised MACs cannot be traced** | Android 10+ generates a per-SSID randomised MAC;
the protocol carries no vendor information |
| **Serial only** | A phone can associate with one network at a time; attempts cannot be parallelised |

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
