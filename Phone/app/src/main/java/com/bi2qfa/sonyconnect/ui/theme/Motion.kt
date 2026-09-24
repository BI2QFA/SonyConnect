package com.bi2qfa.sonyconnect.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * **MD3E 的弹簧物理**：全应用唯一的动效取用点。
 *
 * `MotionScheme.expressive()` 给的就是这六个规格（数值来自官方
 * `ExpressiveMotionTokens`，升级前我们照抄过一份，现在直接向库要）：
 *
 * | | 阻尼 | 刚度 | 手感 | 用在哪 |
 * |---|---|---|---|---|
 * | [spatialFast] | 0.6 | 800 | 快、弹一下就到位 | 小控件的位移/缩放/形变（指示药丸滑动、图标回弹） |
 * | [spatialDefault] | 0.8 | 380 | 标准空间动效 | 页面推入、面板展开、尺寸变化 |
 * | [spatialSlow] | 0.8 | 200 | 慢、稳 | 整屏级别的位移 |
 * | [effectsFast] | 0.6 | 3800 | 几乎瞬间（基本看不出弹） | 颜色/透明度的小幅变化（选中态换色） |
 * | [effectsDefault] | 0.8 | 1600 | 快速淡变 | 淡入淡出、交叉淡变 |
 * | [effectsSlow] | 0.8 | 800 | 大面积的淡变 | 大块内容的淡入 |
 *
 * ★ 分工是 MD3E 的规矩：**会动的（位置/尺寸/形变）用 spatial，只变明暗或颜色的用 effects**。
 *   把颜色也交给 spatial 会让换色带着"回弹"，看着像抖；把位移交给 effects 又会显得发木。
 *   spatial 的阻尼是 0.6/0.8（**允许过冲**，这就是"弹簧感"的来源），effects 的刚度很高
 *   （3800/1600），所以颜色变化是利落收住、不会晃。
 *
 * ★ 一律写成 `@Composable` 函数从主题里取，而不是抄成顶层常量：规格是随主题走的
 *   （`MaterialExpressiveTheme` 注入），写死就脱离了库。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object Motion {

    /** 小控件的空间动效：快、允许过冲。指示器滑动、图标缩放用它。 */
    @Composable
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.fastSpatialSpec()

    /** 标准空间动效：页面推入、面板展开、尺寸变化。 */
    @Composable
    fun <T> spatialDefault(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.defaultSpatialSpec()

    /** 整屏级别的空间动效：慢而稳。 */
    @Composable
    fun <T> spatialSlow(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.slowSpatialSpec()

    /** 颜色/明暗的小幅变化：几乎是瞬间到位。 */
    @Composable
    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.fastEffectsSpec()

    /** 淡入淡出：快速淡变。 */
    @Composable
    fun <T> effectsDefault(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.defaultEffectsSpec()

    /** 大块内容的淡变：比默认再慢一点。 */
    @Composable
    fun <T> effectsSlow(): FiniteAnimationSpec<T> =
        MaterialTheme.motionScheme.slowEffectsSpec()

    // ===== 用户定版的统一趋势：**起步最快、一路减速** =====
    //
    // ★★ 形状是**从参考录屏里反推出来的**（详见 `FloatingNav.overshootEasing` 的注释
    //   与 `tools/fit_reference_curve.py`）：逐帧量原版面板宽度、去采样混叠、
    //   再拿 8 条候选曲线做最小二乘，`easeOutQuad` 的 RMSE 只有 0.042，
    //   而"慢-快-慢"那两条（easeInOut*）是 0.25 —— **差 6 倍**。
    //
    //   所以这里也用 `easeOutQuad`，与面板那条保持一致（全应用一种手感）。
    //   上面那几条库弹簧**保留**给"数值追值"这类非视觉位移的场合
    //   （进度环追百分比、尺寸自适应）—— 它们不需要"看得出快慢"的观感。

    /**
     * **起步最快、一路减速**的位移/形变曲线（从参考实测出来的，全应用统一）。
     *
     * 用在所有"用户看得出在动"的地方：指示块滑动、图标弹起、箭头翻转。
     * ★ 这一条**不带过冲** —— 过冲是控制中心面板专属的（它要"甩出去再落住"），
     *   别的地方过了头会显得毛躁。
     *
     * ★ 时长 280ms：这条曲线中段很陡，实际观感比秒数快；实测原版展开主体约 255ms。
     */
    fun <T> fastOut(): FiniteAnimationSpec<T> =
        tween(durationMillis = 280, easing = FastOutEasing)

    /** 淡入淡出：同一条曲线，时长更短（换色属于"效果"，快一档）。 */
    fun <T> fastOutFade(): FiniteAnimationSpec<T> =
        tween(durationMillis = 180, easing = FastOutEasing)

    /**
     * `easeOutQuad`：**起步最快、一路减速**（导数 `2(1−t)`，t=0 处为 2、t=1 处为 0）。
     *
     * 写成常量而不是每次 new 一个 lambda —— `Easing` 是无状态的纯函数，
     * 复用同一个实例省得每帧重新分配。
     */
    val FastOutEasing = Easing { t -> 1f - (1f - t) * (1f - t) }

    /** 原厂面板形变的时长：`setDuration(350L)`，两个方向同一台 animator。 */
    const val FACTORY_MORPH_MS = 350

    // ===== ★★ 原厂弹簧：**从 MIUI 的拨号盘动画里逆向出来的** =====
    //
    // 用户给了参考录屏对应的系统 APK（通讯录与拨号）与设备，要求"抄原厂的实现"。
    // 扒完之后的结论比我自己反推的准得多 —— 之前那些"驻留 44ms / 飘回 / 1.083 过冲"
    // **全是从视频压缩伪影里过度解读出来的**，原厂根本没有那些细节。
    //
    // ## 原厂的调用（`DialerViewController.a1()`，逐行抄自反编译）
    //
    // ```java
    // // 展开
    // animConfig.setEase(miuix.animation.a.O(0.8f, 0.42f));                    // 主曲线
    // animConfig.setSpecial(TRANSLATION_Y, a.O(0.97f, 0.25f));                 // 位移
    // animConfig.setSpecial(ALPHA,         a.y(50L));                          // 透明度 50ms
    // // 收起
    // animConfig.setEase(a.O(0.97f, 0.25f));                                   // 主曲线
    // animConfig.setSpecial(TRANSLATION_Y, a.O(0.8f, 0.42f));                  // 位移
    // animConfig.setSpecial(ALPHA,         a.I(300L));                         // 透明度 300ms
    // ```
    //
    // `a.O(ζ, response)` → `getStyle(-2, …)` → `SpringEasing(ζ, ω = 2π/response)`
    // —— **就是阻尼谐振子的阶跃响应**（见 [springResponse]）。
    //
    // ★ 注意两组参数在**进出之间是对调**的：进快出慢（MD3E 的规矩，原厂也这么做）。
    //   主曲线 ζ=0.97 / 0.25s ≈ 临界阻尼、几乎不过冲；ζ=0.8 / 0.42s 过冲约 1.5%。
    //   而我之前给它安了个 4.5% 的过冲 —— 比原厂大一个量级，难怪用户说"刻意"。

    /**
     * **原厂形变曲线**：`1 − (1−t)³`（easeOutCubic），原厂就用它跑面板形变。
     *
     * 逆向证据（`DoubleCallButtonContainer.p()`，逐行读过）：
     * ```java
     * valueAnimator = ValueAnimator.ofFloat(0f, 1f);
     * valueAnimator.setInterpolator(r0.a.f21814c);   // = u9.g
     * valueAnimator.setDuration(350L);
     * // u9.g:  float f = t - 1f;  return f*f*f + 1f;   ⟹ 1-(1-t)³
     * ```
     *
     * ★★ 我先前用错了曲线：把 `a1()` 里的 `SpringEasing(0.8, 0.42)` 当成了形变曲线，
     *   但那对参数属于**另一条路径**（FAB 图标自身的显隐，见 [springEasing]）。
     *   面板形变这条路径是**固定 350ms + easeOutCubic**，与视频实测的 333ms 吻合。
     */
    fun <T> factoryMorph(durationMillis: Int = FACTORY_MORPH_MS): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EaseOutCubic)

    /**
     * `1 − (1−t)³` —— 原厂 `u9.g` 的逐行等价物（`f = t-1; f³+1`）。
     * 起步最快、一路减速，末尾导数 0（稳稳收住）。
     */
    val EaseOutCubic = Easing { t -> val q = 1f - t.coerceIn(0f, 1f); 1f - q * q * q }

    /**
     * **原厂弹簧**：阻尼谐振子 `1 − e^(−ζωt)·(cos ωd t + ζω/ωd · sin ωd t)` 的阶跃响应。
     *
     * @param zeta 阻尼比（原厂：0.97 = 几乎不过冲，0.8 = 轻微过冲 1.5%）
     * @param response 固有周期（秒）。原厂 0.25 / 0.42
     * @param durationMillis 时长。★ 弹簧本身没有"时长"，这里取**峰值附近**：
     *   实测原厂那两条曲线分别在 250ms / 420ms 内把 99% 的位移走完，
     *   剩下的 1% 尾巴肉眼不可见，砍掉反而更利落（也避免长尾让面板拖沓）。
     */
    fun springEasing(zeta: Float, response: Float, durationMillis: Int): FiniteAnimationSpec<Float> =
        tween(
            durationMillis = durationMillis,
            easing = Easing { t -> springResponse(t.coerceIn(0f, 1f), zeta, response * 1000f) },
        )

    /**
     * 阻尼谐振子的归一化阶跃响应，`f(0)=0, f(1)≈1`。
     *
     * ```
     * ζ < 1  欠阻尼： e^(−ζωt)·(cos(ωd t) + ζω/ωd · sin(ωd t))     有过冲
     * ζ ≥ 1  临界/过阻尼：直接用 (1 + ωt)·e^(−ωt)                   无过冲
     * ```
     * 末尾那段（`t > PEAK_TAIL`）把残余的 1% 线性抹平 —— 否则 `f(1)` 会停在
     * 0.99x，静止态永远差一点点（这个坑第一次实现 MD3E 弹簧时踩过）。
     *
     * @param tMs "时长"坐标（毫秒），与 [response] 同单位
     */
    private fun springResponse(t: Float, zeta: Float, responseMs: Float): Float {
        val omega = (2f * Math.PI / responseMs * 1000f).toFloat()   // ω = 2π/T
        val s = t * responseMs / 1000f                              // 转成"秒"尺度
        val raw = if (zeta < 1f) {
            val wd = omega * kotlin.math.sqrt(1f - zeta * zeta)     // 阻尼后的频率
            val e = kotlin.math.exp(-zeta * omega * s)
            1f - e * (kotlin.math.cos(wd * s) + (zeta * omega / wd) * kotlin.math.sin(wd * s))
        } else {
            val e = kotlin.math.exp(-omega * s)
            1f - (1f + omega * s) * e
        }
        // 把末尾的残差抹平：0.94 之后线性拉到 1，避免静止态停在 0.99
        val tail = 0.94f
        return if (t <= tail) raw else raw + (1f - raw) * ((t - tail) / (1f - tail))
    }
}
