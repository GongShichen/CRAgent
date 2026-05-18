package com.cragent.cli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PrIdentifier(String owner, String repo, int pr) {
    private static final Pattern URL = Pattern.compile("github\\.com/([^/]+)/([^/]+)/pull/(\\d+)");
    private static final Pattern SHORT = Pattern.compile("([^/\\s]+)/([^/\\s#]+)\\s*(?:#|PR\\s+)?(\\d+)", Pattern.CASE_INSENSITIVE);

    public static PrIdentifier parse(String input) {
        Matcher url = URL.matcher(input);
        if (url.find()) {
            return new PrIdentifier(url.group(1), url.group(2), Integer.parseInt(url.group(3)));
        }
        Matcher shortMatch = SHORT.matcher(input);
        if (shortMatch.find()) {
            return new PrIdentifier(shortMatch.group(1), shortMatch.group(2), Integer.parseInt(shortMatch.group(3)));
        }
        throw new IllegalArgumentException("Cannot parse PR identifier: " + input);
    }

    public String fullRepo() {
        return owner + "/" + repo;
    }
}

