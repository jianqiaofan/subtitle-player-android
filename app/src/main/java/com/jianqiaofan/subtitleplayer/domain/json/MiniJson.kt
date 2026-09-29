package com.jianqiaofan.subtitleplayer.domain.json

import java.math.BigDecimal

sealed class JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val raw: String) : JsonValue() {
        val value: Double get() = raw.toDouble()

        fun longOrNull(): Long? {
            raw.toLongOrNull()?.let { return it }
            return try {
                val exact = BigDecimal(raw).toBigIntegerExact()
                if (exact.bitLength() > 63) null else exact.toLong()
            } catch (_: Exception) {
                null
            }
        }
    }
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()
}

fun parseJson(text: String): JsonValue = JsonParser(text).parse()

fun Map<String, JsonValue>.str(key: String): String? = (this[key] as? JsonValue.Str)?.value

fun Map<String, JsonValue>.bool(key: String): Boolean? = (this[key] as? JsonValue.Bool)?.value

fun Map<String, JsonValue>.obj(key: String): Map<String, JsonValue>? = (this[key] as? JsonValue.Obj)?.map

fun Map<String, JsonValue>.arr(key: String): List<JsonValue>? = (this[key] as? JsonValue.Arr)?.items

fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

fun jsonNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private class JsonParser(private val text: String) {
    private var i = 0

    fun parse(): JsonValue {
        skip()
        val value = readValue()
        skip()
        if (i != text.length) error("trailing")
        return value
    }

    private fun readValue(): JsonValue {
        skip()
        return when (val ch = peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> if (takeLiteral("true")) JsonValue.Bool(true) else error("value")
            'f' -> if (takeLiteral("false")) JsonValue.Bool(false) else error("value")
            'n' -> if (takeLiteral("null")) JsonValue.Null else error("value")
            else -> if (ch == '-' || ch.isDigit()) readNumber() else error("value")
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val map = linkedMapOf<String, JsonValue>()
        skip()
        if (peek() == '}') {
            i++
            return JsonValue.Obj(map)
        }
        while (true) {
            skip()
            val key = readString()
            skip()
            expect(':')
            map[key] = readValue()
            skip()
            when (peek()) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonValue.Obj(map)
                }
                else -> error("object")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skip()
        if (peek() == ']') {
            i++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += readValue()
            skip()
            when (peek()) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonValue.Arr(items)
                }
                else -> error("array")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            val ch = text[i++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    val esc = text[i++]
                    when (esc) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(i, i + 4)
                            i += 4
                            out.append(hex.toInt(16).toChar())
                        }
                        else -> error("escape")
                    }
                }
                else -> out.append(ch)
            }
        }
        error("string")
    }

    private fun readNumber(): JsonValue.Num {
        val start = i
        if (peek() == '-') i++
        while (i < text.length && text[i].isDigit()) i++
        if (i < text.length && text[i] == '.') {
            i++
            while (i < text.length && text[i].isDigit()) i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            while (i < text.length && text[i].isDigit()) i++
        }
        return JsonValue.Num(text.substring(start, i))
    }

    private fun takeLiteral(literal: String): Boolean {
        if (!text.startsWith(literal, i)) return false
        i += literal.length
        return true
    }

    private fun skip() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    private fun peek(): Char = text.getOrNull(i) ?: error("eof")

    private fun expect(ch: Char) {
        if (peek() != ch) error("expected $ch")
        i++
    }
}
