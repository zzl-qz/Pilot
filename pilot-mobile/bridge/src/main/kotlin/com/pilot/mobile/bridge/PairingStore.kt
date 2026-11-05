package com.pilot.mobile.bridge

import com.pilot.mobile.transport.*
import computer.iroh.SecretKey
import kotlinx.serialization.Serializable
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class Device(val id: String, val name: String, val pairedAt: Long)

@Serializable
private data class SavedState(val secretKey: String, val devices: List<Device> = emptyList())

/** 配对码短时、单次有效；授权绑定握手验证过的公钥，重启不重新开放配对。 */
class PairingStore(private val directory: Path, private val now: () -> Long = System::currentTimeMillis) {
    private val file = directory.resolve("devices.json")
    private var state: SavedState
    private var invite: PairingInvite? = null
    private var inviteState = "none"
    private var lastPaired: Device? = null
    val key: ByteArray get() = Base64.getDecoder().decode(state.secretKey)

    init {
        Files.createDirectories(directory)
        privatePermissions(directory, true)
        require(!Files.isSymbolicLink(file)) { "设备存储不能是符号链接" }
        state = if (Files.exists(file)) {
            Protocol.json.decodeFromString(SavedState.serializer(), Files.readString(file))
        } else SavedState(Base64.getEncoder().encodeToString(SecretKey.generate().toBytes())).also { persist(it) }
        require(key.size == 32) { "设备密钥损坏，不能自动覆盖" }
    }

    @Synchronized
    fun issue(ticket: String, name: String, relays: List<String>, development: Boolean): PairingInvite =
        PairingInvite(ticket = ticket, secret = randomSecret(), expiresAt = now() + 300_000,
            name = name.take(80), relays = relays, developmentRelays = development).also { invite = it; inviteState = "ready"; lastPaired = null }

    @Synchronized
    fun pair(peer: String, secret: String?, name: String?): Boolean {
        val current = invite ?: return false
        if (current.expiresAt <= now() || secret == null || secret.length != 43) return false
        if (!MessageDigest.isEqual(current.secret.toByteArray(), secret.toByteArray())) return false
        if (state.devices.size >= MAX_DEVICES && state.devices.none { it.id == peer }) return false
        val device = Device(peer, name?.filterNot { it.isISOControl() }?.take(80)?.ifBlank { "Android" } ?: "Android", now())
        val next = state.copy(devices = state.devices.filterNot { it.id == peer } + device)
        persist(next)
        state = next
        invite = null
        inviteState = "paired"
        lastPaired = device
        return true
    }

    @Synchronized fun pairingState(): String = if (inviteState == "ready" && invite?.expiresAt?.let { it <= now() } == true) "expired" else inviteState

    @Synchronized fun pairedDevice(): Device? = lastPaired

    @Synchronized fun currentInvite(): PairingInvite? = invite?.takeIf { it.expiresAt > now() }

    /** 网络地址更新只刷新路由，不重置授权凭证、有效期或单次消费状态。 */
    @Synchronized fun refreshRouting(ticket: String) {
        currentInvite()?.let { if (it.ticket != ticket) invite = it.copy(ticket = ticket) }
    }

    @Synchronized fun isTrusted(peer: String): Boolean = state.devices.any { it.id == peer }
    @Synchronized fun devices(): List<Device> = state.devices.toList()
    @Synchronized fun revoke(peer: String) {
        val next = state.copy(devices = state.devices.filterNot { it.id == peer })
        persist(next)
        state = next
        if (lastPaired?.id == peer) { lastPaired = null; inviteState = "none" }
    }

    private fun persist(next: SavedState) {
        val temp = Files.createTempFile(directory, "devices-", ".tmp")
        try {
            privatePermissions(temp, false)
            Files.writeString(temp, Protocol.json.encodeToString(SavedState.serializer(), next))
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

    companion object {
        const val MAX_DEVICES = 16
        fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        private fun privatePermissions(path: Path, directory: Boolean) {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            }
        }
    }
}
