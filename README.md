# West Dragons (KSBot)

Kills west dragons, loots only their bones, and restocks through a bank preset
when supplies run out. Runs indefinitely.

Start it anywhere: the first thing it does is teleport home and load the preset,
so the run always begins from a known state.

Source: [`src/WestDragonsScript.java`](src/WestDragonsScript.java)

Written against the real API (`libs/rs.kreme.elorin-api.jar`), so every call is
verified rather than guessed. Compiles clean under `javac -Xlint:all`.

## The loop

```
on start    ->  home teleport -> bank booth -> Last-Preset -> teleport out
supplies OK ->  kill dragon -> take bones -> repeat
supplies out->  home teleport -> bank booth -> Last-Preset -> teleport back
```

The script begins mid-restock rather than at the dragons, so it does not matter
where it is started or what is in the inventory. The startup teleport fires even
if it is started standing at a bank booth, so the run always begins from home
rather than from wherever the player was logged in.

After that, a restock trip starts when **any** of these is true:

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
| `DRAGON_DESTINATION` | `west dragons` | Must match the entry in your teleport menu |

Thresholds:

| Setting | Default |
|---|---|
| `EAT_AT_HP_PERCENT` | `50` |
| `DRINK_PRAYER_AT_PERCENT` | `30` |
| `LOOT_RADIUS` | `8` tiles |

## Dragonfire

Handled by wearing an **anti-dragon shield**, which comes from the bank preset.
There is no antifire potion logic, and none is needed. Make sure the shield is
part of the preset you load.

## Not implemented

- No prayer *activation* (Protect from Magic); the script only drinks potions to
  keep prayer points up.
- No PKer detection — fine if west dragons are non-Wilderness on your server.

## Building

```
javac -cp libs/rs.kreme.elorin-api.jar -d out src/WestDragonsScript.java
```
