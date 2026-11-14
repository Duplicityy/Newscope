# Hollow Depths persistence on Kronos 184

This adapter uses registered local account IDs (`local:<userId>`). Temporary development IDs and autonomous players cannot own dungeon rewards. It does not change client or cache revision.

## What commits together

`HollowDepthsRewards` wraps the package's `FileRewardStore`. The mailbox is an append-only total of earned credits, with idempotent extraction receipts. It must live with private runtime player data, not distributable asset definitions.

`Player.hollowDepths` stores the cumulative redeemed amounts, claim fingerprints, Agility award receipts, an active-run recovery marker and any pending extraction's complete frozen payload. A resource claim changes the inventory and redeemed ledger in the same player JSON snapshot. Available credits are earned minus redeemed; there is no separate mailbox debit. A full inventory leaves unclaimed credits available. Thirty-nine resource voucher types map to verified 184 items; nonstackable resources use verified reciprocal note definitions. Seven progression-credit types remain account-bound and do not become invented cache items.

The dungeon records a recovery destination before entering. Extraction records its stable transaction ID and full reward bundle before queueing mailbox delivery. Login replays that exact pair, including after a crash between mailbox commit and clearing the intent. Clearing a recovery marker requires actual lobby arrival; queueing a teleport is insufficient. Active unfinished run escrow is still deliberately lost on death, abandonment, timeout or process failure before extraction was durably prepared.

Trap XP is exact configured XP, capped by the normal 200-million limit, and respects the player's experience lock. It avoids generic `addXp` because that function also grants unrelated tokens and applies equipment/event multipliers. Requested XP and the unique run/obstacle receipt share the player's atomic save, so duplicate delivery is harmless and a changed payload is rejected.

## Player saves and uncertain outcomes

`PlayerFile` captures immutable JSON on the caller/world thread. Its existing single save worker writes queued snapshots in order. `saveDurably` waits up to five seconds by default (`kronos.durableSaveTimeoutMillis`, bounded to 100–10,000 ms); it does not cancel a write that might already be committing. The actor remains locked until confirmation, so uncommitted items cannot be used or traded.

`AtomicPlayerSave` forces a temporary file and atomically replaces the complete predecessor. It never falls back to truncation or a non-atomic copy. Windows reader/antivirus rename contention gets bounded retries after verifying the predecessor is intact. A failed ordinary save does not acknowledge logout; the existing logout retry remains active.

New snapshots use UTF-8. Loading first validates UTF-8 strictly, then accepts the legacy platform encoding when it differs (Cp1252 on the deployed Windows Java 8 runtime), preserving existing non-ASCII player text.

A definite pre-commit transaction failure restores its before-image. A timeout or unverified replacement retains transaction state, stops actions, suppresses subsequent player saves and locks the actor. `HollowDepthsRewards.pulse()` checks the original future: a late success releases the transaction lock, while a late definite failure restores the before-image. An outcome that remains genuinely unknown requires operator verification or restart. Login mailbox failures use a separate bounded tick-backoff recovery lock and retry automatically.

The file itself is forced. Windows may not support directory fsync, so this does not claim universal sudden-power-loss metadata durability. Back up and restore player saves and their mailbox directory at one consistent point in time. Restoring only an older player file can discard redemption receipts and must not be used as a reward recovery procedure.

## Verification

`tests/AtomicPlayerSaveChecks.java` exercises the real filesystem: initial commit, injected failure before replacement preserving the old snapshot, 60 replacements with concurrent readers, forcibly killing a separate writer process after file forcing but before rename, and a subsequent recovery commit.

`tests/HollowDepthsStateChecks.java` verifies the exposed-state JSON roundtrip, independent before-images, account binding and rejection of negative balances, orphaned payloads and invalid transaction identities.

Both standalone checks passed on the target Windows filesystem using Java 21. The atomic-file and legacy-encoding suite also passed using the actual Java 8 runtime.

The native `HollowDepthsPersistenceProbe` passed against disposable `hdqa1` in isolated revision-184 staging: all 39 resource mappings, duplicate/conflicting mailbox grants, full inventory retention, an older queued ordinary save followed by redemption, item/receipt JSON reload, duplicate/conflicting XP plus reload, replay after mailbox commit, and durable lobby recovery. It also verified that unrelated bank/equipment data stayed unchanged. Its `prepare-restart` and `verify-restart` modes separately require a genuinely different JVM to prove restart replay. Reports remain private under the stage directory; the probe refuses non-staging flags, non-loopback ports and ordinary account names.
