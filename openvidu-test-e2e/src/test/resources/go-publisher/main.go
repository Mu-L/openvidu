// Minimal LiveKit Go SDK publisher used by OpenViduTestAppE2eTest.
//
// It joins the room of LIVEKIT_TOKEN and publishes one video track with codec
// VIDEO_CODEC (vp8, h264, vp9 or av1), looping pre-encoded files forever
// (Annex-B for H264, IVF otherwise; the Java test stops the container):
//
//   - VIDEO_LAYERS=single (default): one plain RTP encoding, no simulcast,
//     from VIDEO_FILE. This is the publish shape of every Go SDK client (lk
//     CLI, lk load-test, Go agents): the AddTrackRequest declares no codec —
//     the exact configuration that triggered the mediasoup Producer
//     codec-binding bug (see MEDIASOUP_CODEC_BINDING_BUG.md).
//   - VIDEO_LAYERS=multi: two RID simulcast layers (LOW 320x240, HIGH
//     640x480) from VIDEO_FILE_LOW / VIDEO_FILE_HIGH. The Go SDK forwards
//     pre-encoded samples and has no encoder, so it cannot produce SVC
//     streams; its VP9/AV1 multi-layer cases are skipped by the Java test
//     (RID simulcast of SVC-class codecs is not a supported publish shape).
//
// Env: LIVEKIT_URL, LIVEKIT_TOKEN, VIDEO_CODEC, VIDEO_LAYERS, VIDEO_FILE*
package main

import (
	"fmt"
	"io"
	"log"
	"os"
	"strings"
	"time"

	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"github.com/pion/webrtc/v4/pkg/media/h264reader"
	"github.com/pion/webrtc/v4/pkg/media/ivfreader"

	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
)

const frameDuration = time.Second / 30

var mimeTypes = map[string]string{
	"vp8":  webrtc.MimeTypeVP8,
	"h264": webrtc.MimeTypeH264,
	"vp9":  webrtc.MimeTypeVP9,
	"av1":  webrtc.MimeTypeAV1,
}

type simulcastLayer struct {
	quality       livekit.VideoQuality
	width, height uint32
	fileEnv       string
}

var simulcastLayers = []simulcastLayer{
	{livekit.VideoQuality_LOW, 320, 240, "VIDEO_FILE_LOW"},
	{livekit.VideoQuality_HIGH, 640, 480, "VIDEO_FILE_HIGH"},
}

func main() {
	mimeType, ok := mimeTypes[os.Getenv("VIDEO_CODEC")]
	if !ok {
		log.Fatalf("unknown VIDEO_CODEC %q", os.Getenv("VIDEO_CODEC"))
	}
	codec := webrtc.RTPCodecCapability{MimeType: mimeType, ClockRate: 90000}

	room, err := lksdk.ConnectToRoomWithToken(os.Getenv("LIVEKIT_URL"), os.Getenv("LIVEKIT_TOKEN"), &lksdk.RoomCallback{})
	if err != nil {
		log.Fatalf("could not connect to room: %v", err)
	}
	defer room.Disconnect()

	if os.Getenv("VIDEO_LAYERS") == "multi" {
		tracks := make([]*lksdk.LocalTrack, 0, len(simulcastLayers))
		for _, layer := range simulcastLayers {
			track, err := lksdk.NewLocalSampleTrack(codec, lksdk.WithSimulcast("go-video",
				&livekit.VideoLayer{Quality: layer.quality, Width: layer.width, Height: layer.height}))
			if err != nil {
				log.Fatalf("could not create local track: %v", err)
			}
			tracks = append(tracks, track)
		}
		if _, err = room.LocalParticipant.PublishSimulcastTrack(tracks, &lksdk.TrackPublicationOptions{
			Name:        "go-video",
			VideoWidth:  640,
			VideoHeight: 480,
		}); err != nil {
			log.Fatalf("could not publish simulcast track: %v", err)
		}
		// The Java test waits for this exact log line before asserting.
		fmt.Println("TRACK_PUBLISHED")
		for i, layer := range simulcastLayers {
			go loopVideoFile(tracks[i], os.Getenv(layer.fileEnv))
		}
		select {}
	}

	track, err := lksdk.NewLocalSampleTrack(codec)
	if err != nil {
		log.Fatalf("could not create local track: %v", err)
	}
	if _, err = room.LocalParticipant.PublishTrack(track, &lksdk.TrackPublicationOptions{
		Name:        "go-video",
		VideoWidth:  640,
		VideoHeight: 480,
	}); err != nil {
		log.Fatalf("could not publish track: %v", err)
	}
	// The Java test waits for this exact log line before asserting.
	fmt.Println("TRACK_PUBLISHED")
	loopVideoFile(track, os.Getenv("VIDEO_FILE"))
}

// loopVideoFile sends the frames of the file forever, paced at 30 fps.
func loopVideoFile(track *lksdk.LocalTrack, path string) {
	for {
		if err := writeVideoFileOnce(track, path); err != nil {
			log.Fatalf("could not write video samples of %s: %v", path, err)
		}
	}
}

// writeVideoFileOnce sends every frame of the file once, paced at 30 fps.
func writeVideoFileOnce(track *lksdk.LocalTrack, path string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()

	var nextFrame func() ([]byte, error)
	if strings.HasSuffix(file.Name(), ".h264") {
		reader, err := h264reader.NewReader(file)
		if err != nil {
			return err
		}
		nextFrame = func() ([]byte, error) {
			nal, err := reader.NextNAL()
			if err != nil {
				return nil, err
			}
			return nal.Data, nil
		}
	} else {
		reader, _, err := ivfreader.NewWith(file)
		if err != nil {
			return err
		}
		nextFrame = func() ([]byte, error) {
			frame, _, err := reader.ParseNextFrame()
			return frame, err
		}
	}

	ticker := time.NewTicker(frameDuration)
	defer ticker.Stop()
	for {
		data, err := nextFrame()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		if err := track.WriteSample(media.Sample{Data: data, Duration: frameDuration}, nil); err != nil {
			return err
		}
		<-ticker.C
	}
}
