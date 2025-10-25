package eu.Divish.tabSBS;

import eu.Divish.tabSBS.boot.DependencyGuard;
import eu.Divish.tabSBS.lang.LangManager;
import eu.Divish.tabSBS.util.Console;
import eu.Divish.tabSBS.papi.PapiExpansionAutoInstaller;
import eu.Divish.tabSBS.papi.PapiValidationListener;
import eu.Divish.tabSBS.papi.PlaceholderValidator;
import eu.Divish.tabSBS.scoreboard.ScoreboardConfig;
import eu.Divish.tabSBS.scoreboard.ScoreboardRuntime;
import eu.Divish.tabSBS.worlds.WorldsGate;
import eu.Divish.tabSBS.worlds.WorldsListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import net.kyori.adventure.text.Component;
import eu.Divish.tabSBS.util.UpdateChecker; // NOVĚ: updater
import net.kyori.adventure.text.event.ClickEvent; // NOVĚ: tlačítka
import net.kyori.adventure.text.format.NamedTextColor; // NOVĚ
import net.kyori.adventure.text.format.TextDecoration; // NOVĚ
import org.bukkit.entity.Player; // NOVĚ
import java.util.Set; // NOVĚ
import java.util.UUID; // NOVĚ
import java.util.concurrent.ConcurrentHashMap; // NOVĚ

import java.util.Objects;

// NOVĚ: pro deserializaci &/§ z lang
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TabSBS extends JavaPlugin {

    // --- runtime držáky ---
    private LangManager lang;
    private ScoreboardRuntime sbRuntime;
    private WorldsGate worldsGate;

    // šířka ASCII rámečku (v počtu viditelných znaků)
    private static final int BOX_WIDTH = 88;

    // Vault services (pro TabSortingService)
    private net.milkbowl.vault.permission.Permission vaultPerms;
    private net.milkbowl.vault.chat.Chat vaultChat;

    // Tablist moduly
    private eu.Divish.tabSBS.tablist.TablistConfig tabCfg;
    private eu.Divish.tabSBS.tablist.TablistManager tablistMgr;
    private eu.Divish.tabSBS.tablist.TabSortingService tabSorting;
    // --- TABLIST: fields END ---

    // Nametag modul
    private eu.Divish.tabSBS.nametag.NametagConfig nametagCfg;
    private eu.Divish.tabSBS.nametag.NametagService nametagSvc;

    // --- UPDATER: stav a session volby ---
    private volatile boolean updateAvailable = false;
    private volatile String latestVersion = "";
    private final Set<UUID> declinedThisBoot = ConcurrentHashMap.newKeySet();


    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.lang = new LangManager(this);
        lang.reload();

        final Console cons = new Console(this, false); // true = pošle i „čistý“ text do loggeru
        // 2) závislosti (Vault + PAPI)
        if (!DependencyGuard.ensureHardDependencies(this, lang)) {
            return; // plugin byl vypnut uvnitř guardu
        }

        // 3) načtení scoreboard configu
        ScoreboardConfig sbCfg = new ScoreboardConfig(this);

        // 4) runtime (manager + overlay) – zatím jen připraveno
        this.sbRuntime = new ScoreboardRuntime(this, sbCfg);

        // 5) světy (whitelist/blacklist) + listener
        this.worldsGate = new WorldsGate(this);
        Bukkit.getPluginManager().registerEvents(new WorldsListener(this, worldsGate, sbRuntime.manager()), this);

        // === TABLIST: INIT START ===

        // Vault services
        var rspPerms = getServer().getServicesManager().getRegistration(Permission.class);
        this.vaultPerms = (rspPerms != null) ? rspPerms.getProvider() : null;

        var rspChat = getServer().getServicesManager().getRegistration(Chat.class);
        this.vaultChat = (rspChat != null) ? rspChat.getProvider() : null;

        if (this.vaultPerms == null) {
            cons.warn(lang.get("tablist.vault.no_perm_provider"));
        }
        if (this.vaultChat == null) {
            cons.warn(lang.get("tablist.vault.no_chat_provider"));
        }

        // --- NAMETAG: INIT (PŘESUNUTO VÝŠ kvůli anti-blink integraci se sortingem) ---
        this.nametagCfg = new eu.Divish.tabSBS.nametag.NametagConfig(this);
        this.nametagSvc = new eu.Divish.tabSBS.nametag.NametagService(this, nametagCfg, this.worldsGate, this.vaultPerms, this.vaultChat);

        // Listener nad service
        getServer().getPluginManager().registerEvents(
                new eu.Divish.tabSBS.nametag.NametagListeners(this, nametagSvc),
                this
        );

        // první apply po startu + periodická smyčka dle configu
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> nametagSvc.applyAll(), 20L);
        if (nametagCfg.enabled()) nametagSvc.start();
        // --- NAMETAG: INIT END ---

        // Tablist config + managery
        this.tabCfg = eu.Divish.tabSBS.tablist.TablistConfig.load(this);
        this.tablistMgr = new eu.Divish.tabSBS.tablist.TablistManager(this, tabCfg, this.worldsGate);

        // PŘEDÁNÍ nametagSvc do TabSortingService (anti-blink při přesazení do tsNNN týmů)
        this.tabSorting = new eu.Divish.tabSBS.tablist.TabSortingService(
                this, tabCfg, this.worldsGate, this.vaultPerms, this.vaultChat, this.nametagSvc
        );

        // Listener
        getServer().getPluginManager().registerEvents(
                new eu.Divish.tabSBS.tablist.TablistListeners(this, tabCfg, tablistMgr, tabSorting, this.worldsGate),
                this
        );

        // start smyček jen pokud povoleno
        if (tabCfg.enabled()) {
            this.tablistMgr.start();
            this.tabSorting.start();
        }

        // === TABLIST: INIT END ===

        // 6) PAPI: auto instalační helper + validace placeholderů
        PapiExpansionAutoInstaller auto = new PapiExpansionAutoInstaller(this, lang);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> {
            auto.ensureForScoreboard(sbCfg, () -> {
                // po /papi reload přerenderuj všechny hráče
                if (sbRuntime != null) sbRuntime.refreshAll();
            });

            Bukkit.getPluginManager().registerEvents(
                    new PapiValidationListener(this, new PlaceholderValidator(this, lang), sbCfg, lang),
                    this
            );
        }, 40L);
        // ~2s po startu

        // 7) spuštění scoreboard smyčky (pokud povoleno v configu)
        sbRuntime.start();

        // 8) ASCII start message
        sendStartupMessage();

        // 9) příkazy
        var cmd = new eu.Divish.tabSBS.commands.TabsbsCommand(
                this,
                this.lang,
                this.sbRuntime,
                this.tabSorting,
                this.nametagSvc   // <— Sem jde NametagService
        );
        Objects.requireNonNull(getCommand("tabsbs")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("tabsbs")).setTabCompleter(cmd);

        // === UPDATE CHECK ===
        new UpdateChecker(this).checkForUpdate();

// Po joinu ukaž výzvu jen pokud JE dostupná aktualizace (žádné hlášky, když je plugin aktuální)
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                Player player = event.getPlayer();
                showUpdatePrompt(player, true); // tahle metoda sama nic neposílá, pokud updateAvailable == false
            }
        }, this);
    }

    @Override
    public void onDisable() {
        if (sbRuntime != null) {
            sbRuntime.stop();
        }
        // TABLIST stop
        if (tabSorting != null) {
            try {
                tabSorting.stop();
            } catch (Throwable ignored) {
            }
        }
        if (tablistMgr != null) {
            try {
                tablistMgr.stop();
            } catch (Throwable ignored) {
            }
        }
        if (nametagSvc != null) {
            try {
                nametagSvc.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Odešle do konzole dvousloupcový ASCII box: vlevo DVSH 5x5 bloky (D upravené), vpravo info.
     */
    private void sendStartupMessage() {
        final String vPlugin = getDescription().getVersion();
        final String vPaper = getServer().getBukkitVersion();
        final String srvName = getServer().getName();

        // Levý sloupec – 5x5 bloky (D upravené, ať nevypadá jako O)
        String[] left = new String[]{
                "&a████  " + "&9█   █" + "  " + "&e████" + "  " + "&c█  █&8",
                "&a█   █ " + "&9█   █" + "  " + "&e█" + "     " + "&c█  █&8",
                "&a█   █ " + "&9 █ █ " + "  " + "&e███" + "   " + "&c████&8",
                "&a█   █ " + "&9 █ █ " + "  " + "&e   █" + "  " + "&c█  █&8",
                "&a████  " + "&9  █  " + "  " + "&e████" + "  " + "&c█  █&8"
        };

        // Pravý sloupec – informační řádky
        String[] right = {
                "",
                lang.format("startup.box.created_by", "author", "Divish"),
                lang.format("startup.box.version_plugin", "version", vPlugin),
                lang.format("startup.box.version_server", "server", srvName, "bukkit", vPaper)
        };

        // výpočet šířek
        final int gap = 5; // mezera mezi sloupci
        int leftMax = 0;
        for (String s : left) leftMax = Math.max(leftMax, visibleLength(s));
        int rightWidth = Math.max(0, BOX_WIDTH - leftMax - gap);

        String border = color("&8+" + "-".repeat(BOX_WIDTH) + "+");
        getServer().getConsoleSender().sendMessage(border);

        int lines = Math.max(left.length, right.length);
        for (int i = 0; i < lines; i++) {
            String L = i < left.length ? left[i] : "";
            String R = i < right.length ? right[i] : "";

            String leftPadded = L + " ".repeat(Math.max(0, leftMax - visibleLength(L)));
            String rightPadded = padToWidth(R, rightWidth);

            String line = "&8|" + leftPadded + " ".repeat(gap) + rightPadded + "|";
            getServer().getConsoleSender().sendMessage(color(line));
        }

        getServer().getConsoleSender().sendMessage(border);
    }
    // někam do class TabSBS (např. pod sendStartupMessage)
    public LangManager getLangManager() {
        return this.lang;
    }

    /* =========================
       Updater – veřejné API
       ========================= */
    public void setUpdateAvailable(boolean updateAvailable, String latestVersion) {
        this.updateAvailable = updateAvailable;
        this.latestVersion = latestVersion == null ? "" : latestVersion;
    }
    public boolean isUpdateAvailable() { return updateAvailable; }
    public String getLatestVersion() { return latestVersion; }
    public boolean hasDeclinedThisBoot(UUID uuid) { return declinedThisBoot.contains(uuid); }
    public void markDeclinedThisBoot(UUID uuid) { declinedThisBoot.add(uuid); }

    /** Vrátí verzi s 'v' prefixem pro zobrazení. */
    private String displayVersion() {
        String v = latestVersion == null ? "" : latestVersion.trim();
        if (v.isEmpty()) return v;
        return (v.startsWith("v") || v.startsWith("V")) ? v : "v" + v;
    }

    /** Kompatibilní alias pro UI: vrací poslední známou verzi s 'v' (nebo prázdný řetězec). */
    public String getLatestVersionSafe() {
        return displayVersion();
    }
    /** URL releasu pro tlačítko „open“ */
    private String getReleaseUrl() {
        String tag = getLatestVersion();
        if (tag != null && !tag.isEmpty()) {
            // normálně "vX.Y.Z", zachováme přesně co přišlo z API
            return "https://github.com/DivishCZ/TabSBS/releases/tag/" + tag;
        }
        return "https://github.com/DivishCZ/TabSBS/releases/latest";
    }

    /** Lokalizační helper: vrátí text z LangManageru, nikdy null. */
    private String L(String key) {
        String s = (lang == null ? "" : lang.get(key));
        return s == null ? "" : s;
    }

    /** Pošli stav updateru do chatu hráči (up-to-date i dostupná aktualizace), s ohledem na perm nebo OP. */
    public void sendUpdateStatus(Player player) {
        if (!(player.isOp() || player.hasPermission("tabsbs.update"))) return;

        if (isUpdateAvailable()) {
            // „Nová verze ... je dostupná“ (lang: update.available s %version%)
            String raw = L("update.available").replace("%version%", getLatestVersionSafe());
            if (!raw.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
            } else {
                // fallback na původní hlavičku
                player.sendMessage(buildUpdateHeaderLineFull());
            }
            String wish = L("update.wish_update");
            if (!wish.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(wish));
            }
            player.sendMessage(buildUpdateButtons()); // lokalizovaná tlačítka
        } else {
            String noUpd = L("update.no_update_available");
            if (!noUpd.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(noUpd));
            }
        }
    }

    /**
     * Sjednocené zobrazení informace o updatu (zpětně kompatibilní varianta).
     * @param player          cílový hráč
     * @param afterJoinDelay  true = po joinu s ~3s zpožděním; false = hned
     */
    public void showUpdatePrompt(Player player, boolean afterJoinDelay) {
        if (!isUpdateAvailable()) return;
        if (!(player.isOp() || player.hasPermission("tabsbs.update"))) return;

        Runnable task = () -> {
            if (hasDeclinedThisBoot(player.getUniqueId())) {
                // už dříve zrušil → jen krátké info, bez otázky a bez tlačítek
                player.sendMessage(buildUpdateInfoLineOnly());
                return;
            }

            // Lokalizovaná hlavička (pokud je v langu), jinak fallback
            String raw = L("update.available").replace("%version%", getLatestVersionSafe());
            if (!raw.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(raw));
            } else {
                player.sendMessage(buildUpdateHeaderLineFull());
            }

            String wish = L("update.wish_update");
            if (!wish.isEmpty()) {
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(wish));
            }
            player.sendMessage(buildUpdateButtons());
        };

        if (afterJoinDelay) {
            Bukkit.getScheduler().runTaskLater(this, task, 60L); // ~3 sekundy
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    /* ============ UI stavebnice (Adventure) ============ */

    // Krátká informační verze – bez otázky a bez tlačítek (použito po "cancel" do restartu)
    private Component buildUpdateInfoLineOnly() {
        return Component.empty()
                .append(Component.text("[TabSBS] ", NamedTextColor.YELLOW))
                .append(Component.text("Nová verze pluginu ", NamedTextColor.GRAY))
                .append(Component.text("TabSBS", NamedTextColor.GREEN))
                .append(Component.text(" (" + displayVersion() + ") je dostupná.", NamedTextColor.GRAY));
    }

    // Plná hlavička (fallback pokud chybí lang klíče)
    private Component buildUpdateHeaderLineFull() {
        return Component.empty()
                .append(Component.text("[TabSBS] ", NamedTextColor.YELLOW))
                .append(Component.text("Nová verze pluginu ", NamedTextColor.GRAY))
                .append(Component.text("TabSBS", NamedTextColor.GREEN))
                .append(Component.text(" (" + displayVersion() + ") je dostupná.", NamedTextColor.GRAY));
    }

    // Interaktivní tlačítka – ✅ confirm / ❌ cancel / 🌐 open (lokalizovaná)
    public Component buildUpdateButtons(LangManager lang) {
        String confirm = lang.get("update.confirm");
        String cancel  = lang.get("update.cancel");
        String openLbl = lang.get("update.open");
        if (confirm == null || confirm.isEmpty()) confirm = "Aktualizovat";
        if (cancel  == null || cancel.isEmpty())  cancel  = "Zrušit";
        if (openLbl == null || openLbl.isEmpty()) openLbl = "Otevřít release";

        String releaseUrl = getReleaseUrl();

        // Texty z lang mohou obsahovat &/§ → použijeme deserializér
        Component cConfirm = LegacyComponentSerializer.legacyAmpersand().deserialize(confirm);
        Component cCancel  = LegacyComponentSerializer.legacyAmpersand().deserialize(cancel);
        Component cOpen    = LegacyComponentSerializer.legacyAmpersand().deserialize(openLbl);

        return Component.empty()
                .append(Component.text("[✅ ", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .append(cConfirm)
                        .append(Component.text("]", NamedTextColor.GREEN, TextDecoration.BOLD))
                        .clickEvent(ClickEvent.runCommand("/tabsbs update confirm")))
                .append(Component.text("  "))
                .append(Component.text("[❌ ", NamedTextColor.RED, TextDecoration.BOLD)
                        .append(cCancel)
                        .append(Component.text("]", NamedTextColor.RED, TextDecoration.BOLD))
                        .clickEvent(ClickEvent.runCommand("/tabsbs update cancel")))
                .append(Component.text("  "))
                .append(Component.text("[🌐 ", NamedTextColor.AQUA, TextDecoration.BOLD)
                        .append(cOpen)
                        .append(Component.text("]", NamedTextColor.AQUA, TextDecoration.BOLD))
                        .clickEvent(ClickEvent.openUrl(releaseUrl)));
    }

    // kompatibilní zkratka – použij aktuální lang
    public Component buildUpdateButtons() {
        return buildUpdateButtons(this.lang);
    }

    /**
     * Vyplní řetězec mezerami na přesnou šířku rámečku (počítá viditelnou délku bez &-barev).
     */
    private String padToWidth(String s, int width) {
        int visible = visibleLength(s);
        int spaces = Math.max(0, width - visible);
        return s + " ".repeat(spaces);
    }

    /**
     * Spočítá viditelnou délku (ignoruje &-barevné kódy typu &a, &9, &l, &r, atd.).
     */
    private int visibleLength(String s) {
        if (s == null) return 0;
        return s.replaceAll("&[0-9A-FK-ORa-fk-or]", "").length();
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // TabSBS.java
    public void reloadAll() {
        // 1) stop – bezpečně vypni běžící smyčky
        try { if (tabSorting != null) tabSorting.stop(); } catch (Throwable ignored) {}
        try { if (tablistMgr != null) tablistMgr.stop(); } catch (Throwable ignored) {}
        try { if (sbRuntime != null) sbRuntime.stop(); } catch (Throwable ignored) {}
        try { if (nametagSvc != null) nametagSvc.stop(); } catch (Throwable ignored) {}

        // 2) config + jazyky
        reloadConfig();
        lang.reload();
        if (worldsGate != null) worldsGate.reload();

        // 3) načti nové konfigurace modulů
        // TABLIST
        this.tabCfg = eu.Divish.tabSBS.tablist.TablistConfig.load(this);
        if (tablistMgr != null) tablistMgr.applyNewConfig(tabCfg);
        if (tabSorting != null) tabSorting.applyNewConfig(tabCfg);

        // SCOREBOARD
        ScoreboardConfig sbCfg = new ScoreboardConfig(this);
        if (sbRuntime != null) sbRuntime.applyNewConfig(sbCfg);

        // NAMETAG
        this.nametagCfg = new eu.Divish.tabSBS.nametag.NametagConfig(this);
        if (nametagSvc != null) nametagSvc.applyNewConfig(nametagCfg);

        // 4) znovu spustit jen povolené moduly
        if (tabCfg.enabled()) {
            if (tablistMgr != null) tablistMgr.start();
            if (tabSorting != null) tabSorting.start();
        }
        if (sbCfg.enabled()) {
            if (sbRuntime != null) sbRuntime.start();
        }
        if (nametagCfg.enabled()) {
            if (nametagSvc != null) nametagSvc.start();
        }

        // 5) okamžité promítnutí stavu na online hráče
        Bukkit.getOnlinePlayers().forEach(p -> {
            // TABLIST: když je vypnuto, pošleme prázdný header/footer
            if (tabCfg.enabled()) {
                if (tabCfg.pushOnJoin() && tablistMgr != null) tablistMgr.pushTo(p);
            } else {
                if (tablistMgr != null) {
                    // prázdný push (TablistManager to vyhodnotí bez world gate); nebo prostě empty H/F
                    try {
                        p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                    } catch (Throwable ignored) {
                        // fallback pro starší Spigot API (pokud existuje)
                        try { p.setPlayerListHeaderFooter("", ""); } catch (Throwable ignored2) {}
                    }
                }
            }

            // SCOREBOARD
            if (sbCfg.enabled()) {
                if (sbRuntime != null) sbRuntime.applyOnce(p);
            } else {
                if (sbRuntime != null) sbRuntime.clearFor(p);
            }

            // NAMETAG
            if (nametagCfg.enabled()) {
                if (nametagSvc != null) nametagSvc.applyFor(p);
            } else {
                if (nametagSvc != null) nametagSvc.clearFor(p); // hned zruš prefix/suffix i na ts… týmech
            }
        });
    }
}
