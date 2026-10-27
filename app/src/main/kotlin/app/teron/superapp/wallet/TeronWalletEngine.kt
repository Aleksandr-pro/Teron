package app.teron.superapp.wallet

import android.os.Handler
import android.os.Looper
import app.teron.superapp.core.TeronDatabase
import app.teron.superapp.crypto.Secp256k1Wallet
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class TeronWalletEngine(
    private val db: TeronDatabase
) {
    interface Listener {
        fun onWalletUpdated(syncMessage: String?)
    }

    companion object {
        const val USDT_ERC20_CONTRACT = "0xdAC17F958D2ee523a2206206994597C13D831ec7"
        const val USDT_TRC20_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"

        private val ETH_RPCS = listOf(
            "https://ethereum-rpc.publicnode.com",
            "https://eth.llamarpc.com",
            "https://cloudflare-eth.com"
        )
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val netPool = Executors.newFixedThreadPool(3)
    private val listeners = Collections.synchronizedSet(LinkedHashSet<Listener>())

    @Volatile var isSyncing: Boolean = false
        private set
    @Volatile var lastSyncStatus: String = "Локальный режим • Нажмите «Обновить» для синхронизации с блокчейном"
        private set

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyUpdated(msg: String? = null) {
        mainHandler.post {
            for (l in listeners.toList()) l.onWalletUpdated(msg)
        }
    }

    // =========================================================================
    // 1. LIVE MULTI-CHAIN BALANCE, MARKET PRICE & HISTORY SYNC
    // =========================================================================
    fun refreshAllChains(userInitiated: Boolean = false) {
        if (isSyncing) return
        isSyncing = true
        lastSyncStatus = "Синхронизация с узлами Bitcoin, Ethereum и TRON…"
        notifyUpdated(null)

        netPool.execute {
            var syncedAny = false
            val profile = try { db.getProfile() } catch (_: Throwable) {
                isSyncing = false
                return@execute
            }

            // 1. Market exchange rates (USD + RUB)
            if (syncMarketRates()) syncedAny = true

            // 2. Bitcoin Native SegWit (bc1q...)
            if (profile.btcAddress.isNotBlank()) {
                if (syncBitcoin(profile.btcAddress)) syncedAny = true
            }

            // 3. Ethereum + USDT ERC-20 (0x...)
            if (profile.walletAddress.startsWith("0x")) {
                if (syncEthereumAndUsdt(profile.walletAddress)) syncedAny = true
            }

            // 4. TRON + USDT TRC-20 (T...)
            if (profile.trxAddress.startsWith("T")) {
                if (syncTronAndTrc20(profile.trxAddress)) syncedAny = true
            }

            isSyncing = false
            val stamp = app.teron.superapp.core.Theme.formatTime(System.currentTimeMillis())
            lastSyncStatus = if (syncedAny) {
                "Ончейн-данные актуальны • Обновлено в $stamp"
            } else {
                "Нет доступа к интернету • Показаны локальные данные"
            }
            notifyUpdated(if (userInitiated) lastSyncStatus else null)
        }
    }

    private fun syncMarketRates(): Boolean {
        // Primary: CoinGecko simple price (USD & RUB)
        try {
            val raw = httpGet("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,tether,tron&vs_currencies=usd,rub")
            if (raw != null) {
                val j = JSONObject(raw)
                val btcUsd = j.optJSONObject("bitcoin")?.optDouble("usd", 0.0) ?: 0.0
                val ethUsd = j.optJSONObject("ethereum")?.optDouble("usd", 0.0) ?: 0.0
                val usdtUsd = j.optJSONObject("tether")?.optDouble("usd", 1.0) ?: 1.0
                val trxUsd = j.optJSONObject("tron")?.optDouble("usd", 0.0) ?: 0.0
                val usdtRub = j.optJSONObject("tether")?.optDouble("rub", 0.0) ?: 0.0
                if (btcUsd > 0.0 && ethUsd > 0.0) {
                    db.updateMarketRates(
                        mapOf("BTC" to btcUsd, "ETH" to ethUsd, "USDT" to usdtUsd, "TRX" to trxUsd),
                        usdtRub
                    )
                    return true
                }
            }
        } catch (_: Throwable) {}

        // Fallback: Coinbase public exchange rates
        try {
            val raw = httpGet("https://api.coinbase.com/v2/exchange-rates?currency=USD")
            if (raw != null) {
                val rates = JSONObject(raw).optJSONObject("data")?.optJSONObject("rates")
                if (rates != null) {
                    val rub = rates.optString("RUB").toDoubleOrNull() ?: 0.0
                    val btcInv = rates.optString("BTC").toDoubleOrNull() ?: 0.0
                    val ethInv = rates.optString("ETH").toDoubleOrNull() ?: 0.0
                    val trxInv = rates.optString("TRX").toDoubleOrNull() ?: 0.0
                    val map = mutableMapOf("USDT" to 1.0)
                    if (btcInv > 0.0) map["BTC"] = 1.0 / btcInv
                    if (ethInv > 0.0) map["ETH"] = 1.0 / ethInv
                    if (trxInv > 0.0) map["TRX"] = 1.0 / trxInv
                    db.updateMarketRates(map, rub)
                    return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun syncBitcoin(btcAddress: String): Boolean {
        val endpoints = listOf(
            "https://mempool.space/api",
            "https://blockstream.info/api"
        )
        for (base in endpoints) {
            try {
                val addrJson = httpGet("$base/address/$btcAddress") ?: continue
                val obj = JSONObject(addrJson)
                val chain = obj.optJSONObject("chain_stats")
                val mempool = obj.optJSONObject("mempool_stats")
                val sats = (chain?.optLong("funded_txo_sum", 0L) ?: 0L) -
                    (chain?.optLong("spent_txo_sum", 0L) ?: 0L) +
                    (mempool?.optLong("funded_txo_sum", 0L) ?: 0L) -
                    (mempool?.optLong("spent_txo_sum", 0L) ?: 0L)
                db.updateChainBalance("BTC", sats.coerceAtLeast(0L) / 100_000_000.0)

                val txsJson = httpGet("$base/address/$btcAddress/txs")
                if (txsJson != null) {
                    val arr = JSONArray(txsJson)
                    for (i in 0 until minOf(arr.length(), 15)) {
                        val tx = arr.getJSONObject(i)
                        val txid = tx.optString("txid")
                        var receivedSats = 0L
                        var sentSats = 0L
                        var otherAddr = ""
                        val vout = tx.optJSONArray("vout") ?: JSONArray()
                        for (k in 0 until vout.length()) {
                            val o = vout.getJSONObject(k)
                            val a = o.optString("scriptpubkey_address")
                            val v = o.optLong("value", 0L)
                            if (a == btcAddress) receivedSats += v else if (otherAddr.isEmpty() && a.isNotEmpty()) otherAddr = a
                        }
                        val vin = tx.optJSONArray("vin") ?: JSONArray()
                        for (k in 0 until vin.length()) {
                            val prev = vin.getJSONObject(k).optJSONObject("prevout") ?: continue
                            val a = prev.optString("scriptpubkey_address")
                            val v = prev.optLong("value", 0L)
                            if (a == btcAddress) sentSats += v else if (otherAddr.isEmpty() && a.isNotEmpty()) otherAddr = a
                        }
                        val net = receivedSats - sentSats
                        val incoming = net >= 0L
                        val amtBtc = kotlin.math.abs(net) / 100_000_000.0
                        val st = tx.optJSONObject("status")
                        val confirmed = st?.optBoolean("confirmed", false) ?: false
                        val ts = (st?.optLong("block_time", 0L) ?: 0L).let { if (it > 0L) it * 1000L else System.currentTimeMillis() }
                        if (amtBtc > 0.0) {
                            db.upsertWalletTransaction(
                                txHash = txid,
                                symbol = "BTC",
                                network = "Bitcoin",
                                amount = amtBtc,
                                counterpartyName = otherAddr.ifBlank { "Bitcoin Network" },
                                counterpartyAddress = otherAddr,
                                isIncoming = incoming,
                                memo = if (confirmed) "Подтверждено в блоке" else "В мемпуле",
                                status = if (confirmed) "Подтверждена" else "В обработке",
                                viaAirMesh = false,
                                timestampMs = ts
                            )
                        }
                    }
                }
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun syncEthereumAndUsdt(ethAddress: String): Boolean {
        var rpcOk = false
        for (rpc in ETH_RPCS) {
            try {
                val balHex = ethRpcCall(rpc, "eth_getBalance", JSONArray().put(ethAddress).put("latest"))
                if (balHex != null && balHex.startsWith("0x")) {
                    val wei = parseHexBigInt(balHex)
                    val ethVal = BigDecimal(wei).divide(BigDecimal.TEN.pow(18), 8, RoundingMode.DOWN).toDouble()
                    db.updateChainBalance("ETH", ethVal)
                    rpcOk = true
                }

                // ERC-20 USDT balanceOf(address) = 0x70a08231 + 32-byte padded address
                val cleanAddr = ethAddress.removePrefix("0x").lowercase().padStart(64, '0')
                val callObj = JSONObject()
                    .put("to", USDT_ERC20_CONTRACT)
                    .put("data", "0x70a08231$cleanAddr")
                val usdtHex = ethRpcCall(rpc, "eth_call", JSONArray().put(callObj).put("latest"))
                if (usdtHex != null && usdtHex.startsWith("0x")) {
                    val units = parseHexBigInt(usdtHex)
                    val usdtVal = BigDecimal(units).divide(BigDecimal.TEN.pow(6), 4, RoundingMode.DOWN).toDouble()
                    db.updateChainBalance("USDT_ERC20", usdtVal)
                    rpcOk = true
                }
                if (rpcOk) break
            } catch (_: Throwable) {}
        }

        // Fetch recent on-chain ETH transactions via Blockscout v2 keyless REST API
        try {
            val raw = httpGet("https://eth.blockscout.com/api/v2/addresses/$ethAddress/transactions")
            if (raw != null) {
                val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
                for (i in 0 until minOf(items.length(), 15)) {
                    val it = items.getJSONObject(i)
                    val hash = it.optString("hash")
                    val fromH = it.optJSONObject("from")?.optString("hash").orEmpty()
                    val toH = it.optJSONObject("to")?.optString("hash").orEmpty()
                    val valWei = it.optString("value", "0").toBigIntegerOrNull() ?: BigInteger.ZERO
                    val amtEth = BigDecimal(valWei).divide(BigDecimal.TEN.pow(18), 6, RoundingMode.DOWN).toDouble()
                    if (amtEth <= 0.0) continue
                    val incoming = toH.equals(ethAddress, ignoreCase = true)
                    val peer = if (incoming) fromH else toH
                    val tsStr = it.optString("timestamp", "")
                    val tsMs = parseIsoTimestampMs(tsStr)
                    db.upsertWalletTransaction(
                        txHash = hash,
                        symbol = "ETH",
                        network = "Ethereum",
                        amount = amtEth,
                        counterpartyName = peer.ifBlank { "Ethereum" },
                        counterpartyAddress = peer,
                        isIncoming = incoming,
                        memo = "Ethereum Mainnet",
                        status = if (it.optString("status") == "ok") "Подтверждена" else "В сети",
                        viaAirMesh = false,
                        timestampMs = tsMs
                    )
                }
            }
        } catch (_: Throwable) {}

        return rpcOk
    }

    private fun syncTronAndTrc20(trxAddress: String): Boolean {
        try {
            val raw = httpGet("https://api.trongrid.io/v1/accounts/$trxAddress") ?: return false
            val dataArr = JSONObject(raw).optJSONArray("data") ?: return false
            if (dataArr.length() == 0) {
                // Unactivated new TRON account has 0.00 TRX & 0.00 USDT
                db.updateChainBalance("TRX", 0.0)
                db.updateChainBalance("USDT_TRC20", 0.0)
                return true
            }
            val acct = dataArr.getJSONObject(0)
            val trxSun = acct.optLong("balance", 0L)
            db.updateChainBalance("TRX", trxSun / 1_000_000.0)

            var usdtBal = 0.0
            val trc20Arr = acct.optJSONArray("trc20") ?: JSONArray()
            for (i in 0 until trc20Arr.length()) {
                val entry = trc20Arr.getJSONObject(i)
                if (entry.has(USDT_TRC20_CONTRACT)) {
                    val rawStr = entry.optString(USDT_TRC20_CONTRACT, "0")
                    val units = rawStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    usdtBal = units.divide(BigDecimal.TEN.pow(6), 4, RoundingMode.DOWN).toDouble()
                }
            }
            db.updateChainBalance("USDT_TRC20", usdtBal)

            // Fetch recent TRC-20 transfers
            val txRaw = httpGet("https://api.trongrid.io/v1/accounts/$trxAddress/transactions/trc20?limit=20")
            if (txRaw != null) {
                val txData = JSONObject(txRaw).optJSONArray("data") ?: JSONArray()
                for (i in 0 until txData.length()) {
                    val t = txData.getJSONObject(i)
                    val txId = t.optString("transaction_id")
                    val from = t.optString("from")
                    val to = t.optString("to")
                    val sym = t.optJSONObject("token_info")?.optString("symbol", "USDT") ?: "USDT"
                    val dec = t.optJSONObject("token_info")?.optInt("decimals", 6) ?: 6
                    val valDec = (t.optString("value", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO)
                        .divide(BigDecimal.TEN.pow(dec), 4, RoundingMode.DOWN).toDouble()
                    if (valDec <= 0.0) continue
                    val incoming = to.equals(trxAddress, ignoreCase = true)
                    val peer = if (incoming) from else to
                    db.upsertWalletTransaction(
                        txHash = txId,
                        symbol = sym,
                        network = "TRC-20",
                        amount = valDec,
                        counterpartyName = peer,
                        counterpartyAddress = peer,
                        isIncoming = incoming,
                        memo = "TRON TRC-20",
                        status = "Подтверждена",
                        viaAirMesh = false,
                        timestampMs = t.optLong("block_timestamp", System.currentTimeMillis())
                    )
                }
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    // =========================================================================
    // 2. REAL LOCAL SIGNING & ON-CHAIN TRANSACTION BROADCASTING
    // =========================================================================
    fun sendOnChainAsset(
        assetKey: String,
        toAddressInput: String,
        amount: Double,
        memo: String,
        callback: (success: Boolean, txHashOrError: String) -> Unit
    ) {
        val recipient = toAddressInput.trim()
        if (amount <= 0.0) {
            callback(false, "Введите корректную сумму перевода")
            return
        }

        netPool.execute {
            try {
                val me = db.getProfile()
                val chains = Secp256k1Wallet.deriveMultiChain(me.seedPhrase)
                val result: Pair<Boolean, String> = when (assetKey) {
                    "ETH" -> broadcastEthereumTransfer(chains.ethAddress, chains.ethPrivKeyHex, recipient, amount, isUsdt = false)
                    "USDT_ERC20" -> broadcastEthereumTransfer(chains.ethAddress, chains.ethPrivKeyHex, recipient, amount, isUsdt = true)
                    "TRX" -> broadcastTronTransfer(chains.trxAddress, chains.trxPrivKeyHex, recipient, amount, isUsdt = false)
                    "USDT_TRC20" -> broadcastTronTransfer(chains.trxAddress, chains.trxPrivKeyHex, recipient, amount, isUsdt = true)
                    "BTC" -> Pair(false, "Для отправки BTC требуется активный UTXO на адресе ${chains.btcAddress.take(14)}… Пополните адрес для расчёта входов SegWit.")
                    else -> Pair(false, "Неизвестная сеть актива")
                }

                if (result.first) {
                    val sym = if (assetKey.startsWith("USDT")) "USDT" else assetKey
                    val netName = when (assetKey) {
                        "USDT_TRC20" -> "TRC-20"
                        "USDT_ERC20" -> "ERC-20"
                        "TRX" -> "TRON"
                        else -> "Ethereum"
                    }
                    db.upsertWalletTransaction(
                        txHash = result.second,
                        symbol = sym,
                        network = netName,
                        amount = amount,
                        counterpartyName = recipient,
                        counterpartyAddress = recipient,
                        isIncoming = false,
                        memo = memo.ifBlank { "Ончейн-перевод $netName" },
                        status = "Отправлена в сеть",
                        viaAirMesh = false,
                        timestampMs = System.currentTimeMillis()
                    )
                    refreshAllChains(false)
                }

                mainHandler.post { callback(result.first, result.second) }
            } catch (t: Throwable) {
                mainHandler.post { callback(false, t.message ?: "Ошибка отправки транзакции") }
            }
        }
    }

    private fun broadcastEthereumTransfer(
        fromAddress: String,
        privKeyHex: String,
        toAddress: String,
        amount: Double,
        isUsdt: Boolean
    ): Pair<Boolean, String> {
        if (!Secp256k1Wallet.isValidEthAddress(toAddress)) {
            return false to "Некорректный адрес Ethereum (должен начинаться с 0x и содержать 40 hex-символов)"
        }
        val rpc = ETH_RPCS.first()
        val nonceHex = ethRpcCall(rpc, "eth_getTransactionCount", JSONArray().put(fromAddress).put("pending"))
            ?: return false to "Нет соединения с RPC-узлом Ethereum"
        val gasPriceHex = ethRpcCall(rpc, "eth_gasPrice", JSONArray())
            ?: return false to "Не удалось получить текущую цену газа Ethereum"

        val nonce = parseHexBigInt(nonceHex)
        val gasPrice = parseHexBigInt(gasPriceHex)

        val rawSignedHex = if (isUsdt) {
            val units = BigDecimal.valueOf(amount).multiply(BigDecimal.TEN.pow(6)).toBigInteger()
            val callData = Secp256k1Wallet.encodeErc20TransferData(toAddress, units)
            Secp256k1Wallet.signEvmLegacyTransaction(
                privKeyHex = privKeyHex,
                nonce = nonce,
                gasPriceWei = gasPrice,
                gasLimit = BigInteger.valueOf(68_000L),
                toAddressHex = USDT_ERC20_CONTRACT,
                valueWei = BigInteger.ZERO,
                data = callData,
                chainId = 1
            )
        } else {
            val wei = BigDecimal.valueOf(amount).multiply(BigDecimal.TEN.pow(18)).toBigInteger()
            Secp256k1Wallet.signEvmLegacyTransaction(
                privKeyHex = privKeyHex,
                nonce = nonce,
                gasPriceWei = gasPrice,
                gasLimit = BigInteger.valueOf(21_000L),
                toAddressHex = toAddress,
                valueWei = wei,
                data = ByteArray(0),
                chainId = 1
            )
        }

        val resp = ethRpcFullResponse(rpc, "eth_sendRawTransaction", JSONArray().put(rawSignedHex))
            ?: return false to "Узел Ethereum не ответил при отправке транзакции"
        val err = resp.optJSONObject("error")
        if (err != null) {
            return false to "Узел Ethereum отклонил транзакцию: ${err.optString("message", "insufficient funds")}"
        }
        val txHash = resp.optString("result", "")
        return if (txHash.startsWith("0x")) true to txHash else false to "Неизвестный ответ RPC"
    }

    private fun broadcastTronTransfer(
        fromAddress: String,
        privKeyHex: String,
        toAddress: String,
        amount: Double,
        isUsdt: Boolean
    ): Pair<Boolean, String> {
        if (!Secp256k1Wallet.isValidTronAddress(toAddress)) {
            return false to "Некорректный адрес TRON (должен начинаться с T и содержать 34 символа)"
        }

        val unsignedTxObj: JSONObject = if (!isUsdt) {
            val sun = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(1_000_000L)).toLong()
            val req = JSONObject()
                .put("owner_address", fromAddress)
                .put("to_address", toAddress)
                .put("amount", sun)
                .put("visible", true)
            val resStr = httpPostJson("https://api.trongrid.io/wallet/createtransaction", req.toString())
                ?: return false to "Нет соединения с сетью TRON (api.trongrid.io)"
            val j = JSONObject(resStr)
            if (j.has("Error")) return false to j.optString("Error")
            j
        } else {
            val to20 = decodeTronBase58To20Bytes(toAddress) ?: return false to "Ошибка декодирования адреса TRON"
            val units = BigDecimal.valueOf(amount).multiply(BigDecimal.TEN.pow(6)).toBigInteger()
            val paramBytes = ByteArray(64)
            System.arraycopy(to20, 0, paramBytes, 12, 20)
            System.arraycopy(Secp256k1Wallet.bigIntTo32Bytes(units), 0, paramBytes, 32, 32)

            val req = JSONObject()
                .put("owner_address", fromAddress)
                .put("contract_address", USDT_TRC20_CONTRACT)
                .put("function_selector", "transfer(address,uint256)")
                .put("parameter", Secp256k1Wallet.toHex(paramBytes))
                .put("fee_limit", 30_000_000L)
                .put("call_value", 0)
                .put("visible", true)
            val resStr = httpPostJson("https://api.trongrid.io/wallet/triggersmartcontract", req.toString())
                ?: return false to "Нет соединения с сетью TRON (api.trongrid.io)"
            val j = JSONObject(resStr)
            val tx = j.optJSONObject("transaction")
                ?: return false to (j.optJSONObject("result")?.optString("message") ?: "Недостаточно TRX/USDT для вызова контракта")
            tx
        }

        val txId = unsignedTxObj.optString("txID", "")
        if (txId.length != 64) {
            return false to (unsignedTxObj.optString("Error").ifBlank { "Недостаточно средств на балансе TRON" })
        }

        // Sign 32-byte txID locally with secp256k1: r (32B) || s (32B) || recoveryId (1B)
        val sig = Secp256k1Wallet.signHash32(privKeyHex, Secp256k1Wallet.fromHex(txId))
        val sigBytes = ByteArray(65)
        System.arraycopy(Secp256k1Wallet.bigIntTo32Bytes(sig.r), 0, sigBytes, 0, 32)
        System.arraycopy(Secp256k1Wallet.bigIntTo32Bytes(sig.s), 0, sigBytes, 32, 32)
        sigBytes[64] = sig.recoveryId.toByte()

        unsignedTxObj.put("signature", JSONArray().put(Secp256k1Wallet.toHex(sigBytes)))
        val bcastStr = httpPostJson("https://api.trongrid.io/wallet/broadcasttransaction", unsignedTxObj.toString())
            ?: return false to "Ошибка отправки подписанной транзакции в сеть TRON"
        val bcast = JSONObject(bcastStr)
        return if (bcast.optBoolean("result", false)) {
            true to txId
        } else {
            false to bcast.optString("message", "Транзакция отклонена сетью TRON")
        }
    }

    private fun decodeTronBase58To20Bytes(address: String): ByteArray? {
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58L)
        for (c in address) {
            val idx = BASE58_ALPHABET.indexOf(c)
            if (idx < 0) return null
            num = num.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        val raw = num.toByteArray()
        val stripped = if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        val full = ByteArray(25)
        if (stripped.size > 25) return null
        System.arraycopy(stripped, 0, full, 25 - stripped.size, stripped.size)
        if (full[0] != 0x41.toByte()) return null
        return full.copyOfRange(1, 21)
    }

    // =========================================================================
    // 3. HTTP / JSON-RPC TRANSPORT HELPERS
    // =========================================================================
    private fun parseIsoTimestampMs(iso: String): Long {
        if (iso.length < 19) return System.currentTimeMillis()
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(iso.take(19))?.time ?: System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun parseHexBigInt(hex: String): BigInteger {
        val clean = hex.removePrefix("0x").removePrefix("0X").trim()
        if (clean.isEmpty()) return BigInteger.ZERO
        return BigInteger(clean, 16)
    }

    private fun ethRpcCall(rpcUrl: String, method: String, params: JSONArray): String? {
        val obj = ethRpcFullResponse(rpcUrl, method, params) ?: return null
        return if (obj.has("result") && !obj.isNull("result")) obj.optString("result") else null
    }

    private fun ethRpcFullResponse(rpcUrl: String, method: String, params: JSONArray): JSONObject? {
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
        val raw = httpPostJson(rpcUrl, req.toString()) ?: return null
        return try { JSONObject(raw) } catch (_: Throwable) { null }
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 8_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TeronWallet/2.0")
        }
        return try {
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(urlStr: String, jsonBody: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 7_500
            readTimeout = 9_500
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TeronWallet/2.0")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(jsonBody)
                it.flush()
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }
}
