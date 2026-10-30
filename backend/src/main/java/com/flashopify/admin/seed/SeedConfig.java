package com.flashopify.admin.seed;

import com.flashopify.auth.service.UserSeedService;
import com.flashopify.common.storage.ObjectStorage;
import com.flashopify.product.service.ProductImportService;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wiring of the seed job; nothing here exists unless the {@code seed} profile is active. */
@Configuration(proxyBeanMethods = false)
@Profile("seed")
@EnableConfigurationProperties(SeedProperties.class)
public class SeedConfig {

  @Bean
  public DummyJsonClient dummyJsonClient(RestClient.Builder builder, SeedProperties props) {
    HttpClient http =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(props.image().timeout())
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(props.image().timeout());
    return new DummyJsonClient(builder.clone().requestFactory(factory), props);
  }

  @Bean
  public PlaceholderImage placeholderImage(ObjectStorage storage) {
    return new PlaceholderImage(storage);
  }

  @Bean
  public ImageMirror imageMirror(
      DummyJsonClient client,
      ObjectStorage storage,
      PlaceholderImage placeholder,
      SeedProperties props) {
    return new ImageMirror(client, storage, placeholder, props);
  }

  @Bean
  public UserSeeder userSeeder(UserSeedService userSeedService, SeedProperties props) {
    return new UserSeeder(userSeedService, props);
  }

  @Bean
  public ProductSeeder productSeeder(
      DummyJsonClient client,
      ImageMirror images,
      ProductImportService importService,
      SeedProperties props) {
    return new ProductSeeder(client, images, importService, props);
  }
}
