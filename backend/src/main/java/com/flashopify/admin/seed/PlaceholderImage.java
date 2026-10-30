package com.flashopify.admin.seed;

import com.flashopify.common.storage.ObjectStorage;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * The "No image" picture used when a product image cannot be downloaded. It is generated, so no
 * binary lives in git.
 */
@Slf4j
public class PlaceholderImage {

  static final String KEY = "products/placeholder.png";
  private static final int SIZE = 800;

  private final ObjectStorage storage;
  private String url;

  public PlaceholderImage(ObjectStorage storage) {
    this.storage = storage;
  }

  /** Public URL of the placeholder; uploads it on first use unless it is already in storage. */
  public synchronized String url() {
    if (url == null) {
      if (storage.head(KEY).isEmpty()) {
        storage.put(KEY, render(), "image/png");
        log.info("placeholder image uploaded key={}", KEY);
      }
      url = storage.publicUrl(KEY);
    }
    return url;
  }

  static byte[] render() {
    BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(new Color(0xE5E5E5));
      g.fillRect(0, 0, SIZE, SIZE);
      g.setColor(new Color(0x8A8A8A));
      g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 64));
      FontMetrics metrics = g.getFontMetrics();
      String text = "No image";
      int x = (SIZE - metrics.stringWidth(text)) / 2;
      int y = (SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
      g.drawString(text, x, y);
    } finally {
      g.dispose();
    }
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
