package com.beauty.ecommerce.product.application.service;

import com.beauty.ecommerce.product.application.port.in.VisualSearchUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "visual-search", name = "index-on-startup", havingValue = "true")
public class VisualSearchIndexingRunner implements ApplicationRunner {

      private final VisualSearchUseCase visualSearchUseCase;

      @Override
      public void run(ApplicationArguments args) {
          try {
              log.info("visual-search.index-on-startup=true, starting product image vector indexing...");
              visualSearchUseCase.indexAllProducts();
          } catch (Exception e) {
              log.error("Failed to start Visual Search indexing on startup", e);
      }
  }
}
