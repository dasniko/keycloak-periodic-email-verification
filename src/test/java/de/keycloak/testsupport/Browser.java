package de.keycloak.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Minimal browser simulation: keeps cookies, follows redirects manually and stops at the redirect URI of the test client.
 */
public class Browser {

	private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*\\saction=\"([^\"]+)\"");

	private final String authorizationUrl;
	private final HttpClient client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	// The cookies are handled here, as java.net.CookieManager does not send cookies without a domain to "localhost".
	private final Map<String, String> cookies = new LinkedHashMap<>();

	public Browser(String authorizationUrl) {
		this.authorizationUrl = authorizationUrl;
	}

	public record Page(int status, URI uri, String body) {

		/**
		 * @return {@code true}, if the redirect URI of the test client has been reached, i.e. the login has been completed.
		 */
		public boolean isLoginCompleted() {
			return uri.toString().startsWith(KeycloakEnvironment.REDIRECT_URI);
		}

		/**
		 * @return the value of the {@code execution} query parameter, i.e. the required action shown, or {@code null}.
		 */
		public String execution() {
			return queryParam("execution");
		}

		public String queryParam(String name) {
			String query = uri.getRawQuery();
			if (query == null) {
				return null;
			}
			for (String param : query.split("&")) {
				String[] keyValue = param.split("=", 2);
				if (keyValue[0].equals(name)) {
					return keyValue.length > 1 ? keyValue[1] : "";
				}
			}
			return null;
		}

		/**
		 * @return the action URL of the first form of the page.
		 */
		public String formAction() {
			return firstGroup(FORM_ACTION, body);
		}

		/**
		 * @return the first href of the page containing the given text.
		 */
		public String linkContaining(String text) {
			return firstGroup(Pattern.compile("href=\"([^\"]*" + Pattern.quote(text) + "[^\"]*)\""), body);
		}
	}

	public Page get(String url) {
		return follow(send(request(URI.create(url)).GET().build()));
	}

	public Page post(String url, Map<String, String> form) {
		String body = form.entrySet().stream()
			.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
		HttpRequest request = request(URI.create(url))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return follow(send(request));
	}

	/**
	 * Starts the authorization code flow of the test client and submits the credentials.
	 *
	 * @return the page the flow ends at: either the redirect URI (login completed) or the page of a required action.
	 */
	public Page login(String username, String password) {
		Page loginPage = get(authorizationUrl);
		if (loginPage.status() != 200 || loginPage.formAction() == null) {
			throw new IllegalStateException("Login page expected, but got status " + loginPage.status() + " at " + loginPage.uri());
		}
		return post(loginPage.formAction(), Map.of("username", username, "password", password));
	}

	private HttpRequest.Builder request(URI uri) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
		if (!cookies.isEmpty()) {
			builder.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
		}
		return builder;
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			storeCookies(response);
			return response;
		} catch (IOException e) {
			throw new IllegalStateException("Request failed: " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted: " + request.uri(), e);
		}
	}

	private void storeCookies(HttpResponse<?> response) {
		for (String setCookie : response.headers().allValues("Set-Cookie")) {
			String[] parts = setCookie.split(";");
			String[] nameValue = parts[0].split("=", 2);
			String name = nameValue[0].trim();
			String value = nameValue.length > 1 ? nameValue[1].trim() : "";
			boolean expired = value.isEmpty() || setCookie.toLowerCase().contains("max-age=0") || setCookie.contains("1970");
			if (expired) {
				cookies.remove(name);
			} else {
				cookies.put(name, value);
			}
		}
	}

	private Page follow(HttpResponse<String> response) {
		int hops = 0;
		while (isRedirect(response) && hops++ < 20) {
			URI target = response.uri().resolve(response.headers().firstValue("Location").orElseThrow());
			if (target.toString().startsWith(KeycloakEnvironment.REDIRECT_URI)) {
				return new Page(response.statusCode(), target, "");
			}
			response = send(request(target).GET().build());
		}
		return new Page(response.statusCode(), response.uri(), response.body());
	}

	private static boolean isRedirect(HttpResponse<?> response) {
		return response.statusCode() >= 300 && response.statusCode() < 400 && response.headers().firstValue("Location").isPresent();
	}

	private static String firstGroup(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1).replace("&amp;", "&") : null;
	}
}
