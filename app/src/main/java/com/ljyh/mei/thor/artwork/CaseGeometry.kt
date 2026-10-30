package com.ljyh.mei.thor.artwork

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class CaseVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: CaseVector) = CaseVector(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: CaseVector) = CaseVector(x - other.x, y - other.y, z - other.z)
    operator fun div(value: Double) = CaseVector(x / value, y / value, z / value)
    fun dot(other: CaseVector) = x * other.x + y * other.y + z * other.z
}

data class CasePoint(val x: Double, val y: Double)
enum class CaseFace { Front, Back, Left, Right, Top, Bottom }
data class ProjectedCaseFace(val face: CaseFace, val corners: List<CasePoint>, val depth: Double, val normal: CaseVector)

/** Six actual planes of a thin cuboid. Projected corners are ordered as texture TL, TR, BR, BL. */
object CaseGeometry {
    const val Depth = .14
    private const val CameraDistance = 6.0
    private val points = listOf(
        CaseVector(-1.0, 1.0, Depth / 2), CaseVector(1.0, 1.0, Depth / 2),
        CaseVector(1.0, -1.0, Depth / 2), CaseVector(-1.0, -1.0, Depth / 2),
        CaseVector(-1.0, 1.0, -Depth / 2), CaseVector(1.0, 1.0, -Depth / 2),
        CaseVector(1.0, -1.0, -Depth / 2), CaseVector(-1.0, -1.0, -Depth / 2),
    )
    private data class Plane(val face: CaseFace, val indices: List<Int>, val normal: CaseVector)
    private val planes = listOf(
        Plane(CaseFace.Front, listOf(0, 1, 2, 3), CaseVector(0.0, 0.0, 1.0)),
        // Reversing the rear quad's world X order keeps its cover readable after a half turn.
        Plane(CaseFace.Back, listOf(5, 4, 7, 6), CaseVector(0.0, 0.0, -1.0)),
        Plane(CaseFace.Left, listOf(4, 0, 3, 7), CaseVector(-1.0, 0.0, 0.0)),
        Plane(CaseFace.Right, listOf(1, 5, 6, 2), CaseVector(1.0, 0.0, 0.0)),
        Plane(CaseFace.Top, listOf(4, 5, 1, 0), CaseVector(0.0, 1.0, 0.0)),
        Plane(CaseFace.Bottom, listOf(3, 2, 6, 7), CaseVector(0.0, -1.0, 0.0)),
    )

    fun project(pose: CasePose, cameraDistance: Double = CameraDistance, depth: Double = Depth): List<ProjectedCaseFace> {
        if (!pose.yawDegrees.isFinite() || !pose.pitchDegrees.isFinite() || !pose.rollDegrees.isFinite()) return emptyList()
        if (!cameraDistance.isFinite() || !depth.isFinite() || depth <= 0 || cameraDistance <= 2 + depth) return emptyList()
        val transform = rotation(pose)
        val vertices = points.map { transform(it.copy(z = it.z * depth / Depth)) }
        return planes.mapNotNull { plane ->
            val normal = transform(plane.normal)
            val quad = plane.indices.map(vertices::get)
            val center = quad.reduce(CaseVector::plus) / 4.0
            if (normal.dot(CaseVector(0.0, 0.0, cameraDistance) - center) <= .00001) return@mapNotNull null
            val corners = quad.map { point ->
                val perspective = cameraDistance / (cameraDistance - point.z)
                CasePoint(point.x * perspective, -point.y * perspective)
            }
            val area = corners.indices.sumOf { i ->
                val a = corners[i]; val b = corners[(i + 1) % 4]
                a.x * b.y - b.x * a.y
            }
            if (abs(area) < .00001) null else ProjectedCaseFace(plane.face, corners, center.z, normal)
        }.sortedBy { it.depth }
    }

    private fun rotation(pose: CasePose): (CaseVector) -> CaseVector {
        val yaw = Math.toRadians(pose.yawDegrees % 360)
        // CSS rotateX(...) rotateY(...) rotateZ(...) applies Z first, then Y, then X.
        // Model Y points upward, so CSS's downward-Y X/Z rotations change sign here.
        val pitch = Math.toRadians(-pose.pitchDegrees)
        val roll = Math.toRadians(-pose.rollDegrees)
        val cy = cos(yaw); val sy = sin(yaw); val cx = cos(pitch); val sx = sin(pitch); val cz = cos(roll); val sz = sin(roll)
        return { p ->
            val rolledX = p.x * cz - p.y * sz
            val rolledY = p.x * sz + p.y * cz
            val x = rolledX * cy + p.z * sy
            val z = -rolledX * sy + p.z * cy
            CaseVector(x, rolledY * cx - z * sx, rolledY * sx + z * cx)
        }
    }
}
