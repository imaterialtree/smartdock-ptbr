package cu.axel.smartdock.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView.OnItemClickListener
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.widget.addTextChangedListener
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cu.axel.smartdock.R
import cu.axel.smartdock.activities.MainActivity
import cu.axel.smartdock.adapters.AppActionsAdapter
import cu.axel.smartdock.adapters.AppAdapter
import cu.axel.smartdock.adapters.AppAdapter.OnAppClickListener
import cu.axel.smartdock.adapters.AppShortcutAdapter
import cu.axel.smartdock.adapters.AppTaskAdapter
import cu.axel.smartdock.adapters.DockAppAdapter
import cu.axel.smartdock.adapters.DockAppAdapter.OnDockAppClickListener
import cu.axel.smartdock.db.DBHelper
import cu.axel.smartdock.models.Action
import cu.axel.smartdock.models.App
import cu.axel.smartdock.models.AppTask
import cu.axel.smartdock.models.DockApp
import cu.axel.smartdock.receivers.BatteryStatsReceiver
import cu.axel.smartdock.receivers.SoundEventsReceiver
import cu.axel.smartdock.utils.AppUtils
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.DeepShortcutManager
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.OnSwipeListener
import cu.axel.smartdock.utils.Utils
import cu.axel.smartdock.widgets.HoverInterceptorLayout
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class DockService : AccessibilityService(), OnSharedPreferenceChangeListener, OnTouchListener, OnAppClickListener, OnDockAppClickListener {
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var activityManager: ActivityManager
    private lateinit var appsBtn: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var homeBtn: ImageView
    private lateinit var recentBtn: ImageView
    private lateinit var assistBtn: ImageView
    private lateinit var powerBtn: ImageView
    private lateinit var bluetoothBtn: ImageView
    private lateinit var wifiBtn: ImageView
    private lateinit var batteryBtn: ImageView
    private lateinit var volBtn: ImageView
    private lateinit var pinBtn: ImageView
    private lateinit var notificationBtn: TextView
    private lateinit var searchTv: TextView
    private lateinit var topRightCorner: Button
    private lateinit var bottomRightCorner: Button
    private lateinit var dockHandle: Button
    private lateinit var appMenu: LinearLayout
    private lateinit var searchLayout: LinearLayout
    private var powerMenu: LinearLayout? = null
    private var audioPanel: LinearLayout? = null
    private var wifiPanel: LinearLayout? = null
    private lateinit var searchEntry: LinearLayout
    private lateinit var dockLayout: RelativeLayout
    private lateinit var windowManager: WindowManager
    private lateinit var appsSeparator: View
    private var appMenuVisible = false
    private var powerMenuVisible = false
    private var isPinned = false
    private var audioPanelVisible = false
    private var wifiPanelVisible = false
    private var systemApp = false
    private var preferLastDisplay = false
    private lateinit var dockLayoutParams: WindowManager.LayoutParams
    private lateinit var searchEt: EditText
    private lateinit var tasksGv: RecyclerView
    private lateinit var favoritesGv: RecyclerView
    private lateinit var appsGv: RecyclerView
    private lateinit var wifiManager: WifiManager
    private lateinit var batteryReceiver: BatteryStatsReceiver
    private lateinit var soundEventsReceiver: SoundEventsReceiver
    private lateinit var gestureDetector: GestureDetector
    private lateinit var db: DBHelper
    private lateinit var dockHandler: Handler
    private lateinit var dock: HoverInterceptorLayout
    private lateinit var bm: BluetoothManager
    private lateinit var dockTrigger: View
    private lateinit var pinnedApps: ArrayList<App>
    private lateinit var dateTv: TextClock
    private var maxApps = 0
    private lateinit var context: Context
    private lateinit var tasks: ArrayList<AppTask>
    private var lastUpdate: Long = 0
    private var previousActivity: String? = null
    override fun onCreate() {
        super.onCreate()
        db = DBHelper(this)
        activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        preferLastDisplay = sharedPreferences.getBoolean("prefer_last_display", false)
        context = DeviceUtils.getDisplayContext(this, preferLastDisplay)
        windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        dockHandler = Handler(Looper.getMainLooper())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Utils.startupTime = System.currentTimeMillis()
        systemApp = AppUtils.isSystemApp(context, packageName)
        maxApps = sharedPreferences.getString("max_running_apps", "10")!!.toInt()

        //Create the dock
        dock = LayoutInflater.from(context).inflate(R.layout.dock, null) as HoverInterceptorLayout
        dockLayout = dock.findViewById(R.id.dock_layout)
        dockTrigger = dock.findViewById(R.id.dock_trigger)
        dockHandle = dock.findViewById(R.id.dock_handle)
        appsBtn = dock.findViewById(R.id.apps_btn)
        tasksGv = dock.findViewById(R.id.apps_lv)
        val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        tasksGv.layoutManager = layoutManager
        backBtn = dock.findViewById(R.id.back_btn)
        homeBtn = dock.findViewById(R.id.home_btn)
        recentBtn = dock.findViewById(R.id.recents_btn)
        assistBtn = dock.findViewById(R.id.assist_btn)
        notificationBtn = dock.findViewById(R.id.notifications_btn)
        pinBtn = dock.findViewById(R.id.pin_btn)
        bluetoothBtn = dock.findViewById(R.id.bluetooth_btn)
        wifiBtn = dock.findViewById(R.id.wifi_btn)
        volBtn = dock.findViewById(R.id.volume_btn)
        batteryBtn = dock.findViewById(R.id.battery_btn)
        dateTv = dock.findViewById(R.id.date_btn)
        dock.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                if (dockLayout.visibility == View.GONE) showDock()
            } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) if (dockLayout.visibility == View.VISIBLE) {
                hideDock(sharedPreferences.getString("dock_hide_delay", "500")!!.toInt())
            }
            false
        }
        gestureDetector = GestureDetector(context, object : OnSwipeListener() {
            override fun onSwipe(direction: Direction): Boolean {
                if (direction == Direction.UP) {
                    if (!isPinned) pinDock() else if (!appMenuVisible) showAppMenu()
                } else if (direction == Direction.DOWN) {
                    if (appMenuVisible) hideAppMenu() else unpinDock()
                } else if (direction == Direction.LEFT) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return true
            }
        })
        dock.setOnTouchListener(this)
        dockLayout.setOnTouchListener(this)
        dockHandle.alpha = 0.01f * sharedPreferences.getString("handle_opacity", "50")!!.toInt()
        dockHandle.setOnClickListener { pinDock() }
        appsBtn.setOnClickListener { toggleAppMenu() }
        appsBtn.setOnLongClickListener {
            launchApp(null, Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        assistBtn.setOnClickListener { launchAssistant() }
        backBtn.setOnClickListener { performGlobalAction(GLOBAL_ACTION_BACK) }
        homeBtn.setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME) }
        recentBtn.setOnClickListener { performGlobalAction(GLOBAL_ACTION_RECENTS) }
        recentBtn.setOnLongClickListener {
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            true
        }
        notificationBtn.setOnClickListener {
            if (sharedPreferences.getBoolean("enable_notif_panel", true)) {
                if (Utils.notificationPanelVisible) sendBroadcast(Intent("$packageName.DOCK").putExtra("action", "HIDE_NOTIF_PANEL")) else {
                    if (audioPanelVisible) hideAudioPanel()
                    if (wifiPanelVisible) hideWiFiPanel()
                    sendBroadcast(Intent("$packageName.DOCK").putExtra("action", "SHOW_NOTIF_PANEL"))
                }
            } else performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        }
        pinBtn.setOnClickListener { togglePin() }
        bluetoothBtn.setOnClickListener { toggleBluetooth() }
        bluetoothBtn.setOnLongClickListener {
            launchApp(null, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        wifiBtn.setOnClickListener { toggleWifi() }
        wifiBtn.setOnLongClickListener {
            launchApp(null, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        volBtn.setOnClickListener { toggleVolume() }
        volBtn.setOnLongClickListener {
            launchApp(null, Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        batteryBtn.setOnClickListener {
            launchApp(null,
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        dateTv.setOnClickListener { launchApp(null, sharedPreferences.getString("app_clock", "com.android.deskclock")!!) }
        dateTv.setOnLongClickListener {
            launchApp(null, Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        dockLayoutParams = Utils.makeWindowParams(-1, -2, context, preferLastDisplay)
        dockLayoutParams.screenOrientation = if (sharedPreferences.getBoolean("lock_landscape", false))
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        dockLayoutParams.gravity = Gravity.BOTTOM or Gravity.START
        windowManager.addView(dock, dockLayoutParams)

        //Hot corners
        topRightCorner = Button(context)
        topRightCorner.setBackgroundResource(R.drawable.corner_background)
        bottomRightCorner = Button(context)
        bottomRightCorner.setBackgroundResource(R.drawable.corner_background)
        topRightCorner.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                val handler = Handler(mainLooper)
                handler.postDelayed({ if (topRightCorner.isHovered) performGlobalAction(GLOBAL_ACTION_RECENTS) }, sharedPreferences.getString("hot_corners_delay", "300")!!.toInt().toLong())
            }
            false
        }
        bottomRightCorner.setOnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                val handler = Handler(mainLooper)
                handler.postDelayed({ if (bottomRightCorner.isHovered) DeviceUtils.lockScreen(context) }, sharedPreferences.getString("hot_corners_delay", "300")!!.toInt().toLong())
            }
            false
        }
        updateCorners()
        val cornersLayoutParams = Utils.makeWindowParams(Utils.dpToPx(context, 2), -2, context,
                preferLastDisplay)
        cornersLayoutParams.gravity = Gravity.TOP or Gravity.END
        windowManager.addView(topRightCorner, cornersLayoutParams)
        cornersLayoutParams.gravity = Gravity.BOTTOM or Gravity.END
        windowManager.addView(bottomRightCorner, cornersLayoutParams)

        //App menu
        appMenu = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
                .inflate(R.layout.apps_menu, null) as LinearLayout
        searchEntry = appMenu.findViewById(R.id.search_entry)
        searchEt = appMenu.findViewById(R.id.menu_et)
        powerBtn = appMenu.findViewById(R.id.power_btn)
        appsGv = appMenu.findViewById(R.id.menu_applist_lv)
        appsGv.layoutManager = GridLayoutManager(context, 5)
        favoritesGv = appMenu.findViewById(R.id.fav_applist_lv)
        favoritesGv.layoutManager = GridLayoutManager(context, 5)
        searchLayout = appMenu.findViewById(R.id.search_layout)
        searchTv = appMenu.findViewById(R.id.search_tv)
        appsSeparator = appMenu.findViewById(R.id.apps_separator)
        powerBtn.setOnClickListener {
            if (sharedPreferences.getBoolean("enable_power_menu", false)) {
                if (powerMenuVisible) hidePowerMenu() else showPowerMenu()
            } else performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            hideAppMenu()
        }
        searchTv.setOnClickListener {
            try {
                launchApp(null,
                        Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q="
                                        + URLEncoder.encode(searchEt.text.toString(), "UTF-8")))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: UnsupportedEncodingException) {
                throw RuntimeException(e)
            }
        }

        searchEt.addTextChangedListener { text ->
            if (text != null) {
                val appAdapter = appsGv.adapter as AppAdapter
                appAdapter.filter(text.toString())
                if (text.length > 1) {
                    searchLayout.visibility = View.VISIBLE
                    searchTv.text = getString(R.string.search_for) + " \"" + text + "\" " + getString(R.string.on_google)
                    toggleFavorites(false)
                } else {
                    searchLayout.visibility = View.GONE
                    toggleFavorites(AppUtils.getPinnedApps(context, packageManager, AppUtils.PINNED_LIST).size > 0)
                }
            }
        }

        searchEt.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_ENTER && searchEt.text.toString().length > 1) {
                    try {
                        launchApp(null,
                                Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://www.google.com/search?q="
                                                + URLEncoder.encode(searchEt.text.toString(), "UTF-8")))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: UnsupportedEncodingException) {
                        throw RuntimeException(e)
                    }
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN)
                    appsGv.requestFocus()
            }
            false
        }
        UpdateAppMenuTask().execute()

        //TODO: Filter app button menu click only
        appMenu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE
                    && (event.y < appMenu.measuredHeight || event.x > appMenu.measuredWidth)) {
                hideAppMenu()
            }
            false
        }

        //Listen for launcher messages
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("action")) {
                    "resume" -> pinDock()
                    "launch" -> launchApp(intent.getStringExtra("mode"), intent.getStringExtra("app")!!)
                }
            }
        }, object : IntentFilter("$packageName.HOME") {})

        //Tell the launcher the service has connected
        sendBroadcast(Intent("$packageName.SERVICE").putExtra("action", "CONNECTED"))

        //Register receivers
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(p1: Context, p2: Intent) {
                if (p2.getStringExtra("action") == "COUNT_CHANGED") {
                    val count = p2.getIntExtra("count", 0)
                    if (count > 0) {
                        notificationBtn.setBackgroundResource(R.drawable.circle)
                        notificationBtn.text = count.toString() + ""
                    } else {
                        notificationBtn.setBackgroundResource(R.drawable.ic_expand_up_circle)
                        notificationBtn.text = ""
                    }
                } else {
                    takeScreenshot()
                }
            }
        }, IntentFilter("$packageName.NOTIFICATION_PANEL"))
        batteryReceiver = BatteryStatsReceiver(batteryBtn)
        registerReceiver(batteryReceiver, IntentFilter("android.intent.action.BATTERY_CHANGED"))
        soundEventsReceiver = SoundEventsReceiver()
        val soundEventsFilter = IntentFilter()
        soundEventsFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        soundEventsFilter.addAction(Intent.ACTION_POWER_CONNECTED)
        registerReceiver(soundEventsReceiver, soundEventsFilter)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(p1: Context, p2: Intent) {
                applyTheme()
            }
        }, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))

        //Play startup sound
        DeviceUtils.playEventSound(context, "startup_sound")
        updateNavigationBar()
        updateQuickSettings()
        updateDockShape()
        applyTheme()
        updateMenuIcon()
        loadPinnedApps()
        placeRunningApps()
        updateDockTrigger()
        if (sharedPreferences.getBoolean("pin_dock", true))
            pinDock()
        else
            Toast.makeText(context, R.string.start_message, Toast.LENGTH_LONG).show()
    }

    private fun getAppActions(app: String): ArrayList<Action> {
        val actions = ArrayList<Action>()
        if (DeepShortcutManager.hasHostPermission(context)) {
            if (DeepShortcutManager.getShortcuts(app, context) != null)
                actions.add(Action(R.drawable.ic_shortcuts, getString(R.string.shortcuts)))
        }
        actions.add(Action(R.drawable.ic_manage, getString(R.string.manage)))
        actions.add(Action(R.drawable.ic_launch_mode, getString(R.string.open_in)))
        if (AppUtils.isPinned(context, app, AppUtils.PINNED_LIST)) actions.add(Action(R.drawable.ic_remove_favorite, getString(R.string.remove))) else actions.add(Action(R.drawable.ic_add_favorite, getString(R.string.to_favorites)))
        if (!AppUtils.isPinned(context, app, AppUtils.DESKTOP_LIST)) actions.add(Action(R.drawable.ic_add_to_desktop, getString(R.string.to_desktop)))
        return actions
    }

    override fun onDockAppClicked(app: DockApp, anchor: View) {
        val tasks = app.tasks
        if (tasks.size == 1) {
            val taskId = tasks[0].id
            if (taskId == -1)
                launchApp(getDefaultLaunchMode(app.packageName), app.packageName)
            else
                activityManager.moveTaskToFront(taskId, 0)
        } else if (tasks.size > 1) {
            val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
            val layoutParams = Utils.makeWindowParams(-2, -2, context, preferLastDisplay)
            ColorUtils.applyMainColor(context, sharedPreferences, view)
            layoutParams.gravity = Gravity.BOTTOM or Gravity.START
            layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            layoutParams.y = Utils.dpToPx(context, 2) + dockLayout.measuredHeight
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            layoutParams.x = location[0]
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    windowManager.removeView(view)
                }
                false
            }
            val tasksLv = view.findViewById<ListView>(R.id.tasks_lv)
            tasksLv.adapter = AppTaskAdapter(context, tasks)
            tasksLv.onItemClickListener = OnItemClickListener { adapterView, _, position, _ ->
                activityManager.moveTaskToFront((adapterView.getItemAtPosition(position) as AppTask).id, 0)
                windowManager.removeView(view)
            }
            windowManager.addView(view, layoutParams)
        } else launchApp(getDefaultLaunchMode(app.packageName), app.packageName)
        if (getDefaultLaunchMode(app.packageName) == "fullscreen") {
            if (isPinned && sharedPreferences.getBoolean("auto_unpin", true)) {
                unpinDock()
            }
        } else {
            if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) {
                pinDock()
            }
        }
    }

    override fun onDockAppLongClicked(app: DockApp, view: View) {
        showDockAppContextMenu(app.packageName, view)
    }

    override fun onAppClicked(app: App, item: View) {
        if (app.packageName == "$packageName.calc") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("results", app.name))
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        } else launchApp(null, app.packageName)
    }

    override fun onAppLongClicked(app: App, view: View) {
        if (app.packageName != "$packageName.calc") {
            showAppContextMenu(app.packageName, view)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val currentActivity = event.className.toString()
            if (currentActivity == "null" || currentActivity.contains("android.app.")
                    || currentActivity.contains("android.widget.")) return
            if (currentActivity != previousActivity) {
                // Activity changed
                //TODO: Filter current input method
                previousActivity = currentActivity
                if (isPinned) updateRunningTasks()
            }
        } else if (isPinned && sharedPreferences.getBoolean("custom_toasts", false) && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.parcelableData !is Notification && event.text.size > 0) {
            val text = event.text[0].toString()
            val app = event.packageName.toString()
            showToast(app, text)
        }
    }

    private fun showToast(app: String, text: String) {
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferLastDisplay)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER
        layoutParams.y = dock.measuredHeight + Utils.dpToPx(context, 4)
        val toast = LayoutInflater.from(context).inflate(R.layout.toast, null)
        ColorUtils.applyMainColor(context, sharedPreferences, toast)
        val textTv = toast.findViewById<TextView>(R.id.toast_tv)
        val iconIv = toast.findViewById<ImageView>(R.id.toast_iv)
        textTv.text = text
        val notificationIcon = AppUtils.getAppIcon(context, app)
        iconIv.setImageDrawable(notificationIcon)
        ColorUtils.applyColor(iconIv, ColorUtils.getDrawableDominantColor(notificationIcon))
        toast.alpha = 0f
        toast.animate().alpha(1f).setDuration(250).setInterpolator(AccelerateDecelerateInterpolator())
        Handler(Looper.getMainLooper()).postDelayed({
            toast.animate().alpha(0f).setDuration(400)
                    .setInterpolator(AccelerateDecelerateInterpolator()).setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            windowManager.removeView(toast)
                        }
                    })
        }, 5000)
        windowManager.addView(toast, layoutParams)
    }

    override fun onInterrupt() {}

    //Handle keyboard shortcuts
    override fun onKeyEvent(event: KeyEvent): Boolean {
        var isModifierPressed = false
        when (sharedPreferences.getString("shortcut_key", "57")) {
            "57" -> isModifierPressed = event.isAltPressed
            "113" -> isModifierPressed = event.isCtrlPressed
            "3" -> isModifierPressed = event.isMetaPressed
        }
        if (event.action == KeyEvent.ACTION_UP && isModifierPressed) {
            if (event.keyCode == KeyEvent.KEYCODE_L && sharedPreferences.getBoolean("enable_lock_desktop", true))
                lockScreen()
            else if (event.keyCode == KeyEvent.KEYCODE_P && sharedPreferences.getBoolean("enable_open_settings", true))
                launchApp(null, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else if (event.keyCode == KeyEvent.KEYCODE_T && sharedPreferences.getBoolean("enable_open_terminal", false))
                launchApp(null, sharedPreferences.getString("app_terminal", "com.termux")!!)
            else if (event.keyCode == KeyEvent.KEYCODE_N && sharedPreferences.getBoolean("enable_expand_notifications", true))
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else if (event.keyCode == KeyEvent.KEYCODE_K)
                DeviceUtils.sendKeyEvent(KeyEvent.KEYCODE_SYSRQ)
            else if (event.keyCode == KeyEvent.KEYCODE_W && sharedPreferences.getBoolean("enable_toggle_pin", true))
                togglePin()
            else if (event.keyCode == KeyEvent.KEYCODE_F11)
                DeviceUtils.restartService(context)
            else if (event.keyCode == KeyEvent.KEYCODE_M && sharedPreferences.getBoolean("enable_open_music", true))
                launchApp(null, sharedPreferences.getString("app_music", "")!!)
            else if (event.keyCode == KeyEvent.KEYCODE_B && sharedPreferences.getBoolean("enable_open_browser", true))
                launchApp(null, sharedPreferences.getString("app_browser", "")!!)
            else if (event.keyCode == KeyEvent.KEYCODE_A && sharedPreferences.getBoolean("enable_open_assist", true))
                launchApp(null, sharedPreferences.getString("app_assistant", "")!!)
            else if (event.keyCode == KeyEvent.KEYCODE_R && sharedPreferences.getBoolean("enable_open_rec", true))
                launchApp(null, sharedPreferences.getString("app_rec", "")!!)
            else if (event.keyCode == KeyEvent.KEYCODE_D)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else if (event.keyCode == KeyEvent.KEYCODE_O) {
                toggleSoftKeyboard()
            } else if (event.keyCode == KeyEvent.KEYCODE_F12)
                DeviceUtils.softReboot()
            else if (event.keyCode == KeyEvent.KEYCODE_F3) {
                if (tasks.size > 0) {
                    val task = tasks[0]
                    AppUtils.resizeTask(context, "portrait", task.id, dockLayout.measuredHeight,
                            preferLastDisplay)
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (tasks.size > 0) {
                    val task = tasks[0]
                    AppUtils.resizeTask(context, "maximized", task.id, dockLayout.measuredHeight,
                            preferLastDisplay)
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (tasks.size > 0) {
                    val task = tasks[0]
                    AppUtils.resizeTask(context, "tiled-left", task.id, dockLayout.measuredHeight,
                            preferLastDisplay)
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (tasks.size > 0) {
                    val task = tasks[0]
                    AppUtils.resizeTask(context, "tiled-right", task.id, dockLayout.measuredHeight,
                            preferLastDisplay)
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (tasks.size > 0) {
                    val task = tasks[0]
                    AppUtils.resizeTask(context, "standard", task.id, dockLayout.measuredHeight,
                            preferLastDisplay)
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            val menuKey = sharedPreferences.getString("menu_key", "3")!!.toInt()
            if (event.keyCode == KeyEvent.KEYCODE_CTRL_RIGHT && sharedPreferences.getBoolean("enable_ctrl_back", true)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return true
            } else if (event.keyCode == KeyEvent.KEYCODE_MENU && sharedPreferences.getBoolean("enable_menu_recents", false)) {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                return true
            } else if (event.keyCode == menuKey) {
                toggleAppMenu()
                return true
            } else if (event.keyCode == KeyEvent.KEYCODE_F10 && sharedPreferences.getBoolean("enable_f10", true)) {
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun toggleSoftKeyboard() {
        if (Build.VERSION.SDK_INT < 30) {
            val im = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            im.showInputMethodPicker()
        } else {
            //TODO
            val kc = softKeyboardController
            val mode = kc.showMode
            if (mode == SHOW_MODE_AUTO || mode == SHOW_MODE_HIDDEN) kc.setShowMode(SHOW_MODE_IGNORE_HARD_KEYBOARD) else kc.setShowMode(SHOW_MODE_HIDDEN)
        }
    }

    private fun togglePin() {
        if (isPinned) unpinDock() else pinDock()
    }

    private fun showDock() {
        dockHandle.visibility = View.GONE
        if (dockLayoutParams.width != -1) {
            dockLayoutParams.width = -1
            windowManager.updateViewLayout(dock, dockLayoutParams)
        }
        dockHandler.removeCallbacksAndMessages(null)
        updateRunningTasks()
        val anim = AnimationUtils.loadAnimation(context, R.anim.slide_up)
        dockLayout.visibility = View.VISIBLE
        dockLayout.startAnimation(anim)
        dockTrigger.visibility = View.GONE
    }

    fun pinDock() {
        isPinned = true
        pinBtn.setImageResource(R.drawable.ic_pin)
        if (dockLayout.visibility == View.GONE) showDock()
    }

    private fun unpinDock() {
        pinBtn.setImageResource(R.drawable.ic_unpin)
        isPinned = false
        if (dockLayout.visibility == View.VISIBLE) hideDock(500)
    }

    private fun hideDock(delay: Int) {
        dockHandler.removeCallbacksAndMessages(null)
        dockHandler.postDelayed({
            if (!isPinned) {
                val anim = AnimationUtils.loadAnimation(context, R.anim.slide_down)
                anim.setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(p1: Animation) {}
                    override fun onAnimationEnd(p1: Animation) {
                        dockLayout.visibility = View.GONE
                        if (sharedPreferences.getString("activation_method", "swipe") == "swipe") dockTrigger.visibility = View.VISIBLE else {
                            if (dockLayoutParams.width == -1) {
                                dockLayoutParams.width = Utils.dpToPx(context, 24)
                                windowManager.updateViewLayout(dock, dockLayoutParams)
                            }
                            dockHandle.visibility = View.VISIBLE
                        }
                    }

                    override fun onAnimationRepeat(p1: Animation) {}
                })
                dockLayout.startAnimation(anim)
            }
        }, delay.toLong())
    }

    private fun launchApp(mode: String?, app: String) {
        var mode = mode
        if (mode == null) mode = getDefaultLaunchMode(app) else {
            if (sharedPreferences.getBoolean("remember_launch_mode", true))
                db.saveLaunchMode(app, mode)
        }
        launchApp(mode, packageManager.getLaunchIntentForPackage(app))
    }

    private fun getDefaultLaunchMode(app: String): String {
        val mode: String? = db.getLaunchMode(app)
        return if (sharedPreferences.getBoolean("remember_launch_mode", true) && mode != null)
            mode
        else if (AppUtils.isGame(packageManager, app) && sharedPreferences.getBoolean("launch_games_fullscreen", true))
            "fullscreen"
        else
            sharedPreferences.getString("launch_mode", "standard")!!
    }

    private fun launchApp(mode: String?, intent: Intent?) {
        var mode = mode
        if (mode == null)
            mode = sharedPreferences.getString("launch_mode", "standard")
        val options: ActivityOptions
        val animation = sharedPreferences.getString("custom_animation", "system")
        if (animation == "none" || animation == "system") {
            options = ActivityOptions.makeBasic()
            if (animation == "none") intent!!.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        } else {
            var animResId = 0
            when (sharedPreferences.getString("custom_animation", "fade")) {
                "fade" -> animResId = R.anim.fade_in
                "slide_up" -> animResId = R.anim.slide_up
                "slide_left" -> animResId = R.anim.slide_left
            }
            options = ActivityOptions.makeCustomAnimation(context, animResId, R.anim.fade_out)
        }
        try {
            val methodName = if (Build.VERSION.SDK_INT >= 28) "setLaunchWindowingMode" else "setLaunchStackId"
            val windowMode: Int
            if (mode == "fullscreen")
                windowMode = 1
            else {
                windowMode = if (Build.VERSION.SDK_INT >= 28) 5 else 2
                options.setLaunchBounds(
                        AppUtils.makeLaunchBounds(context, mode!!, dockLayout.measuredHeight, preferLastDisplay))
            }
            if (Build.VERSION.SDK_INT > 28 && preferLastDisplay)
                options.setLaunchDisplayId(DeviceUtils.getSecondaryDisplay(this).displayId)

            val method = ActivityOptions::class.java.getMethod(methodName, Int::class.javaPrimitiveType)
            method.invoke(options, windowMode)
            context.startActivity(intent, options.toBundle())
            if (appMenuVisible) hideAppMenu()
            if (mode == "fullscreen" && sharedPreferences.getBoolean("auto_unpin", true)) {
                if (isPinned) {
                    unpinDock()
                }
            } else {
                if (!isPinned && sharedPreferences.getBoolean("auto_pin", true)) {
                    pinDock()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, R.string.something_wrong.toString() + e.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private fun setOrientation() {
        dockLayoutParams.screenOrientation = if (sharedPreferences.getBoolean("lock_landscape", false)) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        windowManager.updateViewLayout(dock, dockLayoutParams)
    }

    private fun toggleAppMenu() {
        if (appMenuVisible) hideAppMenu() else showAppMenu()
    }

    fun showAppMenu() {
        val layoutParams: WindowManager.LayoutParams?
        val deviceWidth = DeviceUtils.getDisplayMetrics(context, preferLastDisplay).widthPixels
        val deviceHeight = DeviceUtils.getDisplayMetrics(context, preferLastDisplay).heightPixels
        val dockHeight = dockLayout.measuredHeight
        val margins = Utils.dpToPx(context, 2)
        val navHeight = DeviceUtils.getNavBarHeight(context)
        val diff = if (dockHeight - navHeight > 0) dockHeight - navHeight else 0
        val usableHeight = if (Build.VERSION.SDK_INT > 31 && sharedPreferences.getBoolean("navbar_fix", true))
            deviceHeight - margins - diff - DeviceUtils.getStatusBarHeight(context)
        else
            deviceHeight - dockHeight - DeviceUtils.getStatusBarHeight(context) - margins
        if (sharedPreferences.getBoolean("app_menu_fullscreen", false)) {
            layoutParams = Utils.makeWindowParams(-1, usableHeight, context, preferLastDisplay)
            layoutParams.y = margins + dockHeight
            if (sharedPreferences.getInt("dock_layout", -1) != 0) {
                val padding = Utils.dpToPx(context, 24)
                appMenu.setPadding(padding, padding, padding, padding)
                searchEntry.gravity = Gravity.CENTER
                searchLayout.gravity = Gravity.CENTER
                appsGv.layoutManager = GridLayoutManager(context, 10)
                favoritesGv.layoutManager = GridLayoutManager(context, 10)
            } else {
                appsGv.layoutManager = GridLayoutManager(context, 5)
                favoritesGv.layoutManager = GridLayoutManager(context, 5)
            }
        } else {
            val width = Utils.dpToPx(context, sharedPreferences.getString("app_menu_width", "650")!!.toInt())
            val height = Utils.dpToPx(context, sharedPreferences.getString("app_menu_height", "540")!!.toInt())
            layoutParams = Utils.makeWindowParams(width.coerceAtMost(deviceWidth - margins * 2), height.coerceAtMost(usableHeight),
                    context, preferLastDisplay)
            layoutParams.x = margins
            layoutParams.y = margins + dockHeight
            appsGv.layoutManager = GridLayoutManager(context, sharedPreferences.getString("num_columns", "5")!!.toInt())
            favoritesGv.layoutManager = GridLayoutManager(context, sharedPreferences.getString("num_columns", "5")!!.toInt())
            val padding = Utils.dpToPx(context, 10)
            appMenu.setPadding(padding, padding, padding, padding)
            searchEntry.gravity = Gravity.START
            searchLayout.gravity = Gravity.START
            appMenu.setBackgroundResource(R.drawable.round_rect)
            ColorUtils.applyMainColor(context, sharedPreferences, appMenu)
        }
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val halign = if (sharedPreferences.getBoolean("center_app_menu", false)) Gravity.CENTER_HORIZONTAL else Gravity.START
        layoutParams.gravity = Gravity.BOTTOM or halign
        ColorUtils.applyColor(appsSeparator, ColorUtils.getMainColors(sharedPreferences, this)[4])
        windowManager.addView(appMenu, layoutParams)

        //Load apps
        UpdateAppMenuTask().execute()
        loadFavoriteApps()

        //Load user info
        val avatarIv = appMenu.findViewById<ImageView>(R.id.avatar_iv)
        val userNameTv = appMenu.findViewById<TextView>(R.id.user_name_tv)
        avatarIv.setOnClickListener { anchor -> showUserContextMenu(anchor) }
        if (AppUtils.isSystemApp(context, packageName)) {
            val name = DeviceUtils.getUserName(context)
            if (name != null) userNameTv.text = name
            val icon = DeviceUtils.getUserIcon(context)
            if (icon != null) avatarIv.setImageBitmap(icon)
        } else {
            val name = sharedPreferences.getString("user_name", "")
            if (name!!.isNotEmpty()) userNameTv.text = name
            val iconUri = sharedPreferences.getString("user_icon_uri", "default")
            if (iconUri != "default") {
                val bitmap = Utils.getBitmapFromUri(context, Uri.parse(iconUri))
                val icon = Utils.getCircularBitmap(bitmap)
                if (icon != null)
                    avatarIv.setImageBitmap(icon)
            } else avatarIv.setImageResource(R.drawable.ic_user)
        }
        appMenu.alpha = 0f
        appMenu.animate().alpha(1f).setDuration(200).setInterpolator(AccelerateDecelerateInterpolator())
        searchEt.requestFocus()
        appMenuVisible = true
    }

    fun hideAppMenu() {
        searchEt.setText("")
        windowManager.removeView(appMenu)
        appMenuVisible = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAppContextMenu(app: String, anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferLastDisplay)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = Gravity.START or Gravity.TOP
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        layoutParams.y = location[1] + Utils.dpToPx(context, anchor.measuredHeight / 2)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                windowManager.removeView(view)
            }
            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
        actionsLv.setOnItemClickListener { adapterView, _, position, _ ->
            if (adapterView.getItemAtPosition(position) is Action) {
                val action = adapterView.getItemAtPosition(position) as Action
                if (action.text == getString(R.string.manage)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_info, getString(R.string.app_info)))
                    if (!AppUtils.isSystemApp(context, app) || sharedPreferences.getBoolean("allow_sysapp_uninstall", false)) actions.add(Action(R.drawable.ic_uninstall, getString(R.string.uninstall)))
                    if (sharedPreferences.getBoolean("allow_app_freeze", false)) actions.add(Action(R.drawable.ic_freeze, getString(R.string.freeze)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.shortcuts)) {
                    actionsLv.adapter = AppShortcutAdapter(context, DeepShortcutManager.getShortcuts(app, context)!!)
                } else if (action.text == "") {
                    actionsLv.adapter = AppActionsAdapter(context, getAppActions(app))
                } else if (action.text == getString(R.string.open_in)) {
                    val actions = ArrayList<Action>()
                    actions.add(Action(R.drawable.ic_arrow_back, ""))
                    actions.add(Action(R.drawable.ic_standard, getString(R.string.standard)))
                    actions.add(Action(R.drawable.ic_maximized, getString(R.string.maximized)))
                    actions.add(Action(R.drawable.ic_portrait, getString(R.string.portrait)))
                    actions.add(Action(R.drawable.ic_fullscreen, getString(R.string.fullscreen)))
                    actionsLv.adapter = AppActionsAdapter(context, actions)
                } else if (action.text == getString(R.string.app_info)) {
                    launchApp(null, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.uninstall)) {
                    if (AppUtils.isSystemApp(context, app)) DeviceUtils.runAsRoot("pm uninstall --user 0 $app") else startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$app"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    if (appMenuVisible) hideAppMenu()
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.freeze)) {
                    val status = DeviceUtils.runAsRoot("pm disable $app")
                    if (status != "error") Toast.makeText(context, R.string.app_frozen, Toast.LENGTH_SHORT).show() else Toast.makeText(context, R.string.something_wrong, Toast.LENGTH_SHORT).show()
                    windowManager.removeView(view)
                    if (appMenuVisible) hideAppMenu()
                } else if (action.text == getString(R.string.to_favorites)) {
                    AppUtils.pinApp(context, app, AppUtils.PINNED_LIST)
                    windowManager.removeView(view)
                    loadFavoriteApps()
                } else if (action.text == getString(R.string.remove)) {
                    AppUtils.unpinApp(context, app, AppUtils.PINNED_LIST)
                    windowManager.removeView(view)
                    loadFavoriteApps()
                } else if (action.text == getString(R.string.to_desktop)) {
                    AppUtils.pinApp(context, app, AppUtils.DESKTOP_LIST)
                    sendBroadcast(Intent("$packageName.SERVICE").putExtra("action", "PINNED"))
                    windowManager.removeView(view)
                } else if (action.text == getString(R.string.standard)) {
                    windowManager.removeView(view)
                    launchApp("standard", app)
                } else if (action.text == getString(R.string.maximized)) {
                    windowManager.removeView(view)
                    launchApp("maximized", app)
                } else if (action.text == getString(R.string.portrait)) {
                    windowManager.removeView(view)
                    launchApp("portrait", app)
                } else if (action.text == getString(R.string.fullscreen)) {
                    windowManager.removeView(view)
                    launchApp("fullscreen", app)
                }
            } else if (Build.VERSION.SDK_INT > 24 && adapterView.getItemAtPosition(position) is ShortcutInfo) {
                val shortcut = adapterView.getItemAtPosition(position) as ShortcutInfo
                windowManager.removeView(view)
                DeepShortcutManager.startShortcut(shortcut, context)
            }
        }
        windowManager.addView(view, layoutParams)
    }

    private fun showDockAppContextMenu(app: String, anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.pin_entry, null)
        val pinLayout = view.findViewById<LinearLayout>(R.id.pin_entry_pin)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferLastDisplay)
        view.setBackgroundResource(R.drawable.round_rect)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.START
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 2) + dockLayout.measuredHeight
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                windowManager.removeView(view)
            }
            false
        }
        val icon = view.findViewById<ImageView>(R.id.pin_entry_iv)
        ColorUtils.applySecondaryColor(context, sharedPreferences, icon)
        val text = view.findViewById<TextView>(R.id.pin_entry_tv)
        if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST)) {
            icon.setImageResource(R.drawable.ic_unpin)
            text.setText(R.string.unpin)
            val moveLayout = view.findViewById<LinearLayout>(R.id.pin_entry_move)
            moveLayout.visibility = View.VISIBLE
            val moveLeft = view.findViewById<ImageView>(R.id.pin_entry_left)
            val moveRight = view.findViewById<ImageView>(R.id.pin_entry_right)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveLeft)
            ColorUtils.applySecondaryColor(context, sharedPreferences, moveRight)
            moveLeft.setOnClickListener {
                AppUtils.moveApp(this@DockService, app, AppUtils.DOCK_PINNED_LIST, 0)
                loadPinnedApps()
                updateRunningTasks()
            }
            moveRight.setOnClickListener {
                AppUtils.moveApp(this@DockService, app, AppUtils.DOCK_PINNED_LIST, 1)
                loadPinnedApps()
                updateRunningTasks()
            }
        }
        pinLayout.setOnClickListener {
            if (AppUtils.isPinned(context, app, AppUtils.DOCK_PINNED_LIST)) AppUtils.unpinApp(context, app, AppUtils.DOCK_PINNED_LIST) else AppUtils.pinApp(context, app, AppUtils.DOCK_PINNED_LIST)
            loadPinnedApps()
            updateRunningTasks()
            windowManager.removeView(view)
        }
        windowManager.addView(view, layoutParams)
    }

    private fun showUserContextMenu(anchor: View) {
        val view = LayoutInflater.from(context).inflate(R.layout.task_list, null)
        val layoutParams = Utils.makeWindowParams(-2, -2, context, preferLastDisplay)
        ColorUtils.applyMainColor(context, sharedPreferences, view)
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        layoutParams.x = location[0]
        layoutParams.y = location[1] + Utils.dpToPx(context, anchor.measuredHeight / 2)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                windowManager.removeView(view)
            }
            false
        }
        val actionsLv = view.findViewById<ListView>(R.id.tasks_lv)
        val actions = ArrayList<Action>()
        actions.add(Action(R.drawable.ic_users, getString(R.string.users)))
        actions.add(Action(R.drawable.ic_user_folder, getString(R.string.files)))
        actions.add(Action(R.drawable.ic_user_settings, getString(R.string.settings)))
        actions.add(Action(R.drawable.ic_settings, getString(R.string.dock_settings)))
        actionsLv.adapter = AppActionsAdapter(context, actions)
        actionsLv.onItemClickListener = OnItemClickListener { adapterView, _, position, _ ->
            val action = adapterView.getItemAtPosition(position) as Action
            when (action.text) {
                getString(R.string.users) -> launchApp(null, Intent("android.settings.USER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                getString(R.string.files) -> launchApp(null,
                        sharedPreferences.getString("app_files", "com.android.documentsui")!!)

                getString(R.string.settings) -> launchApp(null, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                getString(R.string.dock_settings) -> launchApp(null, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            windowManager.removeView(view)
        }
        windowManager.addView(view, layoutParams)
    }

    override fun onSharedPreferenceChanged(p1: SharedPreferences, p2: String) {
        if (p2.startsWith("theme")) applyTheme() else if (p2 == "menu_icon_uri") updateMenuIcon() else if (p2.startsWith("icon_") || p2 == "tint_indicators") {
            updateRunningTasks()
            loadFavoriteApps()
        } else if (p2 == "lock_landscape") setOrientation() else if (p2 == "center_running_apps") {
            placeRunningApps()
            updateRunningTasks()
        } else if (p2 == "dock_activation_area") updateDockTrigger() else if (p2.startsWith("enable_corner_")) updateCorners() else if (p2.startsWith("enable_nav_")) {
            updateNavigationBar()
        } else if (p2.startsWith("enable_qs_")) {
            updateQuickSettings()
        } else if (p2 == "dock_square") updateDockShape() else if (p2 == "max_running_apps") {
            maxApps = sharedPreferences.getString("max_running_apps", "10")!!.toInt()
            updateRunningTasks()
        } else if (p2 == "activation_method") {
            if (!isPinned) {
                val method = sharedPreferences.getString(p2, "swipe")
                if (method == "swipe") {
                    dockLayoutParams.width = -1
                    windowManager.updateViewLayout(dock, dockLayoutParams)
                    dockTrigger.visibility = View.VISIBLE
                    dockHandle.visibility = View.GONE
                } else {
                    dockLayoutParams.width = Utils.dpToPx(context, 24)
                    windowManager.updateViewLayout(dock, dockLayoutParams)
                    dockTrigger.visibility = View.GONE
                    dockHandle.visibility = View.VISIBLE
                }
            }
        } else if (p2 == "handle_opacity") dockHandle.alpha = 0.01f * sharedPreferences.getString("handle_opacity", "50")!!.toInt()
    }

    private fun updateDockTrigger() {
        val height = sharedPreferences.getString("dock_activation_area", "10")!!.toInt()
        dockTrigger.layoutParams.height = Utils.dpToPx(context, 1.coerceAtLeast(height).coerceAtMost(50))
    }

    private fun placeRunningApps() {
        val layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT)
        if (sharedPreferences.getBoolean("center_running_apps", true)) {
            layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
        } else {
            layoutParams.addRule(RelativeLayout.END_OF, R.id.nav_panel)
            layoutParams.addRule(RelativeLayout.START_OF, R.id.system_tray)
        }
        tasksGv.layoutParams = layoutParams
    }

    private fun loadPinnedApps() {
        pinnedApps = AppUtils.getPinnedApps(context, packageManager, AppUtils.DOCK_PINNED_LIST)
    }

    private fun updateRunningTasks() {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 500) return
        lastUpdate = now

        //Toast.makeText(context, "Updating running apps...", Toast.LENGTH_LONG).show();
        val apps = ArrayList<DockApp>()
        for (pinnedApp in pinnedApps) {
            apps.add(DockApp(pinnedApp.name, pinnedApp.packageName, pinnedApp.icon))
        }
        val gridSize = Utils.dpToPx(context, 52)

        //TODO: We can eliminate another for
        //TODO: Dont do anything if tasks has not changed
        tasks = if (systemApp) AppUtils.getRunningTasks(activityManager, packageManager, maxApps) else AppUtils.getRecentTasks(context, maxApps)
        for (j in 1..tasks.size) {
            val task = tasks[tasks.size - j]
            val i = AppUtils.containsTask(apps, task)
            if (i != -1) apps[i].addTask(task) else apps.add(DockApp(task))
        }
        tasksGv.layoutParams.width = gridSize * apps.size
        tasksGv.adapter = DockAppAdapter(context, apps, this)

        //TODO: Move context outta here
        wifiBtn.setImageResource(if (wifiManager.isWifiEnabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off)
        val bAdapter = bm.adapter
        if (bAdapter != null) bluetoothBtn.setImageResource(if (bAdapter.isEnabled) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off)
    }

    private fun updateDockShape() {
        dockLayout.setBackgroundResource(if (sharedPreferences.getBoolean("dock_square", false)) R.drawable.rect else R.drawable.round_rect)
        ColorUtils.applyMainColor(context, sharedPreferences, dockLayout)
    }

    private fun updateNavigationBar() {
        appsBtn.visibility = if (sharedPreferences.getBoolean("enable_nav_apps", true)) View.VISIBLE else View.GONE
        backBtn.visibility = if (sharedPreferences.getBoolean("enable_nav_back", true)) View.VISIBLE else View.GONE
        homeBtn.visibility = if (sharedPreferences.getBoolean("enable_nav_home", true)) View.VISIBLE else View.GONE
        recentBtn.visibility = if (sharedPreferences.getBoolean("enable_nav_recents", true)) View.VISIBLE else View.GONE
        assistBtn.visibility = if (sharedPreferences.getBoolean("enable_nav_assist", false)) View.VISIBLE else View.GONE
    }

    private fun updateQuickSettings() {
        notificationBtn.visibility = if (sharedPreferences.getBoolean("enable_qs_notif", true)) View.VISIBLE else View.GONE
        bluetoothBtn.visibility = if (sharedPreferences.getBoolean("enable_qs_bluetooth", false)) View.VISIBLE else View.GONE
        batteryBtn.visibility = if (sharedPreferences.getBoolean("enable_qs_battery", false)) View.VISIBLE else View.GONE
        wifiBtn.visibility = if (sharedPreferences.getBoolean("enable_qs_wifi", true)) View.VISIBLE else View.GONE
        pinBtn.visibility = if (sharedPreferences.getBoolean("enable_qs_pin", true)) View.VISIBLE else View.GONE
        volBtn.visibility = if (sharedPreferences.getBoolean("enable_qs_vol", true)) View.VISIBLE else View.GONE
        dateTv.visibility = if (sharedPreferences.getBoolean("enable_qs_date", true)) View.VISIBLE else View.GONE
    }

    private fun launchAssistant() {
        val assistant = sharedPreferences.getString("app_assistant", "")
        if (assistant!!.isNotEmpty()) launchApp(null, assistant) else {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun toggleBluetooth() {
        try {
            if (bm.adapter.isEnabled) {
                bluetoothBtn.setImageResource(R.drawable.ic_bluetooth_off)
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return
                }
                bm.adapter.disable()
            } else {
                bluetoothBtn.setImageResource(R.drawable.ic_bluetooth)
                bm.adapter.enable()
            }
        } catch (_: Exception) {
        }
    }

    private fun toggleWifi() {
        if (sharedPreferences.getBoolean("enable_wifi_panel", false)) {
            if (wifiPanelVisible) hideWiFiPanel() else showWiFiPanel()
        } else {
            val enabled = wifiManager.isWifiEnabled
            val icon = if (!enabled) R.drawable.ic_wifi_on else R.drawable.ic_wifi_off
            wifiBtn.setImageResource(icon)
            wifiManager.setWifiEnabled(!enabled)
        }
    }

    private fun toggleVolume() {
        //TODO: Implement setting
        //DeviceUtils.toggleVolume(context);
        if (!audioPanelVisible) showAudioPanel() else hideAudioPanel()
    }

    private fun hideAudioPanel() {
        windowManager.removeView(audioPanel)
        audioPanelVisible = false
        audioPanel = null
    }

    private fun showAudioPanel() {
        if (Utils.notificationPanelVisible) sendBroadcast(Intent("$packageName.NOTIFICATION_PANEL").putExtra("action", "hide"))
        if (wifiPanelVisible) hideWiFiPanel()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val layoutParams = Utils.makeWindowParams(Utils.dpToPx(context, 270), -2, context,
                preferLastDisplay)
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 2) + dockLayout.measuredHeight
        layoutParams.x = Utils.dpToPx(context, 2)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        audioPanel = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
                .inflate(R.layout.audio_panel, null) as LinearLayout
        audioPanel!!.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE
                    && (event.y < audioPanel!!.measuredHeight || event.x < audioPanel!!.x)) {
                hideAudioPanel()
            }
            false
        }
        val musicIcon = audioPanel!!.findViewById<ImageView>(R.id.ap_music_icon)
        val musicSb = audioPanel!!.findViewById<SeekBar>(R.id.ap_music_sb)
        musicSb.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        musicSb.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        musicSb.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(p1: SeekBar, p2: Int, p3: Boolean) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p2, 0)
            }

            override fun onStartTrackingTouch(p1: SeekBar) {}
            override fun onStopTrackingTouch(p1: SeekBar) {}
        })
        ColorUtils.applySecondaryColor(context, sharedPreferences, musicIcon)
        ColorUtils.applyMainColor(context, sharedPreferences, audioPanel!!)
        windowManager.addView(audioPanel, layoutParams)
        audioPanelVisible = true
    }

    private fun hideWiFiPanel() {
        windowManager.removeView(wifiPanel)
        wifiPanelVisible = false
        wifiPanel = null
    }

    private fun showWiFiPanel() {
        if (Utils.notificationPanelVisible) sendBroadcast(Intent("$packageName.NOTIFICATION_PANEL").putExtra("action", "hide"))
        if (audioPanelVisible) hideAudioPanel()
        val layoutParams = Utils.makeWindowParams(Utils.dpToPx(context, 300), -2, context,
                preferLastDisplay)
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        layoutParams.y = Utils.dpToPx(context, 2) + dockLayout.measuredHeight
        layoutParams.x = Utils.dpToPx(context, 2)
        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        wifiPanel = LayoutInflater.from(ContextThemeWrapper(context, R.style.AppTheme_Dock))
                .inflate(R.layout.wifi_panel, null) as LinearLayout
        wifiPanel!!.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE
                    && (event.y < wifiPanel!!.measuredHeight || event.x < wifiPanel!!.x)) {
                hideWiFiPanel()
            }
            false
        }
        val ssidTv = wifiPanel!!.findViewById<TextView>(R.id.wp_ssid_tv)
        val wifiSwtch = wifiPanel!!.findViewById<Switch>(R.id.wp_switch)
        val selectBtn = wifiPanel!!.findViewById<Button>(R.id.wp_select_btn)
        val infoLayout = wifiPanel!!.findViewById<LinearLayout>(R.id.wp_info)
        selectBtn.setOnClickListener {
            launchApp(null, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hideWiFiPanel()
        }
        wifiSwtch.isChecked = wifiManager.isWifiEnabled
        wifiSwtch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                wifiManager.setWifiEnabled(true)
                wifiBtn.setImageResource(R.drawable.ic_wifi_on)
            } else {
                wifiManager.setWifiEnabled(false)
                ssidTv.setText(R.string.not_connected)
                wifiBtn.setImageResource(R.drawable.ic_wifi_off)
            }
        }
        val wi = wifiManager.connectionInfo
        if (wifiManager.isWifiEnabled) {
            infoLayout.visibility = View.VISIBLE
            if (wi != null && wi.networkId != -1) {
                ssidTv.text = wi.ssid
            }
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(p1: Context, p2: Intent) {
                val wifiInfo = wifiManager.connectionInfo
                if (wifiManager.isWifiEnabled) {
                    infoLayout.visibility = View.VISIBLE
                    if (wifiInfo != null && wifiInfo.networkId != -1) {
                        ssidTv.text = wifiInfo.ssid
                    } else ssidTv.setText(R.string.not_connected)
                } else infoLayout.visibility = View.GONE
            }
        }, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
        ColorUtils.applyMainColor(context, sharedPreferences, wifiPanel!!)
        windowManager.addView(wifiPanel, layoutParams)
        wifiPanelVisible = true
    }

    private fun showPowerMenu() {
        val layoutParams = Utils.makeWindowParams(Utils.dpToPx(context, 400),
                Utils.dpToPx(context, 120), context, preferLastDisplay)
        layoutParams.gravity = Gravity.CENTER
        layoutParams.x = Utils.dpToPx(context, 10)
        layoutParams.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        powerMenu = LayoutInflater.from(context).inflate(R.layout.power_menu, null) as LinearLayout
        powerMenu!!.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hidePowerMenu()
            }
            false
        }
        val powerOffBtn = powerMenu!!.findViewById<ImageButton>(R.id.power_off_btn)
        val restartBtn = powerMenu!!.findViewById<ImageButton>(R.id.restart_btn)
        val softRestartBtn = powerMenu!!.findViewById<ImageButton>(R.id.soft_restart_btn)
        val lockBtn = powerMenu!!.findViewById<ImageButton>(R.id.lock_btn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, powerOffBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, restartBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, softRestartBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, lockBtn)
        powerOffBtn.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.shutdown()
        }
        restartBtn.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.reboot()
        }
        softRestartBtn.setOnClickListener {
            hidePowerMenu()
            DeviceUtils.softReboot()
        }
        lockBtn.setOnClickListener {
            hidePowerMenu()
            lockScreen()
        }
        ColorUtils.applyMainColor(context, sharedPreferences, powerMenu!!)
        windowManager.addView(powerMenu, layoutParams)
        topRightCorner.visibility = if (sharedPreferences.getBoolean("enable_corner_top_right", false)) View.VISIBLE else View.GONE
        powerMenuVisible = true
    }

    private fun hidePowerMenu() {
        windowManager.removeView(powerMenu)
        powerMenuVisible = false
        powerMenu = null
    }

    fun applyTheme() {
        ColorUtils.applyMainColor(context, sharedPreferences, dockLayout)
        ColorUtils.applyMainColor(context, sharedPreferences, appMenu)
        ColorUtils.applySecondaryColor(context, sharedPreferences, searchEntry)
        ColorUtils.applySecondaryColor(context, sharedPreferences, backBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, homeBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, recentBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, assistBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, pinBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, bluetoothBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, wifiBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, volBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, powerBtn)
        ColorUtils.applySecondaryColor(context, sharedPreferences, batteryBtn)
    }

    private fun updateCorners() {
        topRightCorner.visibility = if (sharedPreferences.getBoolean("enable_corner_top_right", false)) View.VISIBLE else View.GONE
        bottomRightCorner.visibility = if (sharedPreferences.getBoolean("enable_corner_bottom_right", false)) View.VISIBLE else View.GONE
    }

    private fun updateMenuIcon() {
        val iconUri = sharedPreferences.getString("menu_icon_uri", "default")
        if (iconUri == "default") appsBtn.setImageResource(R.drawable.ic_apps_menu) else {
            try {
                val icon = Uri.parse(iconUri)
                if (icon != null) appsBtn.setImageURI(icon)
            } catch (_: Exception) {
            }
        }
    }

    private fun toggleFavorites(visible: Boolean) {
        favoritesGv.visibility = if (visible) View.VISIBLE else View.GONE
        appsSeparator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun loadFavoriteApps() {
        val apps = AppUtils.getPinnedApps(context, packageManager, AppUtils.PINNED_LIST)
        toggleFavorites(apps.size > 0)
        val menuFullscreen = sharedPreferences.getBoolean("app_menu_fullscreen", false)
        val phoneLayout = sharedPreferences.getInt("dock_layout", -1) == 0
        favoritesGv.adapter = AppAdapter(context, apps, this, menuFullscreen && !phoneLayout)
    }

    fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else DeviceUtils.sendKeyEvent(KeyEvent.KEYCODE_SYSRQ)
    }

    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else DeviceUtils.lockScreen(context)
    }

    override fun onTouch(p1: View, p2: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(p2)
        return false
    }

    override fun onDestroy() {
        //TODO: Unregister all receivers
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(soundEventsReceiver)
        super.onDestroy()
    }

    internal inner class UpdateAppMenuTask : AsyncTask<Void?, Void?, ArrayList<App>>() {
        override fun doInBackground(p1: Array<Void?>): ArrayList<App> {
            return AppUtils.getInstalledApps(packageManager)
        }

        override fun onPostExecute(result: ArrayList<App>) {
            super.onPostExecute(result)

            //TODO: Implement efficient adapter
            val menuFullscreen = sharedPreferences.getBoolean("app_menu_fullscreen", false)
            val phoneLayout = sharedPreferences.getInt("dock_layout", -1) == 0
            appsGv.adapter = AppAdapter(context, result, this@DockService,
                    menuFullscreen && !phoneLayout)
        }
    }
}
