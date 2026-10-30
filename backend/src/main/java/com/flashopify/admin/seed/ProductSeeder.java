package com.flashopify.admin.seed;

import com.flashopify.admin.seed.dto.DummyJsonCategory;
import com.flashopify.admin.seed.dto.DummyJsonProduct;
import com.flashopify.admin.seed.dto.DummyJsonProductPage;
import com.flashopify.product.dto.ProductImportCommand;
import com.flashopify.product.service.ProductImportService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;

/**
 * Imports DummyJSON categories and products through {@link ProductImportService} only. Images are
 * copied to object storage before the product's database transaction starts, so no transaction is
 * held open during network I/O; keys are deterministic, so a retry after a failed import simply
 * overwrites the same objects.
 */
@Slf4j
@Order(10)
public class ProductSeeder implements SeedStep {

  static final String HOST = "dummyjson.com";
  static final int MAX_IMAGES = 8;
  private static final int MAX_SLUG = 200;
  private static final int MAX_SKU = 50;
  private static final BigDecimal MAX_RATING = BigDecimal.valueOf(5);
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
  private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

  private final DummyJsonClient client;
  private final ImageMirror images;
  private final ProductImportService importService;
  private final SeedProperties props;

  public ProductSeeder(
      DummyJsonClient client,
      ImageMirror images,
      ProductImportService importService,
      SeedProperties props) {
    this.client = client;
    this.images = images;
    this.importService = importService;
    this.props = props;
  }

  @Override
  public String name() {
    return "products";
  }

  @Override
  public boolean enabled() {
    return props.products().enabled();
  }

  @Override
  public SeedReport run() {
    long started = System.nanoTime();
    int mirroredBefore = images.mirroredCount();
    int placeholdersBefore = images.placeholderCount();

    Map<String, Long> categoryIds = new HashMap<>();
    for (DummyJsonCategory category : client.categories()) {
      categoryIds.put(
          category.slug(), importService.upsertCategory(category.slug(), category.name()));
    }

    int imported = 0;
    int skipped = 0;
    int errors = 0;
    int skip = 0;
    int total;
    do {
      DummyJsonProductPage page = client.products(skip, props.dummyjson().pageSize());
      total = page.total();
      if (page.products() == null || page.products().isEmpty()) {
        break;
      }
      for (DummyJsonProduct source : page.products()) {
        try {
          if (importService.existsBySourceId(source.id())) {
            skipped++;
          } else if (importOne(source, categoryIds)) {
            imported++;
          } else {
            skipped++;
          }
        } catch (RuntimeException e) {
          errors++;
          log.error("seed product failed sourceId={}", source.id(), e);
        }
      }
      skip += page.products().size();
    } while (skip < total);

    long external = importService.countExternalImageUrls(HOST);
    boolean failed = external > 0;
    if (failed) {
      log.error("seed left {} stored image URLs pointing at {}", external, HOST);
    }
    SeedReport report =
        new SeedReport(
            categoryIds.size(),
            imported,
            skipped,
            errors,
            images.mirroredCount() - mirroredBefore,
            images.placeholderCount() - placeholdersBefore,
            (System.nanoTime() - started) / 1_000_000,
            failed);
    log.info(
        "seed products done: categories={} imported={} skipped={} errors={} images={} "
            + "placeholders={} in {}ms",
        report.categories(),
        report.imported(),
        report.skipped(),
        report.errors(),
        report.images(),
        report.placeholders(),
        report.durationMs());
    return report;
  }

  /** True when created, false when another writer got there first. */
  private boolean importOne(DummyJsonProduct source, Map<String, Long> categoryIds) {
    long categoryId =
        categoryIds.computeIfAbsent(
            source.category(), slug -> importService.upsertCategory(slug, slug));
    String base = "products/src-" + source.id();
    String thumbnail = images.mirror(source.thumbnail(), base + "/thumbnail");
    List<String> gallery = new ArrayList<>();
    List<String> sources = source.images() == null ? List.of() : source.images();
    for (int i = 0; i < sources.size() && i < MAX_IMAGES; i++) {
      gallery.add(images.mirror(sources.get(i), base + "/" + i));
    }
    Optional<Long> id =
        importService.importProduct(toCommand(source, categoryId, thumbnail, gallery));
    return id.isPresent();
  }

  static ProductImportCommand toCommand(
      DummyJsonProduct source, long categoryId, String thumbnailUrl, List<String> imageUrls) {
    String name =
        source.title() == null || source.title().isBlank()
            ? "Product " + source.id()
            : source.title();
    String slug = slugify(name);
    if (slug.isEmpty()) {
      slug = "product-" + source.id();
    }
    String sku =
        source.sku() == null || source.sku().isBlank() ? "DJ-" + source.id() : source.sku();
    if (sku.length() > MAX_SKU) {
      sku = "DJ-" + source.id();
    }
    BigDecimal price =
        BigDecimal.valueOf(source.price() == null ? 0 : source.price())
            .setScale(2, RoundingMode.HALF_UP);
    BigDecimal rating =
        BigDecimal.valueOf(source.rating() == null ? 0 : source.rating())
            .setScale(2, RoundingMode.HALF_UP)
            .max(BigDecimal.ZERO)
            .min(MAX_RATING);
    String brand = source.brand() == null || source.brand().isBlank() ? null : source.brand();
    return new ProductImportCommand(
        source.id(),
        categoryId,
        sku,
        name,
        slug,
        source.description(),
        brand,
        price,
        rating,
        Math.max(source.stock() == null ? 0 : source.stock(), 0),
        thumbnailUrl,
        imageUrls);
  }

  /** Lowercase ASCII, runs of other characters become one '-', trimmed, at most 200 chars. */
  static String slugify(String text) {
    String ascii =
        DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
    String slug = NON_ALNUM.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");
    slug = trimDashes(slug);
    if (slug.length() > MAX_SLUG) {
      slug = trimDashes(slug.substring(0, MAX_SLUG));
    }
    return slug;
  }

  private static String trimDashes(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && s.charAt(start) == '-') {
      start++;
    }
    while (end > start && s.charAt(end - 1) == '-') {
      end--;
    }
    return s.substring(start, end);
  }
}
