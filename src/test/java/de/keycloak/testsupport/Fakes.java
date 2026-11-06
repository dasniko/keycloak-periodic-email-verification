package de.keycloak.testsupport;

import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionConfigModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Minimal fakes of Keycloak models for unit tests without a running Keycloak. Methods that are not faked return the default value of their type.
 */
public final class Fakes {

	public static final String USERNAME = "secret-username";

	private Fakes() {
	}

	/**
	 * A fake user, which records the write calls.
	 */
	public static final class FakeUser {
		public String federationLink;
		public String email = "user@keycloak.test";
		public boolean emailVerified = true;
		public Long emailVerifiedAt;
		/** if set, all write calls fail like with a read-only user storage */
		public boolean readOnly;
		/** the names of the write methods, which have been called successfully */
		public final List<String> writes = new ArrayList<>();

		public UserModel model() {
			return proxy(UserModel.class, (method, args) -> {
				switch (method.getName()) {
					case "getId":
						return "00000000-0000-0000-0000-000000000001";
					case "getUsername":
						return USERNAME;
					case "getFederationLink":
						return federationLink;
					case "getEmail":
						return email;
					case "isEmailVerified":
						return emailVerified;
					case "getCreatedTimestamp":
						return System.currentTimeMillis();
					case "getFirstAttribute":
						return "emailVerifiedAt".equals(args[0]) && emailVerifiedAt != null ? String.valueOf(emailVerifiedAt) : null;
					case "getRequiredActionsStream":
						return Stream.empty();
					case "addRequiredAction", "setEmailVerified", "setSingleAttribute":
						if (readOnly) {
							throw new ReadOnlyException("The user is read-only. Not possible to write '" + method.getName() + "' when updating user '" + USERNAME + "'.");
						}
						writes.add(method.getName());
						return null;
					default:
						return defaultValue(method.getReturnType());
				}
			});
		}
	}

	/**
	 * A realm, which knows one user storage component with the given edit mode.
	 */
	public static RealmModel realmWithUserStorage(String componentId, String editMode) {
		ComponentModel component = new ComponentModel();
		component.setId(componentId);
		MultivaluedHashMap<String, String> config = new MultivaluedHashMap<>();
		config.putSingle(LDAPConstants.EDIT_MODE, editMode);
		component.setConfig(config);
		return proxy(RealmModel.class, (method, args) -> {
			if (method.getName().equals("getComponent") && componentId.equals(args[0])) {
				return component;
			}
			return defaultValue(method.getReturnType());
		});
	}

	/**
	 * A context with the given realm, user and configuration of the required action.
	 */
	public static RequiredActionContext context(RealmModel realm, UserModel user, Map<String, String> config) {
		RequiredActionConfigModel configModel = new RequiredActionConfigModel();
		configModel.setConfig(config);
		return proxy(RequiredActionContext.class, (method, args) -> switch (method.getName()) {
			case "getRealm" -> realm;
			case "getUser" -> user;
			case "getConfig" -> configModel;
			default -> defaultValue(method.getReturnType());
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> handler.invoke(method, args));
	}

	private static Object defaultValue(Class<?> type) {
		if (type == boolean.class) {
			return false;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		return null;
	}

	@FunctionalInterface
	private interface Handler {
		Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
	}
}
