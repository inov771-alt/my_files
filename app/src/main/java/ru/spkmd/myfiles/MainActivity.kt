package ru.spkmd.myfiles

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Экран при запуске иконки: объясняет, как пользоваться приложением. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = "Откройте любой файл или фото в другом приложении, нажмите «Поделиться» " +
                "и выберите «Мои файлы» — файлы будут загружены на files.spkmd.ru."
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFF4F6F8.toInt())
            fitsSystemWindows = true
            addView(title)
            addView(hint)
        }
        setContentView(root)
    }
}
