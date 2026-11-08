/*
 * SPDX-FileCopyrightText: AxionOS
 * SPDX-FileCopyrightText: LineageOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.spoof

import android.app.ActivityManager
import android.app.AxSettingsSpoofManager
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Settings-value controls adapted from the spoofing section of Axion's Sandbox. */
class SettingsValueSpoofSettings : SettingsPreferenceFragment() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = AxSettingsSpoofManager.getInstance()
    private val userId = UserHandle.myUserId()
    private var selectedPackage: String? = null
    private var installedApps = emptyList<Pair<String, String>>()
    private var loadingApps = true
    private var operationInFlight = false
    private var controlsLoaded = false
    private var refreshJob: Job? = null
    private lateinit var appPreference: ListPreference
    private lateinit var importPreference: Preference
    private lateinit var exportPreference: Preference
    private val controls = mutableListOf<Pair<SwitchPreferenceCompat, List<String>>>()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri ->
        if (uri != null) runOperation {
            val context = requireContext()
            val installed = installedApps.map { it.second }.toSet()
            withContext(Dispatchers.IO) {
                val data = context.contentResolver.openInputStream(uri)?.use {
                    it.readNBytes(MAX_BACKUP_BYTES + 1)
                } ?: throw IOException("Unable to read backup")
                require(data.size <= MAX_BACKUP_BYTES) { "Backup exceeds size limit" }
                val backup = JSONObject(String(data, Charsets.UTF_8))
                require(backup.opt("version") == 1) { "Unsupported backup version" }
                val apps = backup.getJSONArray("apps")
                val imported = linkedMapOf<String, Set<String>>()
                for (i in 0 until apps.length()) {
                    val app = apps.getJSONObject(i)
                    val pkg = app.getString("packageName")
                    require(!app.has("userId") || app.opt("userId") is Int) {
                        "Invalid backup user"
                    }
                    if (app.optInt("userId", 0) != userId || pkg !in installed) continue
                    if (!app.has("spoofSettings")) continue
                    val values = app.getJSONObject("spoofSettings")
                    require(pkg !in imported) { "Duplicate app in backup" }
                    val enabled = ALL_KEYS.filter { key ->
                        if (!values.has(key)) false else {
                            require(values.opt(key) is Boolean) { "Invalid setting: $key" }
                            values.getBoolean(key)
                        }
                    }.toSet()
                    imported[pkg] = enabled
                }
                // Validate the entire applicable backup before changing service state.
                for ((pkg, enabled) in imported) {
                    for (key in ALL_KEYS) {
                        manager.setSpoofSettingEnabled(pkg, key, key in enabled, userId)
                    }
                    context.getSystemService(ActivityManager::class.java)
                        .forceStopPackageAsUser(pkg, userId)
                }
            }
            toast(R.string.settings_spoof_imported)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runOperation {
            val context = requireContext()
            val installed = installedApps.toList()
            withContext(Dispatchers.IO) {
                val apps = JSONArray()
                for ((_, pkg) in installed) {
                    val enabled = manager.getEnabledSpoofSettings(pkg, userId).toSet()
                    if (enabled.isEmpty()) continue
                    val values = JSONObject()
                    ALL_KEYS.forEach { values.put(it, it in enabled) }
                    apps.put(JSONObject().put("packageName", pkg).put("userId", userId)
                        .put("spoofSettings", values))
                }
                val backup = JSONObject().put("version", 1)
                    .put("timestamp", System.currentTimeMillis()).put("apps", apps)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(backup.toString(2).toByteArray(Charsets.UTF_8))
                } ?: throw IOException("Unable to write backup")
            }
            toast(R.string.settings_spoof_exported)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        selectedPackage = savedInstanceState?.getString(SELECTED_PACKAGE)
        val context = requireContext()
        requireActivity().title = getString(R.string.spoof_settings_title)
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        appPreference = ListPreference(context).apply {
            key = SELECTED_PACKAGE
            isPersistent = false
            setTitle(R.string.settings_spoof_app)
            summary = getString(R.string.settings_spoof_choose_app)
            setOnPreferenceChangeListener { _, value ->
                selectedPackage = value as String
                refreshControls()
                true
            }
        }
        preferenceScreen.addPreference(appPreference)
        val entries = listOf(
            Triple(listOf("adb_enabled"), R.string.spoof_adb_enabled,
                R.string.spoof_adb_enabled_description),
            Triple(listOf("development_settings_enabled"), R.string.spoof_dev_settings_enabled,
                R.string.spoof_dev_settings_enabled_description),
            Triple(listOf("adb_wifi_enabled"), R.string.spoof_adb_wifi_enabled,
                R.string.spoof_adb_wifi_enabled_description),
            Triple(listOf("package_verifier_user_consent"), R.string.spoof_package_verifier,
                R.string.spoof_package_verifier_description),
            Triple(listOf("verify_apps_over_usb"), R.string.spoof_verify_apps_usb,
                R.string.spoof_verify_apps_usb_description),
            Triple(ACCESSIBILITY_KEYS, R.string.spoof_accessibility,
                R.string.spoof_accessibility_description),
        )
        entries.forEach { (keys, title, summary) ->
            val control = SwitchPreferenceCompat(context).apply {
                key = keys.first()
                isPersistent = false
                isEnabled = false
                setTitle(title)
                setSummary(summary)
                setOnPreferenceChangeListener { _, value ->
                    val pkg = selectedPackage ?: return@setOnPreferenceChangeListener false
                    val enabled = value as Boolean
                    runOperation {
                        withContext(Dispatchers.IO) {
                            keys.forEach {
                                manager.setSpoofSettingEnabled(pkg, it, enabled, userId)
                            }
                            context.getSystemService(ActivityManager::class.java)
                                .forceStopPackageAsUser(pkg, userId)
                        }
                    }
                    false // Refresh from the service after the write succeeds.
                }
            }
            controls.add(control to keys)
            preferenceScreen.addPreference(control)
        }
        importPreference = Preference(context).apply {
            setTitle(R.string.settings_spoof_import)
            setOnPreferenceClickListener {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                true
            }
        }
        preferenceScreen.addPreference(importPreference)
        exportPreference = Preference(context).apply {
            setTitle(R.string.settings_spoof_export)
            setOnPreferenceClickListener {
                exportLauncher.launch("settings-spoof.json")
                true
            }
        }
        preferenceScreen.addPreference(exportPreference)
        updateEnabledState()
        scope.launch {
            try {
                installedApps = withContext(Dispatchers.IO) {
                    context.packageManager.getInstalledApplications(0)
                        .filter { it.packageName != "android"
                            && !it.packageName.startsWith("com.android.")
                            && !it.packageName.startsWith("com.google.android.") }
                        .map { it.loadLabel(context.packageManager).toString() to it.packageName }
                        .sortedBy { it.first.lowercase() }
                }
                appPreference.entries = installedApps.map { it.first }.toTypedArray()
                appPreference.entryValues = installedApps.map { it.second }.toTypedArray()
                if (installedApps.none { it.second == selectedPackage }) selectedPackage = null
                appPreference.value = selectedPackage
                loadingApps = false
                refreshControls()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailure(e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SELECTED_PACKAGE, selectedPackage)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun getMetricsCategory(): Int = MetricsEvent.VIEW_UNKNOWN

    private fun refreshControls() {
        if (!isAdded || loadingApps || operationInFlight) return
        refreshJob?.cancel()
        controlsLoaded = false
        updateEnabledState()
        val pkg = selectedPackage
        appPreference.summary = installedApps.firstOrNull { it.second == pkg }?.first
            ?: getString(R.string.settings_spoof_choose_app)
        refreshJob = scope.launch {
            try {
                val enabled = if (pkg == null) emptySet() else withContext(Dispatchers.IO) {
                    manager.getEnabledSpoofSettings(pkg, userId).toSet()
                }
                if (selectedPackage != pkg) return@launch
                controls.forEach { (preference, keys) ->
                    preference.isChecked = keys.all { it in enabled }
                }
                controlsLoaded = true
                updateEnabledState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailure(e)
            }
        }
    }

    private fun runOperation(operation: suspend () -> Unit) {
        if (!isAdded || loadingApps || operationInFlight) return
        operationInFlight = true
        refreshJob?.cancel()
        updateEnabledState()
        scope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailure(e)
            } finally {
                operationInFlight = false
                if (isAdded) refreshControls()
            }
        }
    }

    private fun toast(resource: Int) {
        context?.let { Toast.makeText(it, resource, Toast.LENGTH_SHORT).show() }
    }

    private fun showFailure(error: Exception) {
        context?.let {
            Toast.makeText(it, it.getString(R.string.ts_failed, error.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun updateEnabledState() {
        val ready = !loadingApps && !operationInFlight
        appPreference.isEnabled = ready
        importPreference.isEnabled = ready
        exportPreference.isEnabled = ready
        controls.forEach { it.first.isEnabled = ready && controlsLoaded && selectedPackage != null }
    }

    companion object {
        private const val SELECTED_PACKAGE = "settings_spoof_selected_package"
        private const val MAX_BACKUP_BYTES = 1024 * 1024
        private val ACCESSIBILITY_KEYS = listOf("accessibility_enabled",
            "enabled_accessibility_services", "accessibility_display_inversion_enabled")
        private val ALL_KEYS = listOf("adb_enabled", "development_settings_enabled",
            "adb_wifi_enabled", "package_verifier_user_consent", "verify_apps_over_usb") +
            ACCESSIBILITY_KEYS
    }
}
