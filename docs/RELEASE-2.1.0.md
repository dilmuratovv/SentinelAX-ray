SentinelAX-ray 2.1.0: anti-xray for Paper 1.21 through 26.3 (one JAR, Java 21; Java 25 on 26.x). Works with any client version through ViaVersion/ViaBackwards (Paper rewrites chunks before they are translated).

**How it works**
- Configures Paper's native anti-xray engine correctly for every overworld and nether world (`paper-world.yml`, backed up once, written atomically): x-ray clients never receive the real positions of hidden ores. Default engine-mode 3 (fake ores in stone).
- Mining analysis for bypasses such as ore simulators: players who keep reaching hidden veins with very little digging are flagged (alerts and `XrayFlagEvent` only, never punishes). SentinelGuard 2.2 adds the flags to `/sus`.

**New in 2.1.0**
- **Automatic setup.** On a new install the engine settings are written on first start (`engine.auto-apply: true`); restart Paper once. Upgrading from 2.0.0 keeps your `auto-apply` value: set it to `true` or run `/sxray apply`.
- **Loud warnings.** While the engine is not running as configured (not applied, or a restart is pending) a WARN is logged on every start and admins (`sentinelaxray.admin`) see a red message on join.
- A SEVERE warning when the old **SentinelXray** plugin is still installed next to it.
- `/sxray status` no longer asks for a restart on Paper builds where the running engine cannot be read.

**Upgrade:** delete any `SentinelXray-*.jar`, replace the JAR, restart, check `/sxray status` shows "config applied".

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs <jar>`. Checksums are in `SHA256SUMS.txt`. License: GPL-3.0.

