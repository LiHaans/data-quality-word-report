# data-quality-word-report

一个兼容 Java 8 的 Maven Demo：使用 **Apache POI XWPF** 生成 `.docx` 报告，使用 **JFreeChart** 先生成图表图片再嵌入 Word。

## 功能说明

运行后会在报告中生成以下内容：

1. 标题：`数据质量报告`
2. 饼图：`问题分布图`
3. 饼图：`问题类型分布图`
4. 趋势图：`近90天的问题数据量趋势图`
5. 表格：`问题明细表`

数据全部使用代码内置 Demo 数据，不依赖外部服务。

## 环境要求

- JDK 8+
- Maven 3.6+

## 运行方式

```bash
mvn clean compile exec:java
```

## 输出结果

- Word 报告输出目录：`output/`
- 图表图片输出目录：`output/charts/`
- 报告文件示例：`output/data-quality-report-20250315_173000.docx`

## 项目结构

```text
src/main/java/com/example/dqreport/
├── DataQualityReportApplication.java    # 主类
├── model/
│   ├── IssueRecord.java                 # 问题明细实体
│   └── TrendPoint.java                  # 趋势点实体
├── service/
│   ├── DemoDataFactory.java             # Demo 数据构造
│   └── WordReportGenerator.java         # Word 报告生成
└── util/
    └── ChartGenerator.java              # 图表生成工具
```
