package com.leaves.ash.client.config;

import com.leaves.ash.client.cloudpick.CloudPickProtocol;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Client side tuning. Everything lives in config/ash-client.properties so it can be
 * edited while the game is closed. Values are clamped on load: reach never exceeds
 * the vanilla survival interaction range, rotation speed never exceeds a plausible
 * human flick, attack gaps never drop below the configured floor.
 */
public final class AshConfig {

    public static final Path PATH = Paths.get("config", "ash-client.properties");

    /** How the name list below is applied. */
    public enum FilterMode {
        OFF,
        WHITELIST,
        BLACKLIST
    }

    /** master switch */
    public boolean enabled = true;

    /** hard capped to VANILLA_MAX_REACH on load */
    public double maxReach = 3.0;
    public static final double VANILLA_MAX_REACH = 3.0;

    /** refuse to swing at a target hidden behind blocks */
    public boolean requireLineOfSight = true;

    public boolean attackInvisible = false;

    /**
     * The ONLY thing that decides what may be attacked. There is no hostile/neutral/
     * player switch any more - if it is not on this list, it is not a target.
     *
     * Matched as a case-insensitive substring of the display name or of the entity type
     * id, so "zombie" and "minecraft:zombie" both work. An empty WHITELIST attacks
     * nothing, which is the safe direction to fail in.
     */
    public String nameFilters = "";
    public FilterMode nameFilterMode = FilterMode.WHITELIST;

    /** time between "target appeared" and the first swing, in milliseconds */
    public int reactionMinMs = 110;
    public int reactionMaxMs = 240;

    /** minimum gap between two swings */
    public int attackGapMinMs = 240;
    public int attackGapMaxMs = 420;
    public int attackGapJitterMs = 60;

    /** additionally wait until the 1.9+ attack meter is (almost) full */
    public boolean waitForWeaponCooldown = true;
    public double weaponCooldownThreshold = 0.98;

    /** do not switch targets faster than this */
    public int targetLockMs = 260;
    /** pause after losing a target before locking a new one */
    public int retargetDelayMs = 120;

    /**
     * true (default): the camera is turned onto the target before every swing, the way a
     * player's hand on a mouse would. Costly side effect: it competes with
     * MovementController for yaw, so fighting while walking a #goto route turns into the
     * two of them arguing over the camera every tick.
     *
     * false: the camera is never written to. Every swing goes out in whatever direction
     * you already happen to face, so walking and fighting stop interfering completely.
     * Vanilla and Paper validate the interact packet by distance, not by view angle, so
     * the hits still land. What they do notice is a target standing behind you, which is
     * what attackFovDeg below exists to prevent.
     */
    public boolean rotateToTarget = true;

    /**
     * Full cone, in degrees, a target has to sit inside when rotateToTarget is false.
     * 120 accepts roughly everything in front and to the sides. 360 disables the check,
     * which means "anything within reach including directly behind you" - do that only
     * if you know nobody is watching.
     */
    public double attackFovDeg = 120.0;

    /**
     * Stop steering the camera for a moment after every swing. Without this the view
     * tracks a mob through its own knockback, which reads as an aimbot. With it the
     * crosshair stays where it was, the mob flies out of it, and the next swing starts
     * from a fresh, human-paced re-acquisition.
     *
     * Only meaningful while rotateToTarget is true.
     */
    public boolean freezeAimAfterAttack = true;
    public int aimFreezeMinMs = 150;
    public int aimFreezeMaxMs = 300;

    /** hand tremor applied on top of the perfect aim solution */
    public double aimJitterDeg = 0.7;
    public double maxYawStepDeg = 45.0;
    public double maxPitchStepDeg = 30.0;
    public double aimToleranceDeg = 2.0;

    /**
     * true  : tap the key to engage, tap again to disengage (default)
     * false : the old hold-to-attack behaviour
     */
    public boolean toggleMode = true;

    /**
     * GLFW key that toggles the engine; polled directly, never shown in the controls menu.
     *
     * Full stop, not R: R is one of the keys CloudPick servers bind things to, and ash is
     * not going to take it hostage. Period has no vanilla binding of its own.
     */
    public int keyCode = GLFW.GLFW_KEY_PERIOD;

    /**
     * Walk to the target when nothing is inside reach. Movement is done by setting the
     * vanilla movement keys, never by sending position packets, so the physics on the
     * wire is the client's own.
     */
    public boolean autoApproach = false;
    /** ignore candidate targets farther away than this when picking something to walk to */
    public double approachMaxDistance = 12.0;
    /** stop walking once this close to the target */
    public double approachStopDistance = 2.2;
    /** A* expansion budget; the search returns the best partial path when it runs out */
    public int pathMaxNodes = 2500;
    public int pathRepathMs = 700;
    public boolean pathAllowDiagonal = true;
    /** how far the camera may swing per tick while walking */
    public double moveMaxYawStepDeg = 35.0;
    public boolean jumpWhenBlocked = true;
    public boolean sprintWhenFar = false;

    /** Abort the whole movement instead of following a partial path towards an unreachable goal. */
    public boolean abortOnUnreachable = true;
    /** Stop everything the moment the server resets our position (teleport / anti-cheat pull-back). */
    public boolean stopOnServerTeleport = true;
    /** Stop when we end up in water or lava: swimming physics are not modelled here. */
    public boolean stopOnFluid = true;
    /** Give up when the distance to the goal stops shrinking for this long. */
    public int abortStuckMs = 8000;

    // ---- fishing (toggled with #fish) ----

    /** delay between the bobber registering a bite and the reel */
    public int fishReactionMinMs = 180;
    public int fishReactionMaxMs = 450;
    /** fraction of bites answered late, the way a distracted player would */
    public double fishSlowReactionChance = 0.08;
    public int fishSlowReactionMinMs = 900;
    public int fishSlowReactionMaxMs = 2600;
    /** gap between landing one fish and casting again */
    public int fishRecastMinMs = 800;
    public int fishRecastMaxMs = 1800;
    /** reel in anyway after this long with no bite; 0 disables */
    public int fishGiveUpMs = 120000;
    /** after this many catches take a longer break; 0 disables. Non-zero by default:
     *  landing hundreds of fish with no interruption at all is its own signature. */
    public int fishPauseEvery = 25;
    public int fishPauseMinMs = 4000;
    public int fishPauseMaxMs = 10000;

    // ---- staying alive while away from the keyboard ----

    /**
     * Fishing keeps running with a screen open: escape menu, inventory, or even the chat
     * box you just typed #fish into. Nothing about a cast or a reel goes through the
     * keyboard or the mouse, so nothing here competes with whatever the screen is doing.
     *
     * Death and spectator mode are still hard stops - there is no line to tend when there
     * is no player to hold the rod.
     */
    public boolean fishWhileScreenOpen = true;
    public boolean combatWhileScreenOpen = true;
    /**
     * Presses "respawn" on the death screen on its own, after a human-ish pause, so an
     * unattended session survives a beating instead of sitting on the death screen all
     * night. This is LocalPlayer#respawn, which is exactly what the vanilla button calls:
     * one ServerboundClientCommandPacket(PERFORM_RESPAWN) and nothing else.
     */
    public boolean autoRespawn = true;
    public int autoRespawnMinMs = 900;
    public int autoRespawnMaxMs = 2400;

    /** report "vanilla" on the minecraft:brand channel instead of "fabric" */
    public boolean restoreVanillaBrand = true;

    // ---- cloud pick (yslelf.cloudpick plugin channel) ----

    /**
     * Master switch for the CloudPick module. Off by default.
     *
     * This is the only place in ash that builds a packet by hand, and an unsolicited plugin
     * message on a channel nobody listens to is a fingerprint as much as it is noise, so it
     * stays silent until it is switched on for a server that actually runs the mod.
     */
    public boolean cloudPick = false;

    /** how long after joining to declare; the real mod is not instant either */
    public int cloudPickHandshakeDelayMs = 800;

    /**
     * The keys to offer, comma separated, e.g. I,J,K,L.
     *
     * A-Z, 1-9 and F1-F12 only, upper cased on load. Anything else is dropped with a line
     * in the log: those are the names the mod itself would put on the wire (it upper cases
     * glfwGetKeyName), and every other one is a guess against a case sensitive match.
     *
     * Case does not matter when typing them here - "i,j" becomes I,J.
     */
    public String cloudPickKeys = "";

    public boolean debug = false;

    /** with debug on, log every entity you punch while sneaking with an empty hand */
    public boolean scanManualAttacks = true;

    /** nameFilters split, trimmed and lower-cased once at load time */
    private final List<String> parsedNameFilters = new ArrayList<>();

    /** cloudPickKeys split, trimmed and upper-cased once at load time */
    private final List<String> parsedCloudPickKeys = new ArrayList<>();

    /** the same keys indexed by GLFW code, so a physical press is looked up instead of mapped */
    private final Map<Integer, String> parsedCloudPickCodes = new HashMap<>();

    public List<String> filters() {
        return Collections.unmodifiableList(parsedNameFilters);
    }

    /**
     * The configured key names in order, already upper cased the way the mod sends them.
     * This is what goes on the wire, and the only list {@link #cloudPickKeyName} is built from.
     */
    public List<String> cloudPickKeys() {
        return Collections.unmodifiableList(parsedCloudPickKeys);
    }

    /**
     * GLFW code to key name, built once at load so a physical press is one map lookup.
     *
     * @param glfwCode an incoming GLFW key event
     * @return the name to send, or null if this key is not one we listen for
     */
    public String cloudPickKeyName(int glfwCode) {
        return parsedCloudPickCodes.get(glfwCode);
    }

    public void load() {
        Properties props = new Properties();
        if (Files.exists(PATH)) {
            try {
                // Properties.load(InputStream) decodes as ISO-8859-1, which turns any
                // Chinese in the filter list into mojibake and silently breaks matching.
                // Read the bytes ourselves and decode as UTF-8, falling back to GBK.
                props.load(new StringReader(decode(Files.readAllBytes(PATH))));
            } catch (IOException e) {
                System.err.println("[ash] failed to read config: " + e.getMessage());
            }
        }
        enabled = getBool(props, "enabled", enabled);
        maxReach = clamp(getDouble(props, "maxReach", maxReach), 1.0, VANILLA_MAX_REACH);
        requireLineOfSight = getBool(props, "requireLineOfSight", requireLineOfSight);

        attackInvisible = getBool(props, "attackInvisible", attackInvisible);

        nameFilters = props.getProperty("nameFilters", nameFilters);
        nameFilterMode = parseFilterMode(props.getProperty("nameFilterMode", nameFilterMode.name()));
        if (props.containsKey("includeHostile") || props.containsKey("includePassive")
                || props.containsKey("includePlayers")) {
            System.out.println("[ash] includeHostile/includePassive/includePlayers are gone; targets are decided "
                    + "by nameFilters alone. Delete the old lines from " + PATH + ".");
        }

        reactionMinMs = Math.max(0, getInt(props, "reactionMinMs", reactionMinMs));
        reactionMaxMs = Math.max(reactionMinMs, getInt(props, "reactionMaxMs", reactionMaxMs));

        attackGapMinMs = Math.max(50, getInt(props, "attackGapMinMs", attackGapMinMs));
        attackGapMaxMs = Math.max(attackGapMinMs, getInt(props, "attackGapMaxMs", attackGapMaxMs));
        attackGapJitterMs = Math.max(0, getInt(props, "attackGapJitterMs", attackGapJitterMs));

        waitForWeaponCooldown = getBool(props, "waitForWeaponCooldown", waitForWeaponCooldown);
        weaponCooldownThreshold = clamp(getDouble(props, "weaponCooldownThreshold", weaponCooldownThreshold), 0.5, 1.0);

        targetLockMs = Math.max(0, getInt(props, "targetLockMs", targetLockMs));
        retargetDelayMs = Math.max(0, getInt(props, "retargetDelayMs", retargetDelayMs));

        rotateToTarget = getBool(props, "rotateToTarget", rotateToTarget);
        attackFovDeg = clamp(getDouble(props, "attackFovDeg", attackFovDeg), 10.0, 360.0);

        freezeAimAfterAttack = getBool(props, "freezeAimAfterAttack", freezeAimAfterAttack);
        aimFreezeMinMs = Math.max(0, getInt(props, "aimFreezeMinMs", aimFreezeMinMs));
        aimFreezeMaxMs = Math.max(aimFreezeMinMs, getInt(props, "aimFreezeMaxMs", aimFreezeMaxMs));

        aimJitterDeg = clamp(getDouble(props, "aimJitterDeg", aimJitterDeg), 0.0, 3.0);
        maxYawStepDeg = clamp(getDouble(props, "maxYawStepDeg", maxYawStepDeg), 5.0, 180.0);
        maxPitchStepDeg = clamp(getDouble(props, "maxPitchStepDeg", maxPitchStepDeg), 5.0, 90.0);
        aimToleranceDeg = clamp(getDouble(props, "aimToleranceDeg", aimToleranceDeg), 0.2, 10.0);

        autoApproach = getBool(props, "autoApproach", autoApproach);
        approachMaxDistance = clamp(getDouble(props, "approachMaxDistance", approachMaxDistance), 3.0, 32.0);
        approachStopDistance = clamp(getDouble(props, "approachStopDistance", approachStopDistance), 1.0, 8.0);
        pathMaxNodes = Math.max(200, getInt(props, "pathMaxNodes", pathMaxNodes));
        pathRepathMs = Math.max(100, getInt(props, "pathRepathMs", pathRepathMs));
        pathAllowDiagonal = getBool(props, "pathAllowDiagonal", pathAllowDiagonal);
        moveMaxYawStepDeg = clamp(getDouble(props, "moveMaxYawStepDeg", moveMaxYawStepDeg), 5.0, 180.0);
        jumpWhenBlocked = getBool(props, "jumpWhenBlocked", jumpWhenBlocked);
        sprintWhenFar = getBool(props, "sprintWhenFar", sprintWhenFar);
        abortOnUnreachable = getBool(props, "abortOnUnreachable", abortOnUnreachable);
        stopOnServerTeleport = getBool(props, "stopOnServerTeleport", stopOnServerTeleport);
        stopOnFluid = getBool(props, "stopOnFluid", stopOnFluid);
        abortStuckMs = Math.max(1000, getInt(props, "abortStuckMs", abortStuckMs));

        fishReactionMinMs = Math.max(0, getInt(props, "fishReactionMinMs", fishReactionMinMs));
        fishReactionMaxMs = Math.max(fishReactionMinMs, getInt(props, "fishReactionMaxMs", fishReactionMaxMs));
        fishSlowReactionChance = clamp(getDouble(props, "fishSlowReactionChance", fishSlowReactionChance), 0.0, 1.0);
        fishSlowReactionMinMs = Math.max(0, getInt(props, "fishSlowReactionMinMs", fishSlowReactionMinMs));
        fishSlowReactionMaxMs = Math.max(fishSlowReactionMinMs, getInt(props, "fishSlowReactionMaxMs", fishSlowReactionMaxMs));
        fishRecastMinMs = Math.max(0, getInt(props, "fishRecastMinMs", fishRecastMinMs));
        fishRecastMaxMs = Math.max(fishRecastMinMs, getInt(props, "fishRecastMaxMs", fishRecastMaxMs));
        fishGiveUpMs = Math.max(0, getInt(props, "fishGiveUpMs", fishGiveUpMs));
        fishPauseEvery = Math.max(0, getInt(props, "fishPauseEvery", fishPauseEvery));
        fishPauseMinMs = Math.max(0, getInt(props, "fishPauseMinMs", fishPauseMinMs));
        fishPauseMaxMs = Math.max(fishPauseMinMs, getInt(props, "fishPauseMaxMs", fishPauseMaxMs));

        fishWhileScreenOpen = getBool(props, "fishWhileScreenOpen", fishWhileScreenOpen);
        combatWhileScreenOpen = getBool(props, "combatWhileScreenOpen", combatWhileScreenOpen);
        autoRespawn = getBool(props, "autoRespawn", autoRespawn);
        autoRespawnMinMs = Math.max(0, getInt(props, "autoRespawnMinMs", autoRespawnMinMs));
        autoRespawnMaxMs = Math.max(autoRespawnMinMs, getInt(props, "autoRespawnMaxMs", autoRespawnMaxMs));

        toggleMode = getBool(props, "toggleMode", toggleMode);
        keyCode = getInt(props, "keyCode", keyCode);
        restoreVanillaBrand = getBool(props, "restoreVanillaBrand", restoreVanillaBrand);
        debug = getBool(props, "debug", debug);
        scanManualAttacks = getBool(props, "scanManualAttacks", scanManualAttacks);

        cloudPick = getBool(props, "cloudPick", cloudPick);
        cloudPickHandshakeDelayMs = Math.max(0, getInt(props, "cloudPickHandshakeDelayMs", cloudPickHandshakeDelayMs));
        cloudPickKeys = props.getProperty("cloudPickKeys", cloudPickKeys);

        parseFilters();
        parseCloudPickKeys();
        if (cloudPick && parsedCloudPickKeys.isEmpty()) {
            System.out.println("[ash] cloudPick is on but cloudPickKeys is empty: set cloudPickKeys=I,J,K,L in " + PATH + ".");
        }
        if (nameFilterMode == FilterMode.WHITELIST && parsedNameFilters.isEmpty()) {
            System.out.println("[ash] nameFilters is empty and nameFilterMode=WHITELIST: nothing will be attacked. "
                    + "Use #scan samples (config/ash-entity-scan.txt) to build the list.");
        }
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(enabled));
        props.setProperty("maxReach", String.valueOf(maxReach));
        props.setProperty("requireLineOfSight", String.valueOf(requireLineOfSight));
        props.setProperty("attackInvisible", String.valueOf(attackInvisible));
        props.setProperty("nameFilters", nameFilters);
        props.setProperty("nameFilterMode", nameFilterMode.name());
        props.setProperty("reactionMinMs", String.valueOf(reactionMinMs));
        props.setProperty("reactionMaxMs", String.valueOf(reactionMaxMs));
        props.setProperty("attackGapMinMs", String.valueOf(attackGapMinMs));
        props.setProperty("attackGapMaxMs", String.valueOf(attackGapMaxMs));
        props.setProperty("attackGapJitterMs", String.valueOf(attackGapJitterMs));
        props.setProperty("waitForWeaponCooldown", String.valueOf(waitForWeaponCooldown));
        props.setProperty("weaponCooldownThreshold", String.valueOf(weaponCooldownThreshold));
        props.setProperty("targetLockMs", String.valueOf(targetLockMs));
        props.setProperty("retargetDelayMs", String.valueOf(retargetDelayMs));
        props.setProperty("rotateToTarget", String.valueOf(rotateToTarget));
        props.setProperty("attackFovDeg", String.valueOf(attackFovDeg));
        props.setProperty("freezeAimAfterAttack", String.valueOf(freezeAimAfterAttack));
        props.setProperty("aimFreezeMinMs", String.valueOf(aimFreezeMinMs));
        props.setProperty("aimFreezeMaxMs", String.valueOf(aimFreezeMaxMs));
        props.setProperty("aimJitterDeg", String.valueOf(aimJitterDeg));
        props.setProperty("maxYawStepDeg", String.valueOf(maxYawStepDeg));
        props.setProperty("maxPitchStepDeg", String.valueOf(maxPitchStepDeg));
        props.setProperty("aimToleranceDeg", String.valueOf(aimToleranceDeg));
        props.setProperty("autoApproach", String.valueOf(autoApproach));
        props.setProperty("approachMaxDistance", String.valueOf(approachMaxDistance));
        props.setProperty("approachStopDistance", String.valueOf(approachStopDistance));
        props.setProperty("pathMaxNodes", String.valueOf(pathMaxNodes));
        props.setProperty("pathRepathMs", String.valueOf(pathRepathMs));
        props.setProperty("pathAllowDiagonal", String.valueOf(pathAllowDiagonal));
        props.setProperty("moveMaxYawStepDeg", String.valueOf(moveMaxYawStepDeg));
        props.setProperty("jumpWhenBlocked", String.valueOf(jumpWhenBlocked));
        props.setProperty("sprintWhenFar", String.valueOf(sprintWhenFar));
        props.setProperty("abortOnUnreachable", String.valueOf(abortOnUnreachable));
        props.setProperty("stopOnServerTeleport", String.valueOf(stopOnServerTeleport));
        props.setProperty("stopOnFluid", String.valueOf(stopOnFluid));
        props.setProperty("abortStuckMs", String.valueOf(abortStuckMs));
        props.setProperty("fishReactionMinMs", String.valueOf(fishReactionMinMs));
        props.setProperty("fishReactionMaxMs", String.valueOf(fishReactionMaxMs));
        props.setProperty("fishSlowReactionChance", String.valueOf(fishSlowReactionChance));
        props.setProperty("fishSlowReactionMinMs", String.valueOf(fishSlowReactionMinMs));
        props.setProperty("fishSlowReactionMaxMs", String.valueOf(fishSlowReactionMaxMs));
        props.setProperty("fishRecastMinMs", String.valueOf(fishRecastMinMs));
        props.setProperty("fishRecastMaxMs", String.valueOf(fishRecastMaxMs));
        props.setProperty("fishGiveUpMs", String.valueOf(fishGiveUpMs));
        props.setProperty("fishPauseEvery", String.valueOf(fishPauseEvery));
        props.setProperty("fishPauseMinMs", String.valueOf(fishPauseMinMs));
        props.setProperty("fishPauseMaxMs", String.valueOf(fishPauseMaxMs));
        props.setProperty("fishWhileScreenOpen", String.valueOf(fishWhileScreenOpen));
        props.setProperty("combatWhileScreenOpen", String.valueOf(combatWhileScreenOpen));
        props.setProperty("autoRespawn", String.valueOf(autoRespawn));
        props.setProperty("autoRespawnMinMs", String.valueOf(autoRespawnMinMs));
        props.setProperty("autoRespawnMaxMs", String.valueOf(autoRespawnMaxMs));
        props.setProperty("toggleMode", String.valueOf(toggleMode));
        props.setProperty("keyCode", String.valueOf(keyCode));
        props.setProperty("restoreVanillaBrand", String.valueOf(restoreVanillaBrand));
        props.setProperty("debug", String.valueOf(debug));
        props.setProperty("scanManualAttacks", String.valueOf(scanManualAttacks));
        props.setProperty("cloudPick", String.valueOf(cloudPick));
        props.setProperty("cloudPickHandshakeDelayMs", String.valueOf(cloudPickHandshakeDelayMs));
        props.setProperty("cloudPickKeys", cloudPickKeys);

        try {
            Path parent = PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Properties.store(Writer) escapes every non-ASCII character as \\uXXXX, so the
            // file on disk is pure ASCII. That is deliberate: no editor, locale or code page
            // can corrupt it, and it always reads back exactly as written. Use #filters in
            // game to see the list rendered properly.
            try (Writer out = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                props.store(out, "ash client tuning - non-ASCII values are \\uXXXX escaped on purpose");
            }
        } catch (IOException e) {
            System.err.println("[ash] failed to write config: " + e.getMessage());
        }
    }

    /**
     * UTF-8 when the bytes are valid UTF-8, GBK otherwise.
     *
     * The fallback exists because a Windows text editor saving plain Chinese usually writes
     * the system code page, not UTF-8. Guessing wrong in one direction loses the filter
     * list silently, which is the worst possible failure for a whitelist.
     */
    private static String decode(byte[] raw) {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return strict.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("GBK"));
        }
    }

    private void parseFilters() {
        parsedNameFilters.clear();
        if (nameFilters == null) {
            return;
        }
        for (String part : nameFilters.split(",")) {
            String trimmed = part.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                parsedNameFilters.add(trimmed);
            }
        }
    }

    private void parseCloudPickKeys() {
        parsedCloudPickKeys.clear();
        parsedCloudPickCodes.clear();
        if (cloudPickKeys == null) {
            return;
        }
        for (String part : cloudPickKeys.split(",")) {
            // Locale.ROOT, not the default: a Turkish locale upper cases "i" to dotted I,
            // which would then fail the key whitelist and be dropped
            String trimmed = part.trim().toUpperCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!CloudPickProtocol.isValidKeyName(trimmed)) {
                // dropped, not sent: an illegal name would reach the server, fail the
                // case sensitive match there and produce nothing but silence
                System.out.println("[ash] ignoring cloudPickKeys entry \"" + trimmed
                        + "\" - only A-Z, 1-9 and F1-F12 are supported");
                continue;
            }
            parsedCloudPickKeys.add(trimmed);
            parsedCloudPickCodes.put(CloudPickProtocol.glfwCodeFor(trimmed), trimmed);
        }
    }

    /** true when the current setup can ever match anything. */
    public boolean isTargetingConfigured() {
        if (nameFilterMode == FilterMode.OFF) {
            return true;
        }
        return !parsedNameFilters.isEmpty();
    }

    /**
     * The single matching rule, shared by the combat engine and the #near command so the
     * two can never disagree about whether a mob is attackable.
     *
     * @param name display name with formatting codes already stripped
     * @param type entity type id, e.g. minecraft:zombie
     */
    public boolean matchesFilterText(String name, String type) {
        if (nameFilterMode == FilterMode.OFF) {
            return true;
        }
        if (parsedNameFilters.isEmpty()) {
            // an empty whitelist attacks nothing; an empty blacklist excludes nothing
            return nameFilterMode == FilterMode.BLACKLIST;
        }
        String loweredName = name == null ? "" : name.toLowerCase();
        String loweredType = type == null ? "" : type.toLowerCase();
        for (String filter : parsedNameFilters) {
            if (loweredName.contains(filter) || loweredType.contains(filter)) {
                return nameFilterMode == FilterMode.WHITELIST;
            }
        }
        return nameFilterMode != FilterMode.WHITELIST;
    }

    private static FilterMode parseFilterMode(String raw) {
        if (raw != null) {
            try {
                return FilterMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // an unreadable value must not fall back to "attack everything"
            }
        }
        return FilterMode.WHITELIST;
    }

    private static boolean getBool(Properties props, String key, boolean fallback) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(fallback)).trim());
    }

    private static int getInt(Properties props, String key, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double getDouble(Properties props, String key, double fallback) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
