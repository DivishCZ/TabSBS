package eu.Divish.tabSBS.tablist;

import eu.Divish.tabSBS.worlds.WorldsGate;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renderuje Tablist (header/footer) podle TablistConfigu.
 * - PAPI (pokud je k dispozici) + & barvy
 * - Efekty: scroll, rainbow, pulse
 * - PAPI cache (pro výkon)
 * - Respektování WorldsGate (volitelně)
 *
 * Úpravy:
 * - stop(): nyní navíc resetuje hráčům header/footer, aby se vypnutí projevilo hned
 * - applyNewConfig(): při změně enabled->false provede stop() (s resetem), při false->true provede start() a jednorázový push
 * - přidány aliasy applyOnce(Player) a refreshAll() pro jednotné volání z /reload
 */
public final class TablistManager {

    private final Plugin plugin;
    private final WorldsGate worldsGate; // může být null, když nechceš gate používat
    private TablistConfig cfg;

    // plánovač
    private ScheduledTask loopTask = null;
    private boolean running = false;

    // efektové stavy (globální posuvníky napříč hráči)
    private int scrollHeaderIndex = 0;
    private int scrollFooterIndex = 0;
    private int rainbowOffset = 0;
    private boolean pulseToggle = false;

    // jednoduchá cache pro PAPI (key -> (value, expireAtMs))
    private final Map<CacheKey, CacheVal> papiCache = new ConcurrentHashMap<>();

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    public TablistManager(Plugin plugin, TablistConfig cfg, WorldsGate worldsGate) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.worldsGate = worldsGate;
    }

    // ===== lifecycle =====

    public void start() {
        if (!cfg.enabled() || running) return;
        running = true;
        long ticks = toTicksCeil(cfg.updatePeriod());
        loopTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                t -> updateAll(),
                ticks, ticks
        );

        // Po startu hned jeden „push“, aby se změny projevily okamžitě, ne až po 1. ticku
        pushToAll();
    }

    public void stop() {
        if (!running) {
            // I když neběží smyčka, pokud je modul vypínán z configu, je vhodné provést reset H/F.
            resetAllHeadersFooters();
            papiCache.clear();
            return;
        }
        running = false;
        if (loopTask != null) {
            loopTask.cancel();
            loopTask = null;
        }
        // Okamžitý vizuální reset u všech online hráčů – aby vypnutí bylo vidět bez restartu
        resetAllHeadersFooters();
        papiCache.clear();
    }

    /**
     * Zavoláno po reloadu configu.
     * - resetne efekty a cache
     * - pokud došlo ke změně enabled flagu, provede start/stop
     * - po zapnutí provede okamžitý push (viz start)
     */
    public void applyNewConfig(TablistConfig newCfg) {
        boolean wasRunning = this.running;
        boolean wasEnabled = (this.cfg != null && this.cfg.enabled());

        this.cfg = newCfg;

        // reset efektů + cache
        scrollHeaderIndex = 0;
        scrollFooterIndex = 0;
        rainbowOffset = 0;
        pulseToggle = false;
        papiCache.clear();

        boolean nowEnabled = this.cfg.enabled();

        // změna stavu enabled -> reaguj
        if (wasEnabled && !nowEnabled) {
            // vypnuto – zastav a resetuj H/F
            stop();
        } else if (!wasEnabled && nowEnabled) {
            // nově zapnuto – spusť a hned pushni
            start();
        } else {
            // enabled se nezměnil: když je zapnuto a běželo, rovnou „refreshni“ všem
            if (nowEnabled && wasRunning) {
                pushToAll();
            } else if (!nowEnabled) {
                // je vypnuto – pro jistotu udrž prázdné H/F (např. když se změní texty)
                resetAllHeadersFooters();
            }
        }
    }

    /** Je smyčka spuštěná? */
    public boolean isRunning() { return running; }

    // ===== veřejné API =====

    /** Okamžitý push pouze danému hráči (použij třeba při joinu, když cfg.pushOnJoin=true). */
    public void pushTo(Player p) {
        if (!cfg.enabled()) return;
        renderAndSend(p);
    }

    /** Alias pro jednotnost s ostatními službami (volá se z reloadAll). */
    public void applyOnce(Player p) { pushTo(p); }

    /** Okamžitý push všem. */
    public void pushToAll() {
        if (!cfg.enabled()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            renderAndSend(p);
        }
    }

    /** Alias – „refreshni všem“ (použijeme z /reload). */
    public void refreshAll() { pushToAll(); }

    // ===== hlavní update loop =====

    private void updateAll() {
        if (!cfg.enabled()) return;

        // posuň stavy efektů 1x za update
        stepEffects();

        for (Player p : Bukkit.getOnlinePlayers()) {
            renderAndSend(p);
        }
    }

    private void renderAndSend(Player p) {
        // per-world gate?
        if (cfg.respectWorldsGate() && worldsGate != null) {
            if (!worldsGate.isScoreboardAllowedIn(p.getWorld())) {
                // Pokud je ve světě vypnuto, pošleme prázdný header/footer
                sendHF(p, Component.empty(), Component.empty());
                return;
            }
        }

        String headerBase = cfg.headerRaw();
        String footerBase = cfg.footerRaw();

        // PAPI
        headerBase = applyPapiWithCache(p, headerBase);
        footerBase = applyPapiWithCache(p, footerBase);

        // efekty
        headerBase = applyEffects(headerBase, true);
        footerBase = applyEffects(footerBase, false);

        // & -> Adventure
        Component h = LEGACY.deserialize(headerBase);
        Component f = LEGACY.deserialize(footerBase);

        sendHF(p, h, f);
    }

    // ===== efekty =====

    private void stepEffects() {
        // scroll posun
        var sc = cfg.scroll();
        if (sc.header()) scrollHeaderIndex += Math.max(1, sc.stepChars());
        if (sc.footer()) scrollFooterIndex += Math.max(1, sc.stepChars());

        // rainbow posun
        var rb = cfg.rainbow();
        if (rb.header() || rb.footer()) {
            rainbowOffset += Math.max(0, rb.stepPerUpdate());
        }

        // pulse toggle
        var pl = cfg.pulse();
        if (pl.header() || pl.footer()) {
            pulseToggle = !pulseToggle;
        }
    }

    private String applyEffects(String src, boolean isHeader) {
        if (src == null) src = "";

        // Scroll
        var sc = cfg.scroll();
        if ((isHeader && sc.header()) || (!isHeader && sc.footer())) {
            src = marquee(src, isHeader ? scrollHeaderIndex : scrollFooterIndex, sc.minWidth());
        }

        // Rainbow
        var rb = cfg.rainbow();
        if ((isHeader && rb.header()) || (!isHeader && rb.footer())) {
            src = rainbowize(src, rb.palette(), rainbowOffset);
        }

        // Pulse
        var pl = cfg.pulse();
        if ((isHeader && pl.header()) || (!isHeader && pl.footer())) {
            String prefix = pulseToggle ? pl.colorA() : pl.colorB();
            // prefix aplikuje barvu na každý řádek (odděleno \n)
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                lines[i] = prefix + lines[i];
            }
            src = String.join("\n", lines);
        }

        return src;
    }

    /** Jednoduchý horizontální marquee. Zachovává \n – posouvá se každá řádka zvlášť. */
    private static String marquee(String text, int index, int minWidth) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String ln = stripLegacyColors(lines[i]); // kvůli šířce ignorujeme & kódy
            int width = Math.max(minWidth, ln.length());
            String padded = padRight(lines[i], width + 1); // +1 mezera na oddělovač
            int len = visibleLength(padded);
            if (len <= 0) continue;
            int off = Math.floorMod(index, len);
            lines[i] = sliceVisibleWindow(padded, off, width);
        }
        return String.join("\n", lines);
    }

    /** Duhové obarvení – cyklicky aplikuje & barvy znak po znaku (ignoruje existující & kódy). */
    private static String rainbowize(String text, List<String> palette, int offset) {
        if (palette == null || palette.isEmpty()) palette = List.of("&f");
        String[] lines = text.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String raw = stripLegacyColors(lines[li]); // zjednodušeně odstraníme původní & kódy
            StringBuilder out = new StringBuilder(raw.length() * 3);
            int idx = offset;
            for (int i = 0; i < raw.length(); i++) {
                String color = palette.get(Math.floorMod(idx++, palette.size()));
                out.append(color).append(raw.charAt(i));
            }
            lines[li] = out.toString();
        }
        return String.join("\n", lines);
    }

    // ===== PAPI + cache =====

    private String applyPapiWithCache(Player p, String text) {
        if (text == null || text.isEmpty()) return "";
        double cacheSec = cfg.papiCacheSeconds();
        if (cacheSec <= 0.0) {
            return evalPapi(p, text);
        }
        CacheKey key = new CacheKey(p.getUniqueId(), text);
        long now = System.currentTimeMillis();
        CacheVal cv = papiCache.get(key);
        if (cv != null && cv.expiresAtMs >= now) {
            return cv.value;
        }
        String val = evalPapi(p, text);
        long ttl = (long) Math.ceil(cacheSec * 1000.0);
        papiCache.put(key, new CacheVal(val, now + ttl));
        return val;
    }

    private String evalPapi(Player p, String text) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { return PlaceholderAPI.setPlaceholders(p, text); }
            catch (Throwable ignored) {}
        }
        return text;
    }

    // ===== odeslání hráči =====

    private void sendHF(Player p, Component header, Component footer) {
        try {
            // Moderní Paper/Adventure API
            p.sendPlayerListHeaderAndFooter(header, footer);
        } catch (Throwable t) {
            // Fallback: legacy přes & → (Adventure už máme, tak raději pošleme plain)
            try {
                p.setPlayerListHeaderFooter(
                        LegacyComponentSerializer.legacySection().serialize(header),
                        LegacyComponentSerializer.legacySection().serialize(footer)
                );
            } catch (Throwable ignored) {
                // nakonec ignoruj
            }
        }
    }

    /** Resetuje header/footer všem online hráčům (prázdný Component). */
    private void resetAllHeadersFooters() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            resetHeaderFooter(p);
        }
    }

    /** Resetuje header/footer jednomu hráči. */
    private void resetHeaderFooter(Player p) {
        try {
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        } catch (Throwable t) {
            try {
                p.setPlayerListHeaderFooter("", "");
            } catch (Throwable ignored) {}
        }
    }

    // ===== utility (barvy/viditelná délka/řezání) =====

    private static long toTicksCeil(Duration d) {
        long ticks = (long) Math.ceil(d.toMillis() / 50.0);
        return Math.max(1L, ticks);
    }

    /** Viditelná délka bez &-kódů. */
    private static int visibleLength(String s) {
        return stripLegacyColors(s).length();
    }

    /** Odstraní &-kódy (včetně hex &x formátu). */
    private static String stripLegacyColors(String s) {
        if (s == null || s.isEmpty()) return "";
        char[] b = s.toCharArray();
        StringBuilder sb = new StringBuilder(b.length);
        for (int i = 0; i < b.length; i++) {
            char c = b[i];
            if (c == '&' && i + 1 < b.length) {
                char n = b[i + 1];
                // &x hex formát: &x&F&F&0&0&0&0
                if (n == 'x' || n == 'X') {
                    int jump = Math.min(13, b.length - i); // &x&?&?&?&?&?&?
                    i += jump - 1;
                    continue;
                }
                if ("0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(n) >= 0) {
                    i++; // přeskoč &kód
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Naplní mezerami na danou šířku (podle viditelné délky). */
    private static String padRight(String s, int width) {
        int vis = visibleLength(s);
        if (vis >= width) return s;
        return s + " ".repeat(width - vis);
    }

    /** Vezme z textu okno délky width od offsetu (podle viditelných znaků), cyklicky. */
    private static String sliceVisibleWindow(String s, int offset, int width) {
        // kvůli jednoduchosti použijeme stripnutou verzi pro posuv a k originálu přimalujeme jen mezeru navíc:
        String plain = stripLegacyColors(s);
        if (plain.isEmpty()) return s;
        int n = plain.length();
        StringBuilder out = new StringBuilder(width + 8);
        for (int i = 0; i < width; i++) {
            int pos = (offset + i) % n;
            out.append(plain.charAt(pos));
        }
        return out.toString();
    }

    // ===== cache datové struktury =====

    private record CacheKey(UUID playerId, String rawText) {}
    private record CacheVal(String value, long expiresAtMs) {}
}
