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

    // Current card preview (loaded fresh each time a card is shown)
    private Drawable cardPreview = null;

    // Browse mode state
    private RankedItem browseItem = null;
    private int browseIndex = -1;
    private int browseTotal = 0;
    private boolean browseTouching = false;

    // Video state
    private boolean videoActive = false;
    private boolean videoMuted = true;

    // Scrub preview state
    private RankedItem scrubItem = null;
    private boolean scrubActive = false;

    // Undo pill state
    private boolean undoVisible = false;
    private final RectF undoPillRect = new RectF();

    // Mode bar state
    private int modeBarInboxCount = 0;
    private int modeBarBrowseCount = 0;
    private final RectF modeBarRect = new RectF();
    private final RectF modeBarInboxPill = new RectF();
    private final RectF modeBarBrowsePill = new RectF();
    private static final int MODE_BAR_HEIGHT = 72;
    private static final int MODE_BAR_MARGIN = 12;

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
        void onItemRepositioned(RankedItem item, double previousPosition);
        void onInboxConsumed();
        void onItemTapped(RankedItem item);
        void onCardSkipForward();
        void onCardSkipBackward();
        void onBrowseClose();
        void onBrowseRepositioned(RankedItem item);
        void onBrowseRemoveToInbox(RankedItem item);
        void onModeBarInboxTapped();
        void onModeBarBrowseTapped();
        void onVideoMuteToggle();
        void onScrubItemChanged(RankedItem item);
        void onUndoTapped();
        void onBrowseDeleteRequested(RankedItem item);
        void onClusterTapped(RankedItem item, double clusterCenter, double clusterSpan);
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
        if (item == null) cardPreview = null;
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
        cardPreview = null;
        invalidate();
    }

    public boolean isBrowseMode() { return browseItem != null; }

    public RankedItem getScrubItem() { return scrubItem; }

    public void setUndoVisible(boolean visible) {
        this.undoVisible = visible;
        invalidate();
    }

    public void setVideoActive(boolean active, boolean muted) {
        this.videoActive = active;
        this.videoMuted = muted;
        invalidate();
    }

    /** Returns the card image area bounds. Returns false if no card visible. */
    public boolean getCardImageRect(RectF out) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || !hasCard()) return false;
        float lineY = getLineY();
        float cTop = lineY + 60;
        float cBot = h - CARD_MARGIN;
        float cLeft = CARD_MARGIN;
        float cRight = w - CARD_MARGIN;
        float infoTop = cBot - CARD_INFO_BAR;
        float imgPad = 12;
        out.set(cLeft + imgPad, cTop + imgPad, cRight - imgPad, infoTop - 4);
        return true;
    }

    public void setModeBarCounts(int inbox, int browse) {
        this.modeBarInboxCount = inbox;
        this.modeBarBrowseCount = browse;
        invalidate();
    }

    /** Bind a preview drawable so animated GIFs get an invalidate callback. */
    public void bindPreviewDrawable(Drawable d) {
        cardPreview = d;
        if (d != null) {
            d.setCallback(this);
            if (d instanceof Animatable) {
                ((Animatable) d).start();
            }
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        if (cardPreview == who) return true;
        return super.verifyDrawable(who);
    }

    private boolean hasCard() {
        return inboxItem != null || browseItem != null || scrubItem != null;
    }

    private RankedItem cardItem() {
        if (browseItem != null) return browseItem;
        if (scrubItem != null) return scrubItem;
        return inboxItem;
    }

    public double getCenter() { return center; }
    public double getCurrentZoom() { return currentZoom; }

    public void setViewState(double center, double zoom) {
        this.center = center;
        this.currentZoom = zoom;
        invalidate();
    }

    // --- Zoom function ---
    // Maps finger Y to zoom level using 1/(1-t)^4.
    // Asymptotically infinite at the bottom of the screen.
    // At 90% down: ~10,000x. Last 10% gives 8+ more orders of magnitude.
    // Capped at 1e12 to stay within double precision.
    private double computeZoom(float y) {
        int h = getHeight();
        float lineY = getLineY();
        float zoneStart = lineY + DEAD_ZONE;
        float zoneEnd = h;
        if (zoneEnd <= zoneStart) return 1.0;
        float t = Math.max(0, Math.min(1, (y - zoneStart) / (zoneEnd - zoneStart)));
        double denom = 1.0 - t;
        if (denom < 1e-3) denom = 1e-3; // cap at 1e12
        double d2 = denom * denom;
        return 1.0 / (d2 * d2);
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
            case MotionEvent.ACTION_POINTER_DOWN:
                // Second finger tap cancels repositioning
                if (isRepositioning && repositioningItem != null) {
                    repositioningItem.position = repositionOriginal;
                    repositioningItem = null;
                    isRepositioning = false;
                    isBrowsing = false;
                    scrubItem = null;
                    cardPreview = null;
                    performHapticFeedback(HapticFeedbackConstants.REJECT);
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_DOWN:
                // Clear scrub preview from previous interaction
                if (scrubItem != null) {
                    scrubItem = null;
                    scrubActive = false;
                    cardPreview = null;
                    if (listener != null) listener.onScrubItemChanged(null);
                }

                touchDownX = x;
                touchDownY = y;
                lastTouchX = x;
                lastTouchY = y;
                touchDownTime = System.currentTimeMillis();
                longPressTriggered = false;

                // Check undo pill hit
                if (undoVisible && undoPillRect.contains(x, y)) {
                    if (listener != null) listener.onUndoTapped();
                    return true;
                }

                // Check mode bar pill hit
                if (!hasCard() && hasModeBar()) {
                    if (modeBarInboxPill.contains(x, y) && modeBarInboxCount > 0) {
                        if (listener != null) listener.onModeBarInboxTapped();
                        return true;
                    }
                    if (modeBarBrowsePill.contains(x, y) && modeBarBrowseCount > 0) {
                        if (listener != null) listener.onModeBarBrowseTapped();
                        return true;
                    }
                }

                // Check card hit (inbox or browse)
                if (hasCard() && cardRect.contains(x, y)) {
                    if (browseItem != null) {
                        browseTouching = true;
                        // Start long-press timer for delete
                        final RankedItem browseTarget = browseItem;
                        longPressRunnable = () -> {
                            if (browseTouching && !longPressTriggered) {
                                longPressTriggered = true;
                                browseTouching = false;
                                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                if (listener != null) listener.onBrowseDeleteRequested(browseTarget);
                            }
                        };
                        handler.postDelayed(longPressRunnable, (long) LONG_PRESS_MS);
                    } else {
                        inboxTouching = true;
                    }
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

                // Browsing mode (only if not starting on a thumbnail)
                if (touchDownHitItem == null) {
                    isBrowsing = true;
                }
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
                    float totalDx = Math.abs(x - touchDownX);
                    if (totalDy > 120 && totalDy > totalDx * 2.5f) {
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

                    // Neighbor preview during placement/reposition
                    RankedItem exclude = isRepositioning ? repositioningItem : null;
                    RankedItem nearest = findNearestItemToCenter(exclude);
                    if (nearest != scrubItem) {
                        scrubItem = nearest;
                        cardPreview = null;
                        if (listener != null) listener.onScrubItemChanged(nearest);
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

                    // Scrub preview: show nearest item when no inbox/browse card
                    if (!scrubActive && (Math.abs(x - touchDownX) > 15 || Math.abs(y - touchDownY) > 15)) {
                        scrubActive = true;
                    }
                    if (scrubActive && inboxItem == null && browseItem == null) {
                        RankedItem nearest = findNearestItemToCenter();
                        if (nearest != scrubItem) {
                            scrubItem = nearest;
                            cardPreview = null;
                            if (listener != null) listener.onScrubItemChanged(nearest);
                        }
                    }

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
                        if (totalDy > 150 && Math.abs(totalDy) > Math.abs(totalDx) * 2) {
                            // Swipe down — remove from line, return to inbox
                            RankedItem item = browseItem;
                            items.remove(item);
                            listener.onBrowseRemoveToInbox(item);
                        } else if (Math.abs(totalDx) > 60) {
                            if (totalDx < 0) listener.onCardSkipForward();
                            else listener.onCardSkipBackward();
                        } else if (Math.abs(totalDx) < 15 && Math.abs(totalDy) < 15) {
                            if (videoActive) {
                                listener.onVideoMuteToggle();
                            } else {
                                listener.onBrowseClose();
                            }
                        }
                    }
                    invalidate();
                    return true;
                }

                // Inbox swipe to skip
                if (inboxTouching) {
                    inboxTouching = false;
                    float totalDx = x - touchDownX;
                    float totalDy = y - touchDownY;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (Math.abs(totalDx) > 60) {
                            if (listener != null) {
                                if (totalDx < 0) listener.onCardSkipForward();
                                else listener.onCardSkipBackward();
                            }
                        } else if (videoActive && Math.abs(totalDx) < 15 && Math.abs(totalDy) < 15) {
                            if (listener != null) listener.onVideoMuteToggle();
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
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    if (listener != null) {
                        listener.onItemPlaced(draggingItem);
                        listener.onInboxConsumed(); // sets next inboxItem or null
                    }
                    draggingItem = null;
                    isPlacing = false;
                    isBrowsing = false;
                    inboxDragging = false;
                    scrubItem = null;
                    cardPreview = null;
                    invalidate();
                    return true;
                }

                if (isRepositioning && repositioningItem != null) {
                    double moved = Math.abs(repositioningItem.position - repositionOriginal);
                    float dpMoved = (float)(moved / visibleWidth() * getWidth());
                    if (dpMoved < CANCEL_THRESHOLD) {
                        repositioningItem.position = repositionOriginal;
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        if (listener != null) {
                            listener.onItemRepositioned(repositioningItem, repositionOriginal);
                            if (browseItem != null) {
                                listener.onBrowseRepositioned(repositioningItem);
                            }
                        }
                    }
                    repositioningItem = null;
                    isRepositioning = false;
                    isBrowsing = false;
                    scrubItem = null;
                    cardPreview = null;
                    invalidate();
                    return true;
                }

                // Clear scrub preview
                if (scrubActive) {
                    scrubItem = null;
                    scrubActive = false;
                    cardPreview = null;
                    if (listener != null) listener.onScrubItemChanged(null);
                }

                // Detect short tap on item
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && touchDownHitItem != null
                        && !longPressTriggered
                        && (System.currentTimeMillis() - touchDownTime) < 300
                        && Math.abs(x - touchDownX) < 20
                        && Math.abs(y - touchDownY) < 20) {
                    if (listener != null) {
                        // Check if item is in a cluster
                        List<RankedItem> cluster = getClusterAt(touchDownHitItem);
                        if (cluster.size() > 1) {
                            double minPos = Double.MAX_VALUE, maxPos = -Double.MAX_VALUE;
                            for (RankedItem ci : cluster) {
                                minPos = Math.min(minPos, ci.position);
                                maxPos = Math.max(maxPos, ci.position);
                            }
                            double clusterCenter = (minPos + maxPos) / 2.0;
                            double clusterSpan = maxPos - minPos;
                            listener.onClusterTapped(touchDownHitItem, clusterCenter, clusterSpan);
                        } else {
                            listener.onItemTapped(touchDownHitItem);
                        }
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

    private List<RankedItem> getClusterAt(RankedItem target) {
        float clusterThreshold = THUMB_SIZE * 0.8f;
        float targetX = rangeToScreenX(target.position);
        List<RankedItem> cluster = new ArrayList<>();
        for (RankedItem item : items) {
            float ix = rangeToScreenX(item.position);
            if (Math.abs(ix - targetX) < clusterThreshold) {
                cluster.add(item);
            }
        }
        return cluster;
    }

    private RankedItem findNearestItemToCenter() {
        return findNearestItemToCenter(null);
    }

    private RankedItem findNearestItemToCenter(RankedItem exclude) {
        if (items.isEmpty()) return null;
        double hw = visibleWidth() / 2.0;
        RankedItem nearest = null;
        double minDist = Double.MAX_VALUE;
        for (RankedItem item : items) {
            if (item == exclude) continue;
            double dist = Math.abs(item.position - center);
            if (dist < minDist) {
                minDist = dist;
                nearest = item;
            }
        }
        if (nearest != null && minDist > hw) return null;
        return nearest;
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

        if (!hasCard() && !inboxDragging && hasModeBar()) {
            drawModeBar(canvas, w, h);
        }

        if (undoVisible) {
            drawUndoPill(canvas, w, h);
        }
    }

    private boolean hasModeBar() {
        return modeBarInboxCount > 0 || modeBarBrowseCount > 0;
    }

    // --- Shared pill drawing ---
    private static final float PILL_HEIGHT = 48;
    private static final float PILL_PAD = 32;
    private static final float PILL_TEXT_SIZE = 32f;

    private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        pillTextPaint.setTextSize(PILL_TEXT_SIZE);
        pillTextPaint.setTextAlign(Paint.Align.CENTER);
        pillTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Draw a pill and return its width. Stores bounds in outRect if non-null. */
    private float drawPill(Canvas canvas, String label, float x, float y,
                           int bgColor, int textColor, RectF outRect) {
        pillTextPaint.setTextSize(PILL_TEXT_SIZE);
        float textW = pillTextPaint.measureText(label);
        float w = textW + PILL_PAD * 2;
        RectF rect = new RectF(x, y, x + w, y + PILL_HEIGHT);
        if (outRect != null) outRect.set(rect);
        pillBgPaint.setColor(bgColor);
        canvas.drawRoundRect(rect, PILL_HEIGHT / 2f, PILL_HEIGHT / 2f, pillBgPaint);
        pillTextPaint.setColor(textColor);
        float textVOffset = -(pillTextPaint.ascent() + pillTextPaint.descent()) / 2f;
        canvas.drawText(label, x + w / 2f, y + PILL_HEIGHT / 2f + textVOffset, pillTextPaint);
        return w;
    }

    private void drawModeBar(Canvas canvas, int w, int h) {
        float barTop = h - MODE_BAR_HEIGHT - MODE_BAR_MARGIN;
        float barBot = h - MODE_BAR_MARGIN;
        float barLeft = MODE_BAR_MARGIN;
        float barRight = w - MODE_BAR_MARGIN;
        modeBarRect.set(barLeft, barTop, barRight, barBot);

        Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        barBg.setColor(0xFF1A1A1A);
        canvas.drawRoundRect(modeBarRect, 16, 16, barBg);

        String inboxLabel = "Inbox (" + modeBarInboxCount + ")";
        String browseLabel = "Browse (" + modeBarBrowseCount + ")";
        boolean showInbox = modeBarInboxCount > 0;
        boolean showBrowse = modeBarBrowseCount > 0;

        float pillGap = 16;
        float pillY = barTop + (MODE_BAR_HEIGHT - PILL_HEIGHT) / 2f;

        pillTextPaint.setTextSize(PILL_TEXT_SIZE);
        float inboxW = showInbox ? pillTextPaint.measureText(inboxLabel) + PILL_PAD * 2 : 0;
        float browseW = showBrowse ? pillTextPaint.measureText(browseLabel) + PILL_PAD * 2 : 0;
        float totalW = inboxW + browseW + (showInbox && showBrowse ? pillGap : 0);
        float startX = (barLeft + barRight - totalW) / 2f;

        modeBarInboxPill.setEmpty();
        modeBarBrowsePill.setEmpty();

        float cx = startX;
        if (showInbox) {
            drawPill(canvas, inboxLabel, cx, pillY, 0xFF2A2A2A, 0xFFCCCCCC, modeBarInboxPill);
            cx += inboxW + pillGap;
        }
        if (showBrowse) {
            drawPill(canvas, browseLabel, cx, pillY, 0xFF2A2A2A, 0xFFCCCCCC, modeBarBrowsePill);
        }
    }

    private void drawUndoPill(Canvas canvas, int w, int h) {
        float pillY;
        if (hasCard()) {
            pillY = getLineY() + 20;
        } else {
            pillY = h - MODE_BAR_HEIGHT - MODE_BAR_MARGIN - PILL_HEIGHT - 12;
        }

        pillTextPaint.setTextSize(PILL_TEXT_SIZE);
        float textW = pillTextPaint.measureText("Undo");
        float pillW = textW + PILL_PAD * 2;
        float pillX = (w - pillW) / 2f;
        drawPill(canvas, "Undo", pillX, pillY, 0xFFFF6F00, 0xFFFFFFFF, undoPillRect);
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

        // Highlight scrub preview item
        if (scrubItem != null) {
            float sx = rangeToScreenX(scrubItem.position);
            if (sx > -THUMB_SIZE && sx < w + THUMB_SIZE) {
                Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                hlPaint.setColor(0xFFFF6F00);
                hlPaint.setStyle(Paint.Style.STROKE);
                hlPaint.setStrokeWidth(3f);
                float hlLeft = sx - THUMB_SIZE / 2f - 3;
                float hlTop = lineY - THUMB_SIZE - 15;
                RectF hlRect = new RectF(hlLeft, hlTop, hlLeft + THUMB_SIZE + 6, hlTop + THUMB_SIZE + 6);
                canvas.drawRoundRect(hlRect, 8, 8, hlPaint);
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

        // Neighbor preview during placement shows at full opacity
        int alpha = (inboxDragging && scrubItem == null) ? 80 : 255;
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

        if (videoActive) {
            // Video is playing via PlayerView overlay — skip drawing image area
        } else if (cardPreview != null) {
            Drawable previewDrawable = cardPreview;
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

        // Mute icon for video items
        if (videoActive) {
            String muteIcon = videoMuted ? "\uD83D\uDD07" : "\uD83D\uDD0A";
            infoPaint.setColor(0xFFCCCCCC);
            infoPaint.setTextAlign(Paint.Align.RIGHT);
            infoPaint.setTextSize(24f);
            canvas.drawText(muteIcon, cRight - 16, textY, infoPaint);
        }

        boolean isScrub = scrubItem != null && browseItem == null;
        if (isBrowse || isScrub) {
            RankedItem displayItem = isBrowse ? browseItem : scrubItem;

            // Left: position value
            cursorLabelPaint.setTextSize(24f);
            cursorLabelPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(String.format("%.6f", displayItem.position), cLeft + 16, textY, cursorLabelPaint);
            cursorLabelPaint.setTextSize(28f);
            cursorLabelPaint.setTextAlign(Paint.Align.CENTER);

            if (isBrowse) {
                // Right: #N of M + nav arrows (shift left if video icon present)
                infoPaint.setColor(0xFFAAAAAA);
                infoPaint.setTextAlign(Paint.Align.RIGHT);
                float navRight = videoActive ? cRight - 56 : cRight - 16;
                String nav = (browseIndex > 0 ? "\u276E  " : "    ")
                        + "#" + (browseIndex + 1) + " of " + browseTotal
                        + (browseIndex < browseTotal - 1 ? "  \u276F" : "");
                canvas.drawText(nav, navRight, textY, infoPaint);
            }

            // Label if present
            if (displayItem.label != null && !displayItem.label.isEmpty()) {
                infoPaint.setColor(0xFFCCCCCC);
                infoPaint.setTextAlign(Paint.Align.CENTER);
                infoPaint.setTextSize(20f);
                canvas.drawText(displayItem.label, (cLeft + cRight) / 2f, cTop + 28, infoPaint);
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
