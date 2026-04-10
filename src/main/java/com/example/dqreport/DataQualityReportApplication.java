package com.example.dqreport;

import com.example.dqreport.api.LangDetectServer;
import com.example.dqreport.service.WordReportGenerator;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DataQualityReportApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--lang-api".equalsIgnoreCase(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
            LangDetectServer server = new LangDetectServer(port);
            server.start();
            return;
        }

        Path outputDir = Paths.get("output");
        WordReportGenerator generator = new WordReportGenerator();
        Path reportPath = generator.generate(outputDir);

        System.out.println("报告已生成: " + reportPath.toAbsolutePath());
    }
}
