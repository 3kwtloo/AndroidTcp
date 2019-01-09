package com.colorfy.tcp

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Base64
import android.util.Log
import com.colorfy.tcplib.SocketHelper
import com.colorfy.tcplib.TcpLogger
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import java.net.SocketTimeoutException

class MainActivity : AppCompatActivity(), TcpLogger {

    private val SOCKET_ADDRESS = "192.168.1.1"
    private val SOCKET_PORT = 23

    lateinit var socketHelper: SocketHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        clear.setOnClickListener { logView.text = "" }


        socketHelper = SocketHelper(this@MainActivity, SOCKET_ADDRESS, SOCKET_PORT).apply {
            tcpLogger = this@MainActivity
            socketMessageListener = this@MainActivity.socketMessageListener
        }

        start.setOnClickListener {
            socketHelper.start()
        }

        send.setOnClickListener {
            val imgString = "eyJwdWJsaWNfa2V5X3JlcXVlc3QiIDogeyJwdWJsaWNfa2V5IjogIkJMR0h1N3VQK2lENnAveGpiMjRnU1MwREFwUndUdmVuRVlaYThLOS9pYmZDZEtMNWE4c0ZCRXJlbGQ5Y0lVTEs2a2NaWGRzSjJDQ0xzNW9lcXdTOGRCST0ifX0="
            val msg = Base64.decode(imgString, Base64.NO_WRAP)

            socketHelper.send(msg)
        }

        stop.setOnClickListener {
            socketHelper.stop()
        }
    }

    override fun log(message: String) {
        runOnUiThread {
            Log.e("MainActivity", message)

            logView.append("\n\n$message")
        }
    }

    val socketMessageListener = object : SocketHelper.SocketMessageListener {
        override fun onConnected(connected: Boolean) {
            Log.e("MainActivity", "onConnected!!!!!!!!!!!!!!!, connected: $connected")
        }

        override fun onError(error: Throwable) {
            Log.e("MainActivity", "error!!!!!!!!!!!!!!!, message: ${error.message}")

            when (error) {
                is IOException -> {
                    Log.e("MainActivity", "CANNOT CONNECT ----------------------------------------------------------------")
                }

                is SocketTimeoutException -> {
                    if (error.message?.contains(SOCKET_ADDRESS) == true) {
                        Log.e("MainActivity", "CANNOT CONNECT ----------------------------------------------------------------")
                    } else {

                    }
                }
            }
        }

        override fun onMessage(message: Any?) {
            Log.e("MainActivity", "message!!!!!!!!!!!!!!!")
            Log.e("MainActivity", "message: " + (message?.toString() ?: "<empty>"))
        }

        override fun onAck(sent: Boolean) {
            Log.e("MainActivity", "onAck!!!!!!!!!!!!!!!")
            Log.e("MainActivity", "onAck, sent: $sent")
        }
    }
}
