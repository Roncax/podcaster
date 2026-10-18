"""Sibilia-TTS on Modal: a token-protected, Piper-shaped TTS API for Podcaster.

Deploy:      modal deploy sibilia_app.py           (run from cloud/sibilia)
Weights:     modal run sibilia_app.py::download
Add a voice: modal run sibilia_app.py::add_voice --name anna --wav clip.wav --text "trascrizione"
Smoke test:  modal run sibilia_app.py::smoke
"""

import time
from pathlib import Path

import modal

import sibilia_core as core

MODEL_ID = "DeepMount00/Sibilia-TTS"
MODELS_DIR = "/models"
VOICES_ROOT = "/voices"
VOICES_DIR = Path(VOICES_ROOT) / "voices"
TEST_SENTENCE = ("Buongiorno e benvenuti alla rassegna del mattino. Oggi parliamo di politica, di esteri "
                 "e di una storia curiosa che arriva da Torino.")

app = modal.App("podcaster-sibilia")
models = modal.Volume.from_name("sibilia-models", create_if_missing=True)
voices = modal.Volume.from_name("sibilia-voices", create_if_missing=True)

web_image = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_pip_install("fastapi[standard]==0.120.0", "numpy==2.3.4", "soundfile==0.13.1")
    .add_local_python_source("sibilia_core")
)
gpu_base = (
    modal.Image.debian_slim(python_version="3.12")
    # the versions the 2026-10-17 CPU benchmark produced correct audio with
    .uv_pip_install("torch==2.14.1", "torchaudio==2.11.0", "transformers==5.20.1", "accelerate==1.15.0",
                    "huggingface_hub==2.2.0", "numpy==2.5.4", "soundfile==0.14.0")
    .env({"HF_HOME": MODELS_DIR})
)
# Local sources must be the last layer; only the download job may reach the Hub
gpu_image = gpu_base.env({"HF_HUB_OFFLINE": "1"}).add_local_python_source("sibilia_core")
download_image = gpu_base.env({"HF_HUB_OFFLINE": "0"}).add_local_python_source("sibilia_core")


@app.cls(image=gpu_image, gpu="L4", volumes={MODELS_DIR: models, VOICES_ROOT: voices},
         scaledown_window=300, max_containers=2, timeout=600)
class Sibilia:
    @modal.enter()
    def load(self):
        import torch
        from transformers import AutoProcessor, CsmForConditionalGeneration

        self.torch = torch
        self.processor = AutoProcessor.from_pretrained(MODEL_ID)
        self.model = CsmForConditionalGeneration.from_pretrained(MODEL_ID, torch_dtype=torch.bfloat16, device_map="cuda")
        # transformers 5 skips this tie (tie_word_embeddings=false) although the checkpoint relies on it
        self.model.backbone_model.embed_tokens.embed_audio_tokens.weight = self.model.depth_decoder.model.embed_tokens.weight
        self.model.eval()

    def _reference(self, voice: str):
        resolved = core.resolve_voice(voice, VOICES_DIR)
        if resolved is None:
            return None
        import soundfile as sf
        import torchaudio.functional as F

        clip, transcript = resolved
        data, sr = sf.read(clip, dtype="float32", always_2d=True)
        mono = self.torch.from_numpy(data.mean(axis=1))
        if sr != core.SAMPLE_RATE:
            mono = F.resample(mono, sr, core.SAMPLE_RATE)
        return mono.numpy(), transcript

    @modal.method()
    def synthesize(self, text: str, voice: str) -> bytes:
        voices.reload()
        # BatchFeature.to casts only floating tensors (reference audio) to bf16; token ids stay integers
        inputs = self.processor.apply_chat_template(core.conversation(text, self._reference(voice)),
                                                    tokenize=True, return_dict=True).to(device="cuda", dtype=self.torch.bfloat16)
        cap = core.max_frames(text)
        with self.torch.inference_mode():
            audio = self.model.generate(**inputs, output_audio=True, max_new_tokens=cap)
        samples = audio[0].float().cpu().numpy()
        if len(samples) >= cap * core.SAMPLE_RATE / 12.5 * 0.98:
            raise core.GenerationRambled(f"Reached the {cap}-frame cap for {len(text)} characters")
        return core.wav_bytes(samples)


@app.function(image=web_image, volumes={VOICES_ROOT: voices}, timeout=600)  # as long as the GPU worker
@modal.asgi_app(requires_proxy_auth=True)
def api():
    return core.create_web_app(lambda text, voice: Sibilia().synthesize.remote(text, voice), VOICES_DIR, refresh=voices.reload)


@app.function(image=download_image, volumes={MODELS_DIR: models}, timeout=1800)
def download_weights():
    from huggingface_hub import snapshot_download

    snapshot_download(MODEL_ID)
    models.commit()


@app.local_entrypoint()
def download():
    download_weights.remote()
    print("Weights stored in the sibilia-models volume")


@app.function(image=web_image, volumes={VOICES_ROOT: voices})
def store_voice(name: str, wav: bytes, transcript: str):
    VOICES_DIR.mkdir(parents=True, exist_ok=True)
    (VOICES_DIR / f"{name}.wav").write_bytes(wav)
    (VOICES_DIR / f"{name}.txt").write_text(transcript.strip(), encoding="utf-8")
    voices.commit()


@app.local_entrypoint()
def add_voice(name: str, wav: str, text: str):
    import soundfile as sf

    if not core.valid_voice_name(name):
        raise SystemExit("Voice names must match [a-z0-9]+")
    info = sf.info(wav)
    if not 3 <= info.duration <= 20:
        raise SystemExit(f"Reference clip must be 3-20 s long; {wav} is {info.duration:.1f} s")
    if not text.strip():
        raise SystemExit("--text (the clip's transcript) is required")
    store_voice.remote(name, Path(wav).read_bytes(), text)
    out = Path(f"{name}-test.wav")
    out.write_bytes(Sibilia().synthesize.remote(TEST_SENTENCE, f"{core.BUILT_IN}-{name}"))
    print(f"Stored voice sibilia-{name}; listen to {out} before using it in a show")


@app.local_entrypoint()
def smoke():
    import io

    import soundfile as sf

    started = time.time()
    Sibilia().synthesize.remote("Prova.", core.BUILT_IN)  # untimed warm-up: container start + model load
    print(f"cold start + first call: {time.time() - started:.1f}s (paid about once per episode)")
    for voice in _remote_voices():
        started = time.time()
        wav = Sibilia().synthesize.remote(core.SMOKE_SENTENCE, voice)
        elapsed = time.time() - started
        seconds = sf.info(io.BytesIO(wav)).duration
        Path(f"smoke-{voice}.wav").write_bytes(wav)
        print(f"{voice}: {len(core.SMOKE_SENTENCE)} chars -> {seconds:.1f}s audio in {elapsed:.1f}s warm "
              f"({elapsed / seconds:.2f}x real time) -> ~${core.monthly_cost(elapsed, seconds):.2f}/month for 20 min/day")
    idle = 300 * core.L4_DOLLARS_PER_SECOND * 30
    print(f"plus idle time before scale-down: ~${idle:.2f}/month per daily episode (5 min warm after the last chunk)")


@app.function(image=web_image, volumes={VOICES_ROOT: voices})
def list_remote_voices() -> list[str]:
    return list(core.list_voices(VOICES_DIR))


def _remote_voices() -> list[str]:
    return list_remote_voices.remote()
