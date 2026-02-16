package nethical.digipaws.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import nethical.digipaws.Constants
import nethical.digipaws.blockers.AppBlocker
import nethical.digipaws.blockers.FocusModeBlocker
import nethical.digipaws.ui.activity.MainActivity
import nethical.digipaws.ui.activity.WarningActivity
import nethical.digipaws.utils.NotificationTimerManager
import nethical.digipaws.utils.UsageStatsHelper
import nethical.digipaws.utils.getCurrentKeyboardPackageName
import nethical.digipaws.utils.getDefaultLauncherPackageName

class AppBlockerService : BaseBlockingService() {

    companion object {
        /**
         * Refreshes information about warning screen, cheat hours and blocked app list
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "nethical.digipaws.refresh.appblocker"

        /**
         * Add cooldown to an app.
         * This broadcast should always be sent together with the following keys:
         * selected_time: Int -> Duration of cooldown in minutes
         * result_id : String -> Package name of app to be put into cooldown
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN =
            "nethical.digipaws.refresh.appblocker.cooldown"

        /**
         * Refreshes information related to focus mode.
         */

        const val INTENT_ACTION_REFRESH_FOCUS_MODE = "nethical.digipaws.refresh.focus_mode"
    }

    private var appBlockerWarning = MainActivity.WarningData()
    private lateinit var appBlocker : AppBlocker

    private val focusModeBlocker = FocusModeBlocker()

    // Triggers a recheck after cooldown/cheat-hours expire, even when no new accessibility event fires
    private val recheckHandler = Handler(Looper.getMainLooper())
    private var recheckRunnable: Runnable? = null

    private lateinit var notificationManager: NotificationTimerManager

    private var lastForegroundPackage = ""

    override fun onCreate() {
        appBlocker = AppBlocker(this)
        super.onCreate()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName.toString()
        if (lastForegroundPackage == packageName || packageName == getPackageName()) return

        lastForegroundPackage = packageName
        Log.d("AppBlockerService", "Switched to app $packageName")

        val focusModeResult = focusModeBlocker.doesAppNeedToBeBlocked(packageName)
        if (focusModeResult.isBlocked) {
            handleFocusModeBlockerResult(focusModeResult)
            return
        }
        handleAppBlockerResult(appBlocker.doesAppNeedToBeBlocked(packageName), packageName)
    }


    private fun handleAppBlockerResult(result: AppBlocker.AppBlockerResult, packageName: String) {
        Log.d("AppBlockerService", "$packageName result : $result")

        if(packageName == "com.android.systemui") return // to allow notification panel to be used
        if (result.cheatHoursEndTime != -1L) {
            setUpForcedRefreshChecker(packageName, result.cheatHoursEndTime)
        }
        if (result.cooldownEndTime != -1L) {
            setUpForcedRefreshChecker(packageName, result.cooldownEndTime)
        }
        if(result.usageLimitReached == false && result.remainingUsage != 0L){
            notificationManager.startTimer(result.remainingUsage, timerIdU = packageName)
            setUpForcedRefreshChecker(packageName, result.remainingUsage + SystemClock.uptimeMillis())
        }else{
            notificationManager.stopTimer()
        }

        if (!result.isBlocked) return

        notificationManager.stopTimer()
        if (appBlockerWarning.isWarningDialogHidden) {
            pressHome()
            lastForegroundPackage = ""
            return
        }

        pressHome()
        lastForegroundPackage = ""

        Thread.sleep(300)
        val dialogIntent = Intent(this, WarningActivity::class.java)
        dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        dialogIntent.putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
        dialogIntent.putExtra("result_id", packageName)
        startActivity(dialogIntent)

    }

    private fun handleFocusModeBlockerResult(result: FocusModeBlocker.FocusModeResult) {
        if (result.isRequestingToUpdateSPData) {
            savedPreferencesLoader.saveFocusModeData(focusModeBlocker.focusModeData)
        }

        if (!result.isBlocked) return

        pressHome()
        lastForegroundPackage = ""
        Toast.makeText(this, "This app is currently under focus mode", Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        setupAppBlocker()
        setupFocusMode()
        notificationManager = NotificationTimerManager(this)
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_FOCUS_MODE)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(refreshReceiver, filter)
        }
    }


    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_FOCUS_MODE -> setupFocusMode()
                INTENT_ACTION_REFRESH_APP_BLOCKER -> setupAppBlocker()
                INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> {
                    val interval =
                        intent.getIntExtra("selected_time", appBlockerWarning.timeInterval)
                    val coolPackage = intent.getStringExtra("result_id") ?: ""
                    val cooldownUntil =
                        SystemClock.uptimeMillis() + interval
                    appBlocker.putCooldownTo(
                        coolPackage,
                        cooldownUntil
                    )
                    setUpForcedRefreshChecker(coolPackage, cooldownUntil)

                }
            }

        }
    }

    /**
     * Setup a runnable that fires at [endMillis] to re-evaluate whether the
     * currently-foreground app should be blocked (e.g. after a cooldown or cheat-hours window expires).
     */
    private fun setUpForcedRefreshChecker(cooldownPackage: String, endMillis: Long) {
        recheckRunnable?.let { recheckHandler.removeCallbacks(it) }
        recheckRunnable = null

        Log.d("AppBlockerService", "Scheduling recheck for $cooldownPackage")
        recheckRunnable = Runnable {
            Log.d("AppBlockerService", "Triggered recheck for $cooldownPackage")
            try {
                if (rootInActiveWindow.packageName == cooldownPackage) {
                    handleAppBlockerResult(
                        AppBlocker.AppBlockerResult(true),
                        cooldownPackage
                    )
                    lastForegroundPackage = ""
                    appBlocker.removeCooldownFrom(cooldownPackage)
                }
            } catch (e: Exception) {
                Log.e("AppBlockerService", e.toString())
                setUpForcedRefreshChecker(cooldownPackage, endMillis + 60_000)
            }
        }

        recheckHandler.postAtTime(recheckRunnable!!, endMillis)
    }
    private fun setupAppBlocker() {
        appBlocker.blockedAppsList = savedPreferencesLoader.loadBlockedApps()
        Log.d("blocked Apps List updated",appBlocker.blockedAppsList.toString())
        appBlocker.refreshCheatHoursData(savedPreferencesLoader.loadAppBlockerCheatHoursList())
        appBlockerWarning = savedPreferencesLoader.loadAppBlockerWarningInfo()
    }

    fun setupFocusMode() {
        focusModeBlocker.refreshCheatHoursData(savedPreferencesLoader.loadAutoFocusHoursList())

        val selectedFocusModeApps = savedPreferencesLoader.getFocusModeSelectedApps().toHashSet()
        val focusModeData = savedPreferencesLoader.getFocusModeData()

        // As all apps wil get blocked except the selected ones, add essential packages that need not be blocked
        // to the list of selected apps
        if (focusModeData.modeType == Constants.FOCUS_MODE_BLOCK_ALL_EX_SELECTED) {
            selectedFocusModeApps.add("com.android.systemui")
            getDefaultLauncherPackageName(packageManager)?.let { selectedFocusModeApps.add(it) }
            getCurrentKeyboardPackageName(this)?.let { selectedFocusModeApps.add(it) }
        }

        focusModeData.selectedApps = selectedFocusModeApps
        focusModeBlocker.focusModeData = focusModeData

    }

    override fun onDestroy() {
        super.onDestroy()
        recheckRunnable?.let { recheckHandler.removeCallbacks(it) }
        recheckRunnable = null
        unregisterReceiver(refreshReceiver)
    }

}
