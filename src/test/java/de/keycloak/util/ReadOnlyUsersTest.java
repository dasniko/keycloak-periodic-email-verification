package de.keycloak.util;

import de.keycloak.testsupport.Fakes;
import de.keycloak.testsupport.Fakes.FakeUser;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmModel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyUsersTest {

	private static final String STORAGE_ID = "ldap-storage-id";

	@Test
	void userOfAStorageWithEditModeReadOnlyIsReadOnly() {
		FakeUser user = new FakeUser();
		user.federationLink = STORAGE_ID;

		assertTrue(ReadOnlyUsers.isReadOnlyFederatedUser(Fakes.realmWithUserStorage(STORAGE_ID, "READ_ONLY"), user.model()));
	}

	@Test
	void userOfAStorageWithAnotherEditModeIsNotReadOnly() {
		FakeUser user = new FakeUser();
		user.federationLink = STORAGE_ID;

		assertFalse(ReadOnlyUsers.isReadOnlyFederatedUser(Fakes.realmWithUserStorage(STORAGE_ID, "WRITABLE"), user.model()));
		assertFalse(ReadOnlyUsers.isReadOnlyFederatedUser(Fakes.realmWithUserStorage(STORAGE_ID, "UNSYNCED"), user.model()));
	}

	@Test
	void localUserIsNotReadOnly() {
		assertFalse(ReadOnlyUsers.isReadOnlyFederatedUser(Fakes.realmWithUserStorage(STORAGE_ID, "READ_ONLY"), new FakeUser().model()));
	}

	@Test
	void userWithAnUnknownStorageIsNotReadOnly() {
		FakeUser user = new FakeUser();
		user.federationLink = "unknown";
		RealmModel realm = Fakes.realmWithUserStorage(STORAGE_ID, "READ_ONLY");

		assertFalse(ReadOnlyUsers.isReadOnlyFederatedUser(realm, user.model()));
	}
}
