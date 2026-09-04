import rs.kreme.ksbot.api.game.Prayer;
import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;
import rs.kreme.ksbot.api.wrappers.KSGroundItem;
import rs.kreme.ksbot.api.wrappers.KSNPC;
import rs.kreme.ksbot.api.wrappers.KSObject;
import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.ActionEvent;

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
    private static final int PKER_DETECTION_RADIUS = 3;

    /*
     * COOLDOWNS
     */
    private static final long HOME_COMMAND_COOLDOWN = 5000;
    private static final long WEST_COMMAND_COOLDOWN = 5000;
    private static final long PRESET_COOLDOWN = 3000;
    private static final long ATTACK_COOLDOWN = 1200;
    private static final long LOOT_COOLDOWN = 800;
    private static final long PKER_COOLDOWN = 60000;
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

    /*
     * TIMERS
     */
    private long lastHomeCommand = 0;
    private long lastWestCommand = 0;
    private long lastPresetAttempt = 0;
    private long lastAttackAttempt = 0;
    private long lastLootAttempt = 0;
    private long lastProgressTime = 0;
    private long lastPKerEncounter = 0;
    private long lastTradeAttempt = 0;
    private long lastBankCheck = 0;

    /*
     * TRADING STATE
     */
    private int bonesInBank = 0;
    private boolean tradeInitiated = false;

    /*
     * GUI TRACKING
     */
    private int bonesCollected = 0;
    private JFrame guiFrame = null;
    private JLabel bonesLabel = null;
    private JLabel statusLabel = null;
    private JButton tradeButton = null;
    private boolean forceTradeFlag = false;

    @Override
    public boolean onStart() {

        lastProgressTime = System.currentTimeMillis();

        initializeGUI();

        sendHome();

        return true;
    }

    /*
     * INITIALIZE GUI
     */
    private void initializeGUI() {

        guiFrame = new JFrame("Dragon Bones Tracker");
        guiFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        guiFrame.setSize(300, 180);
        guiFrame.setLocationRelativeTo(null);
        guiFrame.setAlwaysOnTop(true);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new LineBorder(Color.BLACK, 2));
        panel.setBackground(new Color(50, 50, 50));

        bonesLabel = new JLabel("Bones Collected: 0 / " + BONES_TO_TRADE);
        bonesLabel.setForeground(Color.WHITE);
        bonesLabel.setFont(new Font("Arial", Font.BOLD, 14));
        bonesLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        statusLabel = new JLabel("Status: Fighting");
        statusLabel.setForeground(Color.CYAN);
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel buttonPanel = new JPanel();
        buttonPanel.setBackground(new Color(50, 50, 50));

        tradeButton = new JButton("Initiate Trade");
        tradeButton.setBackground(new Color(34, 139, 34));
        tradeButton.setForeground(Color.WHITE);
        tradeButton.setFont(new Font("Arial", Font.BOLD, 12));
        tradeButton.addActionListener(e -> {
            forceTradeFlag = true;
            ctx.log("Trade manually initiated via GUI");
        });

        JButton resetButton = new JButton("Reset Counter");
        resetButton.setBackground(new Color(139, 69, 19));
        resetButton.setForeground(Color.WHITE);
        resetButton.setFont(new Font("Arial", Font.BOLD, 12));
        resetButton.addActionListener(e -> {
            bonesCollected = 0;
            updateGUI();
            ctx.log("Counter reset to 0");
        });

        buttonPanel.add(tradeButton);
        buttonPanel.add(resetButton);

        panel.add(Box.createVerticalStrut(10));
        panel.add(bonesLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(10));
        panel.add(buttonPanel);
        panel.add(Box.createVerticalStrut(10));

        guiFrame.add(panel);
        guiFrame.setVisible(true);
    }

    /*
     * UPDATE GUI DISPLAY
     */
    private void updateGUI() {

        if (bonesLabel != null) {
            bonesLabel.setText("Bones Collected: " + bonesCollected + " / " + BONES_TO_TRADE);

            if (bonesCollected >= BONES_TO_TRADE) {
                bonesLabel.setForeground(new Color(0, 255, 0));
            } else {
                bonesLabel.setForeground(Color.WHITE);
            }
        }

        if (statusLabel != null) {
            String status = "";
            switch (currentState) {
                case FIGHTING:
                    status = "Status: Fighting";
                    break;
                case HOME:
                    status = "Status: Banking";
                    break;
                case TRADING:
                    status = "Status: Trading";
                    break;
                case WAITING_FOR_PARTNER:
                    status = "Status: Waiting for Partner";
                    break;
                case RECOVERING:
                    status = "Status: Recovering";
                    break;
                default:
                    status = "Status: " + currentState;
            }
            statusLabel.setText(status);
        }
    }

    @Override
    public int onProcess() {

        var local = ctx.players.getLocal();

        if (local == null) {
            ctx.log("Player logged out! Scheduling relog...");
            ctx.scheduleRelog(1);
            return 1000;
        }

        updateGUI();

        long now = System.currentTimeMillis();

        /*
         * CHECK IF BONE LIMIT REACHED OR MANUAL TRADE INITIATED
         */
        if ((bonesCollected >= BONES_TO_TRADE || forceTradeFlag) && currentState != State.TRADING && currentState != State.WAITING_FOR_PARTNER) {
            ctx.log("Bone limit reached! Initiating trade...");
            currentState = State.WAITING_FOR_PARTNER;
            tradeInitiated = false;
            forceTradeFlag = false;
            return 2000;
        }

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
         * PRIORITY 1.5:
         * PKER DETECTION
         */
        if (ctx.pathing.inRegion(WEST_DRAGONS_REGION) && isPlayerNearby()) {

            if (now - lastPKerEncounter >= PKER_COOLDOWN) {
                ctx.log("PKer detected! Teleporting home and waiting 60 seconds...");
                sendHome();
                lastPKerEncounter = now;
                return 3000;
            } else {
                /*
                 * Still in cooldown, keep waiting
                 */
                return 2000;
            }
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

            if (ctx.bank.isOpen()) {
                bonesInBank = ctx.bank.getCount("Dragon bones");
                ctx.log("Bones in bank: " + bonesInBank);

                if (bonesInBank >= BONES_BANK_THRESHOLD) {
                    ctx.log("Enough bones for trade! Moving to trade...");
                    currentState = State.WAITING_FOR_PARTNER;
                    tradeInitiated = false;
                    return 2000;
                }
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
    private int handleFighting() {

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

                    bonesCollected++;
                    ctx.log("Bones collected: " + bonesCollected);

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
     * CHECK FOR PKERS (players with skull nearby)
     */
    private boolean isPlayerNearby() {

        if (!ctx.pathing.inRegion(WEST_DRAGONS_REGION)) {
            return false;
        }

        try {
            var players = ctx.players.query().list();

            if (players == null || players.isEmpty()) {
                return false;
            }

            var local = ctx.players.getLocal();
            if (local == null) {
                return false;
            }

            for (var player : players) {
                if (player == null || player.equals(local)) {
                    continue;
                }

                int distance = ctx.pathing.distanceTo(player);

                if (distance <= PKER_DETECTION_RADIUS) {
                    if (player.getSkullIcon() != null) {
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            return false;
        }

        return false;
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

                try {
                    if (ctx.trade.isOpen()) {
                        int bonesInInventory = ctx.inventory.getCount("Dragon bones");

                        if (bonesInInventory < BONES_TO_TRADE) {
                            ctx.log("Not enough bones in inventory, returning to home");
                            currentState = State.HOME;
                            tradeInitiated = false;
                            return 2000;
                        }

                        ctx.log("Offering " + BONES_TO_TRADE + " dragon bones...");
                        ctx.trade.offer("Dragon bones", BONES_TO_TRADE);

                        return 2000;
                    }
                } catch (Exception e) {
                    ctx.log("Trade error: " + e.getMessage());
                }

                return 1000;
            }
        }

        ctx.log("Partner not found, returning to home...");
        currentState = State.HOME;
        tradeInitiated = false;

        bonesCollected = 0;
        ctx.log("Trade complete! Counter reset to 0");
        updateGUI();

        return 2000;
    }

    @Override
    public void onStop() {
        if (guiFrame != null) {
            guiFrame.dispose();
        }
    }
}
