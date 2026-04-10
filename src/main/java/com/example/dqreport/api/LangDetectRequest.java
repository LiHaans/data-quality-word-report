package com.example.dqreport.api;

public class LangDetectRequest {
    private String text;

    public LangDetectRequest() {
    }

    public LangDetectRequest(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
