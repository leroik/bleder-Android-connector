package dev.hybrid.scanner

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class Pairing(val host: String, val port: Int, val token: String) {
    val url = "http://$host:$port"
    companion object {
        fun parse(input: String): Pairing {
            val parts = input.trim().split(':')
            require(parts.size == 4 && parts[0] == "h3s1") { "Нужен код h3s1 из окна Scan Engine" }
            val octets = parts[1].split('.').map { it.toIntOrNull() }
            require(octets.size == 4 && octets.all { it != null && it in 0..255 } &&
                (octets[0] == 10 || octets[0] == 127 ||
                    octets[0] == 192 && octets[1] == 168 ||
                    octets[0] == 172 && octets[1] in 16..31 ||
                    octets[0] == 169 && octets[1] == 254 ||
                    octets[0] == 100 && octets[1] in 64..127)) { "Укажите локальный IPv4 ПК" }
            val port = parts[2].toIntOrNull()
            require(port != null && port in 1..65535 && parts[3].matches(Regex("[a-zA-Z0-9_-]{30,100}"))) {
                "Некорректный порт или токен"
            }
            return Pairing(parts[1], port, parts[3])
        }
    }
}

internal class HttpFailure(val code: Int, message: String) : IOException(message)

internal class ScanNetwork(private val pair: Pairing) {
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .proxy(java.net.Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile var meshNotice: Int = -1
        private set
    private var lastHeartbeat = 0L

    private fun request(path: String) = Request.Builder().url(pair.url + path)
        .header("Authorization", "Bearer ${pair.token}")

    fun events() {
        if (closed || socket != null) return
        synchronized(this) {
            if (closed || socket != null) return
            socket = client.newWebSocket(request("/v1/events").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket !== webSocket) return
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (json.optString("type") == "mesh.updated") meshNotice = json.optInt("revision", -1)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reset(webSocket)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reset(webSocket)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    reset(webSocket)
                }
            })
        }
    }

    private fun reset(ws: WebSocket) {
        synchronized(this) {
            if (socket === ws) {
                socket = null
                meshNotice = -1
            }
        }
    }

    fun state(): JSONObject {
        events()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHeartbeat >= 1800) {
            val result = command("connection.heartbeat", null)
            lastHeartbeat = now
            return result
        }
        return get("/v1/status")
    }

    fun close() {
        synchronized(this) {
            closed = true
            socket?.cancel()
            socket = null
            meshNotice = -1
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    fun get(path: String): JSONObject = json(request(path).build())

    fun command(kind: String, sid: String?, fmt: String? = null): JSONObject {
        val message = JSONObject().put("version", 1).put("type", kind).put("role", "android")
        if (sid != null) message.put("session", sid)
        if (fmt != null) message.put("format", fmt)
        return json(request("/v1/command").post(message.toString()
            .toRequestBody("application/json".toMediaType())).build())
    }

    fun upload(sid: String, file: File) {
        val data = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        val response = json(request("/v1/sessions/$sid/frames").header("X-SHA256", hash)
            .post(data.toRequestBody("application/octet-stream".toMediaType())).build())
        check(response.getInt("ack") == file.nameWithoutExtension.toInt()) { "Неверный ACK кадра" }
    }

    fun download(sid: String, format: String, file: File) {
        client.newCall(request("/v1/sessions/$sid/export/$format").build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code, response.message)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.outputStream().use { output ->
                    requireNotNull(response.body).byteStream().use { it.copyTo(output) }
                }
                check(tmp.renameTo(file)) { "Не удалось сохранить экспорт" }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun json(req: Request): JSONObject = client.newCall(req).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
            throw HttpFailure(response.code, "${response.code}: ${detail.ifBlank { response.message }}")
        }
        JSONObject(body)
    }
}
