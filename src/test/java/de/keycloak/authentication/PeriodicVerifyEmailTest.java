package de.keycloak.authentication;

import de.keycloak.testsupport.Browser;
import de.keycloak.testsupport.KeycloakEnvironment;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.requiredactions.util.EmailCooldownManager;
import org.keycloak.models.Constants;
import org.keycloak.representations.idm.ConfigPropertyRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodicVerifyEmailTest extends KeycloakEnvironment {

	private static final String PERIOD_AMOUNT = "email-verify-period-amount";
	private static final String PERIOD_UNIT = "email-verify-period-unit";

	@BeforeEach
	void switchOffPeriodicVerification() {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "0", PERIOD_UNIT, "DAYS"));
	}

	// --- configuration ---

	@Test
	void configurationOffersUpstreamOptionsFirstAndOwnOptionsAfterwards() {
		List<String> names = realm().flows().getRequiredActionConfigDescription(VERIFY_EMAIL).getProperties().stream()
			.map(ConfigPropertyRepresentation::getName)
			.toList();

		assertEquals(List.of(Constants.MAX_AUTH_AGE_KEY, EmailCooldownManager.EMAIL_RESEND_COOLDOWN_SECONDS, PERIOD_AMOUNT, PERIOD_UNIT), names);
	}

	@Test
	void periodThatIsNotANumberIsRejected() {
		WebApplicationException e = assertThrows(WebApplicationException.class,
			() -> updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "abc", PERIOD_UNIT, "DAYS")));

		assertEquals(400, e.getResponse().getStatus());
	}

	@Test
	void maxAuthAgeThatIsNotANumberIsRejectedByUpstreamValidation() {
		WebApplicationException e = assertThrows(WebApplicationException.class,
			() -> updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS", Constants.MAX_AUTH_AGE_KEY, "abc")));

		assertEquals(400, e.getResponse().getStatus());
	}

	// --- upstream behavior, if the periodic verification is switched off ---

	@Test
	void verifiedUserIsNotAskedToVerifyWhenPeriodicVerificationIsSwitchedOff() {
		TestUser user = createUser(true, true, ago(Duration.ofDays(365)));

		assertTrue(newBrowser().login(user.username(), user.password()).isLoginCompleted());
	}

	@Test
	void unverifiedUserIsAskedToVerifyByUpstreamWhenRealmRequiresIt() {
		RealmRepresentation realm = realm().toRepresentation();
		realm.setVerifyEmail(true);
		realm().update(realm);
		try {
			TestUser user = createUser(false, true, null);

			Browser.Page page = newBrowser().login(user.username(), user.password());

			assertEquals(VERIFY_EMAIL, page.execution());
			mailpit().awaitMessageText(user.email(), 1);
		} finally {
			realm.setVerifyEmail(false);
			realm().update(realm);
		}
	}

	// --- periodic verification ---

	@Test
	void userIsNotAskedToVerifyWithinThePeriod() {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS"));
		TestUser user = createUser(true, true, ago(Duration.ofHours(1)));

		assertTrue(newBrowser().login(user.username(), user.password()).isLoginCompleted());
		assertTrue(reload(user).isEmailVerified());
	}

	@Test
	void periodStartsWithCreationOfTheUserIfNeverVerifiedBefore() {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS"));
		TestUser user = createUser(true, true, null);

		assertTrue(newBrowser().login(user.username(), user.password()).isLoginCompleted());
	}

	@Test
	void userIsAskedToVerifyWhenThePeriodHasPassed() {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS"));
		TestUser user = createUser(true, true, ago(Duration.ofDays(2)));

		Browser.Page page = newBrowser().login(user.username(), user.password());

		assertFalse(page.isLoginCompleted());
		assertEquals(VERIFY_EMAIL, page.execution());
		mailpit().awaitMessageText(user.email(), 1);
		assertFalse(reload(user).isEmailVerified(), "the verified flag has to be reset");
	}

	@Test
	void userWithoutEmailIsNeverAskedToVerify() {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS"));
		TestUser user = createUser(true, false, ago(Duration.ofDays(2)));

		assertTrue(newBrowser().login(user.username(), user.password()).isLoginCompleted());
		assertTrue(reload(user).isEmailVerified());
	}

	// --- resend cooldown (upstream) ---

	@Test
	void emailCannotBeResentDuringTheCooldown() {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS", EmailCooldownManager.EMAIL_RESEND_COOLDOWN_SECONDS, "60"));
		TestUser user = createUser(true, true, ago(Duration.ofDays(2)));
		Browser browser = newBrowser();
		Browser.Page page = browser.login(user.username(), user.password());
		mailpit().awaitMessageText(user.email(), 1);

		Browser.Page resend = browser.get(page.linkContaining("session_code"));

		assertEquals(200, resend.status());
		assertEquals(VERIFY_EMAIL, resend.execution());
		assertEquals(1, mailpit().count(user.email()), "no second email during the cooldown");
	}

	@Test
	void emailCanBeResentAfterTheCooldown() throws InterruptedException {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS", EmailCooldownManager.EMAIL_RESEND_COOLDOWN_SECONDS, "1"));
		TestUser user = createUser(true, true, ago(Duration.ofDays(2)));
		Browser browser = newBrowser();
		Browser.Page page = browser.login(user.username(), user.password());
		mailpit().awaitMessageText(user.email(), 1);
		Thread.sleep(Duration.ofMillis(1500));

		Browser.Page resend = browser.get(page.linkContaining("session_code"));

		assertEquals(VERIFY_EMAIL, resend.execution());
		mailpit().awaitMessageText(user.email(), 2);
	}

	@Test
	void defaultCooldownLimitsABurstOfResendRequestsToOneEmail() {
		// without any configuration of the required action, the default cooldown of upstream applies
		realm().flows().removeRequiredActionConfig(VERIFY_EMAIL);
		TestUser user = createUser(false, true, null);
		UserRepresentation representation = reload(user);
		representation.setRequiredActions(List.of(VERIFY_EMAIL));
		realm().users().get(user.id()).update(representation);
		Browser browser = newBrowser();
		Browser.Page page = browser.login(user.username(), user.password());
		assertEquals(VERIFY_EMAIL, page.execution());
		mailpit().awaitMessageText(user.email(), 1);

		String resendLink = page.linkContaining("session_code");
		for (int i = 0; i < 5; i++) {
			Browser.Page resend = browser.get(resendLink);
			assertEquals(VERIFY_EMAIL, resend.execution());
			resendLink = resend.linkContaining("session_code") != null ? resend.linkContaining("session_code") : resendLink;
		}

		assertEquals(1, mailpit().count(user.email()), "only the first email may be sent within the cooldown");
	}

	// --- direct grant ---

	@Test
	void userWithoutEmailCanUseTheDirectGrantWhenThePeriodHasPassed() {
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS"));
		TestUser user = createUser(true, false, ago(Duration.ofDays(2)));

		tokenResponse(user).then().statusCode(200);
	}

	@Test
	void userWithEmailMustVerifyBeforeUsingTheDirectGrantWhenThePeriodHasPassed() {
		// control for the test above: the direct grant evaluates the periodic verification
		updateRequiredActionConfig(Map.of(PERIOD_AMOUNT, "1", PERIOD_UNIT, "DAYS"));
		TestUser user = createUser(true, true, ago(Duration.ofDays(2)));

		tokenResponse(user).then().statusCode(400).body("error", equalTo("invalid_grant"));
	}

	// --- protection of the timestamp ---

	@Test
	void userCannotChangeTheVerificationTimestampThroughTheAccountApi() {
		long timestamp = ago(Duration.ofDays(1));
		TestUser user = createUser(true, true, timestamp);
		String token = accessToken(user);

		// control: the request itself is valid, a change of an editable attribute is accepted
		Map<String, Object> profile = Map.of("username", user.username(), "email", user.email(), "firstName", "Changed", "lastName", "Tester");
		RestAssured.given().auth().oauth2(token).contentType(ContentType.JSON).body(profile)
			.post(realmUrl() + "/account")
			.then().statusCode(204);
		assertEquals("Changed", reload(user).getFirstName());

		// the same request including the verification timestamp is rejected
		Map<String, Object> manipulated = new HashMap<>(profile);
		manipulated.put("attributes", Map.of(EMAIL_VERIFIED_AT, List.of(String.valueOf(System.currentTimeMillis()))));
		RestAssured.given().auth().oauth2(token).contentType(ContentType.JSON).body(manipulated)
			.post(realmUrl() + "/account")
			.then().statusCode(400)
			.body("errors.field", hasItem(EMAIL_VERIFIED_AT))
			.body("errors.errorMessage", hasItem("error-user-attribute-read-only"));
		assertEquals(String.valueOf(timestamp), reload(user).getAttributes().get(EMAIL_VERIFIED_AT).getFirst());
	}

	private static Long ago(Duration duration) {
		return System.currentTimeMillis() - duration.toMillis();
	}
}
