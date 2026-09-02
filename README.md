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
on start    ->  ::home -> bank -> Last-Preset -> ::wests
supplies OK ->  kill dragon -> take bones -> repeat
supplies out->  ::home -> bank -> Last-Preset -> ::wests
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

1. `ctx.chat.sendCommand("::home")` — home, as a typed chat command.
2. `ctx.bank.openAndWait(...)` — locates the booth, **walks to it**, opens it.
3. `ctx.presets.openAndWait(...)` — the presets panel is a separate interface
   from the bank and has to be opened before anything can be clicked in it.
4. `ctx.presets.lastPreset()` — Last-Preset.
5. `ctx.chat.sendCommand("::wests")` — back to the dragons.

Steps 2 and 3 were previously done by hand: the booth was located with an object
query and clicked directly, and `lastPreset()` was called without opening the
panel. That only worked if the booth happened to already be in reach — which
after a home teleport it generally is not — and the preset click had nothing to
land on. Both now go through the API calls built for the job.

The trip is tracked with a flag rather than inferred from the screen, because
"at home with a full inventory" and "at home having just restocked" look
identical to a screen test.

`sendCommand()` returns `void`, so neither teleport has a success value to
check. The return trip watches for a dragon to come into view. The home trip
watches for the player's position to change, but does **not** treat that failing
as an error — `::home` is a no-op when you are already home. Reaching the bank is
what actually matters, so step 2 is the real gate: if no bank can be reached,
the trip resets and sends `::home` again.

## Configuration

Only four names need checking against your server:

| Setting | Default | Matching |
|---|---|---|
| `DRAGON_NAME` | `Green dragon` | **Wildcard** — `"Dragon"` would also catch blues and reds |
| `LOOT_NAME` | `Dragon bones` | **Exact** — nothing else on the ground is ever taken |
| `BANK_BOOTH_NAME` | `Bank booth` | Registered via `ctx.bank.addCustomBankObject(...)` so the API's bank finder can walk to it |
| `HOME_COMMAND` | `::home` | Sent verbatim, prefix included |
| `DRAGON_COMMAND` | `::wests` | Sent verbatim, prefix included |

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
