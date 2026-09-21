package com.jdd.agent;

import com.jdd.agent.domain.InvestigationRepository;
import com.jdd.agent.domain.InvestigationService;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

@Configuration
public class InvestigationConfiguration {
    @Bean JsonMapperBuilderCustomizer strictInvestigationStrings() {
        // ALLOW_COERCION_OF_SCALARS does not cover JSON numbers/booleans bound to String.
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .addModule(new SimpleModule("strict-investigation-timestamps")
                        .addDeserializer(Instant.class, new IsoInstantDeserializer()));
    }

    private static final class IsoInstantDeserializer extends ValueDeserializer<Instant> {
        @Override public Instant deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(Instant.class, "Timestamp must be an ISO-8601 string");
            }
            try {
                return Instant.parse(parser.getString());
            } catch (DateTimeParseException invalid) {
                return context.reportInputMismatch(Instant.class, "Timestamp must include a valid UTC offset");
            }
        }
    }

    @Bean InvestigationService investigationService(InvestigationRepository repository) {
        return new InvestigationService(repository, Clock.systemUTC());
    }
}
