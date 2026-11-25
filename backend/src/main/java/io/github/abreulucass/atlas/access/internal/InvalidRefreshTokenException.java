package io.github.abreulucass.atlas.access.internal;

class InvalidRefreshTokenException extends RuntimeException {

	InvalidRefreshTokenException() {
		super("Invalid refresh token");
	}

}
