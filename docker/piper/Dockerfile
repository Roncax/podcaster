FROM python:3.12-slim
RUN pip install --no-cache-dir "piper-tts[http]==1.8.0"
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
VOLUME /voices
EXPOSE 5000
ENTRYPOINT ["/entrypoint.sh"]
