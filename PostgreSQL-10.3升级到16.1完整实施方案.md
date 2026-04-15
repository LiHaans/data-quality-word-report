# PostgreSQL 10.3 升级到 16.1 完整实施方案（适配 Spring Boot + MyBatis-Plus 微服务）

## 1. 文档目标

本文档用于指导以下场景的 PostgreSQL 大版本升级：

- 当前数据库版本：**PostgreSQL 10.3**
- 目标数据库版本：**PostgreSQL 16.1**
- 应用架构：**Spring Boot + MyBatis-Plus**
- 服务数量：**7~8 个微服务**
- 目标：
  1. 盘点原 PostgreSQL 10.3 基于官方版本做了哪些变更
  2. 识别当前数据库扩展、配置、对象与兼容性风险
  3. 将老 PG 数据平滑迁移到新 PG 16.1
  4. 修改应用依赖与配置，完成微服务切换
  5. 给出验证、切换、回滚的完整实施步骤

---

## 2. 总体结论

对于当前场景，**不建议直接原地覆盖升级 PostgreSQL 10.3**。

最推荐的方案是：

> **新建 PostgreSQL 16.1 集群 → 全量/增量迁移旧数据 → 验证应用兼容性 → 灰度切换 → 保留旧库回滚窗口**

原因如下：

1. PostgreSQL 10.3 到 16.1 跨度很大，存在参数、扩展、SQL 行为、执行计划差异
2. 微服务数量较多，切换风险高
3. 新建集群方案便于：
   - 对比测试
   - 渐进验证
   - 快速回滚
   - 避免一次性破坏生产环境

---

## 3. 升级总体路线

推荐采用以下五阶段方案：

### 阶段 1：现网盘点
- 盘点旧 PG 10.3 做过哪些变更
- 盘点扩展、用户、schema、函数、触发器、sequence、参数配置
- 盘点应用依赖与连接方式

### 阶段 2：新建 PG 16.1 环境
- 安装 PostgreSQL 16.1
- 安装与旧库相同或兼容的扩展
- 初始化库参数与角色

### 阶段 3：迁移数据与对象
- 导出 schema / role / data
- 恢复到 PG 16.1
- 修复 sequence
- 执行 ANALYZE / VACUUM ANALYZE

### 阶段 4：应用改造与联调
- 升级 PostgreSQL JDBC 驱动
- 检查 MyBatis-Plus / Spring Boot / 连接池兼容性
- 联调 SQL、分页、事务、时间类型、JSONB、扩展能力

### 阶段 5：灰度切换与回滚预案
- 停写旧库
- 增量补齐
- 业务切换到新库
- 验证
- 保留回滚窗口

---

## 4. 第一步：盘点旧 PostgreSQL 基于官方做了哪些变更

这个步骤非常关键。你要先知道旧 PG 不只是“存了数据”，还可能包含：

- 手工改过的配置参数
- 安装过的扩展
- 自定义函数 / 存储过程 / 触发器
- 自定义类型 / 自定义 operator
- 角色权限体系
- 非默认 schema 设计
- 逻辑复制 / 归档 / 备份策略
- 参数层面针对业务的特殊调优

### 4.1 先看 PostgreSQL 版本与构建来源

执行：

```sql
SELECT version();
```

重点确认：

- 是否真的是官方 PG 10.3
- 是否带有发行版打包信息
- 是否含第三方定制痕迹

例如：
- 官方源码安装
- RPM/DEB 安装
- 云厂商封装版本
- 带扩展增强版本

---

### 4.2 盘点数据库扩展

执行：

```sql
SELECT
    extname AS extension_name,
    extversion AS extension_version,
    n.nspname AS schema_name
FROM pg_extension e
JOIN pg_namespace n ON n.oid = e.extnamespace
ORDER BY 1;
```

重点看是否存在以下扩展：

- `uuid-ossp`
- `pg_trgm`
- `hstore`
- `pg_stat_statements`
- `ltree`
- `citext`
- `btree_gin`
- `btree_gist`
- `unaccent`
- `postgis`
- `pgcrypto`
- `tablefunc`
- `dblink`

### 为什么要查

因为 PG16 新环境必须先准备兼容扩展，否则：
- schema 恢复会失败
- 函数调用会报错
- 索引/查询能力会缺失

---

### 4.3 盘点非默认参数配置

执行：

```sql
SELECT name, setting, unit, source
FROM pg_settings
WHERE source NOT IN ('default', 'override')
ORDER BY name;
```

或者更关注关键参数：

```sql
SELECT name, setting, unit, source
FROM pg_settings
WHERE name IN (
    'max_connections',
    'shared_buffers',
    'work_mem',
    'maintenance_work_mem',
    'effective_cache_size',
    'wal_level',
    'max_wal_size',
    'min_wal_size',
    'checkpoint_timeout',
    'checkpoint_completion_target',
    'archive_mode',
    'archive_command',
    'shared_preload_libraries',
    'timezone',
    'log_min_duration_statement',
    'log_statement',
    'statement_timeout',
    'idle_in_transaction_session_timeout'
)
ORDER BY name;
```

### 目的

识别旧库有没有做过：
- 内存调优
- WAL / checkpoint 调优
- 归档配置
- 扩展预加载
- 时区配置
- SQL 日志配置

### 注意

不要把旧 PG10 的 `postgresql.conf` 原封不动抄到 PG16。应以 PG16 默认配置为基线，再迁移真正需要的参数。

---

### 4.4 盘点角色、用户与权限

执行：

```sql
SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolcanlogin
FROM pg_roles
ORDER BY rolname;
```

检查：
- 应用连接用户有哪些
- 是否有只读用户
- 是否有复制用户
- 是否使用超级用户跑业务

还要检查 schema 权限：

```sql
SELECT schema_name, schema_owner
FROM information_schema.schemata
ORDER BY schema_name;
```

---

### 4.5 盘点 schema、表、函数、触发器

#### 查看 schema

```sql
SELECT schema_name
FROM information_schema.schemata
ORDER BY schema_name;
```

#### 查看触发器

```sql
SELECT event_object_schema, event_object_table, trigger_name, action_timing, event_manipulation
FROM information_schema.triggers
ORDER BY event_object_schema, event_object_table, trigger_name;
```

#### 查看函数 / 存储过程

```sql
SELECT n.nspname AS schema_name,
       p.proname AS function_name,
       pg_get_function_identity_arguments(p.oid) AS arguments
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
ORDER BY 1,2;
```

### 为什么要查

升级后这些对象可能因为：
- 扩展未装
- 语法变化
- 权限变化
- search_path 变化
而报错。

---

### 4.6 盘点 sequence 与自增列

执行：

```sql
SELECT
    ns.nspname AS schema_name,
    cls.relname AS table_name,
    attr.attname AS column_name,
    pg_get_serial_sequence(format('%I.%I', ns.nspname, cls.relname), attr.attname) AS seq_name
FROM pg_class cls
JOIN pg_namespace ns ON ns.oid = cls.relnamespace
JOIN pg_attribute attr ON attr.attrelid = cls.oid
WHERE cls.relkind = 'r'
  AND attr.attnum > 0
  AND NOT attr.attisdropped
  AND pg_get_serial_sequence(format('%I.%I', ns.nspname, cls.relname), attr.attname) IS NOT NULL
ORDER BY 1,2,3;
```

### 为什么要查

迁移完数据后，很容易出现：
- sequence 未同步到当前表最大 ID
- 插入时报主键已存在

这一步后续必须修复。

---

## 5. 第二步：盘点应用侧依赖与连接方式

你现在有 7~8 个微服务，建议做一个服务盘点表。

### 5.1 建议盘点的信息

每个微服务记录：

- 服务名
- Spring Boot 版本
- MyBatis-Plus 版本
- PostgreSQL JDBC 驱动版本
- 连接池（HikariCP / Druid）
- 使用的数据库名
- 使用的 schema
- 是否有自定义 SQL
- 是否使用 JSONB
- 是否使用存储过程/函数
- 是否依赖特定扩展
- 是否有批处理任务

### 5.2 JDBC 驱动版本检查

检查 `pom.xml`：

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>你的版本</version>
</dependency>
```

### 建议

升级 PG16 前，建议把 PostgreSQL JDBC 驱动升级到较新的稳定版本，不要继续使用太老版本。

原因：
- 协议兼容性
- 时间类型处理
- prepared statement 行为
- PG16 新特性兼容

---

## 6. 第三步：新建 PostgreSQL 16.1 环境

推荐不要原地覆盖旧 PG10，而是新建一个 PG16 集群。

### 6.1 新环境准备原则

- 新服务器或新实例
- 安装 PostgreSQL 16.1
- 安装旧库所需扩展
- 初始化角色和基础库
- 配置网络访问、白名单、备份策略

### 6.2 先安装扩展依赖

如果旧库中有如下扩展，需要在 PG16 提前准备相应包：

- `postgis`
- `pg_trgm`
- `uuid-ossp`
- `pgcrypto`
- `unaccent`

### 6.3 不要直接复制旧配置文件

新库应：
- 以 PG16 默认配置启动
- 再逐项迁移必要参数

特别不要直接复制：
- `postgresql.conf`
- `pg_hba.conf`
- `recovery.conf`（新版本机制不同）

---

## 7. 第四步：数据迁移方案

这里推荐使用：

> **逻辑导出导入（pg_dump / pg_restore）**

而不是直接物理覆盖升级。

### 7.1 优点

- 跨大版本更稳
- 更适合 PG10 → PG16
- 可以顺手清理历史问题
- 便于测试环境演练

---

### 7.2 推荐迁移方式

建议拆分为：

1. 导出角色
2. 导出 schema
3. 导出数据
4. 恢复到 PG16
5. 修复 sequence
6. 执行 ANALYZE

---

### 7.3 导出角色（如需要）

```bash
pg_dumpall -h old_host -p 5432 -U postgres --globals-only > globals.sql
```

用于迁移：
- role
- user
- tablespace（如有）

---

### 7.4 导出单库结构与数据

推荐使用自定义格式：

```bash
pg_dump -h old_host -p 5432 -U postgres -d yourdb -Fc -f yourdb.dump
```

### 说明

`-Fc` 的好处：
- 支持并行恢复
- 可以按对象筛选恢复
- 出问题更好排查

---

### 7.5 恢复到 PG16

先创建目标库：

```sql
CREATE DATABASE yourdb;
```

然后恢复：

```bash
pg_restore -h new_host -p 5432 -U postgres -d yourdb -j 4 yourdb.dump
```

其中：
- `-j 4` 表示并行恢复，可按机器资源调整

---

## 8. 第五步：迁移后必须执行的修复项

### 8.1 修复 sequence

迁移后建议执行全库 sequence 修复脚本。

推荐脚本：

```sql
DO $$
DECLARE
    r RECORD;
    v_max bigint;
BEGIN
    FOR r IN
        SELECT
            ns.nspname AS schema_name,
            cls.relname AS table_name,
            attr.attname AS column_name,
            pg_get_serial_sequence(
                format('%I.%I', ns.nspname, cls.relname),
                attr.attname
            ) AS seq_name
        FROM pg_class cls
        JOIN pg_namespace ns ON ns.oid = cls.relnamespace
        JOIN pg_attribute attr ON attr.attrelid = cls.oid
        WHERE cls.relkind = 'r'
          AND attr.attnum > 0
          AND NOT attr.attisdropped
          AND pg_get_serial_sequence(
                format('%I.%I', ns.nspname, cls.relname),
                attr.attname
              ) IS NOT NULL
    LOOP
        EXECUTE format(
            'SELECT COALESCE(MAX(%I), 0) FROM %I.%I',
            r.column_name, r.schema_name, r.table_name
        ) INTO v_max;

        EXECUTE format(
            'SELECT setval(%L, %s, true)',
            r.seq_name,
            GREATEST(v_max, 1)
        );

        RAISE NOTICE 'sequence fixed: schema=%, table=%, column=%, seq=%, value=%',
            r.schema_name,
            r.table_name,
            r.column_name,
            r.seq_name,
            GREATEST(v_max, 1);
    END LOOP;
END
$$;
```

---

### 8.2 刷新统计信息

恢复完成后建议执行：

```sql
ANALYZE;
```

必要时执行：

```sql
VACUUM ANALYZE;
```

### 原因

PG16 的优化器需要基于当前数据重新建立统计信息，否则执行计划可能不理想。

---

## 9. 第六步：代码与依赖如何切换

这是微服务升级的关键部分。

---

### 9.1 修改 JDBC 驱动依赖

建议在所有微服务统一升级 PostgreSQL 驱动版本。

如果使用 Maven，修改父工程或统一依赖管理，例如：

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>
```

> 说明：这里示例使用较新的稳定驱动版本。实际可按你们公司制品库可用版本确定，但原则是不要继续使用过老驱动。

### 推荐做法

- 优先在父 POM 中统一管理
- 所有微服务统一升级驱动版本
- 不要让不同服务混用太多 JDBC 驱动版本

---

### 9.2 检查数据源配置

修改配置文件中的连接信息：

#### `application.yml` 示例

```yaml
spring:
  datasource:
    url: jdbc:postgresql://new-pg-host:5432/yourdb
    username: app_user
    password: your_password
    driver-class-name: org.postgresql.Driver
```

### 建议

生产切换前，先通过配置中心或环境变量让服务可切换：

- 旧 PG 地址
- 新 PG 地址

不要写死在代码里。

---

### 9.3 MyBatis-Plus 注意事项

MyBatis-Plus 本身通常不是升级阻碍，重点在于：

- 自定义 SQL
- XML SQL
- 分页插件
- JSON/JSONB 映射
- 时间类型映射

建议重点回归：

1. 分页查询
2. 批量插入
3. 批量更新
4. 主键回填
5. JSON 字段
6. 时间字段
7. 乐观锁 / 逻辑删除

---

### 9.4 代码侧需要重点验证的类型

#### 时间类型
重点检查：

- `timestamp`
- `timestamptz`
- `LocalDateTime`
- `LocalDate`
- `Date`

#### JSON/JSONB
重点检查：

- `json`
- `jsonb`
- 自定义 TypeHandler

#### 主键策略
重点检查：

- sequence 回填
- 主键生成策略
- 插入后返回 ID

---

## 10. 第七步：联调验证清单

建议每个微服务至少完成以下验证：

### 10.1 启动验证
- 服务能否正常启动
- 数据源是否能正常建立连接
- 连接池是否正常

### 10.2 基础 CRUD
- 新增
- 修改
- 删除
- 查询

### 10.3 分页与复杂 SQL
- MyBatis-Plus 分页
- XML 自定义 SQL
- 联表查询
- 聚合统计

### 10.4 事务验证
- 本地事务
- 批量事务
- 回滚是否正常

### 10.5 特殊能力验证
- JSONB
- sequence
- 扩展函数
- 触发器
- 存储过程

### 10.6 时间与时区验证
- 写入时间
- 查询时间
- 时区是否偏移

---

## 11. 第八步：切换实施方案

建议采用“停写 + 增量补齐 + 灰度切换”的方式。

### 11.1 推荐步骤

#### 第一步：全量迁移
- 将旧 PG10.3 全量迁移到 PG16.1

#### 第二步：测试验证
- 应用连接 PG16 测试环境完成验证

#### 第三步：停写旧库
- 切换窗口开始
- 停止业务写入旧库

#### 第四步：执行最终增量同步
- 同步切换窗口前最后一批增量数据

#### 第五步：修复 sequence
- 执行全库 sequence 修复脚本

#### 第六步：切换配置
- 将微服务连接串切换到 PG16

#### 第七步：灰度重启服务
- 一批批重启微服务
- 观察报错率和 SQL 执行情况

#### 第八步：观察运行
- 核心交易链路验证
- 慢 SQL 观察
- 锁等待观察

---

## 12. 回滚方案

升级一定要提前准备回滚预案。

### 12.1 回滚前提

切换后一段时间内，保留：

- 旧 PG10.3 实例不下线
- 旧数据不删除
- 旧配置保留

### 12.2 回滚方法

如果新库出现严重问题：

1. 停止新库写入
2. 将微服务连接串改回旧库
3. 重启服务
4. 恢复旧业务链路

### 12.3 回滚注意

如果切换后新库已经产生新写入，则回滚前必须评估：

- 是否需要把新库增量回灌回旧库
- 是否允许丢弃切换窗口内新写入

因此生产切换窗口一定要规划好。

---

## 13. 风险点总结

升级 PG10.3 到 PG16.1 时，最常见风险包括：

1. JDBC 驱动太老
2. 扩展未提前安装
3. sequence 未修复
4. 时间字段行为差异
5. search_path / 权限差异
6. SQL 执行计划变化
7. 只验证启动，没有验证真实业务 SQL
8. 直接原地升级导致回滚困难

---

## 14. 推荐最终实施策略

### 最推荐方案

- 新建 PostgreSQL 16.1
- 盘点旧库扩展、配置、角色、schema、函数、触发器、sequence
- 使用 `pg_dump/pg_restore` 迁移数据
- 修复 sequence
- 升级 PostgreSQL JDBC 驱动
- 逐个微服务联调验证
- 停写旧库后完成最终切换
- 保留旧库回滚窗口

---

## 15. 一句话总结

对于 Spring Boot + MyBatis-Plus 微服务系统，从 PostgreSQL 10.3 升级到 16.1，最稳妥、最可实施的方案是：

> **先完整盘点旧库的参数、扩展与对象，再新建 PG16.1 集群，通过逻辑迁移导入数据，修复 sequence，统一升级 JDBC 驱动，完成微服务联调后再灰度切换。**

这样可以最大限度降低升级风险，并保留完整回滚能力。
