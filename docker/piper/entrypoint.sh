#!/bin/sh
set -e
VOICE="${PIPER_DEFAULT_VOICE:-it_IT-paola-medium}"
for v in $VOICE $PIPER_EXTRA_VOICES; do
  if [ ! -f "/voices/$v.onnx" ]; then
    echo "Downloading Piper voice $v"
    python -m piper.download_voices "$v" --data-dir /voices
  fi
done
exec python -m piper.http_server --host 0.0.0.0 --port 5000 --data-dir /voices -m "$VOICE"
