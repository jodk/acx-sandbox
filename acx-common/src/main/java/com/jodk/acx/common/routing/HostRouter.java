package com.jodk.acx.common.routing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the sandbox host wire format {@code <port>-<namespace>--<name>.<domain>}.
 * Ported from pkg/sandbox-gateway/filter/config.go and pkg/servers/e2b/adapters.
 */
public final class HostRouter {

    public static final String DEFAULT_SANDBOX_PORT_HEADER = "e2b-sandbox-port";
    public static final String DEFAULT_HOST_HEADER_NAME = "Host";
    public static final String DEFAULT_SANDBOX_PORT = "49983";

    private static final Pattern HOST_PATTERN = Pattern.compile("^(\\d+)-([a-zA-Z0-9\\-]+)\\.");

    private HostRouter() {
    }

    public record HostInfo(String sandboxId, String port) {
    }

    /** Extracts (sandboxId, port) from a host header value; null when parsing fails. */
    public static HostInfo extractHostInfo(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return null;
        }
        Matcher matcher = HOST_PATTERN.matcher(headerValue);
        if (!matcher.find()) {
            return null;
        }
        return new HostInfo(matcher.group(2), matcher.group(1));
    }

    /** Extracts (sandboxId, port) from an authority (e.g. {@code 3000-ns--name.example.com}). */
    public static HostInfo mapAuthority(String authority) {
        return extractHostInfo(authority);
    }
}
