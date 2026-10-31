import pytest

from jarvis_win.core.vad import EndpointConfig, Endpointer, Event, State, frame_size

FRAME = 20


def feed(ep: Endpointer, pattern: str) -> list[Event]:
    """'s' is a speech frame, '.' is silence. Returns non-idle events."""
    events = []
    for ch in pattern:
        event = ep.push(ch == "s")
        if event is not Event.NOTHING:
            events.append(event)
    return events


@pytest.fixture
def ep():
    return Endpointer(
        EndpointConfig(
            endpoint_silence_ms=100,  # 5 frames
            min_speech_ms=60,  # 3 frames
            max_utterance_ms=1000,  # 50 frames
            start_frames=3,
        ),
        frame_ms=FRAME,
    )


class TestStart:
    def test_silence_alone_starts_nothing(self, ep):
        assert feed(ep, "." * 20) == []
        assert ep.state is State.IDLE

    def test_one_stray_frame_does_not_start_speech(self, ep):
        assert feed(ep, ".s.s.s.") == []

    def test_three_consecutive_frames_start_speech(self, ep):
        assert feed(ep, "sss") == [Event.SPEECH_STARTED]
        assert ep.state is State.SPEAKING

    def test_interrupted_run_restarts_the_count(self, ep):
        assert feed(ep, "ss.ss") == []
        assert ep.state is State.IDLE


class TestEnd:
    def test_speech_then_enough_silence_ends_the_utterance(self, ep):
        events = feed(ep, "sss" + "s" * 10 + "." * 5)
        assert events == [Event.SPEECH_STARTED, Event.SPEECH_ENDED]

    def test_short_silence_does_not_end_it(self, ep):
        events = feed(ep, "sss" + "s" * 10 + "..")
        assert events == [Event.SPEECH_STARTED]
        assert ep.state is State.TRAILING

    def test_a_pause_mid_sentence_is_forgiven(self, ep):
        # Silence, then more speech: still one utterance, not two.
        events = feed(ep, "sss" + "s" * 5 + "..." + "s" * 5 + "." * 5)
        assert events == [Event.SPEECH_STARTED, Event.SPEECH_ENDED]

    def test_silence_counter_resets_after_a_pause(self, ep):
        ep_events = feed(ep, "sss" + "ssss" + "...." + "s" + "....")
        # The four silent frames before the single speech frame must not
        # add to the four after it, or the endpoint fires too early.
        assert Event.SPEECH_ENDED not in ep_events

    def test_returns_to_idle_after_ending(self, ep):
        feed(ep, "sss" + "s" * 10 + "." * 5)
        assert ep.state is State.IDLE

    def test_a_second_utterance_is_detected(self, ep):
        events = feed(ep, "sss" + "s" * 5 + "." * 5 + "sss" + "s" * 5 + "." * 5)
        assert events.count(Event.SPEECH_STARTED) == 2
        assert events.count(Event.SPEECH_ENDED) == 2


class TestTooShort:
    def test_a_cough_is_discarded(self, ep):
        # min_speech is 3 frames; start_frames already counts 3, so a bare
        # trigger with nothing after it sits exactly at the boundary.
        short = Endpointer(
            EndpointConfig(endpoint_silence_ms=100, min_speech_ms=200, max_utterance_ms=1000, start_frames=3),
            frame_ms=FRAME,
        )
        events = feed(short, "sss" + "." * 5)
        assert events == [Event.SPEECH_STARTED, Event.TOO_SHORT]

    def test_too_short_also_returns_to_idle(self, ep):
        short = Endpointer(
            EndpointConfig(endpoint_silence_ms=100, min_speech_ms=200, max_utterance_ms=1000, start_frames=3),
            frame_ms=FRAME,
        )
        feed(short, "sss" + "." * 5)
        assert short.state is State.IDLE


class TestMaxDuration:
    def test_endless_speech_is_cut(self, ep):
        events = feed(ep, "s" * 60)
        assert Event.MAX_DURATION in events

    def test_cap_counts_trailing_silence_too(self, ep):
        # A quiet speaker must not be able to hold the session open.
        events = feed(ep, "sss" + "s" * 20 + "." * 2 + "s" + "." * 2 + "s" * 30)
        assert Event.MAX_DURATION in events

    def test_state_resets_at_the_moment_the_cap_fires(self, ep):
        event = Event.NOTHING
        while event is not Event.MAX_DURATION:
            event = ep.push(True)
        assert ep.state is State.IDLE
        assert ep.speech_ms == 0

    def test_speech_continuing_past_the_cap_starts_a_new_utterance(self, ep):
        # The speaker has not stopped, so the next frames are a fresh
        # utterance rather than dead air.
        events = feed(ep, "s" * 60)
        assert events.count(Event.MAX_DURATION) == 1
        assert events[-1] is Event.SPEECH_STARTED
        assert ep.state is State.SPEAKING


class TestAccounting:
    def test_speech_time_includes_the_starting_frames(self, ep):
        feed(ep, "sss")
        assert ep.speech_ms == 3 * FRAME

    def test_speech_time_accumulates(self, ep):
        feed(ep, "sss" + "ss")
        assert ep.speech_ms == 5 * FRAME

    def test_reset_clears_everything(self, ep):
        feed(ep, "sssss")
        ep.reset()
        assert ep.state is State.IDLE
        assert ep.speech_ms == 0


class TestFrameSize:
    @pytest.mark.parametrize("ms,expected", [(10, 160), (20, 320), (30, 480)])
    def test_frame_sizes_webrtcvad_accepts(self, ms, expected):
        assert frame_size(ms, 16_000) == expected


# --------------------------------------------------- speech detection


import math
import struct

from jarvis_win.core.vad import EnergyDetector, make_detector


def tone(amplitude: int, samples: int = 320, freq: float = 200.0, rate: int = 16000) -> bytes:
    """A sine frame at a given amplitude, as 16-bit mono PCM."""
    values = [
        int(amplitude * math.sin(2 * math.pi * freq * i / rate))
        for i in range(samples)
    ]
    return struct.pack(f"<{samples}h", *values)


SILENCE = tone(5)
QUIET_ROOM = tone(60)
SPEECH = tone(6000)


class TestEnergyDetector:
    def test_first_frame_only_calibrates(self):
        det = EnergyDetector()
        assert det.is_speech(SPEECH) is False

    def test_loud_frame_after_quiet_ones_is_speech(self):
        det = EnergyDetector()
        for _ in range(5):
            det.is_speech(QUIET_ROOM)
        assert det.is_speech(SPEECH) is True

    def test_room_noise_is_not_speech(self):
        det = EnergyDetector()
        results = [det.is_speech(QUIET_ROOM) for _ in range(40)]
        assert not any(results)

    def test_noise_floor_adapts_to_a_louder_room(self):
        det = EnergyDetector()
        for _ in range(5):
            det.is_speech(SILENCE)
        quiet_floor = det.noise_floor_db
        for _ in range(200):
            det.is_speech(QUIET_ROOM)
        assert det.noise_floor_db > quiet_floor

    def test_sustained_speech_does_not_drag_the_floor_up(self):
        # If the floor adapted during speech, a long sentence would stop
        # registering partway through.
        det = EnergyDetector()
        for _ in range(5):
            det.is_speech(QUIET_ROOM)
        before = det.noise_floor_db
        results = [det.is_speech(SPEECH) for _ in range(100)]
        assert all(results)
        assert det.noise_floor_db == before

    def test_empty_frame_is_not_speech(self):
        assert EnergyDetector().is_speech(b"") is False

    def test_reset_clears_calibration(self):
        det = EnergyDetector()
        det.is_speech(QUIET_ROOM)
        det.reset()
        assert det.noise_floor_db == det.floor_db

    def test_detector_drives_the_endpointer_end_to_end(self):
        det = EnergyDetector()
        ep = Endpointer(
            EndpointConfig(endpoint_silence_ms=100, min_speech_ms=60, max_utterance_ms=2000, start_frames=3),
            frame_ms=FRAME,
        )
        events = []
        for frame in [QUIET_ROOM] * 5 + [SPEECH] * 15 + [QUIET_ROOM] * 8:
            event = ep.push(det.is_speech(frame))
            if event is not Event.NOTHING:
                events.append(event)
        assert Event.SPEECH_STARTED in events
        assert Event.SPEECH_ENDED in events


class TestDetectorSelection:
    def test_a_detector_is_always_available(self):
        # Packaging must never leave the app with no way to hear.
        assert make_detector().name in {"webrtc", "energy"}
