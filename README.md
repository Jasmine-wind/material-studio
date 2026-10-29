# 素材工坊 · Material Studio

一个可独立运行的招聘笔试作品：选择模板 → 上传真实图片 → 异步受理 → 三阶段制作 → 成功预览 / 指定阶段失败 → 个人历史。需求基准见 [EXAM_SPEC.md](EXAM_SPEC.md)。不包含会员、支付、真实 AI 或账号系统。

## 技术与目录

- 前端：Next.js **16.4.3** App Router、React **19.3.1**、TypeScript、Tailwind CSS 4、TanStack Query 5；锁定 npm 依赖。容器 / CI 使用 **Node 22**。
- 后端：**Java 21 / Spring Boot 3.5.16 / MyBatis Starter 3.0.5**、Flyway + flyway-mysql、Maven Wrapper 3.9.11。
- 数据：**MySQL 8.4**；图片保存于命名卷，不使用浏览器临时 URL 代替服务端上传。
- 测试：JUnit 5 / Mockito / MockMvc / H2 MySQL mode；Vitest / RTL；Playwright 真实浏览器与 API 集成测试。

```text
frontend/src/app/          模板库、制作详情、个人历史、同源 API 代理
frontend/src/components/  上传槽位、任务视图、演示用户与受保护图片
frontend/public/demo/     6 张自制封面、3 张示例 PNG
frontend/e2e/             真实 API 与浏览器闭环验收
backend/src/main/java/    REST、校验、持久化、独立 TaskRunner
backend/src/main/resources/db/migration/  Flyway V1 表结构与索引
backend/src/main/resources/templates.json 唯一模板种子
scripts/generate-demo.py  原创几何封面生成器（仅重新生成时需 Pillow）
compose.yaml              mysql / backend / frontend + 健康检查 + 持久卷
.github/workflows/ci.yml  前后端校验 + Compose 验收及证据归档
```

封面和示例图均由本项目用 Pillow 绘制，非外链、无第三方图片授权依赖。列表采用原题高封面卡片 / 分类栏结构，详情采用左侧制作 + 右侧历史结构。小屏上下堆叠；未照搬参考图中的题外功能。

## 一键启动

需要 Docker Engine / Docker Desktop（Linux containers）及 Compose v2，可访问镜像与 Maven/npm 仓库。首次构建需要下载依赖。

```bash
cp .env.example .env                 # Windows 可直接复制文件
# 示例密码仅用于本机演示；部署前修改
# 若修改数据库密码，已有 MySQL 卷内密码不会自动修改

docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
docker compose ps
```

打开 **http://localhost:3000**；后端 http://localhost:8080/actuator/health。MySQL 不暴露宿主端口。后端端口仅绑定 loopback。前端 `/api/*` 为运行时同源透明代理，转发身份头至 Java，业务逻辑和数据始终在 Java/MySQL。

- `mysql_data`：数据库；`storage_data`：`/data/storage/uploads` 和 `results`。
- Flyway 在 Java 启动时执行 `V1__init.sql`，建立 `assets/tasks/task_assets`、外键及 `(owner_id, created_at)` 索引；模板由 Java 读取 classpath JSON，无双份模板真相。
- 数据库时间统一 UTC（连接 session UTC），API 返回 ISO UTC，前端显示浏览器本地时间。
- 常规关停：`docker compose down`（保留数据）；彻底清空演示：`docker compose down -v`（**不可逆删除数据**）。

### 本地开发（无容器）

自行准备 MySQL 8.4，并创建独立数据库 `material_studio` 和有该库权限的用户 `studio`。不要对已有业务库运行迁移。

```bash
# 终端一：Java 21
cd backend
# 默认 jdbc:mysql://localhost:3306/material_studio
# 可通过 DB_URL / DB_USER / DB_PASSWORD / STORAGE_PATH 覆盖
./mvnw spring-boot:run           # Windows: mvnw.cmd spring-boot:run

# 终端二：Node 22
cd frontend
npm ci
npm run dev                    # 默认代理 http://localhost:8080
```

后端默认本地连接用户 `studio`、密码 `studio_local`。`backend` 的环境变量由 Spring 读取，根目录 `.env` **仅 Compose 自动加载**；本地运行需显式设置环境变量。`BACKEND_URL` 为前端服务端变量，不是 NEXT_PUBLIC 变量。`CORS_ORIGIN` 只允许配置的单一来源，默认 `http://localhost:3000`。`DEMO_ENABLED=false` 拒绝 simulation 参数；普通制作不附带该参数，仍可正常使用。

## 演示操作

1. 顶栏选择 Alice。筛选「灵感海报 / 旅行手记 / 生活拼贴」；六个模板覆盖 1 / 2 / 3 张要求。
2. 进入「月下漫游」；可点击「下载示例图片」，或使用 `frontend/public/demo/sample-1.png`。每槽位独立上传、预览、替换、移除，等待「上传完成」再提交。支持 PNG/JPEG，每张 ≤10MiB、≤4000 万像素；Java 检查签名、MIME、解码、大小。
3. 「开始制作」返回 202 后立即显示「已受理」，不是一直「提交中」。默认三阶段各 2000ms，可继续使用同一组素材提交不同任务。
4. 展开「演示设置」，勾选「启用演示参数」，选择全部成功或素材检查 / 模拟生成 / 结果整理失败；每阶段耗时可调 0～10000ms。三个失败点都确定性触发，不随机失败。
5. 连续提交成功及失败任务，观察右侧独立 ID / 状态 / 阶段。前往「制作历史」查看全部任务；成功图为**第一张素材的原样复制**，并非真实合成。
6. 制作中刷新 / 离开详情再进入历史，任务仍在 Java 执行。刷新页面从 MySQL 读取；任一未终态任务每 2 秒查询一次，全终态停止。可随时手动刷新。
7. 顶栏切换 Bob，不显示 Alice 的任务；Bob 可独立上传、制作。刷新保留选中用户，再切回 Alice 恢复其历史。
8. 可临时停止 Java，点击手动刷新：前端提示「状态暂时无法同步」，保留已有业务状态，不伪造 FAILED。恢复 Java 后手动重试。

**演示身份不是安全认证。** localStorage 保存 alice/bob，所有 Java 业务请求携带 `X-Demo-User`，可人为伪造。后端仅认这两个值；任务、上传内容、结果均按 owner 查询，越权统一 404。受保护图片由带身份头的 fetch → Blob → Object URL 展示，在替换、用户切换和卸载时释放；不能直接用 `<img src="/api/...">` 绕过身份要求。

## 核心 API 示例

以下 Bash/curl 在仓库根执行，Java 默认端口 8080。所有 `/api` 请求包括模板请求都需要身份头；浏览器使用同源 3000 代理亦可。

```bash
API=http://localhost:8080/api
curl -H 'X-Demo-User: alice' "$API/templates"
curl -H 'X-Demo-User: alice' "$API/templates?category=$(python -c "import urllib.parse; print(urllib.parse.quote('旅行手记'))")"
curl -H 'X-Demo-User: alice' "$API/templates/lunar"

curl -i -H 'X-Demo-User: alice' \
  -F 'file=@frontend/public/demo/sample-1.png;type=image/png' "$API/assets"
# 201 {id,mimeType,sizeBytes,previewUrl}；将返回 id 写入下方 ASSET_ID
ASSET_ID=替换为上传ID
curl -H 'X-Demo-User: alice' "$API/assets/$ASSET_ID/content" -o uploaded.png

curl -i -H 'X-Demo-User: alice' -H 'Content-Type: application/json' \
  -d "{\"templateId\":\"lunar\",\"assetIds\":[\"$ASSET_ID\"]}" "$API/tasks"
# 202 {id,status:"QUEUED",stage:"NONE"}

# 指定失败；可改成 CHECK_MATERIAL / FINALIZE，或 null 成功
curl -i -H 'X-Demo-User: alice' -H 'Content-Type: application/json' \
  -d "{\"templateId\":\"lunar\",\"assetIds\":[\"$ASSET_ID\"],\"simulation\":{\"failStage\":\"GENERATE\",\"delayMs\":1000}}" "$API/tasks"

TASK_ID=替换为任务ID
curl -H 'X-Demo-User: alice' "$API/tasks"
curl -H 'X-Demo-User: alice' "$API/tasks/$TASK_ID"
curl -H 'X-Demo-User: alice' "$API/tasks/$TASK_ID/result" -o result.png
curl -i -H 'X-Demo-User: bob' "$API/tasks/$TASK_ID" # 非本人 404
```

任务响应含 `id,templateId,templateTitle,status,stage,failedStage,errorMessage,resultUrl,createdAt,updatedAt,finishedAt`；未就绪字段 null。统一错误 `{code,message}`：参数 400、资源不存在 / 越权 404、结果未就绪 409、队列满 503、服务故障 5xx。生产部署应关闭模拟开关，不应公开这个演示身份服务。

## 异步状态机与可靠性

```text
QUEUED / NONE
  → RUNNING / CHECK_MATERIAL
  → RUNNING / GENERATE
  → RUNNING / FINALIZE
  → SUCCEEDED / FINALIZE + resultUrl + finishedAt
任一执行阶段异常 → FAILED + failedStage + errorMessage + finishedAt
```

- 请求校验模板、数量、素材归属、格式、大小、文件存在；短事务插入任务和有序引用。`TransactionTemplate` 返回即已提交，之后才向 2 worker / 20 queue 的 `ThreadPoolTaskExecutor` 投递；不依赖自调用 `@Async`。
- 独立 TaskRunner 每阶段开始执行一条条件更新，MyBatis 独立 session 自动提交；延迟 / 文件操作完全在事务外。预期旧 stage/status 条件确保顺序；成功和失败更新都不能覆盖终态。
- `GENERATE` 复制第一张素材至结果卷临时文件，`FINALIZE` 同文件系统原子移动。阶段失败立即中断，finally 清理临时文件。真实异常同样记录 FAILED；日志保留诊断信息。
- executor 拒绝时先将已创建任务记为 FAILED / NONE，再返回 503，不留无解释假排队。客户端遇提交网络异常不自动重试（可能已经受理），可同步历史核实，避免盲目重复提交。
- GET 只读，不推进状态。前端以服务端字段为唯一业务真相；查询错误与业务失败分离，多任务按 ID 渲染。缓存键包含 userId；所有 fetch 接收 AbortSignal，用户切换取消查询并重建工作区，上传 / 提交 / 图片请求卸载中止。

## 测试、构建与 CI

```bash
cd backend
./mvnw -B test
./mvnw -B package
cd ../frontend
npm ci
npm run lint
npm run typecheck
npm run test
npm run build
npm audit

# 三个服务运行后执行真实集成验收
npx playwright install chromium
npm run test:e2e
```

- 后端 14 个测试：真实 MockMvc + Flyway/H2 持久化闭环、快速受理、成功结果、三个失败阶段、顺序和后续不执行、终态不可逆、跨用户访问、无效素材、提交先于调度及队列拒绝处理。
- 前端 8 个测试：多 ID 独立更新、HTTP 5xx / 网络故障保留状态、真实定时轮询终止、旧用户迟到请求隔离、202 后离开提交态、每槽上传状态与校验、图片身份头与 Object URL 回收。
- Playwright 2 个集成场景：真实 API 阶段观测 / 二进制结果 / 1/2/3 素材 / 所有失败点；浏览器筛选 / 上传 / 多任务 / 刷新 / 身份 / 同步异常 / 移动端 / 404，并输出四张桌面与手机截图。
- GitHub Actions 在 push / pull_request 执行 Node 22 前端全链、Java 21 test + package，另启真实 Compose/MySQL 8.4 执行 Playwright，检查数据库终态并归档截图、trace、MySQL 记录和容器日志。

### 实际验证记录（持续更新）

- 本机 Windows / Java 21.0.7：后端 **14 tests** 与 package 通过。前端初始用 Node 24.11.0，随后下载独立 Node **22.23.4**，实际用 npm **10.9.9** 执行干净 `npm ci`，再运行 lint / typecheck / **8 tests** / build，全部通过；npm audit **0 漏洞**。
- 本机缺 Docker CLI/Engine，WSL 未安装；未擅自安装需要系统重启的虚拟化功能。替代验证用既有 MySQL 8.0.42 二进制初始化**独立临时数据目录与 3307 端口**，未操作原有 MySQL 服务/数据；真实 API + 浏览器 2 场景通过。这不替代目标 MySQL 8.4 的容器验收。
- **真实 Docker Compose 已连续两轮通过**：最终功能验收 [Actions run 40854969621](https://github.com/Jasmine-wind/material-studio/actions/runs/40854969621)，对应 commit **`e97d8f589ddf51e486bffcd37016087521550827`**；首轮通过记录为 [40854160684](https://github.com/Jasmine-wind/material-studio/actions/runs/40854160684)。Linux runner 中 `docker compose up --build -d --wait` 成功，MySQL **8.4.13** / Java / Next 三服务均 healthy；Node 22 前端校验、Java 14 tests + package、**2 个容器 Playwright 场景全部通过**。最终 SQL 实查 Flyway V1 success=1，**13 条任务：7 成功、6 失败，三个失败阶段各 2 条**；均有 finishedAt，无遗留非终态；11 条上传记录。包括 Bob 独立制作、切换刷新及双向隔离。结果二进制与输入首图逐字节一致。
- 已下载并审阅该 run 的 `compose-acceptance` artifact：含 compose-ps、容器日志、MySQL 记录、HTML 测试报告和四张界面截图。本机还通过 playwright-cli 手动操作 Bob 两槽上传 → 默认耗时提交 → 刷新观察处理中 → 成功结果，并检查 SQL 中 alice/bob 归属。**容器走查为自动化浏览器，本机交互走查为原生服务**，不混淆两种环境。
- 初轮 CI `40852911668` 曾因 npm 跨平台可选依赖缺失而失败；从空 node_modules 重建 lockfile，修复 Node 22 测试中的 Blob realm 与异步通知断言时序后回归通过。没有隐藏失败记录。

## 交付、部署与回滚

仓库源码、迁移、种子、自制素材、lockfile、Maven Wrapper、Docker 与 CI 为完整交付；不包含 node_modules / target / .next / 本机数据库 / 真实凭据。根目录 `.env.example` 仅含明确标注的本机演示默认值。

部署：备份数据库和素材卷 → checkout 已验收 commit → 配置 `.env` → `docker compose up --build -d --wait` → 健康检查与冒烟测试。对公网使用需增加真实认证与 TLS（本题不实现）；勿将演示身份当成访问控制边界。回滚：保留旧镜像/commit，恢复旧版本并 up；本次仅 V1 初始迁移，不改已应用迁移，后续迁移需考虑兼容；破坏性变更先恢复数据库和素材的一致备份。`down -v` 不是回滚。

功能验收 commit：**`e97d8f589ddf51e486bffcd37016087521550827`**（前端、后端、Compose 三个 CI job 全绿）。其后的提交仅更新本 README 验收证据，不改源码；最终目录 commit 可用 `git log -1` 查询。已按仓库现有远程正常推送：**https://github.com/Jasmine-wind/material-studio**。

## 完成范围、限制与取舍

已实现规格内模板、真实上传、异步三阶段、确定性失败、个人历史、结果保护、多任务隔离、轮询生命周期、刷新恢复、用户切换、响应式界面、关键测试、Docker/CI 配置及素材交付（对应规格汇总 R-01～R-14；仓库没有额外原题 R 编号明细，不虚构逐条映射）。

- **没有进程重启自动恢复**：Java 重启前 QUEUED/RUNNING 记录保持可查询，但不会继续；浏览器刷新恢复的是查询，不是重启执行。可能需要人工清理；单进程 demo 无分布式保证。数据库写入故障 / 硬退出也可能留下非终态。
- 无真实 AI，产物为首图复制；不做真实认证、重试/删除、计费、队列中间件或管理后台。
- 未引用的上传文件不会自动回收，取消请求时服务器可能已落盘；长期使用需离线运维清理。历史全量返回不分页，适合有限规模演示，不承诺大量生产任务吞吐。
- 数据库与文件系统不是分布式事务，硬崩溃可能留下孤立文件；文件卷必须与数据库一起备份。
- CI H2 快速测试不等于 MySQL 方言验收，已另用真实 MySQL 8.4 容器覆盖。Boot 管理的 Flyway 11.7.2 对 MySQL 8.4 输出“较新版本”兼容性提醒；实际迁移、业务 SQL、容器测试均通过，未忽略迁移错误。Docker 基础镜像使用主版本标签，应用依赖精确锁定；正式部署可进一步固定镜像 digest。

## 真实投入与 AI 工作流

使用 OpenAI Codex（pi coding harness）完成开发，没有人工编造测试记录。2026-10-29 **09:21 UTC 开始**；**10:11 UTC 完成最终功能回归，约 50 分钟**（10:05 UTC 首轮容器通过，随后补充 Bob 双向隔离并再次跑通容器）；之后仅归档本 README 和等待交付提交的 CI。这是本次会话墙钟投入，不是人工估算工时。

执行方式：先完整读规格与空仓库/环境 → 最大纵向切片建立 Java/MySQL/Flyway 和原创素材 → 实现上传到终态的闭环并运行后端测试（最终 14 项） → 实现前端三页面/身份/轮询，运行 RTL 与生产构建 → 使用独立 MySQL 和浏览器实测 → 发现测试将 Next 路由播报器误当业务 alert，限定到 main 后重新跑通 → npm audit 发现 Vitest mocker 漏洞，升级到 4.1.12 并重新验证 → CI 使用真正 Linux Docker 弥补本机缺引擎的限制 → 核验日志和数据库后完成交付。没有为展示技术增加题外业务；优先短事务、可观察状态、真实 IO 和可重复验证，而非额外架构层。
