# Sibilia Cloud TTS — Design Spec

Date: 2026-10-18
Status: Approved design, pending implementation plan
Branch: `feature/sibilia-cloud-tts`
Parent specs: `2026-10-06-podcaster-design.md`, `2026-10-07-admin-ui-redesign-design.md`

## 1. Purpose

Give shows a higher-quality Italian voice by running **Sibilia-TTS** (`DeepMount00/Sibilia-TTS`) on a serverless GPU at **Modal**. Podcaster selects it per show, next to the existing Piper voices. Expected volume is at most ~20 minutes of audio a day, and real-time speed is not required.

### Success criteria

- A show whose voice is `sibilia` produces a complete episode through the existing pipeline: chunking, resume, progress, chapters, MP3 and calibration.
- Shows on Piper voices behave exactly as today. With Sibilia unconfigured, nothing changes.
- One voice is kept throughout an episode.
- The Modal endpoint can't be used without the token, and unauthorized calls start no container.
- Monthly cost for ~20 min/day stays within Modal's $30 free credit. This is an estimate to be confirmed by the smoke test (§6).

### Findings this design relies on (measured 2026-10-17/18, CPU, transformers 5.20.1)

- Sibilia is a Sesame CSM fine-tune (`CsmForConditionalGeneration`). It has 1.63B parameters, of which a ~1B Llama backbone, a 100M depth decoder and the Mimi codec. Output is 24 kHz mono.
- **Loading bug:** transformers 5 leaves `backbone_model.embed_tokens.embed_audio_tokens.weight` uninitialized. The config has `tie_codebooks_embeddings: true` but `tie_word_embeddings: false`, and the checkpoint stores the table only as `depth_decoder.model.embed_tokens.weight`. It must be re-tied after loading, or the output audio is garbage.
- The **built-in voice is stable.** The same sentence across speaker IDs 0–3 and two seeds gives ECAPA cosine similarity 0.84–0.92 for every pair, so it is one speaker. Speaker IDs therefore don't give different voices.
- CPU speed (Ryzen 5 5600) is ~11× slower than real time, which is why it runs on a GPU.

## 2. Architecture

```
NAS Podcaster ──HTTPS (Modal-Key/Modal-Secret)──▶ Modal: CPU front end (FastAPI)
   RoutingTtsEngine                                   GET /voices   (no GPU)
     ├─ "sibilia", "sibilia-*" → HttpTtsEngine(Sibilia)  POST /synthesize ──▶ GPU worker (L4)
     └─ everything else       → HttpTtsEngine(Piper) ──▶ piper:5000 (unchanged)
```

The Modal service imitates Piper's HTTP API (`GET /voices`, `POST /synthesize` → WAV), so Podcaster reuses one HTTP engine for both. Calls only go outward from the NAS: no ports are opened and no reverse-proxy rule is added.

## 3. Modal service — `cloud/sibilia/sibilia_app.py`

- **App:** `podcaster-sibilia`, deployed with `modal deploy cloud/sibilia/sibilia_app.py`.
- **Image:** Debian slim, CUDA PyTorch, `transformers==5.20.1`, `fastapi`, `soundfile`. Versions are pinned.
- **Volumes:**
  - `sibilia-models` is the Hugging Face cache, mounted at `HF_HOME`. A `download` function fills it once, so cold starts never fetch 6.6 GB.
  - `sibilia-voices` holds reference voices as `voices/<name>.wav` plus `voices/<name>.txt` (the transcript).
- **GPU worker:** a `modal.cls` with `gpu="L4"`, `scaledown_window=300`, `max_containers=2`, `timeout=600`.
  - `@modal.enter` loads the model in bf16, re-ties the embedding (§1), and sets eval mode.
  - **Method** `synthesize(text: str, voice: str) -> bytes` returns a WAV (24 kHz, mono, 16-bit PCM).
  - **Voice `sibilia`:** conversation `[{"role": "0", "content": [{"type": "text", "text": text}]}]`.
  - **Voice `sibilia-<name>`:** a context turn `{"role": "0", "content": [{"type": "text", "text": <transcript>}, {"type": "audio", "path": <clip>}]}` followed by the text turn. The clip is read from the Volume on the server, and Podcaster sends only the voice name.
  - **Generation cap:** `max_new_tokens = ceil(len(text) / 14 * 12.5 * 1.5) + 50` frames. If the output reaches the cap, the model probably rambled, so the method raises and the endpoint returns HTTP 500.
- **Web front end:** `@modal.asgi_app()` on a CPU function with `requires_proxy_auth=True`.
  - `GET /voices` returns a JSON object keyed by voice name, the same shape as Piper's response: `{"sibilia": {...}, "sibilia-anna": {...}}`. The values carry a short description and are ignored by Podcaster.
  - `POST /synthesize` takes a JSON body `{text, voice, length_scale?}`.
    - `length_scale` is accepted and ignored, because CSM has no speed control.
    - Unknown voice or empty text → HTTP 400 with a plain message.
    - Otherwise it calls `Sibilia().synthesize.remote(...)` and returns `audio/wav`.
    - Worker failure → HTTP 500.
    - Calls longer than Modal's ~150 s web limit get Modal's 303 redirect to the result URL, and the client follows it.
- **CLI functions** (run from the PC):
  - `download` fills `sibilia-models`.
  - `add_voice --name <n> --wav <file> --text "<transcript>"` uploads a reference voice. It then synthesizes a fixed test sentence with that voice and saves `<n>-test.wav` locally for listening.
    - Names must match `[a-z0-9]+`.
    - Clips of 3–20 s are accepted, and must be mono or are converted to mono.
  - `smoke` synthesizes one ~300-character sentence per voice. It prints audio seconds, GPU seconds, the speed relative to real time, and estimated $/month for 20 min/day at the L4 rate.
- **Auth:** a Modal proxy-auth token, created in the Modal dashboard. Requests without valid `Modal-Key`/`Modal-Secret` headers get 401 from Modal's edge without starting a container.
- **Python tests** (`cloud/sibilia/test_sibilia_app.py`, pytest, no GPU) cover the frame-cap formula, voice-name validation, the voice listing built from a temporary directory, and the 400 responses. Model calls are stubbed.

## 4. Podcaster changes

### 4.1 Configuration

```yaml
podcaster:
  tts:
    sibilia:
      url: ${PODCASTER_SIBILIA_URL:}        # empty = disabled
      key: ${PODCASTER_SIBILIA_KEY:}
      secret: ${PODCASTER_SIBILIA_SECRET:}
      chunk-chars: 300
      parallelism: 2
      request-timeout: 5m
```

`PodcasterConfig.Tts` gains `Sibilia sibilia()`, with `Optional<String> url/key/secret`, `int chunkChars`, `int parallelism` and `Duration requestTimeout`. Attempts and retry delay are shared with Piper.

### 4.2 `HttpTtsEngine` (replaces `PiperHttpTtsEngine`)

- A plain class, not a CDI bean, built as `HttpTtsEngine(String name, String baseUrl, Map<String,String> headers, int attempts, Duration retryDelay, Duration requestTimeout, Duration voicesTimeout)`.
- Behavior is the same as today's Piper engine, with these changes:
  - It sends `headers` on every request.
  - Its `HttpClient` uses `followRedirects(Redirect.NORMAL)`, which follows Modal's 303 as a GET.
  - Error messages use `name` ("Piper returned HTTP 503", "Sibilia unreachable at …").
  - **401/403** → non-retryable `IllegalStateException("<name> rejected the credentials (HTTP 401)")`.
  - **429** is retryable, like 5xx.
- The `/voices` timeout becomes a constructor parameter. Piper keeps 10 s; Sibilia uses 30 s, to absorb the CPU front end's cold start.
- Existing `PiperHttpTtsEngineTest` cases move to `HttpTtsEngineTest` unchanged in substance.

### 4.3 `TtsEngine` and `RoutingTtsEngine`

- `TtsEngine` gains `TtsLimits limits(String voiceId)`. `TtsLimits` is a record of `(int chunkChars, int parallelism)`.
- `RoutingTtsEngine` is the `@ApplicationScoped` bean behind every existing `@Inject TtsEngine`, so the injection points don't change.
  - It builds a Piper `HttpTtsEngine` from `piper-url`.
  - It builds a Sibilia `HttpTtsEngine` only when `sibilia.url` is set. Its headers are `Modal-Key`/`Modal-Secret`, sent only when both are set.
- **Routing:** `isSibilia(voiceId)` is true for `voiceId.equals("sibilia") || voiceId.startsWith("sibilia-")`.
  - `synthesize` delegates accordingly.
  - A Sibilia voice with Sibilia unconfigured → `IllegalStateException("Sibilia is not configured (PODCASTER_SIBILIA_URL)")`.
- **`voices()`** returns the union, in a `TreeSet`.
  - If Sibilia's `/voices` fails, the router logs a warning and returns Piper's voices.
  - If Piper's fails, it propagates as today (callers already handle it).
- **`limits`** returns Sibilia's chunk size and parallelism for Sibilia voices, and the global `chunk-chars`/`parallelism` for the rest.
- `TtsStage` and `VoiceDryRun` take chunk size and parallelism from `tts.limits(show.voiceId)` instead of the global config.

### 4.4 Guard: voice change between attempts

`TtsStage` writes `voice.txt` (the voice ID and chunk size) in the run's work dir before synthesizing. If the file exists with a different value, it first deletes the existing `chunk-*.wav` files. Otherwise a retry after switching voices would stitch 22 kHz Piper chunks together with 24 kHz Sibilia ones, or mismatched chunk indexes.

### 4.5 Admin UI

- **Show form voice `<select>`:** two `<optgroup>`s, "Piper (local)" and "Sibilia (cloud GPU)". The second appears only when Sibilia voices are listed. The existing fallback option for an unreachable engine stays.
- **Settings page:**
  - "Piper voices" becomes "Voices", split the same way.
  - The System list gains "Sibilia", showing "Not configured", "Configured" with the host only (never the token), or "Unreachable".
- Grouping lives in a template extension, `isCloudVoice(String)`, which delegates to `RoutingTtsEngine.isSibilia`.

## 5. Error handling

| Situation | Behavior |
|---|---|
| Timeout, connection error, HTTP 5xx/429 | Retried (`attempts`, `retry-delay`), then the run fails with the engine-named message |
| HTTP 401/403 | Fails immediately: "Sibilia rejected the credentials" |
| HTTP 400 | Fails immediately with the server's message |
| Non-WAV body | Fails immediately (existing check) |
| Sibilia voice, Sibilia not configured | TTS stage fails: "Sibilia is not configured" |
| Failure mid-episode | Run fails; Retry resumes from missing chunks (existing) |
| Voice changed between attempts | Old chunks discarded (§4.4) |
| Sibilia `/voices` down | Admin lists Piper voices only, plus a warning in the log; saving a show with a Sibilia voice is rejected by the existing voice validation |

## 6. Testing

- **`HttpTtsEngineTest`** (WireMock):
  - Piper cases carried over.
  - Auth headers sent.
  - 303 → GET result followed.
  - 401 not retried and its message names the engine.
  - 429 retried.
- **`RoutingTtsEngineTest`** (two WireMock stubs, Piper and Sibilia):
  - Routing by voice.
  - Union of voices.
  - Sibilia down → Piper voices only.
  - Unconfigured Sibilia → clear error.
  - `limits` per voice.
- **`TtsStage` tests:**
  - A Sibilia voice is chunked at 300 characters.
  - A voice change discards the old chunks.
  - The same voice resumes from the existing chunks.
- **Admin:** the show form renders both optgroups, and the settings page never contains the key or secret.
- **Python:** `cloud/sibilia/test_sibilia_app.py` (§3).
- **Live check, manual, after deploy:**
  - `modal run cloud/sibilia/sibilia_app.py::smoke`, recording measured speed and cost in the PR description.
  - One full manual episode of "Rassegna del mattino" on `sibilia`.

## 7. Rollout

1. **User:** creates a Modal account and runs `modal token new` once.
2. **Implementer:**
   - Deploys and runs `download`, then `smoke`.
   - Reports the L4 speed and projected monthly cost.
   - If the cost is far above the estimate, stops and revisits the GPU choice before wiring the NAS.
3. **User:** creates a proxy-auth token in the Modal dashboard. The URL and token go into the NAS `.env` as `PODCASTER_SIBILIA_URL`, `PODCASTER_SIBILIA_KEY` and `PODCASTER_SIBILIA_SECRET`, never printed or committed.
4. **PR → merge:** the GHCR image builds, then on the NAS: `docker compose pull && docker compose up -d`.
5. **Switch:** "Rassegna del mattino" moves to `sibilia`, followed by one manual run and a listen.
6. **README:** a new "Cloud TTS (Sibilia on Modal)" section covering setup, env vars, `add_voice`, costs and the `length_scale` limitation.

## 8. Out of scope

- Fine-tuning or training new voices.
- Cross-chunk context (feeding the previous chunk's audio into the next) for smoother prosody. It can follow if chunk boundaries sound abrupt.
- Automatic fallback to Piper when Modal fails, which would mix voices.
- Other cloud TTS providers (Cartesia etc.). The `HttpTtsEngine` and router make one straightforward to add later.
- Speed control for Sibilia voices.
