import io
import math

import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient

import sibilia_core as core


def make_voice(tmp_path, name, transcript="Ciao, sono una voce di prova.", seconds=4.0):
    voices = tmp_path / "voices"
    voices.mkdir(exist_ok=True)
    sf.write(voices / f"{name}.wav", np.zeros(int(seconds * core.SAMPLE_RATE), dtype=np.float32), core.SAMPLE_RATE)
    (voices / f"{name}.txt").write_text(transcript, encoding="utf-8")
    return voices


def test_max_frames_formula():
    assert core.max_frames("a" * 300) == math.ceil(300 / 14 * 12.5 * 1.5) + 50
    assert core.max_frames("") == 50


@pytest.mark.parametrize("name,ok", [("anna", True), ("marco2", True), ("Anna", False), ("an-na", False), ("../x", False), ("", False)])
def test_valid_voice_name(name, ok):
    assert core.valid_voice_name(name) is ok


def test_list_voices_has_built_in_and_complete_references_only(tmp_path):
    voices = make_voice(tmp_path, "anna")
    (voices / "orphan.wav").write_bytes(b"x")  # no transcript: ignored
    (voices / "Bad.wav").write_bytes(b"x")
    (voices / "Bad.txt").write_text("x")      # invalid name: ignored
    assert set(core.list_voices(voices)) == {"sibilia", "sibilia-anna"}
    assert core.list_voices(tmp_path / "missing") == {"sibilia": {"kind": "built-in"}}


def test_resolve_voice(tmp_path):
    voices = make_voice(tmp_path, "anna", transcript="  Trascrizione.  ")
    assert core.resolve_voice("sibilia", voices) is None
    clip, transcript = core.resolve_voice("sibilia-anna", voices)
    assert clip.name == "anna.wav" and transcript == "Trascrizione."
    for bad in ("sibilia-marco", "sibilia-../anna", "sibilia-", "paola", "sibilia-Anna"):
        with pytest.raises(core.UnknownVoice):
            core.resolve_voice(bad, voices)


def test_conversation_shapes():
    assert core.conversation("Ciao.", None) == [{"role": "0", "content": [{"type": "text", "text": "Ciao."}]}]
    audio = np.zeros(10, dtype=np.float32)
    conv = core.conversation("Ciao.", (audio, "Riferimento."))
    assert conv[0]["content"][0] == {"type": "text", "text": "Riferimento."}
    assert conv[0]["content"][1]["type"] == "audio" and conv[0]["content"][1]["audio"] is audio
    assert conv[1] == {"role": "0", "content": [{"type": "text", "text": "Ciao."}]}


def test_wav_bytes_is_24khz_mono_16bit():
    data, sr = sf.read(io.BytesIO(core.wav_bytes(np.zeros(2400, dtype=np.float32))))
    info = sf.info(io.BytesIO(core.wav_bytes(np.zeros(2400, dtype=np.float32))))
    assert sr == 24000 and info.channels == 1 and info.subtype == "PCM_16" and len(data) == 2400


@pytest.fixture
def client(tmp_path):
    calls = []

    def synthesize(text, voice):
        calls.append((text, voice))
        if text == "boom":
            raise core.GenerationRambled("hit the frame cap")
        return core.wav_bytes(np.zeros(240, dtype=np.float32))

    app = core.create_web_app(synthesize, make_voice(tmp_path, "anna"))
    c = TestClient(app)
    c.calls = calls
    return c


def test_voices_endpoint(client):
    assert set(client.get("/voices").json()) == {"sibilia", "sibilia-anna"}


def test_synthesize_returns_wav_and_ignores_length_scale(client):
    r = client.post("/synthesize", json={"text": "Ciao a tutti.", "voice": "sibilia", "length_scale": 1.3})
    assert r.status_code == 200 and r.headers["content-type"] == "audio/wav" and r.content[:4] == b"RIFF"
    assert client.calls == [("Ciao a tutti.", "sibilia")]


def test_rejects_unknown_voice_before_synthesis(client):
    r = client.post("/synthesize", json={"text": "Ciao.", "voice": "sibilia-marco"})
    assert r.status_code == 400 and "sibilia-marco" in r.text
    assert client.calls == []


def test_rejects_path_like_voice_before_synthesis(client):
    r = client.post("/synthesize", json={"text": "Ciao.", "voice": "sibilia-../anna"})
    assert r.status_code == 400
    assert client.calls == []


def test_rejects_empty_and_overlong_text(client):
    assert client.post("/synthesize", json={"text": "  ", "voice": "sibilia"}).status_code == 400
    assert client.post("/synthesize", json={"text": "a" * (core.MAX_TEXT_CHARS + 1), "voice": "sibilia"}).status_code == 400
    assert client.calls == []


def test_generation_failure_is_500(client):
    r = client.post("/synthesize", json={"text": "boom", "voice": "sibilia"})
    assert r.status_code == 500
