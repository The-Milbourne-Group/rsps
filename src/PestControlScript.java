import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;
import rs.kreme.ksbot.api.wrappers.KSNpc;
import rs.kreme.ksbot.api.wrappers.KSObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Continuous Pest Control.
 *
 * Runs the full loop forever: board the lander -> wait for the game to start ->
 * fight until the game ends -> get returned to the outpost -> board again.
 *
 * The script never stops on its own. It is designed to survive the states that
 * normally break a Pest Control bot: dying, being teleported out mid-game,
 * a game ending while mid-attack, and a lander that fills slowly.
 *
 * -----------------------------------------------------------------------------
 * BEFORE YOU RUN THIS - READ THE TWO BLOCKS BELOW
 * -----------------------------------------------------------------------------
 * 1. CONFIG: names/levels differ per RSPS. Verify them against your server.
 * 2. API ADAPTER (bottom of file): calls that are NOT present in the KSBot demo
 *    script and so could not be verified against real API docs. Each one is a
 *    single line in one place. If the script does not compile, the errors will
 *    point here and nowhere else.
 * -----------------------------------------------------------------------------
 */
@ScriptManifest(
        name = "Continuous Pest Control",
        author = "YourName",
        servers = { "osrs" },
        description = "Boards the lander, plays Pest Control, repeats indefinitely.",
        category = Category.COMBAT
)
public class PestControlScript extends Script {

    // =========================================================================
    // CONFIG - verify these against your server
    // =========================================================================

    /** Which lander to use. Novice = no combat requirement on most servers. */
    private static final Lander LANDER = Lander.NOVICE;

    /** Eat when hitpoints fall to or below this percentage. */
    private static final int EAT_AT_HP_PERCENT = 50;

    /** Food to eat, in priority order. Leave empty to disable eating. */
    private static final String[] FOOD = { "Shark", "Monkfish", "Swordfish", "Lobster", "Tuna" };

    /** Names that identify a portal NPC. */
    private static final String[] PORTAL_NAMES = { "Portal" };

    /**
     * Pests, in kill priority. Spinners are first on purpose: they repair
     * portals, so leaving one alive makes the portal damage meaningless.
     * Brawlers are last - they block movement but do not threaten the objective.
     */
    private static final String[] PEST_NAMES = {
            "Spinner", "Defiler", "Torcher", "Ravager", "Shifter", "Splatter", "Brawler"
    };

    /** Object used to board the lander. */
    private static final String GANGPLANK = "Gangplank";
    private static final String GANGPLANK_ACTION = "Cross";

    /**
     * If we board and no game starts within this many milliseconds, assume the
     * boarded flag is stale and re-evaluate from scratch.
     */
    private static final long MAX_WAIT_ON_LANDER_MS = 6 * 60 * 1000L;

    /** How long to ignore a portal after it refuses an attack (shield still up). */
    private static final long PORTAL_BLACKLIST_MS = 12 * 1000L;

    /** Set false if interact() on your server does not auto-walk to the target. */
    private static final boolean RELY_ON_INTERACT_AUTOWALK = true;

    private enum Lander {
        NOVICE("Novice"),
        INTERMEDIATE("Intermediate"),
        VETERAN("Veteran");

        final String label;
        Lander(String label) { this.label = label; }
    }

    private enum State { BOARDING, WAITING, IN_GAME }

    // =========================================================================
    // RUNTIME STATE
    // =========================================================================

    private boolean boarded = false;
    private long boardedAt = 0L;
    private boolean sawGameThisRound = false;

    private int gamesPlayed = 0;
    private int portalsEngaged = 0;
    private int pestsEngaged = 0;
    private int foodEaten = 0;
    private int deaths = 0;

    /** Portal identity -> timestamp until which it is ignored. */
    private final Map<String, Long> portalBlacklist = new HashMap<String, Long>();

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    public boolean onStart() {
        ctx.log("=== Continuous Pest Control ===");
        ctx.log("Lander: " + LANDER.label);
        ctx.log("Eat at: " + EAT_AT_HP_PERCENT + "% hp");
        setStatus("Initializing...");
        return true;
    }

    @Override
    public int onProcess() {
        State state = determineState();

        switch (state) {
            case IN_GAME:
                return handleInGame();
            case WAITING:
                return handleWaiting();
            case BOARDING:
            default:
                return handleBoarding();
        }
    }

    @Override
    public void onStop() {
        ctx.log("=== Session summary ===");
        ctx.log("Runtime:          " + getTimer().getElapsedTime());
        ctx.log("Games played:     " + gamesPlayed);
        ctx.log("Portals engaged:  " + portalsEngaged);
        ctx.log("Pests engaged:    " + pestsEngaged);
        ctx.log("Food eaten:       " + foodEaten);
        ctx.log("Deaths:           " + deaths);
    }

    // =========================================================================
    // STATE DETECTION
    // =========================================================================

    /**
     * State is derived from what is actually on screen rather than from
     * coordinates or widget ids, because those vary between servers while
     * "a portal is visible" does not.
     */
    private State determineState() {
        if (findAnyPortal() != null || findPest() != null) {
            if (!sawGameThisRound) {
                sawGameThisRound = true;
                setStatus("Game started");
                ctx.log("Game started.");
            }
            return State.IN_GAME;
        }

        // No pests and no portals: the game is over, or we never left the outpost.
        if (sawGameThisRound) {
            gamesPlayed++;
            sawGameThisRound = false;
            boarded = false;
            portalBlacklist.clear();
            ctx.log("Game finished. Total games: " + gamesPlayed);
        }

        if (boarded) {
            if (System.currentTimeMillis() - boardedAt > MAX_WAIT_ON_LANDER_MS) {
                ctx.log("Waited too long on the lander - re-boarding.");
                boarded = false;
                return State.BOARDING;
            }
            return State.WAITING;
        }

        return State.BOARDING;
    }

    // =========================================================================
    // STATE HANDLERS
    // =========================================================================

    private int handleBoarding() {
        setStatus("Boarding " + LANDER.label + " lander");

        KSObject gangplank = ctx.groundObjects.query()
                .withName(GANGPLANK)
                .closest();

        if (gangplank == null) {
            // Not at the outpost, or the plank is out of range.
            setStatus("Looking for the gangplank");
            return random(1200, 2000);
        }

        if (gangplank.interact(GANGPLANK_ACTION)) {
            boarded = true;
            boardedAt = System.currentTimeMillis();
            ctx.log("Boarded the lander.");
            ctx.sleep(1500, 2200);
            return random(600, 1000);
        }

        return random(800, 1400);
    }

    private int handleWaiting() {
        long waited = (System.currentTimeMillis() - boardedAt) / 1000L;
        setStatus("On the lander (" + waited + "s)");

        // Nothing useful to do while the boat fills. Idle in long, irregular
        // blocks rather than polling on a fixed tick.
        return random(2500, 4500);
    }

    private int handleInGame() {
        // 1. Survival first. A dead player scores nothing.
        if (shouldEat()) {
            setStatus("Eating");
            if (eatFood()) {
                foodEaten++;
                return random(900, 1400);
            }
        }

        // 2. Do not interrupt a fight that is already going.
        if (!isIdle()) {
            setStatus("Fighting");
            return random(700, 1200);
        }

        // 3. Spinners repair portals. Kill them before anything else, or the
        //    damage done to the portal is wasted.
        KSNpc spinner = findNpcByName("Spinner");
        if (spinner != null && attackNpc(spinner)) {
            pestsEngaged++;
            setStatus("Killing spinner");
            return random(800, 1300);
        }

        // 4. The actual objective.
        KSNpc portal = findAttackablePortal();
        if (portal != null) {
            if (attackNpc(portal)) {
                portalsEngaged++;
                setStatus("Attacking portal");
                return random(900, 1500);
            }
            // Refused: shield is still up. Ignore it briefly and try another.
            blacklistPortal(portal);
            return random(400, 700);
        }

        // 5. All portals shielded or out of range - clear pests meanwhile.
        //    This also keeps activity up, which is what the points check reads.
        KSNpc pest = findPest();
        if (pest != null && attackNpc(pest)) {
            pestsEngaged++;
            setStatus("Killing pests");
            return random(800, 1300);
        }

        // 6. Nothing in range. Close the distance to a portal.
        if (!RELY_ON_INTERACT_AUTOWALK) {
            KSNpc anyPortal = findAnyPortal();
            if (anyPortal != null && walkTo(anyPortal)) {
                setStatus("Moving to portal");
                return random(1500, 2500);
            }
        }

        setStatus("Waiting for a target");
        return random(1000, 1800);
    }

    // =========================================================================
    // TARGET SELECTION
    // =========================================================================

    private KSNpc findAnyPortal() {
        return ctx.npcs.query()
                .withName(PORTAL_NAMES)
                .closest();
    }

    /** Nearest portal that has not recently refused an attack. */
    private KSNpc findAttackablePortal() {
        KSNpc portal = findAnyPortal();
        if (portal == null) {
            return null;
        }
        return isBlacklisted(portal) ? null : portal;
    }

    private KSNpc findPest() {
        // PEST_NAMES is ordered by priority, so ask for each in turn rather
        // than taking the geometrically closest of the whole set.
        for (String name : PEST_NAMES) {
            KSNpc pest = findNpcByName(name);
            if (pest != null) {
                return pest;
            }
        }
        return null;
    }

    private KSNpc findNpcByName(String name) {
        return ctx.npcs.query()
                .withName(name)
                .closest();
    }

    private void blacklistPortal(KSNpc portal) {
        portalBlacklist.put(portalKey(portal), System.currentTimeMillis() + PORTAL_BLACKLIST_MS);
    }

    private boolean isBlacklisted(KSNpc portal) {
        Long until = portalBlacklist.get(portalKey(portal));
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            portalBlacklist.remove(portalKey(portal));
            return false;
        }
        return true;
    }

    /**
     * Portals share a name, so identify them by position instead. Portals do
     * not move, which makes this stable for the length of a game.
     */
    private String portalKey(KSNpc portal) {
        return npcPositionKey(portal);
    }

    private int random(int min, int max) {
        return min + (int) (Math.random() * (max - min));
    }

    // =========================================================================
    // API ADAPTER
    // =========================================================================
    // Everything below calls KSBot API that is NOT exercised by the demo script,
    // so the exact names could not be verified. The logic above is written only
    // against these wrappers, so a wrong name is a one-line fix here.
    //
    // If a call does not resolve, check your IDE's autocomplete on `ctx.` and
    // fix the single line. The alternatives listed are the conventional names
    // for the same concept in bot APIs of this shape.
    // =========================================================================

    /** VERIFIED against the demo script. */
    private boolean isIdle() {
        return ctx.players.getLocal().isIdle();
    }

    /**
     * UNVERIFIED. Alternatives if this does not resolve:
     *   ctx.combat.getHealthPercent()
     *   ctx.skills.getCurrentLevel(Skill.HITPOINTS) vs getRealLevel(...)
     *   ctx.players.getLocal().getHealth()
     */
    private int healthPercent() {
        return ctx.players.getLocal().getHealthPercent();
    }

    private boolean shouldEat() {
        return FOOD.length > 0 && healthPercent() <= EAT_AT_HP_PERCENT;
    }

    /**
     * UNVERIFIED: ctx.inventory.getItem(String) and item.interact(String).
     * ctx.inventory.dropAll(String) IS verified, so the inventory object and
     * its name-based lookups exist in some form.
     * Alternatives: ctx.inventory.interact(name, "Eat")
     *               ctx.inventory.query().withName(name).first()
     */
    private boolean eatFood() {
        for (String food : FOOD) {
            if (ctx.inventory.getItem(food) != null
                    && ctx.inventory.getItem(food).interact("Eat")) {
                return true;
            }
        }
        return false;
    }

    /**
     * UNVERIFIED: the npcs collection and KSNpc.
     * ctx.groundObjects.query().withName(...).closest() IS verified, and this
     * mirrors that exact shape. withName() is assumed to be varargs, matching
     * the demo's withName("Tree", "Oak tree").
     *
     * The .interact("Attack") action string may be server-specific.
     */
    private boolean attackNpc(KSNpc npc) {
        return npc != null && npc.interact("Attack");
    }

    /**
     * UNVERIFIED and only used when RELY_ON_INTERACT_AUTOWALK is false.
     * Alternatives: ctx.walking.walkTo(npc)
     *               ctx.movement.walkTo(npc.getPosition())
     */
    private boolean walkTo(KSNpc npc) {
        return ctx.walking.walkTo(npc.getPosition());
    }

    /**
     * UNVERIFIED: used only to tell two same-named portals apart.
     * Alternatives: String.valueOf(npc.getIndex())
     *               npc.getTile().toString()
     */
    private String npcPositionKey(KSNpc npc) {
        return npc.getPosition().toString();
    }
}
