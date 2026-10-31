import pytest

from jarvis_win.core.intent import Intent, normalize_ar, route


class TestNormalise:
    def test_strips_diacritics(self):
        assert normalize_ar("اِفْتَحْ") == "افتح"

    def test_folds_alef_variants(self):
        assert normalize_ar("أحمد") == normalize_ar("احمد")

    def test_converts_arabic_digits(self):
        assert normalize_ar("١٢٣٤٥٦") == "123456"

    def test_collapses_whitespace(self):
        assert normalize_ar("  افتح    يوتيوب  ") == "افتح يوتيوب"


class TestOpenApp:
    @pytest.mark.parametrize(
        "utterance,app",
        [
            ("افتح يوتيوب", "youtube"),
            ("افتح اليوتيوب", "youtube"),
            ("شغل الموسيقى", "music"),
            ("افتح الإعدادات", "settings"),
            ("ادخل لإنستغرام", "instagram"),
            ("افتح الكاميرا", "camera"),
            ("open chrome", "browser"),
        ],
    )
    def test_known_apps(self, utterance, app):
        intent = route(utterance)
        assert intent is not None, utterance
        assert intent.tool in {"open_application", "play_media"}
        if intent.tool == "open_application":
            assert intent.args["app"] == app

    def test_unknown_app_defers_to_model(self):
        assert route("افتح تطبيق المحاسبة الخاص بالشركة") is None

    def test_bare_url(self):
        intent = route("افتح https://example.com")
        assert intent == Intent("open_url", {"url": "https://example.com"})


class TestVolume:
    def test_up(self):
        intent = route("ارفع صوت الهاتف")
        assert intent is not None and intent.tool == "control_volume"
        assert intent.args["direction"] == "up"

    def test_down(self):
        intent = route("خفض الصوت")
        assert intent is not None and intent.args["direction"] == "down"

    def test_mute(self):
        intent = route("اكتم الصوت")
        assert intent is not None and intent.args["direction"] == "mute"


class TestMedia:
    def test_pause(self):
        intent = route("أوقف الموسيقى")
        assert intent is not None and intent.tool == "pause_media"

    def test_play(self):
        intent = route("شغل الموسيقى")
        assert intent is not None and intent.tool == "play_media"


class TestScreen:
    @pytest.mark.parametrize(
        "utterance",
        ["شوف واش ظاهر في الشاشة", "حلل الشاشة", "انظر الى الشاشة"],
    )
    def test_analyze(self, utterance):
        intent = route(utterance)
        assert intent is not None and intent.tool == "analyze_screen"


class TestSearch:
    def test_web_search(self):
        intent = route("ابحث عن سعر الذهب")
        assert intent is not None and intent.tool == "search_web"
        assert intent.args["query"] == "سعر الذهب"

    def test_search_inside_app(self):
        intent = route("افتح يوتيوب وابحث عن فيديو لتعلم بايثون")
        assert intent is not None and intent.tool == "open_application"
        assert intent.args["app"] == "youtube"
        assert "بايثون" in str(intent.args["search"])


class TestDeferral:
    @pytest.mark.parametrize(
        "utterance",
        [
            "",
            "   ",
            "لماذا السماء زرقاء",
            "قارن بين هاتين الصفقتين واخبرني أيهما أفضل",
            "اكتب لي رسالة لصاحب المتجر",
        ],
    )
    def test_ambiguous_goes_to_model(self, utterance):
        assert route(utterance) is None
