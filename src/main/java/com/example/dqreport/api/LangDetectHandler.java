package com.example.dqreport.api;

import com.example.dqreport.util.LanguageDetectionResult;
import com.example.dqreport.util.LanguageDetector;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class LangDetectHandler implements HttpHandler {

    private final Gson gson = new Gson();
    private final LanguageDetector detector = new LanguageDetector();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("Method Not Allowed"));
            return;
        }

        String body = readBody(exchange);
        LangDetectRequest request = gson.fromJson(body, LangDetectRequest.class);
        if (request == null || request.getText() == null || request.getText().trim().isEmpty()) {
            writeJson(exchange, 400, error("text is required"));
            return;
        }

        LanguageDetectionResult result = detector.detectDetailed(request.getText());
        LangDetectResponse response = new LangDetectResponse(
                result.getLang(),
                result.getRawLang(),
                result.getConfidence(),
                result.getFilipinoScore(),
                result.getReason()
        );
        writeJson(exchange, 200, gson.toJson(response));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String error(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return gson.toJson(obj);
    }
}
