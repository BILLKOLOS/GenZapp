package org.thoughtcrime.securesms.keyvalue

import com.squareup.wire.ProtoAdapter
import org.GenZapp.core.util.LongSerializer
import kotlin.reflect.KProperty

internal fun GenZappStoreValues.longValue(key: String, default: Long): GenZappStoreValueDelegate<Long> {
  return LongValue(key, default, this.store)
}

internal fun GenZappStoreValues.booleanValue(key: String, default: Boolean): GenZappStoreValueDelegate<Boolean> {
  return BooleanValue(key, default, this.store)
}

internal fun <T : String?> GenZappStoreValues.stringValue(key: String, default: T): GenZappStoreValueDelegate<T> {
  return StringValue(key, default, this.store)
}

internal fun GenZappStoreValues.integerValue(key: String, default: Int): GenZappStoreValueDelegate<Int> {
  return IntValue(key, default, this.store)
}

internal fun GenZappStoreValues.floatValue(key: String, default: Float): GenZappStoreValueDelegate<Float> {
  return FloatValue(key, default, this.store)
}

internal fun GenZappStoreValues.blobValue(key: String, default: ByteArray): GenZappStoreValueDelegate<ByteArray> {
  return BlobValue(key, default, this.store)
}

internal fun GenZappStoreValues.nullableBlobValue(key: String, default: ByteArray?): GenZappStoreValueDelegate<ByteArray?> {
  return NullableBlobValue(key, default, this.store)
}

internal fun <T : Any?> GenZappStoreValues.enumValue(key: String, default: T, serializer: LongSerializer<T>): GenZappStoreValueDelegate<T> {
  return KeyValueEnumValue(key, default, serializer, this.store)
}

internal fun <M> GenZappStoreValues.protoValue(key: String, adapter: ProtoAdapter<M>): GenZappStoreValueDelegate<M?> {
  return KeyValueProtoValue(key, adapter, this.store)
}

/**
 * Kotlin delegate that serves as a base for all other value types. This allows us to only expose this sealed
 * class to callers and protect the individual implementations as private behind the various extension functions.
 */
sealed class GenZappStoreValueDelegate<T>(private val store: KeyValueStore) {
  operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
    return getValue(store)
  }

  operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
    setValue(store, value)
  }

  internal abstract fun getValue(values: KeyValueStore): T
  internal abstract fun setValue(values: KeyValueStore, value: T)
}

private class LongValue(private val key: String, private val default: Long, store: KeyValueStore) : GenZappStoreValueDelegate<Long>(store) {
  override fun getValue(values: KeyValueStore): Long {
    return values.getLong(key, default)
  }

  override fun setValue(values: KeyValueStore, value: Long) {
    values.beginWrite().putLong(key, value).apply()
  }
}

private class BooleanValue(private val key: String, private val default: Boolean, store: KeyValueStore) : GenZappStoreValueDelegate<Boolean>(store) {
  override fun getValue(values: KeyValueStore): Boolean {
    return values.getBoolean(key, default)
  }

  override fun setValue(values: KeyValueStore, value: Boolean) {
    values.beginWrite().putBoolean(key, value).apply()
  }
}

private class StringValue<T : String?>(private val key: String, private val default: T, store: KeyValueStore) : GenZappStoreValueDelegate<T>(store) {
  override fun getValue(values: KeyValueStore): T {
    @Suppress("UNCHECKED_CAST")
    return values.getString(key, default) as T
  }

  override fun setValue(values: KeyValueStore, value: T) {
    values.beginWrite().putString(key, value).apply()
  }
}

private class IntValue(private val key: String, private val default: Int, store: KeyValueStore) : GenZappStoreValueDelegate<Int>(store) {
  override fun getValue(values: KeyValueStore): Int {
    return values.getInteger(key, default)
  }

  override fun setValue(values: KeyValueStore, value: Int) {
    values.beginWrite().putInteger(key, value).apply()
  }
}

private class FloatValue(private val key: String, private val default: Float, store: KeyValueStore) : GenZappStoreValueDelegate<Float>(store) {
  override fun getValue(values: KeyValueStore): Float {
    return values.getFloat(key, default)
  }

  override fun setValue(values: KeyValueStore, value: Float) {
    values.beginWrite().putFloat(key, value).apply()
  }
}

private class BlobValue(private val key: String, private val default: ByteArray, store: KeyValueStore) : GenZappStoreValueDelegate<ByteArray>(store) {
  override fun getValue(values: KeyValueStore): ByteArray {
    return values.getBlob(key, default)
  }

  override fun setValue(values: KeyValueStore, value: ByteArray) {
    values.beginWrite().putBlob(key, value).apply()
  }
}

private class NullableBlobValue(private val key: String, private val default: ByteArray?, store: KeyValueStore) : GenZappStoreValueDelegate<ByteArray?>(store) {
  override fun getValue(values: KeyValueStore): ByteArray? {
    return values.getBlob(key, default)
  }

  override fun setValue(values: KeyValueStore, value: ByteArray?) {
    values.beginWrite().putBlob(key, value).apply()
  }
}

private class KeyValueProtoValue<M>(
  private val key: String,
  private val adapter: ProtoAdapter<M>,
  store: KeyValueStore
) : GenZappStoreValueDelegate<M?>(store) {
  override fun getValue(values: KeyValueStore): M? {
    return if (values.containsKey(key)) {
      adapter.decode(values.getBlob(key, null))
    } else {
      null
    }
  }

  override fun setValue(values: KeyValueStore, value: M?) {
    if (value != null) {
      values.beginWrite().putBlob(key, adapter.encode(value)).apply()
    } else {
      values.beginWrite().remove(key).apply()
    }
  }
}

private class KeyValueEnumValue<T>(private val key: String, private val default: T, private val serializer: LongSerializer<T>, store: KeyValueStore) : GenZappStoreValueDelegate<T>(store) {
  override fun getValue(values: KeyValueStore): T {
    return if (values.containsKey(key)) {
      serializer.deserialize(values.getLong(key, 0))
    } else {
      default
    }
  }

  override fun setValue(values: KeyValueStore, value: T) {
    values.beginWrite().putLong(key, serializer.serialize(value)).apply()
  }
}
