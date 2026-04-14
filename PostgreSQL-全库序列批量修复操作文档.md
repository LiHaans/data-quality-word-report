# PostgreSQL 全库序列批量修复操作文档

## 1. 背景

在 PostgreSQL 数据迁移完成后，经常会出现如下问题：

- 业务表中已经存在数据
- 主键列使用了 `sequence`
- 但对应序列的当前值仍然停留在旧位置或初始值
- 新插入数据时触发：

```text
duplicate key value violates unique constraint
主键已存在
```

根本原因通常是：

> **表中的实际最大主键值，已经大于对应 sequence 当前值。**

因此需要将整个库中所有与表列绑定的 sequence，统一校正到当前表数据的最大值，避免后续 `nextval()` 生成重复主键。

---

## 2. 目标

本文档目标：

- 批量扫描整个 PostgreSQL 库
- 找出所有与表列绑定的 sequence
- 将 sequence 当前值调整到该列当前数据最大值
- 避免插入时报主键冲突

---

## 3. 适用场景

适用于如下场景：

- 数据迁移后修正 sequence
- 手工导入历史数据后修正 sequence
- 从其他数据库同步数据到 PostgreSQL 后修正 sequence
- 恢复备份后 sequence 未同步修复
- 批量处理整个库，而不是逐表手工执行

---

## 4. 处理原理

PostgreSQL 中，很多主键列会使用如下默认值：

```sql
nextval('xxx_seq'::regclass)
```

当表中已存在数据时，如果 sequence 当前值小于表中最大主键值，例如：

- 表 `user_info` 最大 `id = 1000`
- sequence 当前值仍是 `12`

那么下次插入时，`nextval()` 可能生成 `13`
，从而导致主键冲突。

正确做法是：

```sql
setval(sequence_name, max(id), true)
```

这样下一次 `nextval()` 才会返回：

```text
max(id) + 1
```

---

## 5. 推荐执行时机

建议在以下时机执行：

1. 数据迁移已经完成
2. 应用暂停写入或业务低峰期
3. 执行前完成备份
4. 执行后进行插入验证

不建议在高并发持续写入期间执行，以免过程中数据继续变化，导致校正结果不稳定。

---

## 6. 执行前检查

### 6.1 查看当前库中哪些列绑定了 sequence

可先执行以下 SQL 查看：

```sql
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
ORDER BY 1, 2, 3;
```

### 6.2 确认重点业务表的主键列

如果你担心批量脚本影响特殊表，可以先重点核查如下内容：

- 是否确实为标准 `serial/bigserial/identity`
- 是否存在手工维护的自定义 sequence
- 是否存在非主键列也绑定 sequence

---

## 7. 单表修复示例

如果只修一张表，例如：

- 表：`public.user_info`
- 主键列：`id`
- 序列：`public.user_info_id_seq`

执行：

```sql
SELECT setval(
  'public.user_info_id_seq',
  COALESCE((SELECT MAX(id) FROM public.user_info), 1),
  true
);
```

### 说明

- `MAX(id)`：取当前表中最大主键值
- `COALESCE(..., 1)`：空表时给默认值
- `true`：表示该值已使用，下一次 `nextval()` 会返回更大的值

---

## 8. 全库批量修复脚本（推荐）

以下脚本会：

1. 自动查找所有绑定 sequence 的列
2. 获取对应列的当前最大值
3. 执行 `setval()` 进行修复
4. 输出每张表对应的修复日志

### 8.1 推荐脚本

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

## 9. 脚本工作机制说明

该脚本的核心逻辑如下：

### 第一步：找出 sequence 绑定列

通过：

```sql
pg_get_serial_sequence(schema.table, column)
```

自动识别标准 sequence 绑定关系。

---

### 第二步：获取当前列最大值

执行：

```sql
SELECT COALESCE(MAX(id), 0)
```

如果为空表，则返回 `0`。

---

### 第三步：重置 sequence

执行：

```sql
setval(seq_name, max_value, true)
```

### 重点解释

`true` 的含义是：

- 当前值已经被使用过
- 下一次 `nextval()` 返回 `max_value + 1`

这正符合我们的目标。

---

## 10. 为什么脚本中使用 `GREATEST(v_max, 1)`

原因是：

- 如果表为空，`MAX(id)` 会是 `NULL`
- 脚本里经过 `COALESCE` 后变成 `0`
- 但 sequence 设置到 `0` 在某些场景不够稳妥

因此统一设置为至少 `1`：

```sql
GREATEST(v_max, 1)
```

这样即使空表也不会产生异常值。

---

## 11. 只修某个 schema 的版本

如果你不想修整个库，只想修例如 `public` schema，可使用如下脚本：

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
          AND ns.nspname = 'public'
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

## 12. 执行后如何验证

### 12.1 验证某张表最大值与 sequence 是否一致

例如：

```sql
SELECT MAX(id) FROM public.user_info;
SELECT last_value FROM public.user_info_id_seq;
```

如果：

- `MAX(id) = 1000`
- `last_value = 1000`

则下一次 `nextval()` 会返回 `1001`。

---

### 12.2 验证插入不会再报重复主键

可以测试插入一条数据（如果业务允许）：

```sql
INSERT INTO public.user_info(name) VALUES ('test');
```

若 sequence 已修正，则不会因为主键重复报错。

---

## 13. 特殊情况说明

## 13.1 非标准绑定 sequence 的列

如果某些表的主键不是通过标准 `serial/bigserial/identity` 方式创建，而是手工写的 sequence 或默认值丢失，那么：

```sql
pg_get_serial_sequence(...)
```

可能无法识别。

这种情况下，批量脚本不会处理到该列，需要手工检查：

```sql
SELECT column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'your_table'
  AND column_name = 'id';
```

如果看到：

```sql
nextval('xxx_seq'::regclass)
```

则说明该列仍在使用 sequence。

---

## 13.2 空表处理

如果表没有记录，脚本会将 sequence 至少设置到 `1`。

这样可以避免 sequence 为 `0` 或其他边界问题。

---

## 13.3 非主键列也可能绑定 sequence

脚本扫描的是“所有绑定 sequence 的列”，并不只限定主键列。

这通常是合理的，因为 sequence 校正的目标就是让自增列不冲突。

如果你只想修主键列，需要额外结合主键元数据过滤。

---

## 14. 推荐执行步骤

建议按如下顺序操作：

### 第一步：备份数据库

至少保留：

- schema 备份
- 或整库备份

---

### 第二步：暂停应用写入

避免脚本执行期间仍有新增数据。

---

### 第三步：查看 sequence 绑定关系

先执行：

```sql
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
ORDER BY 1, 2, 3;
```

---

### 第四步：执行批量修复脚本

执行本文第 8 节脚本。

---

### 第五步：抽查几张核心表

验证：

- `MAX(id)`
- `last_value`
- 新增记录

---

### 第六步：恢复业务写入

确认无误后恢复应用写入。

---

## 15. 推荐给生产环境的最终建议

对于生产环境，建议使用如下方式：

- 优先在测试环境验证脚本
- 生产执行前先备份
- 业务低峰期执行
- 执行时暂停写入
- 执行后抽查重点表
- 必要时记录脚本执行输出日志

---

## 16. 一句话总结

如果 PostgreSQL 数据迁移后出现“主键已存在”错误，最常见原因就是 **sequence 没有跟着表数据最大值一起校正**。最推荐的做法是：

> **批量扫描整个库中所有绑定 sequence 的列，并统一执行 `setval(sequence, max(id), true)` 修复。**

这样可以一次性解决大部分迁移后的自增主键冲突问题。
