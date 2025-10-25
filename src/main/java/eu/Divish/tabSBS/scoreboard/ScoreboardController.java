package eu.Divish.tabSBS.scoreboard;

import org.bukkit.entity.Player;

/** Jednoduché rozhraní pro zobrazení/skrývání scoreboardu u hráče. */
public interface ScoreboardController {
    void show(Player player); // připoj, vykresli
    void hide(Player player); // odpoj, ukliď
}
