package app.teron.superapp.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import app.teron.superapp.core.MeshPeer
import app.teron.superapp.core.MeshRelayPacket
import app.teron.superapp.core.TeronCrypto
import app.teron.superapp.core.TeronDatabase
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.pow

class TeronMeshEngine(
    private val context: Context,
    private val db: TeronDatabase
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00007e20-0000-1000-8000-00805f9b34fb")
        val CHAR_PACKET_UUID: UUID = UUID.fromString("00007e21-0000-1000-8000-00805f9b34fb")
        val CHAR_IDENTITY_UUID: UUID = UUID.fromString("00007e22-0000-1000-8000-00805f9b34fb")

        const val UDP_DISCOVERY_PORT = 47920
        const val TCP_FRAME_PORT = 47921
        const val MULTICAST_GROUP_IP = "239.255.79.20"
        const val NSD_SERVICE_TYPE = "_teron._tcp."
    }

    var onOutboundPacketHook: ((MeshRelayPacket) -> Unit)? = null

    interface Listener {
        fun onMeshStateChanged()
        fun onPacketDelivered(summary: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioPool = Executors.newCachedThreadPool()
    private val listeners = Collections.synchronizedSet( LinkedHashSet<Listener>() )

    val livePeers = ConcurrentHashMap<String, MeshPeer>()
    private val seenPackets = Collections.synchronizedSet(LinkedHashSet<String>())
    private val bleLastSyncByAddr = ConcurrentHashMap<String, Long>()
    private val bleWriteBuffers = ConcurrentHashMap<String, StringBuilder>()

    val telemetryLogs = Collections.synchronizedList(mutableListOf<String>())

    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var isBleAdvertising: Boolean = false
        private set
    @Volatile var isBleScanning: Boolean = false
        private set
    @Volatile var isWifiMeshActive: Boolean = false
        private set
    @Volatile var relayedCountSession: Int = 0
        private set

    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var gattServer: BluetoothGattServer? = null
    private var nsdRegListener: NsdManager.RegistrationListener? = null
    private var nsdDiscListener: NsdManager.DiscoveryListener? = null

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun logEvent(msg: String) {
        val stamp = app.teron.superapp.core.Theme.formatTime(System.currentTimeMillis())
        synchronized(telemetryLogs) {
            telemetryLogs.add(0, "[$stamp] $msg")
            if (telemetryLogs.size > 80) telemetryLogs.removeAt(telemetryLogs.lastIndex)
        }
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            val snap = listeners.toList()
            for (l in snap) l.onMeshStateChanged()
        }
    }

    private fun notifyDelivered(summary: String) {
        mainHandler.post {
            try {
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    vib?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(45)
                }
            } catch (_: Exception) {}
            val snap = listeners.toList()
            for (l in snap) {
                l.onPacketDelivered(summary)
                l.onMeshStateChanged()
            }
        }
    }

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun start() {
        if (isRunning) {
            refreshBleTransport()
            return
        }
        isRunning = true
        logEvent("Ядро AirMesh запущено (протокол ECDH + AES-256-GCM)")
        startWifiLanTransport()
        refreshBleTransport()
        schedulePeriodicMaintenance()
    }

    fun stop() {
        isRunning = false
        stopBleTransport()
        stopWifiLanTransport()
        logEvent("Радиомодули AirMesh переведены в спящий режим")
    }

    private fun schedulePeriodicMaintenance() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                // Prune stale peers older than 45s (except simulated lab nodes within 90s)
                val now = System.currentTimeMillis()
                val removed = livePeers.entries.removeIf { (_, p) ->
                    now - p.lastSeenMs > 55_000L
                }
                if (removed) notifyStateChanged()
                mainHandler.postDelayed(this, 6_000L)
            }
        }, 6_000L)
    }

    // =========================================================================
    // 1. REAL WI-FI / HOTSPOT UDP DISCOVERY + TCP ENCRYPTED MESH TRANSPORT
    // =========================================================================
    private fun startWifiLanTransport() {
        ioPool.execute {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wm?.createMulticastLock("TeronAirMeshLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {}

            // Start TCP Frame Receiver
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(TCP_FRAME_PORT))
                tcpServer = srv
                isWifiMeshActive = true
                logEvent("Wi-Fi P2P/LAN сокет активен на порту $TCP_FRAME_PORT")

                ioPool.execute {
                    while (isRunning && !srv.isClosed) {
                        try {
                            val client = srv.accept()
                            ioPool.execute { handleIncomingTcpPeer(client) }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                logEvent("Wi-Fi TCP порт занят или недоступен: ${e.message}")
            }

            // Start UDP + Multicast Beacon Listener & Broadcaster
            try {
                val ds = MulticastSocket(UDP_DISCOVERY_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                    try {
                        @Suppress("DEPRECATION")
                        joinGroup(InetAddress.getByName(MULTICAST_GROUP_IP))
                    } catch (_: Exception) {}
                }
                udpSocket = ds

                ioPool.execute {
                    val buf = ByteArray(4096)
                    while (isRunning && !ds.isClosed) {
                        try {
                            val dp = DatagramPacket(buf, buf.size)
                            ds.receive(dp)
                            val txt = String(dp.data, dp.offset, dp.length, Charsets.UTF_8)
                            handleUdpBeacon(txt, dp.address.hostAddress ?: "")
                        } catch (_: Exception) {
                            if (!isRunning) break
                        }
                    }
                }

                ioPool.execute {
                    while (isRunning && !ds.isClosed) {
                        try {
                            broadcastUdpPresence(ds)
                            Thread.sleep(4_000L)
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                logEvent("UDP маяк AirMesh: ${e.message}")
            }

            startNsdDiscoveryAndRegistration()
        }
    }

    private fun startNsdDiscoveryAndRegistration() {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val me = try { db.getProfile() } catch (_: Throwable) { return }

        stopNsdQuietly(nsd)

        try {
            val svcInfo = NsdServiceInfo().apply {
                serviceName = "Teron-${me.nodeId}"
                serviceType = NSD_SERVICE_TYPE
                port = TCP_FRAME_PORT
            }
            val reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    logEvent("mDNS сервис зарегистрирован (${info.serviceName})")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            nsdRegListener = reg
            nsd.registerService(svcInfo, NsdManager.PROTOCOL_DNS_SD, reg)
        } catch (_: Throwable) {}

        try {
            val disc = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    val sName = service.serviceName ?: return
                    if (!sName.startsWith("Teron-") || sName.contains(me.nodeId)) return
                    try {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val hostIp = resolved.host?.hostAddress ?: return
                                val port = if (resolved.port > 0) resolved.port else TCP_FRAME_PORT
                                handshakeWithTcpPeer(hostIp, port)
                            }
                        })
                    } catch (_: Throwable) {}
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsdDiscListener = disc
            nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
        } catch (_: Throwable) {}
    }

    private fun stopNsdQuietly(nsd: NsdManager?) {
        val mgr = nsd ?: (context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return
        nsdRegListener?.let { try { mgr.unregisterService(it) } catch (_: Throwable) {} }
        nsdDiscListener?.let { try { mgr.stopServiceDiscovery(it) } catch (_: Throwable) {} }
        nsdRegListener = null
        nsdDiscListener = null
    }

    private fun stopWifiLanTransport() {
        isWifiMeshActive = false
        stopNsdQuietly(null)
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        udpSocket = null
        tcpServer = null
    }

    private fun buildOwnBeaconJson(): String {
        val me = db.getProfile()
        return JSONObject().apply {
            put("proto", "TERON_BEACON_V1")
            put("nodeId", me.nodeId)
            put("name", me.displayName)
            put("handle", me.handle)
            put("pubKey", me.publicKeyB64)
            put("port", TCP_FRAME_PORT)
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    private fun broadcastUdpPresence(ds: DatagramSocket) {
        val beacon = buildOwnBeaconJson().toByteArray(Charsets.UTF_8)

        val targets = linkedSetOf<InetAddress>()
        try { targets.add(InetAddress.getByName(MULTICAST_GROUP_IP)) } catch (_: Exception) {}
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) targets.add(bcast)
                }
            }
        } catch (_: Exception) {}

        for (addr in targets) {
            try {
                ds.send(DatagramPacket(beacon, beacon.size, addr, UDP_DISCOVERY_PORT))
            } catch (_: Exception) {}
        }
    }

    private fun handleUdpBeacon(raw: String, senderIp: String) {
        try {
            val obj = JSONObject(raw)
            if (obj.optString("proto") != "TERON_BEACON_V1") return
            val me = db.getProfile()
            val peerId = obj.optString("nodeId")
            if (peerId.isBlank() || peerId == me.nodeId) return

            val name = obj.optString("name", peerId)
            val handle = obj.optString("handle", "")
            val pubKey = obj.optString("pubKey", "")

            val existing = livePeers[peerId]
            val transport = if (existing?.bleAddress != null) "BLE + Wi-Fi" else "Wi-Fi P2P"
            val peer = MeshPeer(
                nodeId = peerId,
                displayName = name,
                handle = handle,
                publicKeyB64 = pubKey,
                transport = transport,
                rssi = existing?.rssi ?: -52,
                distanceMeters = existing?.distanceMeters ?: 12.0,
                hops = 1,
                ipAddress = senderIp,
                bleAddress = existing?.bleAddress,
                lastSeenMs = System.currentTimeMillis(),
                isTrustedContact = true
            )
            val isNew = existing == null
            livePeers[peerId] = peer
            if (pubKey.isNotBlank()) {
                db.upsertContact(peerId, name, handle, pubKey)
            }
            if (isNew) {
                logEvent("Обнаружен узел $name ($peerId) по Wi-Fi ($senderIp)")
            } else {
                notifyStateChanged()
            }
            flushQueueToIpPeer(senderIp)
        } catch (_: Exception) {}
    }

    private fun handshakeWithTcpPeer(ip: String, port: Int = TCP_FRAME_PORT) {
        if (ip.isBlank() || ip == "127.0.0.1") return
        ioPool.execute {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 2_500)
                    s.soTimeout = 3_000
                    val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    writer.write(buildOwnBeaconJson() + "\n")
                    for (pkt in db.getPendingRelayPackets(15)) {
                        writer.write(pkt + "\n")
                    }
                    writer.flush()
                    val firstReply = reader.readLine()?.trim()
                    if (!firstReply.isNullOrEmpty() && firstReply.contains("TERON_BEACON_V1")) {
                        handleUdpBeacon(firstReply, ip)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleIncomingTcpPeer(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 6_000
            val senderIp = s.inetAddress?.hostAddress ?: ""
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            try {
                writer.write(buildOwnBeaconJson() + "\n")
                writer.flush()
            } catch (_: Exception) {}

            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val frame = line?.trim() ?: continue
                if (frame.isEmpty()) continue
                if (frame.contains("TERON_BEACON_V1")) {
                    handleUdpBeacon(frame, senderIp)
                } else {
                    processIncomingPacketJson(frame, "Wi-Fi P2P")
                }
            }
        }
    }

    private fun flushQueueToIpPeer(ip: String?) {
        if (ip.isNullOrBlank()) return
        val pending = db.getPendingRelayPackets(25)
        if (pending.isEmpty()) return
        ioPool.execute {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, TCP_FRAME_PORT), 2_500)
                    val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    for (pkt in pending) {
                        writer.write(pkt)
                        writer.write("\n")
                    }
                    writer.flush()
                }
            } catch (_: Exception) {}
        }
    }

    private fun sendFrameToAllWifiPeers(packetJson: String) {
        val ips = livePeers.values.mapNotNull { it.ipAddress }.distinct()
        for (ip in ips) {
            ioPool.execute {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, TCP_FRAME_PORT), 2_500)
                        val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                        writer.write(packetJson)
                        writer.write("\n")
                        writer.flush()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // =========================================================================
    // 2. REAL BLUETOOTH LOW ENERGY (BLE) ADVERTISER + SCANNER + GATT SERVER
    // =========================================================================
    fun refreshBleTransport() {
        if (!isRunning) return
        if (!hasBlePermissions()) {
            logEvent("BLE ожидает разрешения «Устройства рядом» для радиосвязи")
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) {
            isBleAdvertising = false
            isBleScanning = false
            logEvent("Bluetooth выключен — работает локальный Wi-Fi AirMesh")
            return
        }

        startBleGattServer(bm)
        startBleAdvertising(adapter)
        startBleScanning(adapter)
    }

    private fun stopBleTransport() {
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter
            if (hasBlePermissions() && adapter != null && adapter.isEnabled) {
                adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                adapter.bluetoothLeScanner?.stopScan(scanCallback)
            }
            gattServer?.close()
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
        gattServer = null
        isBleAdvertising = false
        isBleScanning = false
    }

    private fun startBleGattServer(bm: BluetoothManager) {
        if (gattServer != null) return
        try {
            val server = bm.openGattServer(context, gattServerCallback) ?: return
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            val packetChar = BluetoothGattCharacteristic(
                CHAR_PACKET_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )
            val idChar = BluetoothGattCharacteristic(
                CHAR_IDENTITY_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(packetChar)
            service.addCharacteristic(idChar)
            server.addService(service)
            gattServer = server
            logEvent("BLE GATT сервер AirMesh поднят (${SERVICE_UUID.toString().take(8)})")
        } catch (se: SecurityException) {
            logEvent("BLE GATT требует разрешение: ${se.message}")
        } catch (e: Exception) {
            logEvent("BLE GATT ошибка: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            try {
                val payload = when (characteristic.uuid) {
                    CHAR_IDENTITY_UUID -> {
                        val me = db.getProfile()
                        JSONObject().apply {
                            put("nodeId", me.nodeId)
                            put("name", me.displayName)
                            put("handle", me.handle)
                            put("pubKey", me.publicKeyB64)
                        }.toString().toByteArray(Charsets.UTF_8)
                    }
                    CHAR_PACKET_UUID -> {
                        val pending = db.getPendingRelayPackets(3).joinToString("\n")
                        pending.toByteArray(Charsets.UTF_8)
                    }
                    else -> ByteArray(0)
                }
                val slice = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } catch (_: SecurityException) {}
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            try {
                if (characteristic.uuid == CHAR_PACKET_UUID && value != null) {
                    val chunk = String(value, Charsets.UTF_8)
                    val sb = bleWriteBuffers.getOrPut(device.address) { StringBuilder() }
                    synchronized(sb) {
                        sb.append(chunk)
                        var nl = sb.indexOf("\n")
                        while (nl >= 0) {
                            val frame = sb.substring(0, nl).trim()
                            sb.delete(0, nl + 1)
                            if (frame.isNotEmpty()) {
                                ioPool.execute { processIncomingPacketJson(frame, "Bluetooth LE") }
                            }
                            nl = sb.indexOf("\n")
                        }
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } catch (_: SecurityException) {}
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isBleAdvertising = true
            logEvent("BLE маяк AirMesh вещает в радиусе до 200 м")
        }

        override fun onStartFailure(errorCode: Int) {
            isBleAdvertising = false
            logEvent("BLE маяк код $errorCode (устройство продолжает сканирование)")
        }
    }

    fun triggerDiscoverySweep() {
        ioPool.execute {
            try {
                udpSocket?.let { broadcastUdpPresence(it) }
            } catch (_: Throwable) {}
            startNsdDiscoveryAndRegistration()
        }
        mainHandler.post {
            stopBleTransport()
            refreshBleTransport()
        }
    }

    private fun startBleAdvertising(adapter: BluetoothAdapter) {
        if (isBleAdvertising) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        try {
            val me = db.getProfile()
            // Split Service UUID (Primary Adv PDU <= 21B) and 8B Node ID (Scan Response PDU <= 29B)
            // so legacy 31-byte BLE advertising never fails with ADVERTISE_FAILED_DATA_TOO_LARGE!
            val shortCode = me.nodeId.removePrefix("TRN-").take(8).toByteArray(Charsets.US_ASCII)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(SERVICE_UUID), shortCode)
                .build()
            advertiser.startAdvertising(settings, advData, scanResponse, advertiseCallback)
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            logEvent("BLE Advertising: ${e.message}")
        }
    }

    private fun estimateDistanceMeters(rssi: Int): Double {
        val txPower = -59.0
        val ratio = (txPower - rssi) / (10.0 * 2.2)
        return (10.0.pow(ratio) * 10.0).toInt() / 10.0
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val me = db.getProfile()
                val svcData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                val hexTag = if (svcData != null && svcData.isNotEmpty()) {
                    "TRN-" + String(svcData, Charsets.US_ASCII).trim()
                } else {
                    "TRN-" + result.device.address.replace(":", "").takeLast(8)
                }
                if (hexTag == me.nodeId) return

                val dist = estimateDistanceMeters(result.rssi)
                val existing = livePeers[hexTag]
                val peer = MeshPeer(
                    nodeId = hexTag,
                    displayName = existing?.displayName ?: "Узел $hexTag",
                    handle = existing?.handle ?: "${hexTag.lowercase()}@teron.mesh",
                    publicKeyB64 = existing?.publicKeyB64 ?: "",
                    transport = if (existing?.ipAddress != null) "BLE + Wi-Fi" else "Bluetooth LE",
                    rssi = result.rssi,
                    distanceMeters = dist,
                    hops = 1,
                    ipAddress = existing?.ipAddress,
                    bleAddress = result.device.address,
                    lastSeenMs = System.currentTimeMillis(),
                    isTrustedContact = existing?.isTrustedContact ?: false
                )
                val isNew = existing == null
                livePeers[hexTag] = peer
                if (isNew) {
                    logEvent("BLE сигнал от $hexTag (${result.rssi} дБм, ~${dist} м)")
                } else {
                    notifyStateChanged()
                }

                // Periodically sync identity + queued packets over BLE GATT
                val lastSync = bleLastSyncByAddr[result.device.address] ?: 0L
                val now = System.currentTimeMillis()
                if (now - lastSync > 12_000L && (peer.publicKeyB64.isBlank() || db.countPendingRelayPackets() > 0)) {
                    bleLastSyncByAddr[result.device.address] = now
                    syncWithBleDevice(result.device, hexTag)
                }
            } catch (_: SecurityException) {}
        }
    }

    private fun startBleScanning(adapter: BluetoothAdapter) {
        if (isBleScanning) return
        val scanner = adapter.bluetoothLeScanner ?: return
        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            isBleScanning = true
            logEvent("BLE сканер AirMesh ищет смартфоны Teron рядом")
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            logEvent("BLE Scan: ${e.message}")
        }
    }

    private fun syncWithBleDevice(device: BluetoothDevice, fallbackNodeId: String) {
        if (!hasBlePermissions()) return
        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    try {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.requestMtu(512)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gatt.close()
                        }
                    } catch (_: SecurityException) {}
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    try { gatt.discoverServices() } catch (_: SecurityException) {}
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    try {
                        val svc = gatt.getService(SERVICE_UUID) ?: run { gatt.disconnect(); return }
                        val idChar = svc.getCharacteristic(CHAR_IDENTITY_UUID)
                        if (idChar != null) {
                            gatt.readCharacteristic(idChar)
                        } else {
                            pushPendingOverBleGatt(gatt)
                        }
                    } catch (_: SecurityException) {}
                }

                private fun handleReadIdentityBytes(
                    gatt: BluetoothGatt,
                    uuid: UUID,
                    bytes: ByteArray,
                    status: Int
                ) {
                    try {
                        if (status == BluetoothGatt.GATT_SUCCESS && uuid == CHAR_IDENTITY_UUID && bytes.isNotEmpty()) {
                            val obj = JSONObject(String(bytes, Charsets.UTF_8))
                            val nid = obj.optString("nodeId", fallbackNodeId)
                            val name = obj.optString("name", nid)
                            val handle = obj.optString("handle", "")
                            val pubKey = obj.optString("pubKey", "")
                            val prev = livePeers[nid]
                            livePeers[nid] = MeshPeer(
                                nodeId = nid,
                                displayName = name,
                                handle = handle,
                                publicKeyB64 = pubKey,
                                transport = prev?.transport ?: "Bluetooth LE",
                                rssi = prev?.rssi ?: -64,
                                distanceMeters = prev?.distanceMeters ?: 15.0,
                                hops = 1,
                                ipAddress = prev?.ipAddress,
                                bleAddress = device.address,
                                lastSeenMs = System.currentTimeMillis(),
                                isTrustedContact = true
                            )
                            if (pubKey.isNotBlank()) db.upsertContact(nid, name, handle, pubKey)
                            notifyStateChanged()
                        }
                        pushPendingOverBleGatt(gatt)
                    } catch (_: Exception) {
                        try { gatt.disconnect() } catch (_: SecurityException) {}
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    handleReadIdentityBytes(gatt, characteristic.uuid, value, status)
                }

                @Deprecated("Used on Android 7-12 (API < 33)")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: ByteArray(0)
                    handleReadIdentityBytes(gatt, characteristic.uuid, bytes, status)
                }
            })
        } catch (_: SecurityException) {}
    }

    @Suppress("DEPRECATION")
    private fun pushPendingOverBleGatt(gatt: BluetoothGatt) {
        ioPool.execute {
            try {
                val svc = gatt.getService(SERVICE_UUID)
                val pktChar = svc?.getCharacteristic(CHAR_PACKET_UUID)
                val pending = db.getPendingRelayPackets(5)
                if (pktChar != null && pending.isNotEmpty()) {
                    for (frame in pending) {
                        val data = (frame + "\n").toByteArray(Charsets.UTF_8)
                        var idx = 0
                        while (idx < data.size) {
                            val end = (idx + 180).coerceAtMost(data.size)
                            val slice = data.copyOfRange(idx, end)
                            if (Build.VERSION.SDK_INT >= 33) {
                                gatt.writeCharacteristic(pktChar, slice, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                            } else {
                                @Suppress("DEPRECATION")
                                pktChar.value = slice
                                pktChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(pktChar)
                            }
                            Thread.sleep(75)
                            idx = end
                        }
                    }
                }
                Thread.sleep(150)
                gatt.disconnect()
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // 3. MULTI-HOP STORE & FORWARD PACKET CREATION, E2EE & ROUTING
    // =========================================================================
    private fun packetToJson(p: MeshRelayPacket): String = JSONObject().apply {
        put("packetId", p.packetId)
        put("kind", p.kind)
        put("senderNodeId", p.senderNodeId)
        put("senderName", p.senderName)
        put("senderHandle", p.senderHandle)
        put("senderPubKey", p.senderPubKey)
        put("targetNodeId", p.targetNodeId)
        put("ttl", p.ttl)
        put("hopCount", p.hopCount)
        put("viaNodes", p.viaNodes)
        put("cipherTextB64", p.cipherTextB64)
        put("ivB64", p.ivB64)
        put("signatureB64", p.signatureB64)
        put("metaSubject", p.metaSubject)
        put("createdAtMs", p.createdAtMs)
    }.toString()

    fun parseRelayPacketJson(raw: String): MeshRelayPacket? = jsonToPacket(raw)

    private fun jsonToPacket(raw: String): MeshRelayPacket? = try {
        val o = JSONObject(raw)
        MeshRelayPacket(
            packetId = o.getString("packetId"),
            kind = o.getString("kind"),
            senderNodeId = o.getString("senderNodeId"),
            senderName = o.optString("senderName", o.getString("senderNodeId")),
            senderHandle = o.optString("senderHandle", ""),
            senderPubKey = o.optString("senderPubKey", ""),
            targetNodeId = o.getString("targetNodeId"),
            ttl = o.optInt("ttl", 4),
            hopCount = o.optInt("hopCount", 0),
            viaNodes = o.optString("viaNodes", ""),
            cipherTextB64 = o.getString("cipherTextB64"),
            ivB64 = o.getString("ivB64"),
            signatureB64 = o.optString("signatureB64", ""),
            metaSubject = o.optString("metaSubject", ""),
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis())
        )
    } catch (_: Exception) {
        null
    }

    fun sendChatMessage(conversationId: String, plainText: String) {
        val text = plainText.trim()
        if (text.isEmpty()) return
        val me = db.getProfile()
        val now = System.currentTimeMillis()
        val packetId = UUID.randomUUID().toString()

        // Local encrypted notes
        if (conversationId == "notes") {
            val localKey = TeronCrypto.derivePeerKey(me.privateKeyB64, me.publicKeyB64)
            val enc = TeronCrypto.encryptAesGcm(text, localKey)
            db.insertMessageIfNew(
                packetId = packetId,
                conversationId = "notes",
                senderNodeId = me.nodeId,
                senderName = me.displayName,
                recipientNodeId = me.nodeId,
                body = text,
                cipherPreview = enc.cipherTextB64.take(36) + "…",
                timestampMs = now,
                isOutgoing = true,
                transport = "Локальный сейф",
                hopCount = 0,
                viaPath = me.nodeId,
                deliveryState = "AES-256-GCM • На устройстве"
            )
            notifyStateChanged()
            return
        }

        val isBroadcast = conversationId == "#airmesh" || conversationId == "#sos"
        val kind = when (conversationId) {
            "#sos" -> "SOS"
            "#airmesh" -> "BROADCAST"
            else -> "CHAT"
        }
        val targetId = if (isBroadcast) "*" else conversationId
        val peerPub = if (!isBroadcast) {
            livePeers[conversationId]?.publicKeyB64?.takeIf { it.isNotBlank() }
                ?: db.getContactPublicKey(conversationId)
        } else null

        val symKey = when {
            isBroadcast -> TeronCrypto.deriveChannelKey(conversationId)
            peerPub != null -> TeronCrypto.derivePeerKey(me.privateKeyB64, peerPub)
            else -> TeronCrypto.deriveChannelKey("pair:${ listOf(me.nodeId, conversationId).sorted().joinToString(":") }")
        }

        val enc = TeronCrypto.encryptAesGcm(text, symKey)
        val sig = TeronCrypto.signData(me.privateKeyB64, "$packetId:$kind:${me.nodeId}:$targetId:${enc.cipherTextB64}")
        val ttl = if (kind == "SOS") 6 else me.maxRelayHops

        val pkt = MeshRelayPacket(
            packetId = packetId,
            kind = kind,
            senderNodeId = me.nodeId,
            senderName = me.displayName,
            senderHandle = me.handle,
            senderPubKey = me.publicKeyB64,
            targetNodeId = targetId,
            ttl = ttl,
            hopCount = 0,
            viaNodes = me.nodeId,
            cipherTextB64 = enc.cipherTextB64,
            ivB64 = enc.ivB64,
            signatureB64 = sig,
            metaSubject = conversationId,
            createdAtMs = now
        )
        val json = packetToJson(pkt)
        seenPackets.add(packetId)

        val nearbyCount = livePeers.size
        val state = if (nearbyCount > 0) {
            "Передано в эфир ($nearbyCount узл.) • AES-256"
        } else {
            "Store & Forward (ждёт узел рядом)"
        }

        db.insertMessageIfNew(
            packetId = packetId,
            conversationId = conversationId,
            senderNodeId = me.nodeId,
            senderName = me.displayName,
            recipientNodeId = targetId,
            body = text,
            cipherPreview = enc.cipherTextB64.take(40) + "…",
            timestampMs = now,
            isOutgoing = true,
            transport = if (nearbyCount > 0) "AirMesh Радио" else "Очередь AirMesh",
            hopCount = 0,
            viaPath = me.nodeId,
            deliveryState = state
        )

        db.enqueueRelayPacket(packetId, json, targetId, ttl)
        sendFrameToAllWifiPeers(json)
        onOutboundPacketHook?.invoke(pkt)
        logEvent("Отправлен пакет ${packetId.take(8)} ($kind, TTL=$ttl)")
    }

    fun sendAirMeshMail(toAddress: String, targetNodeIdOrBlank: String, subject: String, body: String, priority: String) {
        val me = db.getProfile()
        val now = System.currentTimeMillis()
        val packetId = "mail-" + UUID.randomUUID().toString()

        // Try to match contact by handle if targetNodeId not explicit
        val matchedContact = db.getContacts().firstOrNull {
            it.handle.equals(toAddress.trim(), ignoreCase = true) || it.nodeId.equals(targetNodeIdOrBlank.trim(), ignoreCase = true)
        }
        val targetId = matchedContact?.nodeId ?: targetNodeIdOrBlank.ifBlank { "*" }

        val mailPayload = JSONObject().apply {
            put("toAddress", toAddress.trim())
            put("subject", subject.trim())
            put("body", body)
            put("priority", priority)
        }.toString()

        val key = if (matchedContact != null && matchedContact.publicKeyB64.isNotBlank()) {
            TeronCrypto.derivePeerKey(me.privateKeyB64, matchedContact.publicKeyB64)
        } else {
            TeronCrypto.deriveChannelKey("mail:${toAddress.trim().lowercase()}")
        }
        val enc = TeronCrypto.encryptAesGcm(mailPayload, key)
        val sig = TeronCrypto.signData(me.privateKeyB64, "$packetId:MAIL:${me.nodeId}:$targetId:${enc.cipherTextB64}")

        val pkt = MeshRelayPacket(
            packetId = packetId,
            kind = "MAIL",
            senderNodeId = me.nodeId,
            senderName = me.displayName,
            senderHandle = me.handle,
            senderPubKey = me.publicKeyB64,
            targetNodeId = targetId,
            ttl = me.maxRelayHops,
            hopCount = 0,
            viaNodes = me.nodeId,
            cipherTextB64 = enc.cipherTextB64,
            ivB64 = enc.ivB64,
            signatureB64 = sig,
            metaSubject = toAddress.trim().lowercase(),
            createdAtMs = now
        )
        val json = packetToJson(pkt)
        seenPackets.add(packetId)

        val folder = if (livePeers.isNotEmpty()) "SENT" else "OUTBOX_MESH"
        db.insertMailIfNew(
            packetId = packetId,
            folder = folder,
            fromName = me.displayName,
            fromAddress = me.handle,
            toAddress = toAddress.trim(),
            subject = subject,
            body = body,
            timestampMs = now,
            isRead = true,
            viaAirMesh = true,
            hopCount = 0,
            priority = priority
        )
        db.enqueueRelayPacket(packetId, json, targetId, me.maxRelayHops)
        sendFrameToAllWifiPeers(json)
        onOutboundPacketHook?.invoke(pkt)
        logEvent("Письмо «${subject.take(22)}» поставлено в AirMesh ($folder)")
    }

    fun broadcastSignedCryptoTransfer(
        txHash: String,
        symbol: String,
        amount: Double,
        recipientNodeOrWallet: String,
        memo: String,
        signatureHex: String
    ) {
        val me = db.getProfile()
        val packetId = "tx-$txHash"
        val payload = JSONObject().apply {
            put("txHash", txHash)
            put("symbol", symbol)
            put("amount", amount)
            put("to", recipientNodeOrWallet)
            put("fromWallet", me.walletAddress)
            put("memo", memo)
            put("sig", signatureHex)
        }.toString()
        val enc = TeronCrypto.encryptAesGcm(payload, TeronCrypto.deriveChannelKey("teronpay-v1"))
        val sig = TeronCrypto.signData(me.privateKeyB64, "$packetId:CRYPTO_TX:${me.nodeId}:*:${enc.cipherTextB64}")
        val pkt = MeshRelayPacket(
            packetId = packetId,
            kind = "CRYPTO_TX",
            senderNodeId = me.nodeId,
            senderName = me.displayName,
            senderHandle = me.handle,
            senderPubKey = me.publicKeyB64,
            targetNodeId = "*",
            ttl = me.maxRelayHops,
            hopCount = 0,
            viaNodes = me.nodeId,
            cipherTextB64 = enc.cipherTextB64,
            ivB64 = enc.ivB64,
            signatureB64 = sig,
            createdAtMs = System.currentTimeMillis()
        )
        val json = packetToJson(pkt)
        seenPackets.add(packetId)
        db.enqueueRelayPacket(packetId, json, "*", me.maxRelayHops)
        sendFrameToAllWifiPeers(json)
        onOutboundPacketHook?.invoke(pkt)
        logEvent("Подписанный крипто-перевод $amount $symbol отправлен в AirMesh")
    }

    fun processIncomingPacketJson(rawJson: String, incomingTransport: String) {
        val pkt = jsonToPacket(rawJson) ?: return
        if (!seenPackets.add(pkt.packetId)) return
        if (pkt.kind != "ACK" && db.hasSeenPacket(pkt.packetId)) return

        val me = db.getProfile()
        if (pkt.senderNodeId == me.nodeId) return

        // Verify cryptographic ECDSA authenticity if sender included public key
        if (pkt.senderPubKey.isNotBlank() && pkt.signatureB64.isNotBlank()) {
            val signedPayload = "${pkt.packetId}:${pkt.kind}:${pkt.senderNodeId}:${pkt.targetNodeId}:${pkt.cipherTextB64}"
            if (!TeronCrypto.verifySignature(pkt.senderPubKey, signedPayload, pkt.signatureB64)) {
                logEvent("Отклонён пакет с неверной ECDSA подписью от ${pkt.senderNodeId}")
                return
            }
            db.upsertContact(pkt.senderNodeId, pkt.senderName, pkt.senderHandle, pkt.senderPubKey)
        }

        val hopsHere = pkt.hopCount + 1
        val fullPath = if (pkt.viaNodes.isBlank()) "${pkt.senderNodeId} → ${me.nodeId}" else "${pkt.viaNodes} → ${me.nodeId}"
        val forMe = pkt.targetNodeId == me.nodeId || pkt.targetNodeId == "*"

        if (forMe) {
            when (pkt.kind) {
                "BROADCAST", "SOS" -> {
                    val chId = if (pkt.kind == "SOS") "#sos" else "#airmesh"
                    val plain = TeronCrypto.decryptAesGcm(pkt.cipherTextB64, pkt.ivB64, TeronCrypto.deriveChannelKey(chId))
                    if (plain != null) {
                        db.insertMessageIfNew(
                            packetId = pkt.packetId,
                            conversationId = chId,
                            senderNodeId = pkt.senderNodeId,
                            senderName = pkt.senderName,
                            recipientNodeId = "*",
                            body = plain,
                            cipherPreview = pkt.cipherTextB64.take(36) + "…",
                            timestampMs = pkt.createdAtMs,
                            isOutgoing = false,
                            transport = incomingTransport,
                            hopCount = hopsHere,
                            viaPath = fullPath,
                            deliveryState = "ECDSA • $hopsHere пер.",
                            isRead = false
                        )
                        notifyDelivered("${pkt.senderName} ($chId): ${plain.take(40)}")
                    }
                }
                "CHAT" -> {
                    if (pkt.targetNodeId == me.nodeId) {
                        val key = if (pkt.senderPubKey.isNotBlank()) {
                            TeronCrypto.derivePeerKey(me.privateKeyB64, pkt.senderPubKey)
                        } else {
                            TeronCrypto.deriveChannelKey("pair:${listOf(me.nodeId, pkt.senderNodeId).sorted().joinToString(":")}")
                        }
                        val plain = TeronCrypto.decryptAesGcm(pkt.cipherTextB64, pkt.ivB64, key)
                        if (plain != null) {
                            db.insertMessageIfNew(
                                packetId = pkt.packetId,
                                conversationId = pkt.senderNodeId,
                                senderNodeId = pkt.senderNodeId,
                                senderName = pkt.senderName,
                                recipientNodeId = me.nodeId,
                                body = plain,
                                cipherPreview = pkt.cipherTextB64.take(36) + "…",
                                timestampMs = pkt.createdAtMs,
                                isOutgoing = false,
                                transport = incomingTransport,
                                hopCount = hopsHere,
                                viaPath = fullPath,
                                deliveryState = "E2EE ECDH • $hopsHere пер.",
                                isRead = false
                            )
                            sendDeliveryAck(pkt.packetId, pkt.senderNodeId, hopsHere)
                            notifyDelivered("Личное сообщение от ${pkt.senderName}")
                        }
                    }
                }
                "ACK" -> {
                    if (pkt.targetNodeId == me.nodeId) {
                        val origId = pkt.metaSubject
                        db.updateMessageDeliveryState(origId, "Доставлено адресату (${pkt.hopCount + 1} пер.)")
                        db.markRelayPacketDelivered(origId)
                        db.promoteMailOutboxToSent(origId)
                        logEvent("Подтверждение доставки ACK от ${pkt.senderName}")
                    }
                }
                "MAIL" -> {
                    val isMyMail = pkt.targetNodeId == me.nodeId || pkt.metaSubject.equals(me.handle, ignoreCase = true)
                    if (isMyMail) {
                        val key1 = if (pkt.senderPubKey.isNotBlank()) TeronCrypto.derivePeerKey(me.privateKeyB64, pkt.senderPubKey) else null
                        val plain = (if (key1 != null) TeronCrypto.decryptAesGcm(pkt.cipherTextB64, pkt.ivB64, key1) else null)
                            ?: TeronCrypto.decryptAesGcm(pkt.cipherTextB64, pkt.ivB64, TeronCrypto.deriveChannelKey("mail:${me.handle.lowercase()}"))
                        if (plain != null) {
                            val mo = JSONObject(plain)
                            val subj = mo.optString("subject", "(Без темы)")
                            db.insertMailIfNew(
                                packetId = pkt.packetId,
                                folder = "INBOX",
                                fromName = pkt.senderName,
                                fromAddress = pkt.senderHandle.ifBlank { "${pkt.senderNodeId.lowercase()}@teron.mesh" },
                                toAddress = me.handle,
                                subject = subj,
                                body = mo.optString("body", ""),
                                timestampMs = pkt.createdAtMs,
                                isRead = false,
                                viaAirMesh = true,
                                hopCount = hopsHere,
                                priority = mo.optString("priority", "NORMAL")
                            )
                            sendDeliveryAck(pkt.packetId, pkt.senderNodeId, hopsHere)
                            notifyDelivered("Новое письмо AirMesh: $subj")
                        }
                    }
                }
                "CRYPTO_TX" -> {
                    val plain = TeronCrypto.decryptAesGcm(pkt.cipherTextB64, pkt.ivB64, TeronCrypto.deriveChannelKey("teronpay-v1"))
                    if (plain != null) {
                        val tx = JSONObject(plain)
                        val toTarget = tx.optString("to", "")
                        if (toTarget.equals(me.nodeId, ignoreCase = true) ||
                            toTarget.equals(me.walletAddress, ignoreCase = true) ||
                            toTarget.equals(me.trxAddress, ignoreCase = true) ||
                            toTarget.equals(me.btcAddress, ignoreCase = true) ||
                            toTarget.equals(me.handle, ignoreCase = true)
                        ) {
                            val amt = tx.optDouble("amount", 0.0)
                            val sym = tx.optString("symbol", "USDT")
                            val txHash = tx.optString("txHash", "")
                            if (amt > 0.0 && txHash.isNotBlank()) {
                                db.upsertWalletTransaction(
                                    txHash = txHash,
                                    symbol = sym,
                                    network = "AirMesh ECDSA",
                                    amount = amt,
                                    counterpartyName = pkt.senderName,
                                    counterpartyAddress = tx.optString("fromWallet"),
                                    isIncoming = true,
                                    memo = tx.optString("memo", "Подписанный P2P-чек AirMesh"),
                                    status = "Подписан ECDSA",
                                    viaAirMesh = true,
                                    timestampMs = pkt.createdAtMs
                                )
                                notifyDelivered("Получен подписанный чек $amt $sym от ${pkt.senderName}")
                            }
                        }
                    }
                }
            }
        }

        // Multi-hop Relay (Эстафета AirMesh) if packet is for someone else or broadcast and TTL > 1
        if ((pkt.targetNodeId != me.nodeId) && pkt.ttl > 1) {
            val relayed = pkt.copy(
                ttl = pkt.ttl - 1,
                hopCount = hopsHere,
                viaNodes = fullPath
            )
            val relayedJson = packetToJson(relayed)
            db.enqueueRelayPacket(relayed.packetId, relayedJson, relayed.targetNodeId, relayed.ttl)
            sendFrameToAllWifiPeers(relayedJson)
            relayedCountSession++
            logEvent("Эстафета AirMesh: ретранслирован пакет ${pkt.packetId.take(8)} (переход #$hopsHere, ост. TTL=${relayed.ttl})")
        }
    }

    private fun sendDeliveryAck(originalPacketId: String, recipientNodeId: String, hops: Int) {
        val me = db.getProfile()
        val ackId = "ack-" + UUID.randomUUID().toString()
        val enc = TeronCrypto.encryptAesGcm("ACK:$originalPacketId", TeronCrypto.deriveChannelKey("ack"))
        val sig = TeronCrypto.signData(me.privateKeyB64, "$ackId:ACK:${me.nodeId}:$recipientNodeId:${enc.cipherTextB64}")
        val ack = MeshRelayPacket(
            packetId = ackId,
            kind = "ACK",
            senderNodeId = me.nodeId,
            senderName = me.displayName,
            senderHandle = me.handle,
            senderPubKey = me.publicKeyB64,
            targetNodeId = recipientNodeId,
            ttl = 4,
            hopCount = hops,
            viaNodes = me.nodeId,
            cipherTextB64 = enc.cipherTextB64,
            ivB64 = enc.ivB64,
            signatureB64 = sig,
            metaSubject = originalPacketId,
            createdAtMs = System.currentTimeMillis()
        )
        val json = packetToJson(ack)
        seenPackets.add(ackId)
        db.enqueueRelayPacket(ackId, json, recipientNodeId, 4)
        sendFrameToAllWifiPeers(json)
        onOutboundPacketHook?.invoke(ack)
    }

    fun getLocalIpv4(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Throwable) {}
        return "Нет LAN IP"
    }
}
