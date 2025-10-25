package eu.Divish.tabSBS.papi;

import eu.Divish.tabSBS.lang.LangManager;
import eu.Divish.tabSBS.scoreboard.ScoreboardConfig;
import eu.Divish.tabSBS.util.Console;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ověřuje, že PlaceholderAPI umí vyhodnotit placeholdery použité v title/items scoreboardu.
 * Validace probíhá proti KONKRÉTNÍMU hráči (spusť po joinu prvního hráče).
 *
 * DŮLEŽITÉ:
 * - Validator normalizuje aliasy (%online% -> %server_online% atd.),
 *   aby hlášky odpovídaly skutečnému vyhodnocování v ScoreboardManageru.
 * - Loguje barevně do konzole přes Console utilitu.
 */
public final class PlaceholderValidator {

    private static final Pattern PAPI_TOKEN = Pattern.compile("%[^%\\s]+%");

    private final Plugin plugin;
    private final LangManager lang;
    private final Console cons;

    public PlaceholderValidator(Plugin plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.cons = new Console(plugin, false); // ⬅️ jen barevně, bez duplicit do loggeru
    }

    /**
     * Spusť validaci pro daného hráče a scoreboard konfiguraci.
     * Vrací true, pokud vše vypadá OK (žádné nevyřešené placeholdery).
     */
    public boolean validateFor(Player player, ScoreboardConfig cfg) {
        info(lang.get("startup.papi.scan.begin"));

        Set<String> all = new LinkedHashSet<>();
        // Title
        all.addAll(extract(cfg.titleRaw()));
        // Items
        for (String line : cfg.items()) {
            all.addAll(extract(line));
        }

        if (all.isEmpty()) {
            info(lang.get("startup.papi.scan.ok"));
            return true;
        }

        Map<String, String> unresolved = new LinkedHashMap<>();
        for (String token : all) {
            String normalized = normalizeAliases(token); // aliasy stejně jako v ScoreboardManageru
            String after;
            try {
                after = PlaceholderAPI.setPlaceholders(player, normalized);
            } catch (Throwable t) {
                after = normalized; // když PAPI selže, ber jako nevyhodnocené
            }
            if (isPlaceholderFormat(after)) {
                // reportuj původní token i výsledek (po aliasu)
                unresolved.put(token, after);
            }
        }

        if (unresolved.isEmpty()) {
            info(lang.get("startup.papi.scan.ok"));
            return true;
        }

        warn(lang.get("startup.papi.scan.warn_header"));
        for (Map.Entry<String, String> e : unresolved.entrySet()) {
            warn(lang.format("startup.papi.scan.unresolved_item",
                    "raw", e.getKey(),
                    "result", e.getValue()
            ));
        }
        warn(lang.get("startup.papi.scan.warn_footer"));
        return false;
    }
    /** Vrátí true, když PAPI vrátí jinou hodnotu než vstupní token. */
    public boolean testPlaceholderOn(Player p, String tokenWithPercents) {
        try {
            String out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, tokenWithPercents);
            return out != null && !out.equalsIgnoreCase(tokenWithPercents);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- helpers ----------

    private static Set<String> extract(String src) {
        Set<String> tokens = new LinkedHashSet<>();
        if (src == null || src.isEmpty()) return tokens;
        Matcher m = PAPI_TOKEN.matcher(src);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    private static boolean isPlaceholderFormat(String s) {
        if (s == null) return false;
        // Po vyhodnocení by správné placeholdery NEMĚLY zůstat obalené %...%
        return PAPI_TOKEN.matcher(s).find();
    }

    /**
     * Aliasování krátkých placeholderů na reálné PAPI:
     *  - %online%  -> %server_online%
     *  - %exp%     -> %player_total_exp%
     *  - %money%   -> %vault_eco_balance_formatted%
     *  - %kills%   -> %statistic_player_kills%
     *  - %deaths%  -> %statistic_deaths%
     */
    private static String normalizeAliases(String s) {
        if (s == null) return "";
        return s.replace("%online%", "%server_online%")
                .replace("%exp%", "%player_total_exp%")
                .replace("%money%", "%vault_eco_balance_formatted%")
                .replace("%kills%", "%statistic_player_kills%")
                .replace("%deaths%", "%statistic_deaths%");
    }

    // Barevné logování do konzole
    private void info(String msg) { cons.info(Console.ensureAmpersand(msg)); }
    private void warn(String msg) { cons.warn(Console.ensureAmpersand(msg)); }
}
