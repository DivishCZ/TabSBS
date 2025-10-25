package eu.Divish.tabSBS.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.Divish.tabSBS.TabSBS;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Asynchronně ověří nejnovější release na GitHubu a při nalezení novější verze:
 *  - nastaví stav v pluginu (updateAvailable, latestVersion)
 *  - sjednoceně upozorní všechny online OP/perm hráče přes TabSBS.sendUpdateStatus(...).
 *
 * Řídí se configem:
 *   updates.enabled: true/false
 *   updates.repo_api_url: "https://api.github.com/repos/DivishCZ/TabSBS/releases/latest"
 */
public final class UpdateChecker {

    private final TabSBS plugin;

    public UpdateChecker(TabSBS plugin) {
        this.plugin = plugin;
    }

    public void checkForUpdate() {
        if (!plugin.getConfig().getBoolean("updates.enabled", false)) return;

        final String apiUrl = plugin.getConfig().getString(
                "updates.repo_api_url",
                "https://api.github.com/repos/DivishCZ/TabSBS/releases/latest"
        );

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                // GitHub vyžaduje User-Agent + rozumné timeouty
                PluginDescriptionFile desc = plugin.getDescription();
                conn.setRequestProperty("User-Agent", "TabSBS-UpdateChecker/" + desc.getVersion());
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int status = conn.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    plugin.getLogger().warning("Kontrola aktualizací selhala: HTTP " + status);
                    return;
                }

                String latestTag;
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    JsonObject json = JsonParser.parseReader(in).getAsJsonObject();
                    latestTag = json.get("tag_name").getAsString(); // např. "v1.2.3"
                } finally {
                    conn.disconnect();
                }

                String current = desc.getVersion();
                String latestNorm  = normalizeVersion(latestTag);
                String currentNorm = normalizeVersion(current);

                boolean isNewer = isNewerSemver(latestNorm, currentNorm);
// ... zbytek beze změn ...

                if (isNewer) {
                    plugin.getLogger().warning("Nová verze TabSBS je dostupná: " + latestTag + " (nyní: " + current + ")");
                    plugin.setUpdateAvailable(true, latestTag);

                    // Upozorni online hráče (OP / s právem tabsbs.update) – lokalizovaně
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.getOnlinePlayers().forEach(plugin::sendUpdateStatus));

                } else {
                    // ✅ jen konzole, žádné zprávy do chatu
                    plugin.getLogger().info("TabSBS je aktuální (verze " + current + ")");
                    plugin.setUpdateAvailable(false, latestTag); // stav si klidně ulož, ale nikoho neupozorňuj
                }


            } catch (Exception e) {
                plugin.getLogger().warning("Nepodařilo se zkontrolovat aktualizace: " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "0.0.0";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int dash = v.indexOf('-'); if (dash >= 0) v = v.substring(0, dash);
        int plus = v.indexOf('+'); if (plus >= 0) v = v.substring(0, plus);
        return v;
    }

    // Jednoduché semver porovnání: 1.2.10 > 1.2.3
    private static boolean isNewerSemver(String latest, String current) {
        String[] a = latest.split("\\.");
        String[] b = current.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = (i < a.length ? parseIntSafe(a[i]) : 0);
            int bi = (i < b.length ? parseIntSafe(b[i]) : 0);
            if (ai != bi) return ai > bi;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
