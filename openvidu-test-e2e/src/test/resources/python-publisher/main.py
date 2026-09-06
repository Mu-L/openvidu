# Minimal LiveKit Python SDK publisher used by OpenViduTestAppE2eServerSdkTest
# See ../README.md
import asyncio
import os

from livekit import rtc

LAYERS = int(os.environ.get("VIDEO_LAYERS", "1"))
# The SDK only splits a simulcast source in three layers from 960 px wide
WIDTH, HEIGHT = (1280, 720) if LAYERS == 3 else (640, 480)
# Textured, moving RGBA frames (see ../README.md): a ramp long enough to slice
# any row of any frame out of it at C speed
RAMP = bytes(i & 0xFF for i in range(WIDTH + HEIGHT + 256))
ALPHA = b"\xff" * WIDTH


def textured_frame(n):
    """RGBA frame n of the animation: R = x + 7n, G = y + 3n, B = x + y + 11n."""
    frame = bytearray(WIDTH * HEIGHT * 4)
    red_start = (7 * n) & 0xFF
    red = RAMP[red_start:red_start + WIDTH]
    for y in range(HEIGHT):
        row = memoryview(frame)[y * WIDTH * 4:(y + 1) * WIDTH * 4]
        blue_start = (y + 11 * n) & 0xFF
        row[0::4] = red
        row[1::4] = bytes([(y + 3 * n) & 0xFF]) * WIDTH
        row[2::4] = RAMP[blue_start:blue_start + WIDTH]
        row[3::4] = ALPHA
    return bytes(frame)

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
        # Keep the layer sizes under CPU load (see ../README.md)
        degradation_preference=rtc.DegradationPreference.MAINTAIN_RESOLUTION,
    )
    if codec in ("vp8", "h264"):
        options.simulcast = LAYERS > 1
    else:
        options.scalability_mode = f"L{LAYERS}T{LAYERS}"
    await room.local_participant.publish_track(track, options)

    # The Java test waits for this exact log line before asserting
    print("TRACK_PUBLISHED", flush=True)

    # A short loop of pre-rendered frames keeps the capture loop cheap
    frames = [textured_frame(n) for n in range(16)]
    n = 0
    while True:
        n += 1
        source.capture_frame(rtc.VideoFrame(WIDTH, HEIGHT, rtc.VideoBufferType.RGBA, frames[n % len(frames)]))
        # 15 fps with several layers: several software encoders at 30 fps trigger CPU adaptation
        await asyncio.sleep(1 / 15 if LAYERS > 1 else 1 / 30)


if __name__ == "__main__":
    asyncio.run(main())
