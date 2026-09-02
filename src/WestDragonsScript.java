import rs.kreme.ksbot.api.script.Script;
import rs.kreme.ksbot.api.script.ScriptManifest;
import rs.kreme.ksbot.api.script.ScriptManifest.Category;
import rs.kreme.ksbot.api.wrappers.KSGroundItem;
import rs.kreme.ksbot.api.wrappers.KSNPC;

import net.runelite.api.coords.WorldPoint;

/**
 * West Dragons killer.
 *
 * Kill green dragons, loot only their bones, and run a restock trip whenever the
 * inventory fills up or supplies run out: "::home" -> bank booth ->
 * "Last-Preset" -> teleport back.
 *
 * The script starts by running that same trip, so it can be started from
 * anywhere and with anything in the inventory: it sends "::home", loads the
 * preset, and only then travels out to the dragons.
 *
 * Dragonfire is handled by wearing an anti-dragon shield, so there is no
 * antifire potion handling here. The shield comes from the bank preset.
 */
@ScriptManifest(
        name = "West Dragons",
        author = "YourName",
        servers = { "osrs" },
        description = "Kills west dragons, loots bones, restocks via bank preset.",
        category = Category.COMBAT
)
public class WestDragonsScript extends Script {

    // =========================================================================
    // CONFIG - verify these four names against your server
    // =========================================================================

    /** Matched as a wildcard, so "Dragon" would also catch blues and reds. */
    private static final String DRAGON_NAME = "Green dragon";

    /** Matched exactly, so nothing else on the ground is ever picked up. */
    private static final String LOOT_NAME = "Dragon bones";

    /**
     * Registered with ctx.bank as a custom bank object, so the API's own bank
     * finder recognises this server's booth and can walk to it.
     */
    private static final String BANK_BOOTH_NAME = "Bank booth";

    /**
     * Chat command used to get home. Sent verbatim, so it carries its own "::"
     * prefix. Whether ctx.chat.sendCommand() adds the prefix itself could not be
     * verified against the obfuscated API: if the client ends up sending
     * "::::home", drop the prefix here and leave just "home".
     */
    private static final String HOME_COMMAND = "::home";

    /** Chat command that takes us back out to the dragons after restocking. */
    private static final String DRAGON_COMMAND = "::wests";

    private static final int EAT_AT_HP_PERCENT = 50;
    private static final int DRINK_PRAYER_AT_PERCENT = 30;

    /** Only bones within this many tiles are collected. */
    private static final int LOOT_RADIUS = 8;

    private static final int TELEPORT_TIMEOUT_MS = 6000;
    private static final int BANK_TIMEOUT_MS = 4000;

    /** Generous, because openAndWait() includes walking to the booth. */
    private static final int BANK_OPEN_TIMEOUT_MS = 12000;
    private static final int PRESET_TIMEOUT_MS = 4000;

    private enum State {
        FIGHTING, LOOTING, SEARCHING,
        TELEPORTING_HOME, OPENING_BANK, LOADING_PRESET, RETURNING
    }

    // =========================================================================
    // RUNTIME STATE
    // =========================================================================

    /**
     * True from the moment supplies run short until we are back at the dragons.
     * Starts true so the first thing the script does is a full restock trip.
     */
    private boolean restocking = true;
    private boolean presetLoaded = false;

    /**
     * Cleared at the start of every trip, so the trip always begins by sending
     * the home command - including the startup trip, which is why it starts
     * false. Reset if the bank turns out to be unreachable.
     */
    private boolean teleportedHome = false;

    private int loots = 0;
    private int trips = 0;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    public boolean onStart() {
        ctx.log("=== West Dragons ===");
        ctx.log("Target: " + DRAGON_NAME + " | Loot: " + LOOT_NAME + " (exact match only)");
        ctx.log("Restock trip: " + HOME_COMMAND + " -> " + BANK_BOOTH_NAME
                + " -> Last-Preset -> " + DRAGON_COMMAND);

        // Teaches the API's bank finder about this server's booth, so
        // ctx.bank.openAndWait() can locate and walk to it.
        ctx.bank.addCustomBankObject(BANK_BOOTH_NAME);
        ctx.log("Starting with a restock trip.");
        trips++;
        setStatus("Starting");
        return true;
    }

    @Override
    public int onProcess() {
        // Survival runs in every state, including mid-trip. Both calls no-op when
        // the threshold has not been reached.
        if (ctx.consumables.eatAtPercent(EAT_AT_HP_PERCENT)) {
            setStatus("Eating");
            return random(600, 1000);
        }
        if (ctx.prayer.getPercentLeft() <= DRINK_PRAYER_AT_PERCENT
                && ctx.consumables.drinkPrayer()) {
            setStatus("Drinking prayer");
            return random(600, 1000);
        }

        switch (determineState()) {
            case FIGHTING:          return handleFighting();
            case LOOTING:           return handleLooting();
            case TELEPORTING_HOME:  return handleTeleportHome();
            case OPENING_BANK:      return handleOpenBank();
            case LOADING_PRESET:    return handleLoadPreset();
            case RETURNING:         return handleReturn();
            case SEARCHING:
            default:                return handleSearching();
        }
    }

    @Override
    public void onStop() {
        ctx.log("=== Session summary ===");
        ctx.log("Runtime:        " + getTimer().getElapsedTime());
        ctx.log("Bones looted:   " + loots);
        ctx.log("Restock trips:  " + trips);
    }

    // =========================================================================
    // STATE DETECTION
    // =========================================================================

    /**
     * A restock trip is a flag rather than a screen test: "standing at home with a
     * full inventory" and "standing at home having just restocked" look identical,
     * so the trip has to remember which half of it we are in.
     */
    private State determineState() {
        if (restocking) {
            if (!teleportedHome) return State.TELEPORTING_HOME;
            if (presetLoaded)    return State.RETURNING;
            if (ctx.bank.isOpen() || ctx.presets.isOpen()) {
                return State.LOADING_PRESET;
            }
            return State.OPENING_BANK;
        }

        if (needsRestock()) {
            restocking = true;
            presetLoaded = false;
            teleportedHome = false;
            trips++;
            ctx.log("Restocking: " + restockReason());
            return State.TELEPORTING_HOME;
        }

        if (findLoot() != null)   return State.LOOTING;
        if (findDragon() != null) return State.FIGHTING;
        return State.SEARCHING;
    }

    /** Any one of these ends the trip at the dragons. */
    private boolean needsRestock() {
        return ctx.inventory.isFull()
                || !ctx.consumables.hasFood()
                || !ctx.consumables.hasPrayer();
    }

    private String restockReason() {
        if (ctx.inventory.isFull())        return "inventory full";
        if (!ctx.consumables.hasFood())    return "out of food";
        return "out of prayer potions";
    }

    // =========================================================================
    // AT THE DRAGONS
    // =========================================================================

    private int handleFighting() {
        if (!ctx.players.getLocal().isIdle()) {
            setStatus("Fighting");
            return random(700, 1200);
        }

        KSNPC dragon = findDragon();
        if (dragon != null && dragon.interact("Attack")) {
            setStatus("Attacking");
            return random(800, 1300);
        }
        return random(500, 900);
    }

    /**
     * Bones are taken before the next dragon is engaged, so a full kill's drop is
     * never left behind while a new fight starts.
     */
    private int handleLooting() {
        KSGroundItem bones = findLoot();
        if (bones != null && bones.interact("Take")) {
            loots++;
            setStatus("Looting bones");
            return random(600, 1000);
        }
        return random(400, 800);
    }

    private int handleSearching() {
        setStatus("Looking for dragons");
        return random(1000, 1800);
    }

    // =========================================================================
    // RESTOCK TRIP
    // =========================================================================

    /**
     * sendCommand() returns void, so arrival is watched for rather than returned:
     * the wait is for the player's position to change. That wait failing is not
     * treated as failure, because the command is a no-op when we are already at
     * home. Reaching the bank is what actually matters, and openAndWait() below
     * is the real gate - it resets this flag if it cannot get there.
     */
    private int handleTeleportHome() {
        setStatus("Teleporting home (" + HOME_COMMAND + ")");

        WorldPoint before = ctx.players.getLocal().getWorldLocation();
        ctx.chat.sendCommand(HOME_COMMAND);
        ctx.sleepUntil(
                () -> !ctx.players.getLocal().getWorldLocation().equals(before),
                TELEPORT_TIMEOUT_MS);

        teleportedHome = true;
        return random(600, 1000);
    }

    /**
     * openAndWait() locates the booth, walks to it and opens it. Doing this by
     * hand - querying for the object and clicking it - was the original bug: it
     * only worked when the booth happened to already be in reach, and after a
     * home teleport it usually is not.
     */
    private int handleOpenBank() {
        setStatus("Opening bank");
        if (ctx.bank.openAndWait(BANK_OPEN_TIMEOUT_MS)) {
            return random(400, 800);
        }

        // Could not get to a bank, so we are probably not where we think we are.
        ctx.log("No bank reachable - re-sending " + HOME_COMMAND + ".");
        teleportedHome = false;
        return random(1000, 1600);
    }

    /**
     * The presets panel is a separate interface from the bank, with its own
     * open/isOpen pair. Calling lastPreset() without opening it was the second
     * half of the banking bug - the click had nothing to land on.
     */
    private int handleLoadPreset() {
        if (!ctx.presets.isOpen()) {
            setStatus("Opening presets");
            if (!ctx.presets.openAndWait(PRESET_TIMEOUT_MS)) {
                return random(600, 1000);
            }
        }

        setStatus("Loading last preset");
        if (ctx.presets.lastPreset()) {
            presetLoaded = true;
            ctx.sleep(1200, 1800);
            ctx.bank.closeAndWait(BANK_TIMEOUT_MS);
            return random(600, 1000);
        }
        return random(600, 1000);
    }

    /** Arrival is confirmed by a dragon coming into view rather than a return value. */
    private int handleReturn() {
        setStatus("Returning to dragons (" + DRAGON_COMMAND + ")");
        ctx.chat.sendCommand(DRAGON_COMMAND);

        if (ctx.sleepUntil(() -> findDragon() != null, TELEPORT_TIMEOUT_MS)) {
            restocking = false;
            presetLoaded = false;
            teleportedHome = false;
            ctx.log("Back at the dragons.");
            return random(800, 1400);
        }

        ctx.log("No dragons after " + DRAGON_COMMAND + " - retrying.");
        return random(1000, 1600);
    }

    // =========================================================================
    // LOOKUPS
    // =========================================================================

    private KSNPC findDragon() {
        return ctx.npcs.query().withName(DRAGON_NAME).alive().closest();
    }

    /** Exact name match, so only dragon bones are ever taken. */
    private KSGroundItem findLoot() {
        KSGroundItem bones = ctx.groundItems.query().withExactName(LOOT_NAME).closest();
        if (bones == null || ctx.inventory.isFull()) {
            return null;
        }
        return bones.distanceTo(ctx.players.getLocal()) <= LOOT_RADIUS ? bones : null;
    }

    private int random(int min, int max) {
        return min + (int) (Math.random() * (max - min));
    }
}
