package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * True if the players can play and false if not
     */
    private volatile boolean canPlay= true;

    /**
     * a param for time left to show on screen
     */
    private volatile Date timeStarted = null;

    /**
     * finished the game
     */
    private volatile boolean isGameFinished = false;


    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /*
     * The time of each loop in delear sleep
     */
    private final int sleepTimeForDelearLoopInMilis = 5;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        java.util.Collections.shuffle(deck);
    } 

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for(int i = 0 ; i < players.length ; i++) {//initilizing players 
            new Thread(players[i]).start();
            env.logger.info("thread for player " + i + " starting.");
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            if(timeStarted == null){
                updateTimerDisplay(true);
            }
            canPlay = true;
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime && !shouldFinish()) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            canPlay = true;
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        canPlay = false;
        terminate = true;
        for (int playerId = players.length - 1; playerId >= 0; playerId--) {
            players[playerId].terminate();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate ||  isGameFinished;// deleted env.util.findSets(deck, 1).size() == 0
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        //do nothing
        //in my implemintation I remove the card right after I find a set so it will be sync (and no other player will try this set)
        //so no cards need to be removed here
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {//there is no need for sync here because only one thread of dealer, if there is more we do need to add sync
        // //checking if game has finished
        if(shouldFinish()){
            return;
        }  
        List<Integer> emptySlots = table.getEmptySlots();
        java.util.Collections.shuffle(emptySlots);
        if(emptySlots.size() == env.config.tableSize){
            canPlay = false;
        }
        for(int i = 0 ; i < emptySlots.size() ; i++) {
            if(!this.deck.isEmpty()) { //only adding if they are still cards in the deck
                table.placeCard(deck.remove(0), emptySlots.get(i));
            }
        }
        if (env.config.hints) {
            synchronized (this){ // I synced this because I don't want anyone to remove cards while using this then I can get null pointer exceptions
                table.hints();
            }
        }
    }

    private synchronized boolean checkIfNoSets(){
        List<Integer> mergedList = new ArrayList<>(table.getNotEmptyCards()); // getting the cards that are on the table
        mergedList.addAll(deck);
        if(mergedList.size() == 0){
            canPlay = false;
            isGameFinished = true;
            return true;
        }
        if(env.util.findSets(mergedList, 1).size() == 0 ){ // if this is true that means there are no more sets and we finished the game
            canPlay = false;
            isGameFinished = true;
            env.logger.info("there are no more sets");
            return true;
        } 
        return false; 
    }

    

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        long sleepUntil = 400;
        long sleepLoop = 5;//sleepTimeForDelearLoopInMilis;
        if(sleepUntil < sleepLoop){
            sleepLoop = sleepUntil;
        }
        if(getTimeEndOfGame() > sleepUntil) {
            while (!shouldFinish()  && sleepUntil > 0) {
                try {
                    sleepUntil = sleepUntil -sleepLoop;
                    Thread.sleep(sleepLoop); 
                    updateTimerDisplay(false);
                } catch (InterruptedException e) {
                    env.logger.warning("error in thread sleep: " + e.getMessage());
                }
            }
        }
    }
    /*
     * this function is not synced and checks if there is a set,
     * if there is it calles a sync function to verify again if there is a set and then returns that there is and deletes the cards
     */
    public Boolean checkCardsBasic(int playerIndex) {
        if( table.playerTokens.get(playerIndex).size() == env.config.featureSize){
            int[] inputSlotArray = table.playerTokens.get(playerIndex).stream()
                .mapToInt(Integer::intValue)
                .toArray();
            int[] inputCardArray = new int[inputSlotArray.length];
            for(int i=0; i<inputSlotArray.length; i++){
                if(table.getCard(inputSlotArray[i]) != null){
                    inputCardArray[i] = table.getCard(inputSlotArray[i]);
                } else{
                    env.logger.warning("we got a card that is not in table1");
                }
            }
            Boolean answer = env.util.testSet(inputCardArray);
            if(answer == true){
                return checkCards(playerIndex);
            }
            return false;
        }
        return null;
    }

// 2 players can call this function, and I want only one to call this at a time so this needs to be sync
    public synchronized Boolean checkCards(int playerIndex) {
        //update check players cards if needed
        if( table.playerTokens.get(playerIndex).size() == env.config.featureSize){
            int[] inputSlotArray = table.playerTokens.get(playerIndex).stream()
                .mapToInt(Integer::intValue)
                .toArray();
            int[] inputCardArray = new int[inputSlotArray.length];
            for(int i=0; i<inputSlotArray.length; i++){
                if(table.getCard(inputSlotArray[i]) != null){
                    inputCardArray[i] = table.getCard(inputSlotArray[i]);
                } else{
                    env.logger.warning("we got a card that is not in table1");
                    return null;
                }
            }
            //verify we got exactly 3 cards
            if(inputCardArray.length != env.config.featureSize || inputSlotArray.length != env.config.featureSize){
                return null;
            }
            Boolean answer = env.util.testSet(inputCardArray);//setting inside the legale set the result of validation
            if(answer == true){//if the answer is true, we remove the cards from table
                for(int slotIndex:inputSlotArray){
                    env.logger.warning("got a set for cards: ");
                    env.logger.warning("$$$$ " + table.slotToCard[slotIndex]);
                    table.removeCard(slotIndex);
                }
                updateTimerDisplay(true);
            }   
            return answer;
        }
        return null;
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(shouldFinish()){ // if we should finish we stop everything and just put countDown 0
            env.ui.setCountdown(0, shouldSetWarn(0));
            return;
        }
        if(reset){// if we restart we create a new date and update the countDown
            timeStarted = new Date();
            env.ui.setCountdown(env.config.turnTimeoutMillis, shouldSetWarn(env.config.turnTimeoutMillis));
        } else{
            long timeLeft = getTimeEndOfGame();
            if(timeLeft <= 0 ){// if we got timeout we restart by removing cards, shuffling deck placing cards and restart time, this needs to be synced so no one will try to get a set at this time
                synchronized(this){
                    canPlay = false;
                    env.ui.setCountdown(0, shouldSetWarn(0));
                    removeAllCardsFromTable();
                    Collections.shuffle(deck);
                    if(checkIfNoSets()){
                        return;
                    }
                    placeCardsOnTable();
                    updateTimerDisplay(true);
                    canPlay = true;
                }
            }else{//if there is no timeout update timer
                env.ui.setCountdown(timeLeft, shouldSetWarn(timeLeft));
            }
        }
    }
    /**
     * getting the time until end of the game
     */
    private long getTimeEndOfGame(){
        long timeOfGameRuning = new Date().getTime() -  timeStarted.getTime();
        long timeLeft = env.config.turnTimeoutMillis - timeOfGameRuning;
        return timeLeft;
    }
    /**
     * returns if this should be in warn or not
     */
    private boolean shouldSetWarn(long timeLeft){
        return timeLeft < env.config.turnTimeoutWarningMillis;
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private synchronized void removeAllCardsFromTable() {
        if(shouldFinish()){//todo delete all of this
            int sum = 0;
            for(Player player: players){
                sum = sum +player.score();
                System.out.println(player.score());
            }
            System.out.println(table.getNotEmptyCards().size());
            System.out.println(sum*env.config.featureSize + table.getNotEmptyCards().size()+deck.size());
            // try {
            //     Thread.sleep(5000); 
            // } catch (InterruptedException e) {
            //     env.logger.warning("error in thread sleep: " + e.getMessage());
            // }
       }
        for(int i = 0 ; i < env.config.tableSize ; i++) {
            if(table.slotToCard[i] != null){
                env.logger.warning("%%%% " + table.slotToCard[i]);
                deck.add(table.slotToCard[i]);
                table.removeCard(i);  
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private synchronized void announceWinners() {
        canPlay = false;
        if(terminate == true){
            return; //if we got here because this was terminated we don't want to wait for announce 
        }
        int maxScoer = -1;
        for(Player player: players){
            if(player.score() > maxScoer){
                maxScoer = player.score();
            }
        }
        List<Integer> winnerList = new ArrayList<>();
        for(Player player: players){
            if(player.score() == maxScoer){
                winnerList.add(player.id);
            }
        }
         // converting List to Array
         int[] winnerArrayId = winnerList.stream()
                .mapToInt(Integer::intValue)
                .toArray();
        env.ui.announceWinner(winnerArrayId);
        long sleepUntil = env.config.endGamePauseMillies;
        long sleepLoop = sleepTimeForDelearLoopInMilis;
        while (sleepUntil > 0 && !terminate) {//sleep until finish
            try {
                sleepUntil = sleepUntil -sleepLoop;
                Thread.sleep(sleepLoop); 
            } catch (InterruptedException e) {
                env.logger.warning("error in thread sleep: " + e.getMessage());
            }
        }
        this.terminate();

    }
    /**
     * @return if players can play
     */
    public boolean getCanPlay() {
        return canPlay;
    }
}
