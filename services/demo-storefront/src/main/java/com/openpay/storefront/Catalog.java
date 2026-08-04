package com.openpay.storefront;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What the shop sells, and — more importantly — what each thing costs.
 *
 * <p>Prices live here, on the server, and the browser is never asked what anything costs. A cart
 * arrives as product ids and quantities, and the amount charged is worked out from this list. The
 * alternative, taking a total from the page, is the oldest bug in online retail: the customer edits
 * one number in the request and buys a kettle for a rupee. It is worth doing correctly even in a
 * demo, because a payment platform that accepts a client-supplied amount without comment is making
 * a claim about its own seriousness.
 *
 * <p>Hardcoded rather than in a database, because this shop is a fixture. Its job is to give the
 * platform something plausible to take a payment for; a product admin screen would be a different
 * project.
 */
@Component
public class Catalog {

    /**
     * @param priceMinorUnits paise, like every amount on this platform. Integer minor units end to
     *     end — there is no float anywhere near money here, and a price list is not the place to
     *     start.
     */
    public record Product(String id, String name, String description, long priceMinorUnits) {
    }

    private static final List<Product> PRODUCTS = List.of(
            new Product("kettle", "Cast iron kettle", "1.2 L · stovetop", 240_00L),
            new Product("beans", "Single origin beans", "250 g · whole bean", 899_00L),
            new Product("grinder", "Hand grinder", "conical burr · 40 mm", 3_450_00L),
            new Product("cups", "Stoneware cups", "set of four · 200 ml", 1_180_00L),
            new Product("scale", "Brew scale", "0.1 g · built-in timer", 2_100_00L));

    private final Map<String, Product> byId = new LinkedHashMap<>();

    public Catalog() {
        for (Product product : PRODUCTS) {
            byId.put(product.id(), product);
        }
    }

    public List<Product> products() {
        return PRODUCTS;
    }

    /**
     * Totals a cart against this price list.
     *
     * @throws UnknownProductException if the cart names something this shop does not sell, rather
     *     than silently charging for the part it recognised
     */
    public long total(List<CartItem> items) {
        long total = 0;
        for (CartItem item : items) {
            Product product = byId.get(item.productId());
            if (product == null) {
                throw new UnknownProductException(item.productId());
            }
            // Quantity is bounded by the request validation, so this cannot overflow at any
            // quantity the API will accept.
            total += product.priceMinorUnits() * item.quantity();
        }
        return total;
    }

    public record CartItem(String productId, int quantity) {
    }

    public static class UnknownProductException extends RuntimeException {
        public UnknownProductException(String productId) {
            super("This shop does not sell '" + productId + "'");
        }
    }
}
