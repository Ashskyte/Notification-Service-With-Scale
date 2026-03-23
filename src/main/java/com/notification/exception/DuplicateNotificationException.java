package com.notification.exception;

public class DuplicateNotificationException extends RuntimeException {

    public DuplicateNotificationException(String message) {
        super(message);
    }
}
