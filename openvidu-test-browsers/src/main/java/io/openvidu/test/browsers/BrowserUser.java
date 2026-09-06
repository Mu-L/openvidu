/*
 * (C) Copyright 2017-2022 OpenVidu (https://openvidu.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.openvidu.test.browsers;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.LoggerFactory;

public class BrowserUser {

	protected static final org.slf4j.Logger log = LoggerFactory.getLogger(BrowserUser.class);

	protected WebDriver driver;
	protected WebDriverWait waiter;
	protected String clientData;
	protected int timeOfWaitInSeconds;

	public BrowserUser(String clientData, int timeOfWaitInSeconds) {
		this.clientData = clientData;
		this.timeOfWaitInSeconds = timeOfWaitInSeconds;
	}

	public WebDriver getDriver() {
		return this.driver;
	}

	public WebDriverWait getWaiter() {
		return this.waiter;
	}

	public String getClientData() {
		return this.clientData;
	}

	public int getTimeOfWait() {
		return this.timeOfWaitInSeconds;
	}

	protected void newWaiter(int timeOfWait) {
		this.waiter = new WebDriverWait(this.driver, Duration.ofSeconds(timeOfWait));
	}

	protected void configureDriver() {
		this.waiter = new WebDriverWait(this.driver, Duration.ofSeconds(timeOfWaitInSeconds));
		try {
			// Bound page loads to the user's wait time.
			this.driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(timeOfWaitInSeconds));
		} catch (WebDriverException e) {
			log.warn("Could not set the page load timeout on this driver: {}", e.getMessage());
		}
	}

	/**
	 * How long a remote WebDriver endpoint may take to report itself ready before
	 * the browser setup gives up with an explicit error.
	 */
	private static final Duration REMOTE_READY_TIMEOUT = Duration.ofSeconds(60);

	private static final Pattern REMOTE_READY = Pattern.compile("\"ready\"\\s*:\\s*true");

	/**
	 * Creates the remote session once the endpoint reports ready, retrying once if
	 * the first attempt dies on a connection error.
	 */
	protected static RemoteWebDriver newRemoteWebDriver(URL remoteUrl, Capabilities capabilities, String browser) {
		waitForRemoteWebDriverReady(remoteUrl, browser);
		try {
			return new RemoteWebDriver(remoteUrl, capabilities);
		} catch (SessionNotCreatedException e) {
			Throwable connectionError = connectionErrorOf(e);
			if (connectionError == null) {
				throw e;
			}
			log.warn("Creating the remote {} session at {} failed on a connection error ({}: {}), retrying once",
					browser, remoteUrl, connectionError.getClass().getSimpleName(), connectionError.getMessage());
			sleepQuietly(Duration.ofSeconds(4));
			waitForRemoteWebDriverReady(remoteUrl, browser);
			return new RemoteWebDriver(remoteUrl, capabilities);
		}
	}

	/**
	 * Polls the endpoint's {@code status} resource until it answers HTTP 200 with
	 * {@code "ready": true}, or fails after {@link #REMOTE_READY_TIMEOUT} quoting
	 * the last answer.
	 */
	protected static void waitForRemoteWebDriverReady(URL remoteUrl, String browser) {
		String base = remoteUrl.toString();
		String statusUrl = base.endsWith("/") ? base + "status" : base + "/status";
		Instant start = Instant.now();
		Instant deadline = start.plus(REMOTE_READY_TIMEOUT);
		String lastAnswer = "no answer yet";
		while (true) {
			try {
				HttpURLConnection connection = (HttpURLConnection) new URL(statusUrl).openConnection();
				connection.setConnectTimeout(2000);
				connection.setReadTimeout(2000);
				int code = connection.getResponseCode();
				String body = readBody(connection, code);
				if (code == 200 && REMOTE_READY.matcher(body).find()) {
					long waitedMs = Duration.between(start, Instant.now()).toMillis();
					if (waitedMs > 1000) {
						log.info("Remote {} WebDriver endpoint {} became ready after {} ms", browser, statusUrl,
								waitedMs);
					}
					return;
				}
				lastAnswer = "HTTP " + code + " " + body.replaceAll("\\s+", " ").trim();
			} catch (IOException e) {
				lastAnswer = e.getClass().getSimpleName() + ": " + e.getMessage();
			}
			if (Instant.now().isAfter(deadline)) {
				throw new IllegalStateException("Remote " + browser + " WebDriver endpoint " + statusUrl
						+ " did not report ready within " + REMOTE_READY_TIMEOUT.toSeconds() + " s (last answer: "
						+ lastAnswer + ")");
			}
			sleepQuietly(Duration.ofMillis(250));
		}
	}

	private static String readBody(HttpURLConnection connection, int code) throws IOException {
		try (InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
			if (in == null) {
				return "";
			}
			byte[] bytes = in.readNBytes(4096);
			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	/**
	 * The connection-level cause of a failed session creation
	 */
	private static Throwable connectionErrorOf(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			String name = t.getClass().getSimpleName();
			if (t instanceof ConnectException || t instanceof ClosedChannelException
					|| "ConnectionException".equals(name) || "HttpConnectTimeoutException".equals(name)) {
				return t;
			}
		}
		return null;
	}

	private static void sleepQuietly(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Logs the id of the remote session just created. This helps differentiate
	 * between a hang in the Grid and a hang in the page load.
	 */
	protected void logRemoteSessionCreated(String browser) {
		if (this.driver instanceof RemoteWebDriver) {
			log.info("Remote WebDriver session created [browser: {}, sessionId: {}]", browser,
					((RemoteWebDriver) this.driver).getSessionId());
		}
	}

	protected void configureDriver(Dimension windowDimensions) {
		this.configureDriver();
		if (windowDimensions != null) {
			this.driver.manage().window().setSize(windowDimensions);
		}
	}

	public void waitWithNewTime(int newWaitTime, ExpectedCondition<?> condition) {
		this.waiter.withTimeout(Duration.of(newWaitTime, ChronoUnit.SECONDS));
		this.waiter.until(condition);
		this.waiter.withTimeout(Duration.of(this.getTimeOfWait(), ChronoUnit.SECONDS));
	}

	public void dispose() {
		this.driver.quit();
	}

	public Map<String, Long> getAverageRgbFromVideo(WebElement videoElement) {
		String script = "var callback = arguments[arguments.length - 1];" + "var video = document.getElementById('"
				+ videoElement.getAttribute("id") + "');" + "var canvas = document.createElement('canvas');"
				+ "canvas.height = video.videoHeight;" + "canvas.width = video.videoWidth;"
				+ "var context = canvas.getContext('2d');"
				+ "context.drawImage(video, 0, 0, canvas.width, canvas.height);"
				+ "var imgEl = document.createElement('img');" + "imgEl.src = canvas.toDataURL();"
				+ "var blockSize = 5;" + "var defaultRGB = { r: 0, g: 0, b: 0 };"
				+ "context.drawImage(video, 0, 0, 220, 150);" + "var dataURL = canvas.toDataURL();"
				+ "imgEl.onload = function () {" + "let i = -4;" + "var rgb = { r: 0, g: 0, b: 0 };" + "let count = 0;"
				+ "if (!context) {" + "  return defaultRGB;" + "}"
				+ "var height = canvas.height = imgEl.naturalHeight || imgEl.offsetHeight || imgEl.height;"
				+ "var width = canvas.width = imgEl.naturalWidth || imgEl.offsetWidth || imgEl.width;" + "let data;"
				+ "context.drawImage(imgEl, 0, 0);" + "try {" + "data = context.getImageData(0, 0, width, height);"
				+ "} catch (e) {" + "return defaultRGB;" + "}" + "length = data.data.length;"
				+ "while ((i += blockSize * 4) < length) {" + "++count;" + "rgb.r += data.data[i];"
				+ "rgb.g += data.data[i + 1];" + "rgb.b += data.data[i + 2];" + "}" + "rgb.r = ~~(rgb.r / count);"
				+ "rgb.g = ~~(rgb.g / count);" + "rgb.b = ~~(rgb.b / count);" + "callback(rgb);" + "};";
		Object averageRgb = ((JavascriptExecutor) driver).executeAsyncScript(script);
		return (Map<String, Long>) averageRgb;
	}

	public Map<String, Long> getAverageColorFromPixels(WebElement videoElement, List<Point> pixelPercentagePositions) {
		String script = "var callback = arguments[arguments.length - 1];"
				+ "var points = arguments[arguments.length - 2];" + "points = JSON.parse(points);"
				+ "var video = document.getElementById('local-video-undefined');"
				+ "var canvas = document.createElement('canvas');" + "canvas.height = video.videoHeight;"
				+ "canvas.width = video.videoWidth;" + "var context = canvas.getContext('2d');"
				+ "context.drawImage(video, 0, 0, canvas.width, canvas.height);"
				+ "var imgEl = document.createElement('img');" + "imgEl.src = canvas.toDataURL();"
				+ "var blockSize = 5;" + "var defaultRGB = {r:0,g:0,b:0};" + "context.drawImage(video, 0, 0, 220, 150);"
				+ "var dataURL = canvas.toDataURL();" + "imgEl.onload = function() {" + "    var rgb = {r:0,g:0,b:0};"
				+ "    if (!context) {" + "        return defaultRGB;" + "    }"
				+ "    var height = canvas.height = imgEl.naturalHeight || imgEl.offsetHeight || imgEl.height;"
				+ "    var width = canvas.width = imgEl.naturalWidth || imgEl.offsetWidth || imgEl.width;"
				+ "    let data;" + "    context.drawImage(imgEl, 0, 0);" + "    for (var p of points) {"
				+ "        var xFromPercentage = width * (p.x / 100);"
				+ "        var yFromPercentage = height * (p.y / 100);"
				+ "        data = context.getImageData(xFromPercentage, yFromPercentage, 1, 1).data;"
				+ "        rgb.r += data[0];" + "        rgb.g += data[1];" + "        rgb.b += data[2];" + "    }"
				+ "    rgb.r = ~~(rgb.r / points.length);" + "    rgb.g = ~~(rgb.g / points.length);"
				+ "    rgb.b = ~~(rgb.b / points.length);" + "    callback(rgb);" + "};";
		String points = "[";
		Iterator<Point> it = pixelPercentagePositions.iterator();
		while (it.hasNext()) {
			Point p = it.next();
			points += "{\"x\":" + p.getX() + ",\"y\":" + p.getY() + "}";
			if (it.hasNext()) {
				points += ",";
			}
		}
		points += "]";
		Object averageRgb = ((JavascriptExecutor) driver).executeAsyncScript(script, points);
		return (Map<String, Long>) averageRgb;
	}

	public boolean assertAllElementsHaveTracks(String querySelector, boolean hasAudio, boolean hasVideo) {
		String waitForSrcObject = """
				const sleepUntil = async (f, timeoutMs) => {
				    return new Promise((resolve, reject) => {
				        const timeWas = new Date();
				        const wait = setInterval(function() {
				            if (f()) {
				                clearInterval(wait);
				                resolve();
				            } else if (new Date() - timeWas > timeoutMs) { // Timeout
				                clearInterval(wait);
				                reject();
				            }
				        }, 50);
				    });
				}
				""";
		String calculateReturnValue = "returnValue && ";
		if (hasAudio) {
			calculateReturnValue += "el.srcObject.getAudioTracks().length === 1 && el.srcObject.getAudioTracks()[0].enabled";
		} else {
			calculateReturnValue += "el.srcObject.getAudioTracks().length === 0";
		}
		calculateReturnValue += " && ";
		if (hasVideo) {
			calculateReturnValue += "el.srcObject.getVideoTracks().length === 1 && el.srcObject.getVideoTracks()[0].enabled";
		} else {
			calculateReturnValue += "el.srcObject.getVideoTracks().length === 0";
		}
		String script = waitForSrcObject + """
				var returnValue = true;
				const elements = [...document.querySelectorAll('%s')];
				elements.forEach(async (el) => {
					try {
						await sleepUntil(() => !!el.srcObject, 5000);
						returnValue = %s;
					} catch(error) {
						returnValue = false;
						console.error('Error waiting for srcObject to be defined');
						throw error;
					}
				});
				return returnValue;""".formatted(querySelector, calculateReturnValue);
		boolean tracks = (boolean) ((JavascriptExecutor) driver).executeScript(script);
		return tracks;
	}

	public void changeElementSize(WebElement videoElement, Integer newWidthInPixels, Integer newHeightInPixels) {
		String script = "var htmlelement = document.querySelector('#" + videoElement.getAttribute("id") + "');";
		if (newWidthInPixels != null) {
			script += "htmlelement.style.width = '" + newWidthInPixels + "px';";
		}
		if (newHeightInPixels != null) {
			script += "htmlelement.style.height = '" + newHeightInPixels + "px';";
		}
		((JavascriptExecutor) driver).executeScript(script);
	}

}