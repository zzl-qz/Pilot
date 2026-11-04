package com.pilot.mobile.bridge

import com.pilot.mobile.transport.Request
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** 精确限定移动工作流。设备授权不开放设置、桌面控制、任意 URL 或配对管理。 */
object RoutePolicy {
    private const val ID = "[A-Za-z0-9_-]{1,100}"
    private val routes = mapOf(
        "GET" to listOf("/api/health", "/api/mobile/workbench", "/api/projects", "/api/projects/$ID", "/api/sessions", "/api/approvals",
            "/api/results", "/api/results/$ID", "/api/workspaces/(roots|browse)",
            "/api/sessions/$ID", "/api/sessions/$ID/(history|events|queue|plan|runs/active|products|context|diff)",
            "/api/sessions/$ID/diff/file", "/api/sessions/$ID/products/$ID/(preview|download)"),
        "POST" to listOf("/api/projects", "/api/sessions", "/api/sessions/$ID/(messages|steer|continue)",
            "/api/runs/$ID/(pause|cancel)", "/api/approvals/$ID", "/api/results/$ID/receipt",
            "/api/sessions/$ID/queue/[0-9]{1,4}/(steer|move)"),
        "PATCH" to listOf("/api/projects/$ID", "/api/sessions/$ID", "/api/sessions/$ID/archive"),
        "PUT" to listOf("/api/projects/$ID/folders", "/api/sessions/$ID/queue/[0-9]{1,4}"),
        "DELETE" to listOf("/api/sessions/$ID/queue/[0-9]{1,4}"),
    ).mapValues { (_, paths) -> paths.map(::Regex) }

    fun allowed(request: Request): Boolean {
        if (request.path.length > 8192 || request.body.toByteArray().size > 12 * 1024 * 1024) return false
        if (request.requestId?.matches(Regex("[a-f0-9-]{36}")) == false) return false
        val uri = runCatching { URI(request.path) }.getOrNull() ?: return false
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null || uri.rawPath != uri.path) return false
        if (routes[request.method]?.none { it.matches(uri.path) } != false) return false
        val query = uri.rawQuery ?: return true
        val allowedKeys = when {
            uri.path.endsWith("/history") -> setOf("after", "before", "limit")
            uri.path.endsWith("/events") -> setOf("after")
            uri.path.endsWith("/diff/file") || uri.path == "/api/workspaces/browse" -> setOf("path")
            uri.path.matches(Regex("/api/sessions/$ID/queue/[0-9]{1,4}(/move)?")) -> setOf("to", "revision")
            else -> return false
        }
        val keys = mutableSetOf<String>()
        return query.split('&').all { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size != 2 || pair[0] !in allowedKeys || !keys.add(pair[0])) false
            else if (pair[0] in setOf("after", "before", "limit", "to")) pair[1].matches(Regex("[0-9]{1,18}"))
            else runCatching { URLDecoder.decode(pair[1], StandardCharsets.UTF_8).none { it.isISOControl() } }.getOrDefault(false)
        }
    }
}
