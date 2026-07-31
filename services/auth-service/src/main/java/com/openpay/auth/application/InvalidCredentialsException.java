package com.openpay.auth.application;

/**
 * Deliberately says nothing about which part was wrong.
 *
 * <p>Distinguishing "no such user" from "wrong password" turns the login endpoint into a way to
 * discover who has an account.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email or password is incorrect");
    }
}
