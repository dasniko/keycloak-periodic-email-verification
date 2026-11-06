package de.keycloak.authentication.requiredaction;

import de.keycloak.testsupport.Fakes;
import de.keycloak.testsupport.Fakes.FakeUser;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.RequiredActionContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for users of read-only user storages (e.g. LDAP with the edit mode READ_ONLY), without a running Keycloak.
 */
class PeriodicVerifyEmailReadOnlyTest {

	private static final String STORAGE_ID = "ldap-storage-id";
	private static final Map<String, String> ONE_DAY = Map.of("email-verify-period-amount", "1", "email-verify-period-unit", "DAYS");

	private final PeriodicVerifyEmail requiredAction = new PeriodicVerifyEmail();

	@Test
	void periodicVerificationIsSuspendedForUsersOfAReadOnlyStorage() {
		FakeUser user = overdueUser();
		user.federationLink = STORAGE_ID;

		requiredAction.evaluateTriggers(Fakes.context(Fakes.realmWithUserStorage(STORAGE_ID, "READ_ONLY"), user.model(), ONE_DAY));

		assertTrue(user.writes.isEmpty(), "nothing may be written, but: " + user.writes);
	}

	@Test
	void periodicVerificationIsNotSuspendedForUsersOfAWritableStorage() {
		// control for the test above: the same user of a writable storage is asked to verify
		FakeUser user = overdueUser();
		user.federationLink = STORAGE_ID;
		user.emailVerified = false; // the reset of the flag fires an event, which needs a running Keycloak

		requiredAction.evaluateTriggers(Fakes.context(Fakes.realmWithUserStorage(STORAGE_ID, "WRITABLE"), user.model(), ONE_DAY));

		assertEquals(List.of("addRequiredAction"), user.writes);
	}

	@Test
	void readOnlyExceptionOfTheUserModelDoesNotBreakTheTrigger() {
		// e.g. a user storage without import, which is not detected by its edit mode
		FakeUser user = overdueUser();
		user.emailVerified = false;
		user.readOnly = true;
		RequiredActionContext context = Fakes.context(Fakes.realmWithUserStorage(STORAGE_ID, "WRITABLE"), user.model(), ONE_DAY);

		requiredAction.evaluateTriggers(context);

		assertTrue(user.writes.isEmpty());
	}

	private static FakeUser overdueUser() {
		FakeUser user = new FakeUser();
		user.emailVerifiedAt = System.currentTimeMillis() - Duration.ofDays(2).toMillis();
		return user;
	}
}
