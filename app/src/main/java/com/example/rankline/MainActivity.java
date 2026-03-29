package com.example.rankline;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        rankLineView = findViewById(R.id.rankLineView);
        rankLineView.setListener(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle("");
            }
        }

        loadRankings();
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
    }

    private void closeBrowse() {
        browseSorted.clear();
        browseIndex = -1;
        rankLineView.clearBrowse();
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
            openBrowse(null);
            return true;
        } else if (id == R.id.action_add_gallery) {
            openGalleryPicker();
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

    // --- Gallery picker ---
    private void openGalleryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
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
            addToInbox(uri.toString(), getFileNameFromUri(uri));
            count++;
        }

        if (count > 0) {
            Toast.makeText(this, "Added " + count + " image" + (count > 1 ? "s" : ""),
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
            rankLineView.setInboxItem(null);
        }
        updateInboxCount();
    }

    private void loadPreview(RankedItem item) {
        if (item.preview != null) {
            rankLineView.bindPreviewDrawable(item.preview);
            rankLineView.invalidate();
            return;
        }
        Glide.with(this)
                .load(item.imageUrl)
                .into(new CustomTarget<Drawable>() {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource,
                            @Nullable Transition<? super Drawable> transition) {
                        item.preview = resource;
                        rankLineView.bindPreviewDrawable(resource);
                        rankLineView.invalidate();
                    }
                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {}
                });
    }

    private void updateInboxCount() {
        int count = inboxQueue.size();
        if (rankLineView.getInboxItem() != null) count++;
        rankLineView.setInboxQueueSize(count);
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

    // --- Listener callbacks ---
    @Override
    public void onItemPlaced(RankedItem item) {
        saveRankings();
    }

    @Override
    public void onItemRepositioned(RankedItem item) {
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
        closeBrowse();
        saveRankings();
        // Put item at front of inbox
        RankedItem current = rankLineView.getInboxItem();
        if (current != null) {
            inboxQueue.addFirst(current);
        }
        rankLineView.setInboxItem(item);
        loadPreview(item);
        updateInboxCount();
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
                arr.put(obj);
            }
            root.put("items", arr);

            JSONObject view = new JSONObject();
            view.put("center", rankLineView.getCenter());
            view.put("zoom", rankLineView.getCurrentZoom());
            root.put("view", view);

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
                loaded.add(item);
                loadThumbnail(item);
            }
            rankLineView.setItems(loaded);

            if (root.has("view")) {
                JSONObject view = root.getJSONObject("view");
                rankLineView.setViewState(view.getDouble("center"), view.getDouble("zoom"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
