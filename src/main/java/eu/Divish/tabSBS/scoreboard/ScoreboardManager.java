package eu.Divish.tabSBS.scoreboard;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sidebar scoreboard (pouze & barvy + PAPI, aliasy placeholderů).
 *
 * Klíčové:
 *  - Čistí jen naše poslední řádky (lastEntries) → nepere se s overlayem.
 *  - Má potlačovač (suppressUntilMs): když běží overlay, neren­deruje statickou tabulku.
 *  - Aliasování děláme PŘED voláním PAPI (normalizeAliases → applyPapi).
 *
 * Úpravy:
 *  - přidány helpery applyOnce(Player) a clearFor(Player) pro jednotné volání z reloadAll()
 */
public final class ScoreboardManager {

    private final Plugin plugin;
    private ScoreboardConfig cfg;

    // Per-player vlastní board
    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    // Per-player poslední NAŠE zapsané řádky (abychom je mohli bezpečně smazat)
    private final Map<UUID, List<String>> lastEntries = new ConcurrentHashMap<>();
    // Per-player: dokdy potlačit render statické tabulky (kvůli overlayi)
    private final Map<UUID, Long> suppressUntilMs = new ConcurrentHashMap<>();

    // plánovač
    private ScheduledTask updateTask = null;
    private boolean running = false;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    public ScoreboardManager(Plugin plugin, ScoreboardConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    // ===== lifecycle =====

    /** Spustí periodický refresh všech online hráčů. */
    public void start() {
        if (!cfg.enabled() || running) return;
        running = true;
        long ticks = toTicksCeil(cfg.updatePeriod());
        updateTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                t -> updateAll(),
                ticks, ticks
        );
    }

    /** Zastaví refresh loop (neodebírá hráčům scoreboard). */
    public void stop() {
        if (!running) return;
        running = false;
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    /** Aplikuj novou konfiguraci po /reload. */
    public void applyNewConfig(ScoreboardConfig newCfg) {
        this.cfg = newCfg;
        // Další kroky (okamžitý update) řeší volající (ScoreboardRuntime.start() → updateAll()).
    }

    // ===== veřejné API =====

    /** Připojí a vykreslí scoreboard hráči. */
    public void show(Player p) {
        if (!cfg.enabled()) return;
        Scoreboard sb = boards.computeIfAbsent(p.getUniqueId(), id -> Bukkit.getScoreboardManager().getNewScoreboard());
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
        updateOne(p);
    }

    /** Skryje scoreboard (vrátí hráče na main scoreboard). */
    public void hide(Player p) {
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        boards.remove(p.getUniqueId());
        lastEntries.remove(p.getUniqueId());
        suppressUntilMs.remove(p.getUniqueId());
    }

    /** Okamžitě přerenderuje všechny online hráče (title + items). */
    public void refreshAll() { updateAll(); }

    /** Okamžitě přerenderuje jednoho hráče (title + items). */
    public void refreshOne(Player p) { updateOne(p); }

    /** Alias pro jednotné volání z reloadAll(): pokud je povoleno, zobraz a přerenderuj; jinak skryj. */
    public void applyOnce(Player p) {
        if (cfg.enabled()) show(p); else hide(p);
    }

    /** Vyčistí stav pro hráče (bez ohledu na config). */
    public void clearFor(Player p) { hide(p); }

    /** Aktualizuje všechny online hráče. */
    public void updateAll() {
        if (!cfg.enabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) updateOne(p);
    }

    /** Aktualizuje jednoho hráče (title + items). */
    public void updateOne(Player p) {
        if (!cfg.enabled()) return;

        // Pokud právě běží overlay → na tuto chvíli NERENDERUJ statickou tabulku
        long now = System.currentTimeMillis();
        Long until = suppressUntilMs.get(p.getUniqueId());
        if (until != null && until > now) return;

        Scoreboard sb = boards.computeIfAbsent(p.getUniqueId(), id -> Bukkit.getScoreboardManager().getNewScoreboard());

        // Title (správné pořadí: aliasy → PAPI → &barvy)
        String titleRaw = applyPapi(p, normalizeAliases(cfg.titleRaw()));
        Component titleComp = LEGACY.deserialize(titleRaw);

        Objective obj = sb.getObjective("tabsbs");
        if (obj == null) {
            try {
                obj = sb.registerNewObjective("tabsbs", "dummy", titleComp);
            } catch (Throwable t) {
                obj = sb.registerNewObjective("tabsbs", "dummy", ChatColorLike.translateAmpersandColorCodes(titleRaw));
            }
        } else {
            try { obj.displayName(titleComp); }
            catch (Throwable t) { obj.setDisplayName(ChatColorLike.translateAmpersandColorCodes(titleRaw)); }
        }
        // VŽDY přepni SIDEBAR na statický objektiv (vrací nás zpět ze všech overlayů)
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Smaž POUZE naše staré řádky pro tohoto hráče
        List<String> old = lastEntries.get(p.getUniqueId());
        if (old != null) for (String e : old) sb.resetScores(e);

        // Zapiš nové řádky (max 15)
        List<String> rawLines = cfg.items();
        int n = Math.min(15, rawLines.size());
        int score = n;

        List<String> myNewEntries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // aliasy → PAPI → & barvy
            String prepared = applyPapi(p, normalizeAliases(rawLines.get(i)));
            String legacy = ChatColorLike.translateAmpersandColorCodes(prepared);

            // unikátnost entry (scoreboard vyžaduje unikátní texty)
            legacy = ensureUnique(legacy, i);

            obj.getScore(legacy).setScore(score--);
            myNewEntries.add(legacy);
        }
        lastEntries.put(p.getUniqueId(), myNewEntries);
    }

    // ==== API pro overlay: potlač rendr statického boardu na X milisekund ====
    public void suppressFor(Player p, long millis) {
        suppressUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + Math.max(1L, millis));
    }

    /** Zruší potlačení renderu pro daného hráče – použij při HIDE overlaye před refreshOne(). */
    public void clearSuppress(Player p) {
        suppressUntilMs.remove(p.getUniqueId());
    }

    // ===== pomocné =====

    private String applyPapi(Player p, String text) {
        if (text == null) return "";
        if (cfg.usePapi() && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                return PlaceholderAPI.setPlaceholders(p, text);
            } catch (Throwable ignored) {}
        }
        return text;
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

    private static String ensureUnique(String s, int salt) {
        int times = (salt % 3) + 1;
        return s + "§r".repeat(times);
    }

    private static long toTicksCeil(java.time.Duration d) {
        long ticks = (long) Math.ceil(d.toMillis() / 50.0);
        return Math.max(1L, ticks);
    }

    /** Minimal náhrada za ChatColor.translateAlternateColorCodes('&', ...) bez importu ChatColor. */
    private static final class ChatColorLike {
        static String translateAmpersandColorCodes(String text) {
            if (text == null) return "";
            char[] b = text.toCharArray();
            for (int i = 0; i < b.length - 1; i++) {
                if (b[i] == '&' && isColorChar(b[i + 1])) {
                    b[i] = '§';
                    b[i + 1] = Character.toLowerCase(b[i + 1]);
                }
            }
            return new String(b);
        }
        private static boolean isColorChar(char c) {
            return "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(c) >= 0;
        }
    }
}
