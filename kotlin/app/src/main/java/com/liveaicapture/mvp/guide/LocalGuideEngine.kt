package com.liveaicapture.mvp.guide

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.liveaicapture.mvp.data.AnalyzeEvent
import com.liveaicapture.mvp.data.SceneType
import kotlin.math.abs

/**
 * Local guidance engine after removing style control.
 * Guidance is driven by detected scene type only.
 */
class LocalGuideEngine {

    fun analyze(
        sceneType: SceneType,
        nowMs: Long,
        currentExposureCompensation: Int,
    ): LocalGuideResult {
        val template = sceneTemplate(sceneType)
        val slot = ((nowMs / 3500L) % 3).toInt()
        val hintBody = when (slot) {
            0 -> template.primaryHint
            1 -> template.secondaryHint
            else -> template.actionHint
        }

        val exposureSuggestion = recommendExposure(
            sceneType = sceneType,
            current = currentExposureCompensation,
        )

        val events = buildList {
            add(AnalyzeEvent.Strategy(grid = template.grid, targetPoint = template.targetPointNorm))
            if (template.bboxNorm != null || template.centerNorm != null) {
                add(AnalyzeEvent.Target(bbox = template.bboxNorm, center = template.centerNorm))
            }
            add(AnalyzeEvent.Ui(text = hintBody, level = "info"))
            add(AnalyzeEvent.Param(exposureCompensation = exposureSuggestion))
            add(AnalyzeEvent.Done)
        }

        val debugLine =
            "{" +
                "\"provider\":\"local\"," +
                "\"scene\":\"${sceneType.raw}\"," +
                "\"grid\":\"${template.grid}\"," +
                "\"ev\":$exposureSuggestion" +
                "}"

        return LocalGuideResult(events = events, debugLine = debugLine)
    }

    private fun sceneTemplate(sceneType: SceneType): SceneTemplate {
        return when (sceneType) {
            SceneType.PORTRAIT -> SceneTemplate(
                grid = "center",
                targetPointNorm = Offset(0.50f, 0.40f),
                bboxNorm = Rect(0.30f, 0.18f, 0.70f, 0.84f),
                centerNorm = Offset(0.50f, 0.51f),
                primaryHint = "把人物眼睛放在画面上三分之一附近。",
                secondaryHint = "人物和背景分离一点，避免头顶贴边。",
                actionHint = "微微侧身，肩线不要完全平行画面。",
            )

            SceneType.FOOD -> SceneTemplate(
                grid = "thirds",
                targetPointNorm = Offset(0.50f, 0.62f),
                bboxNorm = Rect(0.18f, 0.34f, 0.82f, 0.90f),
                centerNorm = Offset(0.50f, 0.62f),
                primaryHint = "餐盘中心对准下三分线，避免桌面杂物。",
                secondaryHint = "先拍整体，再靠近拍局部纹理。",
                actionHint = "稍微提高亮度，让食材层次更干净。",
            )

            SceneType.NIGHT -> SceneTemplate(
                grid = "thirds",
                targetPointNorm = Offset(0.50f, 0.45f),
                bboxNorm = null,
                centerNorm = null,
                primaryHint = "优先找主光源，再安排主体位置。",
                secondaryHint = "手稳一点，避免高光拖影。",
                actionHint = "曝光宁可略低，也不要过曝丢细节。",
            )

            SceneType.LANDSCAPE -> SceneTemplate(
                grid = "thirds",
                targetPointNorm = Offset(0.50f, 0.38f),
                bboxNorm = null,
                centerNorm = null,
                primaryHint = "地平线放在上三分线或下三分线，不要居中。",
                secondaryHint = "把前景元素带进来，画面会更有纵深。",
                actionHint = "保持水平，避免倾斜导致景物变形。",
            )

            SceneType.GENERAL -> SceneTemplate(
                grid = "thirds",
                targetPointNorm = Offset(0.66f, 0.52f),
                bboxNorm = null,
                centerNorm = null,
                primaryHint = "主体靠右三分点，给运动方向留空间。",
                secondaryHint = "压低机位一点，让前景和背景形成层次。",
                actionHint = "等待主体进入主光区，再按快门。",
            )
        }
    }

    private fun recommendExposure(
        sceneType: SceneType,
        current: Int,
    ): Int {
        val target = when (sceneType) {
            SceneType.PORTRAIT -> 0
            SceneType.GENERAL -> -1
            SceneType.LANDSCAPE -> 0
            SceneType.FOOD -> 1
            SceneType.NIGHT -> -1
        }
        return if (abs(target - current) <= 1) target else current + if (target > current) 1 else -1
    }
}

data class LocalGuideResult(
    val events: List<AnalyzeEvent>,
    val debugLine: String,
)

private data class SceneTemplate(
    val grid: String,
    val targetPointNorm: Offset,
    val bboxNorm: Rect?,
    val centerNorm: Offset?,
    val primaryHint: String,
    val secondaryHint: String,
    val actionHint: String,
)
