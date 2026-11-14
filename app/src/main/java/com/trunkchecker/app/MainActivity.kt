package com.trunkchecker.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.car.Car
import android.car.VehicleAreaSeat
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAR = 100
        private const val PERM_CAR_SPEED = "android.car.permission.CAR_SPEED"
        private const val PERM_SEAT_BELTS = "android.car.permission.READ_CAR_SEAT_BELTS"
        private const val HARD_BRAKE = 3.0f      // m/s², 앱 자체 기준값
        private const val HARD_ACCEL = 3.0f      // m/s², 앱 자체 기준값
        private const val SHAKE_START = 1.5f     // m/s², 앱 자체 기준값
        private const val SHAKE_FULL = 4.5f      // m/s², 앱 자체 기준값
        private const val SHAKE_ALERT_MS = 5000L // 흔들림 감지 후 붉은 경고 유지 시간
        private const val MOVING_KMH = 3f        // 주행 중으로 보는 속도 (앱 자체 기준값)
    }

    // ---- 차량 SDK (문 4개·트렁크) ----
    private lateinit var vehicle: Vehicle
    private var vehicleReady = false
    private val trunkArea = DoorArea(DoorValue.REAR)
    private val doorAreas = listOf(
        DoorArea(DoorValue.ROW_1_LEFT),   // 0: 운전석
        DoorArea(DoorValue.ROW_1_RIGHT),  // 1: 조수석
        DoorArea(DoorValue.ROW_2_LEFT),   // 2: 뒷좌석 왼쪽
        DoorArea(DoorValue.ROW_2_RIGHT)   // 3: 뒷좌석 오른쪽
    )
    private val doorNames = listOf("운전석 문", "조수석 문", "뒷좌석 왼쪽 문", "뒷좌석 오른쪽 문")
    private val doorOpen = arrayOfNulls<Boolean>(4)
    private val doorRegistered = BooleanArray(4)
    private var trunkRegistered = false

    // ---- AAOS 표준 속성 (속도·안전벨트) ----
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var speedRegistered = false
    private var beltRegistered = false
    private var speedStatus = ""
    private var lastSpeed: Float? = null
    private var lastSpeedTimeNs = 0L
    private var lastShakeTimeNs = 0L
    private var currentSpeedKmh = 0f
    private var hardBrakeCount = 0
    private var hardAccelCount = 0
    private var peakAccel = 0f
    private var shakeAlertUntil = 0L

    // ---- 상태 ----
    private var trunkOpen: Boolean? = null
    private var beltBuckled: Boolean? = null
    private var beltSupported = false
    private val items = mutableListOf<String>()
    private val extraCandidates = mutableListOf<String>()
    private var reminderDialog: Dialog? = null
    private var driveBlockDialog: Dialog? = null
    private var driveBlockContent: LinearLayout? = null
    private var lastDriveReasons: List<String> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    // ---- 글꼴 ----
    private val fontRegular by lazy { loadFont("pretendard_regular", Typeface.DEFAULT) }
    private val fontBold by lazy { loadFont("pretendard_bold", Typeface.DEFAULT_BOLD) }

    // ---- 색상 ----
    private val cDeep = Color.parseColor("#2F6B5F")
    private val cMint = Color.parseColor("#5FAE9C")
    private val cText = Color.parseColor("#2E3B38")
    private val cSub = Color.parseColor("#7D918C")
    private val cAmber = Color.parseColor("#C77700")
    private val cRed = Color.parseColor("#C62828")
    private val cRedSoft = Color.parseColor("#FFE3E1")
    private val argb = ArgbEvaluator()

    // ---- 화면 ----
    private lateinit var sdkStatusText: TextView
    private lateinit var carView: CarTopView
    private lateinit var beltView: SeatbeltView
    private lateinit var beltText: TextView
    private lateinit var brakePedal: PedalView
    private lateinit var accelPedal: PedalView
    private lateinit var brakeCountText: TextView
    private lateinit var accelCountText: TextView
    private lateinit var trunkValue: TextView
    private lateinit var doorValue: TextView
    private lateinit var beltValue: TextView
    private lateinit var itemsValue: TextView
    private lateinit var shakeValue: TextView
    private lateinit var paletteGrid: LinearLayout
    private lateinit var itemInput: EditText

    private val presetItems = listOf(
        "장바구니", "냉장·냉동식품", "노트북 가방", "유모차", "캐리어", "운동 가방", "택배 상자", "반려동물"
    )
    private val livingKeywords = listOf(
        "반려", "강아지", "고양이", "햄스터", "토끼", "물고기", "거북", "앵무", "애완"
    )

    private fun isLiving(name: String) = livingKeywords.any { name.contains(it) }

    // ================= 리스너 =================

    private val trunkListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            runOnUiThread { onTrunkChanged(pos) }
        }
        override fun onFailed(exception: Exception) {}
    }

    private val doorListeners = List(4) { i ->
        object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) {
                runOnUiThread { onDoorChanged(i, pos) }
            }
            override fun onFailed(exception: Exception) {}
        }
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val speed = value.value as? Float ?: return
            val timeNs = value.timestamp
            runOnUiThread { onSpeedSample(speed, timeNs) }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {}
    }

    private val beltCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.areaId != VehicleAreaSeat.SEAT_ROW_1_LEFT) return
            val buckled = value.value as? Boolean ?: return
            runOnUiThread {
                beltBuckled = buckled
                renderStatus()
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadItems()
        buildUi()
        renderAll()
        startVehicle()
        requestCarPermissionsThenStart()
    }

    // ================= 차량 연결: 문 4개·트렁크 =================

    private fun startVehicle() {
        vehicle = Vehicle(applicationContext)
        try {
            vehicle.initialize()
            vehicleReady = true
        } catch (e: Exception) {
            sdkStatusText.text = "차량 SDK 초기화 실패"
            return
        }
        handler.postDelayed({ setupDoorMonitoring() }, 1000)
    }

    private fun setupDoorMonitoring() {
        val door = vehicle.getDoor()
        door.checkDoorPositionCapabilities(
            onComplete = { _, areaBounds ->
                runOnUiThread {
                    val trunkSupported = areaBounds.containsKey(trunkArea)
                    val doorSupported = doorAreas.map { areaBounds.containsKey(it) }
                    val allOk = trunkSupported && doorSupported.all { it }
                    sdkStatusText.text = (if (allOk) "●  차량 연결됨" else "일부 신호 미지원") + speedStatus

                    if (trunkSupported) {
                        door.getCurrentDoorPosition(
                            area = trunkArea,
                            onComplete = { pos -> runOnUiThread { onTrunkChanged(pos, initial = true) } },
                            onFailed = { _ -> }
                        )
                        door.registerDoorPosition(trunkArea, trunkListener)
                        trunkRegistered = true
                    }
                    doorAreas.forEachIndexed { i, area ->
                        if (!doorSupported[i]) return@forEachIndexed
                        door.getCurrentDoorPosition(
                            area = area,
                            onComplete = { pos -> runOnUiThread { onDoorChanged(i, pos, initial = true) } },
                            onFailed = { _ -> }
                        )
                        door.registerDoorPosition(area, doorListeners[i])
                        doorRegistered[i] = true
                    }
                }
            },
            onFailed = { _ -> runOnUiThread { sdkStatusText.text = "차량 연결 실패$speedStatus" } }
        )
    }

    // ================= 차량 연결: 속도·안전벨트 (AAOS 표준) =================

    private fun requestCarPermissionsThenStart() {
        val needed = listOf(PERM_CAR_SPEED, PERM_SEAT_BELTS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_CAR) else startCarProperties()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAR) startCarProperties()
    }

    private fun startCarProperties() {
        try {
            if (car == null) car = Car.createCar(this)
            propertyManager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (_: Exception) {
            speedStatus = " · 차량 속성 연결 실패"
            sdkStatusText.text = sdkStatusText.text.toString() + speedStatus
            return
        }
        val pm = propertyManager ?: return

        if (checkSelfPermission(PERM_CAR_SPEED) == PackageManager.PERMISSION_GRANTED) {
            try {
                speedRegistered = pm.registerCallback(
                    speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL
                )
                speedStatus = if (speedRegistered) "" else " · 속도 구독 실패"
            } catch (_: Exception) {
                speedStatus = " · 속도 오류"
            }
        } else {
            speedStatus = " · 속도 권한 없음"
        }
        sdkStatusText.text = sdkStatusText.text.toString() + speedStatus

        try {
            beltRegistered = pm.registerCallback(
                beltCallback, VehiclePropertyIds.SEAT_BELT_BUCKLED, CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
            beltSupported = beltRegistered
            if (beltRegistered) {
                try {
                    beltBuckled = pm.getBooleanProperty(VehiclePropertyIds.SEAT_BELT_BUCKLED, VehicleAreaSeat.SEAT_ROW_1_LEFT)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            beltSupported = false
        }
        renderStatus()
    }

    private fun onSpeedSample(speedMs: Float, timeNs: Long) {
        currentSpeedKmh = speedMs * 3.6f
        checkDriveSafety()
        val prevSpeed = lastSpeed
        val prevTime = lastSpeedTimeNs
        lastSpeed = speedMs
        lastSpeedTimeNs = timeNs
        if (prevSpeed != null && timeNs > prevTime) {
            val dt = (timeNs - prevTime) / 1_000_000_000f
            if (dt in 0.01f..3f) {
                val accel = (speedMs - prevSpeed) / dt
                peakAccel = max(peakAccel, abs(accel))
                if (timeNs - lastShakeTimeNs > 2_000_000_000L) {
                    if (accel <= -HARD_BRAKE) {
                        lastShakeTimeNs = timeNs
                        recordShake(isBrake = true, magnitude = abs(accel))
                    } else if (accel >= HARD_ACCEL) {
                        lastShakeTimeNs = timeNs
                        recordShake(isBrake = false, magnitude = abs(accel))
                    }
                }
            }
        }
    }

    private fun recordShake(isBrake: Boolean, magnitude: Float) {
        if (isBrake) {
            hardBrakeCount++
            brakePedal.press()
        } else {
            hardAccelCount++
            accelPedal.press()
        }
        peakAccel = max(peakAccel, magnitude)
        shakeAlertUntil = SystemClock.elapsedRealtime() + SHAKE_ALERT_MS
        renderStatus()
        handler.postDelayed({ renderStatus() }, SHAKE_ALERT_MS + 100)
    }

    private fun resetShake() {
        hardBrakeCount = 0
        hardAccelCount = 0
        peakAccel = 0f
        shakeAlertUntil = 0L
        renderStatus()
    }

    private fun shakeSeverity(): Float {
        val countScore = (hardBrakeCount + hardAccelCount) / 4f
        val peakScore = (peakAccel - SHAKE_START) / (SHAKE_FULL - SHAKE_START)
        return max(countScore, peakScore).coerceIn(0f, 1f)
    }

    private fun severityColor(s: Float) =
        if (s < 0.5f) blend(cDeep, cAmber, s / 0.5f) else blend(cAmber, cRed, (s - 0.5f) / 0.5f)

    private fun anyDoorOpen() = doorOpen.any { it == true }

    private fun isAlert() = trunkOpen == true || anyDoorOpen() || beltBuckled == false ||
            SystemClock.elapsedRealtime() < shakeAlertUntil

    // ================= 상태 변화 =================

    private fun onTrunkChanged(pos: Int?, initial: Boolean = false) {
        val nowOpen = pos?.let { it > 0 }
        val wasOpen = trunkOpen
        trunkOpen = nowOpen
        if (!initial && wasOpen != true && nowOpen == true) resetShake()
        renderStatus()
    }

    private fun onDoorChanged(index: Int, pos: Int?, initial: Boolean = false) {
        val nowOpen = pos?.let { it > 0 }
        val wasOpen = doorOpen[index]
        doorOpen[index] = nowOpen
        // 하차 리마인드는 운전석 문(0)이 정차 중에 열릴 때만
        if (index == 0 && !initial && wasOpen != true && nowOpen == true &&
            items.isNotEmpty() && currentSpeedKmh < MOVING_KMH) {
            showLeaveReminder()
        }
        renderStatus()
    }

    // ================= 화면 갱신 =================

    private fun renderAll() {
        renderStatus()
        renderPalette()
    }

    private fun renderStatus() {
        carView.setState(
            BooleanArray(4) { doorOpen[it] == true },
            trunkOpen == true, items.map { kindOf(it) }, isAlert()
        )
        beltView.setBuckled(beltBuckled)

        setValue(trunkValue, openText(trunkOpen), openColor(trunkOpen))

        val openCount = doorOpen.count { it == true }
        when {
            doorOpen.all { it == null } -> setValue(doorValue, "확인 중", cSub)
            openCount == 0 -> setValue(doorValue, "모두 닫힘", cDeep)
            else -> setValue(doorValue, "${openCount}개 열림", cRed)
        }

        when (beltBuckled) {
            true -> {
                setValue(beltValue, "착용", cDeep)
                beltText.text = "운전석 안전벨트 착용"
                beltText.setTextColor(cDeep)
            }
            false -> {
                setValue(beltValue, "미착용", cRed)
                beltText.text = "운전석 안전벨트 미착용"
                beltText.setTextColor(cRed)
            }
            null -> {
                val t = if (beltSupported) "확인 중" else "신호 없음"
                setValue(beltValue, t, cSub)
                beltText.text = "운전석 안전벨트 · $t"
                beltText.setTextColor(cSub)
            }
        }
        setValue(itemsValue, "${items.size}개", if (items.isEmpty()) cSub else cDeep)

        val s = shakeSeverity()
        val sColor = severityColor(s)
        setValue(shakeValue, when {
            s < 0.2f -> "안정"
            s < 0.6f -> "주의"
            else -> "위험"
        }, sColor)
        brakeCountText.text = "급감속 ${hardBrakeCount}회"
        accelCountText.text = "급가속 ${hardAccelCount}회"
        brakeCountText.setTextColor(if (hardBrakeCount > 0) sColor else cSub)
        accelCountText.setTextColor(if (hardAccelCount > 0) sColor else cSub)

        checkDriveSafety()
    }

    /** 값 + 색 점 + 칸 배경을 상태 색으로 함께 바꿈 */
    private fun setValue(view: TextView, text: String, color: Int) {
        view.text = text
        view.setTextColor(color)
        val row = view.parent as? LinearLayout ?: return
        row.getChildAt(0)?.background = circle(color)
        row.background = glass(20, blend(Color.WHITE, color, 0.14f))
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(px(2f), Color.argb(200, 255, 255, 255))
    }

    private fun renderPalette() {
        paletteGrid.removeAllViews()
        val candidates = presetItems + extraCandidates
        candidates.chunked(3).forEach { rowNames ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowNames.forEach { name -> row.addView(paletteTile(name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(3 - rowNames.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            paletteGrid.addView(row, withTop(4))
        }
    }

    private fun paletteTile(name: String): LinearLayout {
        val inTrunk = items.contains(name)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(Item3DIconView(this@MainActivity, kindOf(name), inTrunk), LinearLayout.LayoutParams(dp(104), dp(104)))
            addView(label(name, 16f, if (inTrunk) cSub else cText, bold = true).apply { gravity = Gravity.CENTER }, withTop(4))
            if (inTrunk) addView(label("트렁크 안", 13f, cMint, bold = true).apply { gravity = Gravity.CENTER }, withTop(2))
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 95, 174, 156)), null, mask)
            isClickable = true
            setOnClickListener { toggleItem(name) }
            if (name !in presetItems) {
                setOnLongClickListener {
                    deleteCustomCandidate(name)
                    true
                }
            }
        }
    }

    // ================= 짐 =================

    private fun blockedWhileDriving() = currentSpeedKmh >= MOVING_KMH
    private fun toggleItem(name: String) {
        if (blockedWhileDriving()) return
        if (items.contains(name)) {
            items.remove(name)
        } else {
            items.add(name)
            if (isLiving(name)) showLivingNotice(name)
        }
        saveItems()
        renderAll()
    }

    private fun addCustomItem(raw: String) {
        if (blockedWhileDriving()) return
        val name = raw.trim()
        if (name.isEmpty()) return
        if (name !in presetItems && name !in extraCandidates) extraCandidates.add(name)
        if (!items.contains(name)) {
            items.add(name)
            if (isLiving(name)) showLivingNotice(name)
        }
        saveItems()
        renderAll()
    }

    private fun deleteCustomCandidate(name: String) {
        extraCandidates.remove(name)
        items.remove(name)
        saveItems()
        renderAll()
    }

    private fun loadItems() {
        val prefs = getSharedPreferences("trunk", MODE_PRIVATE)
        items.clear()
        items.addAll((prefs.getString("items", "") ?: "").split("\n").filter { it.isNotBlank() })
        extraCandidates.clear()
        extraCandidates.addAll((prefs.getString("extras", "") ?: "").split("\n").filter { it.isNotBlank() })
        items.filter { it !in presetItems && it !in extraCandidates }.forEach { extraCandidates.add(it) }
    }

    private fun saveItems() {
        getSharedPreferences("trunk", MODE_PRIVATE).edit()
            .putString("items", items.joinToString("\n"))
            .putString("extras", extraCandidates.joinToString("\n"))
            .apply()
    }

    // ================= 팝업 =================

    private fun newDialog(cancelable: Boolean): Dialog = Dialog(this).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(cancelable)
        setCanceledOnTouchOutside(cancelable)
    }

    private fun iconRow(names: List<String>, labelColor: Int): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.take(5).forEach { name ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            tile.addView(Item3DIconView(this, kindOf(name), false), LinearLayout.LayoutParams(dp(76), dp(76)))
            tile.addView(label(name, 14f, labelColor, bold = true).apply { gravity = Gravity.CENTER }, withTop(4))
            row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    /** 트렁크를 눌렀을 때: 안의 짐 확인 + 꺼내기 (바깥 누르면 닫힘) */
    private fun showTrunkContents() {
        if (blockedWhileDriving()) return
        val dialog = newDialog(cancelable = true)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(32, Color.WHITE, strong = true)
            setPadding(dp(36), dp(32), dp(36), dp(28))
        }

        fun rebuild() {
            content.removeAllViews()
            if (items.isEmpty()) {
                content.addView(label("트렁크가 비어 있어요", 24f, cDeep, bold = true))
                content.addView(label("오른쪽 짐 아이콘을 눌러 트렁크에 넣을 수 있어요.", 16f, cSub), withTop(6))
            } else {
                content.addView(label("트렁크 안 짐 ${items.size}개", 24f, cDeep, bold = true))
                content.addView(label("꺼낸 짐은 [꺼내기]를 눌러 주세요", 15f, cSub), withTop(4))
                items.toList().chunked(4).forEach { rowNames ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    rowNames.forEach { name ->
                        val tile = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
                        tile.addView(Item3DIconView(this, kindOf(name), false), LinearLayout.LayoutParams(dp(84), dp(84)))
                        tile.addView(label(name, 15f, if (isLiving(name)) cRed else cText, bold = true).apply {
                            gravity = Gravity.CENTER
                        }, withTop(4))
                        tile.addView(glassButton("꺼내기", primary = false, small = true) {
                            items.remove(name)
                            saveItems()
                            renderAll()
                            rebuild()
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) })
                        row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    repeat(4 - rowNames.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                    content.addView(row, withTop(18))
                }
                val bottomRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                bottomRow.addView(glassButton("모두 꺼내기", primary = true) {
                    items.clear()
                    saveItems()
                    resetShake()
                    renderAll()
                    rebuild()
                })
                content.addView(bottomRow, withTop(22))
            }
            content.addView(label("바깥을 누르면 닫혀요", 13f, cSub).apply { gravity = Gravity.END }, withTop(12))
        }

        rebuild()
        dialog.setContentView(content, ViewGroup.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }

    /** [짐 다 넣었어요] 안내 팝업 - 바깥을 누르면 닫힘 */
    private fun showLoadedSummary() {
        if (blockedWhileDriving()) return
        val dialog = newDialog(cancelable = true)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(32, Color.WHITE, strong = true)
            setPadding(dp(36), dp(32), dp(36), dp(28))
        }
        if (items.isEmpty()) {
            content.addView(label("트렁크가 비어 있어요", 24f, cDeep, bold = true))
            content.addView(label("오른쪽 짐 아이콘을 눌러 트렁크에 넣어 주세요.", 17f, cSub), withTop(8))
        } else {
            content.addView(label("트렁크 짐 ${items.size}개를 기억할게요", 24f, cDeep, bold = true))
            content.addView(label("운전석 문을 열 때 놓고 내리지 않도록 알려드려요.", 17f, cSub), withTop(6))
            content.addView(iconRow(items, cText), withTop(18))
            if (items.size > 5) content.addView(label("외 ${items.size - 5}개", 15f, cSub), withTop(4))
            if (items.any { isLiving(it) }) {
                content.addView(label("살아있는 생물이 포함돼 있어요. 가능하면 뒷좌석 이동장을 이용해 주세요.", 15f, cRed, bold = true).apply {
                    background = glass(16, cRedSoft, strong = true)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }, withTop(16))
            }
        }
        content.addView(label("바깥을 누르면 닫혀요", 13f, cSub).apply { gravity = Gravity.END }, withTop(18))
        dialog.setContentView(content, ViewGroup.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }

    private fun unsafeReasons(): List<String> = buildList {
        if (beltBuckled == false) add("운전석 안전벨트 미착용")
        doorOpen.forEachIndexed { i, open -> if (open == true) add("${doorNames[i]} 열림") }
        if (trunkOpen == true) add("트렁크 열림")
    }

    /** 주행 중인데 위험 조건이 있으면 주행 금지 팝업. 항목이 바뀔 때만 다시 그리고, 해결·정차 시 자동으로 닫음 */
    private fun checkDriveSafety() {
        val reasons = unsafeReasons()
        if (currentSpeedKmh >= MOVING_KMH && reasons.isNotEmpty()) {
            if (driveBlockDialog == null || reasons != lastDriveReasons) {
                lastDriveReasons = reasons
                showDriveBlock(reasons)
            }
        } else if (driveBlockDialog != null) {
            driveBlockDialog?.dismiss()
            driveBlockDialog = null
            driveBlockContent = null
            lastDriveReasons = emptyList()
        }
    }

    private fun showDriveBlock(reasons: List<String>) {
        val content = driveBlockContent ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redPanel()
            setPadding(dp(40), dp(36), dp(40), dp(32))
        }
        content.removeAllViews()
        content.addView(label("주행 금지", 40f, Color.WHITE, bold = true).apply { paint.isFakeBoldText = true })
        content.addView(label("아래 항목을 해결하기 전까지 주행하지 마세요", 19f, Color.WHITE), withTop(6))
        reasons.forEach { reason ->
            content.addView(label("●  $reason", 24f, Color.WHITE, bold = true).apply {
                background = glass(18, Color.WHITE).also { it.alpha = 60 }
                setPadding(dp(20), dp(14), dp(20), dp(14))
            }, withTop(12))
        }
        content.addView(label("안전한 곳에 정차한 뒤 확인해 주세요 · 해결되면 자동으로 닫혀요", 15f, Color.argb(220, 255, 255, 255)), withTop(18))

        if (driveBlockDialog == null) {
            reminderDialog?.dismiss()
            val dialog = newDialog(cancelable = false)
            dialog.setContentView(content, ViewGroup.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.show()
            driveBlockDialog = dialog
            driveBlockContent = content
        }
    }

    private fun showLeaveReminder() {
        reminderDialog?.dismiss()
        val living = items.filter { isLiving(it) }
        val strong = living.isNotEmpty()
        val titleColor = if (strong) Color.WHITE else cRed
        val bodyColor = if (strong) Color.WHITE else Color.parseColor("#8A4B47")
        val dialog = newDialog(cancelable = !strong)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (strong) redPanel() else glass(32, cRedSoft, strong = true)
            setPadding(dp(36), dp(32), dp(36), dp(28))
        }
        if (strong) {
            content.addView(label("살아있는 생물이 트렁크에 있어요", 17f, titleColor, bold = true))
            content.addView(label("${living.joinToString(", ")} 꼭 데리고 내리세요", 28f, titleColor, bold = true), withTop(4))
        } else {
            content.addView(label("내리기 전에 확인하세요", 26f, titleColor, bold = true))
            content.addView(label("트렁크에 짐 ${items.size}개가 있어요.", 18f, bodyColor), withTop(6))
        }
        if (hardBrakeCount + hardAccelCount > 0) {
            content.addView(label(
                "주행 중 급감속 ${hardBrakeCount}회 · 급가속 ${hardAccelCount}회가 있었어요.\n짐이 쏠렸을 수 있으니 트렁크를 열 때 주의하세요.",
                16f, cRed, bold = true
            ).apply {
                background = glass(16, Color.WHITE, strong = true)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }, withTop(14))
        }
        val shown = if (strong) living else items
        content.addView(iconRow(shown, bodyColor), withTop(18))
        if (shown.size > 5) content.addView(label("외 ${shown.size - 5}개", 15f, bodyColor), withTop(4))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (strong) {
            buttonRow.addView(glassButton("데리고 내렸어요", primary = false, danger = true) {
                items.removeAll(living)
                saveItems()
                renderAll()
                dialog.dismiss()
                if (items.isNotEmpty()) showLeaveReminder()
            })
        } else {
            buttonRow.addView(glassButton("두고 갈게요", primary = false, danger = true) { dialog.dismiss() }, wrapWithEnd(12))
            buttonRow.addView(glassButton("모두 꺼냈어요", primary = true, danger = true) {
                items.clear()
                saveItems()
                resetShake()
                renderAll()
                dialog.dismiss()
            })
        }
        content.addView(buttonRow, withTop(24))
        dialog.setContentView(content, ViewGroup.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        reminderDialog = dialog
    }

    private fun showLivingNotice(name: String) {
        val dialog = newDialog(cancelable = false)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redPanel()
            setPadding(dp(36), dp(32), dp(36), dp(28))
        }
        content.addView(label("트렁크는 생물에게 위험할 수 있어요", 24f, Color.WHITE, bold = true))
        content.addView(label(
            "트렁크는 환기와 온도 조절이 어려워 ${name}에게 위험할 수 있어요.\n" +
                    "가능하면 뒷좌석에 이동장으로 태워 주세요.\n" +
                    "내릴 때는 일반 짐보다 강하게 알려드릴게요.",
            17f, Color.WHITE
        ).apply { setLineSpacing(0f, 1.2f) }, withTop(12))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(glassButton("알겠어요", primary = false, danger = true) { dialog.dismiss() })
        content.addView(row, withTop(22))
        dialog.setContentView(content, ViewGroup.LayoutParams(dp(600), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }

    // ================= 화면 구성 =================

    private fun buildUi() {
        val rootFrame = FrameLayout(this).apply { background = backgroundDrawable() }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(20))
        }
        rootFrame.addView(main, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ---- 헤더 ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("TRuNkCheCker", 32f, Color.parseColor("#1F4D44"), bold = true).apply {
            letterSpacing = 0.02f
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sdkStatusText = label("차량 연결 준비 중", 15f, cDeep, bold = true).apply {
            background = glass(999, Color.WHITE)
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        header.addView(sdkStatusText, wrapWithEnd(14))
        header.addView(glassButton("짐 다 넣었어요", primary = true) { showLoadedSummary() })
        main.addView(header)

        // ---- 페달 ----
        val dash = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        brakePedal = PedalView(this, isBrake = true)
        accelPedal = PedalView(this, isBrake = false)
        brakeCountText = label("급감속 0회", 17f, cSub, bold = true)
        accelCountText = label("급가속 0회", 17f, cSub, bold = true)
        dash.addView(pedalBox(brakePedal, "브레이크", brakeCountText), wrapWithEnd(80))
        dash.addView(pedalBox(accelPedal, "액셀", accelCountText))
        main.addView(dash, withTop(14))

        // ---- 가운데 3단 (남은 높이 전부 사용) ----
        val center = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(center, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        // 왼쪽: 차량 상태
        val statusCard = card()
        statusCard.addView(label("차량 상태", 22f, cText, bold = true))
        trunkValue = statusRow(statusCard, "트렁크")
        doorValue = statusRow(statusCard, "문 (4개)")
        beltValue = statusRow(statusCard, "안전벨트")
        itemsValue = statusRow(statusCard, "트렁크 짐")
        shakeValue = statusRow(statusCard, "주행 흔들림")
        center.addView(statusCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.2f).apply { marginEnd = dp(18) })

        // 가운데: 차 + 안전벨트
        val carColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        carColumn.addView(label("트렁크를 누르면 안의 짐을 확인하고 꺼낼 수 있어요", 14f, cSub).apply {
            gravity = Gravity.CENTER
        })
        carView = CarTopView(this)
        carView.onTrunkClick = { showTrunkContents() }
        carColumn.addView(carView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val beltRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        beltView = SeatbeltView(this)
        beltRow.addView(beltView, LinearLayout.LayoutParams(dp(120), dp(120)))
        beltText = label("운전석 안전벨트", 20f, cSub, bold = true)
        beltRow.addView(beltText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        carColumn.addView(beltRow, withTop(4))
        center.addView(carColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4.6f).apply { marginEnd = dp(18) })

        // 오른쪽: 짐 아이콘
        val paletteCard = card()
        paletteCard.addView(label("짐", 22f, cText, bold = true))
        paletteCard.addView(label("누르면 트렁크에 넣고, 다시 누르면 꺼내요", 14f, cSub), withTop(2))
        paletteGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paletteCard.addView(paletteGrid, withTop(6))
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        itemInput = EditText(this).apply {
            hint = "직접 입력 (예: 우산)"
            textSize = 16f
            typeface = fontRegular
            setSingleLine()
            setTextColor(cText)
            setHintTextColor(cSub)
            background = glass(18, Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        inputRow.addView(itemInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        inputRow.addView(glassButton("넣기", primary = true, small = true) {
            addCustomItem(itemInput.text.toString())
            itemInput.setText("")
        })
        paletteCard.addView(inputRow, withTop(12))
        center.addView(paletteCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3.2f))

        setContentView(rootFrame)
    }

    private fun pedalBox(pedal: PedalView, title: String, countText: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(pedal, LinearLayout.LayoutParams(dp(190), dp(190)))
        addView(label(title, 20f, cText, bold = true).apply { gravity = Gravity.CENTER }, withTop(4))
        addView(countText.apply {
            gravity = Gravity.CENTER
            background = glass(999, Color.WHITE, strong = true)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
    }

    private fun statusRow(parent: LinearLayout, title: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(20, Color.WHITE)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val dot = View(this).apply { background = circle(cSub) }
        row.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(12) })
        row.addView(label(title, 18f, cText, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = label("확인 중", 28f, cSub, bold = true)
        row.addView(value)
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
        })
        return value
    }

    // ================= 디자인 도우미 =================

    private fun loadFont(name: String, fallback: Typeface): Typeface {
        val id = resources.getIdentifier(name, "font", packageName)
        if (id == 0) return fallback
        return try {
            resources.getFont(id)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun blend(from: Int, to: Int, fraction: Float) =
        argb.evaluate(fraction.coerceIn(0f, 1f), from, to) as Int

    private fun openText(open: Boolean?) = when (open) {
        true -> "열림"
        false -> "닫힘"
        null -> "확인 중"
    }

    private fun openColor(open: Boolean?) = when (open) {
        true -> cRed
        false -> cDeep
        null -> cSub
    }

    /** 반투명 유리 카드: 투명한 흰색 + 안쪽 민트빛 번짐 + 위쪽 광택 + 연한 테두리 */
    private fun glass(radiusDp: Int, tint: Int, strong: Boolean = false): Drawable {
        val radius = dp(radiusDp).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(withAlpha(tint, if (strong) 245 else 165), withAlpha(tint, if (strong) 225 else 95))
        ).apply { cornerRadius = radius }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.argb(if (strong) 0 else 50, 168, 214, 200), Color.TRANSPARENT)
            gradientRadius = dp(280).toFloat()
            setGradientCenter(0.80f, 0.85f)
            cornerRadius = radius
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(95, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = radius }
        val rim = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(px(1f), Color.argb(if (strong) 170 else 140, 255, 255, 255))
        }
        return LayerDrawable(arrayOf(base, glow, gloss, rim))
    }

    private fun pill(top: String, bottom: String): Drawable {
        val radius = dp(999).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor(top), Color.parseColor(bottom))
        ).apply {
            cornerRadius = radius
            setStroke(px(1f), Color.argb(170, 255, 255, 255))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, gloss))
    }

    private fun redPanel(): Drawable {
        val radius = dp(32).toFloat()
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#EF6B63"), Color.parseColor("#C62828"))
        ).apply {
            cornerRadius = radius
            setStroke(px(1f), Color.argb(170, 255, 255, 255))
        }
        val gloss = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(base, gloss))
    }

    private fun glassButton(
        text: String, primary: Boolean, small: Boolean = false, danger: Boolean = false, onClick: () -> Unit
    ) = TextView(this).apply {
        this.text = text
        textSize = if (small) 15f else 18f
        gravity = Gravity.CENTER
        typeface = fontBold
        minHeight = dp(if (small) 44 else 54)
        setPadding(dp(if (small) 18 else 26), dp(10), dp(if (small) 18 else 26), dp(10))
        setTextColor(when {
            primary -> Color.WHITE
            danger -> cRed
            else -> cDeep
        })
        val shape = when {
            primary && danger -> pill("#F28B82", "#D93B3B")
            primary -> pill("#A6DCCF", "#5FAE9C")
            else -> glass(999, Color.WHITE, strong = danger)
        }
        val ripple = if (danger) Color.argb(60, 198, 40, 40) else Color.argb(60, 95, 174, 156)
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** 아이보리 바탕 + 은은한 민트·핑크 번짐 */
    private fun backgroundDrawable(): Drawable {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F8F6F1"), Color.parseColor("#F2EEE8"))
        )
        fun blob(color: Int, cx: Float, cy: Float, radiusDp: Int) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(color, Color.TRANSPARENT)
            gradientRadius = dp(radiusDp).toFloat()
            setGradientCenter(cx, cy)
        }
        return LayerDrawable(arrayOf(
            base,
            blob(Color.argb(95, 178, 222, 210), 0.15f, 0.20f, 650),
            blob(Color.argb(55, 178, 222, 210), 0.55f, 0.55f, 520),
            blob(Color.argb(55, 250, 232, 214), 0.85f, 0.10f, 450),
            blob(Color.argb(85, 247, 214, 208), 0.90f, 0.88f, 600)
        ))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(28, Color.WHITE)
        setPadding(dp(24), dp(20), dp(24), dp(20))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) fontBold else fontRegular
    }

    private fun kindOf(name: String) = when (name) {
        "장바구니" -> 0
        "냉장·냉동식품" -> 1
        "노트북 가방" -> 2
        "유모차" -> 3
        "캐리어" -> 4
        "운동 가방" -> 5
        "택배 상자" -> 6
        "반려동물" -> 8
        else -> if (isLiving(name)) 8 else 7
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun withTop(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun wrapWithEnd(endDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(endDp) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun px(value: Float) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        reminderDialog?.dismiss()
        driveBlockDialog?.dismiss()
        try {
            if (speedRegistered) propertyManager?.unregisterCallback(speedCallback)
            if (beltRegistered) propertyManager?.unregisterCallback(beltCallback)
            car?.disconnect()
        } catch (_: Exception) {
        }
        if (vehicleReady) {
            try {
                val door = vehicle.getDoor()
                if (trunkRegistered) door.unregisterDoorPosition(trunkArea, trunkListener)
                doorAreas.forEachIndexed { i, area ->
                    if (doorRegistered[i]) door.unregisterDoorPosition(area, doorListeners[i])
                }
            } catch (_: Exception) {
            }
            try {
                vehicle.release()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }
}

// =====================================================================
// 짐 3D 아이콘 그림 (0~100 좌표계, 그라데이션·옆면·그림자로 입체감)
// =====================================================================
object ItemArt {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun col(s: String) = Color.parseColor(s)
    private fun solid(color: Int) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color
    }
    private fun grad(y0: Float, y1: Float, c0: String, c1: String) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, y0, 0f, y1, col(c0), col(c1), Shader.TileMode.CLAMP)
    }
    private fun line(color: Int, width: Float) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = color
    }
    private fun poly(vararg pts: Float) = Path().apply {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
        close()
    }

    fun draw(c: Canvas, kind: Int, withShadow: Boolean = true) {
        if (withShadow) {
            solid(Color.argb(40, 0, 0, 0))
            c.drawOval(RectF(22f, 85f, 78f, 95f), p)
        }
        when (kind) {
            0 -> { // 장바구니 (쇼핑백)
                solid(col("#4E9384")); c.drawPath(poly(66f, 36f, 76f, 30f, 76f, 78f, 66f, 84f), p)
                grad(36f, 84f, "#9ADBC9", "#5FAE9C"); c.drawRect(24f, 36f, 66f, 84f, p)
                solid(Color.argb(90, 255, 255, 255)); c.drawRect(24f, 36f, 66f, 41f, p)
                solid(Color.argb(55, 255, 255, 255)); c.drawRoundRect(RectF(28f, 46f, 33f, 79f), 3f, 3f, p)
                line(col("#2F6B5F"), 3.5f)
                c.drawArc(RectF(32f, 22f, 46f, 46f), 180f, 180f, false, p)
                c.drawArc(RectF(44f, 22f, 58f, 46f), 180f, 180f, false, p)
            }
            1 -> { // 냉장·냉동식품 (아이스박스)
                solid(col("#EAF6FB")); c.drawPath(poly(22f, 40f, 64f, 40f, 78f, 31f, 36f, 31f), p)
                solid(col("#78AFC8")); c.drawPath(poly(64f, 40f, 78f, 31f, 78f, 73f, 64f, 82f), p)
                grad(40f, 82f, "#CFE9F5", "#8EC3DB"); c.drawRect(22f, 40f, 64f, 82f, p)
                solid(col("#5B9DBA")); c.drawRect(22f, 40f, 64f, 47f, p)
                line(col("#3E7E99"), 3f); c.drawLine(42f, 35f, 58f, 35f, p)
                line(Color.WHITE, 2.2f)
                for (i in 0 until 3) {
                    val a = Math.toRadians(i * 60.0)
                    val dx = (8 * cos(a)).toFloat(); val dy = (8 * sin(a)).toFloat()
                    c.drawLine(43f - dx, 64f - dy, 43f + dx, 64f + dy, p)
                }
            }
            2 -> { // 노트북 가방
                solid(col("#22313D")); c.drawPath(poly(70f, 42f, 78f, 36f, 78f, 74f, 70f, 80f), p)
                solid(col("#7D93A3")); c.drawPath(poly(20f, 42f, 70f, 42f, 78f, 36f, 28f, 36f), p)
                grad(42f, 80f, "#5D7486", "#34495E"); c.drawRoundRect(RectF(20f, 42f, 70f, 80f), 5f, 5f, p)
                line(Color.argb(80, 255, 255, 255), 1.5f); c.drawLine(20f, 59f, 70f, 59f, p)
                line(col("#2C3E50"), 4f); c.drawRoundRect(RectF(38f, 27f, 54f, 37f), 4f, 4f, p)
                solid(col("#E0B44C")); c.drawRoundRect(RectF(42f, 47f, 48f, 53f), 1.5f, 1.5f, p)
            }
            3 -> { // 유모차
                line(col("#5E6E6B"), 3.5f)
                c.drawLine(40f, 70f, 35f, 79f, p); c.drawLine(60f, 70f, 67f, 79f, p)
                c.drawLine(78f, 50f, 86f, 32f, p)
                grad(24f, 76f, "#FFD3C7", "#F09A87"); c.drawArc(RectF(24f, 24f, 64f, 76f), 180f, 90f, true, p)
                grad(50f, 70f, "#F4B6A6", "#E07E6B")
                c.drawPath(Path().apply {
                    moveTo(24f, 50f); lineTo(78f, 50f); quadTo(76f, 70f, 60f, 70f)
                    lineTo(40f, 70f); quadTo(26f, 70f, 24f, 50f); close()
                }, p)
                solid(col("#34444A")); c.drawCircle(35f, 81f, 7f, p); c.drawCircle(67f, 81f, 7f, p)
                solid(col("#C9D6D2")); c.drawCircle(35f, 81f, 2.5f, p); c.drawCircle(67f, 81f, 2.5f, p)
            }
            4 -> { // 캐리어
                line(col("#7A8A87"), 3f)
                c.drawLine(44f, 16f, 44f, 31f, p); c.drawLine(56f, 16f, 56f, 31f, p); c.drawLine(44f, 16f, 56f, 16f, p)
                solid(col("#D9826B")); c.drawPath(poly(64f, 31f, 72f, 26f, 72f, 77f, 64f, 82f), p)
                grad(31f, 82f, "#FFB59E", "#F28B6E"); c.drawRoundRect(RectF(30f, 31f, 64f, 82f), 7f, 7f, p)
                line(Color.argb(80, 255, 255, 255), 2f)
                c.drawLine(39f, 37f, 39f, 76f, p); c.drawLine(47f, 37f, 47f, 76f, p); c.drawLine(55f, 37f, 55f, 76f, p)
                solid(col("#3C4A48")); c.drawCircle(36f, 86f, 3f, p); c.drawCircle(58f, 86f, 3f, p)
            }
            5 -> { // 운동 가방 (더플백)
                line(col("#3E4F96"), 4f); c.drawArc(RectF(30f, 24f, 70f, 58f), 180f, 180f, false, p)
                grad(42f, 80f, "#8FA5E8", "#5B72C9"); c.drawRoundRect(RectF(18f, 42f, 82f, 80f), 18f, 18f, p)
                solid(col("#4A5FB0")); c.drawOval(RectF(15f, 44f, 29f, 78f), p); c.drawOval(RectF(71f, 44f, 85f, 78f), p)
                line(Color.argb(130, 255, 255, 255), 2f); c.drawLine(30f, 49f, 70f, 49f, p)
                solid(Color.argb(45, 255, 255, 255)); c.drawRoundRect(RectF(28f, 55f, 72f, 61f), 3f, 3f, p)
            }
            6 -> { // 택배 상자
                solid(col("#F2D3A2")); c.drawPath(poly(20f, 40f, 50f, 28f, 80f, 40f, 50f, 52f), p)
                grad(40f, 86f, "#E2B47A", "#C9955A"); c.drawPath(poly(20f, 40f, 50f, 52f, 50f, 86f, 20f, 74f), p)
                solid(col("#B98348")); c.drawPath(poly(50f, 52f, 80f, 40f, 80f, 74f, 50f, 86f), p)
                line(col("#F7E7C8"), 5f); c.drawLine(35f, 34f, 65f, 46f, p)
                line(Color.argb(60, 255, 255, 255), 1.5f); c.drawLine(50f, 52f, 50f, 86f, p)
            }
            8 -> { // 반려동물 (발바닥)
                grad(48f, 80f, "#FFC9C2", "#F08A80"); c.drawOval(RectF(33f, 48f, 67f, 80f), p)
                grad(22f, 52f, "#FFD6D0", "#F29A90")
                c.drawOval(RectF(20f, 36f, 33f, 52f), p); c.drawOval(RectF(33f, 22f, 46f, 41f), p)
                c.drawOval(RectF(54f, 22f, 67f, 41f), p); c.drawOval(RectF(67f, 36f, 80f, 52f), p)
                solid(Color.argb(110, 255, 255, 255))
                c.drawOval(RectF(40f, 53f, 50f, 60f), p); c.drawOval(RectF(36f, 26f, 41f, 31f), p); c.drawOval(RectF(57f, 26f, 62f, 31f), p)
            }
            else -> { // 직접 입력한 짐 (태그)
                line(col("#B9862A"), 2f); c.drawLine(40f, 42f, 30f, 22f, p)
                grad(30f, 80f, "#FFE08A", "#F2B53C"); c.drawPath(poly(28f, 30f, 56f, 30f, 80f, 54f, 54f, 80f, 28f, 54f), p)
                solid(col("#FFF6DA")); c.drawCircle(40f, 42f, 4f, p)
                solid(Color.argb(60, 255, 255, 255)); c.drawPath(poly(30f, 32f, 54f, 32f, 60f, 38f, 30f, 38f), p)
            }
        }
    }
}

/** 짐 3D 아이콘 뷰 (트렁크 안에 있으면 흐리게 + 체크) */
class Item3DIconView(context: Context, private val kind: Int, private val inTrunk: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s / 100f, s / 100f)
        if (inTrunk) {
            val layer = canvas.saveLayerAlpha(0f, 0f, 100f, 100f, 90)
            ItemArt.draw(canvas, kind)
            canvas.restoreToCount(layer)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, 6f, 0f, 30f, Color.parseColor("#A6DCCF"), Color.parseColor("#5FAE9C"), Shader.TileMode.CLAMP)
            canvas.drawCircle(80f, 18f, 12f, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3.2f
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.WHITE
            canvas.drawPath(Path().apply { moveTo(74f, 18f); lineTo(78.5f, 22.5f); lineTo(86.5f, 13.5f) }, p)
        } else {
            ItemArt.draw(canvas, kind)
        }
        canvas.restore()
    }
}

/** 위에서 본 차: 문 4개·트렁크가 각각 열리고 닫히며, 경고 시 주변이 붉게 깜빡임. 트렁크를 누르면 콜백 */
class CarTopView(context: Context) : View(context) {
    var onTrunkClick: (() -> Unit)? = null
    private val trunkHitRect = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val doorOpen = BooleanArray(4)          // 0 운전석, 1 조수석, 2 뒷좌석 왼쪽, 3 뒷좌석 오른쪽
    private val doorAnim = FloatArray(4)
    private val doorAnimators = arrayOfNulls<ValueAnimator>(4)
    private var trunkOpen = false
    private var kinds: List<Int> = emptyList()
    private var alert = false
    private var trunkAnim = 0f
    private var pulse = 0f
    private var trunkAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inTrunk = trunkHitRect.contains(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return inTrunk
            MotionEvent.ACTION_UP -> if (inTrunk) {
                performClick()
                onTrunkClick?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setState(doors: BooleanArray, trunk: Boolean, itemKinds: List<Int>, isAlert: Boolean) {
        for (i in 0 until 4) {
            if (doors[i] != doorOpen[i]) {
                doorOpen[i] = doors[i]
                doorAnimators[i]?.cancel()
                val idx = i
                doorAnimators[i] = animateTo(doorAnim[i], if (doors[i]) 1f else 0f) { doorAnim[idx] = it }
            }
        }
        if (trunk != trunkOpen) {
            trunkOpen = trunk
            trunkAnimator?.cancel()
            trunkAnimator = animateTo(trunkAnim, if (trunk) 1f else 0f) { trunkAnim = it }
        }
        kinds = itemKinds
        if (isAlert != alert) {
            alert = isAlert
            pulseAnimator?.cancel()
            if (alert) {
                pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                pulse = 0f
            }
        }
        invalidate()
    }

    private fun animateTo(from: Float, to: Float, set: (Float) -> Unit) =
        ValueAnimator.ofFloat(from, to).apply {
            duration = 550
            interpolator = DecelerateInterpolator()
            addUpdateListener { set(it.animatedValue as Float); invalidate() }
            start()
        }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        doorAnimators.forEach { it?.cancel() }
        trunkAnimator?.cancel()
        pulseAnimator?.cancel()
    }

    private fun fill(color: Int) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = color
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        var carH = h * 0.84f
        var carW = carH * 0.47f
        if (carW > w * 0.56f) { carW = w * 0.56f; carH = carW / 0.47f }
        val left = (w - carW) / 2f
        val top = h * 0.03f
        val right = left + carW
        val bottom = top + carH
        val cx = w / 2f
        val cy = top + carH / 2f
        fun x(f: Float) = left + carW * f
        fun y(f: Float) = top + carH * f
        val red = Color.rgb(229, 57, 53)

        // 경고: 주변 붉은 빛
        p.reset(); p.isAntiAlias = true
        if (alert) {
            p.shader = RadialGradient(cx, cy, carH * 0.75f,
                intArrayOf(Color.argb((90 + 100 * pulse).toInt(), 229, 57, 53), Color.argb(0, 229, 57, 53)),
                null, Shader.TileMode.CLAMP)
        } else {
            p.shader = RadialGradient(cx, cy, carH * 0.7f,
                intArrayOf(Color.argb(60, 95, 174, 156), Color.argb(0, 95, 174, 156)), null, Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, carH * 0.75f, p)
        if (alert) {
            p.reset(); p.isAntiAlias = true
            p.style = Paint.Style.STROKE
            p.strokeWidth = carW * 0.035f
            p.color = Color.argb((110 + 110 * pulse).toInt(), 229, 57, 53)
            c.drawRoundRect(RectF(left - carW * 0.10f, top - carW * 0.08f, right + carW * 0.10f, bottom + carW * 0.08f),
                carW * 0.40f, carW * 0.40f, p)
        }

        // 그림자
        fill(Color.argb(45, 0, 0, 0))
        val r = carW * 0.34f
        c.drawRoundRect(RectF(left + carW * 0.03f, top + carW * 0.05f, right + carW * 0.03f, bottom + carW * 0.05f), r, r, p)

        // 바퀴
        fill(Color.parseColor("#2E3A38"))
        for (y0 in listOf(0.15f, 0.70f)) {
            c.drawRoundRect(RectF(left - carW * 0.05f, y(y0), left + carW * 0.10f, y(y0 + 0.13f)), carW * 0.05f, carW * 0.05f, p)
            c.drawRoundRect(RectF(right - carW * 0.10f, y(y0), right + carW * 0.05f, y(y0 + 0.13f)), carW * 0.05f, carW * 0.05f, p)
        }

        // 사이드미러
        fill(Color.parseColor("#C9D9D3"))
        c.drawOval(RectF(left - carW * 0.12f, y(0.25f), left + carW * 0.02f, y(0.29f)), p)
        c.drawOval(RectF(right - carW * 0.02f, y(0.25f), right + carW * 0.12f, y(0.29f)), p)

        // 차체
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(left, 0f, right, 0f,
            intArrayOf(Color.parseColor("#D5E4DF"), Color.WHITE, Color.parseColor("#D5E4DF")), null, Shader.TileMode.CLAMP)
        val bodyRect = RectF(left, top, right, bottom)
        c.drawRoundRect(bodyRect, r, r, p)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = carW * 0.012f; p.color = Color.parseColor("#B5C9C2")
        c.drawRoundRect(bodyRect, r, r, p)

        // 전조등·후미등
        fill(Color.parseColor("#FFF3CF"))
        c.drawRoundRect(RectF(x(0.12f), y(0.025f), x(0.34f), y(0.05f)), carW * 0.03f, carW * 0.03f, p)
        c.drawRoundRect(RectF(x(0.66f), y(0.025f), x(0.88f), y(0.05f)), carW * 0.03f, carW * 0.03f, p)
        fill(Color.parseColor("#D93B3B"))
        c.drawRoundRect(RectF(x(0.08f), y(0.955f), x(0.32f), y(0.98f)), carW * 0.03f, carW * 0.03f, p)
        c.drawRoundRect(RectF(x(0.68f), y(0.955f), x(0.92f), y(0.98f)), carW * 0.03f, carW * 0.03f, p)

        // 보닛 라인
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = carW * 0.008f; p.color = Color.argb(70, 100, 130, 120)
        c.drawLine(x(0.28f), y(0.07f), x(0.32f), y(0.21f), p)
        c.drawLine(x(0.72f), y(0.07f), x(0.68f), y(0.21f), p)

        // 유리 (앞유리·지붕·뒷유리)
        val corner = CornerPathEffect(carW * 0.05f)
        p.reset(); p.isAntiAlias = true; p.pathEffect = corner
        p.shader = LinearGradient(0f, y(0.23f), 0f, y(0.33f), Color.parseColor("#50707C"), Color.parseColor("#2B3D44"), Shader.TileMode.CLAMP)
        c.drawPath(Path().apply {
            moveTo(x(0.22f), y(0.23f)); lineTo(x(0.78f), y(0.23f)); lineTo(x(0.86f), y(0.33f)); lineTo(x(0.14f), y(0.33f)); close()
        }, p)
        p.shader = LinearGradient(0f, y(0.35f), 0f, y(0.62f), Color.parseColor("#3F5862"), Color.parseColor("#2C3F46"), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(x(0.17f), y(0.35f), x(0.83f), y(0.62f)), carW * 0.08f, carW * 0.08f, p)
        p.shader = LinearGradient(0f, y(0.64f), 0f, y(0.71f), Color.parseColor("#3F5862"), Color.parseColor("#2C3F46"), Shader.TileMode.CLAMP)
        c.drawPath(Path().apply {
            moveTo(x(0.16f), y(0.64f)); lineTo(x(0.84f), y(0.64f)); lineTo(x(0.78f), y(0.71f)); lineTo(x(0.22f), y(0.71f)); close()
        }, p)
        fill(Color.argb(40, 255, 255, 255))
        c.drawRoundRect(RectF(x(0.22f), y(0.37f), x(0.38f), y(0.60f)), carW * 0.05f, carW * 0.05f, p)

        // 트렁크 안 (열렸을 때)
        val tl = x(0.12f); val tr = x(0.88f); val tt = y(0.74f); val tb = y(0.95f)
        if (trunkAnim > 0.01f) {
            p.reset(); p.isAntiAlias = true
            p.shader = LinearGradient(0f, tt, 0f, tb, Color.parseColor("#3A4A46"), Color.parseColor("#1F2A27"), Shader.TileMode.CLAMP)
            p.alpha = (255 * trunkAnim).toInt()
            c.drawRoundRect(RectF(tl, tt, tr, tb), carW * 0.06f, carW * 0.06f, p)
            if (trunkAnim > 0.4f && kinds.isNotEmpty()) {
                val shown = kinds.take(8)
                val perRow = 4
                val rows = (shown.size + perRow - 1) / perRow
                val size = min((tr - tl) / 4.4f, (tb - tt) / (rows + 0.3f))
                shown.forEachIndexed { i, kind ->
                    val row = i / perRow
                    val inRow = min(perRow, shown.size - row * perRow)
                    val col = i % perRow
                    val rowWidth = inRow * size
                    val ix = (tl + tr) / 2f - rowWidth / 2f + col * size
                    val iy = tt + (tb - tt) / 2f - rows * size / 2f + row * size
                    c.save()
                    c.translate(ix, iy)
                    c.scale(size / 100f, size / 100f)
                    ItemArt.draw(c, kind, withShadow = false)
                    c.restore()
                }
            }
        }

        // 트렁크 뚜껑 (열리면 뒤로 젖혀짐)
        val lidShift = trunkAnim * carH * 0.20f
        val lidH = (tb - tt) * (1f - 0.55f * trunkAnim)
        val lidRect = RectF(tl, tt + lidShift, tr, tt + lidShift + lidH)
        trunkHitRect.set(tl - carW * 0.06f, tt - carW * 0.06f, tr + carW * 0.06f, max(tb, lidRect.bottom) + carW * 0.06f)
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(tl, 0f, tr, 0f,
            intArrayOf(Color.parseColor("#D5E4DF"), Color.WHITE, Color.parseColor("#D5E4DF")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(lidRect, carW * 0.06f, carW * 0.06f, p)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = carW * (if (alert && trunkOpen) 0.02f else 0.01f)
        p.color = if (alert && trunkOpen) red else Color.parseColor("#9FB5AE")
        c.drawRoundRect(lidRect, carW * 0.06f, carW * 0.06f, p)
        if (trunkAnim < 0.05f && kinds.isNotEmpty()) {
            fill(Color.parseColor("#5FAE9C"))
            val bw = carW * 0.30f
            val bh = carW * 0.11f
            val bcy = (tt + tb) / 2f
            c.drawRoundRect(RectF(cx - bw / 2f, bcy - bh / 2f, cx + bw / 2f, bcy + bh / 2f), bh / 2f, bh / 2f, p)
            textPaint.textSize = bh * 0.62f
            c.drawText("짐 ${kinds.size}", cx, bcy + bh * 0.22f, textPaint)
        }

        // 문 4개 (앞쪽 경첩 기준으로 바깥쪽으로 열림)
        val doorTh = carW * 0.09f
        val spans = listOf(0.34f to 0.52f, 0.535f to 0.69f)   // 앞문, 뒷문 (차 길이 비율)
        for (i in 0 until 4) {
            val isLeft = i % 2 == 0
            val (s, e) = if (i < 2) spans[0] else spans[1]
            val dTop = y(s)
            val dLen = y(e) - dTop
            val anim = doorAnim[i]
            val open = doorOpen[i]
            if (anim > 0.01f) {
                fill(Color.parseColor("#2C3835"))
                if (isLeft) c.drawRect(left, dTop, left + doorTh, dTop + dLen, p)
                else c.drawRect(right - doorTh, dTop, right, dTop + dLen, p)
            }
            c.save()
            c.translate(if (isLeft) left else right, dTop)
            c.rotate(if (isLeft) anim * 62f else -anim * 62f)
            val doorRect = if (isLeft) RectF(0f, 0f, doorTh, dLen) else RectF(-doorTh, 0f, 0f, dLen)
            p.reset(); p.isAntiAlias = true
            p.shader = LinearGradient(doorRect.left, 0f, doorRect.right, 0f,
                if (isLeft) Color.parseColor("#D5E4DF") else Color.WHITE,
                if (isLeft) Color.WHITE else Color.parseColor("#D5E4DF"), Shader.TileMode.CLAMP)
            c.drawRoundRect(doorRect, doorTh * 0.4f, doorTh * 0.4f, p)
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
            p.strokeWidth = carW * (if (alert && open) 0.02f else 0.008f)
            p.color = if (alert && open) red else Color.parseColor("#9FB5AE")
            c.drawRoundRect(doorRect, doorTh * 0.4f, doorTh * 0.4f, p)
            c.restore()
        }
    }
}

/** 안전벨트: 착용(민트 대각선 벨트 + 체크) / 미착용(빨간 벨트가 늘어짐 + X) / 신호 없음(회색) */
class SeatbeltView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var buckled: Boolean? = null

    fun setBuckled(value: Boolean?) {
        buckled = value
        invalidate()
    }

    private fun stroke(color: Int, width: Float) {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND; p.color = color
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s / 100f, s / 100f)

        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, 10f, 0f, 96f, Color.parseColor("#E3ECE9"), Color.parseColor("#C3D3CE"), Shader.TileMode.CLAMP)
        canvas.drawCircle(50f, 22f, 12f, p)
        canvas.drawRoundRect(RectF(22f, 38f, 78f, 98f), 22f, 22f, p)

        when (buckled) {
            true -> {
                stroke(Color.parseColor("#5FAE9C"), 8f)
                canvas.drawLine(70f, 40f, 34f, 88f, p)
                stroke(Color.parseColor("#5FAE9C"), 6f)
                canvas.drawLine(28f, 86f, 74f, 86f, p)
                p.reset(); p.isAntiAlias = true; p.color = Color.parseColor("#2F6B5F")
                canvas.drawRoundRect(RectF(28f, 80f, 40f, 92f), 3f, 3f, p)
                badge(canvas, Color.parseColor("#5FAE9C"), true)
            }
            false -> {
                stroke(Color.parseColor("#E57373"), 8f)
                canvas.drawLine(70f, 40f, 76f, 80f, p)
                p.reset(); p.isAntiAlias = true; p.color = Color.parseColor("#C62828")
                canvas.drawRoundRect(RectF(70f, 80f, 82f, 92f), 3f, 3f, p)
                badge(canvas, Color.parseColor("#C62828"), false)
            }
            null -> {
                stroke(Color.parseColor("#B0BEC5"), 7f)
                canvas.drawLine(70f, 40f, 34f, 88f, p)
            }
        }
        canvas.restore()
    }

    private fun badge(c: Canvas, color: Int, ok: Boolean) {
        p.reset(); p.isAntiAlias = true; p.color = color
        c.drawCircle(84f, 18f, 13f, p)
        stroke(Color.WHITE, 3.2f)
        if (ok) {
            c.drawPath(Path().apply { moveTo(78f, 18f); lineTo(82.5f, 22.5f); lineTo(90f, 13.5f) }, p)
        } else {
            c.drawLine(79f, 13f, 89f, 23f, p)
            c.drawLine(89f, 13f, 79f, 23f, p)
        }
    }
}

/** 페달: 급감속이면 브레이크, 급가속이면 액셀이 눌림. 평소에도 색 테두리로 구분 */
class PedalView(context: Context, private val isBrake: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressAnim = 0f
    private var animator: ValueAnimator? = null

    fun press() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f, 1f, 0f).apply {
            duration = 1100
            addUpdateListener { pressAnim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        canvas.scale(s / 100f, s / 100f)

        if (pressAnim > 0f) {
            p.reset(); p.isAntiAlias = true
            val glow = if (isBrake) Color.argb((150 * pressAnim).toInt(), 229, 57, 53) else Color.argb((150 * pressAnim).toInt(), 95, 174, 156)
            p.shader = RadialGradient(50f, 55f, 48f, intArrayOf(glow, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(50f, 55f, 48f, p)
        }

        canvas.save()
        canvas.translate(0f, 7f * pressAnim)
        canvas.scale(1f - 0.06f * pressAnim, 1f - 0.06f * pressAnim, 50f, 55f)

        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND; p.color = Color.parseColor("#6B7A78")
        val pad: RectF
        if (isBrake) {
            canvas.drawLine(50f, 4f, 50f, 30f, p)
            pad = RectF(16f, 28f, 84f, 74f)
            p.reset(); p.isAntiAlias = true; p.color = Color.parseColor("#1E2426")
            canvas.drawRoundRect(RectF(16f, 34f, 84f, 82f), 12f, 12f, p)
        } else {
            p.reset(); p.isAntiAlias = true; p.color = Color.parseColor("#2E3537")
            canvas.drawRoundRect(RectF(36f, 86f, 64f, 96f), 4f, 4f, p)
            pad = RectF(32f, 8f, 68f, 84f)
            p.color = Color.parseColor("#1E2426")
            canvas.drawRoundRect(RectF(32f, 14f, 68f, 90f), 10f, 10f, p)
        }
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(0f, pad.top, 0f, pad.bottom, Color.parseColor("#6A7678"), Color.parseColor("#2E3537"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(pad, 11f, 11f, p)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f; p.color = Color.argb(70, 255, 255, 255)
        var yy = pad.top + 8f
        while (yy < pad.bottom - 4f) {
            canvas.drawLine(pad.left + 7f, yy, pad.right - 7f, yy, p)
            yy += 7f
        }
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = if (isBrake) Color.argb(200, 229, 57, 53) else Color.argb(200, 95, 174, 156)
        canvas.drawRoundRect(pad, 11f, 11f, p)
        if (pressAnim > 0f) {
            p.reset(); p.isAntiAlias = true
            p.color = if (isBrake) Color.argb((110 * pressAnim).toInt(), 229, 57, 53) else Color.argb((110 * pressAnim).toInt(), 95, 174, 156)
            canvas.drawRoundRect(pad, 11f, 11f, p)
        }
        canvas.restore()
        canvas.restore()
    }
}