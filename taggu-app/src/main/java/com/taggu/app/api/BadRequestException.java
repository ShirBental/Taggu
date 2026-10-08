package com.taggu.app.api;

/** Raised when a request cannot be acted on as sent. The message is written for the caller. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
