package dev.kyungmin.kcoach.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Emulator-only host relay client. The production path remains Wearable Data
 * Layer; this client makes two local Android emulators behave like a paired
 * phone/watch while the real hardware is unavailable.
 */
class EmulatorBridgeClient(
    private val onPayload: (path: String, payload: String) -> Unit,
) {
    @Volatile
    private var running = false
    private var readerThread: Thread? = null
    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    fun start() {
        if (running) return
        running = true
        readerThread = thread(name = "kcoach-emulator-bridge", start = true) {
            while (running) {
                try {
                    Socket(WearSync.EMULATOR_BRIDGE_HOST, WearSync.EMULATOR_BRIDGE_PORT).use { newSocket ->
                        socket = newSocket
                        writer = PrintWriter(newSocket.getOutputStream(), true)
                        BufferedReader(InputStreamReader(newSocket.getInputStream())).useLines { lines ->
                            lines.forEach { line ->
                                if (!running) return@forEach
                                val separator = line.indexOf(":::")
                                if (separator > 0) {
                                    onPayload(line.substring(0, separator), line.substring(separator + 3))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The host relay may start after the APK. Retry without
                    // surfacing a false disconnect to the workout UI.
                } finally {
                    socket = null
                    writer = null
                }
                if (running) Thread.sleep(250)
            }
        }
    }

    fun send(path: String, payload: String): Boolean {
        return try {
            Socket(WearSync.EMULATOR_BRIDGE_HOST, WearSync.EMULATOR_BRIDGE_PORT).use { newSocket ->
                PrintWriter(newSocket.getOutputStream(), true).println("$path:::$payload")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        readerThread?.interrupt()
        readerThread = null
    }
}
