# Loot Compendium integration checkpoint

This is an in-game native widget interface using existing cache group383. The two columns, tabs, search controls, selected entries, collapsing sections, scrolling, paging and detail notes use the same widget/action system as the existing client. No image generation or new raster artwork is used. Background texture1040, existing cache frames/fonts/item icons and actual NPC body geometry supply the visuals.

## Root-owned server hooks

- Initialize `io.ruin.model.inter.loot.LootCompendium.init()` after normal interface-handler registration. Reward providers are loaded lazily on first open, after the normal content registries exist.
- `::drops`: call `LootCompendium.open(player)`.
- Native NPC examine, after decoding and validating its native definition ID: call `LootCompendium.examine(player, nativeNpcId)` to show that NPC's page directly.
- No new Player fields, inventory changes, packet opcodes or raid-controller changes are needed.

`LootCompendiumSources.install()` is optional at link time and loaded reflectively when present. Its `Source`/`Drop` providers must use actual runtime tables. Scripted raid bosses link to their raid rewards; they must not gain fictional direct-kill drops. Native NPC and item IDs must resolve through existing authoritative mappings before registration.

## Existing packet and client hooks

The existing packet90 / `DROP_TABLE_INTERFACE` carries a versioned ASCII envelope beginning`@LC1:`. Its payload is UTF-8 JSON encoded with Base64 inside the existing string field. It still carries the existing no-pet sentinel and an empty legacy drop array. Pages are bounded below62000 bytes, with30 search results and60 detail rows per page. Conditions are stored server-side and sent only when requested. Search input is truncated before parsing; actions validate server-issued tokens and are rate limited. This feature never grants or rolls loot.

`CustomDropViewerInterface.init` delegates the envelope to `CustomLootCompendiumInterface.accept`. Normal legacy viewer packets restore the original cached interface and keep their original behavior. `Widget.copy$getModel` adds opt-in model type7 for real NPC bodies. The new path takes a defensive geometry copy because the NPC loader returns shared animation scratch; it never transforms world NPC geometry in place. Existing model types and public Widget API remain unchanged.

Apply only the Widget, CustomDropViewerInterface and CustomLootCompendiumInterface class families to copies of the current paired Windows/Android client binaries. Preserve all other classes/resources. If an Android bridge or Windows support JAR already overrides either class family, that overriding copy must also receive the exact same compatible extension. Preserve the current HD renderer, mobile controls, mapped popup/input logic, session lifecycle, fresh-cache selector, signing identity and protocol overlays. Do not rebuild from an older base client.

The modal uses group383's normal main-interface host, native alignment, shared drawing/input coordinates and `noClickThrough`. Both scroll panes use native scrollbar/wheel behavior and cache assets. Invisible row hit rectangles additionally use the existing native widget-drag lifecycle to swipe their own pane; crossing the6px threshold suppresses release selection. Stationary holds keep the normal context menu. Toolbar controls, world controls, inventory dragging and MobileLayout/TouchControls are unchanged. A visible-modal timer rebuilds for canvas-size changes, retaining scroll when staying on the same page. Current MobileLayout's generic hosted-modal handling should cover this group; actual Android traversal must still be verified before delivery.

Server383 registration preserves any prior handler's action array and close callback. The wrapped children delegate all legacy action paths whenever the compendium does not own the modal. Opening/reopening installs the new page state after the native modal-close callback, so reopening cannot discard its action tokens.

Search closes MAIN before sending native string-input script110. The native `meslayer_onkey`/`keypress_permit` path intentionally rejects typing while MAIN contains a subinterface, so leaving383 open prevented native typing. The server retains the captured page in a weak pending-search map while the prompt owns the chatbox, then sanitizes the native reply and reopens/reinstalls the page after close callbacks. New catalogue opens invalidate old pending searches. Native cancellation clears `Player.consumerString`, so later input packets cannot resurrect the cancelled page; the weak pending entry itself may remain until a new open/search or key collection. No keyboard, CS2 or global client input behavior changes.

## Data fidelity

- Ordinary monster rows come from actual `NPCDef.lootTable` and pet definitions. The weighted calculation respects the native sequential roller and its exact stored totals, including zero-weight awards which occur only if preceding weighted entries were passed. Invalid negative/non-finite configurations are labelled unavailable rather than normalized into fictional chances.
- Rates are base selection probabilities per roll. Player boosts, god mode, extra rolls, location drops, world events and conditional rewards are explicitly not silently folded into that number.
- Noted drops share their genuine unnoted item page, while their original noted item icons/quantities remain visible on monster tables.
- Value means the configured base item value, not a claimed live marketplace price. Locations are actual native spawn coordinates when available; unknown/scripted spawns remain labelled accordingly.
- Chambers common quantities include the actual native roller's `max(131071, personal points)` floor. Barrows supply quantities show its actual two-stage integer division and brother-count scaling; raw table amounts are not presented as the amount awarded.
- A missing provider or unknown chance is reported as unavailable/conditional. The illustrative reference screenshot's dragon-bone rates/locations are not copied.

## Verification status

On the resumed session, all three current server compendium sources compiled against the actual native classes and ASM7.1 with a512MB compiler limit. The native rates harness passed22 checks against60000 actual `LootTable.rollItems` results. `LootCompendiumPacketChecks` passed20 checks using the actual `PacketSender.sendDropTable` and `OutBuffer` writer, including a near-limit envelope, exact UTF-8/Base64 roundtrip, opcode90, native var-short length and65535 no-pet sentinel. No world, player or network connection was created by those checks. Frozen source/class hashes and logs are in the private work directory's `loot-server-checks/SERVER-FREEZE.json`; production class families were copied to the host build output for staging.

`content-tools/loot/LootCompendiumPrivatePreflight.java` and its prepared agent run only against an exact PID/candidate verified as private, loopback and offline. It verifies actual class resources against the staged candidate, then builds the read-only catalogue and checks provider diagnostics and interface handlers on the World executor. It never rolls/grants loot or changes account/player state. Actual initialized-world provider verification is still pending, and packet serialization checks do not establish client rendering or action behavior.

`content-tools/loot/LootCompendiumChecks.java` compares catalogue math with the real native loot roller, including conditional zero-weight entries, guaranteed entries, missing tables and rare-rate formatting. Remaining required checks are initialized-world provider diagnostics, native window screenshots, Items/Monsters search and selection, examine entry, collapse/scroll/paging, legacy viewer restoration, resize and Android modal/touch blocking. Do not claim these unperformed checks passed.

The2026-11-16 native search correction additionally passed27 controller/action/input assertions through the actual `ActionButtonHandler` and `InputHandler`, with interface/packet transport isolated: MAIN closes before script110, reply reinstalls the captured state, tabs/collapsed sections survive, native cancellation does not reopen, an old callback cannot consume a newer page's pending search, offline replies are rejected and input is bounded to64 characters. Rates22/60000-roll, packet20 and conditional-marker9 checks passed again. Only the16 production loot classes were copied to the host build for a new stage10; existing runtime09 class bytes and its failed native search evidence were preserved. `loot-server-checks/search-fix/SERVER-SEARCH-FREEZE.json` records the new exact source/class hashes. Actual initialized-world/native typing verification for this new candidate is still pending.
