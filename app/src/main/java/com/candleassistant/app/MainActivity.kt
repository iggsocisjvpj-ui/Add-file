package com.candleassistant.app

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.candleassistant.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // Apps Script took ~285 seconds in the user's test; allow time for the
    // response to travel back after server-side processing completes.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(360, TimeUnit.SECONDS)
        .build()
    private var selectedChartUri: Uri? = null

    private val chartPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        selectedChartUri = uri
        binding.chartPreview.setImageURI(uri)
        binding.statusText.text = "تم اختيار الشارت. اضغط تحليل لإرساله إلى Gemini."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectChartButton.setOnClickListener { chartPicker.launch("image/*") }
        binding.analyzeButton.setOnClickListener {
            val uri = selectedChartUri
            if (uri == null) {
                Toast.makeText(this, "اختر صورة الشارت أولاً", Toast.LENGTH_SHORT).show()
            } else {
                analyzeChart(uri)
            }
        }
    }

    private fun analyzeChart(uri: Uri) {
        // Read UI fields on the main thread; never access views from Dispatchers.IO.
        val endpoint = binding.serverInput.text?.toString()?.trim().orEmpty().trimEnd('/')
        val symbol = binding.symbolInput.text?.toString()?.trim().orEmpty()
        val timeframe = binding.timeframeInput.text?.toString()?.trim().orEmpty()

        if (!endpoint.startsWith("https://")) {
            binding.serverInput.error = "أدخل رابط HTTPS صالحاً"
            return
        }

        val requestUrl = when {
            endpoint.endsWith("/exec") -> endpoint
            endpoint.contains("script.google.com/macros/s/") -> {
                binding.serverInput.error = "رابط Apps Script يجب أن ينتهي بـ /exec"
                return
            }
            else -> "$endpoint/v1/chart/analyze"
        }

        binding.analyzeButton.isEnabled = false
        binding.statusText.text = "جارٍ إرسال الصورة وتحليلها في الخلفية…"
        binding.analysisText.text = "قد يستغرق التحليل عدة دقائق؛ اترك التطبيق مفتوحاً حتى تظهر النتيجة."

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val imageBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("تعذرت قراءة ملف الصورة")
                    if (imageBytes.isEmpty()) throw IOException("ملف الصورة فارغ")
                    if (imageBytes.size > 8 * 1024 * 1024) {
                        throw IOException("حجم الصورة يتجاوز 8 ميغابايت؛ قصّها أو قلّل دقتها ثم أعد المحاولة")
                    }

                    val mimeType = contentResolver.getType(uri)
                        ?.lowercase()
                        ?.takeIf { it in setOf("image/jpeg", "image/png", "image/webp") }
                        ?: throw IOException("الصيغ المدعومة: JPEG أو PNG أو WebP")

                    val encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    val payload = JSONObject().apply {
                        put("platform", "Android")
                        put("symbol", symbol)
                        put("timeframe", timeframe)
                        put("image_base64", "data:$mimeType;base64,$encoded")
                    }

                    val request = Request.Builder()
                        .url(requestUrl)
                        .header("Accept", "application/json, text/plain, */*")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val detail = runCatching {
                                val json = JSONObject(responseBody)
                                json.optString("error").ifBlank {
                                    json.optString("detail").ifBlank { responseBody }
                                }
                            }.getOrDefault(responseBody)
                            throw IOException("HTTP ${response.code}: ${detail.take(700)}")
                        }
                        parseAnalysis(responseBody)
                    }
                }
            }

            binding.analyzeButton.isEnabled = true
            outcome.onSuccess { analysis ->
                binding.statusText.text = "اكتمل التحليل"
                binding.analysisText.text = analysis
            }.onFailure { error ->
                binding.statusText.text = "تعذّر إكمال التحليل"
                binding.analysisText.text = explainError(error)
            }
        }
    }

    private fun parseAnalysis(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) throw IOException("الخادم أعاد استجابة فارغة")

        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json != null) {
            val error = json.optString("error").trim()
            if (error.isNotEmpty()) throw IOException(error)

            for (key in listOf("analysis", "text", "result", "output")) {
                val value = json.optString(key).trim()
                if (value.isNotEmpty()) return value
            }
            throw IOException("وصل رد JSON لكن لا يحتوي على حقل analysis. تحقق من استجابة Apps Script.")
        }

        if (text.startsWith("<!DOCTYPE", ignoreCase = true) || text.startsWith("<html", ignoreCase = true)) {
            throw IOException("وصلت صفحة HTML بدلاً من التحليل. تحقق من نشر Web App ورابط /exec.")
        }
        return text
    }

    private fun explainError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("timeout", ignoreCase = true) ->
                "انتهت مهلة الانتظار. جرّب صورة أصغر، وافحص Apps Script → Executions لمعرفة مدة التنفيذ.\n\n$message"
            message.contains("401") || message.contains("403") ->
                "رفض Apps Script الوصول. راجع إعدادات نشر Web App ورابط /exec.\n\n$message"
            message.contains("Unable to resolve host", ignoreCase = true) || message.contains("Failed to connect", ignoreCase = true) ->
                "تعذر الاتصال. تحقق من الإنترنت والرابط المنشور.\n\n$message"
            else -> message.ifBlank { "حدث خطأ غير معروف أثناء التحليل." }
        }
    }
}
