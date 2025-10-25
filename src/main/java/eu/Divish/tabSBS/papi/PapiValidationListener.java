package eu.Divish.tabSBS.papi;

import eu.Divish.tabSBS.lang.LangManager;
import eu.Divish.tabSBS.scoreboard.ScoreboardConfig;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Při připojení prvního hráče jednorázově ověří placeholdery v
 * modules.scoreboard.title + modules.scoreboard.items přes PlaceholderValidator.
 *
 * Registraci uděláme později v mainu.
 */

public final class PapiValidationListener implements Listener {

    private final Plugin plugin;
    private final PlaceholderValidator validator;
    private final ScoreboardConfig cfg;
    private final LangManager lang;
    private final eu.Divish.tabSBS.util.Console cons; // ⬅️ přidej

    private boolean done = false;

    public PapiValidationListener(Plugin plugin, PlaceholderValidator validator, ScoreboardConfig cfg, LangManager lang) {
        this.plugin = plugin;
        this.validator = validator;
        this.cfg = cfg;
        this.lang = lang;
        this.cons = new eu.Divish.tabSBS.util.Console(plugin, false); // ⬅️ jen barevně, bez duplicit

    }

    @EventHandler
    public void onFirstJoin(PlayerJoinEvent e) {
        if (done) return;
        done = true;

        // malé zpoždění, ať má PAPI/vault vše inicializované
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            try {
                validator.validateFor(e.getPlayer(), cfg);
            } catch (Throwable t) {
                cons.error(lang.get("startup.papi.scan.error")); // ⬅️ místo plugin.getLogger().severe(...)
                t.printStackTrace();
            }
        }, 20L); // ~1s
    }
}
