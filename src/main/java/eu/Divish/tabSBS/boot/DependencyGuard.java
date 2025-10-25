package eu.Divish.tabSBS.boot;

import eu.Divish.tabSBS.lang.LangManager;
import eu.Divish.tabSBS.util.Console;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Tvrdá kontrola závislostí při startu:
 *  - PlaceholderAPI MUSÍ být přítomen a povolen
 *  - Vault MUSÍ být přítomen a povolen
 *
 * Použití v onEnable():
 *   LangManager lang = ...; // už načtený dle configu
 *   if (!DependencyGuard.ensureHardDependencies(this, lang)) return; // ukonči onEnable
 */
public final class DependencyGuard {

    private DependencyGuard() {
    }

    public static boolean ensureHardDependencies(Plugin plugin, LangManager lang) {
        PluginManager pm = Bukkit.getPluginManager();

        // Barevně do konzole; do Java loggeru tentokrát NE (kvůli duplicitám)
        Console cons = new Console(plugin, false);

        // PlaceholderAPI
        Plugin papi = pm.getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            bannerError(cons, lang.get("startup.deps.missing_papi"), lang.get("startup.deps.install_hint"));
            pm.disablePlugin(plugin);
            return false;
        }

        // Vault
        Plugin vault = pm.getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            bannerError(cons, lang.get("startup.deps.missing_vault"), lang.get("startup.deps.install_hint"));
            pm.disablePlugin(plugin);
            return false;
        }

        // OK zpráva (tuhle klidně loguj normálně jinde přes Console s alsoLogToJava=true)
        cons.info(lang.get("startup.deps.ok"));
        return true;
    }

    /**
     * Vypíše chybovou hlášku do „rámečku“ (barevně přes Console) + doplňkový hint.
     */
// v eu.Divish.tabSBS.boot.DependencyGuard
    private static void bannerError(Console cons, String mainLine, String hintLine) {
        // červený rámeček
        cons.raw("&c====================================================");

        // hlavní chybová věta – pokud v langu není barva, vynutíme &c
        String msg = Console.ensureAmpersand(mainLine);
        if (!msg.matches("(?i).*&[0-9A-FK-OR].*")) msg = "&c" + msg;
        cons.raw(msg);

        cons.raw("&c====================================================");

        // hint: VŽDY žlutě (i když je v langu jiná / žádná barva)
        String hintRaw = Console.ensureAmpersand(hintLine)
                .replace('&', '§')
                .replaceAll("§x(§[0-9A-Fa-f]){6}", "")     // hex barvy pryč
                .replaceAll("§[0-9A-FK-ORa-fk-or]", "");    // legacy barvy pryč
        cons.raw("&e" + hintRaw);

        cons.raw("&c====================================================");
    }
}
