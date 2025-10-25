package eu.Divish.tabSBS.nametag;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Nametag lifecycle:
 * - applyFor po joinu (krátký delay kvůli Vault/LP init),
 * - při změně světa: pokud je v cílovém světě zakázáno → clearFor, jinak applyFor,
 * - clearFor při quitu (uklidit nt_ entry na boardech viewerů).
 */
public final class NametagListeners implements Listener {

    private final Plugin plugin;
    private final NametagService service;

    public NametagListeners(Plugin plugin, NametagService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Malý delay, ať jsou provider-y připravené (Vault/LP/PAPI).
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> service.applyFor(e.getPlayer()), 10L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        // Krátké zpoždění: nejdřív ať proběhnou ostatní listenery/scoreboard přepnutí.
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            // NametagService si respekt vůči WorldsGate čte z configu (modules.tablist.respect_worlds_gate).
            // Pokud je v cílovém světě zakázáno, okamžitě hráče vyčisti.
            boolean respect = plugin.getConfig().getBoolean("modules.tablist.respect_worlds_gate", true);
            if (respect && !serviceEnabledHere(p)) {
                service.clearFor(p);
            } else {
                service.applyFor(p);
            }
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        service.clearFor(e.getPlayer());
    }

    // Pomůcka: zeptej se přes service (stejná logika jako uvnitř), bez přístupu na WorldsGate.
    private boolean serviceEnabledHere(Player p) {
        // Pokud service interně bere respekt z configu, stačí zkontrolovat aktuální svět přes applyFor().
        // Tady pouze vrátíme true – applyFor() si případné vynechání vyřeší, ale pokud chceš tvrdé
        // chování na listeneru, můžeš brát flag z configu a použít WorldsGate přes service, pokud ho zpřístupní.
        return true;
    }
}
