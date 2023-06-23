package com.ddwapps.p1faker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val tag = "p1faker"

    private var nsdManager : NsdManager? = null

    data class SocketAddress(val host : InetAddress, val port : Int )

    private val candidateAddresses = mutableListOf<SocketAddress>()
    private var currentAddress : SocketAddress? = null

    private fun tryNextAddress() {
        if (candidateAddresses.isEmpty())
            return
        val address = candidateAddresses.first()
        currentAddress = address
        candidateAddresses.removeFirst()
        candidateAddresses.add(address) // re-insert at back of queue -> last resort retry in case of connection problems
        tryAddress(address)
    }

    private fun tryAddress(address : SocketAddress) {
        Log.i(tag, "Try to connect to $address")
        val wheel = findViewById<ProgressBar>(R.id.wheel)
        wheel.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val url = "http://${address.host.hostAddress}:${address.port}/"
            try {
                withTimeout(5000) {
                    val html = URL(url).readText()
                    runOnUiThread {
                        val webView = findViewById<WebView>(R.id.webView)
                        webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", "")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to get $url: ${e.message}")
                delay(500)
                runOnUiThread {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        tryNextAddress()
                }
                return@launch
            }
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Called when the resolve fails. Use the error code to debug.
            Log.v(tag, "Resolve failed: $errorCode")
        }

        override fun onServiceResolved(service: NsdServiceInfo) {
            Log.v(tag, "Resolve Succeeded. $service")
            runOnUiThread {
                val address = SocketAddress(service.host, service.port)
                if (candidateAddresses.contains(address))
                    return@runOnUiThread
                candidateAddresses.add(0, address) // add immediately at the front of the queue -> freshly discovered service is likely reachable
                if (currentAddress == null)
                    tryNextAddress()
            }
        }
    }

    // Instantiate a new DiscoveryListener
    private val discoveryListener = object : NsdManager.DiscoveryListener {

        // Called as soon as service discovery begins.
        override fun onDiscoveryStarted(regType: String) {
            Log.v(tag, "Service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            // A service was found! Do something with it.
            Log.v(tag, "Service discovery success $service")
            if (!service.serviceName.contains("p1faker"))
                return
            nsdManager?.resolveService(service, resolveListener)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            // When the network service is no longer available.
            // Internal bookkeeping code goes here.
            Log.v(tag, "service lost: $service")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(tag, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(tag, "Discovery failed: Error code: $errorCode")
            nsdManager?.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(tag, "Discovery failed: Error code: $errorCode")
            nsdManager?.stopServiceDiscovery(this)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            private var receivedError = false
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(tag, "Loading $url")
                receivedError = false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (receivedError)
                    return // we planned a retry now anyway
                Log.i(tag, "Finished connection to $url successfully")
                runOnUiThread {
                    val wheel = findViewById<ProgressBar>(R.id.wheel)
                    wheel.visibility = View.INVISIBLE
                }
            }

            override fun onReceivedError(view : WebView, request : WebResourceRequest, error : WebResourceError) {
                super.onReceivedError(view, request, error)
                if (receivedError)
                    return // we are already processing a receive error
                Log.e(tag, "Error with $currentAddress: ${error.description}")
                currentAddress = null
                receivedError = true
                runOnUiThread {
                    tryNextAddress()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        currentAddress?.apply { tryAddress(this) }
        nsdManager?.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun onPause() {
        nsdManager?.stopServiceDiscovery(discoveryListener)
        super.onPause()
    }
}