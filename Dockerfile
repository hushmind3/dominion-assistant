FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp
WORKDIR /app
RUN pip install --no-cache-dir "pipecat-ai[livekit,deepgram,cartesia,silero]==1.17.0" "livekit-api==1.2.3" "pypdf==7.1.1"
RUN mkdir -p /data && chown 10001:10001 /data
ENV DOMINION_PLUGIN_STATE=/data/assistant-state.json
COPY app.py voice.py session.py plugin_sdk.py mobile_gateway.py attachments.py assistant_memory.py voice_turn.py index.html app.js /app/
USER 10001:10001
EXPOSE 8080
CMD ["python","/app/app.py"]
