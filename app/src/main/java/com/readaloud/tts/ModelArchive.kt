package com.readaloud.tts

import android.content.Context
import android.os.StatFs
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A downloadable voice package: where it is, how big, and the SHA-256 it must have. */
class ModelSpec(val name: String, val url: String, val bytes: Long, val sha256: String)

/** Thrown when the user cancels; the partly downloaded file is kept so the next attempt can resume. */
class DownloadCancelled : IOException("Download cancelled")

/**
 * Downloads a .tar.bz2 voice package with resume, verifies its checksum, then unpacks it into the target folder.
 * Progress runs 0..1 across three phases (download, check, unpack) and [onStatus] says which one is under way.
 */
object ModelArchive {
    private fun partFile(ctx: Context, spec: ModelSpec) = File(File(ctx.cacheDir, "models").apply { mkdirs() }, "${spec.name}.part")

    /** Bytes of the partial download already on disk, so the UI can say "Resuming". */
    fun downloadedBytes(ctx: Context, spec: ModelSpec): Long = partFile(ctx, spec).takeIf { it.exists() }?.length() ?: 0L

    /** The archive plus the unpacked files must both fit at once, so ask for about twice its size. */
    fun hasRoomFor(ctx: Context, spec: ModelSpec): Boolean {
        val have = StatFs(ctx.filesDir.path).availableBytes
        return have > (spec.bytes - downloadedBytes(ctx, spec)) + spec.bytes * 1.2
    }

    fun install(
        ctx: Context, spec: ModelSpec, target: File, skip: (String) -> Boolean,
        isActive: () -> Boolean, onStatus: (String) -> Unit, onProgress: (Float) -> Unit,
    ) {
        val part = partFile(ctx, spec)
        if (!hasRoomFor(ctx, spec)) throw IOException("Not enough free storage: about ${spec.bytes * 22 / 10 / 1_000_000} MB is needed")
        onStatus(if (part.exists() && part.length() > 0) "Resuming download" else "Downloading")
        download(spec, part, isActive) { onProgress(0.8f * it) }

        onStatus("Checking download")
        if (sha256(part, isActive) { onProgress(0.8f + 0.05f * it) } != spec.sha256) {
            part.delete() // a corrupt file must not be resumed
            throw IOException("The download was corrupted. Please try again.")
        }

        onStatus("Unpacking")
        try {
            unpack(part, target, skip, isActive) { onProgress(0.85f + 0.15f * it) }
        } catch (e: Exception) {
            target.deleteRecursively() // never leave a half-extracted model that looks installed
            throw e
        }
        part.delete()
    }

    private fun download(spec: ModelSpec, part: File, isActive: () -> Boolean, onProgress: (Float) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (have > spec.bytes) { part.delete(); have = 0 }
        if (have == spec.bytes) { onProgress(1f); return }
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code == 200 && have > 0) have = 0 // the server ignored the range, so start over
            else if (code != 200 && code != 206) throw IOException("Server answered $code")
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                val buf = ByteArray(64 * 1024)
                var done = have
                conn.inputStream.use { input ->
                    while (true) {
                        if (!isActive()) throw DownloadCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress((done.toFloat() / spec.bytes).coerceAtMost(1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File, isActive: () -> Boolean, onProgress: (Float) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length().coerceAtLeast(1)
        var done = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (!isActive()) throw DownloadCancelled()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done.toFloat() / total)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unpack(archive: File, target: File, skip: (String) -> Boolean, isActive: () -> Boolean, onProgress: (Float) -> Unit) {
        target.deleteRecursively()
        target.mkdirs()
        val total = archive.length().coerceAtLeast(1)
        val counted = CountingStream(archive.inputStream().buffered()) { onProgress((it.toFloat() / total).coerceAtMost(1f)) }
        TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
            generateSequence { tar.nextEntry }.forEach { e ->
                if (!isActive()) throw DownloadCancelled()
                val rel = e.name.substringAfter('/', "") // drop the package's top-level folder
                if (e.isDirectory || rel.isEmpty() || skip(rel)) return@forEach
                val out = File(target, rel)
                require(out.canonicalPath.startsWith(target.canonicalPath + File.separator)) { "Bad archive entry: ${e.name}" }
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
            }
        }
    }

    private class CountingStream(input: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        private var n = 0L
        private var lastReport = 0L

        private fun add(read: Int) {
            if (read <= 0) return
            n += read
            if (n - lastReport > 256 * 1024) { lastReport = n; onBytes(n) }
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it) }
    }
}
