package com.thomascook.core.dayview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.support.v4.view.ViewCompat
import android.support.v4.widget.NestedScrollView
import android.text.format.DateFormat
import android.util.AttributeSet
import android.widget.RelativeLayout
import android.widget.TextView
import com.thomascook.R
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates

/**
 * Constants
 */
private const val NAME_TAG = "name"
private const val LOCATION_TAG = "location"
private const val HOURS_IN_DAY = 24
private const val MINUTES_IN_HOUR = 60

/**
 * A custom view used to display a list of events in a scrollable area spanning 24 hours
 */
class DayView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : RelativeLayout(context, attrs, defStyleAttr) {

    constructor(context: Context) : this(context, null, 0)
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    // Immutable view state
    private val eventViewGroup = RelativeLayout(context)

    // Mutable view state
    private var timeColumnWidth by Delegates.notNull<Float>()
    private var timeColumnPadding by Delegates.notNull<Float>()
    private var textSize by Delegates.notNull<Float>()
    private var textHeight by Delegates.notNull<Float>()
    private var hourHeight by Delegates.notNull<Float>()
    private var hourSeparatorHeight by Delegates.notNull<Float>()
    private var timeTextPaint by Delegates.notNull<Paint>()
    private var timeColumnBGPaint by Delegates.notNull<Paint>()
    private var hourSeparatorPaint by Delegates.notNull<Paint>()
    private var nowLinePaint by Delegates.notNull<Paint>()
    private var eventCellId by Delegates.notNull<Int>()
    private var eventColumnBGPaint by Delegates.notNull<Paint>()
    private var is24HourFormat by Delegates.notNull<Boolean>()

    // Public mutable state
    var isToday = false

    /**
     * Callback to rest of application
     */
    interface Callbacks {
        /**
         * Callback for when entity is long pressed
         */
        fun onEventClick(entity: DayViewEvent)
    }

    /**
     * Reference to callback implementer
     */
    private var callbacks: Callbacks? = null
    fun setCallbacks(callbacks: Callbacks?) {
        this.callbacks = callbacks
    }

    /**
     * Scrolls the view to the now position
     */
    fun scrollToNow() {
        if (!isToday) return
        val scrollView = parent as? NestedScrollView
        scrollView?.let {
            val now = Calendar.getInstance()
            now.time = Date()
            val deltaY = (hourHeight * now.get(Calendar.HOUR_OF_DAY)).toInt()
            it.scrollY = deltaY
        }
    }

    /**
     * API for application to set the entities to render in the view
     */
    fun setEvents(entities: Collection<DayViewEvent>) {
        // Make sure we have callbacks
        if (callbacks === null)
            throw Exception("You must provide an implementation of TimelineView.Callbacks")

        // Remove all the views from the view group
        eventViewGroup.removeAllViews()

        // Carry out the packing algorithm on the passed events
        val packedEvents = packEvents(entities)

        // For each entity, inflate a layout for it
        packedEvents.forEach { event ->
            val top = calculateTop(event)
            val bottom = calculateBottom(event)

            val cellHeight = Math.abs(top - bottom).toInt()
            val cellWidth = Math.abs(event.right - event.left)

            val cell = inflate(context, eventCellId, null)

            cell.setOnClickListener {
                callbacks?.onEventClick(event)
            }

            val params = RelativeLayout.LayoutParams(cellWidth, cellHeight)
            params.leftMargin = event.left
            params.topMargin = top.toInt()

            val eventName = cell.findViewWithTag<TextView>(NAME_TAG)
            if (eventName != null) {
                eventName.text = event.name
            }
            val eventLocation = cell.findViewWithTag<TextView>(LOCATION_TAG)
            if (eventLocation != null) {
                eventLocation.text = event.moreInfo
            }

            eventViewGroup.addView(cell, params)
        }

        ViewCompat.postInvalidateOnAnimation(this@DayView)
    }

    private fun packEvents(entities: Collection<DayViewEvent>) : Collection<DayViewEvent> {
        // The packed events to return
        val packedEvents = mutableListOf<DayViewEvent>()

        val columns = mutableListOf<MutableCollection<DayViewEvent>>()

        var lastEventEnding: Date? = null

        entities.sortedWith(compareBy({it.startTime}, {it.endTime})).forEach { entity ->

            lastEventEnding?.let {
                if (entity.startTime > it) {
                    expandEvents(columns)
                    columns.clear()
                    lastEventEnding = null
                }
            }

            var placed = false

            columns.forEach {
                if (!placed) {
                    if (it.lastOrNull()?.collidesWith(entity) == false) {
                        it.add(entity)
                        placed = true
                    }
                }
            }
            if (!placed) {
                columns.add(mutableListOf(entity))
            }
            if (lastEventEnding === null || entity.endTime > lastEventEnding) {
                lastEventEnding = entity.endTime
            }
        }

        if (columns.isNotEmpty()) {
            expandEvents(columns)
        }

        columns.forEach { packedEvents.addAll(it) }

        return packedEvents
    }

    private fun expandEvents(columns: Collection<Collection<DayViewEvent>>) {
        val layoutLeft = timeColumnWidth + timeColumnPadding
        val layoutWidth = width - (timeColumnWidth + timeColumnPadding * 2)
        val numColumns = columns.size.toDouble()
        var currentColumn = 0.0
        columns.forEach {
            it.forEach {
                val colSpan = expandEvent(it, currentColumn, columns)
                it.left = (layoutLeft + layoutWidth * (currentColumn / numColumns)).toInt()
                val width = ((layoutWidth / numColumns) * colSpan)
                it.right = (it.left + width).toInt()
            }
            currentColumn++
        }
    }

    private fun expandEvent(event: DayViewEvent, currentColumn: Double, columns: Collection<Collection<DayViewEvent>>) : Double {
        var colSpan = 1.0
        columns.drop((currentColumn + 1).toInt()).forEach {
            it.forEach {
                if (it.collidesWith(event)) {
                    return colSpan
                }
            }
            colSpan++
        }
        return colSpan
    }

    private fun DayViewEvent.collidesWith(other: DayViewEvent) : Boolean {
        return this.endTime > other.startTime && this.startTime < other.endTime
    }

    private fun calculateTop(event: DayViewEvent) : Float {
        // Work out top of cell
        val timeFromCalendar = Calendar.getInstance()
        timeFromCalendar.time = event.startTime
        val timeFromHour = timeFromCalendar.get(Calendar.HOUR_OF_DAY)
        val timeFromMinute = timeFromCalendar.get(Calendar.MINUTE)
        val topHourPixels = timeFromHour * hourHeight

        // Make sure we don't divide by 0
        val topMinutePixels: Int = when (timeFromMinute) {
            0 -> {
                0
            }
            else -> {
                (hourHeight / MINUTES_IN_HOUR * timeFromMinute).toInt()
            }
        }
        return topHourPixels + topMinutePixels + timeColumnPadding + textHeight / 2
    }

    private fun calculateBottom(event: DayViewEvent) : Float {
        // Work out bottom of cell
        val timeToCalendar = Calendar.getInstance()
        timeToCalendar.time = event.endTime
        val timeToHour = timeToCalendar.get(Calendar.HOUR_OF_DAY)
        val timeToMinute = timeToCalendar.get(Calendar.MINUTE)
        val bottomHourPixels = timeToHour * hourHeight

        // Make sure we don't divide by 0
        val bottomMinutePixels: Int = when (timeToMinute) {
            0 -> {
                0
            }
            else -> {
                (hourHeight / MINUTES_IN_HOUR * timeToMinute).toInt()
            }
        }
        return bottomHourPixels + bottomMinutePixels + timeColumnPadding + textHeight / 2
    }

    /**
     * Initialize the view by reading the properties from the layout
     * And populating the respective properties in this instance
     */
    init {
        val dayViewAttributes = context.theme.obtainStyledAttributes(attrs, R.styleable.DayView, 0, 0)
        val timeViewAttributes = context.theme.obtainStyledAttributes(attrs, R.styleable.TimeView, 0, 0)
        try {
            // Dimensions
            timeColumnWidth = timeViewAttributes.getDimension(R.styleable.TimeView_timeColumnWidth, 48f)
            timeColumnPadding = timeViewAttributes.getDimension(R.styleable.TimeView_timeColumnPadding, 0.0f)
            hourHeight = timeViewAttributes.getDimension(R.styleable.TimeView_hourHeight, 100f)
            hourSeparatorHeight = timeViewAttributes.getDimension(R.styleable.TimeView_hourSeparatorHeight, 1f)
            textSize = timeViewAttributes.getDimension(R.styleable.TimeView_mainTextSize, 10.0f)

            // Time text
            timeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            timeTextPaint.textSize = textSize
            timeTextPaint.textAlign = Paint.Align.RIGHT
            timeTextPaint.color = timeViewAttributes.getColor(R.styleable.TimeView_timeTextColor, Color.BLACK)

            // Time text height
            val timeTextRect = Rect()
            timeTextPaint.getTextBounds("24:00", 0, "24:00".length, timeTextRect)
            textHeight = timeTextRect.height().toFloat()

            // Time column background
            timeColumnBGPaint = Paint()
            timeColumnBGPaint.color = timeViewAttributes.getColor(R.styleable.TimeView_timeColumnBGColor, Color.GRAY)

            // Hour separator
            hourSeparatorPaint = Paint()
            hourSeparatorPaint.style = Paint.Style.STROKE
            hourSeparatorPaint.strokeWidth = hourSeparatorHeight
            hourSeparatorPaint.color = timeViewAttributes.getColor(R.styleable.TimeView_hourSeparatorColor, Color.BLACK)

            // Now line
            nowLinePaint = Paint()
            nowLinePaint.style = Paint.Style.STROKE
            nowLinePaint.strokeWidth = timeViewAttributes.getDimension(R.styleable.TimeView_nowLineHeight, 10f)
            nowLinePaint.color = timeViewAttributes.getColor(R.styleable.TimeView_nowLineColor, Color.MAGENTA)

            // Event cell id
            eventCellId = dayViewAttributes.getResourceId(R.styleable.DayView_eventCell, -1)
            if (eventCellId == -1)
                throw Exception("You must provide an event cell id")

            // Event column background
            eventColumnBGPaint = Paint()
            eventColumnBGPaint.color = timeViewAttributes.getColor(R.styleable.TimeView_eventColumnBGColor, Color.WHITE)

            // Get the the date format
            is24HourFormat = DateFormat.is24HourFormat(context)
        } finally {
            dayViewAttributes.recycle()
            timeViewAttributes.recycle()
            val viewGroupWidth = (width - (timeColumnWidth + timeColumnPadding)).toInt()
            val viewGroupHeight = (hourHeight * HOURS_IN_DAY + timeColumnPadding).toInt()
            val params = RelativeLayout.LayoutParams(viewGroupWidth, viewGroupHeight)
            addView(eventViewGroup, params)
            setWillNotDraw(false)
        }
    }

    /**
     * Draw the layout to the [canvas]
     */
    override fun draw(canvas: Canvas?) {
        // Get the canvas if it exists, else return
        val theCanvas = canvas ?: return

        // Draw the time column
        drawTimeColumn(theCanvas)

        // Draw the event column and any events
        drawEventsColumn(theCanvas)

        // Draw the events
        super.draw(theCanvas)

        if (isToday) {
            // Draw the now line
            drawNowLine(theCanvas)
        }
    }

    /**
     * Draws the time column to the [canvas]
     */
    private fun drawTimeColumn(canvas: Canvas) {
        // Draw time column
        canvas.drawRect(0f, 0f, timeColumnWidth + timeColumnPadding, height.toFloat(), timeColumnBGPaint)

        // Draw the times
        for (i in 0 until HOURS_IN_DAY) {
            val top = timeColumnPadding + hourHeight * i
            val time = interpretTime(i)

            if (top < height)
                canvas.drawText(time, timeColumnWidth, top + textHeight, timeTextPaint)
        }
    }

    /**
     * Interpret the passed [hour] to a formatted string
     */
    private fun interpretTime(hour: Int): String {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, 0)

        return try {
            val dateFormat = when(is24HourFormat) {
                true -> {
                    SimpleDateFormat("HH:mm", Locale.getDefault())
                } false -> {
                    SimpleDateFormat("h a", Locale.getDefault())
                }
            }
            dateFormat.format(calendar.time)
        } catch (ex: Exception) {
            return ""
        }
    }

    /**
     * Draws the events contained in the events collection to the [canvas]
     */
    private fun drawEventsColumn(canvas: Canvas) {
        // Draw event column
        canvas.drawRect(timeColumnWidth + timeColumnPadding, 0f,
                width.toFloat(),
                height.toFloat(),
                eventColumnBGPaint)

        // Work out the total line count
        val lineCount = ((height / hourHeight + 1)).toInt()
        val hourLines = FloatArray(lineCount * 4)

        // Draw the lines
        var drawn = 0
        for (hour in 0 until HOURS_IN_DAY) {
            // Work out where top of line is
            val top = timeColumnPadding +
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
    }

    /**
     * Draw the now time line to the [canvas]
     */
    private fun drawNowLine(canvas: Canvas) {
        val now = Calendar.getInstance()
        val nowY = (now.get(Calendar.HOUR_OF_DAY) * hourHeight) + ((hourHeight / MINUTES_IN_HOUR) *
                now.get(Calendar.MINUTE)) + (timeColumnPadding + textHeight / 2)
        canvas.drawLine(timeColumnWidth + timeColumnPadding, nowY,
                width.toFloat() - timeColumnPadding, nowY, nowLinePaint)
    }
}
