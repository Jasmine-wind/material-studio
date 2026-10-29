# Material Studio · 实现规格

本文件记录项目的架构决策、业务流程、数据与 API 契约，以及异步正确性约束。需求依据为 [原始需求 R-01～R-14](docs/EXAM_ORIGINAL.md)；实现细节不构成额外的原题要求，存在冲突时以原题为准。启动、演示和已运行的验证结果见 [README](README.md)。

## 1. 范围与技术决策

- **前端**：Next.js **16.4.3（App Router）**、React **19.3.1**、TypeScript、Tailwind CSS、TanStack Query、Vitest + React Testing Library；Node.js 22；npm + `package-lock.json`。应用依赖使用确定版本并保留锁文件。
- **后端**：Java 21、Spring Boot **3.5.16**、MyBatis Spring Boot Starter **3.0.5**、Maven Wrapper、JUnit 5 + Mockito；MySQL **8.4**；Flyway（包含 `flyway-mysql`）。业务数据和状态由 Java 持久化。
- **异步**：Spring `ThreadPoolTaskExecutor`（2 worker / 20 queue）+ 单进程后台任务；`FailureSettlement` 负责有界的失败状态补写。前端 TanStack Query 在存在未完成任务时轮询用户历史，全终态后停止。不使用 Redis、MQ、SSE、WebSocket、真实 AI 或付费资源。
- **文件**：Java 服务的持久化目录 `storage/uploads`、`storage/results`（Compose 命名卷）；图片只支持 PNG / JPEG。用本地演示图和自制封面素材，不依赖第三方在线图片。
- **身份**：两位可切换演示用户 `alice` / `bob`。浏览器 localStorage 保存选中用户，**每个 Java 业务请求**携带 `X-Demo-User`；后端只认可白名单，并在任务、上传文件、结果查询时按 owner 过滤。**这是演示身份，不是安全认证**，其使用方式和边界在 README 中明确说明。
- **页面**：`/` 模板列表、`/templates/[id]` 详情/制作（右侧展示该用户最近任务）、`/history` 全部个人历史。深色界面，参考附图的“分类栏 + 高封面卡片网格”“左侧封面/上传 + 右侧任务历史”结构；移动端可上下堆叠。参考图内容中的会员、积分、计费、重试、删除、账号设置等题外功能**不实现**。

参考：
- [UI-01 模板列表](https://hire-exam.quvideo.com/stem-images/564c0055-f8fd-490e-9db3-b69979fa9f76.png)
- [UI-02 模板详情](https://hire-exam.quvideo.com/stem-images/73b3f583-479e-4adf-b44a-226d2765b696.png)
- [UI-03 任务历史](https://hire-exam.quvideo.com/stem-images/b54ae1d7-926f-43e5-aeba-32ba3c69a6ee.png)

## 2. 业务流程

1. **模板**：Java 返回至少 **6 个自制模板、3 种分类**，素材数量要求覆盖 **1 / 2 / 3 张**；字段 `id,title,category,coverUrl,description,requiredImageCount,acceptedMimeTypes,maxFileSizeBytes`。模板存于后端资源 JSON，由 Java 读取，不能仅在前端硬编码；封面文件真实可访问。加载/空列表/筛选/404/请求失败都有反馈。
2. **上传**：详情按数量显示槽位；选择、预览、替换、移除；每槽位独立上传状态及错误；提交前校验数量、PNG/JPEG MIME 和大小，提交时服务端再次验证上传记录归属、数量、模板约束及文件存在。上传接口实际保存文件，不能用浏览器临时 URL 充当服务端素材；服务端检查文件签名、大小，生成随机存储键，禁止路径穿越。
3. **提交**：必要校验完成后，Java 在短事务中创建 `QUEUED` 任务及输入引用，**事务提交后**投递进程内 executor；返回 HTTP **202 + taskId**，不等待三阶段执行。前端从“提交中”立即转为已受理/等待或处理中；可继续创建多任务、离开页面或刷新。
4. **三阶段**：后台严格 `CHECK_MATERIAL → GENERATE → FINALIZE`。每阶段开始前独立提交 `RUNNING + stage` 到 MySQL，随后延迟/处理；不能把睡眠包在长事务里。失败立即停止后续步骤，并尝试持久化 `FAILED + failedStage + errorMessage + finishedAt`；数据库暂不可写时由失败结算器在恢复后补写。成功记录 `SUCCEEDED + result_storage_key + finishedAt`，API 返回受保护的 `resultUrl`。终态不可逆，查询接口**只读**，不得推动阶段。`GENERATE` 将第一张已上传图片复制为临时产物，`FINALIZE` 原子移动到结果目录，不实现真实合成。
5. **确定性模拟**：支持 `CHECK_MATERIAL`、`GENERATE`、`FINALIZE` 三阶段指定失败，以及可调整的阶段耗时。同一个 `delayMs` 分别作用于三个阶段，不是任务总耗时。参数通过开发/演示环境的任务创建接口及页面控件设置；关闭演示开关时拒绝带模拟参数的请求。默认各阶段约 2 秒，方便观察任务并行/排队。
6. **历史**：按用户查询任务，显示模板、创建时间、整体状态、当前阶段、成功图片或失败阶段及原因；刷新从 Java/MySQL 恢复。后端任务与浏览器生命周期无关，历史中同时可有 `QUEUED/RUNNING/SUCCEEDED/FAILED`。

## 3. 数据模型与 API 契约

### MySQL（Flyway 建表，索引和初始化）

- `assets(id, owner_id, original_name, mime_type, size_bytes, storage_key, created_at)`。
- `tasks(id, owner_id, template_id, status, stage, failed_stage, error_message, result_storage_key, simulate_fail_stage, simulate_delay_ms, created_at, updated_at, finished_at)`；索引 `(owner_id, created_at)`。
- `task_assets(task_id, position, asset_id)`；主键 `(task_id, position)`，通过外键关联任务与素材。
- 模板从 Java classpath 的 `templates.json` 读取，由 Java API 返回，不维护第二份模板数据。

`status ∈ {QUEUED, RUNNING, SUCCEEDED, FAILED}`；`stage ∈ {NONE, CHECK_MATERIAL, GENERATE, FINALIZE}`。`failedStage` 失败才非空。数据库时间统一 UTC，前端本地化显示。

### Java REST（均为 `/api` 前缀）

所有业务请求均携带白名单身份头 `X-Demo-User: alice` 或 `bob`，包括模板查询。Next.js 的同源代理只转发请求，不执行制作或持久化业务。

| 方法和路径 | 请求 | 主要响应 |
|---|---|---|
| `GET /templates?category=` | 分类可选 | `[{id,title,category,coverUrl,description,requiredImageCount,acceptedMimeTypes,maxFileSizeBytes}]` |
| `GET /templates/{id}` | — | 模板详情；不存在 404 |
| `POST /assets` | `multipart/form-data`，字段 `file` | `201 {id,mimeType,sizeBytes,previewUrl}` |
| `GET /assets/{id}/content` | `X-Demo-User` | 图片二进制；非本人 404 |
| `POST /tasks` | `{templateId,assetIds:[...],simulation?:{failStage:null\|"CHECK_MATERIAL"\|"GENERATE"\|"FINALIZE",delayMs:2000}}` | `202 {id,status:"QUEUED",stage:"NONE"}` |
| `GET /tasks` | — | 当前用户历史数组，创建时间倒序 |
| `GET /tasks/{id}` | — | 单任务详情；非本人 404 |
| `GET /tasks/{id}/result` | — | 成功结果图片二进制；非本人 404，未成功 409 或 404 |

任务统一响应至少 `{id,templateId,templateTitle,status,stage,failedStage,errorMessage,resultUrl,createdAt,updatedAt,finishedAt}`；未就绪的字段为 `null`。`resultUrl` 为后端受用户隔离保护的结果端点路径，不是未经授权的静态公开文件。

统一 API 错误 `{code,message}`；参数非法 **400**、非本人或不存在 **404**、资源状态冲突 **409**、不支持的 Content-Type **415**、执行队列或失败结算容量满 **503**、其他服务异常 **5xx**。`simulation.delayMs` 范围为 0～10000，不使用随机失败。`DEMO_ENABLED=false` 时拒绝 `simulation`，不带该字段的普通请求仍可制作。CORS 只允许已配置前端来源。

**重要细节**：图片二进制接口需要 `X-Demo-User`，浏览器 `<img src>` 不会自行附加此请求头；对于结果/已上传图片，前端需 `fetch` 带身份头 → Blob → `URL.createObjectURL` 预览，并在替换、切换用户、卸载时 `URL.revokeObjectURL`。模板封面为前端本地公开文件，可直接使用图片 URL。

## 4. 异步执行与状态正确性

**后端**
- 请求线程处理校验、创建任务及调度；后台使用独立 `TaskRunner`。`TransactionTemplate` 完成提交后才调度，数据库提交**先于**执行，不依赖自调用 `@Async`。
- 每个阶段通过独立、自动提交的 MyBatis 更新保存状态，延迟在事务外。模拟失败在指定阶段记录，后续阶段不再执行；真实异常同样停止制作并结算为可理解的 `FAILED`。
- 投递被拒绝时返回 503，共用 `FailureSettlement` 将已创建任务结算为 `FAILED / NONE`。失败状态写入自身遇到数据库异常时保留首次任务 ID、失败阶段和原因，由独立单线程以 1/2/4/8/16/30 秒退避补写；不重跑制作，不依赖 GET，不让业务线程循环等待。
- INSERT 前预留失败结算位置，最多 128 个运行/待结算记录；容量满时在创建任务前返回 503。成功、创建事务失败或终态不再可更新时释放预留。日志记录任务、失败阶段、尝试次数、退避间隔和预留数量。
- 阶段推进校验预期状态及前一阶段；终态更新带条件保护，旧线程或延迟结算不能覆盖既有 `SUCCEEDED/FAILED`。阶段状态写入失败时，`stage` 可能保留上一阶段，`failedStage` 记录实际失败的尝试边界。
- 重启后**不自动恢复执行**。结算记录只在当前进程内存中；数据库永久不可写时有界等待，不伪造落库结果。旧的排队/处理中记录保持可查询，但不会自动继续，可能需要人工处理；README 如实说明此边界。

**前端**
- TanStack Query 任务缓存键必须包含 `userId`：`['tasks', userId]`；所有请求可取消（接收 `AbortSignal`），切换用户或离开页面不能出现旧请求覆盖新用户数据。客户端示例用户切换后立即展示对应用户记录或加载态。
- 任务视图以**后端状态为唯一真相**；本地只管理 `uploading/submitting/queryError` 等请求状态，不能用定时器把任务阶段往下推。
- 用户有任一 `QUEUED/RUNNING` 任务时每 2 秒自动轮询用户历史；全部终态时停止轮询；首屏、刷新和重新进入页面主动请求一次；保留“手动刷新”入口。
- 查询报错只展示“状态暂时无法同步 / 重试”，**保留旧任务状态**，绝不改写为 `FAILED`；`FAILED` 只能来自 Java 明确返回的业务状态。
- 多任务必须按 ID 独立渲染；某任务失败不得污染其他任务，提交状态不得误当成任务执行状态；上传和提交禁用重复操作。

## 5. 前端页面与交互

- **全局**：深色、克制的强调色、高质量自制封面、顶部品牌/模板/历史/演示用户切换，明确可访问标签和键盘焦点。
- **模板列表 `/`**：分类横向筛选；多列竖图卡片；标题、封面、图片数；加载骨架、请求失败重试、空结果。
- **详情 `/templates/[id]`**：宽屏左右布局；左侧模板示例和 N 个上传槽位、尺寸/格式提示及创建按钮；右侧该用户历史任务卡片，显示阶段/状态/失败/结果。窄屏上下排列；模板不存在展示 404 和返回入口。
- **历史 `/history`**：用户全部任务倒序，图片结果可预览；加载、空列表、查询失败手动重试；刷新后恢复未结束任务并继续轮询。
- 上传采用原生 `input[type=file]`（隐藏但有可访问 label）或语义化按钮；替换/移除分别可操作；不扩展题外功能。

## 6. 验证说明

后端现有 21 项测试、前端 8 项测试及 3 个 Playwright 集成场景。以下约束通过对应测试和真实 MySQL 容器验证；具体运行证据见 README，不以测试数量或静态检查替代运行结果。

**后端（JUnit 5）**
1. 任务创建返回 `QUEUED` 且不等待阶段总耗时；阶段按 `CHECK_MATERIAL → GENERATE → FINALIZE` 执行，阶段可从持久化/模拟 repository 观察。
2. 对三个阶段分别注入失败；验证失败阶段、原因、终态和**后续阶段未执行**。
3. 不合法模板、数量/MIME/大小、跨用户素材与任务访问被拒绝；查询不触发任务执行；成功后重复查询不会回到 `RUNNING`。
4. 临时数据库写故障、失败写回再次失败及恢复后的结算保留原 ID/阶段/原因；两种既有终态不被覆盖；执行队列拒绝共用结算机制；容量满时拒绝创建，制作线程不循环重试。
5. 不支持的请求媒体类型返回 415，不误创建任务。

**前端（Vitest + React Testing Library）**
1. 同时存在多个任务，一个成功、一个失败，按 ID 分别更新，无串扰。
2. 查询返回 HTTP 5xx/网络错误时已有任务保留原业务状态，错误信息与业务失败明确区分。
3. 任务全部终态停止轮询；用户切换后旧请求结果不可污染新用户；提交已受理后不继续展示“提交中”。

**容器集成验证**
`docker compose up --build` 启动 MySQL、Java、Next。验证流程为模板筛选 → 上传 1/2/3 张 → 连续创建成功和指定阶段失败任务 → 刷新跟踪/查看结果 → 切换 alice/bob 隔离记录，并核对 API 与 MySQL 状态。Playwright 同时检查小屏布局、阶段文字对比度和结果预览；独立补充验证覆盖关页后执行、上传替换/移除及活跃任务查询恢复。

`scripts/review-gate-db.py` 在可丢弃的 Compose 数据库中注入临时 UPDATE 故障，只用 SQL 观察自动结算，不通过 GET 修复状态。该脚本不得用于生产或共享数据库；若被强杀，需要手动删除其 `regate_transient_write_outage` trigger。队列饱和并伴随数据库写故障的分支由 H2 trigger 集成测试覆盖，不声称已在 MySQL 中运行相同组合。

## 7. 仓库结构与交付约定

```text
.
├── frontend/             # Next App Router、组件、API client、状态与测试、public/demo
├── backend/              # Spring Boot、MyBatis、Flyway、executor、测试、mvnw
├── compose.yaml          # mysql / backend / frontend，healthcheck，数据卷
├── .github/workflows/ci.yml
├── .env.example          # 仅示例，无真实凭据
├── SPEC.md               # 本文件
├── docs/EXAM_ORIGINAL.md # 原始需求
└── README.md
```

GitHub Actions 在 `push` 与 `pull_request` 执行：前端 `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`；Java `./mvnw -B test && ./mvnw -B package`。Next.js 16 使用 ESLint CLI，不依赖已移除的 `next lint`。Compose 集成任务运行 Playwright、数据库故障脚本和持久化状态检查，并归档证据。

README 说明版本、连接与配置、运行命令、数据库初始化、素材来源、API、异步状态机、演示身份与业务操作、验证结果、实现限制、投入与 AI 使用过程，以及部署回滚。执行结果和实际投入与证据保持一致，不把会话经过时间当作人工总工时。

交付包含源码、锁文件、迁移/种子、示例素材、Dockerfile/Compose、CI、测试及文档；README 保留可追溯的已验收业务 Commit。源码压缩包不包含依赖、构建缓存、数据库数据或真实凭据。

## 8. 设计取舍与范围

- 单进程线程池与轮询满足当前异步场景，阶段更新采用短事务，便于观察和复现；不引入消息队列或分布式任务系统。
- 本地文件与命名卷满足上传和预览；数据库与文件系统没有原子事务保证，需要一起备份，崩溃可能留下孤立文件。未引用上传不自动清理，历史全量返回不分页，适合有限规模演示。
- 失败结算仅补写状态，不自动重试制作；不实现任务受理幂等、断点续跑或进程重启恢复。原因按数据库 500 字符字段保存，完整异常保留在日志。
- 演示身份、首图复制产物、自制模板和图片降低外部依赖；不实现真实认证/注册、模板管理后台、积分会员、支付、真实 AI 或其他题外功能。

这些是当前实现及其边界，不是新的开发任务清单；后续扩展需要单独评估需求。
