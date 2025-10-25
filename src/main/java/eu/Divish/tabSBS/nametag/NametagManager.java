package eu.Divish.tabSBS.nametag;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Locale;

/**
 * Nametag manager:
 * - Prefix/suffix z Vault Chat (PEX/LuckPerms).
 * - Zabraňuje „úniku“ barvy z prefixu do nicku (přidá &r na konec prefixu).
 * - Barva nicku se řídí configem (auto_from_prefix | force | none) přes Team#color.
 * - Volitelně promítá AFK overlay (stejný styl/suffix jako v tablistu).
 * - Nezávislý modul (nepřepíná hráči scoreboard).
 */
public final class NametagManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static final String TEAM_NS = "tabsbs_nt_"; // náš vlastní team, pokud nenajdeme žádný existující

    private final Plugin plugin;
    private final Chat vaultChat; // může být null

    // --- config ---
    private final boolean enabled;
    private final String source;           // zatím "vault_chat"
    private final String colorMode;        // auto_from_prefix | force | none
    private final String forcedColor;      // použije se když mode = force
    private final boolean applyAfk;        // promítat AFK do suffixu
    private final int maxPrefixChars;
    private final int maxSuffixChars;
    private final Team.OptionStatus nameTagVisibility;

    public NametagManager(Plugin plugin, Chat vaultChat) {
        this.plugin = plugin;
        this.vaultChat = vaultChat;

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("modules.nametag");
        this.enabled = root != null && root.getBoolean("enabled", true);
        this.source = root != null ? root.getString("source", "vault_chat") : "vault_chat";

        ConfigurationSection color = root != null ? root.getConfigurationSection("color") : null;
        this.colorMode   = color != null ? color.getString("mode", "auto_from_prefix").toLowerCase(Locale.ROOT) : "auto_from_prefix";
        this.forcedColor = color != null ? color.getString("force", "&7") : "&7";

        this.applyAfk = root != null && root.getBoolean("apply_afk", true);
        this.maxPrefixChars = root != null ? root.getInt("max_prefix_chars", 32) : 32;
        this.maxSuffixChars = root != null ? root.getInt("max_suffix_chars", 32) : 32;

        String visRaw = root != null ? root.getString("name_tag_visibility", "ALWAYS") : "ALWAYS";
        this.nameTagVisibility = parseVisibility(visRaw);
    }

    // ---------------------------------------------------------------------
    // Veřejné API
    // ---------------------------------------------------------------------

    /** Aplikuj/obnov nametag pro hráče (prefix, barva nicku, suffix). */
    public void applyFor(Player p) {
        if (!enabled) return;

        Scoreboard sb = p.getScoreboard(); // nepřepínáme board (kvůli scoreboard/overlay kompatibilitě)

        // 0) Najdi existující tým, kde ten hráč už JE; když žádný → použij náš.
        Team t = findTeamContaining(sb, p.getName());
        if (t == null) {
            t = ensureTeam(sb, teamNameFor(p));
            safeAddEntry(t, p.getName());
        }

        // 1) Prefix/suffix z Vaultu
        String rawPrefix = safe(vaultChat != null ? vaultChat.getPlayerPrefix(p) : "");
        String rawSuffix = safe(vaultChat != null ? vaultChat.getPlayerSuffix(p) : "");

        // 2) Ořez pro rozumnou délku (počítá viditelné znaky, ignoruje & kódy)
        rawPrefix = truncateLegacy(rawPrefix, maxPrefixChars);
        rawSuffix = truncateLegacy(rawSuffix, maxSuffixChars);

        // 3) Zabraň „úniku barvy“ prefixu → vždy zakonči resetem &r
        String prefixIsolated = ensureEndsWithReset(rawPrefix);

        // 4) Barva nicku dle módu (auto z prefixu | force | none)
        NamedTextColor nickColor = pickNickColor(colorMode, forcedColor, rawPrefix);

        // 5) Aplikace do týmu
        t.prefix(LEGACY.deserialize(prefixIsolated));
        if (nickColor != null) t.color(nickColor);
        t.suffix(LEGACY.deserialize(applyAfk && isAfkByPapi(p)
                ? (rawSuffix + " &7[&eAFK&7]") // jednoduchá indikace; můžeš nahradit svým stylem z configu
                : rawSuffix));

        try { t.setOption(Team.Option.NAME_TAG_VISIBILITY, nameTagVisibility); } catch (Throwable ignored) {}
        try { t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER); } catch (Throwable ignored) {}
    }

    /** Uklidit při odpojení – odebereme entry z našeho NT teamu (pokud existuje). */
    public void clearFor(Player p) {
        if (!enabled) return;
        Scoreboard sb = p.getScoreboard();
        // odeber jen z našeho namespacovaného týmu (sortingové ts*** nech na pokoji)
        String nt = teamNameFor(p);
        Team t = sb.getTeam(nt);
        if (t != null) {
            try { t.removeEntry(p.getName()); } catch (Throwable ignored) {}
            if (t.getSize() <= 0) {
                try { t.unregister(); } catch (Throwable ignored) {}
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Najde tým, ve kterém už je hráč přidaný jako entry. */
    private static Team findTeamContaining(Scoreboard sb, String entry) {
        for (Team t : sb.getTeams()) {
            try {
                if (t != null && t.hasEntry(entry)) return t;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void safeAddEntry(Team t, String entry) {
        try { if (!t.hasEntry(entry)) t.addEntry(entry); } catch (Throwable ignored) {}
    }

    private String teamNameFor(Player p) {
        // krátké + unikátní; vyhni se limitu 16 znaků (název týmu)
        return TEAM_NS + Integer.toHexString(p.getUniqueId().hashCode());
    }

    private static Team ensureTeam(Scoreboard sb, String name) {
        Team t = sb.getTeam(name);
        if (t == null) {
            t = sb.registerNewTeam(name);
        }
        return t;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** Přidá na konec prefixu reset (&r), aby barva prefixu „neprotekla“ do nicku. */
    private static String ensureEndsWithReset(String legacy) {
        String s = safe(legacy).replace('§', '&');
        if (s.endsWith("&r") || s.endsWith("&R")) return s;
        return s + "&r";
    }

    /** Vybere barvu nicku dle módu. */
    private static NamedTextColor pickNickColor(String mode, String forced, String rawPrefix) {
        String effective = (mode == null ? "auto_from_prefix" : mode).toLowerCase(Locale.ROOT);
        switch (effective) {
            case "force" -> {
                ChatColor cc = parseLegacyColor(forced);
                return toNamed(cc);
            }
            case "none" -> {
                return null; // nech default od klienta
            }
            default -> { // "auto_from_prefix"
                ChatColor cc = extractLastLegacyColor(rawPrefix);
                return toNamed(cc);
            }
        }
    }

    /** Poslední skutečná barva (&0–&f) v legacy textu (ignoruje formáty &l,&n,&o,&m,&k). */
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

    /** Převede "&6" nebo "gold" → ChatColor (jen barvy). */
    private static ChatColor parseLegacyColor(String val) {
        if (val == null) return null;
        String v = val.trim().replace('§', '&');
        if (v.length() >= 2 && v.charAt(0) == '&') {
            ChatColor c = ChatColor.getByChar(Character.toLowerCase(v.charAt(1)));
            return (c != null && c.isColor()) ? c : null;
        }
        try { return ChatColor.valueOf(v.toUpperCase(Locale.ROOT)); }
        catch (Throwable ignored) { return null; }
    }

    /** Bukkit ChatColor → Adventure NamedTextColor. */
    private static NamedTextColor toNamed(ChatColor c) {
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

    /** Ořízne legacy řetězec na daný počet viditelných znaků (ignoruje & kódy). */
    private static String truncateLegacy(String legacy, int maxVisible) {
        if (legacy == null || maxVisible <= 0) return "";
        String s = legacy.replace('§', '&');
        StringBuilder out = new StringBuilder(s.length());
        int visible = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && isLegacyCode(s.charAt(i + 1))) {
                out.append(c).append(s.charAt(++i)); // zachovej kód
                continue;
            }
            if (visible >= maxVisible) break;
            out.append(c);
            visible++;
        }
        return out.toString();
    }

    private static boolean isLegacyCode(char c) {
        return "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(c) >= 0;
    }

    // AFK z EssentialsX přes PAPI (stejný placeholder jako v tablistu)
    private boolean isAfkByPapi(Player p) {
        String ph = plugin.getConfig().getString("modules.tablist.afk_display.placeholder", "%essentials_afk%");
        String raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, ph);
        if (raw != null && raw.contains("%")) raw = "";
        String norm = stripLegacyColors(safe(raw)).trim().toLowerCase(Locale.ROOT);

        java.util.List<String> trueVals = plugin.getConfig().getStringList("modules.tablist.afk_display.true_values");
        if (trueVals == null || trueVals.isEmpty()) {
            trueVals = java.util.Arrays.asList("yes","true","1");
        }
        for (String s : trueVals) if (norm.equalsIgnoreCase(s)) return true;
        return false;
    }

    /** Odbarví legacy text (pro normalizaci). */
    private static String stripLegacyColors(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace('§', '&');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && isLegacyCode(s.charAt(i + 1))) {
                i++; // přeskoč kód
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** String → Team.OptionStatus (podporuje i staré aliasy HIDE_FOR_*). */
    private static Team.OptionStatus parseVisibility(String s) {
        if (s == null) return Team.OptionStatus.ALWAYS;
        String v = s.trim().toUpperCase(Locale.ROOT);

        // Akceptuj i aliasy s "HIDE_FOR_*" (původní wording)
        switch (v) {
            case "NEVER":
                return Team.OptionStatus.NEVER;

            case "FOR_OTHER_TEAMS":
            case "HIDE_FOR_OTHER_TEAMS":
                return Team.OptionStatus.FOR_OTHER_TEAMS;

            case "FOR_OWN_TEAM":
            case "HIDE_FOR_OWN_TEAM":
                return Team.OptionStatus.FOR_OWN_TEAM;

            case "ALWAYS":
            default:
                return Team.OptionStatus.ALWAYS;
        }
    }
}
