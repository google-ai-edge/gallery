package androidx.work

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID

class Data(val values: Map<String, Any> = emptyMap()) {
  fun getString(key: String): String? = values[key] as? String
  fun getInt(key: String, defaultValue: Int = 0): Int = (values[key] as? Int) ?: defaultValue
  fun getLong(key: String, defaultValue: Long = 0L): Long = (values[key] as? Long) ?: defaultValue
  fun getFloat(key: String, defaultValue: Float = 0f): Float = (values[key] as? Float) ?: defaultValue
  fun getBoolean(key: String, defaultValue: Boolean = false): Boolean = (values[key] as? Boolean) ?: defaultValue

  class Builder {
    private val map = mutableMapOf<String, Any>()
    fun putString(key: String, value: String?) = apply { value?.let { map[key] = it } }
    fun putInt(key: String, value: Int) = apply { map[key] = value }
    fun putLong(key: String, value: Long) = apply { map[key] = value }
    fun putFloat(key: String, value: Float) = apply { map[key] = value }
    fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
    fun build(): Data = Data(map)
  }
}

enum class ExistingWorkPolicy { KEEP, REPLACE, APPEND, APPEND_OR_REPLACE }
enum class OutOfQuotaPolicy { RUN_AS_NON_EXPEDITED_WORK_REQUEST, DROP_WORK_REQUEST }

class WorkInfo(
  val id: UUID = UUID.randomUUID(),
  val state: State = State.SUCCEEDED,
  val progress: Data = Data(),
  val outputData: Data = Data(),
) {
  enum class State {
    ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED;
    val isFinished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
  }
}

class OneTimeWorkRequest(val id: UUID = UUID.randomUUID())

class OneTimeWorkRequestBuilder<T> {
  fun setExpedited(policy: OutOfQuotaPolicy) = this
  fun setInputData(data: Data) = this
  fun addTag(tag: String) = this
  fun build(): OneTimeWorkRequest = OneTimeWorkRequest()
}

class WorkManager {
  fun enqueueUniqueWork(uniqueWorkName: String, existingWorkPolicy: ExistingWorkPolicy, request: OneTimeWorkRequest) {}
  fun cancelUniqueWork(uniqueWorkName: String) {}
  fun getWorkInfosForUniqueWorkLiveData(uniqueWorkName: String): LiveData<List<WorkInfo>> = MutableLiveData(emptyList())

  companion object {
    private val instance = WorkManager()

    @JvmStatic
    fun getInstance(context: Context): WorkManager = instance
  }
}
