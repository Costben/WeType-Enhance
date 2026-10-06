package com.xposed.wetypehook.wetype.voice.shell.eta

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Eta 被 R8 混淆后方法名全丢，但字符串常量留着，所以只能拿「方法体引用了哪个字符串」当锚点定位。
 *
 * 刻意不引 dexkit：那套挂在 android.* 上，只能进设备；这里要的是同一份代码既能跑在输入法进程里，
 * 也能在 JVM 单测里拿真实 dex 钉住锚点结果。解析范围只覆盖走到 class_data_item → code_item
 * 所必需的那几张表，纯 java.nio，不碰 android.*，也不用 java.nio.file。
 *
 * 注意 R8 会做 class merging，一个类里塞着大量无关方法，因此结果只能按方法签名用，不能按类名筛。
 */
data class DexMethodRef(
    val classDescriptor: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val returnType: String,
)

object EtaDexAnchors {

    /**
     * 扫描 [dex]，返回每个锚点字符串对应的、方法体内引用了该字符串的方法列表。
     * 每个传入的锚点都会出现在返回值里，没命中就是空列表。
     */
    fun scan(dex: ByteArray, anchors: Collection<String>): Map<String, List<DexMethodRef>> {
        val result = anchors.toCollection(LinkedHashSet()).associateWith { mutableListOf<DexMethodRef>() }
        if (result.isEmpty() || dex.size < HEADER_BYTES) return result

        val image = DexImage(dex)
        val anchorByStringIndex = image.anchorStringIndex(result.keys)
        if (anchorByStringIndex.isEmpty()) return result

        image.forEachMethod { methodIndex, codeOff ->
            for (anchor in image.anchorsInCode(codeOff, anchorByStringIndex)) {
                result.getValue(anchor).add(image.methodRef(methodIndex))
            }
        }
        return result
    }

    private const val HEADER_BYTES = 0x70
}

private const val CONST_STRING = 0x1A
private const val CONST_STRING_JUMBO = 0x1B
private const val PACKED_SWITCH_PAYLOAD = 0x0100
private const val SPARSE_SWITCH_PAYLOAD = 0x0200
private const val FILL_ARRAY_DATA_PAYLOAD = 0x0300

private const val STRING_ID_ITEM_SIZE = 4
private const val TYPE_ID_ITEM_SIZE = 4
private const val PROTO_ID_ITEM_SIZE = 12
private const val METHOD_ID_ITEM_SIZE = 8
private const val CLASS_DEF_ITEM_SIZE = 32
private const val CLASS_DEF_CLASS_DATA_OFF = 24

/** 指令宽度表，单位是 16 位码元。表外的一律 1；payload 不走这张表。 */
private val INSTRUCTION_WIDTHS = IntArray(256) { 1 }.also { widths ->
    for (opcode in intArrayOf(0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20, 0x22, 0x23, 0x29, 0xFE, 0xFF)) {
        widths[opcode] = 2
    }
    for (opcode in intArrayOf(0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C, 0xFC, 0xFD)) {
        widths[opcode] = 3
    }
    widths[0x18] = 5
    for (opcode in 0x2D..0x3D) widths[opcode] = 2
    for (opcode in 0x44..0x6D) widths[opcode] = 2
    for (opcode in 0x6E..0x72) widths[opcode] = 3
    for (opcode in 0x74..0x78) widths[opcode] = 3
    for (opcode in 0x90..0xAF) widths[opcode] = 2
    for (opcode in 0xD0..0xE2) widths[opcode] = 2
    widths[0xFA] = 4
    widths[0xFB] = 4
}

private class DexImage(private val bytes: ByteArray) {

    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private val stringIdsSize = buffer.getInt(0x38)
    private val stringIdsOff = buffer.getInt(0x3C)
    private val typeIdsSize = buffer.getInt(0x40)
    private val typeIdsOff = buffer.getInt(0x44)
    private val protoIdsOff = buffer.getInt(0x4C)
    private val methodIdsOff = buffer.getInt(0x5C)
    private val classDefsSize = buffer.getInt(0x60)
    private val classDefsOff = buffer.getInt(0x64)

    private val strings = arrayOfNulls<String>(stringIdsSize)
    private val typeDescriptors = arrayOfNulls<String>(typeIdsSize)

    /**
     * 锚点字符串在 string_ids 里的下标 → 锚点本身。先按 utf16 长度筛一道，只有长度对上的才解码，
     * 30k 条字符串里真正分配 String 的只剩个位数。
     */
    fun anchorStringIndex(anchors: Set<String>): Map<Int, String> {
        val byUtf16Length = anchors.groupBy { it.length }
        val result = HashMap<Int, String>()
        for (index in 0 until stringIdsSize) {
            val cursor = UlebCursor(bytes, buffer.getInt(stringIdsOff + index * STRING_ID_ITEM_SIZE))
            val candidates = byUtf16Length[cursor.next()] ?: continue
            val start = cursor.offset
            var end = start
            while (bytes[end] != 0.toByte()) end++
            val value = String(bytes, start, end - start, Charsets.UTF_8)
            if (candidates.contains(value)) result[index] = value
        }
        return result
    }

    /** 方法体内引用到的锚点集合。按指令宽度逐条走，switch/fill-array 的 payload 单独算长度。 */
    fun anchorsInCode(codeOff: Int, anchorByStringIndex: Map<Int, String>): Set<String> {
        val insnsSize = buffer.getInt(codeOff + 12)
        val insnsOff = codeOff + 16
        val found = LinkedHashSet<String>()
        var pc = 0
        while (pc < insnsSize) {
            val unitOffset = insnsOff + pc * 2
            val first = u2(unitOffset)
            when (first and 0xFF) {
                CONST_STRING -> anchorByStringIndex[u2(unitOffset + 2)]?.let { found.add(it) }
                CONST_STRING_JUMBO -> anchorByStringIndex[buffer.getInt(unitOffset + 2)]?.let { found.add(it) }
            }
            pc += instructionWidth(first, unitOffset)
        }
        return found
    }

    /** 遍历本 dex 里所有**有方法体**的方法（`code_off == 0` 的 abstract/native 直接跳过）。 */
    fun forEachMethod(action: (methodIndex: Int, codeOff: Int) -> Unit) {
        for (index in 0 until classDefsSize) {
            val classDataOff = buffer.getInt(classDefsOff + index * CLASS_DEF_ITEM_SIZE + CLASS_DEF_CLASS_DATA_OFF)
            if (classDataOff == 0) continue

            val cursor = UlebCursor(bytes, classDataOff)
            val staticFields = cursor.next()
            val instanceFields = cursor.next()
            val directMethods = cursor.next()
            val virtualMethods = cursor.next()
            repeat(staticFields + instanceFields) {
                cursor.next()
                cursor.next()
            }
            walkMethods(cursor, directMethods, action)
            walkMethods(cursor, virtualMethods, action)
        }
    }

    fun methodRef(methodIndex: Int): DexMethodRef {
        val methodOff = methodIdsOff + methodIndex * METHOD_ID_ITEM_SIZE
        val protoOff = protoIdsOff + u2(methodOff + 2) * PROTO_ID_ITEM_SIZE
        val parametersOff = buffer.getInt(protoOff + 8)
        return DexMethodRef(
            classDescriptor = typeDescriptor(u2(methodOff)),
            methodName = stringAt(buffer.getInt(methodOff + 4)),
            parameterTypes = if (parametersOff == 0) {
                emptyList()
            } else {
                List(buffer.getInt(parametersOff)) { typeDescriptor(u2(parametersOff + 4 + it * 2)) }
            },
            returnType = typeDescriptor(buffer.getInt(protoOff + 4)),
        )
    }

    /** direct_methods 与 virtual_methods 的 `method_idx_diff` 各自从 0 起累加，两组之间要重置。 */
    private fun walkMethods(cursor: UlebCursor, count: Int, action: (Int, Int) -> Unit) {
        var methodIndex = 0
        repeat(count) {
            methodIndex += cursor.next()
            cursor.next()
            val codeOff = cursor.next()
            if (codeOff != 0) action(methodIndex, codeOff)
        }
    }

    private fun instructionWidth(first: Int, unitOffset: Int): Int {
        val opcode = first and 0xFF
        if (opcode != 0) return INSTRUCTION_WIDTHS[opcode]
        return when (first) {
            PACKED_SWITCH_PAYLOAD -> 4 + 2 * u2(unitOffset + 2)
            SPARSE_SWITCH_PAYLOAD -> 2 + 4 * u2(unitOffset + 2)
            FILL_ARRAY_DATA_PAYLOAD ->
                4 + (u2(unitOffset + 2) * buffer.getInt(unitOffset + 4) + 1) / 2
            else -> 1
        }
    }

    private fun stringAt(index: Int): String {
        strings[index]?.let { return it }
        val cursor = UlebCursor(bytes, buffer.getInt(stringIdsOff + index * STRING_ID_ITEM_SIZE))
        cursor.next()
        val start = cursor.offset
        var end = start
        while (bytes[end] != 0.toByte()) end++
        return String(bytes, start, end - start, Charsets.UTF_8).also { strings[index] = it }
    }

    private fun typeDescriptor(index: Int): String {
        typeDescriptors[index]?.let { return it }
        return stringAt(buffer.getInt(typeIdsOff + index * TYPE_ID_ITEM_SIZE)).also { typeDescriptors[index] = it }
    }

    private fun u2(offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
}

private class UlebCursor(private val bytes: ByteArray, var offset: Int) {

    fun next(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = bytes[offset].toInt() and 0xFF
            offset++
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }
}
