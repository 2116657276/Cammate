# Cammate 测试文档

本文档记录当前代码基线的真实测试结果与可复现步骤。

## 1. 测试环境与前置条件

- 测试日期：2026-03-22
- 代码分支：`main`
- Python 环境：`mamba` 环境 `Cam`
- Android 构建：Gradle（`kotlin/`）
- 网络要求：可访问 `ark.cn-beijing.volces.com` 与图片下载地址（TOS）
- 配置要求：
  - `py/.env.local` 中已配置分析链路 `ARK_API_KEY`
  - `py/.env.local` 中已配置修图链路 `ARK_IMAGE_API_KEY`

## 2. 已执行验证与真实结果

### 2.1 构建与静态验证

1. Android 编译：

```bash
cd kotlin
./gradlew :app:compileDebugKotlin
```

结果：`BUILD SUCCESSFUL`

2. Python 编译检查：

```bash
cd py
mamba run -n Cam python -m compileall .
```

结果：通过（无语法错误）

3. 未使用代码清理校验：

```bash
rg -n "LocalGuideEngine|def _response_schema\\(" kotlin/app/src/main/java py/providers/external_provider.py -S
```

结果：无匹配（已移除）

### 2.2 接口连通与流程验证

执行项：

- `GET /healthz`
- `POST /auth/login`
- `GET /auth/me`
- `POST /scene/detect`
- `POST /analyze`
- `POST /retouch`（连续 3 次）

实际结果摘要（本次实测）：

- `healthz`：`200`，`9ms`
- `auth/login`：`200`，`24ms`
- `auth/me`：`200`，`5ms`
- `scene/detect`：`200`，`2206ms`，场景=`portrait`，置信度=`0.9003`
- `analyze`：`200`，`3718ms`，事件数=`5`
- `retouch_1_portrait_beauty`：`200`，`25183ms`
- `retouch_2_bg_cleanup`：`200`，`23691ms`
- `retouch_3_custom_face`：`200`，`26826ms`

### 2.3 修图产物（3 次）

本次自动化测试产物目录：

- `/tmp/cammate_fulltest_20260322_200356`

关键文件：

- 测试报告：`/tmp/cammate_fulltest_20260322_200356/report.json`
- 对比图：`/tmp/cammate_fulltest_20260322_200356/comparison_sheet.jpg`
- 三次修图输出：
  - `/tmp/cammate_fulltest_20260322_200356/retouch_1_portrait_beauty.jpg`
  - `/tmp/cammate_fulltest_20260322_200356/retouch_2_bg_cleanup.jpg`
  - `/tmp/cammate_fulltest_20260322_200356/retouch_3_custom_face.jpg`

哈希（用于复核一致性）：

- `comparison_sheet.jpg`：`ec3c1274d745b2d1475b3ce479a86a33`
- `retouch_1_portrait_beauty.jpg`：`02ed70e8941f11348c0b2d91f41279f3`
- `retouch_2_bg_cleanup.jpg`：`2b31ea271e708ad266d537bc6cf6c9cd`
- `retouch_3_custom_face.jpg`：`a5cf07772e7fae9b05c3764ae0adda15`

## 3. 手工回归清单（待每次发布前执行）

- 登录页：注册、登录、错误提示（密码不足/鉴权失败）
- 相机页：前后摄切换、闪光、焦距、场景识别与主体框
- AI 分析：稳定/不稳定画面下建议更新、移动箭头方向
- 修图页：模板模式、自定义模式、失败重试、继续修图/完成
- 反馈页：评分提交、`is_retouch` 与 `retouch_preset` 上报
- 设置页：服务地址、分析间隔、语音与调试开关持久化

## 4. 已知限制与风险

- 当前仓库无 `pytest` 用例（`pytest -q` 返回 `no tests ran`）
- 云端接口耗时存在波动，`analyze` 与 `retouch` 延迟受网络和上游负载影响
- 修图输出受模型随机性影响，连续请求结果存在合理差异

## 5. 复现命令（最小集）

```bash
# 1) 启动后端
mamba run -n Cam python -m uvicorn main:app --app-dir py --host 127.0.0.1 --port 8000

# 2) 编译检查
cd kotlin && ./gradlew :app:compileDebugKotlin
cd ../py && mamba run -n Cam python -m compileall .

# 3) API 基础连通
curl http://127.0.0.1:8000/healthz
```
