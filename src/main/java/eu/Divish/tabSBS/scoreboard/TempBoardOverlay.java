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

/**
 * Dočasný "TOP" overlay:
 *  - respektuje modules.scoreboard.temp_board (title, metric, color, count, intervals)
 *  - aliasuje metriky (%kills% → %statistic_player_kills% atd.)
 *  - zobrazení skutečně trvá nastavený čas (potlačí statickou tabulku přes suppressor)
 *  - overlay si čistí pouze své řádky (lastEntries), statickou tabulku nechává být
 *
 * Úpravy:
 *  - start() nyní neběží, pokud je modul scoreboardu celkově vypnutý (cfg.enabled()==false)
 *  - evalMetric() respektuje cfg.usePapi() a bezpečně vrací 0 bez PAPI
 *  - přidány helpery applyOnce(Player) a clearFor(Player) pro jednotné volání
 */
public final class TempBoardOverlay {

    private final Plugin plugin;
    private final ScoreboardConfig cfg;
    private final ScoreboardManager manager;

    private ScheduledTask loopTask = null;
    private boolean running = false;

    // uchováváme si poslední overlay řádky pro každého hráče
    private final Map<UUID, List<String>> lastEntries = new HashMap<>();

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    public TempBoardOverlay(Plugin plugin, ScoreboardConfig cfg, ScoreboardManager manager) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.manager = manager;
    }

    /** Spustí cyklus SHOW/HIDE podle configu. */
    public void start() {
        if (!cfg.enabled() || !cfg.tempEnabled() || running) return; // ← modul musí být celkově povolen
        running = true;

        final long showTicks = toTicksCeil(cfg.tempShow());
        final long hideTicks = toTicksCeil(cfg.tempHide());

        loopTask = Bukkit.getGlobalRegionScheduler().run(plugin, task -> cycle(showTicks, hideTicks));
    }

    /** Zastaví cyklus a okamžitě vrátí statický scoreboard. */
    public void stop() {
        if (!running) return;
        running = false;
        if (loopTask != null) {
            loopTask.cancel();
            loopTask = null;
        }
        // Při vypnutí overlaye ho tvrdě deaktivuj, zruš suppress a hned vrať statický
        for (Player p : Bukkit.getOnlinePlayers()) {
            deactivateOverlay(p);
            manager.clearSuppress(p);
            manager.refreshOne(p);
        }
        lastEntries.clear();
    }

    private void cycle(long showTicks, long hideTicks) {
        if (!running) return;

        // SHOW (všem online hráčům)
        for (Player p : Bukkit.getOnlinePlayers()) {
            showOverlayFor(p, showTicks);
        }

        // po showTicks → HIDE → po hideTicks znovu SHOW
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                deactivateOverlay(p);
                manager.clearSuppress(p);
                manager.refreshOne(p);  // okamžitě vrať statický sidebar
            }
            if (running) {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t2 -> cycle(showTicks, hideTicks), hideTicks);
            }
        }, showTicks);
    }

    private void showOverlayFor(Player viewer, long showTicks) {
        if (!cfg.tempEnabled()) return;

        manager.show(viewer); // zajisti vlastní scoreboard

        // potlač statický render po dobu zobrazení overlaye (+ malá rezerva)
        manager.suppressFor(viewer, showTicks * 50L + 50L);

        Scoreboard sb = viewer.getScoreboard();

        String titleRaw = cfg.tempTitleRaw();
        Component titleComp = LEGACY.deserialize(titleRaw);

        Objective obj = sb.getObjective("tabsbs_temp");
        if (obj == null) {
            try {
                obj = sb.registerNewObjective("tabsbs_temp", "dummy", titleComp);
            } catch (Throwable t) {
                obj = sb.registerNewObjective("tabsbs_temp", "dummy", translateAmpersand(titleRaw));
            }
        } else {
            try { obj.displayName(titleComp); }
            catch (Throwable t) { obj.setDisplayName(translateAmpersand(titleRaw)); }
        }
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Sestav TOP list
        String metricRaw = cfg.tempMetric();
        String metric = normalizeAliases(metricRaw); // aliasy na reálné PAPI metriky
        String valueColor = cfg.tempValueColorRaw();
        int topN = Math.min(15, Math.max(1, cfg.tempTopCount()));

        List<Row> rows = new ArrayList<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            double val = evalMetric(target, metric);
            rows.add(new Row(target.getUniqueId(), target.getName(), val));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> r.value).reversed());

        // vymažeme jen staré overlay řádky u tohoto hráče
        clearOverlayEntries(viewer);

        // zapíšeme nové overlay řádky
        int n = Math.min(topN, rows.size());
        int score = n;
        List<String> myNewEntries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            String line = String.format("&7#%d &f%s &8- %s%s",
                    (i + 1), r.name, valueColor, trimNumber(r.value));
            String legacy = translateAmpersand(line);
            legacy = ensureUnique(legacy, i);
            obj.getScore(legacy).setScore(score--);
            myNewEntries.add(legacy);
        }
        lastEntries.put(viewer.getUniqueId(), myNewEntries);
    }

    private void clearOverlayEntries(Player p) {
        Scoreboard sb = p.getScoreboard();
        List<String> old = lastEntries.remove(p.getUniqueId());
        if (old != null) {
            for (String e : old) sb.resetScores(e);
        }
    }

    private void deactivateOverlay(Player p) {
        Scoreboard sb = p.getScoreboard();
        // 1) sundat display slot, ať se sidebar okamžitě přepne
        Objective temp = sb.getObjective("tabsbs_temp");
        if (temp != null) {
            try { temp.setDisplaySlot(null); } catch (Throwable ignored) {}
        }
        // 2) smazat pouze naše staré řádky
        clearOverlayEntries(p);
        // 3) úplně odregistrovat temp objektiv, ať nic nezůstává viset
        if (temp != null) {
            try { temp.unregister(); } catch (Throwable ignored) {}
        }
    }

    /** Přečte metrický placeholder. Respektuje cfg.usePapi() a dostupnost PAPI. */
    private double evalMetric(Player p, String placeholderWithPercents) {
        if (!cfg.usePapi() || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return 0.0D;
        }
        try {
            String out = PlaceholderAPI.setPlaceholders(p, placeholderWithPercents);
            return parseDoubleSafe(out);
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    // ----- helpery pro jednotné volání zvenčí (nepovinné) -----

    /** Rychlé jednorázové promítnutí overlaye pro hráče (na délku aktuálního SHOW intervalu). */
    public void applyOnce(Player p) {
        if (!cfg.enabled() || !cfg.tempEnabled()) return;
        long showTicks = toTicksCeil(cfg.tempShow());
        showOverlayFor(p, showTicks);
    }

    /** Vyčistí overlay pro hráče (bez ovlivnění statického boardu). */
    public void clearFor(Player p) {
        deactivateOverlay(p);
        manager.clearSuppress(p);
        manager.refreshOne(p);
    }

    // ===== helpers =====

    /** Aliasování zkratek na reálné PAPI placeholdery. */
    private static String normalizeAliases(String s) {
        if (s == null) return "";
        return s.replace("%online%", "%server_online%")
                .replace("%exp%", "%player_total_exp%")
                .replace("%money%", "%vault_eco_balance_formatted%")
                .replace("%kills%", "%statistic_player_kills%")
                .replace("%deaths%", "%statistic_deaths%");
    }

    private static long toTicksCeil(java.time.Duration d) {
        long ticks = (long) Math.ceil(d.toMillis() / 50.0);
        return Math.max(1L, ticks);
    }

    private static String ensureUnique(String s, int salt) {
        int times = (salt % 3) + 1;
        return s + "§r".repeat(times);
    }

    private static String translateAmpersand(String text) {
        if (text == null) return "";
        char[] b = text.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) >= 0) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }

    private static String trimNumber(double val) {
        String s = Double.toString(val);
        if (s.endsWith(".0")) return s.substring(0, s.length() - 2);
        return s;
    }

    /** Robustní parsování čísla z PAPI (ignoruje valuty/znaky, sjednocuje , → .). */
    private static double parseDoubleSafe(String s) {
        if (s == null) return 0.0D;
        s = s.trim();
        if (s.isEmpty()) return 0.0D;
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException ignore) {
            StringBuilder sb = new StringBuilder();
            boolean dot = false;
            for (char c : s.toCharArray()) {
                if (c >= '0' && c <= '9') sb.append(c);
                else if ((c == '.' || c == ',') && !dot) { sb.append('.'); dot = true; }
                else if (c == '-' && sb.length() == 0) sb.append('-');
            }
            if (sb.length() == 0) return 0.0D;
            try { return Double.parseDouble(sb.toString()); }
            catch (Exception e) { return 0.0D; }
        }
    }

    private record Row(UUID id, String name, double value) {}
}
