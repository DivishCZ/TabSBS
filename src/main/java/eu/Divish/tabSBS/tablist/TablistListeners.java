package eu.Divish.tabSBS.tablist;

import eu.Divish.tabSBS.worlds.WorldsGate;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.Plugin;

/**
 * Listener, který:
 *  - při JOIN: (volitelně) okamžitě pošle header/footer + aplikuje řazení v TABu
 *  - při QUIT: uklidí hráče z našich TAB týmů a resetne listName
 *  - při změně světa: re-apply podle WorldsGate (povolit/skrýt tablist, přerovnat týmy)
 */
public final class TablistListeners implements Listener {

    private final Plugin plugin;
    private final TablistConfig cfg;
    private final TablistManager tablist;
    private final TabSortingService sorting;
    private final WorldsGate worldsGate;

    public TablistListeners(Plugin plugin,
                            TablistConfig cfg,
                            TablistManager tablist,
                            TabSortingService sorting,
                            WorldsGate worldsGate) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.tablist = tablist;
        this.sorting = sorting;
        this.worldsGate = worldsGate;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Push header/footer hned po joinu (pokud povoleno v configu).
        if (cfg.pushOnJoin()) {
            // malé zpoždění, ať má hráč načtené PAPI / data
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> tablist.pushTo(e.getPlayer()), 2L);
        }
        // Aplikovat řazení krátce po joinu.
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> sorting.applyAll(), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Odstraníme interní mapování + okamžitě vyčistíme hráče z našich týmů a resetneme listName.
        sorting.remove(e.getPlayer());
        sorting.clearFor(e.getPlayer());
        // Header/footer neřešíme – hráč už odchází.
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        // Respektovat WorldsGate: podle cílového světa.
        if (cfg.respectWorldsGate() && worldsGate != null) {
            boolean allowed = worldsGate.isScoreboardAllowedIn(e.getPlayer().getWorld());
            if (!allowed) {
                // V zakázaném světě hráče vyčistíme z našich týmů a pošleme prázdný header/footer.
                sorting.clearFor(e.getPlayer());
                tablist.pushTo(e.getPlayer()); // TablistManager → pošle empty H/F díky gate
            } else {
                // V povoleném světě pushneme aktuální H/F.
                tablist.pushTo(e.getPlayer());
            }
        }
        // Přerovnat týmy (pro případ odlišných priorit / overlayů).
        sorting.applyAll();
    }
}
