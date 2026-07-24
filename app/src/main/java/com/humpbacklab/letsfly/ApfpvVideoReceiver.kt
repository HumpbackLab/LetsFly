package com.humpbacklab.letsfly

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Receives APFPV UDP packets and decodes only the newest complete JPEG frame. */
internal class ApfpvVideoReceiver(private val onFrame: (Bitmap) -> Unit) {
    companion object {
        private const val LOG_TAG = "ApfpvVideoReceiver"
        private const val RECEIVE_BUFFER_SIZE = 256 * 1024
        private const val MAX_DATAGRAM_SIZE = 2048
        private const val MAX_QUEUED_PACKETS = 2048
        private const val SOCKET_TIMEOUT_MS = 250
        private const val PACKET_POLL_TIMEOUT_MS = 100L
        private const val CONTROL_PACKET_INTERVAL_NS = 250_000_000L
    }

    private class Session {
        val packetQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_PACKETS)
        val pendingJpeg = AtomicReference<ByteArray?>()
        val decodeSignal = Semaphore(0)
        val controlSession = ApfpvProtocol.ControlSession()
        lateinit var receiveThread: Thread
        lateinit var processThread: Thread
        lateinit var decodeThread: Thread
        @Volatile var socket: DatagramSocket? = null
        var controlSessionPaired = false
    }

    private val activeSession = AtomicReference<Session?>()

    fun start() {
        val session = Session()
        session.receiveThread = Thread({ receiveLoop(session) }, "apfpv-udp")
        session.processThread = Thread({ processLoop(session) }, "apfpv-packets")
        session.decodeThread = Thread({ decodeLoop(session) }, "apfpv-jpeg")
        if (!activeSession.compareAndSet(null, session)) {
            return
        }
        session.receiveThread.start()
        session.processThread.start()
        session.decodeThread.start()
    }

    fun stop() {
        val session = activeSession.getAndSet(null) ?: return
        session.socket?.close()
        session.socket = null
        session.receiveThread.interrupt()
        session.processThread.interrupt()
        session.decodeThread.interrupt()
        session.packetQueue.clear()
        session.pendingJpeg.set(null)
    }

    private fun isActive(session: Session) = activeSession.get() === session

    private fun receiveLoop(session: Session) {
        val receiveBuffer = ByteArray(MAX_DATAGRAM_SIZE)
        var nextControlPacketAt = 0L
        var udpSocket: DatagramSocket? = null

        try {
            val cameraAddress = InetAddress.getByName(ApfpvProtocol.CAMERA_HOST)
            val activeSocket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = RECEIVE_BUFFER_SIZE
                soTimeout = SOCKET_TIMEOUT_MS
                bind(InetSocketAddress(ApfpvProtocol.UDP_PORT))
            }
            udpSocket = activeSocket
            if (!isActive(session)) {
                return
            }
            session.socket = activeSocket

            while (isActive(session)) {
                val now = System.nanoTime()
                if (now >= nextControlPacketAt) {
                    for (controlData in session.controlSession.buildControlDatagrams()) {
                        activeSocket.send(
                            DatagramPacket(
                                controlData,
                                controlData.size,
                                cameraAddress,
                                ApfpvProtocol.UDP_PORT
                            )
                        )
                    }
                    nextControlPacketAt = now + CONTROL_PACKET_INTERVAL_NS
                }

                try {
                    val incoming = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    activeSocket.receive(incoming)
                    session.packetQueue.offer(incoming.data.copyOf(incoming.length))
                } catch (_: SocketTimeoutException) {
                    // The timeout also defines the 250 ms APFPV control/handshake cadence.
                }
            }
        } catch (exception: Exception) {
            if (isActive(session)) {
                Log.w(LOG_TAG, "APFPV receive loop stopped", exception)
            }
        } finally {
            udpSocket?.close()
            if (session.socket === udpSocket) {
                session.socket = null
            }
        }
    }

    private fun processLoop(session: Session) {
        val assembler = ApfpvFrameAssembler()
        val fecDecoder = ApfpvFecDecoder()

        while (isActive(session)) {
            val datagram = try {
                session.packetQueue.poll(PACKET_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } ?: continue

            if (!isActive(session)) {
                return
            }

            val transportPacket = ApfpvProtocol.parseTransportPacket(datagram, datagram.size)
                ?: continue
            for (decodedPacket in fecDecoder.push(transportPacket)) {
                if (session.controlSession.acceptAirConfig(decodedPacket.payload)) {
                    if (!session.controlSessionPaired) {
                        session.controlSessionPaired = true
                        Log.i(LOG_TAG, "APFPV control session paired with Air")
                    }
                    continue
                }
                val fragment = ApfpvProtocol.parseVideoPacket(
                    decodedPacket.payload,
                    decodedPacket.payload.size
                ) ?: continue
                val jpeg = assembler.push(fragment) ?: continue
                session.pendingJpeg.set(jpeg)
                if (session.decodeSignal.availablePermits() == 0) {
                    session.decodeSignal.release()
                }
            }
        }
    }

    private fun decodeLoop(session: Session) {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
        }
        val postProcessor = ApfpvJpegPostProcessor()
        while (isActive(session)) {
            try {
                session.decodeSignal.acquire()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!isActive(session)) {
                return
            }
            val jpeg = session.pendingJpeg.getAndSet(null) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: continue
            val processedBitmap = postProcessor.process(bitmap, jpeg)
            if (isActive(session)) {
                onFrame(processedBitmap)
            } else {
                processedBitmap.recycle()
            }
        }
    }
}
