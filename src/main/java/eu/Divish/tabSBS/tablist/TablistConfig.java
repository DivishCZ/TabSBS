package eu.Divish.tabSBS.tablist;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Konfigurační wrapper pro modules.tablist.
 * - Pouze & barvy (render se řeší jinde).
 * - Sekundy v double → Duration (Duration.ofMillis(...)).
 */
public final class TablistConfig {

    private final boolean enabled;
    private final Duration updatePeriod;

    // header/footer
    private final String headerRaw;
    private final String footerRaw;

    // efekty
    private final Effects.ScrollCfg scroll;
    private final Effects.RainbowCfg rainbow;
    private final Effects.PulseCfg pulse;

    // řazení
    private final SortingCfg sorting;

    // pokročilé
    private final double papiCacheSeconds;
    private final boolean respectWorldsGate;
    private final boolean pushOnJoin;

    public static TablistConfig load(Plugin plugin) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("modules.tablist");
        if (root == null) root = plugin.getConfig().createSection("modules.tablist");

        boolean enabled = root.getBoolean("enabled", true);

        Duration upd = fromSeconds(root.getDouble("update_delay_seconds", 1.0));

        String header = root.getString("header", "&6Vítej, %player_name%!\n&aUžij si hru!");
        String footer = root.getString("footer", "&eOnline hráči: &b%server_online%");

        // effects
        ConfigurationSection effSec = root.getConfigurationSection("effects");
        if (effSec == null) effSec = root.createSection("effects");

        // scroll
        ConfigurationSection scrollSec = effSec.getConfigurationSection("scroll");
        if (scrollSec == null) scrollSec = effSec.createSection("scroll");
        boolean scrollHeader = scrollSec.getBoolean("header", false);
        boolean scrollFooter = scrollSec.getBoolean("footer", false);
        int scrollStep = Math.max(1, scrollSec.getInt("speed_chars_per_step", 1));
        int scrollMinWidth = Math.max(1, scrollSec.getInt("min_width", 40));
        Effects.ScrollCfg scrollCfg = new Effects.ScrollCfg(scrollHeader, scrollFooter, scrollStep, scrollMinWidth);

        // rainbow
        ConfigurationSection rainbowSec = effSec.getConfigurationSection("rainbow");
        if (rainbowSec == null) rainbowSec = effSec.createSection("rainbow");
        boolean rainbowHeader = rainbowSec.getBoolean("header", false);
        boolean rainbowFooter = rainbowSec.getBoolean("footer", false);
        List<String> palette = sanitizePalette(rainbowSec.getStringList("palette"));
        int rainbowStep = Math.max(0, rainbowSec.getInt("step_per_update", 1));
        Effects.RainbowCfg rainbowCfg = new Effects.RainbowCfg(rainbowHeader, rainbowFooter, palette, rainbowStep);

        // pulse
        ConfigurationSection pulseSec = effSec.getConfigurationSection("pulse");
        if (pulseSec == null) pulseSec = effSec.createSection("pulse");
        boolean pulseHeader = pulseSec.getBoolean("header", false);
        boolean pulseFooter = pulseSec.getBoolean("footer", false);
        String colorA = pulseSec.getString("color_a", "&f");
        String colorB = pulseSec.getString("color_b", "&7");
        Effects.PulseCfg pulseCfg = new Effects.PulseCfg(pulseHeader, pulseFooter, colorA, colorB);

        // sorting
        ConfigurationSection sortSec = root.getConfigurationSection("sorting");
        if (sortSec == null) sortSec = root.createSection("sorting");
        boolean sortEnabled = sortSec.getBoolean("enabled", true);
        String modeStr = sortSec.getString("mode", "group");
        SortingMode mode = SortingMode.fromString(modeStr);

        String source = sortSec.getString("source", "vault_chat");

        List<String> priority = sortSec.getStringList("priority");
        if (priority == null) priority = Collections.emptyList();
        priority = copyTrimLower(priority);

        int defaultPriority = sortSec.getInt("default_priority", -1);

        String tieStr = sortSec.getString("tie_breaker_name", "asc");
        TieBreakerName tie = TieBreakerName.fromString(tieStr);

        int teamPadding = Math.max(0, Math.min(4, sortSec.getInt("team_number_padding", 2)));
        boolean decorateNames = sortSec.getBoolean("decorate_names", true);

        boolean enforceProto = sortSec.getBoolean("enforce_via_protocollib", false);

        SortingCfg sortingCfg = new SortingCfg(
                sortEnabled, mode, source, priority, defaultPriority, tie, teamPadding, decorateNames, enforceProto
        );

        // advanced
        double papiCacheSec = Math.max(0.0, root.getDouble("papi_cache_seconds", 0.25));
        boolean respectGate = root.getBoolean("respect_worlds_gate", true);
        boolean pushJoin = root.getBoolean("push_on_join", true);

        return new TablistConfig(
                enabled, upd, header, footer,
                scrollCfg, rainbowCfg, pulseCfg,
                sortingCfg, papiCacheSec, respectGate, pushJoin
        );
    }

    private TablistConfig(
            boolean enabled,
            Duration updatePeriod,
            String headerRaw,
            String footerRaw,
            Effects.ScrollCfg scroll,
            Effects.RainbowCfg rainbow,
            Effects.PulseCfg pulse,
            SortingCfg sorting,
            double papiCacheSeconds,
            boolean respectWorldsGate,
            boolean pushOnJoin
    ) {
        this.enabled = enabled;
        this.updatePeriod = updatePeriod;
        this.headerRaw = headerRaw != null ? headerRaw : "";
        this.footerRaw = footerRaw != null ? footerRaw : "";
        this.scroll = scroll;
        this.rainbow = rainbow;
        this.pulse = pulse;
        this.sorting = sorting;
        this.papiCacheSeconds = papiCacheSeconds;
        this.respectWorldsGate = respectWorldsGate;
        this.pushOnJoin = pushOnJoin;
    }

    // ===== getters =====

    public boolean enabled() { return enabled; }
    public Duration updatePeriod() { return updatePeriod; }

    public String headerRaw() { return headerRaw; }
    public String footerRaw() { return footerRaw; }

    public Effects.ScrollCfg scroll() { return scroll; }
    public Effects.RainbowCfg rainbow() { return rainbow; }
    public Effects.PulseCfg pulse() { return pulse; }

    public SortingCfg sorting() { return sorting; }

    public double papiCacheSeconds() { return papiCacheSeconds; }
    public boolean respectWorldsGate() { return respectWorldsGate; }
    public boolean pushOnJoin() { return pushOnJoin; }

    // ===== helpers =====

    private static Duration fromSeconds(double seconds) {
        if (seconds <= 0) return Duration.ofMillis(50); // bezpečné minimum 1 tick
        long ms = (long) Math.ceil(seconds * 1000.0);
        return Duration.ofMillis(ms);
    }

    private static List<String> sanitizePalette(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            List<String> def = new ArrayList<>();
            Collections.addAll(def, "&c","&6","&e","&a","&b","&9","&d");
            return def;
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            // necháváme tak jak je, očekáváme &X/&#RRGGBB (legacy/hex via translate jinde)
            out.add(s);
        }
        return out.isEmpty() ? List.of("&f") : out;
    }

    private static List<String> copyTrimLower(List<String> src) {
        if (src == null || src.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(src.size());
        for (String s : src) {
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ===== nested types =====

    public static final class Effects {
        public record ScrollCfg(boolean header, boolean footer, int stepChars, int minWidth) {}
        public record RainbowCfg(boolean header, boolean footer, List<String> palette, int stepPerUpdate) {}
        public record PulseCfg(boolean header, boolean footer, String colorA, String colorB) {}
    }

    public enum SortingMode {
        NONE, GROUP, PREFIX;

        public static SortingMode fromString(String s) {
            if (s == null) return GROUP;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "none" -> NONE;
                case "prefix" -> PREFIX;
                default -> GROUP;
            };
        }
    }

    public enum TieBreakerName {
        ASC, DESC;

        public static TieBreakerName fromString(String s) {
            if (s == null) return ASC;
            return "desc".equalsIgnoreCase(s) ? DESC : ASC;
        }
    }

    public static final class SortingCfg {
        private final boolean enabled;
        private final SortingMode mode;
        private final String source;
        private final List<String> priority; // already lowercase
        private final int defaultPriority;
        private final TieBreakerName tieBreakerName;
        private final int teamNumberPadding;
        private final boolean decorateNames;
        private final boolean enforceViaProtocolLib; // ← NOVÉ

        public SortingCfg(boolean enabled, SortingMode mode, String source, List<String> priority,
                          int defaultPriority, TieBreakerName tieBreakerName,
                          int teamNumberPadding, boolean decorateNames,
                          boolean enforceViaProtocolLib) { // ← NOVÉ{
            this.enabled = enabled;
            this.mode = mode;
            this.source = (source == null ? "vault_chat" : source);
            this.priority = Collections.unmodifiableList(priority == null ? Collections.emptyList() : priority);
            this.defaultPriority = defaultPriority;
            this.tieBreakerName = tieBreakerName;
            this.teamNumberPadding = teamNumberPadding;
            this.decorateNames = decorateNames;
            this.enforceViaProtocolLib = enforceViaProtocolLib; // ← NOVÉ
        }

        public boolean enabled() { return enabled; }
        public SortingMode mode() { return mode; }
        public String source() { return source; }
        public List<String> priority() { return priority; }
        public int defaultPriority() { return defaultPriority; }
        public TieBreakerName tieBreakerName() { return tieBreakerName; }
        public int teamNumberPadding() { return teamNumberPadding; }
        public boolean decorateNames() { return decorateNames; }
        public boolean enforceViaProtocolLib() { return enforceViaProtocolLib; } // ← NOVÝ GETTER

    }
}
