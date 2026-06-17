package com.glassbar.ssh.ui.screen.dicecup

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class DiceGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    private val diceRenderer = DiceRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(diceRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setDice(value: Int, rolling: Boolean) {
        queueEvent {
            diceRenderer.value = value.coerceIn(1, 6)
            diceRenderer.rolling = rolling
        }
    }
}

private class DiceRenderer : GLSurfaceView.Renderer {
    var value: Int = 5
    var rolling: Boolean = false

    private var program = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var modelHandle = 0
    private var vertexBuffer: FloatBuffer = floatBufferOf(floatArrayOf())
    private var vertexCount = 0
    private var rotX = 0f
    private var rotY = 0f
    private var rotZ = 0f
    private var velX = 0f
    private var velY = 0f
    private var velZ = 0f
    private var wasRolling = false

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        modelHandle = GLES20.glGetUniformLocation(program, "uModel")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 34f, ratio, 1f, 20f)
        Matrix.setLookAtM(view, 0, 3.2f, 2.5f, 6.0f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (rolling) {
            if (!wasRolling) {
                velX = 13.5f
                velY = 18.0f
                velZ = 9.0f
            }
            rotX = (rotX + velX) % 360f
            rotY = (rotY + velY) % 360f
            rotZ = (rotZ + velZ) % 360f
        } else {
            velX *= 0.86f
            velY *= 0.86f
            velZ *= 0.86f
            rotX = dampAngle(rotX + velX)
            rotY = dampAngle(rotY + velY)
            rotZ = dampAngle(rotZ + velZ)
        }
        wasRolling = rolling

        val mesh = DiceMesh.build(value)
        vertexBuffer = floatBufferOf(mesh)
        vertexCount = mesh.size / STRIDE_FLOATS

        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, rotX, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, rotY, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, rotZ, 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        vertexBuffer.position(6)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun dampAngle(value: Float): Float {
        val normalized = ((value + 180f) % 360f + 360f) % 360f - 180f
        val damped = normalized * 0.82f
        return if (kotlin.math.abs(damped) < 0.08f) 0f else damped
    }
}

private object DiceMesh {
    private val orientation = mapOf(
        1 to (2 to 3),
        2 to (6 to 3),
        3 to (2 to 6),
        4 to (2 to 1),
        5 to (1 to 3),
        6 to (2 to 4),
    )

    fun build(frontValue: Int): FloatArray {
        val vertices = ArrayList<Float>(4096)
        val (topValue, rightValue) = orientation[frontValue] ?: (2 to 3)
        val backValue = opposite(frontValue)
        val bottomValue = opposite(topValue)
        val leftValue = opposite(rightValue)

        addShadow(vertices)
        addFace(vertices, floatArrayOf(0.96f, 0.94f, 0.89f, 1f), Face.Front)
        addFace(vertices, floatArrayOf(0.82f, 0.81f, 0.77f, 1f), Face.Right)
        addFace(vertices, floatArrayOf(1.00f, 0.99f, 0.95f, 1f), Face.Top)
        addFace(vertices, floatArrayOf(0.70f, 0.70f, 0.68f, 1f), Face.Left)
        addFace(vertices, floatArrayOf(0.74f, 0.73f, 0.70f, 1f), Face.Bottom)
        addFace(vertices, floatArrayOf(0.64f, 0.64f, 0.62f, 1f), Face.Back)
        addCornerFaces(vertices)

        addPips(vertices, Face.Front, frontValue, 0.105f)
        addPips(vertices, Face.Top, topValue, 0.085f)
        addPips(vertices, Face.Right, rightValue, 0.085f)
        addPips(vertices, Face.Back, backValue, 0.085f)
        addPips(vertices, Face.Bottom, bottomValue, 0.085f)
        addPips(vertices, Face.Left, leftValue, 0.085f)
        return vertices.toFloatArray()
    }

    private fun opposite(value: Int) = 7 - value

    private fun addCornerFaces(out: MutableList<Float>) {
        val c = floatArrayOf(0.90f, 0.88f, 0.82f, 1f)
        val m = CORNER_SIZE
        val s = CORNER_PLANE
        addCorner(out, p(m, m, s), p(s, m, m), p(m, s, m), p(1f, 1f, 1f), c)
        addCorner(out, p(-m, m, s), p(-m, s, m), p(-s, m, m), p(-1f, 1f, 1f), c)
        addCorner(out, p(m, -m, s), p(m, -s, m), p(s, -m, m), p(1f, -1f, 1f), c)
        addCorner(out, p(-m, -m, s), p(-s, -m, m), p(-m, -s, m), p(-1f, -1f, 1f), c)
        addCorner(out, p(m, m, -s), p(m, s, -m), p(s, m, -m), p(1f, 1f, -1f), c)
        addCorner(out, p(-m, m, -s), p(-s, m, -m), p(-m, s, -m), p(-1f, 1f, -1f), c)
        addCorner(out, p(m, -m, -s), p(s, -m, -m), p(m, -s, -m), p(1f, -1f, -1f), c)
        addCorner(out, p(-m, -m, -s), p(-m, -s, -m), p(-s, -m, -m), p(-1f, -1f, -1f), c)
    }

    private fun addCorner(
        out: MutableList<Float>,
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        normal: FloatArray,
        color: FloatArray,
    ) {
        addTri(out, a, b, c, normal, color)
    }

    private fun addShadow(out: MutableList<Float>) {
        val segments = 48
        val color = floatArrayOf(0f, 0f, 0f, 0.18f)
        val normal = p(0f, 1f, 0f)
        val center = p(0f, -1.08f, 0f)
        for (i in 0 until segments) {
            val a0 = (Math.PI * 2.0 * i / segments).toFloat()
            val a1 = (Math.PI * 2.0 * (i + 1) / segments).toFloat()
            val p0 = p(cos(a0) * 1.28f, -1.08f, sin(a0) * 0.42f)
            val p1 = p(cos(a1) * 1.28f, -1.08f, sin(a1) * 0.42f)
            addTri(out, center, p0, p1, normal, color)
        }
    }

    private fun addFace(out: MutableList<Float>, color: FloatArray, face: Face) {
        val p = face.corners()
        val normal = face.normal()
        addTri(out, p[0], p[1], p[2], normal, color)
        addTri(out, p[0], p[2], p[3], normal, color)
    }

    private fun addPips(out: MutableList<Float>, face: Face, value: Int, radius: Float) {
        for ((u, v) in pipPositions(value)) {
            addDisc(out, face, u, v, radius * 1.18f, 1.016f, floatArrayOf(0.30f, 0.30f, 0.30f, 1f))
            addDisc(out, face, u, v, radius, 1.021f, floatArrayOf(0.01f, 0.01f, 0.012f, 1f))
        }
    }

    private fun addDisc(
        out: MutableList<Float>,
        face: Face,
        u: Float,
        v: Float,
        radius: Float,
        plane: Float,
        color: FloatArray,
    ) {
        val segments = 28
        val center = face.point(u, v, plane)
        val normal = face.normal()
        for (i in 0 until segments) {
            val a0 = (Math.PI * 2.0 * i / segments).toFloat()
            val a1 = (Math.PI * 2.0 * (i + 1) / segments).toFloat()
            val p0 = face.point(u + cos(a0) * radius, v + sin(a0) * radius, plane)
            val p1 = face.point(u + cos(a1) * radius, v + sin(a1) * radius, plane)
            addTri(out, center, p0, p1, normal, color)
        }
    }

    private fun addTri(
        out: MutableList<Float>,
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        normal: FloatArray,
        color: FloatArray,
    ) {
        addVertex(out, a, normal, color)
        addVertex(out, b, normal, color)
        addVertex(out, c, normal, color)
    }

    private fun addVertex(out: MutableList<Float>, position: FloatArray, normal: FloatArray, color: FloatArray) {
        out.add(position[0])
        out.add(position[1])
        out.add(position[2])
        out.add(normal[0])
        out.add(normal[1])
        out.add(normal[2])
        out.add(color[0])
        out.add(color[1])
        out.add(color[2])
        out.add(color[3])
    }

    private fun pipPositions(value: Int): List<Pair<Float, Float>> = when (value) {
        1 -> listOf(0f to 0f)
        2 -> listOf(-0.38f to 0.38f, 0.38f to -0.38f)
        3 -> listOf(-0.38f to 0.38f, 0f to 0f, 0.38f to -0.38f)
        4 -> listOf(-0.38f to 0.38f, 0.38f to 0.38f, -0.38f to -0.38f, 0.38f to -0.38f)
        5 -> listOf(-0.38f to 0.38f, 0.38f to 0.38f, 0f to 0f, -0.38f to -0.38f, 0.38f to -0.38f)
        else -> listOf(-0.38f to 0.46f, 0.38f to 0.46f, -0.38f to 0f, 0.38f to 0f, -0.38f to -0.46f, 0.38f to -0.46f)
    }
}

private enum class Face {
    Front, Back, Right, Left, Top, Bottom;

    fun corners(): Array<FloatArray> = when (this) {
        Front -> arrayOf(p(-1f, -1f, 1f), p(1f, -1f, 1f), p(1f, 1f, 1f), p(-1f, 1f, 1f))
        Back -> arrayOf(p(1f, -1f, -1f), p(-1f, -1f, -1f), p(-1f, 1f, -1f), p(1f, 1f, -1f))
        Right -> arrayOf(p(1f, -1f, 1f), p(1f, -1f, -1f), p(1f, 1f, -1f), p(1f, 1f, 1f))
        Left -> arrayOf(p(-1f, -1f, -1f), p(-1f, -1f, 1f), p(-1f, 1f, 1f), p(-1f, 1f, -1f))
        Top -> arrayOf(p(-1f, 1f, 1f), p(1f, 1f, 1f), p(1f, 1f, -1f), p(-1f, 1f, -1f))
        Bottom -> arrayOf(p(-1f, -1f, -1f), p(1f, -1f, -1f), p(1f, -1f, 1f), p(-1f, -1f, 1f))
    }

    fun normal(): FloatArray = when (this) {
        Front -> p(0f, 0f, 1f)
        Back -> p(0f, 0f, -1f)
        Right -> p(1f, 0f, 0f)
        Left -> p(-1f, 0f, 0f)
        Top -> p(0f, 1f, 0f)
        Bottom -> p(0f, -1f, 0f)
    }

    fun point(u: Float, v: Float, plane: Float): FloatArray = when (this) {
        Front -> p(u, v, plane)
        Back -> p(-u, v, -plane)
        Right -> p(plane, v, -u)
        Left -> p(-plane, v, u)
        Top -> p(u, plane, -v)
        Bottom -> p(u, -plane, v)
    }
}

private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

private fun floatBufferOf(values: FloatArray): FloatBuffer {
    return ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
}

private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
    return GLES20.glCreateProgram().also {
        GLES20.glAttachShader(it, vertexShader)
        GLES20.glAttachShader(it, fragmentShader)
        GLES20.glLinkProgram(it)
    }
}

private fun loadShader(type: Int, shaderCode: String): Int {
    return GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, shaderCode)
        GLES20.glCompileShader(it)
    }
}

private const val STRIDE_FLOATS = 10
private const val STRIDE_BYTES = STRIDE_FLOATS * Float.SIZE_BYTES
private const val CORNER_SIZE = 0.90f
private const val CORNER_PLANE = 1.006f

private const val VERTEX_SHADER = """
uniform mat4 uMvp;
uniform mat4 uModel;
attribute vec4 aPosition;
attribute vec3 aNormal;
attribute vec4 aColor;
varying vec4 vColor;
void main() {
    gl_Position = uMvp * aPosition;
    vec3 normal = normalize(mat3(uModel) * aNormal);
    vec3 light = normalize(vec3(-0.45, 0.82, 0.36));
    float diffuse = max(dot(normal, light), 0.0);
    float rim = pow(1.0 - max(normal.z, 0.0), 2.0) * 0.10;
    float shade = 0.52 + diffuse * 0.48 + rim;
    vec3 color = aColor.rgb * shade;
    color += vec3(0.08, 0.075, 0.06) * diffuse;
    vColor = vec4(color, aColor.a);
}
"""

private const val FRAGMENT_SHADER = """
precision mediump float;
varying vec4 vColor;
void main() {
    gl_FragColor = vColor;
}
"""
