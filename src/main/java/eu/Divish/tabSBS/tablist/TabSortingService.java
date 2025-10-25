package eu.Divish.tabSBS.tablist;

import eu.Divish.tabSBS.nametag.NametagService; // NOVĚ: Nametag integrace
import eu.Divish.tabSBS.tablist.TablistConfig.SortingCfg;
import eu.Divish.tabSBS.tablist.TablistConfig.TieBreakerName;
import eu.Divish.tabSBS.worlds.WorldsGate;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Team;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TAB-like sorter:
 * - Per-viewer scoreboard + indexované týmy (ts000..) => deterministické pořadí.
 * - PlayerListName s neviditelným sort-klíčem (pojistka).
 * - Anti-override: udržuje náš scoreboard u hráče, vrací změny zpět.
 * - Konfigurovatelný řetězec řadicích typů (GROUP_ORDER, PREFIX_MATCH, PAPI_NUMBER, PAPI_STRING, NAME).
 *
 * Úpravy:
 * - applyNewConfig(): reaguje na změnu enabled (stop/start + okamžitý enforce).
 * - stop(): navíc odstraní naše týmy z boardů a resetuje listName.
 * - assignTeamsOnBoard(): respektuje WorldsGate (hráče v zakázaných světech z týmů odstraní).
 * - Přidány aliasy refreshAll()/applyOnce()/clearFor().
 * - NMS re-add paket jen pokud sorting.enforce_via_protocollib = true.
 *
 * NOVĚ:
 * - Režim bez sortingu vždy udržuje viditelné jméno v TABu (applyBaseOnlyAll), i když je AFK v configu vypnuté.
 * - clearFor() v režimu bez sortingu vrací bezpečné jméno místo null, aby hráč nemizel z TABu.
 * - Limit viditelných znaků v TABu (modules.tablist.max_list_name_chars; default 80).
 * - Anti-blink integrace s NametagService: při přesazení do tsNNN týmu výchozí dekorace týmu resetneme
 *   a okamžitě „přebarvíme“ hráče přes NametagService.applyFor(..).
 */
public final class TabSortingService {
    private static final String TEAM_PREFIX = "ts";
    private static final String MARKER_TEAM = "__tabsbs_marker__";
    private static final boolean DEBUG = false;

    private final Plugin plugin;
    private final WorldsGate worldsGate; // může být null
    private TablistConfig cfg;

    // Vault (může být null)
    private final Permission perms;
    private final Chat chat;

    // NOVĚ: volitelná reference na Nametag modul (může být null)
    private final NametagService nametagSvc;

    // periodický loop
    private ScheduledTask loopTask = null;
    private boolean running = false;

    // anti-override loop
    private ScheduledTask antiTask = null;

    // throttle pro enforcer
    private long lastEnforceMs = 0L;

    // hráč -> aktuální tým (poslední přiřazení)
    private final Map<UUID, String> currentTeams = new ConcurrentHashMap<>();

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    // PŮVODNÍ konstruktor (ponechán kvůli kompatibilitě)
    public TabSortingService(Plugin plugin, TablistConfig cfg, WorldsGate worldsGate,
                             Permission perms, Chat chat) {
        this(plugin, cfg, worldsGate, perms, chat, null);
    }

    // NOVÝ konstruktor s volitelnou NametagService (eliminace 1s „bliknutí“)
    public TabSortingService(Plugin plugin, TablistConfig cfg, WorldsGate worldsGate,
                             Permission perms, Chat chat, NametagService nametagSvc) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.worldsGate = worldsGate;
        this.perms = perms;
        this.chat = chat;
        this.nametagSvc = nametagSvc; // může být null
    }

    // ===== lifecycle =====

    public void start() {
        // NOVĚ: loop spouštíme vždy, i když je sorting vypnutý (kvůli AFK/base jménům v TABu)
        if (running) return;
        running = true;

        long ticks = toTicksCeil(cfg.updatePeriod());
        loopTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                t -> applyAll(),
                ticks, ticks
        );

        // Anti-override watchdog jen při zapnutém sortingu
        int antiEvery = Math.max(1, plugin.getConfig().getInt("modules.tablist.sorting.anti_override.check_every_ticks", 20));
        boolean antiEnforce = plugin.getConfig().getBoolean("modules.tablist.sorting.anti_override.enforce_scoreboard", true);
        if (antiEnforce && cfg.sorting().enabled()) {
            antiTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin,
                    t -> watchdogScoreboards(),
                    antiEvery, antiEvery
            );
        }

        // Po startu rovnou aplikuj: při ON vynucení pořadí, při OFF jen viditelné jméno/AFK
        lastEnforceMs = 0L;
        if (cfg.sorting().enabled()) {
            enforceOrderDirect();
        } else {
            refreshAll();
        }
    }

    public void stop() {
        if (!running) {
            // i když neběží, při „OFF“ v configu hned uklidíme stopy
            cleanupAllBoardsAndNames();
            return;
        }
        running = false;

        if (loopTask != null) { loopTask.cancel(); loopTask = null; }
        if (antiTask != null) { antiTask.cancel(); antiTask = null; }

        currentTeams.clear();
        lastEnforceMs = 0L;

        // úklid: odstraníme naše týmy z boardů + resetneme listName
        cleanupAllBoardsAndNames();
    }

    /**
     * Reaguje na změnu configu (hlavně enabled).
     */
    public void applyNewConfig(TablistConfig newCfg) {
        boolean wasRunning = this.running;
        boolean wasEnabled = (this.cfg != null && this.cfg.sorting().enabled());

        this.cfg = newCfg;

        boolean nowEnabled = this.cfg.sorting().enabled();

        if (wasEnabled && !nowEnabled) {
            // NOVĚ: při vypnutí sortingu NEZASTAVUJ službu – jen vypni anti-override,
            // vyčisti naše týmy a hned obnov jména (AFK/base) přes refreshAll().
            if (antiTask != null) { antiTask.cancel(); antiTask = null; }
            cleanupAllBoardsAndNames();
            lastEnforceMs = 0L;
            refreshAll();
        } else if (!wasEnabled && nowEnabled) {
            // nově zapnuto: pokud loop běží, jen zapni anti-override watchdog a vynucení pořadí
            if (!wasRunning) start();
            // re-enable anti watchdog
            if (antiTask != null) { antiTask.cancel(); antiTask = null; }
            int antiEvery = Math.max(1, plugin.getConfig().getInt("modules.tablist.sorting.anti_override.check_every_ticks", 20));
            boolean antiEnforce = plugin.getConfig().getBoolean("modules.tablist.sorting.anti_override.enforce_scoreboard", true);
            if (antiEnforce) {
                antiTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                        plugin,
                        t -> watchdogScoreboards(),
                        antiEvery, antiEvery
                );
            }
            lastEnforceMs = 0L;
            enforceOrderDirect();
        } else {
            // stav se nezměnil
            if (nowEnabled && wasRunning) {
                // jen znovu vynutíme pořadí (např. změna chainu/priority)
                lastEnforceMs = 0L;
                enforceOrderDirect();
            } else if (!nowEnabled) {
                // je OFF – udrž čistý stav
                cleanupAllBoardsAndNames();
                refreshAll(); // a hned zapiš base/AFK jména do TABu
            }
        }
    }

    /** Jednotné volání z reloadAll(): „osvěž pořadí“ */
    public void refreshAll() {
        if (!cfg.sorting().enabled()) {
            // NOVĚ: když je sorting OFF, držíme jména v TABu vždy
            if (plugin.getConfig().getBoolean("modules.tablist.afk_display.enabled", false)) {
                applyAfkOnlyAll();
            } else {
                applyBaseOnlyAll(); // NOVĚ
            }
            return;
        }
        lastEnforceMs = 0L;
        enforceOrderDirect();
    }

    /** Alias pro jednotnost s ostatními službami. */
    public void applyOnce(Player ignored) { refreshAll(); }

    /** Okamžité vyčištění stop pro konkrétního hráče. */
    public void clearFor(Player p) {
        // odstranit z našich týmů na jeho boardu
        Scoreboard sb = p.getScoreboard();
        for (Team t : sb.getTeams()) {
            String n = t.getName();
            if (n != null && (n.equals(MARKER_TEAM) || n.startsWith(TEAM_PREFIX))) {
                try { t.removeEntry(p.getName()); } catch (Throwable ignored) {}
            }
        }
        currentTeams.remove(p.getUniqueId());

        // NOVĚ: v režimu bez sortingu vrať do TABu bezpečné (viditelné) základní jméno
        if (!cfg.sorting().enabled() && isAllowedHere(p)) {
            String baseVisible = (cfg.sorting().decorateNames() && chat != null)
                    ? safe(chat.getPlayerPrefix(p)) + p.getName() + safe(chat.getPlayerSuffix(p))
                    : p.getName();
            baseVisible = capVisibleLegacy(baseVisible, maxListNameVisible());
            try { p.playerListName(LEGACY.deserialize(baseVisible)); }
            catch (Throwable ignored) { try { p.setPlayerListName(translateAmpersand(baseVisible)); } catch (Throwable ignored2) {} }
        } else {
            // při zapnutém sortingu nebo v zakázaném světě vrať default
            try { p.playerListName(null); } catch (Throwable ignored) { try { p.setPlayerListName(null); } catch (Throwable ignored2) {} }
        }
    }

    // ===== veřejné API (původní) =====

    public void applyAll() {
        if (cfg.sorting().enabled()) {
            enforceOrderDirect();
            return;
        }
        // NOVĚ: bez sortingu – vždy držíme jména v TABu
        if (plugin.getConfig().getBoolean("modules.tablist.afk_display.enabled", false)) {
            applyAfkOnlyAll();   // použij tvůj AFK overlay do listName
        } else {
            applyBaseOnlyAll();  // žádný overlay, jen bezpečné jméno
        }
    }

    /** NOVĚ: Režim bez sortingu – vynutí základní viditelné jméno v TABu (bez AFK overlaye). */
    private void applyBaseOnlyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isAllowedHere(p)) {
                try { p.playerListName(null); } catch (Throwable ignored) { try { p.setPlayerListName(null); } catch (Throwable ignored2) {} }
                continue;
            }
            String baseVisible = (cfg.sorting().decorateNames() && chat != null)
                    ? safe(chat.getPlayerPrefix(p)) + p.getName() + safe(chat.getPlayerSuffix(p))
                    : p.getName();

            baseVisible = capVisibleLegacy(baseVisible, maxListNameVisible());
            if (isVisiblyEmpty(baseVisible)) baseVisible = p.getName(); // << přidaná pojistka

            try { p.playerListName(LEGACY.deserialize(baseVisible)); }
            catch (Throwable ignored) { try { p.setPlayerListName(translateAmpersand(baseVisible)); } catch (Throwable ignored2) {} }
        }
    }

    private void applyAfkOnlyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isAllowedHere(p)) {
                clearFor(p);
                continue;
            }
            String baseVisible = (cfg.sorting().decorateNames() && chat != null)
                    ? safe(chat.getPlayerPrefix(p)) + p.getName() + safe(chat.getPlayerSuffix(p))
                    : p.getName();

            String visible = applyAfkOverlay(p, baseVisible);

            // limit délky + pojistka proti “prázdnému” viditelnému textu
            visible = capVisibleLegacy(visible, maxListNameVisible());
            if (isVisiblyEmpty(visible)) visible = p.getName();

            // DŮLEŽITÉ: preferuj Adventure, až pak legacy String
            try { p.playerListName(LEGACY.deserialize(visible)); }
            catch (Throwable ignored) { try { p.setPlayerListName(translateAmpersand(visible)); } catch (Throwable ignored2) {} }
        }
    }

    public void remove(Player p) {
        currentTeams.remove(p.getUniqueId());
    }

    // ===== core =====

    private void enforceOrderDirect() {
        if (!cfg.sorting().enabled()) return;
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) return;

        long now = System.currentTimeMillis();
        long minGap = Math.max(250L, (long) cfg.updatePeriod().toMillis());
        if (now - lastEnforceMs < minGap) return;
        lastEnforceMs = now;

        // 1) seber a seřaď
        List<Player> players = new ArrayList<>(online);
        Comparator<Player> cmp = buildComparator();
        players.sort(cmp);

        if (DEBUG) {
            StringBuilder sb = new StringBuilder("[TabSBS:ORDER] ");
            for (int i = 0; i < players.size(); i++) {
                Player x = players.get(i);
                sb.append(String.format(Locale.ROOT, "%03d:%s { %s }  ",
                        i, x.getName(), debugKeys(x)));
            }
            plugin.getLogger().info(sb.toString());
        }

        // 2) listName s neviditelným sort-klíčem + AFK overlay (jen pokud world dovoluje)
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (!isAllowedHere(p)) {
                clearFor(p);
                continue;
            }
            String baseVisible = (cfg.sorting().decorateNames() && chat != null)
                    ? safe(chat.getPlayerPrefix(p)) + p.getName() + safe(chat.getPlayerSuffix(p))
                    : p.getName();

            String visible = applyAfkOverlay(p, baseVisible);

            // i pro sorting režim pojistka na délku
            visible = capVisibleLegacy(visible, maxListNameVisible());

            String legacy = translateAmpersand(buildSortKeyPrefix(i) + visible);
            try { p.setPlayerListName(legacy); }
            catch (Throwable ignored) { try { p.playerListName(LEGACY.deserialize(buildSortKeyPrefix(i) + visible)); } catch (Throwable ignored2) {} }
        }

        // 3) scoreboard režim
        String sbMode = plugin.getConfig().getString("modules.tablist.sorting.scoreboard_mode", "per_viewer").toLowerCase(Locale.ROOT);
        switch (sbMode) {
            case "shared" -> applySharedScoreboard(players);
            default -> applyPerViewerScoreboards(players);
        }

        // 4) NMS re-add jen pokud to máš povolené v configu (enforce_via_protocollib == true)
        if (cfg.sorting().enforceViaProtocolLib()) {
            List<UUID> uuids = new ArrayList<>(players.size());
            List<ServerPlayer> nmsPlayers = new ArrayList<>(players.size());
            for (Player bp : players) {
                uuids.add(bp.getUniqueId());
                nmsPlayers.add(((CraftPlayer) bp).getHandle());
            }
            ClientboundPlayerInfoRemovePacket remove = new ClientboundPlayerInfoRemovePacket(uuids);
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
            );
            ClientboundPlayerInfoUpdatePacket add = new ClientboundPlayerInfoUpdatePacket(actions, nmsPlayers);

            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                for (Player viewer : online) {
                    ServerGamePacketListenerImpl conn = ((CraftPlayer) viewer).getHandle().connection;
                    conn.send(remove);
                    conn.send(add);
                }
            }, 2L);
        }
    }

    // ===== AFK overlay =====

    private boolean isAfkByPapi(Player p) {
        String ph = plugin.getConfig().getString("modules.tablist.afk_display.placeholder", "%essentials_afk%");
        String raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, ph);
        if (raw != null && raw.contains("%")) raw = "";
        String norm = stripLegacyColors(safe(raw)).trim().toLowerCase(Locale.ROOT);

        List<String> trueVals = plugin.getConfig().getStringList("modules.tablist.afk_display.true_values");
        if (trueVals == null || trueVals.isEmpty()) {
            trueVals = java.util.Arrays.asList("yes","true","1");
        }
        for (String s : trueVals) {
            if (norm.equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    private String applyAfkOverlay(Player p, String baseLegacyName) {
        if (!plugin.getConfig().getBoolean("modules.tablist.afk_display.enabled", false)) return baseLegacyName;

        boolean afk = isAfkByPapi(p);
        if (!afk) return baseLegacyName;

        String mode = safe(plugin.getConfig().getString("modules.tablist.afk_display.mode", "suffix"))
                .trim().toLowerCase(Locale.ROOT);
        String suffix = safe(plugin.getConfig().getString("modules.tablist.afk_display.suffix", " &7[&eAFK&7]"));
        String recolor = safe(plugin.getConfig().getString("modules.tablist.afk_display.recolor_color", "&7"));
        boolean keepFmt = plugin.getConfig().getBoolean("modules.tablist.afk_display.keep_formats", true);

        switch (mode) {
            case "recolor":
                return recolorLegacy(baseLegacyName, recolor, keepFmt);
            case "both":
                return recolorLegacy(baseLegacyName, recolor, keepFmt) + suffix;
            case "suffix":
            default:
                return baseLegacyName + suffix;
        }
    }

    private static String recolorLegacy(String legacy, String targetColor, boolean keepFormats) {
        if (legacy == null) return "";
        legacy = legacy.replace('§', '&');
        targetColor = targetColor == null ? "&7" : targetColor;
        char[] b = legacy.toCharArray();
        StringBuilder out = new StringBuilder(b.length * 2);

        for (int i = 0; i < b.length; i++) {
            char c = b[i];
            if (c == '&' && i + 1 < b.length) {
                char n = Character.toLowerCase(b[i + 1]);
                if (n == 'x') {
                    int jump = Math.min(13, b.length - i);
                    i += jump - 1;
                    out.append(targetColor);
                    continue;
                }
                if ("0123456789abcdef".indexOf(n) >= 0) {
                    i++;
                    out.append(targetColor);
                    continue;
                }
                if ("klmno".indexOf(n) >= 0) {
                    i++;
                    if (keepFormats) {
                        out.append('&').append(n);
                    }
                    continue;
                }
                if (n == 'r') {
                    i++;
                    out.append(targetColor);
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    // --- Scoreboard applicators ---

    private void applyPerViewerScoreboards(List<Player> ordered) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = ensureViewerBoard(viewer);
            assignTeamsOnBoard(sb, ordered);
        }
    }

    private void applySharedScoreboard(List<Player> ordered) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        ensureMarker(sb);
        assignTeamsOnBoard(sb, ordered);
    }

    private Scoreboard ensureViewerBoard(Player viewer) {
        Scoreboard current = viewer.getScoreboard();
        ensureMarker(current);
        return current;
    }

    private void ensureMarker(Scoreboard sb) {
        if (sb.getTeam(MARKER_TEAM) == null) {
            Team t = sb.registerNewTeam(MARKER_TEAM);
            try { t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER); } catch (Throwable ignored) {}
        }
    }

    /**
     * Přiřadí indexované týmy ts000..; zároveň odstraní hráče z našich týmů,
     * pokud nejsou ve světě, kde je to povolené (WorldsGate).
     *
     * NOVĚ: Aby se při změně priorit neobjevil na okamžik prefix/suffix předchozího člena,
     * cílový tým vždy nejdřív vyčistíme (entries + dekorace) a teprve poté přidáme hráče
     * a okamžitě necháme NametagService aplikovat správný vzhled.
     */
    private void assignTeamsOnBoard(Scoreboard sb, List<Player> ordered) {
        // Nejprve všechny hráče v „zakázaných“ světech z našich týmů odstraníme.
        for (Team other : sb.getTeams()) {
            String n = other.getName();
            if (n == null) continue;
            if (!n.equals(MARKER_TEAM) && !n.startsWith(TEAM_PREFIX)) continue;
            for (String entry : new HashSet<>(other.getEntries())) {
                Player ep = Bukkit.getPlayerExact(entry);
                if (ep == null) continue;
                if (!isAllowedHere(ep)) {
                    try { other.removeEntry(entry); } catch (Throwable ignored) {}
                }
            }
        }

        // Teď přiřadíme pořadí pouze hráčům, u kterých je to povoleno.
        int i = 0;
        for (Player p : ordered) {
            if (!isAllowedHere(p)) continue;

            String newTeam = TEAM_PREFIX + String.format("%03d", i++);
            Team t = sb.getTeam(newTeam);
            if (t == null) {
                t = sb.registerNewTeam(newTeam);
                try { t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER); } catch (Throwable ignored) {}
                try { t.setCanSeeFriendlyInvisibles(false); } catch (Throwable ignored) {}
                try { t.setAllowFriendlyFire(false); } catch (Throwable ignored) {}
            }

            if (!t.hasEntry(p.getName())) {
                // očisti z jiných našich týmů na TOMTO boardu
                for (Team other : sb.getTeams()) {
                    if (other == t) continue;
                    String n = other.getName();
                    if (n != null && (n.equals(MARKER_TEAM) || n.startsWith(TEAM_PREFIX) || n.startsWith("tabsbs"))) {
                        try { other.removeEntry(p.getName()); } catch (Throwable ignored) {}
                    }
                }

                // NOVĚ: před přidáním hráče do cílového týmu vždy vyčisti zbytky a dekorace
                for (String leftover : new HashSet<>(t.getEntries())) {
                    try { t.removeEntry(leftover); } catch (Throwable ignored) {}
                }
                // reset prefix/suffix/barva – Adventure i legacy fallback
                try { t.prefix(Component.empty()); } catch (Throwable ignored) { try { t.setPrefix(""); } catch (Throwable ignored2) {} }
                try { t.suffix(Component.empty()); } catch (Throwable ignored) { try { t.setSuffix(""); } catch (Throwable ignored2) {} }
                try { t.color(net.kyori.adventure.text.format.NamedTextColor.WHITE); }
                catch (Throwable ignored) { try { t.setColor(org.bukkit.ChatColor.WHITE); } catch (Throwable ignored2) {} }

                // přidej hráče
                try { t.addEntry(p.getName()); } catch (Throwable ignored) {}

                // a hned „přebarvi“ přes NametagService (pokud je k dispozici)
                if (nametagSvc != null) {
                    try { nametagSvc.applyFor(p); } catch (Throwable ignored) {}
                }
            }
            currentTeams.put(p.getUniqueId(), newTeam);
        }
    }

    // --- Anti-override watchdog ---

    private void watchdogScoreboards() {
        boolean log = plugin.getConfig().getBoolean("modules.tablist.sorting.anti_override.log", true);
        String sbMode = plugin.getConfig().getString("modules.tablist.sorting.scoreboard_mode", "per_viewer").toLowerCase(Locale.ROOT);

        if ("shared".equals(sbMode)) {
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            ensureMarker(main);
            for (Player p : Bukkit.getOnlinePlayers()) {
                ensureMarker(p.getScoreboard());
                if (log) plugin.getLogger().fine("[TabSBS] [anti] checked marker (shared) for " + p.getName());
            }
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ensureMarker(p.getScoreboard());
                if (log) plugin.getLogger().fine("[TabSBS] [anti] checked marker (per_viewer) for " + p.getName());
            }
        }
    }

    // ===== comparator chain (TAB-like) =====
    // (beze změn; jen zachováno z tvé implementace)

    private Comparator<Player> buildComparator() {
        List<Map<?,?>> types = plugin.getConfig().getMapList("modules.tablist.sorting.types");
        List<Comparator<Player>> chain = new ArrayList<>();

        if (!types.isEmpty()) {
            for (Map<?,?> m : types) {
                Object typeObj = m.get("type");
                String type = (typeObj == null ? "NAME" : String.valueOf(typeObj)).toUpperCase(Locale.ROOT);

                switch (type) {
                    case "GROUP_ORDER" -> chain.add(groupOrder(toLowerList(m.get("order"))));
                    case "PREFIX_MATCH" -> chain.add(prefixMatch(toLowerList(m.get("order"))));
                    case "PAPI_NUMBER" -> {
                        Object phNumObj = m.get("placeholder");
                        String phNum = (phNumObj == null ? "" : String.valueOf(phNumObj));
                        boolean desc = Boolean.TRUE.equals(m.get("desc"));
                        chain.add(papiNumber(phNum, desc));
                    }
                    case "PAPI_STRING" -> {
                        Object phStrObj = m.get("placeholder");
                        String phStr = (phStrObj == null ? "" : String.valueOf(phStrObj));
                        chain.add(papiString(phStr, toLowerList(m.get("order"))));
                    }
                    case "NAME" -> {
                        boolean desc = Boolean.TRUE.equals(m.get("desc"));
                        chain.add(name(desc));
                    }
                    default -> chain.add(name(false));
                }
            }
        } else {
            chain.add(legacyPriorityComparator());
            chain.add(name(cfg.sorting().tieBreakerName() == TieBreakerName.DESC));
        }

        return chain.stream().reduce(Comparator::thenComparing).orElse(name(false));
    }

    private String debugKeys(Player p) {
        List<Map<?, ?>> types = plugin.getConfig().getMapList("modules.tablist.sorting.types");
        StringBuilder sb = new StringBuilder();
        if (!types.isEmpty()) {
            for (Map<?, ?> m : types) {
                Object typeObj = m.get("type");
                String type = (typeObj == null ? "NAME" : String.valueOf(typeObj)).toUpperCase(Locale.ROOT);

                switch (type) {
                    case "GROUP_ORDER" -> {
                        List<String> order = toLowerList(m.get("order"));
                        String g = normGroup(p);
                        int idx = order.indexOf(g);
                        sb.append("GROUP=").append(g).append("(idx=").append(idx < 0 ? "n/a" : idx).append(") ");
                    }
                    case "PREFIX_MATCH" -> {
                        List<String> order = toLowerList(m.get("order"));
                        String px = normPrefix(p);
                        int hit = -1;
                        for (int i = 0; i < order.size(); i++) {
                            if (wildcardMatch(px, order.get(i))) { hit = i; break; }
                        }
                        sb.append("PREFIX='").append(px).append("'(idx=").append(hit < 0 ? "n/a" : hit).append(") ");
                    }
                    case "PAPI_NUMBER" -> {
                        Object phObj = m.get("placeholder");
                        String ph = (phObj == null ? "" : String.valueOf(phObj));
                        String raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, ph);
                        double val = parseDoubleSafe(raw);
                        boolean desc = Boolean.TRUE.equals(m.get("desc"));
                        sb.append("PAPI#=").append(ph).append(" val=").append(val).append(desc ? " (desc) " : " ");
                    }
                    case "PAPI_STRING" -> {
                        Object phObj = m.get("placeholder");
                        String ph = (phObj == null ? "" : String.valueOf(phObj));
                        String raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, ph);
                        String norm = (raw == null ? "" : stripLegacyColors(raw).trim().toLowerCase(Locale.ROOT));
                        List<String> order = toLowerList(m.get("order"));
                        int idx = order.indexOf(norm);
                        sb.append("PAPI$=").append(ph).append(" '").append(norm).append("'(idx=").append(idx < 0 ? "n/a" : idx).append(") ");
                    }
                    case "NAME" -> {
                        boolean desc = Boolean.TRUE.equals(m.get("desc"));
                        sb.append("NAME=").append(p.getName()).append(desc ? " (desc) " : " ");
                    }
                    default -> sb.append(type).append(" ");
                }
            }
        } else {
            String g = normGroup(p);
            String pxNorm = normPrefix(p);
            int pr = resolvePriorityIndexLegacy(p, cfg.sorting(), g);
            sb.append("LEGACY group=").append(g)
                    .append(" prefix='").append(pxNorm).append("' prio=").append(pr).append(" ");
        }
        return sb.toString().trim();
    }

    private Comparator<Player> groupOrder(List<String> order) {
        return Comparator.comparingInt(p -> {
            String g = normGroup(p);
            int idx = order.indexOf(g);
            return idx < 0 ? 999 : idx;
        });
    }

    private Comparator<Player> prefixMatch(List<String> patterns) {
        return Comparator.comparingInt(p -> {
            String px = normPrefix(p);
            if (px.isEmpty()) return 999;
            for (int i = 0; i < patterns.size(); i++) {
                if (wildcardMatch(px, patterns.get(i))) return i;
            }
            return 999;
        });
    }

    private Comparator<Player> papiNumber(String placeholder, boolean desc) {
        return (a, b) -> {
            double va = parseDoubleSafe(PlaceholderAPI.setPlaceholders(a, placeholder));
            double vb = parseDoubleSafe(PlaceholderAPI.setPlaceholders(b, placeholder));
            int cmp = Double.compare(va, vb);
            return desc ? -cmp : cmp;
        };
    }

    private Comparator<Player> papiString(String placeholder, List<String> order) {
        return Comparator.comparingInt(p -> {
            String v = PlaceholderAPI.setPlaceholders(p, placeholder);
            String norm = (v == null ? "" : stripLegacyColors(v).trim().toLowerCase(Locale.ROOT));
            int idx = order.indexOf(norm);
            return idx < 0 ? 999 : idx;
        });
    }

    private Comparator<Player> name(boolean desc) {
        Comparator<Player> c = Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT));
        return desc ? c.reversed() : c;
    }

    private Comparator<Player> legacyPriorityComparator() {
        return Comparator.comparingInt(p -> {
            String groupKey = normGroup(p);
            return resolvePriorityIndexLegacy(p, cfg.sorting(), groupKey);
        });
    }

    // ===== helpers/legacy =====

    private int resolvePriorityIndexLegacy(Player p, SortingCfg s, String playerGroupKey) {
        List<String> rules = s.priority();
        String playerPrefixNorm = (chat != null)
                ? stripLegacyColors(safe(chat.getPlayerPrefix(p))).trim().toLowerCase(Locale.ROOT)
                : "";

        for (int i = 0; i < rules.size(); i++) {
            String rawRule = rules.get(i);
            if (rawRule == null || rawRule.isBlank()) continue;

            String rule = rawRule.trim().toLowerCase(Locale.ROOT);
            int colon = rule.indexOf(':');
            if (colon >= 0) {
                String prefixPattern = rule.substring(colon + 1).trim();
                if (!prefixPattern.isEmpty() && wildcardMatch(playerPrefixNorm, prefixPattern)) {
                    return i;
                }
                continue;
            }
            if (rule.equals(playerGroupKey)) {
                return i;
            }
        }
        int def = s.defaultPriority();
        if (def >= 0) return Math.min(def, 99);
        return 99;
    }

    private boolean isAllowedHere(Player p) {
        if (!cfg.respectWorldsGate() || worldsGate == null) return true;
        return worldsGate.isScoreboardAllowedIn(p.getWorld());
    }

    // ===== utils =====

    private static long toTicksCeil(Duration d) {
        long ticks = (long) Math.ceil(d.toMillis() / 50.0);
        return Math.max(1L, ticks);
    }

    private String normGroup(Player p) {
        if (perms == null) return "";
        String g = perms.getPrimaryGroup(p);
        return g == null ? "" : g.trim().toLowerCase(Locale.ROOT);
    }

    private String normPrefix(Player p) {
        if (chat == null) return "";
        return stripLegacyColors(safe(chat.getPlayerPrefix(p))).trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static double parseDoubleSafe(String s) {
        if (s == null) return 0.0;
        try { return Double.parseDouble(s.replace(",", ".").replaceAll("[^0-9.+-]", "")); }
        catch (Throwable t) { return 0.0; }
    }

    private static String buildSortKeyPrefix(int index) {
        int v = Math.max(0, Math.min(255, index));
        char[] hex = "0123456789abcdef".toCharArray();
        int hi = (v >> 4) & 0xF, lo = v & 0xF;
        return "§" + hex[hi] + "§" + hex[lo] + "§r";
    }

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
                if (n == 'x' || n == 'X') {
                    int jump = Math.min(13, b.length - i); i += jump - 1; continue;
                }
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(n) >= 0) { i++; continue; }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean wildcardMatch(String text, String pattern) {
        if (text == null) text = "";
        if (pattern == null) pattern = "";
        pattern = stripLegacyColors(pattern).toLowerCase(Locale.ROOT);
        if (!pattern.contains("*")) return text.equals(pattern);
        String[] parts = pattern.split("\\*", -1);
        int pos = 0;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int idx = text.indexOf(part, pos);
            if (idx < 0) return false;
            pos = idx + part.length();
        }
        return pattern.endsWith("*") || pos == text.length();
    }

    private static List<String> toLowerList(Object val) {
        if (val == null) return Collections.emptyList();
        if (val instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) if (o != null) out.add(o.toString().trim().toLowerCase(Locale.ROOT));
            return out;
        }
        String s = String.valueOf(val);
        String[] parts = s.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) out.add(p.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private void cleanupAllBoardsAndNames() {
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            // reset list name všem
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { p.setPlayerListName(null); } catch (Throwable ignored) {}
            }
            // z aktuálních boardů každého hráče odstraníme naše týmy
            for (Player p : Bukkit.getOnlinePlayers()) {
                Scoreboard sb = p.getScoreboard();
                for (Team t : new ArrayList<>(sb.getTeams())) {
                    String n = t.getName();
                    if (n != null && (n.equals(MARKER_TEAM) || n.startsWith(TEAM_PREFIX))) {
                        try { t.unregister(); } catch (Throwable ignored) {}
                    }
                }
            }
            currentTeams.clear();
        });
    }
    /** Vrátí true, pokud legacy text nemá žádné VIDITELNÉ znaky (po odbarvení). */
    private static boolean isVisiblyEmpty(String legacy) {
        String s = stripLegacyColors(legacy == null ? "" : legacy.replace('§','&')).trim();
        return s.isEmpty();
    }

    private void logAnti(String msg) {
        if (plugin.getConfig().getBoolean("modules.tablist.sorting.anti_override.log", true)) {
            plugin.getLogger().warning("[TabSBS] " + msg);
        }
    }

    // ===== NOVÉ pomocné metody pro „bezpečné“ jméno v TABu =====

    /** Max. počet VIDITELNÝCH znaků (barvy/formáty se nepočítají). Lze přepsat v configu. */
    private int maxListNameVisible() {
        return Math.max(16, plugin.getConfig().getInt("modules.tablist.max_list_name_chars", 80));
    }

    /** Ořízne legacy text na daný počet viditelných znaků, kódy &x/&r/&l… ponechá. */
    private static String capVisibleLegacy(String legacy, int maxVisible) {
        if (legacy == null) return "";
        if (maxVisible <= 0) return "";
        String s = legacy.replace('§', '&');
        StringBuilder out = new StringBuilder(s.length());
        int vis = 0;
        char[] b = s.toCharArray();
        for (int i = 0; i < b.length; i++) {
            char c = b[i];
            if (c == '&' && i + 1 < b.length) {
                // zkopíruj řídicí kód, ale nezvyšuj viditelný počet
                out.append(c).append(b[++i]);
                continue;
            }
            out.append(c);
            if (++vis >= maxVisible) break;
        }
        return out.toString();
    }
}
