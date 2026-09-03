// Minimal LiveKit .NET RTC SDK publisher used by OpenViduTestAppE2eTest.
// Joins the room of LIVEKIT_TOKEN and publishes a single video track with codec
// VIDEO_CODEC (vp8, h264, vp9 or av1). VIDEO_LAYERS=single (default): one plain
// RTP encoding (no simulcast for VP8/H264, no SVC = ScalabilityMode L1T1 for
// VP9/AV1). VIDEO_LAYERS=multi: two layers, simulcast for VP8/H264 (the SDK
// derives 480x360 + 640x480 from the 640x480 source) and SVC L2T2 for
// VP9/AV1. Pushes synthetic animated frames forever (the Java test stops the
// container).
// Requires Livekit.Rtc.Dotnet >= 0.1.4 (TrackPublishOptions.VideoCodec).
// Env: LIVEKIT_URL, LIVEKIT_TOKEN, VIDEO_CODEC, VIDEO_LAYERS
using LiveKit.Rtc;
using Proto = LiveKit.Proto;

var codec = Environment.GetEnvironmentVariable("VIDEO_CODEC")!;
var multiLayer = Environment.GetEnvironmentVariable("VIDEO_LAYERS") == "multi";
int width = 640;
int height = 480;

var room = new Room();
await room.ConnectAsync(
    Environment.GetEnvironmentVariable("LIVEKIT_URL")!,
    Environment.GetEnvironmentVariable("LIVEKIT_TOKEN")!,
    new RoomOptions { AutoSubscribe = false });

var videoSource = new VideoSource(width, height);
var videoTrack = LocalVideoTrack.Create("dotnet-video", videoSource);
var options = new TrackPublishOptions
{
    VideoCodec = Enum.Parse<Proto.VideoCodec>(codec, ignoreCase: true),
    Source = Proto.TrackSource.SourceCamera,
};
if (codec == "vp8" || codec == "h264")
{
    options.Simulcast = multiLayer;
}
else
{
    options.ScalabilityMode = multiLayer ? "L2T2" : "L1T1";
}
await room.LocalParticipant!.PublishTrackAsync(videoTrack, options);

// The Java test waits for this exact log line before asserting
Console.WriteLine("TRACK_PUBLISHED");

var data = new byte[width * height * 4];
byte n = 0;
while (true)
{
    n += 7;
    for (int i = 0; i < data.Length; i += 4)
    {
        data[i] = n;
        data[i + 1] = (byte)(255 - n);
        data[i + 2] = (byte)(n * 3);
        data[i + 3] = 255;
    }
    videoSource.CaptureFrame(new VideoFrame(width, height, Proto.VideoBufferType.Rgba, data));
    // 15 fps in multi mode: several software encoders at 30 fps trigger CPU adaptation
    await Task.Delay(multiLayer ? 66 : 33);
}
