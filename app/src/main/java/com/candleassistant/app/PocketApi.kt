package com.candle.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * عميل Pocket Option (الديمو فقط) لجلب الأسعار المباشرة والشموع التاريخية.
 * البروتوكول مأخوذ من مكتبة pocket_option (غير رسمية): Socket.IO v4 عبر WebSocket.
 *
 * ملاحظات أمان:
 * - الاتصال مقيّد بخادم الديمو فقط.
 * - الـ session يحفظ محلياً على الهاتف فقط (SharedPreferences)، ولا يوضع في الكود أو المستودع.
 */
class PocketApi(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onAuthorized()
        fun onPrice(asset: String, timestampSec: Double, value: Double)
        fun onHistory(asset: String, periodSec: Int, candles: List<PriceCandle>)
        fun onStatus(message: String)
    }

    data class PriceCandle(
        val time: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double
    )

    companion object {
        const val DEMO_URL = "wss://demo-api-eu.po.market/socket.io/?EIO=4&transport=websocket"
        const val ORIGIN = "https://m.pocketoption.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val PREF = "po_api"
        private const val PING_MS = 60_000L

        fun saveDemoCredentials(c: Context, session: String, uid: Long) {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("session", session.trim())
                .putLong("uid", uid)
                .apply()
        }

        fun hasCredentials(c: Context): Boolean {
            val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return !p.getString("session", "").isNullOrBlank() && p.getLong("uid", 0L) > 0L
        }

        fun clearCredentials(c: Context) {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    private val http = OkHttpClient.Builder().build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    @Volatile private var authorized = false
    private val subscriptions = LinkedHashMap<String, Int>() // asset -> period sec

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (authorized) emit("ps", null)
            main.postDelayed(this, PING_MS)
        }
    }

    fun isAuthorized(): Boolean = authorized

    fun connect() {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val session = p.getString("session", "") ?: ""
        val uid = p.getLong("uid", 0L)
        if (session.isBlank() || uid <= 0L) {
            listener.onStatus("أدخل session و uid للديمو أولاً")
            return
        }
        if (!DEMO_URL.contains("demo")) {
            listener.onStatus("تم منع الاتصال: الرابط ليس خادم ديمو")
            return
        }

        val req = Request.Builder()
            .url(DEMO_URL)
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .build()

        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text, session, uid)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                authorized = false
                main.removeCallbacks(pingRunnable)
                listener.onStatus("انقطع الاتصال: ${t.message ?: "خطأ غير معروف"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                authorized = false
                main.removeCallbacks(pingRunnable)
                listener.onStatus("أُغلق الاتصال ($code)")
            }
        })
    }

    fun disconnect() {
        main.removeCallbacks(pingRunnable)
        authorized = false
        ws?.close(1000, "bye")
        ws = null
    }

    /** يشترك في أصل معين، ويبدأ البث المباشر للأسعار */
    fun subscribe(asset: String, periodSec: Int) {
        subscriptions[asset] = periodSec
        if (authorized) sendSubscribe(asset, periodSec)
    }

    /** يطلب شموعاً تاريخية تنتهي عند الزمن المحدد (بالثواني) */
    fun requestHistory(asset: String, periodSec: Int, endTimeSec: Double, offset: Int = 1000) {
        val body = JSONObject()
            .put("asset", asset)
            .put("index", JSONObject.NULL)
            .put("time", endTimeSec)
            .put("offset", offset)
            .put("period", periodSec)
        emit("loadHistoryPeriod", body)
    }

    private fun sendSubscribe(asset: String, periodSec: Int) {
        emit("subscribeSymbol", asset)
        emit("changeSymbol", JSONObject().put("asset", asset).put("period", periodSec))
    }

    /** Socket.IO: الرسالة تبدأ بـ 42 متبوعة بمصفوفة [اسم الحدث، البيانات] */
    private fun emit(event: String, data: Any?) {
        val arr = JSONArray().put(event)
        if (data != null) arr.put(data)
        ws?.send("42" + arr.toString())
    }

    private fun handleFrame(text: String, session: String, uid: Long) {
        when {
            text.startsWith("0") -> ws?.send("40")                       // engine.io open: اتصل بالنطاق "/"
            text == "2" -> ws?.send("3")                                  // ping -> pong
            text.startsWith("40") -> sendAuth(session, uid)               // متصل بالنطاق: أرسل التوثيق
            text.startsWith("42") -> handleEvent(text.substring(2))
        }
    }

    private fun sendAuth(session: String, uid: Long) {
        val auth = JSONObject()
            .put("session", session)
            .put("isDemo", 1)
            .put("uid", uid)
            .put("platform", 2)
            .put("isFastHistory", true)
            .put("isOptimized", true)
        emit("auth", auth)
    }

    private fun handleEvent(json: String) {
        val arr = try { JSONArray(json) } catch (e: Exception) { return }
        val name = arr.optString(0)
        val payload = arr.opt(1)

        when (name) {
            "successauth" -> {
                authorized = true
                listener.onAuthorized()
                main.removeCallbacks(pingRunnable)
                main.postDelayed(pingRunnable, PING_MS)
                subscriptions.forEach { (asset, period) -> sendSubscribe(asset, period) }
            }
            "updateStream" -> parsePrices(payload)
            "loadHistoryPeriodFast" -> parseHistory(payload)
        }
    }

    /** payload: قائمة عناصر، كل عنصر كائن {asset,timestamp,value} أو مصفوفة [asset,timestamp,value] */
    private fun parsePrices(payload: Any?) {
        val list = payload as? JSONArray ?: return
        for (i in 0 until list.length()) {
            val item = list.opt(i)
            when (item) {
                is JSONObject -> listener.onPrice(
                    item.optString("asset"),
                    item.optDouble("timestamp"),
                    item.optDouble("value")
                )
                is JSONArray -> listener.onPrice(
                    item.optString(0),
                    item.optDouble(1),
                    item.optDouble(2)
                )
            }
        }
    }

    private fun parseHistory(payload: Any?) {
        val obj = payload as? JSONObject ?: return
        val asset = obj.optString("asset")
        val period = obj.optInt("period")
        val data = obj.optJSONArray("data") ?: return
        val out = ArrayList<PriceCandle>(data.length())
        for (i in 0 until data.length()) {
            val it = data.optJSONObject(i) ?: continue
            out.add(
                PriceCandle(
                    time = it.optLong("time"),
                    open = it.optDouble("open"),
                    high = it.optDouble("high"),
                    low = it.optDouble("low"),
                    close = it.optDouble("close"),
                    volume = it.optDouble("volume")
                )
            )
        }
        out.sortBy { it.time }
        listener.onHistory(asset, period, out)
    }
}
