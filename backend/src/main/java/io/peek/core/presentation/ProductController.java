package io.peek.core.presentation;

import io.peek.core.products.ProductService;
import io.peek.core.products.ProductService.MappingInput;
import io.peek.core.products.ProductService.MappingView;
import io.peek.core.products.ProductService.ProductInput;
import io.peek.core.products.ProductService.ProductView;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
    private final ProductService products;
    public ProductController(ProductService products) { this.products = products; }

    @PostMapping("/api/v1/products")
    public ResponseEntity<ProductView> create(@RequestBody ProductInput input) {
        var result = products.create(input);
        URI location = URI.create("/api/v1/products/" + result.value().id());
        return result.created() ? ResponseEntity.created(location).body(result.value())
            : ResponseEntity.ok().location(location).body(result.value());
    }
    @GetMapping("/api/v1/products")
    public List<ProductView> list() { return products.list(); }
    @GetMapping("/api/v1/products/{id}")
    public ProductView get(@PathVariable UUID id) { return products.get(id); }
    @PutMapping("/api/v1/products/{id}")
    public ProductView update(@PathVariable UUID id, @RequestHeader("If-Match-Version") long version,
                              @RequestBody ProductInput input) { return products.update(id, input, version); }
    @PostMapping("/api/v1/products/{id}/mappings")
    public ResponseEntity<MappingView> map(@PathVariable UUID id, @RequestBody MappingInput input) {
        var result = products.addMapping(id, input);
        URI location = URI.create("/api/v1/product-mappings/" + result.value().id());
        return result.created() ? ResponseEntity.created(location).body(result.value())
            : ResponseEntity.ok().location(location).body(result.value());
    }
    @GetMapping("/api/v1/products/{id}/mappings")
    public List<MappingView> mappings(@PathVariable UUID id) { return products.mappings(id); }
    @PutMapping("/api/v1/product-mappings/{id}")
    public MappingView updateMapping(@PathVariable UUID id, @RequestHeader("If-Match-Version") long version,
                                     @RequestBody MappingInput input) { return products.updateMapping(id, input, version); }
    @GetMapping("/api/v1/product-mappings/resolve")
    public ProductView resolve(@RequestParam String channel, @RequestParam String externalId) {
        return products.get(products.resolve(channel, externalId).productId());
    }
}
