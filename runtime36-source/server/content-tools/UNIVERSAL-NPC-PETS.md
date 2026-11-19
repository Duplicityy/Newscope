# Universal NPC pets

The definitions use the selected Newscape cache's existing model, colour, texture, and sequence IDs. The generator measures the existing model geometry and applies half of each source scale at or above the Skeleton threshold. Generated followers occupy a single tile and use native follower movement. Their attack option only plays an existing animation.

The frozen registry under content-tools/registries associates a source creature with one follower and one unsigned inventory token. Regenerate against the matching base cache and carry this registry forward. Its existing IDs remain assigned even when new creatures are added. The optional rate field overrides the 1 in 5,000 default. A zero rate inherits the default.

Enable the overlay on a paired candidate with the kronos.universalPets system property. Generated pet rolls run independently of the ordinary loot and existing pet progression. Ported encounters use their participation checks and terminal-defeat callbacks. Player-owned NPCs and raid practice do not award these pets. The canonical source appearance is fixed when a claim is earned.

Earned pets are saved to the account's claims before they are announced. Use ::npcpets to claim one. Drop the item to release a follower. Pick up the follower to return it to claims. Carried generated tokens return to claims on ordinary death. The active follower is restored after the player is online and eligible for following.

## Candidate validation

The private check loads the real cache decoders, generated NPC definitions, inventory actions, and registered-account save code. It uses an embedded channel and fixture regions. The copied test account has 800 bank items and 3,130 additional generated pets accessible through claims. The fixture must stay on localhost.

The Windows and Android client JARs support the existing definition encoding. The paired Windows bootstrap uses a separate profile, a hash-checked cache, and localhost. The normal Windows and Android client binaries do not require renderer changes. The Android check exercises the embedded Java decoder on the host. Physical phone testing, actual world login, full raid and dungeon clears, and long-session performance remain to be verified.

## Catalog

The standalone Newscape Pet Catalog shows generated and existing pets. The client software renderer captures the original cache models. When the model cannot produce a usable capture, the catalog shows the original inventory item model. Existing pet rates shown are the enum's fixed base rates when present. Individual content sources and existing progression keep their own rates.