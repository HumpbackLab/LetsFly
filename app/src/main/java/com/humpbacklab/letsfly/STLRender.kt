package com.humpbacklab.letsfly

import android.opengl.GLSurfaceView
import android.opengl.GLU
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// copy from https://github.com/wei-gong/StlRender/blob/master/app/src/main/java/com/gongw/stlrender/stl/STLRenderer.java
// thanks for wei-gong



/**
 * STLObject渲染器
 * Created by gw on 2017/7/11.
 */
class STLRender : GLSurfaceView.Renderer {
    var angleX = 0f
    var angleY = 0f
    var angleZ = 0f
    var rotateZ = 0f
    var positionX = 0f
    var positionY = 0f
    var positionZ = 0f

    //外部控制
    var scale = 1.0f
    var scale_object = 1.0f

    //当前展示
    private var scale_rember = 1.0f
    var scale_object_rember = 1.0f

    //当前固定
    private var scale_now = 1.0f
    private var scale_object_now = 1.0f
    var translation_z = 0f
    var translation_y = 0f
    var red = 0.027f
    var green = 0.38f
    var blue = 0.79f
    var alpha = 1f
    private var stlObject: STLObject? = null
    private val shotHeight = 260f
    fun getStlObject(): STLObject? {
        return stlObject
    }

    /**
     * 简单重绘（适用于旋转等）
     */
    fun requestRedraw() {
        bufferCounter = FRAME_BUFFER_COUNT
    }

    /**
     * 停止渲染
     */
    fun cancelRedraw() {
        bufferCounter = 0
    }

    /**
     * 更换STLObject并重新渲染
     * @param stlObject
     */
    fun requestRedraw(stlObject: STLObject?) {
        this.stlObject = stlObject
        setPreviewParamters()
        bufferCounter = FRAME_BUFFER_COUNT
    }

    /**
     * 创建时调用
     * @param gl
     * @param config
     */
    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        //用指定颜色清空颜色缓存
        gl.glClearColor(0.93f, 0.93f, 0.93f, 1.0f)
        //启动色彩混合
        gl.glEnable(GL10.GL_BLEND)
        //设置源因子和目标因子（源颜色乘以的系数称为“源因子”，目标颜色乘以的系数称为“目标因子”）
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
        //开启更新深度缓冲区
        gl.glEnable(GL10.GL_DEPTH_TEST)
        //指定深度缓冲比较值，GL10.GL_LEQUAL：输入的深度值小于或等于参考值，则通过
        gl.glDepthFunc(GL10.GL_LEQUAL)
        gl.glHint(3152, 4354)
        //法线在转换后被标准化
        gl.glEnable(GL10.GL_NORMALIZE)
        //设置两点间其他点颜色的过渡模式
        gl.glShadeModel(GL10.GL_SMOOTH)

        //开始对投影矩阵操作
        gl.glMatrixMode(GL10.GL_PROJECTION)
        // 打开光源
        gl.glEnable(GL10.GL_LIGHTING)
        // 设置全局环境光
        gl.glLightModelfv(GL10.GL_LIGHT_MODEL_AMBIENT, getFloatBufferFromArray(floatArrayOf(0.5f, 0.5f, 0.5f, 1.0f)))
        //使用GL_LIGHT0光源
        gl.glEnable(GL10.GL_LIGHT0)
        //设置材质的环境颜色和散射颜色
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_AMBIENT_AND_DIFFUSE, floatArrayOf(0.3f, 0.3f, 0.3f, 1.0f), 0)
        //设置光源位置
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, floatArrayOf(0f, 0f, 1000f, 1.0f), 0)
    }

    /**
     * 尺寸发生变化时调用
     * @param gl
     * @param width
     * @param height
     */
    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        val aspectRatio = width.toFloat() / height
        //设置视口矩形的位置，宽度和高度
        gl.glViewport(0, 0, width, height)
        //重置当前矩阵
        gl.glLoadIdentity()
        //清空颜色缓存
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
        //指定观察的视景体在世界坐标系中的具体大小
        GLU.gluPerspective(gl, 45f, aspectRatio, 1f, 5000f)
        //开始对模型视景的操作
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        //定义视点矩阵（视点位置和参考点位置）
        GLU.gluLookAt(gl, 0f, 0f, shotHeight, 0f, 0f, 0f, 0f, 0f, 1f)
    }

    /**
     * 绘制每一帧的时候调用
     * @param gl
     */
    override fun onDrawFrame(gl: GL10) {
        if (bufferCounter < 1) {
            return
        }
        bufferCounter--
        gl.glLoadIdentity()
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
        // 设置旋转和偏移
        gl.glTranslatef(0f, translation_y, 0f)
        gl.glTranslatef(0f, 0f, translation_z)
        gl.glRotatef(angleX, 1f, 0f, 0f)
        gl.glRotatef(angleY, 0f, 1f, 0f)
        gl.glRotatef(angleZ, 0f, 0f, 1f)
        //设置缩放
        scale_rember = scale_now * scale
        gl.glScalef(scale_rember, scale_rember, scale_rember)

        //使能顶点数组功能
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        //开始对模型视景的操作
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        //允许写入深度缓冲区
        gl.glDepthMask(true)
        gl.glTranslatef(positionX, positionY, positionZ)
        gl.glRotatef(rotateZ, 0f, 0f, 1f)
        scale_object_rember = scale_object_now * scale_object
        gl.glScalef(scale_object_rember, scale_object_rember, scale_object_rember)
        //使用颜色材质
        gl.glEnable(GL10.GL_COLOR_MATERIAL)
        //设置材质环境颜色
        gl.glMaterialfv(GL10.GL_FRONT, GL10.GL_AMBIENT, floatArrayOf(0.75f, 0.75f, 0.75f, 1f), 0)
        //设置材质散射颜色
        gl.glMaterialfv(GL10.GL_FRONT, GL10.GL_DIFFUSE, floatArrayOf(0.75f, 0.75f, 0.75f, 1f), 0)
        //保存当前状态
        gl.glPushMatrix()
        gl.glColor4f(red, green, blue, alpha)
        // 画Stl模型
        drawSTLObject(stlObject, gl)
        //恢复之前保存的状态
        gl.glPopMatrix()
        //禁用颜色材质
        gl.glDisable(GL10.GL_COLOR_MATERIAL)
    }

    /**
     * 调整预览设置,使模型展示时大小位置适中
     */
    private fun setPreviewParamters() {
//        val distance_y: Float = stlObject?.maxY - stlObject.minY
//        val distance_z: Float = stlObject?.maxZ - stlObject.minZ
//        translation_z = distance_z * -3f
//        translation_y = distance_y / -5f
//        angleX = -45f
//
//        //将模型置于中央位置
//        positionX = -(stlObject.maxX + stlObject.minX) / 2 * scale_object_rember
//        positionY = -(stlObject.maxY + stlObject.minY) / 2 * scale_object_rember
//        positionZ = -stlObject.minZ * scale_object_rember
    }

    /**
     * 固定缩放比例
     */
    fun setsclae() {
        scale_object_now = scale_object_rember
        scale_object_rember = 1.0f
        scale_object = 1.0f
        scale_now = scale_rember
        scale_rember = 1.0f
        scale = 1.0f
    }

    private fun getFloatBufferFromArray(vertexArray: FloatArray): FloatBuffer {
        val vbb = ByteBuffer.allocateDirect(vertexArray.size * 4)
        vbb.order(ByteOrder.nativeOrder())
        val triangleBuffer = vbb.asFloatBuffer()
        triangleBuffer.put(vertexArray)
        triangleBuffer.position(0)
        return triangleBuffer
    }

    /**
     * 绘制STLObject
     * @param stlObject
     * @param gl
     */
    fun drawSTLObject(stlObject: STLObject?, gl: GL10) {
        if (stlObject == null || stlObject.normalBuffer == null || stlObject.vertexBuffer == null) {
            return
        }
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glEnableClientState(GL10.GL_NORMAL_ARRAY)
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, stlObject.vertexBuffer)
        gl.glNormalPointer(GL10.GL_FLOAT, 0, stlObject.normalBuffer)
        gl.glDrawArrays(GL10.GL_TRIANGLES, 0, stlObject.triangleCount * 3)
        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glDisableClientState(GL10.GL_NORMAL_ARRAY)
    }

    companion object {
        const val FRAME_BUFFER_COUNT = 10
        private var bufferCounter = FRAME_BUFFER_COUNT
    }
}