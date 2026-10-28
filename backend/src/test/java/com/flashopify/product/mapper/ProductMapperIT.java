package com.flashopify.product.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flashopify.product.domain.AdminProductSearchCriteria;
import com.flashopify.product.domain.AdminProductSort;
import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductSearchCriteria;
import com.flashopify.product.domain.ProductSort;
import com.flashopify.product.domain.ProductStatus;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.CatalogFixtures;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductMapperIT extends AbstractIntegrationTest {

  @Autowired ProductMapper productMapper;
  @Autowired CategoryMapper categoryMapper;
  @Autowired JdbcTemplate jdbc;

  private long cat;
  private String brandOne;
  private String brandTwo;
  private long a;
  private long b;
  private long c;
  private long d;
  private long inactive;
  private long deleted;

  @BeforeEach
  void seed() {
    CatalogFixtures f = new CatalogFixtures(jdbc);
    String sfx = CatalogFixtures.rnd();
    brandOne = "Aaa-" + sfx;
    brandTwo = "Bbb-" + sfx;
    LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
    cat = f.category("Cat " + sfx, null);
    a = f.product(cat, "Alpha", brandOne, "10.00", "4.50", "ACTIVE", t, null);
    b = f.product(cat, "Bravo", brandTwo, "20.00", "3.00", "ACTIVE", t.plusHours(1), null);
    c = f.product(cat, "Charlie", brandOne, "30.00", null, "ACTIVE", t.plusHours(2), null);
    d = f.product(cat, "Delta", null, "20.00", "4.50", "ACTIVE", t.plusHours(3), "100%_real stuff");
    inactive = f.product(cat, "Echo", "Zzz-" + sfx, "5.00", "5.00", "INACTIVE", t, null);
    deleted = f.product(cat, "Foxtrot", "Yyy-" + sfx, "15.00", "5.00", "DELETED", t, null);
  }

  private ProductSearchCriteria.ProductSearchCriteriaBuilder base() {
    return ProductSearchCriteria.builder()
        .categoryIds(List.of(cat))
        .sort(ProductSort.CREATED_AT_DESC)
        .offset(0)
        .limit(50);
  }

  private List<Long> ids(ProductSearchCriteria criteria) {
    return productMapper.searchActive(criteria).stream().map(Product::getId).toList();
  }

  @Test
  void onlyActiveProductsAreReturned() {
    assertThat(ids(base().build())).containsExactlyInAnyOrder(a, b, c, d);
    assertThat(productMapper.countActive(base().build())).isEqualTo(4);
  }

  @Test
  void eachSortProducesExpectedOrder() {
    assertThat(ids(base().sort(ProductSort.CREATED_AT_DESC).build())).containsExactly(d, c, b, a);
    assertThat(ids(base().sort(ProductSort.PRICE_ASC).build())).containsExactly(a, b, d, c);
    assertThat(ids(base().sort(ProductSort.PRICE_DESC).build())).containsExactly(c, b, d, a);
    assertThat(ids(base().sort(ProductSort.RATING_DESC).build())).containsExactly(a, d, b, c);
    assertThat(ids(base().sort(ProductSort.NAME_ASC).build())).containsExactly(a, b, c, d);
  }

  @Test
  void filtersWorkIndividually() {
    assertThat(ids(base().q("alpha").build())).containsExactly(a);
    assertThat(ids(base().q("100\\%\\_real").build())).containsExactly(d);
    assertThat(ids(base().q(brandTwo.toLowerCase()).build())).containsExactly(b);
    assertThat(ids(base().brands(List.of(brandOne)).sort(ProductSort.NAME_ASC).build()))
        .containsExactly(a, c);
    assertThat(ids(base().brands(List.of(brandOne, brandTwo)).build())).hasSize(3);
    assertThat(ids(base().minPrice(new BigDecimal("20")).sort(ProductSort.NAME_ASC).build()))
        .containsExactly(b, c, d);
    assertThat(ids(base().maxPrice(new BigDecimal("20")).sort(ProductSort.NAME_ASC).build()))
        .containsExactly(a, b, d);
    assertThat(ids(base().minRating(new BigDecimal("4")).sort(ProductSort.NAME_ASC).build()))
        .containsExactly(a, d);
  }

  @Test
  void filtersCombine() {
    ProductSearchCriteria criteria =
        base()
            .brands(List.of(brandOne, brandTwo))
            .minPrice(new BigDecimal("15"))
            .maxPrice(new BigDecimal("25"))
            .minRating(new BigDecimal("3"))
            .build();
    assertThat(ids(criteria)).containsExactly(b);
    assertThat(productMapper.countActive(criteria)).isEqualTo(1);
  }

  @Test
  void pagingHonoursOffsetAndLimit() {
    ProductSearchCriteria page2 = base().sort(ProductSort.NAME_ASC).offset(2).limit(2).build();
    assertThat(ids(page2)).containsExactly(c, d);
    assertThat(productMapper.countActive(page2)).isEqualTo(4);
  }

  @Test
  void brandsAreDistinctSortedAndActiveOnly() {
    List<String> brands = productMapper.findDistinctActiveBrands(List.of(cat));
    assertThat(brands).containsExactly(brandOne, brandTwo);
    assertThat(productMapper.findDistinctActiveBrands(null)).contains(brandOne, brandTwo);
  }

  @Test
  void findActiveBySlugJoinsCategoryAndHidesNonActive() {
    CatalogFixtures f = new CatalogFixtures(jdbc);
    Product p = productMapper.findActiveBySlug(f.slugOf(a));
    assertThat(p.getCategorySlug()).isEqualTo(f.categorySlug(cat));
    assertThat(p.getPrice()).isEqualByComparingTo("10.00");
    assertThat(productMapper.findActiveBySlug(f.slugOf(inactive))).isNull();
    assertThat(productMapper.findActiveBySlug(f.slugOf(deleted))).isNull();
    assertThat(productMapper.findActiveBySlug("missing-" + CatalogFixtures.rnd())).isNull();
  }

  @Test
  void categoryCountsOnlyIncludeActive() {
    assertThat(categoryMapper.countActiveProductsByCategory())
        .filteredOn(x -> x.getCategoryId() == cat)
        .singleElement()
        .satisfies(x -> assertThat(x.getProductCount()).isEqualTo(4));
  }

  // ---- FLS-009 admin side: any status ----

  private Product newProduct(String sku, String slug, String name, ProductStatus status) {
    return Product.builder()
        .categoryId(cat)
        .sku(sku)
        .name(name)
        .slug(slug)
        .brand(brandOne)
        .price(new BigDecimal("9.99"))
        .status(status)
        .build();
  }

  @Test
  void tc17InsertAssignsIdAndReadsBackAndRejectsDuplicateSku() {
    String sfx = CatalogFixtures.rnd();
    Product p = newProduct("SKU-INS-" + sfx, "ins-" + sfx, "Inserted", ProductStatus.ACTIVE);
    productMapper.insert(p);

    assertThat(p.getId()).isNotNull();
    Product back = productMapper.findById(p.getId());
    assertThat(back.getSku()).isEqualTo("SKU-INS-" + sfx);
    assertThat(back.getName()).isEqualTo("Inserted");
    assertThat(back.getCategorySlug()).isEqualTo(new CatalogFixtures(jdbc).categorySlug(cat));
    assertThat(back.getPrice()).isEqualByComparingTo("9.99");
    assertThat(back.getStatus()).isEqualTo(ProductStatus.ACTIVE);

    Product dup = newProduct("SKU-INS-" + sfx, "other-" + sfx, "Dup", ProductStatus.ACTIVE);
    assertThatThrownBy(() -> productMapper.insert(dup))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uk_products_sku");
  }

  @Test
  void tc17UpdateChangesEditableColumnsOnly() {
    Product before = productMapper.findById(a);
    Product changed =
        Product.builder()
            .id(a)
            .categoryId(cat)
            .sku(before.getSku())
            .name("Renamed Alpha")
            .slug(before.getSlug())
            .brand(brandOne)
            .price(new BigDecimal("11.00"))
            .status(ProductStatus.INACTIVE)
            .build();
    assertThat(productMapper.update(changed)).isEqualTo(1);

    Product after = productMapper.findById(a);
    assertThat(after.getName()).isEqualTo("Renamed Alpha");
    assertThat(after.getPrice()).isEqualByComparingTo("11.00");
    assertThat(after.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    assertThat(after.getSourceId()).isEqualTo(before.getSourceId());
    assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
  }

  @Test
  void tc17LockByIdReturnsAnyStatusWithoutCategoryJoin() {
    Product deletedRow = productMapper.lockById(deleted);
    assertThat(deletedRow).isNotNull();
    assertThat(deletedRow.getStatus()).isEqualTo(ProductStatus.DELETED);
    assertThat(deletedRow.getCategorySlug()).isNull();
    assertThat(productMapper.lockById(Long.MAX_VALUE)).isNull();
  }

  @Test
  void tc17SoftDeleteMarksRowDeletedAndHidesFromPublicReads() {
    String slug = new CatalogFixtures(jdbc).slugOf(b);
    assertThat(productMapper.softDelete(b)).isEqualTo(1);

    assertThat(productMapper.findById(b).getStatus()).isEqualTo(ProductStatus.DELETED);
    assertThat(productMapper.findActiveBySlug(slug)).isNull();
    assertThat(productMapper.lockById(b)).isNotNull();
  }

  @Test
  void tc17AdminSearchFiltersByCategoryStatusAndIncludesDeleted() {
    AdminProductSearchCriteria.AdminProductSearchCriteriaBuilder base =
        AdminProductSearchCriteria.builder()
            .categoryId(cat)
            .sort(AdminProductSort.NAME_ASC)
            .offset(0)
            .limit(50);

    List<Long> all = productMapper.adminSearch(base.build()).stream().map(Product::getId).toList();
    // Names: Alpha, Bravo, Charlie, Delta, Echo (inactive), Foxtrot (deleted).
    assertThat(all).containsExactly(a, b, c, d, inactive, deleted).as("all statuses, by name");
    assertThat(productMapper.adminCount(base.build())).isEqualTo(6);

    assertThat(
            productMapper.adminSearch(base.status(ProductStatus.DELETED).build()).stream()
                .map(Product::getId)
                .toList())
        .containsExactly(deleted);
    assertThat(productMapper.adminCount(base.status(ProductStatus.INACTIVE).build())).isEqualTo(1);
  }
}
