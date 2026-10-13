package com.finecat.pimobile.android.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 首页动效背景：点阵 logo 与随传感器/时间漂移的光晕。
 * 网格与底色常驻；巨大点阵 Logo 仅用于空白首页，不放在会话正文或加载提示下。
 * 浅色跳过深色系 AGSL 流体层，使用 Paper 底与淡 Ocean 光晕。
 */
@Composable
internal fun PiAnimatedBackground(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    showBrandDecoration: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val liquidGlassFrameSignal = LocalPiGlassFrameSignal.current
    val motionEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }
    var active by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, motionEnabled) {
        if (!active || !motionEnabled) {
            elapsed = 0f
            return@LaunchedEffect
        }
        val started = System.nanoTime()
        while (true) {
            elapsed = ((System.nanoTime() - started) / 1_000_000_000.0 % 4096.0).toFloat()
            liquidGlassFrameSignal?.longValue = (elapsed * 20f).toLong()
            delay(33)
        }
    }

    if (!dark) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(PiColors.Paper)
                .semantics { hideFromAccessibility() }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            PiColors.Ocean.copy(alpha = 0.10f),
                            PiColors.Mist.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.72f, size.height * 0.24f),
                        radius = max(size.width, size.height) * 0.72f
                    )
                )
            }
            // 浅色保留点阵 logo：AGSL 流体层是深色系硬编码故跳过，其余同深色布局。
            TechnicalGrid(dark = false)
            if (showBrandDecoration) PiParticleField(elapsed, !motionEnabled, dark = false)
        }
        return
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .semantics { hideFromAccessibility() }
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            FluidField(elapsed)
        } else {
            FluidFallback(elapsed)
        }
        TechnicalGrid()
        if (showBrandDecoration) PiParticleField(elapsed, !motionEnabled)
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.58f to PiColors.Navy.copy(alpha = 0.12f),
                    1f to Color.Black.copy(alpha = 0.58f)
                )
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FluidField(time: Float) {
    val brush = remember { PiFluidBrush() }
    brush.time = time
    Canvas(Modifier.fillMaxSize()) { drawRect(brush) }
}

@Composable
private fun FluidFallback(time: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val x = size.width * (0.46f + sin(time * 0.075f) * 0.08f)
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color(0xFF141414), Color(0xFF0A0A0A), Color(0xFF030303)),
                center = Offset(x, size.height * 0.28f),
                radius = max(size.width, size.height) * 0.82f
            )
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PiFluidBrush : ShaderBrush() {
    private val runtime = RuntimeShader(PI_FLUID_SHADER)
    var time: Float = 0f

    override fun createShader(size: Size): Shader {
        runtime.setFloatUniform("size", size.width, size.height)
        runtime.setFloatUniform("time", time)
        return runtime
    }
}

@Composable
private fun TechnicalGrid(dark: Boolean = true) {
    // 首页现在直接把点阵当品牌页的底，对比度往下压一档：网格只当质感，不抢文字。
    val line = if (dark) Color.White.copy(alpha = 0.030f) else Color.Black.copy(alpha = 0.034f)
    val accent = if (dark) Color.White.copy(alpha = 0.055f) else Color.Black.copy(alpha = 0.060f)
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 42.dp.toPx()
        var x = -spacing
        while (x <= size.width + spacing) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 0.55.dp.toPx())
            x += spacing
        }
        var y = -spacing
        while (y <= size.height + spacing) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), 0.55.dp.toPx())
            y += spacing
        }
        val columns = ceil(size.width / spacing).toInt() + 1
        val rows = ceil(size.height / spacing).toInt() + 1
        repeat(rows) { row ->
            repeat(columns) { column ->
                if ((row * 11 + column * 7) % 13 == 0) {
                    drawRect(
                        accent,
                        Offset(column * spacing - 1.1.dp.toPx(), row * spacing - 1.1.dp.toPx()),
                        Size(2.2.dp.toPx(), 2.2.dp.toPx())
                    )
                }
            }
        }
        if (dark) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.56f to Color.Transparent,
                    1f to Color(0xFF050505)
                ),
                blendMode = BlendMode.SrcOver
            )
        }
    }
}

@Composable
private fun PiParticleField(time: Float, isStatic: Boolean, dark: Boolean = true) {
    val particles = remember { makePiParticles(96) }
    Canvas(Modifier.fillMaxSize().testTag("home-background-decoration")) {
        if (particles.isEmpty()) return@Canvas
        val square = max(size.width * 1.0f, size.height * 0.46f)
        val center = Offset(size.width * 0.5f, size.height * 0.32f)
        val baseDot = max(1.3.dp.toPx(), square / 260f)
        val t = if (isStatic) 0f else time
        particles.forEach { particle ->
            val edgeDrift = 0.35f + 0.65f * particle.edge
            val driftX = sin(t * 0.50f + particle.phase * 0.53f) * 1.4f * edgeDrift
            val driftY = cos(t * 0.42f + particle.phase * 0.71f) * 1.3f * edgeDrift
            val shimmer = 0.90f + 0.10f * sin(t * 1.5f + particle.x * 15f + particle.y * 9f)
            val point = Offset(
                center.x + (particle.x - 0.5f) * square + driftX,
                center.y + (particle.y - 0.5f) * square + driftY
            )
            val dot = baseDot * (0.72f + particle.luminance * 0.58f)
            val alpha = ((0.10f + particle.luminance * 0.38f) * shimmer).coerceIn(0f, 1f) * 0.56f
            drawRoundRect(
                if (dark) {
                    Color(
                        red = (0.88f + 0.12f * particle.light).coerceIn(0f, 1f),
                        green = (0.90f + 0.10f * particle.light).coerceIn(0f, 1f),
                        blue = 1f,
                        alpha = alpha
                    )
                } else {
                    PiColors.Ocean.copy(alpha = alpha * 0.85f)
                },
                topLeft = Offset(point.x - dot / 2f, point.y - dot / 2f),
                size = Size(dot, dot),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(dot * 0.18f)
            )
        }
    }
}

internal data class PiParticle(
    val x: Float,
    val y: Float,
    val luminance: Float,
    val edge: Float,
    val phase: Float,
    val light: Float
)

internal fun makePiParticles(sampleSize: Int = 60): List<PiParticle> {
    val path = requireNotNull(PathParser.createPathFromPathData(PI_PATH))
    val bitmap = createBitmap(sampleSize, sampleSize)
    val scale = minOf(
        sampleSize / PI_VIEWPORT_WIDTH,
        sampleSize / PI_VIEWPORT_HEIGHT
    )
    val offsetX = (sampleSize - PI_VIEWPORT_WIDTH * scale) / 2f
    val offsetY = (sampleSize - PI_VIEWPORT_HEIGHT * scale) / 2f
    val fittedPath = android.graphics.Path(path).apply {
        transform(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(offsetX, offsetY)
            }
        )
        fillType = android.graphics.Path.FillType.EVEN_ODD
    }
    AndroidCanvas(bitmap).apply {
        drawColor(AndroidColor.BLACK)
        drawPath(
            fittedPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.WHITE
                style = Paint.Style.FILL
            }
        )
    }
    val pixels = IntArray(sampleSize * sampleSize)
    bitmap.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
    bitmap.recycle()
    val luminance = Array(sampleSize) { y ->
        FloatArray(sampleSize) { x -> AndroidColor.red(pixels[y * sampleSize + x]) / 255f }
    }
    val inside = Array(sampleSize) { y ->
        BooleanArray(sampleSize) { x -> luminance[y][x] > PI_LUMINANCE_THRESHOLD }
    }
    fun contains(x: Int, y: Int) = x in 0 until sampleSize && y in 0 until sampleSize && inside[y][x]
    val result = mutableListOf<PiParticle>()
    repeat(sampleSize) { y ->
        repeat(sampleSize) { x ->
            if (!inside[y][x]) return@repeat
            var hasNeighbour = false
            for (dy in -2..2) for (dx in -2..2) {
                if ((dx != 0 || dy != 0) && contains(x + dx, y + dy)) hasNeighbour = true
            }
            if (!hasNeighbour) return@repeat
            var missing = 0
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && !contains(x + dx, y + dy)) missing++
            }
            val nx = (x + 0.5f) / sampleSize
            val ny = (y + 0.5f) / sampleSize
            val light = max(0f, 1f - hypot(nx - 0.30f, ny - 0.28f) / 0.92f)
            result += PiParticle(
                nx,
                ny,
                luminance[y][x],
                missing / 8f,
                result.size.toFloat(),
                light
            )
        }
    }
    return result
}

private const val PI_VIEWPORT_WIDTH = 24f
private const val PI_VIEWPORT_HEIGHT = 24f
private const val PI_LUMINANCE_THRESHOLD = 0.2f

private const val PI_PATH = "M1,1h16.5v11H12v5.5H6.5V23H1V1zm5.5,5.5V12H12V6.5H6.5zM17.5,12H23v11h-5.5V12z"

private const val PI_FLUID_SHADER = """
uniform float2 size;
uniform float time;
float hash(float2 p){p=fract(p*float2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float noise(float2 p){float2 i=floor(p);float2 f=fract(p);float2 u=f*f*(3.0-2.0*f);return mix(mix(hash(i),hash(i+float2(1,0)),u.x),mix(hash(i+float2(0,1)),hash(i+1.0),u.x),u.y);}
float fbm(float2 p){float v=0.0;float a=.56;for(int o=0;o<4;o++){v+=a*noise(p);p=float2(.80*p.x-.60*p.y,.60*p.x+.80*p.y)*2.03+17.17;a*=.46;}return v;}
half4 main(float2 position){
 float2 safe=max(size,float2(1));float2 uv=position/safe;float aspect=safe.x/safe.y;float t=time*.075;
 float2 p=float2(uv.x*aspect,uv.y)*2.05+float2(-.30,-.08);
 float2 q=float2(fbm(p*.62+float2(0,t)),fbm(p*.62+float2(5.2,1.3-t)));
 float2 r=float2(fbm(p*.72+q*.62+float2(1.7,3.1+t*.7)),fbm(p*.72+q*.62+float2(9.2,5.7-t*.65)));
 float fluid=fbm((p+q*.76+r*.52)*.72+t*.25);float swirl=noise(p*1.25+r*2.1-t*.42);
 float3 black=float3(.002,.002,.003),deep=float3(.045,.048,.055),ocean=float3(.085,.090,.100),mist=float3(.220,.230,.250),warm=float3(.933,.847,.667);
 float3 color=mix(black,deep,smoothstep(.14,.63,fluid));color=mix(color,ocean,smoothstep(.42,.82,fluid+swirl*.18));color=mix(color,mist,smoothstep(.74,.98,swirl)*.18);
 float2 warped=float2(uv.x*aspect,uv.y)+(q-.5)*.085+(r-.5)*.045;
 float ra=length(warped-float2(aspect*.58,.16));float rb=length(warped-float2(aspect*-.20,.13));
 float ribbonA=exp(-pow((ra-(.35+.025*sin(t*1.5)))/.042,2.0));float ribbonB=exp(-pow((rb-(.83+.020*cos(t*1.15)))/.065,2.0));
 float ribbon=saturate(ribbonA*.78+ribbonB*.62);color=mix(color,warm,ribbon*(.16+.08*swirl));
 float glow=smoothstep(.90,.18,length((uv-float2(.58,.32))*float2(.82,1)));color+=deep*glow*.10;
 float vignette=smoothstep(.95,.23,length((uv-.5)*float2(.86,1)));color*=.38+.72*vignette;color+=(hash(position+floor(time*12.0))-.5)*.010;
 return half4(half3(max(color,0.0)),1);
}
"""
