package de.keycloak.authentication.actiontoken;

import com.google.auto.service.AutoService;
import dasniko.copiedcode.CopiedFromKeycloak;
import de.keycloak.authentication.requiredaction.PeriodicVerifyEmail;
import de.keycloak.provider.DefaultServerInfoAware;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.actiontoken.ActionTokenContext;
import org.keycloak.authentication.actiontoken.ActionTokenHandlerFactory;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionToken;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionTokenHandler;
import org.keycloak.common.util.Time;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.stream.Stream;

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
			user.setSingleAttribute(PeriodicVerifyEmail.EMAIL_VERIFIED_AT, String.valueOf(Time.currentTimeMillis()));
		}

		return super.handleToken(token, tokenContext);
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
