package com.holtherndon.bazelviz.core.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rules that ship, and the ones a user adds.
 *
 * <h2>What the defaults cover</h2>
 *
 * <p>Plan 22.2: "default secret-name patterns should cover common token,
 * password, credential, and key names, while remaining user-editable". The
 * name list below is those four families plus the ones that turned up in
 * measured builds — {@code --remote_header}, which carries an
 * {@code Authorization} value, and the cloud providers' fixed variable names.
 *
 * <h2>Why the value rules are few</h2>
 *
 * <p>Only three shapes are matched by value, and that restraint is deliberate.
 * A rule broad enough to catch "any long random-looking string" would redact
 * digests, action keys and configuration checksums — which is most of what
 * makes an exported session useful — and would still miss a password that
 * happens to be a dictionary word. The three here are shapes that are secret
 * wherever they occur and are not produced by Bazel: a bearer token, a URL
 * with credentials embedded in its authority, and a PEM private-key block.
 *
 * <h2>The list is not a guarantee</h2>
 *
 * <p>Pattern matching finds what it was told to look for. An export is
 * therefore accompanied by a report of what was redacted (plan 22.2: "shows the
 * user exactly what was redacted before anything is written"), so the decision
 * to share rests on something a person read rather than on a claim that
 * everything sensitive was caught.
 */
public final class SecretPatterns {

    private SecretPatterns() {}

    /** The name rules that ship. */
    public static List<SecretPattern> defaultNamePatterns() {
        return List.of(
                SecretPattern.named("*TOKEN*", "a token"),
                SecretPattern.named("*SECRET*", "a secret"),
                SecretPattern.named("*PASSWORD*", "a password"),
                SecretPattern.named("*PASSWD*", "a password"),
                SecretPattern.named("*CREDENTIAL*", "a credential"),
                SecretPattern.named("*APIKEY*", "an API key"),
                SecretPattern.named("*API_KEY*", "an API key"),
                SecretPattern.named("*_KEY", "a key"),
                SecretPattern.named("*PRIVATE_KEY*", "a private key"),
                SecretPattern.named("*ACCESS_KEY*", "an access key"),
                SecretPattern.named("AUTHORIZATION", "an authorization header"),
                SecretPattern.named("*AUTH_TOKEN*", "an auth token"),
                SecretPattern.named("*SESSION_KEY*", "a session key"),
                SecretPattern.named("*PASSPHRASE*", "a passphrase"),
                // Bazel's own flags that carry credentials.
                SecretPattern.named("--remote_header*", "a remote-cache header"),
                SecretPattern.named("--remote_exec_header*", "a remote-executor header"),
                SecretPattern.named("--google_credentials*", "a credentials file"),
                SecretPattern.named("--auth_credentials*", "a credentials file"),
                SecretPattern.named("--tls_client_key*", "a TLS client key"));
    }

    /**
     * The value rules that ship. Not extensible: see the class comment.
     *
     * <p>Each expression is anchored to a literal prefix, so it cannot
     * backtrack over an arbitrary argument, and each declares group 1 as the
     * part that is actually secret — the token after {@code Bearer}, the
     * password inside a URL's authority — so the surrounding text survives and
     * an exported command still reads as a command.
     */
    public static List<SecretPattern> defaultValuePatterns() {
        return List.of(
                SecretPattern.valued(
                        "bearer-token",
                        "\\bBearer\\s+([A-Za-z0-9._~+/=-]{12,})",
                        "a bearer token"),
                SecretPattern.valued(
                        "url-credentials",
                        // scheme://user:PASSWORD@host — the password only.
                        "[a-z][a-z0-9+.-]*://[^\\s/@:]+:([^\\s/@]+)@",
                        "a password embedded in a URL"),
                SecretPattern.valued(
                        "pem-private-key",
                        // (?s) so the body may span lines, which a PEM block
                        // always does.
                        "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----(.*?)"
                                + "-----END [A-Z ]*PRIVATE KEY-----",
                        "a PEM private key"));
    }

    /**
     * The shipped rules plus {@code additional} name globs.
     *
     * @param additionalNameGlobs user-supplied name patterns; a blank entry is
     *     rejected rather than silently ignored, because a pattern that matches
     *     everything and a pattern that matches nothing are both configuration
     *     errors worth hearing about
     */
    public static List<SecretPattern> withUserPatterns(List<String> additionalNameGlobs) {
        Objects.requireNonNull(additionalNameGlobs, "additionalNameGlobs");
        List<SecretPattern> all = new ArrayList<>(defaultNamePatterns());
        for (String glob : additionalNameGlobs) {
            all.add(SecretPattern.named(glob, "a user-defined pattern"));
        }
        all.addAll(defaultValuePatterns());
        return List.copyOf(all);
    }

    /** Every shipped rule, name and value alike. */
    public static List<SecretPattern> defaults() {
        return withUserPatterns(List.of());
    }
}
