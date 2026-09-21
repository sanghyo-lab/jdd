package com.jdd.agent.infra;

import com.jdd.agent.domain.InvestigationModel.Prompt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class InvestigationPromptLoader {
    private InvestigationPromptLoader() {}
    public static Prompt load() {
        String version = "investigation-system-v1";
        try (var stream = InvestigationPromptLoader.class.getResourceAsStream("/prompts/" + version + ".md")) {
            if (stream == null) throw new IllegalStateException("Investigation system prompt is missing");
            byte[] bytes = stream.readAllBytes();
            return new Prompt(version, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot load investigation system prompt", failure);
        }
    }
}
