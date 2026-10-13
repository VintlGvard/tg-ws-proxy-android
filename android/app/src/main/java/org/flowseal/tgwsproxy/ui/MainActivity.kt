package org.flowseal.tgwsproxy.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.flowseal.tgwsproxy.R
import org.flowseal.tgwsproxy.databinding.ActivityMainBinding
import org.flowseal.tgwsproxy.databinding.BottomSheetSettingsBinding
import org.flowseal.tgwsproxy.service.TgWsProxyService
import org.flowseal.tgwsproxy.util.SettingsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Уведомления необходимы для стабильной работы службы", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        setupListeners()
        observeState()
        updateSettingsSummary()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupListeners() {
        binding.btnToggleProxy.setOnClickListener {
            if (TgWsProxyService.isRunning.value) {
                stopProxyService()
            } else {
                startProxyService()
            }
        }

        binding.btnConnectTelegram.setOnClickListener {
            connectToTelegram()
        }

        binding.btnQuickSettings.setOnClickListener {
            showSmartSettingsDialog()
        }

        binding.btnOpenSmartSettings.setOnClickListener {
            showSmartSettingsDialog()
        }

        binding.btnCopyLogs.setOnClickListener {
            val text = binding.tvLogs.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy Logs", text))
            Toast.makeText(this, "Логи скопированы в буфер", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvLogs.text = "Журнал очищен.\n"
        }
    }

    private fun startProxyService() {
        val port = SettingsManager.getPort(this)
        val secret = SettingsManager.getSecret(this)
        val domain = SettingsManager.getDomain(this)

        val intent = Intent(this, TgWsProxyService::class.java).apply {
            action = TgWsProxyService.ACTION_START
            putExtra(TgWsProxyService.EXTRA_PORT, port)
            putExtra(TgWsProxyService.EXTRA_SECRET, secret)
            putExtra(TgWsProxyService.EXTRA_DOMAIN, domain)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopProxyService() {
        val intent = Intent(this, TgWsProxyService::class.java).apply {
            action = TgWsProxyService.ACTION_STOP
        }
        startService(intent)
    }

    private fun connectToTelegram() {
        val port = SettingsManager.getPort(this)
        val secret = SettingsManager.getSecret(this)

        if (!TgWsProxyService.isRunning.value) {
            startProxyService()
        }

        val uri = Uri.parse("tg://proxy?server=127.0.0.1&port=$port&secret=$secret")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Telegram не найден на этом устройстве", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            TgWsProxyService.isRunning.collectLatest { running ->
                updateUiRunningState(running)
            }
        }

        lifecycleScope.launch {
            TgWsProxyService.statsFlow.collectLatest { stats ->
                binding.tvStatsMetrics.text =
                    "Активно: ${stats.active} | Входящий: %.2f MB | Исходящий: %.2f MB".format(
                        stats.downMb,
                        stats.upMb
                    )
            }
        }

        lifecycleScope.launch {
            TgWsProxyService.logFlow.collectLatest { logLine ->
                binding.tvLogs.append("$logLine\n")
                binding.scrollLogs.post {
                    binding.scrollLogs.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun updateUiRunningState(running: Boolean) {
        val port = SettingsManager.getPort(this)

        if (running) {
            binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_active))
            binding.tvStatusTitle.text = getString(R.string.status_running, port.toString())
            binding.btnToggleProxy.text = getString(R.string.btn_stop)
            binding.btnToggleProxy.setIconResource(R.drawable.ic_stop)
            binding.btnToggleProxy.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_inactive)
        } else {
            binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
            binding.tvStatusTitle.text = getString(R.string.status_stopped)
            binding.btnToggleProxy.text = getString(R.string.btn_start)
            binding.btnToggleProxy.setIconResource(R.drawable.ic_play)
            binding.btnToggleProxy.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        }
    }

    private fun updateSettingsSummary() {
        val currentDomain = SettingsManager.getDomain(this)
        val preset = SettingsManager.PRESET_DOMAINS.find { it.first == currentDomain }
        val presetName = preset?.second ?: "Пользовательский"
        val port = SettingsManager.getPort(this)
        val cfStatus = if (SettingsManager.isCloudflareFallback(this)) "включен" else "выключен"

        binding.tvSettingsSummaryDomain.text = "Маскировка (SNI): $currentDomain ($presetName)"
        binding.tvSettingsSummaryPort.text = "Порт: $port • Cloudflare Fallback: $cfStatus"
    }

    private fun showSmartSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        val currentDomain = SettingsManager.getDomain(this)
        sheetBinding.etDomain.setText(currentDomain)
        sheetBinding.etSecret.setText(SettingsManager.getSecret(this))
        sheetBinding.etPort.setText(SettingsManager.getPort(this).toString())
        sheetBinding.switchCloudflare.isChecked = SettingsManager.isCloudflareFallback(this)

        when (currentDomain) {
            "google.com" -> sheetBinding.chipGoogle.isChecked = true
            "icloud.com" -> sheetBinding.chipApple.isChecked = true
            "cloudflare.com" -> sheetBinding.chipCloudflare.isChecked = true
            "microsoft.com" -> sheetBinding.chipMicrosoft.isChecked = true
            "yandex.ru" -> sheetBinding.chipYandex.isChecked = true
        }

        fun applyPreset(domain: String) {
            sheetBinding.etDomain.setText(domain)
            val newSecret = SettingsManager.generateSecretForDomain(domain)
            sheetBinding.etSecret.setText(newSecret)
        }

        sheetBinding.chipGoogle.setOnClickListener { applyPreset("google.com") }
        sheetBinding.chipApple.setOnClickListener { applyPreset("icloud.com") }
        sheetBinding.chipCloudflare.setOnClickListener { applyPreset("cloudflare.com") }
        sheetBinding.chipMicrosoft.setOnClickListener { applyPreset("microsoft.com") }
        sheetBinding.chipYandex.setOnClickListener { applyPreset("yandex.ru") }

        sheetBinding.btnGenerateSecret.setOnClickListener {
            val domain = sheetBinding.etDomain.text.toString().trim().ifBlank { "google.com" }
            val newSecret = SettingsManager.generateRandomSecretForDomain(domain)
            sheetBinding.etSecret.setText(newSecret)
            Toast.makeText(this, "Сгенерирован новый Fake TLS ключ", Toast.LENGTH_SHORT).show()
        }

        sheetBinding.btnCopySecret.setOnClickListener {
            val secret = sheetBinding.etSecret.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy Secret", secret))
            Toast.makeText(this, "Секрет скопирован в буфер", Toast.LENGTH_SHORT).show()
        }

        sheetBinding.btnResetDefaults.setOnClickListener {
            SettingsManager.resetToDefaults(this)
            sheetBinding.etDomain.setText(SettingsManager.DEFAULT_DOMAIN)
            sheetBinding.etSecret.setText(SettingsManager.getSecret(this))
            sheetBinding.etPort.setText(SettingsManager.DEFAULT_PORT.toString())
            sheetBinding.switchCloudflare.isChecked = true
            sheetBinding.chipGoogle.isChecked = true
            Toast.makeText(this, "Настройки сброшены к рекомендованным", Toast.LENGTH_SHORT).show()
        }

        sheetBinding.btnSaveSettings.setOnClickListener {
            val port = sheetBinding.etPort.text.toString().toIntOrNull() ?: 1443
            val domain = sheetBinding.etDomain.text.toString().trim().ifBlank { "google.com" }
            var secret = sheetBinding.etSecret.text.toString().trim()
            if (secret.isBlank()) {
                secret = SettingsManager.generateSecretForDomain(domain)
            }

            SettingsManager.setPort(this, port)
            SettingsManager.setDomain(this, domain)
            SettingsManager.setSecret(this, secret)
            SettingsManager.setCloudflareFallback(this, sheetBinding.switchCloudflare.isChecked)

            updateSettingsSummary()

            if (TgWsProxyService.isRunning.value) {
                stopProxyService()
                binding.root.postDelayed({ startProxyService() }, 300)
            }

            dialog.dismiss()
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }
}
