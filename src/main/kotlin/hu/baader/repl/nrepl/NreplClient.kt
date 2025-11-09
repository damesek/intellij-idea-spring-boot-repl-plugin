package hu.baader.repl.nrepl

import java.io.*
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class NreplClient(private val host: String, private val port: Int) : AutoCloseable {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var session: String? = null
    
    private val messageHandlers = ConcurrentLinkedQueue<(Map<String, String>) -> Unit>()
    private var readerThread: Thread? = null
    private val pending = java.util.concurrent.ConcurrentHashMap<String, (Map<String,String>)->Unit>()
    
    fun connect() {
        close()
        
        socket = Socket(host, port).apply {
            tcpNoDelay = true
        }
        
        val outputStream = socket?.getOutputStream() ?: throw IOException("No output stream")
        val inputStream = socket?.getInputStream() ?: throw IOException("No input stream")
        
        writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        
        // Clone session
        val cloneId = uuid()
        send(mapOf("op" to "clone", "id" to cloneId))
        
        // Start reader thread
        readerThread = thread(isDaemon = true) {
            try {
                while (!Thread.interrupted()) {
                    val msg = readBencode()
                    if (msg != null) {
                        // Extract session from clone response
                        if (msg["id"] == cloneId && msg.containsKey("new-session")) {
                            session = msg["new-session"]
                        }
                        // Per-id callback if present
                        msg["id"]?.let { id -> pending.remove(id)?.invoke(msg) }
                        
                        // Notify handlers
                        messageHandlers.forEach { it(msg) }
                    }
                }
            } catch (e: Exception) {
                if (!Thread.interrupted()) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    fun evalJava(code: String) {
        val payload = mapOf(
            "op" to "java-eval",
            "code" to code,
            "id" to uuid(),
            "session" to (session ?: "")
        )
        send(payload)
    }
    
    fun evalClojure(code: String) {
        val payload = mapOf(
            "op" to "eval",
            "code" to code,
            "id" to uuid(),
            "session" to (session ?: "")
        )
        send(payload)
    }

    fun sendOp(op: String, extra: Map<String, String> = emptyMap(), cb: ((Map<String,String>)->Unit)? = null) {
        val id = uuid()
        val payload = mutableMapOf(
            "op" to op,
            "id" to id,
            "session" to (session ?: "")
        )
        payload.putAll(extra)
        if (cb != null) pending[id] = cb
        send(payload)
    }
    
    fun onMessage(handler: (Map<String, String>) -> Unit) {
        messageHandlers.add(handler)
    }
    
    private fun send(message: Map<String, String>) {
        val bencode = encodeBencode(message)
        writer?.apply {
            write(bencode)
            flush()
        }
    }
    
    private fun encodeBencode(map: Map<String, String>): String {
        val sb = StringBuilder("d")
        map.entries.sortedBy { it.key }.forEach { (k, v) ->
            sb.append("${k.length}:$k")
            sb.append("${v.length}:$v")
        }
        sb.append("e")
        return sb.toString()
    }
    
    private fun readBencode(): Map<String, String>? {
        val r = reader ?: return null

        // Expect dictionary start 'd'
        val start = r.read()
        if (start == -1) return null
        if (start.toChar() != 'd') return null

        val result = mutableMapOf<String, String>()

        loop@ while (true) {
            val ch = r.read()
            if (ch == -1) return null
            val c = ch.toChar()
            if (c == 'e') break // end of dict

            // key must be a string: first char is a digit of length
            if (!c.isDigit()) {
                // malformed; skip dictionary
                skipUntilDictEnd(r)
                break
            }
            val keyLength = readNumber(c, r)
            r.read() // ':'
            val keyBuf = CharArray(keyLength)
            r.read(keyBuf)
            val key = String(keyBuf)

            // Determine value type by peeking next char
            r.mark(1)
            val vcInt = r.read()
            if (vcInt == -1) return null
            val vc = vcInt.toChar()

            when {
                vc.isDigit() -> {
                    // string value
                    val valueLength = readNumber(vc, r)
                    r.read() // ':'
                    val valueChars = CharArray(valueLength)
                    r.read(valueChars)
                    result[key] = String(valueChars)
                }
                vc == 'l' -> {
                    // list - skip it, but if elements are strings, you can join; here we skip
                    skipList(r)
                }
                vc == 'd' -> {
                    // nested dict - skip
                    skipDict(r)
                }
                vc == 'i' -> {
                    // integer - read until 'e'
                    val num = readInteger(r)
                    result[key] = num.toString()
                }
                else -> {
                    // Unknown; skip token generically
                    skipUnknown(r, vc)
                }
            }
        }

        return result
    }

    private fun readNumber(firstChar: Char, reader: BufferedReader): Int {
        val sb = StringBuilder()
        sb.append(firstChar)
        while (true) {
            reader.mark(1)
            val next = reader.read()
            if (next == -1) break
            val c = next.toChar()
            if (c == ':') {
                reader.reset()
                break
            }
            sb.append(c)
        }
        return sb.toString().toInt()
    }

    private fun readInteger(reader: BufferedReader): Long {
        // reads digits until 'e'
        val sb = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) break
            val c = ch.toChar()
            if (c == 'e') break
            sb.append(c)
        }
        return sb.toString().toLongOrNull() ?: 0L
    }

    private fun skipUntilDictEnd(reader: BufferedReader) {
        // naive skip: read until 'e' (dict end)
        while (true) {
            val ch = reader.read()
            if (ch == -1 || ch.toChar() == 'e') break
        }
    }

    private fun skipList(reader: BufferedReader) {
        // list starts after 'l' already consumed; consume items until matching 'e'
        var depth = 1
        while (depth > 0) {
            val ch = reader.read()
            if (ch == -1) return
            when (ch.toChar()) {
                'l' -> depth++
                'd' -> { skipDict(reader); }
                'i' -> { readInteger(reader) }
                'e' -> depth--
                else -> {
                    if (ch.toChar().isDigit()) {
                        // string element
                        val len = readNumber(ch.toChar(), reader)
                        reader.read() // ':'
                        val buf = CharArray(len)
                        reader.read(buf)
                    }
                }
            }
        }
    }

    private fun skipDict(reader: BufferedReader) {
        // dict start 'd' is already consumed; consume until its 'e'
        var depth = 1
        while (depth > 0) {
            val ch = reader.read()
            if (ch == -1) return
            val c = ch.toChar()
            when {
                c == 'e' -> depth--
                c.isDigit() -> {
                    // key string
                    val len = readNumber(c, reader)
                    reader.read() // ':'
                    val keyBuf = CharArray(len)
                    reader.read(keyBuf)
                    // value peek
                    reader.mark(1)
                    val vcInt = reader.read()
                    if (vcInt == -1) return
                    val vc = vcInt.toChar()
                    when {
                        vc.isDigit() -> {
                            val vlen = readNumber(vc, reader)
                            reader.read() // ':'
                            val vbuf = CharArray(vlen)
                            reader.read(vbuf)
                        }
                        vc == 'l' -> skipList(reader)
                        vc == 'd' -> skipDict(reader)
                        vc == 'i' -> readInteger(reader)
                        else -> skipUnknown(reader, vc)
                    }
                }
                else -> {
                    // unexpected token; try to recover
                }
            }
        }
    }

    private fun skipUnknown(reader: BufferedReader, first: Char) {
        // For now, if first is not recognized, try to read until 'e' or newline
        if (first.isDigit()) {
            val len = readNumber(first, reader)
            reader.read()
            val buf = CharArray(len)
            reader.read(buf)
            return
        }
        if (first == 'i') { readInteger(reader); return }
        if (first == 'l') { skipList(reader); return }
        if (first == 'd') { skipDict(reader); return }
        // otherwise, consume until 'e'
        while (true) {
            val ch = reader.read()
            if (ch == -1 || ch.toChar() == 'e') break
        }
    }
    
    override fun close() {
        readerThread?.interrupt()
        readerThread = null
        
        writer?.close()
        reader?.close()
        socket?.close()
        
        writer = null
        reader = null
        socket = null
        session = null
    }
    
    companion object {
        private fun uuid(): String = UUID.randomUUID().toString()
    }
}
