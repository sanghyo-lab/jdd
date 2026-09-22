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
        String version = "investigation-system-v9";
        try {
            String common = resource(version);
            String report = common + "\n\n" + resource("investigation-report-v9");
            String review = common + "\n\n" + resource("investigation-review-v9");
            // Hash both complete instruction variants in a fixed order, separated by NUL.
            byte[] bundle = (report + "\0" + review).getBytes(StandardCharsets.UTF_8);
            return new Prompt(version, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bundle)),
                    report, review);
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot load investigation system prompt", failure);
        }
    }
    private static String resource(String name) throws IOException {
        try (var stream = InvestigationPromptLoader.class.getResourceAsStream("/prompts/" + name + ".md")) {
            if (stream == null) throw new IllegalStateException("Investigation system prompt is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
