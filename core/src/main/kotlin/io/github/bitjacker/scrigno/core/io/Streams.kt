package io.github.bitjacker.scrigno.core.io

import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

object Streams {
    const val BUFFER_SIZE = 64 * 1024

    /** Copies everything from [input] to [output], reporting the bytes copied so far. */
    fun copy(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            onProgress(total)
        }
        output.flush()
        return total
    }
}

/** Counts the bytes that flow through it. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile
    var count: Long = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) count += read
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count += skipped
        return skipped
    }

    override fun markSupported(): Boolean = false
}
