# CANDLE AI backend

FastAPI service that accepts a chart screenshot and returns a cautious Arabic technical-analysis summary from Gemini. The existing `POST /v1/chart/analyze` response remains `{ "analysis": "..." }`; `platform` and `image_base64` remain supported. Optional `symbol` and `timeframe` fields provide context, but the model is instructed not to treat them as independently verified chart facts.

## Run locally

```bash
cd ai-server
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export GEMINI_API_KEY="your-key"
# Optional: export GEMINI_MODEL="a-supported-gemini-model"
uvicorn main:app --host 0.0.0.0 --port 8000
```

Configure `GEMINI_API_KEY` as a server-side secret in the hosting provider. Never embed it in the Android app or send it to clients. `GET /health` reports whether a key is configured without exposing its value.

## Analyze a chart

Send a JPEG, PNG, or WebP as a base64 string, optionally as a data URI:

```json
{
  "platform": "TradingView",
  "symbol": "EURUSD",
  "timeframe": "15m",
  "image_base64": "data:image/png;base64,..."
}
```

The API validates the encoded size, base64, declared MIME type, and image signature. It applies an in-process limit of 8 requests per client IP per minute and an 8 MiB decoded image limit. The Gemini call runs in a worker thread to keep FastAPI's event loop responsive. The limit is per process: deployments with multiple workers/instances should use a shared rate-limit store and configure trusted proxy handling at the hosting layer.

For continuous/background analysis in a client, capture or obtain the chart off the UI thread, submit it to this endpoint, and update the UI only when a response arrives. Avoid submitting duplicate screenshots continuously; debounce updates and cancel or ignore stale results when the symbol/timeframe changes. The backend does not place trades. Analysis is uncertain and should not be treated as financial advice or a guarantee.
