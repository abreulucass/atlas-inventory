package io.github.abreulucass.atlas.access.internal;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.abreulucass.atlas.access.InvalidNameException;
import io.github.abreulucass.atlas.access.Organizations;

@Service
class AuthService {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	private final UserRepository users;

	private final MembershipRepository memberships;

	private final Organizations organizations;

	private final PasswordEncoder passwordEncoder;

	private final TokenIssuer tokens;

	private final RefreshTokens refreshTokens;

	private final TransactionTemplate transaction;

	// checked when the e-mail is unknown, so unknown and wrong-password logins cost the same
	private final String unknownUserHash;

	AuthService(UserRepository users, MembershipRepository memberships, Organizations organizations,
			PasswordEncoder passwordEncoder, TokenIssuer tokens, RefreshTokens refreshTokens,
			PlatformTransactionManager transactionManager) {
		this.users = users;
		this.memberships = memberships;
		this.organizations = organizations;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
		this.refreshTokens = refreshTokens;
		this.transaction = new TransactionTemplate(transactionManager);
		this.unknownUserHash = passwordEncoder.encode("unknown-user");
	}

	@Transactional
	TokenPair register(String name, String email, String password, String organizationName, String organizationSlug) {
		String normalized = normalizeEmail(email);
		if (name == null || name.isBlank() || name.strip().codePointCount(0, name.strip().length()) > 120) {
			throw new InvalidNameException();
		}
		if (password == null || password.codePointCount(0, password.length()) < 12
				|| password.codePointCount(0, password.length()) > 128) {
			throw new InvalidPasswordException("password must have 12 to 128 characters");
		}
		User user;
		try {
			user = users.saveAndFlush(new User(name.strip(), normalized, passwordEncoder.encode(password)));
		}
		catch (DataIntegrityViolationException e) {
			if (NestedExceptionUtils.getMostSpecificCause(e).getMessage().contains("users_email_key")) {
				throw new DuplicateEmailException("e-mail already registered");
			}
			throw e;
		}
		UUID organizationId = organizations.create(organizationName, organizationSlug);
		memberships.saveAndFlush(new Membership(user.getId(), organizationId, Role.OWNER));
		return tokens.issue(user.getId(), organizationId, UUID.randomUUID());
	}

	@Transactional
	TokenPair login(String email, String password) {
		User user = users.findByEmail(email == null ? "" : email.strip().toLowerCase(Locale.ROOT)).orElse(null);
		boolean matches = passwordEncoder.matches(password == null ? "" : password,
				user == null ? unknownUserHash : user.getPasswordHash());
		if (user == null || !matches) {
			throw new BadCredentialsException("Invalid credentials");
		}
		Membership membership = memberships.findFirstByUserIdOrderByCreatedAtAscIdAsc(user.getId())
			.orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
		return tokens.issue(user.getId(), membership.getOrganizationId(), UUID.randomUUID());
	}

	// not @Transactional: the reuse revocation must commit in its own transaction, after this one is closed
	TokenPair refresh(String token) {
		TokenPair pair = transaction.execute(status -> {
			RefreshTokens.Rotated rotated = refreshTokens.rotate(token).orElse(null);
			if (rotated == null) {
				return null;
			}
			if (memberships.findByUserIdAndOrganizationId(rotated.userId(), rotated.organizationId()).isEmpty()) {
				throw new InvalidRefreshTokenException();
			}
			return tokens.issue(rotated.userId(), rotated.organizationId(), rotated.familyId());
		});
		if (pair == null) {
			refreshTokens.revokeFamilyIfReused(token);
			throw new InvalidRefreshTokenException();
		}
		return pair;
	}

	@Transactional
	TokenPair switchOrganization(UUID userId, UUID organizationId) {
		if (memberships.findByUserIdAndOrganizationId(userId, organizationId).isEmpty()) {
			throw new NotAMemberException();
		}
		return tokens.issue(userId, organizationId, UUID.randomUUID());
	}

	void logout(String token) {
		refreshTokens.revoke(token);
	}

	static String normalizeEmail(String email) {
		String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
		if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
			throw new InvalidEmailException("invalid e-mail");
		}
		return normalized;
	}

}
