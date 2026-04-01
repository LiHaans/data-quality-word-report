# CPA Token Uploader

将本地目录中的 token JSON 文件上传到 CPA 管理接口；上传成功后自动备份并删除源文件。

## 适用场景

- token 文件由你自己的系统生成
- 需要定时同步到 CPA 服务
- 上传成功后希望本地留备份并清理源目录

## 文件

- `cpa_token_uploader.py`：主脚本
- `cpa_token_uploader.example.json`：配置模板

## 接口约定

参考 CPA 现有脚本，上传接口为：

- `POST {base}/v0/management/auth-files?name={filename}`
- Header: `Authorization: Bearer <api_key>`
- Body: token JSON 内容

成功判定：

- HTTP 200
- 返回 JSON 中 `status == "ok"`

## 配置优先级

优先级为：

`命令行参数 > 环境变量 > JSON 配置文件 > 默认值`

默认配置文件路径：

- `~/.cpa_token_uploader.json`

你也可以用 `--config` 指定别的路径。

## 配置项说明

```json
{
  "base_url": "http://127.0.0.1:8317",
  "api_key": "你的CPA管理Key",
  "src_dir": "/opt/software/xx/token",
  "backup_dir": "/opt/software/xx/token_backup",
  "file_glob": "*.json",
  "name_mode": "original",
  "name_prefix": "",
  "request_timeout": 30,
  "verify_tls": true,
  "recursive": false,
  "archive_by_date": true,
  "delete_after_upload": true,
  "include_hidden": false
}
```

### 字段含义

- `base_url`
  - CPA 服务地址
- `api_key`
  - CPA 管理接口 Bearer Key
- `src_dir`
  - token 源目录
- `backup_dir`
  - 备份目录
- `file_glob`
  - 文件匹配规则，默认 `*.json`
- `name_mode`
  - 上传到 CPA 的文件名模式
  - 可选：
    - `original`：原文件名
    - `timestamp`：原文件名 + 时间戳
    - `full`：带部分路径信息
- `name_prefix`
  - 上传文件名前缀
- `request_timeout`
  - HTTP 超时时间（秒）
- `verify_tls`
  - 是否校验证书
- `recursive`
  - 是否递归扫描子目录
- `archive_by_date`
  - 备份目录是否按日期分层
- `delete_after_upload`
  - 上传成功后是否删除源文件
- `include_hidden`
  - 是否处理隐藏路径/隐藏文件

## 环境变量

- `CPA_TOKEN_CONFIG`
- `CPA_BASE_URL`
- `CPA_API_KEY`
- `TOKEN_SRC_DIR`
- `TOKEN_BACKUP_DIR`
- `TOKEN_FILE_GLOB`
- `CPA_NAME_MODE`
- `CPA_NAME_PREFIX`
- `CPA_REQUEST_TIMEOUT`
- `CPA_VERIFY_TLS`
- `TOKEN_RECURSIVE`
- `TOKEN_ARCHIVE_BY_DATE`
- `TOKEN_DELETE_AFTER_UPLOAD`

## 运行方式

### 1. 先准备配置

```bash
cp cpa_token_uploader.example.json ~/.cpa_token_uploader.json
vi ~/.cpa_token_uploader.json
```

### 2. 试运行（不实际上传）

```bash
python3 cpa_token_uploader.py --dry-run
```

### 3. 正式运行

```bash
python3 cpa_token_uploader.py
```

### 4. 指定配置文件

```bash
python3 cpa_token_uploader.py --config /path/to/config.json
```

### 5. 命令行覆盖参数

```bash
python3 cpa_token_uploader.py \
  --base-url http://127.0.0.1:8317 \
  --api-key 'your-key' \
  --src-dir /opt/software/xx/token \
  --backup-dir /opt/software/xx/token_backup \
  --recursive
```

## 处理规则

脚本对每个文件的处理顺序：

1. 扫描命中文件
2. 读取并解析 JSON
3. 上传到 CPA
4. 只有上传成功时：
   - 复制到备份目录
   - 根据配置决定是否删除源文件
5. 上传失败时：
   - 保留源文件
   - 打印失败日志

## 备份路径示例

如果：

- `backup_dir=/opt/software/xx/token_backup`
- `archive_by_date=true`

那么 `a.json` 可能会备份到：

- `/opt/software/xx/token_backup/2026-04-02/a.json`

## cron 示例

每 5 分钟跑一次：

```cron
*/5 * * * * /usr/bin/python3 /path/to/cpa_token_uploader.py >> /var/log/cpa_token_uploader.log 2>&1
```

使用自定义配置文件：

```cron
*/5 * * * * /usr/bin/python3 /path/to/cpa_token_uploader.py --config /etc/cpa_token_uploader.json >> /var/log/cpa_token_uploader.log 2>&1
```

## 建议上线步骤

1. 先用 `--dry-run` 检查命中文件是否正确
2. 先设置 `delete_after_upload=false` 试跑一轮
3. 确认 CPA 端已正确接收后，再打开删除源文件
4. 最后再挂 cron

## 退出码

- `0`：全部成功或无文件可处理
- `1`：配置/执行异常
- `2`：部分文件失败

## 依赖

需要 Python 3 和 `requests`：

```bash
pip install requests
```
