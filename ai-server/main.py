
import os
import time
import base64
import binascii
from collections import defaultdict, deque

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from google import genai
from google.genai import types

app = FastAPI(title="CANDLE AI Gemini Backend")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3.8-flash")

MAX_IMAGE_BYTES = 8 * 1024 * 1024
RATE_LIMIT = 8
RATE_WINDOW = 60
request_times = defaultdict(deque)


class ChartRequest(BaseModel):
    platform: str = "Unknown"
    image_base64: str = Field(min_length=100)


@app.get("/")
def root():
    return {"service": "CANDLE AI", "status": "running"}


@app.get("/health")
def health():
    return {
        "ok": True,
        "api_key_configured": bool(GEMINI_API_KEY)
    }


@app.post("/v1/chart/analyze")
def analyze_chart(payload: ChartRequest, request: Request):
    ip = request.client.host if request.client else "unknown"
    now = time.time()
    bucket = request_times[ip]

    while bucket and now - bucket[0] > RATE_WINDOW:
        bucket.popleft()

    if len(bucket) >= RATE_LIMIT:
        raise HTTPException(status_code=429, detail="Too many requests")

    bucket.append(now)

    if not GEMINI_API_KEY:
        raise HTTPException(
            status_code=503,
            detail="Gemini API key is not configured"
        )

    raw = payload.image_base64.strip()

    mime_type = "image/jpeg"

    if raw.startswith("data:"):
        try:
            header, raw = raw.split(",", 1)
            mime_type = header.split(";")[0].split(":")[1]
        except (ValueError, IndexError):
            raise HTTPException(status_code=400, detail="Invalid image data")

    if mime_type not in ("image/jpeg", "image/png", "image/webp"):
        raise HTTPException(status_code=415, detail="Unsupported image format")

    try:
        image_bytes = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="Invalid Base64 image")

    if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image too large or empty")

    prompt = f"""
You are CANDLE AI, a careful technical chart-analysis assistant.

Platform: {payload.platform}

Analyze the supplied trading chart image.

Identify only what is actually visible:
- Market direction and structure.
- Candle patterns and price action.
- Support and resistance zones.
- Trend strength and momentum.
- Visible indicators and their signals.
- Possible entry direction: BUY, SELL, or WAIT.

Return your answer in clear Arabic using this structure:

الاتجاه:
إشارة التحليل: BUY / SELL / WAIT
قوة الإشارة: ضعيفة / متوسطة / قوية
تحليل الشموع:
الدعم والمقاومة:
المؤشرات الظاهرة:
أسباب القرار:
المخاطر:
متى يجب الانتظار أو إلغاء الإشارة:

Important rules:
- Never invent indicator values or volume data.
- If the chart is unclear, say so and choose WAIT.
- Do not claim guaranteed profits or certainty.
- This is analysis only, never execute trades.
"""

    try:
        client = genai.Client(api_key=GEMINI_API_KEY)

        response = client.models.generate_content(
            model=GEMINI_MODEL,
            contents=[
                types.Part.from_text(text=prompt),
                types.Part.from_bytes(
                    data=image_bytes,
                    mime_type=mime_type
                )
            ],
            config=types.GenerateContentConfig(
                temperature=0.2,
                max_output_tokens=1200
            )
        )

        analysis = response.text

        if not analysis:
            raise HTTPException(
                status_code=502,
                detail="Gemini returned an empty response"
            )

        return {"analysis": analysis}

    except HTTPException:
        raise
    except Exception as exc:
        print(f"Gemini request failed: {type(exc).__name__}: {exc}")
        raise HTTPException(
            status_code=502,
            detail="Gemini analysis failed. Check server logs."
        )
