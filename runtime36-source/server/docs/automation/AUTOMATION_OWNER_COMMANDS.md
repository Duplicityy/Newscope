# Owner automation controls

After private candidate activation, use **Owner automation** in the Android v38 menu. The panel opens in portrait. Choose a category and activity, pick a goal, and tap **Start**. Mining and level 99 are the initial choices. Each available skill switch controls whether that skill is included when training enabled skills. The master switch stops active automation when turned off. Pause releases control and retains the current goal. Resume reevaluates the live inventory and position.

Windows and existing clients can open the same server-side options from `::bot`. The original iDungeon settings are available under Dungeoneering and raids. Starting Dungeoneering and active ToB/ToA automation still uses the original native controllers.

| Command | Behavior |
|---|---|
| `::bot` / `::bot help` | Open the owner panel |
| `::bot skill <skill> <2-99>` | Train one supported skill |
| `::bot train all [level]` | Train enabled skills in sequence with one bounded retry pass |
| `::bot slayer` | Train against the existing eligible Slayer assignment |
| `::bot boss <smokedevil\|vorkath\|zulrah> <kills>` | Run the selected eligible encounter, using an ordinary kill goal of 1 through 100 |
| `::bot status` | Show active phase, progress, XP and elapsed time, or the last stop reason |
| `::bot pause` / `::bot resume` | Release or retake control of a training goal |
| `::bot stop` | Stop the current training goal even during locks |

Start in the appropriate area with normal gear and supplies. Vorkath and Zulrah require ordinary arena entry and the gear, food and spells enforced by each controller. Zulrah expects manual shrine entry again after a clear. A boss goal can end below its requested kill count if safe re-entry, supplies or actions are unavailable.
