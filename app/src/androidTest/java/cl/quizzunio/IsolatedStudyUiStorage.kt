package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cl.quizzunio.study.LocalQuizGenerator
import cl.quizzunio.study.StudyDocumentReader
import cl.quizzunio.study.StudyQuizRepository
import cl.quizzunio.study.StudyQuizViewModel
import java.io.File
import java.util.UUID

internal class IsolatedStudyUiStorage(activity: ComponentActivity) {
    private val base = activity.applicationContext
    private val id = "study-ui-${UUID.randomUUID()}"
    private val root = File(base.cacheDir, id).apply { check(mkdirs()) }
    val context: Context = object : ContextWrapper(activity) {
        override fun getApplicationContext() = this
        override fun getNoBackupFilesDir() = root
        override fun getFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${id}_$name", mode)
    }

    fun model(activity: ComponentActivity): StudyQuizViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return StudyQuizViewModel(StudyQuizRepository(context), StudyDocumentReader(context), LocalQuizGenerator()) as T
            }
        }
        return ViewModelProvider(activity, factory)[StudyQuizViewModel::class.java]
    }
}
