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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ChartGenerator {

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
        PiePlot<?> plot = (PiePlot<?>) chart.getPlot();
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

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setRangeGridlinePaint(new Color(220, 220, 220));
        plot.setDomainGridlinePaint(new Color(220, 220, 220));

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setDateFormatOverride(new SimpleDateFormat("MM-dd"));

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        renderer.setSeriesShapesVisible(0, false);
        renderer.setSeriesPaint(0, new Color(54, 162, 235));

        File outputFile = outputDir.resolve(fileName).toFile();
        ChartUtils.saveChartAsPNG(outputFile, chart, 960, 420);
        return outputFile;
    }
}
