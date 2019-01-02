package com.colorfy.tcplib

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory


/**
 * Create socket, send & receive messages, close socket.
 * </br>
 * Default timeout 10 * 1000ms
 */
class SocketHelper(val context: Context, val socketAddress: String, val socketPort: Int) {

    private val TAG = "SocketHelper"

    //config
    private val socketTimeout = 10 * 1000
    private val receiveBufferSize = 1024 * 32
    private val sendBufferSize = 1024 * 32

    var tcpLogger: TcpLogger? = null
    var socketMessageListener: SocketMessageListener? = null

    private val handler: Handler
    private var socketThread: SocketThread? = null

    private var currentCommand: Any? = null

    @Volatile
    private var isStarted: Boolean = false

    private val handlerCallback = Handler.Callback { msg ->
        tcpLogger?.log("[handlerCallback] handleMessage, msg: $msg")

        tcpLogger?.log("[handlerCallback] !(msg.obj instanceof String): " + (msg.obj !is String))


        socketMessageListener?.onMessage(msg.obj)
        false
    }

    init {
        this.handler = Handler(handlerCallback)
    }


    @Synchronized
    fun start() {
        tcpLogger?.log("[start] isStarted: $isStarted")

        if (isStarted)
            return

        isStarted = true

        tcpLogger?.log("Starting socket client")

        socketThread = SocketThread()
        socketThread?.start()
    }

    @Synchronized
    fun stop() {
        if (!isStarted)
            return

        tcpLogger?.log("Stopping socket client")

        isStarted = false
        socketThread?.shutdown()
        socketThread = null
    }

    @Synchronized
    fun restart() {
        tcpLogger?.log("Restarting socket client")

        stop()
        start()
    }

    @Synchronized
    fun reset() {
        tcpLogger?.log("Resetting socket client queue")

//        queue.clear()
    }

    @Synchronized
    fun send(cmd: Any) {
        tcpLogger?.log("[send] cmd: $cmd")

        this.currentCommand = cmd

        if (isStarted) {
            socketThread?.unlock()
        } else {
            start()
        }
    }


    // ------------------------------------------------------------------------------------------------------------------------------
    // SocketThread
    // ------------------------------------------------------------------------------------------------------------------------------
    private inner class SocketThread : Thread("socket-client") {

        private var socket: Socket? = null
        private var isRunning = true
        private var retries = 0

        val lock = java.lang.Object()

        fun unlock() {
            synchronized(lock) {
                lock.notify()
            }
        }

        override fun run() {
            tcpLogger?.log("[run] Socket thread started, isRunning: $isRunning")

            while (isRunning) {

                synchronized(lock) {
                    try {

                        // no effect if already connected
                        connectToSocket()

                        if (currentCommand != null) {
                            writeCommand(currentCommand!!)
                            readResponse()
                            currentCommand = null
                            retries = 0
                        } else {
                            tcpLogger?.log("[run] no cmd to run, waiting .notify (a new message will trigger .notify)")
                            lock.wait()
                        }

                    } catch (e: IOException) {
                        tcpLogger?.log("[run] Exception in socket thread, retrying in 1000")

                        closeQuietly(socket)
                        socket = null

                        socketMessageListener?.onError(e, true)
                        try {
                            Thread.sleep(1000)
                        } catch (e: Exception) {
                        }

                        e.printStackTrace()
                    } catch (e: InterruptedException) {
                        tcpLogger?.log("[run] Socket thread was interrupted")

                        socketMessageListener?.onError(e, false)

                        e.printStackTrace()
                    } catch (e: Exception) {
                        tcpLogger?.log("[run] exception")

                        socketMessageListener?.onError(e, false)

                        e.printStackTrace()
                    }
                }
            }

            tcpLogger?.log("[run] Socket thread stopped")

        }

        @Synchronized
        fun shutdown() {
            isRunning = false
            interrupt()
        }


        @Throws(IOException::class)
        private fun connectToSocket() {

            tcpLogger?.log("[connectToSocket]")

            tcpLogger?.log("[connectToSocket] socket null: ${socket == null}")

            if (socket == null || socket!!.isClosed) {
                tcpLogger?.log("[connectToSocket] creating socket")

                socket = createSocket()
                tcpLogger?.log("[connectToSocket] socket created")

                socket!!.sendBufferSize = sendBufferSize
                socket!!.receiveBufferSize = receiveBufferSize
                socket!!.soTimeout = socketTimeout

                tcpLogger?.log("[connectToSocket] socket connecting...")

                val addr = InetAddress.getByName(socketAddress)
                val socketAddress = InetSocketAddress(addr, socketPort)

                socket!!.connect(socketAddress, socketTimeout)
                tcpLogger?.log("[connectToSocket] socket connecting done")
            } else {
                tcpLogger?.log("[connectToSocket] socket already init")
            }
        }

        @Throws(IOException::class)
        private fun writeCommand(command: Any) {
            tcpLogger?.log("[writeCommand] command: $command")

            val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket!!.getOutputStream(), "UTF-8")))

            writer.print(command)
            writer.flush()

            tcpLogger?.log("[writeCommand] Writing message complete")

        }

        @Throws(IOException::class)
        private fun readResponse() {
            tcpLogger?.log("[readResponse]")

            val reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), "UTF-8"))
            val response = reader.readLine()
            tcpLogger?.log("[readResponse] response: $response")

            val message = handler.obtainMessage()
            message.obj = response

            handler.sendMessage(message)
        }

        @Throws(IOException::class)
        private fun createSocket(): Socket {
            tcpLogger?.log("[createSocket] SDK_IN: " + Build.VERSION.SDK_INT)

            if (Build.VERSION.SDK_INT < 21)
                return SocketFactory.getDefault().createSocket()

            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            for (network in manager.allNetworks)
                if (manager.getNetworkInfo(network) != null && manager.getNetworkInfo(network).type == ConnectivityManager.TYPE_WIFI)
                    return network.socketFactory.createSocket()

            throw IOException("Could not create socket")
        }

        private fun closeQuietly(closeable: Closeable?) {
            if (closeable != null) {
                try {
                    closeable.close()
                } catch (e: IOException) {
                    // Ignore
                }

            }
        }
    }


    // ------------------------------------------------------------------------------------------------------------------------------
    // SocketMessageListener
    // ------------------------------------------------------------------------------------------------------------------------------
    interface SocketMessageListener {
        fun onMessage(message: Any?)

        fun onError(error: Throwable, cannotConnect: Boolean)
    }
}