package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * list of players and for each player there is all the tokens he has, each number is the token slot
     */
    protected List<List<Integer>> playerTokens;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.playerTokens =   new ArrayList<List<Integer>>();
        for(int i = 0 ; i < env.config.players ; i++) {
            this.playerTokens.add(new ArrayList<Integer>());
        }
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
        this.playerTokens =   new LinkedList<List<Integer>>();
        for(int i = 0 ; i < env.config.players ; i++) {
            this.playerTokens.add(new LinkedList<Integer>());
        }
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        if(deck == null || deck.isEmpty()){
            return;
        }
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    //this does not need to be sync, the only one who removes or adds a card is the dealer, and he is only on thread
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    //this does not need to be sync, the only one who removes or adds a card is the dealer, and he is calling from sync function
    //we first sleep as requested, then show in UI that there is no card. then delete the card, and after that delete the toknes
    //we delete card before tokens, because after deleting the card no one will add a token to it (we don't want to add a token to a deleted card)
    public void removeCard(int slot) { 
        Integer cardToRemove = slotToCard[slot];
        if(cardToRemove != null){//if the cards does not exstis no need to wait
            try {
                Thread.sleep(env.config.tableDelayMillis);
            } catch (InterruptedException ignored) { }
        }
        //remove from cardToSlot and slotToCard
        synchronized (this){//this is synced to place token so I won't remove when there is token
            if(cardToRemove != null){
                env.ui.removeCard(slot); 
                cardToSlot[cardToRemove] = null;
                slotToCard[slot] = null;
            }
        }
        int i = 0;//remove the tokens after the card was removed this is to verify non were added
        for (List<Integer> row : playerTokens) {
            this.removeToken(i, slot);
            i++;
        }
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    //this is synced to the remove so I won't place before removing
    public void placeToken(int player, int slot) {
        synchronized (this){
            if (slotToCard[slot] != null) {
                env.ui.placeToken(player, slot);
                playerTokens.get(player).add(slot);
            }
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    //this does not need to sync, becuase there is token per player, and one thread to any player
     public boolean removeToken(int player, int slot) {
        // Check its length and return true id there was a removals
        if (playerTokens.get(player).contains(slot)) {
            env.ui.removeToken(player, slot);
            playerTokens.get(player).remove(Integer.valueOf(slot));
            return true;
        } else {
            return false;
        }
    }

    /**
     * Removes all the tokens of a player
     * @param player - the player the token belongs to.
     */
    //this does not need to be sync, only one how calles is a player and he wants to delete his tokens, he waits until the process finished to add more, and other players don't change this
    public void removeAllTokensOfPlayer(int player) {
        playerTokens.get(player).forEach(token ->{
            env.ui.removeToken(player, token);
        });
        playerTokens.get(player).clear();
    }


    /**
     * return all the empty slots on the table
     */
    //this does not need to be sync, we get all empty slots, the only one who removes or adds a card is the dealer, and he is only on thread
    public List<Integer> getEmptySlots() {
        List<Integer> emptySlots = new LinkedList<>();
        for(int i = 0 ; i < slotToCard.length ; i++) {
            if(slotToCard[i] == null) {
                emptySlots.add(i);
            }
        }
        return emptySlots;
    }
     /**
     * return all the not empty cards
     */
    //this does not need to be sync, we get all not empty cards
    public List<Integer> getNotEmptyCards() {
        List<Integer> emptySlots = new LinkedList<>();
        for(int i = 0 ; i < slotToCard.length ; i++) {
            if(slotToCard[i] != null) {
                emptySlots.add(slotToCard[i]);
            }
        }
        return emptySlots;
    }
    /**
     * return card
     */
    public Integer getCard(int slot) {
        return slotToCard[slot];
    }
}
