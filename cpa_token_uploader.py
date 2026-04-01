#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
将本地 token 目录中的 JSON 文件上传到 CPA 管理接口；上传成功后备份并删除源文件。

特性：
- 参数全部可配置（环境变量 > JSON 配置文件 > 默认值）
- 支持 dry-run
- 支持递归扫描
- 支持归档目录按日期分层
- 失败文件保留原地，不会删除
- 记录运行日志到 stdout，适合 cron

默认接口参考：
- POST   {base}/v0/management/auth-files?name={filename}
- Header Authorization: Bearer <key>
- Body: token JSON 内容
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
import urllib.parse
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple

try:
    import requests
except Exception:
    requests = None


DEFAULT_CONFIG_PATH = str(Path.home() / ".cpa_token_uploader.json")
DEFAULT_SRC_DIR = "/opt/software/xx/token"
DEFAULT_BACKUP_DIR = "/opt/software/xx/token_backup"
DEFAULT_FILE_GLOB = "*.json"
DEFAULT_TIMEOUT = 30
DEFAULT_RECURSIVE = False
DEFAULT_ARCHIVE_BY_DATE = True
DEFAULT_DELETE_AFTER_UPLOAD = True
DEFAULT_VERIFY_TLS = True


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%F %T')}] {msg}")


def load_json_file(path: str) -> Dict[str, Any]:
    p = Path(path)
    if not p.exists():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(f"配置文件解析失败: {path}: {exc}")


def env_bool(name: str, default: bool | None = None) -> bool | None:
    val = os.environ.get(name)
    if val is None:
        return default
    val = val.strip().lower()
    if val in {"1", "true", "yes", "y", "on"}:
        return True
    if val in {"0", "false", "no", "n", "off"}:
        return False
    return default


def merge_config(cli_args: argparse.Namespace) -> Dict[str, Any]:
    config_path = cli_args.config or os.environ.get("CPA_TOKEN_CONFIG") or DEFAULT_CONFIG_PATH
    file_cfg = load_json_file(config_path)

    def pick(key: str, env: str, default: Any = None) -> Any:
        cli_val = getattr(cli_args, key, None)
        if cli_val not in (None, ""):
            return cli_val
        env_val = os.environ.get(env)
        if env_val not in (None, ""):
            return env_val
        if key in file_cfg and file_cfg[key] not in (None, ""):
            return file_cfg[key]
        return default

    cfg = {
        "config": config_path,
        "base_url": pick("base_url", "CPA_BASE_URL", ""),
        "api_key": pick("api_key", "CPA_API_KEY", ""),
        "src_dir": pick("src_dir", "TOKEN_SRC_DIR", DEFAULT_SRC_DIR),
        "backup_dir": pick("backup_dir", "TOKEN_BACKUP_DIR", DEFAULT_BACKUP_DIR),
        "file_glob": pick("file_glob", "TOKEN_FILE_GLOB", DEFAULT_FILE_GLOB),
        "name_mode": pick("name_mode", "CPA_NAME_MODE", "original"),
        "name_prefix": pick("name_prefix", "CPA_NAME_PREFIX", ""),
        "request_timeout": int(pick("request_timeout", "CPA_REQUEST_TIMEOUT", DEFAULT_TIMEOUT)),
        "verify_tls": file_cfg.get("verify_tls", DEFAULT_VERIFY_TLS),
        "recursive": file_cfg.get("recursive", DEFAULT_RECURSIVE),
        "archive_by_date": file_cfg.get("archive_by_date", DEFAULT_ARCHIVE_BY_DATE),
        "delete_after_upload": file_cfg.get("delete_after_upload", DEFAULT_DELETE_AFTER_UPLOAD),
        "dry_run": bool(cli_args.dry_run),
        "include_hidden": bool(file_cfg.get("include_hidden", False)),
    }

    env_verify = env_bool("CPA_VERIFY_TLS")
    if cli_args.verify_tls is not None:
        cfg["verify_tls"] = cli_args.verify_tls
    elif env_verify is not None:
        cfg["verify_tls"] = env_verify

    env_recursive = env_bool("TOKEN_RECURSIVE")
    if cli_args.recursive is not None:
        cfg["recursive"] = cli_args.recursive
    elif env_recursive is not None:
        cfg["recursive"] = env_recursive

    env_archive = env_bool("TOKEN_ARCHIVE_BY_DATE")
    if cli_args.archive_by_date is not None:
        cfg["archive_by_date"] = cli_args.archive_by_date
    elif env_archive is not None:
        cfg["archive_by_date"] = env_archive

    env_delete = env_bool("TOKEN_DELETE_AFTER_UPLOAD")
    if cli_args.delete_after_upload is not None:
        cfg["delete_after_upload"] = cli_args.delete_after_upload
    elif env_delete is not None:
        cfg["delete_after_upload"] = env_delete

    return cfg


def validate_config(cfg: Dict[str, Any]) -> None:
    missing = []
    if not cfg["base_url"]:
        missing.append("base_url / CPA_BASE_URL")
    if not cfg["api_key"]:
        missing.append("api_key / CPA_API_KEY")
    if missing:
        raise RuntimeError("缺少必要配置: " + ", ".join(missing))


def iter_files(src_dir: str, pattern: str, recursive: bool, include_hidden: bool) -> Iterable[Path]:
    base = Path(src_dir)
    if not base.exists():
        raise RuntimeError(f"源目录不存在: {src_dir}")
    if not base.is_dir():
        raise RuntimeError(f"源路径不是目录: {src_dir}")

    if recursive:
        iterator = base.rglob(pattern)
    else:
        iterator = base.glob(pattern)

    for path in sorted(iterator):
        if not path.is_file():
            continue
        if not include_hidden and any(part.startswith('.') for part in path.parts):
            continue
        yield path


def build_remote_name(path: Path, cfg: Dict[str, Any]) -> str:
    mode = str(cfg.get("name_mode", "original")).strip().lower()
    prefix = str(cfg.get("name_prefix", "") or "")
    now = datetime.now().strftime("%Y%m%d_%H%M%S")

    if mode == "timestamp":
        name = f"{path.stem}_{now}{path.suffix}"
    elif mode == "full":
        safe = "__".join(path.parts[-3:])
        name = safe
    else:
        name = path.name

    return f"{prefix}{name}"


def load_token_json(path: Path) -> Dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(f"JSON 解析失败: {path}: {exc}")


def upload_one(base_url: str, api_key: str, remote_name: str, payload: Dict[str, Any], timeout: int, verify_tls: bool) -> Tuple[bool, str]:
    if requests is None:
        raise RuntimeError("缺少 requests 依赖，请安装: pip install requests")

    url = f"{base_url.rstrip('/')}/v0/management/auth-files?name={urllib.parse.quote(remote_name)}"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    try:
        resp = requests.post(url, headers=headers, json=payload, timeout=timeout, verify=verify_tls)
    except Exception as exc:
        return False, f"请求失败: {exc}"

    try:
        body = resp.json()
    except Exception:
        body = {"raw": resp.text[:500]}

    if resp.status_code == 200 and isinstance(body, dict) and body.get("status") == "ok":
        return True, "ok"

    return False, f"HTTP {resp.status_code} | {body}"


def backup_path_for(src_file: Path, backup_root: str, archive_by_date: bool) -> Path:
    root = Path(backup_root)
    if archive_by_date:
        day = datetime.now().strftime("%Y-%m-%d")
        root = root / day
    root.mkdir(parents=True, exist_ok=True)
    return root / src_file.name


def backup_and_delete(src_file: Path, backup_file: Path, delete_after_upload: bool) -> None:
    backup_file.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src_file, backup_file)
    if delete_after_upload:
        src_file.unlink()


def process_files(cfg: Dict[str, Any]) -> int:
    files = list(iter_files(cfg["src_dir"], cfg["file_glob"], bool(cfg["recursive"]), bool(cfg["include_hidden"])))
    if not files:
        log(f"未发现待处理文件: dir={cfg['src_dir']} pattern={cfg['file_glob']}")
        return 0

    log(f"发现待处理文件 {len(files)} 个")
    success = 0
    failed = 0

    for file in files:
        remote_name = build_remote_name(file, cfg)
        log(f"处理文件: {file} -> remote_name={remote_name}")
        try:
            payload = load_token_json(file)
        except Exception as exc:
            failed += 1
            log(f"跳过，原因: {exc}")
            continue

        if cfg["dry_run"]:
            log("dry-run 模式，不实际上传、不备份、不删除")
            success += 1
            continue

        ok, msg = upload_one(
            base_url=cfg["base_url"],
            api_key=cfg["api_key"],
            remote_name=remote_name,
            payload=payload,
            timeout=int(cfg["request_timeout"]),
            verify_tls=bool(cfg["verify_tls"]),
        )
        if not ok:
            failed += 1
            log(f"上传失败: {msg}")
            continue

        backup_file = backup_path_for(file, cfg["backup_dir"], bool(cfg["archive_by_date"]))
        try:
            backup_and_delete(file, backup_file, bool(cfg["delete_after_upload"]))
            log(f"上传成功，已备份到: {backup_file}")
            if cfg["delete_after_upload"]:
                log(f"源文件已删除: {file}")
            success += 1
        except Exception as exc:
            failed += 1
            log(f"上传成功但备份/删除失败: {exc}")

    log(f"处理完成: success={success}, failed={failed}, total={len(files)}")
    return 0 if failed == 0 else 2


def make_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Upload local token JSON files to CPA service, then backup and delete.")
    p.add_argument("--config", help="配置文件路径，默认 ~/.cpa_token_uploader.json")
    p.add_argument("--base-url", dest="base_url", help="CPA 服务地址，如 http://127.0.0.1:8317")
    p.add_argument("--api-key", dest="api_key", help="CPA 管理 API Key")
    p.add_argument("--src-dir", dest="src_dir", help="token 源目录")
    p.add_argument("--backup-dir", dest="backup_dir", help="备份目录")
    p.add_argument("--file-glob", dest="file_glob", help="文件匹配规则，默认 *.json")
    p.add_argument("--name-mode", dest="name_mode", choices=["original", "timestamp", "full"], help="远端文件名模式")
    p.add_argument("--name-prefix", dest="name_prefix", help="远端文件名前缀")
    p.add_argument("--request-timeout", dest="request_timeout", type=int, help="HTTP 超时秒数")
    p.add_argument("--dry-run", action="store_true", help="只扫描与打印，不实际上传")
    p.add_argument("--recursive", dest="recursive", action="store_true", help="递归扫描子目录")
    p.add_argument("--no-recursive", dest="recursive", action="store_false", help="不递归扫描子目录")
    p.add_argument("--archive-by-date", dest="archive_by_date", action="store_true", help="备份目录按日期分层")
    p.add_argument("--no-archive-by-date", dest="archive_by_date", action="store_false", help="备份目录不按日期分层")
    p.add_argument("--delete-after-upload", dest="delete_after_upload", action="store_true", help="上传成功后删除源文件")
    p.add_argument("--no-delete-after-upload", dest="delete_after_upload", action="store_false", help="上传成功后不删除源文件")
    p.add_argument("--verify-tls", dest="verify_tls", action="store_true", help="校验 TLS 证书")
    p.add_argument("--no-verify-tls", dest="verify_tls", action="store_false", help="不校验 TLS 证书")
    p.set_defaults(recursive=None, archive_by_date=None, delete_after_upload=None, verify_tls=None)
    return p


def main() -> int:
    parser = make_parser()
    args = parser.parse_args()
    try:
        cfg = merge_config(args)
        validate_config(cfg)
        log(f"使用配置文件: {cfg['config']}")
        log(f"源目录: {cfg['src_dir']}")
        log(f"备份目录: {cfg['backup_dir']}")
        log(f"文件规则: {cfg['file_glob']}")
        log(f"递归扫描: {cfg['recursive']}")
        log(f"按日期归档: {cfg['archive_by_date']}")
        log(f"上传成功后删除源文件: {cfg['delete_after_upload']}")
        log(f"dry-run: {cfg['dry_run']}")
        return process_files(cfg)
    except Exception as exc:
        log(f"执行失败: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
