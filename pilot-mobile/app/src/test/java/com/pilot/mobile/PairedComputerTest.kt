package com.pilot.mobile

import com.pilot.mobile.transport.Protocol
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.*
import org.junit.Test

class PairedComputerTest {
    @Test fun serializationOmitsLazyIdAndDeserializationInitializesDelegateLazily() {
        val original = PairedComputer(
            ticket = "not-decoded-until-accessed",
            name = "测试电脑",
            relays = listOf("https://relay.example.com"),
            development = false,
        )
        val encoded = Protocol.json.encodeToString(PairedComputer.serializer(), original)
        assertFalse("序列化结果不应包含派生 id 字段：$encoded", encoded.contains("\"id\""))

        val decoded = Protocol.json.decodeFromString(PairedComputer.serializer(), encoded)
        assertEquals(original, decoded)

        val listEncoded = Protocol.json.encodeToString(ListSerializer(PairedComputer.serializer()), listOf(original))
        assertEquals(listOf(original), Protocol.json.decodeFromString(ListSerializer(PairedComputer.serializer()), listEncoded))

        // 反序列化后 lazy 委托必须已初始化，访问 id 时不会因未初始化而抛 NullPointerException。
        val delegateField = PairedComputer::class.java.getDeclaredField("id\$delegate").apply { isAccessible = true }
        assertNotNull(delegateField.get(decoded))
        val failure = runCatching { decoded.id }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse("访问反序列化后的 lazy id 不应抛出 NullPointerException", failure is NullPointerException)
    }
}
