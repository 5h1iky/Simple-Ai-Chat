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

    @Suppress("UNCHECKED_CAST")
    private fun <T> zeroOf(raw: Class<*>): T? = when (raw) {
        String::class.java -> "" as T
        Char::class.java, Char::class.javaObjectType -> '\u0000' as T
        Int::class.java, Int::class.javaObjectType -> 0 as T
        Long::class.java, Long::class.javaObjectType -> 0L as T
        Short::class.java, Short::class.javaObjectType -> 0.toShort() as T
        Byte::class.java, Byte::class.javaObjectType -> 0.toByte() as T
        Double::class.java, Double::class.javaObjectType -> 0.0 as T
        Float::class.java, Float::class.javaObjectType -> 0f as T
        Boolean::class.java, Boolean::class.javaObjectType -> false as T
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
