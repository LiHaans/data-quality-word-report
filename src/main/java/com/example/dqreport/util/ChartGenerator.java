package com.example.dqreport.util;

import com.example.dqreport.model.TrendPoint;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ChartGenerator {

    private static final Font TITLE_FONT = pickFont(Font.BOLD, 18);
    private static final Font LABEL_FONT = pickFont(Font.PLAIN, 12);
    private static final Font TICK_FONT = pickFont(Font.PLAIN, 11);

    private ChartGenerator() {
    }

    public static File generatePieChart(String title,
                                        Map<String, Integer> distribution,
                                        Path outputDir,
                                        String fileName) throws IOException {
        Files.createDirectories(outputDir);

        DefaultPieDataset<String> dataset = new DefaultPieDataset<String>();
        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            dataset.setValue(entry.getKey(), entry.getValue());
        }

        JFreeChart chart = ChartFactory.createPieChart(title, dataset, true, false, false);
        applyCommonFont(chart);

        PiePlot<?> plot = (PiePlot<?>) chart.getPlot();
        plot.setLabelFont(LABEL_FONT);
        plot.setSectionOutlinesVisible(false);
        plot.setBackgroundPaint(Color.WHITE);
        plot.setShadowPaint(null);

        File outputFile = outputDir.resolve(fileName).toFile();
        ChartUtils.saveChartAsPNG(outputFile, chart, 720, 420);
        return outputFile;
    }

    public static File generateNinetyDayTrendChart(String title,
                                                   List<TrendPoint> trend,
                                                   Path outputDir,
                                                   String fileName) throws IOException {
        Files.createDirectories(outputDir);

        TimeSeries series = new TimeSeries("问题数量");
        for (TrendPoint point : trend) {
            LocalDate date = point.getDate();
            series.add(new Day(date.getDayOfMonth(), date.getMonthValue(), date.getYear()), point.getCount());
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection(series);
        JFreeChart chart = ChartFactory.createTimeSeriesChart(title, "日期", "数量", dataset, false, false, false);
        applyCommonFont(chart);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setRangeGridlinePaint(new Color(220, 220, 220));
        plot.setDomainGridlinePaint(new Color(220, 220, 220));

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setDateFormatOverride(new SimpleDateFormat("MM-dd"));
        domainAxis.setLabelFont(LABEL_FONT);
        domainAxis.setTickLabelFont(TICK_FONT);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        rangeAxis.setLabelFont(LABEL_FONT);
        rangeAxis.setTickLabelFont(TICK_FONT);

        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        renderer.setSeriesShapesVisible(0, false);
        renderer.setSeriesPaint(0, new Color(54, 162, 235));

        File outputFile = outputDir.resolve(fileName).toFile();
        ChartUtils.saveChartAsPNG(outputFile, chart, 960, 420);
        return outputFile;
    }

    private static void applyCommonFont(JFreeChart chart) {
        if (chart.getTitle() != null) {
            chart.getTitle().setFont(TITLE_FONT);
        }
        if (chart.getLegend() != null) {
            chart.getLegend().setItemFont(LABEL_FONT);
        }
    }

    private static Font pickFont(int style, int size) {
        String[] candidates = {
                "Noto Sans CJK SC",
                "Noto Sans CJK CN",
                "WenQuanYi Zen Hei",
                "Source Han Sans SC",
                "Microsoft YaHei",
                "SimHei",
                "SansSerif"
        };

        for (String name : candidates) {
            Font font = new Font(name, style, size);
            if (font.canDisplay('中')) {
                return font;
            }
        }

        return new Font("SansSerif", style, size);
    }
}
