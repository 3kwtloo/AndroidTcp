package com.colorfy.tcplib

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.io.PrintWriter
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

    companion object {
        private val TAG = "SocketHelper"
        val LIBRARY_VERSION = BuildConfig.VERSION_NAME
    }

    //config
    private val socketTimeout = 10 * 1000
    private val receiveBufferSize = 1024 * 32
    private val sendBufferSize = 1024 * 32

    var tcpLogger: TcpLogger? = null
    var socketMessageListener: SocketMessageListener? = null

    private var socketThread: SocketThread? = null

    private var currentCommand: Any? = null

    @Volatile
    private var isStarted: Boolean = false

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
    fun send(cmd: Any) {
        tcpLogger?.log("[send] cmd: $cmd, isStarted: $isStarted")

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
                        val alreadyConnected = connectToSocket()
                        if (!alreadyConnected) {
                            tcpLogger?.log("[run] onConnected-------------")

                            socketMessageListener?.onConnected(true)
                        }

                        if (currentCommand != null) {

                            val cmd = currentCommand
                            currentCommand = null

                            writeCommand(cmd!!)
                            readResponse()

//                            var oos: ObjectOutputStream?
//                            var ois: ObjectInputStream?
//
//                            oos = ObjectOutputStream(socket!!.getOutputStream())
//                            tcpLogger?.log("Sending request to Socket Server")
//                            oos.writeObject(currentCommand)
//                            tcpLogger?.log("Sending request to Socket Server DONE")
//
//                            //read the server response message
//                            ois = ObjectInputStream(socket!!.getInputStream())
//                            val message = ois.readObject() as String
//                            tcpLogger?.log("Message: $message")
//                            //close resources
//                            ois.close()
//                            oos.close()
                        } else {
                            tcpLogger?.log("[run] no cmd to run, waiting .notify (a new message will trigger .notify)")

                            try {
                                lock.wait()
                            } catch (e: Exception) {
                            }
                        }

                    } catch (e: IOException) {
                        tcpLogger?.log("[run] Exception in socket thread, IOException")

                        closeQuietly(socket)
                        socket = null


                        // if cannot connect, stop
                        if (e.message?.contains(socketAddress) == true) {
                            socketMessageListener?.onConnected(false)

                            this@SocketHelper.stop()
                        } else {
                            socketMessageListener?.onError(e)
                        }

                        e.printStackTrace()
                    } catch (e: InterruptedException) {
                        tcpLogger?.log("[run] Socket thread was interrupted")

                        socketMessageListener?.onError(e)

                        e.printStackTrace()
                    } catch (e: Exception) {
                        tcpLogger?.log("[run] exception")

                        socketMessageListener?.onError(e)

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


        /**
         * returns - true: not connected && connection successful - false: already connected
         */
        @Throws(IOException::class)
        private fun connectToSocket(): Boolean {

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

                return false
            } else {
                tcpLogger?.log("[connectToSocket] socket already init")

                return true
            }
        }

        @Throws(IOException::class)
        private fun writeCommand(command: Any) {
            tcpLogger?.log("[writeCommand] command: $command")

            val writer = PrintWriter(socket!!.getOutputStream(), true)

            writer.println(command)
            writer.flush()


            tcpLogger?.log("[writeCommand] Writing message complete")

            socketMessageListener?.onAck(true)
        }


        fun convertStreamToString(`is`: java.io.InputStream): String {
            val s = java.util.Scanner(`is`).useDelimiter("\\A")
            return if (s.hasNext()) s.next() else ""
        }


        @Throws(IOException::class)
        private fun readResponse() {
            tcpLogger?.log("[readResponse]")


//            val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
//
//            var response = ""
//            while (true) {
//                val char = reader.read()
//                tcpLogger?.log("[readResponse] char: ${char}")
//
//                if (char == -1)
//                    break
//
//                response += char.toChar().toString()
//            }

            val response = convertStreamToString(socket!!.getInputStream())
            tcpLogger?.log("[readResponse] response: $response")

            Thread(Runnable{
                tcpLogger?.log("[readResponse] socketMessageListener?.onMessagee")

                socketMessageListener?.onMessage(response)
            }).start()
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

            throw IOException("Could not create socket for $socketAddress")
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

        fun onConnected(connected: Boolean)

        fun onAck(sent: Boolean)

        fun onError(error: Throwable)
    }
}