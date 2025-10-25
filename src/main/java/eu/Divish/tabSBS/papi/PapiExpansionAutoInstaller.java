package eu.Divish.tabSBS.papi;

import eu.Divish.tabSBS.lang.LangManager;
import eu.Divish.tabSBS.scoreboard.ScoreboardConfig;
import eu.Divish.tabSBS.util.Console;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CZ:
 *  Automatický instalátor PlaceholderAPI expanzí podle placeholderů použitých ve Scoreboardu a Tablistu.
 *  Postup:
 *    1) Vyextrahuje %...% tokeny z:
 *       - scoreboard: title + items (aliasy: %online% → %server_online%, %money% → %vault_eco_balance_formatted%, ...)
 *       - tablist: header, footer, afk_display.placeholder, sorting.types[*].placeholder
 *    2) Odvodí potřebné expanze (vault, player, statistic, server, luckperms, essentials, ...).
 *    3) Zjistí, které chybí v /plugins/PlaceholderAPI/expansions/.
 *    4) Pro chybějící spustí sekvenční download přes /papi ecloud download <name>,
 *       nejdřív /papi ecloud refresh, po stažení všech /papi reload.
 *    5) Po reloadu ověří, zda vše funguje; pro „Server“ ještě navíc testne %server_online%.
 *
 * EN:
 *  Auto-installs PlaceholderAPI expansions needed by your Scoreboard and Tablist.
 *  Extracts tokens (with aliases normalized), figures out required expansions,
 *  downloads missing ones sequentially, reloads PAPI and verifies. Special handling for "Server".
 */
public final class PapiExpansionAutoInstaller {

    private static final Pattern PAPI_TOKEN = Pattern.compile("%([^%\\s]+)%");

    private final Plugin plugin;
    private final LangManager lang;
    private final Console cons;

    public PapiExpansionAutoInstaller(Plugin plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.cons = new Console(plugin, false);
    }

    /** Zkratka bez callbacku po reloadu. */
    public void ensureForScoreboard(ScoreboardConfig cfg) {
        ensureForScoreboard(cfg, null);
    }

    /**
     * Spusť auto-install pro daný ScoreboardConfig.
     * @param cfg           zdroj placeholderů (title + items)
     * @param onReloadDone  volitelný callback po dokončení /papi reload + verifikaci
     */
    public void ensureForScoreboard(ScoreboardConfig cfg, Runnable onReloadDone) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;

        // 1) Extract + alias normalize (Scoreboard)
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(extractAndNormalize(cfg.titleRaw()));
        for (String line : cfg.items()) tokens.addAll(extractAndNormalize(line));

        // 1b) Extract i z TABLIST konfigurace (header/footer, afk_display.placeholder, sorting.types[*].placeholder)
        tokens.addAll(collectTablistTokensFromConfig());

        // 2) Required expansions (preferred ids; "server" is special)
        Set<String> required = new LinkedHashSet<>();
        for (String inside : tokens) {
            String id = identify(inside);           // např. server_online → "server"; essentials_afk → "essentials"
            String name = mapIdToExpansionName(id); // mapování na eCloud název
            if (name != null) required.add(name);
        }
        if (required.isEmpty()) {
            info(lang.get("startup.papi.auto.no_required"));
            return;
        }

        // 3) Determine missing
        //    Pro „Server“ jsme robustní: pokud není ani "Server" ani "server", přidáme "Server"
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            if ("server".equalsIgnoreCase(name)) {
                if (!isExpansionInstalled("Server") && !isExpansionInstalled("server")) {
                    missing.add("Server"); // primární varianta, fallback přidáme níže
                }
            } else if (!isExpansionInstalled(name)) {
                missing.add(name);
            }
        }

        if (missing.isEmpty()) {
            info(lang.format("startup.papi.auto.all_present", "list", String.join(", ", required)));
            return;
        }

        // 3.5) Pokud je mezi missing "Server", přidej i lowercase fallback "server"
        if (missing.stream().anyMatch(s -> s.equals("Server"))) {
            missing.add("server");
        }

        // Hezký log bez duplicit už teď (pro info)
        info(lang.format("startup.papi.auto.downloading",
                "list", String.join(", ", new LinkedHashSet<>(missing))));

        ConsoleCommandSender console = Bukkit.getConsoleSender();

        // 4) Refresh eCloud index, pak stahuj 1 po druhé s rozestupem (není to blocking)
        dispatch(console, "papi ecloud refresh");

        downloadSequential(console, missing, 10, () -> {
            // 5) Reload až po stažení všech
            dispatch(console, "papi reload");

            // 6) ověř + callback (počkáme ~2s = 40 ticků)
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
                List<String> still = new ArrayList<>();
                for (String name : missing) {
                    if ("Server".equals(name) || "server".equals(name)) {
                        // Server je special-case: ověř přímo placeholder %server_online%
                        if (!serverPlaceholdersWork()) {
                            still.add(name);
                        }
                    } else if (!isExpansionInstalled(name)) {
                        still.add(name);
                    }
                }

                if (still.isEmpty()) {
                    // Sjednoť výpis (Server/server → Server)
                    Set<String> neatInstalled = new LinkedHashSet<>();
                    for (String s : missing) {
                        if (s.equalsIgnoreCase("server")) neatInstalled.add("Server");
                        else neatInstalled.add(s);
                    }
                    info(lang.format("startup.papi.auto.installed_ok",
                            "list", String.join(", ", neatInstalled)));
                } else {
                    // Sjednoť výpis chyb (Server/server → Server)
                    Set<String> neatPartial = new LinkedHashSet<>();
                    boolean serverListed = false;
                    for (String s : still) {
                        if (s.equalsIgnoreCase("server")) {
                            if (!serverListed) neatPartial.add("Server");
                            serverListed = true;
                        } else neatPartial.add(s);
                    }
                    warn(lang.format("startup.papi.auto.installed_partial",
                            "list", String.join(", ", neatPartial)));
                }
                if (onReloadDone != null) onReloadDone.run();
            }, 40L);
        });
    }

    // =====================================================================
    // Tablist scanning – header/footer, afk_display, sorting.types
    // =====================================================================

    /**
     * CZ: Nasbírá PAPI tokeny z Tablist části configu (header, footer, afk_display.placeholder,
     *     sorting.types[*].placeholder) a normalizuje aliasy.
     * EN: Collects PAPI tokens from Tablist config part and normalizes aliases.
     */
    private Set<String> collectTablistTokensFromConfig() {
        Set<String> out = new LinkedHashSet<>();

        ConfigurationSection tab = plugin.getConfig().getConfigurationSection("modules.tablist");
        if (tab == null) return out;

        // header/footer
        out.addAll(extractAndNormalize(tab.getString("header", "")));
        out.addAll(extractAndNormalize(tab.getString("footer", "")));

        // afk_display.placeholder
        ConfigurationSection afk = tab.getConfigurationSection("afk_display");
        if (afk != null) {
            out.addAll(extractAndNormalize(afk.getString("placeholder", "")));
        }

        // sorting.types[*].placeholder (pro PAPI_STRING / PAPI_NUMBER)
        ConfigurationSection sorting = tab.getConfigurationSection("sorting");
        if (sorting != null) {
            List<Map<?, ?>> types = sorting.getMapList("types");
            if (types != null) {
                for (Map<?, ?> m : types) {
                    Object ph = m.get("placeholder");
                    if (ph instanceof String s) {
                        out.addAll(extractAndNormalize(s));
                    }
                }
            }
        }
        return out;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Stáhne expanze sekvenčně (po jedné), s rozestupem everyTicks. */
    private void downloadSequential(ConsoleCommandSender console, List<String> names, int everyTicks, Runnable onDone) {
        if (names.isEmpty()) { onDone.run(); return; }
        // Odstraň duplicity, ale zachovej pořadí (LinkedHashSet)
        List<String> queue = new ArrayList<>(new LinkedHashSet<>(names));
        downloadNext(console, queue, everyTicks, onDone);
    }

    /** Rekurzivní stáhnutí fronty expanzí. */
    private void downloadNext(ConsoleCommandSender console, List<String> queue, int everyTicks, Runnable onDone) {
        if (queue.isEmpty()) { onDone.run(); return; }
        String name = queue.remove(0);
        dispatch(console, "papi ecloud download " + name);
        // další stáhnout za pár ticků (ať má PAPI čas)
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> downloadNext(console, queue, everyTicks, onDone), everyTicks);
    }

    /**
     * Extract + alias normalize → vrací INSIDE (bez procent),
     * např. "Text %online% →" dá "server_online".
     */
    private static Set<String> extractAndNormalize(String src) {
        Set<String> out = new LinkedHashSet<>();
        if (src == null || src.isEmpty()) return out;
        Matcher m = PAPI_TOKEN.matcher(src);
        while (m.find()) {
            String full = m.group();           // např. "%online%"
            String norm = normalizeAliases(full);
            if (norm.length() >= 3 && norm.startsWith("%") && norm.endsWith("%")) {
                out.add(norm.substring(1, norm.length() - 1)); // bez %...%
            }
        }
        return out;
    }

    /** Získá "prefix" placeholderu (server_online → "server"). */
    private static String identify(String tokenInside) {
        if (tokenInside == null || tokenInside.isBlank()) return null;
        int idx = tokenInside.indexOf('_');
        if (idx <= 0) return tokenInside.toLowerCase(Locale.ROOT);
        return tokenInside.substring(0, idx).toLowerCase(Locale.ROOT);
    }

    /**
     * Mapování id → eCloud expansion name (case podle eCloudu).
     * - „Server“ se chová různě napříč verzemi → primárně zkusíme "Server",
     *   fallback na "server" řešíme ve frontě (viz ensureForScoreboard).
     * - Přidáno: Essentials (AFK atd.).
     */
    private static String mapIdToExpansionName(String id) {
        if (id == null) return null;
        return switch (id) {
            case "vault" -> "vault";
            case "player" -> "player";
            case "statistic", "stats", "stat" -> "statistic";
            case "server" -> "Server"; // primárně "Server"
            case "luckperms", "lp" -> "luckperms";
            case "essentials" -> "Essentials"; // ✅ Essentials AFK apod.
            default -> null;
        };
    }

    /** Je expanze fyzicky v /plugins/PlaceholderAPI/expansions/? */
    private boolean isExpansionInstalled(String name) {
        File dir = new File(plugin.getDataFolder().getParentFile(), "PlaceholderAPI/expansions");
        if (!dir.isDirectory()) return false;
        File[] list = dir.listFiles();
        if (list == null) return false;
        String needle = name.toLowerCase(Locale.ROOT);
        for (File f : list) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".jar") && (n.contains("expansion-" + needle) || n.contains(needle))) return true;
        }
        return false;
    }

    /** Aliasování krátkých placeholderů na reálné PAPI (stejně jako ScoreboardManager). */
    private static String normalizeAliases(String s) {
        if (s == null) return "";
        return s.replace("%online%", "%server_online%")
                .replace("%exp%", "%player_total_exp%")
                .replace("%money%", "%vault_eco_balance_formatted%")
                .replace("%kills%", "%statistic_player_kills%")
                .replace("%deaths%", "%statistic_deaths%");
    }

    /** Proveď příkaz v konzoli. */
    private void dispatch(ConsoleCommandSender console, String cmd) {
        Bukkit.dispatchCommand(console, cmd);
    }

    /**
     * Otestuj, zda server placeholdery fungují (např. %server_online% vrací číslo).
     * Používá online hráče; když žádný, zkusí OfflinePlayer (ne všechny PL ale podporují).
     */
    private boolean serverPlaceholdersWork() {
        Player any = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (any == null) {
            // fallback: zkusíme přes OfflinePlayer, některé server placeholdery to zvládnou
            OfflinePlayer off = Bukkit.getOfflinePlayer(UUID.randomUUID());
            try {
                String out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(off, "%server_online%");
                return out != null && out.matches("\\d+");
            } catch (Throwable ignored) {
                return false;
            }
        }
        try {
            String out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(any, "%server_online%");
            return out != null && out.matches("\\d+");
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =====================================================================
    // Logging (barevně)
    // =====================================================================

    private void info(String msg) { cons.info(Console.ensureAmpersand(msg)); }
    private void warn(String msg) { cons.warn(Console.ensureAmpersand(msg)); }

    // =====================================================================
    // Doplňkové: sanity test jednoho placeholderu
    // =====================================================================

    /** Vrátí true, když PAPI vrátí jinou hodnotu než vstupní token. */
    public boolean testPlaceholderOn(Player p, String tokenWithPercents) {
        try {
            String out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, tokenWithPercents);
            return out != null && !out.equalsIgnoreCase(tokenWithPercents);
        } catch (Throwable t) { return false; }
    }
}
