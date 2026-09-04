import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;
import rs.kreme.ksbot.api.wrappers.KSNPC;
import rs.kreme.ksbot.api.wrappers.KSPlayer;

@ScriptManifest(
        name = "Dragon Bone Trader",
        author = "YourName",
        servers = {"osrs"},
        description = "Trades 250 dragon bones to Market account when 250+ bones in bank",
        category = Category.UTILITY
)
public class DragonBoneTradeScript extends Script {

    /*
     * REGIONS
     */
    private static final int HOME_REGION = 12342;

    /*
     * TRADE SETTINGS
     */
    private static final String TRADE_PARTNER = "Market";
    private static final int DRAGON_BONES_TO_TRADE = 250;
    private static final int DRAGON_BONES_MIN_BANK = 250;

    /*
     * STATE
     */
    private enum State {
        CHECKING_BANK,
        GOING_HOME,
        OPENING_BANK,
        WITHDRAWING_BONES,
        FINDING_PARTNER,
        INITIATING_TRADE,
        SENDING_BONES,
        ACCEPTING_TRADE,
        COMPLETE,
        IDLE
    }

    private State currentState = State.CHECKING_BANK;

    /*
     * TIMERS
     */
    private long lastBankCheck = 0;
    private long lastTradeAttempt = 0;
    private long lastProgressTime = System.currentTimeMillis();

    /*
     * TRACKING
     */
    private boolean inTrade = false;
    private int bonesInBank = 0;
    private boolean tradeCompleted = false;

    @Override
    public boolean onStart() {
        ctx.log("=== Dragon Bone Trader Started ===");
        ctx.log("Looking for " + DRAGON_BONES_MIN_BANK + "+ bones in bank to trade to: " + TRADE_PARTNER);
        lastProgressTime = System.currentTimeMillis();
        return true;
    }

    @Override
    public int onProcess() {

        var local = ctx.players.getLocal();

        if (local == null) {
            ctx.log("Logged out! Scheduling relog...");
            ctx.scheduleRelog(1);
            return 1000;
        }

        long now = System.currentTimeMillis();

        /*
         * PRIORITY 1: CHECK IF TRADE COMPLETED
         */
        if (tradeCompleted) {
            ctx.log("Trade completed successfully!");
            currentState = State.COMPLETE;
            return 5000;
        }

        /*
         * PRIORITY 2: STATE HANDLING
         */
        switch (currentState) {

            case CHECKING_BANK:
                return handleCheckBank(now);

            case GOING_HOME:
                return handleGoHome();

            case OPENING_BANK:
                return handleOpenBank();

            case WITHDRAWING_BONES:
                return handleWithdrawBones();

            case FINDING_PARTNER:
                return handleFindPartner();

            case INITIATING_TRADE:
                return handleInitiateTrade();

            case SENDING_BONES:
                return handleSendBones();

            case ACCEPTING_TRADE:
                return handleAcceptTrade();

            case COMPLETE:
                return 2000;

            case IDLE:
                return 5000;

            default:
                return 1000;
        }
    }

    /*
     * CHECK BANK FOR BONES
     */
    private int handleCheckBank(long now) {

        if (now - lastBankCheck < 5000) {
            return 1000;
        }

        lastBankCheck = now;

        ctx.log("Checking bank for dragon bones...");

        if (ctx.bank.isOpen()) {
            int boneCount = ctx.bank.getCount("Dragon bones");
            bonesInBank = boneCount;

            ctx.log("Found " + boneCount + " dragon bones in bank");

            if (boneCount >= DRAGON_BONES_MIN_BANK) {
                ctx.log("Enough bones! Proceeding to trade...");
                currentState = State.GOING_HOME;
                return 1500;
            } else {
                ctx.log("Not enough bones. Need " + DRAGON_BONES_MIN_BANK + ", have " + boneCount);
                return 5000;
            }
        } else {
            ctx.log("Opening bank to check...");
            ctx.bank.open();
            return 2000;
        }
    }

    /*
     * GO HOME
     */
    private int handleGoHome() {

        if (ctx.pathing.inRegion(HOME_REGION)) {
            ctx.log("At home");
            currentState = State.OPENING_BANK;
            return 1500;
        }

        ctx.log("Going home...");
        ctx.chat.sendCommand("home");

        return 3000;
    }

    /*
     * OPEN BANK
     */
    private int handleOpenBank() {

        if (ctx.bank.isOpen()) {
            ctx.log("Bank is open");
            currentState = State.WITHDRAWING_BONES;
            return 1500;
        }

        ctx.log("Opening bank...");
        ctx.bank.open();

        return 2000;
    }

    /*
     * WITHDRAW DRAGON BONES
     */
    private int handleWithdrawBones() {

        if (!ctx.bank.isOpen()) {
            ctx.log("Bank closed, reopening...");
            ctx.bank.open();
            return 2000;
        }

        int inventoryBones = ctx.inventory.getCount("Dragon bones");

        if (inventoryBones >= DRAGON_BONES_TO_TRADE) {
            ctx.log("Have " + inventoryBones + " bones in inventory");
            ctx.bank.close();
            currentState = State.FINDING_PARTNER;
            return 2000;
        }

        ctx.log("Withdrawing " + DRAGON_BONES_TO_TRADE + " dragon bones...");
        ctx.bank.withdraw("Dragon bones", DRAGON_BONES_TO_TRADE);

        return 2000;
    }

    /*
     * FIND TRADE PARTNER
     */
    private int handleFindPartner() {

        var players = ctx.players.query().list();

        if (players == null || players.isEmpty()) {
            ctx.log("No players found nearby");
            return 2000;
        }

        for (KSPlayer player : players) {
            if (player == null || player.getName() == null) {
                continue;
            }

            if (player.getName().equalsIgnoreCase(TRADE_PARTNER)) {
                ctx.log("Found trade partner: " + TRADE_PARTNER);
                currentState = State.INITIATING_TRADE;
                return 1500;
            }
        }

        ctx.log("Trade partner not found, waiting...");
        return 3000;
    }

    /*
     * INITIATE TRADE
     */
    private int handleInitiateTrade() {

        long now = System.currentTimeMillis();

        if (now - lastTradeAttempt < 2000) {
            return 1000;
        }

        var players = ctx.players.query().list();

        if (players == null || players.isEmpty()) {
            ctx.log("Partner disconnected, going back to checking bank");
            currentState = State.CHECKING_BANK;
            return 2000;
        }

        for (KSPlayer player : players) {
            if (player == null || player.getName() == null) {
                continue;
            }

            if (player.getName().equalsIgnoreCase(TRADE_PARTNER)) {
                ctx.log("Initiating trade with " + TRADE_PARTNER);
                player.interact("Trade");
                lastTradeAttempt = now;
                currentState = State.SENDING_BONES;
                return 2000;
            }
        }

        ctx.log("Partner not found");
        return 2000;
    }

    /*
     * SEND BONES (FIRST SCREEN)
     */
    private int handleSendBones() {

        if (!isInTradeWindow()) {
            ctx.log("Trade window not open, retrying...");
            currentState = State.INITIATING_TRADE;
            return 2000;
        }

        int inventoryBones = ctx.inventory.getCount("Dragon bones");

        if (inventoryBones < DRAGON_BONES_TO_TRADE) {
            ctx.log("Not enough bones in inventory!");
            return 2000;
        }

        ctx.log("Adding " + DRAGON_BONES_TO_TRADE + " dragon bones to trade...");
        ctx.trade.offer("Dragon bones", DRAGON_BONES_TO_TRADE);

        return 2000;
    }

    /*
     * ACCEPT TRADE (BOTH SCREENS)
     */
    private int handleAcceptTrade() {

        if (!isInTradeWindow()) {
            ctx.log("Trade window closed");
            currentState = State.CHECKING_BANK;
            return 2000;
        }

        ctx.log("Accepting trade...");
        ctx.trade.accept();

        return 1500;
    }

    /*
     * CHECK IF IN TRADE WINDOW
     */
    private boolean isInTradeWindow() {

        try {
            return ctx.trade.isOpen();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onStop() {
        ctx.log("Trade script stopped");
    }
}
