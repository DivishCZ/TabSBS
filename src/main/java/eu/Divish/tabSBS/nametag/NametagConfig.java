package eu.Divish.tabSBS.nametag;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Locale;

/**
 * CZ: Konfigurace pro modul Nametag (modules.nametag.*).
 * EN: Config holder for Nametag module.
 */
public final class NametagConfig {

    private final boolean enabled;
    private final String source;

    private final String colorMode;   // auto_from_prefix | force | none
    private final String colorForce;  // "&7" etc.

    private final boolean applyAfk;

    private final int maxPrefixChars;
    private final int maxSuffixChars;

    private final String nameTagVisibility; // ALWAYS | NEVER | HIDE_FOR_OTHER_TEAMS | HIDE_FOR_OWN_TEAM

    private final Duration updatePeriod; // periodický refresh

    public NametagConfig(Plugin plugin) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("modules.nametag");
        if (root == null) {
            // když sekce chybí, ber jako vypnuto
            this.enabled = false;
            this.source = "vault_chat";
            this.colorMode = "auto_from_prefix";
            this.colorForce = "&7";
            this.applyAfk = true;
            this.maxPrefixChars = 32;
            this.maxSuffixChars = 32;
            this.nameTagVisibility = "ALWAYS";
            this.updatePeriod = Duration.ofMillis(1000);
            return;
        }

        this.enabled = root.getBoolean("enabled", true);
        this.source  = root.getString("source", "vault_chat");

        ConfigurationSection color = root.getConfigurationSection("color");
        this.colorMode  = (color != null ? color.getString("mode", "auto_from_prefix") : "auto_from_prefix");
        this.colorForce = (color != null ? color.getString("force", "&7") : "&7");

        this.applyAfk = root.getBoolean("apply_afk", true);

        this.maxPrefixChars = Math.max(0, root.getInt("max_prefix_chars", 32));
        this.maxSuffixChars = Math.max(0, root.getInt("max_suffix_chars", 32));

        this.nameTagVisibility = root.getString("name_tag_visibility", "ALWAYS");

        // update period: preferuj modules.nametag.update_delay_seconds, jinak tablist.update_delay_seconds, jinak 1.0
        double secs = 1.0;
        if (root.isSet("update_delay_seconds")) {
            secs = parseDouble(root.get("update_delay_seconds"), 1.0);
        } else {
            Object tabSecs = plugin.getConfig().get("modules.tablist.update_delay_seconds");
            secs = parseDouble(tabSecs, 1.0);
        }
        long ms = (long) Math.ceil(Math.max(0.05, secs) * 1000.0);
        this.updatePeriod = Duration.ofMillis(ms);
    }

    private static double parseDouble(Object raw, double def) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.endsWith("s")) v = v.substring(0, v.length()-1);
            try { return Double.parseDouble(v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    // getters
    public boolean enabled() { return enabled; }
    public String source() { return source; }
    public String colorMode() { return colorMode; }
    public String colorForce() { return colorForce; }
    public boolean applyAfk() { return applyAfk; }
    public int maxPrefixChars() { return maxPrefixChars; }
    public int maxSuffixChars() { return maxSuffixChars; }
    public String nameTagVisibility() { return nameTagVisibility; }
    public Duration updatePeriod() { return updatePeriod; }
}
