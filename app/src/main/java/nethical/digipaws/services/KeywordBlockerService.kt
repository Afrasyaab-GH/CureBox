package nethical.digipaws.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import nethical.digipaws.blockers.BrowserBlocker
import nethical.digipaws.blockers.KeywordBlocker
import nethical.digipaws.data.blockers.KeywordPacks

class KeywordBlockerService : BaseBlockingService() {

    private var eventThrottleDelayMs = 1000
    private var lastContentChangeTimestamp = 0L
    companion object {
        const val INTENT_ACTION_REFRESH_BLOCKED_KEYWORD_LIST =
            "nethical.digipaws.refresh.keywordblocker.blockedwords"

        const val INTENT_ACTION_REFRESH_CONFIG =
            "nethical.digipaws.refresh.keywordblocker.config"
    }

    private val keywordBlocker = KeywordBlocker(this)
    private val browserBlocker = BrowserBlocker(this)
    private var keywordBlockerIgnoredApps: HashSet<String> = hashSetOf()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (!isDelayOver(
                lastContentChangeTimestamp,
                eventThrottleDelayMs
            ) || event == null || event.packageName == "nethical.digipaws" || keywordBlockerIgnoredApps.contains(
                event.packageName
            )
        ) {
            return
        }
        val rootNode: AccessibilityNodeInfo? = rootInActiveWindow
        Log.d("KeywordBlocker", "Searching Keywords")
        handleKeywordBlockerResult(keywordBlocker.detectBlockedKeywords(rootNode, event))
        handleBrowserBlockerResult(browserBlocker.isAppBrowser(event))
        lastContentChangeTimestamp = SystemClock.uptimeMillis()

    }

    override fun onInterrupt() {
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        setupBlockedWords()
        setupConfig()

        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_BLOCKED_KEYWORD_LIST)
            addAction(INTENT_ACTION_REFRESH_CONFIG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
    }


    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_ACTION_REFRESH_BLOCKED_KEYWORD_LIST -> setupBlockedWords()
                INTENT_ACTION_REFRESH_CONFIG -> setupConfig()
            }
        }
    }

    private fun handleKeywordBlockerResult(result: KeywordBlocker.KeywordBlockerResult) {
        if (result.resultDetectWord == null) return
        Toast.makeText(
            this,
            "Blocked keyword ${result.resultDetectWord} was found.",
            Toast.LENGTH_LONG
        ).show()
        if (result.isHomePressRequested) {
            pressHome()
        }
    }

    private fun handleBrowserBlockerResult(isBlocked: Boolean){
        if (!isBlocked) return
        Toast.makeText(
            this,
            "Unsupported Browser Blocked",
            Toast.LENGTH_LONG
        ).show()
        pressHome()
    }

    private fun setupBlockedWords() {
        val keywords = savedPreferencesLoader.loadBlockedKeywords().toMutableSet()
        val sp = getSharedPreferences("keyword_blocker_packs", Context.MODE_PRIVATE)
        val isAdultBlockerOn = sp.getBoolean("adult_blocker", false)
        if (isAdultBlockerOn) {
            keywords.addAll(KeywordPacks.adultKeywords)
        }
        keywordBlocker.blockedKeyword = keywords.toHashSet()
    }

    private fun setupConfig() {
        val sp = getSharedPreferences("keyword_blocker_configs", Context.MODE_PRIVATE)

        keywordBlocker.isSearchAllTextFields = sp.getBoolean("search_all_text_fields", false)
        browserBlocker.isTurnedOn = sp.getBoolean("is_block_all_other_browsers", false)

        keywordBlocker.redirectUrl =
            sp.getString("redirect_url", "https://www.youtube.com/watch?v=x31tDT-4fQw&t=1s")
                .toString()

        if (keywordBlocker.isSearchAllTextFields) {
            eventThrottleDelayMs = 5000
        }

        keywordBlockerIgnoredApps = savedPreferencesLoader.getKeywordBlockerIgnoredApps().toHashSet()

    }



    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(refreshReceiver)
    }
}