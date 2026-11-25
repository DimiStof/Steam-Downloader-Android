package com.example.steamfetch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File

/** Saved Steam refresh tokens (one per account), stored encrypted. Passwords are never stored. */
class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "steam_tokens",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun accounts(): List<String> = prefs.all.keys.sorted()
    fun get(account: String): String? = prefs.getString(account, null)
    fun save(account: String, token: String) = prefs.edit().putString(account, token).apply()
    fun remove(account: String) = prefs.edit().remove(account).apply()
}

class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var downloadDir: String
        get() = p.getString("dir", null) ?: defaultDir()
        set(v) = p.edit().putString("dir", v).apply()

    /** Which platform's depots to download: windows, linux or macos. */
    var targetOs: String
        get() = p.getString("os", "windows") ?: "windows"
        set(v) = p.edit().putString("os", v).apply()

    var preferCode: Boolean
        get() = p.getBoolean("preferCode", false)
        set(v) = p.edit().putBoolean("preferCode", v).apply()

    companion object {
        fun defaultDir(): String = File(Environment.getExternalStorageDirectory(), "SteamFetch").absolutePath
    }
}

object Storage {
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun request(ctx: Context, legacyLauncher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } else {
            legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
