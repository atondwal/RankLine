package com.example.rankline;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import java.util.UUID;

public class RankedItem {
    public String id;
    public double position; // in (-1, 1)
    public String imageUrl;
    public Bitmap thumbnail;  // small, for number line
    public Drawable preview;  // larger, for inbox/browse display (may be animated GIF)
    public String label;

    public RankedItem(String imageUrl, String label) {
        this.id = UUID.randomUUID().toString();
        this.position = 0;
        this.imageUrl = imageUrl;
        this.label = label;
    }

    public RankedItem(String id, double position, String imageUrl, String label) {
        this.id = id;
        this.position = position;
        this.imageUrl = imageUrl;
        this.label = label;
    }
}
