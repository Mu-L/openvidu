// Minimal LiveKit Node RTC SDK publisher used by OpenViduTestAppE2eTest.
// Joins the room of LIVEKIT_TOKEN and publishes a single video track with codec
// VIDEO_CODEC (vp8, h264, vp9 or av1). VIDEO_LAYERS=single (default): one plain
// RTP encoding (no simulcast for VP8/H264, no SVC = scalabilityMode L1T1 for
// VP9/AV1). VIDEO_LAYERS=multi: two layers, simulcast for VP8/H264 (the SDK
// derives 480x360 + 640x480 from the 640x480 source) and SVC L2T2 for
// VP9/AV1. Pushes synthetic animated frames forever (the Java test stops the
// container).
// Env: LIVEKIT_URL, LIVEKIT_TOKEN, VIDEO_CODEC, VIDEO_LAYERS
import {
  Room,
  LocalVideoTrack,
  VideoSource,
  VideoFrame,
  VideoBufferType,
  TrackPublishOptions,
  TrackSource,
  VideoCodec,
} from '@livekit/rtc-node';

const codec = process.env.VIDEO_CODEC;
const multiLayer = process.env.VIDEO_LAYERS === 'multi';
const WIDTH = 640;
const HEIGHT = 480;

const room = new Room();
await room.connect(process.env.LIVEKIT_URL, process.env.LIVEKIT_TOKEN, {
  autoSubscribe: false,
  dynacast: false,
});

const source = new VideoSource(WIDTH, HEIGHT);
const track = LocalVideoTrack.createVideoTrack('node-video', source);
const options = new TrackPublishOptions({
  videoCodec: VideoCodec[codec.toUpperCase()],
  source: TrackSource.SOURCE_CAMERA,
});
if (codec === 'vp8' || codec === 'h264') {
  options.simulcast = multiLayer;
} else {
  options.scalabilityMode = multiLayer ? 'L2T2' : 'L1T1';
}
await room.localParticipant.publishTrack(track, options);

// The Java test waits for this exact log line before asserting
console.log('TRACK_PUBLISHED');

const buf = new Uint8Array(WIDTH * HEIGHT * 4);
let n = 0;
setInterval(() => {
  n++;
  for (let y = 0; y < HEIGHT; y++) {
    for (let x = 0; x < WIDTH; x++) {
      const i = (y * WIDTH + x) * 4;
      buf[i] = (x + n * 7) & 0xff;
      buf[i + 1] = (y + n * 3) & 0xff;
      buf[i + 2] = (x + y + n * 11) & 0xff;
      buf[i + 3] = 0xff;
    }
  }
  source.captureFrame(new VideoFrame(buf, WIDTH, HEIGHT, VideoBufferType.RGBA));
}, multiLayer ? 66 : 33); // 15 fps in multi mode: several software encoders at 30 fps trigger libwebrtc CPU adaptation (layer sizes shrink)
