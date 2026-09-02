import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;
import rs.kreme.ksbot.api.wrappers.KSNPC;
import rs.kreme.ksbot.api.wrappers.KSGroundItem;
import rs.kreme.ksbot.api.game.magic.SpellBook;

import java.util.HashMap;
import java.util.Map;

/**
 * West Dragons Slaying Script.
 *
 * Runs an infinite loop: find dragons -> attack -> loot dragon bones -> eat if needed
 * -> when inventory full: teleport via spell -> load preset -> return to dragons.
 */
@ScriptManifest(
        name = "West Dragons Slayer",
        author = "YourName",
        servers = { "osrs" },
        description = "Kills dragons at West Dragons, loots bones, teleports and loads preset.",
        category = Category.COMBAT
)
public class WestDragonsScript extends Script {

    // =========================================================================
    // CONFIG - verify these against your server
    // =========================================================================

    private static final String[] DRAGON_NAMES = { "Dragon" };
    private static final int EAT_AT_HP_PERCENT = 40;
    private static final String[] FOOD = { "Shark", "Monkfish", "Swordfish", "Lobster", "Tuna" };

    /** Only loot dragon bones. */
    private static final String[] LOOT_PRIORITY = { "Dragon bones" };

    /** Return to bank if inventory is this full. */
    private static final int BANK_AT_INVENTORY_PERCENT = 85;

    /** Teleport spell to cast. Use SpellBook.Standard enum names (e.g., "ORION_HOME_TELEPORT") */
    private static final String TELEPORT_SPELL = "ORION_HOME_TELEPORT";

    /** Preset name to load after teleporting. */
    private static final String BANK_PRESET = "West Dragons";

    /** How long to ignore a dragon after it refuses an attack. */
    private static final long DRAGON_BLACKLIST_MS = 8 * 1000L;

    private enum State { SLAYING, LOOTING, EATING, TELEPORTING, BANKING, WALKING_TO_DRAGONS }

    // =========================================================================
    // RUNTIME STATE
    // =========================================================================

    private int dragonsKilled = 0;
    private int itemsLooted = 0;
    private int foodEaten = 0;
    private int bankTrips = 0;
    private final Map<String, Long> dragonBlacklist = new HashMap<>();

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    public boolean onStart() {
        ctx.log("=== West Dragons Slayer ===");
        ctx.log("Teleport spell: " + TELEPORT_SPELL);
        ctx.log("Banking preset: " + BANK_PRESET);
        setStatus("Initializing...");
        return true;
    }

    @Override
    public int onProcess() {
        State state = determineState();
        switch (state) {
            case SLAYING: return handleSlaying();
            case LOOTING: return handleLooting();
            case EATING: return handleEating();
            case TELEPORTING: return handleTeleporting();
            case BANKING: return handleBanking();
            case WALKING_TO_DRAGONS: return handleWalkingToDragons();
            default: return random(1000, 1500);
        }
    }

    @Override
    public void onStop() {
        ctx.log("=== Session summary ===");
        ctx.log("Runtime:          " + getTimer().getElapsedTime());
        ctx.log("Dragons killed:   " + dragonsKilled);
        ctx.log("Items looted:     " + itemsLooted);
        ctx.log("Food eaten:       " + foodEaten);
        ctx.log("Bank trips:       " + bankTrips);
    }

    // =========================================================================
    // STATE DETECTION
    // =========================================================================

    private State determineState() {
        if (shouldEat()) return State.EATING;
        if (shouldBank()) return State.TELEPORTING;
        if (findLoot() != null) return State.LOOTING;
        if (findDragon() != null) return State.SLAYING;
        return State.WALKING_TO_DRAGONS;
    }

    // =========================================================================
    // STATE HANDLERS
    // =========================================================================

    private int handleSlaying() {
        KSNPC dragon = findDragon();
        if (dragon == null) {
            setStatus("Looking for dragons");
            return random(1000, 1500);
        }

        if (!isIdle()) {
            setStatus("Fighting dragon");
            return random(800, 1400);
        }

        if (attackNpc(dragon)) {
            dragonsKilled++;
            setStatus("Attacking dragon");
            return random(800, 1200);
        }

        blacklistDragon(dragon);
        return random(400, 600);
    }

    private int handleLooting() {
        KSGroundItem loot = findLoot();
        if (loot == null) {
            setStatus("No loot nearby");
            return random(800, 1200);
        }

        if (loot.interact("Take")) {
            itemsLooted++;
            setStatus("Looting: " + loot.getName());
            return random(600, 1000);
        }

        return random(500, 800);
    }

    private int handleEating() {
        setStatus("Eating");
        if (eatFood()) {
            foodEaten++;
            return random(900, 1400);
        }

        setStatus("Out of food");
        return random(2000, 3000);
    }

    private int handleTeleporting() {
        setStatus("Casting teleport");
        if (castTeleportSpell()) {
            ctx.log("Teleported. Waiting for load...");
            ctx.sleep(1500, 2500);
            return random(600, 1000);
        }

        return random(800, 1200);
    }

    private int handleBanking() {
        setStatus("Loading preset");
        bankTrips++;

        if (loadPreset(BANK_PRESET)) {
            ctx.log("Preset loaded. Returning to dragons...");
            ctx.sleep(1500, 2000);
            return random(1000, 1500);
        }

        return random(800, 1200);
    }

    private int handleWalkingToDragons() {
        setStatus("Walking to dragons");
        if (findDragon() != null) {
            return random(600, 1000);
        }

        return random(1500, 2500);
    }

    // =========================================================================
    // TARGET SELECTION
    // =========================================================================

    private KSNPC findDragon() {
        for (String name : DRAGON_NAMES) {
            KSNPC dragon = findNpcByName(name);
            if (dragon != null && !isBlacklisted(dragon)) {
                return dragon;
            }
        }
        return null;
    }

    private KSGroundItem findLoot() {
        if (LOOT_PRIORITY.length == 0) {
            return ctx.groundItems.query().closest();
        }

        for (String item : LOOT_PRIORITY) {
            KSGroundItem loot = ctx.groundItems.query()
                    .withName(item)
                    .closest();
            if (loot != null) return loot;
        }
        return null;
    }

    private KSNPC findNpcByName(String name) {
        return ctx.npcs.query().withName(name).closest();
    }

    private void blacklistDragon(KSNPC dragon) {
        dragonBlacklist.put(dragonKey(dragon), System.currentTimeMillis() + DRAGON_BLACKLIST_MS);
    }

    private boolean isBlacklisted(KSNPC dragon) {
        Long until = dragonBlacklist.get(dragonKey(dragon));
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            dragonBlacklist.remove(dragonKey(dragon));
            return false;
        }
        return true;
    }

    private String dragonKey(KSNPC dragon) {
        return dragon.getWorldLocation().toString();
    }

    private int random(int min, int max) {
        return min + (int) (Math.random() * (max - min));
    }

    // =========================================================================
    // API ADAPTER
    // =========================================================================

    private boolean isIdle() {
        return ctx.players.getLocal().isIdle();
    }

    private int healthPercent() {
        return (int) ctx.combat.getHealthPercent();
    }

    private boolean shouldEat() {
        return FOOD.length > 0 && healthPercent() <= EAT_AT_HP_PERCENT;
    }

    private boolean shouldBank() {
        return getInventoryPercent() >= BANK_AT_INVENTORY_PERCENT;
    }

    private int getInventoryPercent() {
        int count = ctx.inventory.size();
        return (count * 100) / 28;
    }

    private boolean eatFood() {
        for (String food : FOOD) {
            if (ctx.inventory.getItem(food) != null
                    && ctx.inventory.getItem(food).interact("Eat")) {
                return true;
            }
        }
        return false;
    }

    private boolean attackNpc(KSNPC npc) {
        return npc != null && npc.interact("Attack");
    }

    /** Cast the teleport spell by name. */
    private boolean castTeleportSpell() {
        try {
            SpellBook.Standard spell = SpellBook.Standard.valueOf(TELEPORT_SPELL);
            if (spell.canCast()) {
                spell.cast();
                return true;
            }
            ctx.log("Cannot cast " + TELEPORT_SPELL + " (insufficient runes or level)");
            return false;
        } catch (IllegalArgumentException e) {
            ctx.log("Unknown spell: " + TELEPORT_SPELL);
            return false;
        }
    }

    /** Load a preset by name. */
    private boolean loadPreset(String presetName) {
        return ctx.presets.loadPreset(presetName);
    }
}
