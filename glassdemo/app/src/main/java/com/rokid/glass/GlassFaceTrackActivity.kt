package com.rokid.glass

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glesse.databinding.ActivityFaceTrackBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.FaceModel
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.FaceRecgResult
import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.LPRModel
import com.rokid.security.sdk.base.common.out.AIRecgMode.Companion.MODE_FACE
import com.rokid.security.sdk.base.common.outside.PersonnelLibInfo
import com.rokid.security.system.server.media.callback.VideoCallback
import com.rokid.security.system.server.recog.offline.callback.CheckUpdateCallback
import com.rokid.security.system.server.recog.offline.feature.listener.IGlassRecListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 离线人脸检测 + 本地人脸库比对示例（通过平台按场景下发人脸库）。
 *
 * 界面分层，避免回调刷屏：
 * - tvStatus：库状态 / 场景 / 下载进度
 * - tvDetect：人脸检测数量（仅变化时刷新）
 * - tvResult：最近一次比对结果（命中）
 * - tvLog：只保留最近几条关键事件
 * - ivCapture：人脸小图
 *
 * sceneId 默认使用 "hh"，可根据实际场景自定义，也可通过 Intent extra "scene_id" 覆盖。
 */
class GlassFaceTrackActivity : BaseActivity() {
    private val TAG = "GlassFaceTrackActivity"
    private lateinit var binding: ActivityFaceTrackBinding
    private var oldBitmap: Bitmap? = null

    /** 关键事件日志，最多保留 [MAX_LOG_LINES] 行。 */
    private val logLines = ArrayDeque<String>()

    /** 当前场景 ID，默认值与平台上配置的离线库场景保持一致。 */
    private var sceneId: String = DEFAULT_SCENE_ID

    /** 识别是否已启动，避免重复 start。 */
    private var recognitionStarted = false

    /** 上一次检测到的人脸数量，-1 表示还没回调过。 */
    private var lastFaceCount = -1

    /** 上一次比对结果（用于去重，避免同一张脸反复刷新）。 */
    private var lastResultKey = ""

    private var lastResultAt = 0L

    private val mOfflineRecService by lazy {
        GlassSdk.getGlassOfflineRecService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sceneId = intent?.getStringExtra(EXTRA_SCENE_ID)?.takeIf { it.isNotBlank() } ?: DEFAULT_SCENE_ID
        setStatus("离线人脸 · $sceneId")
        setDetect("等待检测…")
        initView()
    }

    private fun initView() {
        val rec = mOfflineRecService
        if (rec == null) {
            setStatus("离线识别服务不可用")
            return
        }
        val hasLocalLib = runCatching { rec.isExistLib(sceneId) }.getOrDefault(false)
        addLog("本地人脸库: ${if (hasLocalLib) "已存在" else "不存在"}")
        setStatus("正在检查人脸库…")

        // 1. 拉取/更新人脸库。连着手机时由手机代拉，没连手机时眼镜直连云端。
        rec.checkUpdateLibVersion(sceneId, object : CheckUpdateCallback.Stub() {
            override fun onProgress(progress: Int) {
                setStatus("人脸库下载中 $progress%")
            }

            override fun onFinish(libInfo: PersonnelLibInfo?) {
                val name = libInfo?.libraryName ?: "未命名"
                val version = libInfo?.libVersion ?: "-"
                setStatus("库已就绪 · $name v$version")
                addLog("人脸库更新完成: $name v$version")
                startRecognition()
            }

            override fun onFail(code: Int, errMsg: String?) {
                when (code) {
                    -4 -> setStatus("未配置离线库(-4)，用本地库")
                    -2 -> setStatus("未获取到设备 SN(-2)")
                    -3 -> setStatus("人脸库下载/激活失败(-3)")
                    else -> setStatus("人脸库更新失败($code)")
                }
                addLog("拉库失败 code=$code, msg=$errMsg")
                startRecognition()
            }
        })
    }

    /** 2. 用本地人脸库启动离线识别（比对在眼镜端完成，不依赖网络）。 */
    private fun startRecognition() {
        val rec = mOfflineRecService ?: return
        if (recognitionStarted) return
        recognitionStarted = true
        runCatching {
            rec.startRecognition(sceneId, MODE_FACE, mOfflineRecListener)
            addLog("离线识别已启动")
        }.onFailure {
            addLog("启动失败: ${it.message}")
        }
    }

    /**
     * 视频录制回调
     * Video recording callback.
     */
    private val videoCallback = object : VideoCallback.Stub() {
        override fun onError() {
            Log.d(TAG, "onError: ")
        }

        override fun onFinish() {
            Log.d(TAG, "onFinish: ")
        }

        override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
            Log.d(TAG, "onNewFile: $path")
        }

        override fun onErrorWithDetail(code: Int, errorMsg: String) {
            Log.d(TAG, "onError: code = $code, errorMsg = $errorMsg")
        }

        override fun onStart() {
            Log.d(TAG, "onStart: ")
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        if (recognitionStarted) {
            runCatching { mOfflineRecService?.stopRecognition(sceneId, mOfflineRecListener) }
            recognitionStarted = false
        }
        if (oldBitmap?.isRecycled == false) {
            oldBitmap?.recycle()
            oldBitmap = null
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        super.onGlassKeyEvent(keyEvent)
        if (keyEvent == com.rokid.glass.base.GlassKeyEvent.KEYCODE_FRONT) {
        } else if (keyEvent == com.rokid.glass.base.GlassKeyEvent.KEYCODE_BEHIND) {
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * 离线识别回调：检测结果 + 本地人脸库比对结果。
     */
    private val mOfflineRecListener = object : IGlassRecListener.Stub() {
        override fun getCallbackId(): String = "glassdemo_face_$sceneId"

        override fun onModeChange(mode: Int) {
            Log.d(TAG, "onModeChange: $mode")
        }

        override fun onFaceTrack(faceModels: MutableList<FaceModel>?) {
            val count = faceModels?.size ?: 0
            if (count == lastFaceCount) return
            lastFaceCount = count
            setDetect(if (count > 0) "检测到 $count 张人脸" else "未检测到人脸")
        }

        override fun onLPRTrack(lprModel: LPRModel?) {
        }

        /** 命中本地人脸库 */
        override fun onFaceRecognize(result: FaceRecgResult?) {
            result ?: return
            val name = displayName(result.label)
            val text = "命中：$name  ${formatScore(result.regScore)}"
            if (setResult(text, key = "hit:$name")) {
                showFaceImage(result)
            }
        }

        /**
         * 未命中本地人脸库：不在界面上展示，只写调试日志。
         */
        override fun onFaceRecognizeNotInLib(result: FaceRecgResult?) {
            result ?: return
            Log.d(TAG, "not in library, score=${formatScore(result.regScore)}, trackId=${result.trackId}")
        }

        override fun onEngineInit() {
            addLog("识别引擎初始化完成")
        }
    }

    /** 3. 取人脸小图并展示。 */
    private fun showFaceImage(result: FaceRecgResult) {
        val frameId = runCatching { result.faceModel.frameId }.getOrDefault(-1L)
        val bitmap = runCatching {
            mOfflineRecService?.getFaceSamllBitmap(frameId, result.trackId)
        }.getOrNull() ?: return
        if (bitmap.isRecycled) return
        if (oldBitmap?.isRecycled == false) {
            oldBitmap?.recycle()
        }
        oldBitmap = bitmap
        val sizeInfo = getBitmapSizeInfo(bitmap)
        lifecycleScope.launch(Dispatchers.Main) {
            binding.ivCapture.setImageBitmap(bitmap)
        }
        addLog("小图 $sizeInfo · trackId=${result.trackId}")
    }

    private fun getBitmapSizeInfo(bitmap: Bitmap?): String {
        if (bitmap == null || bitmap.isRecycled) {
            return "0 B"
        }
        val sizeText = FileSizeUtil.formatFileSize(bitmap.allocationByteCount.toDouble())
        return "${bitmap.width}x${bitmap.height}, $sizeText"
    }

    /**
     * 从引擎返回的 label 中解析人名。
     *
     * 离线库的 label 是竖线分隔的字段串，例如：
     * `374689888739201923384966065|hh|null` → 第 2 段是姓名，`null`/空段忽略。
     * 如果 label 不是这种格式，就原样展示。
     */
    private fun displayName(label: String?): String {
        val raw = label?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return "未知"
        val parts = raw.split("|").map { it.trim() }
        if (parts.size >= 2) {
            val name = parts[1].takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            if (!name.isNullOrEmpty()) return name
        }
        return parts.firstOrNull { it.isNotEmpty() && !it.equals("null", ignoreCase = true) } ?: "未知"
    }

    /**
     * 统一相似度显示：引擎有的版本返回 0~1，有的返回 0~100，这里自动归一成百分比。
     */
    private fun formatScore(score: Float): String {
        val percent = if (score <= 1f) score * 100f else score
        return "%.1f%%".format(percent)
    }

    // ---------------- UI 更新 ----------------

    private fun setStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    private fun setDetect(text: String) {
        runOnUiThread { binding.tvDetect.text = text }
    }

    /**
     * 更新比对结果，同一结果短时间内不重复刷新，避免闪烁/刷屏。
     *
     * @return true 表示这次确实更新了界面（调用方可以据此刷新小图/日志）。
     */
    private fun setResult(text: String, key: String): Boolean {
        val now = System.currentTimeMillis()
        if (key == lastResultKey && now - lastResultAt < RESULT_DEBOUNCE_MS) return false
        lastResultKey = key
        lastResultAt = now
        runOnUiThread {
            binding.tvResult.text = text
            binding.clHitCard.visibility = View.VISIBLE
        }
        addLog(text)
        return true
    }

    /** 只保留最近几条关键事件。 */
    private fun addLog(msg: String) {
        logLines.addLast(msg)
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        val text = logLines.joinToString("\n")
        runOnUiThread { binding.tvLog.text = text }
        Log.d(TAG, "----$msg")
    }

    companion object {
        /** 默认场景 ID，可自定义（按实际场景修改，或通过 Intent 覆盖）。 */
        private const val DEFAULT_SCENE_ID = "hh"

        /** 可选：通过 Intent 传入其他 sceneId。 */
        const val EXTRA_SCENE_ID = "scene_id"

        private const val MAX_LOG_LINES = 3

        private const val RESULT_DEBOUNCE_MS = 3000L
    }
}
