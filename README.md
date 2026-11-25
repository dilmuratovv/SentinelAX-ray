<div align="center">

# SentinelAX-ray

### Ores stay hidden. Lucky miners get noticed.

An anti-xray plugin by **azicoo** for Paper servers: it sets up Paper's native anti-xray engine correctly for every world, and analyses mining to catch the cheats that obfuscation alone cannot stop.

[![Build](https://github.com/dilmuratovv/SentinelAX-ray/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/dilmuratovv/SentinelAX-ray/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/dilmuratovv/SentinelAX-ray?color=ec4899&label=release)](https://github.com/dilmuratovv/SentinelAX-ray/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/paper-1.21%20%E2%80%93%2026.3-0ea5e9.svg)](#compatibility)
[![Author](https://img.shields.io/badge/author-azicoo-8b5cf6.svg)](https://github.com/dilmuratovv/SentinelAX-ray)

[Download](https://github.com/dilmuratovv/SentinelAX-ray/releases) · [How it works](#how-it-works) · [Configuration](#configuration) · [Commands](#commands) · [Compatibility](#compatibility)

</div>

---

## About

Paper already ships a strong anti-xray engine that rewrites chunk packets before they leave the server, but it is off by default and easy to misconfigure — and on 26.x the per-world config files moved. SentinelAX-ray writes the right settings into exactly the file Paper reads for each world, tells you whether the engine is really running, and adds **mining analysis** for bypasses such as ore simulators and seed crackers.

**One JAR for every supported Paper version** (1.21 to 26.3). Players can join with any client version through ViaVersion/ViaBackwards: Paper hides the ores before the chunk is translated, so every client gets the same protected chunk.

It is part of the [Sentinel suite](#sentinel-suite).

**Created and maintained by azicoo.**

## Sentinel suite

Five plugins by **azicoo**, each with its own repository and release. Every one works on its own.

| Plugin | What it does |
|---|---|
| [SentinelAC](https://github.com/dilmuratovv/SentinelAC) | Anticheat: prediction-based movement, combat and packet checks. |
| [SentinelAD](https://github.com/dilmuratovv/SentinelAD) | Anti-dupe: a global item ledger that removes duplicated items. |
| [SentinelAE](https://github.com/dilmuratovv/SentinelAE) | Anti-ESP: players, entities and storage blocks behind walls are never sent to the client. |
| [SentinelAX-ray](https://github.com/dilmuratovv/SentinelAX-ray) | Anti-xray: Paper's engine plus line-of-sight ore hiding, and mining analysis. |
| [SentinelGuard](https://github.com/dilmuratovv/SentinelGuard) | Moderation: bans, mutes, history, `/sus`; collects the flags of AC, AD and AX-ray. |

Together: SentinelGuard adds the findings of SentinelAC, SentinelAD and SentinelAX-ray to `/sus`, and `/sg status` shows the state of SentinelAE and SentinelAX-ray. All five are GPL-3.0 and run on Paper 1.21 to 26.3 with one JAR each (SentinelGuard also has a Velocity JAR).

## Features

| Area | What SentinelAX-ray provides |
|---|---|
| **Line of sight** | Valuable ores the engine sends anyway (on cave walls, in ravines, above its height) are sent as stone, deepslate or netherrack until the player can see them; out of sight for a while, they are hidden again. Works at every height and without a restart. |
| **Native engine** | Paper's anti-xray, configured for every overworld and nether world: x-ray clients never receive the real positions of enclosed ores. |
| **Right file, every version** | Writes the `paper-world.yml` Paper actually reads (26.x: `world/dimensions/<ns>/<name>/`, 1.21: the world folder). Backed up once, written atomically, other keys kept. |
| **Automatic setup** | Settings are written on first start; one restart activates them. |
| **Honest status** | `/sxray status` reads the engine that is really running in each world. While it is not active, every start logs a warning and admins see it on join. |
| **Mining analysis** | Flags players who keep choosing the way to ore they cannot see: each tunnel turn (and each TNT, bed or anchor blast) is compared with the ways they did not take, so luck and rich areas are accounted for. Also flags very little digging per hidden vein. Catches x-ray and ore simulators. |
| **Alerts only** | Analysis never blocks mining or punishes; staff and `/sus` decide. |
| **Anti seed-cracking** | Random feature seeds by default, so ores in new chunks cannot be computed from the world seed. |

## How it works

### 1. The engine

| Mode | What the client gets |
|---|---|
| `1` hide | Enclosed ores are sent as stone. Cheapest. |
| `2` obfuscate | Stone around players is mixed with fake ores. |
| `3` obfuscate-layer | Like 2, with one random fake per layer — real ores cannot be told from fakes. |

With the line-of-sight layer on (default) mode 1 is used: the engine hides enclosed ores and the layer hides the rest, so an x-ray client receives no valuable ore at all; fakes would only cost the layer work.

Overworld up to y=64, nether up to y=128 by default. `update-radius: 2` reveals the real blocks around a block as soon as it is mined, so legitimate mining looks normal. The End is left alone.

In testing on Paper 26.3, mode 1 sent **0 of 20** enclosed diamonds to the client, and in mode 3 enclosed diamonds were indistinguishable from the fakes in stone.

### 2. Line of sight

Paper's engine only hides ores that touch no air. SentinelAX-ray rewrites every chunk packet and block update so that diamonds, ancient debris, emeralds, gold and lapis arrive as their stand-in rock, and sends the real block once one of its faces is in the player's sight within `los.reveal-distance` (64). The rewrite works on every section format from 1.21 to 26.3 and changes no packet length. A revealed ore that has been out of sight for 10 s and is more than 16 blocks away is hidden again.

### 3. Mining analysis

**Direction choices.** Each time a player turns their tunnel (and each spot they blow up with TNT, a bed or a respawn anchor), SentinelAX-ray looks 16 blocks down every way they could have gone and checks which ways hold valuable ore the player was never shown. A blind miner picks a way with ore exactly as often as chance allows; x-ray and ore simulators pick it far more often. The hits are compared with the exact Poisson-binomial distribution of those chances; at a probability of 1 in 10,000 (`detection.choices.max-p`) or less the player is flagged. The test calibrates itself to how rich the area is.

**Digging per vein.** 
Each time a player mines a diamond, ancient debris or emerald vein, SentinelAX-ray checks whether the vein was already open to a cave or another tunnel (the player's own tunnel does not count). A player who finds at least `min-hidden-veins` **hidden** veins within `window-minutes` while mining fewer than `max-filler-per-vein` filler blocks (stone, deepslate, netherrack...) per vein is flagged. Ores the player placed are ignored.

Flags go to the console, to staff with `sentinelaxray.alerts`, and to the `XrayFlagEvent` API. In testing, x-ray style mining (about 20 blocks per vein) was flagged at 75%; normal mining (about 140 blocks per vein) and ores in caves were not.

## Installation

1. Download `SentinelAX-ray-<version>-paper.jar` from [Releases](https://github.com/dilmuratovv/SentinelAX-ray/releases). Delete any old `SentinelXray-*.jar`.
2. Put it in `plugins/` and start the server: the engine settings are written automatically.
3. **Restart Paper once** — Paper reads the engine settings only at startup.
4. `/sxray status` should show `running engine-mode 3 ... config applied` for each world.

Upgrading from 2.0.0 keeps your `config.yml`, including `engine.auto-apply: false`; set it to `true` or run `/sxray apply` and restart.

## Configuration

`plugins/SentinelAX-ray/config.yml`. Detection settings reload with `/sxray reload`; engine settings need `/sxray apply` (or auto-apply) and a restart.

| Key | Default | Purpose |
|---|---|---|
| `los.enabled` · `reveal-distance` · `rehide-after-ticks` · `blocks` | `true` · `64` · `200` · valuable ores | The line-of-sight layer. |
| `engine.mode` | `1` | Engine mode (see above); 1 whenever the line-of-sight layer is on. |
| `engine.auto-apply` | `true` | Write engine settings on every start (`false`: only through `/sxray apply`). |
| `engine.update-radius` | `2` | Blocks revealed around a mined block. |
| `engine.overworld.max-block-height` · `nether.max-block-height` | `64` · `128` | Highest Y that is hidden. Higher costs more CPU and traffic. |
| `engine.<world>.hidden-blocks` · `replacement-blocks` | ores, chests, spawners... | What is hidden and what fakes are made of. |
| `engine.random-feature-seeds` | `true` | Ores in new chunks are independent of the world seed. |
| `detection.enabled` | `true` | Mining analysis. |
| `detection.window-minutes` · `min-hidden-veins` · `max-filler-per-vein` | `30` · `5` · `40` | When a player is flagged. |
| `detection.choices.window-minutes` · `min-hits` · `max-p` · `look-ahead` | `120` · `4` · `0.0001` · `16` | Direction-choice test. |
| `detection.alert-cooldown-seconds` | `120` | Minimum time between two alerts for one player. |
| `detection.valuable` · `filler` | diamond, debris, emerald · stone types | What counts as a vein and as digging. |

## Commands

**Main command:** `/sxray` (aliases `/saxray`, `/sentinelaxray`)

| Command | Purpose | Permission |
|---|---|---|
| `/sxray status` | Running engine mode per world, config state, mining analysis. | `sentinelaxray.admin` |
| `/sxray apply [--dry-run]` | Write the engine settings (or show what would change). | `sentinelaxray.admin` |
| `/sxray reload` | Reload `config.yml`. | `sentinelaxray.admin` |
| `/sxray stats <player>` | A player's mining statistics in the current window. | `sentinelaxray.admin` |
| `/sxray testflag <player>` | Send a test alert (never counts). | `sentinelaxray.admin` |

`sentinelaxray.admin` (op) includes `sentinelaxray.alerts` (op), which receives suspicious-mining alerts.

## API

`uz.sentinel.xray.api.XrayFlagEvent`: `getPlayerId`, `getPlayerName`, `getTypeId` (`xray-mining`), `getConfidence`, `getDetail`, `isTest`. `getProtectionStatus()` on the plugin returns the running engine per world (used by SentinelGuard's `/sg status`).

## Compatibility

| Platform | File | Supported versions | Java |
|---|---|---|---|
| **Paper** | `SentinelAX-ray-<version>-paper.jar` | 1.21 – 1.21.11, 26.1.1, 26.1.2, 26.2, 26.3 | 21 on 1.21.x, 25 on 26.x |

The JAR is compiled against the oldest supported API (Paper 1.21); every class and method it uses was checked to exist on each supported version. Tested on real Paper 1.21, 1.21.4, 1.21.11 and 26.3 servers with ViaVersion/ViaBackwards; chunk packets received by the client were parsed to prove what it gets. Other forks, Folia and Paper before 1.21 are not supported.

### Good to know

- The line-of-sight layer covers the valuable ores at every height; other ores (iron, coal, copper) above `max-block-height` are left to the engine's height limit. Cave shapes themselves are not hidden (no anti-xray hides air).
- Ore simulators compute ores from the world seed: random feature seeds protect new chunks only, the direction-choice test catches the mining in old ones.
- Mining analysis is statistical: a very lucky player or a very careful cheater can be misjudged, which is why it only alerts.
- Mode 3 makes chunk packets about 28% larger in testing (mode 1 slightly smaller than vanilla).

## Build from source

You need **Maven 3.9+** and **JDK 21 or newer**.

```sh
git clone https://github.com/dilmuratovv/SentinelAX-ray.git
cd SentinelAX-ray
mvn -B package
```

The JAR appears in `plugin/target/SentinelAX-ray-<version>-paper.jar`.

## Releases

Each release contains one JAR signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed) and a `SHA256SUMS.txt`. Check with `jarsigner -verify -certs SentinelAX-ray-<version>-paper.jar`.

## Credits & license

**SentinelAX-ray — created and maintained by azicoo.**

Distributed under **[GPL-3.0](LICENSE)**. Thanks to the maintainers of Paper, whose anti-xray engine does the hiding.
