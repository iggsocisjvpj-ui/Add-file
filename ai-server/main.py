import asyncio
import base64
import binascii
import os
import time
from collections import defaultdict, deque
from typing import Optional

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool
from google import genai
from google.genai import types

app = FastAPI(title="CANDLE AI Gemini Backend", version="2.0.0")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3.8-flash")
MAX_IMAGE_BYTES = 8 * 1024 * 1024
RATE_LIMIT = 8
RATE_WINDOW_SECONDS = 60

# This in-memory limiter is per process. Use a shared store (for example Redis)
# if the service is deployed with multiple workers or instances.
request_times = defaultdict(deque)
rate_limit_lock = asyncio.Lock()


class ChartRequest(BaseModel):
    platform: str = Field(default="Unknown", max_length=80)
    symbol: Optional[str] = Field(default=None, max_length=32)
    timeframe: Optional[str] = Field(default=None, max_length=32)
    image_base64: str = Field(min_length=100, max_length=12_000_000)


def _image_signature_matches(image_bytes: bytes, mime_type: str) -> bool:
    if mime_type == "image/jpeg":
        return image_bytes.startswith(b"\xff\xd8\xff")
    if mime_type == "image/png":
        return image_bytes.startswith(b"\x89PNG\r\n\x1a\n")
    if mime_type == "image/webp":
        return (
            len(image_bytes) >= 12
            and image_bytes.startswith(b"RIFF")
            and image_bytes[8:12] == b"WEBP"
        )
    return False


def _decode_chart_image(image_value: str) -> tuple[bytes, str]:
    raw = image_value.strip()
    mime_type = "image/jpeg"

    if raw.startswith("data:"):
        try:
            header, raw = raw.split(",", 1)
            mime_type = header.split(";", 1)[0].split(":", 1)[1].lower()
        except (ValueError, IndexError):
            raise HTTPException(status_code=400, detail="Invalid image data URI")

    if mime_type not in ("image/jpeg", "image/png", "image/webp"):
        raise HTTPException(status_code=415, detail="Unsupported image format")

    # Reject oversized payloads before allocating the decoded image buffer.
    if len(raw) > ((MAX_IMAGE_BYTES + 2) // 3) * 4 + 4:
        raise HTTPException(status_code=413, detail="Image is too large")

    try:
        image_bytes = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="Invalid Base64 image")

    if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image too large or empty")
    if not _image_signature_matches(image_bytes, mime_type):
        raise HTTPException(status_code=400, detail="Image content does not match its format")

    return image_bytes, mime_type


async def _check_rate_limit(client_ip: str) -> None:
    now = time.monotonic()
    async with rate_limit_lock:
        bucket = request_times[client_ip]
        while bucket and now - bucket[0] >= RATE_WINDOW_SECONDS:
            bucket.popleft()
        if len(bucket) >= RATE_LIMIT:
            raise HTTPException(status_code=429, detail="Too many requests; try again shortly")
        bucket.append(now)

        # Keep stale IP entries from accumulating indefinitely on a public service.
        if len(request_times) > 10_000:
            expired = [
                ip for ip, timestamps in request_times.items()
                if not timestamps or now - timestamps[-1] >= RATE_WINDOW_SECONDS
            ]
            for ip in expired:
                request_times.pop(ip, None)


def _generate_analysis(prompt: str, image_bytes: bytes, mime_type: str) -> str:
    client = genai.Client(api_key=GEMINI_API_KEY)
    response = client.models.generate_content(
        model=GEMINI_MODEL,
        contents=[
            types.Part.from_text(text=prompt),
            types.Part.from_bytes(data=image_bytes, mime_type=mime_type),
        ],
        config=types.GenerateContentConfig(
            temperature=0.15,
            max_output_tokens=1600,
        ),
    )
    return response.text or ""


@app.get("/")
def root():
    return {"service": "CANDLE AI", "status": "running"}


@app.get("/health")
def health():
    return {"ok": True, "api_key_configured": bool(GEMINI_API_KEY)}


@app.post("/v1/chart/analyze")
async def analyze_chart(payload: ChartRequest, request: Request):
    client_ip = request.client.host if request.client else "unknown"
    await _check_rate_limit(client_ip)

    if not GEMINI_API_KEY:
        raise HTTPException(status_code=503, detail="Gemini API key is not configured")

    image_bytes, mime_type = _decode_chart_image(payload.image_base64)
    chart_context = [f"المنصة: {payload.platform.strip() or 'غير معروفة'}"]
    if payload.symbol:
        chart_context.append(f"الرمز الذي أدخله المستخدم: {payload.symbol.strip()}")
    if payload.timeframe:
        chart_context.append(f"الإطار الزمني الذي أدخله المستخدم: {payload.timeframe.strip()}")

    prompt = f"""
أنت مساعد حذر لتحليل الرسوم البيانية الفنية باللغة العربية. افحص الصورة نفسها، ولا تعتبر بيانات السياق التالية حقائق مؤكدة عن الرسم:
{chr(10).join(chart_context)}

حلّل ما يمكن قراءته بصرياً فقط. اذكر بوضوح إن كانت الصورة غير واضحة أو أن الأسعار/المحاور/المؤشرات لا يمكن قراءتها. لا تستنتج بيانات حجم أو قيم مؤشرات غير ظاهرة، ولا تدّعِ وجود بيانات لحظية أو تحقق خارجي.

اكتب الإجابة بالعربية وبالعناوين التالية:
الاتجاه وبنية السوق:
إشارة السيناريو الفني: BUY / SELL / WAIT (سيناريو تعليمي مشروط، وليس توصية شخصية أو أمراً للتنفيذ)
قوة الدليل: ضعيفة / متوسطة / قوية (ليست احتمالاً إحصائياً)
قراءة الشموع والزخم:
مناطق الدعم والمقاومة: اذكرها كمناطق تقريبية فقط إذا كان مقياس السعر مقروءاً، وإلا فصرّح بذلك.
المؤشرات الظاهرة:
الأدلة المؤيدة والمعارضة:
ما الذي يبطل السيناريو أو يستدعي الانتظار:
المخاطر وحدود التحليل:

قواعد الدقة والسلامة:
- افصل بين الملاحظة المرئية والاستنتاج؛ لا تختلق أسعاراً أو مستويات رقمية.
- لا تصف نتيجة بأنها مؤكدة، ولا تعد بأرباح، ولا تقدّم حجم صفقة أو توجيهاً شخصياً.
- لا تستخدم إلا BUY أو SELL أو WAIT في خانة السيناريو؛ اختر WAIT عند نقص الوضوح أو تعارض الإشارات.
- لا تنفذ صفقات ولا تطلب بيانات اعتماد أو مفاتيح من المستخدم.
""".strip()

    try:
        # Gemini's synchronous client runs off the event loop so other API
        # requests remain responsive while chart analysis is in progress.
        analysis = await run_in_threadpool(
            _generate_analysis, prompt, image_bytes, mime_type
        )
        if not analysis.strip():
            raise HTTPException(status_code=502, detail="Gemini returned an empty response")
        return {"analysis": analysis.strip()}
    except HTTPException:
        raise
    except Exception as exc:
        # Avoid returning provider details, prompts, or credentials to clients.
        print(f"Gemini request failed: {type(exc).__name__}")
        raise HTTPException(
            status_code=502,
            detail="Gemini analysis failed. Check server logs.",
        ) from exc
