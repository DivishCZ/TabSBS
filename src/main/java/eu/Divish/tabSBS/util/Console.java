package eu.Divish.tabSBS.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Barevné logování do konzole pomocí Adventure (legacy &-kódy).
 * - console.sendMessage(Component) = barvy se vykreslí v Paper konzoli
 * - volitelně pošle i „čistý“ text do Java loggeru (bez barev), pokud alsoLogToJava = true
 */
public final class Console {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand(); // &kódy → barvy

    private final Plugin plugin;
    private final ConsoleCommandSender console;

    /** Když true, posíláme kromě barevné konzole i odbarvený text do plugin loggeru. */
    private final boolean alsoLogToJava;

    /** Základní konstruktor: výchozí chování = posílat i do Java loggeru (true). */
    public Console(Plugin plugin) {
        this(plugin, true);
    }

    /** Rozšířený konstruktor: můžeš řídit, zda zapisovat i do Java loggeru. */
    public Console(Plugin plugin, boolean alsoLogToJava) {
        this.plugin = plugin;
        this.console = Bukkit.getConsoleSender();
        this.alsoLogToJava = alsoLogToJava;
    }

    /** Pro jistotu převádíme případné § na & (tvé langy používají &). */
    public static String ensureAmpersand(String s) {
        if (s == null) return "";
        return s.replace('§', '&');
    }

    /** INFO (barevně v konzoli + volitelně nebarevně do loggeru). */
    public void info(String msgWithAmpersands) {
        String withPrefix = "&7[" + plugin.getName() + "]&r " + ensureAmpersand(msgWithAmpersands);
        Component comp = LEGACY.deserialize(withPrefix);
        console.sendMessage(comp);
        if (alsoLogToJava) {
            plugin.getLogger().info(stripColors(withPrefix));
        }
    }

    /** WARN (žlutě). */
    public void warn(String msgWithAmpersands) {
        String withPrefix = "&7[" + plugin.getName() + "] &e" + ensureAmpersand(msgWithAmpersands);
        console.sendMessage(LEGACY.deserialize(withPrefix));
        if (alsoLogToJava) {
            plugin.getLogger().warning(stripColors(withPrefix));
        }
    }

    /** ERROR (červeně). */
    public void error(String msgWithAmpersands) {
        String withPrefix = "&7[" + plugin.getName() + "] &c" + ensureAmpersand(msgWithAmpersands);
        console.sendMessage(LEGACY.deserialize(withPrefix));
        if (alsoLogToJava) {
            plugin.getLogger().severe(stripColors(withPrefix));
        }
    }

    /** Pošle do konzole přesně to, co předáš (žádný automatický prefix ani barva navíc). */
    public void raw(String legacyAmpersands) {
        String msg = ensureAmpersand(legacyAmpersands);
        console.sendMessage(LEGACY.deserialize(msg));
        if (alsoLogToJava) {
            plugin.getLogger().info(stripColors(msg));
        }
    }

    /** Odstraní & barvy pro čistý logger výpis. */
    private static String stripColors(String legacyAmp) {
        if (legacyAmp == null) return "";
        // Převod & → § a pak prosté odbarvení
        String s = legacyAmp.replace('&', '§');
        StringBuilder out = new StringBuilder(s.length());
        char[] b = s.toCharArray();
        for (int i = 0; i < b.length; i++) {
            char c = b[i];
            if (c == '§' && i + 1 < b.length) {
                char n = Character.toLowerCase(b[i + 1]);
                // hex §x§R§R§G§G§B§B
                if (n == 'x') { i += Math.min(13, b.length - i) - 1; continue; }
                // barvy 0-9a-f a formáty k,l,m,n,o,r
                if ("0123456789abcdefklmnor".indexOf(n) >= 0) { i++; continue; }
            }
            out.append(c);
        }
        return out.toString().replaceAll("§.", "");
    }
}