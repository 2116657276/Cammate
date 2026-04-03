# Cammate

Cammate 是一个面向拍摄辅助与社区演示的 `Android + FastAPI` 项目。当前仓库重点是把“能完整演示”的主链路跑通：用户登录后，可以进入拍摄、社区、设置三个底部分栏，完成拍摄辅助、社区浏览、社区发帖、姿势参考、AI 融合与基础账号管理。

当前仓库文档分工如下：

- `README.md`：项目入口、目录结构、快速启动、演示约定、技术选型结论
- `DEVELOPMENT.md`：开发进度、实现细节、运行手册、日志与排障、后续计划

## 1. 当前演示范围

当前演示版本已经收敛为三个底部分栏：

- 拍摄：辅助拍摄、姿势推荐、AI 融合
- 社区：朋友圈动态流，右上角进入“发布动态”和“玩法入口”
- 设置：账号信息与退出登录

当前可演示的主链路：

`注册 / 登录 -> 进入拍摄页 -> 场景识别与拍摄辅助 -> 拍照 -> 修图 / 评分 -> 可选发布到社区 -> 浏览朋友圈 -> 点赞 / 评论 / 姿势参考 / AI 融合 -> 退出登录`

## 2. 主要能力

### 客户端

- Jetpack Compose 页面与状态管理
- CameraX 拍摄与预览
- 登录态持久化与服务端地址持久化
- 场景识别与 AI 构图建议
- 修图结果预览与保存
- 社区动态流、点赞、评论、详情页操作
- 社区独立发帖页与玩法入口页
- 姿势推荐与 AI 融合独立页面
- 网络图片鉴权加载
- 客户端文件日志与精简 logcat 输出

### 服务端

- FastAPI 鉴权、拍摄分析、修图、反馈、社区接口
- SQLite 数据层与自动迁移
- 社区演示 seed 数据
- 社区推荐、点赞、评论、举报、审核动作
- AI 融合 / 双人共创异步任务
- Redis 队列与健康指标
- 本地文件结果存储，兼容 MinIO / S3 抽象
- 精简终端日志与完整文件日志

## 3. 目录结构

- `kotlin/`：Android 客户端代码
- `py/`：FastAPI 服务端、脚本、模型、数据库、演示素材
- `py/app/`：服务端业务代码
- `py/scripts/`：启动、seed、恢复、联调、告警脚本
- `py/demo_assets/community_seed/`：社区演示图片与 `manifest.json`
- `py/demo_app_data.db`：默认演示数据库
- `py/app_data.db`：本地实验数据库，不作为演示默认库
- `Pictures/`：本地原始演示素材来源目录

## 4. 默认运行约定

当前项目统一口径如下：

- 服务端端口固定使用 `8010`
- Android 模拟器默认访问 `http://10.0.2.2:8010`
- 真机默认访问 `http://<你的局域网 IP>:8010`
- 演示数据库固定使用 `py/demo_app_data.db`
- Redis 默认地址为 `redis://127.0.0.1:6379/0`
- 对象存储默认可对接 `http://127.0.0.1:9000`

补充说明：

- `py/app_data.db` 里可能保留旧联调数据或旧占位图，不建议作为演示默认库
- 客户端历史默认地址 `8000` 已迁移到 `8010`
- 社区演示图片以 `py/demo_assets/community_seed/` 和 `py/demo_app_data.db` 为准

## 5. 快速启动

### 5.1 安装依赖

```bash
mamba run -n Cam python -m pip install -r py/requirements.txt
```

### 5.2 启动 API

推荐统一使用下面这条命令：

```bash
mamba run -n Cam python py/scripts/run_api_server.py
```

这个脚本会默认：

- 使用 `py/demo_app_data.db`
- 监听 `8010`
- 如果发现 `8010` 已被旧服务占用，会先终止旧进程，再自动重启新服务

如果你只想检查服务是否正常：

```bash
curl http://127.0.0.1:8010/healthz
```

### 5.3 重建演示数据

如果你更新了 `Pictures/` 或 `manifest.json`，可以重建社区演示库：

```bash
mamba run -n Cam python py/scripts/build_demo_seed.py
mamba run -n Cam python py/scripts/run_api_server.py
```

### 5.4 启动创意任务 worker

如果需要演示异步 AI 融合 / 双人共创任务，建议单独启动 worker：

```bash
mamba run -n Cam python py/scripts/run_api_server.py
PYTHONPATH=py APP_DB_PATH=py/demo_app_data.db mamba run -n Cam python py/scripts/run_creative_worker.py
```

如果只是最小演示，也可以先不启 worker，但对应异步任务能力会受限。

### 5.5 运行 Android 客户端

1. 用 Android Studio 打开 `kotlin/`
2. 等待 Gradle Sync 完成
3. 运行 `app`
4. 在设置页确认服务端地址

常用地址：

- 模拟器：`http://10.0.2.2:8010`
- 真机：`http://<你的局域网 IP>:8010`

## 6. 日志与排障入口

### 服务端日志

- 终端：只保留启动信息、警告、错误、慢请求等核心输出
- 文件：完整日志写入 `py/logs/server.log`

### 客户端日志

- `logcat` 默认保留更关键的日志级别
- 客户端文件日志写入应用私有目录下的 `client.log`

### 常用排查顺序

1. 先看 `curl http://127.0.0.1:8010/healthz`
2. 再看 `py/logs/server.log`
3. 再看 Android `adb logcat`
4. 确认客户端连接地址是否仍然是旧端口或错误 IP
5. 确认服务端当前连接的是不是 `py/demo_app_data.db`

## 7. 当前技术选型结论

### SQLite

当前阶段继续使用 SQLite 是合理的：

- 单机演示与本地联调足够稳定
- 已启用 `WAL`、`busy_timeout` 等基础配置
- 数据模型和脚本仍然围绕 SQLite 设计

什么时候再考虑 MySQL：

- 多实例部署
- 更高并发写入
- 需要更强的后台运营与分析支撑

### Redis

当前建议保留 Redis：

- 创意任务已经做成异步队列
- 有 worker、重试、租约、心跳、健康指标
- 比进程内队列更适合后续扩展

### MinIO

当前建议保留抽象，但不强依赖：

- 单机演示时，本地文件存储就足够
- 多机部署、对象统一管理、结果持久化时，MinIO / S3 更合适

当前最实际的组合是：

`SQLite + Redis + 本地文件`

如果后续更接近生产，再升级到：

`SQLite / MySQL + Redis + MinIO / S3`

## 8. 当前重点与已知边界

当前已经比较适合演示，但仍然是“演示优先”的工程状态：

- 社区内容和推荐基于演示数据与规则，不是完整生产策略
- AI 融合 / 双人共创支持异步任务，但部分场景仍有 fallback 逻辑
- 发布动态目前以“单图发布 + 可选地点 / 场景 / 文案”为主
- 设置页已经刻意简化，只保留账号相关功能
- UI 后续还计划继续升级，但当前阶段先以功能完整性和稳定性为主

## 9. 进一步阅读

更详细的开发进度、运行细节、环境变量、恢复脚本和阶段计划见 `DEVELOPMENT.md`。
