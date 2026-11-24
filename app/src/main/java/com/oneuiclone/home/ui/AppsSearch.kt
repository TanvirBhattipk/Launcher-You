package com.oneuiclone.home.ui
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.motion.Easing
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class AppsSearch(
    context: Context,
    private val dp: DeviceProfile,
    private val apps: () -> List<AppInfo>,
    private val onLaunch: (IconView, AppInfo) -> Unit,
    private val configureIcon: (IconView, AppInfo) -> Unit,
    private val sources: SearchSources,
    private val firstPage: () -> Set<AppKey>,
    private val onOpenIntent: (View, Intent) -> Unit,
    private val onRequestContacts: () -> Unit,
    private val canSwipeHome: () -> Boolean,
    private val onSwipeHome: () -> Unit,
    private val onSwipeUp: () -> Unit,
    private val onDismiss: () -> Unit,
    private val barGlass: WidgetGlass? = null,
    private val cardGlass: WidgetGlass? = null,
    private val backdrop: LiveBackdrop? = null,
    private val barOpacity: Float = 1f,
    private val recents: () -> List<AppInfo> = { emptyList() },
    private val contactsEnabled: Boolean = true,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    class Origin(
        val rect: RectF,
        val fill: Int,
        val stroke: Int,
        val elevation: Float,
        val glyphLeft: Float,
        val glyphBox: Float,
        val textStart: Float,
        val textSizePx: Float,
        val glass: WidgetGlass? = null,
        val ink: Int? = null,
    )

    private val prefs = com.oneuiclone.home.settings.Prefs(context)

    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isNight(): Boolean = isNightMode

    private fun alphaFromPercent(percent: Int, fallbackPercent: Int): Int {
        val p = if (percent in 1..100) percent else fallbackPercent
        return ((p.coerceIn(0, 100) / 100f) * 255f).roundToInt().coerceIn(0, 255)
    }

    // Always use dark mode settings for surface (0x00)
    private val surfaceRgb: Int
        get() = 0x00

    // Always use white borders (0xFF)
    private val borderRgb: Int
        get() = 0xFF 

    // Always use white text (0xFFFFFFFF)
    private val primaryInk: Int
        get() = 0xFFFFFFFF.toInt()

    private val secondaryInk: Int
        get() = 0xCCFFFFFF.toInt()

    private val hintInk: Int
        get() = 0x99FFFFFF.toInt()

    private val dividerLineColor: Int
        get() = 0x33FFFFFF.toInt()

    private val searchBarFillColor: Int
        get() = YunoGlass.fill(false, prefs.appSearchBarOpacity)
        
    private val searchBarBorderColor: Int
        get() = YunoGlass.border(prefs.appSearchBorderOpacity)
    

    private val searchBarBorderWidth: Float
        get() = maxOf(1f, dp.dp(prefs.appSearchBorderSize / 10f))
        
    private val cardFillColor: Int
        get() = YunoGlass.fill(false, prefs.appSearchCardsBgOpacity)

    private val cardBorderColor: Int
     get() = YunoGlass.border(prefs.appSearchCardsBorderOpacity)
    
    private val cardBorderWidth: Float
        get() = maxOf(1f, dp.dp(prefs.appSearchCardsBorderSize / 10f))
        
    private val backdropDimColor: Int
        get() = surfaceRgb

    private val backdropDimMaxAlpha: Int
        get() = 0   // the App Search background tint now comes from BackgroundController (searchDim)

    private val field = EditText(context)
    private val pill = FrameLayout(context)
    private val pillBg = GradientDrawable()
    private val clearBtn = ClearButton(context)
    private val glyph = MagnifierView(context, secondaryInk)

    // Acrylic frost texture (reused from Acrylic singleton if available, otherwise built here)
    private val frostedNoisePaint by lazy {
        val size = 256
        val pixels = IntArray(size * size)
        val rnd = kotlin.random.Random(12345)
        for (i in pixels.indices) {
            val gray = rnd.nextInt(256)
            pixels[i] = android.graphics.Color.argb(10, gray, gray, gray)
        }
        val bmp = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        android.graphics.Paint().apply { this.shader = shader }
    }

    // Cards
    private val appsCard = GlassCard(context, flatBottom = false)
    private val appsRow = LinearLayout(context)
    private val contactsCard = GlassCard(context, flatBottom = false, chrome = false)
    private val contactsListLayout = LinearLayout(context)
    private val suggestionsCard = GlassCard(context, flatBottom = false, chrome = false)
    private val suggestionsListLayout = LinearLayout(context)
    private val bottomCards = LinearLayout(context)        // Contacts + Suggestions, stacked up from the bar
    private val bottomRegion = FrameLayout(context)        // the room between the Apps card and the bar
    private val calcBox = TextView(context)
    private var calcResult: String? = null

    private var favorites: List<SearchSources.Contact> = emptyList()
    private val content = LinearLayout(context)
    private var scores: Map<AppKey, Double> = emptyMap()
    private var suggested: List<SearchSources.Contact> = emptyList()
    private var appsList: List<AppInfo> = emptyList()
    private var contactsList: List<SearchSources.Contact> = emptyList()
    private var webList: List<String> = emptyList()

    private var shownApps: List<AppKey> = emptyList()
    private var shownContacts: List<Long> = emptyList()
    private var shownSuggestions: List<String> = emptyList()
    private var opening = false
    private var pendingSources: Runnable? = null
    private val searchPrefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
    private var enterTarget: View? = null
    private lateinit var origin: Origin
    private var pillGlass: WidgetGlass? = null

       private inner class GlassCard(ctx: Context, private val flatBottom: Boolean, private val chrome: Boolean = true) : LinearLayout(ctx) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glassPaint = GlassPaint()
        private val toWindow = android.graphics.Matrix()
        private val rim = YunoRim()
        private val rect = RectF()
        private val cardPath = android.graphics.Path()
        private val strokePath = android.graphics.Path()

        init {
            orientation = VERTICAL
            setWillNotDraw(false)
            syncWithBar()
        }

        fun syncWithBar() {
            fillPaint.color = cardFillColor
            strokePaint.color = cardBorderColor
            strokePaint.strokeWidth = cardBorderWidth
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!chrome) { super.onDraw(canvas); return }          // no glass, fill or border
            val radius = dp.dp(24f)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())

            val radii = if (flatBottom) {
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            } else {
                floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            }

            cardPath.reset()
            cardPath.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)

            // Cards' own blur: live App Search background, blurred and bent at the edges. 0 = no extra layer.
            if (prefs.appSearchCardsBgBlur > 0) {
                val amount = dp.dp(YunoGlass.LENS_AMOUNT_DP)
                val depth = dp.dp(YunoGlass.LENS_DEPTH_DP)
                val live = backdrop?.draw(canvas, this, rect, radius, dp.dp(prefs.appSearchCardsBgBlur.toFloat()), amount, depth, cardPath) == true
                val g = cardGlass
                if (!live && g != null &&
                    glassPaint.aimLens(g, toWindow.apply { reset(); transformMatrixToGlobal(this) }, rect, radius, amount, depth)) {
                    glassPaint.paint.colorFilter = YunoGlass.saturation
                    glassPaint.paint.alpha = 255
                    canvas.drawPath(cardPath, glassPaint.paint)
                }
            }
            canvas.drawPath(cardPath, fillPaint)

            if (strokePaint.strokeWidth > 0f && android.graphics.Color.alpha(strokePaint.color) > 0) {
                val inset = strokePaint.strokeWidth / 2f
                val strokeRect = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                val cr = maxOf(0f, radius - inset)
                val strokeRadii = if (flatBottom) {
                    floatArrayOf(cr, cr, cr, cr, 0f, 0f, 0f, 0f)
                } else {
                    floatArrayOf(cr, cr, cr, cr, cr, cr, cr, cr)
                }
                strokePath.reset()
                strokePath.addRoundRect(strokeRect, strokeRadii, android.graphics.Path.Direction.CW)
                canvas.drawPath(strokePath, strokePaint)
                rim.drawPath(canvas, strokePath, strokeRect, cr, strokePaint.strokeWidth, YunoGlass.rimPeak(prefs.appSearchCardsBorderOpacity))
            }
            super.onDraw(canvas)
        }
    }
    private inner class GlassPill : android.graphics.drawable.DrawableWrapper(pillBg) {
        private val glassPaint = GlassPaint()
        private val toWindow = android.graphics.Matrix()
        private val rect = RectF()
        private val rim = YunoRim()

        override fun draw(canvas: Canvas) {
            if (prefs.appSearchBarBlur > 0) {
                rect.set(bounds)
                val amount = dp.dp(YunoGlass.LENS_AMOUNT_DP)
                val depth = dp.dp(YunoGlass.LENS_DEPTH_DP)
                val live = backdrop?.draw(canvas, pill, rect, pillBg.cornerRadius, dp.dp(prefs.appSearchBarBlur.toFloat()), amount, depth) == true
                val g = barGlass
                if (!live && g != null) {
                    toWindow.reset(); pill.transformMatrixToGlobal(toWindow)
                    if (glassPaint.aimLens(g, toWindow, rect, pillBg.cornerRadius, amount, depth)) {
                        glassPaint.paint.colorFilter = YunoGlass.saturation
                        glassPaint.paint.alpha = 255
                        canvas.drawRoundRect(rect, pillBg.cornerRadius, pillBg.cornerRadius, glassPaint.paint)
                    }
                }
            }
            super.draw(canvas)
            val w = searchBarBorderWidth
            rect.set(bounds); rect.inset(w / 2f, w / 2f)
            rim.draw(canvas, rect, pillBg.cornerRadius, w, YunoGlass.rimPeak(prefs.appSearchBorderOpacity))
        }
    }
    fun refreshBackdrop() {
        pill.invalidate()
        appsCard.invalidate(); contactsCard.invalidate(); suggestionsCard.invalidate()
    }
    var isOpen = false; private set
    private var f = 0f
    private var anim: ValueAnimator? = null
    private var kb = 0f
    private var kbTarget = 0f
    private var kbAnim: ValueAnimator? = null
    private var ime = 0
    private var imeAnimating = false

    private val openTextStart = dp.dp(16f + 20f + 10f)
    private val openTextSize = 16f * resources.displayMetrics.scaledDensity
    private val keyboardPrefs = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    private val keyboardKey = "ime_height_${dp.widthPx}x${dp.heightPx}"

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "web-suggest") }
    private var pendingFetch: Runnable? = null

    private val imeCallback = object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        private fun WindowInsetsAnimation.isIme() = (typeMask and WindowInsets.Type.ime()) != 0

        override fun onPrepare(animation: WindowInsetsAnimation) {
            if (animation.isIme()) imeAnimating = true
        }

        override fun onStart(animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
            if (animation.isIme() && isOpen) {
                val end = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: bounds.upperBound.bottom
                if (end > 0) {
                    keyboardComing = true
                    retargetKeyboard(end.toFloat(), animation.durationMillis.takeIf { it > 0 } ?: Easing.IME_DURATION)
                } else {
                    retargetKeyboard(0f, 0L)
                }
            }
            return bounds
        }

        override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
            if (running.any { it.isIme() }) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                layoutPill()
            }
            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimation) {
            if (!animation.isIme()) return
            imeAnimating = false
            ime = rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            layoutPill()
        }
    }

    init {
        isClickable = true
        pill.background = GlassPill()
        field.apply {
            background = null
            hint = "Search anything..."
            setHintTextColor(hintInk)
            setTextColor(primaryInk)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, openTextSize)
            typeface = Fonts.ui(context, 400)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(openTextStart.roundToInt(), 0, dp.dpi(44f), 0)
            gravity = Gravity.CENTER_VERTICAL
            pivotX = 0f
        }
        pill.addView(field, LayoutParams(dp.searchBarWidth, LayoutParams.MATCH_PARENT))
        pill.addView(glyph, LayoutParams(dp.dpi(20f), dp.dpi(20f), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp.dpi(16f)
        })
        clearBtn.apply {
            contentDescription = "Clear"
            visibility = VISIBLE
            setOnClickListener {
                if (field.text.isNotEmpty()) field.text = null else onDismiss()
            }
        }
        pill.addView(clearBtn, LayoutParams(dp.dpi(22f), dp.dpi(22f), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp.dpi(14f)
        })

        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.TOP
        content.clipChildren = false

        calcBox.apply {
            visibility = GONE
            setPadding(dp.dpi(18f), dp.dpi(14f), dp.dpi(18f), dp.dpi(14f))
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp.dp(22f)
                setColor(cardFillColor)
                setStroke(cardBorderWidth.toInt(), cardBorderColor)
            }
            isClickable = true
            setOnClickListener {
                val r = calcResult ?: return@setOnClickListener
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("result", r))
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(calcBox, cardParams(top = 0))

        appsCard.setPadding(dp.dpi(12f), dp.dpi(14f), dp.dpi(12f), dp.dpi(14f))
        appsCard.addView(sectionHeader("Apps", showAll = true, horizontalPadDp = 4f) { onSwipeUp() })
        appsRow.orientation = LinearLayout.HORIZONTAL
        appsRow.gravity = Gravity.TOP
        appsCard.addView(appsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp.dpi(12f)
        })
        content.addView(appsCard, cardParams(top = 0))

        contactsCard.setPadding(0, dp.dpi(14f), 0, dp.dpi(4f))
        contactsCard.addView(sectionHeader("Contacts", showAll = true, horizontalPadDp = 0f) {
            onOpenIntent(it, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        contactsListLayout.orientation = LinearLayout.VERTICAL
        contactsListLayout.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        contactsListLayout.dividerDrawable = insetDivider(0, 0)
        contactsCard.addView(contactsListLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp.dpi(6f)
        })
        bottomCards.addView(contactsCard, cardParams(top = dp.dpi(14f)))

        suggestionsCard.setPadding(0, dp.dpi(14f), 0, dp.dpi(4f))
        suggestionsCard.addView(sectionHeader("Suggestions", showAll = false, horizontalPadDp = 0f, onShowAll = null))
        suggestionsListLayout.orientation = LinearLayout.VERTICAL
        suggestionsListLayout.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        suggestionsListLayout.dividerDrawable = insetDivider(0, 0)
        suggestionsCard.addView(suggestionsListLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp.dpi(6f)
        })
        bottomCards.addView(suggestionsCard, cardParams(top = dp.dpi(14f)))

        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = contentTop(); bottomMargin = contentBottom()
        })
        // Apps stays fixed at the top. Contacts + Suggestions stack up from the bar; if they're taller than the room
        // between, it is their top that gets cut (the part next to the bar stays).
        bottomCards.orientation = LinearLayout.VERTICAL
        bottomCards.gravity = Gravity.Top
        bottomCards.clipChildren = false
        bottomRegion.addView(bottomCards, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.Top))
        bottomRegion.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = android.graphics.Rect(0, 0, v.width, v.height) }
        content.addView(bottomRegion, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(pill, LayoutParams(dp.searchBarWidth, dp.searchBarHeight))

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        field.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                enterTarget?.performClick()
                true
            } else false
        }
        setOnApplyWindowInsetsListener { _, insets ->
            if (!imeAnimating) {
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (isOpen && ime > 0) retargetKeyboard(ime.toFloat())
                layoutPill()
            }
            insets
        }
        setWindowInsetsAnimationCallback(imeCallback)
        setOnClickListener { if (isOpen) onDismiss() }
    }

    private fun cardParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = dp.searchBarSide
        rightMargin = dp.searchBarSide
        topMargin = top
    }

    private fun insetDivider(startInsetPx: Int, endInsetPx: Int): android.graphics.drawable.Drawable {
        val line = GradientDrawable().apply {
            setColor(dividerLineColor)
            setSize(0, 1)
        }
        return android.graphics.drawable.InsetDrawable(line, startInsetPx, 0, endInsetPx, 0)
    }

    private fun sectionHeader(title: String, showAll: Boolean, horizontalPadDp: Float = 2f, onShowAll: ((View) -> Unit)?): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp.dpi(horizontalPadDp), 0, dp.dpi(horizontalPadDp), 0)
            addView(TextView(context).apply {
                text = title
                setTextColor(primaryInk)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Fonts.ui(context, 600)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (showAll && onShowAll != null) {
                val action = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setOnClickListener { onShowAll(this) }
                    addView(TextView(context).apply {
                        text = "Show All"
                        setTextColor(secondaryInk)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = Fonts.ui(context, 400)
                    })
                    addView(ChevronGlyph(context, secondaryInk), LinearLayout.LayoutParams(dp.dpi(11f), dp.dpi(11f)).apply {
                        leftMargin = dp.dpi(4f)
                    })
                }
                addView(action)
            }
        }
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    private val index: List<Triple<AppInfo, String, String>> by lazy {
        apps().map { a -> val l = normalize(a.label); Triple(a, l, initials(l)) }
    }

    private fun filter(q: String) {
        if (!isOpen) return
        calcResult = Calculator.evaluate(q)
        val raw = q.trim()
        val query = normalize(raw)
        if (query.isEmpty()) {
            pendingFetch?.let { main.removeCallbacks(it) }
            pendingSources?.let { main.removeCallbacks(it) }
            val rList = recents().take(MAX_APPS)
            appsList = if (rList.isNotEmpty()) rList else mostUsed()
            contactsList = favorites
            webList = emptyList()
            render()
            return
        }
        appsList = matches(query)
        querySources(raw)
        fetchSuggestions(raw)
        render()
    }

    private fun mostUsed(): List<AppInfo> {
        val all = index.map { it.first }
        val ranked = all.sortedByDescending { scores[it.key] ?: 0.0 }
        return ranked.take(MAX_APPS)
    }

    private fun matches(query: String): List<AppInfo> = index.mapNotNull { (a, l, ini) ->
        val tier = when {
            l.startsWith(query) -> 0
            l.split(' ', '-', '.').any { it.startsWith(query) } || ini.startsWith(query) -> 1
            l.contains(query) -> 2
            else -> return@mapNotNull null
        }
        Triple(a, tier, scores[a.key] ?: 0.0)
    }.sortedWith(compareBy<Triple<AppInfo, Int, Double>> { it.second }.thenByDescending { it.third }).take(MAX_APPS).map { it.first }

    private fun render() {
        showCalc()
        showApps(appsList)
        val activeContacts = if (!contactsEnabled) emptyList()
        else if (field.text.isNullOrBlank()) favorites else contactsList
        showContacts(activeContacts, contactsEnabled && !sources.contactsAllowed)
        showSuggestions()
        updateEnterTarget()
    }

    private fun showCalc() {
        val r = calcResult
        if (r == null) {
            calcBox.visibility = GONE
            (appsCard.layoutParams as LinearLayout.LayoutParams).topMargin = 0
            return
        }
        (appsCard.layoutParams as LinearLayout.LayoutParams).topMargin = dp.dpi(10f)
        (calcBox.background as? GradientDrawable)?.apply {
            setColor(cardFillColor)
            setStroke(cardBorderWidth.toInt(), cardBorderColor)
        }
        val typed = field.text.toString().trim()
        val b = android.text.SpannableStringBuilder()
        b.append("$typed = ")
        b.setSpan(android.text.style.ForegroundColorSpan(secondaryInk), 0, b.length, 0)
        b.setSpan(android.text.style.AbsoluteSizeSpan(15, true), 0, b.length, 0)
        val start = b.length
        b.append(r)
        b.setSpan(android.text.style.ForegroundColorSpan(primaryInk), start, b.length, 0)
        b.setSpan(android.text.style.AbsoluteSizeSpan(22, true), start, b.length, 0)
        b.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, b.length, 0)
        calcBox.text = b
        calcBox.visibility = VISIBLE
    }

    private fun loadFavorites() {
        if (!contactsEnabled) return
        if (!sources.contactsAllowed) { favorites = emptyList(); render(); return }
        Thread {
            val list = sources.favoriteContacts(4)
            post {
                if (isOpen) {
                    favorites = list
                    if (field.text.isNullOrBlank()) { contactsList = list; render() }
                }
            }
        }.start()
    }

    private fun reveal(v: View) {
        if (opening) return
        v.animate().cancel()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(Easing.BASIC).start()
    }

    private fun showApps(list: List<AppInfo>) {
        val keys = list.take(MAX_APPS).map { it.key }
        if (keys == shownApps && appsRow.childCount == keys.size) return
        val old = shownApps
        shownApps = keys
        appsRow.removeAllViews()
        enterTarget = null
        val iconPx = dp.searchIconSizePx                       // Home's icon size, fixed
        list.take(MAX_APPS).forEachIndexed { i, a ->
            val holder = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setTag(R_ENTERABLE, true)
                val hiddenIcon = IconView(context, dp).apply { configureIcon(this, a) }
                setOnClickListener { onLaunch(hiddenIcon, a) }
                addView(BitmapIconView(context, hiddenIcon.icon ?: a.drawerIcon, iconPx * 0.24f), LinearLayout.LayoutParams(iconPx, iconPx))
                addView(TextView(context).apply {
                    text = a.label
                    setTextColor(primaryInk)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Fonts.ui(context, 400)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp.dpi(5f)
                })
            }
            appsRow.addView(holder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (old.getOrNull(i) != a.key) reveal(holder)
        }
        appsCard.visibility = if (keys.isEmpty()) GONE else VISIBLE
    }

    private fun initials(s: String) = s.split(' ', '-', '.').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

    private fun fetchSuggestions(q: String) {
        pendingFetch?.let { main.removeCallbacks(it) }
        if (q.isEmpty() || !com.oneuiclone.home.settings.Prefs(context).webSuggestions) return
        val r = Runnable {
            net.execute {
                val list = try { suggest(q) } catch (_: Exception) { emptyList() }
                main.post { if (isOpen && field.text.toString().trim() == q && list.isNotEmpty()) setWeb(q, list) }
            }
        }
        pendingFetch = r
        main.postDelayed(r, 150)
    }

    private fun suggest(q: String): List<String> {
        val lang = Locale.getDefault().toLanguageTag()
        val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&ie=UTF-8&oe=UTF-8&hl=$lang&q=" +
            URLEncoder.encode(q, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 2500
        return try {
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            c.disconnect()
        }
    }

    private fun setWeb(q: String, suggestions: List<String>) {
        webList = listOf(q) + suggestions.filter { !it.equals(q, ignoreCase = true) }.take(MAX_WEB - 1)
        render()
    }

    private data class SuggestionItem(val id: String, val title: String, val app: AppInfo?, val query: String?)

    private fun buildDefaultSuggestions(): List<SuggestionItem> {
        val all = apps()
        fun findApp(vararg pkgs: String, keyword: String) =
            pkgs.firstNotNullOfOrNull { p -> all.firstOrNull { it.key.component.packageName == p } }
                ?: all.firstOrNull { it.label.contains(keyword, ignoreCase = true) }

        val out = ArrayList<SuggestionItem>()
        findApp("com.google.android.youtube", "com.android.chrome", keyword = "YouTube")?.let {
            out += SuggestionItem("yt", "Open YouTube", it, null)
        }
        findApp("com.google.android.apps.maps", keyword = "Maps")?.let {
            out += SuggestionItem("maps", "Directions to Office", it, "Directions to Office")
        }
        findApp("com.google.android.apps.photos", "com.sec.android.gallery3d", keyword = "Photos")?.let {
            out += SuggestionItem("photos", "View Recent Photos", it, null)
        }
        findApp("com.google.android.keep", "com.samsung.android.app.notes", keyword = "Notes")?.let {
            out += SuggestionItem("notes", "New Note", it, null)
        }
        if (out.size < 4) {
            for (a in mostUsed()) {
                if (out.none { it.app?.key == a.key }) {
                    out += SuggestionItem("app_${a.key}", "Open ${a.label}", a, null)
                    if (out.size >= 4) break
                }
            }
        }
        return out.take(4)
    }

    private fun showSuggestions() {
        val items = if (field.text.isNullOrBlank()) {
            buildDefaultSuggestions()
        } else {
            webList.take(4).map { q -> SuggestionItem("web_$q", q, null, q) }
        }
        val keys = items.map { it.id }
        if (keys == shownSuggestions && suggestionsListLayout.childCount == items.size) return
        val old = shownSuggestions
        shownSuggestions = keys
        suggestionsListLayout.removeAllViews()
        suggestionsCard.visibility = if (items.isEmpty()) GONE else VISIBLE
        items.forEachIndexed { i, item ->
            val iconView = if (item.app != null) {
                BitmapIconView(context, item.app.drawerIcon, dp.dp(7f))
            } else {
                MagnifierView(context, secondaryInk)
            }
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                isClickable = true
                setTag(R_ENTERABLE, true)
                background = rowRipple()
                setOnClickListener {
                    if (item.app != null && item.query == null) {
                        val dummy = IconView(context, dp).apply { configureIcon(this, item.app) }
                        onLaunch(dummy, item.app)
                    } else if (item.app != null && item.query != null) {
                        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(item.query)))
                            .setPackage(item.app.key.component.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        onOpenIntent(this, mapIntent)
                    } else if (item.query != null) {
                        webSearch(item.query)
                    }
                }
                addView(iconView, LinearLayout.LayoutParams(dp.dpi(28f), dp.dpi(28f)))
                addView(TextView(context).apply {
                    text = item.title
                    setTextColor(primaryInk)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    typeface = Fonts.ui(context, 400)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp.dpi(12f)
                })
                addView(ChevronGlyph(context, secondaryInk), LinearLayout.LayoutParams(dp.dpi(13f), dp.dpi(13f)))
            }
            suggestionsListLayout.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.dpi(44f)))
            if (old.getOrNull(i) != item.id) reveal(r)
        }
    }

    private fun querySources(raw: String) {
        pendingSources?.let { main.removeCallbacks(it) }
        val r = Runnable {
            sources.query(raw) { ct ->
                if (!isOpen || field.text.toString().trim() != raw) return@query
                contactsList = ct
                render()
            }
        }
        pendingSources = r
        main.postDelayed(r, 60)
    }

    private fun showContacts(list: List<SearchSources.Contact>, invite: Boolean) {
        val visibleList = list.take(4)
        val keys = if (invite) listOf(INVITE_ID) else visibleList.map { it.id }
        if (keys == shownContacts && contactsListLayout.childCount == keys.size) return
        val old = shownContacts
        shownContacts = keys
        contactsListLayout.removeAllViews()

        if (invite) {
            contactsCard.visibility = VISIBLE
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                isClickable = true
                background = rowRipple()
                setOnClickListener { onRequestContacts() }
                addView(ContactAvatar(context, null, "", 0xFF7B8591.toInt()), LinearLayout.LayoutParams(dp.dpi(40f), dp.dpi(40f)))
                addView(TextView(context).apply {
                    text = "Allow access to show contacts"
                    setTextColor(primaryInk)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Fonts.ui(context, 400)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp.dpi(14f) })
            }
            contactsListLayout.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.dpi(54f)))
            return
        }

        contactsCard.visibility = if (visibleList.isEmpty()) GONE else VISIBLE
        visibleList.forEachIndexed { i, c ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                isClickable = true
                setTag(R_ENTERABLE, true)
                background = rowRipple()
                setOnClickListener {
                    sources.contactOpened(c)
                    onOpenIntent(this, Intent(Intent.ACTION_VIEW, c.uri))
                }
                addView(ContactAvatar(context, c.photo, c.name, avatarColor(c.name)), LinearLayout.LayoutParams(dp.dpi(42f), dp.dpi(42f)))
                addView(TextView(context).apply {
                    text = c.name
                    setTextColor(primaryInk)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    typeface = Fonts.ui(context, 400)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp.dpi(14f)
                    rightMargin = dp.dpi(8f)
                })
                addView(ActionCircleButton(context, ActionCircleButton.PHONE, isNight()).apply {
                    contentDescription = "Call ${c.name}"
                    setOnClickListener {
                        sources.contactOpened(c)
                        onOpenIntent(this, Intent(Intent.ACTION_VIEW, c.uri))
                    }
                }, LinearLayout.LayoutParams(dp.dpi(34f), dp.dpi(34f)).apply { rightMargin = dp.dpi(10f) })
                addView(ActionCircleButton(context, ActionCircleButton.VIDEO, isNight()).apply {
                    contentDescription = "Video call ${c.name}"
                    setOnClickListener {
                        sources.contactOpened(c)
                        onOpenIntent(this, Intent(Intent.ACTION_VIEW, c.uri))
                    }
                }, LinearLayout.LayoutParams(dp.dpi(34f), dp.dpi(34f)))
            }
            contactsListLayout.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.dpi(56f)))
            if (old.getOrNull(i) != c.id) reveal(row)
        }
    }

    fun onContactsPermission(granted: Boolean) {
        if (!isOpen) return
        if (!granted) searchPrefs.edit().putBoolean(KEY_CONTACTS_DECLINED, true).apply()
        val raw = field.text.toString().trim()
        render()
        if (granted) sources.refresh(force = true) {
            suggested = sources.snapshot.contacts
            loadFavorites()
        }
        if (raw.isNotEmpty()) querySources(raw)
        field.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
    }

    private fun avatarColor(name: String) = AVATAR_COLORS[(name.hashCode() and 0x7FFFFFFF) % AVATAR_COLORS.size]

    private fun rowRipple() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null // Forced light ripple
    )

    private fun firstEnterable(box: ViewGroup): View? {
        if (box.visibility != VISIBLE) return null
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i)
            if (c.getTag(R_ENTERABLE) == true) return c
            if (c is ViewGroup) firstEnterable(c)?.let { return it }
        }
        return null
    }

    private fun updateEnterTarget() {
        enterTarget = if (field.text.isNullOrBlank()) null
        else firstEnterable(appsRow) ?: firstEnterable(contactsListLayout) ?: firstEnterable(suggestionsListLayout)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swipedHome = false

    private fun checkSwipeHome(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; swipedHome = false }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (!swipedHome && isOpen && canSwipeHome()) {
                val dy = ev.rawY - downY
                if (abs(dy) > slop && abs(dy) > abs(ev.rawX - downX) * 1.2f) {
                    swipedHome = true
                    if (dy > 0) onSwipeHome() else onSwipeUp()
                }
            }
        }
        return swipedHome
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = checkSwipeHome(ev) || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val before = swipedHome
        if (checkSwipeHome(ev)) {
            if (!before) MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; super.onTouchEvent(it); it.recycle() }
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun webSearch(q: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(search)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        onDismiss()
    }

    /**
     * The bar sits at the bottom: just above the gesture bar when there's no keyboard, and 10dp above the keyboard
     * while it is up. [ime] is the live keyboard inset (it changes every frame while the keyboard slides), so the bar
     * rides on the keyboard as it opens and drops back down as it closes.
     */
    private fun barGap(): Float = maxOf(dp.searchBarBottomMargin.toFloat(), ime + dp.dp(10f))
    private fun barY(): Float = dp.heightPx - barGap() - dp.searchBarHeight
    private fun contentTop() = dp.insets.top + dp.dpi(12f)
    private fun contentBottom() = (barGap() + dp.searchBarHeight + dp.dp(14f)).roundToInt()

    private fun layoutPill() {
        if (!::origin.isInitialized) return
        val h = dp.searchBarHeight
        val lp = pill.layoutParams
        if (lp.width != dp.searchBarWidth || lp.height != h) {
            lp.width = dp.searchBarWidth; lp.height = h
            pill.layoutParams = lp
        }

        val progress = f.coerceIn(0f, 1f)
        val dimAlpha = (backdropDimMaxAlpha * progress).roundToInt().coerceIn(0, 255)
        setBackgroundColor(android.graphics.Color.argb(dimAlpha, backdropDimColor, backdropDimColor, backdropDimColor))

        pill.x = dp.searchBarSide.toFloat()
        pill.y = barY() + dp.dp(BAR_SLIDE_DP) * (1f - f)          // slides up into place from below
        pill.alpha = f.coerceIn(0f, 1f)
        updateContentInset()
        pillBg.cornerRadius = h / 2f
        pillBg.setColor(searchBarFillColor)
        pillBg.setStroke(searchBarBorderWidth.toInt(), searchBarBorderColor)

        pillGlass = barGlass ?: origin.glass
        appsCard.syncWithBar()
        contactsCard.syncWithBar()
        suggestionsCard.syncWithBar()
        glyph.color = secondaryInk
        field.setTextColor(primaryInk)
        field.setHintTextColor(hintInk)
        clearBtn.setNight(true) // Force night mode style for clear button
        pill.elevation = dp.dp(10f)
        pill.outlineAmbientShadowColor = 0x33000000
        pill.outlineSpotShadowColor = 0x59000000
        pill.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, pillBg.cornerRadius)
                outline.alpha = 1f
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        contactsListLayout.dividerDrawable = insetDivider(0, 0)
        suggestionsListLayout.dividerDrawable = insetDivider(0, 0)
        layoutPill()
        if (isOpen) {
            shownApps = emptyList()
            shownContacts = emptyList()
            shownSuggestions = emptyList()
            render()
        }
    }

    private fun updateContentInset() {
        val lp = content.layoutParams as LayoutParams
        val top = contentTop()
        val bottom = contentBottom()
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top; lp.bottomMargin = bottom
            content.layoutParams = lp
        }
    }

    private fun rememberedKeyboard(): Float =
        keyboardPrefs.getInt(keyboardKey, 0).takeIf { it > 0 }?.toFloat() ?: (dp.heightPx * 0.38f)

    private fun retargetKeyboard(h: Float, duration: Long = Easing.IME_DURATION) {
        if (h > 0f && keyboardPrefs.getInt(keyboardKey, 0) != h.roundToInt()) keyboardPrefs.edit().putInt(keyboardKey, h.roundToInt()).apply()
        if (h == kbTarget) return
        kbTarget = h
        kbAnim?.cancel()
        updateContentInset()
        if (isOpen) render()
        if (duration <= 0L) { kb = h; layoutPill(); return }
        kbAnim = ValueAnimator.ofFloat(kb, h).apply {
            this.duration = duration
            interpolator = Easing.IME
            addUpdateListener { kb = it.animatedValue as Float; layoutPill() }
            start()
        }
    }

    private val noKeyboard = Runnable { if (isOpen && ime == 0 && !imeAnimating) retargetKeyboard(0f) }

    fun open(from: Origin, fromAbove: Boolean = false) {
        sources.snapshot.let { scores = it.scores; suggested = it.contacts }
        origin = from
        field.setTextColor(primaryInk)
        isOpen = true
        openedAt = SystemClock.uptimeMillis()
        loadFavorites()
        visibility = VISIBLE
        kb = rememberedKeyboard(); kbTarget = kb
        f = 0f
        layoutPill()
        updateContentInset()
        opening = true
        filter("")
        opening = false
        content.alpha = 0f
        content.translationY = dp.dp(OPEN_RISE_DP)
        content.animate().alpha(1f).translationY(0f).setDuration(OPEN_MS).setInterpolator(Easing.IME).start()
        animateTo(1f)
        field.isCursorVisible = true
        field.requestFocus()
        keyboardComing = false
        askForKeyboard()
        main.postDelayed(noKeyboard, 2500)
    }

    private fun askForKeyboard(until: Long = SystemClock.uptimeMillis() + KEYBOARD_RETRY_MS) {
        main.removeCallbacks(keyboardRetry)
        keyboardDeadline = until
        keyboardRetry.run()
    }

    private var keyboardDeadline = 0L
    private var keyboardComing = false
    private var openedAt = 0L
    private val keyboardRetry: Runnable = object : Runnable {
        override fun run() {
            if (!isOpen || keyboardComing) return
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
            if (SystemClock.uptimeMillis() < keyboardDeadline) main.postDelayed(this, 80)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isOpen) askForKeyboard()
    }

    fun close(to: Origin = origin, lift: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        origin = to
        main.removeCallbacks(noKeyboard)
        main.removeCallbacks(keyboardRetry)
        pendingFetch?.let { main.removeCallbacks(it) }
        pendingSources?.let { main.removeCallbacks(it) }
        net.shutdownNow()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.isCursorVisible = false
        content.animate().alpha(0f).translationY(dp.dp(OPEN_RISE_DP)).setDuration(CLOSE_MS).setInterpolator(Easing.IME).start()
        if (field.length() > 0) field.animate().alpha(0f).setDuration(110).withEndAction {
            field.text = null
            field.animate().alpha(1f).setDuration(150).start()
        }.start()
        animateTo(0f) { onClose() }
    }

    private fun animateTo(to: Float, end: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(f, to).apply {
            duration = ((if (to > f) OPEN_BAR_MS else CLOSE_MS) * abs(to - f)).toLong().coerceAtLeast(120L)
            interpolator = Easing.IME
            addUpdateListener { f = it.animatedValue as Float; layoutPill() }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    companion object {
        const val MORPH_MS = 300L
        private val MARKS = Regex("\\p{Mn}+")
        private const val KEY_CONTACTS_DECLINED = "contacts_declined"
        private const val INVITE_ID = -1L
        private const val MAX_APPS = 4
        private const val MAX_WEB = 4
        const val OPEN_MS = 425L
        private const val OPEN_BAR_MS = 360L
        private const val CLOSE_MS = 240L
        private const val BAR_SLIDE_DP = 36f
        const val OPEN_RISE_DP = 48f
        private const val FADE_MS = 150L
        private const val KEYBOARD_RETRY_MS = 1500L
        private val R_ENTERABLE = com.oneuiclone.home.R.id.search_enterable
        private val AVATAR_COLORS = intArrayOf(0xFF7B8591.toInt(), 0xFF6E7885.toInt(), 0xFF64707D.toInt(), 0xFF737D8A.toInt())
    }

    private class ClearButton(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x52FFFFFF }
        private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2C2C2E.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.7f * context.resources.displayMetrics.density
        }
        fun setNight(night: Boolean) {
            bgPaint.color = if (night) 0x52FFFFFF else 0x26000000
            xPaint.color = 0xFFFFFFFF.toInt() // Forced white x button
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(cx, cy)
            canvas.drawCircle(cx, cy, r, bgPaint)
            val d = r * 0.38f
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, xPaint)
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, xPaint)
        }
    }

    private class ChevronGlyph(context: Context, ink: Int) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt() // Forced white chevron
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.6f * context.resources.displayMetrics.density
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawLine(w * 0.3f, h * 0.2f, w * 0.7f, h * 0.5f, p)
            canvas.drawLine(w * 0.7f, h * 0.5f, w * 0.3f, h * 0.8f, p)
        }
    }

    private class BitmapIconView(context: Context, private val bmp: Bitmap, private val radius: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save(); canvas.clipPath(path)
            canvas.drawBitmap(bmp, null, rect, paint)
            canvas.restore()
        }
    }

    private class ActionCircleButton(context: Context, private val kind: Int, night: Boolean) : View(context) {
        companion object { const val PHONE = 0; const val VIDEO = 1 }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF } // Forced dark mode background
        private val fgColor = 0xFFFFFFFF.toInt() // Forced white action buttons
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgColor
            style = Paint.Style.FILL
        }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(cx, cy)
            canvas.drawCircle(cx, cy, r, bg)
            val s = r * 0.46f
            if (kind == PHONE) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fgColor
                    style = Paint.Style.STROKE
                    strokeWidth = s * 0.42f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.save()
                canvas.rotate(135f, cx, cy)
                canvas.drawArc(cx - s * 0.85f, cy - s * 0.55f, cx + s * 0.85f, cy + s * 0.55f, 200f, 140f, false, p)
                canvas.restore()
            } else {
                canvas.drawRoundRect(cx - s * 0.9f, cy - s * 0.52f, cx + s * 0.3f, cy + s * 0.52f, s * 0.22f, s * 0.22f, fg)
                val tri = Path().apply {
                    moveTo(cx + s * 0.35f, cy - s * 0.2f)
                    lineTo(cx + s * 0.9f, cy - s * 0.45f)
                    lineTo(cx + s * 0.9f, cy + s * 0.45f)
                    lineTo(cx + s * 0.35f, cy + s * 0.2f)
                    close()
                }
                canvas.drawPath(tri, fg)
            }
        }
    }

    private class ContactAvatar(context: Context, private val photo: Bitmap?, name: String, private val color: Int) : View(context) {
        private val letters = name.trim().split(' ').filter { it.isNotEmpty() }.let { parts ->
            when {
                parts.isEmpty() -> ""
                parts.size == 1 -> parts[0].take(2).uppercase()
                else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
            }
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            if (photo != null) {
                path.reset(); path.addCircle(r, r, r, Path.Direction.CW)
                canvas.save(); canvas.clipPath(path)
                canvas.drawBitmap(photo, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
                canvas.restore()
                return
            }
            paint.color = color
            canvas.drawCircle(r, r, r, paint)
            paint.color = 0xFFFFFFFF.toInt()
            if (letters.isEmpty()) {
                canvas.drawCircle(r, r * 0.78f, r * 0.30f, paint)
                canvas.drawArc(r * 0.42f, r * 1.18f, r * 1.58f, r * 2.2f, 180f, 180f, true, paint)
                return
            }
            paint.textSize = r * 0.72f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Fonts.ui(context, 500)
            val fm = paint.fontMetrics
            canvas.drawText(letters, r, r - (fm.ascent + fm.descent) / 2f, paint)
        }
    }
}
