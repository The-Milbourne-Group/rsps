# Continuous Pest Control (KSBot)

A KSBot script that plays Pest Control indefinitely: board the lander, wait,
fight the game out, get returned to the outpost, board again.

Source: [`src/PestControlScript.java`](src/PestControlScript.java)

## Important: what is verified and what is not

The only KSBot API reference available when this was written was the demo
Woodcutter script. `ksbot.org` was unreachable from the build environment, and
neither of this account's repositories contained the API.

So the script is written in two layers:

- **Game logic** (the top ~300 lines) — uses only the small wrapper methods
  defined at the bottom of the file. This part is API-independent.
- **API adapter** (the bottom ~90 lines) — every call that could not be verified
  against real docs, isolated one call per method, each with a comment naming the
  likely alternatives.

If it does not compile, the errors will land in the adapter block. Each fix is a
single line. Nothing in the game logic needs to change.

### Verified from the demo script

| Call | Used for |
|---|---|
| `ctx.log(String)` | logging |
| `ctx.sleep(min, max)` | blocking sleeps |
| `setStatus(String)` | status line |
| `getTimer().getElapsedTime()` | runtime in the summary |
| `ctx.players.getLocal().isIdle()` | "am I already fighting" |
| `ctx.groundObjects.query().withName(...).closest()` | finding the gangplank |
| `KSObject.interact(String)` | crossing the gangplank |
| `ctx.inventory` name-based lookups | eating (`dropAll(String)` proves the shape) |

### Not verified — check these first

| Call | Most likely alternatives |
|---|---|
| `ctx.npcs.query().withName(...).closest()` | mirrors the verified `groundObjects` query exactly; the collection name is the risk |
| `KSNpc.interact("Attack")` | action string may differ per server |
| `ctx.players.getLocal().getHealthPercent()` | `ctx.combat.getHealthPercent()`, `ctx.skills.getCurrentLevel(Skill.HITPOINTS)` |
| `ctx.inventory.getItem(name).interact("Eat")` | `ctx.inventory.interact(name, "Eat")` |
| `ctx.walking.walkTo(...)` | `ctx.movement.walkTo(...)` — only used if auto-walk is off |
| `npc.getPosition()` | `npc.getTile()`, `npc.getIndex()` |
| `Category.COMBAT` in the manifest | the demo only proves `Category.WOODCUTTING` exists; `Category.MINIGAME` may be the right constant |

### Compilation status

The script compiles cleanly (`javac`, no warnings) against a stub of the API
shape described above. That verifies the script's own syntax and type-correctness
only — it does **not** verify that the real KSBot API matches the stub. The
adapter block is where any mismatch will surface.

## Configuration

All at the top of `PestControlScript.java`:

| Setting | Default | Notes |
|---|---|---|
| `LANDER` | `NOVICE` | Labels only — boarding finds the nearest gangplank, so stand at the lander you want |
| `EAT_AT_HP_PERCENT` | `50` | Set food to an empty array to disable eating |
| `FOOD` | Shark → Tuna | Priority order |
| `PORTAL_NAMES` | `Portal` | Change if your server names portals differently |
| `PEST_NAMES` | Spinner first | **Order is kill priority, not a list** — see below |
| `RELY_ON_INTERACT_AUTOWALK` | `true` | Set false if `interact()` does not walk to out-of-range targets |

## How it decides what to do

State is derived from **what is on screen**, not from coordinates or widget IDs,
because those vary between private servers while "a portal is visible" does not.

```
portals or pests visible?  -> IN_GAME
else, boarded recently?    -> WAITING
else                       -> BOARDING
```

The `WAITING` state has a 6-minute ceiling. If a game has not started by then the
boarded flag is treated as stale and the script re-boards, which is what recovers
it from a failed gangplank click or an unexpected teleport.

Game-end is detected by the transition out of `IN_GAME`, which also increments the
game counter and clears the portal blacklist.

### In-game priority

1. **Eat** if HP is at or below the threshold.
2. **Do nothing** if already in combat — never interrupt an ongoing fight.
3. **Kill Spinners.** They repair portals, so damage dealt while one is alive is
   wasted. This is why they are first in `PEST_NAMES` and also get their own
   explicit check ahead of the portal step.
4. **Attack a portal.**
5. **Kill other pests** when every portal is still shielded. This also keeps
   activity up, which is what the points requirement measures.
6. **Close distance** to a portal if nothing is in range.

### Shielded portals

A shielded portal refuses the attack. Rather than detect shields via widget or
NPC IDs — both server-specific — the script attempts the attack, and on refusal
blacklists that portal by position for 12 seconds and moves to the next one.
Portals do not move, so position is a stable key for the length of a game.

## Setup

1. Set `author` in the `@ScriptManifest`.
2. Verify `PORTAL_NAMES` and `PEST_NAMES` against your server's NPC names.
3. Verify `servers = { "osrs" }` matches your target server.
4. Compile against your KSBot API jar and drop the class into your scripts
   directory.
5. Start the script standing at the Void Knights' Outpost, near the lander you
   want to use, with food in the inventory.

## Not implemented

- Boat selection by combat level — boarding takes the nearest gangplank, so
  position the character at the intended lander before starting.
- Points tracking — reading the points counter needs widget IDs, which are
  server-specific and could not be verified here.
- Banking or restocking food between games.
- Prayer or special attack usage.

---

# West Dragons (KSBot)

Kills west dragons, loots only their bones, and restocks through a bank preset
when supplies run out. Runs indefinitely.

Source: [`src/WestDragonsScript.java`](src/WestDragonsScript.java)

Unlike the Pest Control script, this one was written against the real API
(`libs/rs.kreme.elorin-api.jar`), so every call is verified rather than guessed.
It compiles clean under `javac -Xlint:all`.

## The loop

```
supplies OK  ->  kill dragon -> take bones -> repeat
supplies out ->  home teleport -> bank booth -> Last-Preset -> teleport back
```

A restock trip starts when **any** of these is true:

- the inventory is full
- there is no food left
- there are no prayer potions left

Eating and prayer restore run on every tick, including mid-trip, so a low-HP
teleport still gets healed on the way.

## Restocking

The trip is:

1. `ctx.magic.teleportHomeAndWait(...)` — home.
2. Find the bank booth.
3. If the booth has a **Last-Preset** right-click action, click it. That restocks
   in one click, which is how most servers expose presets.
4. Otherwise open the bank and call `ctx.presets.lastPreset()` from inside it.
5. `ctx.teleporter.teleportAndWait(..., DRAGON_DESTINATION)` — back to the dragons.

The trip is tracked with a flag rather than inferred from the screen, because
"at home with a full inventory" and "at home having just restocked" look
identical to a screen test.

## Configuration

Only four names need checking against your server:

| Setting | Default | Matching |
|---|---|---|
| `DRAGON_NAME` | `Green dragon` | **Wildcard** — `"Dragon"` would also catch blues and reds |
| `LOOT_NAME` | `Dragon bones` | **Exact** — nothing else on the ground is ever taken |
| `BANK_BOOTH_NAME` | `Bank booth` | Wildcard |
| `DRAGON_DESTINATION` | `Green dragons` | Must match the entry in your teleport menu |

Thresholds:

| Setting | Default |
|---|---|
| `EAT_AT_HP_PERCENT` | `50` |
| `DRINK_PRAYER_AT_PERCENT` | `30` |
| `LOOT_RADIUS` | `8` tiles |

## Not implemented

- **Antifire is not handled.** `ctx.consumables.hasAntifire()` exists in the API,
  so this can be added — say whether you use an antifire potion or an
  anti-dragon shield and it can go in the restock check.
- No prayer *activation* (Protect from Magic); the script only drinks potions to
  keep prayer points up.
- No PKer detection — fine if west dragons are non-Wilderness on your server.

## Building

```
javac -cp libs/rs.kreme.elorin-api.jar -d out src/WestDragonsScript.java
```
