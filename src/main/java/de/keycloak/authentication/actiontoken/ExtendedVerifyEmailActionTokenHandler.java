package de.keycloak.authentication.actiontoken;

import com.google.auto.service.AutoService;
import dasniko.copiedcode.CopiedFromKeycloak;
import de.keycloak.authentication.requiredaction.PeriodicVerifyEmail;
import de.keycloak.provider.DefaultServerInfoAware;
import de.keycloak.util.ReadOnlyUsers;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.authentication.actiontoken.ActionTokenContext;
import org.keycloak.authentication.actiontoken.ActionTokenHandlerFactory;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionToken;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionTokenHandler;
import org.keycloak.common.util.Time;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.storage.ReadOnlyException;

import java.util.stream.Stream;

@JBossLog
@AutoService(ActionTokenHandlerFactory.class)
public class ExtendedVerifyEmailActionTokenHandler extends VerifyEmailActionTokenHandler implements DefaultServerInfoAware {

	@Override
	@CopiedFromKeycloak(
		artifact = "keycloak-services",
		source = "org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionTokenHandler#handleToken(VerifyEmailActionToken, ActionTokenContext)",
		version = "26.8.0",
		modified = true,
		changes = "Only the conditions under which upstream verifies the email are mirrored (not already verified, no confirmation page), "
			+ "to set the verification timestamp. The handling itself is delegated to upstream.",
		reason = "Upstream offers no hook to run own code when the email gets verified."
	)
	public Response handleToken(VerifyEmailActionToken token, ActionTokenContext<VerifyEmailActionToken> tokenContext) {
		AuthenticationSessionModel authSession = tokenContext.getAuthenticationSession();
		UserModel user = authSession.getAuthenticatedUser();

		// The timestamp has to be set before delegating: upstream evaluates the required action triggers (including the periodic
		// verification, which relies on the timestamp) before it returns, so setting it afterwards would trigger the verification again.
		boolean alreadyVerified = user.isEmailVerified() && !isVerifyEmailActionSet(user, authSession);
		if (!alreadyVerified && !tokenContext.isAuthenticationSessionFresh()) {
			storeVerificationTimestamp(tokenContext.getRealm(), user);
		}

		return super.handleToken(token, tokenContext);
	}

	/**
	 * Stores the time of the verification. Users of a read-only user storage (e.g. LDAP with the edit mode {@code READ_ONLY})
	 * reject writing the attribute; this must not fail the verification, so such users are skipped.
	 */
	void storeVerificationTimestamp(RealmModel realm, UserModel user) {
		if (ReadOnlyUsers.isReadOnlyFederatedUser(realm, user)) {
			log.debug("Not storing the verification timestamp for a user of a read-only user storage");
			return;
		}
		try {
			user.setSingleAttribute(PeriodicVerifyEmail.EMAIL_VERIFIED_AT, String.valueOf(Time.currentTimeMillis()));
		} catch (ReadOnlyException e) {
			// the message of the exception contains the username, so it is not logged
			log.warnf("Cannot store the verification timestamp, as the user storage is read-only (user id %s)", user.getId());
		}
	}

	@CopiedFromKeycloak(
		artifact = "keycloak-services",
		source = "org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionTokenHandler#isVerifyEmailActionSet(UserModel, AuthenticationSessionModel)",
		version = "26.8.0",
		reason = "Private in upstream, but needed to mirror the conditions in handleToken()."
	)
	private boolean isVerifyEmailActionSet(UserModel user, AuthenticationSessionModel authSession) {
		return Stream.concat(user.getRequiredActionsStream(), authSession.getRequiredActions().stream())
			.anyMatch(UserModel.RequiredAction.VERIFY_EMAIL.name()::equals);
	}

	@Override
	public int order() {
		return super.order() + 10;
	}

}
