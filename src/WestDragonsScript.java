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

    private static final String BANK_BOOTH_NAME = "Bank booth";

    /**
     * Chat command used to get home. Sent verbatim, so it carries its own "::"
     * prefix. Whether ctx.chat.sendCommand() adds the prefix itself could not be
     * verified against the obfuscated API: if the client ends up sending
     * "::::home", drop the prefix here and leave just "home".
     */
    private static final String HOME_COMMAND = "::home";

    /**
     * Destination in the teleport menu used to get back after restocking. The
     * API's name matching is obfuscated, so whether it is case-sensitive could
     * not be verified: if the return teleport never fires, try "west dragons".
     */
    private static final String DRAGON_DESTINATION = "West dragons";

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

    /**
     * True from the moment supplies run short until we are back at the dragons.
     * Starts true so the first thing the script does is a full restock trip.
     */
    private boolean restocking = true;
    private boolean presetLoaded = false;

    /**
     * Forces the startup trip to begin with the home teleport even if the script
     * was started standing at a bank booth, so the run always begins from a known
     * location rather than wherever the player happened to be logged in.
     */
    private boolean startupTeleportPending = true;

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
            if (startupTeleportPending)  return State.TELEPORTING_HOME;
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

    /**
     * The chat command is fire-and-forget - sendCommand() returns void, so unlike
     * the old magic-tab teleport there is no return value saying whether it
     * landed. Arrival is confirmed by waiting for the bank booth to come into
     * range, which is the thing the trip actually needs. Failing that wait is not
     * an error: the state machine simply routes back here and sends it again.
     */
    private int handleTeleportHome() {
        setStatus("Teleporting home (" + HOME_COMMAND + ")");
        ctx.chat.sendCommand(HOME_COMMAND);

        if (ctx.sleepUntil(() -> findBankBooth() != null, TELEPORT_TIMEOUT_MS)) {
            startupTeleportPending = false;
            return random(600, 1000);
        }

        ctx.log("No bank booth after " + HOME_COMMAND + " - retrying.");
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
