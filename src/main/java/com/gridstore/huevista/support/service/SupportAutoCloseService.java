package com.gridstore.huevista.support.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Ends chats that have gone quiet, so a conversation doesn't linger open forever.
 * A chat with no new message for {@code app.support.idle-auto-close-hours} hours is
 * marked resolved on a fixed schedule; the customer's next message opens a fresh
 * thread. Set the property to 0 (or negative) to disable auto-close entirely.
 * Scheduling is enabled on the application.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SupportAutoCloseService {

    private final SupportService supportService;

    /** Hours of inactivity after which a live chat is auto-closed (0 disables). */
    @Value("${app.support.idle-auto-close-hours:24}")
    private long idleHours;

    @Scheduled(cron = "0 15 * * * *") // hourly, at :15 past the hour
    public void closeIdleConversations() {
        if (idleHours <= 0) return; // 0/negative = feature off
        int closed = supportService.autoCloseIdle(Duration.ofHours(idleHours));
        if (closed > 0) {
            log.info("Support auto-close swept {} idle conversation(s)", closed);
        }
    }
}
