# Redis 单节点内存异常与连接暴涨排查手册

> 适用场景：内网环境、单节点 Redis、Key 数量不多但内存持续增长、`maxmemory` 不断调大仍再次告警、怀疑连接泄漏/连接风暴/输出缓冲堆积等问题。

## 1. 问题现象与核心判断

你当前的现象是：

- Redis 为**单节点**
- 历史上 `maxmemory=2g` 能稳定运行
- 上周开始出现**内存溢出 / OOM / 超限**
- 调整为 `4g` 后坚持约一周再次出问题
- 继续调整为 `8g` 后，当前已使用约 `5.3g`
- Redis 中 **key 仅约一千多个**，且大多数是**小 key**
- 观察到**连接数极高（例如 900 万）**

### 1.1 初步结论

如果 **Key 少、Value 小、连接数极大、内存仍持续上涨**，那么大概率：

1. **不是数据本身膨胀导致**
2. 而是 **客户端连接异常** 导致 Redis 内存被吃掉
3. 常见根因包括：
   - 应用未使用连接池，每次请求新建连接
   - 连接泄漏，创建后未释放
   - 网络抖动或异常导致重连风暴
   - Pub/Sub / 慢客户端导致输出缓冲堆积
   - 某业务实例异常打满 Redis
   - Redis 暴露范围异常，存在非预期客户端接入

### 1.2 为什么调大 `maxmemory` 没有真正解决问题

`maxmemory` 只限制**数据淘汰相关内存**，但 Redis 的内存中还包含：

- 客户端连接对象
- 输入缓冲区
- 输出缓冲区
- 复制缓冲区
- AOF/RDB 相关缓冲
- 字典/元数据开销
- 内存碎片

如果问题根因是**连接侧**，那把 `maxmemory` 从 `2g -> 4g -> 8g`，只是把“出问题的时间”往后推，并没有解决连接异常本身。

---

## 2. 排查总路线

建议按以下顺序排查：

1. **确认 Redis 内存到底花在哪**
2. **确认连接数是否真实、由谁产生**
3. **确认是否存在连接泄漏/短连接风暴/慢客户端**
4. **先止血：限制连接、清理空闲、拦截异常来源**
5. **回到业务代码修复根因**
6. **建立长期监控和容量基线**

---

## 3. 必查命令总表

以下命令建议按顺序执行，并保存原始输出：

```bash
redis-cli INFO memory
redis-cli INFO clients
redis-cli INFO stats
redis-cli INFO persistence
redis-cli MEMORY STATS
redis-cli CLIENT LIST | head -n 50
redis-cli CONFIG GET maxmemory
redis-cli CONFIG GET maxmemory-policy
redis-cli CONFIG GET maxclients
redis-cli CONFIG GET timeout
redis-cli CONFIG GET tcp-keepalive
redis-cli DBSIZE
```

系统层面补充：

```bash
ss -ant | grep ':6379' | awk '{print $1}' | sort | uniq -c
ss -ant | grep ':6379' | awk '{print $5}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30
redis-cli CLIENT LIST | awk -F'addr=| ' '{print $2}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30
```

如果你机器上有 `lsof`，可以再看：

```bash
lsof -i :6379 | head -n 50
```

---

## 4. 每个命令、字段、含义与判断方法

# 4.1 `redis-cli INFO memory`

用途：查看 Redis 内存组成。

常见关键字段：

## 4.1.1 `used_memory`
- 含义：Redis 从分配器申请到、当前正在使用的总内存（字节）
- 判断：这是最直观的 Redis 内存使用量

## 4.1.2 `used_memory_human`
- 含义：`used_memory` 的人类可读格式
- 判断：方便快速看是 MB 还是 GB

## 4.1.3 `used_memory_rss`
- 含义：操作系统视角下，Redis 进程实际驻留内存（Resident Set Size）
- 判断：
  - 如果远高于 `used_memory`，说明可能有**内存碎片**
  - 但碎片一般不是“连接数爆炸”的首因

## 4.1.4 `used_memory_dataset`
- 含义：真正用于存放数据集（key/value）的内存估算
- 判断：
  - 如果这个值不高，但总内存很高，说明**不是数据本身大**

## 4.1.5 `used_memory_overhead`
- 含义：数据集之外的 Redis 开销内存
- 包括：
  - key 字典元数据
  - 客户端连接对象
  - 缓冲区
  - Lua / functions / 其他结构开销
- 判断：
  - 如果这个值很高，通常说明问题在**连接、元数据、缓冲区**等非数据部分

## 4.1.6 `maxmemory`
- 含义：Redis 设置的最大内存上限
- 判断：
  - 仅看这个值没有意义，要和 `used_memory` 一起看

## 4.1.7 `maxmemory_policy`
- 含义：达到 `maxmemory` 后的淘汰策略
- 常见值：
  - `noeviction`：不淘汰，写入报错
  - `allkeys-lru`：所有 key 中按 LRU 淘汰
  - `volatile-lru`：仅对设置过过期时间的 key 进行 LRU 淘汰
  - `allkeys-random` / `volatile-random`
  - `volatile-ttl`
- 判断：
  - 如果是 `noeviction`，达到阈值时会直接报错
  - 但注意：连接开销不一定能靠淘汰策略解决

## 4.1.8 `mem_fragmentation_ratio`
- 含义：内存碎片率，通常是 `used_memory_rss / used_memory`
- 判断：
  - 接近 1：比较正常
  - 1.2~1.5：可接受，视场景而定
  - 很高（如 >1.5 甚至更高）：可能有碎片问题
  - 但如果连接数异常大，碎片不是主因

### 4.1.9 典型判断规则

如果你看到：

- `used_memory_dataset` 很小
- `used_memory_overhead` 很大
- `connected_clients` 很大

那么基本结论就是：

> 内存主要不是花在 key/value 数据上，而是花在客户端连接、缓冲区和 Redis 自身开销上。

---

# 4.2 `redis-cli INFO clients`

用途：查看 Redis 客户端连接状态。

关键字段：

## 4.2.1 `connected_clients`
- 含义：当前已连接客户端数
- 判断：
  - 正常业务一般是有限的、稳定的
  - 如果暴涨到几十万、几百万，明显异常
  - 你提到 900 万，这已经是重点问题

## 4.2.2 `blocked_clients`
- 含义：被阻塞命令占住的客户端数
- 典型命令：
  - `BLPOP`
  - `BRPOP`
  - `XREAD BLOCK`
- 判断：
  - 如果很多，可能有消费者模型或阻塞使用方式异常

## 4.2.3 `client_recent_max_input_buffer`
- 含义：近期最大输入缓冲区大小
- 判断：
  - 如果很高，说明某些客户端输入流量或请求包很大

## 4.2.4 `client_recent_max_output_buffer`
- 含义：近期最大输出缓冲区大小
- 判断：
  - 如果很高，可能存在慢客户端、订阅客户端积压、输出缓冲膨胀

## 4.2.5 `tracking_clients`
- 含义：启用客户端缓存跟踪（client-side caching）的客户端数量
- 判断：
  - 如果没有用到，一般是 0

---

# 4.3 `redis-cli INFO stats`

用途：看命令处理、连接、异常等统计。

关键字段：

## 4.3.1 `total_connections_received`
- 含义：Redis 启动以来累计接收到的连接数
- 判断：
  - 如果这个数增长极快，说明存在短连接风暴或频繁重连

## 4.3.2 `total_commands_processed`
- 含义：累计处理命令数
- 判断：
  - 如果连接数极大但命令数不成比例，说明可能大量连接只是空连、泄漏、保活

## 4.3.3 `instantaneous_ops_per_sec`
- 含义：当前每秒处理命令数
- 判断：
  - 如果 ops 不高，但连接极高，进一步说明连接不正常

## 4.3.4 `rejected_connections`
- 含义：因达到上限等原因被拒绝的连接数
- 判断：
  - 如果开始出现，说明连接数量已超系统承受能力

## 4.3.5 `evicted_keys`
- 含义：被淘汰的 key 数量
- 判断：
  - 如果很多，说明曾触发淘汰策略
  - 但不能说明问题一定在数据层

## 4.3.6 `expired_keys`
- 含义：过期被删除的 key 数量
- 判断：
  - 用于理解是否大量依赖 TTL

---

# 4.4 `redis-cli INFO persistence`

用途：确认持久化是否对内存/系统资源造成附加压力。

关键字段：

## 4.4.1 `aof_enabled`
- 含义：是否开启 AOF
- 判断：
  - AOF 不一定导致当前问题，但可能增加 I/O 和缓冲开销

## 4.4.2 `rdb_bgsave_in_progress`
- 含义：后台 RDB 是否在执行
- 判断：
  - 执行期间内存和系统压力会变化

## 4.4.3 `aof_rewrite_in_progress`
- 含义：AOF 重写是否在执行
- 判断：
  - 重写期会增加资源消耗

### 说明

你当前现象更像连接问题，不像持久化是主因，但仍建议保留该输出用于排除干扰项。

---

# 4.5 `redis-cli MEMORY STATS`

用途：更细粒度看内存分布。

常见项目及意义（不同 Redis 版本字段可能略有不同）：

## 4.5.1 `peak.allocated`
- 含义：历史峰值已分配内存

## 4.5.2 `total.allocated`
- 含义：当前总分配内存

## 4.5.3 `startup.allocated`
- 含义：Redis 启动基础开销

## 4.5.4 `clients.normal`
- 含义：普通客户端占用内存
- 判断：
  - 如果它很大，说明客户端连接是主要内存来源之一

## 4.5.5 `clients.slaves` / `clients.replica`
- 含义：复制客户端占用内存
- 判断：
  - 单节点无从库时通常较低

## 4.5.6 `overhead.total`
- 含义：总开销内存
- 判断：
  - 大说明 Redis 自身或连接侧开销多

## 4.5.7 `dataset.bytes`
- 含义：数据集字节数
- 判断：
  - 如果这个小而 `clients.normal` 大，结论就很清晰了

---

# 4.6 `redis-cli CLIENT LIST`

用途：查看每个客户端连接的明细。

每一行是一个客户端，类似：

```text
id=123 addr=10.0.0.8:53211 laddr=10.0.0.10:6379 fd=12 name= age=3600 idle=3590 flags=N db=0 sub=0 psub=0 ssub=0 multi=-1 qbuf=0 qbuf-free=20474 argv-mem=0 multi-mem=0 rbs=1024 rbp=0 obl=0 oll=0 omem=0 tot-mem=22328 events=r cmd=get user=default redir=-1 resp=2
```

关键字段解释如下：

## 4.6.1 `id`
- 含义：客户端连接 ID

## 4.6.2 `addr`
- 含义：客户端源地址与端口，格式为 `IP:port`
- 判断：
  - 用它反查是哪个应用服务器

## 4.6.3 `laddr`
- 含义：Redis 本地监听地址

## 4.6.4 `fd`
- 含义：文件描述符编号

## 4.6.5 `name`
- 含义：客户端名称（如果业务代码显式设置了 `CLIENT SETNAME`）
- 判断：
  - 很有用，能直接定位来源服务

## 4.6.6 `age`
- 含义：连接存活时长（秒）
- 判断：
  - 很长说明长连接常驻
  - 很短但数量大，说明可能是频繁短连/重连风暴

## 4.6.7 `idle`
- 含义：空闲时长（秒）
- 判断：
  - 如果很多连接 `idle` 很久，可能是连接泄漏或池子过大

## 4.6.8 `flags`
- 含义：连接标识
- 常见值：
  - `N`：普通连接
  - `P`：Pub/Sub 客户端
  - `b`：阻塞中
  - 其他值依版本不同会变化
- 判断：
  - 如果很多 `P` 或 `b`，要检查订阅或阻塞消费模型

## 4.6.9 `db`
- 含义：当前数据库编号

## 4.6.10 `sub` / `psub` / `ssub`
- 含义：订阅频道数 / 模式订阅数 / 分片订阅数
- 判断：
  - 非 0 说明该客户端参与了订阅模型

## 4.6.11 `qbuf`
- 含义：查询缓冲区已使用大小
- 判断：
  - 大说明客户端发送请求堆积

## 4.6.12 `qbuf-free`
- 含义：查询缓冲区剩余容量

## 4.6.13 `argv-mem`
- 含义：当前命令参数占用内存

## 4.6.14 `obl`
- 含义：固定输出缓冲区长度

## 4.6.15 `oll`
- 含义：输出链表长度
- 判断：
  - 大说明返回数据发送不出去，客户端在“慢读”

## 4.6.16 `omem`
- 含义：输出缓冲区占用内存
- 判断：
  - 很关键
  - 如果很多连接 `omem` 高，说明慢客户端/订阅客户端正在吃内存

## 4.6.17 `tot-mem`
- 含义：该连接总内存占用
- 判断：
  - 非常适合判断单连接成本

## 4.6.18 `cmd`
- 含义：该连接最近执行的命令
- 判断：
  - `get/set/hgetall/...` 可看业务类型
  - `subscribe/psubscribe` 表示订阅
  - `NULL` 或空闲很久可疑

## 4.6.19 `user`
- 含义：ACL 用户

### 用 `CLIENT LIST` 怎么判断问题

#### 情况 A：大量连接 `idle` 很久
说明：
- 应用可能建立连接后不释放
- 或连接池开太大且空闲回收无效

#### 情况 B：大量连接 `age` 很短
说明：
- 存在频繁短连、重连风暴

#### 情况 C：大量连接 `omem` 很高
说明：
- 输出缓冲堆积，慢客户端/订阅模型可能有问题

#### 情况 D：大量连接来自同一个 `addr`
说明：
- 很可能某个应用实例有 bug

---

# 4.7 `redis-cli CONFIG GET ...`

## 4.7.1 `CONFIG GET maxmemory`
- 含义：当前最大内存限制
- 用途：确认是否真的是配置值生效

## 4.7.2 `CONFIG GET maxmemory-policy`
- 含义：淘汰策略
- 用途：判断达到阈值时写入行为

## 4.7.3 `CONFIG GET maxclients`
- 含义：最大客户端连接数限制
- 用途：防止连接无限增长
- 判断：
  - 如果特别大或未合理控制，连接异常时容易被拖死

## 4.7.4 `CONFIG GET timeout`
- 含义：客户端空闲超时秒数
- 用途：自动清理长期空闲连接
- 判断：
  - 如果是 `0`，表示永不因空闲而断开

## 4.7.5 `CONFIG GET tcp-keepalive`
- 含义：TCP keepalive 间隔
- 用途：帮助系统探测死连接

---

# 4.8 `redis-cli DBSIZE`

- 含义：当前数据库中 key 的数量
- 判断：
  - 如果 key 数很少，但内存很高，说明方向不能只盯 key

---

# 4.9 系统命令：`ss -ant | grep ':6379'`

用途：从操作系统角度看 socket 状态。

## 常见 TCP 状态含义

### `ESTAB`
- 含义：已建立连接
- 判断：
  - 很多说明确实存在大量活跃连接

### `TIME-WAIT`
- 含义：连接关闭后的等待状态
- 判断：
  - 很多通常意味着客户端频繁创建并关闭短连接

### `CLOSE-WAIT`
- 含义：对端已关闭，本地还未完全关闭
- 判断：
  - 很多通常说明应用层关闭处理异常

### `SYN-RECV`
- 含义：握手进行中
- 判断：
  - 如果很多，可能有连接风暴或异常探测

### 通过 IP 聚合的意义

```bash
ss -ant | grep ':6379' | awk '{print $5}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30
```

可以快速回答：

- 连接主要来自哪几台机器
- 是否存在某台实例异常
- 是否存在陌生来源

---

## 5. 标准排查步骤（建议照做）

# 步骤 1：先确认问题是不是“数据膨胀”

执行：

```bash
redis-cli DBSIZE
redis-cli INFO memory
redis-cli MEMORY STATS
```

### 判断方法

如果你看到：
- `DBSIZE` 很小（例如只有几百/几千）
- `dataset.bytes` / `used_memory_dataset` 不高
- `used_memory_overhead` 很高

则可以初步排除“大 key / 海量 key”是主因。

---

# 步骤 2：确认连接数和连接趋势

执行：

```bash
redis-cli INFO clients
redis-cli INFO stats
```

### 看什么

- `connected_clients`
- `total_connections_received`
- `rejected_connections`

### 判断方法

- `connected_clients` 很高：当前连接压力大
- `total_connections_received` 增长很快：短连/重连问题明显
- `rejected_connections` > 0：系统已经开始扛不住

---

# 步骤 3：找出连接来源 IP

执行：

```bash
redis-cli CLIENT LIST | awk -F'addr=| ' '{print $2}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30
```

### 目标

回答这几个问题：

1. 是一台机器导致的，还是多台机器共同导致的？
2. 有没有来源 IP 明显异常？
3. 有没有不认识的机器接入 Redis？

### 结果解释

- **某个 IP 占绝大多数**：优先排查那台应用服务器
- **多个 IP 都很高**：可能是公共 SDK 用法错误，或网关/服务模板有统一 bug
- **出现陌生 IP**：需要立刻核查网络访问策略

---

# 步骤 4：抽样分析客户端细节

执行：

```bash
redis-cli CLIENT LIST | head -n 50
```

如果输出太多，可以按 IP 过滤，例如：

```bash
redis-cli CLIENT LIST | grep 'addr=10.0.0.8:' | head -n 50
```

### 重点看这些字段

- `age`
- `idle`
- `cmd`
- `flags`
- `omem`
- `tot-mem`

### 怎么判断

#### 模式 1：长时间空闲连接堆积
- `age` 大
- `idle` 也大
- 连接很多

结论：
- 很像连接泄漏，或连接池配置失控

#### 模式 2：短生命周期连接很多
- `age` 很小
- 总连接和累计连接增长很快

结论：
- 很像短连接风暴 / 失败重连 / 没用连接池

#### 模式 3：`omem` 很大
结论：
- 输出缓冲问题
- 要检查慢客户端、订阅客户端、消费者处理慢

---

# 步骤 5：看操作系统层连接状态

执行：

```bash
ss -ant | grep ':6379' | awk '{print $1}' | sort | uniq -c
ss -ant | grep ':6379' | awk '{print $5}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30
```

### 判断方法

#### `ESTAB` 巨多
- 真实活跃连接过多

#### `TIME-WAIT` 巨多
- 客户端频繁短连短断

#### `CLOSE-WAIT` 巨多
- 某侧连接关闭逻辑异常

---

## 6. 常见根因与对应证据

# 6.1 未使用连接池 / 每次请求都新建连接

### 典型表现
- `total_connections_received` 持续快速增加
- `TIME-WAIT` 很多
- 每秒 ops 不高，但连接很多

### 业务代码常见错误
- Java 每次 new Jedis / new Lettuce 连接
- Python 每次请求 new Redis 客户端对象
- Node.js 每个请求都 `new Redis()`
- Go 每次 `Dial` 不复用

### 解决
- 强制改为连接池/单例复用
- 限制池大小
- 增加连接回收与空闲超时

---

# 6.2 连接泄漏

### 典型表现
- `connected_clients` 持续上涨
- `age` 很长
- `idle` 也很长
- 重启应用后连接明显下降

### 解决
- 检查连接获取后是否释放
- 检查异常流程是否漏掉 close / returnObject
- 对连接池增加借出/归还监控

---

# 6.3 重连风暴

### 典型表现
- `total_connections_received` 快速增加
- `age` 很短
- 应用日志中有大量 reconnect/timeout/reset

### 解决
- 增加指数退避重连
- 排查网络抖动
- 限制重试速率
- 避免多实例同时无脑重连

---

# 6.4 慢客户端 / 输出缓冲区堆积

### 典型表现
- `CLIENT LIST` 中 `omem` 高
- `oll` 高
- `client_recent_max_output_buffer` 高

### 常见原因
- Pub/Sub 消费者处理太慢
- 一次性返回大结果但客户端读取慢
- 网络带宽/下游消费堵塞

### 解决
- 排查订阅消费者
- 限制单次返回体量
- 分页/拆批
- 必要时断开异常慢连接

---

# 6.5 Pub/Sub 或阻塞型消费模型异常

### 典型表现
- `flags=P` 或 `flags=b` 较多
- `sub`/`psub` 非 0
- `blocked_clients` 偏高

### 解决
- 检查消费者是否堆积
- 检查是否存在无人消费的订阅连接
- 检查阻塞命令是否设计合理

---

# 6.6 网络暴露范围不合理 / 非预期来源接入

### 典型表现
- 来源 IP 中出现陌生机器
- 连接来源不符合业务拓扑

### 解决
- 限制 Redis 仅允许业务网段访问
- 增加 ACL/密码
- 核查防火墙与白名单

---

## 7. 立即止血方案

> 目标：在未彻底修复业务前，先让 Redis 不要继续失控。

# 7.1 限制最大客户端数

查看当前配置：

```bash
redis-cli CONFIG GET maxclients
```

临时设置：

```bash
redis-cli CONFIG SET maxclients 20000
```

### 含义
- 超过此值的新连接会被拒绝
- 防止 Redis 被无限连死

### 注意
- 上限不能瞎设太低，要结合正常业务峰值
- 但绝对不应该默认容忍“百万/千万连接”

---

# 7.2 设置空闲连接超时

查看当前值：

```bash
redis-cli CONFIG GET timeout
```

如果是 `0`，表示空闲连接永不自动关闭。

可临时设置：

```bash
redis-cli CONFIG SET timeout 300
```

### 含义
- 空闲 300 秒自动断开

### 适用场景
- 大量闲置连接堆积

### 注意
- 如果业务依赖超长空闲长连接，需要先评估

---

# 7.3 检查并处理异常来源 IP

如果某个来源 IP 明显异常：

- 先下线该应用实例
- 或在网络层限制该 IP 对 Redis 的访问
- 再回头修业务逻辑

不要只调大 Redis 内存继续硬扛。

---

# 7.4 处理慢客户端

如果 `omem` 很高：

- 检查是否订阅消费者不消费
- 检查是否有命令返回巨大结果
- 必要时断开异常客户端

可人工 `CLIENT KILL`（谨慎操作）：

```bash
redis-cli CLIENT KILL <ip:port>
```

> 注意：生产环境执行前先确认影响范围。

---

## 8. 业务侧最终修复建议

# 8.1 强制统一连接方式

所有业务都应满足：

- 使用连接池或单例客户端
- 禁止每次请求新建 Redis 连接
- 设置合理池大小
- 设置空闲回收
- 设置连接借用超时

---

# 8.2 合理设置连接池大小

错误思路：
- “连接越多越保险”

正确思路：
- 连接数应与并发、命令耗时、业务实例数相匹配
- 一般每个实例几十到几百连接就该重新评估了
- 不应无上限堆连接

---

# 8.3 增加重连退避

当 Redis 不稳定或网络抖动时：

- 不要立即无限重试
- 使用指数退避
- 设置最大重试间隔
- 避免所有实例同时重连

---

# 8.4 让业务打上客户端名称

建议业务启动后执行：

```text
CLIENT SETNAME service-a
```

或者在客户端库中配置 clientName。

这样 `CLIENT LIST` 中的 `name=` 会直接显示业务名，定位问题会容易很多。

---

# 8.5 补监控

建议至少监控：

- `used_memory`
- `used_memory_dataset`
- `used_memory_overhead`
- `used_memory_rss`
- `connected_clients`
- `blocked_clients`
- `total_connections_received`
- `rejected_connections`
- `instantaneous_ops_per_sec`
- 慢查询数量
- 来源 IP 连接分布（如果能采集）

---

## 9. 一次完整排查示例

假设你执行后发现：

- `DBSIZE = 1200`
- `used_memory = 5.3GB`
- `used_memory_dataset = 300MB`
- `used_memory_overhead = 4.5GB`
- `connected_clients = 9000000`
- `clients.normal` 很高
- 大量连接来自 `10.10.12.31`
- `CLIENT LIST` 中该来源连接普遍 `idle` 很久

那么结论基本可以写成：

> Redis 内存异常不是由 key/value 数据膨胀引起，而是来自 `10.10.12.31` 的客户端连接异常堆积。连接长期空闲未回收，疑似业务代码存在连接泄漏或连接池配置失效。短期需通过 `maxclients`、`timeout`、实例隔离进行止血；长期需修复该业务的 Redis 连接管理方式。

---

## 10. 推荐现场执行顺序

按这个顺序最稳：

### 第 1 步：保留证据

```bash
redis-cli INFO memory > redis_info_memory.txt
redis-cli INFO clients > redis_info_clients.txt
redis-cli INFO stats > redis_info_stats.txt
redis-cli MEMORY STATS > redis_memory_stats.txt
redis-cli CLIENT LIST > redis_client_list.txt
```

### 第 2 步：看是不是数据问题

```bash
redis-cli DBSIZE
redis-cli INFO memory
redis-cli MEMORY STATS
```

### 第 3 步：看是不是连接问题

```bash
redis-cli INFO clients
redis-cli INFO stats
```

### 第 4 步：找来源 IP

```bash
redis-cli CLIENT LIST | awk -F'addr=| ' '{print $2}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30
```

### 第 5 步：系统层验证

```bash
ss -ant | grep ':6379' | awk '{print $1}' | sort | uniq -c
ss -ant | grep ':6379' | awk '{print $5}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30
```

### 第 6 步：临时止血

```bash
redis-cli CONFIG SET maxclients 20000
redis-cli CONFIG SET timeout 300
```

### 第 7 步：定位业务

- 根据来源 IP 找应用机器
- 根据应用机器找服务进程
- 检查 Redis 客户端初始化方式
- 检查是否使用连接池
- 检查异常流程是否释放连接
- 检查重试逻辑是否无上限

---

## 11. 最终整改建议（落地版）

建议形成固定整改项：

1. **Redis 接入规范化**
   - 强制连接池
   - 禁止短连接直连模式

2. **统一客户端命名**
   - 每个服务设置 `clientName`

3. **限制连接数**
   - Redis 设置 `maxclients`
   - 应用连接池设置最大连接数

4. **限制空闲连接**
   - Redis `timeout`
   - 连接池 idle 回收

5. **优化重连策略**
   - 指数退避
   - 限速
   - 熔断

6. **补监控与告警**
   - 连接数告警
   - 内存开销结构告警
   - 来源 IP 异常分布告警

7. **网络收口**
   - Redis 只允许业务网段访问
   - 禁止非授权机器接入

---

## 12. 对你当前案例的最可能结论

基于你提供的信息，优先级最高的怀疑对象是：

### 高概率
- 某个业务服务存在**连接泄漏**
- 或某个业务没有使用连接池，导致**频繁建连**
- 或某个实例/网关在发生**重连风暴**

### 中概率
- 存在 Pub/Sub / 慢消费者，导致输出缓冲堆积

### 低概率
- 小 key 本身撑爆 Redis
- 单纯内存碎片导致问题

---

## 13. 建议的输出模板（便于你现场记录）

可按下面模板记录：

```markdown
# Redis 异常排查记录

## 基本信息
- 时间：
- Redis 版本：
- 部署模式：单节点
- maxmemory：
- maxmemory-policy：

## 现象
- used_memory：
- used_memory_dataset：
- used_memory_overhead：
- connected_clients：
- total_connections_received：
- DBSIZE：

## 连接来源 TOP
- IP1：
- IP2：
- IP3：

## CLIENT LIST 抽样结论
- age：
- idle：
- cmd：
- omem：
- flags：

## 系统层结论
- ESTAB：
- TIME-WAIT：
- CLOSE-WAIT：

## 初步判断
- 

## 临时止血动作
- 

## 根因修复计划
- 
```

---

## 14. 结语

这个问题的关键，不是继续把 `maxmemory` 往上抬，而是先把下面这件事查清：

> **Redis 的内存到底是被数据吃掉，还是被客户端连接和缓冲区吃掉。**

结合你当前信息，后者概率显著更高。

如果后续你把现场命令输出补齐，这份手册就可以继续往下收敛成“哪一台机器、哪一个服务、哪一段代码”导致的问题。

---

## 15. 内网一次性导入版补充

本章节用于增强离线可用性，目标是：

- 让手册在**没有外网、不能临时搜索资料**的环境里也足够用
- 让排障人员可以按“现象 -> 命令 -> 判断 -> 动作”直接执行
- 尽量减少二次导入文档的需要

---

## 16. 五分钟速查版（适合故障现场先看）

如果你正在故障现场，先不要全篇慢慢读，先执行下面这些命令：

```bash
redis-cli INFO memory
redis-cli INFO clients
redis-cli INFO stats
redis-cli MEMORY STATS
redis-cli DBSIZE
redis-cli CLIENT LIST | head -n 30
redis-cli CLIENT LIST | awk -F'addr=| ' '{print $2}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 20
ss -ant | grep ':6379' | awk '{print $1}' | sort | uniq -c
ss -ant | grep ':6379' | awk '{print $5}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 20
```

### 五分钟判断口诀

#### 情况 1：`DBSIZE` 小，`used_memory_overhead` 大
结论：
- 先别怀疑数据本身
- 优先查连接、缓冲区、客户端行为

#### 情况 2：`connected_clients` 特别大
结论：
- 先查来源 IP
- 再查是否连接泄漏 / 未用连接池 / 重连风暴

#### 情况 3：`client_recent_max_output_buffer` 大，`CLIENT LIST` 里 `omem` 大
结论：
- 优先查慢客户端、订阅、输出缓冲积压

#### 情况 4：`TIME-WAIT` 很多
结论：
- 优先查短连接频繁创建

#### 情况 5：`ESTAB` 特别多，且大量空闲
结论：
- 优先查连接泄漏或连接池回收失效

---

## 17. 应急止血命令清单

> 注意：以下命令适合故障止血，但执行前要确认业务影响。

### 17.1 限制最大连接数

```bash
redis-cli CONFIG SET maxclients 20000
```

用途：
- 避免 Redis 被异常连接持续打满

风险：
- 超出上限的新连接会被拒绝，部分业务可能报错

---

### 17.2 自动清理空闲连接

```bash
redis-cli CONFIG SET timeout 300
```

用途：
- 自动关闭空闲超过 300 秒的连接

风险：
- 如果你的业务依赖长期空闲长连接，可能触发重连

---

### 17.3 查看当前最大连接和空闲超时配置

```bash
redis-cli CONFIG GET maxclients
redis-cli CONFIG GET timeout
redis-cli CONFIG GET tcp-keepalive
```

---

### 17.4 按来源杀异常客户端（谨慎）

先查样本：

```bash
redis-cli CLIENT LIST | grep 'addr=10.0.0.8:' | head -n 20
```

单个连接踢除：

```bash
redis-cli CLIENT KILL 10.0.0.8:53211
```

用途：
- 验证某个来源是否为异常制造者

风险：
- 会中断对应业务连接

---

### 17.5 网络层先切异常来源

如果明确某个 IP 异常，优先：
- 下线对应应用实例
- 或通过防火墙/安全组临时隔离

这是比持续加大 `maxmemory` 更有效的止血动作。

---

## 18. 按现象定位问题

### 18.1 现象：Key 很少，但 Redis 内存很高
优先怀疑：
- 连接开销
- 输出缓冲区
- Redis 元数据/overhead
- 内存碎片（次级怀疑）

先执行：

```bash
redis-cli INFO memory
redis-cli MEMORY STATS
redis-cli INFO clients
```

---

### 18.2 现象：连接数不断上涨，从不回落
优先怀疑：
- 连接泄漏
- 连接池没有回收
- 某服务实例没有释放 Redis 连接

先执行：

```bash
redis-cli INFO clients
redis-cli CLIENT LIST | head -n 50
redis-cli CLIENT LIST | awk -F'addr=| ' '{print $2}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 20
```

重点看：
- `age`
- `idle`
- `addr`

---

### 18.3 现象：连接数波动剧烈，同时 `TIME-WAIT` 很多
优先怀疑：
- 短连接模式
- 重连风暴
- 网络抖动后客户端无脑重试

先执行：

```bash
redis-cli INFO stats
ss -ant | grep ':6379' | awk '{print $1}' | sort | uniq -c
```

重点看：
- `total_connections_received`
- `TIME-WAIT`

---

### 18.4 现象：Redis 内存高，但 ops 并不高
优先怀疑：
- 连接长期挂着不干活
- 空闲连接太多
- 订阅连接堆积

先执行：

```bash
redis-cli INFO stats
redis-cli INFO clients
redis-cli CLIENT LIST | head -n 50
```

重点看：
- `instantaneous_ops_per_sec`
- `connected_clients`
- `idle`

---

### 18.5 现象：订阅/消息场景下内存涨得快
优先怀疑：
- 消费者慢
- Pub/Sub 输出缓冲堆积

先执行：

```bash
redis-cli INFO clients
redis-cli CLIENT LIST | grep 'flags=P\|sub=[1-9]\|psub=[1-9]' | head -n 50
```

重点看：
- `flags=P`
- `sub`
- `psub`
- `omem`
- `oll`

---

## 19. 配置项建议值（参考，不是绝对值）

> 以下是单节点内网 Redis 的一般建议，具体仍要结合你的业务峰值和连接模型。

### 19.1 `maxclients`
建议：
- 不要无限大
- 通常应按“实例数 × 每实例连接池上限 × 冗余系数”估算

例如：
- 20 个应用实例
- 每实例最大 200 连接
- 冗余系数 2

则上限可先估：
- `20 × 200 × 2 = 8000`

再结合现场调到 10000 或 12000，通常比“放任几百万连接”靠谱得多。

---

### 19.2 `timeout`
建议：
- 若业务允许，可设置 60~300 秒
- 如果必须使用长期空闲连接，要确认客户端保活和池回收策略

---

### 19.3 `tcp-keepalive`
建议：
- 不要关闭
- 常见可用值：60 或 300

作用：
- 帮助识别死连接、半开连接

---

### 19.4 `maxmemory-policy`
常见建议：
- 如果业务不能接受写失败，可评估 `allkeys-lru` / `volatile-lru`
- 如果你的 Redis 更像缓存而非强一致存储，不建议长期 `noeviction`

但注意：
- 连接问题**不能靠淘汰策略解决**

---

## 20. 业务代码排查清单（按语言）

### 20.1 Java（Jedis / Lettuce / Spring Data Redis）

重点检查：
- 是否每次请求都创建新连接
- 是否启用连接池
- 连接池 `maxTotal` / `maxIdle` / `minIdle` 是否合理
- 异常流程是否归还连接
- 是否有定时任务/批处理重复初始化 Redis 客户端

常见错误信号：
- `new Jedis(host, port)` 在业务方法里频繁出现
- 每次请求 `new RedisClient()` / `connect()`
- 没有统一 Bean / 单例注入

建议：
- Spring 项目统一由容器管理 Redis 客户端
- 检查连接池借用归还监控
- 给连接打 `clientName`

---

### 20.2 Go（go-redis / redigo）

重点检查：
- 是否反复 `NewClient()`
- 是否每次操作都 `Dial()`
- 是否启用了 `PoolSize`、`MinIdleConns`
- 是否在 goroutine 中无限创建客户端

常见错误信号：
- 工具函数里每次都初始化 client
- worker 数量和连接池大小完全失控

建议：
- Redis client 做成进程级单例
- 限制池大小
- 加入连接数和重试次数监控

---

### 20.3 Python（redis-py）

重点检查：
- 是否每次请求都 `redis.Redis(...)`
- 是否复用了 `ConnectionPool`
- 是否在脚本/任务循环中不断创建 client

常见错误信号：
- 业务函数内直接 new client
- celery / 定时任务每轮循环重新初始化客户端

建议：
- 使用全局连接池
- 检查任务重试与连接释放

---

### 20.4 Node.js（ioredis / node-redis）

重点检查：
- 是否每个请求都 `new Redis()`
- 是否 Web 请求生命周期中重复初始化 client
- 是否在消息消费者中反复建连
- 是否断线后采用无上限立即重连

常见错误信号：
- controller / handler 内部 `new Redis()`
- 一个模块被多次 import 后重复初始化客户端

建议：
- Redis 客户端做成模块级单例
- 重连使用退避策略
- 配置 client name

---

## 21. 现场采集模板（适合离线保存）

把下面内容复制成现场记录文件，排障时直接填：

```markdown
# Redis 现场排障记录

## 一、基础信息
- 时间：
- 主机：
- Redis 版本：
- 部署模式：单节点
- 监听端口：6379
- maxmemory：
- maxmemory-policy：
- maxclients：
- timeout：
- tcp-keepalive：

## 二、异常现象
- 现象开始时间：
- 当前 used_memory：
- 当前 used_memory_rss：
- 当前 used_memory_dataset：
- 当前 used_memory_overhead：
- 当前 connected_clients：
- 当前 blocked_clients：
- 当前 DBSIZE：
- 当前 ops/s：

## 三、连接来源 TOP
- 1.
- 2.
- 3.
- 4.
- 5.

## 四、CLIENT LIST 抽样
- 来源 IP：
- age：
- idle：
- cmd：
- flags：
- omem：
- tot-mem：

## 五、系统连接状态
- ESTAB：
- TIME-WAIT：
- CLOSE-WAIT：
- SYN-RECV：

## 六、初步判断
- 

## 七、已执行止血动作
- 

## 八、怀疑的业务服务
- 

## 九、后续整改项
- 
```

---

## 22. 常见误判

### 22.1 误判：Key 少就说明 Redis 不可能占很多内存
错误原因：
- Redis 内存不只有数据，还有连接、缓冲区、元数据、碎片

正确理解：
- Key 少只能说明“未必是数据量膨胀”，不能排除连接问题

---

### 22.2 误判：调大 `maxmemory` 就是解决了
错误原因：
- 只是延后了故障触发时间

正确理解：
- 如果根因是连接问题，不修业务，内存还会继续涨

---

### 22.3 误判：`connected_clients` 高就是 Redis 自身 bug
错误原因：
- 大多数情况下是上游接入方式不合理

正确理解：
- Redis 更像“受害者”，真正问题通常在客户端行为

---

### 22.4 误判：只有大 key 才会导致内存问题
错误原因：
- 慢客户端、连接泄漏、缓冲堆积都能导致大内存

---

## 23. 建议增加的长期机制

### 23.1 连接审计
建议让每个服务：
- 配置 `clientName`
- 在监控里上报实例名
- 记录连接池大小、空闲数、借出数

### 23.2 周期巡检
建议每周至少巡检：
- `connected_clients`
- `used_memory_overhead`
- `clients.normal`
- 来源 IP TOP
- `total_connections_received` 增长速度

### 23.3 基线建立
建议记录正常时：
- 平均连接数
- 峰值连接数
- 正常 ops/s
- 正常 dataset/overhead 比例

后续一旦偏离基线，就能更快发现异常。

---

## 24. 建议的离线附件清单

如果你准备一次性导入内网环境，建议把下面这些一起带进去：

1. 本手册 Markdown
2. Redis 常用巡检脚本（shell）
3. 一份业务语言对应的 Redis 连接池规范
4. 现场记录模板
5. 你的 Redis 当前配置备份

如果后续需要，我还可以继续补：
- `redis_check.sh` 巡检脚本
- `redis_emergency_commands.md` 应急命令卡片
- Java / Go / Python / Node 的连接池规范模板

---

## 25. 一句话结论

对于“单节点 Redis、Key 少但内存暴涨、连接数高得离谱”的场景，最有效的排查路线是：

> **先确认内存是不是耗在连接和缓冲区，再定位来源 IP，再回到业务代码修复连接管理。**

继续单纯增大 `maxmemory`，通常不是治本，只是延后再次出事。
