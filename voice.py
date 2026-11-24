"""Pipecat 1.17 / LiveKit standard STT → DOMINION → TTS pipeline.

The only reasoning service is the DOMINION bridge. All room and speech
credentials stay within this process. A private single-user room is required.
"""
import asyncio
import time
from voice_turn import VoiceTurnGate

async def run_live(conversation, config, stop, report=lambda state: None):
    from pipecat.audio.vad.silero import SileroVADAnalyzer
    from pipecat.frames.frames import LLMContextFrame, TTSSpeakFrame, InterruptionFrame
    from pipecat.pipeline.pipeline import Pipeline
    from pipecat.pipeline.worker import PipelineParams, PipelineWorker
    from pipecat.processors.aggregators.llm_context import LLMContext
    from pipecat.processors.aggregators.llm_response_universal import LLMContextAggregatorPair, LLMUserAggregatorParams
    from pipecat.processors.frame_processor import FrameProcessor, FrameDirection
    from pipecat.services.cartesia.tts import CartesiaTTSService
    from pipecat.services.deepgram.stt import DeepgramSTTService
    from pipecat.transports.livekit.transport import LiveKitParams, LiveKitTransport
    from pipecat.workers.runner import WorkerRunner
    from loguru import logger

    # SDK debug logs may include speech transcripts or provider metadata.
    logger.remove()
    transport = LiveKitTransport(url=config["url"], token=config["token"],
        room_name=config["room"], params=LiveKitParams(audio_in_enabled=True, audio_out_enabled=True))
    stt = DeepgramSTTService(api_key=config["sttKey"],
        settings=DeepgramSTTService.Settings(language=config["language"], model="nova-3"))
    tts = CartesiaTTSService(api_key=config["ttsKey"],
        settings=CartesiaTTSService.Settings(voice=config["voice"], language=config["language"]))
    context = LLMContext()
    user, _ = LLMContextAggregatorPair(context, user_params=LLMUserAggregatorParams(
        vad_analyzer=SileroVADAnalyzer()))

    class Dominion(FrameProcessor):
        def __init__(self):
            super().__init__()
            self.last_count = 0
            self.gate = VoiceTurnGate()
            self.pump_task = None

        async def process_frame(self, frame, direction):
            await super().process_frame(frame, direction)
            if isinstance(frame,InterruptionFrame) and direction==FrameDirection.DOWNSTREAM:
                self.gate.interrupt()
                await self.push_frame(frame,direction)
                return
            if not isinstance(frame, LLMContextFrame) or direction != FrameDirection.DOWNSTREAM:
                await self.push_frame(frame, direction)
                return
            if getattr(frame, "speculation", False):
                return
            messages = frame.context.get_messages()
            if len(messages) <= self.last_count:
                return
            self.last_count = len(messages)
            latest = next((m for m in reversed(messages) if isinstance(m, dict) and m.get("role") == "user"), None)
            if not latest:
                return
            text = latest.get("content")
            if isinstance(text, list):
                text = " ".join(x.get("text", "") for x in text if isinstance(x, dict) and x.get("type") == "text")
            if not isinstance(text, str) or not text.strip():
                return
            self.offer(text)

        def offer(self,text):
            self.gate.offer(text)
            if self.pump_task is None or self.pump_task.done():
                self.pump_task=self.create_task(self.pump())

        async def pump(self):
            while not stop.is_set():
                if self.gate.latest is None:
                    return
                if conversation.request():
                    await asyncio.sleep(.15)
                    continue
                item=self.gate.take()
                if item is None: continue
                epoch,text=item
                try:
                    if config.get("phoneId"):
                        rid=conversation.submit(text,"phone",kind="chat",phone_id=config["phoneId"],objective=config["objective"])
                    elif mode in ("outbound","inbound"):
                        rid=conversation.submit(text,"phone",kind="chat")
                    else:
                        rid=conversation.submit(text,"live",kind="chat")
                except ValueError:
                    if self.gate.current(epoch):
                        await self.push_frame(TTSSpeakFrame("발화를 처리할 수 없습니다." if config["language"]=="ko" else "Unable to process this utterance."))
                    continue
                await self.wait_answer(rid,epoch)

        async def wait_answer(self,rid,epoch):
            deadline=time.monotonic()+180
            while time.monotonic()<deadline and not stop.is_set():
                result=conversation.answer(rid)
                if result is not None:
                    if result and self.gate.current(epoch):
                        await self.push_frame(TTSSpeakFrame(result[:8000]))
                    return
                state=conversation.status()
                if not state["pending"] or state["pending"]["requestId"]!=rid:
                    last=state.get("last") or {}
                    if last.get("requestId")==rid and last.get("status") not in (None,"completed") and self.gate.current(epoch):
                        await self.push_frame(TTSSpeakFrame(last["answer"][:1000]))
                    return
                await asyncio.sleep(.15)
            if not stop.is_set() and self.gate.current(epoch):
                await self.push_frame(TTSSpeakFrame("답변이 지연되고 있습니다." if config["language"]=="ko" else "The answer is taking longer."))

    dominion = Dominion()
    worker = PipelineWorker(Pipeline([transport.input(), stt, user, dominion, tts, transport.output()]),
        params=PipelineParams(enable_metrics=True, enable_usage_metrics=True))
    runner = WorkerRunner(handle_sigint=False)
    await runner.add_workers(worker)
    ready = asyncio.Event()
    participants = set()
    first = asyncio.Event()
    mode = config.get("mode", "manual")

    @transport.event_handler("on_connected")
    async def connected(transport):
        ready.set()

    @transport.event_handler("on_participant_connected")
    async def joined(transport, participant_id):
        participants.add(participant_id)
        if mode == "mobile" and participant_id != config["peer"]:
            stop.set()
            return
        if mode == "outbound" and participant_id != config["peer"]:
            stop.set()
            return
        if len(participants) > 1:
            stop.set()
            return
        first.set()
        if mode == "inbound":
            report("connected")
        if mode in ("outbound","inbound"):
            await worker.queue_frame(TTSSpeakFrame("안녕하세요, 도미니온 AI 음성 비서입니다." if config["language"]=="ko" else "Hello, this is the DOMINION AI voice assistant."))
            if config.get("phoneId"):
                dominion.offer("통화 상대가 연결되었습니다. 통화 목적에 맞는 첫 질문 또는 인사를 하세요.")

    @transport.event_handler("on_participant_disconnected")
    async def left(transport, participant_id):
        participants.discard(participant_id)
        if mode != "manual":
            stop.set()

    @transport.event_handler("on_disconnected")
    async def disconnected(transport):
        stop.set()

    task = asyncio.create_task(runner.run())
    deadline = time.monotonic() + (600 if mode in ("outbound", "inbound") else 1800)
    dial = None
    try:
        while not ready.is_set() and not task.done() and not stop.is_set():
            if time.monotonic() > deadline - (570 if mode in ("outbound", "inbound") else 1770):
                raise RuntimeError("LiveKit connection timeout")
            await asyncio.sleep(0.1)
        if task.done():
            await task
            raise RuntimeError("Voice worker exited before connection")
        if stop.is_set():
            return
        if mode == "outbound":
            from session import dial_once
            report("dialing")
            dial = asyncio.create_task(dial_once(config))
        else:
            report(("connected" if first.is_set() else "waiting") if mode in ("mobile","inbound") else "running")
        while not task.done() and not stop.is_set() and time.monotonic() < deadline:
            if dial is not None and dial.done():
                await dial
                report("connected")
                dial = None
            if mode == "mobile" and first.is_set():
                report("connected")
            await asyncio.sleep(0.25)
        if task.done():
            await task
    finally:
        # Never issue a second dial on an uncertain result. Terminate the
        # uniquely allocated outbound room, even after timeout or cancellation.
        if dial is not None:
            if not dial.done():
                dial.cancel()
            await asyncio.gather(dial, return_exceptions=True)
        try:
            if not task.done():
                await runner.cancel()
                await asyncio.wait_for(task, timeout=15)
        finally:
            if mode in ("outbound","inbound"):
                from session import close_phone
                await close_phone(config, participants)
