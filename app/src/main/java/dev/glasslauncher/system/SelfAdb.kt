package dev.glasslauncher.system

// Ported to Kotlin from Free My TV's Adb.java (github.com/simbasjungles/free-my-tv, commit 0e33f16, MIT,
// Copyright (c) 2026 Free My TV contributors). The MIT text is in licenses/free-my-tv-MIT.txt and NOTICE.

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Runs one command in a TV shell and returns its output (stdout and stderr together). */
fun interface TvShell { @Throws(IOException::class) fun run(command: String): String }

/**
 * An ADB client that talks to the TV itself (127.0.0.1:5555, open while USB debugging is on), with the app's own RSA key, so the user sees
 * one "Allow USB debugging?" prompt. It never runs at boot or idle: [HomeSwitch] opens it for one switch or undo, off the UI thread, and
 * closes it. Only fixed commands go through it, never text from outside.
 * Protocol: android.googlesource.com/platform/packages/modules/adb, protocol.txt
 */
class SelfAdb private constructor(private val socket: Socket) : Closeable, TvShell {
    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val input = DataInputStream(socket.getInputStream())
    private val out: OutputStream = socket.getOutputStream()
    private var nextLocalId = 1

    override fun run(command: String): String = shell(command)

    /** Runs a command in the TV's shell and returns its output. */
    @Throws(IOException::class)
    fun shell(command: String): String {
        val local = nextLocalId++
        send(OPEN, local, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
        var remote = 0
        val result = ByteArrayOutputStream()
        while (true) {
            val m = read()
            if (m.arg1 != local) continue
            when (m.command) {
                OKAY -> remote = m.arg0
                WRTE -> { result.write(m.data); send(OKAY, local, m.arg0, null) }
                CLSE -> {
                    if (remote == 0 && m.arg0 == 0) throw IOException("ADB refused the command")
                    send(CLSE, local, m.arg0, null)
                    return result.toString("UTF-8")
                }
            }
        }
    }

    override fun close() { runCatching { socket.close() } }

    private fun read(): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int; val a0 = b.int; val a1 = b.int; val length = b.int
        b.int // checksum (newer adbd doesn't check it)
        val magic = b.int
        if (command xor -1 != magic) throw IOException("invalid ADB message")
        if (length < 0 || length > 1024 * 1024) throw IOException("ADB message too large")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, a0, a1, data)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        val length = data?.size ?: 0
        var sum = 0
        data?.forEach { sum += it.toInt() and 0xff }
        val b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command xor -1)
        if (data != null) b.put(data)
        out.write(b.array())
        out.flush()
    }

    companion object {
        private const val CNXN = 0x4e584e43; private const val AUTH = 0x48545541; private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f; private const val CLSE = 0x45534c43; private const val WRTE = 0x45545257
        private const val STLS = 0x534c5453
        private const val VERSION = 0x01000001; private const val MAX_DATA = 256 * 1024
        private const val AUTH_TOKEN = 1; private const val AUTH_SIGNATURE = 2; private const val AUTH_RSAPUBLICKEY = 3
        const val PORT = 5555
        /** Start of the PKCS#1 block of a SHA-1 signature (adbd treats the token as if it were the digest). */
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /** Connects and authenticates. If the TV doesn't know our key yet, waits up to [approvalMs] for the user to accept; [approval] says the prompt is up. */
        @Throws(IOException::class)
        fun connect(context: Context, approvalMs: Int = 60_000, approval: (() -> Unit)? = null): SelfAdb {
            val keys = keys(context.filesDir)
            val s = Socket()
            try { s.connect(InetSocketAddress("127.0.0.1", PORT), 3000) } catch (e: IOException) {
                s.close(); throw IOException("USB debugging is off on the TV (port 5555 is closed)")
            }
            val adb = try { s.soTimeout = 10_000; SelfAdb(s) } catch (e: IOException) { s.close(); throw e } catch (e: RuntimeException) { s.close(); throw e }
            try {
                adb.send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII))
                var signed = false; var offered = false
                while (true) {
                    val m = try { adb.read() } catch (e: EOFException) {
                        throw IOException(if (offered) "the debugging permission wasn't accepted on the TV" else "the TV's ADB didn't respond")
                    } catch (e: SocketTimeoutException) {
                        throw IOException(if (offered) "the debugging permission wasn't accepted on the TV" else "the TV's ADB didn't respond")
                    }
                    if (m.command == CNXN) { s.soTimeout = 20_000; return adb }
                    if (m.command == STLS) throw IOException("the TV asked for TLS (wireless debugging); use USB debugging")
                    if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw IOException("unexpected reply from ADB")
                    if (!signed) {
                        adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.private, m.data))
                        signed = true
                    } else if (!offered) {
                        // Unknown key: send the public one; the TV shows "Allow USB debugging?".
                        adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.public))
                        offered = true
                        approval?.invoke()
                        if (android.os.Build.VERSION.SDK_INT >= 30) s.soTimeout = approvalMs
                        else {
                            // Android 8 to 10 hand the answer to the first connection that offered a key while it stays open.
                            // "Always allow" saves our key, so a new connection that only signs gets in.
                            val until = SystemClock.elapsedRealtime() + approvalMs
                            while (adb.input.available() == 0) {
                                if (SystemClock.elapsedRealtime() > until) throw IOException("the debugging permission wasn't accepted on the TV")
                                SystemClock.sleep(2000)
                                val fresh = signedIn(keys)
                                if (fresh != null) { adb.close(); return fresh }
                            }
                        }
                    } else throw IOException("the TV rejected the app's key")
                }
            } catch (e: IOException) { adb.close(); throw e } catch (e: RuntimeException) { adb.close(); throw e }
        }

        /** A new connection that only signs (it never offers the key, so no second prompt); null while the TV doesn't know it. */
        private fun signedIn(keys: KeyPair): SelfAdb? {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
                s.soTimeout = 5000
                val adb = SelfAdb(s)
                adb.send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII))
                var m = adb.read()
                if (m.command == AUTH && m.arg0 == AUTH_TOKEN) { adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.private, m.data)); m = adb.read() }
                if (m.command == CNXN) { s.soTimeout = 20_000; return adb }
            } catch (_: IOException) { } catch (_: RuntimeException) { }
            runCatching { s.close() }
            return null
        }

        /** The app's key, kept in private storage (never logged). */
        @Synchronized
        internal fun keys(dir: File): KeyPair {
            val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
            try {
                val factory = KeyFactory.getInstance("RSA")
                if (priv.exists() && pub.exists()) {
                    try { return KeyPair(factory.generatePublic(X509EncodedKeySpec(pub.readBytes())), factory.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))) }
                    catch (_: GeneralSecurityException) { } catch (_: IOException) { } // corrupted (power went out mid-write): a new key, the TV asks again
                }
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                write(priv, pair.private.encoded); write(pub, pair.public.encoded)
                return pair
            } catch (e: GeneralSecurityException) { throw IOException("ADB key: ${e.message}") }
        }

        internal fun sign(key: PrivateKey, token: ByteArray): ByteArray {
            val block = ByteArray(256)
            block[1] = 1
            val sep = block.size - SHA1_PREFIX.size - token.size - 1
            for (i in 2 until sep) block[i] = 0xff.toByte()
            SHA1_PREFIX.copyInto(block, sep + 1)
            token.copyInto(block, sep + 1 + SHA1_PREFIX.size)
            return try {
                Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(block)
            } catch (e: GeneralSecurityException) { throw IOException("ADB signature: ${e.message}") }
        }

        /** The public key in adbd's format (RSAPublicKey struct in base64, plus a name): 524 bytes before encoding. */
        internal fun publicKeyBlob(key: PublicKey, b64: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) }): ByteArray {
            val rsa = key as RSAPublicKey
            val n = rsa.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
            val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
            val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(64).putInt(n0inv.toInt()).put(littleEndian(n, 256)).put(littleEndian(rr, 256)).putInt(rsa.publicExponent.toInt())
            return (b64(b.array()) + " vitre@tv\u0000").toByteArray(Charsets.US_ASCII)
        }

        private fun littleEndian(v: BigInteger, size: Int): ByteArray {
            val big = v.toByteArray()
            return ByteArray(size).also { for (i in 0 until minOf(size, big.size)) it[i] = big[big.size - 1 - i] }
        }

        /** Writes all or nothing: a file alongside, synced, then renamed. */
        private fun write(f: File, data: ByteArray) {
            val tmp = File(f.path + ".tmp")
            java.io.FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
            if (!tmp.renameTo(f)) throw IOException("could not save ${f.name}")
        }
    }
}
