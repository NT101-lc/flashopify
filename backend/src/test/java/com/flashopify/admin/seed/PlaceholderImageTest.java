package com.flashopify.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashopify.common.storage.ObjectMetadata;
import com.flashopify.common.storage.ObjectStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlaceholderImageTest {

  @Test
  void rendersAnEightHundredPixelPng() throws IOException {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(PlaceholderImage.render()));

    assertThat(image.getWidth()).isEqualTo(800);
    assertThat(image.getHeight()).isEqualTo(800);
  }

  @Test
  void uploadsOnceAndCachesTheUrl() {
    ObjectStorage storage = mock(ObjectStorage.class);
    when(storage.head(PlaceholderImage.KEY)).thenReturn(Optional.empty());
    when(storage.publicUrl(PlaceholderImage.KEY)).thenReturn("http://m/products/placeholder.png");
    PlaceholderImage placeholder = new PlaceholderImage(storage);

    assertThat(placeholder.url()).isEqualTo("http://m/products/placeholder.png");
    assertThat(placeholder.url()).isEqualTo("http://m/products/placeholder.png");

    ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
    verify(storage, times(1)).put(anyString(), any(), type.capture());
    assertThat(type.getValue()).isEqualTo("image/png");
  }

  @Test
  void skipsUploadWhenAlreadyInStorage() {
    ObjectStorage storage = mock(ObjectStorage.class);
    when(storage.head(PlaceholderImage.KEY))
        .thenReturn(Optional.of(new ObjectMetadata(10, "image/png")));
    when(storage.publicUrl(PlaceholderImage.KEY)).thenReturn("u");

    assertThat(new PlaceholderImage(storage).url()).isEqualTo("u");
    verify(storage, never()).put(anyString(), any(), anyString());
  }
}
