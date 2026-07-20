package com.docmind.confluence;

public class ConfluenceException extends RuntimeException {

    public ConfluenceException(String message) {
        super(message);
    }

    public ConfluenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
