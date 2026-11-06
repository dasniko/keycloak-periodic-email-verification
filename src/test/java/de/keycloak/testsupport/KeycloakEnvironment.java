package de.keycloak.testsupport;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.restassured.RestAssured;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.Version;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionConfigRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Base class for tests against a Keycloak container with the extension under test and a Mailpit container as mail catcher.
 * <p>
 * The containers are managed by the Testcontainers JUnit extension and are started (and stopped) for each test class.
 * The realm is configured once after the start; the helper methods operate on the running containers.
 */
@Testcontainers
public abstract class KeycloakEnvironment {

	public static final String REALM = "test";
	public static final String CLIENT_ID = "test-client";
	public static final String REDIRECT_URI = "http://localhost/callback";
	public static final String VERIFY_EMAIL = "VERIFY_EMAIL";
	public static final String EMAIL_VERIFIED_AT = "emailVerifiedAt";

	private static final String PASSWORD = "secret";
	private static final String MAILPIT_ALIAS = "mailpit";
	private static final int MAILPIT_SMTP_PORT = 1025;
	private static final int MAILPIT_HTTP_PORT = 8025;

	private static final Network network = Network.newNetwork();

	@Container
	private static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:" + Version.VERSION)
		.withNetwork(network)
		.withDefaultProviderClasses()
		.withRealmImportFile("test-realm.json");

	@Container
	private static final GenericContainer<?> mailpitContainer = new GenericContainer<>("axllent/mailpit:latest")
		.withNetwork(network)
		.withNetworkAliases(MAILPIT_ALIAS)
		.withExposedPorts(MAILPIT_SMTP_PORT, MAILPIT_HTTP_PORT)
		.waitingFor(Wait.forListeningPorts(MAILPIT_SMTP_PORT, MAILPIT_HTTP_PORT));

	private static RealmResource realm;
	private static Mailpit mailpit;

	@BeforeAll
	static void configureRealm() {
		mailpit = new Mailpit("http://" + mailpitContainer.getHost() + ":" + mailpitContainer.getMappedPort(MAILPIT_HTTP_PORT));
		realm = keycloak.getKeycloakAdminClient().realm(REALM);

		// declare the attribute emailVerifiedAt in the user profile, editable by administrators only (users must not change it)
		UPConfig userProfile = realm.users().userProfile().getConfiguration();
		UPAttributePermissions permissions = new UPAttributePermissions();
		permissions.setView(Set.of("admin"));
		permissions.setEdit(Set.of("admin"));
		userProfile.addOrReplaceAttribute(new UPAttribute(EMAIL_VERIFIED_AT, permissions));
		// the email is required by default, but users without an email address are part of the tests
		userProfile.getAttribute("email").setRequired(null);
		realm.users().userProfile().update(userProfile);

		RealmRepresentation representation = realm.toRepresentation();
		representation.setSmtpServer(Map.of("host", MAILPIT_ALIAS, "port", String.valueOf(MAILPIT_SMTP_PORT), "from", "keycloak@keycloak.test"));
		realm.update(representation);

		ClientRepresentation client = new ClientRepresentation();
		client.setClientId(CLIENT_ID);
		client.setPublicClient(true);
		client.setStandardFlowEnabled(true);
		client.setDirectAccessGrantsEnabled(true);
		client.setRedirectUris(List.of(REDIRECT_URI));
		try (Response response = realm.clients().create(client)) {
			if (response.getStatus() != 201) {
				throw new IllegalStateException("Client not created: " + response.getStatus());
			}
		}
	}

	protected static RealmResource realm() {
		return realm;
	}

	protected static Mailpit mailpit() {
		return mailpit;
	}

	/**
	 * @return the base URL of the realm, e.g. {@code http://localhost:12345/realms/test}
	 */
	protected static String realmUrl() {
		return keycloak.getAuthServerUrl() + "/realms/" + REALM;
	}

	/**
	 * Requests a token for the user by the direct access grant (resource owner password credentials) of the test client.
	 */
	protected static io.restassured.response.Response tokenResponse(TestUser user) {
		return RestAssured.given()
			.formParam("grant_type", "password")
			.formParam("client_id", CLIENT_ID)
			.formParam("username", user.username())
			.formParam("password", user.password())
			.post(realmUrl() + "/protocol/openid-connect/token");
	}

	/**
	 * Gets an access token of the user by the direct access grant of the test client.
	 */
	protected static String accessToken(TestUser user) {
		return tokenResponse(user).then().statusCode(200).extract().path("access_token");
	}

	/**
	 * @return a new browser without any cookies.
	 */
	protected static Browser newBrowser() {
		return new Browser(keycloak.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/auth"
			+ "?client_id=" + CLIENT_ID + "&response_type=code&scope=openid&redirect_uri=" + REDIRECT_URI + "&state=test");
	}

	protected static void updateRequiredActionConfig(Map<String, String> config) {
		RequiredActionConfigRepresentation representation = new RequiredActionConfigRepresentation();
		representation.setConfig(new HashMap<>(config));
		realm.flows().updateRequiredActionConfig(VERIFY_EMAIL, representation);
	}

	/**
	 * Creates a user with a unique name (and email address, if wanted).
	 *
	 * @param emailVerified   the initial value of {@code emailVerified}
	 * @param withEmail       whether the user has an email address
	 * @param emailVerifiedAt the initial value of the attribute {@value #EMAIL_VERIFIED_AT} (epoch millis), or {@code null}
	 */
	protected static TestUser createUser(boolean emailVerified, boolean withEmail, Long emailVerifiedAt) {
		String name = "user-" + UUID.randomUUID();
		String email = withEmail ? name + "@keycloak.test" : null;

		CredentialRepresentation credential = new CredentialRepresentation();
		credential.setType(CredentialRepresentation.PASSWORD);
		credential.setValue(PASSWORD);
		credential.setTemporary(false);

		UserRepresentation user = new UserRepresentation();
		user.setUsername(name);
		user.setEmail(email);
		user.setFirstName("Theo");
		user.setLastName("Tester");
		user.setEnabled(true);
		user.setEmailVerified(emailVerified);
		user.setCredentials(List.of(credential));
		if (emailVerifiedAt != null) {
			user.setAttributes(Map.of(EMAIL_VERIFIED_AT, List.of(String.valueOf(emailVerifiedAt))));
		}

		try (Response response = realm.users().create(user)) {
			if (response.getStatus() != 201) {
				throw new IllegalStateException("User not created: " + response.getStatus() + " " + response.readEntity(String.class));
			}
			String path = response.getLocation().getPath();
			return new TestUser(path.substring(path.lastIndexOf('/') + 1), name, email, PASSWORD);
		}
	}

	protected static UserRepresentation reload(TestUser user) {
		return realm.users().get(user.id()).toRepresentation();
	}

	public record TestUser(String id, String username, String email, String password) {
	}
}
