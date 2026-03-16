package com.example.dqreport;

import com.example.dqreport.service.WordReportGenerator;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DataQualityReportApplication {

    public static void main(String[] args) throws Exception {
        Path outputDir = Paths.get("output");
        WordReportGenerator generator = new WordReportGenerator();
        Path reportPath = generator.generate(outputDir);

        System.out.println("报告已生成: " + reportPath.toAbsolutePath());
    }
}
