package eu.Divish.tabSBS.worlds;

import eu.Divish.tabSBS.scoreboard.ScoreboardManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Zapíná/vypíná sidebar scoreboard podle configu "worlds.disabled".
 * - join/respawn: krátké zpoždění (ať se stihne inicializovat klient/ostatní pluginy)
 * - změna světa: aplikace ihned
 */
public final class WorldsListener implements Listener {

    private final Plugin plugin;
    private final WorldsGate worldsGate;
    private final ScoreboardManager scoreboard;

    private static final long ATTACH_DELAY_TICKS = 10L;

    public WorldsListener(Plugin plugin, WorldsGate worldsGate, ScoreboardManager scoreboard) {
        this.plugin = plugin;
        this.worldsGate = worldsGate;
        this.scoreboard = scoreboard;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> apply(p), ATTACH_DELAY_TICKS);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> apply(p), ATTACH_DELAY_TICKS);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        apply(p);
    }

    private void apply(Player p) {
        if (p == null || p.getWorld() == null) return;
        boolean allowed = worldsGate.isScoreboardAllowedIn(p.getWorld());
        if (allowed) {
            scoreboard.show(p);
        } else {
            // v zakázaném světě sidebar vypneme (bez ohledu na enabled stav modulu je to safe)
            scoreboard.hide(p);
        }
    }
}
