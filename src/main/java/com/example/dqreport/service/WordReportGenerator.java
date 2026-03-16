package com.example.dqreport.service;

import com.example.dqreport.model.IssueRecord;
import com.example.dqreport.model.TrendPoint;
import com.example.dqreport.util.ChartGenerator;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class WordReportGenerator {

    public Path generate(Path outputDir) throws Exception {
        Files.createDirectories(outputDir);

        List<IssueRecord> records = DemoDataFactory.createIssueRecords();
        Map<String, Integer> systemDistribution = DemoDataFactory.buildSystemDistribution(records);
        Map<String, Integer> issueTypeDistribution = DemoDataFactory.buildIssueTypeDistribution(records);
        List<TrendPoint> trend = DemoDataFactory.createNinetyDayTrend();

        Path chartDir = outputDir.resolve("charts");
        File chart1 = ChartGenerator.generatePieChart("问题分布图", systemDistribution, chartDir, "pie-system.png");
        File chart2 = ChartGenerator.generatePieChart("问题类型分布图", issueTypeDistribution, chartDir, "pie-type.png");
        File chart3 = ChartGenerator.generateNinetyDayTrendChart("近90天的问题数据量趋势图", trend, chartDir, "trend-90days.png");

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path reportPath = outputDir.resolve("data-quality-report-" + timestamp + ".docx");

        try (XWPFDocument document = new XWPFDocument(); FileOutputStream out = new FileOutputStream(reportPath.toFile())) {
            addTitle(document, "数据质量报告");

            addSectionWithImage(document, "问题分布图", chart1, 520, 300);
            addSectionWithImage(document, "问题类型分布图", chart2, 520, 300);
            addSectionWithImage(document, "近90天的问题数据量趋势图", chart3, 520, 260);

            addHeading(document, "问题明细表", true, 14);
            addIssueTable(document, records);

            document.write(out);
        }

        return reportPath;
    }

    private void addTitle(XWPFDocument document, String titleText) {
        XWPFParagraph title = document.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = title.createRun();
        run.setBold(true);
        run.setFontSize(22);
        run.setText(titleText);

        document.createParagraph();
    }

    private void addSectionWithImage(XWPFDocument document,
                                     String heading,
                                     File image,
                                     int width,
                                     int height) throws Exception {
        addHeading(document, heading, true, 14);

        XWPFParagraph chartParagraph = document.createParagraph();
        chartParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun chartRun = chartParagraph.createRun();

        try (FileInputStream chartInput = new FileInputStream(image)) {
            chartRun.addPicture(
                    chartInput,
                    XWPFDocument.PICTURE_TYPE_PNG,
                    image.getName(),
                    Units.toEMU(width),
                    Units.toEMU(height)
            );
        }

        document.createParagraph();
    }

    private void addHeading(XWPFDocument document, String text, boolean bold, int fontSize) {
        XWPFParagraph heading = document.createParagraph();
        heading.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = heading.createRun();
        run.setBold(bold);
        run.setFontSize(fontSize);
        run.setText(text);
    }

    private void addIssueTable(XWPFDocument document, List<IssueRecord> records) {
        XWPFTable table = document.createTable(records.size() + 1, 8);
        XWPFTableRow header = table.getRow(0);
        header.getCell(0).setText("编号");
        header.getCell(1).setText("问题名称");
        header.getCell(2).setText("问题类型");
        header.getCell(3).setText("所属系统");
        header.getCell(4).setText("严重程度");
        header.getCell(5).setText("状态");
        header.getCell(6).setText("发现日期");
        header.getCell(7).setText("责任人");

        for (int i = 0; i < records.size(); i++) {
            IssueRecord record = records.get(i);
            XWPFTableRow row = table.getRow(i + 1);
            row.getCell(0).setText(record.getId());
            row.getCell(1).setText(record.getIssueName());
            row.getCell(2).setText(record.getIssueType());
            row.getCell(3).setText(record.getSystemName());
            row.getCell(4).setText(record.getSeverity());
            row.getCell(5).setText(record.getStatus());
            row.getCell(6).setText(record.getFoundDate().toString());
            row.getCell(7).setText(record.getOwner());
        }
    }
}
