package com.leaves.ash.client.util;

import net.minecraft.world.entity.Entity;

/**
 * Display names on an RPG server are components and almost never plain text: they carry
 * section-sign colour codes, and the same mob can be re-skinned with a custom name.
 * Everything that compares names goes through here so matching is done on stripped,
 * lower-cased text.
 */
public final class Names {

    private Names() {
    }

    /** Display name with formatting codes removed, trimmed. */
    public static String plain(Entity entity) {
        if (entity == null) {
            return "";
        }
        String raw = entity.getName().getString();
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00A7') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /**
     * ASCII-only rendering for console output.
     *
     * A Chinese name printed to a Windows console comes out as garbage whenever the JVM's
     * stdout encoding and the console code page disagree, which makes the log actively
     * misleading. Escaping keeps it readable and, as a bonus, makes it obvious how many
     * characters a name really has - useful when hunting for invisible ones.
     */
    public static String escaped(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            } else {
                out.append(String.format("\\u%04X", (int) c));
            }
        }
        return out.toString();
    }
}
