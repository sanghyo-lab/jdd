package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.AnalysisReport;
import com.jdd.agent.domain.Investigation.EvidenceSummary;
import com.jdd.agent.domain.Investigation.EvidenceType;
import com.jdd.agent.domain.Investigation.SupportLevel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates a model candidate against observations already stored for this investigation. */
public final class ReportValidator {
    private static final Set<String> INPUT_FIELDS = Set.of("message", "context.customerId", "context.orderId",
            "context.productId", "context.requestId", "context.checkoutKey", "context.occurredAt");

    public List<String> validate(AnalysisReport report, List<EvidenceSummary> storedEvidence) {
        var errors = new ArrayList<String>();
        if (report == null) return List.of("report is required");
        if (!"1.0".equals(report.schemaVersion())) errors.add("schemaVersion must be 1.0");
        text(report.summary(), "summary", errors);
        var evidenceIds = new HashSet<String>();
        var sourcePaths = new HashSet<String>();
        for (var evidence : storedEvidence) {
            evidenceIds.add(evidence.evidenceId());
            if (evidence.type() == EvidenceType.CODE && evidence.source() != null
                    && evidence.source().get("path") instanceof String path) sourcePaths.add(path);
        }
        var itemIds = new HashSet<String>();
        if (report.facts() == null) errors.add("facts is required");
        else for (var fact : report.facts()) {
            if (fact == null) { errors.add("facts contains null"); continue; }
            item(fact.id(), fact.description(), "facts", itemIds, errors);
            references(fact.evidenceIds(), true, "facts", evidenceIds, errors);
        }
        if (report.hypotheses() == null) errors.add("hypotheses is required");
        else for (var candidate : report.hypotheses()) {
            if (candidate == null) { errors.add("hypotheses contains null"); continue; }
            item(candidate.id(), candidate.description(), "hypotheses", itemIds, errors);
            if (candidate.supportLevel() == null) errors.add("hypotheses.supportLevel is required");
            references(candidate.evidenceIds(), candidate.supportLevel() != SupportLevel.UNVERIFIED,
                    "hypotheses", evidenceIds, errors);
            texts(candidate.limitations(), candidate.supportLevel() != SupportLevel.SUPPORTED,
                    "hypotheses.limitations", errors);
        }
        if (report.actions() == null) errors.add("actions is required");
        else for (var action : report.actions()) {
            if (action == null) { errors.add("actions contains null"); continue; }
            item(action.id(), action.description(), "actions", itemIds, errors);
            references(action.evidenceIds(), false, "actions", evidenceIds, errors);
            if (!action.requiresHumanAction()) errors.add("actions.requiresHumanAction must be true");
        }
        if (report.prevention() == null) errors.add("prevention is required");
        else for (var change : report.prevention()) {
            if (change == null) { errors.add("prevention contains null"); continue; }
            item(change.id(), change.description(), "prevention", itemIds, errors);
            references(change.evidenceIds(), false, "prevention", evidenceIds, errors);
            texts(change.validationSteps(), true, "prevention.validationSteps", errors);
            texts(change.targetPaths(), false, "prevention.targetPaths", errors);
            if (change.targetPaths() != null) for (String path : change.targetPaths()) {
                if (!sourcePaths.contains(path)) errors.add("prevention.targetPaths contains an unobserved source path");
            }
        }
        if (report.missingInformation() == null) errors.add("missingInformation is required");
        else {
            var missingFields = new HashSet<String>();
            for (var missing : report.missingInformation()) {
                if (missing == null) { errors.add("missingInformation contains null"); continue; }
                if (missing.field() == null || !INPUT_FIELDS.contains(missing.field())) {
                    errors.add("missingInformation.field must be an allowed user input");
                } else if (!missingFields.add(missing.field())) errors.add("missingInformation contains a duplicate field");
                text(missing.reason(), "missingInformation.reason", errors);
            }
        }
        return List.copyOf(errors);
    }

    private static void item(String id, String description, String field, Set<String> ids, List<String> errors) {
        text(id, field + ".id", errors);
        text(description, field + ".description", errors);
        if (id != null && !ids.add(id)) errors.add("report item IDs must be unique");
    }

    private static void references(List<String> ids, boolean required, String field,
                                   Set<String> stored, List<String> errors) {
        texts(ids, required, field + ".evidenceIds", errors);
        if (ids != null) for (String id : ids) {
            if (!stored.contains(id)) errors.add(field + ".evidenceIds contains an unknown observation");
        }
    }

    private static void texts(List<String> values, boolean required, String field, List<String> errors) {
        if (values == null) { errors.add(field + " is required"); return; }
        if (required && values.isEmpty()) errors.add(field + " must not be empty");
        for (String value : values) text(value, field, errors);
    }

    private static void text(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) errors.add(field + " must not be blank");
    }
}
