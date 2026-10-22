package io.github.imroxy2222.lsp_colorOSRecord

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method

/**
 * 通话录音列表界面注入"一键转文本"浮动按钮。
 *
 * hook `CallRecordListFragment.onResume()`，在 Activity 的 contentView 上加一个 Button。
 * 点击时后台扫 RecorderDB 最近 10 条 record_type==3（通话录音），用
 * MediaDBUtils.queryIdByData(路径) 拿 MediaStore _id，调 trigger.onMediaIdSynced 触发转文本。
 * trigger 内部有 checkAlreadyConvertComplete + 60s 去重，已转的自动跳过。
 *
 * 设计原因：VoIP 通话录音（QQ/微信）结束时录音机进程不运行，模块无法自动触发。
 * 改为用户打开录音机 App 后，在通话录音列表点一下按钮批量触发，进程肯定存活。
 */
class ScanButtonInjector(
    private val api: XposedInterface,
    private val param: XposedModuleInterface.PackageReadyParam
) {

    fun install() {
        val cl = param.classLoader
        val onResumeMethod: Method = try {
            Class.forName(CLS_BROWSE_FILE, false, cl)
                .getDeclaredMethod("onResume")
        } catch (t: Throwable) {
            XLog.w("BrowseFile.onResume not found: ${t.message}")
            return
        }

        api.hook(onResumeMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    // BrowseFile 是 Activity，chain.thisObject 就是 Activity 实例
                    val activity = chain.thisObject as? Activity
                        ?: return@intercept result
                    injectButton(activity)
                } catch (t: Throwable) {
                    XLog.w("scan button inject failed", t)
                }
                result
            }
        XLog.i("hooked BrowseFile.onResume [scan-button]")
    }

    private fun injectButton(activity: Activity) {
        val contentView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // 防重复注入
        if (contentView.findViewWithTag<View>(TAG_BTN) != null) return

        val button = Button(activity).apply {
            text = "一键转文本"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#CC1E88E5"))
            val hPad = dpToPx(activity, 20)
            val vPad = dpToPx(activity, 8)
            setPadding(hPad, vPad, hPad, vPad)
            tag = TAG_BTN
            setOnClickListener { onScanClicked(activity) }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            val margin = dpToPx(activity, 24)
            bottomMargin = margin
            rightMargin = margin
        }
        contentView.addView(button, params)
        XLog.i("scan button injected into ${activity.javaClass.simpleName}")
    }

    private fun onScanClicked(activity: Activity) {
        val trigger = TriggerHolder.trigger
        if (trigger == null) {
            Toast.makeText(activity, "转文本引擎未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, "开始扫描通话录音…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                XLog.i("scan button clicked, scanning latest 10 call records")
                val cl = param.classLoader
                val mediaDbCls = Class.forName(CLS_MEDIA_DB_UTILS, false, cl)
                val queryIdByData = mediaDbCls.getMethod(M_QUERY_ID_BY_DATA, String::class.java)
                val resolver = activity.contentResolver
                val queryUri = Uri.parse(
                    "content://$RECORDER_PROVIDER_AUTHORITY/$RECORDER_PATH_RECORDS?limit=10"
                )
                val cursor = resolver.query(
                    queryUri,
                    arrayOf(COL_DATA, COL_DISPLAY_NAME),
                    "$COL_RECORD_TYPE = ?",
                    arrayOf(TYPE_CALL.toString()),
                    "$COL_ID DESC"
                )
                // 开启一轮批量：清空旧队列，绑定上下文；队列内部逐条驱动，
                // 每条转完由 FileAsrController.toastAsrComplete 等 hook 回调推进，
                // 不再用固定 sleep —— 长录音也会等到真正转完才继续。
                ConvertQueue.startBatch(activity, trigger)
                var scanned = 0
                var enqueued = 0
                var skipped = 0
                cursor?.use { c ->
                    val dataIdx = c.getColumnIndex(COL_DATA)
                    while (c.moveToNext()) {
                        val data = if (dataIdx >= 0) c.getString(dataIdx) else null
                        if (data.isNullOrEmpty()) continue
                        var mediaId = -1L
                        try {
                            mediaId = queryIdByData.invoke(null, data) as? Long ?: -1L
                        } catch (t: Throwable) {
                            XLog.d("queryIdByData failed for $data: ${t.message}")
                        }
                        if (mediaId <= 0L) {
                            skipped++
                            continue
                        }
                        scanned++
                        XLog.d("scan[$scanned]: mediaId=$mediaId data=$data")
                        ConvertQueue.enqueue(mediaId)
                        enqueued++
                    }
                }
                val msg = "扫描完成：共 $scanned 条，入队 $enqueued 条" +
                    if (skipped > 0) "，跳过 $skipped 条" else ""
                XLog.i(msg)
                if (enqueued == 0) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(activity, "没有需要转文本的通话录音", Toast.LENGTH_LONG).show()
                    }
                }
                // 队列自行驱动后续条目，无需在此 sleep
            } catch (t: Throwable) {
                XLog.w("scan failed", t)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(activity, "扫描失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            isDaemon = true
            name = "CallRecConvert-Scan"
        }.start()
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val CLS_BROWSE_FILE = "com.soundrecorder.browsefile.BrowseFile"
        private const val TAG_BTN = "callrec_scan_btn"
    }
}
