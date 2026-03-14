package com.humpbacklab.letsfly

import java.nio.FloatBuffer

class STLObject {
    //三角面片法向量数据
    var normalBuffer: FloatBuffer? = null

    //三角面片法顶点数据
    var vertexBuffer: FloatBuffer? = null

    //三角面片数
    var triangleCount = 0
    var maxX = 0f
    var maxY = 0f
    var maxZ = 0f
    var minX = 0f
    var minY = 0f
    var minZ = 0f
}