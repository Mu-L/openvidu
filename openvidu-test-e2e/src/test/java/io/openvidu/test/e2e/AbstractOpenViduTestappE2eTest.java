package io.openvidu.test.e2e;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openvidu.test.browsers.BrowserUser;

import static org.openqa.selenium.OutputType.BASE64;

public class AbstractOpenViduTestappE2eTest extends OpenViduTestE2e {

	protected Collection<OpenViduTestappUser> testappUsers = new HashSet<>();

	private void connectToOpenViduTestApp(OpenViduTestappUser user) {
		user.getDriver().get(APP_URL);
		try {
			user.getWaiter().until(ExpectedConditions.presenceOfElementLocated(By.id("livekit-url")));
		} catch (TimeoutException e) {
			// Dump diagnostics and retry once with a reload before giving up
			String screenshot = "data:image/png;base64,"
					+ ((TakesScreenshot) user.getDriver()).getScreenshotAs(BASE64);
			System.out.println("TIMEOUT WAITING FOR " + APP_URL + " TO LOAD, RETRYING ONCE. Page source:");
			System.out.println(user.getDriver().getPageSource());
			System.out.println(screenshot);
			user.getDriver().get(APP_URL);
			user.getWaiter().until(ExpectedConditions.presenceOfElementLocated(By.id("livekit-url")));
		}
		WebElement urlInput = user.getDriver().findElement(By.id("livekit-url"));
		urlInput.clear();
		urlInput.sendKeys(LIVEKIT_URL);
		WebElement keyInput = user.getDriver().findElement(By.id("livekit-api-key"));
		keyInput.clear();
		keyInput.sendKeys(LIVEKIT_API_KEY);
		WebElement secretInput = user.getDriver().findElement(By.id("livekit-api-secret"));
		secretInput.clear();
		secretInput.sendKeys(LIVEKIT_API_SECRET);
		user.getEventManager().startPolling();
	}

	protected OpenViduTestappUser setupBrowserAndConnectToOpenViduTestapp(String browser) throws Exception {
		BrowserUser browserUser = this.setupBrowser(browser);
		OpenViduTestappUser testappUser = new OpenViduTestappUser(browserUser);
		this.testappUsers.add(testappUser);
		this.connectToOpenViduTestApp(testappUser);
		return testappUser;
	}

	protected String getNetemContainerName(OpenViduTestappUser user) {
		return this.getNetemContainerName(user.getBrowserUser());
	}

	protected void gracefullyLeaveParticipants(OpenViduTestappUser user, int numberOfParticipants) throws Exception {
		int accumulatedDisconnected = 0;
		for (int j = 1; j <= numberOfParticipants; j++) {
			user.getDriver().findElement(By.className("disconnect-btn")).sendKeys(Keys.ENTER);
			user.getEventManager().waitUntilEventReaches("disconnected", "RoomEvent", j);
			user.getEventManager().waitUntilEventReaches("connectionStateChanged", "RoomEvent", j);
			accumulatedDisconnected = (j != numberOfParticipants) ? (accumulatedDisconnected + numberOfParticipants - j)
					: (accumulatedDisconnected);
			user.getEventManager().waitUntilEventReaches("participantDisconnected", "RoomEvent",
					accumulatedDisconnected);
		}
	}

	@AfterEach
	protected void dispose() {
		// Dispose all testapp users
		Iterator<OpenViduTestappUser> it2 = testappUsers.iterator();
		while (it2.hasNext()) {
			OpenViduTestappUser u = it2.next();
			u.dispose();
			it2.remove();
		}
		super.dispose();
	}

	protected static final long WAIT_UNTIL_MAX_MILLIS = 20000;

	// Minimum average frame rate that a subscriber video must sustain over a
	// window of at least MIN_FRAMES_DECODED_WINDOW_MILLIS to be considered
	// properly decoded and played
	protected static final long MIN_FRAMES_DECODED_FPS = 4;
	protected static final long MIN_FRAMES_DECODED_WINDOW_MILLIS = 2000;

	protected static void pullRemoteBrowserImages() {
		pullRemoteBrowserImage("REMOTE_URL_CHROME", "selenium/standalone-chrome:" + CHROME_VERSION);
		pullRemoteBrowserImage("REMOTE_URL_FIREFOX", "selenium/standalone-firefox:" + FIREFOX_VERSION);
		pullRemoteBrowserImage("REMOTE_URL_EDGE", "selenium/standalone-edge:" + EDGE_VERSION);
	}

	protected static void pullRemoteBrowserImage(String remoteUrlProperty, String image) {
		if (System.getProperty(remoteUrlProperty) == null) {
			return; // This browser runs as a native driver here. No Docker image to pull
		}
		try {
			log.info("Pre-pulling Selenium image {}", image);
			commandLine.executeCommand("docker pull " + image, 300);
		} catch (Exception e) {
			System.err.println("Pre-pull of " + image + " failed: " + e.getMessage());
		}
	}

	protected int countNumberOfPublishedLayers(OpenViduTestappUser user, WebElement publisherVideo) {
		JsonArray json = this.getLayersAsJsonArray(user, publisherVideo);
		return json.size();
	}

	protected int getSubscriberVideoFrameWidth(OpenViduTestappUser user, WebElement subscriberVideo) {
		return getSubscriberVideoLayerStat(user, subscriberVideo, "frameWidth", JsonElement::getAsInt);
	}

	protected int getSubscriberVideoFrameHeight(OpenViduTestappUser user, WebElement subscriberVideo) {
		return getSubscriberVideoLayerStat(user, subscriberVideo, "frameHeight", JsonElement::getAsInt);
	}

	protected long getSubscriberVideoBytesReceived(OpenViduTestappUser user, WebElement subscriberVideo) {
		return getSubscriberVideoLayerStat(user, subscriberVideo, "bytesReceived", JsonElement::getAsLong);
	}

	protected int getSubscriberVideoFramesPerSecond(OpenViduTestappUser user, WebElement subscriberVideo) {
		return getSubscriberVideoLayerStat(user, subscriberVideo, "framesPerSecond", JsonElement::getAsInt);
	}

	protected long getSubscriberVideoFramesDecoded(OpenViduTestappUser user, WebElement subscriberVideo) {
		return getSubscriberVideoLayerStat(user, subscriberVideo, "framesDecoded", JsonElement::getAsLong);
	}

	protected long getSubscriberVideoFramesReceived(OpenViduTestappUser user, WebElement subscriberVideo) {
		return getSubscriberVideoLayerStat(user, subscriberVideo, "framesReceived", JsonElement::getAsLong);
	}

	protected String getSubscriberVideoCodec(OpenViduTestappUser user, WebElement subscriberVideo) {
		return getSubscriberVideoLayerStat(user, subscriberVideo, "codec", JsonElement::getAsString);
	}

	protected <T> T getSubscriberVideoLayerStat(OpenViduTestappUser user, WebElement subscriberVideo, String field,
			java.util.function.Function<JsonElement, T> extractor) {
		final long deadline = System.currentTimeMillis() + WAIT_UNTIL_MAX_MILLIS;
		JsonElement element = null;
		do {
			try {
				element = getLayersAsJsonArray(user, subscriberVideo).get(0).getAsJsonObject().get(field);
			} catch (Exception e) {
				element = null;
			}
			if (element == null || element.isJsonNull()) {
				try {
					Thread.sleep(250);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		} while ((element == null || element.isJsonNull()) && System.currentTimeMillis() < deadline);
		if (element == null || element.isJsonNull()) {
			Assertions.fail("Timeout waiting for " + field + " to exist");
		}
		return extractor.apply(element);
	}

	// Several stats of the same subscriber layer, taken from a single info dialog
	// update. Sampling them one by one through getSubscriberVideoLayerStat would
	// pay
	// a full dialog read, and a stats refresh, for each one of them
	protected JsonObject getSubscriberVideoLayer(OpenViduTestappUser user, WebElement subscriberVideo) {
		JsonArray layers = this.getLayersAsJsonArray(user, subscriberVideo);
		return layers.isEmpty() ? new JsonObject() : layers.get(0).getAsJsonObject();
	}

	// Cumulative counter of the given layer, or -1 if it is not there
	protected long getLayerCounter(JsonObject layer, String field) {
		JsonElement element = layer.get(field);
		return element == null || element.isJsonNull() ? -1 : element.getAsLong();
	}

	// If rid is null, retrieve the first layer
	protected JsonElement getPublisherVideoLayerAttribute(OpenViduTestappUser user, WebElement publisherVideo,
			String rid,
			String attribute) {
		JsonArray json = this.getLayersAsJsonArray(user, publisherVideo);
		JsonElement result;
		if (rid != null) {
			result = json.asList().stream().parallel()
					.filter(jsonElement -> rid.equals(jsonElement.getAsJsonObject().get("rid").getAsString())).findAny()
					.get();
		} else {
			result = json.get(0);
		}
		return result.getAsJsonObject().get(attribute);
	}

	protected String getLayersAsString(OpenViduTestappUser user, WebElement video) {
		this.openInfoDialog(user, video);
		user.getDriver().findElement(By.cssSelector("#update-value-btn")).click();
		WebElement textarea = user.getDriver().findElement(By.id("info-text-area"));
		return textarea.getAttribute("value");
	}

	protected JsonArray getLayersAsJsonArray(OpenViduTestappUser user, WebElement video) {
		String value = getLayersAsString(user, video);
		return JsonParser.parseString(value).getAsJsonArray();
	}

	protected void waitUntilVideoLayersNotEmpty(OpenViduTestappUser user, WebElement videoElement) {
		this.waitUntilAux(user, videoElement, () -> {
			String value = getLayersAsString(user, videoElement);
			return !value.isBlank() && !JsonParser.parseString(value).getAsJsonArray().isEmpty();
		}, "Timeout waiting video layers to not be empty");
	}

	protected void waitUntilSubscriberFramesPerSecondNotZero(OpenViduTestappUser user, WebElement videoElement) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getSubscriberVideoFramesPerSecond(user, videoElement) > 0;
		}, "Timeout waiting for video track to have a framesPerSecond greater than 0");
	}

	protected void waitUntilSubscriberFramesPerSecondIs(OpenViduTestappUser user, WebElement videoElement, int fps) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getSubscriberVideoFramesPerSecond(user, videoElement) == fps;
		}, "Timeout waiting for video track to have a framesPerSecond equal to " + fps);
	}

	protected void waitUntilSubscriberFrameWidthIs(OpenViduTestappUser user, WebElement videoElement,
			final int expectedFrameWidth) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getSubscriberVideoFrameWidth(user, videoElement) == expectedFrameWidth;
		}, "Timeout waiting for video track to have a frameWidth of " + expectedFrameWidth);
	}

	protected void waitUntilSubscriberFrameHeightIs(OpenViduTestappUser user, WebElement videoElement,
			final int expectedFrameHeight) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getSubscriberVideoFrameHeight(user, videoElement) == expectedFrameHeight;
		}, "Timeout waiting for video track to have a frameHeight of " + expectedFrameHeight);
	}

	protected void waitUntilSubscriberFrameWidthChanges(OpenViduTestappUser user, WebElement videoElement,
			final int oldFrameWidth, final boolean shouldBeHigher) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getSubscriberVideoFrameWidth(user, videoElement) != oldFrameWidth;
		}, "Timeout waiting for video track to reach a " + (shouldBeHigher ? "higher" : "lower") + " resolution");
		int newFrameWidth = this.getSubscriberVideoFrameWidth(user, videoElement);
		if (shouldBeHigher) {
			Assertions.assertTrue(newFrameWidth > oldFrameWidth,
					"Video track should have now a higher resolution, but it is not. Old width: " + oldFrameWidth
							+ ". New width: " + newFrameWidth);
		} else {
			Assertions.assertTrue(newFrameWidth < oldFrameWidth,
					"Video track should have now a lower resolution, but it is not. Old width: " + oldFrameWidth
							+ ". New width: " + newFrameWidth);
		}
	}

	protected void waitUntilSubscriberBytesReceivedIncrease(OpenViduTestappUser user, WebElement videoElement,
			final long previousBytesReceived) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getSubscriberVideoBytesReceived(user, videoElement) > previousBytesReceived;
		}, "Timeout waiting for subscriber track to increase its bytesReceived from " + previousBytesReceived);
	}

	// A subscriber video is only properly received AND played if its decoder keeps
	// producing new frames at a sustained rate. Receiving bytes is not enough: a
	// subscriber may receive media that it is not able to decode at all. And a
	// single new decoded frame is not enough either: a video that only decodes one
	// or two frames over a timespan of several seconds is a frozen video, not a
	// playing one, and must fail the test. So framesDecoded is required to grow at
	// MIN_FRAMES_DECODED_FPS or more, averaged over a window of at least
	// MIN_FRAMES_DECODED_WINDOW_MILLIS
	protected void waitUntilSubscriberFramesDecodedIncrease(OpenViduTestappUser user, WebElement videoElement) {
		final long initialFramesDecoded = this.getSubscriberVideoFramesDecoded(user, videoElement);
		final long initialFramesReceived = this.getSubscriberVideoFramesReceived(user, videoElement);
		final long windowStart = System.currentTimeMillis();
		// Last sample taken by the loop, only to report it if the wait times out
		final java.util.concurrent.atomic.AtomicLong lastFramesDecoded = new java.util.concurrent.atomic.AtomicLong();
		final java.util.concurrent.atomic.AtomicLong lastFramesReceived = new java.util.concurrent.atomic.AtomicLong();
		final java.util.concurrent.atomic.AtomicLong lastWindowMillis = new java.util.concurrent.atomic.AtomicLong();
		this.waitUntilAux(user, videoElement, () -> {
			// Both counters must come from the very same dialog update: sampling
			// them one by one would double the cost of every iteration
			JsonObject layer = this.getSubscriberVideoLayer(user, videoElement);
			long framesDecoded = this.getLayerCounter(layer, "framesDecoded");
			long framesReceived = this.getLayerCounter(layer, "framesReceived");
			if (framesDecoded < 0 || framesReceived < 0) {
				return false;
			}
			long windowMillis = System.currentTimeMillis() - windowStart;
			lastFramesDecoded.set(framesDecoded - initialFramesDecoded);
			lastFramesReceived.set(framesReceived - initialFramesReceived);
			lastWindowMillis.set(windowMillis);
			// The window keeps growing while waiting, so a video that decodes a
			// frame every now and then falls further behind the required rate
			// instead of eventually satisfying it
			return windowMillis >= MIN_FRAMES_DECODED_WINDOW_MILLIS
					&& lastFramesDecoded.get() * 1000 >= MIN_FRAMES_DECODED_FPS * windowMillis;
		}, () -> {
			long framesDecoded = lastFramesDecoded.get();
			long framesReceived = lastFramesReceived.get();
			long windowMillis = lastWindowMillis.get();
			// framesReceived counts the frames the depacketizer assembled, before
			// handing them to the decoder. Comparing it against framesDecoded tells
			// apart three failures that otherwise all look like "no video"
			String diagnosis;
			if (framesReceived <= 0) {
				diagnosis = "The subscriber is not receiving assembled frames at all:"
						+ " the media is not reaching it";
			} else if (framesDecoded <= 0) {
				diagnosis = "The subscriber IS receiving assembled frames (" + framesReceived
						+ ") but decoded none of them: the media that reaches it is undecodable"
						+ " (a Producer bound to the wrong codec, or a missing or wrong dependency"
						+ " descriptor)";
			} else {
				diagnosis = "The subscriber received " + framesReceived + " assembled frame(s) and decoded "
						+ framesDecoded + " of them, but too slowly for a video that is actually playing";
			}
			return "Timeout waiting for subscriber track to decode video at a sustained frame rate: only "
					+ framesDecoded + " frame(s) decoded in " + windowMillis + " ms ("
					+ String.format("%.2f", framesDecoded * 1000d / Math.max(1, windowMillis))
					+ " fps), while at least " + MIN_FRAMES_DECODED_FPS
					+ " fps are required. Such a subscriber video is a frozen video. " + diagnosis;
		});
	}

	protected void waitUntilPublisherBytesSentIncrease(OpenViduTestappUser user, WebElement videoElement, String rid,
			final long previousBytesSent) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getPublisherVideoLayerAttribute(user, videoElement, rid, "bytesSent")
					.getAsLong() > previousBytesSent;
		}, "Timeout waiting for publisher track to increase its bytesSent from " + previousBytesSent);
	}

	protected void waitUntilPublisherFramesEncodedIncrease(OpenViduTestappUser user, WebElement videoElement,
			String rid,
			final long previousFramesEncoded) {
		this.waitUntilAux(user, videoElement, () -> {
			return this.getPublisherVideoLayerAttribute(user, videoElement, rid, "framesEncoded")
					.getAsLong() > previousFramesEncoded;
		}, "Timeout waiting for publisher track to increase its framesEncoded from " + previousFramesEncoded);
	}

	protected void waitUntilPublisherLayerActive(OpenViduTestappUser user, final WebElement publisherVideo,
			final String rid, final boolean active) {
		this.waitUntilAux(user, publisherVideo, () -> {
			boolean currentlyActive = this.getPublisherVideoLayerAttribute(user, publisherVideo, rid, "active")
					.getAsBoolean();
			if (active) {
				JsonElement frameWidth = this.getPublisherVideoLayerAttribute(user, publisherVideo, rid, "frameWidth");
				return currentlyActive && frameWidth != null;
			} else {
				return !currentlyActive;
			}
		}, "Timeout waiting for video track layer to be " + (active ? "active" : "inactive"));
	}

	protected void waitUntilAux(OpenViduTestappUser user, WebElement videoElement,
			Callable<Boolean> breakFromLoopFunction, String errMsg) {
		this.waitUntilAux(user, videoElement, breakFromLoopFunction, () -> errMsg);
	}

	// Same as above, but building the error message only if the wait times out, so
	// that it can report the values actually observed by the last iteration
	protected void waitUntilAux(OpenViduTestappUser user, WebElement videoElement,
			Callable<Boolean> breakFromLoopFunction, java.util.function.Supplier<String> errMsg) {
		try {
			final long intervalWait = 250;
			final long deadline = System.currentTimeMillis() + WAIT_UNTIL_MAX_MILLIS;
			boolean breakFromLoop = false;
			while (!breakFromLoop && System.currentTimeMillis() < deadline) {
				try {
					breakFromLoop = breakFromLoopFunction.call();
				} catch (Exception e1) {
					e1.printStackTrace();
				}
				if (breakFromLoop) {
					break;
				} else {
					try {
						Thread.sleep(intervalWait);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			if (!breakFromLoop) {
				Assertions.fail(errMsg.get());
			}
		} finally {
			// Best-effort close of the info dialog
			try {
				if (!user.getDriver().findElements(By.cssSelector("#close-dialog-btn")).isEmpty()) {
					this.waitForBackdropAndClick(user, "#close-dialog-btn");
					Thread.sleep(500);
				}
			} catch (Exception e) {
				log.warn("Best-effort info-dialog close failed (ignored): {}", e.getMessage());
			}
		}
	}

	protected void openInfoDialog(OpenViduTestappUser user, WebElement video) {
		String videoId = video.getDomProperty("id");
		// Open the track info dialog if required
		boolean dialogWasOpened;
		if (!user.getDriver().findElements(By.cssSelector("app-info-dialog")).isEmpty()) {
			// Dialog already opened
			if (!user.getDriver().findElement(By.cssSelector("#subtitle")).getText().equals(videoId)) {
				// Wrong dialog
				this.waitForBackdropAndClick(user, "#close-dialog-btn");
				this.waitForBackdropAndClick(user, "#" + videoId + " ~ .bottom-div .video-track-info");
				dialogWasOpened = true;
			} else {
				dialogWasOpened = false;
			}
		} else {
			// Dialog is not opened
			this.waitForBackdropAndClick(user, "#" + videoId + " ~ .bottom-div .video-track-info");
			dialogWasOpened = true;
		}
		if (dialogWasOpened) {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	protected void addPublisherSubscriber(OpenViduTestappUser user, boolean hasAudio, boolean hasVideo)
			throws InterruptedException {
		this.addPublisher(user, true, true, true, true, hasAudio, hasVideo, null, null, null);
	}

	protected void addOnlyPublisherVideo(OpenViduTestappUser user, boolean simulcast, boolean dynacast, boolean hd)
			throws InterruptedException {
		if (hd) {
			this.addPublisher(user, false, simulcast, dynacast, false, false, true, 1920, 1080, null);
		} else {
			this.addPublisher(user, false, simulcast, dynacast, false, false, true, null, null, null);
		}
	}

	protected void addOnlyPublisherVideo(OpenViduTestappUser user, boolean simulcast, boolean dynacast, boolean hd,
			String scalabilityMode)
			throws InterruptedException {
		if (hd) {
			this.addPublisher(user, false, simulcast, dynacast, false, false, true, 1920, 1080, scalabilityMode);
		} else {
			this.addPublisher(user, false, simulcast, dynacast, false, false, true, null, null, null);
		}
	}

	protected void addOnlyPublisherAudio(OpenViduTestappUser user) throws InterruptedException {
		this.addPublisher(user, false, false, false, false, true, false, null, null, null);
	}

	protected void addPublisher(OpenViduTestappUser user, boolean isSubscriber, boolean simulcast, boolean dynacast,
			boolean adaptiveStream, boolean hasAudio, boolean hasVideo, Integer width, Integer height,
			String scalabilityMode) throws InterruptedException {
		if (!user.getDriver().findElements(By.id("close-dialog-btn")).isEmpty()) {
			user.getDriver().findElement(By.id("close-dialog-btn")).click();
			Thread.sleep(300);
		}
		final int previousInstances = user.getDriver().findElements(By.cssSelector("app-openvidu-instance")).size();
		user.getDriver().findElement(By.id("add-user-btn")).click();
		// The new instance is rendered asynchronously: counting the instances right
		// after the click can still see only the previous ones, and then every
		// "#openvidu-instance-<index>" selector built from that count is off by one
		// (with a single instance it even becomes "#openvidu-instance--1")
		user.getWaiter().until(ExpectedConditions.numberOfElementsToBe(By.cssSelector("app-openvidu-instance"),
				previousInstances + 1));
		int numberOfUser = previousInstances;
		if (!isSubscriber) {
			user.getDriver().findElement(By.cssSelector("#openvidu-instance-" + numberOfUser + " .subscriber-checkbox"))
					.click();
		}
		this.waitForBackdropAndClick(user, "#room-options-btn-" + numberOfUser);
		Thread.sleep(300);
		if (!hasAudio) {
			user.getDriver().findElement(By.id("audio-capture-false")).click();
		} else {
			user.getDriver().findElement(By.id("audio-capture-true")).click();
		}
		if (!hasVideo) {
			user.getDriver().findElement(By.id("video-capture-false")).click();
		} else {
			user.getDriver().findElement(By.id("video-capture-true")).click();
			if (width != null || height != null || scalabilityMode != null) {
				this.setPublisherCustomVideoProperties(user, width, height, scalabilityMode);
			}
		}
		if (!simulcast) {
			user.getDriver().findElement(By.id("trackPublish-simulcast")).click();
		}
		if (!dynacast) {
			user.getDriver().findElement(By.id("room-dynacast")).click();
		}
		if (!adaptiveStream) {
			user.getDriver().findElement(By.id("room-adaptiveStream")).click();
		}
		user.getDriver().findElement(By.id("close-dialog-btn")).click();
		Thread.sleep(300);
	}

	protected void addSubscriber(OpenViduTestappUser user, boolean adaptiveStream) throws InterruptedException {
		if (!user.getDriver().findElements(By.id("close-dialog-btn")).isEmpty()) {
			user.getDriver().findElement(By.id("close-dialog-btn")).click();
			Thread.sleep(300);
		}
		final int previousInstances = user.getDriver().findElements(By.cssSelector("app-openvidu-instance")).size();
		user.getDriver().findElement(By.id("add-user-btn")).click();
		// The new instance is rendered asynchronously: counting the instances right
		// after the click can still see only the previous ones, and then every
		// "#openvidu-instance-<index>" selector built from that count is off by one
		// (with a single instance it even becomes "#openvidu-instance--1")
		user.getWaiter().until(ExpectedConditions.numberOfElementsToBe(By.cssSelector("app-openvidu-instance"),
				previousInstances + 1));
		int numberOfUser = previousInstances;
		user.getDriver().findElement(By.cssSelector("#openvidu-instance-" + numberOfUser + " .publisher-checkbox"))
				.click();
		if (!adaptiveStream) {
			this.waitForBackdropAndClick(user, "#room-options-btn-" + numberOfUser);
			this.waitForBackdropAndClick(user, "#room-adaptiveStream");
			user.getDriver().findElement(By.id("close-dialog-btn")).click();
			Thread.sleep(300);
		}
	}

	protected void createIngress(OpenViduTestappUser user, String preset, String codec, boolean simulcast,
			String urlType,
			String urlUri) throws InterruptedException {
		if (!user.getDriver().findElements(By.id("close-dialog-btn")).isEmpty()) {
			this.waitForBackdropAndClick(user, "#close-dialog-btn");
			Thread.sleep(300);
		}
		user.getDriver().findElement(By.xpath("//button[contains(@title,'Room API')]")).click();
		if (preset != null) {
			this.waitForBackdropAndClick(user, "#ingress-preset-select");
			this.waitForBackdropAndClick(user, "#mat-option-" + preset.toUpperCase());
		} else {
			if (!simulcast) {
				this.waitForBackdropAndClick(user, "#ingress-simulcast");
				Thread.sleep(300);
			}
			this.waitForBackdropAndClick(user, "#ingress-video-codec-select");
			this.waitForBackdropAndClick(user, "#mat-option-" + codec.toUpperCase());
		}
		if (urlType != null) {
			this.waitForBackdropAndClick(user, "#ingress-url-type-select");
			this.waitForBackdropAndClick(user, "#mat-option-" + urlType.toUpperCase());
		}
		if (urlUri != null) {
			user.getDriver().findElement(By.cssSelector("#ingress-url-uri-field")).sendKeys(urlUri);
			Thread.sleep(300);
		}
		this.waitForBackdropAndClick(user, "#create-ingress-api-btn");
		this.waitForBackdropAndClick(user, "#close-dialog-btn");
		Thread.sleep(300);
	}

	protected void setPublisherSimulcastLayersAndResolution(OpenViduTestappUser user, int numberOfUser,
			String simulcastLayerName, Integer width, Integer height) throws InterruptedException {
		this.waitForBackdropAndClick(user, "#room-options-btn-" + numberOfUser);
		Thread.sleep(300);
		this.setPublisherCustomVideoProperties(user, width, height, null);
		user.getDriver().findElement(By.id("trackPublish-videoSimulcastLayers")).click();
		this.waitForBackdropAndClick(user, "#mat-option-" + simulcastLayerName);
		new org.openqa.selenium.interactions.Actions(user.getDriver())
				.sendKeys(org.openqa.selenium.Keys.ESCAPE).perform();
		Thread.sleep(300);
		this.waitForBackdropAndClick(user, "#close-dialog-btn");
		Thread.sleep(300);
	}

	protected void setPublisherCustomVideoProperties(OpenViduTestappUser user, Integer width, Integer height,
			String scalabilityMode) {
		user.getDriver().findElement(By.id("video-capture-custom")).click();
		if (width != null) {
			WebElement trackWidth = user.getDriver().findElement(By.id("resolution-video-capture-options-width"));
			trackWidth.clear();
			trackWidth.sendKeys(width.toString());
		}
		if (height != null) {
			WebElement trackHeight = user.getDriver().findElement(By.id("resolution-video-capture-options-height"));
			trackHeight.clear();
			trackHeight.sendKeys(height.toString());
		}
		if (scalabilityMode != null) {
			user.getDriver().findElement(By.id("trackPublish-scalabilityMode")).click();
			this.waitForBackdropAndClick(user, ".mode-" + scalabilityMode);
		}
	}

	/**
	 * Waits for any Material Design backdrop overlays to disappear and then clicks
	 * the element. This prevents ElementClickInterceptedException caused by overlay
	 * backdrops.
	 */
	protected void waitForBackdropAndClick(OpenViduTestappUser user, String cssSelector) {
		final long startTime = System.currentTimeMillis();
		final long timeoutMillis = 10000; // 10 seconds total timeout
		final long retryIntervalMillis = 500; // 500ms between retries

		WebElement element = null;

		while (System.currentTimeMillis() - startTime < timeoutMillis) {
			try {
				// Try to find and click the element immediately
				element = user.getDriver().findElement(By.cssSelector(cssSelector));
				if (element.isDisplayed() && element.isEnabled()) {
					element.click();
					return; // Success! Exit the method
				}
			} catch (org.openqa.selenium.ElementClickInterceptedException e) {
				// Element is being intercepted by overlay, continue retrying
			} catch (org.openqa.selenium.NoSuchElementException e) {
				// Element not found, wait a bit and retry
			} catch (org.openqa.selenium.StaleElementReferenceException e) {
				// Element reference is stale, retry with fresh element
			} catch (Exception e) {
				// Any other exception, continue retrying
			}

			// Wait before next retry
			try {
				Thread.sleep(retryIntervalMillis);
			} catch (InterruptedException e) {
				// Print screenshot
				String screenshot = "data:image/png;base64,"
						+ ((TakesScreenshot) user.getDriver()).getScreenshotAs(BASE64);
				System.out.println("INTERRUPTED EXCEPTION WHILE WAITING FOR ELEMENT TO BE CLICKABLE: " + cssSelector);
				System.out.println(screenshot);
				Thread.currentThread().interrupt();
				throw new RuntimeException("Thread interrupted while waiting for backdrop to clear", e);
			}
		}

		String screenshot = "data:image/png;base64," + ((TakesScreenshot) user.getDriver()).getScreenshotAs(BASE64);
		System.out.println("TIMEOUT WAITING FOR ELEMENT TO BE CLICKABLE (): " + cssSelector);
		System.out.println(screenshot);

		// If we get here, we've timed out
		throw new RuntimeException("Timeout waiting for element '" + cssSelector
				+ "' to be clickable without backdrop interference after " + timeoutMillis + "ms");
	}

	protected boolean assertAllElementsHaveTracks(OpenViduTestappUser user, String selector, boolean hasAudio,
			boolean hasVideo) {
		org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) user.getDriver();
		String script = "var elements = document.querySelectorAll(arguments[0]);" +
				"for (var i = 0; i < elements.length; i++) {" +
				"    var el = elements[i];" +
				"    if (!el.srcObject) return false;" +
				"    if (arguments[1] && el.srcObject.getAudioTracks().length === 0) return false;" +
				"    if (!arguments[1] && el.srcObject.getAudioTracks().length > 0) return false;" +
				"    if (arguments[2] && el.srcObject.getVideoTracks().length === 0) return false;" +
				"    if (!arguments[2] && el.srcObject.getVideoTracks().length > 0) return false;" +
				"}" +
				"return true;";
		return (Boolean) js.executeScript(script, selector, hasAudio, hasVideo);
	}

	protected void changeElementSize(OpenViduTestappUser user, org.openqa.selenium.WebElement element, int width,
			int height) {
		org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) user.getDriver();
		js.executeScript(
				"arguments[0].style.width = '" + width + "px'; arguments[0].style.height = '" + height + "px';",
				element);
	}

	/**
	 * Waits until the subscriber's video bytesReceived grows between two
	 * consecutive samples. Unlike waitUntilSubscriberBytesReceivedIncrease (which
	 * compares against the very first sample) this tolerates the periodic
	 * inbound-rtp counter restarts Firefox shows with the mediasoup engine: a
	 * low-bitrate stream could otherwise never climb back above a first sample
	 * taken late in a counter window.
	 */
	protected void waitUntilSubscriberBytesReceivedIncreasing(OpenViduTestappUser user, WebElement videoElement) {
		final java.util.concurrent.atomic.AtomicLong previous = new java.util.concurrent.atomic.AtomicLong(
				this.getSubscriberVideoBytesReceived(user, videoElement));
		this.waitUntilAux(user, videoElement, () -> {
			long current = this.getSubscriberVideoBytesReceived(user, videoElement);
			return current > previous.getAndSet(current);
		}, "Timeout waiting for the subscriber track bytesReceived to grow between consecutive samples");
	}
}
