package io.github.abreulucass.atlas.access.internal;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	AuthService(UserRepository users, MembershipRepository memberships, Organizations organizations,
			PasswordEncoder passwordEncoder, TokenIssuer tokens) {
		this.users = users;
		this.memberships = memberships;
		this.organizations = organizations;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
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

	static String normalizeEmail(String email) {
		String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
		if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
			throw new InvalidEmailException("invalid e-mail");
		}
		return normalized;
	}

}
