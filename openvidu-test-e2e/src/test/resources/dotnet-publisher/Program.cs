// Minimal LiveKit .NET SDK publisher used by OpenViduTestAppE2eServerSdkTest.
// See ../README.md
using LiveKit.Rtc;
using Proto = LiveKit.Proto;

var codec = Environment.GetEnvironmentVariable("VIDEO_CODEC")!;
var layers = int.Parse(Environment.GetEnvironmentVariable("VIDEO_LAYERS") ?? "1");
// The SDK only splits a simulcast source in three layers from 960 px wide
int width = layers == 3 ? 1280 : 640;
int height = layers == 3 ? 720 : 480;

var room = new Room();
await room.ConnectAsync(
    Environment.GetEnvironmentVariable("LIVEKIT_URL")!,
    Environment.GetEnvironmentVariable("LIVEKIT_TOKEN")!,
    new RoomOptions { AutoSubscribe = false, Dynacast = false });

var videoSource = new VideoSource(width, height);
var videoTrack = LocalVideoTrack.Create("dotnet-video", videoSource);
var options = new TrackPublishOptions
{
    VideoCodec = Enum.Parse<Proto.VideoCodec>(codec, ignoreCase: true),
    Source = Proto.TrackSource.SourceCamera,
};
if (codec == "vp8" || codec == "h264")
{
    options.Simulcast = layers > 1;
}
else
{
    options.ScalabilityMode = $"L{layers}T{layers}";
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
    // 15 fps with several layers: several software encoders at 30 fps trigger CPU adaptation
    await Task.Delay(layers > 1 ? 66 : 33);
}
