# Flink Doris Sink 写入机制与事务超时问题分析

## 1. 背景

当前使用 Flink 将数据写入 Doris，已知现象为：

- Doris 报错：**事务超时**
- 当前仅配置了：

```sql
'sink.mode' = 'batch'
```

- 其他参数均使用默认配置

本文档基于 `/data/code/doris-flink-connector` 项目源码分析 Doris Connector 的写入机制，并结合默认参数行为，给出事务超时问题的原因分析与建议配置。

---

## 2. 结论先行

如果只配置：

```sql
'sink.mode' = 'batch'
```

而其他参数保持默认，那么**非常容易出现 Doris 事务超时问题**。

根本原因不是单纯因为“batch 模式”本身，而是：

> Doris Flink Connector 的 batch 模式底层仍然是 **Stream Load 批量写入**，并且会结合 **buffer 累积、定时 flush、异步 flush、checkpoint flush、2PC/commit** 一起工作。

如果批次过大、flush 太慢、checkpoint 太久、2PC 开启、Doris 导入能力不足，就会导致 Doris 端事务存活时间过长，从而出现事务超时。

---

## 3. Connector 是怎么写入 Doris 的

## 3.1 `sink.mode=batch` 的本质

从源码中可以看到，batch 模式并不是 JDBC 批量写，也不是一次性文件导入，而是会映射到 Doris Connector 的：

```java
WriteMode.STREAM_LOAD_BATCH
```

也就是说：

- 开启 batch 模式
- 底层仍然是通过 Doris `/_stream_load` HTTP 接口写入
- 只是采用“缓冲 + 批量 flush”的方式组织数据

---

## 3.2 关键源码路径

### 1）参数解析入口

文件：

```text
org/apache/doris/flink/table/DorisDynamicTableFactory.java
```

关键逻辑：

```java
builder.setBatchMode(readableConfig.get(SINK_ENABLE_BATCH_MODE));
if (readableConfig.get(SINK_ENABLE_BATCH_MODE)) {
    builder.setWriteMode(WriteMode.STREAM_LOAD_BATCH);
}
```

说明：

- `sink.enable.batch-mode = true`
- 最终进入 `STREAM_LOAD_BATCH`

---

### 2）真正的 sink writer

文件：

```text
org/apache/doris/flink/sink/DorisSink.java
```

关键逻辑：

```java
} else if (WriteMode.STREAM_LOAD_BATCH.equals(dorisExecutionOptions.getWriteMode())) {
    return new DorisBatchWriter(...)
}
```

说明：

- batch 模式最终由 `DorisBatchWriter` 负责写入

---

### 3）批量 writer 的行为

文件：

```text
org/apache/doris/flink/sink/batch/DorisBatchWriter.java
```

关键逻辑：

- 数据写入 buffer
- 定时线程定期 flush
- checkpoint 时强制 flush

例如：

```java
scheduledExecutorService.scheduleWithFixedDelay(
    this::intervalFlush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
```

以及 checkpoint：

```java
batchStreamLoad.checkpointFlush();
```

---

### 4）底层真正写 Doris 的方式

文件：

```text
org/apache/doris/flink/sink/batch/DorisBatchStreamLoad.java
```

关键逻辑：

```java
private static final String LOAD_URL_PATTERN = "http://%s/api/%s/%s/_stream_load";
```

真正写入通过：

```java
httpClient.execute(putBuilder.build())
```

说明：

- 底层就是 Doris HTTP Stream Load
- 每次 flush 本质上对应一个 Doris 导入事务

---

## 4. Batch 模式下完整写入链路

可以概括为：

```text
Flink 记录进入 Sink
  -> 写入内存 buffer
  -> 到达 max-rows/max-bytes/interval 条件
  -> 放入 flushQueue
  -> 异步线程执行 Stream Load
  -> Doris 创建导入事务
  -> 导入完成后提交事务
  -> checkpoint 时强制 flush 并等待异步 load 完成
```

也就是说：

> 一个 Doris 事务的生命周期并不是“单条写入瞬间完成”，而是和 Flink 批次积累、异步 flush、checkpoint/commit 行为强相关。

---

## 5. 默认参数分析

根据源码默认值，batch 模式下几个关键参数如下。

文件：

```text
org/apache/doris/flink/cfg/DorisExecutionOptions.java
org/apache/doris/flink/table/DorisConfigOptions.java
```

---

## 5.1 默认 flush 行数

```java
DEFAULT_BUFFER_FLUSH_MAX_ROWS = 500000;
```

也就是：

```sql
'sink.buffer-flush.max-rows' = '500000'
```

### 风险

50 万行一个 batch，对于很多业务来说已经很大。

如果：

- 单行较宽
- 列很多
- 包含大文本 / JSON
- Doris 表写入本身成本高
- FE/BE 压力偏大

那么一个 batch 执行时间会明显变长，事务更容易超时。

---

## 5.2 默认 flush 字节数

```java
DEFAULT_BUFFER_FLUSH_MAX_BYTES = 100 * 1024 * 1024;
```

也就是：

```sql
'sink.buffer-flush.max-bytes' = '100mb'
```

### 风险

100MB 的单次 Stream Load 对很多生产链路来说并不小。

如果网络、BE 导入、compaction、tablet 分布不理想，就会导致这次导入事务持续时间过长。

---

## 5.3 默认 flush 间隔

```java
DEFAULT_BUFFER_FLUSH_INTERVAL_MS = 10 * 1000;
```

即：

```sql
'sink.buffer-flush.interval' = '10s'
```

### 风险

如果流量高，10 秒内会攒下很多数据；
如果流量低，又会在 checkpoint 时集中强刷。

这两种情况都可能让单次事务偏重。

---

## 5.4 默认 flush 队列大小

```java
DEFAULT_FLUSH_QUEUE_SIZE = 2;
```

即：

```sql
'sink.flush.queue-size' = '2'
```

### 风险

flush 队列较小，在 Doris 端处理慢时，容易出现：

- flush 线程阻塞
- buffer 持续堆积
- checkpoint 期间等待时间拉长

---

## 5.5 默认开启 2PC

在 `DorisConfigOptions.java` 中：

```java
sink.enable-2pc defaultValue(true)
```

### 风险

即使开了 batch 模式，2PC 默认仍然开启。

而 2PC 会让事务和 checkpoint 的生命周期耦合得更紧：

- writer flush
- checkpoint 触发
- prepare/commit
- Doris 事务等待最终提交

如果 checkpoint 周期、checkpoint 对齐或 Doris 导入速度较慢，事务就更容易超时。

---

## 6. 为什么会出现 Doris 事务超时

Doris 事务超时的本质是：

> 某个导入事务从开始到完成提交，耗时超过 Doris 允许的超时时间。

在当前 connector 下，导致超时的常见原因包括：

### 1）单批次过大

例如：

- 50 万行
- 100MB

导致单次 stream load 执行太久。

---

### 2）checkpoint 触发时强制 flush

源码中 checkpoint 会执行：

```java
batchStreamLoad.checkpointFlush();
```

并等待异步导入完成。

如果此时缓存很多数据，checkpoint 会明显变慢，同时 Doris 事务生命周期被拉长。

---

### 3）2PC 打开导致事务存活时间进一步增长

事务需要等待更完整的 checkpoint/commit 生命周期。

---

### 4）Doris 端导入能力不足

例如：

- FE/BE 负载高
- tablet 多
- compaction 正忙
- 导入并发高
- 表模型写入成本大（如聚合表、复杂索引）

---

### 5）数据本身较宽或包含大字段

例如：

- 文本列很大
- JSON 很长
- 宽表列数很多

这会让单批虽然行数不夸张，但 payload 已经很大。

---

## 7. 你当前场景的问题判断

已知你只配置了：

```sql
'sink.mode' = 'batch'
```

其他都默认。

那么最可能的问题是：

### 高概率原因

1. **默认批次太大**
2. **默认 2PC 开启**
3. **checkpoint 期间强制 flush 导致事务过长**
4. **Doris 导入速度跟不上默认 batch 大小**

因此问题大概率不是 Doris 单方面故障，而是：

> **Connector 默认参数偏向吞吐，而不是偏向避免事务超时。**

---

## 8. 建议的优化原则

如果当前优先目标是：

- 先跑稳
- 减少 Doris 事务超时
- 允许牺牲一部分吞吐

建议遵循以下原则：

### 原则 1：缩小单批次大小

优先调小：

- `sink.buffer-flush.max-rows`
- `sink.buffer-flush.max-bytes`
- `sink.buffer-flush.interval`

---

### 原则 2：先关闭 2PC 观察效果

如果当前不强依赖 exactly-once，可以先：

```sql
'sink.enable-2pc' = 'false'
```

---

### 原则 3：降低每次 checkpoint 的 flush 压力

如果 checkpoint 周期很长、单次数据积累很多，也容易放大问题。

---

### 原则 4：根据数据宽度调整 bytes，而不是只看 rows

宽表、大文本场景下，`max-bytes` 比 `max-rows` 更关键。

---

## 9. 推荐配置方案

## 9.1 推荐第一版（先求稳定）

适合当前你这种已经出现事务超时、先止血的场景：

```sql
CREATE TABLE doris_sink_table (
    id BIGINT,
    title STRING,
    content STRING,
    site_name STRING,
    publish_time TIMESTAMP(3),
    lang STRING,
    dt STRING
) WITH (
    'connector' = 'doris',
    'fenodes' = '127.0.0.1:8030',
    'table.identifier' = 'test_db.test_table',
    'username' = 'root',
    'password' = '123456',

    'sink.enable.batch-mode' = 'true',
    'sink.enable-2pc' = 'false',

    'sink.buffer-flush.max-rows' = '50000',
    'sink.buffer-flush.max-bytes' = '10mb',
    'sink.buffer-flush.interval' = '5s',

    'sink.flush.queue-size' = '2',
    'sink.max-retries' = '3',
    'sink.label-prefix' = 'flink_doris_sink',

    'sink.properties.format' = 'json',
    'sink.properties.read_json_by_line' = 'true',
    'sink.properties.group_commit' = 'off_mode'
);
```

---

## 9.2 更保守版本（优先防超时）

如果上面这版仍然超时，则进一步减小：

```sql
'sink.enable.batch-mode' = 'true',
'sink.enable-2pc' = 'false',
'sink.buffer-flush.max-rows' = '20000',
'sink.buffer-flush.max-bytes' = '5mb',
'sink.buffer-flush.interval' = '3s',
'sink.flush.queue-size' = '2',
'sink.max-retries' = '3'
```

适合：

- 宽表
- 大文本
- Doris 集群写入压力偏大
- 当前事务超时频繁

---

## 9.3 必须保留 2PC 的版本

如果业务必须保留更强语义，可以这样：

```sql
'sink.enable.batch-mode' = 'true',
'sink.enable-2pc' = 'true',
'sink.buffer-flush.max-rows' = '20000',
'sink.buffer-flush.max-bytes' = '5mb',
'sink.buffer-flush.interval' = '3s'
```

但要明确：

- 保留 2PC 后，事务超时风险会高于关闭 2PC 的方案
- 此时更依赖 checkpoint 与 Doris 端性能稳定

---

## 10. 为什么这些参数有效

## 10.1 减小 `max-rows`

作用：

- 降低单次事务处理行数
- 每次 stream load 更快结束

---

## 10.2 减小 `max-bytes`

作用：

- 降低单次 HTTP Stream Load payload 大小
- 更适合宽表与大文本场景

---

## 10.3 缩短 `flush.interval`

作用：

- 不让 buffer 积攒太久
- 减轻 checkpoint 时强制 flush 的压力

---

## 10.4 关闭 `sink.enable-2pc`

作用：

- 减少事务与 checkpoint 提交流程耦合
- 降低事务存活时间

---

## 11. 排查建议

如果你后续还要继续定位，请重点看以下几个方面。

## 11.1 Flink 侧

重点看：

- checkpoint interval
- checkpoint timeout
- checkpoint duration
- checkpoint alignment time
- sink backpressure
- sink flush 是否在 checkpoint 时明显变慢

---

## 11.2 Doris 侧

重点看：

- FE 导入事务超时参数
- Stream Load 导入耗时
- BE 是否高负载
- Compaction 是否繁忙
- 导入并发是否过高

---

## 11.3 数据特征

重点看：

- 单行大小
- 列数
- 是否包含大文本/JSON
- 实际单批 payload 大小

---

## 12. 最终结论

你当前只配置：

```sql
'sink.mode' = 'batch'
```

而其他参数使用默认值时，事务超时的原因大概率是：

> **默认 batch 阈值偏大、2PC 默认开启、checkpoint 强制 flush、Doris 导入耗时共同作用，导致 Doris 事务生命周期过长。**

因此，建议优先采用以下策略：

1. 显式开启 batch 模式
2. 显式关闭 2PC（如果业务允许）
3. 将 `max-rows` 从 500000 降到 50000 或 20000
4. 将 `max-bytes` 从 100MB 降到 10MB 或 5MB
5. 将 `flush.interval` 从 10s 降到 5s 或 3s

---

## 13. 一句话建议

如果你现在的目标是先解决 Doris 事务超时，最推荐先试这一组：

```sql
'sink.enable.batch-mode' = 'true',
'sink.enable-2pc' = 'false',
'sink.buffer-flush.max-rows' = '50000',
'sink.buffer-flush.max-bytes' = '10mb',
'sink.buffer-flush.interval' = '5s'
```

如果还不稳，再进一步缩小到：

```sql
'sink.buffer-flush.max-rows' = '20000',
'sink.buffer-flush.max-bytes' = '5mb',
'sink.buffer-flush.interval' = '3s'
```
