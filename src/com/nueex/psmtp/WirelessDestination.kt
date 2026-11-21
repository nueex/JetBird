/* Copyright (C) 2026 nueex. SPDX-License-Identifier: GPL-3.0-or-later */
package com.nueex.psmtp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.Closeable
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal class WirelessPair internal constructor(val address: InetAddress, private val pin: ByteArray, private val secret: ByteArray) {
    fun sealedCredentials() = LocalSecrets.seal(KEY_ALIAS, address.hostAddress.orEmpty(), pin + secret)

    fun probe() = Socket().use { socket -> open(socket, false).close() }

    fun storage(): ConsoleStorage? = Socket().use { socket ->
        open(socket, true).use { tls ->
            tls.soTimeout = 4_000
            tls.outputStream.write(storageRequest)
            val bytes = ByteArray(24)
            DataInputStream(tls.inputStream).readFully(bytes)
            decodeStorage(ByteBuffer.wrap(bytes))
        }
    }

    fun open(socket: Socket, transfer: Boolean): SSLSocket {
        val tls = openTls(socket, address, pin, transfer)
        try {
            val out = DataOutputStream(tls.outputStream)
            out.writeInt(0x50534c31) // PSL1
            out.write(secret)
            out.writeByte(if (transfer) 1 else 0)
            out.flush()
            checkReply(DataInputStream(tls.inputStream))
            return tls
        } catch (error: Exception) {
            runCatching { tls.close() }
            throw error
        }
    }

    companion object {
        private const val KEY_ALIAS = "psmtp-wireless-pair"
        @Volatile var active: WirelessPair? = null

        fun restore(address: InetAddress, sealed: String): WirelessPair {
            val credential = LocalSecrets.open(KEY_ALIAS, address.hostAddress.orEmpty(), sealed)
            require(credential.size == 32)
            return WirelessPair(address, credential.copyOfRange(0, 16), credential.copyOfRange(16, 32))
        }
        fun parse(address: InetAddress, entry: String): WirelessPair? {
            val code = entry.filterNot { it.isWhitespace() || it == '-' }
            if (!code.matches(Regex("[a-fA-F0-9]{64}"))) return null
            val bytes = code.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return WirelessPair(address, bytes.copyOfRange(0, 16), bytes.copyOfRange(16, 32))
        }

        fun fromQr(value: String, addressParser: (String) -> InetAddress?): WirelessPair? {
            if (value.length > 96) return null
            val parts = value.split(':')
            if (parts.size != 3 || parts[0] != "PSMTP1") return null
            return addressParser(parts[1])?.let { parse(it, parts[2]) }
        }
    }
}

private fun fingerprint(cert: X509Certificate) =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).copyOf(16)

private fun openTls(socket: Socket, address: InetAddress, expected: ByteArray?, transfer: Boolean = false): SSLSocket {
    socket.soTimeout = 22_000
    socket.tcpNoDelay = true
    socket.sendBufferSize = 512 * 1024
    socket.connect(InetSocketAddress(address, 41984), 5_000)
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { throw CertificateException() }
        override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
            val cert = chain.firstOrNull() ?: throw CertificateException("Missing console certificate")
            if (expected != null && !MessageDigest.isEqual(expected, fingerprint(cert)))
                throw CertificateException("PS4 certificate changed")
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    val tls = context.socketFactory.createSocket(socket, address.hostAddress, 41984, true) as SSLSocket
    try {
        tls.enabledProtocols = arrayOf("TLSv1.2")
        if (transfer) tls.sslParameters = tls.sslParameters.apply { applicationProtocols = arrayOf(MULTIPART_ALPN) }
        tls.startHandshake()
        return tls
    } catch (error: Exception) {
        runCatching { tls.close() }
        throw error
    }
}

private object PairingCrypto {
    init { System.loadLibrary("psmtp_pair") }

    external fun share(code: Int, random: ByteArray): ByteArray?
    external fun proofs(code: Int, random: ByteArray, phone: ByteArray, console: ByteArray, fingerprint: ByteArray): ByteArray?
}

internal class ManualPair private constructor(
    private val address: InetAddress, private val tls: SSLSocket, private val pin: ByteArray,
    private val input: DataInputStream, private val output: DataOutputStream, private val console: ByteArray
) : Closeable {
    fun matches(entered: String) = entered.length == 6 && entered.all { it in '0'..'9' }

    fun finish(entered: String): WirelessPair {
        if (!matches(entered)) throw TransferError("Enter the six-digit PS4 code.")
        val code = entered.toInt()
        val scalar = ByteArray(32)
        val random = SecureRandom()
        var phone: ByteArray?
        do {
            random.nextBytes(scalar)
            phone = PairingCrypto.share(code, scalar)
        } while (phone == null)
        val proofs = try {
            PairingCrypto.proofs(code, scalar, phone, console, pin)
                ?: throw TransferError("The PS4 returned an invalid pairing message.")
        } finally {
            scalar.fill(0)
        }
        try {
            output.write(phone)
            output.write(proofs, 0, 32)
            output.flush()
            checkReply(input)
            val reply = ByteArray(32).also { input.readFully(it) }
            if (!MessageDigest.isEqual(proofs.copyOfRange(32, 64), reply)) {
                throw TransferError("The pairing code could not authenticate this PS4.")
            }
            val secret = ByteArray(16).also { input.readFully(it) }
            return WirelessPair(address, pin, secret)
        } finally {
            proofs.fill(0)
        }
    }

    override fun close() { runCatching { tls.close() } }

    companion object {
        fun open(address: InetAddress): ManualPair {
            val socket = Socket()
            try {
                val tls = openTls(socket, address, null)
                val pin = fingerprint(tls.session.peerCertificates.first() as X509Certificate)
                val input = DataInputStream(tls.inputStream)
                val output = DataOutputStream(tls.outputStream)
                output.writeInt(0x50534d33) // PSM3
                output.flush()
                val console = ByteArray(65).also { input.readFully(it) }
                return ManualPair(address, tls, pin, input, output, console)
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }
    }
}

internal class WirelessDestination(private val pair: WirelessPair, private val install: Boolean) : Destination {
    private val socket = Socket()
    private var secured: SSLSocket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var size = -1L
    private var pending = 0
    private val frame = ByteArray(128 * 1024 + 4)
    private val header = ByteBuffer.wrap(frame)

    private fun open() {
        val connection = try {
            pair.open(socket, true)
        } catch (_: java.net.SocketTimeoutException) {
            throw TransferError("The PS4 did not respond over Wi-Fi. Open Wireless Transfer on the console and retry.")
        } catch (_: java.net.ConnectException) {
            throw TransferError("The PS4 receiver is not accepting connections. Enable Wireless Transfer on the console.")
        } catch (_: javax.net.ssl.SSLException) {
            throw TransferError("Could not verify the paired PS4. Open Wireless Transfer on the console and pair again.")
        } catch (_: IOException) {
            throw TransferError("The wireless connection closed before the PS4 accepted the request. Pair again if the console ended the session.")
        }
        secured = connection
        input = DataInputStream(connection.inputStream)
        output = DataOutputStream(BufferedOutputStream(connection.outputStream, 512 * 1024))
    }

    override fun handoff(name: String, size: Long, request: ByteArray) {
        open()
        val filename = name.toByteArray()
        val stream = output
        stream.write(transferHeader(if (install) 0x50534c32 else 0x50534c31, filename, size))
        stream.flush()
        requireHandoffReady(readReply(input))
        stream.write(filename)
        stream.write(ByteBuffer.allocate(4).putInt(request.size).array())
        stream.write(request)
        stream.flush()
        secured?.soTimeout = 60_000
        finalReply { checkReply(input) }
    }

    override fun begin(name: String, total: Long, resume: ResumeOffer?): Long {
        open()
        val encoded = name.toByteArray(Charsets.UTF_8)
        val magic = (if (install) 0x50534931 else 0x50534431) + if (resume != null) 1 else 0
        output.write(transferHeader(magic, encoded, total))
        output.write(encoded)
        if (resume != null) {
            output.write(resume.key)
            output.writeLong(resume.maximum)
        }
        output.flush()
        size = total
        checkReply(input)
        return if (resume != null) input.readLong() else 0
    }

    override fun write(bytes: ByteArray, count: Int) {
        require(count in 1..frame.size - 4)
        header.putInt(0, count)
        bytes.copyInto(frame, 4, 0, count)
        output.write(frame, 0, count + 4)
        if (++pending >= 8) {
            output.flush()
            acceptChunk()
        }
    }

    override fun showParts(progress: MultipartProgress) {
        if (secured?.applicationProtocol != MULTIPART_ALPN) return
        output.write(progress.frame())
        output.flush()
        while (pending > 0) acceptChunk()
        checkReply(input)
    }

    private fun acceptChunk() {
        checkReply(input)
        pending--
    }

    override fun finish(digest: ByteArray, stopping: () -> Boolean, progress: (Long, Long) -> Unit): Boolean {
        output.flush()
        while (pending > 0) acceptChunk()
        if (stopping()) {
            stop()
            return false
        }
        output.writeInt(0)
        output.write(digest)
        output.flush()
        if (install) progress(size, size)
        var stopSent = false
        var status = finalReply { checkReply(input, install) }
        while (status == INSTALL_WAIT) {
            if (stopping() && !stopSent) {
                output.writeInt(STOP_FRAME)
                output.flush()
                stopSent = true
            }
            progress(size, size)
            status = finalReply { checkReply(input, waiting = true, stopping = stopSent) }
        }
        return status != STOP_CONFIRMED
    }

    override fun pause(digest: ByteArray) {
        output.flush()
        while (pending > 0) acceptChunk()
        output.writeInt(-1)
        output.write(digest)
        output.flush()
        checkReply(input)
    }

    override fun stop() {
        output.writeInt(STOP_FRAME)
        output.flush()
        while (pending > 0) acceptChunk()
        if (checkReply(input, stopping = true) != STOP_CONFIRMED) throw TransferError("The PS4 did not confirm the stop.")
    }

    override fun discard(name: String, size: Long, key: ByteArray) {
        open()
        val encoded = name.toByteArray(Charsets.UTF_8)
        output.write(discardHeader(install, encoded, size))
        output.write(encoded)
        output.write(key)
        output.flush()
        if (checkReply(input, stopping = true) != STOP_CONFIRMED) throw TransferError("The PS4 did not confirm the stop.")
    }

    override fun abort() { runCatching { socket.close() } }
    override fun close() {
        runCatching { secured?.close() }
        runCatching { socket.close() }
    }
}

private fun readReply(input: DataInputStream): Int {
    if (input.readInt() != 0x50535231) throw TransferError("Unsupported PS4 wireless receiver.")
    return input.readInt()
}

private fun checkReply(input: DataInputStream, waiting: Boolean = false, stopping: Boolean = false): Int {
    return when (val status = readReply(input)) {
        0 -> status
        INSTALL_WAIT -> if (waiting) status else throw TransferError("Unexpected PS4 reply.")
        STOP_CONFIRMED -> if (stopping) status else throw TransferError("Unexpected PS4 reply.")
        else -> throw TransferError(ps4ReplyMessage(status))
    }
}
