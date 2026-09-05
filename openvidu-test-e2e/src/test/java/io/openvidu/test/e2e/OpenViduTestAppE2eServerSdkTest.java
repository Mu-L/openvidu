package io.openvidu.test.e2e;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import livekit.LivekitModels.ParticipantInfo;
import livekit.LivekitModels.TrackInfo;
import livekit.LivekitModels.TrackType;
import livekit.LivekitModels.VideoLayer;

/**
 * E2E tests of the LiveKit server RTC SDK publishers (go, node, python, rust,
 * dotnet) against browser subscribers.
 *
 * Split out of {@link OpenViduTestAppE2eTest} because the full matrix is slow:
 * this way it can be run on its own with
 * {@code mvn -Dtest=OpenViduTestAppE2eServerSdkTest test}, and the system
 * properties {@code sdk.codecs} / {@code sdk.layers} narrow it further.
 *
 * @author Pablo Fuente (pablofuenteperez@gmail.com)
 */
@Tag("e2e")
@DisplayName("E2E tests for OpenVidu TestApp: server SDK publishers")
@ExtendWith(SpringExtension.class)
public class OpenViduTestAppE2eServerSdkTest extends AbstractOpenViduTestappE2eTest {

	@BeforeAll()
	protected static void setupAll() throws Exception {
		loadEnvironmentVariables();
		setUpLiveKitClient();
		CompletableFuture.runAsync(OpenViduTestAppE2eServerSdkTest::pullRemoteBrowserImages);
	}

	@BeforeEach()
	protected void setupEach() {
		this.closeAllRooms(LK);
	}

	@AfterEach()
	protected void finishEach() {
		this.closeAllRooms(LK);
	}

	// Server RTC SDK publishers matrix: each SDK publishes each codec first as
	// one plain RTP encoding (no simulcast for VP8/H264, no SVC for VP9/AV1)
	// and then as two layers (high and low quality: simulcast for VP8/H264, SVC
	// L2T2 for VP9/AV1), and a Chrome subscriber and a Firefox subscriber must
	// both receive it — for two layers, switching to the LOW and then to the
	// HIGH layer. The Go SDK single-layer H264 lane reproduces the mediasoup
	// Producer codec-binding bug: Go SDK publishers declare no codec in their
	// AddTrackRequest and prefer an H264 variant the server does not support,
	// so the server's answer ends up VP8 first, the Producer is bound to VP8,
	// the worker discards every incoming packet and the subscribers receive no
	// media at all. See MEDIASOUP_CODEC_BINDING_BUG.md

	/**
	 * {vp8, h264, vp9, av1} x {single, multi} layers, single-layer cases first.
	 * System properties sdk.codecs / sdk.layers (comma-separated) restrict the
	 * matrix, e.g. -Dsdk.layers=multi -Dsdk.codecs=vp9,av1
	 */
	static Stream<Arguments> serverSdkPublisherMatrix() {
		List<String> layersFilter = List.of(System.getProperty("sdk.layers", "single,multi").split(","));
		List<String> codecsFilter = List.of(System.getProperty("sdk.codecs", "vp8,h264,vp9,av1").split(","));
		return Stream.of("single", "multi").filter(layersFilter::contains)
				.flatMap(layers -> Stream.of("vp8", "h264", "vp9", "av1").filter(codecsFilter::contains)
						.map(codec -> Arguments.of(codec, layers)));
	}

	@ParameterizedTest(name = "Go SDK {0} {1}-layer publisher to Chrome and Firefox subscribers")
	@MethodSource("serverSdkPublisherMatrix")
	@DisplayName("Go SDK publisher to Chrome and Firefox subscribers")
	void goSdkPublisherToBrowserSubscribersTest(String codec, String layers) throws Exception {
		serverSdkPublisherToBrowserSubscribersAux("go", codec, layers);
	}

	@ParameterizedTest(name = "Node SDK {0} {1}-layer publisher to Chrome and Firefox subscribers")
	@MethodSource("serverSdkPublisherMatrix")
	@DisplayName("Node SDK publisher to Chrome and Firefox subscribers")
	void nodeSdkPublisherToBrowserSubscribersTest(String codec, String layers) throws Exception {
		serverSdkPublisherToBrowserSubscribersAux("node", codec, layers);
	}

	@ParameterizedTest(name = "Python SDK {0} {1}-layer publisher to Chrome and Firefox subscribers")
	@MethodSource("serverSdkPublisherMatrix")
	@DisplayName("Python SDK publisher to Chrome and Firefox subscribers")
	void pythonSdkPublisherToBrowserSubscribersTest(String codec, String layers) throws Exception {
		serverSdkPublisherToBrowserSubscribersAux("python", codec, layers);
	}

	@ParameterizedTest(name = "Rust SDK {0} {1}-layer publisher to Chrome and Firefox subscribers")
	@MethodSource("serverSdkPublisherMatrix")
	@DisplayName("Rust SDK publisher to Chrome and Firefox subscribers")
	void rustSdkPublisherToBrowserSubscribersTest(String codec, String layers) throws Exception {
		serverSdkPublisherToBrowserSubscribersAux("rust", codec, layers);
	}

	@ParameterizedTest(name = ".NET SDK {0} {1}-layer publisher to Chrome and Firefox subscribers")
	@MethodSource("serverSdkPublisherMatrix")
	@DisplayName(".NET SDK publisher to Chrome and Firefox subscribers")
	void dotnetSdkPublisherToBrowserSubscribersTest(String codec, String layers) throws Exception {
		// Requires Livekit.Rtc.Dotnet >= 0.1.4 (TrackPublishOptions.VideoCodec)
		serverSdkPublisherToBrowserSubscribersAux("dotnet", codec, layers);
	}

	/**
	 * A Chrome browser and a Firefox browser join the room as subscriber-only
	 * participants and a LiveKit server RTC SDK participant
	 * (startServerSdkPublisher) joins the same room ("TestRoom" is the testapp
	 * default) publishing a single video track with the given codec: as one plain
	 * RTP encoding (layers "single") or as two layers (layers "multi": high and
	 * low quality — simulcast for VP8/H264, SVC L2T2 for VP9/AV1). Both
	 * subscribers must receive the track's media with that codec, going through
	 * exactly the same steps; with two layers they also switch to the LOW and
	 * then to the HIGH layer.
	 */
	private void serverSdkPublisherToBrowserSubscribersAux(String sdk, String codec, String layers)
			throws Exception {
		final String expectedCodec = "video/" + codec.toUpperCase();
		final String publisherIdentity = sdk + "-publisher";
		final boolean multiLayer = "multi".equals(layers);

		// The Go SDK forwards pre-encoded samples (no encoder), so its only
		// multi-layer shape is RID simulcast — and LiveKit does not support RID
		// simulcast for the SVC-class codecs (multi-layer VP9/AV1 must be SVC):
		// that combination is skipped as an unsupported publish shape
		Assumptions.assumeFalse(multiLayer && "go".equals(sdk) && ("vp9".equals(codec) || "av1".equals(codec)),
				"The Go SDK cannot publish SVC, and VP9/AV1 RID simulcast is not a supported LiveKit publish shape");

		List<OpenViduTestappUser> subscribers = List.of(setupBrowserAndConnectToOpenViduTestapp("chrome"),
				setupBrowserAndConnectToOpenViduTestapp("firefox"));

		log.info("{} SDK {} {}-layer publisher to Chrome and Firefox subscribers", sdk, codec, layers);

		for (OpenViduTestappUser user : subscribers) {
			this.addSubscriber(user, false);
			WebElement participantNameInput = user.getDriver().findElement(By.id("participant-name-input-0"));
			participantNameInput.clear();
			participantNameInput.sendKeys(browserName(user) + "-subscriber");
			user.getDriver().findElements(By.className("connect-btn")).forEach(el -> el.sendKeys(Keys.ENTER));
			user.getEventManager().waitUntilEventReaches("connected", "RoomEvent", 1);
		}
		for (OpenViduTestappUser user : subscribers) {
			user.getEventManager().waitUntilEventReaches("active", "ParticipantEvent", 1);
		}

		this.startServerSdkPublisher(sdk, "TestRoom", codec, multiLayer);

		// The server's authoritative view of the publication (RoomService API):
		// the LiveKit TrackInfo of a video publication lists one layer per
		// simulcast layer or SVC spatial layer, so one plain encoding (no
		// simulcast, no SVC / L1T1) has exactly one layer and a two-layer
		// publish (simulcast or SVC L2T2) has two
		TrackInfo trackInfo = this.getPublishedVideoTrackInfo("TestRoom", publisherIdentity);
		final int expectedLayers = multiLayer ? 2 : 1;
		Assertions.assertEquals(expectedLayers, trackInfo.getLayersCount(), "Expected " + expectedLayers
				+ " video layer(s) in the track published by the " + sdk + " SDK, but the server reports "
				+ trackInfo.getLayersList());
		Assertions.assertEquals(expectedLayers, trackInfo.getCodecs(0).getLayersCount(), "Expected " + expectedLayers
				+ " video layer(s) for codec " + expectedCodec + " published by the " + sdk + " SDK");
		// VP8/H264 multi-layer publishes are simulcast and VP9/AV1 ones are SVC
		// L2T2, except for the Go SDK: it forwards pre-encoded samples (no
		// encoder, so no SVC) and publishes simulcast for every codec
		final boolean expectSimulcast = multiLayer && ("vp8".equals(codec) || "h264".equals(codec) || "go".equals(sdk));
		if (!multiLayer || expectSimulcast) {
			Assertions.assertEquals(expectSimulcast, trackInfo.getSimulcast(),
					"Simulcast flag of the track published by the " + sdk + " SDK");
			// (the server's explicit SVC verdict: an SVC publication would be
			// MULTIPLE_SPATIAL_LAYERS_PER_STREAM)
			Assertions.assertEquals(VideoLayer.Mode.ONE_SPATIAL_LAYER_PER_STREAM,
					trackInfo.getCodecs(0).getVideoLayerMode(),
					"The track published by the " + sdk + " SDK should not be SVC");
		}

		// Both browsers must receive the track, with its codec and its layers
		for (OpenViduTestappUser user : subscribers) {
			assertSubscriberReceivesVideo(user, sdk, publisherIdentity, expectedCodec, trackInfo);
		}

		for (OpenViduTestappUser user : subscribers) {
			gracefullyLeaveParticipants(user, 1);
		}
	}

	/** "Chrome", "Firefox"... from the BrowserUser class of the testapp user. */
	private String browserName(OpenViduTestappUser user) {
		return user.getBrowserUser().getClass().getSimpleName().replace("User", "");
	}

	/**
	 * Selects the max video quality (LOW, MEDIUM or HIGH) of the remote track of
	 * the first testapp instance, closing the track info dialog if it is open.
	 */
	private void selectSubscriberVideoQuality(OpenViduTestappUser user, String quality) throws InterruptedException {
		if (!user.getDriver().findElements(By.cssSelector("app-info-dialog")).isEmpty()) {
			user.getDriver().findElement(By.cssSelector("#close-dialog-btn")).click();
			Thread.sleep(300);
		}
		user.getDriver().findElement(By.cssSelector("#openvidu-instance-0 #max-video-quality")).click();
		this.waitAndClick(user, "mat-option.mode-" + quality);
	}

	/**
	 * The subscriber-only participant of the given browser must receive the video
	 * track published by the SDK participant: media actually flowing, with the
	 * expected codec, with the layers the server reports in trackInfo. With more
	 * than one layer the subscriber switches to the LOW and then to the HIGH
	 * layer, each recognised by its frame width.
	 */
	private void assertSubscriberReceivesVideo(OpenViduTestappUser user, String sdk, String publisherIdentity,
			String expectedCodec, TrackInfo trackInfo) throws Exception {
		final String browser = browserName(user);

		user.getEventManager().waitUntilEventReaches("trackSubscribed", "ParticipantEvent", 1);

		user.getWaiter().until(ExpectedConditions.numberOfElementsToBe(By.tagName("video"), 1));
		Assertions.assertTrue(assertAllElementsHaveTracks(user, "video", false, true),
				browser + ": HTMLVideoElements were expected to have only one video track");

		WebElement subscriberVideo = user.getDriver().findElement(By.cssSelector("#openvidu-instance-0 video.remote"));

		// Media must actually reach the subscriber (with the codec-binding bug
		// present the track subscribes but receives 0 bytes forever)
		waitUntilVideoLayersNotEmpty(user, subscriberVideo);
		this.waitUntilSubscriberBytesReceivedIncreasing(user, subscriberVideo);
		this.waitUntilSubscriberFramesPerSecondNotZero(user, subscriberVideo);

		// And with the codec the publisher actually sends (a Producer bound to
		// the wrong codec makes every subscriber negotiate that wrong codec)
		Assertions.assertEquals(expectedCodec, this.getSubscriberVideoCodec(user, subscriberVideo),
				browser + " subscriber should negotiate the codec the " + sdk + " SDK publisher sends");

		// And with the layers the server reports, in the track info received by
		// the subscriber
		JsonArray subscriberLayers = this.getRemoteVideoTrackInfoLayers(user, publisherIdentity);
		Assertions.assertEquals(trackInfo.getLayersCount(), subscriberLayers.size(),
				"Expected " + trackInfo.getLayersCount() + " video layer(s) in the track published by the " + sdk
						+ " SDK, but the " + browser + " subscriber sees " + subscriberLayers);

		if (trackInfo.getLayersCount() > 1) {
			// Multiple layers: with adaptiveStream disabled the received layer
			// only changes through the max-video-quality selector. Receiving the
			// LOW layer and then the HIGH layer — each recognised by the frame
			// width the publisher declared for it in the TrackInfo, and each
			// actually decoding (framesPerSecond > 0: an undecodable layer still
			// reports its frame size) — proves that the publisher sends every
			// layer and that the SFU forwards the requested one. The declared
			// widths are reliable because the multi-layer publishers capture at
			// 15 fps: at 30 fps libwebrtc's CPU adaptation can scale every layer
			// down under load
			this.selectQualityAndAwaitLayer(user, subscriberVideo, "LOW", lowestLayerWidth(trackInfo));
			this.selectQualityAndAwaitLayer(user, subscriberVideo, "HIGH", highestLayerWidth(trackInfo));
		}
	}

	/**
	 * Selects the max video quality of the subscriber's remote track and waits
	 * until the received video has the frame width of that layer and decodes
	 * (framesPerSecond > 0). Retries the selection once: under CPU load the SFU
	 * can take longer than one wait window to ramp back up to a higher layer.
	 */
	private void selectQualityAndAwaitLayer(OpenViduTestappUser user, WebElement subscriberVideo, String quality,
			int expectedFrameWidth) throws Exception {
		for (int attempt = 1; attempt <= 2; attempt++) {
			this.selectSubscriberVideoQuality(user, quality);
			try {
				this.waitUntilSubscriberFrameWidthIs(user, subscriberVideo, expectedFrameWidth);
				break;
			} catch (AssertionError e) {
				if (attempt == 2) {
					throw e;
				}
			}
		}
		this.waitUntilSubscriberFramesPerSecondNotZero(user, subscriberVideo);
	}

	/**
	 * Width of the lowest-quality published layer, from the server's TrackInfo.
	 * The layers are picked by width, not by their VideoQuality label: the SDKs
	 * label a two-layer publish inconsistently (simulcast: LOW + MEDIUM; SVC
	 * L2T2: MEDIUM + HIGH; the Go program: LOW + HIGH), while the subscriber's
	 * LOW/HIGH selection always clamps to the lowest/highest available layer.
	 */
	private int lowestLayerWidth(TrackInfo trackInfo) {
		return trackInfo.getLayersList().stream().mapToInt(VideoLayer::getWidth).min()
				.orElseThrow(() -> new AssertionError("No layers in " + trackInfo));
	}

	/**
	 * Width of the highest-quality published layer, from the server's TrackInfo.
	 */
	private int highestLayerWidth(TrackInfo trackInfo) {
		return trackInfo.getLayersList().stream().mapToInt(VideoLayer::getWidth).max()
				.orElseThrow(() -> new AssertionError("No layers in " + trackInfo));
	}

	/**
	 * The first video track published by the given participant, as reported by
	 * the LiveKit server (RoomService GetParticipant). Waits up to 10 seconds for
	 * it: the SDK programs log TRACK_PUBLISHED as soon as their publish call
	 * returns, a few milliseconds before the server registers the track.
	 */
	private TrackInfo getPublishedVideoTrackInfo(String roomName, String participantIdentity) throws Exception {
		for (int attempt = 0; attempt < 40; attempt++) {
			ParticipantInfo participant = LK.getParticipant(roomName, participantIdentity).execute().body();
			if (participant != null) {
				Optional<TrackInfo> videoTrack = participant.getTracksList().stream()
						.filter(track -> track.getType() == TrackType.VIDEO).findFirst();
				if (videoTrack.isPresent()) {
					return videoTrack.get();
				}
			}
			Thread.sleep(250);
		}
		throw new AssertionError(participantIdentity + " has no published video track in room " + roomName);
	}

	/**
	 * Video layers (VideoLayer[] of the LiveKit TrackInfo) of the first video
	 * track published by the remote participant with the given identity, as seen
	 * by the local participant of the first testapp instance.
	 */
	private JsonArray getRemoteVideoTrackInfoLayers(OpenViduTestappUser user, String participantIdentity) {
		String layers = (String) ((JavascriptExecutor) user.getDriver()).executeScript(
				"var room = window['room_0'];"
						+ "var participant = room.remoteParticipants.get(arguments[0]);"
						+ "var publication = participant.videoTrackPublications.values().next().value;"
						+ "return JSON.stringify(publication.trackInfo.layers);",
				participantIdentity);
		return JsonParser.parseString(layers).getAsJsonArray();
	}
}
