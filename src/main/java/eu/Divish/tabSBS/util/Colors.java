package eu.Divish.tabSBS.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

public final class Colors {
    private Colors() {}

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    /** Pro staré API (& → §). */
    public static String colorizeLegacy(String input) {
        if (input == null) return "";
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    /** &-kódy → Adventure Component. */
    public static Component legacyToComponent(String input) {
        if (input == null) return Component.empty();
        return LEGACY.deserialize(input);
    }
}
