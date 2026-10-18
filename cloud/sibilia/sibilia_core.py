"""Pure logic for the Sibilia TTS service: no modal/torch imports, so it is testable locally."""

import io
import math
import re
from pathlib import Path
from typing import Callable

import numpy as np
import soundfile as sf

BUILT_IN = "sibilia"
SAMPLE_RATE = 24000
MAX_TEXT_CHARS = 2000
_NAME = re.compile(r"[a-z0-9]+")
L4_DOLLARS_PER_SECOND = 0.000222
# One full Sibilia chunk (podcaster.tts.sibilia.chunk-chars = 300), so smoke timings match real episodes
SMOKE_SENTENCE = ("Buongiorno e benvenuti alla rassegna del mattino. Ieri sera il governo ha approvato la legge "
                  "di bilancio, che ora passa all'esame del Parlamento. Le opposizioni parlano di una manovra senza "
                  "coraggio, mentre la maggioranza rivendica il taglio delle tasse per i redditi medi e bassi.")


class UnknownVoice(ValueError):
    pass


class GenerationRambled(RuntimeError):
    """The model hit the frame cap: almost always babble, so the caller should retry."""


def max_frames(text: str) -> int:
    """Mimi runs at 12.5 frames/s; Italian narration is ~14 chars/s; 1.5x headroom plus 4 s."""
    return math.ceil(len(text) / 14 * 12.5 * 1.5) + 50


def monthly_cost(gpu_seconds: float, audio_seconds: float, minutes_per_day: float = 20,
                 dollars_per_second: float = L4_DOLLARS_PER_SECOND) -> float:
    """GPU cost of minutes_per_day of audio for 30 days at the measured GPU-seconds per audio-second."""
    return gpu_seconds / audio_seconds * minutes_per_day * 60 * 30 * dollars_per_second


def valid_voice_name(name: str) -> bool:
    return bool(_NAME.fullmatch(name))


def list_voices(voices_dir: Path) -> dict[str, dict]:
    voices = {BUILT_IN: {"kind": "built-in"}}
    if voices_dir.is_dir():
        for clip in sorted(voices_dir.glob("*.wav")):
            if valid_voice_name(clip.stem) and clip.with_suffix(".txt").is_file():
                voices[f"{BUILT_IN}-{clip.stem}"] = {"kind": "reference"}
    return voices


def resolve_voice(voice: str, voices_dir: Path) -> tuple[Path, str] | None:
    """None for the built-in voice, (clip, transcript) for a reference voice."""
    if voice == BUILT_IN:
        return None
    prefix = BUILT_IN + "-"
    name = voice[len(prefix):] if voice.startswith(prefix) else ""
    if not valid_voice_name(name):
        raise UnknownVoice(f"Unknown voice '{voice}'")
    clip, transcript = voices_dir / f"{name}.wav", voices_dir / f"{name}.txt"
    if not (clip.is_file() and transcript.is_file()):
        raise UnknownVoice(f"Unknown voice '{voice}'")
    return clip, transcript.read_text(encoding="utf-8").strip()


def conversation(text: str, reference: tuple[np.ndarray, str] | None) -> list[dict]:
    """CSM chat format: an optional context turn (transcript + 24 kHz audio) then the text to speak."""
    turns = []
    if reference is not None:
        audio, transcript = reference
        turns.append({"role": "0", "content": [{"type": "text", "text": transcript}, {"type": "audio", "audio": audio}]})
    turns.append({"role": "0", "content": [{"type": "text", "text": text}]})
    return turns


def wav_bytes(samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> bytes:
    buf = io.BytesIO()
    sf.write(buf, np.asarray(samples, dtype=np.float32).reshape(-1), sample_rate, format="WAV", subtype="PCM_16")
    return buf.getvalue()


def create_web_app(synthesize: Callable[[str, str], bytes], voices_dir: Path, refresh: Callable[[], None] = lambda: None):
    """Piper-shaped API: GET /voices, POST /synthesize {text, voice, length_scale?} -> audio/wav."""
    from fastapi import Body, FastAPI, HTTPException
    from fastapi.responses import Response

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/voices")
    def voices():
        refresh()
        return list_voices(voices_dir)

    @app.post("/synthesize")
    def synth(body: dict = Body(...)):
        text = str(body.get("text") or "").strip()
        voice = str(body.get("voice") or BUILT_IN)
        if not text:
            raise HTTPException(400, "Text is empty")
        if len(text) > MAX_TEXT_CHARS:
            raise HTTPException(400, f"Text is {len(text)} characters; the limit is {MAX_TEXT_CHARS}")
        refresh()
        try:
            resolve_voice(voice, voices_dir)
        except UnknownVoice as e:
            raise HTTPException(400, str(e))
        try:
            audio = synthesize(text, voice)
        except Exception as e:  # GenerationRambled, CUDA errors...: 500 so Podcaster retries
            raise HTTPException(500, f"Synthesis failed: {type(e).__name__}: {e}")
        return Response(content=audio, media_type="audio/wav")

    return app
