package net.apocalypse.plugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class ColorUtil {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private ColorUtil() {
    }

    public static Component parse(String legacyText) {
        return SERIALIZER.deserialize(legacyText);
    }
}
