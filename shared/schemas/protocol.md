# بروتوكول JARVIS — v1

المرجع الواحد لما تتبادله الأجهزة والخادم. أي تغيير هنا يعني تغييرًا في `android/` و`windows/` و`backend/` معًا.

## رمز الجهاز

```
jv1.<hubId>.<deviceId>.<secret>
     12 حرفًا  10 أحرف   40-60 حرفًا
```

أبجدية `hubId` و`deviceId`: `abcdefghijkmnpqrstuvwxyz23456789` — بلا `l` و`o` و`0` و`1`، لأن الرمز يُقرأ صوتيًا أو يُكتب يدويًا. الخادم يخزّن `SHA-256(secret)` فقط. العمر 90 يومًا.

## رمز الإقران

```
<hubId>-<6 أرقام>
```

مُهشَّر في الخادم، يُستهلك مرّة واحدة، صالح 300 ثانية. خمس محاولات فاشلة تقفل الإقران عشر دقائق.

## شكل الخطأ

كل خطأ من الخادم بهذا الشكل، فتستطيع الواجهة ترجمته إلى رسالة عربية واحدة:

```json
{
  "error": { "code": "device_offline", "message": "...", "status": 409,
             "retryAfterSeconds": 42 },
  "requestId": "uuid"
}
```

الرموز المستعملة: `unauthorized`، `token_expired`، `invalid_pairing_code`، `pairing_locked`، `device_not_found`، `device_offline`، `device_limit`، `rate_limited`، `validation_error`، `invalid_json`، `unsupported_media_type`، `payload_too_large`، `missing_provider_key`، `voice_not_configured`، `voice_timeout`، `memory_full`، `not_found`، `internal_error`.

## رسائل WebSocket

من الخادم إلى الجهاز:

| `type` | الحمولة | المعنى |
|---|---|---|
| `hello` | `deviceId`, `devices[]` | أول رسالة بعد الاتصال |
| `presence` | `deviceId`, `online` | جهاز آخر ظهر أو غاب |
| `task` | `task` | مهمة موجّهة لهذا الجهاز |
| `task_update` | `task` | تحديث لمهمة أرسلها هذا الجهاز |
| `device_paired` | `deviceId` | جهاز جديد انضمّ إلى الـ hub |
| `pong` | `t` | ردّ على `ping` |

من الجهاز إلى الخادم: `{"type":"ping"}` فقط. الحدّ 16 كيلوبايت للرسالة.

## المهمة

```json
{
  "id": "abc123def456",
  "origin": "<deviceId>",
  "target": "<deviceId>",
  "kind": "instruction" | "open_conversation",
  "payload": { "instruction": "افتح هذا الرابط في الكمبيوتر" },
  "status": "delivered" | "accepted" | "running" | "completed"
          | "partial" | "failed" | "rejected",
  "result": { "summary": "...", "evidence": "..." } | null
}
```

المهمة **لا تُطابَر**: إن كان الجهاز الهدف غير متصل يعود الخادم بـ `409 device_offline` فورًا، بدلًا من إيهام المستخدم بأن الأمر في الطريق.

## الحالات التسع

`idle`، `listening`، `understanding`، `thinking`، `speaking`، `executing`، `success`، `error`، `disconnected`.

`idle` و`error` و`disconnected` قابلة للوصول من كل حالة. وصول `idle` من أي مكان ليس تسهيلًا بل شرط: الإلغاء يجب أن يعمل دائمًا.

## الصوت

```json
{ "text": "...", "voice": "ar-DZ-IsmaelNeural",
  "pitch": "-15Hz", "rate": "-8%", "style": "calm" }
```

الحدود: 3000 حرفًا، `pitch` بين ‎-50Hz و+50Hz، `rate` بين ‎-50% و+100%. الأنماط: `neutral`، `calm`، `serious`، `friendly`، `excited`، `concerned` — وتُطبَّق بالـ prosody فقط، والردّ يحمل `x-voice-style-applied: prosody-only`.

## الأدوات

أسماء الأدوات: حروف صغيرة وأرقام وشرطة سفلية، 60 حرفًا كحدّ أقصى، و100 أداة لكل جهاز. كل جهاز يعلن ما يستطيعه فعلًا عند الإقران، فالخادم لا يوجّه مهمة إلى جهاز لا يملك أداتها.
