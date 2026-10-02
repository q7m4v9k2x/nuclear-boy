package com.nuclearboy.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 接收更新通知点击 → 触发应用内 APK 下载
 */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        val version = intent.getStringExtra("version") ?: "latest"
        val expectedSize = intent.getLongExtra("expected_size", 0L)
        val expectedDigest = intent.getStringExtra("expected_digest").orEmpty()
        Log.e("NuclearBoy", "[DownloadReceiver] 开始下载: v$version url=$url")
        UpdateDownloader.download(context, url, version, expectedSize, expectedDigest)
    }
}
