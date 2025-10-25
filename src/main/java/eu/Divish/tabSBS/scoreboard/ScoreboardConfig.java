package eu.Divish.tabSBS.scoreboard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Načítá a drží konfiguraci pro sekci: modules.scoreboard
 * Všechny časy jsou čteny v sekundách (podporuje i "1.2s") a ukládají se jako Duration.
 */
public final class ScoreboardConfig {

    // ZÁKLAD
    private final boolean enabled;
    private final String titleRaw;
    private final Duration updatePeriod;

    // ITEMS
    private final List<String> items;

    // INTEGRACE
    private final boolean usePapi;
    private final boolean useVaultEco;
    private final boolean useVaultChat;

    // TRACK STATS
    private final boolean trackStatsEnabled;

    // TEMP BOARD
    private final boolean tempEnabled;
    private final String tempTitleRaw;
    private final String tempMetric;
    private final String tempValueColorRaw;
    private final int tempTopCount;
    private final Duration tempShow;
    private final Duration tempHide;

    public ScoreboardConfig(Plugin plugin) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("modules.scoreboard");
        if (root == null) {
            throw new IllegalStateException("Missing config section: modules.scoreboard");
        }

        // --- ZÁKLAD ---
        this.enabled = root.getBoolean("enabled", true);
        this.titleRaw = root.getString("title", "&b&lScoreboard");
        this.updatePeriod = parseSecondsToDuration(root.get("update_delay_seconds"), 2.0);

        // --- ITEMS ---
        List<String> it = root.getStringList("items");
        this.items = (it == null) ? Collections.emptyList() : it;

        // --- INTEGRACE ---
        ConfigurationSection integ = root.getConfigurationSection("integrations");
        this.usePapi      = integ == null || integ.getBoolean("use_placeholderapi", true);
        this.useVaultEco  = integ != null && integ.getBoolean("use_vault_economy", true);
        this.useVaultChat = integ != null && integ.getBoolean("use_vault_chat", true);

        // --- TRACK STATS ---
        ConfigurationSection track = root.getConfigurationSection("track_stats");
        this.trackStatsEnabled = track != null && track.getBoolean("enabled", true);

        // --- TEMP BOARD ---
        ConfigurationSection temp = root.getConfigurationSection("temp_board");
        this.tempEnabled       = temp != null && temp.getBoolean("enabled", true);
        this.tempTitleRaw      = temp != null ? temp.getString("title", "&a&lTop") : "&a&lTop";
        this.tempMetric        = temp != null ? temp.getString("metric", "%kills%") : "%kills%";
        this.tempValueColorRaw = temp != null ? temp.getString("value_color", "&6") : "&6";
        this.tempTopCount      = temp != null ? temp.getInt("top_count", 5) : 5;
        this.tempShow          = parseSecondsToDuration(temp != null ? temp.get("interval_show_seconds") : null, 20.0);
        this.tempHide          = parseSecondsToDuration(temp != null ? temp.get("interval_hide_seconds") : null, 10.0);
    }

    // ----------------- Gettery -----------------
    public boolean enabled() { return enabled; }
    public String titleRaw() { return titleRaw; }
    public Duration updatePeriod() { return updatePeriod; }

    public List<String> items() { return items; }

    public boolean usePapi() { return usePapi; }
    public boolean useVaultEco() { return useVaultEco; }
    public boolean useVaultChat() { return useVaultChat; }

    public boolean trackStatsEnabled() { return trackStatsEnabled; }

    public boolean tempEnabled() { return tempEnabled; }
    public String tempTitleRaw() { return tempTitleRaw; }
    public String tempMetric() { return tempMetric; }
    public String tempValueColorRaw() { return tempValueColorRaw; }
    public int tempTopCount() { return tempTopCount; }
    public Duration tempShow() { return tempShow; }
    public Duration tempHide() { return tempHide; }

    // ----------------- Helpers -----------------
    /**
     * Přijme hodnotu z configu a vrátí Duration (sekundy, podporuje i "1.2s"). Zaokrouhluje nahoru na ms.
     */
    private static Duration parseSecondsToDuration(Object raw, double defSeconds) {
        double secs = defSeconds;
        if (raw instanceof Number n) {
            secs = n.doubleValue();
        } else if (raw instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.endsWith("s")) v = v.substring(0, v.length() - 1);
            try { secs = Double.parseDouble(v); } catch (NumberFormatException ignored) { /* use default */ }
        }
        long ms = (long) Math.ceil(secs * 1000.0);
        if (ms < 50) ms = 50; // bezpečné minimum
        return Duration.ofMillis(ms);
    }
}
