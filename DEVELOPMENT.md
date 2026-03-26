# Cammate 开发文档（当前实现）

本文档只描述当前仓库已落地能力，不写目标蓝图。最终版规划请看 [`DESIGN.md`](./DESIGN.md)。

## 1. 当前版本快照

- 文档更新时间：2026-03-26
- 代码基线：当前工作区（Android + FastAPI）
- 运行数据库：SQLite（`py/app_data.db`，轻量化本地存储）

当前主链路可用：

`登录/注册 -> 场景识别 -> AI 构图建议 -> 拍照 -> AI 修图 -> 评分（含文字评价） -> 可选发布社区 -> 社区主动发布 -> 社区互动 -> 社区推荐 -> AI 融合/双人共创 -> 保存到相册`

## 2. 当前已实现功能

### 2.1 Android 客户端（`kotlin/`）

已实现页面与导航：

- `splash`
- `login` / `register`
- `home`（底部导航：拍摄功能 / 用户社区 / 个人设置）
- `camera`
- `settings`
- `retouch`
- `feedback`
- `community`

已实现业务能力：

- 登录态持久化与会话恢复（DataStore）
- 退出登录链路：本地会话立即清理，服务端回收异步执行（弱网下也可即时退出）
- CameraX 取帧、场景识别联动、AI 分析结果叠加
- 修图模式（模板/自定义）、结果预览与保存
- 评分页：
  - `review_text`（0~280）
  - 发布到社区开关
  - 地点标签与风景类型
  - 固定流程：先 `/feedback`，再按开关发布
- 社区页：
  - 主动发布（相册 / 最近拍摄）
  - 双通道发布：评分后发布关联 `feedback_id`；主动发布允许 `feedback_id` 为空
  - 社区流分页（仅服务端真实数据；网络失败时不再注入本地 mock 帖子）
  - 点赞 / 取消点赞
  - 一级评论（新增、列表、作者删除）
  - 评论接口支持分页参数（`offset/limit`）
  - 接力发布（引用父帖）
  - 同款复刻指南（模板引导）
  - 推荐（地点 + 场景）
  - AI 融合（单人，任务化异步执行）
  - 双人共创（单用户双图，任务化异步执行）
  - 融合/共创任务状态轮询、失败重试
  - 前后对比滑杆（融合/共创结果）
- 社区图片网络加载（`coil-compose` + Bearer Header）

UI 现状：

- 高级浅色玻璃风（暖色纸感背景 + 玻璃卡片）
- 渐变背景 + 玻璃卡片 + 动效容器
- 内置衬线字体（`Noto Serif SC`）

### 2.2 服务端（`py/`）

已实现路由：

- `GET /healthz`
- `POST /auth/register`
- `POST /auth/login`
- `GET /auth/me`
- `POST /auth/logout`
- `POST /scene/detect`
- `POST /analyze`（NDJSON）
- `POST /retouch`
- `POST /feedback`
- `POST /community/posts`（评分后发布，兼容旧链路）
- `POST /community/posts/direct`（主动发布）
- `POST /community/relay/posts`（接力发布）
- `GET /community/feed`
- `GET /community/recommendations`
- `GET /community/posts/{post_id}/image`
- `POST /community/posts/{post_id}/likes`
- `DELETE /community/posts/{post_id}/likes`
- `GET /community/posts/{post_id}/comments?offset&limit`
- `POST /community/posts/{post_id}/comments`
- `DELETE /community/comments/{comment_id}`
- `POST /community/remake/guide`
- `POST /community/compose`
- `POST /community/compose/jobs`
- `POST /community/cocreate/compose`
- `POST /community/cocreate/jobs`
- `GET /community/jobs/{job_id}`
- `POST /community/jobs/{job_id}/retry`
- `POST /community/jobs/{job_id}/cancel`

已实现关键逻辑：

- 全业务接口鉴权（除健康检查与登录注册）
- 社区发布基础审核：
  - 文本敏感词过滤（`COMMUNITY_BLOCKED_WORDS`）
  - 图片 Base64 校验与解码
  - 最大边长限制（`COMMUNITY_UPLOAD_MAX_SIDE`）
- 推荐固定规则：
  - `final_score = 0.65*match + 0.25*(rating/5) + 0.10*freshness`
  - 30 天半衰
  - 回退：全匹配 -> 单维匹配 -> 全局
- 融合/共创模型接入：
  - 默认用 `COMMUNITY_IMAGE_*`
  - 未配置时回退 `ARK_IMAGE_*`
  - 可返回 `implementation_status=ready|placeholder`
  - 支持异步任务流：`queued -> running -> success/failed/canceled`
  - 支持失败任务重试（仅 `failed` 可重试）与显式取消
- 创意任务稳定性（V1.2）：
  - 任务领取规则固定：`queued + next_retry_at<=now`，按 `priority DESC, created_at ASC`
  - 运行中周期写心跳，并维护租约过期时间（`lease_expires_at`）
  - 启动和轮询过程会恢复“遗留 running 且租约过期”任务
  - `queued` 任务可立即取消；`running` 任务按安全检查点软取消
  - `/community/jobs/{job_id}` 返回扩展字段：
    `priority/started_at/heartbeat_at/lease_expires_at/cancel_reason`
- 健康检查增强（V1.2）：
  - `GET /healthz` 返回 `creative_queue` 摘要：`queued/running/failed_recent/workers`
- SQLite 连接层增强：
  - `PRAGMA foreign_keys=ON`
  - `PRAGMA busy_timeout=8000`
  - `PRAGMA journal_mode=WAL`
  - `PRAGMA synchronous=NORMAL`
- 兼容迁移（已实现）：
  - 旧库若 `community_posts.feedback_id` 为 `NOT NULL`，启动时自动迁移为可空
  - 主动发布默认不再写 synthetic feedback（仅历史库兜底分支保留）

### 2.3 当前数据库（真实运行结构）

当前为 SQLite 自动建表（`ensure_db`）：

- `users`
- `sessions`
- `feedback`（含 `review_text`）
- `community_posts`（含互动统计与接力字段）
- `community_post_likes`
- `community_post_comments`
- `community_creative_jobs`
  - 关键调度字段：`priority`、`started_at`、`heartbeat_at`、`lease_expires_at`、`cancel_reason`

说明：

- 社区图片文件存储在本地目录（默认 `py/uploads/community`）
- `community_posts.image_path` 存本地路径
- 当前生产代码仍沿用轻量 SQLite，不依赖 MySQL

## 3. 运行与配置

### 3.1 后端运行

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 0.0.0.0 --port 8000
```

### 3.2 环境变量加载顺序

1. 进程环境变量（优先）
2. `py/.env.local`
3. `py/.env`

### 3.3 当前配置分层

- 分析链路：`ARK_*`
- 修图链路：`ARK_IMAGE_*`
- 社区链路：`COMMUNITY_*`
- 社区图像可选覆盖：`COMMUNITY_IMAGE_*`（未配置回退 `ARK_IMAGE_*`）
- 社区创意任务并发：`COMMUNITY_CREATIVE_WORKER_COUNT`（默认 `2`，范围 `1~8`）
- 社区创意任务重试：`COMMUNITY_CREATIVE_MAX_RETRIES`、`COMMUNITY_CREATIVE_RETRY_BASE_SEC`
- 社区假数据默认策略：
  - 开发环境默认开启（便于“朋友圈”联调）
  - 若 `APP_ENV=production` 默认关闭
  - 可通过 `COMMUNITY_SEED_ENABLED` 显式覆盖

## 4. 已知限制（当前代码）

- 仍是 SQLite（未迁移 MySQL）
- 社区审核为“自动发布 + 基础过滤”，没有人工审核后台
- 姿势推荐维度尚未上线（推荐仍以地点/场景为主）
- 融合/共创任务执行器为进程内线程池（并发数可配），尚未接入独立消息队列
- 任务取消为“软取消”：运行中任务会尽快标记取消，但上游请求不可强制中断
- 双人共创是创意融合 MVP，不承诺严格身份保真

## 5. 日志与排障

- 服务端日志：`py/logs/server.log`
- 客户端日志：`files/logs/client.log`
- 请求追踪：`x-request-id` / `req=<id>`

常用排障入口：

- 鉴权：`/auth/*` + `app/core/security.py`
- 社区：`app/services/community_service.py`
- 融合：`providers/image_edit_provider.py`

## 6. 完整数据库设计（作业版，MySQL 8.x）

说明：

- 这一节是“完整数据库设计作业输出”，用于展示正式版结构。
- 实际应用当前仍运行 SQLite（见本文第 2.3 节），MySQL 设计尚未切换上线。
- 以下 DDL 带注释，字段命名与当前代码语义保持一致。

### 6.1 设计目标

- 支持用户与会话管理
- 支持拍摄反馈与社区发帖双通道
- 支持点赞、评论、接力链路
- 支持融合/共创异步任务与结果追踪
- 便于推荐查询与分页读取

### 6.2 MySQL DDL（含注释）

```sql
CREATE DATABASE IF NOT EXISTS cammate
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE cammate;

-- 用户主表
CREATE TABLE users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  email VARCHAR(191) NOT NULL COMMENT '登录邮箱，唯一',
  password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
  password_salt VARCHAR(128) NOT NULL COMMENT '密码盐值',
  nickname VARCHAR(64) NOT NULL COMMENT '展示昵称',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active,0=disabled',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  KEY idx_users_created_at (created_at)
) ENGINE=InnoDB COMMENT='用户表';

-- 会话表（Bearer Token）
CREATE TABLE user_sessions (
  token CHAR(64) NOT NULL COMMENT '会话token主键',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID',
  expires_at DATETIME(3) NOT NULL COMMENT '过期时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  client_ip VARCHAR(45) NULL COMMENT '登录IP',
  user_agent VARCHAR(255) NULL COMMENT '终端UA',
  PRIMARY KEY (token),
  KEY idx_sessions_user_expires (user_id, expires_at),
  KEY idx_sessions_expires (expires_at),
  CONSTRAINT fk_sessions_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB COMMENT='登录会话表';

-- 拍摄反馈表（评分+文字评价）
CREATE TABLE feedback (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '反馈用户',
  rating TINYINT UNSIGNED NOT NULL COMMENT '评分 1..5',
  scene VARCHAR(24) NOT NULL COMMENT '场景类型',
  tip_text VARCHAR(240) NOT NULL DEFAULT '' COMMENT '当次提示文本快照',
  photo_uri VARCHAR(1024) NULL COMMENT '客户端图片URI快照',
  is_retouch TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否修图后提交',
  review_text VARCHAR(280) NOT NULL DEFAULT '' COMMENT '文字评价',
  session_meta JSON NULL COMMENT '会话元数据',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_feedback_user_created (user_id, created_at DESC),
  KEY idx_feedback_scene_created (scene, created_at DESC),
  KEY idx_feedback_rating_created (rating, created_at DESC),
  CONSTRAINT fk_feedback_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB COMMENT='拍摄反馈表';

-- 社区帖子主表（含主动发布/接力）
CREATE TABLE community_posts (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '发帖用户',
  feedback_id BIGINT UNSIGNED NULL COMMENT '可选：关联反馈ID（双通道发布）',
  scene_type VARCHAR(24) NOT NULL COMMENT '帖子场景类型',
  place_tag VARCHAR(64) NOT NULL COMMENT '地点标签',
  rating_snapshot TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '帖子评分快照(0~5)',
  review_text VARCHAR(280) NOT NULL DEFAULT '' COMMENT '帖子评价文本',
  caption VARCHAR(280) NOT NULL DEFAULT '' COMMENT '帖子文案',
  post_type ENUM('normal','relay') NOT NULL DEFAULT 'normal' COMMENT '普通帖/接力帖',
  source_type ENUM('feedback_flow','direct') NOT NULL DEFAULT 'feedback_flow' COMMENT '发布来源',
  relay_parent_post_id BIGINT UNSIGNED NULL COMMENT '接力来源帖子ID',
  style_template_post_id BIGINT UNSIGNED NULL COMMENT '同款模板帖子ID',
  like_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数缓存',
  comment_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数缓存',
  moderation_status ENUM('published', 'hidden', 'deleted', 'pending') NOT NULL DEFAULT 'published',
  visibility ENUM('login_only', 'public') NOT NULL DEFAULT 'login_only',
  published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_post_scene_place_published (scene_type, place_tag, published_at DESC),
  KEY idx_post_published (published_at DESC),
  KEY idx_post_post_type_created (post_type, created_at DESC),
  KEY idx_post_relay_parent (relay_parent_post_id, created_at DESC),
  KEY idx_post_status_visibility (moderation_status, visibility),
  CONSTRAINT fk_post_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT fk_post_feedback
    FOREIGN KEY (feedback_id) REFERENCES feedback(id)
    ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT fk_post_relay_parent
    FOREIGN KEY (relay_parent_post_id) REFERENCES community_posts(id)
    ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT fk_post_style_template
    FOREIGN KEY (style_template_post_id) REFERENCES community_posts(id)
    ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT chk_post_rating_snapshot CHECK (rating_snapshot BETWEEN 0 AND 5)
) ENGINE=InnoDB COMMENT='社区帖子表';

-- 帖子媒体表（支持未来多图/对象存储）
CREATE TABLE community_post_media (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '媒体ID',
  post_id BIGINT UNSIGNED NOT NULL COMMENT '所属帖子',
  storage_provider ENUM('local', 's3', 'oss', 'cos') NOT NULL DEFAULT 'local',
  storage_key VARCHAR(512) NOT NULL COMMENT '存储键或路径',
  mime_type VARCHAR(64) NOT NULL DEFAULT 'image/jpeg',
  width INT UNSIGNED NULL,
  height INT UNSIGNED NULL,
  file_size BIGINT UNSIGNED NULL,
  sha256 CHAR(64) NULL COMMENT '去重校验',
  is_primary TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否主图',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_media_storage (storage_provider, storage_key),
  KEY idx_media_post (post_id),
  KEY idx_media_post_primary (post_id, is_primary),
  CONSTRAINT fk_media_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB COMMENT='帖子媒体表';

-- 点赞明细表（防重复点赞）
CREATE TABLE community_post_likes (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '点赞ID',
  post_id BIGINT UNSIGNED NOT NULL COMMENT '帖子ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '点赞用户',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_like_post_user (post_id, user_id),
  KEY idx_like_post_created (post_id, created_at DESC),
  KEY idx_like_user_created (user_id, created_at DESC),
  CONSTRAINT fk_like_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT fk_like_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB COMMENT='点赞表';

-- 一级评论表（不做楼中楼）
CREATE TABLE community_post_comments (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评论ID',
  post_id BIGINT UNSIGNED NOT NULL COMMENT '帖子ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '评论用户',
  content VARCHAR(280) NOT NULL COMMENT '评论文本',
  status ENUM('normal','deleted') NOT NULL DEFAULT 'normal',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_comment_post_created (post_id, created_at DESC),
  KEY idx_comment_user_created (user_id, created_at DESC),
  CONSTRAINT fk_comment_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT fk_comment_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB COMMENT='评论表（一级评论）';

-- 创意任务表（融合/共创/复刻任务）
CREATE TABLE community_creative_jobs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '任务发起用户',
  job_type ENUM('compose','cocreate','remake_guide') NOT NULL COMMENT '任务类型',
  reference_post_id BIGINT UNSIGNED NOT NULL COMMENT '参考帖子ID',
  status ENUM('queued','running','success','failed') NOT NULL DEFAULT 'queued',
  implementation_status ENUM('ready','placeholder') NOT NULL DEFAULT 'ready',
  progress TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '任务进度 0..100',
  request_payload JSON NOT NULL COMMENT '请求快照',
  result_meta JSON NOT NULL COMMENT '结果元信息',
  result_image_base64 LONGTEXT NULL COMMENT '结果图（轻量版本可直存）',
  compare_input_base64 LONGTEXT NULL COMMENT '对比输入图',
  provider VARCHAR(64) NOT NULL DEFAULT '' COMMENT '模型提供商',
  model VARCHAR(128) NOT NULL DEFAULT '' COMMENT '模型名',
  error_message VARCHAR(512) NOT NULL DEFAULT '' COMMENT '失败信息',
  request_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '链路请求ID',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_job_user_created (user_id, created_at DESC),
  KEY idx_job_type_created (job_type, created_at DESC),
  KEY idx_job_status_updated (status, updated_at DESC),
  CONSTRAINT fk_job_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT fk_job_ref_post
    FOREIGN KEY (reference_post_id) REFERENCES community_posts(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB COMMENT='创意任务表';
```

### 6.3 推荐查询样例（MySQL）

```sql
-- 输入参数：:place_tag, :scene_type, :limit
SELECT
  cp.id,
  cp.user_id,
  cp.scene_type,
  cp.place_tag,
  cp.rating_snapshot,
  cp.published_at,
  CASE
    WHEN cp.place_tag = :place_tag AND cp.scene_type = :scene_type THEN 1.0
    WHEN cp.place_tag = :place_tag OR cp.scene_type = :scene_type THEN 0.7
    ELSE 0.4
  END AS match_score,
  EXP(
    -LN(2) * TIMESTAMPDIFF(SECOND, cp.published_at, NOW(3)) / (30 * 24 * 3600)
  ) AS freshness_score
FROM community_posts cp
WHERE cp.moderation_status = 'published'
ORDER BY
  (0.65 * CASE
    WHEN cp.place_tag = :place_tag AND cp.scene_type = :scene_type THEN 1.0
    WHEN cp.place_tag = :place_tag OR cp.scene_type = :scene_type THEN 0.7
    ELSE 0.4 END)
  + (0.25 * (cp.rating_snapshot / 5.0))
  + (0.10 * EXP(-LN(2) * TIMESTAMPDIFF(SECOND, cp.published_at, NOW(3)) / (30 * 24 * 3600)))
DESC,
cp.published_at DESC
LIMIT :limit;
```

## 7. 文档边界

- `README.md`：入口与导航
- `DEVELOPMENT.md`（本文）：当前实现 + 作业版完整数据库设计
- `DESIGN.md`：计划中与未实现路线
