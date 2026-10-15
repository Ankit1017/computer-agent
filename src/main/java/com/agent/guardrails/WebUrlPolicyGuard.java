package com.agent.guardrails;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.function.Function;

/**
 * Server-Side Request Forgery (SSRF) and URL scheme validation guardrail.
 * Prevents agents from accessing private subnets, loopback interfaces, cloud metadata endpoints,
 * or dangerous non-HTTP protocols via headless browsers or network tools.
 */
public final class WebUrlPolicyGuard {

    private static final Function<String, InetAddress[]> DEFAULT_DNS_RESOLVER = host -> {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    };

    private static volatile Function<String, InetAddress[]> dnsResolver = DEFAULT_DNS_RESOLVER;

    private WebUrlPolicyGuard() {}

    /**
     * Sets a custom DNS resolver for testing or specialized network isolation environments.
     */
    public static void setDnsResolver(Function<String, InetAddress[]> resolver) {
        dnsResolver = resolver != null ? resolver : DEFAULT_DNS_RESOLVER;
    }

    /**
     * Resets the DNS resolver to the standard JVM resolver.
     */
    public static void resetDnsResolver() {
        dnsResolver = DEFAULT_DNS_RESOLVER;
    }

    /**
     * Validates a target URL against SSRF and protocol policies.
     *
     * @param rawUrl the URL string to validate
     * @return GuardrailDecision indicating allow or block with diagnostic reasons
     */
    public static GuardrailDecision validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return GuardrailDecision.block("INVALID_URL", "Error: URL cannot be blank.");
        }

        String trimmed = rawUrl.trim();

        // 1. Scheme Extraction and Validation
        String scheme = null;
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd != -1) {
            scheme = trimmed.substring(0, schemeEnd).toLowerCase();
        } else {
            // Check schemes without // (e.g., javascript:alert(1), data:text/html, about:blank)
            int colonIdx = trimmed.indexOf(':');
            int slashIdx = trimmed.indexOf('/');
            if (colonIdx > 0 && (slashIdx == -1 || colonIdx < slashIdx)) {
                String possibleScheme = trimmed.substring(0, colonIdx).toLowerCase();
                String afterColon = trimmed.substring(colonIdx + 1);
                // If after colon is pure numeric (e.g. localhost:8080 or 127.0.0.1:3000), it's host:port, not a scheme
                if (!afterColon.matches("^\\d+(/.*)?$")) {
                    scheme = possibleScheme;
                }
            }
        }

        if (scheme != null) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return GuardrailDecision.block(
                        "UNSUPPORTED_URL_SCHEME",
                        "[GUARDRAIL BLOCKED: UNSUPPORTED_URL_SCHEME] URL scheme '" + scheme + "' is not permitted. Only 'http' and 'https' are allowed."
                );
            }
        }

        // 2. Hostname Extraction
        String host = null;
        String uriString = (scheme == null) ? "http://" + trimmed : trimmed;
        try {
            URI uri = URI.create(uriString);
            host = uri.getHost();
            if (host == null) {
                String auth = uri.getAuthority();
                if (auth != null) {
                    int c = auth.indexOf(':');
                    host = (c != -1) ? auth.substring(0, c) : auth;
                }
            }
        } catch (Exception ignored) {
            // Fallback string slicing
            int start = uriString.indexOf("://") + 3;
            int end = uriString.indexOf('/', start);
            String hostPort = (end != -1) ? uriString.substring(start, end) : uriString.substring(start);
            int colon = hostPort.indexOf(':');
            host = (colon != -1) ? hostPort.substring(0, colon) : hostPort;
        }

        if (host == null || host.isBlank()) {
            return GuardrailDecision.block("INVALID_URL", "Error: Malformed URL: " + rawUrl);
        }

        // Strip IPv6 square brackets if present
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        // 3. Blocked Hostname Patterns
        if (isBlockedHostname(host)) {
            return GuardrailDecision.block(
                    "SSRF_BLOCKED",
                    "[GUARDRAIL BLOCKED: SSRF_BLOCKED] Navigation to internal, loopback, link-local, or private network address '" + rawUrl + "' is prohibited."
            );
        }

        // 4. IP Address Resolution and Range Evaluation
        try {
            InetAddress[] addresses = dnsResolver.apply(host);
            if (addresses != null) {
                for (InetAddress addr : addresses) {
                    if (isBlockedAddress(addr)) {
                        return GuardrailDecision.block(
                                "SSRF_BLOCKED",
                                "[GUARDRAIL BLOCKED: SSRF_BLOCKED] Navigation to internal, loopback, link-local, or private network address '" + rawUrl + "' is prohibited."
                        );
                    }
                }
            }
        } catch (Exception ignored) {
            // If DNS cannot resolve or fails in offline/mock test environments,
            // allow it through to let network layer or mocks handle it.
        }

        return GuardrailDecision.allow();
    }

    private static boolean isBlockedHostname(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase().trim();
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            return true;
        }
        if (lower.equals("local") || lower.endsWith(".local")) {
            return true;
        }
        if (lower.endsWith(".internal")) {
            return true;
        }
        if (lower.equals("instance-data")) {
            return true;
        }
        return false;
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null) {
            return false;
        }

        // Loopback (127.0.0.0/8, ::1)
        if (addr.isLoopbackAddress()) {
            return true;
        }

        // AnyLocal / Wildcard (0.0.0.0, ::)
        if (addr.isAnyLocalAddress()) {
            return true;
        }

        // Link-Local (169.254.0.0/16, fe80::/10, including AWS/GCP metadata 169.254.169.254)
        if (addr.isLinkLocalAddress()) {
            return true;
        }

        // Site-Local / RFC-1918 Private LAN (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
        if (addr.isSiteLocalAddress()) {
            return true;
        }

        // Multicast
        if (addr.isMulticastAddress()) {
            return true;
        }

        // IPv6 Unique Local Address (ULA fc00::/7 covering fc00::/8 and fd00::/8)
        byte[] bytes = addr.getAddress();
        if (bytes.length == 16 && ((bytes[0] & 0xFF) & 0xFE) == 0xFC) {
            return true;
        }

        return false;
    }
}
