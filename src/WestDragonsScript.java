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
    private static final int DRAGON_SEARCH_RADIUS = 50;

    /*
     * COOLDOWNS
     */
    private static final long HOME_COMMAND_COOLDOWN = 5000;
    private static final long WEST_COMMAND_COOLDOWN = 5000;
    private static final long PRESET_COOLDOWN = 3000;
    private static final long ATTACK_COOLDOWN = 1200;
    private static final long LOOT_COOLDOWN = 800;
    private static final long TRADE_COOLDOWN = 2000;

    /*
     * TRADING
     */
    private static final String TRADE_PARTNER = "Market";
    private static final int BONES_TO_TRADE = 250;
    private static final int BONES_BANK_THRESHOLD = 250;

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
        LOOTING,
        RETURNING,
        RECOVERING,
        TRADING,
        WAITING_FOR_PARTNER
    }

    private State currentState = State.RECOVERING;
    private KSNPC currentTarget = null;

    /*
     * TIMERS
     */
    private long lastHomeCommand = 0;
    private long lastWestCommand = 0;
    private long lastPresetAttempt = 0;
    private long lastAttackAttempt = 0;
    private long lastLootAttempt = 0;
    private long lastProgressTime = 0;
    private long lastTradeAttempt = 0;
    private long lastBankCheck = 0;

    /*
     * TRADING STATE
     */
    private int bonesInBank = 0;
    private boolean tradeInitiated = false;

    @Override
    public boolean onStart() {

        lastProgressTime = System.currentTimeMillis();

        sendHome();

        return true;
    }

    @Override
    public int onProcess() {

        var local = ctx.players.getLocal();

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
                return handleFighting(local);

            case RECOVERING:
                return handleRecovery();

            case TRADING:
                return handleTrade();

            case WAITING_FOR_PARTNER:
                return handleWaitingForPartner();

            default:
                return 1000;
        }
    }

    /*
     * HOME / BANK HANDLING
     */
    private int handleHome() {

        long now = System.currentTimeMillis();

        /*
         * CHECK BANK FOR BONES TO TRADE
         */
        if (now - lastBankCheck >= 3000) {
            lastBankCheck = now;

            if (!ctx.bank.isOpen()) {
                ctx.bank.open();
                return 2000;
            }

            bonesInBank = ctx.bank.getCount("Dragon bones");
            ctx.log("Bones in bank: " + bonesInBank);

            if (bonesInBank >= BONES_BANK_THRESHOLD) {
                ctx.log("Enough bones for trade! Moving to trade...");
                ctx.bank.close();
                currentState = State.WAITING_FOR_PARTNER;
                tradeInitiated = false;
                return 2000;
            }
        }

        /*
         * If we have loot, bank it.
         */
        if (ctx.inventory.contains("Dragon bones")) {
            if (preset()) {
                markProgress();
                return 2500;
            }
            return 1500;
        }

        /*
         * If we are low/out of food, load the preset.
         */
        if (foodCount() < MIN_FOOD_TO_CONTINUE) {
            if (preset()) {
                markProgress();
                return 2500;
            }
            return 1500;
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
    private int handleFighting(Object localPlayer) {

        /*
         * PRIORITY 1:
         * MAKE SURE WE HAVE ENOUGH FOOD
         */
        if (foodCount() < MIN_FOOD_TO_CONTINUE) {
            sendHome();
            return 1500;
        }

        /*
         * PRIORITY 2:
         * INVENTORY FULL
         */
        if (ctx.inventory.isFull()) {
            sendHome();
            return 1500;
        }

        /*
         * PRIORITY 3:
         * PRAYER
         */
        ctx.prayer.enable(Prayer.Prayers.PROTECT_FROM_MELEE);

        if (ctx.prayer.getPoints() < LOW_PRAYER) {

            ctx.consumables.drinkPrayer();

            markProgress();

            return 800;
        }

        /*
         * PRIORITY 4:
         * EAT WHEN NECESSARY
         */
        if (ctx.combat.getCurrentHealth() < EAT_HEALTH) {

            eatIfPossible();

            return 800;
        }

        /*
         * PRIORITY 5:
         * LOOT
         */
        long now = System.currentTimeMillis();

        if (now - lastLootAttempt >= LOOT_COOLDOWN) {

            KSGroundItem bones = ctx.groundItems.query()
                    .withExactName("Dragon bones")
                    .closest();

            if (bones != null
                    && !ctx.inventory.isFull()) {

                if (ctx.pathing.distanceTo(bones) <= BONE_DISTANCE) {

                    lastLootAttempt = now;

                    bones.interact("Take");

                    markProgress();

                    return 1000;
                }
            }
        }

        /*
         * PRIORITY 6:
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
         * PRIORITY 7:
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
            currentTarget = dragon;

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
     * FIND AN AVAILABLE DRAGON WITHIN 24 TILE RADIUS
     */
    private KSNPC findAvailableDragon() {

        var dragons = ctx.npcs.query()
                .withName("Green dragon")
                .list();

        if (dragons == null || dragons.isEmpty()) {
            return null;
        }

        var local = ctx.players.getLocal();
        if (local == null) {
            return null;
        }

        KSNPC closestAvailable = null;
        int closestDistance = Integer.MAX_VALUE;

        for (KSNPC dragon : dragons) {
            if (dragon == null) {
                continue;
            }

            int distance = ctx.pathing.distanceTo(dragon);

            if (distance > 24) {
                continue;
            }

            if (!isDragonInCombat(dragon)) {
                if (distance < closestDistance) {
                    closestAvailable = dragon;
                    closestDistance = distance;
                }
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
            var interacting = dragon.getInteracting();

            if (interacting != null) {
                return true;
            }

        } catch (Exception e) {
            return false;
        }

        return false;
    }

    /*
     * CHECK IF WE ARE ALREADY ATTACKING
     */
    private boolean isAlreadyAttacking() {

        try {
            var local = ctx.players.getLocal();

            if (local == null) {
                return false;
            }

            var interacting = local.getInteracting();

            return interacting != null;

        } catch (Exception e) {
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
                .withName("Bank booth")
                .closest();

        if (bank == null) {
            return false;
        }

        lastPresetAttempt = now;

        boolean interacted = bank.interact("Last-preset");

        if (interacted) {
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

        if (ctx.inventory.contains("Shark")) {

            ctx.consumables.eat("Shark");

            markProgress();
        }
    }

    /*
     * FOOD COUNT
     */
    private int foodCount() {

        return ctx.inventory.contains("Shark") ? MIN_FOOD_TO_CONTINUE : 0;
    }

    /*
     * RECORD MEANINGFUL PROGRESS
     */
    private void markProgress() {
        lastProgressTime = System.currentTimeMillis();
    }

    /*
     * WAITING FOR TRADE PARTNER
     */
    private int handleWaitingForPartner() {

        var players = ctx.players.query().list();

        if (players == null || players.isEmpty()) {
            ctx.log("No players found, waiting...");
            return 2000;
        }

        for (var player : players) {
            if (player == null || player.getName() == null) {
                continue;
            }

            if (player.getName().equalsIgnoreCase(TRADE_PARTNER)) {
                ctx.log("Found trade partner: " + TRADE_PARTNER);
                currentState = State.TRADING;
                return 1000;
            }
        }

        ctx.log("Waiting for " + TRADE_PARTNER + "...");
        return 3000;
    }

    /*
     * HANDLE TRADE
     */
    private int handleTrade() {

        long now = System.currentTimeMillis();

        if (now - lastTradeAttempt < TRADE_COOLDOWN) {
            return 800;
        }

        var players = ctx.players.query().list();

        if (players == null || players.isEmpty()) {
            ctx.log("Partner disconnected!");
            currentState = State.HOME;
            tradeInitiated = false;
            return 2000;
        }

        for (var player : players) {
            if (player == null || player.getName() == null) {
                continue;
            }

            if (player.getName().equalsIgnoreCase(TRADE_PARTNER)) {
                if (!tradeInitiated) {
                    ctx.log("Initiating trade with " + TRADE_PARTNER);
                    player.interact("Trade");
                    lastTradeAttempt = now;
                    tradeInitiated = true;
                    return 2000;
                }

                if (ctx.trade.isOpen()) {
                    int bonesInInventory = ctx.inventory.getCount("Dragon bones");

                    if (bonesInInventory < BONES_TO_TRADE) {
                        ctx.log("Withdrawing bones from bank...");
                        ctx.trade.decline();
                        ctx.bank.open();
                        return 2000;
                    }

                    if (!ctx.trade.hasOffered("Dragon bones", BONES_TO_TRADE)) {
                        ctx.log("Offering " + BONES_TO_TRADE + " dragon bones...");
                        ctx.trade.offer("Dragon bones", BONES_TO_TRADE);
                        return 2000;
                    }

                    if (ctx.trade.otherPlayerAccepted()) {
                        ctx.log("Accepting trade...");
                        ctx.trade.accept();
                        return 2000;
                    }

                    if (ctx.trade.isOnFinalScreen()) {
                        ctx.log("Confirming final trade...");
                        ctx.trade.accept();
                        markProgress();
                        return 3000;
                    }
                }

                return 1000;
            }
        }

        ctx.log("Partner not found, returning to home...");
        currentState = State.HOME;
        tradeInitiated = false;
        return 2000;
    }

    @Override
    public void onStop() {
    }
}
