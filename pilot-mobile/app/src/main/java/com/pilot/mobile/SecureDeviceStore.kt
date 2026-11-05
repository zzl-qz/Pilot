package com.pilot.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.pilot.mobile.transport.Protocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import computer.iroh.EndpointTicket
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class PairedComputer(val ticket: String, val name: String, val relays: List<String>, val development: Boolean) {
    val id: String get() = EndpointTicket.fromString(ticket).use { it.endpointAddr().use { address -> address.id().use { key -> key.toString() } } }
}

/** 连接身份由 Android Keystore 加密，关闭系统备份，不把密钥写入日志或 Intent。 */
class SecureDeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val alias = "pilot-device-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(name: String): ByteArray? {
        val raw = preferences.getString(name, null) ?: return null
        val bytes = Base64.getDecoder().decode(raw)
        require(bytes.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
    private fun encrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(bytes))
    }
    private fun write(name: String, bytes: ByteArray) {
        check(preferences.edit().putString(name, encrypt(bytes)).commit())
    }
    /** 先原子落盘结果、确认队列和 pending 清除，再允许确认服务器回执。 */
    @Synchronized fun completeRequest(host: String, requestId: String, result: String) {
        val acknowledgments = (acknowledgments(host) + requestId).distinct()
        check(preferences.edit()
            .putString("host:$host:receipt-result", encrypt(result.toByteArray()))
            .putString("host:$host:receipt-acks", encrypt(acknowledgments.joinToString("\n").toByteArray()))
            .remove("host:$host:pending").commit())
    }
    @Synchronized fun acknowledgments(host: String): List<String> = value(host, "receipt-acks")?.lines()?.filter { it.isNotBlank() }.orEmpty()
    @Synchronized fun acknowledged(host: String, requestId: String) {
        value(host, "receipt-acks", acknowledgments(host).filterNot { it == requestId }.joinToString("\n"))
    }
    fun identity(): ByteArray = read("identity") ?: ByteArray(32).also { java.security.SecureRandom().nextBytes(it); write("identity", it) }
    fun computers(): List<PairedComputer> = read("computers")?.let {
        Protocol.json.decodeFromString(ListSerializer(PairedComputer.serializer()), String(it))
    } ?: read("computer")?.let { listOf(Protocol.json.decodeFromString(PairedComputer.serializer(), String(it))) }.orEmpty()
    fun computer(): PairedComputer? = computers().let { list -> list.firstOrNull { it.id == preferences.getString("selected", "") } ?: list.firstOrNull() }
    fun select(id: String) { check(preferences.edit().putString("selected", id).commit()) }
    fun save(computer: PairedComputer) {
        val list = computers().filterNot { it.id == computer.id } + computer
        require(list.size <= 16) { "最多保存 16 台电脑，请先移除不用的连接" }
        write("computers", Protocol.json.encodeToString(ListSerializer(PairedComputer.serializer()), list).toByteArray())
        check(preferences.edit().remove("computer").putString("selected", computer.id).commit())
    }
    fun forget(id: String) {
        write("computers", Protocol.json.encodeToString(ListSerializer(PairedComputer.serializer()), computers().filterNot { it.id == id }).toByteArray())
        val editor = preferences.edit().remove("computer")
        preferences.all.keys.filter { it.startsWith("host:$id:") }.forEach { editor.remove(it) }
        check(editor.commit())
    }
    fun value(host: String, key: String): String? = read("host:$host:$key")?.toString(Charsets.UTF_8)
    fun value(host: String, key: String, value: String?) {
        if (value == null) check(preferences.edit().remove("host:$host:$key").commit())
        else write("host:$host:$key", value.toByteArray())
    }
}
