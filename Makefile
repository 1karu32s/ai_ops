# SuperBizAgent Makefile
# 用于自动化项目初始化和文档向量化

# 配置变量
SERVER_URL = http://localhost:9900
UPLOAD_API = $(SERVER_URL)/api/upload
DOCS_DIR = aiops-docs
HEALTH_CHECK_API = $(SERVER_URL)/milvus/health
DOCKER_COMPOSE_FILE = vector-database.yml
MILVUS_CONTAINER = milvus-standalone

# 颜色输出
GREEN = \033[0;32m
YELLOW = \033[0;33m
RED = \033[0;31m
NC = \033[0m # No Color

.PHONY: help init start stop restart check upload clean up down status wait

# 默认目标：显示帮助信息
help:
	@echo -e "$(GREEN)SuperBizAgent Makefile$(NC)"
	@echo -e ""
	@echo -e "可用命令："
	@echo -e "  $(YELLOW)make init$(NC)    - 🚀 一键初始化（启动Docker → 启动服务 → 上传文档）"
	@echo -e "  $(YELLOW)make up$(NC)      - 启动 Docker Compose（Milvus 向量数据库）"
	@echo -e "  $(YELLOW)make down$(NC)    - 停止 Docker Compose"
	@echo -e "  $(YELLOW)make status$(NC)  - 查看 Docker 容器状态"
	@echo -e "  $(YELLOW)make start$(NC)   - 启动 Spring Boot 服务（后台运行）"
	@echo -e "  $(YELLOW)make stop$(NC)    - 停止 Spring Boot 服务"
	@echo -e "  $(YELLOW)make restart$(NC) - 重启 Spring Boot 服务"
	@echo -e "  $(YELLOW)make check$(NC)   - 检查服务器是否运行"
	@echo -e "  $(YELLOW)make upload$(NC)  - 上传 aiops-docs 目录下的所有文档"
	@echo -e "  $(YELLOW)make clean$(NC)   - 清理临时文件"
	@echo -e ""
	@echo -e "使用示例："
	@echo -e "  1. 一键初始化: make init"
	@echo -e "  2. 手动启动: make up && make start && make upload"
	@echo -e "  3. 停止服务: make stop && make down"

# 一键初始化：启动Docker → 启动服务 → 检查服务 → 上传文档
init:
	@echo -e "$(GREEN)🚀 开始一键初始化 SuperBizAgent...$(NC)"
	@echo -e ""
	@echo -e "$(YELLOW)步骤 1/4: 启动 Docker Compose（Milvus 向量数据库）$(NC)"
	@$(MAKE) up
	@echo -e ""
	@echo -e "$(YELLOW)步骤 2/4: 启动 Spring Boot 服务$(NC)"
	@$(MAKE) start
	@echo -e ""
	@echo -e "$(YELLOW)步骤 3/4: 等待服务就绪$(NC)"
	@$(MAKE) wait
	@echo -e ""
	@echo -e "$(YELLOW)步骤 4/4: 上传 AIOps 文档到向量数据库$(NC)"
	@$(MAKE) upload
	@echo -e ""
	@echo -e "$(GREEN)═══════════════════════════════════════════════════════$(NC)"
	@echo -e "$(GREEN)✅ 初始化完成！所有文档已成功向量化存储到数据库$(NC)"
	@echo -e "$(GREEN)═══════════════════════════════════════════════════════$(NC)"
	@echo -e ""
	@echo -e "$(GREEN)🌐 服务访问地址:$(NC)"
	@echo -e "   API 服务: $(SERVER_URL)"
	@echo -e "   Attu (Web UI): http://localhost:8000"
	@echo -e ""
	@echo -e "$(YELLOW)💡 提示: 服务正在后台运行，查看日志: tail -f server.log$(NC)"

# 启动 Spring Boot 服务（后台运行）
start:
	@echo -e "$(YELLOW)🚀 启动 Spring Boot 服务...$(NC)"
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
	   echo -e "$(GREEN)✅ 服务已经在运行中 ($(SERVER_URL))$(NC)"; \
	else \
	   echo -e "$(YELLOW)📦 正在启动服务（后台运行）...$(NC)"; \
	   nohup mvn spring-boot:run > server.log 2>&1 & \
	   echo $$! > server.pid; \
	   echo -e "$(GREEN)✅ 服务启动命令已执行$(NC)"; \
	   echo -e "$(YELLOW)   PID: $$(cat server.pid)$(NC)"; \
	   echo -e "$(YELLOW)   日志文件: server.log$(NC)"; \
	fi

# 等待服务器就绪（最多等待 60 秒）
wait:
	@echo -e "$(YELLOW)⏳ 等待服务器就绪...$(NC)"
	@max_attempts=60; \
	attempt=0; \
	while [ $$attempt -lt $$max_attempts ]; do \
	   if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
		  echo -e "$(GREEN)✅ 服务器已就绪！($(SERVER_URL))$(NC)"; \
		  exit 0; \
	   fi; \
	   attempt=$$((attempt + 1)); \
	   printf "$(YELLOW)   等待中... [$$attempt/$$max_attempts]$(NC)\r"; \
	   sleep 1; \
	done; \
	echo -e ""; \
	echo -e "$(RED)❌ 服务器启动超时！$(NC)"; \
	echo -e "$(YELLOW)请检查日志: tail -f server.log$(NC)"; \
	exit 1

# 检查服务器是否运行
check:
	@echo -e "$(YELLOW)🔍 检查服务器状态...$(NC)"
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
	   echo -e "$(GREEN)✅ 服务器运行正常 ($(SERVER_URL))$(NC)"; \
	else \
	   echo -e "$(RED)❌ 服务器未运行或无法连接！$(NC)"; \
	   echo -e "$(YELLOW)请先启动项目: mvn spring-boot:run$(NC)"; \
	   exit 1; \
	fi

# 上传所有文档
upload:
	@echo -e "$(YELLOW)📤 开始上传 $(DOCS_DIR) 目录下的文档...$(NC)"
	@if [ ! -d "$(DOCS_DIR)" ]; then \
	   echo -e "$(RED)❌ 目录 $(DOCS_DIR) 不存在！$(NC)"; \
	   exit 1; \
	fi
	@count=0; \
	success=0; \
	failed=0; \
	for file in $(DOCS_DIR)/*.md; do \
	   if [ -f "$$file" ]; then \
		  count=$$((count + 1)); \
		  filename=$$(basename "$$file"); \
		  echo -e "$(YELLOW)  [$$count] 上传文件: $$filename$(NC)"; \
		  response=$$(curl -s -w "\n%{http_code}" -X POST $(UPLOAD_API) \
			 -F "file=@$$file" \
			 -H "Accept: application/json"); \
		  http_code=$$(echo "$$response" | tail -n1); \
		  body=$$(echo "$$response" | sed '$$d'); \
		  if [ "$$http_code" = "200" ]; then \
			 echo -e "$(GREEN)      ✅ 成功: $$filename$(NC)"; \
			 success=$$((success + 1)); \
		  else \
			 echo -e "$(RED)      ❌ 失败: $$filename (HTTP $$http_code)$(NC)"; \
			 echo -e "$$body" | head -n 3; \
			 failed=$$((failed + 1)); \
		  fi; \
		  sleep 1; \
	   fi; \
	done; \
	echo -e ""; \
	echo -e "$(GREEN)📊 上传统计:$(NC)"; \
	echo -e "   总计: $$count 个文件"; \
	echo -e "   $(GREEN)成功: $$success$(NC)"; \
	if [ $$failed -gt 0 ]; then \
	   echo -e "   $(RED)失败: $$failed$(NC)"; \
	fi

# 停止 Spring Boot 服务
stop:
	@echo -e "$(YELLOW)🛑 停止 Spring Boot 服务...$(NC)"
	@if [ -f server.pid ]; then \
	   pid=$$(cat server.pid); \
	   if ps -p $$pid > /dev/null 2>&1; then \
		  kill $$pid; \
		  echo -e "$(GREEN)✅ 服务已停止 (PID: $$pid)$(NC)"; \
	   else \
		  echo -e "$(YELLOW)⚠️  进程不存在 (PID: $$pid)$(NC)"; \
	   fi; \
	   rm -f server.pid; \
	else \
	   echo -e "$(YELLOW)⚠️  未找到 server.pid 文件$(NC)"; \
	   pkill -f "spring-boot:run" && echo -e "$(GREEN)✅ 已停止所有 spring-boot 进程$(NC)" || echo -e "$(YELLOW)⚠️  没有运行中的 spring-boot 进程$(NC)"; \
	fi

# 重启 Spring Boot 服务
restart:
	@echo -e "$(YELLOW)🔄 重启 Spring Boot 服务...$(NC)"
	@echo -e ""
	@echo -e "$(YELLOW)步骤 1/2: 停止服务$(NC)"
	@$(MAKE) stop
	@echo -e ""
	@echo -e "$(YELLOW)步骤 2/2: 启动服务$(NC)"
	@$(MAKE) start
	@echo -e ""
	@$(MAKE) wait
	@echo -e ""
	@echo -e "$(GREEN)✅ 服务重启完成！$(NC)"

# 清理临时文件
clean:
	@echo -e "$(YELLOW)🧹 清理临时文件...$(NC)"
	@rm -rf uploads/*.tmp
	@rm -f server.pid server.log
	@echo -e "$(GREEN)✅ 清理完成$(NC)"

# 显示文档列表
list-docs:
	@echo -e "$(YELLOW)📚 $(DOCS_DIR) 目录下的文档:$(NC)"
	@if [ -d "$(DOCS_DIR)" ]; then \
	   ls -lh $(DOCS_DIR)/*.md 2>/dev/null || echo -e "$(RED)没有找到 .md 文件$(NC)"; \
	else \
	   echo -e "$(RED)目录 $(DOCS_DIR) 不存在$(NC)"; \
	fi

# 测试单个文件上传
test-upload:
	@echo -e "$(YELLOW)🧪 测试上传单个文件...$(NC)"
	@if [ -f "$(DOCS_DIR)/cpu_high_usage.md" ]; then \
	   curl -X POST $(UPLOAD_API) \
		  -F "file=@$(DOCS_DIR)/cpu_high_usage.md" \
		  -H "Accept: application/json" | jq .; \
	else \
	   echo -e "$(RED)测试文件不存在$(NC)"; \
	fi

# 启动 Docker Compose（智能检测，避免重复启动）
up:
	@echo -e "$(YELLOW)🐳 检查 Docker 容器状态...$(NC)"
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
	   echo -e "$(RED)❌ Docker Compose 文件不存在: $(DOCKER_COMPOSE_FILE)$(NC)"; \
	   exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
	   echo -e "$(GREEN)✅ Milvus 容器已经在运行中$(NC)"; \
	   echo -e "$(YELLOW)📋 当前运行的容器:$(NC)"; \
	   docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
	else \
	   echo -e "$(YELLOW)🚀 启动 Docker Compose...$(NC)"; \
	   docker-compose -f $(DOCKER_COMPOSE_FILE) up -d; \
	   echo -e ""; \
	   echo -e "$(YELLOW)⏳ 等待容器启动...$(NC)"; \
	   sleep 5; \
	   if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
		  echo -e "$(GREEN)✅ Docker Compose 启动成功！$(NC)"; \
		  echo -e ""; \
		  echo -e "$(GREEN)📋 运行中的容器:$(NC)"; \
		  docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
		  echo -e ""; \
		  echo -e "$(GREEN)🌐 服务访问地址:$(NC)"; \
		  echo -e "   Milvus: localhost:19530"; \
		  echo -e "   Attu (Web UI): http://localhost:8000"; \
		  echo -e "   MinIO: http://localhost:9001 (admin/minioadmin)"; \
	   else \
		  echo -e "$(RED)❌ 容器启动失败，请检查日志: docker-compose -f $(DOCKER_COMPOSE_FILE) logs$(NC)"; \
		  exit 1; \
	   fi; \
	fi

# 停止 Docker Compose
down:
	@echo -e "$(YELLOW)🛑 停止 Docker Compose...$(NC)"
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
	   echo -e "$(RED)❌ Docker Compose 文件不存在: $(DOCKER_COMPOSE_FILE)$(NC)"; \
	   exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "milvus"; then \
	   docker-compose -f $(DOCKER_COMPOSE_FILE) down; \
	   echo -e "$(GREEN)✅ Docker Compose 已停止$(NC)"; \
	else \
	   echo -e "$(YELLOW)⚠️  没有运行中的 Milvus 容器$(NC)"; \
	fi

# 查看 Docker 容器状态
status:
	@echo -e "$(YELLOW)📊 Docker 容器状态:$(NC)"
	@echo -e ""
	@if docker ps -a --format '{{.Names}}' | grep -q "milvus"; then \
	   docker ps -a --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
	   echo -e ""; \
	   running=$$(docker ps --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
	   total=$$(docker ps -a --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
	   echo -e "$(GREEN)运行中: $$running / $$total$(NC)"; \
	else \
	   echo -e "$(YELLOW)⚠️  没有找到 Milvus 相关容器$(NC)"; \
	   echo -e "$(YELLOW)提示: 运行 'make docker-up' 启动容器$(NC)"; \
	fi