import rs.kreme.ksbot.api.game.Prayer;
import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;
import rs.kreme.ksbot.api.wrappers.KSNPC;

@ScriptManifest(
        name = "Demonic Gorillas",
        author = "YourName",
        servers = {"osrs"},
        description = "Fights demonic gorillas, heals, and returns to the cave on failure",
        category = Category.COMBAT
)
public class DemonicGorillaScript extends Script {

    /*
     * NAMES
     */
    private static final String GORILLA = "Demonic Gorilla";
    private static final String FOOD = "Shark";

    /*
     * COMMANDS
     *
     * The original clicked through the slayer interface. KSBot scripts
     * drive the server through chat commands, so set these to whatever
     * your server accepts.
     */
    private static final String TELEPORT_COMMAND = "slayer";
    private static final String HOME_COMMAND = "home";

    /*
     * HEALTH / PRAYER
     */
    private static final int EAT_HEALTH = 60;
    private static final int EMERGENCY_HEALTH = 35;
    private static final int LOW_PRAYER = 30;

    /*
     * INVENTORY
     */
    private static final int MIN_FOOD_TO_CONTINUE = 3;

    /*
     * DISTANCES
     */
    private static final int GORILLA_SEARCH_RADIUS = 24;

    /*
     * COOLDOWNS
     */
    private static final long TELEPORT_COOLDOWN = 5000;
    private static final long HOME_COMMAND_COOLDOWN = 5000;
    private static final long ATTACK_COOLDOWN = 1750;

    /*
     * ANTI-STUCK
     */
    private static final long STUCK_TIMEOUT = 60000;

    private enum State {
        FIGHTING,
        RECOVERING
    }

    private State currentState = State.RECOVERING;

    /*
     * TIMERS
     */
    private long lastTeleport = 0;
    private long lastHomeCommand = 0;
    private long lastAttackAttempt = 0;
    private long lastProgressTime = 0;

    @Override
    public boolean onStart() {

        lastProgressTime = System.currentTimeMillis();

        return true;
    }

    @Override
    public int onProcess() {

        /*
         * NO LOCAL PLAYER
         *
         * The world is not loaded (logged out, loading screen).
         * Every ctx call below assumes a live player, so idle
         * instead of acting on a half-initialised world.
         */
        if (ctx.players.getLocal() == null) {
            return 1000;
        }

        long now = System.currentTimeMillis();

        /*
         * DETERMINE CURRENT STATE
         *
         * The original located a cave object by id to decide whether it
         * had arrived. Visible gorillas are the same signal and need no
         * hardcoded object id.
         */
        currentState = gorillasVisible() ? State.FIGHTING : State.RECOVERING;

        /*
         * ANTI-STUCK CHECK
         */
        if (now - lastProgressTime > STUCK_TIMEOUT) {
            currentState = State.RECOVERING;
        }

        /*
         * PRIORITY 1:
         * EMERGENCY HEALTH
         */
        if (ctx.combat.getCurrentHealth() <= EMERGENCY_HEALTH) {

            eatIfPossible();

            markProgress();

            /*
             * If emergency health remains a problem,
             * leave rather than die here.
             */
            if (ctx.combat.getCurrentHealth() <= EMERGENCY_HEALTH) {
                sendHome();
            }

            return 1000;
        }

        /*
         * PRIORITY 2:
         * STATE HANDLING
         */
        switch (currentState) {

            case FIGHTING:
                return handleFighting();

            case RECOVERING:
                return handleRecovery();

            default:
                return 1000;
        }
    }

    /*
     * GORILLA CAVE
     */
    private int handleFighting() {

        /*
         * PRIORITY 1:
         * OUT OF FOOD, OR NOWHERE TO PUT LOOT
         */
        if (foodCount() < MIN_FOOD_TO_CONTINUE || ctx.inventory.isFull()) {
            sendHome();
            return 1500;
        }

        /*
         * PRIORITY 2:
         * PRAYER
         *
         * The original restored prayer with bandages on a timer. Draining
         * to a threshold is the equivalent, and reacts to the real level.
         *
         * Note: gorillas switch attack style, so a single protection
         * prayer is not enough on its own. The original script did not
         * switch prayers either; add switching here if you need it.
         */
        ctx.prayer.enable(Prayer.Prayers.PROTECT_FROM_MELEE);

        if (ctx.prayer.getPoints() < LOW_PRAYER) {

            ctx.consumables.drinkPrayer();

            markProgress();

            return 800;
        }

        /*
         * PRIORITY 3:
         * EAT WHEN NECESSARY
         */
        if (ctx.combat.getCurrentHealth() < EAT_HEALTH) {

            eatIfPossible();

            return 800;
        }

        /*
         * PRIORITY 4:
         * STAY ON THE CURRENT TARGET
         *
         * Replaces the original's manual "same npcId, hp > 0" bookkeeping.
         */
        if (isAlreadyAttacking()) {
            return 800;
        }

        /*
         * PRIORITY 5:
         * PICK A GORILLA NOBODY ELSE IS FIGHTING
         */
        long now = System.currentTimeMillis();

        if (now - lastAttackAttempt < ATTACK_COOLDOWN) {
            return 800;
        }

        KSNPC gorilla = findAvailableGorilla();

        if (gorilla == null) {
            return 1000;
        }

        gorilla.interact("Attack");

        lastAttackAttempt = now;

        markProgress();

        return ATTACK_COOLDOWN;
    }

    /*
     * RECOVERY / TRAVEL
     */
    private int handleRecovery() {

        long now = System.currentTimeMillis();

        if (now - lastTeleport < TELEPORT_COOLDOWN) {
            return 1000;
        }

        ctx.log("Teleporting to gorillas");

        ctx.chat.sendCommand(TELEPORT_COMMAND);

        lastTeleport = now;

        markProgress();

        return 3000;
    }

    /*
     * ARE WE SOMEWHERE WITH GORILLAS
     */
    private boolean gorillasVisible() {

        var gorillas = ctx.npcs.query()
                .withName(GORILLA)
                .list();

        return gorillas != null && !gorillas.isEmpty();
    }

    /*
     * FIND THE CLOSEST GORILLA IN RANGE THAT NOBODY IS FIGHTING
     */
    private KSNPC findAvailableGorilla() {

        var gorillas = ctx.npcs.query()
                .withName(GORILLA)
                .list();

        if (gorillas == null || gorillas.isEmpty()) {
            return null;
        }

        KSNPC closestAvailable = null;
        int closestDistance = Integer.MAX_VALUE;

        for (KSNPC gorilla : gorillas) {

            if (gorilla == null) {
                continue;
            }

            int distance = ctx.pathing.distanceTo(gorilla);

            if (distance > GORILLA_SEARCH_RADIUS || distance >= closestDistance) {
                continue;
            }

            if (!isInCombat(gorilla)) {
                closestAvailable = gorilla;
                closestDistance = distance;
            }
        }

        return closestAvailable;
    }

    /*
     * CHECK IF A GORILLA IS ALREADY ENGAGED
     */
    private boolean isInCombat(KSNPC gorilla) {

        if (gorilla == null) {
            return false;
        }

        try {
            return gorilla.getInteracting() != null;

        } catch (Exception e) {
            /*
             * The NPC can despawn between the query and this read.
             * A stale wrapper is not one we can attack, and it must
             * not kill the script loop, so treat it as busy.
             */
            return true;
        }
    }

    /*
     * CHECK IF WE ARE ALREADY ATTACKING
     */
    private boolean isAlreadyAttacking() {

        var local = ctx.players.getLocal();

        if (local == null) {
            return false;
        }

        try {
            return local.getInteracting() != null;

        } catch (Exception e) {
            /*
             * Interaction target can despawn mid-read. Assume we are
             * not attacking so the script re-targets rather than stalls.
             */
            return false;
        }
    }

    /*
     * SEND HOME WITH COOLDOWN
     */
    private void sendHome() {

        long now = System.currentTimeMillis();

        if (now - lastHomeCommand < HOME_COMMAND_COOLDOWN) {
            return;
        }

        ctx.chat.sendCommand(HOME_COMMAND);

        lastHomeCommand = now;

        markProgress();
    }

    /*
     * EAT FOOD
     */
    private void eatIfPossible() {

        if (ctx.inventory.contains(FOOD)) {

            ctx.consumables.eat(FOOD);

            markProgress();
        }
    }

    /*
     * FOOD COUNT
     */
    private int foodCount() {
        return ctx.inventory.getCount(FOOD);
    }

    /*
     * RECORD MEANINGFUL PROGRESS
     */
    private void markProgress() {
        lastProgressTime = System.currentTimeMillis();
    }

    @Override
    public void onStop() {
    }
}
