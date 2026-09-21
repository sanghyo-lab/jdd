package com.jdd.agent.infra;

import com.jdd.agent.domain.InvestigationFailure;
import com.jdd.agent.domain.Investigation.ApiError;
import java.nio.file.*;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, per-request file auth. No cached tokens, refresh, keychain access, or API-key fallback. */
final class CodexOAuthCredentials {
    private CodexOAuthCredentials() {}
    static Map<String, String> headers(Path file, JsonMapper json, Clock clock) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(65537); }
            if (bytes.length > 65536) throw new IllegalArgumentException();
            var auth = json.readTree(bytes);
            // Older CLI versions omit auth_mode. API-key and other credential modes are not OAuth.
            if (!(auth.path("auth_mode").isMissingNode() || auth.path("auth_mode").isNull()
                    || "chatgpt".equals(auth.path("auth_mode").asText())) || !auth.path("OPENAI_API_KEY").isNull() && !auth.path("OPENAI_API_KEY").isMissingNode())
                throw new IllegalArgumentException();
            String token = auth.path("tokens").path("access_token").asText("");
            String account = auth.path("tokens").path("account_id").asText("");
            if (token.isBlank() || token.length() > 16384 || !token.matches("[A-Za-z0-9._~-]+")
                    || account.isBlank() || !account.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException();
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException();
            var claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            // JWT claims only detect expiry here; the provider verifies the signature and access rights.
            if (!claims.path("exp").isIntegralNumber() || claims.path("exp").asLong() <= clock.instant().getEpochSecond())
                throw new IllegalArgumentException();
            if (claims.path("https://api.openai.com/auth").path("chatgpt_account_is_fedramp").asBoolean())
                throw new IllegalArgumentException(); // This adapter implements only the explicitly requested endpoint.
            return Map.of("Authorization", "Bearer " + token, "ChatGPT-Account-Id", account);
        } catch (Exception invalid) { throw loginRequired(); } // Never attach credential/parser exceptions.
    }
    static InvestigationFailure loginRequired() {
        return new InvestigationFailure(new ApiError("LLM_CONFIGURATION_ERROR",
                "로컬 Codex 인증을 확인해 주세요. 로컬 로그인 명령 ./scripts/llm login 을 다시 실행하세요.", false));
    }
}
