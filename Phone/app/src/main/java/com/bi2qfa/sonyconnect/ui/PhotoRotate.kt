package com.bi2qfa.sonyconnect.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 预览器共用的旋转几何与位图变换（**文件页大预览**与**传输页查看器**两处共用）。
 *
 * 旋转的实现思路（两处必须一致，否则同一张图在两个入口转出来的手感不一样）：
 * 1. 先转**视觉**：给图层一个平滑的 `rotationZ` 动画；
 * 2. 同时**连续适配尺寸**：Fit 是按未旋转位图算的，光转角度会在动画结束、
 *    烘进位图的那一刻突然跳一下尺寸 —— 所以要按当前角度算出旋转后的外接框，
 *    再求"塞进容器"所需的额外缩放，让尺寸跟角度走同一个时钟；
 * 3. 动画结束**烘进位图**（真的转过来）并把视觉角度归零 —— 两者等价，无缝。
 */
object PhotoRotate {

    /** 一步转多少度。用户定版：按钮 = 逆时针 90°。 */
    const val STEP_DEG = -90f

    /** 单步动画时长（毫秒）。与用户定版的节奏一致。 */
    const val STEP_MS = 260

    /**
     * 旋转 [angleDeg] 度时，为把图完整塞进容器所需的**额外缩放**（1f = 不用缩）。
     *
     * 角度为 0 时恒为 1f；位图或容器尺寸未就绪时也是 1f。
     */
    fun fit(bmp: ImageBitmap?, box: IntSize, angleDeg: Float): Float {
        if (bmp == null || box.width <= 0 || box.height <= 0) return 1f
        if (angleDeg % 360f == 0f) return 1f
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return 1f
        val boxW = box.width.toFloat()
        val boxH = box.height.toFloat()
        // Fit 之后的实际显示尺寸
        val base = minOf(boxW / bw, boxH / bh)
        val fitW = bw * base
        val fitH = bh * base
        // 旋转后的外接框（绝对值的 cos/sin 对任意角度都成立）
        val rad = Math.toRadians(angleDeg.toDouble())
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val rotW = fitW * c + fitH * s
        val rotH = fitW * s + fitH * c
        if (rotW <= 0f || rotH <= 0f) return 1f
        return minOf(boxW / rotW, boxH / rotH)
    }

    /**
     * 把已解码的位图真的转过去（"烘焙"），返回新位图。
     *
     * 只用于 ±90 的整数倍；失败时原样返回（宁可没转，也不能让图消失）。
     */
    fun rotate(src: ImageBitmap, degrees: Float): ImageBitmap = runCatching {
        val bmp = src.asAndroidBitmap()
        val m = android.graphics.Matrix().apply { postRotate(degrees) }
        android.graphics.Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height, m, /* filter = */ true,
        ).asImageBitmap()
    }.getOrDefault(src)

    /**
     * 一次性把 [turns] 个 90° 转好（装载首帧用：回到一张之前转过的图时，
     * 要**瞬时**呈现它的朝向，而不是当着用户的面转几圈）。
     */
    fun rotateSteps(src: ImageBitmap, turns: Int): ImageBitmap =
        if (turns <= 0) src else rotate(src, STEP_DEG * turns)

}
