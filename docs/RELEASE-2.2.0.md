SentinelAX-ray 2.2.0: no valuable ore reaches an x-ray client, and ore simulators are caught by how they choose where to dig. Paper 1.21 through 26.3 (one JAR, Java 21; Java 25 on 26.x).

**Line-of-sight ore hiding**
- Paper's engine only hides ores that touch no air; ores on cave walls, in ravines and above its height limit were sent as they are, and that is what x-ray shows. Now diamonds, ancient debris, emeralds, gold and lapis arrive as their stand-in rock (deepslate in deepslate, netherrack in the Nether, stone otherwise) in every chunk and block update, and the real block is sent once one of its faces is in the player's sight within 64 blocks. Out of sight for 10 s and more than 16 blocks away, it is hidden again. Works at every height, needs no restart.
- The chunk rewrite reads every section layout from 1.21 to 26.3 and changes no packet length. When the stand-in's id would not fit the replaced palette entry, the section's own stand-in entry is used (deepslate sections have deepslate), otherwise stone.
- With the layer on, the engine runs in mode 1: it hides enclosed ores, the layer hides the rest. Fakes of modes 2/3 would only cost the layer work.

**Mining analysis that holds up**
- New direction-choice test: every tunnel turn, and every TNT, bed or respawn-anchor blast, is a choice between ways that do or do not hold ore the player was never shown (16 blocks ahead, 5 wide, 4 high; five spots for blasts). Hits are compared with the exact Poisson-binomial distribution of the blind chances, so the area's richness is accounted for; flagged at p ≤ 1e-4 with at least 4 hits. Up to 1.2 the test could be beaten with 40 blocks of digging per vein, fewer than 5 veins in 30 minutes, TNT or beds, or a rejoin; none of that helps against this test.
- A rejoin no longer turns the player's own tunnel into a "foreign opening".
- Random feature seeds are on by default (new chunks cannot be computed from the seed).
- Engine settings also go to `config/paper-world-defaults.yml` and to worlds loaded later (Multiverse).

**Validation**
- 31 unit tests, including the chunk rewriter against every section layout and the stand-in fallback, and the choice test against simulated miners: 20,000 blind sessions in poor to rich areas gave at most 4 flags; an x-ray miner who goes for ore 90% of the time is caught after 40 turns in ≥ 88% and after 80 turns in ≥ 99% of sessions, one who does it half the time after 120 turns in ≥ 80%.
- Protocol bot (`tools/axray_probe.py`, a viewer bot decoding every chunk), 11/11 on Paper 26.3, 1.21.11 and 1.21.1, and together with SentinelAE: cave ore seen from outside the hill arrives as stone, deepslate ore as deepslate, ore sealed in stone and an emerald far up in the sky as stone; inside the cave the ores on its walls are revealed, the sealed one stays hidden until dug to; revealed ore is hidden again after leaving; the emerald is revealed in sight.
- Mining simulator (`tools/mining_probe.py` with the test-only `tools/mining-harness`, real BlockBreakEvents through Player#breakBlock, vanilla-like diamond density): a blind strip miner (120 turns) was not flagged (11 of 25 choices hit ore, 10.3 expected by chance); an x-ray miner was flagged (12 of 12, p = 3.8e-6) and so was one who cheats half the time; a player who set off TNT next to hidden diamonds was flagged after 7 blasts (p = 5e-5), one who did so at random was not.
- Real x-ray client (`tools/axray_realclient.py`): Minecraft 1.21.11 with Meteor Client's X-ray on: outside the hill no ore shows, inside the cave the diamonds and gold on its walls do, after walking away and back they are gone again.
- Load (`tools/axray_load.py`): 8,192 exposed diamond ores and five moving players on Paper 1.21.11: average tick 6.5 ms against 4.1 ms without the plugin, worst tick 27 ms against 29 ms.

**Upgrade:** replace the JAR and restart. Existing configs keep their `engine.mode`; it is used as 1 while `los.enabled` is true. `random-feature-seeds` stays as set in an existing config.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs <jar>`. Checksums are in `SHA256SUMS.txt`. License: GPL-3.0.
