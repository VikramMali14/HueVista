package com.gridstore.huevista.newsletter;

import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.common.web.SiteUrls;
import com.gridstore.huevista.newsletter.model.NewsletterSubscriber;
import com.gridstore.huevista.newsletter.repository.NewsletterSubscriberRepository;
import com.gridstore.huevista.newsletter.service.NewsletterService;
import com.gridstore.huevista.notification.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The monthly letter, which until now was a form that stored nothing and sent nothing.
 *
 * What matters here is that the promises the sign-up section makes are actually kept:
 * an address that presses Subscribe ends up on a real list AND gets told so by e-mail,
 * pressing it twice does not double anybody's post, and "cancel quietly, any time"
 * works without a login.
 */
class NewsletterServiceTest {

    private NewsletterSubscriberRepository subscribers;
    private EmailSender email;
    private NewsletterService svc;

    @BeforeEach
    void setUp() {
        subscribers = mock(NewsletterSubscriberRepository.class);
        email = mock(EmailSender.class);
        SiteUrls siteUrls = new SiteUrls();
        ReflectionTestUtils.setField(siteUrls, "webBaseUrl", "https://huevista.org");
        ReflectionTestUtils.setField(siteUrls, "corsAllowedOrigins", "");
        ReflectionTestUtils.setField(siteUrls, "apiBaseUrl", "http://localhost:8080");

        svc = new NewsletterService(subscribers, email, siteUrls);
        ReflectionTestUtils.setField(svc, "from", "no-reply@huevista.org");

        when(subscribers.saveAndFlush(any())).thenAnswer(inv -> {
            NewsletterSubscriber s = inv.getArgument(0);
            s.setId("sub-1");
            return s;
        });
    }

    private static NewsletterSubscriber existing(NewsletterSubscriber.Status status, boolean welcomed) {
        return NewsletterSubscriber.builder()
                .id("sub-1")
                .email("reader@example.com")
                .status(status)
                .unsubscribeToken("tok-old")
                .welcomedAt(welcomed ? java.time.LocalDateTime.now().minusDays(30) : null)
                .build();
    }

    // ── Joining ─────────────────────────────────────────────────────────────

    @Test
    void aNewAddressIsStoredAndWelcomed() {
        when(subscribers.findByEmail("reader@example.com")).thenReturn(Optional.empty());

        svc.subscribe("  Reader@Example.COM ", "journal");

        ArgumentCaptor<NewsletterSubscriber> saved = ArgumentCaptor.forClass(NewsletterSubscriber.class);
        verify(subscribers).saveAndFlush(saved.capture());
        // Normalised the same way every other address in the product is, so "Reader@…"
        // and "reader@…" can never become two subscriptions and two copies of the letter.
        assertThat(saved.getValue().getEmail()).isEqualTo("reader@example.com");
        assertThat(saved.getValue().getStatus()).isEqualTo(NewsletterSubscriber.Status.SUBSCRIBED);
        assertThat(saved.getValue().getUnsubscribeToken()).isNotBlank();

        verify(email).send(eq("no-reply@huevista.org"), eq("reader@example.com"), anyString(), anyString());
    }

    /** The welcome mail has to carry a way out, or "cancel quietly" is not on offer. */
    @Test
    void theWelcomeMailCarriesAWorkingUnsubscribeLink() {
        when(subscribers.findByEmail("reader@example.com")).thenReturn(Optional.empty());

        svc.subscribe("reader@example.com", "journal");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).send(anyString(), anyString(), anyString(), body.capture());
        ArgumentCaptor<NewsletterSubscriber> saved = ArgumentCaptor.forClass(NewsletterSubscriber.class);
        verify(subscribers).saveAndFlush(saved.capture());
        // The WEBSITE's page, not this API's — the reader has a browser, not a JSON client.
        assertThat(body.getValue()).contains(
                "https://huevista.org/newsletter/unsubscribe?token="
                        + saved.getValue().getUnsubscribeToken());
    }

    @Test
    void subscribingTwiceIsOneSubscriptionAndOneWelcome() {
        when(subscribers.findByEmail("reader@example.com"))
                .thenReturn(Optional.of(existing(NewsletterSubscriber.Status.SUBSCRIBED, true)));

        svc.subscribe("reader@example.com", "journal");

        verify(subscribers, never()).saveAndFlush(any());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * Coming back after leaving reuses the row (one address, one subscription, ever) but
     * gets a FRESH token — so an unsubscribe link from the previous stint, sitting in an
     * old mail or in whatever crawled it, cannot silently remove them a second time.
     */
    @Test
    void resubscribingReusesTheRowWithANewTokenAndReWelcomes() {
        NewsletterSubscriber gone = existing(NewsletterSubscriber.Status.UNSUBSCRIBED, true);
        gone.setUnsubscribedAt(java.time.LocalDateTime.now().minusDays(2));
        when(subscribers.findByEmail("reader@example.com")).thenReturn(Optional.of(gone));

        svc.subscribe("reader@example.com", "journal");

        assertThat(gone.getStatus()).isEqualTo(NewsletterSubscriber.Status.SUBSCRIBED);
        assertThat(gone.getUnsubscribedAt()).isNull();
        assertThat(gone.getUnsubscribeToken()).isNotEqualTo("tok-old");
        verify(subscribers, never()).saveAndFlush(any());
        verify(email).send(anyString(), eq("reader@example.com"), anyString(), anyString());
    }

    /**
     * A mail outage must not lose the subscription — but it must not be recorded as a
     * welcome either, or the reader is on a list they were never told about and the
     * retry never happens.
     */
    @Test
    void aFailedWelcomeKeepsTheSubscriptionAndLeavesItUnwelcomed() {
        when(subscribers.findByEmail("reader@example.com")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("smtp down"))
                .when(email).send(anyString(), anyString(), anyString(), anyString());

        svc.subscribe("reader@example.com", "journal");

        ArgumentCaptor<NewsletterSubscriber> saved = ArgumentCaptor.forClass(NewsletterSubscriber.class);
        verify(subscribers).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(NewsletterSubscriber.Status.SUBSCRIBED);
        assertThat(saved.getValue().getWelcomedAt()).isNull();
    }

    // ── Leaving ─────────────────────────────────────────────────────────────

    @Test
    void theTokenTakesThatAddressOffTheList() {
        NewsletterSubscriber on = existing(NewsletterSubscriber.Status.SUBSCRIBED, true);
        when(subscribers.findByUnsubscribeToken("tok-old")).thenReturn(Optional.of(on));

        svc.unsubscribe("tok-old");

        assertThat(on.getStatus()).isEqualTo(NewsletterSubscriber.Status.UNSUBSCRIBED);
        assertThat(on.getUnsubscribedAt()).isNotNull();
        verify(subscribers).save(on);
    }

    /** A second click on the same link says "you're off", not "unknown link". */
    @Test
    void unsubscribingTwiceIsHarmless() {
        NewsletterSubscriber off = existing(NewsletterSubscriber.Status.UNSUBSCRIBED, true);
        when(subscribers.findByUnsubscribeToken("tok-old")).thenReturn(Optional.of(off));

        svc.unsubscribe("tok-old");

        assertThat(off.getStatus()).isEqualTo(NewsletterSubscriber.Status.UNSUBSCRIBED);
        verify(subscribers, never()).save(any());
    }

    @Test
    void anUnknownTokenUnsubscribesNobody() {
        when(subscribers.findByUnsubscribeToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.unsubscribe("nope"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(subscribers, never()).save(any());
    }
}
