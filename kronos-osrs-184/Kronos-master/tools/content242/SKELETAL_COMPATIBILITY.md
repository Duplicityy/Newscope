# Skeletal animation extension for the existing184 client

This changes the internal model/animation decoder, not the UI, game protocol,
widgets, camera, input handling or account format. An updated client binary is
required. The JS5 handshake remains five bytes but advertises cache capability
184242; the game login remains184. `Content242Capabilities` and
`client-capabilities.json` describe the new records. The updater must reject an
old184 handshake before serving this cache.

## Data and implementation

The reference is the pinned TSPS tree
`1ccb548be725c70900b604968354dd3147b839ea`, particularly
`client/rs/model/skeletal/{SkeletalSeq,SkeletalBase,SkeletalBone,Curve,CurveInterp}.ts`
and `client/rs/config/npctype/NpcModelLoader.ts`. The source242 cache moved cached
animation curves to index22. The importer remaps them into separate ordinary
index0 groups and remaps the skeleton ID in bytes1–2. Skeleton index1 records
retain their native bind-pose trailer. Existing groups are never overwritten.

New sequence opcodes114,115,117 explicitly select cached animations, duration
and blend masks. Opcode13 retains its original184 sound meaning. Synthetic
one-cycle frame arrays keep existing actor/object/widget/spot animation clocks
working. Transform entry points dispatch before legacy frame lookup, including
the RuneLite interpolation wrappers. Cached-model time is local, matching the
donor's caller behavior; start/end define the duration, not a time offset.

`K242` model envelopes carry an unchanged, length-prefixed legacy geometry
payload followed by all per-vertex bone IDs/weights. Copies, shared models,
lighting and assembled models retain these arrays. Vertex merging compares
weights as well as coordinates, so independently weighted seams cannot collapse.
Old unmarked models take the original decoding path.

The implementation includes bind hierarchy/inverses, rotation/scale/translation
curves, Hermite and weighted Bezier interpolation, constant/step tangents, infinity
modes, alpha clamping and masked blending. Duplicate-time keys present in nine
real sequences are accepted with rightmost-key lookup. Bounded readers reject
truncation, non-finite inputs, invalid parents, cycles and invalid channels. A
weighted LRU limits retained curves by both sequence count and key count.
Software-renderer vertex/face, priority and depth scratch buffers grow within
explicit limits; depth buckets no longer overflow at512 faces.

`content-tools/AnimationImporter(..., allow_weighted=True)` enables this format.
The default remains the old legacy-only conversion. `weighted_model184` is the
corresponding model encoder. Raw source IDs must still be mapped by the importer.

## Evidence produced in the isolated worktree

Generated fixtures/results live in `fixtures-generated/` and are ignored by git.
They contain public cache assets, not player data. They can be regenerated from
the source cache using `extract_skeletal_fixtures.py` and
`extract_npc_skeletal_fixtures.py`.

* All1,097 weighted sequence records decode and evaluate finite curves/poses.
* All1,097 pass server duration decoding. Original legacy duration stays intact.
* All1,097 import with zero exclusions into an in-memory184 store, preserving
  original groups:1,017 distinct cached records and166 skeletal bases.
* 473 model fixtures include all452 weighted meshes and31 oversized meshes (some
  overlap), with copy/merge/lighting/shared-model checks.
* The pinned donor's unmodified curve evaluator is the independent oracle for
  66,675 values. Maximum relative error was3.64e-7.
* Legacy vertex/alpha output hashes match the unmodified client exactly for131
  sequences,1,677 decoded frames and132 male/female Torva/Fang outfit poses:
  `e14fa3343b7e30cb2df2ba08e56b3f9eae907783028ea3adcc3f06d39dd50e31`.
* Five actual NPC assemblies (Scurrius, Amoxliatl, three Muspah forms) deform
  correctly within bounds across56 sampled idle/walk/turn poses. This is a native
  model check, not an actual-window screenshot test.
* Constructed tests cover hierarchy, inverse binding, multiple weights, alpha,
  cached-model immutability, malformed input and all infinity modes.

The full standalone client `shadowJar` build passed with one worker and a768MiB
Gradle heap. The final probes ran against that complete jar without replacing
client classes:110,565,527 decoder/model assertions,38 constructed contracts,
66,675 curve-oracle values and the unchanged legacy hash all passed. Its SHA256
was `2d28fb3c0a40ef129f0249184808fe8dca23ab0f4da821112200af7cf51c6a79`.
Later source changes require a fresh build and fingerprint; this fingerprint is
decoder evidence, not a deployment approval.

`Skeletal242Probe`, `Curve242OracleProbe`, `Legacy242Parity`,
`Npc242SkeletalProbe`, `Skeletal242ContractProbe` and `Server242AnimProbe` use
small javac invocations against the existing client/server jars. They do not
launch a server, attach to production, create an account or change gameplay.

## Remaining limits and staging checks

Six source meshes with referenced complex texture coordinates remain explicitly
excluded by the model converter. Animation sound IDs are not remapped by this
module, and source sequence visual-offset opcode16 is not applied. The generated
capability manifest records these boundaries.

All456 source identity-kit meshes are unweighted. The selected Fang, Voidwaker,
Shadow, bow and ordinary movement animations are legacy and therefore use their
existing group-based rig. Refreshing a kit does not manufacture Maya weights;
skeletal clips must be paired with their actual NPC/object/spot rig. Custom
unweighted meshes cannot be made skeletal merely by changing an animation ID.

Actual staged login, cached-archive delivery, visible weighted NPC motion,
alpha transitions, movement/attack blends, weighted spot/object animations and
both player body types still need the freshly built client and complete mapped
cache. The decoder evidence does not assert every entity pairing or visual scene
has been inspected. No deployment, production cache, current client/APK, Hollow
Depths or autonomous-player state was changed by this work.
