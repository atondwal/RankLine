package com.example.rankline;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class RankLineView extends View {

    // --- Constants ---
    private static final double Z_MAX = 10_000.0;
    private static final float DEAD_ZONE = 48f;
    private static final int OVERVIEW_HEIGHT = 40;
    private static final int OVERVIEW_TOP = 8;
    private static final int THUMB_SIZE = 56;
    private static final int LINE_Y_OFFSET = 180; // distance from bottom of overview bar
    private static final float LONG_PRESS_MS = 400f;
    private static final float CANCEL_THRESHOLD = 20f; // dp for reposition cancel

    // --- Coordinate state ---
    private double center = 0.0;
    private double currentZoom = 1.0;

    // --- Data ---
    private final List<RankedItem> items = new ArrayList<>();
    private RankedItem draggingItem = null;      // item being placed from inbox
    private RankedItem repositioningItem = null;  // item being repositioned
    private double repositionOriginal = 0;

    // --- Bottom card (shared by inbox + browse) ---
    private RankedItem inboxItem = null;
    private boolean inboxDragging = false;
    private boolean inboxTouching = false; // touched inbox but not yet dragging up
    private final RectF cardRect = new RectF();
    private static final int CARD_MARGIN = 16;
    private static final int CARD_INFO_BAR = 56;
    private int inboxQueueSize = 0;

    // Browse mode state
    private RankedItem browseItem = null;
    private int browseIndex = -1;
    private int browseTotal = 0;
    private boolean browseTouching = false;

    // --- Touch state ---
    private float touchDownX, touchDownY;
    private float lastTouchX, lastTouchY;
    private boolean isBrowsing = false;
    private boolean isPlacing = false;
    private boolean isRepositioning = false;
    private long touchDownTime;
    private boolean longPressTriggered = false;
    private RankedItem touchDownHitItem = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;

    // --- Paints ---
    private final Paint overviewBgPaint = new Paint();
    private final Paint overviewWindowPaint = new Paint();
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clusterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clusterTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inboxBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inboxTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // --- Listener ---
    public interface Listener {
        void onItemPlaced(RankedItem item);
        void onItemRepositioned(RankedItem item);
        void onInboxConsumed();
        void onItemTapped(RankedItem item);
        void onCardSkipForward();
        void onCardSkipBackward();
        void onBrowseClose();
        void onBrowseRepositioned(RankedItem item);
        void onBrowseRemoveToInbox(RankedItem item);
    }
    private Listener listener;

    public RankLineView(Context context) {
        super(context);
        init();
    }

    public RankLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RankLineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        overviewBgPaint.setColor(0xFF2A2A2A);
        overviewWindowPaint.setColor(0x441976D2);
        overviewWindowPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(0xFFCCCCCC);
        linePaint.setStrokeWidth(2f);

        tickPaint.setColor(0xFF888888);
        tickPaint.setStrokeWidth(1f);

        tickLabelPaint.setColor(0xFFAAAAAA);
        tickLabelPaint.setTextSize(24f);
        tickLabelPaint.setTextAlign(Paint.Align.CENTER);

        cursorPaint.setColor(0xFFFF6F00);
        cursorPaint.setStrokeWidth(3f);
        cursorPaint.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));

        cursorLabelPaint.setColor(0xFFFF6F00);
        cursorLabelPaint.setTextSize(28f);
        cursorLabelPaint.setTextAlign(Paint.Align.CENTER);
        cursorLabelPaint.setTypeface(Typeface.MONOSPACE);

        thumbBorderPaint.setColor(0xFFFFFFFF);
        thumbBorderPaint.setStyle(Paint.Style.STROKE);
        thumbBorderPaint.setStrokeWidth(3f);

        clusterPaint.setColor(0xFF1976D2);
        clusterPaint.setStyle(Paint.Style.FILL);

        clusterTextPaint.setColor(0xFFFFFFFF);
        clusterTextPaint.setTextSize(22f);
        clusterTextPaint.setTextAlign(Paint.Align.CENTER);
        clusterTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        inboxBgPaint.setColor(0xFF333333);
        inboxBgPaint.setStyle(Paint.Style.FILL);

        inboxTextPaint.setColor(0xFFCCCCCC);
        inboxTextPaint.setTextSize(28f);
        inboxTextPaint.setTextAlign(Paint.Align.CENTER);

        ghostPaint.setAlpha(128);

        labelBgPaint.setColor(0xCC000000);
        labelBgPaint.setStyle(Paint.Style.FILL);
    }

    public void setListener(Listener l) { this.listener = l; }

    public List<RankedItem> getItems() { return items; }

    public void setItems(List<RankedItem> list) {
        items.clear();
        items.addAll(list);
        invalidate();
    }

    public void addItem(RankedItem item) {
        items.add(item);
        invalidate();
    }

    public void setInboxItem(RankedItem item) {
        this.inboxItem = item;
        invalidate();
    }

    public RankedItem getInboxItem() { return inboxItem; }

    public void setInboxQueueSize(int size) {
        this.inboxQueueSize = size;
        invalidate();
    }

    public void setBrowseItem(RankedItem item, int index, int total) {
        this.browseItem = item;
        this.browseIndex = index;
        this.browseTotal = total;
        invalidate();
    }

    public void clearBrowse() {
        this.browseItem = null;
        this.browseIndex = -1;
        this.browseTotal = 0;
        invalidate();
    }

    public boolean isBrowseMode() { return browseItem != null; }

    /** Bind a preview drawable so animated GIFs get an invalidate callback. */
    public void bindPreviewDrawable(Drawable d) {
        if (d != null) {
            d.setCallback(this);
            if (d instanceof Animatable) {
                ((Animatable) d).start();
            }
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        // Allow any preview drawable to trigger invalidation
        RankedItem card = cardItem();
        if (card != null && card.preview == who) return true;
        return super.verifyDrawable(who);
    }

    private boolean hasCard() {
        return inboxItem != null || browseItem != null;
    }

    private RankedItem cardItem() {
        return browseItem != null ? browseItem : inboxItem;
    }

    public double getCenter() { return center; }
    public double getCurrentZoom() { return currentZoom; }

    public void setViewState(double center, double zoom) {
        this.center = center;
        this.currentZoom = zoom;
        invalidate();
    }

    // --- Zoom function ---
    // Maps finger Y to zoom level. Full screen height below line is the zoom zone,
    // regardless of card visibility (finger can be over the card during drag).
    private double computeZoom(float y) {
        int h = getHeight();
        float lineY = getLineY();
        float zoneStart = lineY + DEAD_ZONE;
        float zoneEnd = h;
        if (zoneEnd <= zoneStart) return 1.0;
        float t = Math.max(0, Math.min(1, (y - zoneStart) / (zoneEnd - zoneStart)));
        return Math.pow(Z_MAX, t);
    }

    private double visibleWidth() {
        return 2.0 / currentZoom;
    }

    private float rangeToScreenX(double pos) {
        double left = center - visibleWidth() / 2.0;
        double frac = (pos - left) / visibleWidth();
        return (float)(frac * getWidth());
    }

    private double screenXToRange(float sx) {
        double left = center - visibleWidth() / 2.0;
        return left + (sx / getWidth()) * visibleWidth();
    }

    private float getLineY() {
        return OVERVIEW_TOP + OVERVIEW_HEIGHT + LINE_Y_OFFSET;
    }

    private int getCardTotalHeight() {
        if (!hasCard()) return 0;
        int h = getHeight();
        float lineY = getLineY();
        float cardTop = lineY + 60;
        return (int)(h - cardTop);
    }

    private double viewCenterRange() {
        return center;
    }

    // --- Clamp center ---
    private void clampCenter() {
        double hw = visibleWidth() / 2.0;
        if (hw >= 1.0) {
            center = 0;
        } else {
            center = Math.max(-1.0 + hw, Math.min(1.0 - hw, center));
        }
    }

    // --- Touch handling ---
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = x;
                touchDownY = y;
                lastTouchX = x;
                lastTouchY = y;
                touchDownTime = System.currentTimeMillis();
                longPressTriggered = false;

                // Check card hit (inbox or browse)
                if (hasCard() && cardRect.contains(x, y)) {
                    if (browseItem != null) {
                        browseTouching = true;
                    } else {
                        inboxTouching = true;
                    }
                    cancelLongPressTimer();
                    return true;
                }

                // Check item hit for tap / long-press reposition
                RankedItem hit = hitTestItem(x, y);
                touchDownHitItem = hit;
                if (hit != null) {
                    final RankedItem target = hit;
                    longPressRunnable = () -> {
                        if (!longPressTriggered) {
                            longPressTriggered = true;
                            isRepositioning = true;
                            repositioningItem = target;
                            repositionOriginal = target.position;
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            invalidate();
                        }
                    };
                    handler.postDelayed(longPressRunnable, (long) LONG_PRESS_MS);
                }

                // Browsing mode
                isBrowsing = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastTouchX;
                float dy = y - lastTouchY;

                // Cancel long press if moved too much
                if (!longPressTriggered && (Math.abs(x - touchDownX) > 15 || Math.abs(y - touchDownY) > 15)) {
                    cancelLongPressTimer();
                }

                // Browse card touch: horizontal swipe or drag-up to reposition
                if (browseTouching && !isRepositioning) {
                    float totalDy = touchDownY - y;
                    if (totalDy > 40) {
                        // Drag up — reposition this item
                        browseTouching = false;
                        isRepositioning = true;
                        repositioningItem = browseItem;
                        repositionOriginal = browseItem.position;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }

                // Inbox touch: decide drag-up vs horizontal swipe
                if (inboxTouching && !isPlacing) {
                    float totalDy = touchDownY - y; // positive = dragging up
                    if (totalDy > 40) {
                        // Dragging up — enter placement mode
                        inboxTouching = false;
                        inboxDragging = true;
                        isPlacing = true;
                        draggingItem = inboxItem;
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }

                if (isPlacing || isRepositioning) {
                    // Zoom-scrub: Y controls zoom, X scrolls
                    currentZoom = computeZoom(y);
                    double vw = visibleWidth();
                    double dxRange = -(dx / getWidth()) * vw * 2.5;
                    center += dxRange;
                    clampCenter();
                    if (isRepositioning && repositioningItem != null) {
                        repositioningItem.position = viewCenterRange();
                        repositioningItem.position = Math.max(-0.9999, Math.min(0.9999, repositioningItem.position));
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }

                if (isBrowsing && !longPressTriggered) {
                    // Zoom-scrub browsing
                    currentZoom = computeZoom(y);
                    double vw = visibleWidth();
                    double dxRange = -(dx / getWidth()) * vw * 2.5;
                    center += dxRange;
                    clampCenter();
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }

                lastTouchX = x;
                lastTouchY = y;
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelLongPressTimer();

                // Browse card swipe
                if (browseTouching) {
                    browseTouching = false;
                    float totalDx = x - touchDownX;
                    float totalDy = y - touchDownY; // positive = swipe down
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                        if (totalDy > 60 && Math.abs(totalDy) > Math.abs(totalDx)) {
                            // Swipe down — remove from line, return to inbox
                            RankedItem item = browseItem;
                            items.remove(item);
                            listener.onBrowseRemoveToInbox(item);
                        } else if (Math.abs(totalDx) > 60) {
                            if (totalDx < 0) listener.onCardSkipForward();
                            else listener.onCardSkipBackward();
                        } else if (Math.abs(totalDx) < 15 && Math.abs(totalDy) < 15) {
                            listener.onBrowseClose();
                        }
                    }
                    invalidate();
                    return true;
                }

                // Inbox swipe to skip
                if (inboxTouching) {
                    inboxTouching = false;
                    float totalDx = x - touchDownX;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(totalDx) > 60) {
                        if (listener != null) {
                            if (totalDx < 0) listener.onCardSkipForward();
                            else listener.onCardSkipBackward();
                        }
                    }
                    invalidate();
                    return true;
                }

                if (isPlacing && draggingItem != null) {
                    // Place item at center
                    draggingItem.position = viewCenterRange();
                    draggingItem.position = Math.max(-0.9999, Math.min(0.9999, draggingItem.position));
                    items.add(draggingItem);
                    if (listener != null) {
                        listener.onItemPlaced(draggingItem);
                        listener.onInboxConsumed(); // sets next inboxItem or null
                    }
                    draggingItem = null;
                    isPlacing = false;
                    isBrowsing = false;
                    inboxDragging = false;
                    invalidate();
                    return true;
                }

                if (isRepositioning && repositioningItem != null) {
                    double moved = Math.abs(repositioningItem.position - repositionOriginal);
                    float dpMoved = (float)(moved / visibleWidth() * getWidth());
                    if (dpMoved < CANCEL_THRESHOLD) {
                        repositioningItem.position = repositionOriginal;
                    } else if (listener != null) {
                        listener.onItemRepositioned(repositioningItem);
                        if (browseItem != null) {
                            listener.onBrowseRepositioned(repositioningItem);
                        }
                    }
                    repositioningItem = null;
                    isRepositioning = false;
                    isBrowsing = false;
                    invalidate();
                    return true;
                }

                // Detect short tap on item
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && touchDownHitItem != null
                        && !longPressTriggered
                        && (System.currentTimeMillis() - touchDownTime) < 300
                        && Math.abs(x - touchDownX) < 20
                        && Math.abs(y - touchDownY) < 20) {
                    if (listener != null) {
                        listener.onItemTapped(touchDownHitItem);
                    }
                }
                touchDownHitItem = null;
                isBrowsing = false;
                break;
        }
        return true;
    }

    private void cancelLongPressTimer() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private RankedItem hitTestItem(float x, float y) {
        float lineY = getLineY();
        float thumbTop = lineY - THUMB_SIZE - 12;
        float thumbBot = lineY - 4;
        if (y < thumbTop || y > thumbBot) return null;

        for (RankedItem item : items) {
            float ix = rangeToScreenX(item.position);
            if (Math.abs(x - ix) < THUMB_SIZE / 2f + 8) {
                return item;
            }
        }
        return null;
    }

    // --- Drawing ---
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        drawOverviewBar(canvas, w);
        drawZoomedLine(canvas, w);
        drawItems(canvas, w);

        if (isPlacing || isRepositioning) {
            drawPlacementCursor(canvas, w, h);
        }

        drawCard(canvas, w, h);
    }

    // --- Overview bar ---
    private void drawOverviewBar(Canvas canvas, int w) {
        float top = OVERVIEW_TOP;
        float bot = top + OVERVIEW_HEIGHT;
        float left = 24;
        float right = w - 24;
        float barWidth = right - left;

        // Background
        canvas.drawRoundRect(left, top, right, bot, 8, 8, overviewBgPaint);

        // Visible window indicator
        double vw = visibleWidth();
        double viewLeft = center - vw / 2.0;
        double viewRight = center + vw / 2.0;
        float wl = left + (float)((viewLeft + 1.0) / 2.0) * barWidth;
        float wr = left + (float)((viewRight + 1.0) / 2.0) * barWidth;
        wl = Math.max(left, Math.min(right, wl));
        wr = Math.max(left, Math.min(right, wr));
        canvas.drawRoundRect(wl, top, wr, bot, 4, 4, overviewWindowPaint);

        // Item dots
        for (RankedItem item : items) {
            if (item == repositioningItem) continue;
            float dx = left + (float)((item.position + 1.0) / 2.0) * barWidth;
            dotPaint.setColor(colorForItem(item));
            canvas.drawCircle(dx, (top + bot) / 2f, 5, dotPaint);
        }

        // Repositioning item dot (highlighted)
        if (repositioningItem != null) {
            float dx = left + (float)((repositioningItem.position + 1.0) / 2.0) * barWidth;
            dotPaint.setColor(0xFFFF6F00);
            canvas.drawCircle(dx, (top + bot) / 2f, 7, dotPaint);
        }
    }

    // --- Zoomed number line ---
    private void drawZoomedLine(Canvas canvas, int w) {
        float lineY = getLineY();

        // Main line
        canvas.drawLine(0, lineY, w, lineY, linePaint);

        // Adaptive tick marks
        double vw = visibleWidth();
        double tickSpacing = niceNumber(vw / 8.0, true);
        double tickStart = Math.ceil((center - vw / 2.0) / tickSpacing) * tickSpacing;

        double tickEnd = center + vw / 2.0;
        for (double t = tickStart; t <= tickEnd; t += tickSpacing) {
            if (t < -1.0 || t > 1.0) continue;
            float sx = rangeToScreenX(t);
            canvas.drawLine(sx, lineY - 12, sx, lineY + 12, tickPaint);
            String label = formatTickLabel(t, tickSpacing);
            canvas.drawText(label, sx, lineY + 36, tickLabelPaint);
            if (tickSpacing < 1e-15) break; // safety: avoid infinite loop at fp limits
        }

        // Sub-ticks
        double subSpacing = tickSpacing / 5.0;
        if (subSpacing >= 1e-15) {
            double subStart = Math.ceil((center - vw / 2.0) / subSpacing) * subSpacing;
            for (double t = subStart; t <= tickEnd; t += subSpacing) {
                if (t < -1.0 || t > 1.0) continue;
                float sx = rangeToScreenX(t);
                canvas.drawLine(sx, lineY - 5, sx, lineY + 5, tickPaint);
                if (subSpacing < 1e-15) break;
            }
        }

        // -1 and 1 boundary markers
        float negOne = rangeToScreenX(-1.0);
        float posOne = rangeToScreenX(1.0);
        linePaint.setStrokeWidth(4f);
        if (negOne >= 0 && negOne <= w) {
            canvas.drawLine(negOne, lineY - 20, negOne, lineY + 20, linePaint);
        }
        if (posOne >= 0 && posOne <= w) {
            canvas.drawLine(posOne, lineY - 20, posOne, lineY + 20, linePaint);
        }
        linePaint.setStrokeWidth(2f);
    }

    // --- Items ---
    private void drawItems(Canvas canvas, int w) {
        float lineY = getLineY();
        double vw = visibleWidth();
        double viewLeft = center - vw / 2.0;
        double viewRight = center + vw / 2.0;

        // Cluster items that are close together on screen
        float clusterThreshold = THUMB_SIZE * 0.8f;
        List<List<RankedItem>> clusters = new ArrayList<>();

        for (RankedItem item : items) {
            if (item == repositioningItem && isRepositioning) continue;
            if (item.position < viewLeft - vw * 0.1 || item.position > viewRight + vw * 0.1) continue;

            float sx = rangeToScreenX(item.position);
            boolean merged = false;
            for (List<RankedItem> cluster : clusters) {
                float clusterX = rangeToScreenX(cluster.get(0).position);
                if (Math.abs(sx - clusterX) < clusterThreshold) {
                    cluster.add(item);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                List<RankedItem> newCluster = new ArrayList<>();
                newCluster.add(item);
                clusters.add(newCluster);
            }
        }

        for (List<RankedItem> cluster : clusters) {
            RankedItem representative = cluster.get(0);
            float sx = rangeToScreenX(representative.position);
            float thumbLeft = sx - THUMB_SIZE / 2f;
            float thumbTop = lineY - THUMB_SIZE - 12;

            if (cluster.size() == 1) {
                drawThumbnail(canvas, representative, thumbLeft, thumbTop, THUMB_SIZE, 255);
            } else {
                // Draw representative + badge
                drawThumbnail(canvas, representative, thumbLeft, thumbTop, THUMB_SIZE, 255);
                // Count badge
                float badgeX = sx + THUMB_SIZE / 2f - 8;
                float badgeY = thumbTop + 4;
                canvas.drawCircle(badgeX, badgeY, 14, clusterPaint);
                canvas.drawText(String.valueOf(cluster.size()), badgeX, badgeY + 7, clusterTextPaint);
            }
        }

        // Draw repositioning item (lifted)
        if (isRepositioning && repositioningItem != null) {
            float sx = rangeToScreenX(repositioningItem.position);
            float thumbLeft = sx - THUMB_SIZE * 0.7f;
            float thumbTop = lineY - THUMB_SIZE * 1.4f - 20;
            int size = (int)(THUMB_SIZE * 1.3f);
            drawThumbnail(canvas, repositioningItem, thumbLeft, thumbTop, size, 220);
        }
    }

    private void drawThumbnail(Canvas canvas, RankedItem item, float left, float top, int size, int alpha) {
        RectF rect = new RectF(left, top, left + size, top + size);
        if (item.thumbnail != null) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setAlpha(alpha);
            canvas.drawBitmap(item.thumbnail, null, rect, p);
        } else {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(colorForItem(item));
            bg.setAlpha(alpha);
            canvas.drawRoundRect(rect, 6, 6, bg);

            if (item.label != null && !item.label.isEmpty()) {
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xFFFFFFFF);
                tp.setAlpha(alpha);
                tp.setTextSize(16f);
                tp.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(item.label.substring(0, Math.min(4, item.label.length())),
                        left + size / 2f, top + size / 2f + 6, tp);
            }
        }
        // Border
        thumbBorderPaint.setAlpha(alpha);
        canvas.drawRoundRect(rect, 6, 6, thumbBorderPaint);
    }

    // --- Placement cursor ---
    private void drawPlacementCursor(Canvas canvas, int w, int h) {
        float lineY = getLineY();
        float cx = w / 2f;
        double rangeVal = viewCenterRange();

        // Dashed vertical line
        canvas.drawLine(cx, OVERVIEW_TOP + OVERVIEW_HEIGHT, cx, lineY + 50, cursorPaint);

        // Position label
        int precision = (int) Math.max(1, Math.log10(currentZoom) + 1);
        String valStr = String.format("%." + precision + "f", rangeVal);

        // Label background
        float textWidth = cursorLabelPaint.measureText(valStr);
        float labelY = lineY + 70;
        canvas.drawRoundRect(cx - textWidth / 2f - 8, labelY - 24, cx + textWidth / 2f + 8,
                labelY + 8, 6, 6, labelBgPaint);
        canvas.drawText(valStr, cx, labelY, cursorLabelPaint);

        // Ghost thumbnail for placing item
        RankedItem ghost = isPlacing ? draggingItem : repositioningItem;
        if (ghost != null) {
            float thumbLeft = cx - THUMB_SIZE / 2f;
            float thumbTop = lineY - THUMB_SIZE - 12;
            drawThumbnail(canvas, ghost, thumbLeft, thumbTop, THUMB_SIZE, 128);
        }
    }

    // --- Bottom card (shared: inbox + browse) ---
    private void drawCard(Canvas canvas, int w, int h) {
        RankedItem item = cardItem();
        if (item == null && !inboxDragging) return;

        boolean isBrowse = browseItem != null;
        float lineY = getLineY();
        float cTop = lineY + 60;
        float cLeft = CARD_MARGIN;
        float cRight = w - CARD_MARGIN;
        float cBot = h - CARD_MARGIN;
        cardRect.set(cLeft, cTop, cRight, cBot);

        int alpha = inboxDragging ? 80 : 255;
        inboxBgPaint.setAlpha(alpha);
        canvas.drawRoundRect(cardRect, 16, 16, inboxBgPaint);

        if (item == null) return;

        float infoTop = cBot - CARD_INFO_BAR;

        // --- Image area ---
        float imgPad = 12;
        float imgLeft = cLeft + imgPad;
        float imgRight = cRight - imgPad;
        float imgTop = cTop + imgPad;
        float imgBot = infoTop - 4;
        float imgW = imgRight - imgLeft;
        float imgH = imgBot - imgTop;

        Drawable previewDrawable = item.preview;
        if (previewDrawable != null) {
            float bmpW = previewDrawable.getIntrinsicWidth();
            float bmpH = previewDrawable.getIntrinsicHeight();
            if (bmpW <= 0) bmpW = imgW;
            if (bmpH <= 0) bmpH = imgH;
            float scale = Math.min(imgW / bmpW, imgH / bmpH);
            float drawW = bmpW * scale;
            float drawH = bmpH * scale;
            float cx = imgLeft + imgW / 2f;
            float cy = imgTop + imgH / 2f;
            previewDrawable.setAlpha(alpha);
            previewDrawable.setBounds(
                    (int)(cx - drawW / 2f), (int)(cy - drawH / 2f),
                    (int)(cx + drawW / 2f), (int)(cy + drawH / 2f));
            previewDrawable.draw(canvas);
        } else if (item.thumbnail != null) {
            float bmpW = item.thumbnail.getWidth();
            float bmpH = item.thumbnail.getHeight();
            float scale = Math.min(imgW / bmpW, imgH / bmpH);
            float drawW = bmpW * scale;
            float drawH = bmpH * scale;
            float cx = imgLeft + imgW / 2f;
            float cy = imgTop + imgH / 2f;
            RectF dst = new RectF(cx - drawW / 2f, cy - drawH / 2f,
                    cx + drawW / 2f, cy + drawH / 2f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(alpha);
            canvas.drawBitmap(item.thumbnail, null, dst, p);
        } else {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(colorForItem(item));
            bg.setAlpha(alpha);
            float sz = Math.min(imgW, imgH) * 0.5f;
            float cx = imgLeft + imgW / 2f;
            float cy = imgTop + imgH / 2f;
            canvas.drawRoundRect(cx - sz / 2f, cy - sz / 2f, cx + sz / 2f, cy + sz / 2f, 12, 12, bg);
        }

        // --- Info bar ---
        float textY = infoTop + CARD_INFO_BAR / 2f + 8;
        Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        infoPaint.setTextSize(22f);

        if (isBrowse) {
            // Left: position value
            cursorLabelPaint.setTextSize(24f);
            cursorLabelPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(String.format("%.6f", browseItem.position), cLeft + 16, textY, cursorLabelPaint);
            cursorLabelPaint.setTextSize(28f);
            cursorLabelPaint.setTextAlign(Paint.Align.CENTER);

            // Right: #N of M + nav arrows
            infoPaint.setColor(0xFFAAAAAA);
            infoPaint.setTextAlign(Paint.Align.RIGHT);
            String nav = (browseIndex > 0 ? "\u276E  " : "    ")
                    + "#" + (browseIndex + 1) + " of " + browseTotal
                    + (browseIndex < browseTotal - 1 ? "  \u276F" : "");
            canvas.drawText(nav, cRight - 16, textY, infoPaint);

            // Label if present
            if (browseItem.label != null && !browseItem.label.isEmpty()) {
                infoPaint.setColor(0xFFCCCCCC);
                infoPaint.setTextAlign(Paint.Align.CENTER);
                infoPaint.setTextSize(20f);
                canvas.drawText(browseItem.label, (cLeft + cRight) / 2f, cTop + 28, infoPaint);
            }
        } else {
            // Inbox mode
            // Left: label
            String label = item.label != null && !item.label.isEmpty()
                    ? item.label : "Drag up to place";
            inboxTextPaint.setTextAlign(Paint.Align.LEFT);
            inboxTextPaint.setTextSize(24f);
            inboxTextPaint.setAlpha(alpha);
            canvas.drawText(label, cLeft + 16, textY, inboxTextPaint);

            // Right: queue count
            if (inboxQueueSize > 1 && !inboxDragging) {
                infoPaint.setColor(0xFF888888);
                infoPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText("\u276E  " + inboxQueueSize + " in queue  \u276F",
                        cRight - 16, textY, infoPaint);
            }

            // Top hint
            if (!inboxDragging) {
                Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                hintPaint.setColor(0x55FFFFFF);
                hintPaint.setTextSize(28f);
                hintPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("\u2191 drag up to place", (cLeft + cRight) / 2f, cTop + 30, hintPaint);
            }

            // Reset
            inboxTextPaint.setTextAlign(Paint.Align.CENTER);
            inboxTextPaint.setTextSize(28f);
        }
    }

    // --- Utilities ---
    private int colorForItem(RankedItem item) {
        int hash = item.id.hashCode();
        float hue = ((hash & 0x7FFFFFFF) % 360);
        return Color.HSVToColor(new float[]{hue, 0.6f, 0.8f});
    }

    private static double niceNumber(double range, boolean round) {
        double exp = Math.floor(Math.log10(range));
        double frac = range / Math.pow(10, exp);
        double nice;
        if (round) {
            if (frac < 1.5) nice = 1;
            else if (frac < 3) nice = 2;
            else if (frac < 7) nice = 5;
            else nice = 10;
        } else {
            if (frac <= 1) nice = 1;
            else if (frac <= 2) nice = 2;
            else if (frac <= 5) nice = 5;
            else nice = 10;
        }
        return nice * Math.pow(10, exp);
    }

    private String formatTickLabel(double value, double spacing) {
        if (spacing >= 0.1) return String.format("%.1f", value);
        int digits = (int) Math.max(1, -Math.floor(Math.log10(spacing)) + 1);
        return String.format("%." + digits + "f", value);
    }
}
