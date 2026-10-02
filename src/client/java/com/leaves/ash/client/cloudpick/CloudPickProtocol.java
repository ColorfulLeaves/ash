package com.leaves.ash.client.cloudpick;

import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Byte level builders for the CloudPick (yslelf.cloudpick) client to server plugin message.
 *
 * <p>The wire format was reversed from CloudPick-Forge-1.20.1-1.3.2 and then verified
 * byte for byte against a real client by sniffing the channel on a Spigot server, so what
 * is produced here is not a guess: it is what the mod itself puts on the wire.
 *
 * <p>Frame:
 * <pre>
 *   1 byte   magic 0x40      - anything else is silently dropped server side
 *   int32 BE type            - fixed width, NOT a VarInt
 *   payload
 * </pre>
 *
 * <p>Payload primitives are asymmetric between read and write:
 * <ul>
 *   <li>string: VarInt(byte length) + UTF-8 bytes</li>
 *   <li>string list: fixed int32 element count, then each string with its own VarInt prefix</li>
 * </ul>
 *
 * <p>Only the client to server direction is built here. Server to client frames carry an
 * extra int32 sequence field between type and payload, and use a completely different type
 * numbering (11=Authorize, 14=RegisterKey, 100=RunScript, 101=OpenGui, ...). Nothing in ash
 * parses those, so they are only noted here to stop anyone reusing these constants inbound.
 */
public final class CloudPickProtocol {

    /** Channel declared by the mod's own static initialiser: namespace "cloudpick", path "main". */
    public static final String CHANNEL = "cloudpick:main";

    public static final byte MAGIC = 0x40;

    /** GLFW PRESS: string key + list of currently held keys (the real client sends an empty list). */
    public static final int MSG_KEY_PRESS = 10;
    /** Custom packet: string id + list of string arguments. The handshake uses this. */
    public static final int MSG_CUSTOM_PACKET = 100;

    public static final String HANDSHAKE_ID = "CloudPick_WithMod";

    /**
     * Every key name ash will agree to send: A-Z, 1-9, F1-F12, which is what the user asked
     * for and nothing more.
     *
     * <p>Inside this set the mod's own transformation - glfwGetKeyName upper cased - always
     * lands on exactly what is written here, so there is nothing to guess: "A" is "A", "7"
     * is "7", "f3" becomes "F3". Outside it the name is guesswork that guessing wrong pays
     * for silently, because the server simply matches nothing and nothing happens: "left
     * shift" versus LEFT_SHIFT, ESCAPE versus ESC. Those keys are refused rather than sent,
     * and widening this pattern is the single change needed if that ever becomes necessary.
     */
    private static final Pattern KEY_NAME = Pattern.compile("^[A-Z]$|^[1-9]$|^F(?:[1-9]|1[0-2])$");

    /** true for A-Z, 1-9 and F1-F12 only; {@code null} is never valid. */
    public static boolean isValidKeyName(String key) {
        return key != null && KEY_NAME.matcher(key).matches();
    }

    /**
     * The GLFW key code behind a valid key name, or -1.
     *
     * <p>This maps the other way round from how the packet is built and it is exact rather
     * than sampled: the ranges are contiguous in GLFW (A=65..Z=90, 1=49..9=57,
     * F1=290..F12=301), so nothing here depends on what glfwGetKeyName reports on this
     * machine. The result is only ever used to compare against an incoming key event - what
     * goes on the wire is always the name from the config.
     */
    public static int glfwCodeFor(String key) {
        if (!isValidKeyName(key)) {
            return -1;
        }
        char first = key.charAt(0);
        if (key.length() == 1) {
            if (first >= 'A' && first <= 'Z') {
                return GLFW.GLFW_KEY_A + (first - 'A');
            }
            return GLFW.GLFW_KEY_1 + (first - '1');
        }
        return GLFW.GLFW_KEY_F1 + (Integer.parseInt(key.substring(1)) - 1);
    }

    private CloudPickProtocol() {
    }

    /**
     * The one packet that makes the server treat us as a CloudPick client.
     *
     * Without it the server accepts the channel but never runs the key handlers. A sniffed
     * real client sent exactly this on join and nothing else in the handshake position.
     */
    public static byte[] handshake() {
        return customPacket(HANDSHAKE_ID, Collections.singletonList("true"));
    }

    /**
     * A key going down. This is the packet that makes the server run whatever that key is
     * bound to - the sniffed client produced press and repeat while walking and never a
     * release, so a press on its own is the whole story.
     */
    public static byte[] keyPress(String key) {
        return build(MSG_KEY_PRESS, out -> {
            writeString(out, key);
            writeStringList(out, Collections.emptyList());
        });
    }

    public static byte[] customPacket(String id, List<String> data) {
        return build(MSG_CUSTOM_PACKET, out -> {
            writeString(out, id);
            writeStringList(out, data);
        });
    }

    public static byte[] fromHex(String text) {
        String cleaned = text.replaceAll("[\\s:]", "");
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Debug helper: dump a frame as hex so it can be compared against a capture byte for byte. */
    public static String hex(byte[] data) {
        if (data == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private interface PayloadWriter {
        void write(ByteArrayOutputStream out) throws IOException;
    }

    private static byte[] build(int type, PayloadWriter writer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            dos.writeByte(MAGIC);
            dos.writeInt(type);
            writer.write(out);
        } catch (IOException e) {
            throw new IllegalStateException("failed to build a cloudpick frame", e);
        }
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeStringList(ByteArrayOutputStream out, List<String> list) throws IOException {
        List<String> safe = list == null ? Collections.emptyList() : list;
        writeInt(out, safe.size());
        for (String value : safe) {
            writeString(out, value);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while (true) {
            if ((v & 0xFFFFFF80) == 0) {
                out.write(v);
                return;
            }
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }
}
