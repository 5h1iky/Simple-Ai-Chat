package www.cetool.com.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * C2：让 Gson 读到的 JSON `null` 变成各自类型的零值，**绝不**写进 Kotlin 非空字段。
 *
 * Gson 的反射赋值**不做 Kotlin 空安全检查**：`"description": null` 会把
 * `TavernCardData.description: String` 直接置为真正的 null，之后任何使用点都可能 NPE。
 * 可达路径（审计已核实）：
 * - `extensions == null` → `CharacterManager.processEmbeddedWorldBook` 里 `extensions[...]` 直接 NPE
 * - `description == null` → `CharacterManager.list` 的 `take(100)` 抛错，
 *   被外层 `catch (_: Exception) { null }` 吞掉 → **卡片从列表静默消失**
 * - `tags == null` → 赋值给非空 `List` 属性抛 NPE
 *
 * 实现要点：只用一层 TypeAdapterFactory，对每个「基本类型 / 字符串 / 集合 / Map」
 * 在 **read 时**把 JsonToken.NULL 翻译成零值；对象类型不做包一层 delegate 的嵌套，
 * 因为对象内部每个字段会各自走到这里，逐级兜住即可（避免自引用递归）。
 *
 * H2 修订：**可空数值/布尔（Integer/Boolean 等装箱类型）不再翻译成 0/false**，
 * 保留 null 原样返回。原因：这类字段（如 CharacterBookEntry.priority/scan_depth、
 * ConversationEntry.isArchived）在业务代码里都用 `?: 默认值` 表达"未设置"语义，
 * 翻译成 0 会让兜底永久失效（priority 变 0 乱序、scan_depth 变 0 只扫 1 条消息）。
 * Kotlin 非空数值/布尔字段编译为 primitive（如 int/boolean），在 create() 里
 * `raw.isPrimitive` 提前放行，不受本翻译影响，也不会被塞 null。
 * String 与集合仍保留零值兜底（description/tags 等非空字段的 NPE 是本类存在的初衷）。
 */
object NullSafeTypeAdapterFactory : TypeAdapterFactory {

    override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        if (raw.isPrimitive) return null

        val zero: T? = zeroOf(raw)
        if (zero == null && !isSupportedContainer(raw) && !isSupportedScalar(raw)) return null

        val delegate: TypeAdapter<T> = gson.getDelegateAdapter(this, type)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) {
                delegate.write(out, value)
            }

            override fun read(reader: JsonReader): T? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return zero ?: emptyContainer(raw)
                }
                return delegate.read(reader)
            }
        }
    }

    private fun isSupportedScalar(raw: Class<*>) = raw in SCALARS

    private fun isSupportedContainer(raw: Class<*>) =
        List::class.java.isAssignableFrom(raw) ||
            MutableList::class.java.isAssignableFrom(raw) ||
            Set::class.java.isAssignableFrom(raw) ||
            MutableSet::class.java.isAssignableFrom(raw) ||
            Map::class.java.isAssignableFrom(raw) ||
            MutableMap::class.java.isAssignableFrom(raw) ||
            raw.isArray

    private val SCALARS = setOf(
        String::class.java, Char::class.java,
        Int::class.java, Long::class.java, Short::class.java, Byte::class.java,
        Double::class.java, Float::class.java, Boolean::class.java
    )

    /**
     * H2：装箱数值/布尔/字符返回 null（保留"未设置"语义，交给调用方 `?:` 兜底）；
     * 只有 String 保留 "" 零值（见类注释）。primitive 类型根本走不到这里
     * （create() 对 `raw.isPrimitive` 提前返回 null）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> zeroOf(raw: Class<*>): T? = when (raw) {
        String::class.java -> "" as T
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> emptyContainer(raw: Class<*>): T? = when {
        Set::class.java.isAssignableFrom(raw) || MutableSet::class.java.isAssignableFrom(raw) ->
            emptySet<Any>() as T
        Map::class.java.isAssignableFrom(raw) || MutableMap::class.java.isAssignableFrom(raw) ->
            emptyMap<Any, Any>() as T
        List::class.java.isAssignableFrom(raw) || MutableList::class.java.isAssignableFrom(raw) ->
            emptyList<Any>() as T
        Collection::class.java.isAssignableFrom(raw) -> emptyList<Any>() as T
        raw.isArray -> java.lang.reflect.Array.newInstance(raw.componentType, 0) as T
        else -> null
    }

    /** 便捷构造：已注册好本品工厂的 Gson */
    fun buildGson(): Gson = GsonBuilder()
        .registerTypeAdapterFactory(NullSafeTypeAdapterFactory)
        .create()
}
