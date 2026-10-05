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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure JVM/Android implementation: no shell, native executable, or post-signing hook. */
object MillieCore {
    enum class Version(val appVersion: String, val prefix: String, val dexCount: Int) {
        V21("2.1.0.0", "", 5),
        V24("2.4.0.0", "2.4.0.0/", 1);

        val dexNames get() = (1..dexCount).map { if (it == 1) "classes.dex" else "classes$it.dex" }
    }

    fun inputProfile(version: Version, payload: (String) -> ByteArray) = Properties().apply {
        load(ByteArrayInputStream(payload("${version.prefix}inputs.properties")))
    }

    fun identifyVersion(manifest: ByteArray, payload: (String) -> ByteArray): Version {
        val hash = sha256(manifest)
        return requireNotNull(Version.entries.singleOrNull {
            inputProfile(it, payload).getProperty("AndroidManifest.xml") == hash
        }) { "Unsupported manifest. Select an original Millie e-ink 2.1.0.0 or 2.4.0.0 APK." }
    }

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
                "Unsupported or already patched DEX; select the supported original APK"
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

    // The 2.4 application DEX lives in an encrypted single-entry ZIP. Bound reads
    // before applying the DEX delta; do not distribute the complete ZIP or DEX.
    fun patchPackedDex(asset: ByteArray, delta: ByteArray): ByteArray {
        val archive = decodeAsset(asset).first
        var timestamp = 0L
        val original = ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            val entry = requireNotNull(zip.nextEntry) { "Missing packed DEX" }
            require(entry.name == "classes.dex" && !entry.isDirectory) { "Unexpected packed DEX entry" }
            timestamp = entry.time
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                require(out.size() + count <= 16_777_216) { "Packed DEX exceeds size limit" }
                out.write(buffer, 0, count)
            }
            require(zip.nextEntry == null) { "Unexpected extra packed DEX entry" }
            out.toByteArray()
        }
        validateDex(original)
        val modified = applyDelta(original, delta)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex").apply { time = timestamp })
            zip.write(modified)
            zip.closeEntry()
        }
        return encodeAsset(out.toByteArray(), asset)
    }

    fun patch(files: Map<String, ByteArray>, payload: (String) -> ByteArray): Map<String, ByteArray> {
        val version = identifyVersion(files.getValue("AndroidManifest.xml"), payload)
        val profile = inputProfile(version, payload)
        for (name in profile.stringPropertyNames()) {
            require(sha256(files.getValue(name)) == profile.getProperty(name)) {
                "Unsupported or modified input: $name. Use the supported original ${version.appVersion} APK (not the file named 2.5.0.0)."
            }
        }
        val dexNames = version.dexNames
        require(files.keys.filter { Regex("classes\\d*\\.dex").matches(it) }.toSet() == dexNames.toSet()) {
            "Unexpected DEX layout for ${version.appVersion}"
        }
        require(!files.containsKey("lib/armeabi-v7a/libconscrypt_jni.so")) { "This APK already contains Conscrypt" }
        val changed = linkedMapOf<String, ByteArray>()
        val changedDex = if (version == Version.V21) listOf("classes.dex", "classes5.dex") else listOf("classes.dex")
        for (name in changedDex) {
            changed[name] = applyDelta(files.getValue(name), payload("${version.prefix}$name.delta.gz"))
        }
        if (version == Version.V24) {
            changed["assets/classes3.jet"] = patchPackedDex(files.getValue("assets/classes3.jet"),
                payload("${version.prefix}classes3.dex.delta.gz"))
        }
        fun record(patched: Boolean) = dexNames.joinToString("") { name ->
            val value = crc(if (patched) changed[name] ?: files.getValue(name) else files.getValue(name))
            require(value != 0L) { "Unexpected zero DEX CRC" }
            "%08x".format(value)
        } + "41424344".repeat(8 - dexNames.size)
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
        // In 2.4, config 110 describes the pre-packing DEX, not the outer DEX.
        // Preserve it exactly; gc1 above covers the actual outer ZIP entries.
        if (version == Version.V21) {
            replaceUnique(config, "110:$oldRecord;".toByteArray(), "110:$newRecord;".toByteArray())
        }
        replaceUnique(config, "141:${sha256(originalModule)};".toByteArray(), "141:${sha256(newModule)};".toByteArray())
        changed["assets/agconfig"] = encodeAsset(config, originalConfig)
        val native = payload("libconscrypt_jni.so")
        require(sha256(native) == String(payload("conscrypt.sha256"), Charsets.US_ASCII).trim()) { "Corrupt Conscrypt JNI payload" }
        changed["lib/armeabi-v7a/libconscrypt_jni.so"] = native
        return changed
    }
}
