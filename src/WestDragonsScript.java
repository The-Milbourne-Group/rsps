import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;
import rs.kreme.ksbot.api.wrappers.KSGroundItem;
import rs.kreme.ksbot.api.wrappers.KSNPC;
import rs.kreme.ksbot.api.wrappers.KSObject;

/**
 * West Dragons killer.
 *
 * Kill green dragons, loot only their bones, and run a restock trip whenever the
 * inventory fills up or supplies run out: home teleport -> bank booth ->
 * "Last-Preset" -> teleport back.
 *
 * Verify the four names in CONFIG against your server before running. Everything
 * else is resolved through the API rather than hardcoded, so it does not need
 * per-server tuning.
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

    private static final String BANK_BOOTH_NAME = "Bank booth";

    /** Destination in the teleport menu used to get back after restocking. */
    private static final String DRAGON_DESTINATION = "Green dragons";

    private static final int EAT_AT_HP_PERCENT = 50;
    private static final int DRINK_PRAYER_AT_PERCENT = 30;

    /** Only bones within this many tiles are collected. */
    private static final int LOOT_RADIUS = 8;

    private static final int TELEPORT_TIMEOUT_MS = 6000;
    private static final int BANK_TIMEOUT_MS = 4000;

    private enum State {
        FIGHTING, LOOTING, SEARCHING,
        TELEPORTING_HOME, OPENING_BANK, LOADING_PRESET, RETURNING
    }

    // =========================================================================
    // RUNTIME STATE
    // =========================================================================

    /** True from the moment supplies run short until we are back at the dragons. */
    private boolean restocking = false;
    private boolean presetLoaded = false;

    private int loots = 0;
    private int trips = 0;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    public boolean onStart() {
        ctx.log("=== West Dragons ===");
        ctx.log("Target: " + DRAGON_NAME + " | Loot: " + LOOT_NAME + " (exact match only)");
        ctx.log("Restock trip: home teleport -> " + BANK_BOOTH_NAME
                + " -> Last-Preset -> " + DRAGON_DESTINATION);
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
            if (presetLoaded)            return State.RETURNING;
            if (ctx.bank.isOpen())       return State.LOADING_PRESET;
            if (findBankBooth() != null) return State.OPENING_BANK;
            return State.TELEPORTING_HOME;
        }

        if (needsRestock()) {
            restocking = true;
            presetLoaded = false;
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

    private int handleTeleportHome() {
        setStatus("Teleporting home");
        if (ctx.magic.teleportHomeAndWait(TELEPORT_TIMEOUT_MS)) {
            return random(600, 1000);
        }
        return random(1000, 1600);
    }

    /**
     * Most servers put "Last-Preset" straight on the booth, which restocks in one
     * click. Where they do not, the bank is opened and the preset applied from
     * inside it instead.
     */
    private int handleOpenBank() {
        KSObject booth = findBankBooth();
        if (booth == null) {
            return random(800, 1200);
        }

        if (booth.hasAction("Last-Preset")) {
            setStatus("Booth: Last-Preset");
            if (booth.interact("Last-Preset")) {
                presetLoaded = true;
                ctx.sleep(1200, 1800);
                return random(600, 1000);
            }
        }

        setStatus("Opening bank");
        if (booth.interact("Bank")) {
            ctx.sleep(1000, 1600);
        }
        return random(600, 1000);
    }

    private int handleLoadPreset() {
        setStatus("Loading last preset");
        if (ctx.presets.lastPreset()) {
            presetLoaded = true;
            ctx.sleep(1200, 1800);
            ctx.bank.closeAndWait(BANK_TIMEOUT_MS);
            return random(600, 1000);
        }
        return random(600, 1000);
    }

    private int handleReturn() {
        setStatus("Returning to dragons");
        if (ctx.teleporter.teleportAndWait(TELEPORT_TIMEOUT_MS, DRAGON_DESTINATION)) {
            restocking = false;
            presetLoaded = false;
            ctx.log("Back at the dragons.");
            return random(800, 1400);
        }
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

    private KSObject findBankBooth() {
        return ctx.groundObjects.query().withName(BANK_BOOTH_NAME).closest();
    }

    private int random(int min, int max) {
        return min + (int) (Math.random() * (max - min));
    }
}
