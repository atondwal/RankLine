package com.example.rankline;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

public class MainActivity extends AppCompatActivity implements RankLineView.Listener {

    private RankLineView rankLineView;
    private final Deque<RankedItem> inboxQueue = new ArrayDeque<>();
    private static final String SAVE_FILE = "rankings.json";
    private static final int THUMB_PX = 128;
    private static final int PICK_GALLERY = 1001;

    // Browse state
    private List<RankedItem> browseSorted = new ArrayList<>();
    private int browseIndex = -1;
    private String lastBrowseItemId = null;

    // Video state
    private PlayerView videoPlayerView;
    private ExoPlayer exoPlayer;
    private boolean videoMuted = true;

    // Undo stack (persistent)
    private enum UndoType { PLACE, BROWSE_REMOVE, REPOSITION }
    private static final int MAX_UNDO_STACK = 50;

    private static class UndoAction {
        UndoType type;
        String itemId;
        double position;
        String imageUrl;
        String label;
        boolean isVideo;
    }
    private final List<UndoAction> undoStack = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        rankLineView = findViewById(R.id.rankLineView);
        rankLineView.setListener(this);

        videoPlayerView = findViewById(R.id.videoPlayer);
        videoPlayerView.setUseController(false);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle("");
            }
        }

        loadRankings();
    }

    @Override
    protected void onDestroy() {
        hideVideoPlayer();
        super.onDestroy();
    }

    // --- Browse mode ---
    private void openBrowse(@Nullable RankedItem startItem) {
        List<RankedItem> items = rankLineView.getItems();
        if (items.isEmpty()) {
            Toast.makeText(this, "No ranked items yet", Toast.LENGTH_SHORT).show();
            return;
        }

        browseSorted = new ArrayList<>(items);
        browseSorted.sort(Comparator.comparingDouble(a -> a.position));

        browseIndex = 0;
        if (startItem != null) {
            for (int i = 0; i < browseSorted.size(); i++) {
                if (browseSorted.get(i).id.equals(startItem.id)) {
                    browseIndex = i;
                    break;
                }
            }
        }
        showBrowseItem();
        updateModeBar();
    }

    private void openBrowseAtLastPosition() {
        List<RankedItem> items = rankLineView.getItems();
        if (items.isEmpty()) {
            Toast.makeText(this, "No ranked items yet", Toast.LENGTH_SHORT).show();
            return;
        }

        browseSorted = new ArrayList<>(items);
        browseSorted.sort(Comparator.comparingDouble(a -> a.position));

        browseIndex = 0;
        if (lastBrowseItemId != null) {
            for (int i = 0; i < browseSorted.size(); i++) {
                if (browseSorted.get(i).id.equals(lastBrowseItemId)) {
                    browseIndex = i;
                    break;
                }
            }
        }
        showBrowseItem();
        updateModeBar();
    }

    private void closeBrowse() {
        browseSorted.clear();
        browseIndex = -1;
        hideVideoPlayer();
        rankLineView.clearBrowse();
        updateModeBar();
    }

    private void navigateBrowse(int delta) {
        int newIndex = browseIndex + delta;
        if (newIndex < 0 || newIndex >= browseSorted.size()) return;
        browseIndex = newIndex;
        showBrowseItem();
    }

    private void showBrowseItem() {
        if (browseIndex < 0 || browseIndex >= browseSorted.size()) return;
        RankedItem item = browseSorted.get(browseIndex);
        lastBrowseItemId = item.id;
        loadPreview(item);
        rankLineView.setBrowseItem(item, browseIndex, browseSorted.size());

        // Center view on item; zoom to show nearest neighbors
        double leftPos = browseIndex > 0
                ? browseSorted.get(browseIndex - 1).position : -1.0;
        double rightPos = browseIndex < browseSorted.size() - 1
                ? browseSorted.get(browseIndex + 1).position : 1.0;
        double span = Math.max(rightPos - leftPos, 1e-10);
        double zoom = Math.max(1.0, Math.min(1e8, 2.0 / (span * 1.5)));
        rankLineView.setViewState(item.position, zoom);
    }

    @Override
    public void onBackPressed() {
        if (rankLineView.isBrowseMode()) {
            closeBrowse();
            return;
        }
        super.onBackPressed();
    }

    // --- Menu ---
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_url) {
            showAddUrlDialog();
            return true;
        } else if (id == R.id.action_import_feed) {
            showImportFeedDialog();
            return true;
        } else if (id == R.id.action_browse) {
            openBrowseAtLastPosition();
            return true;
        } else if (id == R.id.action_add_gallery) {
            openGalleryPicker();
            return true;
        } else if (id == R.id.action_export) {
            exportRankings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- URL input ---
    private void showAddUrlDialog() {
        EditText urlInput = new EditText(this);
        urlInput.setHint("https://example.com/image.gif");
        urlInput.setSingleLine();

        EditText labelInput = new EditText(this);
        labelInput.setHint("Label (optional)");
        labelInput.setSingleLine();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = 48;
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(urlInput);
        layout.addView(labelInput);

        new AlertDialog.Builder(this)
                .setTitle("Add Image URL")
                .setView(layout)
                .setPositiveButton("Add", (d, w) -> {
                    String url = urlInput.getText().toString().trim();
                    String label = labelInput.getText().toString().trim();
                    if (!url.isEmpty()) {
                        addToInbox(url, label);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Feed import ---
    private void showImportFeedDialog() {
        EditText input = new EditText(this);
        input.setHint("Paste feed JSON or URL");
        input.setMinLines(3);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = 48;
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Import Feed")
                .setView(layout)
                .setPositiveButton("Import", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        parseFeed(text);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void parseFeed(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            JSONArray arr = obj.getJSONArray("items");
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String url = item.getString("url");
                String label = item.optString("label", "");
                addToInbox(url, label);
                count++;
            }
            Toast.makeText(this, "Added " + count + " items", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            try {
                JSONArray arr = new JSONArray(text);
                int count = 0;
                for (int i = 0; i < arr.length(); i++) {
                    String url = arr.getString(i);
                    addToInbox(url, "");
                    count++;
                }
                Toast.makeText(this, "Added " + count + " items", Toast.LENGTH_SHORT).show();
            } catch (Exception e2) {
                Toast.makeText(this, "Invalid feed format", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- Export ---
    private void exportRankings() {
        try {
            List<RankedItem> sorted = new ArrayList<>(rankLineView.getItems());
            sorted.sort(Comparator.comparingDouble(a -> a.position));

            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (RankedItem item : sorted) {
                JSONObject obj = new JSONObject();
                obj.put("url", item.imageUrl);
                obj.put("label", item.label != null ? item.label : "");
                obj.put("position", item.position);
                if (item.isVideo) obj.put("isVideo", true);
                arr.put(obj);
            }
            root.put("items", arr);

            String json = root.toString(2);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TEXT, json);
            intent.putExtra(Intent.EXTRA_SUBJECT, "RankLine Export");
            startActivity(Intent.createChooser(intent, "Export Rankings"));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Gallery picker ---
    private void openGalleryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_GALLERY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GALLERY || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        int count = 0;
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) { /* ok */ }
            String mime = getContentResolver().getType(uri);
            boolean isVideo = mime != null && mime.startsWith("video/");
            RankedItem item = new RankedItem(uri.toString(), getFileNameFromUri(uri));
            item.isVideo = isVideo;
            inboxQueue.addLast(item);
            loadThumbnail(item);
            count++;
        }
        if (!inboxQueue.isEmpty() && rankLineView.getInboxItem() == null
                && !rankLineView.isBrowseMode()) {
            showNextInboxItem();
        }
        updateInboxCount();

        if (count > 0) {
            Toast.makeText(this, "Added " + count + " item" + (count > 1 ? "s" : ""),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        int dot = path.lastIndexOf('.');
        if (dot > 0) path = path.substring(0, dot);
        int colon = path.lastIndexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        return path;
    }

    // --- Inbox management ---
    private void addToInbox(String url, String label) {
        RankedItem item = new RankedItem(url, label);
        item.isVideo = looksLikeVideo(url);
        inboxQueue.addLast(item);
        loadThumbnail(item);
        if (rankLineView.getInboxItem() == null && !rankLineView.isBrowseMode()) {
            showNextInboxItem();
        }
        updateInboxCount();
    }

    private void showNextInboxItem() {
        if (!inboxQueue.isEmpty()) {
            RankedItem next = inboxQueue.pollFirst();
            rankLineView.setInboxItem(next);
            loadPreview(next);
        } else {
            hideVideoPlayer();
            rankLineView.setInboxItem(null);
        }
        updateInboxCount();
        saveRankings();
    }

    private void loadPreview(RankedItem item) {
        if (item.isVideo) {
            showVideoPlayer(item);
        } else {
            hideVideoPlayer();
            Glide.with(this)
                    .load(item.imageUrl)
                    .into(new CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource,
                                @Nullable Transition<? super Drawable> transition) {
                            rankLineView.bindPreviewDrawable(resource);
                            rankLineView.invalidate();
                        }
                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {}
                    });
        }
    }

    private void updateInboxCount() {
        int count = inboxQueue.size();
        if (rankLineView.getInboxItem() != null) count++;
        rankLineView.setInboxQueueSize(count);
        updateModeBar();
    }

    private void updateModeBar() {
        int inboxCount = inboxQueue.size();
        if (rankLineView.getInboxItem() != null) inboxCount++;
        int browseCount = rankLineView.getItems().size();
        rankLineView.setModeBarCounts(inboxCount, browseCount);
    }

    private void loadThumbnail(RankedItem item) {
        Glide.with(this)
                .asBitmap()
                .load(item.imageUrl)
                .override(THUMB_PX, THUMB_PX)
                .centerCrop()
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                            @Nullable Transition<? super Bitmap> transition) {
                        item.thumbnail = resource;
                        rankLineView.invalidate();
                    }
                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {}
                });
    }

    // --- Video playback ---
    private static boolean looksLikeVideo(String url) {
        String lower = url.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".m3u8")
                || lower.endsWith(".mkv") || lower.endsWith(".avi");
    }

    private void showVideoPlayer(RankedItem item) {
        hideVideoPlayer();

        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setVolume(videoMuted ? 0f : 1f);
        videoPlayerView.setPlayer(exoPlayer);

        MediaItem mediaItem = MediaItem.fromUri(item.imageUrl);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        // Position PlayerView over the card image area
        rankLineView.post(() -> {
            RectF rect = new RectF();
            if (rankLineView.getCardImageRect(rect)) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        (int) rect.width(), (int) rect.height());
                lp.leftMargin = (int) rect.left;
                lp.topMargin = (int) rect.top;
                videoPlayerView.setLayoutParams(lp);
            }
            videoPlayerView.setVisibility(android.view.View.VISIBLE);
        });

        rankLineView.setVideoActive(true, videoMuted);
    }

    private void hideVideoPlayer() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
        videoPlayerView.setVisibility(android.view.View.GONE);
        videoPlayerView.setPlayer(null);
        rankLineView.setVideoActive(false, videoMuted);
    }

    // --- Listener callbacks ---
    @Override
    public void onItemPlaced(RankedItem item) {
        recordUndo(UndoType.PLACE, item, item.position);
        saveRankings();
        updateModeBar();
    }

    @Override
    public void onItemRepositioned(RankedItem item, double previousPosition) {
        recordUndo(UndoType.REPOSITION, item, previousPosition);
        saveRankings();
    }

    @Override
    public void onInboxConsumed() {
        showNextInboxItem();
    }

    @Override
    public void onItemTapped(RankedItem item) {
        openBrowse(item);
    }

    @Override
    public void onCardSkipForward() {
        hideVideoPlayer();
        if (rankLineView.isBrowseMode()) {
            navigateBrowse(1);
        } else {
            // Inbox skip forward
            RankedItem current = rankLineView.getInboxItem();
            if (current != null && !inboxQueue.isEmpty()) {
                inboxQueue.addLast(current);
                showNextInboxItem();
            }
        }
    }

    @Override
    public void onCardSkipBackward() {
        hideVideoPlayer();
        if (rankLineView.isBrowseMode()) {
            navigateBrowse(-1);
        } else {
            // Inbox skip backward
            RankedItem current = rankLineView.getInboxItem();
            if (current != null && !inboxQueue.isEmpty()) {
                inboxQueue.addFirst(current);
                RankedItem prev = ((ArrayDeque<RankedItem>) inboxQueue).pollLast();
                if (prev != null) {
                    rankLineView.setInboxItem(prev);
                    loadPreview(prev);
                    updateInboxCount();
                }
            }
        }
    }

    @Override
    public void onBrowseClose() {
        closeBrowse();
    }

    @Override
    public void onBrowseRemoveToInbox(RankedItem item) {
        hideVideoPlayer();
        recordUndo(UndoType.BROWSE_REMOVE, item, item.position);
        // Add removed item to inbox queue (front, pushing any current inbox item back)
        RankedItem current = rankLineView.getInboxItem();
        if (current != null) {
            inboxQueue.addFirst(current);
        }
        inboxQueue.addFirst(item);
        updateInboxCount();

        // Remove from browse list
        browseSorted.remove(item);
        saveRankings();

        if (!browseSorted.isEmpty()) {
            // Clamp index and stay in browse
            if (browseIndex >= browseSorted.size()) {
                browseIndex = browseSorted.size() - 1;
            }
            showBrowseItem();
        } else {
            // No items left — exit browse
            closeBrowse();
            showNextInboxItem();
        }
    }

    @Override
    public void onModeBarInboxTapped() {
        showNextInboxItem();
    }

    @Override
    public void onModeBarBrowseTapped() {
        openBrowseAtLastPosition();
    }

    @Override
    public void onVideoMuteToggle() {
        videoMuted = !videoMuted;
        if (exoPlayer != null) {
            exoPlayer.setVolume(videoMuted ? 0f : 1f);
        }
        rankLineView.setVideoActive(true, videoMuted);
    }

    @Override
    public void onScrubItemChanged(RankedItem item) {
        hideVideoPlayer();
        if (item != null) {
            final String targetId = item.id;
            Glide.with(this)
                    .load(item.imageUrl)
                    .into(new CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource,
                                @Nullable Transition<? super Drawable> transition) {
                            RankedItem scrub = rankLineView.getScrubItem();
                            if (scrub != null && scrub.id.equals(targetId)) {
                                rankLineView.bindPreviewDrawable(resource);
                                rankLineView.invalidate();
                            }
                        }
                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {}
                    });
        }
    }

    @Override
    public void onBrowseRepositioned(RankedItem item) {
        // Re-sort browse list after an item was repositioned
        if (!browseSorted.isEmpty()) {
            browseSorted.sort(Comparator.comparingDouble(a -> a.position));
            // Re-find the repositioned item's new index
            for (int i = 0; i < browseSorted.size(); i++) {
                if (browseSorted.get(i).id.equals(item.id)) {
                    browseIndex = i;
                    break;
                }
            }
            showBrowseItem();
        }
    }

    @Override
    public void onClusterTapped(RankedItem item, double clusterCenter, double clusterSpan) {
        // Zoom in to separate the cluster
        double targetSpan = Math.max(clusterSpan * 3.0, 1e-10);
        double zoom = Math.max(1.0, Math.min(1e8, 2.0 / targetSpan));
        rankLineView.setViewState(clusterCenter, zoom);
        rankLineView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    @Override
    public void onBrowseDeleteRequested(RankedItem item) {
        String[] options = {"Edit Label", "Delete"};
        new AlertDialog.Builder(this)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showEditLabelDialog(item);
                    } else {
                        confirmDelete(item);
                    }
                })
                .show();
    }

    private void showEditLabelDialog(RankedItem item) {
        EditText input = new EditText(this);
        input.setText(item.label != null ? item.label : "");
        input.setSelectAllOnFocus(true);
        input.setSingleLine();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = 48;
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Edit Label")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    item.label = input.getText().toString().trim();
                    saveRankings();
                    rankLineView.invalidate();
                    if (rankLineView.isBrowseMode()) {
                        showBrowseItem();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(RankedItem item) {
        String name = (item.label != null && !item.label.isEmpty()) ? item.label : "this item";
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Permanently delete " + name + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    undoStack.removeIf(a -> a.itemId.equals(item.id));
                    syncUndoVisibility();
                    rankLineView.getItems().remove(item);
                    browseSorted.remove(item);
                    saveRankings();

                    if (!browseSorted.isEmpty()) {
                        if (browseIndex >= browseSorted.size()) {
                            browseIndex = browseSorted.size() - 1;
                        }
                        showBrowseItem();
                    } else {
                        closeBrowse();
                    }
                    updateModeBar();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Undo ---
    private void recordUndo(UndoType type, RankedItem item, double position) {
        UndoAction action = new UndoAction();
        action.type = type;
        action.itemId = item.id;
        action.position = position;
        action.imageUrl = item.imageUrl;
        action.label = item.label;
        action.isVideo = item.isVideo;
        undoStack.add(action);
        if (undoStack.size() > MAX_UNDO_STACK) {
            undoStack.remove(0);
        }
        rankLineView.setUndoVisible(true);
    }

    private void clearUndo() {
        undoStack.clear();
        rankLineView.setUndoVisible(false);
    }

    private void syncUndoVisibility() {
        rankLineView.setUndoVisible(!undoStack.isEmpty());
    }

    @Override
    public void onUndoTapped() {
        if (undoStack.isEmpty()) return;
        UndoAction action = undoStack.remove(undoStack.size() - 1);

        switch (action.type) {
            case PLACE:
                // Find item on line and move to inbox
                RankedItem placed = findItemById(action.itemId);
                if (placed != null) {
                    rankLineView.getItems().remove(placed);
                    placed.position = 0;
                    inboxQueue.addFirst(placed);
                    showNextInboxItem();
                }
                break;
            case BROWSE_REMOVE:
                // Recreate item on line at original position
                RankedItem restored = new RankedItem(
                        action.itemId, action.position, action.imageUrl,
                        action.label != null ? action.label : "");
                restored.isVideo = action.isVideo;
                rankLineView.getItems().add(restored);
                loadThumbnail(restored);
                // Remove from inbox if still there
                removeFromInbox(action.itemId);
                break;
            case REPOSITION:
                // Snap back to original position
                RankedItem moved = findItemById(action.itemId);
                if (moved != null) {
                    moved.position = action.position;
                    if (rankLineView.isBrowseMode()) {
                        browseSorted.sort(Comparator.comparingDouble(a -> a.position));
                        for (int i = 0; i < browseSorted.size(); i++) {
                            if (browseSorted.get(i).id.equals(action.itemId)) {
                                browseIndex = i;
                                break;
                            }
                        }
                        showBrowseItem();
                    }
                }
                break;
        }

        syncUndoVisibility();
        saveRankings();
        updateModeBar();
        rankLineView.invalidate();
    }

    private RankedItem findItemById(String id) {
        for (RankedItem item : rankLineView.getItems()) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    private void removeFromInbox(String id) {
        RankedItem current = rankLineView.getInboxItem();
        if (current != null && current.id.equals(id)) {
            showNextInboxItem();
            return;
        }
        for (RankedItem qi : inboxQueue) {
            if (qi.id.equals(id)) {
                inboxQueue.remove(qi);
                return;
            }
        }
    }

    // --- Persistence ---
    private void saveRankings() {
        try {
            JSONObject root = new JSONObject();

            JSONArray arr = new JSONArray();
            for (RankedItem item : rankLineView.getItems()) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.id);
                obj.put("position", item.position);
                obj.put("imageUrl", item.imageUrl);
                obj.put("label", item.label != null ? item.label : "");
                if (item.isVideo) obj.put("isVideo", true);
                arr.put(obj);
            }
            root.put("items", arr);

            JSONObject view = new JSONObject();
            view.put("center", rankLineView.getCenter());
            view.put("zoom", rankLineView.getCurrentZoom());
            root.put("view", view);

            if (lastBrowseItemId != null) {
                root.put("lastBrowseItemId", lastBrowseItemId);
            }

            // Persist inbox queue
            JSONArray inboxArr = new JSONArray();
            for (RankedItem qi : inboxQueue) {
                JSONObject qo = new JSONObject();
                qo.put("id", qi.id);
                qo.put("imageUrl", qi.imageUrl);
                qo.put("label", qi.label != null ? qi.label : "");
                if (qi.isVideo) qo.put("isVideo", true);
                inboxArr.put(qo);
            }
            // Also save the current inbox card item
            RankedItem currentInbox = rankLineView.getInboxItem();
            if (currentInbox != null) {
                JSONObject qo = new JSONObject();
                qo.put("id", currentInbox.id);
                qo.put("imageUrl", currentInbox.imageUrl);
                qo.put("label", currentInbox.label != null ? currentInbox.label : "");
                if (currentInbox.isVideo) qo.put("isVideo", true);
                inboxArr.put(0, qo); // put at front
            }
            if (inboxArr.length() > 0) {
                root.put("inbox", inboxArr);
            }

            // Persist undo stack
            if (!undoStack.isEmpty()) {
                JSONArray undoArr = new JSONArray();
                for (UndoAction ua : undoStack) {
                    JSONObject uo = new JSONObject();
                    uo.put("type", ua.type.name());
                    uo.put("itemId", ua.itemId);
                    uo.put("position", ua.position);
                    uo.put("imageUrl", ua.imageUrl);
                    uo.put("label", ua.label != null ? ua.label : "");
                    if (ua.isVideo) uo.put("isVideo", true);
                    undoArr.put(uo);
                }
                root.put("undoStack", undoArr);
            }

            File f = new File(getFilesDir(), SAVE_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadRankings() {
        try {
            File f = new File(getFilesDir(), SAVE_FILE);
            if (!f.exists()) return;

            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            fis.close();

            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray arr = root.getJSONArray("items");

            List<RankedItem> loaded = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                RankedItem item = new RankedItem(
                        obj.getString("id"),
                        obj.getDouble("position"),
                        obj.getString("imageUrl"),
                        obj.optString("label", "")
                );
                item.isVideo = obj.optBoolean("isVideo", false);
                loaded.add(item);
                loadThumbnail(item);
            }
            rankLineView.setItems(loaded);

            if (root.has("view")) {
                JSONObject view = root.getJSONObject("view");
                rankLineView.setViewState(view.getDouble("center"), view.getDouble("zoom"));
            }

            if (root.has("lastBrowseItemId")) {
                lastBrowseItemId = root.getString("lastBrowseItemId");
            }

            // Restore inbox queue
            if (root.has("inbox")) {
                JSONArray inboxArr = root.getJSONArray("inbox");
                for (int i = 0; i < inboxArr.length(); i++) {
                    JSONObject qo = inboxArr.getJSONObject(i);
                    RankedItem qi = new RankedItem(
                            qo.getString("id"),
                            0,
                            qo.getString("imageUrl"),
                            qo.optString("label", "")
                    );
                    qi.isVideo = qo.optBoolean("isVideo", false);
                    inboxQueue.addLast(qi);
                    loadThumbnail(qi);
                }
                if (!inboxQueue.isEmpty()) {
                    showNextInboxItem();
                }
            }

            // Restore undo stack
            if (root.has("undoStack")) {
                JSONArray undoArr = root.getJSONArray("undoStack");
                for (int i = 0; i < undoArr.length(); i++) {
                    JSONObject uo = undoArr.getJSONObject(i);
                    UndoAction ua = new UndoAction();
                    ua.type = UndoType.valueOf(uo.getString("type"));
                    ua.itemId = uo.getString("itemId");
                    ua.position = uo.getDouble("position");
                    ua.imageUrl = uo.getString("imageUrl");
                    ua.label = uo.optString("label", "");
                    ua.isVideo = uo.optBoolean("isVideo", false);
                    undoStack.add(ua);
                }
            }
            syncUndoVisibility();

            updateModeBar();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
