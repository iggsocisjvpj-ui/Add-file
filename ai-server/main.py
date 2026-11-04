import base64
import binascii
import os
import time
from collections import defaultdict, deque
from typing import Deque

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from openai import OpenAI

app = FastAPI(title="CANDLE AI Backend", version="1.0.0")

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-5.5").strip()
MAX_IMAGE_BYTES = 8 * 1024 * 1024
RATE_LIMIT = 8  # requests per IP per minute; basic in-memory protection
_request_times: dict[str, Deque[float]] = defaultdict(deque)

class ChartRequest(BaseModel):
    platform: str = Field(default="Unknown", max_length=80)
    image_base64: str = Field(min_length=1)

@app.get("/")
def root():
    return {"service": "CANDLE AI", "status": "running", "endpoint": "/v1/chart/analyze"}

@app.get("/health")
def health():
    return {"ok": True, "api_key_configured": bool(OPENAI_API_KEY)}

@app.post("/v1/chart/analyze")
def analyze_chart(payload: ChartRequest, request: Request):
    now = time.time()
    ip = request.client.host if request.client else "unknown"
    bucket = _request_times[ip]
    while bucket and now - bucket[0] > 60:
        bucket.popleft()
    if len(bucket) >= RATE_LIMIT:
        raise HTTPException(status_code=429, detail="طلبات كثيرة. انتظر دقيقة ثم حاول مجدداً.")
    bucket.append(now)

    if not OPENAI_API_KEY:
        raise HTTPException(status_code=503, detail="الخادم لم يضبط OPENAI_API_KEY بعد")

    raw = payload.image_base64.strip()
    if raw.startswith("data:") and "," in raw:
        raw = raw.split(",", 1)[1]
    try:
        image_bytes = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail="صيغة الصورة غير صحيحة")
    if not image_bytes:
        raise HTTPException(status_code=400, detail="الصورة فارغة")
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="حجم الصورة أكبر من 8 ميجابايت")

    # The Android client currently sends JPEG bytes.
    image_data_url = "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode("ascii")
    prompt = f"""حلّل صورة الرسم البياني المرفقة باللغة العربية بصفتك مساعداً تعليمياً لتحليل الشموع.
المنصة/المصدر الذي ذكره التطبيق: {payload.platform}

قواعد صارمة:
- اذكر أولاً ما إذا كانت الصورة واضحة وما إذا كان الإطار الزمني والزوج ظاهرين. لا تخمّن ما لا يظهر.
- صف الاتجاه العام وبنية القمم والقيعان والشموع ومستويات الدعم/المقاومة المرئية فقط.
- ميّز بين الملاحظة المؤكدة والاحتمال. لا تخترع قيم RSI أو EMA أو حجم تداول أو بيانات Footprint غير ظاهرة.
- إن لم تكن الصورة كافية، فالنتيجة WAIT / انتظار، واطلب صورة أوضح.
- أعطِ سيناريو صعود وسيناريو هبوط، وما الذي يبطل كل سيناريو إن أمكن.
- لا تعطِ ضماناً للربح ولا تعرض نسبة نجاح متوقعة. لا تعتبر التحليل توصية مالية.
- لا تنفذ صفقات ولا تطلب بيانات دخول أو مفاتيح المنصة.

استخدم هذا التنسيق المختصر:
1) جودة الصورة والبيانات
2) الاتجاه المرئي
3) أهم المستويات والأنماط
4) السيناريو الأقرب: صعود / هبوط / انتظار، مع السبب
5) ما الذي يجب تأكيده قبل أي قرار يدوي
"""
    try:
        client = OpenAI(api_key=OPENAI_API_KEY, timeout=45.0, max_retries=1)
        response = client.responses.create(
            model=OPENAI_MODEL,
            instructions="أنت محلل رسوم بيانية حذر. الدقة والشفافية أهم من إعطاء إشارة. لا تدّعِ رؤية معلومات غير موجودة في الصورة.",
            input=[{
                "role": "user",
                "content": [
                    {"type": "input_text", "text": prompt},
                    {"type": "input_image", "image_url": image_data_url, "detail": "high"},
                ],
            }],
        )
        analysis = (response.output_text or "").strip()
        if not analysis:
            raise HTTPException(status_code=502, detail="لم يرجع نموذج الذكاء الاصطناعي نصاً")
        return {"analysis": analysis}
    except HTTPException:
        raise
    except Exception as exc:
        # Do not expose provider credentials or raw exception details to the app.
        print(f"OpenAI request failed: {type(exc).__name__}")
        raise HTTPException(status_code=502, detail="تعذر إكمال التحليل السحابي. تحقق من إعدادات API والرصيد ثم حاول مجدداً.")
