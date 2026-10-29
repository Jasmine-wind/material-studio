# 素材制作网站｜最小开发规格（Codex Sol 执行版）

> 本文件是唯一的开发规格。目标：独立运行、覆盖试题 R-01～R-14、能够实测演示，**不追求超出题目的复杂度**。实现时以原题为最终约束；冲突时以原题为准。

## 1. 范围与技术决策

- **前端**：Next.js **16.x（App Router）**、React 19、TypeScript、Tailwind CSS、TanStack Query、Vitest + React Testing Library；Node.js 22；npm + `package-lock.json`。使用当前兼容的安全补丁版本并锁定依赖。
- **后端**：Java 21、Spring Boot **3.5.16**、MyBatis Spring Boot Starter **3.0.5**、Maven Wrapper、JUnit 5 + Mockito；MySQL **8.4**；Flyway（包含 `flyway-mysql`）。业务数据和状态由 Java 持久化。
- **异步**：Spring `ThreadPoolTaskExecutor`（建议 2 worker / 20 queue）+ 单进程后台任务；前端 TanStack Query **仅轮询未完成任务**。不使用 Redis、MQ、SSE、WebSocket、真实 AI、付费资源。
- **文件**：Java 服务的持久化目录 `storage/uploads`、`storage/results`（Compose 命名卷）；图片只支持 PNG / JPEG。用本地演示图和自制封面素材，不依赖第三方在线图片。
- **身份**：两位可切换演示用户 `alice` / `bob`。浏览器 localStorage 保存选中用户，**每个 Java 业务请求**携带 `X-Demo-User`；后端只认可白名单，并在任务、上传文件、结果查询时按 owner 过滤。**这是演示身份，不是安全认证**，README 必须明确。
- **页面**：`/` 模板列表、`/templates/[id]` 详情/制作（右侧展示该用户最近任务）、`/history` 全部个人历史。深色界面，参考附图的“分类栏 + 高封面卡片网格”“左侧封面/上传 + 右侧任务历史”结构；移动端可上下堆叠。参考图内容中的会员、积分、计费、重试、删除、账号设置等题外功能**不实现**。

参考：
- [UI-01 模板列表](https://hire-exam.quvideo.com/stem-images/564c0055-f8fd-490e-9db3-b69979fa9f76.png)
- [UI-02 模板详情](https://hire-exam.quvideo.com/stem-images/73b3f583-479e-4adf-b44a-226d2765b696.png)
- [UI-03 任务历史](https://hire-exam.quvideo.com/stem-images/b54ae1d7-926f-43e5-aeba-32ba3c69a6ee.png)

## 2. 必要业务闭环

1. **模板**：Java 返回至少 **6 个自制模板、3 种分类**，素材数量要求覆盖 **1 / 2 / 3 张**；字段 `id,title,category,coverUrl,description,requiredImageCount,acceptedMimeTypes,maxFileSizeBytes`。模板存于后端资源 JSON 或 MySQL 种子表，不能仅在前端硬编码；封面文件真实可访问。加载/空列表/筛选/404/请求失败都有反馈。
2. **上传**：详情按数量显示槽位；选择、预览、替换、移除；每槽位独立上传状态及错误；提交前校验数量、PNG/JPEG MIME 和大小，提交时服务端再次验证上传记录归属、数量、模板约束及文件存在。上传接口实际保存文件，不能用浏览器临时 URL 充当服务端素材；服务端检查文件签名、大小，生成随机存储键，禁止路径穿越。
3. **提交**：必要校验完成后，Java 在短事务中创建 `QUEUED` 任务及输入引用，**事务提交后**投递进程内 executor；返回 HTTP **202 + taskId**，不等待三阶段执行。前端从“提交中”立即转为已受理/等待或处理中；可继续创建多任务、离开页面或刷新。
4. **三阶段**：后台严格 `CHECK_MATERIAL → GENERATE → FINALIZE`。每阶段开始前独立提交 `RUNNING + stage` 到 MySQL，随后延迟/处理；不能把睡眠包在长事务里。失败立即持久化 `FAILED + failedStage + errorMessage + finishedAt` 并停止后续步骤；成功 `SUCCEEDED + resultPath + finishedAt`。终态不可逆，查询接口**只读**，不得推动阶段。建议 `GENERATE` 准备临时模拟产物，`FINALIZE` 原子移动到结果目录；可复制第一张已上传图片作为预览产物，不必实现真实合成。
5. **确定性模拟**：支持 `CHECK_MATERIAL`、`GENERATE`、`FINALIZE` 三阶段指定失败，以及逐阶段可调耗时。通过开发/演示环境的任务创建参数控制（见接口）；关闭演示开关时忽略或拒绝这些参数。默认各阶段约 2 秒，方便观察任务并行/排队。
6. **历史**：按用户查询任务，显示模板、创建时间、整体状态、当前阶段、成功图片或失败阶段及原因；刷新从 Java/MySQL 恢复。后端任务与浏览器生命周期无关，历史中同时可有 `QUEUED/RUNNING/SUCCEEDED/FAILED`。

## 3. 最小数据模型与 API 契约

### MySQL（Flyway 建表，索引和初始化）

- `assets(id, owner_id, original_name, mime_type, size_bytes, storage_key, created_at)`。
- `tasks(id, owner_id, template_id, status, stage, failed_stage, error_message, result_storage_key, simulate_fail_stage, simulate_delay_ms, created_at, updated_at, finished_at)`；索引 `(owner_id, created_at)`。
- `task_assets(task_id, position, asset_id)`；唯一约束 `(task_id, position)`。
- 模板可从 Java classpath 的 seed JSON 读取，数据由 Java API 返回；也可落 `templates` 表，但不要两份真相。

`status ∈ {QUEUED, RUNNING, SUCCEEDED, FAILED}`；`stage ∈ {NONE, CHECK_MATERIAL, GENERATE, FINALIZE}`。`failedStage` 失败才非空。数据库时间统一 UTC，前端本地化显示。

### Java REST（均为 `/api` 前缀）

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

统一 API 错误 `{code,message}`；参数非法 **400**、无权或不存在 **404**、资源状态冲突 **409**、服务异常 **5xx**。`simulation.delayMs` 限制在合理范围（例如 0～10000），不使用随机失败。CORS 只允许已配置前端来源。

**重要细节**：图片二进制接口需要 `X-Demo-User`，浏览器 `<img src>` 不会自行附加此请求头；对于结果/已上传图片，前端需 `fetch` 带身份头 → Blob → `URL.createObjectURL` 预览，并在替换、切换用户、卸载时 `URL.revokeObjectURL`。模板封面为前端本地公开文件，可直接使用图片 URL。

## 4. 异步正确性与前端状态纪律

**后端**
- 请求线程只处理校验、创建任务及调度；后台使用独立 `TaskRunner`。确保数据库提交**先于**调度（事务后事件或显式事务模板）；不要依赖自调用 `@Async`。
- 每个阶段状态更新使用独立短事务；延迟在事务外。模拟失败必须在指定阶段被记录，后续阶段不能运行；真实异常同样转为可理解的 `FAILED`。
- 投递被拒绝也须明确处理（拒绝请求或把已创建任务标记为失败），不能留下本次请求造成的无解释假排队。终态更新加条件保护，避免旧线程覆盖终态。
- 重启后**无需自动恢复执行**；重启前遗留的排队/处理中记录应保持可查询，README 说明不会自动继续、可能需要人工清理。不得把此能力伪装成已实现。

**前端**
- TanStack Query 任务缓存键必须包含 `userId`：`['tasks', userId]`；所有请求可取消（接收 `AbortSignal`），切换用户或离开页面不能出现旧请求覆盖新用户数据。客户端示例用户切换后立即展示对应用户记录或加载态。
- 任务视图以**后端状态为唯一真相**；本地只管理 `uploading/submitting/queryError` 等请求状态，不能用定时器把任务阶段往下推。
- 用户有任一 `QUEUED/RUNNING` 任务时自动轮询（建议 2 秒）；全部终态时停止轮询；首屏、刷新和重新进入页面主动请求一次；保留“手动刷新”入口。
- 查询报错只展示“状态暂时无法同步 / 重试”，**保留旧任务状态**，绝不改写为 `FAILED`；`FAILED` 只能来自 Java 明确返回的业务状态。
- 多任务必须按 ID 独立渲染；某任务失败不得污染其他任务，提交状态不得误当成任务执行状态；上传和提交禁用重复操作。

## 5. 前端页面与交互

- **全局**：深色、克制的强调色、高质量自制封面、顶部品牌/模板/历史/演示用户切换，明确可访问标签和键盘焦点。
- **模板列表 `/`**：分类横向筛选；多列竖图卡片；标题、封面、图片数；加载骨架、请求失败重试、空结果。
- **详情 `/templates/[id]`**：宽屏左右布局；左侧模板示例和 N 个上传槽位、尺寸/格式提示及创建按钮；右侧该用户历史任务卡片，显示阶段/状态/失败/结果。窄屏上下排列；模板不存在展示 404 和返回入口。
- **历史 `/history`**：用户全部任务倒序，图片结果可预览；加载、空列表、查询失败手动重试；刷新后恢复未结束任务并继续轮询。
- 上传采用原生 `input[type=file]`（隐藏但有可访问 label）或语义化按钮；替换/移除分别可操作；不扩展题外功能。

## 6. 最小关键测试与验收

**后端（JUnit 5）**
1. 任务创建返回 `QUEUED` 且不等待阶段总耗时；阶段按 `CHECK_MATERIAL → GENERATE → FINALIZE` 执行，阶段可从持久化/模拟 repository 观察。
2. 对三个阶段分别注入失败；验证失败阶段、原因、终态和**后续阶段未执行**。
3. 不合法模板、数量/MIME/大小、跨用户素材与任务访问被拒绝；查询不触发任务执行；成功后重复查询不会回到 `RUNNING`。

**前端（Vitest + React Testing Library）**
1. 同时存在多个任务，一个成功、一个失败，按 ID 分别更新，无串扰。
2. 查询返回 HTTP 5xx/网络错误时已有任务保留原业务状态，错误信息与业务失败明确区分。
3. 任务全部终态停止轮询；用户切换后旧请求结果不可污染新用户；提交已受理后不继续展示“提交中”。

**人工容器验收**
`docker compose up --build` 成功启动 MySQL、Java、Next；打开 `http://localhost:3000`：模板筛选 → 上传 1/2/3 张 → 连续创建成功和指定阶段失败任务 → 刷新仍能观察/查看结果 → 切换 alice/bob 隔离记录。检查 API 返回状态及 MySQL 记录。至少一次真实容器启动与端到端手工走查，发现问题则修复并回归。

## 7. 仓库结构与交付门槛

```text
.
├── frontend/             # Next App Router、组件、API client、状态与测试、public/demo
├── backend/              # Spring Boot、MyBatis、Flyway、executor、测试、mvnw
├── compose.yaml          # mysql / backend / frontend，healthcheck，数据卷
├── .github/workflows/ci.yml
├── .env.example          # 仅示例，无真实凭据
├── EXAM_SPEC.md          # 本文件
└── README.md
```

GitHub Actions 在 `push` 与 `pull_request` 执行：前端 `npm ci && npm run lint && npm run typecheck && npm run test && npm run build`；Java `./mvnw -B test && ./mvnw -B package`。确保有 `lint/typecheck/test/build` 脚本；Next.js 16 使用 ESLint CLI，**不要依赖已移除的 `next lint`**。

README 必须包含：版本与结构、配置及数据库连接、启动/测试/构建/关停、Flyway 初始化、模板和本地图片来源、全部核心 API 示例、异步状态机、演示用户与隔离方式、成功/每阶段失败/耗时调整/多任务/刷新恢复的实际演示步骤、CI、部署和回滚基础步骤、已完成/未完成/限制、**真实投入和取舍**、是否使用 AI 及如何让 Codex 拆解→生成→验证→修复的具体工作流。不编造执行过的命令、通过的测试、工时或 Git commit。

**交付定义**：源码、lockfile、迁移/初始化、示例素材、Dockerfile/Compose、CI、测试及 README 全部在仓库中；关键命令实际运行通过；如果仓库已有远程且允许推送，提交并在 README 标明验收 commit；没有远程不虚构链接，可交付完整项目目录或 ZIP。

## 8. 执行顺序（Codex 逐段完成并验证）

1. **工程基座**：仓库结构 → 后端和 MySQL/Flyway → 模板种子、真实本地图片 → Compose 可启动。
2. **后端纵向闭环**：上传 → 提交 → 三阶段执行 → 成功/失败 → 用户隔离/历史；先写核心后端测试。
3. **前端纵向闭环**：统一 API client + 用户身份 → 列表/详情/上传 → 任务列表、轮询、异常处理；写关键前端测试。
4. **视觉与集成**：参考 UI 调整桌面/移动布局 → 多任务/刷新/用户切换人工走查 → CI/Compose 修复。
5. **交付**：README 真实记录、限制说明、验证命令/结果、最终 commit/压缩包；检查无凭据、无 `node_modules`/`target`/`.next` 等构建产物进入交付。

**禁止无必要扩展**：不要构建真实认证/注册、管理后台、模板 CRUD、积分会员、支付、真实 AI、消息队列、分布式任务、自动重试、断点续跑或重启自动恢复。不牺牲必要验证来做额外功能。
