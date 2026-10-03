package androidx.lifecycle

open class LiveData<T>(private var _value: T? = null) {
  open val value: T?
    get() = _value

  open fun observeForever(observer: (T) -> Unit) {}
  open fun removeObserver(observer: (T) -> Unit) {}
}

open class MutableLiveData<T>(initialValue: T? = null) : LiveData<T>(initialValue) {
  private var _mutValue: T? = initialValue

  override val value: T?
    get() = _mutValue

  fun setValue(v: T?) {
    _mutValue = v
  }

  fun postValue(v: T?) {
    _mutValue = v
  }
}
