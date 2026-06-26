#!/bin/bash
# ============================================================
# CRYD 自动部署脚本
# 由 ECS cron 定时执行，或手动运行
# 用法: bash deploy.sh
# ============================================================
set -e

REPO_DIR="/root/SGHR"
FRONTEND_DIR="$REPO_DIR/frontend"
BACKEND_DIR="$REPO_DIR/后端"
DEPLOY_TIME=$(date +%Y%m%d_%H%M%S)
JAR_PATH="/opt/sghr/app/cryd-1.0.0.jar"
BACKUP_DIR="/opt/sghr/app/backups"
FRONTEND_TARGET="/opt/sghr/frontend"
LOG_FILE="/opt/sghr/app/deploy.log"

log() {
    echo "[$(date '+%H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "=========================================="
log "🚀 开始部署: $DEPLOY_TIME"
log "=========================================="

# ---- 1. 拉取最新代码 ----
log "[1/8] 拉取最新代码..."
cd "$REPO_DIR"
git reset --hard >> "$LOG_FILE" 2>&1
if ! git pull origin master 2>&1 | tee -a "$LOG_FILE"; then
    log "❌ git pull 失败"
    exit 1
fi
COMMIT=$(git log -1 --format="%h %s")
log "   最新提交: $COMMIT"

# ---- 2. 构建前端 ----
log "[2/8] 构建前端..."
cd "$FRONTEND_DIR"
npm install --silent >> "$LOG_FILE" 2>&1
if ! npm run build 2>&1 | tee -a "$LOG_FILE"; then
    log "❌ 前端构建失败"
    exit 1
fi
log "   前端构建完成"

# ---- 3. 部署前端 ----
log "[3/8] 部署前端..."
rm -rf "$FRONTEND_TARGET"/*
cp -r dist/* "$FRONTEND_TARGET/"
log "   前端已部署到 $FRONTEND_TARGET"

# ---- 4. 备份旧 JAR ----
log "[4/8] 备份旧 JAR..."
if [ -f "$JAR_PATH" ]; then
    mkdir -p "$BACKUP_DIR"
    cp "$JAR_PATH" "$BACKUP_DIR/cryd-1.0.0_${DEPLOY_TIME}.jar"
    log "   已备份: cryd-1.0.0_${DEPLOY_TIME}.jar"

    # 只保留最近 5 个备份
    cd "$BACKUP_DIR"
    ls -t cryd-1.0.0_*.jar 2>/dev/null | tail -n +6 | xargs -r rm -f
    log "   保留最近 5 个备份"
else
    log "   无旧 JAR（首次部署？）"
fi

# ---- 5. 构建后端 ----
log "[5/8] 构建后端（Docker Maven）..."
cd "$BACKEND_DIR"
rm -rf target

if ! docker run --rm \
    -v "$BACKEND_DIR:/app" \
    -v /root/.m2:/root/.m2 \
    -w /app \
    maven:3.9-eclipse-temurin-21 \
    mvn package -DskipTests -B -q 2>&1 | tee -a "$LOG_FILE"; then
    log "❌ 后端构建失败"
    exit 1
fi
log "   后端构建完成"

# ---- 6. 部署 JAR ----
log "[6/8] 部署 JAR..."
NEW_JAR=$(ls target/cryd-*.jar 2>/dev/null | head -1)
if [ -z "$NEW_JAR" ]; then
    log "❌ 找不到构建产物"
    exit 1
fi
JAR_SIZE=$(stat -c %s "$NEW_JAR")
cp -f "$NEW_JAR" "$JAR_PATH"
log "   JAR 已部署: ${JAR_SIZE} bytes"

# ---- 7. 重启服务 ----
log "[7/8] 重启 Spring Boot..."
# 注入 LLM_API_KEY 环境变量到 systemd 服务
systemctl set-environment LLM_API_KEY="${LLM_API_KEY:-$(grep LLM_API_KEY /opt/sghr/app/.env 2>/dev/null | cut -d= -f2-)}"
systemctl restart sghr
sleep 3
log "   服务已重启"

# ---- 8. 健康检查 ----
log "[8/8] 健康检查..."
SUCCESS=false
for i in $(seq 1 10); do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" != "000" ]; then
        log "   ✅ 服务正常: HTTP $HTTP_CODE (第${i}次)"
        SUCCESS=true
        break
    fi
    log "   等待中... ($i/10)"
    sleep 3
done

if [ "$SUCCESS" = false ]; then
    log "❌ 服务启动失败！正在回滚..."
    LATEST_BACKUP=$(ls -t "$BACKUP_DIR"/cryd-1.0.0_*.jar 2>/dev/null | head -1)
    if [ -n "$LATEST_BACKUP" ]; then
        cp -f "$LATEST_BACKUP" "$JAR_PATH"
        systemctl restart sghr
        log "   已回滚到: $LATEST_BACKUP"
    else
        log "   ⚠️ 无备份可回滚！"
    fi
    exit 1
fi

log "=========================================="
log "✅ 部署完成: $DEPLOY_TIME"
log "   提交: $COMMIT"
log "=========================================="
