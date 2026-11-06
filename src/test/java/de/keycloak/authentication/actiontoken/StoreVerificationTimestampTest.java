package de.keycloak.authentication.actiontoken;

import de.keycloak.testsupport.Fakes;
import de.keycloak.testsupport.Fakes.FakeUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for storing the timestamp for users of read-only user storages, without a running Keycloak.
 */
class StoreVerificationTimestampTest {

	private static final String STORAGE_ID = "ldap-storage-id";

	private final ExtendedVerifyEmailActionTokenHandler handler = new ExtendedVerifyEmailActionTokenHandler();

	@Test
	void timestampIsStoredForLocalUsers() {
		FakeUser user = new FakeUser();

		handler.storeVerificationTimestamp(Fakes.realmWithUserStorage(STORAGE_ID, "READ_ONLY"), user.model());

		assertEquals(List.of("setSingleAttribute"), user.writes);
	}

	@Test
	void timestampIsNotStoredForUsersOfAReadOnlyStorage() {
		FakeUser user = new FakeUser();
		user.federationLink = STORAGE_ID;

		handler.storeVerificationTimestamp(Fakes.realmWithUserStorage(STORAGE_ID, "READ_ONLY"), user.model());

		assertTrue(user.writes.isEmpty());
	}

	@Test
	void readOnlyExceptionOfTheUserModelDoesNotFailTheVerification() {
		FakeUser user = new FakeUser();
		user.readOnly = true;

		handler.storeVerificationTimestamp(Fakes.realmWithUserStorage(STORAGE_ID, "WRITABLE"), user.model());

		assertTrue(user.writes.isEmpty());
	}
}
