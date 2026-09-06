// Minimal LiveKit Rust SDK publisher used by OpenViduTestAppE2eServerSdkTest
// See ../README.md
use std::{env, time::Duration};

use livekit::options::{DegradationPreference, TrackPublishOptions, VideoCodec};
use livekit::track::{LocalTrack, LocalVideoTrack, TrackSource};
use livekit::webrtc::video_frame::{I420Buffer, VideoFrame, VideoRotation};
use livekit::webrtc::video_source::{native::NativeVideoSource, RtcVideoSource, VideoResolution};
use livekit::{Room, RoomOptions};

#[tokio::main]
async fn main() {
    let codec = env::var("VIDEO_CODEC").unwrap();
    let layers: u32 = env::var("VIDEO_LAYERS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    // The SDK only splits a simulcast source in three layers from 960 px wide
    let (width, height): (u32, u32) = if layers == 3 { (1280, 720) } else { (640, 480) };

    let mut options = TrackPublishOptions {
        source: TrackSource::Camera,
        // Keep the layer sizes under CPU load (see ../README.md)
        degradation_preference: Some(DegradationPreference::MaintainResolution),
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
        options.simulcast = layers > 1;
    } else {
        options.scalability_mode = Some(format!("L{layers}T{layers}"));
    }

    // RoomOptions is #[non_exhaustive]: it cannot be built with a struct expression
    let mut room_options = RoomOptions::default();
    room_options.dynacast = false;
    let (room, mut _events) = Room::connect(
        &env::var("LIVEKIT_URL").unwrap(),
        &env::var("LIVEKIT_TOKEN").unwrap(),
        room_options,
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
    let (stride_y, stride_u, stride_v) = frame.buffer.strides();
    let mut n: u32 = 0;
    loop {
        n += 1;
        // Textured, moving frames (see ../README.md): a diagonal ramp on the luma
        // plane and slower ramps on chroma
        let (data_y, data_u, data_v) = frame.buffer.data_mut();
        for y in 0..height as usize {
            for x in 0..width as usize {
                data_y[y * stride_y as usize + x] = (x + y + 7 * n as usize) as u8;
            }
        }
        for y in 0..(height as usize + 1) / 2 {
            for x in 0..(width as usize + 1) / 2 {
                data_u[y * stride_u as usize + x] = (x + 3 * n as usize) as u8;
                data_v[y * stride_v as usize + x] = (y + 5 * n as usize) as u8;
            }
        }
        source.capture_frame(&frame);
        // 15 fps with several layers: several software encoders at 30 fps trigger CPU adaptation
        tokio::time::sleep(Duration::from_millis(if layers > 1 { 66 } else { 33 })).await;
    }
}
