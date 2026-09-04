import rs.kreme.ksbot.api.scripts.Category;
import rs.kreme.ksbot.api.scripts.Script;
import rs.kreme.ksbot.api.scripts.ScriptManifest;

@ScriptManifest(
        name = "Trade Acceptor",
        author = "YourName",
        servers = {"osrs"},
        description = "Accepts trade requests and confirms both trade screens",
        category = Category.UTILITY
)
public class TradeAcceptorScript extends Script {

    /*
     * TRADE SETTINGS
     */
    private static final long TRADE_CHECK_INTERVAL = 500;

    /*
     * STATE
     */
    private enum State {
        IDLE,
        ACCEPTING_TRADE,
        CONFIRMING_TRADE,
        COMPLETE
    }

    private State currentState = State.IDLE;

    /*
     * TIMERS
     */
    private long lastTradeCheck = 0;
    private long tradeStartTime = 0;
    private long tradeTimeout = 60000;

    @Override
    public boolean onStart() {
        ctx.log("=== Trade Acceptor Started ===");
        ctx.log("Waiting for trade requests...");
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
         * PRIORITY 1: STATE HANDLING
         */
        switch (currentState) {

            case IDLE:
                return handleIdle(now);

            case ACCEPTING_TRADE:
                return handleAcceptingTrade(now);

            case CONFIRMING_TRADE:
                return handleConfirmingTrade(now);

            case COMPLETE:
                return handleComplete();

            default:
                return 1000;
        }
    }

    /*
     * IDLE STATE - WAIT FOR TRADE REQUEST
     */
    private int handleIdle(long now) {

        if (now - lastTradeCheck < TRADE_CHECK_INTERVAL) {
            return 500;
        }

        lastTradeCheck = now;

        /*
         * CHECK IF TRADE WINDOW IS OPEN
         */
        if (ctx.trade.isOpen()) {
            ctx.log("Trade request received!");
            currentState = State.ACCEPTING_TRADE;
            tradeStartTime = now;
            return 1000;
        }

        return 500;
    }

    /*
     * ACCEPTING TRADE - FIRST SCREEN
     */
    private int handleAcceptingTrade(long now) {

        /*
         * CHECK FOR TIMEOUT
         */
        if (now - tradeStartTime > tradeTimeout) {
            ctx.log("Trade timeout! Aborting...");
            if (ctx.trade.isOpen()) {
                ctx.trade.decline();
            }
            currentState = State.IDLE;
            return 2000;
        }

        if (!ctx.trade.isOpen()) {
            ctx.log("Trade window closed");
            currentState = State.IDLE;
            return 1000;
        }

        if (ctx.trade.isOnFinalScreen()) {
            ctx.log("Moving to final confirmation...");
            currentState = State.CONFIRMING_TRADE;
            return 1000;
        }

        ctx.log("Accepting first trade screen...");
        ctx.trade.accept();

        return 1500;
    }

    /*
     * CONFIRMING TRADE - FINAL SCREEN
     */
    private int handleConfirmingTrade(long now) {

        /*
         * CHECK FOR TIMEOUT
         */
        if (now - tradeStartTime > tradeTimeout) {
            ctx.log("Trade timeout! Aborting...");
            if (ctx.trade.isOpen()) {
                ctx.trade.decline();
            }
            currentState = State.IDLE;
            return 2000;
        }

        if (!ctx.trade.isOpen()) {
            ctx.log("Trade completed successfully!");
            currentState = State.COMPLETE;
            return 2000;
        }

        if (ctx.trade.isOnFinalScreen()) {
            ctx.log("Confirming final trade screen...");
            ctx.trade.accept();
            return 1500;
        }

        return 1000;
    }

    /*
     * TRADE COMPLETE
     */
    private int handleComplete() {

        ctx.log("Trade finished! Returning to idle...");
        currentState = State.IDLE;

        return 3000;
    }

    @Override
    public void onStop() {
        ctx.log("Trade acceptor stopped");
    }
}
