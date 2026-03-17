package com.dexradar.logger

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DiscoveryLogger(private val context: Context) {

    private val queue = LinkedBlockingQueue<String>(10000)
    private val recentLogs = ArrayDeque<String>(500)
    private val occurrenceCount = ConcurrentHashMap<String, Int>()
    private val MAX_OCCURRENCES = 20

    private val logFile: File by lazy {
        val extDir = Environment.getExternalStorageDirectory()
        val dir = if (extDir.canWrite()) File(extDir, "dexradar") else context.filesDir
        dir.mkdirs()
        File(dir, "discovery_log.txt")
    }

    private val writerThread = Thread {
        try {
            PrintWriter(FileWriter(logFile, true)).use { writer ->
                while (!Thread.currentThread().isInterrupted) {
                    val line = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    writer.println(line)
                    writer.flush()
                    synchronized(recentLogs) {
                        if (recentLogs.size >= 500) recentLogs.removeFirst()
                        recentLogs.addLast(line)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }.also { it.isDaemon = true; it.start() }

    // Log all params for JoinFinished / NewCharacter (capped at MAX_OCCURRENCES)
    fun maybeLogAllParams(eventName: String, params: HashMap<Int, Any?>) {
        val targetEvents = setOf("JoinFinished", "NewCharacter")
        if (eventName !in targetEvents) return
        val count = occurrenceCount.merge(eventName, 1, Int::plus) ?: return
        if (count > MAX_OCCURRENCES) return

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("[$ts] PARAMS $eventName (occurrence $count/$MAX_OCCURRENCES)")
        sb.appendLine("  Param count: ${params.size}")
        params.entries.sortedBy { it.key }.forEach { (k, v) ->
            sb.appendLine("  key=${k.toString().padEnd(4)} type=${(v?.javaClass?.simpleName ?: "Null").padEnd(10)} value=$v")
        }
        queue.offer(sb.toString())
    }

    // Plan C failure — log all params for debugging
    fun logDiscovery(eventName: String, objectId: Int, params: HashMap<Int, Any?>) {
        val key = "DISCOVERY:$eventName"
        val count = occurrenceCount.merge(key, 1, Int::plus) ?: return
        if (count > MAX_OCCURRENCES) return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val sb = StringBuilder("[$ts] DISCOVERY $eventName objectId=$objectId\n")
        params.entries.sortedBy { it.key }.forEach { (k, v) ->
            sb.appendLine("  key=$k type=${v?.javaClass?.simpleName} val=$v")
        }
        queue.offer(sb.toString())
    }

    // Completely unknown event code
    fun logUnknownEvent(code: Int, params: HashMap<Int, Any?>) {
        val key = "UNKNOWN_CODE:$code"
        val count = occurrenceCount.merge(key, 1, Int::plus) ?: return
        if (count > 5) return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val sb = StringBuilder("[$ts] UNKNOWN_EVENT code=$code\n")
        params.entries.sortedBy { it.key }.forEach { (k, v) ->
            if (v is String) sb.appendLine("  key=$k STRING: $v")
            else if (v is Int) sb.appendLine("  key=$k INT: $v")
        }
        queue.offer(sb.toString())
    }

    fun logUnknownType(typeCode: Int) {
        queue.offer("UNKNOWN_TYPE_CODE: 0x${typeCode.toString(16)}")
    }

    fun getRecentLogs(n: Int): List<String> = synchronized(recentLogs) { recentLogs.takeLast(n) }

    fun stop() { writerThread.interrupt() }
}
