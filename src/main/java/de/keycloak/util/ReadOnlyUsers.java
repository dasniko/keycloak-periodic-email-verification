package de.keycloak.util;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.UserStorageProvider;

/**
 * Detection of users, whose attributes cannot be written by Keycloak.
 */
public final class ReadOnlyUsers {

	private ReadOnlyUsers() {
	}

	/**
	 * Checks if the user is provided by a user storage (e.g. LDAP) in the edit mode {@code READ_ONLY}.
	 * <p>
	 * Keycloak rejects writing attributes of such users (except for the {@code locale}) with a
	 * {@link org.keycloak.storage.ReadOnlyException}, see {@code ReadonlyLDAPUserModelDelegate}.
	 *
	 * @return {@code true}, if the user is linked to a user storage with the edit mode {@code READ_ONLY}
	 */
	public static boolean isReadOnlyFederatedUser(RealmModel realm, UserModel user) {
		String federationLink = user.getFederationLink();
		if (federationLink == null) {
			return false;
		}
		ComponentModel storage = realm.getComponent(federationLink);
		if (storage == null || storage.getConfig() == null) {
			return false;
		}
		return UserStorageProvider.EditMode.READ_ONLY.name().equals(storage.getConfig().getFirst(LDAPConstants.EDIT_MODE));
	}
}
