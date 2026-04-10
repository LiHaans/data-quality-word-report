
## 语种识别接口（新增）

本项目新增一个轻量级 HTTP 接口用于语种识别，不依赖 Spring。

### 启动方式

```bash
mvn -q -DskipTests exec:java -Dexec.args="--lang-api 8080"
```

### 请求

```
POST http://127.0.0.1:8080/api/lang/detect
Content-Type: application/json

{"text":"Hello world"}
```

### 响应

```json
{"lang":"eng"}
```

### 语种编码说明

- 统一返回 **ISO 639-3** 形式（如 `eng`、`zho`、`fil`、`spa`）
- 中文会进一步区分：
  - `zh-Hans`（简体）
  - `zh-Hant`（繁体）

### 菲律宾语识别增强

在默认识别结果基础上，增加 Tagalog/Filipino 词汇特征判断：

- 当文本中出现典型菲律宾语停用词（如 `ang`, `ng`, `mga`, `hindi`, `salamat` 等）时，强制返回 `fil`
- 如果识别器输出 `tl` 或 `fil`，统一返回 `fil`

> 说明：此增强仅用于提升菲律宾语识别准确率，不影响其他语种识别。
