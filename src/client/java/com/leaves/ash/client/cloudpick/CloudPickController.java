package com.leaves.ash.client.cloudpick;

import com.leaves.ash.client.config.AshConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.ResourceLocationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Sends CloudPick plugin messages from the Fabric client.
 *
 * <p>This is the one place in ash that puts a hand built packet on the wire. Every other
 * subsystem drives the game through keys and calls, and that remains the rule everywhere
 * else: a plugin message is not a movement, an attack or an interaction, there is no
 * vanilla call that would produce it, so there is nothing to simulate instead.
 *
 * <p>Two things happen here, and only two:
 * <ul>
 *   <li>once per connection it declares itself a short delay after joining, because without
 *       that the server accepts every later packet on the channel and acts on none of them</li>
 *   <li>it turns a key into a type=10 press, which is what makes the server run whatever
 *       that key is bound to - either a physical key named in cloudPickKeys going down, or
 *       one named by hand with #cp send</li>
 * </ul>
 *
 * <p>Every press is one type=10 frame and nothing else - no repeat, no release, no hold
 * duration. That is exactly what the captured real client produced: it walked on W/A/S/D
 * with press and repeat and never once sent a release, so a single press is the whole story.
 *
 * <p>There is deliberately no automatic key cycling. Timing, order and repetition belong to
 * whoever is driving - a real key going down, #cp send by hand, or a #run script with #wait
 * lines, which already exists and is already visible in the chat log.
 *
 * <p>Only A-Z, 1-9 and F1-F12 are accepted, upper cased, because that is what the mod does
 * to glfwGetKeyName before sending. Those are the keys whose wire name is unambiguous;
 * anything else is refused rather than sent, see CloudPickProtocol#isValidKeyName.
 */
public final class CloudPickController {

    public static final CloudPickController INSTANCE = new CloudPickController();

    /**
     * Fixed at class load rather than parsed per packet. The string is a constant that is
     * known to be valid, so the only correct response to it ever being malformed is to fail
     * here instead of discovering it at the first send.
     */
    private static final ResourceLocation CHANNEL = channel(CloudPickProtocol.CHANNEL);

    /** how many key presses may wait for the next tick before the rest are dropped */
    private static final int MAX_PENDING = 8;

    /** key names queued by the GLFW callback, drained once per tick */
    private final Deque<String> pending = new ArrayDeque<>();

    private AshConfig config;

    /** the connection the handshake was scheduled for; a different instance means a new session */
    private ClientPacketListener session;
    /** wall clock for the pending handshake; 0 means nothing scheduled */
    private long handshakeAt;
    private boolean handshakeSent;

    private CloudPickController() {
    }

    public void setConfig(AshConfig config) {
        this.config = config;
    }

    /**
     * Full reset. Called on #reload and whenever the world is swapped or dropped: the
     * handshake belongs to a connection, not to the config, so a new world has to declare
     * itself again from scratch.
     */
    public void hardReset() {
        session = null;
        handshakeAt = 0L;
        handshakeSent = false;
        pending.clear();
    }

    public boolean isHandshakeSent() {
        return handshakeSent;
    }

    public void tick(Minecraft mc) {
        if (config == null || !config.enabled || !config.cloudPick) {
            return;
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            if (session != null) {
                hardReset();
            }
            return;
        }
        if (session != connection) {
            // new session: the server has no idea who we are until the declaration arrives
            session = connection;
            handshakeSent = false;
            handshakeAt = System.currentTimeMillis() + config.cloudPickHandshakeDelayMs;
        }
        if (handshakeAt != 0L && System.currentTimeMillis() >= handshakeAt) {
            handshakeAt = 0L;
            sendHandshake(mc);
        }
        while (!pending.isEmpty()) {
            press(mc, pending.poll());
        }
    }

    public void sendHandshake(Minecraft mc) {
        if (!send(mc, CloudPickProtocol.handshake())) {
            return;
        }
        handshakeSent = true;
        handshakeAt = 0L;
        note(mc, "cloudpick handshake sent");
    }

    /**
     * One key going down. A press alone is what the real client produced for every movement
     * key in the capture: press and repeat while walking, never a release.
     *
     * @return true when a frame actually went out
     */
    public boolean press(Minecraft mc, String key) {
        // Locale.ROOT, not the default: under a Turkish locale "i" upper cases to dotted I,
        // which would then fail the key whitelist and get refused
        String name = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        if (!CloudPickProtocol.isValidKeyName(name)) {
            note(mc, "cloudpick: refusing \"" + key + "\" - only A-Z, 1-9 and F1-F12 are sent");
            return false;
        }
        return send(mc, CloudPickProtocol.keyPress(name));
    }

    /**
     * Called from the GLFW key callback: a physical key went down.
     *
     * <p>It only queues. Sending from here would mean writing to the connection from the
     * window callback, outside the tick, which every other packet in the game goes through.
     * The queue is drained on the next tick, at most 50ms later, which no human and no
     * server will notice.
     *
     * <p>Keys are ignored while any screen is open. Chat alone makes that mandatory: typing
     * a word containing I in the chat box would otherwise fire off a press for every
     * letter. In game, with no screen, is also the only time these presses mean anything.
     */
    public void onPhysicalKeyPressed(Minecraft mc, int glfwCode) {
        if (config == null || !config.enabled || !config.cloudPick) {
            return;
        }
        if (mc.screen != null) {
            return;
        }
        String name = config.cloudPickKeyName(glfwCode);
        if (name == null) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            // several of these going down inside one tick means something is hammering the
            // key; dropping the tail is better than replaying a burst
            return;
        }
        pending.add(name);
    }

    /** Raw escape hatch: an entire frame, magic byte included, handed over untouched. */
    public void sendRaw(Minecraft mc, byte[] frame) {
        send(mc, frame);
    }

    /**
     * The only outbound path. Nothing is sent without a live connection, and nothing is sent
     * while the module is switched off - including the manual commands.
     */
    private boolean send(Minecraft mc, byte[] frame) {
        if (frame == null || frame.length == 0) {
            return false;
        }
        if (config == null) {
            return false;
        }
        if (!config.cloudPick) {
            note(mc, "cloudPick is off - #cp on, or set cloudPick=true in " + AshConfig.PATH);
            return false;
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            note(mc, "cloudpick: not connected");
            return false;
        }
        if (config.debug) {
            System.out.println("[ash] cloudpick -> " + CHANNEL + " : " + CloudPickProtocol.hex(frame));
        }
        connection.send(new ServerboundCustomPayloadPacket(CHANNEL,
                new FriendlyByteBuf(Unpooled.wrappedBuffer(frame))));
        return true;
    }

    private static ResourceLocation channel(String id) {
        try {
            return new ResourceLocation(id);
        } catch (ResourceLocationException e) {
            throw new IllegalStateException("bad cloudpick channel: " + id, e);
        }
    }

    private void note(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ash] " + message), true);
        }
        System.out.println("[ash] " + message);
    }
}
