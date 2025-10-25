package eu.Divish.tabSBS.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.Divish.tabSBS.TabSBS;
import eu.Divish.tabSBS.lang.LangManager;
import eu.Divish.tabSBS.nametag.NametagService;
import eu.Divish.tabSBS.papi.PapiExpansionAutoInstaller;
import eu.Divish.tabSBS.papi.PlaceholderValidator;
import eu.Divish.tabSBS.scoreboard.ScoreboardConfig;
import eu.Divish.tabSBS.scoreboard.ScoreboardRuntime;
import eu.Divish.tabSBS.tablist.TabSortingService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import static me.clip.placeholderapi.util.Msg.msg;

public final class TabsbsCommand implements TabExecutor, TabCompleter {

    private final TabSBS plugin;
    private final LangManager lang;
    private final ScoreboardRuntime sbRuntime;
    private final TabSortingService tabSorting;
    private final NametagService nametagSvc;
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static String toLegacy(Component c) { return (c == null) ? "" : LEGACY_AMP.serialize(c); }

    public TabsbsCommand(
            TabSBS plugin,
            LangManager lang,
            ScoreboardRuntime sbRuntime,
            TabSortingService tabSorting,
            NametagService nametagSvc
    ) {
        this.plugin = plugin;
        this.lang = lang;
        this.sbRuntime = sbRuntime;
        this.tabSorting = tabSorting;
        this.nametagSvc = nametagSvc;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = (args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT));
        try {
            switch (sub) {
                case "help" -> help(sender, label);
                case "version" -> version(sender);

                case "sort" -> {
                    requirePerm(sender, "tabsbs.sort.now");
                    if (args.length >= 2 && args[1].equalsIgnoreCase("now")) {
                        tabSorting.applyAll();
                        msgKey(sender, "commands.sort.now.ok");
                    } else usage(sender, label, "sort now");
                }

                case "nametag" -> handleNametag(sender, label, Arrays.copyOfRange(args, 1, args.length));
                case "papi"     -> handlePapi(sender, label, Arrays.copyOfRange(args, 1, args.length));

                case "scoreboard" -> {
                    requirePerm(sender, "tabsbs.scoreboard.refresh");
                    if (args.length >= 2 && args[1].equalsIgnoreCase("refresh")) {
                        if (sbRuntime != null) sbRuntime.refreshAll();
                        msgKey(sender, "commands.scoreboard.refresh_ok");
                    } else usage(sender, label, "scoreboard refresh");
                }

                case "reload" -> {
                    requirePerm(sender, "tabsbs.reload");
                    msgKey(sender, "commands.reload.started");
                    try {
                        plugin.reloadAll();
                        msgKey(sender, "commands.reload.done");
                    } catch (Throwable t) {
                        msgKey(sender, "commands.reload.failed",
                                "type", t.getClass().getSimpleName(),
                                "message", String.valueOf(t.getMessage()));
                        t.printStackTrace();
                    }
                }

                // ===== UPDATE =====
                case "update" -> {
                    requirePerm(sender, "tabsbs.update");
                    if (args.length < 2) { usage(sender, label, "update <confirm|cancel>"); break; }

                    String what = args[1].toLowerCase(Locale.ROOT);

                    if ("cancel".equals(what)) {
                        if (sender instanceof Player pl) {
                            plugin.markDeclinedThisBoot(pl.getUniqueId());
                            sendLang(sender, "update.cancelled");
                        } else {
                            sendLang(sender, "update.cancelled");
                        }
                        break;
                    }

                    if ("confirm".equals(what)) {
                        // když není dostupná aktualizace, nechceme zobrazovat tlačítka ani nic stahovat
                        if (!plugin.isUpdateAvailable()) {
                            sendLang(sender, "update.no_update_available");
                            break;
                        }
                        sendLang(sender, "update.downloading");

                        // stáhni asynchronně .jar do plugins/update/TabSBS.jar
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            boolean ok = downloadLatestJar();
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (ok) sendLang(sender, "update.success");
                                else    sendLang(sender, "update.failed");
                            });
                        });
                        break;
                    }

                    usage(sender, label, "update <confirm|cancel>");
                }

                case "team" -> handleTeam(sender, label, Arrays.copyOfRange(args, 1, args.length));

                default -> help(sender, label);
            }
        } catch (NoPermission ex) {
            msgKey(sender, "commands.no_permission", "node", ex.node);
        } catch (Throwable t) {
            msgKey(sender, "commands.error", "type", t.getClass().getSimpleName(), "message", String.valueOf(t.getMessage()));
            t.printStackTrace();
        }
        return true;
    }

    // -------- NAMETAG --------
    private void handleNametag(CommandSender sender, String label, String[] args) throws NoPermission {
        if (args.length == 0) { usage(sender, label, "nametag <refresh|clear> [player|all]"); return; }
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "refresh" -> {
                requirePerm(sender, "tabsbs.nametag.refresh");
                if (args.length == 1 || args[1].equalsIgnoreCase("all")) {
                    nametagSvc.applyAll();
                    msgKey(sender, "commands.nametag.refresh_all.ok");
                } else {
                    Player p = Bukkit.getPlayerExact(args[1]);
                    if (p == null) { msgKey(sender, "commands.common.player_not_online"); return; }
                    nametagSvc.applyFor(p);
                    msgKey(sender, "commands.nametag.refresh_one.ok", "player", p.getName());
                }
            }
            case "clear" -> {
                requirePerm(sender, "tabsbs.nametag.clear");
                if (args.length == 1 || args[1].equalsIgnoreCase("all")) {
                    for (Player p : Bukkit.getOnlinePlayers()) nametagSvc.clearFor(p);
                    msgKey(sender, "commands.nametag.clear_all.ok");
                } else {
                    Player p = Bukkit.getPlayerExact(args[1]);
                    if (p == null) { msgKey(sender, "commands.common.player_not_online"); return; }
                    nametagSvc.clearFor(p);
                    msgKey(sender, "commands.nametag.clear_one.ok", "player", p.getName());
                }
            }
            default -> usage(sender, label, "nametag <refresh|clear> [player|all]");
        }
    }

    // -------- PAPI --------
    private void handlePapi(CommandSender sender, String label, String[] args) throws NoPermission {
        if (args.length == 0) { usage(sender, label, "papi <ensure|test>"); return; }
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "ensure" -> {
                requirePerm(sender, "tabsbs.papi.ensure");
                var auto = new PapiExpansionAutoInstaller(plugin, lang);
                var sbCfg = new ScoreboardConfig(plugin);
                auto.ensureForScoreboard(sbCfg, () -> { if (sbRuntime != null) sbRuntime.refreshAll(); });
                msgKey(sender, "commands.papi.ensure.started");
            }
            case "test" -> {
                requirePerm(sender, "tabsbs.papi.test");
                if (args.length < 2) { usage(sender, label, "papi test <placeholder> [player]"); return; }
                String token = args[1];
                Player target = (args.length >= 3) ? Bukkit.getPlayerExact(args[2]) : (sender instanceof Player pl ? pl : null);
                if (target == null) { msgKey(sender, "commands.common.target_player_required"); return; }
                boolean ok = new PlaceholderValidator(plugin, lang).testPlaceholderOn(target, token);
                msgKey(sender, ok ? "commands.papi.test.ok" : "commands.papi.test.fail", "player", target.getName());
            }
            default -> usage(sender, label, "papi <ensure|test>");
        }
    }

    // -------- TEAM DEBUG --------
    private void handleTeam(CommandSender sender, String label, String[] args) throws NoPermission {
        requirePerm(sender, "tabsbs.debug.team");

        Player viewer = (sender instanceof Player pl ? pl : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null));
        if (viewer == null) { msgKey(sender, "commands.team.no_viewer_online"); return; }

        Player target = (args.length >= 1) ? Bukkit.getPlayerExact(args[0]) : viewer;
        if (target == null) { msgKey(sender, "commands.common.player_not_online"); return; }

        if (args.length >= 2) {
            Player v2 = Bukkit.getPlayerExact(args[1]);
            if (v2 != null) viewer = v2;
        }

        var sb = viewer.getScoreboard();
        Team found = null;
        for (Team t : sb.getTeams()) {
            try { if (t.hasEntry(target.getName())) { found = t; break; } } catch (Throwable ignored) {}
        }

        if (found == null) {
            msgKey(sender, "commands.team.not_in_team", "player", target.getName(), "viewer", viewer.getName());
            return;
        }

        String options = "";
        try { options = "NTVis=" + found.getOption(Team.Option.NAME_TAG_VISIBILITY) + ", Coll=" + found.getOption(Team.Option.COLLISION_RULE); } catch (Throwable ignored) {}

        msgKey(sender, "commands.team.viewer", "viewer", viewer.getName());
        msgKey(sender, "commands.team.target", "target", target.getName());
        msgKey(sender, "commands.team.team_line", "team", found.getName(), "options", options);

        // === barvy v prefix/suffix ===
        try {
            String px = ensureSectionCodes(toLegacy(found.prefix()));
            String sx = ensureSectionCodes(toLegacy(found.suffix()));
            msgKey(sender, "commands.team.prefix", "target", target.getName(), "prefix", (px == null ? "" : px));
            msgKey(sender, "commands.team.suffix", "target", target.getName(), "suffix", (sx == null ? "" : sx));
        } catch (Throwable ignored) {
            try {
                String px = ensureSectionCodes(String.valueOf(found.getPrefix()));
                msgKey(sender, "commands.team.prefix", "target", target.getName(), "prefix", px);
            } catch (Throwable ignored2) {}
            try {
                String sx = ensureSectionCodes(String.valueOf(found.getSuffix()));
                msgKey(sender, "commands.team.suffix", "target", target.getName(), "suffix", sx);
            } catch (Throwable ignored2) {}
        }
    }

    private void help(CommandSender s, String label) {
        String all = lang.format("commands.help.text", "label", label);
        for (String line : all.split("\n")) {
            msgRaw(s, line);
        }
    }

    private void version(CommandSender s) {
        msgKey(s, "commands.version",
                "version", plugin.getDescription().getVersion(),
                "server", plugin.getServer().getName(),
                "bukkit", plugin.getServer().getBukkitVersion());
    }

    // ----- messaging helpers -----
    private void msgKey(CommandSender s, String key, Object... kv) {
        s.sendMessage(lang.get("prefix") + lang.format(key, kv));
    }
    private void msgRaw(CommandSender s, String rawColored) {
        s.sendMessage(lang.get("prefix") + rawColored);
    }
    private void usage(CommandSender s, String label, String usage) {
        msgKey(s, "commands.usage", "label", label, "usage", usage);
    }
    private void requirePerm(CommandSender s, String node) throws NoPermission {
        if (s.hasPermission(node)) return;
        throw new NoPermission(node);
    }
    // --- jednoduchý helper na poslání přeložené zprávy z lang ---
    private void sendLang(CommandSender s, String key) {
        String prefix = lang.get("prefix");
        String msg = lang.get(key);
        s.sendMessage((prefix == null ? "" : prefix) + (msg == null ? key : msg));
    }
    private static final class NoPermission extends Exception { final String node; NoPermission(String n){this.node=n;} }

    // ------ TAB COMPLETER ------
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (args.length == 1) return begins(args[0], List.of("help","version","reload","sort","nametag","papi","scoreboard","team","update"));
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "sort" -> begins(args[1], List.of("now"));
                case "nametag" -> begins(args[1], List.of("refresh","clear"));
                case "papi" -> begins(args[1], List.of("ensure","test"));
                case "scoreboard" -> begins(args[1], List.of("refresh"));
                case "team" -> online(args[1]);
                case "update" -> begins(args[1], List.of("confirm","cancel"));
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "nametag" -> begins(args[2], withOnlinePlusAll());
                case "papi" -> args[1].equalsIgnoreCase("test") ? online(args[2]) : List.of();
                case "team" -> online(args[2]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> withOnlinePlusAll() {
        List<String> out = new ArrayList<>();
        out.add("all");
        out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList());
        return out;
    }
    private List<String> online(String prefix) {
        return begins(prefix, Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList());
    }
    private List<String> begins(String token, List<String> options) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).collect(Collectors.toList());
    }

    // === NOVÉ: bezpečná konverze &-kódů na §-kódy pro zobrazení barev v klientovi ===
    private static String ensureSectionCodes(String s) {
        if (s == null) return "";
        if (s.indexOf('§') >= 0) return s;
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /* =========================
       Interní downloader (bez nové třídy)
       ========================= */
    private boolean downloadLatestJar() {
        try {
            String apiUrl = plugin.getConfig().getString(
                    "updates.repo_api_url",
                    "https://api.github.com/repos/DivishCZ/TabSBS/releases/latest"
            );

            // načti JSON releasu
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TabSBS-UpdateCmd/" + plugin.getDescription().getVersion());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(15000);

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                plugin.getLogger().warning("Update(download): HTTP " + conn.getResponseCode());
                return false;
            }

            JsonObject rel;
            try (InputStreamReader in = new InputStreamReader(conn.getInputStream())) {
                rel = JsonParser.parseReader(in).getAsJsonObject();
            } finally {
                conn.disconnect();
            }

            // najdi .jar asset
            JsonArray assets = rel.getAsJsonArray("assets");
            if (assets == null || assets.size() == 0) {
                plugin.getLogger().warning("Update(download): release nemá assets.");
                return false;
            }

            String downloadUrl = null;
            for (int i = 0; i < assets.size(); i++) {
                JsonObject a = assets.get(i).getAsJsonObject();
                String name = a.get("name").getAsString();
                if (name != null && name.toLowerCase().endsWith(".jar")) {
                    if (name.toLowerCase().contains("tabsbs")) {
                        downloadUrl = a.get("browser_download_url").getAsString();
                        break;
                    }
                    if (downloadUrl == null) {
                        downloadUrl = a.get("browser_download_url").getAsString();
                    }
                }
            }
            if (downloadUrl == null) {
                plugin.getLogger().warning("Update(download): nenašel jsem .jar asset.");
                return false;
            }

            // stáhni do plugins/update/TabSBS.jar
            File updateDir = new File(plugin.getDataFolder().getParentFile(), "update");
            if (!updateDir.exists() && !updateDir.mkdirs()) {
                plugin.getLogger().warning("Update(download): nelze vytvořit složku " + updateDir.getAbsolutePath());
                return false;
            }
            File outFile = new File(updateDir, "TabSBS.jar");

            HttpURLConnection dcon = (HttpURLConnection) new URL(downloadUrl).openConnection();
            dcon.setRequestProperty("User-Agent", "TabSBS-UpdateCmd/" + plugin.getDescription().getVersion());
            dcon.setConnectTimeout(7000);
            dcon.setReadTimeout(60000);

            if (dcon.getResponseCode() != HttpURLConnection.HTTP_OK) {
                plugin.getLogger().warning("Update(download): download HTTP " + dcon.getResponseCode());
                dcon.disconnect();
                return false;
            }

            try (BufferedInputStream in = new BufferedInputStream(dcon.getInputStream());
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            } finally {
                dcon.disconnect();
            }

            plugin.getLogger().info("Update(download): staženo do " + outFile.getAbsolutePath());
            return true;

        } catch (Exception ex) {
            plugin.getLogger().warning("Update(download) selhal: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }
}
