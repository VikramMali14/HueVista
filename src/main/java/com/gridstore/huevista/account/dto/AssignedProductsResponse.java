package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.paint.dto.ShopProductResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * The paint a customer may browse, grouped by the shop that unlocked it.
 *
 * <h2>Why this is a list of shops now</h2>
 *
 * It used to describe exactly one: a shop name, its brands, its products. That shape
 * came from an assumption that a customer belongs to a shop — and the assumption is
 * wrong in the ordinary case, not just in an edge case. Nothing stops somebody redeeming
 * a code from the shop near work and another from the shop near home, and both are real
 * unlocks that were separately paid for. With one slot to put them in, the second
 * redemption looked like it had REPLACED the first: the same page, a different shop's
 * name at the top, and the first shop's paint simply gone.
 *
 * <p>So every redeemed code contributes a section, and the customer sees the union.
 *
 * <h2>What this is NOT</h2>
 *
 * It is not a link between the customer and a retailer. Redeeming a code does not put
 * the customer inside that shop's billing, does not depend on that shop's subscription
 * staying live, and does not make the customer visible to the shop as one of "its"
 * customers. Products are the entire relationship: the shop said "these are the paints I
 * stock", and the customer can now see them. Everything else about their account — their
 * projects, their credits, their boards — is their own.
 */
@Data
@Builder
public class AssignedProductsResponse {

    /**
     * One section per shop whose code this customer has redeemed, newest redemption
     * first — so the shop they have just been to is the one at the top of the page.
     */
    private List<Shop> shops;

    /** One shop's unlocked paint. */
    @Data
    @Builder
    public static class Shop {
        /** Stable id, so the client can remember which sections a customer collapsed. */
        private String shopId;
        private String shopName;
        /** Whole companies unlocked. Empty means no company restriction (all brands). */
        private List<String> allowedBrands;
        /** Individually unlocked products, resolved to full listings. */
        private List<ShopProductResponse> products;
    }
}
