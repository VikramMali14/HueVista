package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.account.model.OrgMemberRole;
import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.paint.dto.CreateShopProductRequest;
import com.gridstore.huevista.paint.dto.ShopProductResponse;
import com.gridstore.huevista.paint.model.PaintLine;
import com.gridstore.huevista.paint.model.QualityTier;
import com.gridstore.huevista.paint.model.ShopProduct;
import com.gridstore.huevista.paint.repository.PaintLineRepository;
import com.gridstore.huevista.paint.repository.ShopProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** A retailer's own product listings (price/image/details) against catalogue lines. */
@Service
@RequiredArgsConstructor
public class ShopProductService {

    private final ShopProductRepository productRepository;
    private final PaintLineRepository lineRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMembershipRepository membershipRepository;

    @Transactional(readOnly = true)
    public List<ShopProductResponse> list(String userId, String orgId) {
        requireMember(userId, orgId);
        return productRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId)
                .stream().map(ShopProductResponse::from).toList();
    }

    @Transactional
    public ShopProductResponse create(String userId, String orgId, CreateShopProductRequest req) {
        requireOwnerOrManager(userId, orgId);
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
        PaintLine line = lineRepository.findById(req.getLineId())
                .orElseThrow(() -> new ResourceNotFoundException("Paint line not found: " + req.getLineId()));

        QualityTier tier = req.getQualityTier() != null ? req.getQualityTier() : line.getQualityTier();
        int brightness = req.getBrightness() != null ? req.getBrightness()
                : (tier != null ? tier.defaultBrightness() : 4);

        ShopProduct product = productRepository.save(ShopProduct.builder()
                .organization(org)
                .line(line)
                .price(req.getPrice())
                .priceUnit(req.getPriceUnit())
                .packSize(req.getPackSize())
                .coverage(req.getCoverage())
                .finish(req.getFinish() != null ? req.getFinish() : line.getDefaultFinish())
                .qualityTier(tier != null ? tier : QualityTier.PREMIUM)
                .brightness(brightness)
                .imageUrl(req.getImageUrl())
                .features(req.getFeatures())
                .description(req.getDescription())
                .build());
        return ShopProductResponse.from(product);
    }

    @Transactional
    public void delete(String userId, String orgId, String productId) {
        requireOwnerOrManager(userId, orgId);
        ShopProduct product = productRepository.findByIdAndOrganizationId(productId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        productRepository.delete(product);
    }

    private void requireMember(String userId, String orgId) {
        if (membershipRepository.findByUserIdAndOrganizationId(userId, orgId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You don't have access to this organization.");
        }
    }

    private void requireOwnerOrManager(String userId, String orgId) {
        boolean ok = membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.OWNER)
                || membershipRepository.existsByUserIdAndOrganizationIdAndRole(userId, orgId, OrgMemberRole.MANAGER);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the shop owner or a manager can manage products.");
        }
    }
}
