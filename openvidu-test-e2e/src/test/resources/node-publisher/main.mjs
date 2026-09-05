// Minimal LiveKit Node SDK publisher used by OpenViduTestAppE2eServerSdkTest
// See ../README.md
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
const layers = Number(process.env.VIDEO_LAYERS || '1');
// The SDK only splits a simulcast source in three layers from 960 px wide
const [WIDTH, HEIGHT] = layers === 3 ? [1280, 720] : [640, 480];

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
  options.simulcast = layers > 1;
} else {
  options.scalabilityMode = `L${layers}T${layers}`;
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
}, layers > 1 ? 66 : 33); // 15 fps with several layers: several software encoders at 30 fps trigger libwebrtc CPU adaptation (layer sizes shrink)
