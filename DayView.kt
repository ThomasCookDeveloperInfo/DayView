package com.thomascook.core.dayview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.support.v4.view.GestureDetectorCompat
import android.support.v4.view.ViewCompat
import android.support.v4.view.animation.FastOutLinearInInterpolator
import android.text.*
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.*
import android.widget.OverScroller
import com.condecosoftware.roombooking.R
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates

/**
 * Scroll direction constants
 */
private const val SCROLL_DIRECTION_NONE = 0
private const val SCROLL_DIRECTION_VERTICAL = 1

/**
 * A custom view used to display a list of events in a scrollable area spanning 24 hours
 */
class DayView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {

    constructor(context: Context) : this(context, null, 0)
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    /**
     * Refreshes [currentEventShapes] with the [newEvents], should be called by consumer
     * Plays an animation to move the view left or right depending which way it moved
     */
    fun refreshEvents(newEvents: Collection<DayViewEvent>, newCalendar: Calendar) {
        // Add the current event shapes to the old event shapes
        // To cache them for the animation
        oldEventShapes.addAll(currentEventShapes)

        // Clear the event shapes collection
        currentEventShapes.clear()

        // Map all the new events to event shapes and add them to event shapes collection
        currentEventShapes.addAll(newEvents.map { event ->
            EventShape(event, null)
        })

        // Work out movement
        val movement = newCalendar.get(Calendar.DAY_OF_YEAR) -
                currentCalendar.get(Calendar.DAY_OF_YEAR)

        // Scroll to that day
        scrollToDay(movement)

        // Set the current calendar
        currentCalendar.time = newCalendar.time

        // Invalidate the view
        ViewCompat.postInvalidateOnAnimation(this@DayView)
    }

    // Interface to rest of application
    private var dayViewCallbacks: Callbacks? = null
    fun setDayViewCallbacks(callbacks: Callbacks?) {
        dayViewCallbacks = callbacks
    }

    // Immutable view state
    private val oldEventShapes = mutableListOf<EventShape>()
    private val currentEventShapes = mutableListOf<EventShape>()
    private val scroller = OverScroller(context, FastOutLinearInInterpolator())
    private val scrollDuration = 250
    private val currentOrigin = PointF(0f, 0f)

    /**
     * Date time interpreter implementation
     * Logic to handle how date and time information is displayed
     * Is encapsulated here
     */
    private val dateTimeInterpreter = object : DateTimeInterpreter {
        override fun interpretDate(date: Calendar): String {
            return try {
                val dateFormat = SimpleDateFormat("EEEEE M/dd")
                dateFormat.format(date.time).toUpperCase()
            } catch (ex: Exception) {
                ""
            }
        }

        override fun interpretTime(hour: Int): String {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, 0)

            return try {
                val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                dateFormat.format(calendar.time)
            } catch (ex: Exception) {
                return ""
            }
        }
    }

    // Mutable view state
    private var gestureDetector by Delegates.notNull<GestureDetectorCompat>()
    private var currentScrollDirection = SCROLL_DIRECTION_NONE
    private var currentFlingDirection = SCROLL_DIRECTION_NONE
    private var minimumFlingVelocity by Delegates.notNull<Int>()
    private var navigationRowBGPaint by Delegates.notNull<Paint>()
    private var navigationRowHeight by Delegates.notNull<Float>()
    private var navigationRowTextPaint by Delegates.notNull<Paint>()
    private var chevronPaint by Delegates.notNull<Paint>()
    private var chevronVerticalPadding: Int
    private var chevronHorizontalPadding: Int
    private var chevronHeight: Int
    private var chevronWidth: Int
    private var leftChevronRect: Rect? = null
    private var rightChevronRect: Rect? = null
    private var currentCalendar = Calendar.getInstance()
    private var timeColumnWidth by Delegates.notNull<Float>()
    private var timeColumnPadding by Delegates.notNull<Float>()
    private var textSize by Delegates.notNull<Float>()
    private var textHeight by Delegates.notNull<Float>()
    private var eventNameTextSize by Delegates.notNull<Float>()
    private var hourHeight by Delegates.notNull<Float>()
    private var hourSeparatorHeight by Delegates.notNull<Float>()
    private var timeTextPaint by Delegates.notNull<Paint>()
    private var timeColumnBGPaint by Delegates.notNull<Paint>()
    private var hourSeparatorPaint by Delegates.notNull<Paint>()
    private var nowLinePaint by Delegates.notNull<Paint>()
    private var eventColumnBGPaint by Delegates.notNull<Paint>()
    private var eventBGPaint by Delegates.notNull<Paint>()
    private var eventCornerRadius by Delegates.notNull<Float>()
    private var eventTextPaint by Delegates.notNull<TextPaint>()

    /**
     * Initialize the view by reading the properties from the layout
     * And populating the respective properties in this instance
     */
    init {
        val typedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.DayView, 0, 0)
        try {
            /**
             * Implementation of simple gesture listener
             * Used for handling scroll events and touch and long press on events
             */
            gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {

                /**
                 * Override the on down event handler
                 */
                override fun onDown(e: MotionEvent?): Boolean {
                    // Stop the current scroll animation
                    scroller.forceFinished(true)

                    // Reset scroll and fling state
                    resetScrollAndFlingState()

                    return true
                }

                /**
                 * Handle scrolling
                 */
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean {
                    currentScrollDirection = SCROLL_DIRECTION_VERTICAL
                    currentOrigin.y -= distanceY
                    if (currentOrigin.y > 0) {
                        currentOrigin.y = 0.0f
                    } else if (currentOrigin.y < -(hourHeight * 24) + height - navigationRowHeight) {
                        currentOrigin.y = -(hourHeight * 24) + height - navigationRowHeight
                    }
                    ViewCompat.postInvalidateOnAnimation(this@DayView)
                    return true
                }

                /**
                 * Handle fling style scrolling, returns true if the event is swallowed
                 */
                override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {

                    // Stop any scrolling
                    scroller.forceFinished(true)

                    // Set the current fling direction and, if the fling is vertical, apply the fling to the scroller
                    currentFlingDirection = currentScrollDirection
                    when (currentFlingDirection) {
                        SCROLL_DIRECTION_VERTICAL -> {
                            scroller.fling(currentOrigin.x.toInt(), currentOrigin.y.toInt(),
                                    0, velocityY.toInt(),
                                    Integer.MIN_VALUE, Integer.MAX_VALUE,
                                    -(hourHeight * 24 - textHeight / 2 - timeColumnPadding / 2 - height + navigationRowHeight).toInt(), 0)
                        }
                    }

                    ViewCompat.postInvalidateOnAnimation(this@DayView)
                    return true
                }

                /**
                 * Handle the single tap
                 * If it's on an event, then call back
                 * Else if it's near a chevron then call back
                 * Else ignore
                 */
                override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                    // If the event is null then just call to super to ignore
                    if (e == null) return super.onSingleTapConfirmed(e)

                    // If we have callbacks
                    if (dayViewCallbacks != null) {
                        // If the tap was on an event then callback
                        val reversedEvents = currentEventShapes
                        Collections.reverse(reversedEvents)
                        reversedEvents.forEach { reversedEventRect ->
                            val rect = reversedEventRect.rect
                            if (rect != null && e.x > rect.left && e.x < rect.right && e.y > rect.top && e.y < rect.bottom) {
                                dayViewCallbacks?.onEventClick(reversedEventRect.event)
                                        ?: return super.onSingleTapConfirmed(e)
                                playSoundEffect(SoundEffectConstants.CLICK)
                                return super.onSingleTapConfirmed(e)
                            }
                        }
                        // If user tapped on left of nav row
                        if (e.x < width / 4 && e.y < navigationRowHeight) {
                            // Work out new calendar
                            val newCalendar = Calendar.getInstance()
                            newCalendar.time = currentCalendar.time
                            newCalendar.set(Calendar.DAY_OF_YEAR, newCalendar.get(Calendar.DAY_OF_YEAR) - 1)
                            dayViewCallbacks?.onDayChanged(newCalendar)
                        }
                        // Else if user tapped on right of nav row
                        else if (e.x > width / 2 + width / 4 && e.y < navigationRowHeight) {
                            // Work out new calendar
                            val newCalendar = Calendar.getInstance()
                            newCalendar.time = currentCalendar.time
                            newCalendar.set(Calendar.DAY_OF_YEAR, newCalendar.get(Calendar.DAY_OF_YEAR) + 1)
                            dayViewCallbacks?.onDayChanged(newCalendar)
                        }
                    } else {
                        throw Exception("You must implement DayView.Callbacks")
                    }

                    // Call to super class implementation
                    return super.onSingleTapConfirmed(e)
                }

                /**
                 * Handle the long press
                 * If it's on an event, then call back
                 * Else if it's near a chevron, then call back
                 */
                override fun onLongPress(e: MotionEvent?) {
                    // If the event is null then just return
                    if (e == null) return

                    // Call the super class function
                    super.onLongPress(e)

                    // If the long press was on an event then callback
                    if (dayViewCallbacks != null) {
                        val reversedEvents = currentEventShapes
                        Collections.reverse(reversedEvents)
                        reversedEvents.forEach { reversedEventRect ->
                            val rect = reversedEventRect.rect
                            if (rect != null && e.x > rect.left && e.x < rect.right && e.y > rect.top && e.y < rect.bottom) {
                                dayViewCallbacks?.onEventLongPress(reversedEventRect.event)
                                        ?: return
                                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                return
                            }
                        }

                        // If user tapped on left of nav row
                        if (e.x < width / 4 && e.y < navigationRowHeight) {
                            // Work out new calendar
                            val newCalander = currentCalendar
                            newCalander.set(Calendar.DAY_OF_YEAR, newCalander.get(Calendar.DAY_OF_YEAR) - 1)
                            dayViewCallbacks?.onDayChanged(newCalander)
                        }
                        // Else if user tapped on right of nav row
                        else if (e.x > width / 2 + width / 4 && e.y < navigationRowHeight) {
                            // Work out new calendar
                            val newCalander = currentCalendar
                            newCalander.set(Calendar.DAY_OF_YEAR, newCalander.get(Calendar.DAY_OF_YEAR) + 1)
                            dayViewCallbacks?.onDayChanged(newCalander)
                        }
                    }
                }
            })

            // Minimum fling velocity
            minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

            // Dimensions
            navigationRowHeight = typedArray.getDimension(R.styleable.DayView_navigationRowHeight, 48f)
            timeColumnWidth = typedArray.getDimension(R.styleable.DayView_timeColumnWidth, 48f)
            timeColumnPadding = typedArray.getDimension(R.styleable.DayView_timeColumnPadding, 0.0f)
            hourHeight = typedArray.getDimension(R.styleable.DayView_hourHeight, 100f)
            hourSeparatorHeight = typedArray.getDimension(R.styleable.DayView_hourSeparatorHeight, 1f)
            eventCornerRadius = typedArray.getDimension(R.styleable.DayView_eventCornerRadius, 0.0f)
            textSize = typedArray.getDimension(R.styleable.DayView_mainTextSize, 10.0f)
            eventNameTextSize = typedArray.getDimension(R.styleable.DayView_eventNameTextSize, 18.0f)

            // Paints
            // Navigation row background
            navigationRowBGPaint = Paint()
            navigationRowBGPaint.color = typedArray.getColor(R.styleable.DayView_navigationRowBGColor, Color.WHITE)

            // Navigation row text
            navigationRowTextPaint = Paint()
            navigationRowTextPaint.textSize = textSize
            navigationRowTextPaint.textAlign = Paint.Align.CENTER
            navigationRowTextPaint.color = typedArray.getColor(R.styleable.DayView_navigationRowTextColor, Color.GRAY)

            // Chevron paint
            chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            chevronPaint.style = Paint.Style.STROKE
            chevronPaint.strokeWidth = 10.0f
            chevronPaint.color = typedArray.getColor(R.styleable.DayView_timeTextColor, Color.GRAY)

            // Chevron padding
            chevronVerticalPadding = (navigationRowHeight / 4).toInt()
            chevronHorizontalPadding = (navigationRowHeight / 2).toInt()
            chevronHeight = (navigationRowHeight / 2).toInt()
            chevronWidth = (chevronHeight / 1.6).toInt()

            // Time text
            timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            timeTextPaint.textSize = textSize
            timeTextPaint.textAlign = Paint.Align.RIGHT
            timeTextPaint.color = typedArray.getColor(R.styleable.DayView_timeTextColor, Color.BLACK)

            // Time text height
            val timeTextRect = Rect()
            timeTextPaint.getTextBounds("24:00", 0, "24:00".length, timeTextRect)
            textHeight = timeTextRect.height().toFloat()

            // Time column background
            timeColumnBGPaint = Paint()
            timeColumnBGPaint.color = typedArray.getColor(R.styleable.DayView_timeColumnBGColor, Color.GRAY)

            // Hour separator
            hourSeparatorPaint = Paint()
            hourSeparatorPaint.style = Paint.Style.STROKE
            hourSeparatorPaint.strokeWidth = hourSeparatorHeight
            hourSeparatorPaint.color = typedArray.getColor(R.styleable.DayView_hourSeparatorColor, Color.BLACK)

            // Now line
            nowLinePaint = Paint()
            nowLinePaint.style = Paint.Style.STROKE
            nowLinePaint.strokeWidth = 10.0f
            nowLinePaint.color = typedArray.getColor(R.styleable.DayView_nowLineColor, Color.MAGENTA)

            // Event column background
            eventColumnBGPaint = Paint()
            eventColumnBGPaint.color = typedArray.getColor(R.styleable.DayView_eventColumnBGColor, Color.WHITE)

            // Event background paint
            eventBGPaint = Paint()
            eventBGPaint.color = typedArray.getColor(R.styleable.DayView_eventBGColor, Color.WHITE)

            // Event text
            eventTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG)
            eventTextPaint.textSize = eventNameTextSize
            eventTextPaint.style = Paint.Style.FILL
            eventTextPaint.color = typedArray.getColor(R.styleable.DayView_eventTextColor, Color.WHITE)
        } finally {
            typedArray.recycle()
        }
    }

    /**
     * Draw the time column and event column
     * To the [canvas]
     */
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        // Get the canvas if it exists, else return
        val _canvas = canvas ?: return

        // Draw the time column
        drawTimeColumn(_canvas)

        // Draw the event column and any events
        drawEventsColumn(_canvas)

        // Draw the navigation row
        drawNavigationRow(_canvas)
    }

    /**
     * Draws the navigation row to the [canvas]
     */
    private fun drawNavigationRow(canvas: Canvas) {
        // Clip to paint only in the navigation row
        canvas.clipRect(0f, 0f, width.toFloat(), navigationRowHeight, Region.Op.REPLACE)

        // Draw the navigation row
        canvas.drawRect(0f, 0f, width.toFloat(), navigationRowHeight, navigationRowBGPaint)

        // If we haven't initialized the chevron rect's then do so now
        // We can't do this in init because layout hasn't been fully inflated
        // So we don't know the width or height of the view
        if (leftChevronRect == null) {
            leftChevronRect = Rect(chevronHorizontalPadding, chevronVerticalPadding,
                    chevronHorizontalPadding + chevronWidth, chevronVerticalPadding + chevronHeight)
        }
        if (rightChevronRect == null) {
            rightChevronRect = Rect(width - chevronWidth - chevronHorizontalPadding, chevronVerticalPadding,
                    width - chevronHorizontalPadding, chevronVerticalPadding + chevronHeight)
        }

        // Draw left chevron
        canvas.drawLine(leftChevronRect!!.right.toFloat(), leftChevronRect!!.top.toFloat(),
                leftChevronRect!!.left.toFloat(), chevronVerticalPadding + (leftChevronRect!!.height() / 2).toFloat(), chevronPaint)
        canvas.drawLine(leftChevronRect!!.left.toFloat(), chevronVerticalPadding + (leftChevronRect!!.height() / 2).toFloat(),
                leftChevronRect!!.right.toFloat(), leftChevronRect!!.bottom.toFloat(), chevronPaint)

        // Draw right chevron
        canvas.drawLine(rightChevronRect!!.left.toFloat(), rightChevronRect!!.top.toFloat(),
                rightChevronRect!!.right.toFloat(), chevronVerticalPadding + (rightChevronRect!!.height() / 2).toFloat(), chevronPaint)
        canvas.drawLine(rightChevronRect!!.right.toFloat(), chevronVerticalPadding + (rightChevronRect!!.height() / 2).toFloat(),
                rightChevronRect!!.left.toFloat(), rightChevronRect!!.bottom.toFloat(), chevronPaint)

        // Draw the text
        canvas.drawText(dateTimeInterpreter.interpretDate(currentCalendar),
                (width / 2).toFloat(), ((navigationRowHeight - textHeight) / 2) + textHeight,
                navigationRowTextPaint)
    }

    /**
     * Draws the time column to the [canvas]
     */
    private fun drawTimeColumn(canvas: Canvas) {
        // Clip to paint in left column only
        canvas.clipRect(0f, navigationRowHeight, timeColumnWidth + timeColumnPadding, height.toFloat(), Region.Op.REPLACE)

        // Draw time column
        canvas.drawRect(0f, navigationRowHeight, timeColumnWidth + timeColumnPadding, height.toFloat(), timeColumnBGPaint)

        // Draw the times
        for (i in 0 until 24) {
            val top = navigationRowHeight + timeColumnPadding * 2 + currentOrigin.y + hourHeight * i
            val time = dateTimeInterpreter.interpretTime(i)

            if (top < height)
                canvas.drawText(time, timeColumnWidth, top + textHeight, timeTextPaint)
        }
    }

    /**
     * Draws the events contained in the events collection to the [canvas]
     */
    private fun drawEventsColumn(canvas: Canvas) {

        // Clip to paint in right column only
        canvas.clipRect(timeColumnWidth + timeColumnPadding, navigationRowHeight,
                width - timeColumnPadding,
                height.toFloat(), Region.Op.REPLACE)

        // Draw event column
        canvas.drawRect(timeColumnWidth + timeColumnPadding, navigationRowHeight,
                width - timeColumnPadding,
                height.toFloat(), eventColumnBGPaint)

        // If scroller is scrolling horizontally
        if (currentOrigin.x.toInt() != scroller.finalX) {
            // Work out the difference between current and final x
            val difference = scroller.finalX - currentOrigin.x

            // Work out if moving left or right
            val isMovingLeft = difference < 0

            // Calculate the bounds of the event columns
            val eventColumns = calculateMutlipleDayEventColumns(difference)

            // Track the column index
            var currentColumn = 0

            // For both left and right columns
            eventColumns.forEach { column ->
                // Calculate the line count
                val lineCount = ((height - navigationRowHeight / hourHeight + 1)).toInt()
                val hourLines = FloatArray(lineCount * 4)

                // Draw the lines
                var drawn = 0
                for (hour in 0 until 24) {
                    // Work out where top of this line is
                    val top = navigationRowHeight + currentOrigin.y +
                            timeColumnPadding * 2 + textHeight / 2 + hourHeight * hour

                    // If the top is in the viewport, create a line for it
                    if (top > 0.0f && top < height) {
                        hourLines[drawn * 4] = column.left.toFloat()
                        hourLines[drawn * 4 + 1] = top
                        hourLines[drawn * 4 + 2] = column.right.toFloat()
                        hourLines[drawn * 4 + 3] = top
                        drawn++
                    }
                }

                // Draw the hour lines
                canvas.drawLines(hourLines, hourSeparatorPaint)

                // If we're moving left, then first column needs to contain old events
                if (isMovingLeft && currentColumn == 0) {
                    if (oldEventShapes.isNotEmpty()) {
                        // Draw the events
                        drawEvents(canvas, column, oldEventShapes)
                    }
                }
                // Else if we're moving left, then second column needs to contain new events
                else if (isMovingLeft && currentColumn == 1) {
                    // Draw the events
                    drawEvents(canvas, column, currentEventShapes)
                }
                // Else if we're moving right, the first column needs to contain new events
                else if (!isMovingLeft && currentColumn == 0) {
                    // Draw the events
                    drawEvents(canvas, column, currentEventShapes)
                }
                // Else we must be moving right, the second column needs to contain old events
                else {
                    // Draw the events
                    drawEvents(canvas, column, oldEventShapes)
                }

                // Increment the current column
                currentColumn++
            }
        }
        // Else there is no horizontal scrolling
        // So just draw lines normally
        else {
            // We are not animating, so let's clear the oldEventShapes cache
            oldEventShapes.clear()

            // Work out the total line count
            val lineCount = ((height - navigationRowHeight / hourHeight + 1)).toInt()
            val hourLines = FloatArray(lineCount * 4)

            // Draw the lines
            var drawn = 0
            for (hour in 0 until 24) {
                // Work out where top of line is
                val top = navigationRowHeight + currentOrigin.y + timeColumnPadding * 2 +
                        textHeight / 2 + hourHeight * hour

                // If top is in viewport, add line
                if (top > 0.0f && top < height) {
                    val left = timeColumnWidth + timeColumnPadding
                    hourLines[drawn * 4] = left
                    val right = width - timeColumnPadding
                    hourLines[drawn * 4 + 1] = top
                    hourLines[drawn * 4 + 2] = right
                    hourLines[drawn * 4 + 3] = top
                    drawn++
                }
            }

            // Draw the hour lines
            canvas.drawLines(hourLines, hourSeparatorPaint)

            // Draw the events
            if (currentEventShapes.isNotEmpty()) {
                // Calculate the bounds of the events
                val rect = Rect((timeColumnWidth + timeColumnPadding).toInt(),
                        navigationRowHeight.toInt(), width, height)

                // Draw them
                drawEvents(canvas, rect, currentEventShapes)
            }
        }

        // Draw the current time line
        val now = Calendar.getInstance()
        val beforeNow = (now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0f) * hourHeight
        val startY = navigationRowHeight + currentOrigin.y + timeColumnPadding * 2 + hourHeight
        canvas.drawLine(timeColumnWidth, startY + beforeNow,
                width.toFloat() - timeColumnPadding, startY + beforeNow, nowLinePaint)
    }

    /**
     * Draws events on the [canvas] in the given [bounds]
     */
    private fun drawEvents(canvas: Canvas, bounds: Rect, events: Collection<EventShape>) {
        events.forEach { eventShape ->
            // Get a reference to the event to avoid unboxing needlessly
            val event = eventShape.event

            // Work out top of hour
            val timeFromHour = event.startTime.get(Calendar.HOUR_OF_DAY)
            val timeFromMinute = event.startTime.get(Calendar.MINUTE)
            val topHourPixels = timeFromHour * hourHeight

            // Make sure we don't divide by 0
            val topMinutePixels: Int = when (timeFromMinute) {
                0 -> {
                    0
                }
                else -> {
                    (hourHeight / (60 / timeFromMinute)).toInt()
                }
            }
            val top = currentOrigin.y + topHourPixels + topMinutePixels - textHeight + hourHeight

            // Work out top of hour
            val timeToHour = event.endTime.get(Calendar.HOUR_OF_DAY)
            val timeToMinute = event.endTime.get(Calendar.MINUTE)
            val bottomHourPixels = timeToHour * hourHeight

            // Make sure we don't divide by 0
            val bottomMinutePixels: Int = when (timeToMinute) {
                0 -> {
                    0
                }
                else -> {
                    (hourHeight / (60 / timeToMinute)).toInt()
                }
            }
            val bottom = currentOrigin.y + bottomHourPixels + bottomMinutePixels - textHeight + hourHeight

            // If the event is in the visible area
            if (top < height && bottom > 0 && bottom > top) {
                val actualTop: Float = when (top) {
                    navigationRowHeight -> {
                        navigationRowHeight
                    } else -> {
                        top
                    }
                }
                val actualBottom: Float = when (bottom) {
                    height.toFloat() -> {
                        height.toFloat()
                    }
                    else -> {
                        bottom
                    }
                }
                // Calculate the rect
                val rect = RectF(bounds.left.toFloat(), actualTop,
                        bounds.right.toFloat(), actualBottom)

                // Set the event shapes rect
                eventShape.rect = rect

                // Draw the rect
                canvas.drawRoundRect(eventShape.rect, eventCornerRadius, eventCornerRadius, eventBGPaint)

                // Then draw the event title if it's in bound
                val inBounds = rectInBounds(rect)
                if (inBounds) {
                    // Prepare the name of the event
                    val spannableStringBuilder = SpannableStringBuilder()
                    spannableStringBuilder.append(event.name)
                    spannableStringBuilder.setSpan(StyleSpan(Typeface.BOLD), 0, spannableStringBuilder.length, 0)

                    // Add a space to separate name and location of event
                    spannableStringBuilder.append(' ')

                    // Prepare the location of the event
                    spannableStringBuilder.append(event.moreInfo)

                    // Work out available width and height
                    val availableHeight = rect.bottom - rect.top - timeColumnPadding * 2
                    val availableWidth = rect.right - rect.left - timeColumnPadding * 2

                    // If there is any point drawing the text...
                    if (availableHeight > 0 && availableWidth > 0) {
                        // Get the text dimensions
                        var textLayout = StaticLayout(spannableStringBuilder, eventTextPaint,
                                availableWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false)

                        // Work out the height of a line
                        val lineHeight = textLayout.height / textLayout.lineCount

                        // If we have enough space
                        if (availableHeight >= lineHeight && availableWidth > 0) {
                            // Work out how many lines we can show
                            var availableLines = availableHeight / lineHeight
                            do {
                                // Ellipsize text to fit into event rect
                                textLayout = StaticLayout(TextUtils.ellipsize(spannableStringBuilder,
                                        eventTextPaint, availableLines * availableWidth,
                                        TextUtils.TruncateAt.END), eventTextPaint,
                                        availableWidth.toInt(), Layout.Alignment.ALIGN_NORMAL,
                                        1.0f, 0.0f, false)

                                // Reduce line count
                                availableLines--

                            } while (textLayout.height > availableHeight)

                            // Draw text
                            canvas.save()
                            canvas.translate(rect.left + timeColumnPadding, top + timeColumnPadding)
                            textLayout.draw(canvas)
                            canvas.restore()
                        }
                    }
                }
            } else {
                eventShape.rect = null
            }
        }
    }

    /**
     * Calculates if the passed [rect] is in the viewport bounds
     */
    private fun rectInBounds(rect: RectF): Boolean {
        if (rect.right - rect.left < 0) return false
        if (rect.bottom - rect.top < 0) return false
        return true
    }

    /**
     * Scrolls the view left or right to the next day
     */
    private fun scrollToDay(movement: Int) {
        // Work out delta x
        val deltaX = movement * (width - timeColumnWidth - timeColumnPadding)

        // Force stop on any animation playing
        scroller.forceFinished(true)

        // Scroll to the new day
        scroller.startScroll(currentOrigin.x.toInt(), currentOrigin.y.toInt(), -deltaX.toInt(), 0, scrollDuration)

        // Reset the current scroll and fling directions
        resetScrollAndFlingState()
    }

    /**
     * Calculates the bounds of a days event column
     * Returns a collection containing the columns
     */
    private fun calculateMutlipleDayEventColumns(difference: Float) : Collection<Rect> {
        // Columns for drawing lines in
        val leftColumn: Rect
        val rightColumn: Rect

        // Scroll left
        if (difference < 0) {
            // Work out where the right hand side of the left line column should be
            val rightOfLeftColumn = (width - timeColumnWidth - timeColumnPadding) -
                    ((width - timeColumnWidth - timeColumnPadding) + difference)

            // Work out left lines column
            leftColumn = Rect((timeColumnWidth + timeColumnPadding).toInt(),
                    navigationRowHeight.toInt(), rightOfLeftColumn.toInt(), height)

            // Work out right lines column
            rightColumn = Rect((leftColumn.right + timeColumnPadding * 4).toInt(),
                    navigationRowHeight.toInt(), (width - timeColumnPadding).toInt(), height)
        }
        // Scroll right
        else {
            // Work out where the left hand side of the right line column should be
            val leftOfRightColumn = (timeColumnWidth + timeColumnPadding) + (width - timeColumnPadding - timeColumnWidth) - difference

            // Work out right lines column
            leftColumn = Rect(leftOfRightColumn.toInt(), navigationRowHeight.toInt(), width, height)

            // Work out left lines column
            rightColumn = Rect((timeColumnWidth + timeColumnPadding).toInt(), navigationRowHeight.toInt(),
                    (leftOfRightColumn - timeColumnPadding * 4).toInt(), height)
        }

        return listOf(leftColumn, rightColumn)
    }

    /**
     * Handles touch events to trigger scroller
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false

        // Get the value of gesture detector on touch
        val onTouchValue = gestureDetector.onTouchEvent(event)

        // If user has released touch and fling direction is none, then make current scroll direction none too
        if (event.action == MotionEvent.ACTION_UP && currentFlingDirection == SCROLL_DIRECTION_NONE) {
            currentScrollDirection = SCROLL_DIRECTION_NONE
        }

        // Return the value from the gesture detector
        return onTouchValue
    }

    /**
     * Function for computing scroll
     */
    override fun computeScroll() {
        // Call to super class method
        super.computeScroll()

        // If scroller is finished and the fling action is underway
        if (scroller.isFinished) {
            if (currentFlingDirection != SCROLL_DIRECTION_NONE) {
                // Stop the current scroll animation
                scroller.forceFinished(true)

                // Reset scroll and fling state
                resetScrollAndFlingState()
            }
        } else {
            if (currentFlingDirection != SCROLL_DIRECTION_NONE && shouldScrollingStop()) {
                // Stop the current scroll animation
                scroller.forceFinished(true)

                // Reset scroll and fling state
                resetScrollAndFlingState()
            } else if (scroller.computeScrollOffset()) {
                currentOrigin.x = scroller.currX.toFloat()
                currentOrigin.y = scroller.currY.toFloat()
                ViewCompat.postInvalidateOnAnimation(this)
            }
        }
    }

    /**
     * Check if scrolling should be stopped
     * Return true if scrolling should be stopped
     */
    private fun shouldScrollingStop(): Boolean {
        return scroller.currVelocity <= minimumFlingVelocity
    }

    /**
     * Scroll to the nearest origin
     */
    private fun resetScrollAndFlingState() {
        // Reset scrolling and fling direction
        currentScrollDirection = SCROLL_DIRECTION_NONE
        currentFlingDirection = SCROLL_DIRECTION_NONE
    }

    /**
     * Definition of the shape of an event on the day view
     * A [DayViewEvent] is attached to it as the [DayView] operates on
     * EventShapes but needs access to event information
     */
    private class EventShape(val event: DayViewEvent,
                             var rect: RectF?)

    /**
     * Interface for date time interpreter
     * Implement this for interpreting dates in a format suitable for the day view
     */
    interface DateTimeInterpreter {

        /**
         * Interpret the passed [date] to a string
         */
        fun interpretDate(date: Calendar): String

        /**
         * Interpret the passed [hour] to a string
         */
        fun interpretTime(hour: Int): String
    }

    /**
     * Set of callbacks for interfacing between this component
     * And the rest of the application
     */
    interface Callbacks {
        /**
         * Callback for when event is long pressed
         */
        fun onEventLongPress(event: DayViewEvent)

        /**
         * Callback for when event is clicked
         */
        fun onEventClick(event: DayViewEvent)

        /**
         * Callback for when day is changed
         */
        fun onDayChanged(newDay: Calendar)
    }
}
