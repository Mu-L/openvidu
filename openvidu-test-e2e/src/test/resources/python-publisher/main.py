# Minimal LiveKit Python RTC SDK publisher used by OpenViduTestAppE2eTest.
# Joins the room of LIVEKIT_TOKEN and publishes a single video track with codec
# VIDEO_CODEC (vp8, h264, vp9 or av1). VIDEO_LAYERS=single (default): one plain
# RTP encoding (no simulcast for VP8/H264, no SVC = scalability_mode L1T1 for
# VP9/AV1). VIDEO_LAYERS=multi: two layers, simulcast for VP8/H264 (the SDK
# derives 480x360 + 640x480 from the 640x480 source) and SVC L2T2 for
# VP9/AV1. Pushes synthetic animated frames forever (the Java test stops the
# container).
# Env: LIVEKIT_URL, LIVEKIT_TOKEN, VIDEO_CODEC, VIDEO_LAYERS
import asyncio
import os

from livekit import rtc

MULTI_LAYER = os.environ.get("VIDEO_LAYERS") == "multi"
WIDTH = 640
HEIGHT = 480


async def main():
    codec = os.environ["VIDEO_CODEC"]

    room = rtc.Room()
    await room.connect(
        os.environ["LIVEKIT_URL"], os.environ["LIVEKIT_TOKEN"], options=rtc.RoomOptions(auto_subscribe=False)
    )

    source = rtc.VideoSource(WIDTH, HEIGHT)
    track = rtc.LocalVideoTrack.create_video_track("python-video", source)
    options = rtc.TrackPublishOptions(
        video_codec=getattr(rtc.VideoCodec, codec.upper()),
        source=rtc.TrackSource.SOURCE_CAMERA,
    )
    if codec in ("vp8", "h264"):
        options.simulcast = MULTI_LAYER
    else:
        options.scalability_mode = "L2T2" if MULTI_LAYER else "L1T1"
    await room.local_participant.publish_track(track, options)

    # The Java test waits for this exact log line before asserting
    print("TRACK_PUBLISHED", flush=True)

    n = 0
    while True:
        n = (n + 7) % 256
        data = bytes((n, 255 - n, (n * 3) % 256, 255)) * (WIDTH * HEIGHT)
        source.capture_frame(rtc.VideoFrame(WIDTH, HEIGHT, rtc.VideoBufferType.RGBA, data))
        await asyncio.sleep(1 / 15 if MULTI_LAYER else 1 / 30)  # 15 fps in multi mode: several software encoders at 30 fps trigger CPU adaptation


if __name__ == "__main__":
    asyncio.run(main())
