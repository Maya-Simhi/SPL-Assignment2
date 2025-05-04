package bguspl.set.ex;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The dealer.
     */
    private Dealer dealer;

    /**
     * The insertaion of the keyboard
     */
    private ArrayBlockingQueue<Integer> inputCard;

    /**
     * checks if a player can play
     */
    private volatile boolean canPlay = true;

    /*
     * max num of keys can set before processing it
     */
    private final int maxNumOfPreprocessKeys;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.maxNumOfPreprocessKeys = env.config.featureSize;
        this.inputCard = new ArrayBlockingQueue<Integer>(maxNumOfPreprocessKeys);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            if(dealer.getCanPlay() && canPlay){
                try {
                 //   checkingSet();
                    int slot = inputCard.take();
                    if (!table.removeToken(id, slot) && table.playerTokens.get(id).size() < maxNumOfPreprocessKeys) { // we only go in if it was picked a diffrenet card, if it is a card that was already picked we "unpick" it
                        //checking if we can take one more card 
                            table.placeToken(id, slot);
                            checkingSet();
                    }
                } catch (InterruptedException e) {
                    env.logger.warning("error adding a card " + e.getMessage());
                }
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    private void checkingSet(){
        if (table.playerTokens.get(id).size() == maxNumOfPreprocessKeys && !terminate) {// checks this player found a set and we did not terminate
            // asking delear to check the cards
            Boolean answer =  dealer.checkCardsBasic(id); 
            //checking the answer and doing as it is (pleanty or point)
            if (answer != null) {
                //if the set is leagel
                if (answer == true) {//the delear already deleted the cards and tokens
                    point();
                } else {//of the set is not leagel
                   // table.removeAllTokensOfPlayer(id);//need to remove tokens only if got worng 
                    penalty();
                }
            }
        }
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        Random random = new Random();
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                if(dealer.getCanPlay() && inputCard.size() != maxNumOfPreprocessKeys && canPlay){
                    int keyPress = random.nextInt(env.config.tableSize);
                    keyPressed(keyPress);
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(inputCard.size() != maxNumOfPreprocessKeys && canPlay){
            inputCard.add(slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score = score+1;
        env.ui.setScore(id, score);
        long timeOfPointFreeze = env.config.pointFreezeMillis;
        freeze(timeOfPointFreeze);
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long timeOfPenalty = env.config.penaltyFreezeMillis;
        freeze(timeOfPenalty);
    }

    private void threadSleep(long timeOfPenalty){
        try {
            Thread.currentThread();
            Thread.sleep(timeOfPenalty);
        } catch (Exception e) {
            env.logger.warning("error using thread sleep " + e.getMessage());
        }
    }

    private void freeze(long timeOfFreeze){
        canPlay = false;
        env.ui.setFreeze(id, timeOfFreeze);
        //if there is more then 1 sec
        while (timeOfFreeze > 1000) {
            //sleep for 1 sec and update the board
            threadSleep(1000);
            timeOfFreeze = timeOfFreeze - 1000;
            env.ui.setFreeze(id, timeOfFreeze);
        }
        //if there is still more time to wait
        if (timeOfFreeze > 0) {
            threadSleep(timeOfFreeze);
        }
        env.ui.setFreeze(id, 0);
        canPlay = true;
    }

    public int score() {
        return score;
    }

    public ArrayBlockingQueue<Integer> getInputCard(){
        return inputCard;
    }
}
