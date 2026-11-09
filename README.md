# Cammate

Android Compose + FastAPI 摄影辅助原型，包含场景/人脸检测、构图建议、修图、反馈、社区与异步图片融合。

本仓库维护代码、配置模板与精简运行入口。完整架构、期中验收、开发计划和状态维护在 [SRTP](https://github.com/2116657276/SRTP)；本地相邻目录为 `../SRTP/`，实际进度以其中的 `docs/06-current-state.md` 为准。

## 环境与目录

- 服务端：uv、Python 3.12；依赖为 [py/requirements.txt](py/requirements.txt)。
- 客户端：JDK 21、Android SDK Platform 36；Android Studio 打开 `kotlin/`。
- 本机 API 端口：`8010`；模拟器使用 `http://10.0.2.2:8010`，真机使用电脑局域网 IP。
- 演示库：`py/demo_app_data.db`；模型位于 `py/models/`，供给、许可和 SHA256 随交付版本记录。

## 服务端启动

以下命令从 Cammate 根目录执行。已有 `.venv` 可跳过创建；已有私有配置只补缺项，不覆盖。

```bash
uv venv .venv --python 3.12
uv pip install --python .venv/bin/python -r py/requirements.txt
cp -n py/.env.example py/.env.local
chmod 600 py/.env.local
```

编辑 `py/.env.local` 后启动 API：

```bash
.venv/bin/python py/scripts/run_api_server.py --no-restart
```

另开终端检查本地健康状态：

```bash
curl http://127.0.0.1:8010/healthz
```

## 云端配置

配置加载顺序是进程环境变量 → `py/.env.local` → `py/.env`。修改后重启 API，以及独立运行的 worker。真实 Key 只放本地私有配置，不写进 README、APK 或 Git。

| 功能 | 配置 | 说明 |
| --- | --- | --- |
| 看图、构图建议 | `ARK_API_KEY`、`ARK_MODEL`、`ARK_CHAT_API_URL` | 沿用变量名，接口支持 OpenAI 兼容协议；当前使用 DeepSeek 官方 Flash |
| 图片编辑 | `ARK_IMAGE_API_KEY`、`ARK_IMAGE_MODEL`、`ARK_IMAGE_API_URL` | 使用独立图片编辑服务，不能把 DeepSeek 看图 Key 转发给其他厂商 |
| 融合、双人共创 | `COMMUNITY_IMAGE_*` | 可单独配置；未设置时沿用 `ARK_IMAGE_*` |

DeepSeek 官方看图配置已写入 [py/.env.example](py/.env.example)，模型为 `deepseek-flash`，`ARK_REASONING_EFFORT=none` 关闭思考。该模型接入不代表图片编辑或融合已具备真实云端成功能力。接口格式见 [DeepSeek 官方视觉文档](https://api-docs.deepseek.com/guides/vision)。

默认先离线核对配置，真实云端只做必要的单次连通与总用时记录，不自动批量付费压测。首字延迟、分位数和完整应用链路须分别记录，不能用一次官方请求代替全部验收。

## Redis 与 worker

融合/双人共创需要 Redis 和 worker。Redis 队列在开发模式下会启用内嵌 worker，仅设 `CREATIVE_EMBEDDED_WORKER=false` 不会关闭它；需要独立进程时同时设置 `APP_ENV=production`，再用以下命令分别启动 API 与 worker，避免重复启动。

```bash
# 终端一，Cammate 根目录
APP_ENV=production CREATIVE_EMBEDDED_WORKER=false \
  .venv/bin/python py/scripts/run_api_server.py --no-restart
```

```bash
# 终端二，Cammate 根目录
PYTHONPATH=py APP_DB_PATH=py/demo_app_data.db \
  .venv/bin/python py/scripts/run_creative_worker.py
```

未配置图片编辑服务时可能返回明确标注的本地融合预览；这不算真实云端生成验收通过。

## Android 构建与连接

在 `kotlin/local.properties` 配置服务端地址；真机需替换为电脑的局域网 IP。地址会编入 APK，修改后重新构建。

```properties
cammate.server.url=http://10.0.2.2:8010
```

```bash
cd kotlin
./gradlew :app:assembleDebug
```

APK 输出为 `kotlin/app/build/outputs/apk/debug/app-debug.apk`。安装后依次验证登录、相机权限、拍照和分析，不以构建成功代替设备验收。

## 离线回归与证据

以下服务端和 JVM 回归使用隔离数据库、模型/外部服务替身或本机测试 HTTP 服务，不调用真实云端付费模型。

```bash
# Cammate 根目录
PYTHONPATH=py .venv/bin/python -m unittest discover -s py/tests -p 'test_server_repairs.py' -v
cd kotlin
./gradlew :app:testDebugUnitTest
```

原始日志、截图、测试照片、凭据与运行数据库仅保留在本地忽略目录。`runs/` 中的证据不随代码提交；完整验收状态见 SRTP，不在本仓库另维护一套进度表。

本轮修复覆盖删帖数据归属、原图 TTL、图片输入边界、NDJSON 错误事件、会话/照片请求隔离、任务入队和查询恢复、协程取消、相机坐标。真机、真实图片编辑/融合、全量功能、稳定性及独立复现仍按 SRTP 验收协议执行；不据此宣称性能提速或模型准确率提升。
