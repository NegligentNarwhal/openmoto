package dev.coletz.opencfmoto

import android.os.Build
import org.json.JSONObject
import java.io.OutputStream

/**
 * Strategy for a specific CFMoto dashboard generation ("bike variant").
 *
 * The PXC/EasyConn protocol is broadly the same across dashboards, but newer head units add
 * steps and fields the older ones don't. Rather than branch on version literals all over the
 * handshake, each dashboard gets a [BikeProfile] selected from the bike's CLIENT_INFO
 * ([BikeProfiles.select]) and consulted at the points where behavior diverges.
 *
 * Selected in [PxcHandshake.onClientInfo] and stored on the shared [PxcHandshake] instance;
 * the media plane reaches it via `handshake.profile`. The bike opens the media ports only AFTER
 * the control handshake, so the profile is always chosen before the media plane needs it.
 */
/** A video resolution/orientation Android Auto can be asked to project (maps to an AAP enum). */
enum class AaResolution(val w: Int, val h: Int) {
    LANDSCAPE_800x480(800, 480),
    PORTRAIT_720x1280(720, 1280),
    PORTRAIT_1080x1920(1080, 1920),
}

/** The Android Auto video config a dash should request: orientation/size + panel density. */
data class AaVideoSpec(val resolution: AaResolution, val dpi: Int) {
    val width: Int get() = resolution.w
    val height: Int get() = resolution.h
}

interface BikeProfile {
    /** Human-readable label — appears in bike-test logs so a capture is self-describing. */
    val name: String

    /** How strongly this profile claims a given CLIENT_INFO. Highest positive score wins; 0 = no claim. */
    fun score(info: JSONObject): Int

    /**
     * Coarse match from the QR `modelId` — the only bike identity available BEFORE connecting.
     * Used to pick [aaVideo] up front (Android Auto's resolution must be chosen before AA starts,
     * which is before the CLIENT_INFO handshake). CLIENT_INFO scoring refines it later.
     */
    fun matchesModelId(modelId: String): Boolean = false

    /** The Android Auto video config this dash should request (orientation + size + density). */
    val aaVideo: AaVideoSpec

    // ---- capability flags ----
    /** Bike advertises enableSockServerAuth — likely needs an auth exchange before media (see Cfdl26). */
    val requiresSockServerAuth: Boolean
    val supportsScreenTouch: Boolean
    /** Value we advertise in our own CLIENT_INFO reply's `supportFunction`. */
    val advertisedSupportFunction: Int

    /** Build the phone's CLIENT_INFO reply (cmd 0x10011). `phoneUuid` is owned by [PxcHandshake]. */
    fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject

    /**
     * Handle a control-plane cmd not covered by [PxcHandshake]'s fixed switch. Return true if the
     * profile replied/consumed it; false to fall back to the legacy "log only, no reply" behavior.
     */
    fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean

    // ---- media-plane hooks (behavior-preserving defaults; only rounding is wired this pass) ----
    /** Round a requested capture dimension to what the encoder/bike accept. Default: down to /16. */
    fun roundCaptureDimension(px: Int): Int = px and 0xFFF0

    /** Media-plane GET_VERSION reply (version, subVersion). */
    fun versionReply(): Pair<Int, Int> = 3 to 1
}

/** Registry + selection. Never returns null — falls back to the legacy (BIKE A) profile. */
object BikeProfiles {
    val legacy: BikeProfile = LegacyCfdl16Profile
    private val all: List<BikeProfile> = listOf(Cfdl26Profile, LegacyCfdl16Profile)

    /** Authoritative selection from CLIENT_INFO (during the PXC handshake). */
    fun select(info: JSONObject, log: (String) -> Unit): BikeProfile {
        val scored = all.map { it to it.score(info) }
        log("[profile] scores=" + scored.joinToString { "${it.first.name}=${it.second}" })
        return scored.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: legacy
    }

    /** Early selection from the QR `modelId`, before we connect. Falls back to legacy. */
    fun selectByModelId(modelId: String?): BikeProfile =
        modelId?.let { id -> all.firstOrNull { it.matchesModelId(id) } } ?: legacy
}

/**
 * Process-wide active bike profile. Set early from the QR modelId ([BikeProfiles.selectByModelId])
 * so the Android Auto stack ([ServiceDiscoveryResponse]) can request the right resolution before AA
 * starts, then confirmed authoritatively from CLIENT_INFO in [PxcHandshake]. Read across the
 * activity + the Android Auto foreground service, so it lives here as a process global (like
 * [ProjectionHolder] / [AaVideoBridge]).
 */
object BikeProfileHolder {
    @Volatile var active: BikeProfile = BikeProfiles.legacy
}

/** Shared base CLIENT_INFO reply. Keys/order match the original PxcHandshake.buildClientInfoReply so
 *  that LegacyCfdl16Profile (supportFunction=0) produces byte-identical output. */
private fun basePhoneClientInfo(huid: String?, phoneUuid: String, supportFunction: Int): JSONObject =
    JSONObject().apply {
        put("pxcVersion", "1.0.2")
        put("phoneUUID", phoneUuid)
        put("phoneBrand", Build.BRAND)
        put("phoneModel", Build.MODEL)
        put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
        put("phoneOs", "Android")
        put("package", EasyConnProber.SPOOFED_PACKAGE)
        put("versionCode", 126)
        put("token", 0)
        put("pubkey", RsaKeys.publicKeyBase64)
        put("encryptedHUID", huid?.let { RsaKeys.signHuid(it) } ?: "")
        put("bluetoothName", "OpenCfMoto")
        put("supportH264IFrame", true)
        put("supportFunction", supportFunction)
        put("appVersionFingerPrint", "opencfmoto-poc")
    }

/**
 * BIKE A — the CFDL16 head unit (sdkVersion 0.9.29.1) the app was reverse-engineered against and
 * confirmed working end-to-end. This profile reproduces the current behavior EXACTLY (byte-identical
 * CLIENT_INFO reply, no reply to unknown cmds) and is the safe default for any unrecognized bike.
 */
object LegacyCfdl16Profile : BikeProfile {
    override val name = "CFDL16 / legacy (BIKE A)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 0

    /** The 675's 5" panel is landscape ~800x386. */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    /** Known 675 QR modelId. Legacy is also the fallback, so this is just for a positive early match. */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37416"

    /** Constant floor so legacy always wins ties and is the guaranteed fallback. */
    override fun score(info: JSONObject): Int = 1

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = false  // reproduces the original "log only, no reply" else-branch
}

/**
 * BIKE B — the CFDL26 / MotoPlay head unit on the 1000 MT-X (sdkVersion 1.1.4,
 * package com.cfmoto.cfdashmotoplay, enableSockServerAuth=true, WiFi-Direct P2P).
 *
 * Status: the control handshake gets through CLIENT_INFO + channel selects, then the bike sends an
 * unhandled [PxcFrame.CMD_LOG_REPORT] (0x10780) and — if left unanswered — closes after ~9s without
 * ever opening the media ports. This profile's first experimental divergence is to ack that frame.
 * Root cause is unconfirmed; see the auth TODO in [handleUnknownControl].
 */
object Cfdl26Profile : BikeProfile {
    override val name = "CFDL26 / MotoPlay (BIKE B, 1000 MT-X)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128

    /** The 1000 MT-X's 8" panel is a tall PORTRAIT screen (requests ~800x951). Ask AA for portrait
     *  720x1280 at the panel's advertised 240 dpi; the compositor letterboxes it into the canvas. */
    override val aaVideo = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)

    /** Known CFDL26 / 1000 MT-X QR modelId (from the bike's pairing QR). */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("version_name").startsWith("CFDL26")) s += 4
        val sdk = info.optString("sdkVersion")
        if (sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2   // 1.1.4 etc., not the 0.9.x legacy unit
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        if (info.optString("package_name") == "com.cfmoto.cfdashmotoplay") s += 2
        if (info.optInt("supportFunction", 0) == 128) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction).apply {
            put("supportScreenTouch", true)
        }

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean {
        // After CHECK_SN the CFDL26 unit sends a burst of JSON notify frames the older CFDL16 never
        // did — 0x10780 (log), 0x103a0 (OTA FTP creds), 0x10020 (media-feature flags), and possibly
        // more — and will NOT connect to the media ports until each is acked. The whole PXC protocol
        // acks with reply = cmd+1 (empty), so ack every otherwise-unhandled control frame that way.
        // Only genuinely-unknown frames reach here (channel selects, CLIENT_INFO, QUERY_SPEED,
        // CHECK_SN, heartbeat are all handled upstream in PxcHandshake.handle), so this is safe.
        val body = if (frame.payload.isEmpty()) "" else String(frame.payload, Charsets.UTF_8)
        // Also hex-dump the payload: touch/screen frames (e.g. CMD_SCREEN_TOUCH 0x30040) carry BINARY
        // coordinates that garble as UTF-8. This is how we'll reverse the touch layout from a bike test.
        val hex = if (frame.payload.isEmpty()) "" else
            " hex=" + BleProtocol.bytesToHex(frame.payload.copyOf(minOf(48, frame.payload.size)))
        val tag2 = if (frame.cmd == PxcFrame.CMD_SCREEN_TOUCH) " *** SCREEN_TOUCH ***" else ""
        val ack = frame.cmd + 1
        log("[$tag] CFDL26 ctrl ${frame.cmdHex()} (${PxcFrame.nameOf(frame.cmd)})$tag2 len=${frame.payload.size} " +
            "$body$hex → ack 0x${ack.toUInt().toString(16)} (empty)")
        PxcFrame(ack, ByteArray(0)).write(out)
        return true
        // NOTE if this still stalls: enableSockServerAuth=true may need a real auth exchange (the
        // 0x2001x REMOTE_AUTH_RESULT / 0x3001x AUTH_HUID family) rather than a bare ack — the log
        // above will show which frame the bike repeats or waits on.
    }
}
