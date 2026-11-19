# Resource interactions in the installed 242 world

Newscape already has mining, woodcutting, cooking, and resource depletion engines. Their interaction registrations predate part of the 242 backport. For example, the installed landscape places copper object 38327 and iron object 38350, while the original mining code registered 10943 and 11364. Several remapped ranges already handled raw food used on the object. Their visible **Cook** action had no handler.

The new bindings connect the existing gameplay to installed map objects. The startup adapter resolves aliases from the frozen 242 manifest, checks the expected object family and visible option, and preserves any explicit action already registered. Morphing objects and unrelated definitions are excluded. Existing game rules govern level requirements, tools, inventory, yields, experience, depletion, and respawns.

| Native skill | Bound definitions | Installed scenery placements | Map squares |
| --- | ---: | ---: | ---: |
| Mining | 26 | 421 | 31 |
| Woodcutting | 15 | 961 | 35 |
| Cooking | 11 | 30 | 14 |

The 1,412 placements cover 65 distinct map squares. Mining includes two depleted-rock definitions which report the exhausted state. The modern catalogue identifies source 11386 as sandstone and 11387 as granite. The new aliases follow those verified families, and the original legacy registrations retain their prior behavior. Woodcutting uses the remapped stump when installed and the existing matching native stump otherwise. A range opens the existing cooking action directly for one ingredient, or prompts players to choose among eligible ingredients they carry.

## Home and dungeon preservation

The read-only placement audit checks Newscape home square 7991, Edgeville 12342, the Arceuus teaser in 24772, all four Daemonheim lobby squares, the authored runtime object spawn files, the home objects identified when the library teaser was mapped, and the native 727 object registry. None contain affected interaction IDs. The overlay changes Java handlers only. It uses the existing cache, object placements, and client.

## Validation

The installed cache and native skill initialization passed 88 detached JDK 8 checks, including registration of each alias, preservation of pre-existing handlers, the depleted-rock target, native stump fallback, custom resource actions, and the existing item-on-range recipes. These checks initialize the real definition decoders and action tables. Gameplay and client execution for these skill interactions remain pending.

Run the placement audit using the installed candidate's directory.

```text
python -B content-tools/audit_world242_resources.py <candidate-directory>
```

Compile the four changed Java sources ahead of the existing owner runtime classes and sealed game libraries. Compile `content-tools/checks/World242ResourceChecks.java` against that combined classpath. The JDK 8 command uses Newscape's normal Kilim class loader without starting a server.

```text
java -Xverify:all -Xmx384m -cp "<checks>;<overlay>;<owner-runtime-classpath>" kilim.tools.Kilim World242ResourceChecks <candidate-directory>
```

For a client smoke test in a development world, mine copper at (3184, 2507, 0) with a pickaxe, then observe depletion and respawn. The installed quarry at (3244, 2351, 0) has sandstone, with granite at (3243, 2353, 0). Chop the tree at (3144, 2520, 0) and check the stump and regrowth. Bring two kinds of supported raw food to the range at (3502, 3056, 0), choose **Cook**, select a recipe, and verify consumption, cooked or burnt output, and experience. Check that moving away before choosing a recipe prevents it from starting. Verify the same native skills and services at home afterward.

## Remaining gameplay gaps

The coverage counts describe targeted resource placements with native action bindings. They cannot establish that every service, object, or activity in those squares works. Conditional bank-booth morphs at imported locations need a separate player-state-aware audit before additional bank options can be wired. Quests, restricted areas, unsupported encounters, activities, and other unbound interactions remain subject to the overall coverage audit.
