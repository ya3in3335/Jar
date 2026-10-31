"""Speech layer: the parts that can be checked without a sound card."""

import pytest

from jarvis_win.core.stt import SttProvider, Transcript, pcm_to_wav
from jarvis_win.core.stt import pick_provider as pick_stt
from jarvis_win.core.tts import (
    AzureTtsProvider,
    TtsProvider,
    Voice,
    build_ssml,
    escape_xml,
    pitch_to_multiplier,
    rate_to_multiplier,
    rate_to_sapi_words,
)
from jarvis_win.core.tts import pick_provider as pick_tts


class FakeStt(SttProvider):
    def __init__(self, ident: str, available: bool):
        self.id = ident
        self._available = available

    def is_available(self) -> bool:
        return self._available

    def transcribe(self, pcm: bytes, sample_rate: int = 16000) -> Transcript:
        return Transcript(text="نص")


class FakeTts(TtsProvider):
    def __init__(self, ident: str, available: bool):
        self.id = ident
        self._available = available

    def is_available(self) -> bool:
        return self._available

    def voices(self):
        return []

    def speak(self, text, voice, pitch, rate):
        pass

    def stop(self):
        pass


class TestProviderSelection:
    def test_preferred_provider_wins_when_available(self):
        chosen = pick_stt([FakeStt("whisper", True), FakeStt("azure", True)], "azure")
        assert chosen.id == "azure"

    def test_falls_back_when_the_preferred_one_cannot_run(self):
        chosen = pick_stt([FakeStt("whisper", True), FakeStt("azure", False)], "azure")
        assert chosen.id == "whisper"

    def test_returns_nothing_when_none_are_available(self):
        assert pick_stt([FakeStt("whisper", False)], None) is None

    def test_same_rule_for_speech_output(self):
        chosen = pick_tts([FakeTts("sapi5", False), FakeTts("azure", True)], "sapi5")
        assert chosen.id == "azure"


class TestTranscript:
    def test_blank_text_counts_as_empty(self):
        assert Transcript(text="   ").is_empty

    def test_confidence_is_absent_rather_than_invented(self):
        # None means "not measured", which the UI must distinguish from 0.
        assert Transcript(text="x").confidence is None


class TestWavWrapping:
    def test_produces_a_valid_wav_header(self):
        wav = pcm_to_wav(b"\x00\x01" * 1600, 16000)
        assert wav[:4] == b"RIFF"
        assert wav[8:12] == b"WAVE"

    def test_carries_the_payload(self):
        pcm = b"\x00\x01" * 1600
        assert len(pcm_to_wav(pcm, 16000)) > len(pcm)

    def test_sample_rate_is_written_into_the_header(self):
        import io
        import wave

        with wave.open(io.BytesIO(pcm_to_wav(b"\x00\x01" * 100, 16000))) as handle:
            assert handle.getframerate() == 16000
            assert handle.getnchannels() == 1
            assert handle.getsampwidth() == 2


class TestProsodyConversion:
    @pytest.mark.parametrize(
        "pitch,expected",
        [("-15Hz", 0.85), ("+0Hz", 1.0), ("0Hz", 1.0), ("+20Hz", 1.2)],
    )
    def test_pitch_maps_onto_a_multiplier(self, pitch, expected):
        assert pitch_to_multiplier(pitch) == pytest.approx(expected)

    @pytest.mark.parametrize("rate,expected", [("-8%", 0.92), ("+0%", 1.0), ("+50%", 1.5)])
    def test_rate_maps_onto_a_multiplier(self, rate, expected):
        assert rate_to_multiplier(rate) == pytest.approx(expected)

    def test_values_are_clamped_to_a_usable_range(self):
        assert pitch_to_multiplier("-500Hz") == 0.5
        assert rate_to_multiplier("+900%") == 2.0

    def test_nonsense_falls_back_to_neutral(self):
        assert pitch_to_multiplier("loud") == 1.0
        assert rate_to_multiplier("") == 1.0

    def test_sapi_words_per_minute_stays_in_range(self):
        assert rate_to_sapi_words("-8%") == 184
        assert 80 <= rate_to_sapi_words("-900%") <= 400
        assert 80 <= rate_to_sapi_words("+900%") <= 400


class TestSsml:
    def test_wraps_voice_and_prosody(self):
        ssml = build_ssml("مرحبا", "ar-DZ-IsmaelNeural", "-15Hz", "-8%")
        assert '<voice name="ar-DZ-IsmaelNeural">' in ssml
        assert '<prosody pitch="-15Hz" rate="-8%">' in ssml
        assert "مرحبا" in ssml

    def test_locale_comes_from_the_voice_id(self):
        assert 'xml:lang="ar-SA"' in build_ssml("x", "ar-SA-HamedNeural", "0Hz", "0%")

    def test_injected_markup_cannot_escape(self):
        ssml = build_ssml('</prosody></voice><voice name="evil">x', "ar-DZ-IsmaelNeural", "0Hz", "0%")
        assert ssml.count("<voice name=") == 1
        assert "&lt;/prosody&gt;" in ssml

    def test_ampersand_is_escaped(self):
        assert "&amp;" in escape_xml("سؤال & جواب")

    def test_arabic_is_left_alone(self):
        assert escape_xml("مرحبا بك") == "مرحبا بك"


class TestVoiceCatalogue:
    def test_azure_offers_the_algerian_male_voice_first(self):
        provider = AzureTtsProvider("k", "francecentral")
        assert provider.voices()[0].id == "ar-DZ-IsmaelNeural"

    def test_azure_is_unavailable_without_credentials(self):
        assert not AzureTtsProvider(None, None).is_available()
        assert not AzureTtsProvider("key", None).is_available()

    def test_every_catalogue_voice_is_arabic_and_named(self):
        for voice in AzureTtsProvider("k", "r").voices():
            assert voice.is_arabic
            assert voice.name.strip()
            assert voice.locale

    def test_display_includes_the_locale(self):
        assert "(ar-DZ)" in Voice("x", "إسماعيل", "ar-DZ").display

    def test_both_genders_are_offered(self):
        genders = {v.gender for v in AzureTtsProvider("k", "r").voices()}
        assert genders == {"Male", "Female"}
