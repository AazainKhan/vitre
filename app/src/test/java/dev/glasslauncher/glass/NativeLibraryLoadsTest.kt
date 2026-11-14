package dev.glasslauncher.glass

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P347: libglassimg.so is no_std, so nothing in it may name a symbol the system cannot give. The arm64 build named
 * `rust_eh_personality` and `dlopen` failed on every 64-bit device (the kernels silently fell back to Skia, and no JVM test could
 * see it because the library does not load here). This reads each committed .so's dynamic symbol table and checks that every
 * undefined symbol is one bionic or the NDK's jnigraphics provides.
 */
class NativeLibraryLoadsTest {
    private val provided = Regex("(__cxa_finalize|__cxa_atexit|__register_atfork|abort|mallopt|AndroidBitmap_(getInfo|lockPixels|unlockPixels))(@.*)?")

    private fun root() = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/jniLibs").isDirectory }

    /** Names of the undefined dynamic symbols of an ELF shared object (32 or 64 bit, little endian). */
    private fun undefined(file: File): List<String> {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val is64 = b.get(4).toInt() == 2
        val shoff = if (is64) b.getLong(0x28).toInt() else b.getInt(0x20)
        val shentsize = b.getShort(if (is64) 0x3A else 0x2E).toInt()
        val shnum = b.getShort(if (is64) 0x3C else 0x30).toInt()
        fun sh(i: Int, off32: Int, off64: Int, len64: Boolean) = shoff + i * shentsize + (if (is64) off64 else off32)
        var dynsym = -1
        for (i in 0 until shnum) if (b.getInt(shoff + i * shentsize + 4) == 11) dynsym = i   // SHT_DYNSYM
        check(dynsym >= 0) { "no dynamic symbol table in ${file.name}" }
        val base = shoff + dynsym * shentsize
        val off = if (is64) b.getLong(base + 0x18).toInt() else b.getInt(base + 0x10)
        val size = if (is64) b.getLong(base + 0x20).toInt() else b.getInt(base + 0x14)
        val strIndex = b.getInt(base + (if (is64) 0x28 else 0x18))
        val strBase = shoff + strIndex * shentsize
        val strOff = if (is64) b.getLong(strBase + 0x18).toInt() else b.getInt(strBase + 0x10)
        val entSize = if (is64) 24 else 16
        val out = mutableListOf<String>()
        for (n in 1 until size / entSize) {
            val e = off + n * entSize
            val nameIdx = b.getInt(e)
            val shndx = b.getShort(if (is64) e + 6 else e + 14).toInt() and 0xFFFF
            if (shndx != 0 || nameIdx == 0) continue
            var p = strOff + nameIdx; val sb = StringBuilder()
            while (b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar())
            out += sb.toString()
        }
        return out
    }

    @Test fun everyBundledLibraryOnlyNamesWhatTheSystemProvides() {
        val libs = File(root(), "app/src/main/jniLibs").walkTopDown().filter { it.name == "libglassimg.so" }.toList()
        assertTrue("both ABIs are bundled", libs.size >= 2)
        for (lib in libs) {
            val missing = undefined(lib).filterNot { provided.matches(it) }
            assertTrue("${lib.parentFile.name}/${lib.name} names symbols the system does not provide, so dlopen fails: $missing", missing.isEmpty())
        }
    }
}
