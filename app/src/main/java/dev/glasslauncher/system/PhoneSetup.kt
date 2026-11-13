package dev.glasslauncher.system

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

data class PhoneField(
    val key: String,
    val label: String,
    val value: String = "",
    val placeholder: String = "",
    val secret: Boolean = false,
)

/**
 * Typing API keys and URLs with a TV remote is painful (tvOS guideline REMOTE-08), so the TV can
 * serve a one-time form on the local network: scan the QR code, fill it in on a phone, and the
 * values arrive on the TV. Runs only while its panel is open, answers only on a random token path,
 * accepts one submission, and is bound to the LAN address.
 */
class PhoneSetupServer(
    private val title: String,
    private val fields: List<PhoneField>,
    private val onSubmit: suspend (Map<String, String>) -> Unit,
) {
    private val token = buildString {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        repeat(14) { append(chars[random.nextInt(chars.length)]) }
    }
    private var socket: ServerSocket? = null
    private var job: Job? = null

    var url: String? = null
        private set

    /** Starts listening; returns the URL for the QR code, or null when there's no LAN address. */
    suspend fun start(scope: CoroutineScope): String? = withContext(Dispatchers.IO) {
        val address = lanAddress() ?: return@withContext null
        val server = ServerSocket(0, 8, address)
        socket = server
        url = "http://${address.hostAddress}:${server.localPort}/$token"
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { client.use { handle(it) } }
            }
        }
        url
    }

    fun stop() {
        runCatching { socket?.close() }
        job?.cancel()
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val (method, path) = parts[0] to parts[1].substringBefore('?')
        var length = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val out = client.getOutputStream()
        when {
            path != "/$token" -> respond(out, 404, "<h1>Not found</h1>")
            method == "GET" -> respond(out, 200, page(sent = false))
            method == "POST" && length in 0..32_768 -> {
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val values = String(body, 0, read).split('&').filter { '=' in it }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8").trim()
                }.filterKeys { k -> fields.any { it.key == k } }
                withContext(Dispatchers.Main) { onSubmit(values) }
                respond(out, 200, page(sent = true))
            }
            else -> respond(out, 400, "<h1>Bad request</h1>")
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun respond(out: OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray()
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(sent: Boolean): String {
        val body = if (sent) {
            """<div class="done">✓</div><h1>Sent to your TV</h1><p>You can close this page.</p>"""
        } else {
            val inputs = fields.joinToString("") { f ->
                """<label>${esc(f.label)}<input name="${esc(f.key)}" value="${esc(f.value)}" placeholder="${esc(f.placeholder)}" autocomplete="off" autocapitalize="off" spellcheck="false"${if (f.secret) " type=\"password\"" else ""}></label>"""
            }
            """<h1>${esc(title)}</h1><p>Fields you leave empty stay unchanged.</p><form method="post">$inputs<button>Send to TV</button></form>"""
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>VITRE</title><style>
:root{color-scheme:dark}body{margin:0;min-height:100vh;font:16px -apple-system,system-ui,sans-serif;color:#fff;
background:radial-gradient(circle at 20% 10%,#3a2e8f,transparent 55%),radial-gradient(circle at 90% 30%,#0e7c86,transparent 50%),#0b1026;display:flex;justify-content:center}
main{margin:24px 16px;padding:24px;max-width:460px;width:100%;border-radius:28px;background:rgba(255,255,255,.08);
border:1px solid rgba(255,255,255,.2);backdrop-filter:blur(30px);-webkit-backdrop-filter:blur(30px);box-sizing:border-box}
h1{font-size:24px;margin:0 0 6px}p{opacity:.7;margin:0 0 18px}label{display:block;font-weight:600;margin:0 0 14px}
input{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:14px;border-radius:14px;border:1px solid rgba(255,255,255,.25);
background:rgba(0,0,0,.25);color:#fff;font-size:16px}button{width:100%;padding:15px;border:0;border-radius:999px;background:#fff;color:#111;font-weight:700;font-size:17px;margin-top:6px}
.done{font-size:56px}</style></head><body><main>$body</main></body></html>"""
    }

    companion object {
        fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        fun qr(text: String, size: Int = 360): ImageBitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val pixels = IntArray(matrix.width * matrix.height) { i ->
                if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}
