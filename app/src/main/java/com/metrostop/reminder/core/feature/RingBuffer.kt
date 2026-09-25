package com.metrostop.reminder.core.feature

/** 定长环形缓冲（Float），用于 vib 的 RMS 统计窗；无 android.* 依赖 */
class RingBuffer(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val data = FloatArray(capacity)
    private var head = 0
    private var size = 0
    private var sumSq = 0.0

    val count: Int get() = size

    fun clear() {
        head = 0
        size = 0
        sumSq = 0.0
    }

    /** 追加一个值，返回被挤出窗的旧值（窗未满时为 0） */
    fun push(value: Float): Float {
        val evicted: Float
        if (size < capacity) {
            data[(head + size) % capacity] = value
            size++
            evicted = 0f
        } else {
            evicted = data[head]
            data[head] = value
            head = (head + 1) % capacity
        }
        val v = value.toDouble()
        sumSq += v * v
        val e = evicted.toDouble()
        sumSq -= e * e
        if (sumSq < 0.0) sumSq = 0.0 // 浮点误差兜底
        return evicted
    }

    /** 窗内均方根值 */
    fun rms(): Float {
        if (size == 0) return 0f
        return kotlin.math.sqrt(sumSq / size).toFloat()
    }
}
