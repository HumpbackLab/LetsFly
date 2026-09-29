package com.humpbacklab.letsfly

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Keeps a settings-only APFPV control session while MainActivity's video socket is paused. */
internal class ApfpvChannelClient(
    private val context: Context,
    private val onWifiReady: () -> Unit,
    private val onNetworkUnavailable: () -> Unit,
    private val onPacketVersion: (Int) -> Unit,
    private val onInvalidConfig: () -> Unit,
    private val onChannel: (Int) -> Unit,
    private val onCommandSent: (Int) -> Unit,
    private val onError: (Boolean) -> Unit
) {
    companion object {
        private const val LOG_TAG = "ApfpvChannelClient"
        private const val CONTROL_INTERVAL_NS = 250_000_000L
    }

    private val active = AtomicBoolean(false)
    private val requestedChannel = AtomicInteger(0)
    @Volatile private var controlSession = ApfpvProtocol.ControlSession()
    @Volatile private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!active.compareAndSet(false, true)) return
        worker = Thread(::runLoop, "apfpv-channel").also { it.start() }
    }

    fun stop() {
        active.set(false)
        socket?.close()
        worker?.interrupt()
    }

    fun requestChannel(channel: Int): Boolean {
        if (controlSession.currentWifiChannel() == null) return false
        controlSession.requestWifiChannel(channel)
        requestedChannel.set(channel)
        return true
    }

    private fun runLoop() {
        val cameraAddress = InetAddress.getByName(ApfpvProtocol.CAMERA_HOST)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        var networkAvailable = false
        var networkStateReported = false
        try {
            while (active.get()) {
                val cameraNetwork = findCameraNetwork(connectivity, cameraAddress)
                if (cameraNetwork == null) {
                    if (!networkStateReported || networkAvailable) {
                        networkAvailable = false
                        networkStateReported = true
                        onNetworkUnavailable()
                    }
                    Thread.sleep(500)
                    continue
                }
                var udpSocket: DatagramSocket? = null
                try {
                    // A channel change can replace Android's Wi-Fi Network. The old
                    // network-bound socket cannot receive from the replacement AP.
                    val connectedSocket = DatagramSocket(null)
                    udpSocket = connectedSocket
                    connectedSocket.apply {
                        reuseAddress = true
                        soTimeout = 250
                        bind(InetSocketAddress(ApfpvProtocol.UDP_PORT))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            cameraNetwork.bindSocket(this)
                        }
                    }
                    socket = connectedSocket
                    controlSession = ApfpvProtocol.ControlSession()
                    requestedChannel.set(0)
                    networkAvailable = true
                    networkStateReported = true
                    onWifiReady()
                    val receiveBuffer = ByteArray(2048)
                    var nextControlAt = 0L
                    var lastReportedChannel = 0
                    var lastSentChannel = 0
                    var lastReportedVersion = 0
                    var invalidConfigReported = false

                    while (active.get() &&
                        findCameraNetwork(connectivity, cameraAddress) == cameraNetwork
                    ) {
                        val now = System.nanoTime()
                        if (now >= nextControlAt) {
                            val datagrams = controlSession.buildControlDatagrams()
                            for (data in datagrams) {
                                connectedSocket.send(DatagramPacket(
                                    data, data.size, cameraAddress, ApfpvProtocol.UDP_PORT
                                ))
                            }
                            val requested = requestedChannel.get()
                            if (requested != 0 && requested != lastSentChannel &&
                                datagrams.size == 2
                            ) {
                                lastSentChannel = requested
                                onCommandSent(requested)
                            }
                            nextControlAt = now + CONTROL_INTERVAL_NS
                        }

                        try {
                            val incoming = DatagramPacket(receiveBuffer, receiveBuffer.size)
                            connectedSocket.receive(incoming)
                            val packet = ApfpvProtocol.parseTransportPacket(
                                incoming.data, incoming.length
                            ) ?: continue
                            if (packet.version != lastReportedVersion) {
                                lastReportedVersion = packet.version
                                onPacketVersion(packet.version)
                            }
                            controlSession.observePacketVersion(packet.version)
                            // Air repeats Config on primary FEC packets while video runs.
                            if (packet.packetIndex >= 6 ||
                                packet.payload.firstOrNull() != 3.toByte()
                            ) continue
                            val accepted = controlSession.acceptAirConfig(packet.payload)
                            val channel = if (accepted) controlSession.currentWifiChannel() else null
                            if (channel == null) {
                                if (!invalidConfigReported) {
                                    invalidConfigReported = true
                                    onInvalidConfig()
                                }
                                continue
                            }
                            if (channel != lastReportedChannel) {
                                lastReportedChannel = channel
                                onChannel(channel)
                            }
                        } catch (_: SocketTimeoutException) {
                            // Check the Wi-Fi Network and continue the control handshake.
                        }
                    }
                } catch (exception: Exception) {
                    if (active.get()) {
                        Log.w(LOG_TAG, "APFPV network session ended", exception)
                    }
                } finally {
                    udpSocket?.close()
                    if (socket === udpSocket) socket = null
                    if (active.get() && networkAvailable) {
                        networkAvailable = false
                        onNetworkUnavailable()
                    }
                }
                if (active.get()) Thread.sleep(500)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (exception: Exception) {
            if (active.get()) {
                Log.w(LOG_TAG, "APFPV channel control stopped", exception)
                onError(networkAvailable)
            }
        }
    }

    private fun findCameraNetwork(
        connectivity: ConnectivityManager, cameraAddress: InetAddress
    ): Network? = connectivity.allNetworks.firstOrNull { network ->
        // API 21 cannot bind a UDP socket to a Network, so Wi-Fi must be the default route.
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ||
            connectivity.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI) &&
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            connectivity.getLinkProperties(network)?.linkAddresses?.any { link ->
                isOnSameSubnet(link.address, link.prefixLength, cameraAddress)
            } == true
    }

    private fun isOnSameSubnet(
        local: InetAddress, prefixLength: Int, target: InetAddress
    ): Boolean {
        val localBytes = local.address
        val targetBytes = target.address
        if (localBytes.size != targetBytes.size || prefixLength <= 0) return false
        return localBytes.indices.all { index ->
            val bits = (prefixLength - index * 8).coerceIn(0, 8)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            (localBytes[index].toInt() and mask) == (targetBytes[index].toInt() and mask)
        }
    }
}
