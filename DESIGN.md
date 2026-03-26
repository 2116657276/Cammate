# Cammate 设计文档（最终版目标）

本文档描述最终版要做什么、为什么这样做，以及未来补充路线。  
当前已实现现状请看 [`DEVELOPMENT.md`](./DEVELOPMENT.md)。

## 1. 设计目标

### 1.1 产品目标

把 Cammate 打造成“拍摄指导 + 社区灵感 + AI 创意融合”的一体化产品：

- 拍前：AI 构图指导提升出片率
- 拍后：修图与反馈闭环
- 发布后：社区沉淀真实素材与经验
- 再创作：用户基于社区参考图做 AI 融合

### 1.2 最终版体验目标

- UI：高级浅色玻璃风，统一视觉系统、动效、层级和交互语言
- 社区：拍摄后评分页可选一键发布，形成内容增长飞轮
- 推荐：基于地点与风景类型返回可解释推荐结果
- 融合：用户上传半身/全身照，选参考图后一键生成融合图

## 2. 最终版范围（Final Scope）

### 2.1 客户端最终能力

- 全局 UI 重构：
  - 浅色高级主题
  - 玻璃卡片/渐变背景
  - 统一圆角、阴影与字体系
  - 页面入场、卡片错峰、按钮状态过渡
- 评分页：
  - 星级评分
  - 文字评价（可选）
  - 发布到社区开关（可选）
  - 地点标签 + 风景类型选择
  - 固定流程：`submitFeedback -> publishPost(可选)`
- 社区页：
  - 社区流（分页）
  - 推荐（地点/风景类型筛选）
  - AI 融合（人物图上传、参考图选择、强度控制、预览与保存）

### 2.2 服务端最终能力

- 反馈能力：
  - `feedback` 增加 `review_text` 字段（0~280）
- 社区能力：
  - 发布、分页流、推荐、图片鉴权读取
  - 自动发布 + 基础过滤（文本长度/敏感词/图片可解码/尺寸上限）
- 融合能力：
  - 使用与修图同源图像模型能力
  - 支持配置覆盖与回退

### 2.3 V1 非目标（明确不做）

- 姿势推荐（二期）
- 人工审核后台（V1 不引入）
- 严格身份保真承诺（融合能力为创意融合 MVP）

## 3. 最终架构设计

### 3.1 分层架构

- Android（Jetpack Compose + CameraX）：
  - 页面与状态管理
  - 鉴权会话
  - 拍照、修图、发布、推荐、融合交互
- FastAPI：
  - 鉴权与会话
  - 场景识别 + 构图分析代理
  - 修图、社区、推荐、融合服务
- AI Provider：
  - 分析模型（`ARK_*`）
  - 图像模型（`ARK_IMAGE_*` / `COMMUNITY_IMAGE_*`）
- 存储：
  - V1 开发：SQLite + 本地文件
  - 生产目标：MySQL + 对象存储

### 3.2 配置策略（最终要求）

- 默认复用修图图像模型：
  - `COMMUNITY_IMAGE_*` 未配置时，回退 `ARK_IMAGE_*`
- 必备配置：
  - `COMMUNITY_UPLOAD_DIR`
  - `COMMUNITY_BLOCKED_WORDS`
  - `COMMUNITY_UPLOAD_MAX_SIDE`
  - `COMMUNITY_RECOMMEND_LIMIT_DEFAULT`

## 4. 最终 API 设计

### 4.1 反馈与社区接口

- `POST /feedback`
  - 新增：`review_text`
- `POST /community/posts`
  - 入参：`feedback_id + image_base64 + place_tag + scene_type`
- `POST /community/posts/direct`
  - 入参：`image_base64 + place_tag + scene_type (+ caption/review/rating 可选)`
  - 说明：主动发布不强依赖 `feedback_id`
- `GET /community/feed?offset&limit`
- `GET /community/recommendations?place_tag&scene_type&limit`
  - 返回：`score`、`reason`
- `GET /community/posts/{post_id}/image`
  - 鉴权后返回图片二进制
- `POST /community/compose`
  - 入参：`reference_post_id + person_image_base64 + strength`
  - 返回：`composed_image_base64/provider/model`

### 4.2 推荐算法（最终规则）

- `final_score = 0.65*match + 0.25*(rating/5) + 0.10*freshness`
- `match`：
  - 地点+类型全匹配 = `1.0`
  - 单维匹配 = `0.7`
  - 无匹配兜底 = `0.4`
- `freshness`：
  - 30 天半衰（指数衰减）
- 回退：
  - 全匹配 -> 单维匹配 -> 全局热门

## 5. 验收标准（最终版）

- 从拍照到“评分+可选发布”全链路走通
- 社区推荐可稳定返回匹配结果与推荐理由
- 融合能力 60s 内返回结果或明确失败原因
- 推荐/融合配置回退生效（仅有 `ARK_IMAGE_*` 也可运行）

## 6. MySQL 完整数据库设计（最终构想）

说明：

- 当前代码运行仍为 SQLite，以下为生产目标数据库设计（MySQL 8.x）。
- 该设计覆盖用户、会话、反馈、社区内容、媒体、融合任务与基础审核词库。

### 6.1 ER 关系（文字版）

- `users` 1:N `user_sessions`
- `users` 1:N `feedback`
- `users` 1:N `community_posts`
- `feedback` 1:0..1 `community_posts`
- `community_posts` 1:N `community_post_media`
- `users` 1:N `community_compose_jobs`
- `community_posts` 1:N `community_compose_jobs`

### 6.2 DDL（完整示例）

```sql
CREATE DATABASE IF NOT EXISTS cammate
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE cammate;

CREATE TABLE users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email VARCHAR(191) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  password_salt VARCHAR(128) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active,0=disabled',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  KEY idx_users_created_at (created_at)
) ENGINE=InnoDB;

CREATE TABLE user_sessions (
  token CHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  client_ip VARCHAR(45) NULL,
  user_agent VARCHAR(255) NULL,
  PRIMARY KEY (token),
  KEY idx_sessions_user_expires (user_id, expires_at),
  KEY idx_sessions_expires (expires_at),
  CONSTRAINT fk_sessions_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE feedback (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  rating TINYINT UNSIGNED NOT NULL COMMENT '1..5',
  scene VARCHAR(24) NOT NULL,
  tip_text VARCHAR(240) NOT NULL DEFAULT '',
  photo_uri VARCHAR(1024) NULL,
  is_retouch TINYINT(1) NOT NULL DEFAULT 0,
  review_text VARCHAR(280) NOT NULL DEFAULT '',
  session_meta JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_feedback_user_created (user_id, created_at DESC),
  KEY idx_feedback_scene_created (scene, created_at DESC),
  KEY idx_feedback_rating_created (rating, created_at DESC),
  CONSTRAINT fk_feedback_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB;

CREATE TABLE community_posts (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  feedback_id BIGINT UNSIGNED NULL,
  scene_type VARCHAR(24) NOT NULL,
  place_tag VARCHAR(64) NOT NULL,
  rating_snapshot TINYINT UNSIGNED NOT NULL,
  review_text VARCHAR(280) NOT NULL DEFAULT '',
  moderation_status ENUM('published', 'hidden', 'deleted', 'pending') NOT NULL DEFAULT 'published',
  visibility ENUM('login_only', 'public') NOT NULL DEFAULT 'login_only',
  published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_post_feedback (feedback_id),
  KEY idx_post_scene_place_published (scene_type, place_tag, published_at DESC),
  KEY idx_post_published (published_at DESC),
  KEY idx_post_status_visibility (moderation_status, visibility),
  CONSTRAINT fk_post_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT fk_post_feedback
    FOREIGN KEY (feedback_id) REFERENCES feedback(id)
    ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT chk_post_rating CHECK (rating_snapshot BETWEEN 1 AND 5)
) ENGINE=InnoDB;

CREATE TABLE community_post_media (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  post_id BIGINT UNSIGNED NOT NULL,
  storage_provider ENUM('local', 's3', 'oss', 'cos') NOT NULL DEFAULT 'local',
  storage_key VARCHAR(512) NOT NULL,
  mime_type VARCHAR(64) NOT NULL DEFAULT 'image/jpeg',
  width INT UNSIGNED NULL,
  height INT UNSIGNED NULL,
  file_size BIGINT UNSIGNED NULL,
  sha256 CHAR(64) NULL,
  is_primary TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_media_storage (storage_provider, storage_key),
  KEY idx_media_post (post_id),
  KEY idx_media_post_primary (post_id, is_primary),
  CONSTRAINT fk_media_post
    FOREIGN KEY (post_id) REFERENCES community_posts(id)
    ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE community_compose_jobs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  reference_post_id BIGINT UNSIGNED NOT NULL,
  person_media_id BIGINT UNSIGNED NULL,
  merged_input_key VARCHAR(512) NULL,
  output_media_key VARCHAR(512) NULL,
  status ENUM('queued', 'running', 'success', 'failed') NOT NULL DEFAULT 'queued',
  provider VARCHAR(32) NOT NULL DEFAULT 'doubao',
  model VARCHAR(128) NOT NULL DEFAULT '',
  strength DECIMAL(4,3) NOT NULL DEFAULT 0.450,
  request_id VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  KEY idx_compose_user_created (user_id, created_at DESC),
  KEY idx_compose_post_created (reference_post_id, created_at DESC),
  KEY idx_compose_status_created (status, created_at DESC),
  CONSTRAINT fk_compose_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT fk_compose_post
    FOREIGN KEY (reference_post_id) REFERENCES community_posts(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_compose_person_media
    FOREIGN KEY (person_media_id) REFERENCES community_post_media(id)
    ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE moderation_blocklist (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  bad_word VARCHAR(64) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_block_bad_word (bad_word)
) ENGINE=InnoDB;
```

### 6.3 推荐排序 SQL 示例

```sql
-- 入参：:place_tag, :scene_type, :limit
SELECT
  cp.id,
  cp.user_id,
  u.nickname AS user_nickname,
  cp.scene_type,
  cp.place_tag,
  cp.rating_snapshot,
  cp.review_text,
  cp.published_at,
  CASE
    WHEN cp.place_tag = :place_tag AND cp.scene_type = :scene_type THEN 1.0
    WHEN cp.place_tag = :place_tag OR cp.scene_type = :scene_type THEN 0.7
    ELSE 0.4
  END AS match_score,
  EXP(
    -LN(2) *
    TIMESTAMPDIFF(SECOND, cp.published_at, NOW(3)) / (30 * 24 * 3600)
  ) AS freshness_score,
  (
    0.65 * (CASE
      WHEN cp.place_tag = :place_tag AND cp.scene_type = :scene_type THEN 1.0
      WHEN cp.place_tag = :place_tag OR cp.scene_type = :scene_type THEN 0.7
      ELSE 0.4 END)
    + 0.25 * (cp.rating_snapshot / 5.0)
    + 0.10 * EXP(-LN(2) * TIMESTAMPDIFF(SECOND, cp.published_at, NOW(3)) / (30 * 24 * 3600))
  ) AS final_score
FROM community_posts cp
JOIN users u ON u.id = cp.user_id
WHERE cp.moderation_status = 'published'
  AND cp.visibility = 'login_only'
ORDER BY final_score DESC, cp.published_at DESC
LIMIT :limit;
```

### 6.4 迁移策略（SQLite -> MySQL）

1. 冻结写流量（或双写）
2. 导出 SQLite 中间数据（CSV/JSON）
3. 按主键顺序导入 MySQL
4. 校验总量与抽样内容
5. 切换读流量并观察错误率与慢查询

## 7. 计划中 / 未实现功能（Roadmap）

本节只列“尚未完成或正在规划”的能力，便于和开发文档区分。

### 7.1 当前未完成项（截至 2026-03-26）

- 创意任务仍是“进程内 worker + SQLite”轻量架构，未引入外部队列与独立调度器
- 运行中任务仍为软取消（无法强制中断外部 AI 请求）
- 同款复刻的人体关键点匹配与实时姿态纠偏
- 双人共创精细遮罩与几何一致性增强
- 姿势维度推荐（当前推荐以地点/场景为主）
- 社区举报和多级审核后台
- 对象存储替换本地文件（当前为本地文件路径）

### 7.2 迭代优先顺序（按用户价值）

1. `P0` 稳定体验层  
   创意任务从“轻量稳定”继续升级到“可运维稳定”（队列外置、可观测性细化）
2. `P1` 复刻质量层  
   同款复刻加入姿态/构图实时纠偏（关键点匹配）
3. `P2` 共创质量层  
   双人共创精细遮罩、边缘质量和双人透视一致性
4. `P3` 社区增长层  
   接力活动化、榜单、举报审核与个性化推荐权重

### 7.3 V1.2（已落地重点 + 收尾）

- 已落地：
  - `creative_jobs` 任务化链路（创建/轮询/重试/取消）
  - 轻量调度字段：`priority/started_at/heartbeat_at/lease_expires_at/cancel_reason`
  - 超时与租约恢复：遗留 `running` 任务自动纠正并重试/失败
  - `healthz` 增加 `creative_queue` 诊断指标
  - 客户端移除断网本地 mock 动态注入，社区流以服务端数据为准
- 收尾项：
  - 结果存储从 `base64` 向对象存储迁移（S3/OSS/COS）

### 7.4 V1.3（中长期目标）

- 多级审核：
  - 文本审核
  - 图像审核
  - 风险分级与自动下线
- 推荐系统升级：
  - 个性化权重
  - 互动信号（点赞/评论/停留）

### 7.5 V2（远期）

- 跨设备素材同步
- 作品编辑历史与版本管理
- 专业摄影模板市场与运营位

## 8. 文档约束

- `README.md`：入口与导航，不承载详细设计
- `DEVELOPMENT.md`：只写当前已实现能力
- `DESIGN.md`（本文件）：只写最终版目标与未来补充
