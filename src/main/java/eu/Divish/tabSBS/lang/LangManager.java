package eu.Divish.tabSBS.lang;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LangManager – jednoduchý i18n loader pro jazyky cz/en/de.
 * Všechny hlášky se berou z jar resources: /lang/<code>.yml.
 * Pokud soubor v data folderu chybí, zkopíruje se z jar.
 *
 * Konfigurace:
 *   config.yml -> language: cz|en|de  (default: cz)
 *
 * Použití:
 *   LangManager lang = new LangManager(plugin);
 *   lang.reload(); // při startu a při /reload
 *   String s = lang.get("startup.enabled");
 *   String t = lang.format("reload.ok", Map.of("lang", lang.getActiveCode()));
 */
public class LangManager {

    private final Plugin plugin;
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    private FileConfiguration activeLang;
    private String activeCode = "cz";

    private static final String[] SUPPORTED = {"cz", "en", "de"};

    public LangManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Načti/obnov jazyky podle configu. Volat při onEnable a po reloadu configu. */
    public void reload() {
        languages.clear();
        ensureLangFolder();
        loadLanguagesIntoCache();
        selectActiveFromConfig();
    }

    /** Získej zprávu podle klíče v aktivním jazyce. Vrací barevný text s & kódy převedenými. */
    public String get(String key) {
        String raw = (activeLang == null) ? null : activeLang.getString(key);
        if (raw == null) {
            // fallback: zkus en
            FileConfiguration en = languages.get("en");
            raw = (en != null) ? en.getString(key) : null;
        }
        if (raw == null) raw = "Missing lang: " + key;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Formátování placeholderů ve tvaru {name} → value. */
    public String format(String key, Map<String, String> placeholders) {
        String out = get(key);
        if (placeholders == null || placeholders.isEmpty()) return out;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    /** Krátká varianta: klíč-hodnota-klič-hodnota... např. format("reload.lang", "lang", "cz") */
    public String format(String key, Object... kvPairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            map.put(String.valueOf(kvPairs[i]), String.valueOf(kvPairs[i + 1]));
        }
        return format(key, map);
    }

    /** Získání zprávy z konkrétního jazyka (obchází aktivní volbu). */
    public String getMessage(String path, String langCode) {
        String code = normalize(langCode);
        FileConfiguration lang = languages.get(code);
        if (lang == null) return "";
        String raw = lang.getString(path, "");
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** Aktuálně zvolený jazyk (cz/en/de). */
    public String getActiveCode() {
        return activeCode;
    }

    // ===== Interní metody =====

    private void ensureLangFolder() {
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists()) {
            // vytvoř data folder i lang/ složku
            plugin.getDataFolder().mkdirs();
            dir.mkdirs();
        }
    }

    private void loadLanguagesIntoCache() {
        for (String lang : SUPPORTED) {
            File outFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");

            if (!outFile.exists()) {
                // zkopíruj default z jar (nebude přepisovat existující)
                plugin.saveResource("lang/" + lang + ".yml", false);
            }

            FileConfiguration cfg = YamlConfiguration.loadConfiguration(outFile);

            // nastav defaulty z jar resource, aby chybějící klíče měly fallback
            try (InputStream is = plugin.getResource("lang/" + lang + ".yml")) {
                if (is != null) {
                    YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
                    cfg.setDefaults(def);
                }
            } catch (Exception ignored) {}

            languages.put(lang, cfg);
        }
    }

    private void selectActiveFromConfig() {
        String selected = plugin.getConfig().getString("language", "cz");
        String norm = normalize(selected);
        this.activeLang = languages.getOrDefault(norm, languages.get("cz"));
        this.activeCode = (this.activeLang == null) ? "cz" : norm;
    }

    private String normalize(String code) {
        if (code == null) return "cz";
        String c = code.toLowerCase(Locale.ROOT);
        return switch (c) {
            case "cs", "cz" -> "cz";
            case "en" -> "en";
            case "de" -> "de";
            default -> "cz";
        };
    }
}
