package it.ristomanager.cloud.user;

import it.ristomanager.cloud.common.ApiException;

/** The one place that decides what an acceptable new password is. */
public final class PasswordPolicy {

    public static final int MIN = 10;
    /** bcrypt only reads the first 72 bytes, so longer ones are refused instead of silently cut. */
    public static final int MAX = 72;

    private PasswordPolicy() {
    }

    public static void check(String password, String field) {
        if (password == null || password.length() < MIN || password.length() > MAX) {
            throw ApiException.invalid(field, "must be between " + MIN + " and " + MAX + " characters");
        }
    }
}
