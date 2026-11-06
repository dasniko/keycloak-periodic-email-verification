package de.keycloak.authentication.requiredaction;

import com.google.auto.service.AutoService;
import dasniko.copiedcode.CopiedFromKeycloak;
import de.keycloak.provider.DefaultServerInfoAware;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.requiredactions.VerifyEmail;
import org.keycloak.events.Details;
import org.keycloak.events.EventType;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionConfigModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.light.LightweightUserAdapter;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.services.validation.Validation;
import org.keycloak.userprofile.ValidationException;
import org.keycloak.validate.ValidationError;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@JBossLog
@AutoService(RequiredActionFactory.class)
public class PeriodicVerifyEmail extends VerifyEmail implements DefaultServerInfoAware {

	public static final String EMAIL_VERIFIED_AT = UserModel.EMAIL_VERIFIED + "At";
	/** Value of the event detail {@code reason}, if the verified flag of a user has been reset by the periodic verification. */
	public static final String PERIODIC_VERIFICATION_DUE = "periodic_verification_due";

	private static final List<ProviderConfigProperty> CONFIG_PROPERTIES;
	private static final String EMAIL_VERIFY_PERIOD_AMOUNT = "email-verify-period-amount";
	private static final String EMAIL_VERIFY_PERIOD_UNIT = "email-verify-period-unit";

	static {
		CONFIG_PROPERTIES = ProviderConfigurationBuilder.create()
			.property()
			.name(EMAIL_VERIFY_PERIOD_AMOUNT)
			.label("Verification Period")
			.helpText("Enter the amount of the period, after which the user should be enforced to verify its email address again (0 = switched off).")
			.type(ProviderConfigProperty.STRING_TYPE)
			.defaultValue("0")
			.add()
			.property()
			.name(EMAIL_VERIFY_PERIOD_UNIT)
			.label("Verification Period Unit")
			.helpText("Select the temporal unit of the periodic verification.")
			.type(ProviderConfigProperty.LIST_TYPE)
			.options(List.of(ChronoUnit.MINUTES.name(), ChronoUnit.HOURS.name(), ChronoUnit.DAYS.name(), ChronoUnit.WEEKS.name(), ChronoUnit.MONTHS.name()))
			.defaultValue(ChronoUnit.MONTHS.name())
			.add()
			.build();
	}

	@Override
	@CopiedFromKeycloak(
		artifact = "keycloak-services",
		source = "org.keycloak.authentication.requiredactions.VerifyEmail#evaluateTriggers(RequiredActionContext)",
		version = "26.8.0",
		modified = true,
		changes = "Periodic mode: trigger is the age of the last verification instead of realm.isVerifyEmail() and !emailVerified, "
			+ "transient users are skipped, emailVerified is reset (with an event) when the action is added. "
			+ "The skip rules for blank emails and a pending UPDATE_EMAIL are taken over unchanged. "
			+ "If periodic verification is switched off, the upstream implementation is called.",
		reason = "Upstream requires realm.isVerifyEmail(), which must not be a precondition for the periodic verification."
	)
	public void evaluateTriggers(RequiredActionContext context) {
		Optional<Period> period = getPeriod(context.getConfig());
		if (period.isPresent()) {
			UserModel user = context.getUser();
			if (user instanceof LightweightUserAdapter) {
				// if user is a transient/lightweight user, don't enforce verification by email
				return;
			}
			if (isWithinPeriod(user, period.get())) {
				return;
			}
			if (Validation.isBlank(user.getEmail())) {
				log.debug("Skipping VERIFY_EMAIL because the user has no email set");
				return;
			}
			// Don't add VERIFY_EMAIL if UPDATE_EMAIL is already present (UPDATE_EMAIL takes precedence)
			if (user.getRequiredActionsStream().anyMatch(UserModel.RequiredAction.UPDATE_EMAIL.name()::equals)) {
				log.debug("Skipping VERIFY_EMAIL because UPDATE_EMAIL is already present");
				return;
			}
			resetEmailVerified(context, user);
			user.addRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
			log.debug("User is required to verify email upon periodic check.");
		} else {
			super.evaluateTriggers(context);
		}
	}

	@Override
	public void requiredActionChallenge(RequiredActionContext context) {
		if (shouldDelegateToUpstream(context)) {
			super.requiredActionChallenge(context);
		}
	}

	@Override
	public void processAction(RequiredActionContext context) {
		if (shouldDelegateToUpstream(context)) {
			super.processAction(context);
		}
	}

	/**
	 * The upstream implementation decides on {@link UserModel#isEmailVerified()} only, so the periodic check is done beforehand.
	 * <p>
	 * Has side effects: if the periodic verification is not due, the action is completed here; if it is due,
	 * the verified flag of the user is reset, so that the upstream implementation requests the verification.
	 *
	 * @return {@code true}, if the upstream implementation has to handle the request (periodic verification switched off or due).
	 * {@code false}, if the periodic verification is enabled and the last verification is still within the period,
	 * so the action is already completed.
	 */
	private boolean shouldDelegateToUpstream(RequiredActionContext context) {
		Optional<Period> period = getPeriod(context.getConfig());
		if (period.isEmpty()) {
			return true;
		}

		UserModel user = context.getUser();
		if (isWithinPeriod(user, period.get())) {
			context.success();
			context.getAuthenticationSession().removeAuthNote(Constants.VERIFY_EMAIL_KEY);
			return false;
		}

		// The verification is due, but the action may be started without evaluateTriggers() having been called
		// (e.g. application initiated action in a long-running session). Upstream would complete the action at once
		// for a verified user, so reset the flag the same way as evaluateTriggers() does.
		if (!Validation.isBlank(user.getEmail())) {
			resetEmailVerified(context, user);
		}
		return true;
	}

	/**
	 * Resets the verified flag of the user, because the periodic verification is due, and fires a {@link EventType#CUSTOM_REQUIRED_ACTION} event.
	 * Does nothing, if the user is not marked as verified, so that there is exactly one event per reset.
	 */
	private void resetEmailVerified(RequiredActionContext context, UserModel user) {
		if (!user.isEmailVerified()) {
			return;
		}
		user.setEmailVerified(false);
		context.getEvent().clone()
			.event(EventType.CUSTOM_REQUIRED_ACTION)
			.user(user)
			.detail(Details.CUSTOM_REQUIRED_ACTION, getId())
			.detail(Details.REASON, PERIODIC_VERIFICATION_DUE)
			.success();
	}

	@Override
	public String getDisplayText() {
		return super.getDisplayText() + " (Periodic)";
	}

	@Override
	public int order() {
		return super.order() + 10;
	}

	@Override
	public List<ProviderConfigProperty> getConfigMetadata() {
		return Stream.concat(super.getConfigMetadata().stream(), CONFIG_PROPERTIES.stream()).toList();
	}

	@Override
	public void validateConfig(KeycloakSession session, RealmModel realm, RequiredActionConfigModel model) {
		super.validateConfig(session, realm, model);
		long amount;
		try {
			amount = parseAmount(model.getConfigValue(EMAIL_VERIFY_PERIOD_AMOUNT, "0"));
		} catch (NumberFormatException ex) {
			throw new ValidationException(new ValidationError(getId(), EMAIL_VERIFY_PERIOD_AMOUNT, "periodNotANumber"));
		}
		ChronoUnit unit;
		try {
			unit = parseUnit(model.getConfigValue(EMAIL_VERIFY_PERIOD_UNIT, ChronoUnit.MONTHS.name()));
		} catch (IllegalArgumentException ex) {
			throw new ValidationException(new ValidationError(getId(), EMAIL_VERIFY_PERIOD_UNIT, "periodUnitNotSupported"));
		}
		try {
			new Period(amount, unit).start();
		} catch (UnsupportedTemporalTypeException ex) {
			throw new ValidationException(new ValidationError(getId(), EMAIL_VERIFY_PERIOD_UNIT, "periodUnitNotSupported"));
		} catch (DateTimeException | ArithmeticException ex) {
			throw new ValidationException(new ValidationError(getId(), EMAIL_VERIFY_PERIOD_AMOUNT, "periodOutOfRange"));
		}
	}

	/**
	 * Reads the verification period from the configuration of the required action.
	 * <p>
	 * An invalid configuration (e.g. imported or set by a way that skipped the validation) must not break the login of all users.
	 * Therefore it is logged as an error and the periodic verification is treated as switched off (fail-open),
	 * so that the behavior of upstream applies.
	 *
	 * @return the period, or empty, if the periodic verification is switched off or the configuration is invalid
	 */
	private Optional<Period> getPeriod(RequiredActionConfigModel model) {
		if (model == null || !model.containsConfigKey(EMAIL_VERIFY_PERIOD_AMOUNT)) {
			return Optional.empty();
		}
		String amount = model.getConfigValue(EMAIL_VERIFY_PERIOD_AMOUNT, "0");
		String unit = model.getConfigValue(EMAIL_VERIFY_PERIOD_UNIT, ChronoUnit.MONTHS.name());
		try {
			long parsedAmount = parseAmount(amount);
			if (parsedAmount <= 0) {
				return Optional.empty();
			}
			Period period = new Period(parsedAmount, parseUnit(unit));
			period.start(); // fails for combinations that cannot be calculated
			return Optional.of(period);
		} catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
			log.errorf("Invalid configuration of the periodic email verification (%s='%s', %s='%s'), "
					+ "the periodic verification is switched off until the configuration is corrected: %s",
				EMAIL_VERIFY_PERIOD_AMOUNT, amount, EMAIL_VERIFY_PERIOD_UNIT, unit, e.getMessage());
			return Optional.empty();
		}
	}

	private static long parseAmount(String amount) {
		return Long.parseLong(amount);
	}

	private static ChronoUnit parseUnit(String unit) {
		return ChronoUnit.valueOf(unit);
	}

	/**
	 * @param amount the amount of the period, greater than 0
	 * @param unit   the unit of the period
	 */
	private record Period(long amount, ChronoUnit unit) {

		/**
		 * @return the point in time, before which a verification is outdated
		 */
		Instant start() {
			return Instant.now().atZone(ZoneId.systemDefault()).minus(amount, unit).toInstant();
		}
	}

	private boolean isWithinPeriod(UserModel user, Period period) {
		String emailVerifiedAtTimestamp = user.getFirstAttribute(EMAIL_VERIFIED_AT);
		Long timestamp;
		if (emailVerifiedAtTimestamp != null && !emailVerifiedAtTimestamp.isEmpty()) {
			timestamp = Long.parseLong(emailVerifiedAtTimestamp);
		} else {
			timestamp = user.getCreatedTimestamp();
		}
		Instant verifiedAt = Instant.ofEpochMilli(timestamp);
		return !verifiedAt.isBefore(period.start());
	}
}
