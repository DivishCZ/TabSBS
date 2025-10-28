package eu.Divish.tabSBS.nametag;

import eu.Divish.tabSBS.worlds.WorldsGate;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.util.*;

/**
 * CZ: Samostatný modul pro NameTagy nad hlavami.
 * - Sorting zapnutý: dekoruje existující "ts..." týmy (každý hráč má svůj team).
 * - Sorting vypnutý: vytvoří per-player "nt_<uuid>" tým na AKTUÁLNÍM scoreboardu hráče (bez přepínání boardu).
 * - Respektuje AFK overlay (suffix/recolor/both) z modules.tablist.afk_display.* pokud nametag.apply_afk = true.
 * - Barva jména dle color.mode (auto_from_prefix | force | none).
 *
 * EN: Independent nametag module. Never reassigns scoreboards; it decorates
 *     teams on the viewer's current scoreboard, so it plays nicely with sorting and sidebar.
 *
 * Úpravy:
 * - clearFor() čistí i když je modul vypnutý a nově odstraňuje dekorace i z "ts..." týmů.
 * - stop()/applyNewConfig(): při vypnutí okamžitý úklid (clearAll), při zapnutí okamžitá aplikace.
 * - Folia-safe plánovač přes GlobalRegionScheduler.
 * - ✅ Nově: naše per-player NT týmy mají „vanilla-like defaulty“
 *   (kolize zapnuté, friendly fire povolený, death messages & name tags viditelné, žádné „see friendly invisibles“),
 *   aby šlo strkat do mobů, dávat je do lodí atd.
 */
public final class NametagService {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static final String SORT_TEAM_PREFIX = "ts";      // z TabSortingService
    private static final String OWN_TEAM_PREFIX  = "nt_";     // naše per-player týmy když sorting není
    private static final String MARKER_OLD       = "tabsbs";  // legacy čistění, just in case

    private final Plugin plugin;
    private NametagConfig cfg;
    private final WorldsGate worldsGate; // může být null
    private final Permission perms;      // může být null
    private final Chat chat;             // může být null

    private io.papermc.paper.threadedregions.scheduler.ScheduledTask loop = null;
    private boolean running = false;

    public NametagService(Plugin plugin, NametagConfig cfg, WorldsGate worldsGate,
                          Permission perms, Chat chat) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.worldsGate = worldsGate;
        this.perms = perms;
        this.chat = chat;
    }

    // ---------- lifecycle ----------

    public void start() {
        if (running || !cfg.enabled()) return;
        running = true;
        long ticks = toTicksCeil(cfg.updatePeriod());
        loop = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                t -> applyAll(),
                ticks, ticks
        );
        // okamžitá aplikace (bez čekání na první tick)
        applyAll();
    }

    public void stop() {
        if (!running) {
            // i když neběží, při vypnutí chceme uklidit
            clearAll();
            return;
        }
        running = false;
        if (loop != null) { loop.cancel(); loop = null; }
        clearAll();
    }

    public void applyNewConfig(NametagConfig newCfg) {
        boolean wasRunning = this.running;
        boolean wasEnabled = (this.cfg != null && this.cfg.enabled());
        this.cfg = newCfg;

        boolean nowEnabled = this.cfg.enabled();

        if (wasEnabled && !nowEnabled) {
            stop(); // zajistí clearAll()
        } else if (!wasEnabled && nowEnabled) {
            start();
        } else {
            // enabled se nezměnil
            if (nowEnabled && wasRunning) {
                applyAll(); // refresh podle nového nastavení (barva/AFK/visibility atd.)
            } else if (!nowEnabled) {
                clearAll();
            }
        }
    }

    /** Uklidí target hráče ze všech našich stop + odstraní dekorace i z "ts..." týmu. */
    public void clearFor(Player target) {
        if (target == null) return;
        String name = target.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = viewer.getScoreboard();

            // 1) z našich per-player/legacy týmů
            for (Team t : sb.getTeams()) {
                String tn = safe(t.getName());
                if (tn.startsWith(OWN_TEAM_PREFIX) || tn.startsWith(MARKER_OLD)) {
                    try { t.removeEntry(name); } catch (Throwable ignored) {}
                    try { if (t.getSize() <= 0) t.unregister(); } catch (Throwable ignored) {}
                }
            }

            // 2) z "ts..." týmu pouze odstraníme dekorace (prefix, suffix, barva, visibility)
            Team ts = findTeamContaining(sb, name, SORT_TEAM_PREFIX);
            if (ts != null) {
                try { ts.prefix(LEGACY.deserialize("")); } catch (Throwable ignored) { try { ts.setPrefix(""); } catch (Throwable ignored2) {} }
                try { ts.suffix(LEGACY.deserialize("")); } catch (Throwable ignored) { try { ts.setSuffix(""); } catch (Throwable ignored2) {} }
                try { ts.color(NamedTextColor.WHITE); } catch (Throwable ignored) { try { ts.setColor(ChatColor.WHITE); } catch (Throwable ignored2) {} }
                try { ts.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS); } catch (Throwable ignored) {}
            }
        }
    }

    /** Uklidí všechny hráče (voláno při stop()/disable). */
    public void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearFor(p);
        }
    }

    // ---------- core ----------

    /** Pro každý VIEWER (jeho aktuální scoreboard) nasadíme per-player nametag. */
    public void applyAll() {
        if (!cfg.enabled()) return;

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!isAllowedHere(viewer)) continue;

            Scoreboard sb = viewer.getScoreboard();
            for (Player target : Bukkit.getOnlinePlayers()) {
                applyNametagFor(sb, target);
            }
        }
    }

    /** Public per-player refresh: nasadí targetovi NT na všech viewerech. */
    public void applyFor(Player target) {
        if (!cfg.enabled() || target == null) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!isAllowedHere(viewer)) continue;
            Scoreboard sb = viewer.getScoreboard();
            applyNametagFor(sb, target);
        }
    }

    /** Nasadí nametag pro konkrétního hráče na daný scoreboard. */
    private void applyNametagFor(Scoreboard sb, Player target) {
        // 1) pokus se najít "ts..." tým obsahující playera (sorting zapnutý)
        Team team = findTeamContaining(sb, target.getName(), SORT_TEAM_PREFIX);

        // 2) pokud není "ts..." tým, použij / vytvoř náš vlastní "nt_<uuid>"
        if (team == null) {
            team = ensureOwnTeam(sb, target);
        }

        // 3) nastav prefix/suffix + barvu + visibility
        decorateTeamFor(team, target);
    }

    /**
     * Vytvoří (nebo vrátí) náš per-player tým a nastaví „vanilla-like“ defaulty:
     * - kolize: ALWAYS (jde strkat do mobů, lodě apod.)
     * - jmenovky: ALWAYS
     * - death message: ALWAYS
     * - friendly fire: true (respektuje serverové pvp nastavení)
     * - see friendly invisibles: false
     */
    private Team ensureOwnTeam(Scoreboard sb, Player target) {
        String base = OWN_TEAM_PREFIX + shortUuid(target.getUniqueId()); // nt_ab12cd34
        Team t = sb.getTeam(base);
        if (t == null) {
            t = sb.registerNewTeam(base);
        }

        // ✅ Vanilla defaulty aplikuj vždy (idempotentní, klidně opakovaně):
        applyVanillaTeamDefaults(t);

        // ujisti se, že hráč je jen v TOMTO našem týmu (odstraníme z jiných nt_/legacy)
        for (Team other : sb.getTeams()) {
            if (other == t) continue;
            String n = safe(other.getName());
            if (n.startsWith(OWN_TEAM_PREFIX) || n.startsWith(MARKER_OLD)) {
                try { other.removeEntry(target.getName()); } catch (Throwable ignored) {}
            }
        }
        if (!t.hasEntry(target.getName())) {
            try { t.addEntry(target.getName()); } catch (Throwable ignored) {}
        }
        return t;
    }

    /** Vanilla-like defaults pro naše NT týmy. */
    private static void applyVanillaTeamDefaults(Team team) {
        try { team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.ALWAYS); } catch (Throwable ignored) {}
        try { team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS); } catch (Throwable ignored) {}
        try { team.setOption(Team.Option.DEATH_MESSAGE_VISIBILITY, Team.OptionStatus.ALWAYS); } catch (Throwable ignored) {}
        try { team.setAllowFriendlyFire(true); } catch (Throwable ignored) {}
        try { team.setCanSeeFriendlyInvisibles(false); } catch (Throwable ignored) {}
    }

    private Team findTeamContaining(Scoreboard sb, String entry, String mustStartWith) {
        for (Team t : sb.getTeams()) {
            String n = safe(t.getName());
            if (!n.startsWith(mustStartWith)) continue;
            if (t.hasEntry(entry)) return t;
        }
        return null;
    }

    private void decorateTeamFor(Team t, Player p) {
        // ----- 1) získat prefix/suffix z Vault Chat -----
        String px = (chat != null) ? safe(chat.getPlayerPrefix(p)) : "";
        String sx = (chat != null) ? safe(chat.getPlayerSuffix(p)) : "";

        // ----- 2) ořez délky -----
        px = cutTo(px, cfg.maxPrefixChars());
        sx = cutTo(sx, cfg.maxSuffixChars());

        // ----- 3) AFK overlay (volitelně) -----
        if (cfg.applyAfk() && afkEnabled()) {
            boolean afk = isAfkByPapi(p);
            if (afk) {
                String mode   = getAfkMode();
                String suffix = getAfkSuffix();
                String recol  = getAfkRecolor();
                boolean keep  = keepAfkFormats();

                switch (mode) {
                    case "recolor" -> {
                        px = recolorLegacy(px, recol, keep);
                        sx = recolorLegacy(sx, recol, keep);
                    }
                    case "both" -> {
                        px = recolorLegacy(px, recol, keep);
                        sx = recolorLegacy(sx, recol, keep) + suffix;
                    }
                    default -> { // "suffix"
                        sx = sx + suffix;
                    }
                }
            }
        }

        // 4) izolovaný prefix &r
        px = ensureEndsWithReset(px);

        // 5) nasadit prefix/suffix
        try { t.prefix(LEGACY.deserialize(px)); } catch (Throwable ignored) {
            try { t.setPrefix(translateAmpersand(px)); } catch (Throwable ignored2) {}
        }
        try { t.suffix(LEGACY.deserialize(sx)); } catch (Throwable ignored) {
            try { t.setSuffix(translateAmpersand(sx)); } catch (Throwable ignored2) {}
        }

        // 6) barva jména
        ChatColor color = resolveColor(px);
        if (color != null) {
            NamedTextColor named = toNamed(color);
            if (named != null) {
                try { t.color(named); } catch (Throwable ignored) {
                    try { t.setColor(color); } catch (Throwable ignored2) {}
                }
            }
        }

        // 7) viditelnost jmen (může přepsat vanilla default podle configu)
        Team.OptionStatus vis = readNameTagVisibility();
        try { t.setOption(Team.Option.NAME_TAG_VISIBILITY, vis); } catch (Throwable ignored) {}
    }

    // ---------- AFK utils ----------

    private boolean afkEnabled() {
        return plugin.getConfig().getBoolean("modules.tablist.afk_display.enabled", false);
    }
    private String getAfkMode() {
        return safe(plugin.getConfig().getString("modules.tablist.afk_display.mode", "suffix")).trim().toLowerCase(Locale.ROOT);
    }
    private String getAfkSuffix() {
        return safe(plugin.getConfig().getString("modules.tablist.afk_display.suffix", " &7[&eAFK&7]"));
    }
    private String getAfkRecolor() {
        return safe(plugin.getConfig().getString("modules.tablist.afk_display.recolor_color", "&7"));
    }
    private boolean keepAfkFormats() {
        return plugin.getConfig().getBoolean("modules.tablist.afk_display.keep_formats", true);
    }
    private boolean isAfkByPapi(Player p) {
        String ph  = safe(plugin.getConfig().getString("modules.tablist.afk_display.placeholder", "%essentials_afk%"));
        String raw = PlaceholderAPI.setPlaceholders(p, ph);
        if (raw != null && raw.contains("%")) raw = ""; // nevyřešený placeholder → ne-AFK
        String norm = stripLegacyColors(safe(raw)).trim().toLowerCase(Locale.ROOT);
        List<String> trues = plugin.getConfig().getStringList("modules.tablist.afk_display.true_values");
        if (trues == null || trues.isEmpty()) trues = Arrays.asList("yes","true","1");
        for (String s : trues) if (norm.equalsIgnoreCase(s)) return true;
        return false;
    }

    // ---------- color/visibility helpers ----------

    private ChatColor resolveColor(String prefixLegacy) {
        String mode = safe(cfg.colorMode()).trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "none":
                return null;
            case "force": {
                ChatColor c = parseLegacyColor(cfg.colorForce());
                return (c != null ? c : ChatColor.WHITE);
            }
            default: { // "auto_from_prefix"
                return extractLastLegacyColor(prefixLegacy);
            }
        }
    }

    private static ChatColor extractLastLegacyColor(String legacy) {
        if (legacy == null) return null;
        String s = legacy.replace('§', '&');
        ChatColor last = null;
        char[] b = s.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&') {
                char n = Character.toLowerCase(b[i + 1]);
                ChatColor c = ChatColor.getByChar(n);
                if (c != null && c.isColor()) last = c;
            }
        }
        return last;
    }

    private static String ensureEndsWithReset(String legacy) {
        String s = legacy == null ? "" : legacy.replace('§','&');
        if (s.endsWith("&r") || s.endsWith("&R")) return s;
        return s + "&r";
    }

    private Team.OptionStatus readNameTagVisibility() {
        String raw = safe(cfg.nameTagVisibility()).trim().toUpperCase(Locale.ROOT);
        return switch (raw) {
            case "NEVER" -> Team.OptionStatus.NEVER;
            case "HIDE_FOR_OTHER_TEAMS" -> Team.OptionStatus.FOR_OTHER_TEAMS;
            case "HIDE_FOR_OWN_TEAM" -> Team.OptionStatus.FOR_OWN_TEAM;
            case "ALWAYS" -> Team.OptionStatus.ALWAYS;
            default -> Team.OptionStatus.ALWAYS;
        };
    }

    // ---------- misc utils ----------

    private static long toTicksCeil(Duration d) {
        long ticks = (long) Math.ceil(d.toMillis() / 50.0);
        return Math.max(1L, ticks);
    }

    private boolean isAllowedHere(Player p) {
        boolean respect = plugin.getConfig().getBoolean("modules.tablist.respect_worlds_gate", true);
        if (!respect || worldsGate == null) return true;
        return worldsGate.isScoreboardAllowedIn(p.getWorld());
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String translateAmpersand(String text) {
        if (text == null) return "";
        char[] b = text.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) >= 0) {
                b[i] = '§'; b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }

    private static String stripLegacyColors(String s) {
        if (s == null || s.isEmpty()) return "";
        char[] b = s.toCharArray();
        StringBuilder sb = new StringBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            char c = b[i];
            if (c == '&' && i + 1 < b.length) {
                char n = b[i + 1];
                if (n == 'x' || n == 'X') { int jump = Math.min(13, b.length - i); i += jump - 1; continue; }
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(n) >= 0) { i++; continue; }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String cutTo(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars);
    }

    private static String shortUuid(UUID u) {
        String x = u.toString().replace("-", "");
        return (x.length() > 10) ? x.substring(0, 10) : x;
    }

    private static ChatColor parseLegacyColor(String legacyColor) {
        if (legacyColor == null) return null;
        String s = legacyColor.replace('§','&');
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '&') {
                char n = Character.toLowerCase(s.charAt(i + 1));
                if ("0123456789abcdef".indexOf(n) >= 0) return ChatColor.getByChar(n);
            }
        }
        return null;
    }

    /** Přebarví VŠECHNY barvy na cílovou barvu, formáty zachová dle keepFormats. */
    private static String recolorLegacy(String legacy, String targetColor, boolean keepFormats) {
        if (legacy == null) return "";
        legacy = legacy.replace('§', '&');
        targetColor = (targetColor == null ? "&7" : targetColor);
        char[] b = legacy.toCharArray();
        StringBuilder out = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            char c = b[i];
            if (c == '&' && i + 1 < b.length) {
                char n = Character.toLowerCase(b[i + 1]);
                if (n == 'x') { int jump = Math.min(13, b.length - i); i += jump - 1; out.append(targetColor); continue; }
                if ("0123456789abcdef".indexOf(n) >= 0) { i++; out.append(targetColor); continue; }
                if ("klmno".indexOf(n) >= 0) { i++; if (keepFormats) out.append('&').append(n); continue; }
                if (n == 'r') { i++; out.append(targetColor); continue; }
            }
            out.append(c);
        }
        return out.toString();
    }

    // Bukkit ChatColor -> Adventure Named
    private static NamedTextColor toNamed(org.bukkit.ChatColor c) {
        if (c == null) return null;
        return switch (c) {
            case BLACK -> NamedTextColor.BLACK;
            case DARK_BLUE -> NamedTextColor.DARK_BLUE;
            case DARK_GREEN -> NamedTextColor.DARK_GREEN;
            case DARK_AQUA -> NamedTextColor.DARK_AQUA;
            case DARK_RED -> NamedTextColor.DARK_RED;
            case DARK_PURPLE -> NamedTextColor.DARK_PURPLE;
            case GOLD -> NamedTextColor.GOLD;
            case GRAY -> NamedTextColor.GRAY;
            case DARK_GRAY -> NamedTextColor.DARK_GRAY;
            case BLUE -> NamedTextColor.BLUE;
            case GREEN -> NamedTextColor.GREEN;
            case AQUA -> NamedTextColor.AQUA;
            case RED -> NamedTextColor.RED;
            case LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE;
            case YELLOW -> NamedTextColor.YELLOW;
            case WHITE -> NamedTextColor.WHITE;
            default -> null;
        };
    }
}
