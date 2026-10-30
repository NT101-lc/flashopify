package com.flashopify.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashopify.admin.seed.dto.DummyJsonCategory;
import com.flashopify.admin.seed.dto.DummyJsonProduct;
import com.flashopify.admin.seed.dto.DummyJsonProductPage;
import com.flashopify.product.dto.ProductImportCommand;
import com.flashopify.product.service.ProductImportService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** TC-3. */
class ProductSeederTest {

  private DummyJsonClient client;
  private ImageMirror images;
  private ProductImportService importService;
  private ProductSeeder seeder;

  @BeforeEach
  void setUp() {
    client = mock(DummyJsonClient.class);
    images = mock(ImageMirror.class);
    importService = mock(ProductImportService.class);
    seeder = new ProductSeeder(client, images, importService, SeedTestProps.of("x", 3, 2));

    when(client.categories()).thenReturn(List.of(new DummyJsonCategory("beauty", "Beauty", "u")));
    when(importService.upsertCategory(anyString(), anyString())).thenReturn(7L);
    when(images.mirror(anyString(), anyString())).thenAnswer(i -> "our:" + i.getArgument(1));
    when(importService.importProduct(any())).thenReturn(Optional.of(1L));
  }

  private static DummyJsonProduct product(int id) {
    return new DummyJsonProduct(
        id,
        "Title " + id,
        "d",
        "beauty",
        1.5,
        3.0,
        5,
        "Brand",
        "SKU-" + id,
        "t://" + id,
        List.of());
  }

  private void page(int skip, int total, DummyJsonProduct... products) {
    when(client.products(eq(skip), anyInt()))
        .thenReturn(new DummyJsonProductPage(List.of(products), total, skip, 2));
  }

  @Test
  void existingSourceIdIsSkippedWithoutDownloadOrImport() {
    page(0, 1, product(1));
    when(importService.existsBySourceId(1)).thenReturn(true);

    SeedReport report = seeder.run();

    assertThat(report.skipped()).isEqualTo(1);
    assertThat(report.imported()).isZero();
    verify(importService, never()).importProduct(any());
    verify(images, never()).mirror(anyString(), anyString());
  }

  @Test
  void pagesUntilTheTotalIsReached() {
    page(0, 3, product(1), product(2));
    page(2, 3, product(3));

    SeedReport report = seeder.run();

    assertThat(report.imported()).isEqualTo(3);
    assertThat(report.categories()).isEqualTo(1);
    verify(client).products(0, 2);
    verify(client).products(2, 2);
    verify(importService).countExternalImageUrls("dummyjson.com");
  }

  @Test
  void mapsTheSourceProduct() {
    DummyJsonProduct source =
        new DummyJsonProduct(
            42,
            "  Crème Brûlée -- Gift Set!! ",
            "desc",
            "beauty",
            9.995,
            7.5,
            -3,
            null,
            null,
            "t://42",
            IntStream.range(0, 10).mapToObj(i -> "i://" + i).toList());
    page(0, 1, source);

    seeder.run();

    ArgumentCaptor<ProductImportCommand> captor =
        ArgumentCaptor.forClass(ProductImportCommand.class);
    verify(importService).importProduct(captor.capture());
    ProductImportCommand cmd = captor.getValue();
    assertThat(cmd.sourceId()).isEqualTo(42);
    assertThat(cmd.categoryId()).isEqualTo(7L);
    assertThat(cmd.slug()).isEqualTo("creme-brulee-gift-set");
    assertThat(cmd.sku()).isEqualTo("DJ-42");
    assertThat(cmd.brand()).isNull();
    assertThat(cmd.price()).isEqualByComparingTo(new BigDecimal("10.00"));
    assertThat(cmd.price().scale()).isEqualTo(2);
    assertThat(cmd.rating()).isEqualByComparingTo(new BigDecimal("5"));
    assertThat(cmd.stock()).isZero();
    assertThat(cmd.thumbnailUrl()).isEqualTo("our:products/src-42/thumbnail");
    assertThat(cmd.imageUrls()).hasSize(8);
    assertThat(cmd.imageUrls().get(0)).isEqualTo("our:products/src-42/0");
    assertThat(cmd.imageUrls().get(7)).isEqualTo("our:products/src-42/7");
    verify(images, never()).mirror(eq("i://8"), anyString());
  }

  @Test
  void keepsTheSourceSkuAndBrand() {
    page(0, 1, product(5));

    seeder.run();

    ArgumentCaptor<ProductImportCommand> captor =
        ArgumentCaptor.forClass(ProductImportCommand.class);
    verify(importService).importProduct(captor.capture());
    assertThat(captor.getValue().sku()).isEqualTo("SKU-5");
    assertThat(captor.getValue().brand()).isEqualTo("Brand");
    assertThat(captor.getValue().rating()).isEqualByComparingTo("3.00");
  }

  @Test
  void unknownCategoryIsCreatedWithTheSlugAsName() {
    DummyJsonProduct source =
        new DummyJsonProduct(1, "T", "d", "gadgets", 1.0, 1.0, 1, "b", "s", "t", List.of());
    page(0, 1, source);
    when(importService.upsertCategory("gadgets", "gadgets")).thenReturn(99L);

    seeder.run();

    ArgumentCaptor<ProductImportCommand> captor =
        ArgumentCaptor.forClass(ProductImportCommand.class);
    verify(importService).importProduct(captor.capture());
    assertThat(captor.getValue().categoryId()).isEqualTo(99L);
  }

  @Test
  void aFailingProductDoesNotStopTheRun() {
    page(0, 3, product(1), product(2));
    page(2, 3, product(3));
    when(importService.importProduct(any()))
        .thenAnswer(
            i -> {
              if (((ProductImportCommand) i.getArgument(0)).sourceId() == 2) {
                throw new IllegalStateException("boom");
              }
              return Optional.of(1L);
            });

    SeedReport report = seeder.run();

    assertThat(report.imported()).isEqualTo(2);
    assertThat(report.errors()).isEqualTo(1);
    assertThat(report.failed()).isFalse();
  }

  @Test
  void aProductImportedByAnotherWriterCountsAsSkipped() {
    page(0, 1, product(1));
    when(importService.importProduct(any())).thenReturn(Optional.empty());

    SeedReport report = seeder.run();

    assertThat(report.skipped()).isEqualTo(1);
    assertThat(report.imported()).isZero();
  }

  @Test
  void externalImageUrlsLeftInTheDatabaseFailTheRun() {
    page(0, 1, product(1));
    when(importService.countExternalImageUrls("dummyjson.com")).thenReturn(2L);

    assertThat(seeder.run().failed()).isTrue();
  }

  @Test
  void cleanRunDoesNotFail() {
    page(0, 1, product(1));

    SeedReport report = seeder.run();

    assertThat(report.failed()).isFalse();
    verify(importService, never()).upsertCategory(eq("gadgets"), anyString());
    verify(importService).upsertCategory("beauty", "Beauty");
  }

  @Test
  void slugifyHandlesEdgeCases() {
    assertThat(ProductSeeder.slugify("Hello,  World!")).isEqualTo("hello-world");
    assertThat(ProductSeeder.slugify("---")).isEmpty();
    assertThat(ProductSeeder.slugify("a".repeat(300))).hasSize(200);
  }
}
