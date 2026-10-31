# J.A.R.V.I.S

مساعد ذكي عربي لأندرويد وويندوز: صوت عربي ذكوري، أوامر محلية بلا استهلاك رموز، وربط بين الهاتف والحاسوب.

> **اقرأ [`docs/BUILD_STATUS.md`](docs/BUILD_STATUS.md) أولًا.** يفصّل ما تحقّقتُ منه فعلًا وما لم يُبنَ بعد. لا شيء في هذا المستودع يوهم بوظيفة غير موجودة.

## الحالة في سطر واحد

الخادم مبني ومختبَر (27/27)، تطبيق ويندوز مبني ومختبَر (65/65)، تطبيق أندرويد مكتوب ولم يُصرَّف بعد لعدم توفّر Android SDK في بيئة التطوير.

## البنية

```
JARVIS/
├── backend/   Cloudflare Worker + Durable Objects (TypeScript)
├── android/   Kotlin + Jetpack Compose + Hilt
├── windows/   Python + PySide6
├── shared/    مخطّطات البروتوكول المشترك
└── docs/      المعمارية، القرارات، حالة البناء، التقدّم
```

## التشغيل

### الخادم

```bash
cd backend
npm install          # .npmrc يضبط legacy-peer-deps، مطلوب لحلّ تبعيات wrangler
npm test             # 27 اختبارًا داخل workerd
npm run typecheck
npm run dev          # محليًا على 8787
```

للنشر: ضع `CLOUDFLARE_API_TOKEN` كـ Secret في المستودع ودع `backend.yml` ينشر من `main`، أو انشر من حاسوبك بـ `npx wrangler deploy`.

مفتاح محرّك النطق لا يُكتب في ملف:

```bash
npx wrangler secret put VOICE_API_KEY
```

### ويندوز

```bash
cd windows
python -m venv .venv && .venv/Scripts/activate
pip install -r requirements-dev.txt
python -m pytest tests -q
python -m jarvis_win
```

### أندرويد

```bash
cd android
gradle wrapper --gradle-version 8.11.1   # مرّة واحدة، لتوليد ./gradlew
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

لا يوجد `gradlew` في المستودع: لم أستطع توليد `gradle-wrapper.jar` دون شبكة، ولن أُضيف ملفًا ثنائيًا لم أولّده. سير البناء في GitHub Actions يثبّت Gradle مباشرة.

## الأسرار

لا يوجد أي مفتاح في هذا المستودع، ولن يوجد. `.gitignore` يحجب `*.env` و`.dev.vars` و`*.keystore` و`local.properties`.

| السرّ | مكانه |
|---|---|
| مفتاح OpenRouter | على الجهاز: Android Keystore، أو مخزن اعتماد ويندوز |
| رمز الجهاز | نفس المكان؛ وفي الخادم مُهشَّرًا فقط |
| `CLOUDFLARE_API_TOKEN` | GitHub Secret |
| `VOICE_API_KEY` | `wrangler secret put` |

## الصوت

الافتراضي `ar-DZ-IsmaelNeural` بـ `pitch=-15Hz` و`rate=-8%`.

`edge-tts` يعتمد على واجهة غير رسمية لخدمة Microsoft: قد تتوقّف دون إشعار وهي غير مرخّصة تجاريًا. لذلك المحرّك خلف واجهة قابلة للاستبدال. وأنماط النطق (هادئ، جادّ، ودّي…) تُطبَّق بالـ prosody فقط، والخادم يصرّح بذلك في ترويسة `x-voice-style-applied` بدل الإيهام بتعبير عاطفي حقيقي.

## الخطوات التالية

1. رابط مستودع GitHub حتى يبدأ البناء الآلي وتُعرَف حالة تصريف أندرويد.
2. فيديو الواجهة المرجعي — المرحلة السابعة موقوفة عليه.
3. المرحلة الثالثة: التعرّف على الكلام والنطق العربي.
