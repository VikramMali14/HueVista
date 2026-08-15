package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.ai.util.DeltaEMatcher;
import com.gridstore.huevista.paint.dto.ShadeDecodeResponse;
import com.gridstore.huevista.paint.dto.ShadeResponse;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The counter's decoder: a customer's code in, the real colour out.
 *
 * This is the shop half of the HV code. A customer's screen, share link and colour board
 * all carry HV codes, which are row numbers and carry no information — no company, no
 * shade, nothing to reverse. That is deliberate: it means a board can be handed to
 * anyone and photographed anywhere without giving the colour away. The exchange is that
 * SOMEBODY has to be able to read it, and that somebody is any shop with a HueVista
 * account, through here.
 *
 * Whether a caller is allowed in is the controller's business ({@code @PreAuthorize}),
 * not this class's — but the rule it enforces is the feature: a shop that has not signed
 * up cannot decode, which is exactly why signing up is worth doing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadeDecodeService {

    private final ShadeRepository shadeRepository;

    /**
     * ΔE bands, in CIE76. The numbers are the usual perceptual landmarks: ~1 is the
     * threshold a trained eye can detect at all, ~2 is where a side-by-side comparison
     * starts to show it, and beyond ~5 nobody needs telling.
     *
     * These exist because "ΔE 3.1" means nothing to the person at the counter, and the
     * counter is the only audience this endpoint has.
     */
    private static final double EXACT_EPSILON = 0.05;

    @Transactional(readOnly = true)
    public ShadeDecodeResponse decode(String rawCode, String brandSlug) {
        String query = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        ShadeDecodeResponse.ShadeDecodeResponseBuilder out = ShadeDecodeResponse.builder().query(query);
        if (query.isEmpty()) return out.build();

        // An HV code first, because that is what a customer is actually holding. Only if
        // it is not one do we treat the input as a manufacturer's own code — the counter
        // types both into the same box, and asking them which kind it is would be asking
        // them the one thing the code is designed not to tell them.
        Optional<Shade> byHv = shadeRepository.findByHvCode(query);
        if (byHv.isPresent()) {
            Shade shade = byHv.get();
            return out.matchedBy("HV_CODE")
                    .shade(ShadeResponse.from(shade))
                    .brandMatch(matchInBrand(shade, brandSlug))
                    .build();
        }

        List<Shade> byCode = shadeRepository.findByShadeCodeIgnoreCase(query);
        if (byCode.isEmpty()) return out.build();
        if (byCode.size() > 1) {
            // Ambiguous on purpose rather than resolved by a guess: picking the first
            // company would quote a real shade from the wrong manufacturer, which reads
            // exactly like a correct answer and is the worst failure this can have.
            return out.matchedBy("SHADE_CODE")
                    .candidates(byCode.stream().map(ShadeResponse::from).toList())
                    .build();
        }
        Shade shade = byCode.get(0);
        return out.matchedBy("SHADE_CODE")
                .shade(ShadeResponse.from(shade))
                .brandMatch(matchInBrand(shade, brandSlug))
                .build();
    }

    /**
     * The nearest thing {@code brandSlug} makes to this colour — or the colour itself,
     * when that company happens to carry it.
     *
     * The decoded shade's own company is not special-cased away: a shop that stocks the
     * same company the room was designed against asks for it and gets {@code exact},
     * which is the answer that lets them sell straight off the shelf.
     */
    private ShadeDecodeResponse.BrandMatch matchInBrand(Shade source, String brandSlug) {
        if (brandSlug == null || brandSlug.isBlank()) return null;
        String slug = brandSlug.trim();
        String sourceHex = source.getHexCode();
        if (sourceHex == null) return null;

        // Same two-phase scan the colour matcher uses: an id+hex projection over the
        // company's range, then one fetch for the winner. A brand can carry thousands of
        // shades and none of the other columns are read here.
        List<ShadeRepository.ShadeHex> catalog = shadeRepository.findProjectedByBrandSlug(slug);
        record Scored(Long id, double deltaE) {}
        Optional<Scored> best = catalog.stream()
                .filter(s -> s.getHexCode() != null && s.getHexCode().matches("^#?[0-9a-fA-F]{6}$"))
                .map(s -> new Scored(s.getId(), DeltaEMatcher.computeDeltaE(sourceHex, s.getHexCode())))
                .min(Comparator.comparingDouble(Scored::deltaE));
        if (best.isEmpty()) return null;

        Shade winner = shadeRepository.findById(best.get().id()).orElse(null);
        if (winner == null) return null;

        double deltaE = Math.round(best.get().deltaE() * 100.0) / 100.0;
        boolean exact = deltaE <= EXACT_EPSILON;
        return ShadeDecodeResponse.BrandMatch.builder()
                .brandName(winner.getBrand().getName())
                .brandSlug(winner.getBrand().getSlug())
                .shade(ShadeResponse.from(winner))
                .exact(exact)
                // Report a true zero rather than a rounded-to-zero: "exact" and
                // "ΔE 0.04" side by side would look like the label was lying.
                .deltaE(exact ? 0.0 : deltaE)
                .closeness(describe(deltaE, exact))
                .build();
    }

    /** ΔE in the counter's words. */
    private static String describe(double deltaE, boolean exact) {
        if (exact) return "The same colour";
        if (deltaE < 1.0) return "Indistinguishable by eye";
        if (deltaE < 2.0) return "Very close — a difference only a side-by-side shows";
        if (deltaE < 3.5) return "Close — a small but visible difference";
        if (deltaE < 6.0) return "Noticeably different";
        return "The nearest this company makes, but clearly a different colour";
    }

    /**
     * HV codes for a set of manufacturer codes, keyed by {@code brandSlug + "/" + code}.
     *
     * Used where something already holds shade codes and needs the customer-facing
     * numbering for them — the colour-board PDF and the project read paths — without a
     * query per swatch.
     */
    @Transactional(readOnly = true)
    public Map<String, String> hvCodesFor(List<String> shadeCodes) {
        if (shadeCodes == null || shadeCodes.isEmpty()) return Map.of();
        return shadeCodes.stream()
                .map(code -> shadeRepository.findByShadeCodeIgnoreCase(code))
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toMap(
                        s -> s.getBrand().getSlug() + "/" + s.getShadeCode(),
                        Shade::getHvCode,
                        (a, b) -> a));
    }

    /**
     * The same lookup keyed by the manufacturer's code alone, upper-cased.
     *
     * For callers that hold a code and no brand — a recorded colour-board page keeps the
     * code the customer's sheet was printed from and nothing else, because that is all
     * re-rendering the combination ever needed. Two companies using the same code string
     * collapse onto one entry; that is a worse key than {@link #hvCodesFor}'s and it is
     * the only one available here, so the loser is a shade whose HV code belongs to its
     * namesake rather than a wrong shade being painted.
     */
    @Transactional(readOnly = true)
    public Map<String, String> hvCodesByShadeCode(List<String> shadeCodes) {
        if (shadeCodes == null || shadeCodes.isEmpty()) return Map.of();
        return shadeCodes.stream()
                .map(shadeRepository::findByShadeCodeIgnoreCase)
                .flatMap(List::stream)
                .filter(s -> s.getHvCode() != null)
                .collect(java.util.stream.Collectors.toMap(
                        s -> s.getShadeCode().toUpperCase(java.util.Locale.ROOT),
                        Shade::getHvCode,
                        (a, b) -> a));
    }
}
