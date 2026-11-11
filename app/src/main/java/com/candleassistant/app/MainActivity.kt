import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private var selectedChartUri: Uri? = null

    private val chartPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        selectedChartUri = uri
        binding.chartPreview.setImageURI(uri)
        binding.statusText.text = "Chart loaded. AI analysis will run in the background."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectChartButton.setOnClickListener {
            chartPicker.launch("image/*")
        }

        binding.analyzeButton.setOnClickListener {
            val uri = selectedChartUri
            if (uri == null) {
                Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            analyzeChartInBackground(uri)
        }
    }

    private fun analyzeChartInBackground(uri: Uri) {
        binding.statusText.text = "Running background AI analysis…"
        binding.analysisText.text = "Sending chart image to the model…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open image stream")

                    val bytes = inputStream.readBytes()
                    inputStream.close()

                    val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val payload = JSONObject().apply {
                        put("platform", "Android")
                        put("symbol", binding.symbolInput.text?.toString()?.trim().orEmpty())
                        put("timeframe", binding.timeframeInput.text?.toString()?.trim().orEmpty())
                        put("image_base64", "data:$mimeType;base64,$encoded")
                    }

                    val url = binding.serverInput.text?.toString()?.trim()
                        ?.ifBlank { "https://your-render-url.example" }
                        ?: "https://your-render-url.example"

                    val request = Request.Builder()
                        .url(if (url.endsWith("/")) "$url/v1/chart/analyze" else "$url/v1/chart/analyze")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        throw IOException("Server error: ${response.code} - $body")
                    }

                    val json = JSONObject(body)
                    json.optString("analysis", body)
                }.getOrElse { it.message ?: "Unknown error" }
            }

            binding.statusText.text = "Background analysis finished"
            binding.analysisText.text = result
        }
    }
}
