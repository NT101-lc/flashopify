package com.flashopify.product.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashopify.product.domain.Product;
import com.flashopify.product.domain.ProductSearchCriteria;
import com.flashopify.product.domain.ProductSort;
import com.flashopify.support.AbstractIntegrationTest;
import com.flashopify.support.CatalogFixtures;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
