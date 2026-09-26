package com.xposed.wetypehook.wetype.settings

/** Optional raw HyperOS glass parameters. Null preserves the XiaoAI frosted material. */
data class GlassMaterialOverrides(
    val glass: List<Float>? = null,
    val blurRadii: List<Int>? = null,
    val bloom: List<Float>? = null,
    val materialType: Int? = null
) {
    /** The editor's master switch is represented by a complete glass parameter array. */
    val isLiquidGlassEnabled: Boolean
        get() = glass != null

    init {
        require(glass == null || (glass.size == 42 && glass.all { it.isFinite() }))
        require(blurRadii == null || (blurRadii.size == 2 && blurRadii.all { it in 0..400 }))
        require(bloom == null || (bloom.size in 12..16 && bloom.all { it.isFinite() }))
        require(materialType == null || materialType in 0..1)
    }

    fun withGlassEnabled(enabled: Boolean): GlassMaterialOverrides = copy(
        glass = if (enabled) glass ?: GlassSliderParameter.startingGlass() else null,
        blurRadii = if (enabled) blurRadii ?: GlassSliderParameter.startingBlurRadii() else null,
        bloom = if (enabled) bloom ?: GlassSliderParameter.startingBloom() else null,
        materialType = if (enabled) 1 else null
    )

    /** One switch owns all native interfaces, including previously independent overrides. */
    fun forLiquidGlass(): GlassMaterialOverrides = withGlassEnabled(glass != null)

    /** Native final RGB mix; leave the separate multiplicative tint disabled. */
    fun glassWithTint(argb: Int?): List<Float>? = glass?.let { values ->
        if (argb == null) values else values.toMutableList().apply {
            this[11] = ((argb ushr 16) and 255) / 255f
            this[12] = ((argb ushr 8) and 255) / 255f
            this[13] = (argb and 255) / 255f
            this[14] = 0f
            this[36] = ((argb ushr 24) and 255) / 255f
        }
    }

    fun text(field: GlassOverrideField): String = when (field) {
        GlassOverrideField.GLASS -> glass?.joinToString(", ").orEmpty()
        GlassOverrideField.BLUR_RADII -> blurRadii?.joinToString(", ").orEmpty()
        GlassOverrideField.BLOOM -> bloom?.joinToString(", ").orEmpty()
        GlassOverrideField.MATERIAL_TYPE -> materialType?.toString().orEmpty()
    }

    companion object {
        private fun tokens(text: String): List<String>? {
            if (text.isBlank()) return null
            val normalized = text.trim().removeSurrounding("[", "]").replace('，', ',')
            require(normalized.isNotBlank())
            val commaParts = normalized.split(',')
            require(commaParts.none { it.isBlank() })
            return commaParts.flatMap { it.trim().split(Regex("\\s+")) }
        }

        fun isValid(field: GlassOverrideField, text: String): Boolean = runCatching {
            parse(mapOf(field to text))
        }.isSuccess

        fun parse(text: Map<GlassOverrideField, String>): GlassMaterialOverrides = GlassMaterialOverrides(
            glass = tokens(text[GlassOverrideField.GLASS].orEmpty())?.map { it.toFloat() },
            blurRadii = tokens(text[GlassOverrideField.BLUR_RADII].orEmpty())?.map { it.toInt() },
            bloom = tokens(text[GlassOverrideField.BLOOM].orEmpty())?.map { it.toFloat() },
            materialType = tokens(text[GlassOverrideField.MATERIAL_TYPE].orEmpty())?.single()?.toInt()
        )

        fun read(values: Map<String, Any>): GlassMaterialOverrides = runCatching {
            fun floats(key: String, lengths: IntRange): List<Float>? {
                val count = values["${key}_count"] as? Int ?: return null
                require(count in lengths)
                return List(count) { requireNotNull(values["${key}_$it"] as? Float) }
            }
            val radii = if (values.containsKey("glass_blur_small")) {
                listOf(requireNotNull(values["glass_blur_small"] as? Int),
                    requireNotNull(values["glass_blur_large"] as? Int))
            } else null
            GlassMaterialOverrides(GlassSliderParameter.migrateStartingGlass(floats("glass_params", 42..42)), radii,
                floats("glass_bloom", 12..16), values["glass_material_type"] as? Int)
        }.getOrDefault(GlassMaterialOverrides())
    }
}

enum class GlassOverrideField { GLASS, BLUR_RADII, BLOOM, MATERIAL_TYPE }

/** Verified against HyperOS 4 libhwui's JNI copy and runtime-shader uniform uploads. */
enum class GlassSliderParameter(
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    private val glassIndex: Int,
    val bloomIndex: Int? = null
) {
    LIGHT_ANGLE(0f..359f, 1f, 25, 2),
    LIGHT_INTENSITY(0f..100f, 1f, 28, 5),
    REFRACTION(1f..2.5f, 0.01f, 32),
    DEPTH(1f..100f, 1f, 19, 0),
    SPLAY(1f..180f, 1f, 30, 7),
    THICKNESS(1f..200f, 1f, 21),
    EDGE_CURVE(0.1f..4f, 0.05f, 20, 1);

    fun read(glass: List<Float>): Float = readAt(glass, glassIndex)
    fun readBloom(bloom: List<Float>): Float = readAt(bloom, requireNotNull(bloomIndex))

    private fun readAt(values: List<Float>, index: Int): Float = when (this) {
        LIGHT_ANGLE -> ((Math.toDegrees(kotlin.math.atan2(values[index].toDouble(), values[index + 1].toDouble())) + 360.0) % 360.0).toFloat()
        LIGHT_INTENSITY -> values[index] * 100f
        SPLAY -> values[index] * 180f
        else -> values[index]
    }

    fun write(glass: List<Float>, value: Float): List<Float> {
        require(glass.size == 42)
        return writeAt(glass, glassIndex, value)
    }

    fun writeBloom(bloom: List<Float>, value: Float): List<Float> {
        require(bloom.size in 12..16)
        return writeAt(bloom, requireNotNull(bloomIndex), value)
    }

    private fun writeAt(values: List<Float>, index: Int, value: Float): List<Float> {
        require(values.all { it.isFinite() })
        require(value.isFinite() && value in range)
        return values.toMutableList().apply {
            when (this@GlassSliderParameter) {
                LIGHT_ANGLE -> {
                    val radians = Math.toRadians(value.toDouble())
                    // Preserve the existing elevation; the native shader normalizes xyz.
                    val length = kotlin.math.hypot(this[index].toDouble(), this[index + 1].toDouble())
                        .takeIf { it > 0.0001 } ?: 1.0
                    this[index] = (kotlin.math.sin(radians) * length).toFloat()
                    this[index + 1] = (kotlin.math.cos(radians) * length).toFloat()
                }
                LIGHT_INTENSITY -> this[index] = value / 100f
                SPLAY -> this[index] = value / 180f
                else -> this[index] = value
            }
        }
    }

    companion object {
        /** Repair only the exact buggy initial preset; preserve deliberate raw customization. */
        fun migrateStartingGlass(values: List<Float>?): List<Float>? {
            if (values == null) return null
            // Freeze the historical preset so new defaults cannot change migration matching.
            val corrected = listOf(
                0f, 0.33333334f, 0.6666667f, 1f, 0f, 1f, 0f, 0f, 0f, 1f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 30f, 1f, 30f,
                0f, 0f, 0f, 0f, 1f, 1f, 0.25f, 0f, 0.5f, 1.1764705f,
                1.5f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f
            )
            val previous = corrected.toMutableList().apply { this[6] = 1f; this[34] = 1f }
            return if (values == previous) corrected else values
        }

        fun startingBlurRadii(): List<Int> = listOf(60, 16)

        fun startingBloom(): List<Float> = listOf(
            30f, 1f, 0f, 0.99999994f, 1f, 0.1f, 0f, 0.5f, 0f, 0f, 0f, 0f
        )

        /** Module defaults selected by the user; not a XiaoAI glass token. */
        fun startingGlass(): List<Float> = listOf(
            0f, 0.33333334f, 0.6666667f, 1f, 0f, 1f, 0f, 0f, 0f, 1f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 50f, 1f, 160f,
            0f, 0f, 0f, 0f, 1f, 1f, 0.5f, 0f, 0.5f, 1.1764705f,
            2f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f
        )
    }
}
