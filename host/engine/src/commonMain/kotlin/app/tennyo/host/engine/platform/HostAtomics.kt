package app.tennyo.host.engine.platform

/** A map shared between threads. Iteration works on snapshots, like a weakly consistent concurrent map. */
class HostConcurrentMap<K, V> {
    private val monitor = HostMonitor()
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = monitor.guarded { map[key] }

    /** Stores [value] and returns the previous value. */
    fun put(key: K, value: V): V? = monitor.guarded { map.put(key, value) }

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun remove(key: K): V? = monitor.guarded { map.remove(key) }

    /** Removes [key] only while it still maps to [value]. */
    fun remove(key: K, value: V): Boolean =
        monitor.guarded {
            if (map[key] == value) {
                map.remove(key)
                true
            } else {
                false
            }
        }

    fun computeIfAbsent(key: K, create: (K) -> V): V = monitor.guarded { map.getOrPut(key) { create(key) } }

    fun containsKey(key: K): Boolean = monitor.guarded { key in map }

    fun keys(): List<K> = monitor.guarded { map.keys.toList() }

    fun values(): List<V> = monitor.guarded { map.values.toList() }

    fun entries(): List<Pair<K, V>> = monitor.guarded { map.entries.map { it.key to it.value } }

    val size: Int get() = monitor.guarded { map.size }

    fun isEmpty(): Boolean = monitor.guarded { map.isEmpty() }

    fun isNotEmpty(): Boolean = !isEmpty()

    fun clear() = monitor.guarded { map.clear() }
}

/** A set shared between threads. */
class HostConcurrentSet<E> {
    private val monitor = HostMonitor()
    private val set = LinkedHashSet<E>()

    /** Adds [element]; `false` when it was already present. */
    fun add(element: E): Boolean = monitor.guarded { set.add(element) }

    fun remove(element: E): Boolean = monitor.guarded { set.remove(element) }

    operator fun contains(element: E): Boolean = monitor.guarded { element in set }

    fun toList(): List<E> = monitor.guarded { set.toList() }

    fun clear() = monitor.guarded { set.clear() }
}
