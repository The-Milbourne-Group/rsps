import rs.kreme.ksbot.api.game.Prayer;
import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;
import rs.kreme.ksbot.api.wrappers.KSGroundItem;
import rs.kreme.ksbot.api.wrappers.KSNPC;
import rs.kreme.ksbot.api.wrappers.KSObject;

@ScriptManifest(
        name = "West Dragons",
        author = "YourName",
        servers = {"osrs"},
        description = "Fights green dragons, collects bones, banks, and recovers from failures",
        category = Category.COMBAT
)
public class WestDragonsScript extends Script {

    /*
     * REGIONS
     */
    private static final int HOME_REGION = 12342;
    private static final int WEST_DRAGONS_REGION = 11832;

    /*
     * NAMES
     */
    private static final String FOOD = "Shark";
    private static final String LOOT = "Dragon bones";
    private static final String DRAGON = "Green dragon";
    private static final String BANK_BOOTH = "Bank booth";

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
    private static final int BONE_DISTANCE = 12;
    private static final int DRAGON_SEARCH_RADIUS = 24;

    /*
     * COOLDOWNS
     */
    private static final long HOME_COMMAND_COOLDOWN = 5000;
    private static final long WEST_COMMAND_COOLDOWN = 5000;
    private static final long PRESET_COOLDOWN = 3000;
    private static final long ATTACK_COOLDOWN = 1200;
    private static final long LOOT_COOLDOWN = 800;

    /*
     * ANTI-STUCK
     */
    private static final long STUCK_TIMEOUT = 60000;

    /*
     * NAVIGATION
     */
    private static final int DRAGON_AREA_X = 2978;
    private static final int DRAGON_AREA_Y = 3612;

    private enum State {
        HOME,
        FIGHTING,
        RECOVERING
    }

    private State currentState = State.RECOVERING;

    /*
     * TIMERS
     */
    private long lastHomeCommand = 0;
    private long lastWestCommand = 0;
    private long lastPresetAttempt = 0;
    private long lastAttackAttempt = 0;
    private long lastLootAttempt = 0;
    private long lastProgressTime = 0;

    @Override
    public boolean onStart() {

        lastProgressTime = System.currentTimeMillis();

        sendHome();

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
         */
        if (ctx.pathing.inRegion(HOME_REGION)) {
            currentState = State.HOME;

        } else if (ctx.pathing.inRegion(WEST_DRAGONS_REGION)) {
            currentState = State.FIGHTING;

        } else {
            currentState = State.RECOVERING;
        }

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
             * return to the safe/home state.
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

            case HOME:
                return handleHome();

            case FIGHTING:
                return handleFighting();

            case RECOVERING:
                return handleRecovery();

            default:
                return 1000;
        }
    }

    /*
     * HOME / BANK HANDLING
     */
    private int handleHome() {

        /*
         * Loot to deposit, or food to restock:
         * both are resolved by the same preset.
         */
        if (ctx.inventory.contains(LOOT) || foodCount() < MIN_FOOD_TO_CONTINUE) {

            /*
             * preset() marks progress itself on success.
             */
            return preset() ? 2500 : 1500;
        }

        /*
         * We have supplies and no bones.
         * Travel to West Dragons.
         */
        sendWest();

        return 2000;
    }

    /*
     * DRAGON REGION
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
         * LOOT
         *
         * Inventory space is already guaranteed by PRIORITY 1.
         */
        long now = System.currentTimeMillis();

        if (now - lastLootAttempt >= LOOT_COOLDOWN) {

            KSGroundItem bones = ctx.groundItems.query()
                    .withExactName(LOOT)
                    .closest();

            if (bones != null && ctx.pathing.distanceTo(bones) <= BONE_DISTANCE) {

                lastLootAttempt = now;

                bones.interact("Take");

                markProgress();

                return 1000;
            }
        }

        /*
         * PRIORITY 5:
         * FIND AVAILABLE DRAGON (not in combat)
         */
        KSNPC dragon = findAvailableDragon();

        if (dragon == null) {

            ctx.pathing.walkPoint(
                    DRAGON_AREA_X,
                    DRAGON_AREA_Y
            );

            markProgress();

            return 1500;
        }

        /*
         * PRIORITY 6:
         * COMBAT VERIFICATION
         */
        if (now - lastAttackAttempt >= ATTACK_COOLDOWN) {

            if (isAlreadyAttacking()) {
                return 800;
            }

            if (isDragonInCombat(dragon)) {
                return 800;
            }

            dragon.interact("Attack");

            lastAttackAttempt = now;

            markProgress();

            return 1200;
        }

        return 800;
    }

    /*
     * RECOVERY / FAILURE HANDLING
     */
    private int handleRecovery() {

        sendHome();

        return 3000;
    }

    /*
     * FIND THE CLOSEST DRAGON IN RANGE THAT NOBODY IS FIGHTING
     */
    private KSNPC findAvailableDragon() {

        var dragons = ctx.npcs.query()
                .withName(DRAGON)
                .list();

        if (dragons == null || dragons.isEmpty()) {
            return null;
        }

        KSNPC closestAvailable = null;
        int closestDistance = Integer.MAX_VALUE;

        for (KSNPC dragon : dragons) {

            if (dragon == null) {
                continue;
            }

            int distance = ctx.pathing.distanceTo(dragon);

            if (distance > DRAGON_SEARCH_RADIUS || distance >= closestDistance) {
                continue;
            }

            if (!isDragonInCombat(dragon)) {
                closestAvailable = dragon;
                closestDistance = distance;
            }
        }

        return closestAvailable;
    }

    /*
     * CHECK IF DRAGON IS IN COMBAT
     */
    private boolean isDragonInCombat(KSNPC dragon) {

        if (dragon == null) {
            return false;
        }

        try {
            return dragon.getInteracting() != null;

        } catch (Exception e) {
            /*
             * The NPC can despawn between the query and this read.
             * A stale wrapper is not a dragon we can attack, and it
             * must not kill the script loop, so treat it as busy.
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
     * LAST PRESET
     */
    private boolean preset() {

        long now = System.currentTimeMillis();

        if (now - lastPresetAttempt < PRESET_COOLDOWN) {
            return false;
        }

        KSObject bank = ctx.groundObjects.query()
                .withName(BANK_BOOTH)
                .closest();

        if (bank == null) {
            return false;
        }

        lastPresetAttempt = now;

        if (bank.interact("Last-preset")) {
            ctx.log("Preset loaded successfully");
            markProgress();
            return true;
        }

        ctx.log("Preset failed to load, will retry");
        return false;
    }

    /*
     * SEND HOME WITH COOLDOWN
     */
    private void sendHome() {

        long now = System.currentTimeMillis();

        if (now - lastHomeCommand < HOME_COMMAND_COOLDOWN) {
            return;
        }

        ctx.chat.sendCommand("home");

        lastHomeCommand = now;

        markProgress();
    }

    /*
     * SEND TO WEST DRAGONS WITH COOLDOWN
     */
    private void sendWest() {

        long now = System.currentTimeMillis();

        if (now - lastWestCommand < WEST_COMMAND_COOLDOWN) {
            return;
        }

        ctx.chat.sendCommand("wests");

        lastWestCommand = now;

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
