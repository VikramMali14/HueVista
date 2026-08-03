package com.gridstore.huevista.newsletter.service;

import com.gridstore.huevista.auth.util.Emails;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.newsletter.model.NewsletterSubscriber;
import com.gridstore.huevista.newsletter.repository.NewsletterSubscriberRepository;
import com.gridstore.huevista.notification.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * The monthly-letter list: joining it, leaving it, and the one e-mail that confirms you
 * joined.
 *
 * <p>The journal form used to be a pure piece of theatre — it set "Thank you ✓" in React
 * state and threw the address away. Nothing was stored, nothing was sent, and there was
 * no list to send the letter to, so every person who signed up was quietly dropped.
 *
 * <p>Two rules shape what is here. Joining is <b>idempotent</b>: the same address twice is
 * one row and one welcome mail, so a double-click or a second visit never doubles anyone's
 * post. And leaving needs <b>no account</b> — the welcome mail carries a per-row token that
 * removes exactly that address, because the promise on the form is "cancel quietly, any
 * time" and a list you cannot leave without logging in does not keep it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsletterService {

    private final NewsletterSubscriberRepository subscriberRepository;
    private final EmailSender emailSender;
    /** The unsubscribe link opens the WEBSITE's page, not this API — see SiteUrls. */
    private final com.gridstore.huevista.common.web.SiteUrls siteUrls;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.mail.from:no-reply@huevista.org}")
    private String from;

    /**
     * Put {@code rawEmail} on the list and welcome it.
     *
     * Returns quietly whether the address was new, already subscribed, or coming back
     * after an unsubscribe: the caller answers "check your inbox" either way, so this
     * endpoint can't be used to find out who is on the list.
     */
    @Transactional
    public void subscribe(String rawEmail, String source) {
        String email = Emails.normalize(rawEmail);
        NewsletterSubscriber subscriber = subscriberRepository.findByEmail(email)
                .orElse(null);

        if (subscriber == null) {
            subscriber = NewsletterSubscriber.builder()
                    .email(email)
                    .source(source)
                    .status(NewsletterSubscriber.Status.SUBSCRIBED)
                    .unsubscribeToken(newToken())
                    .build();
            try {
                subscriber = subscriberRepository.saveAndFlush(subscriber);
            } catch (DataIntegrityViolationException raceLost) {
                // Two submits of the same address at once: the unique index picks a winner
                // and the loser adopts the row that won, so neither ends up creating a
                // second one or re-welcoming somebody.
                subscriber = subscriberRepository.findByEmail(email)
                        .orElseThrow(() -> raceLost);
            }
            log.info("Newsletter signup: id={} source={}", subscriber.getId(), source);
        } else if (!subscriber.isSubscribed()) {
            subscriber.setStatus(NewsletterSubscriber.Status.SUBSCRIBED);
            subscriber.setUnsubscribedAt(null);
            // A fresh token, so an unsubscribe link from the previous stint — sitting in an
            // old mail, or in whatever crawled it — cannot silently remove them again.
            subscriber.setUnsubscribeToken(newToken());
            // Re-welcome a returning reader: the last thing they were told by this list was
            // that they had left it.
            subscriber.setWelcomedAt(null);
            subscriberRepository.save(subscriber);
            log.info("Newsletter re-subscribe: id={}", subscriber.getId());
        }

        if (subscriber.getWelcomedAt() == null) {
            sendWelcome(subscriber);
        }
    }

    /**
     * Take an address off the list by its own token. Idempotent, and deliberately does not
     * delete the row: the token has to keep resolving so a second click on the same link
     * says "you're unsubscribed" rather than "unknown link".
     */
    @Transactional
    public void unsubscribe(String token) {
        NewsletterSubscriber subscriber = subscriberRepository.findByUnsubscribeToken(token)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "That unsubscribe link isn't valid. Write to us and we'll take you off by hand."));
        if (subscriber.isSubscribed()) {
            subscriber.setStatus(NewsletterSubscriber.Status.UNSUBSCRIBED);
            subscriber.setUnsubscribedAt(LocalDateTime.now());
            subscriberRepository.save(subscriber);
            log.info("Newsletter unsubscribe: id={}", subscriber.getId());
        }
    }

    /**
     * Confirm the signup. Best-effort, like every other transactional mail here: the
     * address is already on the list, and a mail outage must not roll that back and leave
     * a reader who pressed Subscribe on nothing at all.
     */
    private void sendWelcome(NewsletterSubscriber subscriber) {
        try {
            emailSender.send(from, subscriber.getEmail(),
                    "You're on the list — the HueVista monthly letter",
                    """
                    Hello,

                    You're subscribed to the HueVista monthly letter. On the first Sunday of
                    each month, one essay on colour, paint and the counter arrives here. One
                    letter, once a month — nothing else, and no tracking pixel.

                    Nothing else changes: this is the letter only, not a HueVista account, and
                    we never pass your address on.

                    Changed your mind? Leave any time, no login needed:
                    %s

                    — The HueVista team
                    """.formatted(unsubscribeUrl(subscriber)));
            subscriber.setWelcomedAt(LocalDateTime.now());
            subscriberRepository.save(subscriber);
        } catch (Exception e) {
            // Left unstamped on purpose: the next signup attempt from the same address
            // retries the welcome rather than treating a failed send as a delivered one.
            log.warn("Newsletter welcome mail failed (subscription kept): id={} error={}",
                    subscriber.getId(), e.getMessage());
        }
    }

    private String unsubscribeUrl(NewsletterSubscriber subscriber) {
        return siteUrls.on("/newsletter/unsubscribe?token=" + subscriber.getUnsubscribeToken());
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
