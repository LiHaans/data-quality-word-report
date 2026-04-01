#!/usr/bin/env bash
set -euo pipefail

REDIS_CLI_BIN="${REDIS_CLI_BIN:-redis-cli}"
HOST="${REDIS_HOST:-127.0.0.1}"
PORT="${REDIS_PORT:-6379}"
PASS="${REDIS_PASSWORD:-}"
OUT_DIR="${1:-./redis-check-$(date +%F-%H%M%S)}"

mkdir -p "$OUT_DIR"

redis_cmd() {
  if [[ -n "$PASS" ]]; then
    "$REDIS_CLI_BIN" -h "$HOST" -p "$PORT" -a "$PASS" "$@"
  else
    "$REDIS_CLI_BIN" -h "$HOST" -p "$PORT" "$@"
  fi
}

run_and_save() {
  local name="$1"
  shift
  echo "[RUN] $name"
  {
    echo "# command: $*"
    echo "# time: $(date '+%F %T %Z')"
    echo
    bash -lc "$*"
  } > "$OUT_DIR/$name.txt" 2>&1 || true
}

echo "[INFO] output dir: $OUT_DIR"
echo "[INFO] target redis: ${HOST}:${PORT}"

run_and_save redis_ping "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} PING"
run_and_save info_memory "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} INFO memory"
run_and_save info_clients "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} INFO clients"
run_and_save info_stats "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} INFO stats"
run_and_save info_persistence "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} INFO persistence"
run_and_save memory_stats "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} MEMORY STATS"
run_and_save dbsize "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} DBSIZE"
run_and_save config_maxmemory "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CONFIG GET maxmemory"
run_and_save config_maxmemory_policy "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CONFIG GET maxmemory-policy"
run_and_save config_maxclients "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CONFIG GET maxclients"
run_and_save config_timeout "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CONFIG GET timeout"
run_and_save config_tcp_keepalive "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CONFIG GET tcp-keepalive"
run_and_save client_list_head "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CLIENT LIST | head -n 50"
run_and_save client_list_full "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CLIENT LIST"
run_and_save client_ip_top "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CLIENT LIST | awk -F'addr=| ' '{print \$2}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30"
run_and_save client_omem_nonzero "$(printf '%q ' "$REDIS_CLI_BIN") -h $(printf '%q' "$HOST") -p $(printf '%q' "$PORT") ${PASS:+-a $(printf '%q' "$PASS")} CLIENT LIST | grep -E 'omem=[1-9]|qbuf=[1-9]' | head -n 200"
run_and_save ss_state_count "ss -ant | grep ':${PORT}' | awk '{print \$1}' | sort | uniq -c"
run_and_save ss_ip_top "ss -ant | grep ':${PORT}' | awk '{print \$5}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -n 30"
run_and_save ps_redis "ps -ef | grep '[r]edis-server'"
run_and_save top_mem "ps -eo pid,ppid,cmd,%mem,%cpu,rss,vsz --sort=-rss | head -n 20"

cat > "$OUT_DIR/README.txt" <<README
Redis 巡检结果目录

目标:
- Redis: ${HOST}:${PORT}
- 时间: $(date '+%F %T %Z')

文件说明:
- info_memory.txt: Redis INFO memory
- info_clients.txt: Redis INFO clients
- info_stats.txt: Redis INFO stats
- memory_stats.txt: Redis MEMORY STATS
- client_list_full.txt: 完整 CLIENT LIST
- client_ip_top.txt: 按来源 IP 聚合的连接 TOP
- client_omem_nonzero.txt: omem/qbuf 非 0 的客户端抽样
- ss_state_count.txt: 系统层 TCP 状态统计
- ss_ip_top.txt: 系统层来源 IP TOP

建议:
1. 先看 info_memory.txt / info_clients.txt / memory_stats.txt
2. 再看 client_ip_top.txt / ss_ip_top.txt
3. 如果重启后问题复现，重启后 1~3 分钟内再跑一次脚本，对比两次结果
README

echo "[DONE] collected to: $OUT_DIR"
