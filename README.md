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

# West Dragons Slayer (KSBot)

A KSBot script that kills dragons at West Dragons indefinitely: find and attack
dragons, loot valuable drops, eat when needed, bank when full, return to dragons.

Source: [`src/WestDragonsScript.java`](src/WestDragonsScript.java)

## Overview

The script runs an infinite combat loop at West Dragons. It detects game state
from what is visible on screen (dragons present, ground loot visible) rather than
from coordinates or widget IDs, which vary between private servers.

### State Machine

```
Dragon visible?        -> SLAYING
else, ground loot?     -> LOOTING
else, health critical? -> EATING
else, inventory full?  -> BANKING
else                   -> WALKING_TO_DRAGONS
```

In the SLAYING state, the script prioritizes:
1. Attack if idle
2. Blacklist and try another if the dragon refuses (spell/projectile blocked)
3. Idle while in combat

### Loot Priority

High-value drops (Dragon scales, bones, dragonstone) are looted automatically by
name. Configure `LOOT_PRIORITY` to change what gets picked up, or leave it empty
to loot everything on the ground.

### Configuration

All at the top of `WestDragonsScript.java`:

| Setting | Default | Notes |
|---|---|---|
| `DRAGON_NAMES` | `Dragon` | Change if your server names them differently |
| `EAT_AT_HP_PERCENT` | `40` | Set food to an empty array to disable eating |
| `FOOD` | Shark → Tuna | Priority order |
| `LOOT_PRIORITY` | Scales, bones, ore | Leave empty to loot everything |
| `BANK_AT_INVENTORY_PERCENT` | `85` | Return to bank when inventory hits this % full |
| `MAX_PURSUIT_DISTANCE` | `50` | Tiles to chase a dragon before giving up |
| `RELY_ON_INTERACT_AUTOWALK` | `true` | Set false if `interact()` does not walk to out-of-range targets |

## Not Implemented

- Specific dragon selection (waterfall vs. brutal vs. blue) — the script finds and
  attacks any dragon named "Dragon"
- Banking location — currently stubbed; implement based on your server's bank
- Walking path back to dragons — currently stubbed; customize for your server
- Prayer or special attack usage
- Multi-stage loot routes (only banks at full inventory)
- Avoiding PKers in wilderness (if applicable to your server)

## API Notes

The script is written in two layers:

- **Game logic** (lines 1-270) — uses only the wrapper methods defined at the
  bottom. This part is API-independent.
- **API adapter** (lines 280-350) — every call isolated, with alternatives
  documented where the API could vary.

The Elorin API (rs.kreme.elorin-api.jar) is a RuneLite-based abstraction, so
methods are stable. If it does not compile, errors will land in the adapter block.

