# Minimal LiveKit Python SDK publisher used by OpenViduTestAppE2eServerSdkTest
# See ../README.md
import asyncio
import os

from livekit import rtc

LAYERS = int(os.environ.get("VIDEO_LAYERS", "1"))
# The SDK only splits a simulcast source in three layers from 960 px wide
WIDTH, HEIGHT = (1280, 720) if LAYERS == 3 else (640, 480)

async def main():
    codec = os.environ["VIDEO_CODEC"]

    room = rtc.Room()
    await room.connect(
        os.environ["LIVEKIT_URL"],
        os.environ["LIVEKIT_TOKEN"],
        options=rtc.RoomOptions(auto_subscribe=False, dynacast=False),
    )

    source = rtc.VideoSource(WIDTH, HEIGHT)
    track = rtc.LocalVideoTrack.create_video_track("python-video", source)
    options = rtc.TrackPublishOptions(
        video_codec=getattr(rtc.VideoCodec, codec.upper()),
        source=rtc.TrackSource.SOURCE_CAMERA,
    )
    if codec in ("vp8", "h264"):
        options.simulcast = LAYERS > 1
    else:
        options.scalability_mode = f"L{LAYERS}T{LAYERS}"
    await room.local_participant.publish_track(track, options)

    # The Java test waits for this exact log line before asserting
    print("TRACK_PUBLISHED", flush=True)

    n = 0
    while True:
        n = (n + 7) % 256
        data = bytes((n, 255 - n, (n * 3) % 256, 255)) * (WIDTH * HEIGHT)
        source.capture_frame(rtc.VideoFrame(WIDTH, HEIGHT, rtc.VideoBufferType.RGBA, data))
        # 15 fps with several layers: several software encoders at 30 fps trigger CPU adaptation
        await asyncio.sleep(1 / 15 if LAYERS > 1 else 1 / 30)


if __name__ == "__main__":
    asyncio.run(main())
