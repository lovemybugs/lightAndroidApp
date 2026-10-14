package com.htmlstudy.library.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 一块"液态玻璃"：把截图贴到 [surface] 上，再用 GPU 做折射。
 *
 * 光学模型（AGSL 单 pass，Android 13+）：
 * 1. 圆角矩形的 SDF → 数值梯度 → 屏幕空间法线；
 * 2. 厚度剖面用逆幂衰减（越贴边折得越狠）算出"斜面斜率"，
 *    采样点沿法线往面板内侧移 —— 于是边缘出现内侧背景的压缩镜像；
 * 3. 红绿蓝三个通道的位移量略有差别 → 玻璃边上那一圈彩边（色散）；
 * 4. 同一套法线驱动光照：迎光侧与背光侧各一道对称的窄高光 + 迎光侧的柔和辉光，
 *    没有跟方向无关的常亮描边，所以不会看起来像"描了个边"；
 * 5. 逐像素自适应染色：背景亮就压暗一点、背景暗就提亮一点 ——
 *    面板上的图标文字在任何背景上都读得清，同时又不用整体加一层厚底把背景糊死。
 *
 * 降级路线（都做了失败回退，最差也就是半透明底色）：
 * - Android 13+：AGSL 透镜（上面这一套）
 * - Android 12：只有高斯模糊
 * - Android 7~11：截图缩到 1/6 再放大，双线性插值当模糊
 *
 * 参考了 GitHub 上 QWEA0/Liquid-Glass-Android（View 体系里做同一件事，
 * 198 stars）的光学模型与 AGSL 写法，按本项目的规模做了裁剪：
 * 单输入、无触摸凸起、无形状合并。
 */
class LiquidGlass(
    private val activity: Activity,
    private val surface: ImageView,
    private val scrim: View,
    private val radiusDp: Float,
    private val blurDp: Float,
    private val bevelDp: Float,
    private val refractDp: Float,
    private val tintAmount: Float
) {

    enum class Tier { LENS, BLUR, DOWNSCALE }

    var tier = Tier.DOWNSCALE
        private set

    /** 透镜那一档跑起来了没有 */
    val lens: Boolean get() = tier == Tier.LENS

    /** 老系统要先把截图缩小多少倍（缩小再放大就是模糊） */
    val downscale: Int get() = if (tier == Tier.DOWNSCALE) 6 else 1

    private var shader: RuntimeShader? = null
    private var width = 0f
    private var height = 0f

    private var lightX = LIGHT_X
    private var lightY = LIGHT_Y
    private var listening = false
    private val sensors by lazy { activity.getSystemService(SensorManager::class.java) }

    fun setup() {
        tier = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Tier.DOWNSCALE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && compileShader()) {
            Tier.LENS
        } else {
            Tier.BLUR
        }
        // 透镜那一档已经逐像素调过明暗了，固定底色就该让开 —— 玻璃才够通透；
        // 退化的两档没有这一层，还得靠底色保证面板上的字读得清
        scrim.alpha = if (lens) SCRIM_WITH_LENS else 1f
        surface.clipToOutline = !lens // 透镜自己会算圆角覆盖率，多一层裁剪反而会把折射带切掉
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun compileShader(): Boolean {
        shader = runCatching { RuntimeShader(LENS_AGSL) }.getOrNull()
        return shader != null
    }

    fun setSurfaceSize(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        apply()
    }

    /** 贴一张截图上去。位图归调用方所有，这里只画不回收。 */
    fun bind(bitmap: Bitmap, matrix: Matrix) {
        surface.imageMatrix = matrix
        surface.setImageBitmap(bitmap)
        apply()
    }

    /**
     * 换成新的一张背景（尺寸没变）。
     *
     * 和 [bind] 的差别是**不重建** RenderEffect：滚动时每秒要换十几张，
     * 而效果只跟面板尺寸、光照方向有关，跟位图无关，没必要跟着重建。
     */
    fun rebind(bitmap: Bitmap, matrix: Matrix) {
        surface.imageMatrix = matrix
        surface.setImageBitmap(bitmap)
    }

    /**
     * 每帧只挪一下位置：网页滚动时走的就是这条路，代价只有一个 Matrix。
     * （ImageView 是 matrix 缩放，重新设一次矩阵就会重画一遍，着色器跟着重算。）
     */
    fun slide(matrix: Matrix) {
        surface.imageMatrix = matrix
    }

    fun clear() {
        surface.setImageDrawable(null)
    }

    private fun apply() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effect = runCatching { buildEffect() }.getOrNull() ?: return
        runCatching { surface.setRenderEffect(effect) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildEffect(): RenderEffect {
        // 模糊只留一点点：折射位移（十几 px）远大于模糊半径，后面的字才看得出被折弯。
        // 糊得太狠的话位移会被抹平，看起来就只是块磨砂板。
        val blur = RenderEffect.createBlurEffect(
            dp(blurDp), dp(blurDp), Shader.TileMode.CLAMP
        )
        val sh = shader
        if (!lens || sh == null || width <= 0f || height <= 0f) return blur
        sh.setFloatUniform("viewSize", floatArrayOf(width, height))
        sh.setFloatUniform("radius", dp(radiusDp))
        sh.setFloatUniform("bevel", dp(bevelDp))
        sh.setFloatUniform("refractPx", dp(refractDp))
        sh.setFloatUniform("falloff", FALLOFF)
        sh.setFloatUniform("rimSoft", dp(3f))
        sh.setFloatUniform("dispersion", DISPERSION)
        sh.setFloatUniform("lightDir", floatArrayOf(lightX, lightY))
        sh.setFloatUniform("specStrength", 0.85f)
        sh.setFloatUniform("tintAmount", tintAmount)
        val lensEffect = RenderEffect.createRuntimeShaderEffect(sh, "input")
        // 先糊、再折射：着色器的 input 拿到的就是模糊后的背景
        return RenderEffect.createChainEffect(lensEffect, blur)
    }

    // ------------------------------------------------------------------ 跟随重力的光源

    /**
     * 面板拉开后才听传感器：光源固定在"世界"里，设备一歪，玻璃边上的高光跟着走。
     * 面板收起来就注销，别一直占着传感器。
     */
    fun startLight() {
        if (!lens || listening) return
        val sm = sensors ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        listening = runCatching {
            sm.registerListener(lightListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }.getOrDefault(false)
    }

    fun stopLight() {
        if (!listening) return
        listening = false
        runCatching { sensors?.unregisterListener(lightListener) }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val gx = event.values[0]
            val gy = event.values[1]
            // 按当前屏幕方向把重力重映射到"屏幕坐标系"（x 向右、y 向上）
            val rotation = runCatching { activity.display?.rotation }.getOrNull()
            val ux: Float
            val uy: Float
            when (rotation) {
                Surface.ROTATION_90 -> { ux = -gy; uy = gx }
                Surface.ROTATION_180 -> { ux = -gx; uy = -gy }
                Surface.ROTATION_270 -> { ux = gy; uy = -gx }
                else -> { ux = gx; uy = gy }
            }

            // 屏幕平面里的重力分量：0 = 平放，1 = 竖直
            val planar = sqrt(ux * ux + uy * uy)
            val tilt = (planar / SensorManager.GRAVITY_EARTH).coerceIn(0f, 1f)

            var tx = lightX
            var ty = lightY
            if (planar > 0.5f) {
                // 光从"世界上方"来：行进方向 = -up，翻到 view 坐标（y 向下）就是 (-ux, uy)。
                // 平放时渐变回默认方向，免得躺着用的时候高光乱抖。
                val inv = 1f / planar
                tx = (-ux * inv) * tilt + LIGHT_X * (1f - tilt)
                ty = (uy * inv) * tilt + LIGHT_Y * (1f - tilt)
            }
            val len = sqrt(tx * tx + ty * ty)
            if (len > 1e-4f) {
                tx /= len
                ty /= len
            }

            // 低通滤波：传感器噪声直接喂给高光会闪
            var nx = lightX + (tx - lightX) * SMOOTHING
            var ny = lightY + (ty - lightY) * SMOOTHING
            val nLen = sqrt(nx * nx + ny * ny)
            if (nLen <= 1e-4f) return
            nx /= nLen
            ny /= nLen
            if (abs(nx - lightX) < NOTIFY_EPSILON && abs(ny - lightY) < NOTIFY_EPSILON) return
            lightX = nx
            lightY = ny
            apply()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun dp(value: Float): Float = value * activity.resources.displayMetrics.density

    private companion object {
        /** 默认光源方向（view 坐标，y 向下）：左上方来光，跟 iOS 液态玻璃的亮边角度一致 */
        const val LIGHT_X = 0.866f
        const val LIGHT_Y = 0.5f

        /** 折射剖面衰减指数：越大越集中在最贴边的几个像素里 */
        const val FALLOFF = 1.8f
        const val DISPERSION = 0.06f

        /** 低通滤波系数与重绘阈值（传感器噪声很大） */
        const val SMOOTHING = 0.14f
        const val NOTIFY_EPSILON = 0.006f

        /** 透镜这一档自己会染色，固定底色只留一点点 */
        const val SCRIM_WITH_LENS = 0.28f

        /**
         * 透镜着色器。单输入（模糊后的背景），所有几何量都在视图像素坐标里算。
         */
        const val LENS_AGSL = """
            uniform shader input;       // 面板背后的内容（已经轻微模糊过）
            uniform float2 viewSize;    // 面板尺寸（px）
            uniform float  radius;      // 圆角（px）
            uniform float  bevel;       // 斜面（折射带）宽度（px）
            uniform float  refractPx;   // 最大折射位移（px）
            uniform float  falloff;     // 逆幂衰减指数
            uniform float  rimSoft;     // 折射带内沿法线的抹匀宽度（px）—— 那点"晕染"
            uniform float  dispersion;  // 色散强度
            uniform float2 lightDir;    // 光线行进方向（view 坐标，y 向下）
            uniform float  specStrength;// 高光强度
            uniform float  tintAmount;  // 自适应染色强度

            float sdRoundedBox(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - halfSize + r;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 coord) {
                float2 halfSize = viewSize * 0.5;
                float2 p = coord - halfSize;
                float d = sdRoundedBox(p, halfSize, radius);

                // 覆盖率：1.5px 羽化，圆角之外直接透明（所以这一档不需要额外裁剪）
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                // 中心差分求 SDF 梯度 → 屏幕空间外法线
                float2 g = float2(
                    sdRoundedBox(p + float2(1.0, 0.0), halfSize, radius)
                        - sdRoundedBox(p - float2(1.0, 0.0), halfSize, radius),
                    sdRoundedBox(p + float2(0.0, 1.0), halfSize, radius)
                        - sdRoundedBox(p - float2(0.0, 1.0), halfSize, radius)
                );
                float gl = length(g);
                float2 n = (gl > 0.0001) ? (g / gl) : float2(0.0, -1.0);

                // 厚度剖面：t = 1 是面板内部，t = 0 贴在边上
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float gB = pow(5.0, -falloff);
                float slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);

                // 采样点沿法线往面板内侧挪：边缘是内侧背景的压缩镜像（越贴边折得越狠）
                float2 offset = -n * (slope * refractPx);

                // 色散：三个通道位移量略有差别，边缘就有一圈彩边
                float2 cR = coord + offset * (1.0 - dispersion * slope);
                float2 cG = coord + offset;
                float2 cB = coord + offset * (1.0 + dispersion * slope);

                float3 col;
                if (rimSoft > 0.01) {
                    // 沿法线抹匀三下：压缩带从一条硬边变成一段渐变，就是那点晕染
                    float2 sm = n * (rimSoft * slope);
                    col = float3(
                        (input.eval(cR).r + input.eval(cR - sm).r + input.eval(cR + sm).r) / 3.0,
                        (input.eval(cG).g + input.eval(cG - sm).g + input.eval(cG + sm).g) / 3.0,
                        (input.eval(cB).b + input.eval(cB - sm).b + input.eval(cB + sm).b) / 3.0
                    );
                } else {
                    col = float3(input.eval(cR).r, input.eval(cG).g, input.eval(cB).b);
                }

                // 逐像素自适应染色：背景亮就压暗、背景暗就提亮。
                // 面板上的字在任何背景上都读得清，又不用整体糊一层厚底。
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                float e = smoothstep(0.35, 0.75, lum);
                col = mix(col, float3(1.0 - e), tintAmount * (0.70 + 0.40 * e));

                // 光照：同一套法线驱动。迎光侧、背光侧各一道对称的窄角瓣
                // （背光侧那道是透明介质的内壁反射），没有与方向无关的常亮项。
                float facing = dot(n, -lightDir);
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);

                // 贴边亮线（中心在边内 1px、半宽 2px）+ 迎光侧往内的柔和辉光
                float bandW = max(bevel * 0.3, 2.0);
                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
                float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5);
                float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0);
                float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.12 * lobeF) * specStrength;
                col += float3(spec);

                col = clamp(col, float3(0.0), float3(1.0));
                return half4(half3(col * cov), half(cov));
            }
        """
    }
}
