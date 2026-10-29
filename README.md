# 素材工坊 · Material Studio

## 1. 项目介绍

一个基于 Next.js、Java 和 MySQL 的素材制作应用：选择模板、上传图片、提交异步任务，并在个人历史中查看处理阶段、失败原因和成功结果。任务由 Java 后台独立执行，用户可以连续制作、离开页面或刷新后继续查看进度。

项目使用可控的本地模拟生成器，不调用真实 AI 服务。**成功产物是首张上传图片的复制，不是图片合成。**

## 2. 技术栈与项目结构

| 层次 | 技术 |
|---|---|
| 前端 | Next.js **16.4.3** App Router、React **19.3.1**、TypeScript **5.9.3**、Tailwind CSS 4、TanStack Query 5；Node.js **22** |
| 后端 | Java **21**、Spring Boot **3.5.16**、MyBatis Starter **3.0.5**、Maven Wrapper **3.9.11** |
| 数据与文件 | MySQL **8.4**、Flyway；后端文件目录及 Compose 命名卷 |
| 测试 | JUnit 5、Mockito、MockMvc、H2 MySQL mode；Vitest、React Testing Library、Playwright |

前端依赖由 [package-lock.json](frontend/package-lock.json) 锁定，后端版本由 [pom.xml](backend/pom.xml) 及其 Spring Boot 依赖管理确定。架构、数据模型与正确性约束见 [SPEC.md](SPEC.md)。

```text
frontend/src/app/                        模板列表、制作详情、个人历史、同源 API 代理
frontend/src/components/                 上传、制作、任务状态、用户切换和图片预览
frontend/public/demo/                    6 张自制封面、3 张示例图片
frontend/e2e/                            浏览器与 API 集成测试
backend/src/main/java/studio/            REST、文件存储、任务执行和失败结算
backend/src/main/resources/templates.json  唯一模板种子
backend/src/main/resources/db/migration/  Flyway 数据库迁移
scripts/                                示例图片生成、数据库故障回归脚本
compose.yaml                            MySQL、Java、Next.js 及持久卷
.github/workflows/ci.yml                 持续集成
SPEC.md                                 实现规格
docs/EXAM_ORIGINAL.md                    原始需求
```

## 3. 快速开始

### Docker Compose

需要 Docker Engine / Docker Desktop（Linux containers）、Compose v2，以及镜像和 npm/Maven 仓库的网络访问权限。

```bash
cp .env.example .env
# 示例密码仅用于本机演示；对外部署前必须修改。
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
docker compose ps
```

- 应用：<http://localhost:3000>
- Java 健康检查：<http://localhost:8080/actuator/health>
- MySQL 不映射宿主端口；Java 的 8080 端口仅绑定 loopback。
- 浏览器访问前端同源 `/api/*`，Next.js 转发请求至 Java；业务执行和持久化始终由 Java/MySQL 完成。

Java 启动时执行 [V1__init.sql](backend/src/main/resources/db/migration/V1__init.sql)，建立 `assets`、`tasks`、`task_assets` 表、外键和索引。模板由 Java 从 JSON 读取，封面和示例素材已随源码提供，不需要另行下载。数据库及 API 时间统一 UTC，界面按浏览器本地时间显示。

`mysql_data` 保存数据库，`storage_data` 保存 `/data/storage/uploads` 和 `results`。修改 `.env` 中的密码不会自动修改已有 MySQL 数据卷内的账号密码。

```bash
docker compose down       # 关停并保留数据
docker compose down -v    # 删除数据库与素材卷，不可恢复；不是回滚操作
```

### 配置与本地开发

配置示例见 [.env.example](.env.example)。根目录 `.env` 仅由 Compose 自动加载；直接运行 Java 或 Next.js 时，需要显式设置环境变量。

| 配置项 | 用途与默认值 |
|---|---|
| `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD` | Compose 数据库密码；示例为 `studio_local`、`local_root_only`，不用于公网部署 |
| `DB_URL`、`DB_USER`、`DB_PASSWORD` | Java 本地数据库连接；默认连接 `localhost:3306/material_studio`，用户 `studio`、密码 `studio_local`；Compose 内连接 `mysql:3306` |
| `STORAGE_PATH` | Java 本地默认 `storage`；后端容器为 `/data/storage` |
| `BACKEND_URL` | Next.js 服务端代理地址；本地默认 `http://localhost:8080`，Compose 为 `http://backend:8080`，不是浏览器公开变量 |
| `CORS_ORIGIN` | Java 允许的单一前端来源，默认 `http://localhost:3000` |
| `DEMO_ENABLED` | 默认 `true`；设为 `false` 时拒绝带 `simulation` 的创建请求，普通制作仍可使用 |

无容器开发时，准备 Java 21、Node 22 和 MySQL 8.4，创建独立的 `material_studio` 数据库及有该库权限的 `studio` 用户。不要对已有业务库运行初始化迁移。

```bash
# 终端一：Java；在此终端按需设置 DB_*、STORAGE_PATH
cd backend
./mvnw spring-boot:run        # Windows 使用 mvnw.cmd

# 终端二：Next.js
cd frontend
npm ci
npm run dev
```

## 4. 功能演示

1. 顶栏选择 Alice，切换模板分类并进入详情。六个模板覆盖 1、2、3 张素材要求。
2. 使用页面的「下载示例图片」，或选择 `frontend/public/demo/sample-1.png` 等本地文件。每个槽位独立上传，支持预览、替换和移除。仅接受 PNG/JPEG，每张不超过 10 MiB、4000 万像素；素材未就绪或提交中时按钮禁用。
3. 点击「开始制作」，受理后立即显示任务。默认三个阶段各约 2000ms，成功后可预览结果。
4. 展开「演示设置」，勾选「启用演示参数」。分别选择素材检查、模拟生成、结果整理失败，验证失败阶段和原因；将「每阶段耗时」设为 0～10000ms，观察不同处理速度。该数值同等应用于三个阶段，不是总耗时。
5. 使用同一组素材连续提交成功和失败任务，查看各自独立的等待、处理中和终态。
6. 制作中刷新、离开或关闭页面，再进入「制作历史」；页面从 Java 恢复记录并继续跟踪未完成任务。刷新不会中断后台制作。
7. 切换 Bob，独立上传和制作；Bob 不显示 Alice 的记录。刷新保留所选用户，切回 Alice 可恢复其历史。
8. 若查询暂时不可用，界面保留已有任务并提示同步错误，可手动重试；不会把请求异常改成任务 FAILED。不要用重启 Java 演示任务续跑，项目不支持进程重启恢复。

## 5. API 与数据访问

所有 `/api` 请求（包括模板查询）均需 `X-Demo-User: alice` 或 `bob`。Java 默认地址为 `http://localhost:8080/api`，也可通过前端 3000 端口的同源代理访问。

| 方法和路径 | 请求 / 响应 |
|---|---|
| `GET /templates?category=` | 分类可选，返回模板数组 |
| `GET /templates/{id}` | 返回模板详情；不存在时 404 |
| `POST /assets` | multipart 字段 `file`；201 返回 `{id,mimeType,sizeBytes,previewUrl}` |
| `GET /assets/{id}/content` | 返回本人上传的图片二进制；非本人 404 |
| `POST /tasks` | `{templateId,assetIds,simulation?}`；202 返回 `{id,status:"QUEUED",stage:"NONE"}` |
| `GET /tasks` | 本人历史任务，创建时间倒序 |
| `GET /tasks/{id}` | 单任务详情；非本人 404 |
| `GET /tasks/{id}/result` | 本人成功结果二进制；未就绪 409、非本人 404 |

模板字段：`id,title,category,coverUrl,description,requiredImageCount,acceptedMimeTypes,maxFileSizeBytes`。

任务字段：`id,templateId,templateTitle,status,stage,failedStage,errorMessage,resultUrl,createdAt,updatedAt,finishedAt`；未就绪字段为 `null`。`simulation` 支持 `failStage`（`null` 或三个阶段名）及 `delayMs`（0～10000，默认 2000）。

统一错误为 `{code,message}`：参数错误 400、资源不存在或非本人 404、结果未就绪 409、不支持的 Content-Type 415、任务队列或失败结算容量满 503、服务异常 5xx。

### 请求示例

以下命令在仓库根目录执行；将上传及创建响应中的 ID 分别填入变量。

```bash
API=http://localhost:8080/api
curl -H 'X-Demo-User: alice' "$API/templates"
curl -G -H 'X-Demo-User: alice' --data-urlencode 'category=旅行手记' "$API/templates"
curl -H 'X-Demo-User: alice' "$API/templates/lunar"

curl -i -H 'X-Demo-User: alice' \
  -F 'file=@frontend/public/demo/sample-1.png;type=image/png' "$API/assets"
ASSET_ID='替换为上传响应的id'
curl -H 'X-Demo-User: alice' "$API/assets/$ASSET_ID/content" -o uploaded.png

curl -i -H 'X-Demo-User: alice' -H 'Content-Type: application/json' \
  -d "{\"templateId\":\"lunar\",\"assetIds\":[\"$ASSET_ID\"]}" "$API/tasks"

# 指定失败；改为 CHECK_MATERIAL / FINALIZE，或 null 表示成功
curl -i -H 'X-Demo-User: alice' -H 'Content-Type: application/json' \
  -d "{\"templateId\":\"lunar\",\"assetIds\":[\"$ASSET_ID\"],\"simulation\":{\"failStage\":\"GENERATE\",\"delayMs\":1000}}" "$API/tasks"

TASK_ID='替换为正常制作任务的id'
curl -H 'X-Demo-User: alice' "$API/tasks"
curl -H 'X-Demo-User: alice' "$API/tasks/$TASK_ID"
# 确认该任务已 SUCCEEDED 后读取图片；失败任务没有成功结果
curl -H 'X-Demo-User: alice' "$API/tasks/$TASK_ID/result" -o result.png
curl -i -H 'X-Demo-User: bob' "$API/tasks/$TASK_ID"  # 非本人 404
```

### 演示身份与图片访问

**演示身份不属于安全认证。** 浏览器用 localStorage 保存 alice/bob，后端校验身份头白名单，并按 owner 过滤任务、上传文件和结果。身份头可以被人为伪造，不能将本服务直接当作公网多用户安全边界。

受保护图片通过带身份头的 fetch 获取 Blob，再创建 Object URL 预览；替换、切换用户和卸载时释放。直接使用 `<img src="/api/…">` 不会自动携带身份头。模板封面是前端公开的本地静态文件，不受此限制。

## 6. 异步执行与状态管理

```text
QUEUED / NONE
  → RUNNING / CHECK_MATERIAL
  → RUNNING / GENERATE
  → RUNNING / FINALIZE
  → SUCCEEDED / FINALIZE + resultUrl + finishedAt
任一阶段失败 → 停止制作 → FAILED + failedStage + errorMessage + finishedAt
```

- **受理与执行分离**：Java 校验素材后，在短事务中创建任务及有序素材引用；事务提交后投递至 2 个 worker、20 个排队位置的线程池，返回 202，不等待制作结束。
- **阶段可观察**：TaskRunner 在每阶段开始前独立提交状态，延迟和文件操作在事务外。模拟生成复制首图至临时文件，结果整理原子移动至结果目录。GET 只读，不推进制作；条件 SQL 保证终态不可逆。
- **失败可靠结算**：执行异常及线程池拒绝共用 FailureSettlement，只重试 FAILED 写回，不重跑制作。数据库暂不可写时，独立单线程以 1/2/4/8/16/30 秒退避重试，保留原任务 ID、失败阶段和首次原因。最多预留 128 个运行/待结算记录；满时在 INSERT 前拒绝新任务，避免无界积压。数据库仍不可写时，状态可能暂时保持旧值，不谎报已经完成结算。
- **前端不模拟进度**：上传、提交及查询异常是本地请求状态，任务阶段和终态只来自 Java。任务缓存键包含 userId，各任务按 ID 渲染；有任一未完成任务时每 2 秒同步历史，全部终态后停止。重新进入、聚焦和手动刷新仍可主动请求。
- **生命周期清理**：请求接收 AbortSignal，切换用户或卸载时取消/隔离旧请求。提交网络异常不自动重试，因为服务端可能已经受理；应先同步历史，避免重复创建。

## 7. 测试与持续集成

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

# Compose 三服务运行后，在 frontend 目录执行
npx playwright install chromium
npm run test:e2e
```

[GitHub Actions](.github/workflows/ci.yml) 在 push / pull request 时执行前端检查、Java 测试和构建，并启动真实 Compose/MySQL 运行集成测试、核对数据库终态和归档日志、报告及截图。

已验收业务基线为 **[930c4df97f5970213cf212a7b37ee5fd45b2c39a](https://github.com/Jasmine-wind/material-studio/commit/930c4df97f5970213cf212a7b37ee5fd45b2c39a)**，独立验收结论为 PASS。本次交付整理仅变更文档，不改变该业务基线。

| 验证 | 已运行结果与证据 |
|---|---|
| 前端、Java、Compose CI | [基线 CI](https://github.com/Jasmine-wind/material-studio/actions/runs/40868212357)：三个 job 通过；Java 21 项、前端 8 项测试及 3 个 Playwright 场景通过，lint/typecheck/build/package 和 npm audit 通过 |
| 容器内独立复验 | [容器验收](https://github.com/Jasmine-wind/material-studio/actions/runs/40868655851)：固定检出上述业务基线，测试驱动及构建也在容器内；MySQL 8.4.13 初始化、短暂写故障结算、415 不创建任务、关页继续制作、查询恢复及停止轮询通过 |

核心测试覆盖阶段顺序和失败中断、终态保护、跨用户访问、多任务独立、请求异常与业务失败分离、上传/提交状态及请求清理。数据库失败结算的队列拒绝和容量测试使用真实 executor、MyBatis 与 H2 trigger；不将其声称为真实 MySQL 队列饱和测试。对比度测试针对阶段文字，不代表全站无障碍认证。

数据库故障回归脚本为 [scripts/review-gate-db.py](scripts/review-gate-db.py)。**仅在可丢弃的 Compose 验收数据库上运行** `python3 scripts/review-gate-db.py`，不得用于生产或共享库。脚本临时拒绝任务 UPDATE，随后恢复并用 SQL 等待失败结算；如被强杀，需要手动删除 `regate_transient_write_outage` trigger。

## 8. 实现范围与已知限制

已实现模板分类、真实文件上传、异步三阶段、指定阶段失败、耗时配置、多任务、个人历史、结果预览、演示用户隔离及响应式三页面。

- **没有进程重启自动恢复**。当前进程内已捕获的失败会在数据库恢复后结算；进程退出会丢失内存中的结算记录。重启前 QUEUED/RUNNING 仍可查询，但不会自动继续，可能需要人工处理。浏览器刷新恢复的是查询，不是重启执行。
- **模拟生成只复制首图，演示身份不是认证**。不包含真实 AI、账号服务、会员支付、模板管理后台、任务重做/删除、受理幂等、断点续跑或分布式调度。
- 数据库永久不可写时，结算器有界保留记录、退避并拒绝超额新任务，不能保证已写入终态；单次 JDBC 操作仍受连接/驱动等待行为约束。硬退出及事务提交结果不明等边界不属于进程内补写保证。
- 数据库与文件系统不是原子事务，崩溃可能留下孤立文件；未引用的上传文件不会自动清理。历史全量返回、不分页，适合有限规模演示。
- Flyway 11.7.2 对 MySQL 8.4 有兼容性提醒；实际迁移和业务 SQL 已通过容器验证。Docker 基础镜像使用主版本标签，未固定 digest；不声称完整生产环境认证。

## 9. 部署与回滚

1. 备份数据库和素材卷，保持二者一致。
2. 检出已验收版本，配置 `.env`，执行 `docker compose up --build -d --wait`。
3. 检查三服务健康状态，并验证上传、制作和结果访问。对公网使用前需要真实认证、TLS 和适当的访问控制；关闭模拟参数开关不能替代认证。
4. 回滚时保留旧镜像或 Commit，恢复相应版本后重新启动。本版本仅有 V1 初始化迁移，不修改已应用的迁移；后续破坏性变更需恢复数据库与素材的一致备份。

`docker compose down -v` 会删除数据，不能作为回滚手段。

## 10. 开发过程、投入与 AI 辅助

开发使用 OpenAI Codex 辅助拆解、生成代码、编写测试和排查问题。工作流程为：提供原始需求与架构约束 → 先贯通上传、受理、执行和结果的纵向流程 → 完成前端状态与交互 → 用单测、真实数据库和浏览器验证 → 根据复现证据局部修复 → 独立验收。

一个具体例子是短暂数据库写入故障：先复现阶段更新及失败写回同时失败导致任务悬挂，再补充 trigger 故障回归，加入有界的进程内失败结算器，最后用 H2 和真实 MySQL 容器验证恢复后写入 FAILED、首个原因保留、后续制作停止及业务线程释放。没有用页面查询修复状态，也没有引入消息系统。

开发记录中的时间为 2026-10-29 UTC：初次实现与验证 **09:21～10:11，约 50 分钟**；可靠性完善与复验 **10:40～11:07，约 27 分钟**。合计约 77 分钟是这两段 AI 辅助开发会话的经过时间，**不是人工投入总工时**，也不包含后续独立验收、文档整理等过程；未单独统计可核实的人工总工时。

主要取舍是采用单进程线程池、短事务、轮询和本地文件，使异步状态可观察且便于复现，而非增加 MQ、真实认证或重启恢复。前端使用所列开源框架和测试库，后端使用 Spring/MyBatis 及 Apache Maven Wrapper；未复用第三方业务项目或整套 UI。封面和示例图由 [项目脚本](scripts/generate-demo.py) 使用 Pillow 自制，不依赖付费、内部或第三方在线图片。

## 11. 需求对照

[原始需求 R-01～R-14](docs/EXAM_ORIGINAL.md) 为需求依据；[SPEC.md](SPEC.md) 记录本项目的实现选择，不将额外实现细节当作原题强制要求。

| 需求 | 实现与验证入口 |
|---|---|
| R-01 技术栈与职责 | Next App Router；Java API、MyBatis、MySQL；前后端构建与容器验证 |
| R-02 模板与分类 | Java Templates/JSON 种子、列表筛选、本地封面；API 与浏览器测试 |
| R-03 详情、上传与校验 | UploadSlot/Maker、Storage/TaskService；槽位、素材验证及 1/2/3 张上传测试 |
| R-04 异步受理 | 短事务后调度 TaskRunner；快速202与关页继续执行验证 |
| R-05 阶段与持久化 | 三阶段独立更新、只读 GET、终态条件保护；顺序/状态查询测试 |
| R-06 失败与模拟 | simulation 参数、失败中断、失败结算；三个失败点及数据库故障回归 |
| R-07 状态与请求异常 | 上传/提交状态独立，查询错误保留旧状态；RTL 与浏览器恢复验证 |
| R-08 多任务与生命周期 | 按 ID 渲染、用户缓存键、AbortSignal、停轮询测试 |
| R-09 历史与结果 | Java 历史、刷新跟踪、受保护图片预览；刷新及结果二进制验证 |
| R-10 用户身份 | alice/bob 选择、身份头及 owner 过滤；双用户制作和隔离验证 |
| R-11 界面与可用性 | 三页面、标签/焦点、小屏布局；桌面/手机视口及阶段对比度测试 |
| R-12 CI 与关键测试 | GitHub Actions；JUnit、RTL、Playwright 与数据库回归 |
| R-13 提交材料与说明 | 完整源码、依赖锁定、迁移/种子、素材、配置示例及本 README |
| R-14 容器交付 | Compose、Dockerfile、健康检查与数据卷；真实 MySQL 容器启动通过 |
