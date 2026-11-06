package de.keycloak.testsupport;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Access to the messages received by the Mailpit container, via its REST API.
 */
public class Mailpit {

	private static final Pattern ACTION_TOKEN_LINK = Pattern.compile("https?://\\S+/action-token\\S*");

	private final String apiUrl;

	public Mailpit(String apiUrl) {
		this.apiUrl = apiUrl;
	}

	/**
	 * @return the number of messages sent to the given address.
	 */
	public int count(String recipient) {
		return searchIds(recipient).size();
	}

	/**
	 * Waits for the given number of messages sent to the given address and returns the text of the last one.
	 */
	public String awaitMessageText(String recipient, int expectedCount) {
		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		List<String> ids;
		do {
			ids = searchIds(recipient);
			if (ids.size() >= expectedCount) {
				return RestAssured.get(apiUrl + "/api/v1/message/" + ids.getLast()).jsonPath().getString("Text");
			}
			sleep();
		} while (System.nanoTime() < deadline);
		throw new IllegalStateException("Expected " + expectedCount + " mail(s) for " + recipient + ", but received " + ids.size());
	}

	/**
	 * @return the action token link (e.g. to verify the email) contained in the text of a message.
	 */
	public static String actionTokenLink(String messageText) {
		Matcher matcher = ACTION_TOKEN_LINK.matcher(messageText);
		if (!matcher.find()) {
			throw new IllegalStateException("No action token link found in: " + messageText);
		}
		return matcher.group().replace("&amp;", "&");
	}

	private List<String> searchIds(String recipient) {
		JsonPath json = RestAssured.given().queryParam("query", "to:" + recipient).get(apiUrl + "/api/v1/search").jsonPath();
		List<String> ids = json.getList("messages.ID");
		return ids == null ? List.of() : ids;
	}

	private static void sleep() {
		try {
			Thread.sleep(250);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
