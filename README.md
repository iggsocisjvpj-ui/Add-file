# CANDLE AI Android App

This repository now includes a minimal Android app module under `app/` that connects to your AI backend service for chart analysis in the background.

## Project structure

- `app/src/main/java/com/candleassistant/app/MainActivity.kt` – image picker + background analysis logic
- `app/src/main/res/layout/activity_main.xml` – dark dashboard UI
- `ai-server/main.py` – Python FastAPI backend that accepts chart images and returns a Gemini-powered analysis

## How to use

1. Start the Python AI backend:
   ```bash
   cd ai-server
   python -m venv .venv
   . .venv/bin/activate
   pip install -r requirements.txt
   export GEMINI_API_KEY="your-gemini-key"
   uvicorn main:app --host 0.0.0.0 --port 8000
   ```
2. Deploy that backend to Render or any public host, then paste the public URL in the Android app.
3. Open the app, choose a chart screenshot, and tap Analyze.
4. The request runs in a background coroutine so the UI stays responsive.

## Important

The AI model should be treated as an analytical assistant, not a guaranteed trading signal. It inspects the visible chart only and must not be used as financial advice.
