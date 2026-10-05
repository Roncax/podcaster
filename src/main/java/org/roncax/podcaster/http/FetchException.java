package org.roncax.podcaster.http;

public class FetchException extends Exception {
    public FetchException(String message) { super(message); }
    public FetchException(String message, Throwable cause) { super(message, cause); }
}
