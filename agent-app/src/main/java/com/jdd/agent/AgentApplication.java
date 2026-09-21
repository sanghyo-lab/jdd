package com.jdd.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(AgentApplication.class);
        app.addInitializers(context -> com.jdd.agent.infra.LlmRuntimeConfiguration.validateRuntime(context.getEnvironment()::getProperty));
        app.run(args);
    }
}
