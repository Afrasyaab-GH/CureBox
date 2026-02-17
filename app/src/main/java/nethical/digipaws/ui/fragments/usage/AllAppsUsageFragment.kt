package nethical.digipaws.ui.fragments.usage

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.icu.util.Calendar
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.util.Pair
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nethical.digipaws.R
import nethical.digipaws.data.models.AppUsageStat
import nethical.digipaws.databinding.AppUsageItemBinding
import nethical.digipaws.databinding.DialogPermissionInfoBinding
import nethical.digipaws.databinding.FragmentAllAppUsageBinding
import nethical.digipaws.ui.activity.FragmentActivity
import nethical.digipaws.ui.activity.SelectAppsActivity
import nethical.digipaws.utils.SavedPreferencesLoader
import nethical.digipaws.utils.TimeTools
import nethical.digipaws.utils.UsageStatsHelper
import nethical.digipaws.utils.getDefaultLauncherPackageName
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale

class AllAppsUsageFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "all_app_usage"
    }

    private var selectedDate:Long = System.currentTimeMillis()
    private var currentDate:Long = selectedDate
    private var earliestDate:Long = selectedDate

    private var _binding: FragmentAllAppUsageBinding? = null
    private val binding get() = _binding!!

    private var ignoredPackages: MutableSet<String> = mutableSetOf()
    private lateinit var savedPreferencesLoader: SavedPreferencesLoader

    val selectIgnoredAppsLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            selectedApps?.let {
                savedPreferencesLoader.saveIgnoredAppUsageTracker(it.toSet())
                ignoredPackages.addAll(it)
            }
        }
    }

    private var csvDataToExport: String = ""

    private val createCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openOutputStream(it)?.use { stream ->
                        stream.write(csvDataToExport.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Data exported successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("ExportCSV", "Error writing file", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Failed to export data", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAllAppUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        savedPreferencesLoader = SavedPreferencesLoader(requireContext())

        if (!hasUsageStatsPermission(requireContext())) {
            makeUsageStatsPermissoinDialog()
        }

        val adapter = AppUsageAdapter(emptyList())
        binding.appUsageRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.appUsageRecyclerView.adapter = adapter

        lifecycleScope.launch(Dispatchers.IO) {

            getDefaultLauncherPackageName(requireContext().packageManager)?.let {
                ignoredPackages.add(
                    it
                )
            }
            ignoredPackages.addAll(savedPreferencesLoader.loadIgnoredAppUsageTracker())

            setUsageStats()

            findDataAvailabilityRange()
        }
        binding.exportData.setOnClickListener {
            // 1. Select Date Range
            val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("Select Export Range")
                .setSelection(
                    androidx.core.util.Pair(
                        MaterialDatePicker.thisMonthInUtcMilliseconds(),
                        MaterialDatePicker.todayInUtcMilliseconds()
                    )
                )
                .build()

            dateRangePicker.addOnPositiveButtonClickListener { selection ->
                val startDateMs = selection.first
                val endDateMs = selection.second

                // 2. Select Export Format
                val options = arrayOf("Daily Breakdown (Time Series)", "Total Summary", "Both")
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Select Data Format")
                    .setSingleChoiceItems(options, 0) { dialog, which ->
                        // 3. Generate Data based on selection
                        generateAndExportCsv(startDateMs, endDateMs, which)
                        dialog.dismiss()
                    }
                    .show()
            }
            dateRangePicker.show(childFragmentManager, "EXPORT_DATE_picker")
        }
        binding.openMenu.setOnClickListener {
            val popupMenu = PopupMenu(requireContext(), binding.openMenu)
            popupMenu.menuInflater.inflate(R.menu.usage_tracker_options, popupMenu.menu)

            // Handle menu item clicks
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.select_ignored -> {

                        val intent = Intent(requireContext(), SelectAppsActivity::class.java)
                        intent.putStringArrayListExtra(
                            "PRE_SELECTED_APPS",
                            ArrayList(savedPreferencesLoader.loadIgnoredAppUsageTracker())
                        )
                        selectIgnoredAppsLauncher.launch(
                            intent,
                            ActivityOptionsCompat.makeCustomAnimation(
                                requireContext(),
                                R.anim.fade_in,
                                R.anim.fade_out
                            )
                        )
                        true
                    }

                    R.id.add_shortcut_usage_tracker -> {

                        val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                            action = Intent.ACTION_CREATE_SHORTCUT
                        }

                        intent.putExtra("fragment", FRAGMENT_ID)
                        val shortcutInfo =
                            ShortcutInfoCompat.Builder(requireContext(), "digipaws_usage_tracker")
                                .setShortLabel("Usage Stats")
                                .setLongLabel("Usage Stats")
                                .setIntent(intent)
                                .setIcon(
                                    IconCompat.createWithResource(
                                        requireContext(),
                                        R.drawable.baseline_query_stats_24
                                    )
                                )
                                .build()


                        val supported =
                            ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())
                        val dynamicShortcuts =
                            ShortcutManagerCompat.getDynamicShortcuts(requireContext())

                        if (supported) {
                            if (dynamicShortcuts.contains(shortcutInfo)) {
                                return@setOnMenuItemClickListener false
                            }
                        }
                        val pinnedShortcutCallbackIntent =
                            Intent("example.intent.action.SHORTCUT_CREATED")

                        val successCallback = PendingIntent.getBroadcast(
                            requireContext(),
                            3000,
                            pinnedShortcutCallbackIntent,
                            FLAG_IMMUTABLE
                        )

                        ShortcutManagerCompat.requestPinShortcut(
                            requireContext(),
                            shortcutInfo,
                            successCallback.intentSender
                        )

                        true
                    }

                    else -> false
                }
            }

            popupMenu.show()

        }
        binding.selectDate.setOnClickListener {
            showDatePickerDialog(selectedDate, earliestDate, currentDate) { newDate ->
                selectedDate = newDate
                binding.selectDate.text = TimeTools.formatDate(newDate)
                val localDate = Instant.ofEpochMilli(newDate)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()

                lifecycleScope.launch(Dispatchers.IO) {
                    setUsageStats(localDate)
                }

            }
        }

    }

    fun findDataAvailabilityRange() {

        val usageStatsManager = requireContext().getSystemService(UsageStatsManager::class.java)
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            0, System.currentTimeMillis()
        )

        // Calculate earliest available date
        earliestDate = stats.minOfOrNull { it.firstTimeStamp } ?: System.currentTimeMillis()
        currentDate = System.currentTimeMillis()
        selectedDate = currentDate.coerceAtLeast(earliestDate) // Ensure valid range

    }
    override fun onResume() {
        super.onResume()

        lifecycleScope.launch(Dispatchers.IO) {
            val localDate = Instant.ofEpochMilli(selectedDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

            setUsageStats(localDate)
            findDataAvailabilityRange()
        }
    }
    private fun makeUsageStatsPermissoinDialog() {
        val dialogBinding =
            DialogPermissionInfoBinding.inflate(layoutInflater)
        dialogBinding.title.text =
            getString(R.string.enable_2, "Device Usage Access")

        dialogBinding.desc.text =
            "DigiPaws requires device usage access to monitor apps, helping you manage screen time effectively and stay focused on your goals. Rest assured, all data stays securely on your device and is never shared with anyone, ensuring your privacy is fully protected."

        dialogBinding.point1.text = "Track what apps you use"
        dialogBinding.point2.visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .show()

        dialogBinding.btnReject.setOnClickListener {
            dialog.dismiss()
            activity?.finish()
        }
        dialogBinding.btnAccept.setOnClickListener {
            Toast.makeText(requireContext(), "Find 'Digipaws' and press enable", Toast.LENGTH_LONG)
                .show()
            requestUsageStatsPermission(requireContext())
            dialog.dismiss()
        }
    }

    private suspend fun setUsageStats(date : LocalDate = LocalDate.now()) {
        val usageStatsHelper = UsageStatsHelper(requireContext())
        val list = usageStatsHelper.getForegroundStatsByDay(date).filter {
            it.totalTime >= 180_000 && it.packageName !in ignoredPackages
        }
        val totalTime = TimeTools.formatTime(calculateTotalScreenTimeInHours(list),false)

        withContext(Dispatchers.Main) {
            try {
                val adapter = binding.appUsageRecyclerView.adapter as AppUsageAdapter
                if(list.isEmpty()){
                    Toast.makeText(requireContext(),"No data available",Toast.LENGTH_SHORT).show()
                }
                updatePieChart(list)
                binding.totalUsage.text = totalTime

                adapter.updateData(list)
            } catch (e: Exception) {
                Log.e("AppUsageFragment", "Error updating UI with stats", e)
            }
        }
    }

    private fun calculateTotalScreenTimeInHours(stats: List<AppUsageStat>): Long {
        val totalTimeInMillis = stats.sumOf { it.totalTime }

        return totalTimeInMillis
    }

    private fun showDatePickerDialog(
        selectedDate: Long,
        startDate: Long,
        endDate: Long,
        onDateSelected: (Long) -> Unit
    ) {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = selectedDate

        val datePicker = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val pickedCalendar = Calendar.getInstance()
                pickedCalendar.set(year, month, dayOfMonth)
                onDateSelected(pickedCalendar.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // Restrict the selectable date range
        datePicker.datePicker.minDate = startDate
        datePicker.datePicker.maxDate = endDate
        datePicker.show()
    }

    private fun updatePieChart(statsList: List<AppUsageStat>) {
        val sortedStats = statsList.sortedByDescending { it.totalTime }
        val topApps = sortedStats.take(3)

        val othersTime = sortedStats.drop(3)
            .sumOf { it.totalTime }

        val entries = mutableListOf<PieEntry>()
        val pm = requireContext().packageManager
        topApps.forEach { stats ->
            val appInfo = pm.getApplicationInfo(stats.packageName,0)
            val icon = appInfo.loadIcon(pm)
            val usageTime = stats.totalTime

            entries.add(PieEntry(usageTime.toFloat(),resizeIcon(icon,25,25)))
        }

        if (othersTime > 0) {
            entries.add(PieEntry(othersTime.toFloat(), ""))
        }
        val pieDataSet = PieDataSet(entries, "").apply {
            colors = listOf(
                // Material Blue 500
                Color.parseColor("#2196F3"),

                // Material Red 500
                Color.parseColor("#F44336"),

                // Material Green 500
                Color.parseColor("#4CAF50"),

                // Material Yellow 500
                requireContext().getColor(R.color.md_theme_inverseSurface)
            )


            // Add spacing between slices
            sliceSpace = 3f

            setDrawValues(false)

            // Increase selection shift
            selectionShift = 10f

            setGradientColor(
                MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorPrimaryContainer,
                    Color.LTGRAY
                ),
                MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorSecondaryContainer,
                    Color.DKGRAY
                )
            )
        }

        val pieData = PieData(pieDataSet)

        binding.pieChart.apply {
            data = pieData
            description.isEnabled = false
            isRotationEnabled = true

            // Center hole styling
            isDrawHoleEnabled = true
            holeRadius = 85f
            transparentCircleRadius = 0f  // Remove transparent circle
            setHoleColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE))

            legend.isEnabled = false

            // External labels styling
            setDrawEntryLabels(true)  // Disable internal labels
            animateY(1200, Easing.EaseInOutQuart)


            //Todo: Add external labels
            invalidate()
        }
    }

    private fun generateAndExportCsv(startMs: Long, endMs: Long, mode: Int) {
        Toast.makeText(requireContext(), "Generating Analysis CSV...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val usageStatsHelper = UsageStatsHelper(requireContext())
            val pm = requireContext().packageManager
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val header = "Date,App Name,Package Name,Category,Duration (ms),Duration (mins),Is System App,Install Date,Last Update,Start Times\n"

            // Helper to fetch and format a single row
            fun processStatsForRange(start: Long, end: Long, dateLabel: String): String {
                val rangeSb = StringBuilder()
                // Query Usage Stats
                val statsMap = usageStatsHelper.getForegroundStatsByTimestamps(start, end)

                statsMap.forEach { it ->
                    if (it.totalTime > 0) {
                        var appName = it.packageName
                        var category = "Undefined"
                        var isSystem = "No"
                        var installDate = "N/A"
                        var lastUpdate = "N/A"

                        try {
                            val appInfo = pm.getApplicationInfo(it.packageName, 0)
                            val pkgInfo = pm.getPackageInfo(it.packageName, 0)

                            // Sanitize name for CSV
                            appName = appInfo.loadLabel(pm).toString().replace(",", " ")
                            isSystem = if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "Yes" else "No"
                            installDate = dateFormat.format(Date(pkgInfo.firstInstallTime))
                            lastUpdate = dateFormat.format(Date(pkgInfo.lastUpdateTime))

                            category = when (appInfo.category) {
                                ApplicationInfo.CATEGORY_GAME -> "Game"
                                ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                                ApplicationInfo.CATEGORY_VIDEO -> "Video"
                                ApplicationInfo.CATEGORY_AUDIO -> "Audio"
                                else -> "Other"
                            }
                        } catch (e: Exception) { /* App likely uninstalled */ }

                        val minutes = it.totalTime / 1000 / 60


                        rangeSb.append("$dateLabel,$appName,${it.packageName},$category,${it.totalTime},$minutes,$isSystem,$installDate,$lastUpdate\n,${it.startTimes}\n")
                    }
                }
                return rangeSb.toString()
            }

            // --- Mode 0 or 2: Daily Breakdown ---
            if (mode == 0 || mode == 2) {
                sb.append("--- DAILY BREAKDOWN ---\n")
                sb.append(header)

                // Convert to LocalDates to iterate
                val startInstant = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
                val endInstant = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).toLocalDate()

                var current = startInstant
                // Loop through every day in the range
                while (!current.isAfter(endInstant)) {
                    val dayStart = current.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val dayEnd = current.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                    sb.append(processStatsForRange(dayStart, dayEnd, current.toString()))
                    current = current.plusDays(1)
                }
                sb.append("\n")
            }

            // --- Mode 1 or 2: Total Summary ---
            if (mode == 1 || mode == 2) {
                sb.append("--- TOTAL SUMMARY (${dateFormat.format(Date(startMs))} to ${dateFormat.format(Date(endMs))}) ---\n")
                sb.append(header)
                sb.append(processStatsForRange(startMs, endMs, "TOTAL RANGE"))
            }

            csvDataToExport = sb.toString()

            withContext(Dispatchers.Main) {
                val name = "UsageData_${dateFormat.format(Date(startMs))}.csv"
                createCsvLauncher.launch(name)
            }
        }
    }
    private fun resizeIcon(icon: Drawable, width: Int, height: Int): Drawable {
        // Convert Drawable to Bitmap
        val bitmap = if (icon is BitmapDrawable) {
            icon.bitmap
        } else {
            val bitmap = Bitmap.createBitmap(
                icon.intrinsicWidth,
                icon.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            icon.setBounds(0, 0, canvas.width, canvas.height)
            icon.draw(canvas)
            bitmap
        }

        // Calculate the target size in pixels (assuming density is needed)
        val density = Resources.getSystem().displayMetrics.density
        val targetWidth = (width * density).toInt()
        val targetHeight = (height * density).toInt()

        // Create scaled bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(
            bitmap,
            targetWidth,
            targetHeight,
            true
        )

        // Convert back to Drawable
        return BitmapDrawable(Resources.getSystem(), scaledBitmap)
    }

    inner class AppUsageViewHolder(private val binding: AppUsageItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stats: AppUsageStat, packageManager: PackageManager) {
            val appInfo = packageManager.getApplicationInfo(stats.packageName, 0)
            binding.root.setOnClickListener{
                activity?.supportFragmentManager?.beginTransaction()
                    ?.setCustomAnimations(R.anim.fade_in,R.anim.fade_out)
                    ?.replace(R.id.fragment_holder, AppUsageBreakdown(stats))
                    ?.addToBackStack(null)
                    ?.commit()
            }
            binding.root.setOnLongClickListener {

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Add to ignored packages?")
                    .setMessage("This action will cause the tracker to not display any stats from this app.")
                    .setCancelable(true)
                    .setPositiveButton("Okay") { _, _ ->
                        val savedPreferencesLoader = SavedPreferencesLoader(requireContext())
                        val ignoredAppsSP =
                            savedPreferencesLoader.loadIgnoredAppUsageTracker().toMutableSet()
                        ignoredAppsSP.add(stats.packageName)
                        ignoredPackages.addAll(ignoredAppsSP)
                        savedPreferencesLoader.saveIgnoredAppUsageTracker(ignoredAppsSP)

                        lifecycleScope.launch(Dispatchers.IO) {
                            val localDate = Instant.ofEpochMilli(selectedDate)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()

                            setUsageStats(localDate)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }

            // Load app icon and label on the main thread
            binding.appIcon.setImageDrawable(appInfo.loadIcon(packageManager))
            binding.appName.text = appInfo.loadLabel(packageManager)
            binding.appUsage.text = TimeTools.formatTime(stats.totalTime)
        }
    }

    inner class AppUsageAdapter(
        private var appUsageStats: List<AppUsageStat>
    ) : RecyclerView.Adapter<AppUsageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppUsageViewHolder {
            val binding = AppUsageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AppUsageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: AppUsageViewHolder, position: Int) {
            holder.bind(appUsageStats[position], holder.itemView.context.packageManager)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newAppUsageStats: List<AppUsageStat>) {
            appUsageStats = newAppUsageStats

            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = appUsageStats.size
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestUsageStatsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    @Deprecated("Use AppUsageStat from data.models instead", ReplaceWith("AppUsageStat", "nethical.digipaws.data.models.AppUsageStat"))
    class Stat(
        val packageName: String,
        val totalTime: Long,
        val startTimes: List<ZonedDateTime>
    )

}