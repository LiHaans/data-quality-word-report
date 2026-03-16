package com.example.dqreport.service;

import com.example.dqreport.model.IssueRecord;
import com.example.dqreport.model.TrendPoint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class DemoDataFactory {

    private DemoDataFactory() {
    }

    public static List<IssueRecord> createIssueRecords() {
        List<IssueRecord> records = new ArrayList<IssueRecord>();
        LocalDate baseDate = LocalDate.now();

        records.add(new IssueRecord("DQ-001", "用户主键重复", "唯一性", "客户主数据", "高", "处理中", baseDate.minusDays(2), "张三"));
        records.add(new IssueRecord("DQ-002", "身份证号缺失", "完整性", "客户主数据", "高", "已修复", baseDate.minusDays(5), "李四"));
        records.add(new IssueRecord("DQ-003", "地址字段格式异常", "有效性", "营销标签", "中", "待处理", baseDate.minusDays(7), "王五"));
        records.add(new IssueRecord("DQ-004", "交易金额精度不一致", "一致性", "交易流水", "高", "处理中", baseDate.minusDays(10), "赵六"));
        records.add(new IssueRecord("DQ-005", "手机号重复绑定", "唯一性", "账户中心", "中", "待处理", baseDate.minusDays(12), "钱七"));
        records.add(new IssueRecord("DQ-006", "日期字段非法", "有效性", "交易流水", "低", "已修复", baseDate.minusDays(16), "孙八"));
        records.add(new IssueRecord("DQ-007", "机构编码缺失", "完整性", "财务报表", "中", "处理中", baseDate.minusDays(18), "周九"));
        records.add(new IssueRecord("DQ-008", "指标口径冲突", "一致性", "风控指标", "高", "处理中", baseDate.minusDays(20), "吴十"));
        records.add(new IssueRecord("DQ-009", "账号格式异常", "有效性", "账户中心", "中", "待处理", baseDate.minusDays(25), "郑十一"));
        records.add(new IssueRecord("DQ-010", "渠道编码缺失", "完整性", "营销标签", "低", "已修复", baseDate.minusDays(29), "王十二"));

        return records;
    }

    public static Map<String, Integer> buildSystemDistribution(List<IssueRecord> records) {
        Map<String, Integer> distribution = new LinkedHashMap<String, Integer>();
        for (IssueRecord record : records) {
            increase(distribution, record.getSystemName());
        }
        return distribution;
    }

    public static Map<String, Integer> buildIssueTypeDistribution(List<IssueRecord> records) {
        Map<String, Integer> distribution = new LinkedHashMap<String, Integer>();
        for (IssueRecord record : records) {
            increase(distribution, record.getIssueType());
        }
        return distribution;
    }

    public static List<TrendPoint> createNinetyDayTrend() {
        List<TrendPoint> trend = new ArrayList<TrendPoint>();
        LocalDate startDate = LocalDate.now().minusDays(89);
        Random random = new Random(42);

        for (int i = 0; i < 90; i++) {
            int wave = (int) (8 + 4 * Math.sin(i / 8.0));
            int noise = random.nextInt(4);
            int value = Math.max(1, wave + noise);
            trend.add(new TrendPoint(startDate.plusDays(i), value));
        }

        return trend;
    }

    private static void increase(Map<String, Integer> target, String key) {
        Integer current = target.get(key);
        if (current == null) {
            target.put(key, 1);
        } else {
            target.put(key, current + 1);
        }
    }
}
