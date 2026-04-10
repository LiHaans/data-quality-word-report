package com.example.dqreport.util;

public class LanguageDetectionResult {
    private final String lang;
    private final String rawLang;
    private final double confidence;
    private final int filipinoScore;
    private final String reason;

    public LanguageDetectionResult(String lang, String rawLang, double confidence, int filipinoScore, String reason) {
        this.lang = lang;
        this.rawLang = rawLang;
        this.confidence = confidence;
        this.filipinoScore = filipinoScore;
        this.reason = reason;
    }

    public String getLang() {
        return lang;
    }

    public String getRawLang() {
        return rawLang;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getFilipinoScore() {
        return filipinoScore;
    }

    public String getReason() {
        return reason;
    }
}
