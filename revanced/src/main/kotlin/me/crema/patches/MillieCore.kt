package me.crema.patches

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure JVM/Android implementation: no shell, native executable, or post-signing hook. */
object MillieCore {
    private fun digest(bytes: ByteArray, algorithm: String) =
        MessageDigest.getInstance(algorithm).digest(bytes)

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sha256(bytes: ByteArray) = hex(digest(bytes, "SHA-256"))
    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value
    private fun little(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun applyDelta(original: ByteArray, compressed: ByteArray): ByteArray {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(compressed))).use { input ->
            fun bytes(size: Int) = ByteArray(size).also { input.readFully(it) }
            require(String(bytes(6), Charsets.US_ASCII) == "MDLT01") { "Invalid delta format" }
            val sourceHash = bytes(32)
            val targetHash = bytes(32)
            require(input.readInt() == original.size && digest(original, "SHA-256").contentEquals(sourceHash)) {
                "Unsupported or already patched DEX; select the original Millie e-ink 2.1.0.0 APK"
            }
            val size = input.readInt()
            require(size in 1..16_777_216) { "Invalid delta output length" }
            val out = ByteArrayOutputStream(size)
            while (true) {
                when (input.readUnsignedByte()) {
                    0 -> {
                        val offset = input.readInt()
                        val length = input.readInt()
                        require(offset >= 0 && length > 0 && offset.toLong() + length <= original.size &&
                            out.size().toLong() + length <= size) { "Invalid delta copy range" }
                        out.write(original, offset, length)
                    }
                    1 -> {
                        val length = input.readInt()
                        require(length > 0 && out.size().toLong() + length <= size) { "Invalid delta literal length" }
                        out.write(bytes(length))
                    }
                    255 -> break
                    else -> error("Unknown delta operation")
                }
            }
            val result = out.toByteArray()
            require(input.read() == -1 && result.size == size && digest(result, "SHA-256").contentEquals(targetHash)) {
                "Corrupt delta output"
            }
            validateDex(result)
            return result
        }
    }

    fun validateDex(bytes: ByteArray) {
        require(bytes.size >= 112 && bytes.copyOfRange(0, 8).contentEquals("dex\n035\u0000".toByteArray())) {
            "Expected an Android 4.4 compatible DEX 035"
        }
        val header = little(bytes)
        require(header.getInt(32) == bytes.size && header.getInt(36) == 112) { "Invalid DEX size" }
        require(bytes.copyOfRange(12, 32).contentEquals(digest(bytes.copyOfRange(32, bytes.size), "SHA-1"))) {
            "Invalid DEX SHA-1"
        }
        require((header.getInt(8).toLong() and 0xffffffffL) == Adler32().apply {
            update(bytes, 12, bytes.size - 12)
        }.value) { "Invalid DEX Adler32" }
    }

    private fun crypt(bytes: ByteArray, metadata: ByteArray, mode: Int): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(metadata.copyOfRange(8, 40), "AES"),
                IvParameterSpec(metadata.copyOfRange(40, 56)))
            doFinal(bytes)
        }

    fun decodeAsset(asset: ByteArray): Pair<ByteArray, ByteArray> {
        require(asset.size >= 104 && (asset.size - 88) % 16 == 0) { "Invalid asset length" }
        val split = minOf(1024, asset.size - 88)
        val meta = asset.copyOfRange(split, split + 88)
        val padded = crypt(asset.copyOfRange(0, split) + asset.copyOfRange(split + 88, asset.size), meta, Cipher.DECRYPT_MODE)
        val size = little(meta).getInt(0)
        require(size > 0 && size <= padded.size && padded.size - size < 16) { "Invalid asset plaintext length" }
        require((size until padded.size).all { padded[it] == 32.toByte() }) { "Invalid asset padding" }
        val plain = padded.copyOf(size)
        require(hex(digest(plain, "MD5")) == String(meta.copyOfRange(56, 88), Charsets.US_ASCII)) { "Asset MD5 mismatch" }
        return plain to meta
    }

    fun encodeAsset(plain: ByteArray, template: ByteArray): ByteArray {
        val meta = decodeAsset(template).second.copyOf()
        require(plain.isNotEmpty())
        little(meta).putInt(0, plain.size)
        hex(digest(plain, "MD5")).toByteArray(Charsets.US_ASCII).copyInto(meta, 56)
        val padding = ByteArray((16 - plain.size % 16) % 16) { 32 }
        val ciphertext = crypt(plain + padding, meta, Cipher.ENCRYPT_MODE)
        val split = minOf(1024, ciphertext.size)
        return ciphertext.copyOfRange(0, split) + meta + ciphertext.copyOfRange(split, ciphertext.size)
    }

    private fun replaceUnique(bytes: ByteArray, before: ByteArray, after: ByteArray) {
        require(before.size == after.size)
        var found = -1
        for (i in 0..bytes.size - before.size) {
            if (before.indices.all { bytes[i + it] == before[it] }) {
                require(found == -1) { "Ambiguous integrity record" }
                found = i
            }
        }
        require(found >= 0) { "Expected integrity record missing" }
        after.copyInto(bytes, found)
    }

    private fun nibble(text: String) = text.toByteArray(Charsets.US_ASCII).flatMap {
        listOf((it.toInt() ushr 4).toByte(), (it.toInt() and 15).toByte())
    }.toByteArray()

    fun patch(files: Map<String, ByteArray>, payload: (String) -> ByteArray): Map<String, ByteArray> {
        val profile = Properties().apply { load(ByteArrayInputStream(payload("inputs.properties"))) }
        for (name in profile.stringPropertyNames()) {
            require(sha256(files.getValue(name)) == profile.getProperty(name)) {
                "Unsupported or modified input: $name. Use the original Millie e-ink 2.1.0.0 APK."
            }
        }
        require(!files.containsKey("classes6.dex")) { "Expected exactly five original DEX files" }
        val changed = linkedMapOf<String, ByteArray>()
        for (name in listOf("classes.dex", "classes5.dex")) {
            changed[name] = applyDelta(files.getValue(name), payload("$name.delta.gz"))
        }
        val dexNames = listOf("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex", "classes5.dex")
        fun record(patched: Boolean) = dexNames.joinToString("") { name ->
            val value = crc(if (patched) changed[name] ?: files.getValue(name) else files.getValue(name))
            require(value != 0L) { "Unexpected zero DEX CRC" }
            "%08x".format(value)
        } + "41424344".repeat(3)
        val oldRecord = record(false)
        val newRecord = record(true)
        val originalModule = files.getValue("assets/m7a")
        val module = decodeAsset(originalModule).first
        fun instruction(offset: Int, before: String, after: String) {
            fun fromHex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val expected = fromHex(before)
            require(module.copyOfRange(offset, offset + expected.size).contentEquals(expected)) {
                "Unsupported ARM module at 0x${offset.toString(16)}"
            }
            fromHex(after).copyInto(module, offset)
        }
        instruction(0x1d09be, "00f0b9f8", "012000bf")
        instruction(0xe8c30, "9501feeb", "0000a0e1")
        replaceUnique(module, "gc1:".toByteArray() + nibble(oldRecord) + byteArrayOf(59),
            "gc1:".toByteArray() + nibble(newRecord) + byteArrayOf(59))
        val newModule = encodeAsset(module, originalModule)
        changed["assets/m7a"] = newModule
        val originalConfig = files.getValue("assets/agconfig")
        val config = decodeAsset(originalConfig).first
        replaceUnique(config, "110:$oldRecord;".toByteArray(), "110:$newRecord;".toByteArray())
        replaceUnique(config, "141:${sha256(originalModule)};".toByteArray(), "141:${sha256(newModule)};".toByteArray())
        changed["assets/agconfig"] = encodeAsset(config, originalConfig)
        val native = payload("libconscrypt_jni.so")
        require(sha256(native) == String(payload("conscrypt.sha256"), Charsets.US_ASCII).trim()) { "Corrupt Conscrypt JNI payload" }
        changed["lib/armeabi-v7a/libconscrypt_jni.so"] = native
        return changed
    }
}
