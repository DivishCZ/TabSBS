package eu.Divish.tabSBS.scoreboard;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Koordinátor Scoreboardu:
 * - drží ScoreboardConfig, ScoreboardManager a TempBoardOverlay
 * - nabízí start/stop celého modulu (základní board + overlay)
 * - poskytuje jednoduché API pro show/hide/refresh
 *
 * Zapojíme do mainu později.
 *
 * Úpravy:
 * - overlay už není final a při applyNewConfig() se opravdu re-instancuje s novým configem
 * - start() po spuštění hned volá manager.updateAll() (okamžitý vizuální refresh)
 * - přidány helpery applyOnce(Player) a clearFor(Player) pro jednotné volání z reloadAll()
 */
public final class ScoreboardRuntime {

    private final Plugin plugin;

    private ScoreboardConfig config;
    private final ScoreboardManager manager;
    private TempBoardOverlay overlay; // ← už není final, aby šel re-instancovat po reloadu

    public ScoreboardRuntime(Plugin plugin, ScoreboardConfig config) {
        this.plugin = plugin;
        this.config = config;

        this.manager = new ScoreboardManager(plugin, config);
        this.overlay = new TempBoardOverlay(plugin, config, manager);
    }

    // ---------- lifecycle ----------

    /** Spustí periodické updaty základního scoreboardu a (pokud je povolen) overlay cyklus. */
    public void start() {
        if (!config.enabled()) {
            // pro jistotu zastav vše, kdyby volající zapomněl
            stop();
            return;
        }
        manager.start();
        if (config.tempEnabled()) {
            overlay.start();
        }
        // Okamžitý vizuální refresh (title + řádky) – ať je změna vidět hned po /reload
        manager.updateAll();
    }

    /** Zastaví overlay i periodické updaty. Hráčům scoreboard nenecháváme násilně mizet. */
    public void stop() {
        try { overlay.stop(); } catch (Throwable ignored) {}
        try { manager.stop(); } catch (Throwable ignored) {}
    }

    /**
     * Po změně configu (např. /reload) přepošleme nová nastavení dovnitř.
     * - re-instancujeme overlay s novým configem (předtím skutečně nedocházelo)
     * - znovu spustíme jen pokud je modul povolen
     * - provedeme okamžitý refresh obsahu
     */
    public void applyNewConfig(ScoreboardConfig newConfig) {
        // 1) zastav běžící smyčky podle starého configu
        stop();

        // 2) přepni na nový config
        this.config = newConfig;

        // 3) posuň nový config do manageru
        manager.applyNewConfig(newConfig);

        // 4) re-instancuj overlay, protože držel referenci na starý config
        this.overlay = new TempBoardOverlay(plugin, newConfig, manager);

        // 5) znovu spusť jen pokud je povoleno
        if (newConfig.enabled()) {
            manager.start();
            if (newConfig.tempEnabled()) {
                overlay.start();
            }
            // 6) okamžitý refresh, aby se změny (vč. title u temp overlaye) hned projevily
            manager.updateAll();
        }
    }

    // ---------- veřejné API ----------

    /** Připojí hráči náš scoreboard a vykreslí (title + items). */
    public void show(Player p) {
        manager.show(p);
    }

    /** Vrátí hráče na main scoreboard. */
    public void hide(Player p) {
        manager.hide(p);
    }

    /** Okamžitá aktualizace pro všechny online hráče (title + items). */
    public void refreshAll() {
        manager.updateAll();
    }

    /** Alias pro jednotné volání z reloadAll(): okamžitě přepočti pro hráče (show = vynutí naše zobrazení). */
    public void applyOnce(Player p) {
        if (!config.enabled()) {
            // pokud je modul OFF, tak ho hráči raději skryjeme
            hide(p);
            return;
        }
        show(p);
    }

    /** Vyčistí stav pro hráče (přepne na main scoreboard). */
    public void clearFor(Player p) {
        hide(p);
    }

    // ---------- getters ----------

    public ScoreboardManager manager() { return manager; }
    public ScoreboardConfig config() { return config; }
    public TempBoardOverlay overlay() { return overlay; }
}
