package com.yatori.android.data.local.dao

import androidx.room.*
import com.yatori.android.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY startTime DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = 'RUNNING'")
    fun observeRunning(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, endTime = :endTime, errorMessage = :err WHERE id = :id")
    suspend fun finish(id: String, status: String, endTime: Long, err: String = "")

    @Query("UPDATE tasks SET doneCourses = :doneCourses, doneVideos = :doneVideos, totalCourses = :totalCourses, totalVideos = :totalVideos WHERE id = :id")
    suspend fun updateProgress(id: String, doneCourses: Int, doneVideos: Int, totalCourses: Int, totalVideos: Int)

    @Query("DELETE FROM tasks WHERE startTime < :before")
    suspend fun deleteOlderThan(before: Long)
}
