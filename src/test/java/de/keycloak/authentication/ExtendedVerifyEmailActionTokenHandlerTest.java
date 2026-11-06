package de.keycloak.authentication;

import de.keycloak.testsupport.Browser;
import de.keycloak.testsupport.KeycloakEnvironment;
import de.keycloak.testsupport.Mailpit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedVerifyEmailActionTokenHandlerTest extends KeycloakEnvironment {

	@BeforeEach
	void enablePeriodicVerification() {
		updateRequiredActionConfig(Map.of("email-verify-period-amount", "1", "email-verify-period-unit", "DAYS"));
	}

	@Test
	void verifyingTheEmailSetsTheTimestampAndRestartsThePeriod() {
		long oldTimestamp = System.currentTimeMillis() - Duration.ofDays(2).toMillis();
		TestUser user = createUser(true, true, oldTimestamp);
		Browser browser = newBrowser();
		assertEquals(VERIFY_EMAIL, browser.login(user.username(), user.password()).execution());
		String link = Mailpit.actionTokenLink(mailpit().awaitMessageText(user.email(), 1));
		long beforeVerification = System.currentTimeMillis();

		Browser.Page page = browser.get(link);

		// the timestamp is set before the required actions are evaluated again, otherwise the verification would be requested again
		assertTrue(page.isLoginCompleted(), "login has to be completed, but ended at " + page.uri());
		UserRepresentation reloaded = reload(user);
		assertTrue(reloaded.isEmailVerified());
		assertTrue(emailVerifiedAt(reloaded) >= beforeVerification, "timestamp has to be set to the time of the verification");
		assertTrue(newBrowser().login(user.username(), user.password()).isLoginCompleted(), "no further verification within the period");
	}

	@Test
	void confirmationPageForALinkOpenedInAnotherBrowserDoesNotVerifyTheEmail() {
		long oldTimestamp = System.currentTimeMillis() - Duration.ofDays(2).toMillis();
		TestUser user = createUser(true, true, oldTimestamp);
		assertEquals(VERIFY_EMAIL, newBrowser().login(user.username(), user.password()).execution());
		String link = Mailpit.actionTokenLink(mailpit().awaitMessageText(user.email(), 1));
		Browser otherBrowser = newBrowser();

		Browser.Page confirmation = otherBrowser.get(link);

		assertEquals(200, confirmation.status());
		String confirmLink = confirmation.linkContaining("action-token");
		assertNotNull(confirmLink, "confirmation link expected");
		UserRepresentation reloaded = reload(user);
		assertFalse(reloaded.isEmailVerified(), "the email must not be verified by the confirmation page");
		assertEquals(oldTimestamp, emailVerifiedAt(reloaded), "the timestamp must not be changed by the confirmation page");

		Browser.Page verified = otherBrowser.get(confirmLink);

		assertEquals(200, verified.status());
		reloaded = reload(user);
		assertTrue(reloaded.isEmailVerified());
		assertTrue(emailVerifiedAt(reloaded) > oldTimestamp, "timestamp has to be set after the confirmation");
	}

	private static long emailVerifiedAt(UserRepresentation user) {
		return Long.parseLong(user.getAttributes().get(EMAIL_VERIFIED_AT).getFirst());
	}
}
