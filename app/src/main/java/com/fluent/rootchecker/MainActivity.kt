package com.fluent.rootchecker

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fluent.rootchecker.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: CheckAdapter
    private var scanJob: Job? = null
    private var pulse: ObjectAnimator? = null
    private var heroColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppContext(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CheckAdapter()
        binding.checkList.layoutManager = LinearLayoutManager(this)
        binding.checkList.adapter = adapter

        binding.checkButton.setOnClickListener { startScan() }
        binding.langButton.setOnClickListener { showLanguageMenu() }

        showHero(
            title = getString(R.string.hero_idle_title),
            subtitle = getString(R.string.hero_idle_sub),
            iconRes = R.drawable.ic_shield,
            iconColor = ContextCompat.getColor(this, R.color.primary),
            bgColor = ContextCompat.getColor(this, R.color.hero_idle_bg),
            animate = false
        )

        // Автозапуск диагностики при открытии
        binding.checkButton.postDelayed({ startScan() }, 450)
    }

    override fun onDestroy() {
        scanJob?.cancel()
        pulse?.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------- language

    private fun showLanguageMenu() {
        val popup = PopupMenu(this, binding.langButton)
        val menu = popup.menu

        val languages = listOf(
            LANG_RU to getString(R.string.lang_russian),
            LANG_EN to getString(R.string.lang_english),
            LANG_UK to getString(R.string.lang_ukrainian)
        )
        languages.forEachIndexed { index, (_, label) ->
            menu.add(MENU_GROUP, index, index, label)
        }
        menu.setGroupCheckable(MENU_GROUP, true, true)

        val current = currentLanguageTag()
        val currentIndex = languages.indexOfFirst { it.first == current }
        if (currentIndex >= 0) {
            menu.findItem(currentIndex)?.isChecked = true
        }

        popup.setOnMenuItemClickListener { item ->
            val (tag, _) = languages[item.itemId]
            if (tag != current) {
                // Пересоздаёт активити с новой локалью; выбор сохраняется appcompat'ом
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            true
        }
        popup.show()
    }

    /** Текущий язык приложения: выбранный вручную или язык системы. */
    private fun currentLanguageTag(): String {
        val selected = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (selected.isNotEmpty()) {
            return selected.split(",").first()
        }
        return resources.configuration.locales[0].language
    }

    // ----------------------------------------------------------------- scan

    private fun startScan() {
        scanJob?.cancel()
        adapter.reset()
        binding.checkList.scrollToPosition(0)

        binding.checkButton.isEnabled = false
        binding.checkButton.text = getString(R.string.scanning)
        binding.progress.progress = 0
        binding.progress.isVisible = true

        showHero(
            title = getString(R.string.hero_scanning_title),
            subtitle = getString(R.string.hero_scanning_sub),
            iconRes = R.drawable.ic_shield,
            iconColor = ContextCompat.getColor(this, R.color.primary),
            bgColor = ContextCompat.getColor(this, R.color.hero_idle_bg),
            animate = true
        )
        startPulse()

        scanJob = lifecycleScope.launch {
            var hasRoot = false
            var hasWarning = false
            val total = RootScanner.checks.size

            RootScanner.checks.forEachIndexed { index, check ->
                delay(90)
                val result = withContext(Dispatchers.IO) { check() }
                adapter.append(result)
                binding.progress.setProgressCompat(((index + 1) * 100) / total, true)

                when (result.status) {
                    CheckStatus.ROOT -> hasRoot = true
                    CheckStatus.WARNING -> hasWarning = true
                    else -> Unit
                }
            }

            delay(200)
            finishScan(hasRoot, hasWarning)
        }
    }

    private fun finishScan(hasRoot: Boolean, hasWarning: Boolean) {
        stopPulse()
        binding.progress.isVisible = false
        binding.checkButton.isEnabled = true
        binding.checkButton.text = getString(R.string.retry)

        when {
            hasRoot -> showHero(
                title = getString(R.string.hero_root_title),
                subtitle = getString(R.string.hero_root_sub),
                iconRes = R.drawable.ic_error,
                iconColor = ContextCompat.getColor(this, R.color.status_root),
                bgColor = ContextCompat.getColor(this, R.color.hero_root_bg)
            )
            hasWarning -> showHero(
                title = getString(R.string.hero_warn_title),
                subtitle = getString(R.string.hero_warn_sub),
                iconRes = R.drawable.ic_warning,
                iconColor = ContextCompat.getColor(this, R.color.status_warn),
                bgColor = ContextCompat.getColor(this, R.color.hero_warn_bg)
            )
            else -> showHero(
                title = getString(R.string.hero_safe_title),
                subtitle = getString(R.string.hero_safe_sub),
                iconRes = R.drawable.ic_check_circle,
                iconColor = ContextCompat.getColor(this, R.color.status_safe),
                bgColor = ContextCompat.getColor(this, R.color.hero_safe_bg)
            )
        }
    }

    // ------------------------------------------------------------------- herо

    private fun showHero(
        title: String,
        subtitle: String,
        iconRes: Int,
        iconColor: Int,
        bgColor: Int,
        animate: Boolean = true
    ) {
        binding.heroTitle.text = title
        binding.heroSubtitle.text = subtitle

        if (animate && heroColor != 0 && heroColor != bgColor) {
            ValueAnimator.ofArgb(heroColor, bgColor).apply {
                duration = 380
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    binding.heroCard.setCardBackgroundColor(it.animatedValue as Int)
                }
                start()
            }
        } else {
            binding.heroCard.setCardBackgroundColor(bgColor)
        }
        heroColor = bgColor

        if (animate) {
            binding.heroIcon.animate().cancel()
            binding.heroIcon
                .animate()
                .alpha(0f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(160)
                .withEndAction {
                    binding.heroIcon.setImageResource(iconRes)
                    binding.heroIcon.setColorFilter(iconColor)
                    binding.heroIcon
                        .animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240)
                        .start()
                }
                .start()
        } else {
            binding.heroIcon.setImageResource(iconRes)
            binding.heroIcon.setColorFilter(iconColor)
            binding.heroIcon.alpha = 1f
            binding.heroIcon.scaleX = 1f
            binding.heroIcon.scaleY = 1f
        }
    }

    private fun startPulse() {
        pulse?.cancel()
        binding.heroIcon.scaleX = 1f
        binding.heroIcon.scaleY = 1f
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.heroIcon,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.12f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.12f)
        ).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        binding.heroIcon.scaleX = 1f
        binding.heroIcon.scaleY = 1f
    }

    companion object {
        private const val LANG_RU = "ru"
        private const val LANG_EN = "en"
        private const val LANG_UK = "uk"
        private const val MENU_GROUP = 1
    }
}
