// Minimal LiveKit Rust SDK publisher used by OpenViduTestAppE2eTest.
// Joins the room of LIVEKIT_TOKEN and publishes a single video track with codec
// VIDEO_CODEC (vp8, h264, vp9 or av1). VIDEO_LAYERS=single (default): one plain
// RTP encoding (no simulcast for VP8/H264, no SVC = scalability_mode L1T1 for
// VP9/AV1). VIDEO_LAYERS=multi: two layers, simulcast for VP8/H264 (the SDK
// derives 480x360 + 640x480 from the 640x480 source) and SVC L2T2 for
// VP9/AV1. Pushes synthetic animated frames forever (the Java test stops the
// container).
// Env: LIVEKIT_URL, LIVEKIT_TOKEN, VIDEO_CODEC, VIDEO_LAYERS
use std::{env, time::Duration};

use livekit::options::{TrackPublishOptions, VideoCodec};
use livekit::track::{LocalTrack, LocalVideoTrack, TrackSource};
use livekit::webrtc::video_frame::{I420Buffer, VideoFrame, VideoRotation};
use livekit::webrtc::video_source::{native::NativeVideoSource, RtcVideoSource, VideoResolution};
use livekit::{Room, RoomOptions};

#[tokio::main]
async fn main() {
    let codec = env::var("VIDEO_CODEC").unwrap();
    let multi_layer = env::var("VIDEO_LAYERS").map(|v| v == "multi").unwrap_or(false);
    let (width, height): (u32, u32) = (640, 480);

    let mut options = TrackPublishOptions {
        source: TrackSource::Camera,
        video_codec: match codec.as_str() {
            "vp8" => VideoCodec::VP8,
            "h264" => VideoCodec::H264,
            "vp9" => VideoCodec::VP9,
            "av1" => VideoCodec::AV1,
            other => panic!("unknown VIDEO_CODEC {other}"),
        },
        ..Default::default()
    };
    if codec == "vp8" || codec == "h264" {
        options.simulcast = multi_layer;
    } else {
        options.scalability_mode = Some(if multi_layer { "L2T2" } else { "L1T1" }.to_string());
    }

    let (room, mut _events) = Room::connect(
        &env::var("LIVEKIT_URL").unwrap(),
        &env::var("LIVEKIT_TOKEN").unwrap(),
        RoomOptions::default(),
    )
    .await
    .expect("could not connect to room");

    let source = NativeVideoSource::new(
        VideoResolution { width, height },
        false, // is_screencast
    );
    let track = LocalVideoTrack::create_video_track("rust-video", RtcVideoSource::Native(source.clone()));
    room.local_participant()
        .publish_track(LocalTrack::Video(track), options)
        .await
        .expect("could not publish track");

    // The Java test waits for this exact log line before asserting
    println!("TRACK_PUBLISHED");

    let mut frame = VideoFrame {
        rotation: VideoRotation::VideoRotation0,
        timestamp_us: 0,
        frame_metadata: None,
        buffer: I420Buffer::new(width, height),
    };
    let mut n: u8 = 0;
    loop {
        n = n.wrapping_add(7);
        let (data_y, data_u, data_v) = frame.buffer.data_mut();
        data_y.fill(n);
        data_u.fill(255 - n);
        data_v.fill(n.wrapping_mul(3));
        source.capture_frame(&frame);
        // 15 fps in multi mode: several software encoders at 30 fps trigger CPU adaptation
        tokio::time::sleep(Duration::from_millis(if multi_layer { 66 } else { 33 })).await;
    }
}
