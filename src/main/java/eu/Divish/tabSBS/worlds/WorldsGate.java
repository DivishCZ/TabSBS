package eu.Divish.tabSBS.worlds;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Řídí, zda je Scoreboard v daném světě povolen.
 * Čte z configu:
 * worlds.disabled.as_whitelist (boolean)
 * worlds.disabled.list (List<String>)
 */
public final class WorldsGate {
    private final Plugin plugin;

    private boolean asWhitelist = false;     // false = blacklist (disabled in list), true = whitelist (enabled only in list)
    private final Set<String> worldNames = new HashSet<>(); // lowercase

    public WorldsGate(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        worldNames.clear();

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("worlds.disabled");
        if (sec != null) {
            this.asWhitelist = sec.getBoolean("as_whitelist", false);
            var list = sec.getStringList("list");
            if (list != null) {
                for (String w : list) {
                    if (w != null && !w.isBlank()) {
                        worldNames.add(w.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } else {
            this.asWhitelist = false;
        }
    }

    /** Je scoreboard povolen v tomto světě? */
    public boolean isScoreboardAllowedIn(World world) {
        if (world == null) return true; // bez světa nic neblokuj
        return isScoreboardAllowedIn(world.getName());
    }

    /** Je scoreboard povolen ve světě s tímto názvem? */
    public boolean isScoreboardAllowedIn(String worldName) {
        if (worldName == null) return true;
        String key = worldName.toLowerCase(Locale.ROOT);

        if (!asWhitelist) {
            // BLACKLIST režim: pokud je svět v seznamu, scoreboard je zakázán
            return !worldNames.contains(key);
        } else {
            // WHITELIST režim: scoreboard je povolen jen ve světech v seznamu
            return worldNames.contains(key);
        }
    }
}
