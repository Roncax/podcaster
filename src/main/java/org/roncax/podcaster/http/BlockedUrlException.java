package org.roncax.podcaster.http;

/** A URL that must not be fetched (non-HTTP scheme, or a host on a private, loopback or link-local network). */
public class BlockedUrlException extends FetchException {
    public BlockedUrlException(String message) { super(message); }
}
