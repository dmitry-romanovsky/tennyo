package app.tennyo.mobile.design

enum class ProjectAvatarForm {
    Circle,
    Squircle,
    Clover,
    Cookie,
    Burst,
    Triangle,
    Pentagon,
    Hexagon,
    Arch,
    Pill,
}

data class ProjectAvatarStyle(
    val form: ProjectAvatarForm,
    val rotationDegrees: Int,
)

private const val FNV_OFFSET_BASIS = 0x811c9dc5L
private const val FNV_PRIME = 0x01000193L
private const val UINT32_MASK = 0xFFFFFFFFL
private const val ROTATION_STEP_DEGREES = 30
private const val ROTATION_STEPS = 360 / ROTATION_STEP_DEGREES

/**
 * Same name, same form and rotation on every platform and launch: FNV-1a over the
 * name's characters, so the choice never depends on [String.hashCode] implementation details.
 */
fun projectAvatarStyle(name: String): ProjectAvatarStyle {
    var hash = FNV_OFFSET_BASIS
    for (char in name) {
        hash = ((hash xor char.code.toLong()) * FNV_PRIME) and UINT32_MASK
    }
    val form = ProjectAvatarForm.entries[(hash % ProjectAvatarForm.entries.size).toInt()]
    val rotation = ((hash / ProjectAvatarForm.entries.size) % ROTATION_STEPS).toInt() * ROTATION_STEP_DEGREES
    return ProjectAvatarStyle(form = form, rotationDegrees = rotation)
}
