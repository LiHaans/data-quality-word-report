package com.example.dqreport.api;

public class LangDetectResponse {
    private String lang;
    private String rawLang;
    private double confidence;
    private int filipinoScore;
    private String reason;

    public LangDetectResponse() {
    }

    public LangDetectResponse(String lang, String rawLang, double confidence, int filipinoScore, String reason) {
        this.lang = lang;
        this.rawLang = rawLang;
        this.confidence = confidence;
        this.filipinoScore = filipinoScore;
        this.reason = reason;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getRawLang() {
        return rawLang;
    }

    public void setRawLang(String rawLang) {
        this.rawLang = rawLang;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public int getFilipinoScore() {
        return filipinoScore;
    }

    public void setFilipinoScore(int filipinoScore) {
        this.filipinoScore = filipinoScore;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
