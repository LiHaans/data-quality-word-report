package com.example.dqreport.api;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class LangDetectServer {

    private final int port;
    private HttpServer server;

    public LangDetectServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/lang/detect", new LangDetectHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("LangDetectServer started at http://127.0.0.1:" + port + "/api/lang/detect");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
