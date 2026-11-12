# قرارات معمارية — JARVIS

كل قرار هنا مع سببه والمقايضة التي يفرضها.

---

## D1 — Monorepo واحد

مستودع واحد يضم `android/` و`windows/` و`backend/` و`shared/`.

**السبب:** بروتوكول الاتصال بين الأجهزة مشترك. فصل المستودعات يعني إصدارات بروتوكول تتباعد بصمت.

**المقايضة:** سير البناء يجب أن يُقيّد بالمسارات (`paths:`) حتى لا يُبنى أندرويد عند تعديل ملف بايثون.

---

## D2 — الخادم على Cloudflare Workers + Durable Objects، لا FastAPI

المواصفة اقترحت Python + FastAPI. اخترتُ TypeScript على Workers.

**السبب:** Worker لا يستطيع تشغيل خادم FastAPI دائم — لا توجد عملية طويلة العمر ولا مقبس يستمع. والمواصفة نفسها تذكر هذا القيد. ما نحتاجه فعلًا هو:

- اتصال WebSocket يبقى مفتوحًا بين الهاتف والحاسوب،
- وحالة مخزّنة لكل "hub" (الأجهزة المقترنة، المهام، الذاكرة).

Durable Objects تعطي الاثنين: كائن واحد لكل hub، بداخله SQLite، مع WebSocket Hibernation فلا تُحتسب كلفة أثناء الخمول.

**المقايضة:** بايثون لغة أعرف لدى المالك، والمنطق الخادمي أصبح TypeScript. قُبِل لأن المنطق الخادمي رقيق مقصودًا.

**البديل لو رُفض:** FastAPI على خدمة تدعم العمليات الدائمة (Fly.io، Render، VPS)، والكلفة تصبح شهرية ثابتة بدل مجانية عند الخمول.

---

## D3 — مفتاح OpenRouter يبقى على الجهاز (BYOK)

الجهاز يرسل مفتاحه في ترويسة `X-OpenRouter-Key`، والخادم يمرّره إلى OpenRouter ولا يخزّنه ولا يسجّله.

**السبب:** يحقق شرط المواصفة "لا تضع مفتاحًا في الكود" دون أن يتحول الخادم إلى حاملٍ لمفتاح المالك. ولو سُرّب الخادم لا يُسرَّب المفتاح.

**المقايضة:** كل جهاز يحتاج إدخال المفتاح مرة واحدة. مقبول لأن الاستخدام شخصي.

---

## D4 — رمز الإقران: 6 أرقام + معرّف hub، صالح 5 دقائق

**السبب:** رمز قصير يُقرأ صوتيًا أو يُكتب يدويًا. ربطه بمعرّف hub يمنع تخمين رمز جهاز شخص آخر.

**الحماية:** الرمز يُخزَّن مُهشَّرًا (SHA-256)، ويُستهلك مرة واحدة، و5 محاولات فاشلة تقفل الإقران 10 دقائق.

---

## D5 — edge-tts كمحرّك نطق أولي فقط

الصوت الافتراضي `ar-DZ-IsmaelNeural` مع `pitch=-15Hz` و`rate=-8%`.

**السبب:** أفضل صوت عربي ذكوري متاح مجانًا، ولهجته جزائرية.

**القيد الصريح:** `edge-tts` يعتمد على واجهة غير رسمية لخدمة Microsoft. قد تتوقف دون إشعار، وليست مرخّصة للاستخدام التجاري. لذلك محرّك النطق خلف واجهة `TtsProvider` قابلة للاستبدال دون إعادة كتابة التطبيق.

---

## D6 — الأوامر الحتمية لا تمرّ على نموذج ذكاء

"افتح يوتيوب"، "ارفع الصوت"، "أوقف الموسيقى" — يتعامل معها موجّه نوايا محلي.

**السبب:** شرط المواصفة في تقليل استهلاك الرموز. استدعاء نموذج لفتح تطبيق هو هدر وتأخير بلا مقابل.

**المقايضة:** الموجّه المحلي يحتاج صيانة لكل أمر جديد. ولا يُستدعى النموذج إلا عند الغموض أو التخطيط.

---

## D7 — لا بناء APK داخل بيئة التطوير السحابية

**السبب:** لا يوجد Android SDK في البيئة، و`maven.google.com` و`dl.google.com` محجوبان عنها. اختُبر الاتصال وفشل بـ 403.

**النتيجة:** بناء APK يحدث في GitHub Actions فقط. لن يُعلن عن أي بناء ناجح إلا بعد تأكيد Actions.

---

## D8 — ديبڨرام للسمع فقط، والنطق بمحرّكاتنا

التعرّف على الكلام عبر Deepgram Nova-3 بثًّا مباشرًا. النطق **لا** يمرّ عليه.

**السبب — تحقّقتُ من وثائقه:** Nova-3 يغطّي العربية جيدًا، وقائمة لغاته تشمل `ar-DZ`. لكن أصوات Aura وAura-2 وFlux تغطّي الإنجليزية والإسبانية والألمانية والفرنسية والهولندية والإيطالية واليابانية **فقط**، والعربية ليست مطروحة في خارطة الطريق المعلنة.

**النتيجة:** لو استُعمل Voice Agent كاملًا (سمع + نموذج + نطق في وصلة واحدة) لردّ جارفيس بالإنجليزية. فبقي النطق على Azure وPiper ومحرّك الهاتف.

**المقايضة:** فقدنا النموذج المُدار من ديبڨرام، وبقيت حلقة الأدوات على OpenRouter. وهي تعمل ومختبَرة، فالخسارة محدودة.

**ما قد يغيّر هذا:** يدعم Voice Agent نطقًا خارجيًا عبر نقطة مخصّصة. فلو وُجّهت إلى خادمنا لعاد النموذج المُدار مع صوت عربي. أُجّل حتى يعمل المسار الأول، لأن عقد النقطة المخصّصة لا يمكن اختباره من بيئة التطوير.

---

## D9 — مُلتقط صوت واحد لا اثنان

`AudioCapture` يملك `AudioRecord` وحيدًا ويوزّع إطاراته على مقياس الدائرة وعلى التعرّف معًا.

**السبب:** أندرويد يعطي التطبيق مجرى التقاط واحدًا عمليًا. ولمّا كان لكلٍّ مُسجِّله الخاص، كان الثاني يحصل على صمت أو يفشل في الفتح. وهذه علّة كانت ستظهر فقط على جهاز حقيقي.

**القرار المرافق:** عند امتلاء المخزن تُسقَط أقدم الإطارات لا أحدثها، فلا يُعطِّل مستهلكٌ بطيء المُسجِّل ويتأخّر التعرّف عن المتحدّث.

---

## D10 — الرموز المؤقّتة لا المفتاح

مفتاح ديبڨرام يبقى سرًّا في الخادم، والجهاز يطلب رمزًا من `/v1/auth/grant` عند كل جلسة.

**السبب:** مفتاح داخل APK هو مفتاح مكشوف لمن يفكّ الحزمة. الرمز المؤقّت ينتهي خلال دقائق، فتسريبه يكلّف قليلًا.

**TTL:** عشر دقائق. الافتراضي عند ديبڨرام ثلاثون ثانية وهي ضيّقة لهاتف قد يقفل شاشته بين الطلب والاتصال؛ وأقصى ما يسمح به ساعة. والخادم **يقصّ** ما يطلبه الجهاز، فلا يستطيع جهاز طلب رمز ليوم كامل.

## D11 — Screen automation is wired to the agent, not just declared

The accessibility service existed since phase 1 but was never connected to
the tool dispatcher, so "open YouTube and play the first result" opened
YouTube and stopped. Intents reach an app's front door and no further.

`ScreenTools.kt` exposes eight tools over it: read_screen, find_on_screen,
wait_for_screen, tap, scroll_screen, type_text, press_key, and
open_accessibility_settings. The service gained the actions they need —
gesture dispatch for taps that Compose and Flutter surfaces do not expose
as clickable nodes, ACTION_SET_TEXT for typing, scroll actions with a swipe
fallback, and the global back/home/recents actions.

Two constraints shaped it:

- **The service is off until the user turns it on by hand, and it stays
  that way.** The app cannot enable it, and does not pretend to. Every tool
  returns a failure naming the settings path, and the agent prompt tells
  the model to offer that page once and then stop rather than retrying.
- **Looking costs tool calls.** The model is instructed to read the screen
  before tapping and after every action, because the alternative is tapping
  text it guessed at. That raised the ceiling from 14 calls to 24; a single
  UI chain spends eight or nine before it has done anything.

Password fields are skipped in every traversal, and the service config
requests no key-event filtering — reading what is on screen on request is
the capability, not input capture.

## D12 — Streaming synthesis, because the wait was four waits

Speech was: POST the whole sentence, wait for the whole audio, write it to
a cache file, let MediaPlayer decode and prepare it, then play. Sentence
chunking helped the first word arrive sooner but every chunk still paid all
four costs.

Both cloud voices now stream into `AudioTrack` through a shared `PcmPlayer`:

- **Deepgram** over its synthesis WebSocket (`wss://.../v1/speak`, `/v2/speak`
  for Flux), which emits raw PCM as it is generated. The socket is held open
  between sentences so the TLS handshake is not in front of each one.
- **Azure** over the REST endpoint it already used, asking for
  `raw-24khz-16bit-mono-pcm` instead of MP3. The body was always chunked;
  MP3 was the only reason it had to land on disk first, because MediaPlayer
  needs a seekable source. Raw PCM needs no decoder and no seeking.

Time to first sound stops scaling with sentence length. Both file-based
providers stay in the chain behind their streaming siblings, so a blocked
WebSocket or a flaky connection still speaks.

Three details that are easy to get wrong and were:

- The flush acknowledgement arrives on OkHttp's reader thread. Signalling it
  with a channel rather than a continuation avoids resuming a coroutine from
  inside a lock, where the resumed code goes straight on to block on the
  speaker.
- The audio channel is unbounded. Pacing belongs at the blocking write into
  AudioTrack; bounding the channel would mean either stalling the socket
  thread or retrying off-thread, and the latter can reorder frames.
- Playback has to drain before the track is released, or the last word of
  every sentence is cut off. `playbackHeadPosition` gives the real
  remainder, so it is a bounded wait rather than a guessed sleep.

The orb's level now comes from the actual samples being written, measured in
`PcmPlayer.levelOf`, rather than from an animation standing in for one.


## D13 — The home screen shows a 3D head instead of the orb

The orb is replaced on Android by `JarvisAvatar`: a rigged male head that
dissolves below the neck, looks at the finger, opens its mouth with the real
voice level, nods on success and shakes on error. It takes the same three
inputs the orb took (`state`, `amplitude`, `reducedMotion`), so nothing above
it changed.

It is a WebGL page rather than native 3D because the scene already existed as
Three.js and a WebView costs one small dependency (`androidx.webkit`) instead
of a rendering engine. The page is bundled into `assets/jarvis` and served by
`WebViewAssetLoader`; every other request gets an empty response, so the view
cannot reach the network and the app stays usable offline.

- Source and build: `android/avatar-web` (`npm install && npm run build`).
  The built files are committed, so CI needs no Node step.
- The page API is `Jarvis.setState(name)` with the lowercase `AssistantState`
  names, and `Jarvis.setLevel(0..1)`.
- `JarvisOrb` is kept as the fallback for devices without a WebView and for
  reduced motion.
- The head and the animation clips are the sample models from the three.js
  examples (a Ready Player Me avatar and Mixamo motion). They are fine for
  development; swap `models/jarvis.glb` for an avatar you own before a public
  release.

## D14 — Groq is the default brain; OpenRouter is free-models-only

The app now talks to two providers through the one chat client, chosen in
settings: **Groq** (default) and **OpenRouter**. Both speak the OpenAI
chat-completions dialect, so only the base URL, the key and the model list
differ (`ModelProvider`). Each provider keeps its own key and its own chosen
model.

OpenRouter is limited to free models: the `:free` variants and the
`openrouter/free` router. This is enforced in three places so it cannot be
bypassed by accident — the picker lists only free models, the settings store
ignores a paid id (including one saved by an older build), and the client
refuses a paid id before sending anything. The old default,
`anthropic/claude-3.5-haiku`, was a paid model.

Web search moved with it. It used OpenRouter's `:online` plugin on
`gpt-4o-mini`, which bills per search; it now uses Groq's server-side
`browser_search` tool on `openai/gpt-oss-20b`, so it needs the Groq key.

Not verified: whether Groq's free tier includes `browser_search`, and how its
rate limits feel in a long tool chain. Neither could be tested without a key.
