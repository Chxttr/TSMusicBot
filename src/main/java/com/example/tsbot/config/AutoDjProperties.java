package com.example.tsbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "autodj")
public class AutoDjProperties {

    private boolean enabled = true;
    private String ollamaUrl = "http://localhost:30068";
    private String model = "llama3";
    private int suggestCount = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOllamaUrl() {
        return ollamaUrl;
    }

    public void setOllamaUrl(String ollamaUrl) {
        this.ollamaUrl = ollamaUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getSuggestCount() {
        return suggestCount;
    }

    public void setSuggestCount(int suggestCount) {
        this.suggestCount = suggestCount;
    }
}
