package com.taggu.app.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when a requested row does not exist. Carries an id, never any message content. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super("no such " + what + ": " + id);
    }
}
