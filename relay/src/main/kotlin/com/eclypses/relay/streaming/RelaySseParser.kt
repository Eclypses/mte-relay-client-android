package com.eclypses.relay.streaming

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class RelaySseParser(
    private val maxPendingCharacters: Int = DEFAULT_MAX_PENDING_CHARACTERS,
) {
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    private val undecodedBytes = ByteBuffer.allocate(MAX_UTF8_BYTES_PER_CHARACTER)
    private val textBuffer = StringBuilder()
    private val pendingDataLines = mutableListOf<String>()
    private var pendingCommentOnly = false

    fun append(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()
        decode(chunk, endOfInput = false)
        enforceBufferLimit()
        return drainDecodedLines(allowTrailingFlush = false)
    }

    fun finish(): List<String> {
        decode(ByteArray(0), endOfInput = true)
        enforceBufferLimit()
        return drainDecodedLines(allowTrailingFlush = true)
    }

    private fun decode(chunk: ByteArray, endOfInput: Boolean) {
        val input = if (undecodedBytes.position() == 0) {
            ByteBuffer.wrap(chunk)
        } else {
            undecodedBytes.flip()
            val combined = ByteArray(undecodedBytes.remaining() + chunk.size)
            undecodedBytes.get(combined, 0, undecodedBytes.remaining())
            chunk.copyInto(combined, destinationOffset = combined.size - chunk.size)
            undecodedBytes.clear()
            ByteBuffer.wrap(combined)
        }

        val charBuffer = CharBuffer.allocate((input.remaining() * 2).coerceAtLeast(32))
        try {
            while (true) {
                val result = decoder.decode(input, charBuffer, endOfInput)
                if (result.isError) {
                    result.throwException()
                }
                if (result.isOverflow) {
                    charBuffer.flip()
                    textBuffer.append(charBuffer)
                    charBuffer.clear()
                    continue
                }
                break
            }

            if (endOfInput) {
                while (true) {
                    val flushResult = decoder.flush(charBuffer)
                    if (flushResult.isError) {
                        flushResult.throwException()
                    }
                    if (flushResult.isOverflow) {
                        charBuffer.flip()
                        textBuffer.append(charBuffer)
                        charBuffer.clear()
                        continue
                    }
                    break
                }
            }

            charBuffer.flip()
            textBuffer.append(charBuffer)
            if (endOfInput) {
                undecodedBytes.clear()
                decoder.reset()
            } else {
                storeUndecodedBytes(input)
            }
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("invalid UTF-8 sequence in SSE stream", error)
        }
    }

    private fun storeUndecodedBytes(input: ByteBuffer) {
        undecodedBytes.clear()
        if (!input.hasRemaining()) return
        require(input.remaining() <= undecodedBytes.remaining()) {
            "unexpected UTF-8 carry-over larger than ${MAX_UTF8_BYTES_PER_CHARACTER} bytes"
        }
        undecodedBytes.put(input)
    }

    private fun drainDecodedLines(allowTrailingFlush: Boolean): List<String> {
        val events = mutableListOf<String>()
        while (true) {
            val newlineIndex = textBuffer.indexOf("\n")
            if (newlineIndex == -1) break
            val rawLine = textBuffer.substring(0, newlineIndex)
            textBuffer.delete(0, newlineIndex + 1)
            processLine(rawLine.removeSuffix("\r"), events)
        }

        if (allowTrailingFlush && textBuffer.isNotEmpty()) {
            val trailingLine = textBuffer.toString().removeSuffix("\r")
            textBuffer.clear()
            processLine(trailingLine, events)
        }
        if (allowTrailingFlush) {
            flushCurrentEvent(events)
        }
        return events
    }

    private fun processLine(line: String, events: MutableList<String>) {
        if (line.isEmpty()) {
            flushCurrentEvent(events)
            return
        }

        if (line.startsWith(":")) {
            pendingCommentOnly = pendingDataLines.isEmpty()
            return
        }

        if (line == "data" || line.startsWith("data:")) {
            if (pendingDataLines.isNotEmpty()) {
                flushCurrentEvent(events)
            }
            pendingCommentOnly = false
            pendingDataLines += extractDataValue(line)
            return
        }
    }

    private fun extractDataValue(line: String): String {
        if (line == "data") return ""
        val rawValue = line.substringAfter(':', missingDelimiterValue = "")
        return if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
    }

    private fun flushCurrentEvent(events: MutableList<String>) {
        if (pendingDataLines.isNotEmpty()) {
            events += pendingDataLines.joinToString("\n")
            pendingDataLines.clear()
        }
        pendingCommentOnly = false
    }

    private fun enforceBufferLimit() {
        val pendingCharacters = textBuffer.length + pendingDataLines.sumOf { it.length } + if (pendingCommentOnly) 1 else 0
        if (pendingCharacters > maxPendingCharacters) {
            throw IllegalStateException(
                "SSE parser buffer exceeded limit of $maxPendingCharacters characters",
            )
        }
    }

    companion object {
        private const val MAX_UTF8_BYTES_PER_CHARACTER = 4
        private const val DEFAULT_MAX_PENDING_CHARACTERS = 64 * 1024
    }
}