SentinelAX-ray 2.2.1: chunk layout from the server version, verified rewrites, chunk diagnostics.

Same change as SentinelAE 2.1.1 for the line-of-sight ore layer: the section layout comes from the running Minecraft version, ambiguous chunks are left unchanged, the rewrite runs on a copy and is checked before it replaces the packet's bytes, and chunks that cannot be read exactly are counted (`/sxray status`: `chunks unreadable/ambiguous/rejected`) and logged with their coordinates.

Tests: 34 unit tests; AX-ray probe 11/11 and the mining simulation (honest miner clean, x-ray and half x-ray flagged, TNT at hidden ore flagged, random TNT clean) on Paper 26.2, 26.3, 1.21.11 and 1.21.1 with the other four plugins installed.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Checksums are in `SHA256SUMS.txt`.
